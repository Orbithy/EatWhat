package you.v50to.eatwhat.control;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Serial, fixed local verification commands. No arbitrary shell, environment, URL or Maven argument input. */
public final class ControlRuns implements AutoCloseable {
    private final ControlProperties config;
    private final ControlAudit audit;
    private final ControlService state;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<UUID,Map<String,Object>> runs=new LinkedHashMap<>();
    private final AtomicReference<UUID> active=new AtomicReference<>();
    private volatile Process process;
    private volatile List<ProcessHandle> stopping=List.of();
    private boolean closed;
    public ControlRuns(ControlProperties config,ControlAudit audit,ControlService state) {this.config=config;this.audit=audit;this.state=state;}
    public List<Map<String,Object>> suites() {return List.of(
            Map.of("id","business","scope","LOCAL_BUSINESS_RUSTFS","enabled",configured()),
            Map.of("id","coordinated-gc","scope","LOCAL_DUAL_SITE_PROTOCOL","enabled",configured()),
            Map.of("id","full-verify","scope","LOCAL_CLEAN_VERIFY","enabled",configured()));}
    private boolean configured(){return "isolated-acceptance".equals(config.getEnvironmentKind()) && config.getRepository()!=null && config.getJavaHome()!=null;}
    List<String> command(String suite) {
        if(!Set.of("business","coordinated-gc","full-verify").contains(suite)) throw new ControlFailure(400,"SUITE_NOT_ALLOWED");
        if(!configured() || !config.getRepository().isAbsolute() || !config.getJavaHome().isAbsolute()
                || !Files.isExecutable(config.getRepository().resolve("mvnw")) || !Files.isExecutable(config.getJavaHome().resolve("bin/java"))) throw new ControlFailure(409,"TEST_RUNNER_NOT_CONFIGURED");
        var command=new ArrayList<>(List.of(config.getRepository().resolve("mvnw").toString(),"-o","-B","-Pintegration","-DforkCount=2"));
        if(suite.equals("full-verify"))command.add("clean");
        else {command.add("-Dtest=Control*Test");command.add("-Dit.test="+(suite.equals("business")?"LocalBusinessFilesIT,FileReferenceReleaseBusinessIT":"FileCoordinatedGcIT,FileEventChannelIT"));}
        command.add("verify");return command;
    }
    public synchronized Map<String,Object> start(String actor,String suite) {
        if(closed)throw new ControlFailure(503,"TEST_RUNNER_CLOSED");
        var command=command(suite);UUID id=UUID.randomUUID();
        if(!active.compareAndSet(null,id))throw new ControlFailure(409,"TEST_RUN_ALREADY_ACTIVE");
        try {
            audit.append(id,actor,"TEST_RUN",suite,"ACCEPTED");
            var run=new LinkedHashMap<String,Object>();run.put("id",id.toString());run.put("suite",suite);run.put("scope",suites().stream().filter(x->x.get("id").equals(suite)).findFirst().orElseThrow().get("scope"));
            run.put("state","QUEUED");run.put("createdAt",Instant.now().toString());run.put("deploymentPerformed",false);run.put("remoteAcceptance","NOT_RUN");
            runs.put(id,run);state.save("run-"+id,run);worker.submit(()->execute(id,actor,command));return Map.copyOf(run);
        } catch(RuntimeException e){active.compareAndSet(id,null);throw e;}
    }
    private void execute(UUID id,String actor,List<String> command) {
        int exit=-1;
        try {
            synchronized(this) {
                if(closed){runs.get(id).put("state","INTERRUPTED");runs.get(id).put("reason","RUNNER_CLOSED");state.save("run-"+id,runs.get(id));return;}
                if("CANCELLED".equals(runs.get(id).get("state")))return;
                var log=audit.directory().resolve("run-"+id+".log");Files.createFile(log,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                var builder=new ProcessBuilder(command).directory(config.getRepository().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
                builder.environment().put("JAVA_HOME",config.getJavaHome().toString());
                process=builder.start();runs.get(id).put("state","RUNNING");runs.get(id).put("startedAt",Instant.now().toString());state.save("run-"+id,runs.get(id));
            }
            exit=process.waitFor();
            synchronized(this) {
                var run=runs.get(id);
                if(!"CANCELLED".equals(run.get("state")))run.put("state",exit==0?"COMMAND_SUCCEEDED":"FAILED");
                // No green E2E claim: the command status and independent report review remain separate.
                run.put("exitCode",exit);run.put("reportStatus","REVIEW_REQUIRED");run.put("completedAt",Instant.now().toString());state.save("run-"+id,run);
            }
            audit.append(id,actor,"TEST_RUN",(String)runs.get(id).get("suite"),exit==0?"COMMAND_SUCCEEDED":"FAILED");
        } catch(Exception e) {
            synchronized(this){if(!"CANCELLED".equals(runs.get(id).get("state"))){runs.get(id).put("state","FAILED");runs.get(id).put("reason","RUNNER_FAILED");}try{state.save("run-"+id,runs.get(id));}catch(Exception ignored){}}
        } finally {
            boolean stopped=awaitOwnedProcesses();process=null;
            if(stopped)active.compareAndSet(id,null);
            else synchronized(this){runs.get(id).put("reason","OWNED_PROCESS_EXIT_NOT_CONFIRMED");try{state.save("run-"+id,runs.get(id));}catch(Exception ignored){}}
        }
    }
    public synchronized List<Map<String,Object>> list(){
        var ids=new LinkedHashSet<>(runs.keySet());
        try(var files=Files.list(audit.directory())){files.map(p->p.getFileName().toString()).filter(n->n.matches("run-[0-9a-f-]{36}\\.json"))
                .limit(1000).forEach(n->{try{ids.add(UUID.fromString(n.substring(4,n.length()-5)));}catch(IllegalArgumentException ignored){}});}
        catch(Exception e){throw new ControlFailure(503,"CONTROL_STATE_UNAVAILABLE");}
        return ids.stream().map(this::get).sorted(Comparator.comparing(r->Objects.toString(r.get("createdAt"),""))).toList();
    }
    public synchronized Map<String,Object> get(UUID id){var run=runs.get(id);if(run!=null)return Map.copyOf(run);
        try {Map<String,Object> previous=tools.jackson.databind.json.JsonMapper.builder().build().readValue(Files.readString(audit.directory().resolve("run-"+id+".json")),Map.class);
            if(Set.of("QUEUED","RUNNING").contains(previous.get("state"))){previous.put("state","INTERRUPTED");previous.put("reason","RUNNER_PROCESS_NOT_OWNED_AFTER_RESTART");}
            return previous;}
        catch(Exception e){throw new ControlFailure(404,"RUN_NOT_FOUND");}}
    public synchronized Map<String,Object> cancel(String actor,UUID id) {
        if(!id.equals(active.get()))throw new ControlFailure(409,"RUN_NOT_ACTIVE");
        audit.append(id,actor,"TEST_CANCEL",id.toString(),"REQUESTED");
        runs.get(id).put("state","CANCELLED");stopProcess();state.save("run-"+id,runs.get(id));return Map.copyOf(runs.get(id));
    }
    private void stopProcess(){Process p=process;if(p!=null){var children=new ArrayList<>(p.descendants().toList());children.add(p.toHandle());stopping=List.copyOf(children);children.forEach(ProcessHandle::destroy);
        CompletableFuture.delayedExecutor(5,TimeUnit.SECONDS).execute(()->children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly));}}
    private boolean awaitOwnedProcesses(){
        var owned=stopping;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(owned.stream().anyMatch(ProcessHandle::isAlive)&&System.nanoTime()<deadline){try{Thread.sleep(50);}catch(InterruptedException e){owned.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);Thread.currentThread().interrupt();break;}}
        boolean exited=owned.stream().noneMatch(ProcessHandle::isAlive);if(exited)stopping=List.of();return exited;
    }
    @Override public void close(){
        synchronized(this){closed=true;stopProcess();worker.shutdown();}
        stopping.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        // Finish the owned worker's final state write before its private directory can be removed.
        // An immediate shutdownNow can interrupt audit fsync after COMMAND_SUCCEEDED was persisted.
        try{if(!worker.awaitTermination(8,TimeUnit.SECONDS)){worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}}
        catch(InterruptedException e){worker.shutdownNow();Thread.currentThread().interrupt();}
    }
}
