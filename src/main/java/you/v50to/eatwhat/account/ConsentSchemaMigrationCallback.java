package you.v50to.eatwhat.account;

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

/** Grants explicit consent operations, never access to the owner-only import context. */
public final class ConsentSchemaMigrationCallback implements Callback {
    private static final Map<String, String> MUTABLE = mutableColumns();
    private final SiteProperties site;
    private final String runtimeUsername;
    private final boolean multiwriter;

    public ConsentSchemaMigrationCallback(SiteProperties site, String runtimeUsername) {
        this(site,runtimeUsername,false);
    }
    public ConsentSchemaMigrationCallback(SiteProperties site, String runtimeUsername,boolean multiwriter) {
        this.site = site;
        this.runtimeUsername = runtimeUsername;
        this.multiwriter=multiwriter;
    }

    @Override public boolean supports(Event event, Context context) { return event == Event.AFTER_MIGRATE; }
    @Override public boolean canHandleInTransaction(Event event, Context context) { return true; }
    @Override public String getCallbackName() { return "eatwhat-consent-permissions"; }

    @Override
    public void handle(Event event, Context context) {
        try {
            grantRuntimePrivileges(context.getConnection());
        } catch (SQLException | IllegalStateException failure) {
            throw new FlywayException("Consent schema runtime permissions could not be established", failure);
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
                throw new IllegalStateException("Explicit-site runtime role must not control consent schema objects");
            }
            // A LEGACY owner remains compatible. Owners cannot be isolated by revoking their own ACL.
            if (!owner) {
                String role = quote(runtimeUsername);
                execute(connection, "REVOKE ALL ON SCHEMA eatwhat_consent FROM " + role);
                execute(connection, "REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_consent FROM " + role);
                execute(connection, "REVOKE ALL ON ALL SEQUENCES IN SCHEMA eatwhat_consent FROM " + role);
                execute(connection, "REVOKE ALL ON ALL FUNCTIONS IN SCHEMA eatwhat_consent FROM " + role);
                execute(connection, "GRANT USAGE ON SCHEMA eatwhat_consent TO " + role);
                for (String table : MUTABLE.keySet()) {
                    execute(connection, "GRANT SELECT,INSERT ON eatwhat_consent." + table + " TO " + role);
                }
                execute(connection, "GRANT EXECUTE ON FUNCTION eatwhat_consent.import_account(uuid,bigint,jsonb),"
                        + "eatwhat_consent.purge_account(uuid,bigint) TO " + role);
                for (var table : MUTABLE.entrySet()) {
                    if (!table.getValue().isEmpty()) {
                        execute(connection, "GRANT UPDATE (" + table.getValue() + ") ON eatwhat_consent."
                                + table.getKey() + " TO " + role);
                    }
                }
                rejectBroaderPrivileges(connection);
                try (var probe=connection.createStatement();var rows=probe.executeQuery("SELECT to_regclass('eatwhat_account_multiwriter.configuration') IS NOT NULL")) {
                    rows.next(); if(rows.getBoolean(1)) {
                        if(multiwriter)execute(connection,"UPDATE eatwhat_account_multiwriter.configuration SET enabled=true WHERE singleton");
                        execute(connection,"GRANT USAGE ON SCHEMA eatwhat_account_multiwriter TO "+role);
                        execute(connection,"GRANT SELECT ON ALL TABLES IN SCHEMA eatwhat_account_multiwriter TO "+role);
                        execute(connection,"REVOKE ALL ON eatwhat_account_multiwriter.command_context FROM "+role);
                        execute(connection,"GRANT INSERT ON eatwhat_account_multiwriter.operation,eatwhat_account_multiwriter.delivery TO "+role);
                        execute(connection,"GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_account_multiwriter TO "+role);
                        execute(connection,"GRANT INSERT,UPDATE,DELETE ON eatwhat_account_multiwriter.alias_claim,eatwhat_account_multiwriter.conflict TO "+role);
                        execute(connection,"GRANT INSERT,UPDATE ON eatwhat_account_multiwriter.session TO "+role);
                        execute(connection,"GRANT EXECUTE ON FUNCTION eatwhat_account_multiwriter.heads(uuid,text),eatwhat_account_multiwriter.begin_command(bigint,bigint),eatwhat_account_multiwriter.initialize(bigint) TO "+role);
                        try(var w=connection.createStatement();var present=w.executeQuery("SELECT to_regclass('eatwhat_account_multiwriter.authorization_witness') IS NOT NULL")) {
                            present.next();if(present.getBoolean(1)) {
                                execute(connection,"GRANT SELECT,INSERT ON eatwhat_account_multiwriter.authorization_witness TO "+role);
                                execute(connection,"GRANT SELECT,INSERT,UPDATE(state) ON eatwhat_account_multiwriter.authorization_review TO "+role);execute(connection,"GRANT SELECT,INSERT ON eatwhat_account_multiwriter.review_decision TO "+role);
                                execute(connection,"GRANT EXECUTE ON FUNCTION eatwhat_account_multiwriter.begin_command(bigint,bigint,uuid) TO "+role);
                                execute(connection,"GRANT EXECUTE ON FUNCTION eatwhat_account_multiwriter.project_field(bigint,text,text),eatwhat_account_multiwriter.end_command(),eatwhat_account_multiwriter.append_local(bigint,text,text) TO "+role);
                            }
                        }
                        try(var w=connection.createStatement();var present=w.executeQuery("SELECT to_regclass('eatwhat_account_multiwriter.signing_key') IS NOT NULL")) {
                            present.next();if(present.getBoolean(1)){execute(connection,"GRANT SELECT,INSERT ON eatwhat_account_multiwriter.signing_key,eatwhat_account_multiwriter.session_fact,eatwhat_account_multiwriter.session_revocation TO "+role);execute(connection,"GRANT EXECUTE ON FUNCTION eatwhat_account_multiwriter.revoke_received_session_key(text,text,uuid),eatwhat_account_multiwriter.erase_withdrawn_projection(uuid) TO "+role);execute(connection,"GRANT SELECT,INSERT,DELETE ON eatwhat_account_multiwriter.transfer_page TO "+role);}
                        }
                    }
                }
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
                    SELECT nspowner AS owner FROM pg_namespace WHERE nspname='eatwhat_consent'
                    UNION SELECT c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='eatwhat_consent'
                    UNION SELECT p.proowner FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='eatwhat_consent'
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
                    WHERE n.nspname='eatwhat_consent' AND c.relkind='r'
                      AND has_table_privilege(r.oid,c.oid,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
                  OR EXISTS (SELECT 1 FROM reachable r WHERE has_schema_privilege(r.oid,'eatwhat_consent','CREATE')
                    OR has_table_privilege(r.oid,'eatwhat_consent.import_context','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                    OR has_parameter_privilege(r.oid,'session_replication_role','SET'))
                """)) {
            query.setString(1, runtimeUsername);
            try (var row = query.executeQuery()) {
                row.next();
                if (row.getBoolean(1)) throw new IllegalStateException("Runtime retains broader consent privileges through another role");
            }
        }
        for (var table : MUTABLE.entrySet()) {
            try (var query = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM pg_roles r CROSS JOIN pg_attribute a
                      WHERE pg_has_role(?::name,r.oid,'MEMBER') AND a.attrelid=?::regclass
                        AND a.attnum>0 AND NOT a.attisdropped AND a.attname<>ALL(?::text[])
                        AND has_column_privilege(r.oid,a.attrelid,a.attnum,'UPDATE'))
                    """)) {
                query.setString(1, runtimeUsername);
                query.setString(2, "eatwhat_consent." + table.getKey());
                query.setArray(3, connection.createArrayOf("text", table.getValue().isEmpty()
                        ? new String[0] : table.getValue().split(",")));
                try (var row = query.executeQuery()) {
                    row.next();
                    if (row.getBoolean(1)) throw new IllegalStateException("Runtime can update immutable consent columns");
                }
            }
        }
    }

    private static Map<String, String> mutableColumns() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("subject", "epoch,revision,status,policy_version,valid_until,identity_ready,backfill_status,cleanup_status,withdrawal_request_id,source_archived,local_dirty,effects_pending,effects_version,registration_revision,registration_hash,account_hash");
        values.put("job", "status,available_at,lease_token,lease_until,attempts");
        values.put("proof", "consumed_at");
        values.put("withdrawal_receipt", "");
        values.put("conflict_receipt", "");
        return values;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
}
