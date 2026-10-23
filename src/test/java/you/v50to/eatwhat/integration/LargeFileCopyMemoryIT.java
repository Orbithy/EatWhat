package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import you.v50to.eatwhat.service.storage.replication.FileTransferJobStore;
import you.v50to.eatwhat.service.storage.replication.FilePublicationService;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static you.v50to.eatwhat.integration.LargeFileTestSupport.MIB;

/**
 * T11 peak-memory test: the server-side copy of a large object must run in bounded memory.
 *
 * <p>A child JVM with a 96 MiB heap ({@code -Xms96m -Xmx96m -XX:+UseSerialGC}, so the heap shape is fixed) copies a
 * synthetic object between two RustFS buckets through {@link you.v50to.eatwhat.service.storage.replication.FileReplicationWorker}
 * (download to a temp file, multipart upload with 8 MiB parts, full SHA-256 readback) and reports the peak heap usage
 * of the copy (all heap pools' peak usage reset right before it) together with the end-to-end SHA-256.
 *
 * <p>It is run for two sizes. Asserted: both copies complete with the expected size and SHA, and the peak of the larger
 * copy is at most the peak of the smaller one plus {@code 2 * part-size} plus a small slack, i.e. peak memory does not
 * grow with the object size. A copy that buffered the object would also die with an OutOfMemoryError at these sizes
 * (the heap is smaller than the larger object).
 *
 * <p>The synthetic source object is streamed to a temp file (SHA-256 computed while writing) and uploaded with the same
 * {@link S3ReplicaObjectStore}, so generating it is bounded-memory too.
 *
 * <p><b>Sizes.</b> The default run uses 64 MiB and 192 MiB. {@code -Dit.large=true} switches the larger one to 512 MiB
 * (manual / nightly profile; needs about 3 GiB of free disk: the generated file, the RustFS copies and the temp file), for
 * example {@code mvn -Pintegration -Dit.test=LargeFileCopyMemoryIT -Dit.large=true verify}.
 */
class LargeFileCopyMemoryIT {
    private static final String REGION = "us-east-1";
    private static final long PART = 8 * MIB;
    private static final long THRESHOLD = 16 * MIB;
    private static final long SLACK = 16 * MIB;
    private static final Pattern RESULT = Pattern.compile(
            "RESULT outcome=(\\w+) peakHeapBytes=(\\d+) maxHeapBytes=(\\d+) size=(-?\\d+) sha256=([0-9a-f]*)");

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static String endpoint;
    private static String postgresUrlBase;
    private static String localBucket;
    private static String peerBucket;
    private static S3ReplicaObjectStore localStore;

    @TempDir
    Path scratch;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("lfcm");
        postgres.start();
        postgresUrlBase = IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE);
        rustfs = IsolatedContainers.rustfs("lfcm");
        rustfs.start();
        endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
        localBucket = "local-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        peerBucket = "peer-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(localBucket).build());
            admin.createBucket(CreateBucketRequest.builder().bucket(peerBucket).build());
        }
        // The seeding store uses the production defaults (16 MiB threshold, 8 MiB parts): bounded memory as well.
        localStore = new S3ReplicaObjectStore(endpoint, REGION, localBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
    }

    @AfterAll
    static void stop() {
        if (localStore != null) localStore.close();
        if (rustfs != null) rustfs.stop();
        if (postgres != null) postgres.stop();
    }

    private record Measurement(long peakHeapBytes, long maxHeapBytes) { }

    @Test
    void peakHeapOfAServerSideCopyDoesNotGrowWithTheObjectSize() throws Exception {
        long small = 64 * MIB;
        long large = Boolean.getBoolean("it.large") ? 512 * MIB : 192 * MIB;

        Measurement smallRun = copyInChildJvm(small, 1);
        Measurement largeRun = copyInChildJvm(large, 2);

        long allowed = smallRun.peakHeapBytes() + 2 * PART + SLACK;
        System.out.println("T11 peak heap: " + small / MIB + " MiB -> " + smallRun.peakHeapBytes() / MIB + " MiB, "
                + large / MIB + " MiB -> " + largeRun.peakHeapBytes() / MIB + " MiB (allowed " + allowed / MIB
                + " MiB, heap " + smallRun.maxHeapBytes() / MIB + " MiB)");
        assertTrue(smallRun.maxHeapBytes() <= 100 * MIB, "the child ran with the small heap");
        assertTrue(largeRun.peakHeapBytes() <= allowed,
                "peak heap grew with the object size: " + largeRun.peakHeapBytes() + " > " + allowed);
    }

    /** Seed an object of the given size, copy it LOCAL -> PEER in a fresh child JVM and verify size and SHA. */
    private Measurement copyInChildJvm(long size, long seed) throws Exception {
        Path workDir = Files.createDirectory(scratch.resolve("run-" + size));
        var generated = LargeFileTestSupport.generate(workDir, "source", size, seed);
        UUID fileId = UUID.randomUUID();
        String key = "verified/" + fileId.toString().replace("-", "");
        localStore.putFile(key, "application/octet-stream", generated.file(), generated.sha256());
        Files.delete(generated.file()); // free the disk before the copy needs its temp file

        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(postgresUrlBase, IsolatedContainers.USER, IsolatedContainers.PASSWORD))
                .execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, UUID.randomUUID(), "test/" + fileId);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at)
                VALUES(?, 1, ?, ?, ?, 'application/octet-stream', 'VERIFIED', clock_timestamp())
                """, fileId, key, generated.sha256(), size);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at)
                VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, ?, clock_timestamp())
                """, fileId, key, generated.sha256(), size);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
                VALUES(?, 1, 'LOCAL_ONLY', clock_timestamp(), clock_timestamp()) ON CONFLICT DO NOTHING
                """, fileId);
        new FileTransferJobStore(jdbc, new FilePublicationService(jdbc)).enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);

        Path childTemp = Files.createDirectory(workDir.resolve("child-temp"));
        Path log = workDir.resolve("child.log");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms96m", "-Xmx96m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError",
                "-Dlogging.config=classpath:logback-test.xml",
                "-cp", System.getProperty("java.class.path"),
                LargeFileCopyWorker.class.getName(),
                url, IsolatedContainers.USER, endpoint, localBucket, peerBucket, childTemp.toString(),
                String.valueOf(PART), String.valueOf(THRESHOLD));
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("TZ", "UTC");
        Process child = builder.start();
        long timeoutMinutes = 5 + size / (32 * MIB);
        boolean finished = child.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            child.destroyForcibly();
        }
        String output = Files.readString(log);
        assertTrue(finished, "the child did not finish within " + timeoutMinutes + " minutes:\n" + output);
        assertEquals(0, child.exitValue(), "the child failed (an OutOfMemoryError exits with 3):\n" + output);

        Matcher result = RESULT.matcher(output);
        assertTrue(result.find(), "no RESULT line in the child output:\n" + output);
        assertEquals("COMPLETED", result.group(1), output);
        assertEquals(size, Long.parseLong(result.group(4)), "end-to-end size");
        assertEquals(generated.sha256(), result.group(5), "end-to-end SHA-256 of the destination object");
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE file_id=?", String.class, fileId));
        assertNull(jdbc.queryForObject("SELECT multipart_upload_id FROM eatwhat_file.file_transfer_job WHERE file_id=?", String.class, fileId));
        return new Measurement(Long.parseLong(result.group(2)), Long.parseLong(result.group(3)));
    }
}
