package you.v50to.eatwhat.account.multiwriter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.EmailService;
import you.v50to.eatwhat.sync.SyncTransactions;

/** Recovery requires the existing verified mailbox, bound to the local issuing site and exact subject. */
@Service
@ConditionalOnProperty(name="app.account.multiwriter.enabled",havingValue="true")
public final class AccountPasswordRecovery {
    private final JdbcTemplate jdbc;private final MultiwriterAccounts accounts;private final EmailService mail;private final PasswordEncoder passwords;private final SyncTransactions transactions;
    public AccountPasswordRecovery(JdbcTemplate jdbc,MultiwriterAccounts accounts,EmailService mail,PasswordEncoder passwords,SyncTransactions transactions){this.jdbc=jdbc;this.accounts=accounts;this.mail=mail;this.passwords=passwords;this.transactions=transactions;}
    public void request(String identifier,String clientIp){
        Long user=accounts.resolveLogin(identifier);if(user==null)throw new BizException(BizCode.USER_NOT_FOUND);
        String email=recoveryEmail(user);if(!accounts.state(user,"password").conflict())throw new BizException(BizCode.STATE_NOT_ALLOWED,"账号没有待恢复的密码冲突");
        mail.sendVerificationLink(email,user,clientIp,EmailService.EmailPurpose.RECOVERY);
    }
    public void reset(String token,String newPassword){
        if(newPassword==null||newPassword.length()<8||newPassword.length()>128)throw new BizException(BizCode.STATE_NOT_ALLOWED,"密码长度须为8到128位");
        var challenge=mail.verifyToken(token,EmailService.EmailPurpose.RECOVERY);
        transactions.required(()->{jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,challenge.userId());String current=recoveryEmail(challenge.userId());
            if(!current.equalsIgnoreCase(challenge.email())||!accounts.state(challenge.userId(),"password").conflict())throw new BizException(BizCode.STATE_NOT_ALLOWED,"恢复凭证对应的账号状态已变化，请重新验证");
            accounts.resetVerifiedPassword(challenge.userId(),passwords.encode(newPassword));return null;});
    }
    /** Explicitly privileged recovery after the administrator's identity verification; never an anonymous fallback. */
    public void resetByAdministrator(long actor,long user,String newPassword){
        if(newPassword==null||newPassword.length()<8||newPassword.length()>128)throw new BizException(BizCode.PARAM_INVALID);
        accounts.command(actor,user,()->{if(!"admin".equals(accounts.state(actor,"role").value())||!"false".equals(accounts.state(actor,"banned").value()))throw new BizException(BizCode.NO_PERMISSION);if(!accounts.state(user,"password").conflict())throw new BizException(BizCode.STATE_NOT_ALLOWED,"账号没有待恢复的密码冲突");jdbc.update("UPDATE users SET password_hash=? WHERE id=?",passwords.encode(newPassword),user);return null;});
    }
    private String recoveryEmail(long user){
        var rows=jdbc.queryForList("SELECT banned FROM users WHERE id=?",Boolean.class,user);if(rows.size()!=1||rows.getFirst())throw new BizException(BizCode.USER_DISABLED);
        var emails=jdbc.queryForList("SELECT lower(verified_email) FROM verifications WHERE account_id=? AND verified AND verified_email IS NOT NULL UNION SELECT lower(email::text) FROM contacts WHERE account_id=? AND email IS NOT NULL",String.class,user,user);
        if(emails.size()!=1||emails.getFirst().isBlank())throw new BizException(BizCode.STATE_NOT_ALLOWED,"需要账号已绑定且无冲突的验证邮箱");String email=emails.getFirst();
        boolean collision=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM contacts WHERE account_id<>? AND lower(email::text)=?) OR EXISTS(SELECT 1 FROM verifications WHERE account_id<>? AND verified AND lower(verified_email)=?) OR EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.alias_claim a JOIN eatwhat_identity.entity_identity i ON i.global_id=a.subject_id AND i.entity_type='users' WHERE a.kind='email' AND i.numeric_id<>? AND lower(a.value)=?)",Boolean.class,user,email,user,email,user,email));
        if(collision)throw new BizException(BizCode.STATE_NOT_ALLOWED,"验证邮箱存在冲突，需要管理员核实身份");return email;
    }
}
