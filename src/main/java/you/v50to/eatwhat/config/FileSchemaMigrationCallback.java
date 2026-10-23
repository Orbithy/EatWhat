package you.v50to.eatwhat.config;

import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.sql.Connection;
import java.sql.Statement;

/** Grants the runtime role only the row operations needed by the file lifecycle service. */
public final class FileSchemaMigrationCallback extends BaseCallback {
    private final String runtimeUsername;

    public FileSchemaMigrationCallback(String runtimeUsername) { this.runtimeUsername = runtimeUsername; }

    @Override public boolean supports(Event event, Context context) { return event == Event.AFTER_MIGRATE; }

    @Override public void handle(Event event, Context context) {
        if (runtimeUsername == null || runtimeUsername.isBlank()) return;
        String role = '"' + runtimeUsername.replace("\"", "\"\"") + '"';
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("GRANT USAGE ON SCHEMA eatwhat_file TO " + role);
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.upload_intent TO " + role);
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_orphan_candidate TO " + role);
            statement.execute("GRANT SELECT,INSERT ON eatwhat_file.file_object,eatwhat_file.file_revision,eatwhat_file.file_key_alias,eatwhat_file.file_reference TO " + role);
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_replica,eatwhat_file.file_transfer_job TO " + role);
            statement.execute("GRANT SELECT ON eatwhat_file.file_object,eatwhat_file.file_revision,eatwhat_file.file_replica,eatwhat_file.file_key_alias,eatwhat_file.upload_intent TO " + role);
            // V28: Grant permissions for replication tables
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_publication TO " + role);
            statement.execute("GRANT SELECT,INSERT ON eatwhat_file.file_replica_alert TO " + role);
            // V29: Grant permissions for reference release event tables and view
            statement.execute("GRANT SELECT,INSERT ON eatwhat_file.file_reference_event TO " + role);
            statement.execute("GRANT USAGE,SELECT ON SEQUENCE eatwhat_file.file_reference_event_event_id_seq TO " + role);
            statement.execute("GRANT SELECT ON eatwhat_file.file_reference_live TO " + role);
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_tombstone TO " + role);
            // V30: Grant permissions for report-only inventory runs and findings
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_gc_run TO " + role);
            statement.execute("GRANT SELECT,INSERT ON eatwhat_file.file_inventory_finding TO " + role);
            statement.execute("GRANT USAGE,SELECT ON SEQUENCE eatwhat_file.file_inventory_finding_finding_id_seq TO " + role);
            // V31: GC candidates (state machine; uuid primary key, so no sequence). No DELETE: rows are retained.
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_gc_candidate TO " + role);
            // V32: CDN invalidation queue. The tombstone trigger (running as the invoking role) inserts tasks and the
            // worker claims/updates them. uuid key: no sequence. Rows are never deleted.
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_cdn_invalidation TO " + role);
            statement.execute("GRANT SELECT,INSERT ON eatwhat_file.file_sync_ack TO " + role);
            statement.execute("GRANT SELECT ON eatwhat_file.file_profile_avatar_keys TO " + role);
            // V34: retained UUID-only file channel state. No DELETE/TRUNCATE privileges.
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_sync_dirty,eatwhat_file.file_sync_peer_state,eatwhat_file.file_sync_receipt TO " + role);
            statement.execute("GRANT SELECT,INSERT ON eatwhat_file.file_sync_origin TO " + role);
            statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_file.file_gc_coordination TO " + role);
            boolean clusterSchema;
            try(var rows=statement.executeQuery("SELECT to_regclass('eatwhat_cluster.gc_fence') IS NOT NULL")){rows.next();clusterSchema=rows.getBoolean(1);}
            if(clusterSchema){statement.execute("GRANT USAGE ON SCHEMA eatwhat_cluster TO "+role);statement.execute("GRANT SELECT ON eatwhat_cluster.local_state TO "+role);statement.execute("GRANT SELECT,UPDATE ON eatwhat_cluster.gc_fence TO "+role);}
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to grant file lifecycle privileges", e);
        }
    }
}
