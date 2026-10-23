package you.v50to.eatwhat.cluster;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.AccountSyncWorker;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.file.*;
import you.v50to.eatwhat.sync.transport.*;
import java.time.Duration;
import java.util.*;
import static you.v50to.eatwhat.cluster.ClusterStore.*;

/** Resumable per-recipient delivery, using the existing committed Inbox, lease fencing and domain handlers. */
public final class ClusterReplayService implements ClusterNodeAgent.DataPlane {
    public record Target(String targetSite,Map<String,Set<Integer>> targetSchemas){
        public Target(String targetSite){this(targetSite,null);}
        public Target{name(targetSite);targetSchemas=targetSchemas==null?EventSchemas.legacy():EventSchemas.copy(targetSchemas);}
    }
    public record Push(NodeIdentity source,List<TransportProtocol.EventItem> events){public Push{events=List.copyOf(events);if(events.size()>TransportProtocol.MAX_BATCH)throw new ClusterFailure(400,"CLUSTER_BATCH_TOO_LARGE");}}
    public record Settle(String targetSite,List<TransportProtocol.Ack> acks,List<TransportProtocol.EventItem> withheld){public Settle{name(targetSite);acks=List.copyOf(acks);withheld=List.copyOf(withheld);if(acks.size()+withheld.size()>TransportProtocol.MAX_BATCH)throw new ClusterFailure(400,"CLUSTER_BATCH_TOO_LARGE");}}
    private final JdbcTemplate jdbc;private final ClusterTransactions tx;private final ClusterMember local;private final ClusterMembershipView membership;
    private final DeliveryService delivery;private final InboxService inbox;private final EventCodec codec;private final EventPolicy policy;private final InboxWorker worker;
    private final ObjectProvider<FileSyncWorker> files;private final ObjectProvider<AccountSyncWorker> accounts;
    private boolean singleSiteOnly;
    public ClusterReplayService singleSiteOnly(boolean value){singleSiteOnly=value;return this;}
    public boolean singleSiteOnly(){return singleSiteOnly;}
    @Override public String mode(){return singleSiteOnly?"SINGLE_SITE":"MULTI_SITE";}
    @Override public Map<String,Set<Integer>> eventSchemas(){return policy.supportedSchemaVersions();}
    private void singlePlan(ClusterMembershipCoordinator.Plan plan){if(!singleSiteOnly||!plan.participants().equals(List.of(local))||!plan.nextMembers().equals(List.of(local))||!plan.kind().equals("JOIN")||!plan.siteId().equals(local.siteId()))throw failure("SINGLE_SITE_CLUSTER_ONLY");}
    /** A one-node membership has no remote accounts or events to project. The actual staged cursor is still verified. */
    public void synchronizeSingleSite(ClusterMembershipCoordinator.Plan plan,List<ClusterProfiles.Entry> nodes){
        singlePlan(plan);if(nodes.size()!=1||!nodes.getFirst().member().equals(local))throw failure("SINGLE_SITE_CLUSTER_ONLY");
        saved(plan);seed(plan);tx.required(()->{jdbc.update("UPDATE eatwhat_cluster.replay_cursor SET account_complete=true WHERE change_id=? AND source_site=? AND complete",plan.changeId(),local.siteId());return null;});
        verifyCatchup(plan,local);
    }
    public ClusterReplayService(JdbcTemplate jdbc,SyncTransactions tx,ClusterMember local,DeliveryService delivery,InboxService inbox,EventCodec codec,List<EventPolicy> policies,ObjectProvider<FileSyncWorker> files,ObjectProvider<AccountSyncWorker> accounts){
        this.jdbc=jdbc;this.tx=new ClusterTransactions(tx);this.local=local;this.membership=new ClusterMembershipView(jdbc);this.delivery=delivery;this.inbox=inbox;this.codec=codec;this.policy=EventPolicy.compose(policies);this.files=files;this.accounts=accounts;this.worker=new InboxWorker(inbox,Duration.ofSeconds(30),Duration.ofMillis(100),new TransportMetrics());
    }
    @Override public boolean ready(){if(singleSiteOnly)return local.allocationSlot()==0&&jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.member WHERE site_id<>?",Long.class,local.siteId())==0;return policy.aggregateTypes().containsAll(Set.of("activity_foods","activity_dinners","activity_posts","relation.operation","profile.operation","privacy.operation","notification.read.operation","catalog.operation",FileEvents.METADATA,FileEvents.REFERENCES,FileEvents.RECEIPT,FileEvents.REPLICA));}
    private ClusterMember target(String site){return membership.participants().stream().filter(m->m.siteId().equals(site)&&!m.equals(local)).findFirst().orElseThrow(()->failure("TARGET_NOT_A_MEMBER"));}
    private void saved(ClusterMembershipCoordinator.Plan plan){var rows=jdbc.queryForList("SELECT plan_hash FROM eatwhat_cluster.local_change WHERE change_id=?",plan.changeId());if(rows.size()!=1||!hash(plan).equals(rows.getFirst().get("plan_hash")))throw failure("MEMBERSHIP_PLAN_CHANGED");}
    public Map<String,Object> seed(ClusterMembershipCoordinator.Plan plan){saved(plan);return tx.required(()->{
        var rows=jdbc.queryForList("SELECT * FROM eatwhat_cluster.replay_cursor WHERE change_id=? AND source_site=? FOR UPDATE",plan.changeId(),local.siteId());if(rows.size()!=1)throw failure("SOURCE_CUTOFF_MISSING");var row=rows.getFirst();long cutoff=((Number)row.get("cutoff")).longValue(),last=((Number)row.get("last_order")).longValue();
        if(plan.kind().equals("JOIN")&&!plan.siteId().equals(local.siteId())){
            var page=jdbc.queryForList("SELECT event_id,local_order,aggregate_type FROM eatwhat_sync.outbox WHERE local_order>? AND local_order<=? ORDER BY local_order LIMIT 1000",last,cutoff);
            for(var event:page){if(!Set.of(FileEvents.RECEIPT,FileGcEvents.TYPE).contains(event.get("aggregate_type")))jdbc.update("INSERT INTO eatwhat_sync.delivery(event_id,target_site) VALUES(?,?) ON CONFLICT DO NOTHING",event.get("event_id"),plan.siteId());last=((Number)event.get("local_order")).longValue();}
            if(page.size()<1000)last=cutoff;
        }else last=cutoff;
        jdbc.update("UPDATE eatwhat_cluster.replay_cursor SET last_order=?,complete=? WHERE change_id=? AND source_site=?",last,last==cutoff,plan.changeId(),local.siteId());return Map.of("seeded",last==cutoff,"cutoff",cutoff,"lastOrder",last);
    });}
    public List<TransportProtocol.EventItem> pull(String recipient){return pull(new Target(recipient));}
    public List<TransportProtocol.EventItem> pull(Target requested){var target=target(requested.targetSite());return ClusterBarrier.internal(()->{
        var leases=delivery.claimForAggregates(target.siteId(),TransportProtocol.MAX_BATCH,Duration.ofSeconds(60),policy.aggregateTypes());var result=new ArrayList<TransportProtocol.EventItem>();
        for(var lease:leases){
            var event=lease.event();try{
                policy.validate(event,local.identity());
                if(!EventSchemas.supports(requested.targetSchemas(),event.aggregateType(),event.schemaVersion())){
                    delivery.retry(lease,Duration.ofSeconds(30),"RECIPIENT_READER_UPGRADE_REQUIRED");continue;
                }
                if(event.aggregateType().equals(FileGcEvents.TYPE)){delivery.quarantine(lease,"CLUSTER_LEGACY_GC_EXCLUDED");continue;}
                var outbound=policy.outbound(event);if(outbound==EventPolicy.OutboundDecision.WAIT){delivery.retry(lease,Duration.ofSeconds(1),"CONSENT_NOT_READY");continue;}
                if(outbound==EventPolicy.OutboundDecision.POLICY_BLOCKED){delivery.quarantine(lease,"CONSENT_EPOCH_BLOCKED");continue;}
                if(FileEvents.METADATA.equals(event.aggregateType())){var m=FileEvents.metadata(event.payloadJson());if(FileGcProtocol.deletionFrozen(jdbc,m.file().fileId(),m.file().revision())){delivery.quarantine(lease,"FILE_ALREADY_FROZEN");continue;}}
                var encoded=codec.encode(event);result.add(new TransportProtocol.EventItem(event.eventId(),encoded.hash(),lease.leaseToken(),encoded.json()));
            }catch(IllegalArgumentException invalid){delivery.quarantine(lease,"EVENT_POLICY_REJECTED");}
        }
        return result;
    });}
    public List<TransportProtocol.Ack> push(Push input){
        if(membership.participants().stream().noneMatch(m->m.identity().equals(input.source()))||input.source().equals(local.identity()))throw new ClusterFailure(403,"EVENT_ORIGIN_NOT_A_MEMBER");
        return ClusterBarrier.internal(()->{
            var endpoint=new NodeEndpoint(local.identity(),input.source(),inbox,delivery,codec,Duration.ofSeconds(60),Duration.ofSeconds(1),policy);
            var request=new TransportProtocol.Request(new TransportProtocol.Header(1,UUID.randomUUID(),input.source(),local.identity()),input.events(),List.of(),0);
            var response=endpoint.handle(TransportProtocol.PREFIX+"/push",request);worker.runOnce();return response.acks();
        });
    }
    public Map<String,Object> settle(Settle input){target(input.targetSite());return ClusterBarrier.internal(()->{
        for(var ack:input.acks())delivery.settleRemote(ack.eventId(),input.targetSite(),ack.leaseToken(),ack.envelopeHash(),ack.status(),Duration.ofSeconds(1));
        tx.required(()->{for(var event:input.withheld()){
            jdbc.query("SELECT 1 FROM eatwhat_sync.delivery WHERE event_id=? AND target_site=? FOR UPDATE",rs->{},event.eventId(),input.targetSite());
            jdbc.update("UPDATE eatwhat_sync.delivery d SET status='QUARANTINED',lease_token=NULL,lease_until=NULL,last_error='RECIPIENT_CONSENT_REQUIRED' FROM eatwhat_sync.outbox o WHERE o.event_id=d.event_id AND d.event_id=? AND d.target_site=? AND d.status='LEASED' AND d.lease_token=? AND d.lease_until>clock_timestamp() AND o.envelope_hash=?",event.eventId(),input.targetSite(),event.leaseToken(),event.envelopeHash());
        }return null;});return Map.of("processed",input.acks().size()+input.withheld().size());
    });}
    public Map<String,Object> flush(){return ClusterBarrier.internal(()->{var account=accounts.getIfAvailable();if(account!=null)account.runOnce();worker.runOnce();var file=files.getIfAvailable();if(file!=null)file.runOnce();return status();});}
    public Map<String,Object> reseed(String recipient){target(recipient);return tx.required(()->{int count=jdbc.update("UPDATE eatwhat_sync.delivery SET status='READY',available_at=clock_timestamp(),last_error=NULL WHERE target_site=? AND status='QUARANTINED' AND last_error='RECIPIENT_CONSENT_REQUIRED'",recipient);return Map.of("requeued",count);});}
    public Map<String,Object> status(){
        long pending=jdbc.queryForObject("SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED' AND NOT(status='QUARANTINED' AND last_error IN ('RECIPIENT_CONSENT_REQUIRED','CONSENT_EPOCH_BLOCKED','CLUSTER_LEGACY_GC_EXCLUDED','FILE_ALREADY_FROZEN'))",Long.class);
        return Map.of("pendingDeliveries",pending,"pendingInbox",jdbc.queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE status<>'APPLIED'",Long.class),"fileTransfers",jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE site='LOCAL' AND state='PENDING_SYNC'",Long.class));
    }
    @Override public void verifyCatchup(ClusterMembershipCoordinator.Plan plan,ClusterMember member){if(singleSiteOnly)singlePlan(plan);saved(plan);var cursors=jdbc.queryForList("SELECT complete,account_complete FROM eatwhat_cluster.replay_cursor WHERE change_id=? AND source_site=?",plan.changeId(),local.siteId());if(cursors.size()!=1||!Boolean.TRUE.equals(cursors.getFirst().get("complete"))||!Boolean.TRUE.equals(cursors.getFirst().get("account_complete")))throw failure("INITIAL_SEED_PENDING");
        var state=status();if(((Number)state.get("pendingDeliveries")).longValue()>0||((Number)state.get("pendingInbox")).longValue()>0||((Number)state.get("fileTransfers")).longValue()>0)throw failure("INITIAL_SYNC_PENDING");
    }
    EventPolicy policy(){return policy;}
}
