package you.v50to.eatwhat.sync.preferences;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.identity.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local projection, logical winner and Outbox commit together. Receive never calls replace. */
public final class PreferenceCommands {
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final OutboxService outbox;
    private final ConsentStore consent;
    private final SiteProperties site;
    private final String peer;
    private final int batch;
    private final EntityIdentityService identities;
    private final AtomicBoolean active = new AtomicBoolean();
    record State(long epoch, long clock, UUID event, String site, String hash, Instant at,
                 boolean empty, boolean dirty, long publishedEpoch) { }
    record Value(List<String> liked, List<String> disliked, Instant at) {
        String hash() { return PreferenceEvents.valueHash(liked, disliked); }
        boolean empty() { return liked.isEmpty() && disliked.isEmpty(); }
    }
    public PreferenceCommands(JdbcTemplate jdbc, SyncTransactions transactions, OutboxService outbox,
            ConsentStore consent, SiteProperties site, String peer, int batch) {
        this.jdbc=jdbc; this.transactions=transactions; this.outbox=outbox; this.consent=consent;
        this.site=site; this.peer=peer; this.batch=batch; this.identities=new EntityIdentityService(jdbc);
        transactions.requireSameDataSource(jdbc);
    }
    static Instant micros(Instant time) { return Instant.ofEpochSecond(time.getEpochSecond(), time.getNano()/1000*1000); }
    static long microsValue(Instant time) { return Math.addExact(Math.multiplyExact(time.getEpochSecond(),1000000),time.getNano()/1000); }
    static Instant time(long value) { return Instant.ofEpochSecond(value/1000000,value%1000000*1000); }
    static void lock(JdbcTemplate jdbc, UUID subject) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", r -> {}, "preferences:"+subject);
    }
    static State state(JdbcTemplate jdbc, UUID subject) {
        return jdbc.query("SELECT * FROM eatwhat_sync.preference_state WHERE subject_gid=? FOR UPDATE", (r,n) ->
                new State(r.getLong("epoch"),r.getLong("logical_clock"),r.getObject("winner_id",UUID.class),
                        r.getString("winner_site"),r.getString("value_hash"),r.getTimestamp("observed_at").toInstant(),
                        r.getBoolean("is_empty"),r.getBoolean("local_dirty"),r.getLong("published_epoch")),subject)
                .stream().findFirst().orElse(null);
    }
    static void remember(JdbcTemplate jdbc, UUID subject, State state) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.preference_state(subject_gid,epoch,logical_clock,winner_id,winner_site,value_hash,
                  observed_at,is_empty,local_dirty,published_epoch) VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(subject_gid) DO UPDATE SET epoch=EXCLUDED.epoch,logical_clock=EXCLUDED.logical_clock,
                  winner_id=EXCLUDED.winner_id,winner_site=EXCLUDED.winner_site,value_hash=EXCLUDED.value_hash,
                  observed_at=EXCLUDED.observed_at,is_empty=EXCLUDED.is_empty,local_dirty=EXCLUDED.local_dirty,
                  published_epoch=EXCLUDED.published_epoch
                """,subject,state.epoch,state.clock,state.event,state.site,state.hash,Timestamp.from(state.at),
                state.empty,state.dirty,state.publishedEpoch);
    }
    static void project(JdbcTemplate jdbc, long user, Value value) {
        if (value.empty()) jdbc.update("DELETE FROM public.user_food_preferences WHERE account_id=?",user);
        else jdbc.update("""
                INSERT INTO public.user_food_preferences(account_id,liked_tags,disliked_tags,updated_at) VALUES(?,?,?,?)
                ON CONFLICT(account_id) DO UPDATE SET liked_tags=EXCLUDED.liked_tags,disliked_tags=EXCLUDED.disliked_tags,
                  updated_at=EXCLUDED.updated_at
                """,user,value.liked.toArray(String[]::new),value.disliked.toArray(String[]::new),Timestamp.from(value.at));
    }
    private Value value(long user, State before) {
        return jdbc.query("SELECT * FROM public.user_food_preferences WHERE account_id=?",(r,n) -> new Value(
                Arrays.asList((String[])r.getArray("liked_tags").getArray()),Arrays.asList((String[])r.getArray("disliked_tags").getArray()),
                r.getTimestamp("updated_at").toInstant()),user).stream().findFirst()
                .orElseGet(() -> new Value(List.of(),List.of(),before!=null&&before.empty?before.at:micros(Instant.now())));
    }
    private State localState(ConsentModels.State grant, State before, Value value) {
        long clock=before==null?1:Math.addExact(before.clock,1);
        if(clock>VersionVector.MAX_COUNTER) throw new IllegalStateException("PREFERENCE_CLOCK_EXHAUSTED");
        return new State(grant==null?0:grant.epoch(),clock,UUID.randomUUID(),site.getId(),value.hash(),value.at,value.empty(),true,0);
    }
    private ConsentModels.State lockAccount(long user) {
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE user_id=? FOR UPDATE",(r,n)->r.getLong(1),user);
        return consent.get(user);
    }
    public void replace(long user, List<String> liked, List<String> disliked) {
        PreferenceEvents.validateTags(liked); PreferenceEvents.validateTags(disliked);
        if(liked.stream().anyMatch(disliked::contains)) throw new IllegalArgumentException("INVALID_PREFERENCE_EVENT");
        transactions.required(() -> {
            var grant=lockAccount(user);
            if(site.getMode()==SiteProperties.Mode.EXTERNAL&&!consent.isAuthorized(user)) throw new BizException(BizCode.CROSS_BORDER_CONSENT_REQUIRED);
            UUID subject=identities.findGlobalId(EntityType.USERS,user).orElseThrow(); lock(jdbc,subject);
            if(jdbc.queryForList("SELECT id FROM public.users WHERE id=? FOR SHARE",Long.class,user).isEmpty()) throw new BizException(BizCode.DATA_SYNC_PENDING);
            var value=new Value(List.copyOf(liked),List.copyOf(disliked),micros(Instant.now()));
            var next=localState(grant,state(jdbc,subject),value); project(jdbc,user,value); remember(jdbc,subject,next);
            publish(user,subject,next,value); return null;
        });
    }
    private boolean publish(long user, UUID subject, State next, Value value) {
        if(!consent.replicationAllowed(user)) return false;
        var grant=consent.get(user);
        if(grant.epoch()!=next.epoch) return false;
        var op=new PreferenceEvents.Snapshot(subject,next.epoch,next.clock,value.liked,value.disliked,microsValue(next.at));
        var event=new SyncEvent(next.event,site.getId(),site.getInstanceId(),PreferenceEvents.AGGREGATE,next.event,
                jdbc.queryForObject("SELECT eatwhat_sync.next_relation_number()",Long.class),PreferenceEvents.EVENT,1,
                VersionVector.empty(),VersionVector.empty().increment(site.getId()),op.dependencies(),subject,
                "preferences:"+next.event,next.event,PreferenceEvents.payload(op));
        new PreferenceEvents(consent).validate(event,new NodeIdentity(site.getId(),site.getInstanceId()));
        outbox.append(event,you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peer));
        remember(jdbc,subject,new State(next.epoch,next.clock,next.event,next.site,next.hash,next.at,next.empty,false,next.epoch));
        return true;
    }
    private boolean backfill(long user) {
        return transactions.required(() -> {
            var grant=lockAccount(user); if(grant==null||!consent.replicationAllowed(user)) return false;
            UUID subject=grant.subjectId(); lock(jdbc,subject); var before=state(jdbc,subject);
            // The campus retained copy bridges consent epochs. An erased overseas projection never becomes a clear command.
            if(site.getMode()==SiteProperties.Mode.EXTERNAL&&before!=null&&before.epoch!=grant.epoch()) return false;
            var value=value(user,before);
            if(before==null&&value.empty()) return false;
            boolean same=before!=null&&before.hash.equals(value.hash())&&before.at.equals(value.at);
            if(same&&!before.dirty&&before.publishedEpoch==grant.epoch()) return false;
            var next=same&&before.dirty&&before.epoch==grant.epoch()?before:localState(grant,before,value);
            remember(jdbc,subject,next); return publish(user,subject,next,value);
        });
    }
    /** Inbound only waits; the existing source worker captures this local choice outside inbound scope. */
    boolean hasUnpublishedLocalSnapshot(ConsentModels.State grant) {
        var before=state(jdbc,grant.subjectId());
        if(before!=null&&(before.epoch>grant.epoch()||site.getMode()==SiteProperties.Mode.EXTERNAL&&before.epoch!=grant.epoch())) return false;
        var value=value(grant.userId(),before);
        if(before==null) return !value.empty();
        return before.epoch!=grant.epoch()||before.dirty||before.publishedEpoch!=grant.epoch()
                ||!before.hash.equals(value.hash())||!before.at.equals(value.at);
    }

    public int runOnce() {
        if(Thread.currentThread().isInterrupted()||!active.compareAndSet(false,true)) return 0;
        try {
            var users=jdbc.queryForList("""
                    SELECT s.user_id FROM eatwhat_consent.subject s JOIN public.users u ON u.id=s.user_id
                    LEFT JOIN public.user_food_preferences p ON p.account_id=s.user_id
                    LEFT JOIN eatwhat_sync.preference_state v ON v.subject_gid=s.subject_id
                    WHERE s.status='GRANTED' AND s.identity_ready AND s.withdrawal_request_id IS NULL
                      AND s.policy_version=? AND NOT u.banned AND (NOT ? OR s.valid_until>clock_timestamp())
                      AND (NOT ? OR v.subject_gid IS NULL OR v.epoch=s.epoch)
                      AND ((v.subject_gid IS NULL AND p.account_id IS NOT NULL) OR v.local_dirty OR v.published_epoch<>s.epoch
                        OR (p.account_id IS NOT NULL AND p.updated_at IS DISTINCT FROM v.observed_at)
                        OR (p.account_id IS NULL AND v.is_empty=false))
                    ORDER BY s.user_id LIMIT ?
                    """,Long.class,consent.policyVersion(),site.getMode()==SiteProperties.Mode.EXTERNAL&&!consent.multiwriterEnabled(),
                    site.getMode()==SiteProperties.Mode.EXTERNAL,batch);
            int sent=0;
            for(long user:users) {
                if(Thread.currentThread().isInterrupted()) break;
                try { if(backfill(user)) sent++; } catch(RuntimeException retry) { /* No settings or identities in logs. */ }
            }
            return sent;
        } finally { active.set(false); }
    }
}
