package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import you.v50to.eatwhat.config.FileGcProperties;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link FileGcService#detect()} and then the evaluate/act loop every gc-interval on one daemon thread
 * ("file-gc"). Enabled only when app.storage.files.gc.enabled=true (inventory-enabled alone never starts it). With the default configuration
 * (dry-run=true, execute-enabled=false) it only records ELIGIBLE/BLOCKED/REVIVED states and never touches the store.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.files.gc.enabled", havingValue = "true")
public class FileGcRunner implements SmartLifecycle {
    private final FileGcService service;
    private final FileGcProperties props;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public FileGcRunner(FileGcService service, FileGcProperties props) {
        this.service = service;
        this.props = props;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        try {
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "file-gc");
                thread.setDaemon(true);
                return thread;
            });
            long intervalMs = props.getGcInterval().toMillis();
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    service.runCycle();
                } catch (Throwable failure) {
                    // Catch all Throwables so the schedule never dies; the next cycle retries.
                    log.error("Error in file GC cycle", failure);
                }
            }, 0, intervalMs, TimeUnit.MILLISECONDS);
            running = true;
            log.info("File GC started (interval: {}ms, mode: {})", intervalMs, service.currentMode());
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("Cannot start file GC", failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        running = false;
        if (scheduler != null) {
            try {
                if (!scheduler.awaitTermination(35, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("File GC did not stop within the bounded timeout");
                }
                scheduler = null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the file GC");
            }
        }
        log.info("File GC stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 80;
    }
}
