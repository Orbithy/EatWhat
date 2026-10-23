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
 * Schedules the report-only FileInventoryService on a fixed delay (LOCAL, then PEER) on one daemon thread.
 * Enabled only when app.storage.files.gc.inventory-enabled=true. Never deletes anything.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.files.gc.inventory-enabled", havingValue = "true")
public class FileInventoryRunner implements SmartLifecycle {
    private final FileInventoryService service;
    private final FileGcProperties props;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public FileInventoryRunner(FileInventoryService service, FileGcProperties props) {
        this.service = service;
        this.props = props;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        try {
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "file-inventory");
                thread.setDaemon(true);
                return thread;
            });
            long intervalMs = props.getInterval().toMillis();
            scheduler.scheduleWithFixedDelay(() -> {
                for (String site : new String[]{FileInventoryService.LOCAL, FileInventoryService.PEER}) {
                    try {
                        service.runInventory(site);
                    } catch (Throwable failure) {
                        // Catch all Throwables so the schedule never dies; the next cycle retries.
                        log.error("Error in file inventory cycle for site {}", site, failure);
                    }
                }
            }, 0, intervalMs, TimeUnit.MILLISECONDS);
            running = true;
            log.info("File inventory started (interval: {}ms)", intervalMs);
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("Cannot start file inventory", failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        running = false;
        if (scheduler != null) {
            try {
                if (!scheduler.awaitTermination(35, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("File inventory did not stop within the bounded timeout");
                }
                scheduler = null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping the file inventory");
            }
        }
        log.info("File inventory stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 90;
    }
}
