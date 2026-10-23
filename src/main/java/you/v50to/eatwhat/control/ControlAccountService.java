package you.v50to.eatwhat.control;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import you.v50to.eatwhat.cluster.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ControlAccountService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.control.independent.IndependentControlAccounts independentControlAccounts;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts multiwriter;
    private final JdbcTemplate jdbc;private final PasswordEncoder passwords;private final ControlAccountSecurity security;private final TransactionTemplate tx;private final StringRedisTemplate redis;private final ObjectProvider<ClusterControlLease> lease;private final ObjectProvider<ClusterProperties> cluster;
    private static final DefaultRedisScript<Long> ATTEMPTS=new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],300) end; return n",Long.class);
    public ControlAccountService(JdbcTemplate jdbc,PasswordEncoder passwords,ControlAccountSecurity security,PlatformTransactionManager manager,StringRedisTemplate redis,ObjectProvider<ClusterControlLease> lease,ObjectProvider<ClusterProperties> cluster){this.jdbc=jdbc;this.passwords=passwords;this.security=security;this.redis=redis;this.lease=lease;this.cluster=cluster;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(15);}
    public Map<String,Object> profile(long id){
        if(independentControlAccounts!=null)return independentControlAccounts.profile(id);
        security.requireAdmin(id);
        try{String name=jdbc.queryForObject("SELECT nick_name FROM users WHERE id=?",String.class,id);String reason=writeBlocker();return Map.of("username",name,"canEdit",reason.isEmpty(),"reason",reason);}
        catch(DataAccessException failure){throw new ControlFailure(503,"ACCOUNT_SECURITY_UNAVAILABLE");}
    }
    private String writeBlocker(){
        if(!security.authority())return "ACCOUNT_AUTHORITY_REQUIRED";
        var config=cluster.getIfAvailable();if(config!=null&&!config.isCoordinator())return "ACCOUNT_AUTHORITY_REQUIRED";
        var current=lease.getIfAvailable();if(current!=null){try{current.requireAccepting();}catch(ClusterFailure failure){return failure.getMessage();}}
        return "";
    }
    public Map<String,Object> change(long id,String currentPassword,String newUsername,String newPassword){
        if(independentControlAccounts!=null)return independentControlAccounts.change(id,currentPassword,newUsername,newPassword);
        String blocker=writeBlocker();if(!blocker.isEmpty())throw new ControlFailure(409,blocker);
        if(newUsername==null&&newPassword==null)throw new ControlFailure(400,"NO_CREDENTIAL_CHANGES");
        if(newUsername!=null&&!newUsername.matches("^(?![0-9]{11}$)[A-Za-z0-9_]{3,32}$"))throw new ControlFailure(400,"INVALID_USERNAME");
        if(newPassword!=null&&(newPassword.codePointCount(0,newPassword.length())<12||newPassword.codePointCount(0,newPassword.length())>64||!StandardCharsets.UTF_8.newEncoder().canEncode(newPassword)||newPassword.getBytes(StandardCharsets.UTF_8).length>72||newPassword.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xfeff)))throw new ControlFailure(400,"INVALID_NEW_PASSWORD");
        try{Long count=redis.execute(ATTEMPTS,List.of("control:credential-attempts:"+id));if(count==null)throw new ControlFailure(503,"ACCOUNT_SECURITY_UNAVAILABLE");if(count>5)throw new ControlFailure(429,"CREDENTIAL_RATE_LIMITED");}
        catch(ControlFailure failure){throw failure;}catch(RuntimeException failure){throw new ControlFailure(503,"ACCOUNT_SECURITY_UNAVAILABLE");}
        var mutationAttempted=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            Map<String,Object> result=tx.execute(status->{
                var current=lease.getIfAvailable();if(current!=null)current.requireHeldInTransaction();
                var rows=jdbc.queryForList("SELECT nick_name,password_hash,role,banned FROM users WHERE id=? FOR UPDATE",id);
                if(rows.size()!=1)throw new ControlFailure(401,"AUTHENTICATION_REQUIRED");
                security.requireAdmin(id);var row=rows.getFirst();
                boolean matches;try{matches=passwords.matches(currentPassword,(String)row.get("password_hash"));}catch(IllegalArgumentException invalid){matches=false;}
                if(!matches)throw new ControlFailure(400,"CURRENT_PASSWORD_INVALID");
                String username=newUsername==null?(String)row.get("nick_name"):newUsername;
                boolean nameChanged=!username.equals(row.get("nick_name"));
                if(!nameChanged&&newPassword==null)throw new ControlFailure(400,"NO_CREDENTIAL_CHANGES");
                if(newPassword!=null&&passwords.matches(newPassword,(String)row.get("password_hash")))throw new ControlFailure(400,"PASSWORD_UNCHANGED");
                UUID request=UUID.randomUUID();
                mutationAttempted.set(true);
                String hash=newPassword==null?(String)row.get("password_hash"):passwords.encode(newPassword);
                java.util.function.Supplier<Integer> mutation=()->jdbc.update("UPDATE users SET nick_name=?,password_hash=?,updated_at=clock_timestamp() WHERE id=?",username,hash,id);
                if(multiwriter!=null&&multiwriter.enabled())multiwriter.command(id,id,mutation);else mutation.get();
                jdbc.update("INSERT INTO eatwhat_control.credential_change(request_id,user_id,username_changed,password_changed) VALUES(?,?,?,?)",request,id,nameChanged,newPassword!=null);
                return Map.of("username",username,"reauthenticationRequired",true);
            });
            // DB epoch already denies stale tokens, including sessions issued during a racing login.
            try{StpUtil.logout(id);}catch(RuntimeException ignored){/* Persistent fence is authoritative. */}
            return result;
        }catch(ControlFailure failure){throw failure;}catch(DuplicateKeyException conflict){throw new ControlFailure(409,"USERNAME_UNAVAILABLE");}catch(DataAccessException failure){throw new ControlFailure(503,mutationAttempted.get()?"ACCOUNT_UPDATE_OUTCOME_UNKNOWN":"ACCOUNT_UPDATE_UNAVAILABLE");}
    }
}
