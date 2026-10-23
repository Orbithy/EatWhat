package you.v50to.eatwhat.sync.notification;

import org.springframework.context.SmartLifecycle;
import java.util.concurrent.*;

/** Local durable capture/backfill progresses without a control-plane connection. */
public final class NotificationHistoryWorker implements SmartLifecycle {
    private final NotificationHistoryCommands commands;private ScheduledExecutorService executor;
    public NotificationHistoryWorker(NotificationHistoryCommands commands){this.commands=commands;}
    @Override public synchronized void start(){if(executor!=null)return;
        executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"notification-history-publisher");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(()->{try{commands.runOnce();}catch(RuntimeException retry){/* Durable metadata remains for retry; no content is logged. */}},1,5,TimeUnit.SECONDS);
    }
    @Override public synchronized void stop(){if(executor==null)return;executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}executor=null;}
    @Override public boolean isRunning(){return executor!=null&&!executor.isShutdown();}
}
