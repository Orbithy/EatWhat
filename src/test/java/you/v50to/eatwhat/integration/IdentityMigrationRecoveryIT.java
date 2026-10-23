package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** A blocked V15-to-latest (including cluster sessions) upgrade must roll back and be safe to retry. */
class IdentityMigrationRecoveryIT {
    @Test
    void lockTimeoutRollsBackTheIdentityMigrationAndRetryPreservesHistoricalRows() throws Exception {
        try (GenericContainer<?> postgres = IsolatedContainers.startedPostgresWithInspectRetry("identity-migration-recovery")) {
            try (Connection bootstrap = connect(postgres, IsolatedContainers.DATABASE);
                 var statement = bootstrap.createStatement()) {
                statement.execute("CREATE DATABASE migration_recovery TEMPLATE template0");
            }
            String url = IsolatedContainers.jdbcUrl(postgres, "migration_recovery");
            Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration").target("15").cleanDisabled(true).load().migrate();
            Flyway upgrade = Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration").cleanDisabled(true).load();
            try (Connection owner = connect(postgres, "migration_recovery");
                 Connection writer = connect(postgres, "migration_recovery");
                 var sql = owner.createStatement(); var lock = writer.createStatement()) {
                sql.execute("INSERT INTO users(nick_name,password_hash,updated_at) VALUES ('before','synthetic','2020-01-01Z')");
                String original;
                try (var row = sql.executeQuery("SELECT row_to_json(u)::text FROM users u")) {
                    assertTrue(row.next());
                    original = row.getString(1);
                }
                writer.setAutoCommit(false);
                lock.execute("LOCK TABLE users IN ROW EXCLUSIVE MODE");
                try {
                    FlywayException failure = assertTimeoutPreemptively(Duration.ofSeconds(15),
                            () -> assertThrows(FlywayException.class, upgrade::migrate));
                    assertTrue(hasSqlState(failure, "55P03"), "Failure must come from the bounded migration lock wait");
                    try (var row = sql.executeQuery("SELECT count(*) FROM pg_namespace WHERE nspname='eatwhat_identity'")) {
                        assertTrue(row.next());
                        assertEquals(0, row.getInt(1), "Earlier DDL in the failing V16 transaction must roll back");
                    }
                    try (var row = sql.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                        assertTrue(row.next());
                        assertEquals(14, row.getInt(1));
                    }
                } finally {
                    writer.rollback();
                }
                assertEquals(36, upgrade.migrate().migrationsExecuted);
                assertEquals(0, upgrade.migrate().migrationsExecuted);
                upgrade.validate();
                assertEquals("51", upgrade.info().current().getVersion().getVersion());
                try (var row = sql.executeQuery("SELECT to_regclass('eatwhat_file.upload_intent') IS NOT NULL")) {
                    assertTrue(row.next());
                    assertTrue(row.getBoolean(1), "The V15-to-latest recovery path must install the V27 file schema");
                }
                try (var row = sql.executeQuery("SELECT row_to_json(u)::text FROM users u")) {
                    assertTrue(row.next());
                    assertEquals(original, row.getString(1));
                }
                try (var row = sql.executeQuery("SELECT count(*) FROM eatwhat_identity.entity_identity")) {
                    assertTrue(row.next());
                    assertEquals(0, row.getInt(1), "Schema migration never invents a historical UUID baseline");
                }
            }
        }
    }

    private static Connection connect(GenericContainer<?> postgres, String database) throws SQLException {
        return DriverManager.getConnection(IsolatedContainers.jdbcUrl(postgres, database),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD);
    }

    private boolean hasSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && expected.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
