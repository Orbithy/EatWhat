package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;

/** Bounded resumable rounds. Account projections precede events; every remote ACK is correlated to its lease. */
public final class ClusterMeshSynchronizer implements ClusterHttpNodes.Synchronizer {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private you.v50to.eatwhat.account.multiwriter.MultiwriterExchange multiwriter;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final ConsentStore accounts;private final ClusterAccountChannel accountChannel;
    private final ClusterEventRecipients recipients;private final ClusterRecipientConsent grants;private final EventCodec codec=new EventCodec();
    private final org.springframework.beans.factory.ObjectProvider<ClusterFileCoordinator> files;
    private final ClusterStore membership;
    private final java.nio.file.Path nodeCaFile;
    public ClusterMeshSynchronizer(JdbcTemplate jdbc,ConsentStore accounts,ClusterAccountChannel accountChannel,org.springframework.beans.factory.ObjectProvider<ClusterFileCoordinator> files,ClusterStore membership,java.nio.file.Path nodeCaFile){this.jdbc=jdbc;this.accounts=accounts;this.accountChannel=accountChannel;this.files=files;this.membership=membership;this.nodeCaFile=nodeCaFile;recipients=new ClusterEventRecipients(jdbc);grants=new ClusterRecipientConsent(jdbc);}
    @Override public void synchronize(ClusterMembershipCoordinator.Plan plan,List<ClusterProfiles.Entry> nodes){
        var http=new ClusterHttpNodes(plan.clusterId(),this,membership,nodeCaFile);
        for(int round=0;round<4;round++){
            boolean seeded=true;for(var node:nodes){var progress=(Map<?,?>)http.call(node,"replay-seed",plan).get("progress");seeded&=Boolean.TRUE.equals(progress.get("seeded"));}
            synchronizePolicyBeforeAccounts(http,nodes);
            boolean projected=projectBaseline(plan,nodes);
            cycle(http,nodes);
            boolean drained=true;for(var node:nodes){var state=(Map<?,?>)http.call(node,"replay-status",null).get("progress");for(String key:List.of("pendingDeliveries","pendingInbox","fileTransfers"))drained&=((Number)state.get(key)).longValue()==0;}
            if(seeded&&projected&&drained)return;
        }
        throw new ClusterFailure(409,"INITIAL_SYNC_PENDING");
    }
    /** Account snapshots validate the active policy, so a new member needs configuration first. */
    private void synchronizePolicyBeforeAccounts(ClusterHttpNodes http,List<ClusterProfiles.Entry> nodes){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.consent_policy WHERE revision>0)",Boolean.class)))return;
        var probes=new HashMap<ClusterMember,Map<String,Object>>();
        for(var node:nodes)probes.put(node.member(),http.call(node,"probe",null));
        for(int page=0;page<4;page++){
            int sent=0;
            for(var source:nodes)for(var target:nodes)if(!source.member().equals(target.member()))sent+=pump(http,source,target,probes,true);
            for(var node:nodes)http.call(node,"flush",Map.of());
            if(sent==0)return;
        }
        throw new ClusterFailure(409,"CONSENT_POLICY_CATCHUP_PENDING");
    }
    boolean projectBaseline(ClusterMembershipCoordinator.Plan plan,List<ClusterProfiles.Entry> nodes){
        var rows=jdbc.queryForList("SELECT account_last_user,account_complete FROM eatwhat_cluster.replay_cursor WHERE change_id=? AND source_site=(SELECT site_id FROM eatwhat_identity.allocation WHERE singleton)",plan.changeId());
        if(rows.size()!=1)throw new ClusterFailure(409,"ACCOUNT_BASELINE_NOT_STAGED");var row=rows.getFirst();if(Boolean.TRUE.equals(row.get("account_complete")))return true;
        long after=((Number)row.get("account_last_user")).longValue();var page=jdbc.queryForList("SELECT user_id FROM eatwhat_consent.subject WHERE user_id>? ORDER BY user_id LIMIT 32",Long.class,after);
        for(long id:page){
            boolean independent=multiwriter!=null&&multiwriter.writing();
            var batches=independent?ClusterBarrier.internal(()->multiwriter.pages(id)):List.<tools.jackson.databind.node.ObjectNode>of();
            var snapshot=independent?(batches.isEmpty()?null:JSON.treeToValue(batches.getFirst().get("snapshot"),you.v50to.eatwhat.account.ConsentModels.Snapshot.class))
                    :ClusterBarrier.internal(()->accounts.exportCurrent(id));
            if(snapshot!=null)for(var target:nodes){if(target.member().allocationSlot()==0)continue;membership.requireLeadership();
                boolean allowed=snapshot.status().equals("WITHDRAWN")?grants.historicallyAllowed(snapshot.subjectId(),target.member()):grants.allowed(snapshot.subjectId(),snapshot.epoch(),target.member());
                if(allowed){
                    if(independent){
                        for(int index=0;index<batches.size();index++){
                            String status=multiwriter.transmit(batches.get(index),part -> accountChannel.multiwriter(target,part)).path("status").asText();
                            if(!status.equals("APPLIED")&&!(index<batches.size()-1&&status.equals("STAGED")))
                                throw new ClusterFailure(409,"ACCOUNT_BASELINE_PENDING");
                        }
                    }else if(!Set.of("APPLIED","STALE").contains(accountChannel.project(target,snapshot).path("status").asText()))
                        throw new ClusterFailure(409,"ACCOUNT_BASELINE_PENDING");
                }}
            after=id;
        }
        boolean complete=page.size()<32;long cursor=after;
        membership.fencedWrite(()->jdbc.update("UPDATE eatwhat_cluster.replay_cursor SET account_last_user=?,account_complete=? WHERE change_id=? AND source_site=(SELECT site_id FROM eatwhat_identity.allocation WHERE singleton)",cursor,complete,plan.changeId()));return complete;
    }
    public void cycle(ClusterHttpNodes http,List<ClusterProfiles.Entry> nodes){
        var probes=new HashMap<ClusterMember,Map<String,Object>>();
        for(var node:nodes)probes.put(node.member(),http.call(node,"probe",null));
        for(var node:nodes)http.call(node,"flush",Map.of());
        for(var source:nodes)for(var target:nodes)if(!source.member().equals(target.member()))pump(http,source,target,probes);
        var transfer=files.getIfAvailable();if(transfer!=null)transfer.cycle(http,nodes);
        for(var node:nodes)http.call(node,"flush",Map.of());
    }
    private void pump(ClusterHttpNodes http,ClusterProfiles.Entry source,ClusterProfiles.Entry target,Map<ClusterMember,Map<String,Object>> probes){
        pump(http,source,target,probes,false);
    }
    private int pump(ClusterHttpNodes http,ClusterProfiles.Entry source,ClusterProfiles.Entry target,Map<ClusterMember,Map<String,Object>> probes,boolean policyOnly){
        var supported=EventSchemas.fromProbe(probes.get(target.member()));
        if(policyOnly){
            String family=you.v50to.eatwhat.account.ConsentPolicyEvents.AGGREGATE;
            if(!EventSchemas.supports(supported,family,1))throw new ClusterFailure(409,"CONSENT_POLICY_READER_UPGRADE_REQUIRED");
            supported=Map.of(family,Set.of(1));
        }
        // Keep the old request shape when talking to a source that predates negotiation.
        Object request=probes.get(source.member()).containsKey("eventSchemas")
                ?new ClusterReplayService.Target(target.member().siteId(),supported):Map.of("targetSite",target.member().siteId());
        var response=http.call(source,"replay-pull",request);
        var events=JSON.convertValue(response.get("events"),JSON.getTypeFactory().constructCollectionType(List.class,TransportProtocol.EventItem.class));
        List<TransportProtocol.EventItem> batch=(List<TransportProtocol.EventItem>)events;var allowed=new ArrayList<TransportProtocol.EventItem>();var withheld=new ArrayList<TransportProtocol.EventItem>();
        for(var item:batch){var event=codec.decode(item.envelope());if(!codec.encode(event).hash().equals(item.envelopeHash())||!event.eventId().equals(item.eventId())||!event.originSite().equals(source.member().siteId())||!event.originInstanceId().equals(source.member().instanceId()))throw new ClusterFailure(409,"EVENT_SOURCE_PROOF_MISMATCH");
            // No ACK or policy quarantine: a reader upgrade can retry this immutable delivery.
            if(!EventSchemas.supports(supported,event.aggregateType(),event.schemaVersion()))continue;
            if(recipients.allowed(event,target.member()))allowed.add(item);else withheld.add(item);
        }
        var acks=new ArrayList<TransportProtocol.Ack>();if(!allowed.isEmpty()){
            var pushed=http.call(target,"replay-push",new ClusterReplayService.Push(source.member().identity(),allowed));
            List<TransportProtocol.Ack> received=JSON.convertValue(pushed.get("acks"),JSON.getTypeFactory().constructCollectionType(List.class,TransportProtocol.Ack.class));
            if(received.size()!=allowed.size()||received.stream().map(TransportProtocol.Ack::eventId).distinct().count()!=received.size())throw new ClusterFailure(409,"EVENT_ACK_MISMATCH");
            for(var ack:received){if(allowed.stream().noneMatch(e->e.eventId().equals(ack.eventId())&&e.envelopeHash().equals(ack.envelopeHash())&&e.leaseToken().equals(ack.leaseToken())))throw new ClusterFailure(409,"EVENT_ACK_MISMATCH");acks.add(ack);}
        }
        if(!acks.isEmpty()||!withheld.isEmpty())http.call(source,"replay-ack",new ClusterReplayService.Settle(target.member().siteId(),acks,withheld));
        return allowed.size()+withheld.size();
    }
}
