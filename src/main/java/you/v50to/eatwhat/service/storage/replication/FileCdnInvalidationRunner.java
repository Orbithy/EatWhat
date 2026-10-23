package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import you.v50to.eatwhat.config.FileCdnInvalidationProperties;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs {@link FileCdnInvalidationWorker#runOnce()} on a fixed delay on one daemon thread ("file-cdn-invalidation"). */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.files.cdn-invalidation.enabled", havingValue = "true")
public class FileCdnInvalidationRunner implements SmartLifecycle {
    private final FileCdnInvalidationWorker worker;
    private final FileCdnInvalidationProperties props;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public FileCdnInvalidationRunner(FileCdnInvalidationWorker worker, FileCdnInvalidationProperties props) {
        this.worker = worker;
        this.props = props;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        try {
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "file-cdn-invalidation");
                thread.setDaemon(true);
                return thread;
            });
            long pollMs = props.getPollInterval().toMillis();
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    worker.runOnce();
                } catch (Throwable failure) {
                    // Catch all Throwables so the schedule never dies; the next cycle retries.
                    log.error("Error in CDN invalidation cycle", failure);
                }
            }, 0, pollMs, TimeUnit.MILLISECONDS);
            running = true;
            log.info("CDN invalidation worker started (poll interval: {}ms, provider: {})", pollMs, props.getProvider());
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("Cannot start CDN invalidation worker", failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        running = false;
        if (scheduler != null) {
            try {
                if (!scheduler.awaitTermination(35, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("CDN invalidation worker did not stop within the bounded timeout");
                }
                scheduler = null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the CDN invalidation worker");
            }
        }
        log.info("CDN invalidation worker stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 85;
    }
}
