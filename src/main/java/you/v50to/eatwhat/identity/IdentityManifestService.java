package you.v50to.eatwhat.identity;

import you.v50to.eatwhat.config.SiteProperties;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit operator tool, never a Spring bean or startup task. Use a migration-owner connection
 * against a quiesced LEGACY database with a READ_COMMITTED operator connection.
 * Binding a baseline permanently freezes business writes;
 * this phase has no unfreeze operation. The caller owns and closes the connection. First restore
 * the same business snapshot at V15, then migrate and import its authoritative identity manifest;
 * inserting the snapshot after V16 generates different identities and must fail import.
 * This tool neither copies business data nor activates a site or resets a sequence.
 * Its manifest is trusted operator metadata, not an authenticated transport or business-content digest.
 */
public final class IdentityManifestService {
    public static final int MANIFEST_VERSION = 1;
    public static final int MAX_BATCH_SIZE = 1_000;
    public static final long HISTORICAL_MAX_ID = 1_000_000_000L;

    public record Baseline(UUID id, int version) {
        public Baseline {
            Objects.requireNonNull(id, "baseline id");
            if (version != MANIFEST_VERSION) {
                throw new IllegalArgumentException("Unsupported manifest version");
            }
        }
    }

    public record Entry(long numericId, UUID globalId, String originSite) {
        public Entry {
            if (numericId <= 0 || numericId > HISTORICAL_MAX_ID) {
                throw new IllegalArgumentException("Manifest ID is outside the historical range");
            }
            Objects.requireNonNull(globalId, "globalId");
            if (originSite != null && !originSite.matches("[a-z][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("Invalid origin site");
            }
        }
    }

    /** Only identity metadata; no username, credential, profile or business content is exported. */
    public record ManifestPage(Baseline baseline, EntityType type, long afterExclusive,
                               long highWatermark, List<Entry> entries, boolean complete) {
        public ManifestPage {
            Objects.requireNonNull(baseline, "baseline");
            independent(type);
            entries = List.copyOf(entries);
            if (entries.size() > MAX_BATCH_SIZE || afterExclusive < 0
                    || highWatermark < afterExclusive || highWatermark > HISTORICAL_MAX_ID) {
                throw new IllegalArgumentException("Invalid manifest page bounds");
            }
            long previous = afterExclusive;
            for (Entry entry : entries) {
                if (entry.numericId() <= previous || entry.numericId() > highWatermark) {
                    throw new IllegalArgumentException("Manifest IDs must be ordered within the page bounds");
                }
                previous = entry.numericId();
            }
            if (!complete && (entries.isEmpty() || previous == highWatermark)) {
                throw new IllegalArgumentException("Incomplete manifest page must advance the cursor");
            }
            if (complete && previous != highWatermark) {
                throw new IllegalArgumentException("Complete manifest page must reach its snapshot high watermark");
            }
        }

        public long nextCursor() {
            return complete ? highWatermark : entries.getLast().numericId();
        }
    }

    public record Progress(EntityType type, long lastNumericId, long highWatermark, boolean completed) {}

    private final Connection connection;

    public IdentityManifestService(Connection operatorConnection, SiteProperties.Mode configuredMode) {
        connection = Objects.requireNonNull(operatorConnection, "operatorConnection");
        if (configuredMode != SiteProperties.Mode.LEGACY) {
            throw new IllegalArgumentException("Historical identity work requires LEGACY mode");
        }
    }

    public void beginAuthoritative(Baseline baseline) throws SQLException {
        Objects.requireNonNull(baseline, "baseline");
        transaction(() -> {
            lockAllBusinessTables();
            if (bindBaseline(baseline, "AUTHORITATIVE")) {
                installPermanentWriteGuards();
                for (EntityType type : EntityType.independentTypes()) {
                    long high = businessHighWatermark(type);
                    execute("""
                            INSERT INTO eatwhat_identity.baseline_checkpoint(entity_type, high_watermark)
                            VALUES (?,?)
                            """, type.tableName(), high);
                }
            }
            return null;
        });
    }

    public void beginReplica(Baseline baseline) throws SQLException {
        Objects.requireNonNull(baseline, "baseline");
        transaction(() -> {
            lockAllBusinessTables();
            if (bindBaseline(baseline, "REPLICA")) {
                installPermanentWriteGuards();
            }
            return null;
        });
    }

    public Progress backfillBatch(EntityType type, int batchSize) throws SQLException {
        independent(type);
        bounded(batchSize);
        return transaction(() -> {
            requireBaseline("AUTHORITATIVE");
            lockBusinessTable(type);
            Progress progress = checkpoint(type);
            List<Long> ids = new ArrayList<>();
            try (var statement = prepare("SELECT id FROM public." + type.tableName()
                    + " WHERE id>? AND id<=? ORDER BY id LIMIT ?",
                    progress.lastNumericId(), progress.highWatermark(), batchSize);
                 var result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
            for (long id : ids) {
                execute("""
                        INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
                        VALUES (?,?,?,NULL) ON CONFLICT(entity_type,numeric_id) DO NOTHING
                        """, type.tableName(), id, UUID.randomUUID());
            }
            long cursor = ids.isEmpty() ? progress.lastNumericId() : ids.getLast();
            boolean completed = !hasBusinessAfter(type, cursor, progress.highWatermark());
            if (completed) {
                requireCoverage(type, progress.highWatermark());
                cursor = progress.highWatermark();
            }
            updateCheckpoint(type, cursor, completed);
            return new Progress(type, cursor, progress.highWatermark(), completed);
        });
    }

    public ManifestPage exportPage(EntityType type, long afterExclusive, int batchSize) throws SQLException {
        independent(type);
        bounded(batchSize);
        return transaction(() -> {
            Baseline baseline = requireBaseline("AUTHORITATIVE");
            lockBusinessTable(type);
            Progress progress = checkpoint(type);
            if (!progress.completed() || afterExclusive < 0 || afterExclusive > progress.highWatermark()) {
                throw new IllegalStateException("Complete the authoritative backfill before export");
            }
            requireCoverage(type, progress.highWatermark());
            List<Entry> entries = new ArrayList<>();
            try (var statement = prepare("""
                    SELECT i.numeric_id,i.global_id,i.origin_site
                    FROM eatwhat_identity.entity_identity i
                    JOIN public.%s b ON b.id=i.numeric_id
                    WHERE i.entity_type=? AND i.numeric_id>? AND i.numeric_id<=?
                    ORDER BY i.numeric_id LIMIT ?
                    """.formatted(type.tableName()), type.tableName(), afterExclusive,
                    progress.highWatermark(), batchSize); var result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(new Entry(result.getLong(1), result.getObject(2, UUID.class), result.getString(3)));
                }
            }
            long cursor = entries.isEmpty() ? afterExclusive : entries.getLast().numericId();
            return new ManifestPage(baseline, type, afterExclusive, progress.highWatermark(), entries,
                    !hasBusinessAfter(type, cursor, progress.highWatermark()));
        });
    }

    /** Ordered pages may be replayed. A conflicting row, UUID, origin or baseline aborts the whole batch. */
    public Progress importPage(ManifestPage page) throws SQLException {
        Objects.requireNonNull(page, "page");
        return transaction(() -> {
            if (!requireBaseline("REPLICA").equals(page.baseline())) {
                throw new IllegalStateException("Manifest baseline does not match this replica");
            }
            EntityType type = page.type();
            lockBusinessTable(type);
            execute("""
                    INSERT INTO eatwhat_identity.baseline_checkpoint(entity_type, high_watermark)
                    VALUES (?,?) ON CONFLICT(entity_type) DO NOTHING
                    """, type.tableName(), page.highWatermark());
            Progress progress = checkpoint(type);
            boolean replay = page.nextCursor() <= progress.lastNumericId();
            if (progress.highWatermark() != page.highWatermark()
                    || (!replay && (progress.completed() || progress.lastNumericId() != page.afterExclusive()))) {
                throw new IllegalStateException("Manifest cursor or snapshot boundary does not match");
            }
            long importedRows = scalarLong("SELECT imported_rows FROM eatwhat_identity.baseline_checkpoint WHERE entity_type=?",
                    type.tableName()) + (replay ? 0 : page.entries().size());
            for (Entry entry : page.entries()) {
                if (!exists("SELECT 1 FROM public." + type.tableName() + " WHERE id=?", entry.numericId())) {
                    throw new IllegalStateException("Manifest business row is absent");
                }
                execute("""
                        INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
                        VALUES (?,?,?,?) ON CONFLICT(entity_type,numeric_id) DO NOTHING
                        """, type.tableName(), entry.numericId(), entry.globalId(), entry.originSite());
                if (!exists("""
                        SELECT 1 FROM eatwhat_identity.entity_identity
                        WHERE entity_type=? AND numeric_id=? AND global_id=? AND origin_site IS NOT DISTINCT FROM ?
                        """, type.tableName(), entry.numericId(), entry.globalId(), entry.originSite())) {
                    throw new IllegalStateException("Existing identity conflicts with the manifest");
                }
            }
            if (page.complete()) {
                if (businessHighWatermark(type) > page.highWatermark()) {
                    throw new IllegalStateException("Replica business snapshot exceeds the manifest boundary");
                }
                requireCoverage(type, page.highWatermark());
                if (scalarLong("SELECT count(*) FROM public." + type.tableName()) != importedRows) {
                    throw new IllegalStateException("Replica business snapshot contains rows absent from the manifest");
                }
            }
            if (!replay || (page.complete() && !progress.completed())) {
                updateCheckpoint(type, page.nextCursor(), page.complete());
                execute("UPDATE eatwhat_identity.baseline_checkpoint SET imported_rows=? WHERE entity_type=?",
                        importedRows, type.tableName());
            }
            return checkpoint(type);
        });
    }

    public Progress progress(EntityType type) throws SQLException {
        independent(type);
        return transaction(() -> checkpoint(type));
    }

    private boolean bindBaseline(Baseline baseline, String role) throws SQLException {
        if (exists("SELECT 1 FROM eatwhat_identity.baseline")) {
            if (!requireBaseline(role).equals(baseline)) {
                throw new IllegalStateException("Database is already bound to another baseline");
            }
            return false;
        }
        execute("""
                INSERT INTO eatwhat_identity.baseline(singleton,baseline_id,manifest_version,baseline_role)
                VALUES (true,?,?,?)
                """, baseline.id(), baseline.version(), role);
        return true;
    }

    private Baseline requireBaseline(String role) throws SQLException {
        try (var statement = prepare("""
                SELECT baseline_id,manifest_version,baseline_role FROM eatwhat_identity.baseline
                WHERE singleton FOR UPDATE
                """); var result = statement.executeQuery()) {
            if (!result.next() || !role.equals(result.getString(3))) {
                throw new IllegalStateException("The required authoritative/replica baseline is not initialized");
            }
            return new Baseline(result.getObject(1, UUID.class), result.getInt(2));
        }
    }

    private Progress checkpoint(EntityType type) throws SQLException {
        try (var statement = prepare("""
                SELECT last_numeric_id,high_watermark,completed
                FROM eatwhat_identity.baseline_checkpoint WHERE entity_type=? FOR UPDATE
                """, type.tableName()); var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("No checkpoint for this entity type");
            }
            return new Progress(type, result.getLong(1), result.getLong(2), result.getBoolean(3));
        }
    }

    private void updateCheckpoint(EntityType type, long cursor, boolean complete) throws SQLException {
        execute("""
                UPDATE eatwhat_identity.baseline_checkpoint SET last_numeric_id=?,completed=? WHERE entity_type=?
                """, cursor, complete, type.tableName());
    }

    private long businessHighWatermark(EntityType type) throws SQLException {
        try (var statement = prepare("SELECT COALESCE(min(id),0), COALESCE(max(id),0), count(*) FROM public." + type.tableName());
             var result = statement.executeQuery()) {
            result.next();
            long min = result.getLong(1);
            long max = result.getLong(2);
            if ((result.getLong(3) > 0 && min <= 0) || max > HISTORICAL_MAX_ID) {
                throw new IllegalStateException("Business IDs are outside the historical allocation range");
            }
            return max;
        }
    }

    private boolean hasBusinessAfter(EntityType type, long cursor, long high) throws SQLException {
        return exists("SELECT 1 FROM public." + type.tableName() + " WHERE id>? AND id<=? LIMIT 1", cursor, high);
    }

    private void requireCoverage(EntityType type, long high) throws SQLException {
        if (exists("SELECT 1 FROM public." + type.tableName() + " b WHERE b.id<=? AND NOT EXISTS "
                + "(SELECT 1 FROM eatwhat_identity.entity_identity i WHERE i.entity_type=? AND i.numeric_id=b.id) LIMIT 1",
                high, type.tableName())) {
            throw new IllegalStateException("Business snapshot contains unmapped identities");
        }
    }

    private void lockBusinessTable(EntityType type) throws SQLException {
        execute("LOCK TABLE public." + type.tableName() + " IN SHARE MODE");
    }

    private void lockAllBusinessTables() throws SQLException {
        for (String table : frozenBusinessTables()) {
            execute("LOCK TABLE public." + table + " IN SHARE ROW EXCLUSIVE MODE");
        }
    }

    private void installPermanentWriteGuards() throws SQLException {
        for (String table : frozenBusinessTables()) {
            execute("CREATE TRIGGER identity_baseline_permanent_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE"
                    + " ON public." + table + " FOR EACH STATEMENT"
                    + " EXECUTE FUNCTION eatwhat_identity.reject_frozen_business_write()");
        }
    }

    private static List<String> frozenBusinessTables() {
        List<String> tables = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            tables.add(type.tableName());
        }
        tables.addAll(List.of("provinces", "cities"));
        return tables;
    }

    private <T> T transaction(SqlWork<T> work) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("Operator connection must not have an existing transaction");
        }
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("Operator connection must use READ_COMMITTED isolation");
        }
        connection.setAutoCommit(false);
        boolean transactionEnded = false;
        try {
            execute("SET LOCAL lock_timeout = '5s'");
            execute("SET LOCAL statement_timeout = '15s'");
            // Same lock order as site activation; prevents a site from activating midway through a batch.
            execute("LOCK TABLE eatwhat_identity.node_state IN SHARE ROW EXCLUSIVE MODE");
            if (exists("SELECT 1 FROM eatwhat_identity.node_state")) {
                throw new IllegalStateException("Historical identity work is forbidden after site activation");
            }
            T result = work.run();
            connection.commit();
            transactionEnded = true;
            return result;
        } catch (SQLException | RuntimeException error) {
            try {
                connection.rollback();
                transactionEnded = true;
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        } finally {
            // If rollback failed, changing auto-commit could commit an uncertain transaction.
            if (transactionEnded) {
                connection.setAutoCommit(true);
            }
        }
    }

    private PreparedStatement prepare(String sql, Object... arguments) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            return statement;
        } catch (SQLException error) {
            statement.close();
            throw error;
        }
    }

    private void execute(String sql, Object... arguments) throws SQLException {
        try (var statement = prepare(sql, arguments)) {
            statement.executeUpdate();
        }
    }

    private boolean exists(String sql, Object... arguments) throws SQLException {
        try (var statement = prepare(sql, arguments); ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private long scalarLong(String sql, Object... arguments) throws SQLException {
        try (var statement = prepare(sql, arguments); var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void independent(EntityType type) {
        Objects.requireNonNull(type, "type");
        if (type == EntityType.USER_INFO) {
            throw new IllegalArgumentException("user_info must use its users identity");
        }
    }

    private static void bounded(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Batch size must be between 1 and 1000");
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }
}
