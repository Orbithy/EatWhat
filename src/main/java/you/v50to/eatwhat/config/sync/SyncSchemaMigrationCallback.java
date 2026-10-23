package you.v50to.eatwhat.config.sync;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import you.v50to.eatwhat.config.SiteProperties;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.LinkedHashMap;
import java.util.Map;

/** Grants only event operations needed by the runtime; it never changes identity allocation. */
public final class SyncSchemaMigrationCallback implements Callback {
    private static final Map<String, String> MUTABLE = mutableColumns();
    private final SiteProperties site;
    private final String runtimeUsername;

    public SyncSchemaMigrationCallback(SiteProperties site, String runtimeUsername) {
        this.site = site;
        this.runtimeUsername = runtimeUsername;
    }

    @Override public boolean supports(Event event, Context context) { return event == Event.AFTER_MIGRATE; }
    @Override public boolean canHandleInTransaction(Event event, Context context) { return true; }
    @Override public String getCallbackName() { return "eatwhat-sync-permissions"; }

    @Override
    public void handle(Event event, Context context) {
        try {
            grantRuntimePrivileges(context.getConnection());
        } catch (SQLException | IllegalStateException failure) {
            throw new FlywayException("Event schema runtime permissions could not be established", failure);
        }
    }

    public void grantRuntimePrivileges(Connection connection) throws SQLException {
        if (runtimeUsername == null || runtimeUsername.isBlank()) {
            if (site.getMode() != SiteProperties.Mode.LEGACY) {
                throw new IllegalStateException("Explicit site requires a configured runtime database role");
            }
            return;
        }
        boolean ownsTransaction = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (ownsTransaction) connection.setAutoCommit(false);
        else savepoint = connection.setSavepoint();
        try {
            execute(connection, "SET LOCAL lock_timeout='5s'");
            execute(connection, "SET LOCAL statement_timeout='30s'");
            boolean owner = privilegedOrOwner(connection);
            if (site.getMode() != SiteProperties.Mode.LEGACY && owner) {
                throw new IllegalStateException("Explicit-site runtime role must not control event schema objects");
            }
            // A LEGACY owner remains compatible. Owners cannot be isolated by revoking their own ACL.
            if (!owner) {
                String role = quote(runtimeUsername);
                execute(connection, "REVOKE ALL ON SCHEMA eatwhat_sync FROM " + role);
                execute(connection, "REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_sync FROM " + role);
                execute(connection, "REVOKE ALL ON ALL SEQUENCES IN SCHEMA eatwhat_sync FROM " + role);
                execute(connection, "REVOKE ALL ON ALL FUNCTIONS IN SCHEMA eatwhat_sync FROM " + role);
                execute(connection, "GRANT USAGE ON SCHEMA eatwhat_sync TO " + role);
                execute(connection, "GRANT SELECT,INSERT ON ALL TABLES IN SCHEMA eatwhat_sync TO " + role);
                if (syncTableExists(connection, "catalog_multiwriter_config")) {
                    execute(connection, "REVOKE INSERT,UPDATE,DELETE,TRUNCATE ON eatwhat_sync.catalog_multiwriter_config FROM " + role);
                    execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.catalog_command_actor(text,bigint) TO " + role);
                }
                if (syncTableExists(connection, "catalog_visibility_context")) {
                    execute(connection, "REVOKE INSERT,UPDATE,DELETE,TRUNCATE ON eatwhat_sync.catalog_visibility_context FROM " + role);
                    execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.refresh_catalog_visibility(text,bigint) TO " + role);
                }
                if (syncTableExists(connection, "notification_history")) {
                    execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.import_notification_history(jsonb) TO " + role);
                }
                if (syncTableExists(connection, "catalog_decision_projection_context")) {
                    execute(connection, "REVOKE ALL ON eatwhat_sync.catalog_decision_projection_context FROM " + role);
                    execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.apply_catalog_decision_projection(uuid) TO " + role);
                }
                if (syncTableExists(connection, "activity_intent")) {
                    execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.apply_activity_state_created(text,jsonb) TO " + role);
                }
                if (syncTableExists(connection, "historical_domain_source_claim")) {
                    execute(connection, "REVOKE INSERT,UPDATE,DELETE,TRUNCATE ON eatwhat_sync.historical_domain_source_claim FROM " + role);
                }
                if (syncTableExists(connection, "scoped_erasure_context")) {
                    execute(connection, "REVOKE ALL ON eatwhat_sync.scoped_erasure_context FROM " + role);
                }
                if (syncTableExists(connection, "catalog_erased_operation")) {
                    execute(connection, "REVOKE ALL ON eatwhat_sync.catalog_erased_operation FROM " + role);
                }
                if (syncTableExists(connection, "purged_personal_event")) {
                    execute(connection, "REVOKE INSERT,UPDATE,DELETE,TRUNCATE ON eatwhat_sync.purged_personal_event FROM " + role);
                    execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.personal_event_retention_blocked(jsonb) TO " + role);
                }
                execute(connection, "GRANT USAGE,SELECT ON SEQUENCE eatwhat_sync.outbox_local_order_seq TO " + role);
                execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.apply_activity_created(text,jsonb),"
                        + "eatwhat_sync.apply_activity_deleted(text,bigint,uuid,timestamptz) TO " + role);
                execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.next_relation_number(),"
                        + "eatwhat_sync.project_relation(text,bigint,bigint,bigint,boolean,bigint,uuid,text,text),"
                        + "eatwhat_sync.import_relation_notification(uuid,bigint,bigint,text,jsonb) TO " + role);
                execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.next_notification_read_number() TO " + role);
                execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_sync.next_catalog_number(),"
                        + "eatwhat_sync.apply_catalog_projection(text,uuid,bigint,text,uuid,uuid,text,jsonb),"
                        + "eatwhat_sync.clear_catalog_projection(text,bigint,uuid),"
                        + "eatwhat_sync.prepare_catalog_withdrawal_cleanup(uuid,bigint),"
                        + "eatwhat_sync.begin_catalog_business_write(text,bigint,bigint,bigint,bigint,text,boolean) TO " + role);
                // These tables are protected SECURITY DEFINER context, never caller-writable.
                // The broad event-table INSERT grant above must not expose the context protocol.
                execute(connection, "REVOKE INSERT,UPDATE,DELETE,TRUNCATE ON "
                        + "eatwhat_sync.catalog_business_write_context,eatwhat_sync.catalog_business_write_pending,"
                        + "eatwhat_sync.catalog_withdrawal_write_context FROM " + role);
                execute(connection, "REVOKE INSERT,UPDATE,DELETE,TRUNCATE ON eatwhat_sync.relation_projection,"
                        + "eatwhat_sync.relation_alias,eatwhat_sync.relation_notification,"
                        + "eatwhat_sync.relation_baseline_claim FROM " + role);
                execute(connection, "GRANT SELECT ON eatwhat_sync.relation_projection,"
                        + "eatwhat_sync.relation_alias,eatwhat_sync.relation_notification TO " + role);
                for (var table : MUTABLE.entrySet()) {
                    if (optionalPreferenceTableMissing(connection, table.getKey())) continue;
                    if (!table.getValue().isEmpty()) {
                        execute(connection, "GRANT UPDATE (" + table.getValue() + ") ON eatwhat_sync."
                                + table.getKey() + " TO " + role);
                    }
                }
                rejectBroaderPrivileges(connection);
            }
            if (ownsTransaction) connection.commit();
            else connection.releaseSavepoint(savepoint);
        } catch (SQLException | RuntimeException failure) {
            try {
                if (ownsTransaction) connection.rollback();
                else connection.rollback(savepoint);
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            if (ownsTransaction) connection.setAutoCommit(true);
        }
    }

    private boolean privilegedOrOwner(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM pg_roles WHERE pg_has_role(?::name,oid,'MEMBER') AND (rolsuper OR rolcreaterole))
                  OR EXISTS (SELECT 1 FROM (
                    SELECT nspowner AS owner FROM pg_namespace WHERE nspname='eatwhat_sync'
                    UNION SELECT c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='eatwhat_sync'
                    UNION SELECT p.proowner FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='eatwhat_sync'
                  ) protected_owner WHERE pg_has_role(?::name,owner,'MEMBER'))
                """)) {
            query.setString(1, runtimeUsername);
            query.setString(2, runtimeUsername);
            try (var row = query.executeQuery()) { row.next(); return row.getBoolean(1); }
        }
    }

    private void rejectBroaderPrivileges(Connection connection) throws SQLException {
        // Check switchable memberships too, including PostgreSQL 16 NOINHERIT grants.
        try (var query = connection.prepareStatement("""
                WITH reachable AS (SELECT oid FROM pg_roles WHERE pg_has_role(?::name,oid,'MEMBER'))
                SELECT EXISTS (SELECT 1 FROM reachable r CROSS JOIN pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='eatwhat_sync' AND c.relkind='r'
                      AND has_table_privilege(r.oid,c.oid,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
                  OR EXISTS (SELECT 1 FROM reachable r CROSS JOIN pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='eatwhat_sync' AND c.relname IN ('relation_projection','relation_alias','relation_notification',
                      'relation_baseline_claim')
                      AND has_table_privilege(r.oid,c.oid,'INSERT'))
                  OR EXISTS (SELECT 1 FROM reachable r CROSS JOIN pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='eatwhat_sync' AND c.relname IN ('catalog_business_write_context',
                      'catalog_business_write_pending','catalog_multiwriter_config','catalog_visibility_context',
                      'catalog_decision_projection_context','historical_domain_source_claim','scoped_erasure_context',
                      'purged_personal_event','catalog_erased_operation')
                      AND (has_table_privilege(r.oid,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE')
                        OR (c.relname IN ('catalog_decision_projection_context','scoped_erasure_context','catalog_erased_operation') AND has_table_privilege(r.oid,c.oid,'SELECT'))))
                  OR EXISTS (SELECT 1 FROM reachable r WHERE has_schema_privilege(r.oid,'eatwhat_sync','CREATE')
                    OR has_sequence_privilege(r.oid,'eatwhat_sync.outbox_local_order_seq','UPDATE')
                    OR has_parameter_privilege(r.oid,'session_replication_role','SET')
                    OR has_function_privilege(r.oid,
                      'eatwhat_sync.claim_relation_baseline(text,bigint,uuid,uuid,uuid,uuid,uuid,text)','EXECUTE'))
                """)) {
            query.setString(1, runtimeUsername);
            try (var row = query.executeQuery()) {
                row.next();
                if (row.getBoolean(1)) throw new IllegalStateException("Runtime retains broader event privileges through another role");
            }
        }
        for (String function : java.util.List.of("eatwhat_sync.catalog_decision_proven(uuid)",
                "eatwhat_sync.claim_historical_domain_source(text,bigint,uuid,text,text)",
                "eatwhat_sync.scoped_erasure_guard()", "eatwhat_sync.erase_scoped_rows(text,jsonb)",
                "eatwhat_sync.check_erased_catalog_identity()",
                "eatwhat_sync.prepare_catalog_withdrawal_cleanup_v1(uuid,bigint)",
                "eatwhat_sync.personal_event_subjects(jsonb)",
                "eatwhat_sync.erase_withdrawn_business_payload(uuid)")) {
            try (var query = connection.prepareStatement("""
                    SELECT EXISTS(SELECT 1 FROM pg_roles r JOIN pg_proc p ON p.oid=to_regprocedure(?)
                      WHERE pg_has_role(?::name,r.oid,'MEMBER') AND has_function_privilege(r.oid,p.oid,'EXECUTE'))
                    """)) {
                query.setString(1, function); query.setString(2, runtimeUsername);
                try (var row = query.executeQuery()) {
                    row.next();
                    if (row.getBoolean(1)) throw new IllegalStateException("Runtime retains private migration function privileges through another role");
                }
            }
        }
        for (var table : MUTABLE.entrySet()) {
            if (optionalPreferenceTableMissing(connection, table.getKey())) continue;
            try (var query = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM pg_roles r CROSS JOIN pg_attribute a
                      WHERE pg_has_role(?::name,r.oid,'MEMBER') AND a.attrelid=?::regclass
                        AND a.attnum>0 AND NOT a.attisdropped AND a.attname<>ALL(?::text[])
                        AND has_column_privilege(r.oid,a.attrelid,a.attnum,'UPDATE'))
                    """)) {
                query.setString(1, runtimeUsername);
                query.setString(2, "eatwhat_sync." + table.getKey());
                query.setArray(3, connection.createArrayOf("text", table.getValue().isEmpty()
                        ? new String[0] : table.getValue().split(",")));
                try (var row = query.executeQuery()) {
                    row.next();
                    if (row.getBoolean(1)) throw new IllegalStateException("Runtime can update immutable event columns");
                }
            }
        }
    }

    private static Map<String, String> mutableColumns() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("outbox", "");
        values.put("consent_policy", "revision,version,url,event_id");
        values.put("consent_policy_history", "");
        values.put("delivery", "status,attempts,available_at,lease_token,lease_until,acked_at,receipt_status,last_error");
        values.put("inbox", "status,applied_at,attempts,available_at,lease_token,lease_until,last_error");
        values.put("entity_state", "applied_vector,last_event_id,conflicted");
        values.put("pending", "dependencies,retry_at,resolved_at,reason");
        values.put("conflict", "");
        values.put("command_dedup", "status,result_json,completed_at");
        values.put("relation_state", "vector");
        values.put("relation_operation", "");
        values.put("relation_projection", "");
        values.put("relation_alias", "");
        values.put("relation_notification", "");
        values.put("relation_intent", "desired_present,intent_revision,published_revision,published_actor_epoch,published_owner_epoch,row_id,row_gid,row_origin,local_intent,blocked_reason,updated_at");
        values.put("relation_backfill_job", "status,phase,row_cursor,history_cursor,attempts,available_at,lease_token,lease_until,last_error,updated_at");
        values.put("relation_baseline_claim", "");
        values.put("relation_causal_state", "actor_epoch,owner_epoch,seen,add_witnesses,remove_frontier,legacy_loaded,blocked_reason,updated_at");
        values.put("relation_causal_fact", "");
        values.put("preference_state", "epoch,logical_clock,winner_id,winner_site,value_hash,observed_at,is_empty,local_dirty,published_epoch");
        values.put("profile_domain", "vector,active_epoch,published_epoch,revision,managed,local_dirty");
        values.put("profile_operation", "");
        values.put("profile_field_state", "value,applied_vector,head_id,head_set,local_dirty,conflict_revision");
        values.put("profile_conflict", "heads,retired_heads,status,resolved_head,revision,updated_at");
        values.put("profile_conflict_audit", "");
        values.put("privacy_domain", "vector,active_epoch,published_epoch,following_value,follower_value,following_vector,follower_vector,following_false_context,follower_false_context,revision,managed,local_dirty,updated_at");
        values.put("privacy_operation", "");
        values.put("notification_read_operation", "");
        values.put("notification_read_state", "vector");
        values.put("notification_read_batch", "status,lease_token,lease_until,cursor_id");
        values.put("notification_read_member", "recipient_epoch,actor_gid,actor_epoch,event_id,status,last_attempt_at");
        values.put("catalog_object", "authority_site,author_gid,parent_gid,revision,causal_state,tombstone,rejected,managed,updated_at");
        values.put("catalog_revision", "");
        values.put("catalog_natural_key", "revision,updated_at");
        values.put("catalog_candidate", "candidate_id,revision,content_hash,summary,status,updated_at");
        values.put("catalog_conflict", "revision,status,candidates,updated_at");
        values.put("catalog_conflict_audit", "");
        values.put("catalog_head_conflict", "revision,status,candidates,updated_at");
        values.put("catalog_head_conflict_audit", "");
        values.put("catalog_head_resolution_receipt", "");
        values.put("catalog_import_batch", "status,created_count,updated_count,result,completed_at");
        values.put("catalog_hub_source", "content_hash,updated_at");
        values.put("catalog_assignment", "");
        values.put("catalog_backfill_job", "cursor_kind,cursor_gid,status,attempts,available_at,lease_token,lease_until,last_error,updated_at");
        values.put("catalog_backfill_publication", "consent_epoch,object_revision,published_heads,consent_epochs,bridge_event_id,published_at");
        values.put("catalog_managed_domain", "");
        values.put("catalog_multiwriter_config", "");
        values.put("catalog_projection_state", "status,decision_heads,updated_at");
        values.put("catalog_actor_evidence", "");
        values.put("catalog_authorization_review", "");
        values.put("catalog_decision_operation", "");
        values.put("catalog_decision_state", "heads,seen,status,winner_operation_id,updated_at");
        values.put("catalog_import_claim", "");
        values.put("catalog_visibility_context", "");
        values.put("catalog_decision_projection_context", "");
        values.put("profile_authorization", "");
        values.put("profile_authorization_review", "");
        values.put("profile_authorization_review_decision", "");
        values.put("notification_history", "published_actor_epoch,published_recipient_epoch,attempted_at");
        values.put("notification_history_read", "published_actor_epoch,published_recipient_epoch,attempted_at,read_at");
        values.put("notification_history_issue", "");
        values.put("activity_intent", "deleted_at,revision,published_revision,published_epoch,attempted_at");
        values.put("historical_domain_source_claim", "");
        values.put("scoped_erasure_context", "");
        values.put("purged_personal_event", "");
        values.put("catalog_erased_operation", "");
        values.put("catalog_decision_archive", "");
        values.put("catalog_decision_authorization_review", "");
        values.put("activity_source_proof", "");
        values.put("notification_source_proof", "");
        return values;
    }

    /** Old migration targets must not require tables introduced by later additive migrations. */
    private static boolean optionalPreferenceTableMissing(Connection connection, String table) throws SQLException {
        if (!java.util.Set.of("consent_policy", "consent_policy_history", "preference_state", "catalog_multiwriter_config", "catalog_projection_state",
                "catalog_actor_evidence", "activity_intent", "catalog_authorization_review", "catalog_decision_operation",
                "catalog_decision_state", "catalog_import_claim", "catalog_visibility_context", "catalog_decision_projection_context", "profile_authorization",
                "profile_authorization_review", "profile_authorization_review_decision", "historical_domain_source_claim",
                "scoped_erasure_context", "purged_personal_event", "catalog_erased_operation", "catalog_decision_archive", "catalog_decision_authorization_review", "activity_source_proof", "notification_source_proof",
                "notification_history", "notification_history_read", "notification_history_issue").contains(table)) return false;
        return !syncTableExists(connection, table);
    }

    private static boolean syncTableExists(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            query.setString(1, "eatwhat_sync." + table);
            try (var row = query.executeQuery()) { row.next(); return row.getBoolean(1); }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
}
