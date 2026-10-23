package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.service.storage.S3CompatibleStorageService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the S3 adapter against a test-owned RustFS container with synthetic credentials. */
class RustFsObjectStorageIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "image/png";

    @Test
    void signedStagingPutCanBeVerifiedAndCopiedToAnImmutableKey() throws Exception {
        Path source = Files.createTempFile("rustfs-source-", ".png");
        Path downloaded = Files.createTempFile("rustfs-downloaded-", ".png");
        Path immutable = Files.createTempFile("rustfs-immutable-", ".png");
        try (GenericContainer<?> rustfs = IsolatedContainers.rustfs("file-adapter")) {
            try {
            rustfs.start();
            String endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
            String bucket = "eatwhat-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY);
            try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(REGION))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
                admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

                S3StorageProperties properties = new S3StorageProperties();
                properties.setProvider(S3StorageProperties.Provider.RUSTFS);
                properties.setEndpoint(endpoint);
                properties.setRegion(REGION);
                properties.setBucket(bucket);
                properties.setAccessKey(IsolatedContainers.RUSTFS_ACCESS_KEY);
                properties.setSecretKey(IsolatedContainers.RUSTFS_SECRET_KEY);
                properties.setPathStyle(true);
                try (S3CompatibleStorageService storage = new S3CompatibleStorageService(properties)) {
                    String stagingKey = "staging/" + UUID.randomUUID();
                    String finalKey = "verified/" + UUID.randomUUID().toString().replace("-", "");
                    byte[] original = pngBytes("verified first upload");
                    byte[] replacement = pngBytes("late staging replacement");
                    Files.write(source, original);

                    URI putUrl = URI.create(storage.presignStagingPut(stagingKey, CONTENT_TYPE, 300));
                    assertEquals(endpoint, putUrl.getScheme() + "://" + putUrl.getAuthority());
                    assertTrue(putUrl.getRawQuery().contains("X-Amz-Expires=300"),
                            "The presigned PUT has its own shorter lifetime than the database intent");
                    assertEquals(200, put(putUrl, original), "RustFS accepts the exact signed content type");
                    var metadata = storage.downloadToFile(stagingKey, downloaded, 1024);
                    assertEquals(original.length, metadata.contentLength());
                    assertEquals(CONTENT_TYPE, metadata.contentType());
                    assertArrayEquals(original, Files.readAllBytes(downloaded));
                    storage.putImmutableFile(finalKey, CONTENT_TYPE, downloaded);

                    assertEquals(200, put(putUrl, replacement), "The still-valid URL can overwrite only staging");
                    storage.downloadToFile(finalKey, immutable, 1024);
                    assertArrayEquals(original, Files.readAllBytes(immutable),
                            "A later staging PUT cannot alter the immutable destination key");
                    var object = admin.headObject(builder -> builder.bucket(bucket).key(finalKey));
                    assertEquals(original.length, object.contentLength());
                    assertEquals(CONTENT_TYPE, object.contentType());
                }
            }
            } finally {
                Files.deleteIfExists(source);
                Files.deleteIfExists(downloaded);
                Files.deleteIfExists(immutable);
            }
        }
    }

    private static int put(URI url, byte[] bytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20))
                .header("Content-Type", CONTENT_TYPE).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static byte[] pngBytes(String suffix) {
        byte[] marker = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        byte[] tail = suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(marker, marker.length + tail.length);
        System.arraycopy(tail, 0, result, marker.length, tail.length);
        return result;
    }
}
