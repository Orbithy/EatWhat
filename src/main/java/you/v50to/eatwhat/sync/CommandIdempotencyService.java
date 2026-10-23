package you.v50to.eatwhat.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Internal, same-database command boundary. Callers supply a stable principal identity and a
 * result containing durable business references; session credentials and external side effects
 * do not belong here. There is deliberately no HTTP binding or automatic business interception.
 */
public final class CommandIdempotencyService {
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final SiteProperties site;

    public CommandIdempotencyService(JdbcTemplate jdbc, SyncTransactions transactions, SiteProperties site) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.transactions.requireSameDataSource(jdbc);
        this.site = Objects.requireNonNull(site);
    }

    /** Joining a caller's transaction never commits that transaction on the caller's behalf. */
    public String execute(String principalKey, String commandType, String requestKey,
                          String requestJson, Supplier<String> callback) {
        return transactions.required(() -> {
            if (site.getMode() == null || site.getMode() == SiteProperties.Mode.LEGACY) {
                throw new IllegalStateException("Command idempotency requires an explicit site identity");
            }
            SyncEvent.requireName(site.getId(), "site id", 64);
            SyncEvent.requireUuid(site.getInstanceId(), "site instance id");
            SyncEvent.requireName(principalKey, "principal key", 128);
            SyncEvent.requireName(commandType, "command type", 128);
            SyncEvent.requireName(requestKey, "request key", 128);
            Objects.requireNonNull(callback, "Command callback");
            String requestHash = EventCodec.sha256(canonicalObject(requestJson));
            int inserted = jdbc.update("""
                    INSERT INTO eatwhat_sync.command_dedup
                        (site_id,principal_key,command_type,request_key,request_hash)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT (site_id,principal_key,command_type,request_key) DO NOTHING
                    """, site.getId(), principalKey, commandType, requestKey, requestHash);
            Command row = jdbc.queryForObject("""
                    SELECT request_hash,status,result_json::text AS result_json
                    FROM eatwhat_sync.command_dedup
                    WHERE site_id=? AND principal_key=? AND command_type=? AND request_key=?
                    FOR UPDATE
                    """, (rs, index) -> new Command(rs.getString("request_hash"),
                    rs.getString("status"), rs.getString("result_json")),
                    site.getId(), principalKey, commandType, requestKey);
            if (row == null || !requestHash.equals(row.requestHash())) {
                throw new IllegalStateException("Idempotency key was used with a different request");
            }
            if ("COMPLETED".equals(row.status())) {
                return canonicalObject(row.resultJson());
            }
            if (inserted != 1 || !"RUNNING".equals(row.status())) {
                throw new IllegalStateException("Idempotent command is already running or incomplete");
            }
            String result = canonicalObject(callback.get());
            int completed = jdbc.update("""
                    UPDATE eatwhat_sync.command_dedup
                    SET status='COMPLETED',result_json=?::jsonb,completed_at=clock_timestamp()
                    WHERE site_id=? AND principal_key=? AND command_type=? AND request_key=?
                      AND status='RUNNING' AND request_hash=?
                    """, result, site.getId(), principalKey, commandType, requestKey, requestHash);
            if (completed != 1) throw new IllegalStateException("Command completion lost its transaction ownership");
            return result;
        });
    }

    private static String canonicalObject(String json) {
        if (json == null || json.length() > EventCodec.MAX_PAYLOAD_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > EventCodec.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Command JSON exceeds its byte limit");
        }
        String canonical = EventCodec.canonicalJson(json);
        if (!canonical.startsWith("{") || canonical.getBytes(StandardCharsets.UTF_8).length > EventCodec.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Command request and result must be bounded JSON objects");
        }
        return canonical;
    }

    private record Command(String requestHash, String status, String resultJson) { }
}
