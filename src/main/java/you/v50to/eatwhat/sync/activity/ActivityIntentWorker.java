package you.v50to.eatwhat.sync.activity;

import org.springframework.context.SmartLifecycle;
import java.util.concurrent.*;

/** Local durable capture/backfill progresses without a control-plane connection. */
public final class ActivityIntentWorker implements SmartLifecycle {
    private final ActivityDurableCommands commands;private ScheduledExecutorService executor;
    public ActivityIntentWorker(ActivityDurableCommands commands){this.commands=commands;}
    @Override public synchronized void start(){if(executor!=null)return;
        executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"activity-intent-publisher");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(()->{try{commands.runOnce();}catch(RuntimeException retry){/* Durable metadata remains for retry; no content is logged. */}},1,5,TimeUnit.SECONDS);
    }
    @Override public synchronized void stop(){if(executor==null)return;executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}executor=null;}
    @Override public boolean isRunning(){return executor!=null&&!executor.isShutdown();}
}
