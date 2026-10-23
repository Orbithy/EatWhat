package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.FileObjectStorage;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Per-user presign limits (stage 07E): unlimited by default, then open-intent and hourly-bytes caps, per owner. */
class FilePresignLimitIT {
    private static final long SIZE = 100;

    @Test
    void presignLimitsAreUnlimitedByDefaultAndEnforcedPerUserWhenConfigured() {
        try (var postgres = IsolatedContainers.postgres("presign-limits")) {
            postgres.start();
            String database = "presign_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE),
                    IsolatedContainers.USER, IsolatedContainers.PASSWORD)).execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                    .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long alice = user(jdbc, "alice");
            long bob = user(jdbc, "bob");
            S3StorageProperties storage = new S3StorageProperties();
            storage.setAllowedContentTypes(List.of("image/png"));
            var tx = new DataSourceTransactionManager(dataSource);

            // ---- defaults: 0 means unlimited, nothing is counted or rejected ----
            FileLifecycleProperties unlimited = lifecycle(0, 0);
            FileLifecycleService free = new FileLifecycleService(jdbc, new SigningOnlyStorage(), storage, unlimited, tx);
            for (int i = 0; i < 25; i++) {
                free.presign(alice, UploadBiz.FOODS, "a" + i + ".png", "image/png", SIZE);
            }
            assertEquals(25, openIntents(jdbc, alice));

            // ---- max open intents per user: the (N+1)th presign is refused, other users are unaffected ----
            FileLifecycleService openLimited = new FileLifecycleService(jdbc, new SigningOnlyStorage(), storage, lifecycle(27, 0), tx);
            openLimited.presign(alice, UploadBiz.FOODS, "b1.png", "image/png", SIZE);
            openLimited.presign(alice, UploadBiz.FOODS, "b2.png", "image/png", SIZE); // 27 open now
            BizException refused = assertThrows(BizException.class,
                    () -> openLimited.presign(alice, UploadBiz.FOODS, "b3.png", "image/png", SIZE));
            assertEquals(BizCode.TOO_MANY_REQUESTS, refused.getBizCode());
            assertEquals(27, openIntents(jdbc, alice), "the refused presign left no intent behind");
            assertNotNull(openLimited.presign(bob, UploadBiz.FOODS, "bob.png", "image/png", SIZE), "limits are per user");

            // Completed or expired intents no longer count as open (an intent's expiry is immutable, so seed expired ones).
            long eve = user(jdbc, "eve");
            for (int i = 0; i < 3; i++) {
                seedIntent(jdbc, eve, SIZE, "PENDING", "clock_timestamp()-interval '2 hours'", "clock_timestamp()-interval '1 second'");
            }
            seedIntent(jdbc, eve, SIZE, "COMPLETE", "clock_timestamp()", "clock_timestamp()+interval '1 hour'");
            FileLifecycleService twoOpen = new FileLifecycleService(jdbc, new SigningOnlyStorage(), storage, lifecycle(2, 0), tx);
            assertNotNull(twoOpen.presign(eve, UploadBiz.FOODS, "e1.png", "image/png", SIZE));
            assertNotNull(twoOpen.presign(eve, UploadBiz.FOODS, "e2.png", "image/png", SIZE));
            assertThrows(BizException.class, () -> twoOpen.presign(eve, UploadBiz.FOODS, "e3.png", "image/png", SIZE));

            // ---- bytes per hour per user (database clock) ----
            long carol = user(jdbc, "carol");
            FileLifecycleService bytesLimited = new FileLifecycleService(jdbc, new SigningOnlyStorage(), storage, lifecycle(0, 250), tx);
            bytesLimited.presign(carol, UploadBiz.FOODS, "c1.png", "image/png", 100L);
            bytesLimited.presign(carol, UploadBiz.FOODS, "c2.png", "image/png", 150L); // exactly 250: allowed
            BizException overBudget = assertThrows(BizException.class,
                    () -> bytesLimited.presign(carol, UploadBiz.FOODS, "c3.png", "image/png", 1L));
            assertEquals(BizCode.TOO_MANY_REQUESTS, overBudget.getBizCode());
            assertEquals(2, openIntents(jdbc, carol), "the refused presign left no intent behind");
            // Only the last hour counts: an intent from 61 minutes ago is ignored, one from 59 minutes ago still counts.
            long frank = user(jdbc, "frank");
            seedIntent(jdbc, frank, 250, "COMPLETE", "clock_timestamp()-interval '61 minutes'", "clock_timestamp()+interval '1 hour'");
            seedIntent(jdbc, frank, 100, "COMPLETE", "clock_timestamp()-interval '59 minutes'", "clock_timestamp()+interval '1 hour'");
            assertThrows(BizException.class, () -> bytesLimited.presign(frank, UploadBiz.FOODS, "f1.png", "image/png", 151L));
            assertNotNull(bytesLimited.presign(frank, UploadBiz.FOODS, "f2.png", "image/png", 150L));
            // A single request above the budget is refused even for a user with nothing recent.
            long dave = user(jdbc, "dave");
            assertThrows(BizException.class, () -> bytesLimited.presign(dave, UploadBiz.FOODS, "d1.png", "image/png", 251L));
        }
    }

    private static FileLifecycleProperties lifecycle(int maxOpen, long maxBytesPerHour) {
        FileLifecycleProperties properties = new FileLifecycleProperties();
        properties.setLifecycleEnabled(true);
        properties.setMaxOpenIntentsPerUser(maxOpen);
        properties.setMaxBytesPerHourPerUser(maxBytesPerHour);
        return properties;
    }

    /** Insert an intent directly: its created_at and expires_at are immutable once written. */
    private static void seedIntent(JdbcTemplate jdbc, long owner, long size, String status, String createdExpr, String expiresExpr) {
        UUID ownerGid = jdbc.queryForObject(
                "SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?", UUID.class, owner);
        jdbc.update("INSERT INTO eatwhat_file.upload_intent(intent_id, owner_id, owner_gid, biz, logical_key, staging_key, "
                        + "declared_size, declared_type, expires_at, status, created_at) VALUES (?,?,?,'foods',?,?,?,'image/png',"
                        + expiresExpr + ",?," + createdExpr + ")",
                UUID.randomUUID(), owner, ownerGid, "foods/" + UUID.randomUUID(), "staging/" + UUID.randomUUID(), size, status);
    }

    private static int openIntents(JdbcTemplate jdbc, long owner) {
        return jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.upload_intent WHERE owner_id=? AND status='PENDING'", Integer.class, owner);
    }

    private static long user(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("INSERT INTO public.users(nick_name,password_hash) VALUES (?, 'synthetic') RETURNING id", Long.class, name);
    }

    /** Presign needs only the signed URL; every other storage operation is unused here. */
    private static final class SigningOnlyStorage implements FileObjectStorage {
        @Override public String presignStagingPut(String stagingKey, String contentType, long ttlSeconds) {
            return "https://objects.example.invalid/" + stagingKey;
        }
        @Override public StagedMetadata head(String key) { throw new UnsupportedOperationException(); }
        @Override public StagedMetadata downloadToFile(String key, Path destination, long maxBytes) { throw new UnsupportedOperationException(); }
        @Override public void putImmutableFile(String key, String contentType, Path source) { throw new UnsupportedOperationException(); }
    }
}
