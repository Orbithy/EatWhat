package you.v50to.eatwhat.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.service.storage.replication.ByteRateLimiter;
import you.v50to.eatwhat.service.storage.replication.FilePublicationService;
import you.v50to.eatwhat.service.storage.replication.FileReplicationWorker;
import you.v50to.eatwhat.service.storage.replication.FileTransferJobStore;
import you.v50to.eatwhat.service.storage.replication.ReplicationConsentGate;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Test-only child process of {@link LargeFileCopyMemoryIT}: runs with a tiny heap (-Xmx96m), claims the single
 * COPY_TO_PEER job of the database and copies the object through {@link FileReplicationWorker} between two buckets,
 * then reports the peak heap usage of the copy and the end-to-end SHA-256 of the destination object.
 *
 * <p>Args: jdbcUrl, dbUser, rustfsEndpoint, localBucket, peerBucket, tempDir, partSizeBytes, thresholdBytes.
 * Output (one line, parsed by the parent): {@code RESULT outcome=... peakHeapBytes=... maxHeapBytes=... size=... sha256=...}
 */
public final class LargeFileCopyWorker {
    private LargeFileCopyWorker() { }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = args[0];
        String dbUser = args[1];
        String endpoint = args[2];
        String localBucket = args[3];
        String peerBucket = args[4];
        Path tempDir = Path.of(args[5]);
        long partSize = Long.parseLong(args[6]);
        long threshold = Long.parseLong(args[7]);

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, dbUser, IsolatedContainers.PASSWORD));
        FileTransferJobStore jobs = new FileTransferJobStore(jdbc, new FilePublicationService(jdbc));
        var tuning = new S3ReplicaObjectStore.Tuning(threshold, partSize, ByteRateLimiter.unlimited());
        try (S3ReplicaObjectStore local = new S3ReplicaObjectStore(endpoint, "us-east-1", localBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true, tuning);
             S3ReplicaObjectStore peer = new S3ReplicaObjectStore(endpoint, "us-east-1", peerBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true, tuning)) {
            FileReplicationProperties props = new FileReplicationProperties();
            props.setTempDir(tempDir.toString());
            props.setRetryBase(Duration.ofMillis(50));
            props.setRetryMax(Duration.ofSeconds(2));
            props.setLeaseSeconds(600);
            props.setTempReserveBytes(0);
            props.setMultipartThreshold(threshold);
            props.setPartSize(partSize);
            FileReplicationWorker worker = new FileReplicationWorker(jobs, new AllowAll(), local, peer, props);

            var claimed = jobs.claim(1, Duration.ofSeconds(600));
            if (claimed.size() != 1) {
                System.out.println("ERROR no job to claim");
                System.exit(2);
            }
            var job = claimed.get(0);

            List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
            System.gc();
            heapPools.forEach(MemoryPoolMXBean::resetPeakUsage); // measure the copy only, not JVM and SDK start-up

            FileReplicationWorker.Outcome outcome = worker.process(job);

            // The sum of per-pool peaks bounds the real peak from above; it is read before any further work.
            long peak = heapPools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            String sha = "";
            long size = -1;
            if (outcome == FileReplicationWorker.Outcome.COMPLETED) {
                var digest = peer.readbackDigest(job.finalKey(), job.sizeBytes());
                sha = digest.sha256Hex();
                size = digest.size();
            }
            System.out.println("RESULT outcome=" + outcome + " peakHeapBytes=" + peak
                    + " maxHeapBytes=" + Runtime.getRuntime().maxMemory() + " size=" + size + " sha256=" + sha);
            System.out.flush();
            // The temp file must be gone as well.
            try (var left = Files.list(tempDir)) {
                if (left.findAny().isPresent()) {
                    System.out.println("ERROR temp file left behind");
                    System.exit(3);
                }
            }
        }
    }

    private static final class AllowAll implements ReplicationConsentGate {
        @Override public boolean replicationAllowed(UUID ownerGid) { return true; }
        @Override public Long currentEpoch(UUID ownerGid) { return 1L; }
    }
}
