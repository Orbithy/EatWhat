package you.v50to.eatwhat.service.storage.replication;

import you.v50to.eatwhat.config.FileReplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

/**
 * Schedules FileReplicationWorker.runOnce() on a fixed delay, with daemon threads and graceful shutdown.
 * Enabled only when both app.storage.files.replication.enabled=true AND worker-enabled=true.
 *
 * Processing logic:
 * - Runs worker.runOnce() every pollInterval milliseconds
 * - If runOnce() processes a full batch, immediately reschedules (drain) rather than wait
 * - Never spins on errors; transient failures are logged and retried on next scheduled cycle
 * - Shutdown waits up to 35 seconds for worker thread to terminate
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
@ConditionalOnProperty(name = "app.storage.files.replication.worker-enabled", havingValue = "true")
public class FileReplicationRunner implements SmartLifecycle {
    private final FileReplicationWorker worker;
    private final FileReplicationProperties props;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public FileReplicationRunner(FileReplicationWorker worker, FileReplicationProperties props) {
        this.worker = worker;
        this.props = props;
        this.running = false;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        try {
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "file-replication-worker");
                thread.setDaemon(true);
                return thread;
            });

            long pollIntervalMs = props.getPollInterval().toMillis();
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    int processed = worker.runOnce();
                    // If full batch was processed, immediately run again to drain the queue.
                    // If processed < batch, wait for next scheduled interval.
                    if (processed >= props.getBatch()) {
                        log.debug("Processed full batch ({}); will re-run immediately next cycle", processed);
                    }
                } catch (RuntimeException failure) {
                    // Log but do not crash: the scheduler continues on next cycle.
                    log.error("Error in file replication worker cycle", failure);
                } catch (Throwable failure) {
                    // Catch all Throwables to ensure the schedule never dies.
                    log.error("Unexpected error in file replication worker", failure);
                }
            }, 0, pollIntervalMs, TimeUnit.MILLISECONDS);

            running = true;
            log.info("File replication worker started (poll interval: {}ms)", pollIntervalMs);
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("Cannot start file replication worker", failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        running = false;
        if (scheduler != null) {
            try {
                if (!scheduler.awaitTermination(35, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("File replication worker did not stop within the bounded timeout");
                }
                scheduler = null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the file replication worker");
            }
        }
        log.info("File replication worker stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Start after basic infrastructure (database, etc.) but before most application logic.
        // Use a phase number lower than SyncTransportRuntime (Integer.MAX_VALUE - 100).
        return Integer.MAX_VALUE - 90;
    }
}
