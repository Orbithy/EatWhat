package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.*;

/** Current identities for routing; retained names for causal vectors. A retired name is never a live recipient. */
public final class ClusterMembershipView {
    private final JdbcTemplate jdbc;private Boolean bound;
    public ClusterMembershipView(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean bound(){
        if(bound==null){boolean schema=Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('eatwhat_identity.allocation') IS NOT NULL",Boolean.class));bound=schema&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_identity.allocation)",Boolean.class));}return bound;
    }
    public List<ClusterMember> current(){if(!bound())return List.of();var rows=jdbc.queryForList("SELECT members::text AS members FROM eatwhat_cluster.local_state WHERE singleton");if(rows.size()!=1)throw new ClusterFailure(409,"LOCAL_CLUSTER_NOT_JOINED");return ClusterStore.parseMembers((String)rows.getFirst().get("members"));}
    public List<ClusterMember> participants(){if(!bound())return List.of();var rows=jdbc.queryForList("SELECT members::text AS members,pending_change FROM eatwhat_cluster.local_state WHERE singleton");if(rows.size()!=1)throw new ClusterFailure(409,"LOCAL_CLUSTER_NOT_JOINED");var row=rows.getFirst();if(row.get("pending_change")!=null){var plans=jdbc.queryForList("SELECT plan->'participants' AS participants FROM eatwhat_cluster.local_change WHERE change_id=?",row.get("pending_change"));if(plans.size()!=1)throw new ClusterFailure(409,"MEMBERSHIP_PLAN_MISSING");return ClusterStore.parseMembers(plans.getFirst().get("participants").toString());}
        var staged=jdbc.queryForList("SELECT c.plan->'participants' AS participants FROM eatwhat_cluster.local_change c JOIN eatwhat_cluster.local_phase p USING(change_id) WHERE p.phase='STAGED' AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.local_phase released WHERE released.change_id=c.change_id AND released.phase='RELEASED') ORDER BY c.created_at DESC LIMIT 1");
        return staged.isEmpty()?ClusterStore.parseMembers((String)row.get("members")):ClusterStore.parseMembers(staged.getFirst().get("participants").toString());}
    public NodeIdentity origin(SyncEvent event,NodeIdentity fallback){if(!bound())return fallback;return participants().stream().map(ClusterMember::identity).filter(n->n.siteId().equals(event.originSite())&&n.instanceId().equals(event.originInstanceId())).findFirst().orElseThrow(()->new ClusterFailure(403,"EVENT_ORIGIN_NOT_A_MEMBER"));}
    public boolean current(NodeIdentity peer){return current().stream().anyMatch(m->m.identity().equals(peer));}
    public List<ClusterMember> retained(){if(!bound())return List.of();var all=new LinkedHashMap<String,ClusterMember>();for(var member:participants())all.put(member.siteId(),member);for(var row:jdbc.queryForList("SELECT plan->'participants' AS participants FROM eatwhat_cluster.local_change"))for(var member:ClusterStore.parseMembers(row.get("participants").toString())){var old=all.putIfAbsent(member.siteId(),member);if(old!=null&&!old.equals(member))throw new ClusterFailure(409,"RETAINED_MEMBER_IDENTITY_CHANGED");}return List.copyOf(all.values());}
    public boolean knownSite(String site){if(!bound())return false;if(participants().stream().anyMatch(m->m.siteId().equals(site)))return true;return jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.local_change WHERE EXISTS(SELECT 1 FROM jsonb_array_elements(plan->'participants') p WHERE p->>'siteId'=?)",Long.class,site)>0;}
    public static Set<String> sites(JdbcTemplate jdbc,Set<String> fallback){return new LiveSites(new ClusterMembershipView(jdbc),Set.copyOf(fallback));}
    public static Set<String> freezeOrLive(Set<String> sites){return sites instanceof LiveSites?sites:Set.copyOf(sites);}
    private static final class LiveSites extends AbstractSet<String> {
        private final ClusterMembershipView view;private final Set<String> fallback;
        LiveSites(ClusterMembershipView view,Set<String> fallback){this.view=view;this.fallback=fallback;}
        private Set<String> values(){if(!view.bound())return fallback;var set=new LinkedHashSet<String>();for(var member:view.participants())set.add(member.siteId());
            for(var row:view.jdbc.queryForList("SELECT plan->'participants' AS participants FROM eatwhat_cluster.local_change"))for(var member:ClusterStore.parseMembers(row.get("participants").toString()))set.add(member.siteId());return Collections.unmodifiableSet(set);}
        public Iterator<String> iterator(){return values().iterator();}public int size(){return values().size();}
        public boolean contains(Object item){return item instanceof String site&&(view.bound()?view.knownSite(site):fallback.contains(site));}
    }
}
