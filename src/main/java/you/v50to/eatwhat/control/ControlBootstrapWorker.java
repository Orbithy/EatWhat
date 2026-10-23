package you.v50to.eatwhat.control;

import org.springframework.context.SmartLifecycle;
import you.v50to.eatwhat.cluster.ClusterControlLease;
import you.v50to.eatwhat.cluster.ClusterFailure;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent single worker. The shared execution lock is separate from the
 * short journal/intent lock; no SSH or transfer is performed inside a DB tx.
 * A restart/new lease needs explicit retained recovery, never silent adoption. */
public final class ControlBootstrapWorker implements SmartLifecycle {
    private final ControlServerStore store;private final ControlBootstrapJournal journal;
    private final ClusterControlLease lease;private final ControlBootstrapWorkflow workflow;
    private final AtomicBoolean cycling=new AtomicBoolean(),active=new AtomicBoolean();private ScheduledExecutorService worker;
    public ControlBootstrapWorker(ControlServerStore store,ControlBootstrapJournal journal,ClusterControlLease lease,
            ControlBootstrapWorkflow workflow,DeploymentExecutor deployment){
        this.store=store;this.journal=journal;this.lease=lease;this.workflow=workflow;
        deployment.addIdleGuard(()->active.get()?"CONTROLLER_BOOTSTRAP_RUNNING":journal.busyReason());
    }
    void cycle(){
        if(!cycling.compareAndSet(false,true))return;
        try{
            lease.requireHeld();
            var path=store.directory().resolve("controller-bootstrap-execution.lock");
            try{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            ControlServerStore.privateFile(path,1024);
            try(var channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileLock held;try{held=channel.tryLock();}catch(OverlappingFileLockException busy){return;}
                if(held==null)return;
                try(held){var pending=journal.pending();if(pending.isEmpty())return;active.set(true);for(var id:pending){
                    lease.requireHeld();
                    ControlBootstrapAdmission.Frozen f;
                    try{f=journal.originalAdmission(id,false);}catch(ControlFailure historical){continue;}
                    var status=lease.status();
                    if(!Boolean.TRUE.equals(status.get("localLeader")))return;
                    if(!lease.controllerId().equals(f.holderId())||!(status.get("generation") instanceof Number generation)||generation.longValue()!=f.generation()){
                        journal.executionObservation(id,"BOOTSTRAP_ORIGINAL_CONTROLLER_REQUIRED",true);continue;
                    }
                    if(Boolean.TRUE.equals(journal.job(id).get("requiresReapproval")))continue;
                    try{workflow.advance(id);journal.executionObservation(id,null,false);}
                    catch(ControlFailure failure){journal.executionObservation(id,failure.code,false);}
                    catch(ClusterFailure failure){journal.executionObservation(id,"BOOTSTRAP_AUTHORITY_UNAVAILABLE",false);}
                    catch(Exception failure){journal.executionObservation(id,"BOOTSTRAP_EXECUTION_OBSERVATION_UNAVAILABLE",false);}
                }}
            }
        }catch(Exception unavailable){/* Preserve original claims; next cycle may observe only those claims. */}
        finally{active.set(false);cycling.set(false);}
    }
    public synchronized void start(){if(worker!=null)return;worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"control-controller-bootstrap");t.setDaemon(true);return t;});worker.scheduleWithFixedDelay(this::cycle,4,2,TimeUnit.SECONDS);}
    public synchronized void stop(){if(worker==null)return;worker.shutdown();try{if(!worker.awaitTermination(2,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException interrupted){worker.shutdownNow();Thread.currentThread().interrupt();}worker=null;}
    public synchronized boolean isRunning(){return worker!=null&&!worker.isShutdown();}
    public int getPhase(){return 1100;}
}
