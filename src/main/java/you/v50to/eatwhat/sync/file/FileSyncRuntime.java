package you.v50to.eatwhat.sync.file;

import org.springframework.context.SmartLifecycle;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

/** Both installations publish committed facts; the existing CAMPUS transport owns all network initiation. */
public final class FileSyncRuntime implements SmartLifecycle {
    private final FileSyncWorker worker;
    private final FileSyncProperties settings;
    private ScheduledExecutorService executor;
    public FileSyncRuntime(FileSyncWorker worker,FileSyncProperties settings) { this.worker=worker; this.settings=settings; }
    @Override public synchronized void start() {
        if(isRunning()) return;
        executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"file-event-publisher");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(()->{
            try { worker.runOnce(); }
            catch(RuntimeException failure) { LoggerFactory.getLogger(FileSyncRuntime.class).warn("File event publication failed; committed markers retained for retry ({})",failure.getClass().getSimpleName()); }
        },0,settings.getPollInterval().toMillis(),TimeUnit.MILLISECONDS);
    }
    @Override public synchronized void stop() {
        if(executor==null) return;
        executor.shutdownNow();
        try { if(!executor.awaitTermination(35,TimeUnit.SECONDS)) throw new IllegalStateException("File event publisher did not stop"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("File publisher stop interrupted"); }
        executor=null;
    }
    @Override public synchronized boolean isRunning() { return executor!=null&&!executor.isShutdown(); }
    @Override public int getPhase() { return Integer.MAX_VALUE-120; }
}
