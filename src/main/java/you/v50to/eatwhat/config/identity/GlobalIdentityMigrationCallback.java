package you.v50to.eatwhat.config.identity;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import you.v50to.eatwhat.config.SiteProperties;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Uses Flyway's migration connection; runtime credentials never alter sequences or binding state. */
public final class GlobalIdentityMigrationCallback implements Callback {
    public static final long RESERVED_ID_MAX = 1_000_000_000L;
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    public static final long CLUSTER_ID_FLOOR = 4_000_000_000_000L;
    public static final List<String> ENTITY_TYPES = List.of(
            "users", "contacts", "verifications", "activity_foods", "activity_dinners",
            "activity_likes", "notifications", "follow", "privacy", "hub", "restaurant",
            "foods", "browse_history", "food_tags", "food_taggings", "food_favorites", "likes",
            "activities", "activity_posts");

    private final SiteProperties site;
    private final String runtimeUsername;
    private long allocationMinimum,allocationMaximum;
    private int allocationIncrement=2;

    public GlobalIdentityMigrationCallback(SiteProperties site, String runtimeUsername) {
        this.site = site;
        this.runtimeUsername = runtimeUsername;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public String getCallbackName() {
        return "eatwhat-global-identity";
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            activateOrVerify(context.getConnection());
        } catch (SQLException | IllegalStateException failure) {
            throw new FlywayException("Global identity activation or verification failed: " + failure.getMessage(), failure);
        }
    }

    /** Public for isolated upgrade/recovery rehearsals using a dedicated migration connection. */
    public void activateOrVerify(Connection connection) throws SQLException {
        boolean ownTransaction = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (ownTransaction) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint();
        }
        try {
            execute(connection, "SET LOCAL lock_timeout = '5s'");
            execute(connection, "SET LOCAL statement_timeout = '30s'");
            execute(connection, "SELECT pg_advisory_xact_lock(1161909335, 3)");
            execute(connection, "LOCK TABLE eatwhat_identity.node_state IN SHARE ROW EXCLUSIVE MODE");
            NodeState state = readState(connection);
            configureAllocation(connection,state);
            if (state != null) {
                requireMatchingBinding(site, state);
                Fingerprint fingerprint = fingerprint(connection);
                if (!state.databaseName().equals(fingerprint.databaseName())
                        || state.databaseOid() != fingerprint.databaseOid()
                        || !state.clusterIdentifier().equals(fingerprint.clusterIdentifier())) {
                    throw new IllegalStateException("Database fingerprint differs from the bound installation; controlled recovery is required");
                }
                for (String entity : presentEntityTypes(connection)) {
                    adoptNewEntity(connection, entity, sequence(connection, entity));
                    verifySequence(connection, entity);
                }
            } else if (site.getMode() != SiteProperties.Mode.LEGACY) {
                requireExplicitSite();
                if (scalarBoolean(connection, "SELECT to_regclass('eatwhat_identity.baseline') IS NOT NULL")
                        && scalarBoolean(connection, "SELECT EXISTS (SELECT 1 FROM eatwhat_identity.baseline)")) {
                    throw new IllegalStateException("A historical baseline remains frozen; explicit-site bootstrap requires a later controlled procedure");
                }
                // Same lock order as the historical manifest operator; serialize first activation.
                for (String entity : presentEntityTypes(connection)) {
                    execute(connection, "LOCK TABLE public." + quote(entity) + " IN SHARE ROW EXCLUSIVE MODE");
                }
                for (String entity : presentEntityTypes(connection)) {
                    if (scalarBoolean(connection, "SELECT EXISTS (SELECT 1 FROM public." + quote(entity) + ")")) {
                        throw new IllegalStateException("First explicit-site activation requires empty business tables; historical bootstrap is not automatic");
                    }
                }
                if (scalarBoolean(connection, "SELECT EXISTS (SELECT 1 FROM eatwhat_identity.entity_identity WHERE numeric_id > "
                        + RESERVED_ID_MAX + ")")) {
                    throw new IllegalStateException("Existing identity reservations overlap the new allocation domain");
                }
                long start = firstId();
                long maximum = maximumId();
                for (String entity : presentEntityTypes(connection)) {
                    Sequence sequence = sequence(connection, entity);
                    requireSerialDefault(connection, entity, sequence);
                    execute(connection, "ALTER SEQUENCE " + sequence.name()
                            + " INCREMENT BY " + allocationIncrement + " MINVALUE " + start + " MAXVALUE " + maximum
                            + " START WITH " + start + " RESTART WITH " + start + " CACHE 1 NO CYCLE");
                }
                Fingerprint fingerprint = fingerprint(connection);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO eatwhat_identity.node_state
                        (singleton,site_id,mode,instance_id,database_name,database_oid,cluster_identifier)
                        VALUES (true,?,?,?,?,?,?)
                        """)) {
                    insert.setString(1, site.getId());
                    insert.setString(2, site.getMode().name());
                    insert.setObject(3, site.getInstanceId());
                    insert.setString(4, fingerprint.databaseName());
                    insert.setLong(5, fingerprint.databaseOid());
                    insert.setString(6, fingerprint.clusterIdentifier());
                    insert.executeUpdate();
                }
            }
            persistAllocation(connection);
            grantRuntimeReadAccess(connection);
            if (ownTransaction) {
                connection.commit();
            } else {
                connection.releaseSavepoint(savepoint);
            }
        } catch (SQLException | RuntimeException failure) {
            try {
                if (ownTransaction) {
                    connection.rollback();
                } else {
                    connection.rollback(savepoint);
                }
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            if (ownTransaction) {
                connection.setAutoCommit(true);
            }
        }
    }

    private void requireExplicitSite() {
        if (site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY
                || site.getId() == null || site.getId().isBlank()
                || site.getInstanceId() == null || site.getInstanceId().equals(new UUID(0, 0))) {
            throw new IllegalStateException("Explicit identity activation requires app.site.mode, app.site.id and app.site.instance-id");
        }
    }

    static void requireMatchingBinding(SiteProperties site, NodeState state) {
        if (site.getMode() == null || !state.mode().equals(site.getMode().name())
                || !Objects.equals(state.siteId(), site.getId())
                || !Objects.equals(state.instanceId(), site.getInstanceId())) {
            throw new IllegalStateException("Site identity does not match the bound database; LEGACY downgrade and clone reassignment are forbidden");
        }
    }

    static NodeState readState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT site_id,mode,instance_id,database_name,database_oid,cluster_identifier
                     FROM eatwhat_identity.node_state WHERE singleton
                     """)) {
            if (!rows.next()) {
                return null;
            }
            return new NodeState(rows.getString(1), rows.getString(2), rows.getObject(3, UUID.class),
                    rows.getString(4), rows.getLong(5), rows.getString(6));
        }
    }

    private Fingerprint fingerprint(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT current_database(), oid::bigint,
                            (SELECT system_identifier::text FROM pg_control_system())
                     FROM pg_database WHERE datname=current_database()
                     """)) {
            if (!row.next()) {
                throw new IllegalStateException("Cannot determine database fingerprint");
            }
            return new Fingerprint(row.getString(1), row.getLong(2), row.getString(3));
        }
    }

    private void verifySequence(Connection connection, String entity) throws SQLException {
        Sequence sequence = sequence(connection, entity);
        requireSerialDefault(connection, entity, sequence);
        if (sequence.increment() != allocationIncrement || sequence.minimum() != firstId()
                || sequence.maximum() != maximumId() || sequence.start() != firstId()
                || sequence.cache() != 1 || sequence.cycle()) {
            throw new IllegalStateException("Allocation sequence configuration drift: " + entity);
        }
        try (PreparedStatement missing = connection.prepareStatement("""
                SELECT EXISTS (
                  SELECT 1 FROM public.%s business
                  LEFT JOIN eatwhat_identity.entity_identity identity
                    ON identity.entity_type=? AND identity.numeric_id=business.id
                  WHERE identity.numeric_id IS NULL)
                """.formatted(quote(entity)))) {
            missing.setString(1, entity);
            try (ResultSet row = missing.executeQuery()) {
                row.next();
                if (row.getBoolean(1)) {
                    throw new IllegalStateException("Business rows lack identity reservations: " + entity);
                }
            }
        }
        // Read the committed identity high-water mark before the sequence value: concurrent normal
        // inserts can only advance nextval before their mapping commits, so this ordering is safe.
        long highestReserved;
        try (PreparedStatement high = connection.prepareStatement("""
                SELECT coalesce(max(numeric_id),0) FROM eatwhat_identity.entity_identity
                WHERE entity_type=? AND numeric_id>=? AND numeric_id<=? AND mod(numeric_id-?,?)=0
                """)) {
            high.setString(1, entity);
            high.setLong(2,firstId());high.setLong(3,maximumId());high.setLong(4,firstId());high.setInt(5,allocationIncrement);
            try (ResultSet row = high.executeQuery()) {
                row.next();
                highestReserved = row.getLong(1);
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT last_value,is_called FROM " + sequence.name())) {
            row.next();
            long last = row.getLong(1);
            long next = row.getBoolean(2) ? last + allocationIncrement : last;
            if (last < firstId() || last > maximumId() || Math.floorMod(last-firstId(),allocationIncrement)!=0
                    || next <= highestReserved || next > maximumId()) {
                throw new IllegalStateException("Allocation sequence was rewound, exhausted or moved outside its domain: " + entity);
            }
        }
    }

    private Sequence sequence(Connection connection, String entity) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT n.nspname,c.relname,s.seqincrement,s.seqmin,s.seqmax,s.seqstart,s.seqcache,s.seqcycle,s.seqtypid
                FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE s.seqrelid=pg_get_serial_sequence(?, 'id')::regclass
                """)) {
            query.setString(1, "public." + entity);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getLong(9) != 20) {
                    throw new IllegalStateException("Expected an owned bigint sequence: " + entity);
                }
                return new Sequence(quote(row.getString(1)) + "." + quote(row.getString(2)),
                        row.getLong(3), row.getLong(4), row.getLong(5), row.getLong(6),
                        row.getLong(7), row.getBoolean(8));
            }
        }
    }

    private void requireSerialDefault(Connection connection, String entity, Sequence sequence) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT a.atttypid=20 AND pg_get_expr(d.adbin,d.adrelid)=format('nextval(%L::regclass)',?::regclass::text)
                FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                WHERE a.attrelid=?::regclass AND a.attname='id'
                """)) {
            query.setString(1, sequence.name());
            query.setString(2, "public." + entity);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || !row.getBoolean(1)) {
                    throw new IllegalStateException("Numeric ID no longer uses its owned serial default: " + entity);
                }
            }
        }
    }

    private void grantRuntimeReadAccess(Connection connection) throws SQLException {
        if (runtimeUsername == null || runtimeUsername.isBlank()) {
            if (site.getMode() != SiteProperties.Mode.LEGACY) {
                throw new IllegalStateException("Explicit identity activation requires spring.datasource.username");
            }
            return;
        }
        String role = quote(runtimeUsername);
        if (site.getMode() != SiteProperties.Mode.LEGACY) {
            requireRestrictedRuntimeRole(connection);
        }
        execute(connection, "GRANT USAGE ON SCHEMA eatwhat_identity TO " + role);
        boolean ownsMetadata;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT pg_get_userbyid(relowner)=? FROM pg_class
                WHERE oid='eatwhat_identity.entity_identity'::regclass
                """)) {
            query.setString(1, runtimeUsername);
            try (ResultSet row = query.executeQuery()) {
                row.next();
                ownsMetadata = row.getBoolean(1);
            }
        }
        // An owner cannot be isolated from its own objects. Revoking the function owner's own
        // INSERT would break SECURITY DEFINER triggers for non-superuser legacy deployments.
        if (!ownsMetadata) {
            execute(connection, "REVOKE INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER ON "
                    + "eatwhat_identity.entity_identity,eatwhat_identity.node_state FROM " + role);
        }
        execute(connection, "GRANT SELECT ON eatwhat_identity.entity_identity,eatwhat_identity.node_state TO " + role);
        if(scalarBoolean(connection,"SELECT to_regclass('eatwhat_identity.allocation') IS NOT NULL"))execute(connection,"GRANT SELECT ON eatwhat_identity.allocation TO "+role);
        if(scalarBoolean(connection,"SELECT to_regclass('eatwhat_cluster.local_state') IS NOT NULL"))execute(connection,"GRANT USAGE ON SCHEMA eatwhat_cluster TO "+role);
        if(scalarBoolean(connection,"SELECT to_regclass('eatwhat_cluster.local_state') IS NOT NULL"))execute(connection,"GRANT SELECT ON eatwhat_cluster.local_state,eatwhat_cluster.gc_fence TO "+role);
        if(scalarBoolean(connection,"SELECT to_regprocedure('eatwhat_cluster.writes_allowed()') IS NOT NULL"))execute(connection,"GRANT EXECUTE ON FUNCTION eatwhat_cluster.writes_allowed() TO "+role);
        if (site.getMode() != SiteProperties.Mode.LEGACY) {
            requireNoInheritedWritePrivileges(connection);
        }
    }

    /** Read-only admission for a shared controller which must not run migration callbacks. */
    public void verifyRuntimeRole(Connection connection) throws SQLException {
        requireExplicitSite();
        if(runtimeUsername==null||runtimeUsername.isBlank())throw new IllegalStateException("Runtime database role required");
        requireRestrictedRuntimeRole(connection);
        requireNoInheritedWritePrivileges(connection);
    }

    private void requireRestrictedRuntimeRole(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname=?")) {
            query.setString(1, runtimeUsername);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Configured runtime database role does not exist", "42704");
                }
            }
        }
        // MEMBER deliberately includes NOINHERIT memberships: SET ROLE must not bypass the guard.
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM pg_roles
                  WHERE pg_has_role(?::name,oid,'MEMBER') AND (rolsuper OR rolcreaterole))
                OR EXISTS (
                  SELECT 1 FROM (
                    SELECT datdba AS owner FROM pg_database WHERE datname=current_database()
                    UNION SELECT nspowner FROM pg_namespace WHERE nspname='eatwhat_identity'
                    UNION SELECT c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                      WHERE n.nspname='eatwhat_identity'
                         OR (n.nspname='public' AND c.relname=ANY(?))
                         OR c.oid IN (SELECT pg_get_serial_sequence(format('public.%I',entity),'id')::regclass
                                      FROM unnest(?::text[]) AS entity)
                    UNION SELECT p.proowner FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                      WHERE n.nspname='eatwhat_identity'
                  ) protected_owner WHERE pg_has_role(?::name,owner,'MEMBER'))
                """)) {
            query.setString(1, runtimeUsername);
            query.setArray(2, connection.createArrayOf("text", protectedBusinessTables(connection)));
            query.setArray(3, connection.createArrayOf("text", protectedBusinessTables(connection)));
            query.setString(4, runtimeUsername);
            try (ResultSet row = query.executeQuery()) {
                row.next();
                if (row.getBoolean(1)) {
                    throw new IllegalStateException("Explicit-site runtime role must be separate from privileged and protected-object owner roles");
                }
            }
        }
    }

    private void requireNoInheritedWritePrivileges(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                WITH reachable_roles AS (
                  SELECT oid FROM pg_roles WHERE pg_has_role(?::name,oid,'MEMBER')
                )
                SELECT EXISTS (
                  SELECT 1 FROM reachable_roles r CROSS JOIN pg_class c
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                  WHERE n.nspname='eatwhat_identity' AND c.relkind IN ('r','p')
                    AND has_table_privilege(r.oid,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
                OR EXISTS (
                  SELECT 1 FROM reachable_roles r CROSS JOIN unnest(?::text[]) AS entity
                  WHERE has_sequence_privilege(r.oid,pg_get_serial_sequence(format('public.%I',entity),'id'),'UPDATE'))
                OR EXISTS (
                  SELECT 1 FROM reachable_roles r
                  WHERE has_schema_privilege(r.oid,'eatwhat_identity','CREATE')
                     OR has_parameter_privilege(r.oid,'session_replication_role','SET'))
                """)) {
            query.setString(1, runtimeUsername);
            query.setArray(2, connection.createArrayOf("text", protectedBusinessTables(connection)));
            try (ResultSet row = query.executeQuery()) {
                row.next();
                if (row.getBoolean(1)) {
                    throw new IllegalStateException("Runtime role retains inherited or switchable identity-write, sequence-update or trigger-bypass privileges");
                }
            }
        }
    }

    /**
     * Registered business tables that exist at the current schema version. A callback can run for an older migration
     * target (an upgrade rehearsal), before a later migration has created a newly registered table.
     */
    private static List<String> presentEntityTypes(Connection connection) throws SQLException {
        List<String> present = new java.util.ArrayList<>();
        for (String entity : ENTITY_TYPES) {
            if (scalarBoolean(connection, "SELECT to_regclass('public." + quote(entity) + "') IS NOT NULL")) present.add(entity);
        }
        return present;
    }

    private static String[] protectedBusinessTables(Connection connection) throws SQLException {
        return java.util.stream.Stream.concat(presentEntityTypes(connection).stream(), java.util.stream.Stream.of("user_info", "provinces", "cities"))
                .toArray(String[]::new);
    }

    private long firstId() {
        return allocationMinimum>0?allocationMinimum:RESERVED_ID_MAX + (site.getMode() == SiteProperties.Mode.CAMPUS ? 1 : 2);
    }

    private long maximumId() {
        return allocationMaximum>0?allocationMaximum:MAX_SAFE_INTEGER - (site.getMode() == SiteProperties.Mode.CAMPUS ? 0 : 1);
    }

    private void configureAllocation(Connection connection,NodeState state)throws SQLException {
        allocationMinimum=0;allocationMaximum=0;allocationIncrement=2;
        boolean schema=scalarBoolean(connection,"SELECT to_regclass('eatwhat_identity.allocation') IS NOT NULL");
        if(!schema){if(site.getClusterId()!=null||site.getAllocationSlot()!=null)throw new IllegalStateException("Cluster allocation requires V36");return;}
        boolean saved=scalarBoolean(connection,"SELECT EXISTS(SELECT 1 FROM eatwhat_identity.allocation)");
        if(site.getClusterId()==null){if(saved||site.getAllocationSlot()!=null)throw new IllegalStateException("Cluster allocation cannot be disabled or partially configured");return;}
        Integer slot=site.getAllocationSlot();if(slot==null||slot<0||slot>1023||site.getMode()==SiteProperties.Mode.LEGACY
                ||slot==0&&site.getMode()!=SiteProperties.Mode.CAMPUS||slot==1&&site.getMode()!=SiteProperties.Mode.EXTERNAL)throw new IllegalStateException("Invalid immutable cluster allocation slot");
        allocationIncrement=slot<2?2:1024;
        allocationMinimum=slot<2?RESERVED_ID_MAX+(slot==0?1:2):CLUSTER_ID_FLOOR+slot;
        allocationMaximum=slot<2?CLUSTER_ID_FLOOR-(slot==0?1:2):MAX_SAFE_INTEGER-Math.floorMod(MAX_SAFE_INTEGER-allocationMinimum,1024);
        if(saved){verifyAllocationBinding(connection,site);return;}
        if(state==null)return;
        requireMatchingBinding(site,state);
        // One-time controlled conversion: never rewind a legacy sequence or let new slots retain parity-domain rows.
        for(String entity:presentEntityTypes(connection)){
            execute(connection,"LOCK TABLE public."+quote(entity)+" IN SHARE ROW EXCLUSIVE MODE");
            if(slot>=2&&scalarBoolean(connection,"SELECT EXISTS(SELECT 1 FROM public."+quote(entity)+")"))throw new IllegalStateException("New allocation slot requires an empty installation");
            Sequence sequence=sequence(connection,entity);requireSerialDefault(connection,entity,sequence);
            if(adoptNewEntity(connection,entity,sequence))continue;
            if(sequence.increment()!=2||sequence.maximum()!=MAX_SAFE_INTEGER-(site.getMode()==SiteProperties.Mode.CAMPUS?0:1))throw new IllegalStateException("Legacy allocator drift before cluster conversion");
            try(var query=connection.createStatement();var row=query.executeQuery("SELECT last_value FROM "+sequence.name())){row.next();if(row.getLong(1)>=CLUSTER_ID_FLOOR)throw new IllegalStateException("Legacy allocation overlaps multi-site domain");}
            execute(connection,"ALTER SEQUENCE "+sequence.name()+" INCREMENT BY "+allocationIncrement+" MINVALUE "+allocationMinimum+" MAXVALUE "+allocationMaximum+" START WITH "+allocationMinimum+(slot>=2?" RESTART WITH "+allocationMinimum:"")+" CACHE 1 NO CYCLE");
        }
    }
    /**
     * A business table added by a later migration still has its default serial sequence on an already bound site.
     * It is moved into this site's allocation domain once, and only while it is still empty.
     */
    private boolean adoptNewEntity(Connection connection, String entity, Sequence sequence) throws SQLException {
        if (sequence.increment() != 1 || sequence.minimum() != 1 || sequence.start() != 1) return false;
        requireSerialDefault(connection, entity, sequence);
        execute(connection, "LOCK TABLE public." + quote(entity) + " IN SHARE ROW EXCLUSIVE MODE");
        if (scalarBoolean(connection, "SELECT EXISTS (SELECT 1 FROM public." + quote(entity) + ")")) {
            throw new IllegalStateException("A newly registered business table already has rows outside the allocation domain: " + entity);
        }
        execute(connection, "ALTER SEQUENCE " + sequence.name() + " INCREMENT BY " + allocationIncrement
                + " MINVALUE " + firstId() + " MAXVALUE " + maximumId() + " START WITH " + firstId()
                + " RESTART WITH " + firstId() + " CACHE 1 NO CYCLE");
        return true;
    }
    private void persistAllocation(Connection connection)throws SQLException {
        if(site.getClusterId()==null)return;
        try(var insert=connection.prepareStatement("INSERT INTO eatwhat_identity.allocation(singleton,cluster_id,site_id,instance_id,slot,minimum_id,maximum_id,increment_by) VALUES(true,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")){
            insert.setObject(1,site.getClusterId());insert.setString(2,site.getId());insert.setObject(3,site.getInstanceId());insert.setInt(4,site.getAllocationSlot());insert.setLong(5,firstId());insert.setLong(6,maximumId());insert.setInt(7,allocationIncrement);insert.executeUpdate();
        }
        verifyAllocationBinding(connection,site);
    }
    public static void verifyAllocationBinding(Connection connection,SiteProperties site)throws SQLException {
        if(!scalarBoolean(connection,"SELECT to_regclass('eatwhat_identity.allocation') IS NOT NULL")){if(site.getClusterId()!=null)throw new IllegalStateException("Cluster allocation schema missing");return;}
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT cluster_id,site_id,instance_id,slot FROM eatwhat_identity.allocation WHERE singleton")){
            if(!rows.next()){if(site.getClusterId()!=null)throw new IllegalStateException("Cluster allocation binding missing");return;}
            if(!Objects.equals(site.getClusterId(),rows.getObject(1,UUID.class))||!Objects.equals(site.getId(),rows.getString(2))||!Objects.equals(site.getInstanceId(),rows.getObject(3,UUID.class))||!Objects.equals(site.getAllocationSlot(),rows.getInt(4)))throw new IllegalStateException("Cluster allocation identity mismatch");
        }
    }

    private static boolean scalarBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getBoolean(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    record NodeState(String siteId, String mode, UUID instanceId, String databaseName,
                     long databaseOid, String clusterIdentifier) { }
    private record Fingerprint(String databaseName, long databaseOid, String clusterIdentifier) { }
    private record Sequence(String name, long increment, long minimum, long maximum,
                            long start, long cache, boolean cycle) { }
}
