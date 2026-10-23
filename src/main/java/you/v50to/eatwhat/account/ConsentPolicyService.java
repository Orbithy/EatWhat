package you.v50to.eatwhat.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.dto.SaveConsentPolicyDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.vo.*;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.net.URI;
import java.util.*;
import java.util.function.Supplier;

/** One authority publishes immutable policy revisions; every site keeps a durable local copy. */
public class ConsentPolicyService {
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final OutboxService outbox;
    private final SiteProperties site;
    private final ConsentPolicyVO initial;
    private final Supplier<NodeIdentity> authority;
    private final List<String> recipients;

    public ConsentPolicyService(JdbcTemplate jdbc, SyncTransactions transactions, OutboxService outbox,
            SiteProperties site, String initialVersion, Supplier<NodeIdentity> authority, List<String> recipients) {
        this.jdbc=jdbc; this.transactions=transactions; this.outbox=outbox; this.site=site;
        this.initial=ConsentPolicyVO.of(initialVersion); this.authority=authority; this.recipients=List.copyOf(recipients);
        transactions.requireSameDataSource(jdbc);
    }

    public NodeIdentity authority() { return authority.get(); }

    public ConsentPolicySettingsVO settings() {
        // Authorization transactions hold a shared lock until commit, preventing a grant
        // from checking one policy and recording consent against another during an edit.
        String lock=TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()?" FOR SHARE":"";
        return read(lock);
    }

    private ConsentPolicySettingsVO read(String lock) {
        return jdbc.queryForObject("SELECT version,url,revision FROM eatwhat_sync.consent_policy WHERE singleton"+lock,
                (r,n)->r.getLong(3)==0 ? new ConsentPolicySettingsVO(initial.version(),initial.url(),0)
                        : new ConsentPolicySettingsVO(r.getString(1),r.getString(2),r.getLong(3)));
    }

    public ConsentPolicyVO current() {
        var value=settings(); return new ConsentPolicyVO(value.version(),value.url());
    }

    public static void validate(String version,String url) {
        if(version==null||!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")||url==null||url.length()>2048
                ||url.isBlank()||url.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))||url.contains("\\"))
            throw new BizException(BizCode.PARAM_INVALID,"政策版本或 URL 格式无效");
        try {
            URI parsed=URI.create(url);
            boolean local=!parsed.isAbsolute()&&url.startsWith("/")&&!url.startsWith("//")&&parsed.getRawAuthority()==null;
            boolean external="https".equalsIgnoreCase(parsed.getScheme())&&parsed.getHost()!=null&&parsed.getUserInfo()==null;
            if(!local&&!external)throw new IllegalArgumentException();
        } catch(IllegalArgumentException invalid) { throw new BizException(BizCode.PARAM_INVALID,"URL 必须是站内绝对路径或 HTTPS 地址"); }
    }

    public ConsentPolicySettingsVO save(SaveConsentPolicyDTO request,long administrator) {
        validate(request.version(),request.url());
        if(request.expectedRevision()==null||request.expectedRevision()<0||request.expectedRevision()>=VersionVector.MAX_COUNTER)
            throw new BizException(BizCode.PARAM_INVALID);
        var local=new NodeIdentity(site.getId(),site.getInstanceId());
        if(!local.equals(authority()))throw new BizException(BizCode.NO_PERMISSION,"请在业务权威站修改政策，配置会自动同步到其他站点");
        return transactions.required(()->{
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND role='admin' AND NOT banned)",Boolean.class,administrator)))
                throw new BizException(BizCode.NO_PERMISSION);
            var previous=read(" FOR UPDATE");
            if(previous.revision()!=request.expectedRevision())throw new BizException(BizCode.DATA_SYNC_PENDING,"政策已被修改，请重新读取后保存");
            if(previous.version().equals(request.version())&&Objects.equals(previous.url(),request.url()))return previous;
            if(!previous.version().equals(request.version())&&(initial.version().equals(request.version())
                    ||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.consent_policy_history WHERE version=?)",Boolean.class,request.version()))))
                throw new BizException(BizCode.STATE_NOT_ALLOWED,"已使用的政策版本不能重新启用，请使用新版本号");
            var change=new ConsentPolicyEvents.Change(previous.revision()+1,request.version(),request.url());
            UUID id=UUID.randomUUID();
            var event=new SyncEvent(id,site.getId(),site.getInstanceId(),ConsentPolicyEvents.AGGREGATE,id,change.revision(),
                    ConsentPolicyEvents.EVENT,1,VersionVector.empty(),VersionVector.empty().increment(site.getId()),
                    List.of(),null,"consent-policy:"+id,id,ConsentPolicyEvents.payload(change));
            apply(event,administrator);
            outbox.append(event,recipients);
            return new ConsentPolicySettingsVO(change.version(),change.url(),change.revision());
        });
    }

    /** Called only inside the same local transaction as the Inbox application. */
    public void apply(SyncEvent event,Long administrator) {
        var change=ConsentPolicyEvents.parse(event.payloadJson());
        var current=read(" FOR UPDATE");
        jdbc.update("""
                INSERT INTO eatwhat_sync.consent_policy_history(revision,event_id,version,url,source_site,source_instance,local_admin_id)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(revision) DO NOTHING
                """,change.revision(),event.eventId(),change.version(),change.url(),event.originSite(),event.originInstanceId(),administrator);
        boolean same=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT event_id=? AND version=? AND url=? AND source_site=? AND source_instance=?
                  FROM eatwhat_sync.consent_policy_history WHERE revision=?
                """,Boolean.class,event.eventId(),change.version(),change.url(),event.originSite(),event.originInstanceId(),change.revision()));
        if(!same)throw new IllegalArgumentException("POLICY_REVISION_IDENTITY_CONFLICT");
        if(change.revision()>current.revision())jdbc.update("""
                UPDATE eatwhat_sync.consent_policy SET revision=?,version=?,url=?,event_id=? WHERE singleton
                """,change.revision(),change.version(),change.url(),event.eventId());
    }
}
