package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import you.v50to.eatwhat.service.storage.replication.ObjectNotFoundException;
import you.v50to.eatwhat.service.storage.replication.ObjectTooLargeException;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for S3ReplicaObjectStore using RustFS container.
 * Tests bidirectional replication scenarios with two separate buckets.
 */
class RustFsReplicaObjectStoreIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/octet-stream";

    @Test
    void putHeadDownloadRoundTripWithMatchingShaAndSize() throws Exception {
        Path sourceFile = Files.createTempFile("replication-source-", ".bin");
        Path downloadedFile = Files.createTempFile("replication-downloaded-", ".bin");
        try (GenericContainer<?> rustfs = IsolatedContainers.rustfs("replica-adapter")) {
            try {
                rustfs.start();
                String endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
                String localBucket = "local-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                String peerBucket = "peer-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                AwsBasicCredentials credentials = AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY);

                try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint))
                        .region(Region.of(REGION))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build()) {
                    admin.createBucket(CreateBucketRequest.builder().bucket(localBucket).build());
                    admin.createBucket(CreateBucketRequest.builder().bucket(peerBucket).build());

                    S3ReplicaObjectStore local = new S3ReplicaObjectStore(endpoint, REGION, localBucket,
                            IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
                    S3ReplicaObjectStore peer = new S3ReplicaObjectStore(endpoint, REGION, peerBucket,
                            IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);

                    try {
                        // Create source file with known content
                        byte[] originalContent = pngBytes("replication test payload");
                        String sha256Hex = computeSha256(originalContent);
                        Files.write(sourceFile, originalContent);

                        // Upload to local store
                        String key = "verified/" + UUID.randomUUID().toString().replace("-", "");
                        local.putFile(key, CONTENT_TYPE, sourceFile, sha256Hex);

                        // Verify via head()
                        var meta = local.head(key);
                        assertTrue(meta.isPresent(), "Object should be found after upload");
                        assertEquals(originalContent.length, meta.get().size());
                        assertEquals(CONTENT_TYPE, meta.get().contentType());

                        // Download and verify
                        var digestResult = local.downloadTo(key, downloadedFile, 10 * 1024 * 1024);
                        assertEquals(originalContent.length, digestResult.size());
                        assertEquals(sha256Hex, digestResult.sha256Hex());
                        assertArrayEquals(originalContent, Files.readAllBytes(downloadedFile));
                    } finally {
                        local.close();
                        peer.close();
                    }
                }
            } finally {
                Files.deleteIfExists(sourceFile);
                Files.deleteIfExists(downloadedFile);
            }
        }
    }

    @Test
    void headOfMissingKeyReturnsEmpty() throws Exception {
        try (GenericContainer<?> rustfs = IsolatedContainers.rustfs("replica-missing")) {
            {
                rustfs.start();
                String endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
                String bucket = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                AwsBasicCredentials credentials = AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY);

                try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint))
                        .region(Region.of(REGION))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build()) {
                    admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

                    S3ReplicaObjectStore store = new S3ReplicaObjectStore(endpoint, REGION, bucket,
                            IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
                    try {
                        var result = store.head("nonexistent/key");
                        assertTrue(result.isEmpty(), "head() should return empty for missing key");
                    } finally {
                        store.close();
                    }
                }
            }
        }
    }

    @Test
    void downloadToThrowsObjectTooLargeExceptionWhenExceedsMaxBytes() throws Exception {
        Path sourceFile = Files.createTempFile("replication-oversized-", ".bin");
        Path downloadedFile = Files.createTempFile("replication-overflow-", ".bin");
        try (GenericContainer<?> rustfs = IsolatedContainers.rustfs("replica-maxbytes")) {
            try {
                rustfs.start();
                String endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
                String bucket = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                AwsBasicCredentials credentials = AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY);

                try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint))
                        .region(Region.of(REGION))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build()) {
                    admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

                    S3ReplicaObjectStore store = new S3ReplicaObjectStore(endpoint, REGION, bucket,
                            IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
                    try {
                        // Create a 1 MB file
                        byte[] content = new byte[1024 * 1024];
                        Arrays.fill(content, (byte) 0xAB);
                        String sha256Hex = computeSha256(content);
                        Files.write(sourceFile, content);

                        String key = "verified/" + UUID.randomUUID().toString().replace("-", "");
                        store.putFile(key, CONTENT_TYPE, sourceFile, sha256Hex);

                        // Try to download with maxBytes=512 KiB, should fail
                        assertThrows(ObjectTooLargeException.class,
                                () -> store.downloadTo(key, downloadedFile, 512 * 1024),
                                "Should throw ObjectTooLargeException when size exceeds maxBytes");
                    } finally {
                        store.close();
                    }
                }
            } finally {
                Files.deleteIfExists(sourceFile);
                Files.deleteIfExists(downloadedFile);
            }
        }
    }

    @Test
    void downloadToThrowsObjectNotFoundExceptionForMissingKey() throws Exception {
        Path downloadedFile = Files.createTempFile("replication-notfound-", ".bin");
        try (GenericContainer<?> rustfs = IsolatedContainers.rustfs("replica-notfound")) {
            try {
                rustfs.start();
                String endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
                String bucket = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                AwsBasicCredentials credentials = AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY);

                try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint))
                        .region(Region.of(REGION))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build()) {
                    admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

                    S3ReplicaObjectStore store = new S3ReplicaObjectStore(endpoint, REGION, bucket,
                            IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
                    try {
                        ObjectNotFoundException ex = assertThrows(ObjectNotFoundException.class,
                                () -> store.downloadTo("nonexistent/key", downloadedFile, 10 * 1024 * 1024));
                        assertEquals("nonexistent/key", ex.getKey());
                    } finally {
                        store.close();
                    }
                }
            } finally {
                Files.deleteIfExists(downloadedFile);
            }
        }
    }

    @Test
    void putFileRefusesOverwriteOnExistingKey() throws Exception {
        Path sourceFile = Files.createTempFile("replication-overwrite-", ".bin");
        try (GenericContainer<?> rustfs = IsolatedContainers.rustfs("replica-overwrite")) {
            try {
                rustfs.start();
                String endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
                String bucket = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                AwsBasicCredentials credentials = AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY);

                try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint))
                        .region(Region.of(REGION))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build()) {
                    admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

                    S3ReplicaObjectStore store = new S3ReplicaObjectStore(endpoint, REGION, bucket,
                            IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
                    try {
                        byte[] originalContent = pngBytes("original content");
                        String sha256Hex = computeSha256(originalContent);
                        Files.write(sourceFile, originalContent);

                        String key = "verified/" + UUID.randomUUID().toString().replace("-", "");
                        store.putFile(key, CONTENT_TYPE, sourceFile, sha256Hex);

                        // Attempt to overwrite should fail
                        byte[] replacementContent = pngBytes("replacement content");
                        Files.write(sourceFile, replacementContent);
                        String replacementSha = computeSha256(replacementContent);

                        assertThrows(IllegalStateException.class,
                                () -> store.putFile(key, CONTENT_TYPE, sourceFile, replacementSha),
                                "Second putFile on same key must refuse overwrite");
                    } finally {
                        store.close();
                    }
                }
            } finally {
                Files.deleteIfExists(sourceFile);
            }
        }
    }

    @Test
    void readbackDigestVerifiesShaAfterUpload() throws Exception {
        Path sourceFile = Files.createTempFile("replication-readback-", ".bin");
        try (GenericContainer<?> rustfs = IsolatedContainers.rustfs("replica-readback")) {
            try {
                rustfs.start();
                String endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
                String bucket = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                AwsBasicCredentials credentials = AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY);

                try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint))
                        .region(Region.of(REGION))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build()) {
                    admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

                    S3ReplicaObjectStore store = new S3ReplicaObjectStore(endpoint, REGION, bucket,
                            IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
                    try {
                        byte[] content = pngBytes("readback verification test");
                        String sha256Hex = computeSha256(content);
                        Files.write(sourceFile, content);

                        String key = "verified/" + UUID.randomUUID().toString().replace("-", "");
                        store.putFile(key, CONTENT_TYPE, sourceFile, sha256Hex);

                        // Verify via readbackDigest (streaming to null sink)
                        var digestResult = store.readbackDigest(key, 10 * 1024 * 1024);
                        assertEquals(content.length, digestResult.size());
                        assertEquals(sha256Hex, digestResult.sha256Hex(),
                                "readbackDigest SHA must match the uploaded SHA");
                    } finally {
                        store.close();
                    }
                }
            } finally {
                Files.deleteIfExists(sourceFile);
            }
        }
    }

    private static byte[] pngBytes(String suffix) {
        byte[] marker = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        byte[] tail = suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(marker, marker.length + tail.length);
        System.arraycopy(tail, 0, result, marker.length, tail.length);
        return result;
    }

    private static String computeSha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(data);
        return HexFormat.of().formatHex(digest.digest());
    }
}
