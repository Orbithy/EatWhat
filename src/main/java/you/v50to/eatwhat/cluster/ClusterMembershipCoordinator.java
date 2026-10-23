package you.v50to.eatwhat.cluster;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static you.v50to.eatwhat.cluster.ClusterStore.*;

/** Resumable all-participant prepare -> durable decision -> all apply -> release; never resets missing votes. */
public final class ClusterMembershipCoordinator {
    public record Plan(UUID clusterId,UUID changeId,long baseEpoch,long nextEpoch,String kind,String siteId,List<ClusterMember> participants,List<ClusterMember> nextMembers){
        public Plan{participants=List.copyOf(participants);nextMembers=List.copyOf(nextMembers);}
    }
    public interface Nodes {
        String fingerprint(ClusterProfiles.Entry profile);
        void stage(ClusterProfiles.Entry profile,Plan plan);
        void fence(ClusterProfiles.Entry profile,Plan plan);
        void synchronize(Plan plan,List<ClusterProfiles.Entry> participants);
        String prepared(ClusterProfiles.Entry profile,Plan plan);
        String apply(ClusterProfiles.Entry profile,Plan plan);
        String release(ClusterProfiles.Entry profile,Plan plan);
    }
    /** A composite task checks its original approval at every effect. SQL
     * callbacks are synchronous and may be enclosed in a short authority
     * transaction; remote callbacks must run after that transaction commits. */
    public interface Boundary {
        void check(String operation);
        void write(String operation,Runnable effect);
        default void remote(String operation,Runnable effect){check(operation);effect.run();}
    }
    private static final Boundary ORDINARY=new Boundary(){
        public void check(String operation){}
        public void write(String operation,Runnable effect){effect.run();}
    };
    private final UUID clusterId;private final ClusterStore store;private final ClusterProfiles profiles;private final Nodes nodes;
    public ClusterMembershipCoordinator(UUID clusterId,ClusterStore store,ClusterProfiles profiles,Nodes nodes){this.clusterId=clusterId;this.store=store;this.profiles=profiles;this.nodes=nodes;}
    public Map<String,Object> register(String actor,UUID id,long epoch,String profile){return store.register(actor,id,epoch,profile,profiles.get(profile).member());}
    public Map<String,Object> registerExpected(String actor,UUID id,long epoch,String profile,ClusterMember expected){
        if(!profiles.get(profile).member().equals(expected))throw failure("MEMBER_PROFILE_IDENTITY_CHANGED");
        return store.register(actor,id,epoch,profile,expected);
    }
    public Map<String,Object> begin(String actor,UUID id,long epoch,String kind,String site){
        try{var prior=store.change(id);if(((Number)prior.get("base_epoch")).longValue()!=epoch||!kind.equals(prior.get("kind"))||!site.equals(prior.get("site_id")))throw failure("IDEMPOTENCY_CONFLICT");return prior;}catch(ClusterFailure missing){if(missing.status()!=404)throw missing;}
        var profile=profiles.get(store.profile(site));String fp=remote(ORDINARY,"FINGERPRINT",()->nodes.fingerprint(profile));return store.begin(actor,id,epoch,kind,site,fp);
    }
    /** BEGIN for an already sealed bootstrap identity and database. A changed
     * probe never becomes a replacement approval, including on retry. */
    public Map<String,Object> beginJoinExpected(String actor,UUID id,long epoch,ClusterMember member,String fingerprint,Boundary boundary){
        Objects.requireNonNull(boundary);boundary.check("BEGIN_OBSERVE");
        var prior=store.joinedCommand(actor,id,epoch,member,fingerprint);if(prior.isPresent())return prior.get();
        var entry=profiles.get(store.profile(member.siteId()));
        if(!entry.member().equals(member))throw failure("MEMBER_PROFILE_IDENTITY_CHANGED");
        String observed=remote(boundary,"FINGERPRINT",()->nodes.fingerprint(entry));
        if(!Objects.equals(fingerprint,observed))throw failure("DATABASE_IDENTITY_CHANGED");
        return effect(boundary,"BEGIN",false,()->{
            // Catalog reload and the short fence are independent operations.
            // Recheck identity immediately before persisting the decision.
            if(!profiles.get(store.profile(member.siteId())).member().equals(member))throw failure("MEMBER_PROFILE_IDENTITY_CHANGED");
            return store.begin(actor,id,epoch,"JOIN",member.siteId(),fingerprint);
        });
    }
    public Map<String,Object> beginOwnedJoinExpected(UUID bootstrapId,String binding,String actor,UUID id,long epoch,ClusterMember member,String fingerprint,Boundary boundary){
        return store.ownedJoin(bootstrapId,binding,id,()->{
            store.ownedJoinedCommand(bootstrapId,binding,actor,id,epoch,member,fingerprint);
            return beginJoinExpected(actor,id,epoch,member,fingerprint,boundary);
        });
    }
    public Map<String,Object> reconcileOwnedJoinExpected(UUID bootstrapId,String binding,String actor,UUID id,long epoch,ClusterMember member,String fingerprint,Boundary boundary){
        return store.ownedJoin(bootstrapId,binding,id,()->{
            if(store.ownedJoinedCommand(bootstrapId,binding,actor,id,epoch,member,fingerprint).isEmpty())throw failure("JOIN_COMMAND_REQUIRED");
            return reconcile(actor,id,boundary);
        });
    }
    public Plan plan(UUID id){var c=store.change(id);var participants=store.participants(id);var next=new ArrayList<>(participants);if(c.get("kind").equals("RETIRE"))next.removeIf(m->m.siteId().equals(c.get("site_id")));return new Plan(clusterId,id,((Number)c.get("base_epoch")).longValue(),((Number)c.get("next_epoch")).longValue(),(String)c.get("kind"),(String)c.get("site_id"),participants,next);}
    public Map<String,Object> reconcileJoinExpected(String actor,UUID id,long epoch,ClusterMember member,String fingerprint,Boundary boundary){
        if(store.joinedCommand(actor,id,epoch,member,fingerprint).isEmpty())throw failure("JOIN_COMMAND_REQUIRED");
        return reconcile(actor,id,boundary);
    }
    public Map<String,Object> reconcile(String actor,UUID id){return reconcile(actor,id,ORDINARY);}
    public Map<String,Object> reconcile(String actor,UUID id,Boundary boundary){
        Objects.requireNonNull(boundary);boundary.check("RECONCILE_OBSERVE");
        store.requireChangeOwner(id);
        if(store.change(id).get("state").equals("APPLIED"))return store.change(id);
        Plan p=plan(id);var entries=p.participants().stream().map(m->profiles.get(store.profile(m.siteId()))).toList();String state=(String)store.change(id).get("state");
        for(int i=0;i<entries.size();i++)if(!entries.get(i).member().equals(p.participants().get(i)))throw failure("MEMBER_PROFILE_IDENTITY_CHANGED");
        if(state.equals("PREPARING")){
            for(var e:entries)remote(boundary,"STAGE:"+e.id(),()->{nodes.stage(e,p);return null;});
            remote(boundary,"SYNCHRONIZE",()->{nodes.synchronize(p,entries);return null;});
            // Drain secondary registrations before freezing the authority that must archive them.
            for(var e:entries.stream().sorted(Comparator.comparingInt((ClusterProfiles.Entry e)->p.kind().equals("RETIRE")&&e.member().siteId().equals(p.siteId())?2048:e.member().allocationSlot()).reversed()).toList())remote(boundary,"FENCE:"+e.id(),()->{nodes.fence(e,p);return null;});
            remote(boundary,"SYNCHRONIZE",()->{nodes.synchronize(p,entries);return null;});
            for(var e:entries){String proof=remote(boundary,"PREPARED:"+e.id(),()->nodes.prepared(e,p));effect(boundary,"VOTE_PREPARED:"+e.id(),false,()->{store.vote(id,e.member(),"PREPARED",proof);return null;});}
            effect(boundary,"DECIDE",false,()->store.decide(actor,id));state="COMMITTED";
        }
        if(state.equals("COMMITTED")){
            for(var e:entries){String proof=remote(boundary,"APPLY:"+e.id(),()->nodes.apply(e,p));effect(boundary,"VOTE_APPLIED:"+e.id(),false,()->{store.vote(id,e.member(),"APPLIED",proof);return null;});}
            effect(boundary,"FINISH",false,()->store.finish(actor,id));state="INSTALLED";
        }
        // Safe after a crash at any release: every node has already durably installed the same epoch.
        if(state.equals("INSTALLED")){for(var e:entries){String proof=remote(boundary,"RELEASE:"+e.id(),()->nodes.release(e,p));effect(boundary,"VOTE_RELEASED:"+e.id(),false,()->{store.vote(id,e.member(),"RELEASED",proof);return null;});}effect(boundary,"RELEASED",false,()->store.released(actor,id));}return store.change(id);
    }
    private <T>T remote(Boundary boundary,String operation,Supplier<T> request){return effect(boundary,operation,true,()->boundary==ORDINARY?request.get():store.remoteProtocol(boundary,request));}
    static <T>T fenced(Boundary boundary,String operation,Supplier<T> work){return effect(boundary,operation,false,work);}
    private static <T>T effect(Boundary boundary,String operation,boolean remote,Supplier<T> work){
        if(remote)noRemoteTransaction();
        Thread owner=Thread.currentThread();var open=new AtomicBoolean(true);var invoked=new AtomicBoolean();var invalid=new AtomicBoolean();var result=new AtomicReference<T>();var failure=new AtomicReference<Throwable>();
        Runnable effect=()->{
            if(Thread.currentThread()!=owner||!open.get()||!invoked.compareAndSet(false,true)){invalid.set(true);throw ClusterStore.failure("MEMBERSHIP_BOUNDARY_INVALID");}
            try{if(remote)noRemoteTransaction();result.set(work.get());}catch(RuntimeException|Error error){failure.set(error);throw error;}
        };
        try{if(remote)boundary.remote(operation,effect);else boundary.write(operation,effect);}finally{open.set(false);}
        if(invalid.get()||!invoked.get())throw ClusterStore.failure("MEMBERSHIP_BOUNDARY_INVALID");
        if(failure.get() instanceof RuntimeException error)throw error;
        if(failure.get() instanceof Error error)throw error;
        return result.get();
    }
    private static void noRemoteTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw failure("MEMBERSHIP_REMOTE_TRANSACTION_FORBIDDEN");}
}
