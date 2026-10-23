package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.service.storage.FileAliasResolver;
import you.v50to.eatwhat.service.storage.S3CompatibleStorageService;
import you.v50to.eatwhat.service.storage.replication.FilePublicationService;
import you.v50to.eatwhat.service.storage.replication.FileUrlResolver;
import you.v50to.eatwhat.service.storage.replication.ReplicationConsentGate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FileUrlResolver and S3CompatibleStorageService integration.
 * Tests URL resolution states (UNMANAGED, PENDING, AVAILABLE_LOCAL, AVAILABLE_GLOBAL, PAUSED)
 * and CDN URL generation based on replication state and consent.
 */
class FileUrlResolverIT {
    private static GenericContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static FileUrlResolver resolver;
    private static FileAliasResolver aliasResolver;
    private static FilePublicationService publicationService;
    private static S3CompatibleStorageService storageService;
    private static FileReplicationProperties replicationProperties;
    private static TestReplicationConsentGate consentGate;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("url-resolver-svc");
        postgres.start();
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD)).execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
        aliasResolver = new FileAliasResolver(jdbc);
        publicationService = new FilePublicationService(jdbc);
        consentGate = new TestReplicationConsentGate();
        replicationProperties = new FileReplicationProperties();
        resolver = new FileUrlResolver(jdbc, replicationProperties, consentGate);
        setupStorageService();
    }

    @AfterAll
    static void stop() {
        if (storageService != null) storageService.close();
        if (postgres != null) postgres.stop();
    }

    private static void setupStorageService() {
        S3StorageProperties s3props = new S3StorageProperties();
        s3props.setProvider(S3StorageProperties.Provider.RUSTFS);
        s3props.setEndpoint("http://localhost:9000");
        s3props.setRegion("us-east-1");
        s3props.setBucket("test-bucket");
        s3props.setAccessKey(IsolatedContainers.RUSTFS_ACCESS_KEY);
        s3props.setSecretKey(IsolatedContainers.RUSTFS_SECRET_KEY);
        s3props.setPublicBaseUrl("https://cdn.example.invalid");
        storageService = new S3CompatibleStorageService(s3props, aliasResolver);
        storageService.setFileUrlResolver(resolver);
    }

    @Test
    void unmanagedKeyReturnsUnmanagedState() {
        String logicalKey = "unmanaged/" + UUID.randomUUID();

        FileUrlResolver.Resolution resolution = resolver.describe(logicalKey);

        assertEquals(FileUrlResolver.State.UNMANAGED, resolution.state());
        assertNull(resolution.finalKey());
        assertFalse(resolution.publicReference());
        assertFalse(resolution.cdnEligible());
    }

    @Test
    void revisionNotVerifiedReturnsPendingState() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;

        // Create file object and CANDIDATE revision (not verified)
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'CANDIDATE')",
                fileId, "verified/" + fileId, "a".repeat(64));
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);

        FileUrlResolver.Resolution resolution = resolver.describe(logicalKey);

        assertEquals(FileUrlResolver.State.PENDING, resolution.state());
        assertNull(resolution.finalKey());
        assertFalse(resolution.cdnEligible());
    }

    @Test
    void localReplicaPendingSyncReturnsPendingState() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;

        // Create VERIFIED revision but LOCAL replica PENDING_SYNC (not readable)
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, "a".repeat(64));
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) " +
                "VALUES(?, 1, 'LOCAL', ?, 'PENDING_SYNC')",
                fileId, finalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);

        FileUrlResolver.Resolution resolution = resolver.describe(logicalKey);

        assertEquals(FileUrlResolver.State.PENDING, resolution.state());
        assertNull(resolution.finalKey());
        assertFalse(resolution.cdnEligible());
    }

    @Test
    void availableLocalWithLocalOnlyPublicationReturnsAvailableLocalState() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        // Create VERIFIED revision + LOCAL AVAILABLE_LOCAL + publication LOCAL_ONLY
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at) " +
                "VALUES(?, 1, 'LOCAL_ONLY', clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'test', 'ref1', 'PUBLIC')",
                logicalKey);

        FileUrlResolver.Resolution resolution = resolver.describe(logicalKey);

        assertEquals(FileUrlResolver.State.AVAILABLE_LOCAL, resolution.state());
        assertEquals(finalKey, resolution.finalKey());
        assertTrue(resolution.publicReference());
        assertFalse(resolution.cdnEligible(), "LOCAL_ONLY publication is not CDN eligible");
    }

    @Test
    void availableLocalReturnsOriginUrlNotCdnUrl() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        // Same setup as above
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at) " +
                "VALUES(?, 1, 'LOCAL_ONLY', clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);

                jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'test', 'ref1', 'PUBLIC')",
                logicalKey);

        String url = storageService.signGetUrl(logicalKey);

        assertNotNull(url);
        assertTrue(url.contains("X-Amz-Signature"), "Should return presigned origin URL");
        assertFalse(url.contains("cdn.example.invalid"), "Should not use CDN for LOCAL_ONLY");
    }

    @Test
    void availableGlobalWithCdnDisabledReturnsOriginUrl() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        // cdnOutputEnabled is false by default
        replicationProperties.setCdnOutputEnabled(false);

        // Create AVAILABLE_GLOBAL publication
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', ?, 'PEER_VERIFIED', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at, created_at, updated_at) " +
                "VALUES(?, 1, 'AVAILABLE_GLOBAL', clock_timestamp(), clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'test', 'ref1', 'PUBLIC')",
                logicalKey);

        String url = storageService.signGetUrl(logicalKey);

        assertNotNull(url);
        assertTrue(url.contains("X-Amz-Signature"), "Should return presigned origin URL when CDN disabled");
    }

    @Test
    void availableGlobalWithCdnEnabledAndConsentAllowedReturnsCdnUrl() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        replicationProperties.setCdnOutputEnabled(true);
        consentGate.setAllowed(ownerGid, true);

        // Create AVAILABLE_GLOBAL publication
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', ?, 'PEER_VERIFIED', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at, created_at, updated_at) " +
                "VALUES(?, 1, 'AVAILABLE_GLOBAL', clock_timestamp(), clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'test', 'ref1', 'PUBLIC')",
                logicalKey);

        String url = storageService.signGetUrl(logicalKey);

        assertNotNull(url);
        assertTrue(url.startsWith("https://cdn.example.invalid/"), "Should return CDN URL");
        assertTrue(url.contains(finalKey), "Should contain final key");
        assertFalse(url.contains("X-Amz-Signature"), "Should not contain signature");
    }

    @Test
    void availableGlobalWithConsentDeniedReturnsOriginUrl() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        replicationProperties.setCdnOutputEnabled(true);
        consentGate.setAllowed(ownerGid, false);

        // Create AVAILABLE_GLOBAL publication
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', ?, 'PEER_VERIFIED', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at, created_at, updated_at) " +
                "VALUES(?, 1, 'AVAILABLE_GLOBAL', clock_timestamp(), clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'test', 'ref1', 'PUBLIC')",
                logicalKey);

        String url = storageService.signGetUrl(logicalKey);

        assertNotNull(url);
        assertTrue(url.contains("X-Amz-Signature"), "Should return presigned origin URL when consent denied");
        assertFalse(url.contains("cdn.example.invalid"), "Should not use CDN");
    }

    @Test
    void availableGlobalWithoutPublicReferenceReturnsNull() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        replicationProperties.setCdnOutputEnabled(true);
        consentGate.setAllowed(ownerGid, true);

        // Create AVAILABLE_GLOBAL publication but NO PUBLIC reference
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', ?, 'PEER_VERIFIED', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at, created_at, updated_at) " +
                "VALUES(?, 1, 'AVAILABLE_GLOBAL', clock_timestamp(), clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);
        // No public reference inserted

        String url = storageService.signGetUrl(logicalKey);

        assertNull(url, "Should return null when no PUBLIC reference exists");
    }

    @Test
    void pausedPublicationReturnsOriginUrlNotNull() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        // Create PAUSED publication (readable locally but paused globally)
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at) " +
                "VALUES(?, 1, 'PAUSED', clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);

        FileUrlResolver.Resolution resolution = resolver.describe(logicalKey);

        assertEquals(FileUrlResolver.State.PAUSED, resolution.state());
        assertEquals(finalKey, resolution.finalKey());
        assertFalse(resolution.cdnEligible());

        // signGetUrl should still return presigned origin URL for PAUSED
                jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'test', 'ref1', 'PUBLIC')",
                logicalKey);

        String url = storageService.signGetUrl(logicalKey);
        assertNotNull(url);
        assertTrue(url.contains("X-Amz-Signature"));
    }

    @Test
    void noResolverSetKeepsBytesForBytesOriginalBehavior() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        // Create AVAILABLE_GLOBAL publication with public ref and consent
        replicationProperties.setCdnOutputEnabled(true);
        consentGate.setAllowed(ownerGid, true);

        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', ?, 'PEER_VERIFIED', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at, created_at, updated_at) " +
                "VALUES(?, 1, 'AVAILABLE_GLOBAL', clock_timestamp(), clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'test', 'ref1', 'PUBLIC')",
                logicalKey);

        // Service without resolver should return origin URL
        S3StorageProperties s3props = new S3StorageProperties();
        s3props.setProvider(S3StorageProperties.Provider.RUSTFS);
        s3props.setEndpoint("http://localhost:9000");
        s3props.setRegion("us-east-1");
        s3props.setBucket("test-bucket");
        s3props.setAccessKey(IsolatedContainers.RUSTFS_ACCESS_KEY);
        s3props.setSecretKey(IsolatedContainers.RUSTFS_SECRET_KEY);
        s3props.setPublicBaseUrl("https://cdn.example.invalid");
        try (var serviceWithoutResolver = new S3CompatibleStorageService(s3props, aliasResolver)) {
            // No setFileUrlResolver call
            String url = serviceWithoutResolver.signGetUrl(logicalKey);

            assertNotNull(url);
            assertTrue(url.contains("X-Amz-Signature"), "Without resolver, should return origin URL");
            assertFalse(url.contains("cdn.example.invalid"), "Without resolver, should not use CDN");
        }
    }

    @Test
    void consentGateIdentityPendingFallsBackToOriginUrl() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String logicalKey = "test/" + fileId;
        String finalKey = "verified/" + fileId;
        String sha = "a".repeat(64);

        replicationProperties.setCdnOutputEnabled(true);
        consentGate.setPending(ownerGid);

        // Create AVAILABLE_GLOBAL publication
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', ?, 'PEER_VERIFIED', ?, 100, clock_timestamp())",
                fileId, finalKey, sha);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at, created_at, updated_at) " +
                "VALUES(?, 1, 'AVAILABLE_GLOBAL', clock_timestamp(), clock_timestamp(), clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'test', 'ref1', 'PUBLIC')",
                logicalKey);

        FileUrlResolver.Resolution resolution = resolver.describe(logicalKey);

        assertEquals(FileUrlResolver.State.AVAILABLE_GLOBAL, resolution.state());
        assertFalse(resolution.cdnEligible(), "CDN not eligible when consent gate throws");

        String url = storageService.signGetUrl(logicalKey);
        assertNotNull(url);
        assertTrue(url.contains("X-Amz-Signature"), "Should return origin URL when consent unknown");
    }

    /**
     * Fake ReplicationConsentGate for testing.
     */
    static class TestReplicationConsentGate implements ReplicationConsentGate {
        private final java.util.Map<UUID, Boolean> consent = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Set<UUID> pending = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<>());

        void setAllowed(UUID ownerGid, boolean allowed) {
            consent.put(ownerGid, allowed);
            pending.remove(ownerGid);
        }

        void setPending(UUID ownerGid) {
            pending.add(ownerGid);
            consent.remove(ownerGid);
        }

        @Override
        public boolean replicationAllowed(UUID ownerGid) {
            if (pending.contains(ownerGid)) {
                throw new IllegalArgumentException("IDENTITY_PENDING");
            }
            return consent.getOrDefault(ownerGid, false);
        }

        @Override
        public Long currentEpoch(UUID ownerGid) {
            if (pending.contains(ownerGid)) {
                throw new IllegalArgumentException("IDENTITY_PENDING");
            }
            return null;
        }
    }
}
