package you.v50to.eatwhat.control.independent;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import you.v50to.eatwhat.cluster.ClusterControlLease;
import you.v50to.eatwhat.control.ControlFailure;
import you.v50to.eatwhat.data.dto.LoginDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.sync.EventCodec;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** A management credential is not a consumer account, consent grant, or business role.
 * Sessions and rate limits use this controller's Redis; committed versions fence revocation. */
public final class IndependentControlAccounts {
    private static final String REALM="eatwhat:independent-control:v1",VERSION="control:independentVersion";
    private static final DefaultRedisScript<Long> ATTEMPTS=new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],300) end;return n",Long.class);
    private final JdbcTemplate jdbc;private final PasswordEncoder passwords;private final StringRedisTemplate redis;
    private final TransactionTemplate tx;private final ObjectProvider<ClusterControlLease> lease;
    public IndependentControlAccounts(JdbcTemplate jdbc,PasswordEncoder passwords,StringRedisTemplate redis,
            PlatformTransactionManager transactions,ObjectProvider<ClusterControlLease> lease){
        this.jdbc=jdbc;this.passwords=passwords;this.redis=redis;this.lease=lease;
        tx=new TransactionTemplate(transactions);tx.setTimeout(15);
    }
    private Map<String,Object> account(long id,boolean lock){
        var rows=jdbc.queryForList("SELECT id,username,password_hash,credential_version,disabled FROM eatwhat_controller.account WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.size()!=1||Boolean.TRUE.equals(rows.getFirst().get("disabled")))throw new ControlFailure(401,"AUTHENTICATION_REQUIRED");
        return rows.getFirst();
    }
    private void rate(String key){
        Long attempts=redis.execute(ATTEMPTS,List.of("control:independent-attempts:"+key));
        if(attempts==null)throw new ControlFailure(503,"ACCOUNT_SECURITY_UNAVAILABLE");
        if(attempts>10)throw new ControlFailure(429,"CREDENTIAL_RATE_LIMITED");
    }
    public Result<?> login(LoginDTO request){
        rate("login:"+EventCodec.sha256(request.getUsername()));
        var rows=jdbc.queryForList("SELECT id,password_hash,credential_version FROM eatwhat_controller.account WHERE username=? AND NOT disabled",request.getUsername());
        if(rows.size()!=1||!passwords.matches(request.getPassword(),(String)rows.getFirst().get("password_hash")))return Result.fail(BizCode.PASSWORD_ERROR);
        var before=rows.getFirst();long id=((Number)before.get("id")).longValue();
        StpUtil.login(id,new cn.dev33.satoken.stp.parameter.SaLoginParameter().setDeviceType(request.getDevice().name()).setIsShare(false));
        String issued=StpUtil.getTokenValue();
        try{
            var after=account(id,false);
            if(!Objects.equals(before.get("password_hash"),after.get("password_hash"))
                    ||((Number)before.get("credential_version")).longValue()!=((Number)after.get("credential_version")).longValue())
                throw new ControlFailure(401,"ACCOUNT_CREDENTIALS_CHANGED");
            StpUtil.getTokenSession().set("control:realm",REALM).set(VERSION,after.get("credential_version"));
            var info=StpUtil.getTokenInfo();info.setTag("admin");return Result.ok(info);
        }catch(RuntimeException failure){StpUtil.getStpLogic().logoutByTokenValue(issued);throw failure;}
    }
    public Result<Void> logout(){StpUtil.logout();return Result.ok();}
    public void requireSession(long id){
        StpUtil.checkLogin();if(StpUtil.getLoginIdAsLong()!=id)throw new ControlFailure(401,"AUTHENTICATION_REQUIRED");
        var row=account(id,false);var session=StpUtil.getTokenSession();Object version=session.get(VERSION);
        if(!REALM.equals(session.get("control:realm"))||!(version instanceof Number number)
                ||number.longValue()!=((Number)row.get("credential_version")).longValue())throw new ControlFailure(401,"ACCOUNT_CREDENTIALS_CHANGED");
    }
    public String requireAdmin(){long id=StpUtil.getLoginIdAsLong();requireSession(id);return Long.toString(id);}
    /** Sealed by the bootstrap admission caller; never expose this list in an API or log. */
    public List<Object> admissionBinding(long id){var row=account(id,false);return List.of(row.get("id"),row.get("username"),row.get("password_hash"),row.get("credential_version"));}
    private String blocker(){var current=lease.getIfAvailable();if(current==null)return "CONTROL_LEASE_REQUIRED";try{current.requireAccepting();return "";}catch(you.v50to.eatwhat.cluster.ClusterFailure failure){return failure.getMessage();}}
    public Map<String,Object> profile(long id){requireSession(id);String reason=blocker();return Map.of("username",account(id,false).get("username"),"canEdit",reason.isEmpty(),"reason",reason);}
    public Map<String,Object> change(long id,String currentPassword,String username,String password){
        requireSession(id);String reason=blocker();if(!reason.isEmpty())throw new ControlFailure(409,reason);
        if(username==null&&password==null)throw new ControlFailure(400,"NO_CREDENTIAL_CHANGES");
        if(username!=null&&!username.matches("^(?![0-9]{11}$)[A-Za-z0-9_]{3,32}$"))throw new ControlFailure(400,"INVALID_USERNAME");
        if(password!=null&&(password.codePointCount(0,password.length())<12||password.codePointCount(0,password.length())>64
                ||password.getBytes(StandardCharsets.UTF_8).length>72||!StandardCharsets.UTF_8.newEncoder().canEncode(password)
                ||password.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xfeff)))throw new ControlFailure(400,"INVALID_NEW_PASSWORD");
        rate("change:"+id);
        var attempted=new java.util.concurrent.atomic.AtomicBoolean();
        Map<String,Object> result;
        try{result=tx.execute(ignored->{
            lease.getObject().requireHeldInTransaction();var row=account(id,true);
            requireSession(id);
            if(!passwords.matches(currentPassword,(String)row.get("password_hash")))throw new ControlFailure(400,"CURRENT_PASSWORD_INVALID");
            String next=username==null?(String)row.get("username"):username;
            if(next.equals(row.get("username"))&&password==null)throw new ControlFailure(400,"NO_CREDENTIAL_CHANGES");
            if(password!=null&&passwords.matches(password,(String)row.get("password_hash")))throw new ControlFailure(400,"PASSWORD_UNCHANGED");
            String hash=password==null?(String)row.get("password_hash"):passwords.encode(password);
            attempted.set(true);jdbc.update("UPDATE eatwhat_controller.account SET username=?,password_hash=?,credential_version=credential_version+1,updated_at=clock_timestamp() WHERE id=?",next,hash,id);
            jdbc.update("INSERT INTO eatwhat_controller.credential_change(request_id,account_id,credential_version) SELECT ?,id,credential_version FROM eatwhat_controller.account WHERE id=?",UUID.randomUUID(),id);
            return Map.of("username",next,"reauthenticationRequired",true);
        });}catch(org.springframework.dao.DuplicateKeyException conflict){throw new ControlFailure(409,"USERNAME_UNAVAILABLE");}
        catch(org.springframework.dao.DataAccessException failure){throw new ControlFailure(503,attempted.get()?"ACCOUNT_UPDATE_OUTCOME_UNKNOWN":"ACCOUNT_UPDATE_UNAVAILABLE");}
        try{StpUtil.logout(id);}catch(RuntimeException ignored){/* Database version still denies every stale token. */}
        return result;
    }
}
