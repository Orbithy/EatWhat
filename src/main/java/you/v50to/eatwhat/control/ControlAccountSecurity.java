package you.v50to.eatwhat.control;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.po.User;
import java.util.*;

/** A committed DB version denies stale tokens even when Redis logout fails or races a login. */
public final class ControlAccountSecurity {
    private static final String VERSION="control:credentialVersion";
    private final JdbcTemplate jdbc;
    private final SiteProperties site;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts multiwriter;
    public ControlAccountSecurity(JdbcTemplate jdbc,SiteProperties site){this.jdbc=jdbc;this.site=site;}
    public boolean authority(){return site.getMode()!=SiteProperties.Mode.EXTERNAL;}
    private Map<String,Object> snapshot(long id){
        try{
            var rows=jdbc.queryForList("SELECT u.nick_name,u.password_hash,u.role,u.banned,coalesce(v.version,0) AS version FROM users u LEFT JOIN eatwhat_control.credential_version v ON v.user_id=u.id WHERE u.id=?",id);
            if(rows.size()!=1)throw new ControlFailure(401,"AUTHENTICATION_REQUIRED");return rows.getFirst();
        }catch(DataAccessException failure){throw new ControlFailure(503,"ACCOUNT_SECURITY_UNAVAILABLE");}
    }
    /** Capture the epoch with the exact hash/name that was checked, before issuing a token. */
    public Long loginVersion(User user){
        if(!authority())return null;
        var row=snapshot(user.getId());long version=((Number)row.get("version")).longValue();
        if(!"admin".equals(row.get("role"))&&version==0)return null;
        if(!Objects.equals(user.getUserName(),row.get("nick_name"))||!Objects.equals(user.getPasswordHash(),row.get("password_hash"))||!Objects.equals(user.getRole(),row.get("role"))||Boolean.TRUE.equals(row.get("banned")))throw new ControlFailure(401,"ACCOUNT_CREDENTIALS_CHANGED");
        return version;
    }
    public void stamp(Long version){if(version!=null)StpUtil.getTokenSession().set(VERSION,version);}
    public void requireSession(long id){
        if(!authority())return;
        var row=snapshot(id);long version=((Number)row.get("version")).longValue();
        if(!"admin".equals(row.get("role"))&&version==0)return;
        Object issued=StpUtil.getTokenSession().get(VERSION);
        if(Boolean.TRUE.equals(row.get("banned"))||(version>0&&(!(issued instanceof Number number)||number.longValue()!=version)))throw new ControlFailure(401,"ACCOUNT_CREDENTIALS_CHANGED");
    }
    /** Business sessions use their signed causal security state; control-plane sessions keep the local credential fence. */
    public boolean independentBusinessAuthentication(){return multiwriter!=null&&multiwriter.enabled();}
    public void requireBusinessSession(long id){
        if(!independentBusinessAuthentication()){requireSession(id);return;}
        try{var session=multiwriter.authorizeSession(StpUtil.getTokenValue());if(session.userId()!=id)throw new IllegalStateException("Session subject mismatch");}
        catch(RuntimeException invalid){throw new ControlFailure(401,"ACCOUNT_CREDENTIALS_CHANGED");}
    }
    public void requireAdmin(long id){requireSession(id);var row=snapshot(id);if(!"admin".equals(row.get("role"))||Boolean.TRUE.equals(row.get("banned")))throw new ControlFailure(403,"ADMIN_REQUIRED");}
}
