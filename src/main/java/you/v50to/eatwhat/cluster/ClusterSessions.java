package you.v50to.eatwhat.cluster;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** Opaque tokens live in the authority's HA database; local Redis sessions are replaceable caches. */
public final class ClusterSessions {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ClusterSessions.class);
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts multiwriter;
    public boolean independentAuthentication(){return multiwriter!=null&&multiwriter.enabled();}
    public boolean localAuthenticationHealthy(){return independentAuthentication()&&multiwriter.healthy();}
    public record Token(String token){public Token{if(token==null||!token.matches("[A-Za-z0-9_-]{24,256}"))throw new ClusterFailure(401,"CLUSTER_SESSION_INVALID");}@Override public String toString(){return "ClusterSessionToken[redacted]";}}
    public record Authorization(long userId,UUID subjectId,long epoch,long revision,long expiresInSeconds){}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final ClusterTransactions tx;private final ClusterProperties settings;private final ClusterMember local;private final ClusterAccountChannel channel;private final ObjectProvider<ClusterHttpNodes> nodes;private final ObjectProvider<ClusterProfiles> profiles;private final ObjectProvider<ClusterAuthStore> authStore;
    public ClusterSessions(JdbcTemplate jdbc,SyncTransactions tx,ClusterProperties settings,ClusterMember local,ClusterAccountChannel channel,ObjectProvider<ClusterHttpNodes> nodes,ObjectProvider<ClusterProfiles> profiles,ObjectProvider<ClusterAuthStore> authStore){this.jdbc=jdbc;this.tx=new ClusterTransactions(tx);this.settings=settings;this.local=local;this.channel=channel;this.nodes=nodes;this.profiles=profiles;this.authStore=authStore;}
    private boolean shared(){return !settings.isCoordinator()&&authStore.getIfAvailable()!=null;}
    private JdbcTemplate authority(){return shared()?authStore.getObject().jdbc():jdbc;}
    private <T> T checked(Supplier<T> work){try{if(shared())authStore.getObject().verify();return work.get();}catch(ClusterFailure failure){throw failure;}catch(DataAccessException|org.springframework.transaction.TransactionException failure){throw new ClusterFailure(503,"CLUSTER_AUTH_STORE_UNAVAILABLE");}}
    private <T> T required(Supplier<T> work){return shared()?authStore.getObject().required(work):tx.required(work);}
    private void lockUser(JdbcTemplate database,long id){if(shared())database.query("SELECT pg_advisory_xact_lock(1161909335,hashtext(?))",rs->{},Long.toString(id));else database.query("SELECT id FROM users WHERE id=? FOR UPDATE",rs->{},id);}
    private boolean withdrawalFenced(JdbcTemplate database,UUID subject,long epoch){return Boolean.TRUE.equals(database.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.session_withdraw_fence WHERE subject_id=? AND epoch>=?)",Boolean.class,subject,epoch));}
    private Map<String,Object> securitySnapshot(JdbcTemplate database,long id){var rows=database.queryForList("SELECT u.password_hash,u.banned,u.role,i.global_id,coalesce((SELECT version FROM eatwhat_cluster.session_security_version WHERE user_id=u.id),0) AS security_version FROM users u JOIN eatwhat_identity.entity_identity i ON i.entity_type='users' AND i.numeric_id=u.id WHERE u.id=?",id);if(rows.size()!=1)throw new ClusterFailure(401,"CLUSTER_SESSION_REVOKED");return rows.getFirst();}
    private Map<String,Object> security(JdbcTemplate database,long id){var row=securitySnapshot(database,id);if(Boolean.TRUE.equals(row.get("banned"))||StpUtil.isDisable(id))throw new ClusterFailure(401,"CLUSTER_SESSION_REVOKED");return row;}
    private String fingerprint(Map<String,Object> row){return EventCodec.sha256(row.get("password_hash")+"\u0000"+row.get("banned")+"\u0000"+row.get("role"));}
    public boolean active(){return jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.local_state WHERE singleton AND state IN ('ACTIVE','DRAINING')",Long.class)==1;}
    public Map<String,Object> localProof(Token input){Object id=StpUtil.getLoginIdByToken(input.token());if(id==null)throw new ClusterFailure(401,"CLUSTER_SESSION_INVALID");long user=Long.parseLong(id.toString());var security=security(jdbc,user);long remaining=StpUtil.getTokenTimeout(input.token()),idle=StpUtil.getStpLogic().getConfigOrGlobal().getActiveTimeout();if(remaining==-2)throw new ClusterFailure(401,"CLUSTER_SESSION_INVALID");return Map.of("userId",user,"expiresInSeconds",remaining<0?2592000:Math.min(2592000,remaining),"idleSeconds",idle<1?2592000:Math.min(2592000,idle),"securityHash",fingerprint(security));}
    public Authorization register(Token token){if(independentAuthentication()){var proof=localProof(token);try{var session=multiwriter.registerSession(token.token(),((Number)proof.get("userId")).longValue(),((Number)proof.get("expiresInSeconds")).longValue());return localAuthorization(session);}catch(RuntimeException invalid){logUnavailable("LOCAL_REGISTER",invalid);throw new ClusterFailure(401,"CLUSTER_SESSION_REVOKED");}}if(!settings.isCoordinator()&&!shared())return remote("session-register",token);return checked(()->register(local.identity(),token));}
    Authorization register(NodeIdentity source,Token token){if(!settings.isCoordinator()&&!shared())throw new ClusterFailure(403,"AUTHORITY_REQUIRED");
        var database=authority();var member=new ClusterMembershipView(database).current().stream().filter(m->m.identity().equals(source)).findFirst().orElseThrow(()->new ClusterFailure(403,"SESSION_SOURCE_NOT_ACTIVE"));
        Map<?,?> login;if(member.equals(local))login=localProof(token);else{var entry=profiles.getObject().get(profileId(member));login=(Map<?,?>)nodes.getObject().call(entry,"session-introspect",token).get("session");}long user=((Number)login.get("userId")).longValue(),ttl=((Number)login.get("expiresInSeconds")).longValue(),idle=((Number)login.get("idleSeconds")).longValue();if(ttl<1||ttl>2592000||idle<1||idle>2592000)throw new ClusterFailure(401,"CLUSTER_SESSION_INVALID");
        final long id=user;return required(()->{
            lockUser(database,id);
            var row=security(database,id);if(!fingerprint(row).equals(login.get("securityHash")))throw new ClusterFailure(409,"SESSION_ACCOUNT_SYNC_PENDING");
            UUID gid=(UUID)row.get("global_id");var consent=database.queryForList("SELECT epoch FROM eatwhat_consent.subject WHERE subject_id=?",gid);long epoch=consent.isEmpty()?0:((Number)consent.getFirst().get("epoch")).longValue();String hash=EventCodec.sha256(token.token());
            if(withdrawalFenced(database,gid,epoch))throw new ClusterFailure(401,"CLUSTER_SESSION_REVOKED");
            var old=database.queryForList("SELECT user_id FROM eatwhat_cluster.login_session WHERE token_hash=?",hash);if(!old.isEmpty()){if(((Number)old.getFirst().get("user_id")).longValue()!=id)throw new ClusterFailure(409,"SESSION_TOKEN_COLLISION");return authorize(token,member);}
            database.update("UPDATE eatwhat_cluster.login_session SET revoked=true WHERE user_id=? AND NOT revoked",id);
            database.update("INSERT INTO eatwhat_cluster.login_session(token_hash,subject_id,user_id,source_site,source_instance,consent_epoch,security_hash,security_version,expires_at,idle_seconds) VALUES(?,?,?,?,?,?,?,?,clock_timestamp()+(? * interval '1 second'),?)",hash,gid,id,member.siteId(),member.instanceId(),epoch,fingerprint(row),row.get("security_version"),ttl,idle);return authorize(token,member);
        });
    }
    private String profileId(ClusterMember member){return profiles.getObject().list().stream().filter(p->p.get("siteId").equals(member.siteId())).map(p->(String)p.get("id")).findFirst().orElseThrow(()->new ClusterFailure(503,"SESSION_SOURCE_PROFILE_MISSING"));}
    public Authorization authorize(Token token){if(independentAuthentication()){try{return localAuthorization(multiwriter.authorizeSession(token.token()));}catch(RuntimeException invalid){throw new ClusterFailure(401,"CLUSTER_SESSION_INVALID");}}if(!settings.isCoordinator()&&!shared())return remote("session-authorize",token);return checked(()->authorize(token,local));}
    private Authorization localAuthorization(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Session session){var states=jdbc.queryForList("SELECT epoch,revision FROM eatwhat_consent.subject WHERE user_id=?",session.userId());return new Authorization(session.userId(),session.subjectId(),states.isEmpty()?0:((Number)states.getFirst().get("epoch")).longValue(),states.isEmpty()?0:((Number)states.getFirst().get("revision")).longValue(),session.expiresInSeconds());}
    Authorization authorize(Token token,ClusterMember target){
        var database=authority();if(!new ClusterMembershipView(database).current().contains(target))throw new ClusterFailure(403,"SESSION_TARGET_NOT_ACTIVE");
        var rows=database.queryForList("SELECT *,extract(epoch FROM expires_at-clock_timestamp())::bigint AS remaining FROM eatwhat_cluster.login_session WHERE token_hash=? AND NOT revoked AND expires_at>clock_timestamp() AND last_seen+(idle_seconds * interval '1 second')>clock_timestamp()",EventCodec.sha256(token.token()));if(rows.size()!=1)throw new ClusterFailure(401,"CLUSTER_SESSION_INVALID");var session=rows.getFirst();long user=((Number)session.get("user_id")).longValue();var row=security(database,user);
        if(!session.get("subject_id").equals(row.get("global_id"))||!session.get("security_hash").equals(fingerprint(row))||((Number)session.get("security_version")).longValue()!=((Number)row.get("security_version")).longValue())throw new ClusterFailure(401,"CLUSTER_SESSION_REVOKED");
        var states=database.queryForList("SELECT epoch,revision,status,withdrawal_request_id FROM eatwhat_consent.subject WHERE subject_id=?",session.get("subject_id"));long epoch=0,revision=0;
        if(!states.isEmpty()){var state=states.getFirst();epoch=((Number)state.get("epoch")).longValue();revision=((Number)state.get("revision")).longValue();long captured=((Number)session.get("consent_epoch")).longValue();boolean initialGrant=captured==0&&epoch==1&&state.get("status").equals("GRANTED");if((epoch!=captured&&!initialGrant)||state.get("withdrawal_request_id")!=null||state.get("status").equals("WITHDRAWN"))throw new ClusterFailure(401,"CLUSTER_SESSION_REVOKED");}
        if(withdrawalFenced(database,(UUID)session.get("subject_id"),epoch))throw new ClusterFailure(401,"CLUSTER_SESSION_REVOKED");
        if(target.allocationSlot()!=0&&!new ClusterRecipientConsent(database).allowed((UUID)session.get("subject_id"),epoch,target))throw new ClusterFailure(409,"SESSION_RECIPIENT_CONSENT_REQUIRED");
        if(database.update("UPDATE eatwhat_cluster.login_session SET last_seen=clock_timestamp() WHERE token_hash=? AND NOT revoked AND expires_at>clock_timestamp() AND last_seen+(idle_seconds * interval '1 second')>clock_timestamp()",session.get("token_hash"))!=1)throw new ClusterFailure(401,"CLUSTER_SESSION_REVOKED");
        return new Authorization(user,(UUID)session.get("subject_id"),epoch,revision,Math.max(1,((Number)session.get("remaining")).longValue()));
    }
    public void revoke(Token token){if(independentAuthentication()){multiwriter.revokeSession(token.token());return;}if(!settings.isCoordinator()&&!shared()){channel.call("session-revoke",JSON.valueToTree(token));return;}checked(()->{authority().update("UPDATE eatwhat_cluster.login_session SET revoked=true WHERE token_hash=?",EventCodec.sha256(token.token()));return null;});}
    /** Persist a global denial before an external site accepts a local withdrawal. */
    public void fenceWithdrawal(long userId){if(!shared())return;checked(()->required(()->{
        var database=authority();lockUser(database,userId);
        var localSecurity=securitySnapshot(jdbc,userId);var globalSecurity=securitySnapshot(database,userId);
        if(!localSecurity.get("global_id").equals(globalSecurity.get("global_id"))||!fingerprint(localSecurity).equals(fingerprint(globalSecurity)))throw new ClusterFailure(409,"SESSION_ACCOUNT_SYNC_PENDING");
        var rows=database.queryForList("SELECT epoch FROM eatwhat_consent.subject WHERE subject_id=? AND user_id=?",globalSecurity.get("global_id"),userId);
        if(rows.size()!=1)throw new ClusterFailure(409,"SESSION_ACCOUNT_SYNC_PENDING");
        long epoch=((Number)rows.getFirst().get("epoch")).longValue();
        database.update("INSERT INTO eatwhat_cluster.session_withdraw_fence(subject_id,user_id,epoch) VALUES(?,?,?) ON CONFLICT DO NOTHING",globalSecurity.get("global_id"),userId,epoch);
        database.update("UPDATE eatwhat_cluster.login_session SET revoked=true WHERE user_id=? AND NOT revoked",userId);return null;
    }));}
    JsonNode handle(NodeIdentity sender,String operation,JsonNode body){var token=JSON.treeToValue(body,Token.class);var member=new ClusterMembershipView(jdbc).current().stream().filter(m->m.identity().equals(sender)).findFirst().orElseThrow(()->new ClusterFailure(403,"SESSION_TARGET_NOT_ACTIVE"));return switch(operation){case "session-register"->JSON.valueToTree(register(sender,token));case "session-authorize"->JSON.valueToTree(authorize(token,member));case "session-revoke"->{revoke(token);yield JSON.valueToTree(Map.of("revoked",true));}default->throw new ClusterFailure(400,"INVALID_SESSION_OPERATION");};}
    private Authorization remote(String operation,Token token){try{return JSON.treeToValue(channel.call(operation,JSON.valueToTree(token)),Authorization.class);}catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(503,"CLUSTER_SESSION_AUTHORITY_UNAVAILABLE");}}
    public void attach(Token token,Authorization auth){
        if(independentAuthentication()){var checked=multiwriter.authorizeSession(token.token());if(checked.userId()!=auth.userId())throw new ClusterFailure(409,"SESSION_LOCAL_IDENTITY_MISMATCH");}
        var states=jdbc.queryForList("SELECT epoch,revision,identity_ready,status,valid_until,withdrawal_request_id FROM eatwhat_consent.subject WHERE subject_id=? AND user_id=?",auth.subjectId(),auth.userId());
        if(local.allocationSlot()!=0){if(states.size()!=1)throw new ClusterFailure(409,"SESSION_ACCOUNT_SYNC_PENDING");var state=states.getFirst();if((!independentAuthentication()&&(((Number)state.get("epoch")).longValue()!=auth.epoch()||((Number)state.get("revision")).longValue()!=auth.revision()))||!Boolean.TRUE.equals(state.get("identity_ready"))||!state.get("status").equals("GRANTED")||state.get("withdrawal_request_id")!=null||(!independentAuthentication()&&(!(state.get("valid_until") instanceof Timestamp until)||until.toInstant().isBefore(java.time.Instant.now()))))throw new ClusterFailure(409,"SESSION_ACCOUNT_SYNC_PENDING");}
        security(jdbc,auth.userId());Object current=StpUtil.getLoginIdByToken(token.token());if(current!=null&&!current.toString().equals(Long.toString(auth.userId())))throw new ClusterFailure(409,"SESSION_LOCAL_IDENTITY_MISMATCH");
        if(current==null)StpUtil.login(auth.userId(),new cn.dev33.satoken.stp.parameter.SaLoginParameter().setToken(token.token()).setTimeout(auth.expiresInSeconds()).setIsConcurrent(true).setIsShare(false));
        StpUtil.getStpLogic().updateLastActiveToNow(token.token());
        StpUtil.getTokenSession().set(AccountAuthService.SESSION_EPOCH,auth.epoch()).set(AccountAuthService.SESSION_REVISION,auth.revision());
    }
    // Authentication exceptions can contain credentials or SQL parameters. Never log messages or Throwable objects.
    private static void logUnavailable(String phase,RuntimeException failure) {
        String sqlState="";
        Throwable cause=failure;
        for(int depth=0;cause!=null&&depth<8;depth++,cause=cause.getCause())
            if(cause instanceof java.sql.SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}")){sqlState=sql.getSQLState();break;}
        LOG.warn("Account session unavailable [phase={},failure={},sqlState={}]",phase,failure.getClass().getSimpleName(),sqlState);
    }
}
