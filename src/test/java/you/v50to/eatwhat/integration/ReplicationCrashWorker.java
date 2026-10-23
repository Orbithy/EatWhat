package you.v50to.eatwhat.integration;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.service.storage.replication.FilePublicationService;
import you.v50to.eatwhat.service.storage.replication.FileReplicationWorker;
import you.v50to.eatwhat.service.storage.replication.FileTransferJobStore;
import you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore;
import you.v50to.eatwhat.service.storage.replication.ReplicationConsentGate;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Test-only child process for crash recovery testing.
 * Claims one replication job and processes it, with configurable crash points
 * that write a signal file and block forever, simulating a crash after certain milestones.
 *
 * Args: checkpoint, jdbc_url, db_user, rustfs_endpoint, local_bucket, peer_bucket, signal_file_path
 * Checkpoints:
 *   - "after-put": object successfully written to peer, then crash
 *   - "mid-download": source object half-downloaded, then crash
 */
public final class ReplicationCrashWorker {
    private ReplicationCrashWorker() { }

    public static void main(String[] args) throws Exception {
        String checkpoint = args[0];
        String jdbcUrl = args[1];
        String dbUser = args[2];
        String rustfsEndpoint = args[3];
        String localBucket = args[4];
        String peerBucket = args[5];
        Path signalFile = Path.of(args[6]);

        // Setup database and stores
        var dataSource = new DriverManagerDataSource(jdbcUrl, dbUser, IsolatedContainers.PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        FilePublicationService publicationService = new FilePublicationService(jdbc);
        FileTransferJobStore jobStore = new FileTransferJobStore(jdbc, publicationService);

        // Create S3 stores
        S3ReplicaObjectStore localStore = new S3ReplicaObjectStore(rustfsEndpoint, "us-east-1", localBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
        S3ReplicaObjectStore peerStore = new S3ReplicaObjectStore(rustfsEndpoint, "us-east-1", peerBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);

        // COPY_TO_PEER reads from the local store and writes to the peer store: "mid-download" faults the
        // source after its bytes were fetched, "after-put" faults the destination after its PUT succeeded.
        ReplicaObjectStore faultLocalStore = "mid-download".equals(checkpoint)
                ? new FaultInjectionReplicaObjectStore(localStore, checkpoint, signalFile) : localStore;
        ReplicaObjectStore faultPeerStore = "after-put".equals(checkpoint)
                ? new FaultInjectionReplicaObjectStore(peerStore, checkpoint, signalFile) : peerStore;

        // Create worker with crash gate
        FileReplicationProperties props = new FileReplicationProperties();
        props.setTempDir(Files.createTempDirectory("crash-test-").toString());
        props.setRetryBase(Duration.ofMillis(50));
        props.setRetryMax(Duration.ofSeconds(2));
        props.setJitter(0.1);
        props.setAlertAfterAttempts(50);
        props.setLeaseSeconds(120);
        props.setBatch(8);

        FileReplicationWorker worker = new FileReplicationWorker(jobStore, new AlwaysAllowConsentGate(), faultLocalStore, faultPeerStore, props);

        try {
            // Claim and process one job
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));
            if (jobs.isEmpty()) {
                throw new IllegalStateException("No jobs available to claim");
            }
            var job = jobs.get(0);
            var outcome = worker.process(job);

            // If we get here without hitting the crash point, fail
            throw new IllegalStateException("Replication worker did not reach checkpoint: " + checkpoint + ", outcome: " + outcome);
        } finally {
            localStore.close();
            peerStore.close();
        }
    }

    /**
     * Wrapper around ReplicaObjectStore that injects faults at specific checkpoints.
     */
    private static class FaultInjectionReplicaObjectStore implements ReplicaObjectStore {
        private final ReplicaObjectStore delegate;
        private final String checkpoint;
        private final Path signalFile;

        FaultInjectionReplicaObjectStore(ReplicaObjectStore delegate, String checkpoint, Path signalFile) {
            this.delegate = Objects.requireNonNull(delegate);
            this.checkpoint = Objects.requireNonNull(checkpoint);
            this.signalFile = Objects.requireNonNull(signalFile);
        }

        @Override
        public Optional<ObjectMeta> head(String key) {
            return delegate.head(key);
        }

        @Override
        public DigestResult downloadTo(String key, Path tmp, long maxBytes) {
            DigestResult result = delegate.downloadTo(key, tmp, maxBytes);
            if ("mid-download".equals(checkpoint)) {
                // Successfully downloaded; now signal and crash
                try {
                    Files.writeString(signalFile, "MID_DOWNLOAD");
                    signalAndBlock();
                } catch (Exception e) {
                    throw new RuntimeException("Crash checkpoint failed", e);
                }
            }
            return result;
        }

        @Override
        public void putFile(String key, String contentType, Path src, String sha256Hex) {
            delegate.putFile(key, contentType, src, sha256Hex);
            if ("after-put".equals(checkpoint)) {
                try {
                    Files.writeString(signalFile, "AFTER_PUT");
                    signalAndBlock();
                } catch (Exception e) {
                    throw new RuntimeException("Crash checkpoint failed", e);
                }
            }
        }

        @Override
        public DigestResult readbackDigest(String key, long maxBytes) {
            return delegate.readbackDigest(key, maxBytes);
        }

        private static void signalAndBlock() throws InterruptedException {
            new CountDownLatch(1).await();
        }
    }

    private static class AlwaysAllowConsentGate implements ReplicationConsentGate {
        @Override
        public boolean replicationAllowed(UUID ownerGid) {
            return true;
        }

        @Override
        public Long currentEpoch(UUID ownerGid) {
            return 1L;
        }
    }
}
