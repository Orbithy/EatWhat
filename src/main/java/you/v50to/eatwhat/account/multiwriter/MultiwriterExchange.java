package you.v50to.eatwhat.account.multiwriter;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.util.*;

/** Versioned, authenticated account-operation exchange. Never relays an interactive write. */
public final class MultiwriterExchange {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(MultiwriterExchange.class);
    private volatile String phase="IDLE",lastFailure="";
    public String diagnosticCode(){return lastFailure;}
    public void failed(RuntimeException failure){String sql="";for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException database){sql=Objects.toString(database.getSQLState(),"");break;}String code=phase+":"+failure.getClass().getSimpleName()+":"+sql;if(!code.equals(lastFailure)){lastFailure=code;LOG.warn("Account operation synchronization delayed [{}]",code);}}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final SyncTransactions tx;private final ConsentStore consent;private final MultiwriterAccounts accounts;private final SiteProperties site;
    public MultiwriterExchange(JdbcTemplate jdbc,SyncTransactions tx,ConsentStore consent,MultiwriterAccounts accounts,SiteProperties site){this.jdbc=jdbc;this.tx=tx;this.consent=consent;this.accounts=accounts;this.site=site;}
    public boolean writing(){return accounts.enabled();}
    public JsonNode capabilities(){return JSON.createObjectNode().put("schemaVersion",2).put("active",accounts.enabled()&&accounts.healthy());}
    public ObjectNode batch(long user){return tx.required(()->buildBatch(user));}
    private ObjectNode buildBatch(long user){
        accounts.preserveLegacyWithdrawal(user);
        var snapshot=consent.exportMultiwriter(user);if(snapshot==null)return null;if(snapshot.data()!=null)accounts.initialize(user);
        var changes=accounts.changes(user);if(snapshot.data()==null)changes=changes.stream().filter(c->c.operation().field().equals("consent")).toList();
        ObjectNode body=JSON.createObjectNode().put("schemaVersion",2);body.set("snapshot",JSON.valueToTree(snapshot));body.set("changes",JSON.valueToTree(changes));body.set("witnesses",JSON.valueToTree(snapshot.data()==null?List.of():accounts.witnesses(user)));body.set("sessionKeys",JSON.valueToTree(accounts.sessionKeys()));body.set("reviewDecisions",JSON.valueToTree(snapshot.data()==null?List.of():accounts.decisions(user)));body.set("sessions",JSON.valueToTree(snapshot.data()==null?accounts.sessionFacts(user).stream().filter(SignedSessions.Fact::revoked).toList():accounts.sessionFacts(user)));return body;
    }
    /** Bounded authenticated chunks; the receiver durably stages and atomically applies the complete graph. */
    public List<ObjectNode> pages(long user){
        ObjectNode batch=batch(user);if(batch==null)return List.of();byte[] payload=JSON.writeValueAsBytes(batch);
        if(payload.length<=180*1024)return List.of(batch);
        int count=(payload.length+131071)/131072;if(count>512)throw new IllegalStateException("ACCOUNT_TRANSFER_RESOURCE_LIMIT");
        String digest=you.v50to.eatwhat.sync.EventCodec.sha256(new String(payload,java.nio.charset.StandardCharsets.UTF_8));
        var snapshot=(ObjectNode)batch.get("snapshot").deepCopy();snapshot.putNull("data");List<ObjectNode> pages=new ArrayList<>();
        for(int index=0;index<count;index++){var page=JSON.createObjectNode().put("schemaVersion",2).put("transferId",digest).put("pageIndex",index).put("pageCount",count).put("payload",Base64.getEncoder().encodeToString(Arrays.copyOfRange(payload,index*131072,Math.min(payload.length,(index+1)*131072))));page.set("snapshot",snapshot);page.set("consentChanges",JSON.valueToTree(accounts.changes(user).stream().filter(c->c.operation().field().equals("consent")).toList()));pages.add(page);}return List.copyOf(pages);
    }
    private JsonNode receivePage(JsonNode body){
        var sender=AccountPeerScope.current();if(sender==null||body.size()!=7||body.path("schemaVersion").asInt()!=2)throw new IllegalArgumentException("Invalid account transfer");
        String digest=body.path("transferId").asText();int index=body.path("pageIndex").asInt(-1),count=body.path("pageCount").asInt(-1);
        if(!digest.matches("[0-9a-f]{64}")||count<1||count>512||index<0||index>=count||body.path("payload").asText().length()>174764)throw new IllegalArgumentException("Invalid account transfer bounds");
        byte[] payload=Base64.getDecoder().decode(body.path("payload").asText());var snapshot=JSON.treeToValue(body.get("snapshot"),ConsentModels.Snapshot.class);
        if(!snapshot.sourceSite().equals(sender.siteId())||!snapshot.sourceInstance().equals(sender.instanceId())||!snapshot.policyVersion().equals(consent.policyVersion()))throw new IllegalArgumentException("Account transfer source mismatch");
        var consentChanges=List.of(JSON.treeToValue(body.get("consentChanges"),MultiwriterAccounts.Change[].class));
        if(consentChanges.stream().anyMatch(c->!c.operation().field().equals("consent")))throw new IllegalArgumentException("Invalid transfer consent proof");
        boolean authorized=tx.required(()->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,snapshot.subjectId().toString());
            accounts.preserveLegacyWithdrawal(snapshot.userId());
            consent.prepareMultiwriterSubject(snapshot);jdbc.queryForList("SELECT subject_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",UUID.class,snapshot.subjectId());
            accounts.receive(snapshot.userId(),consentChanges,List.of(),sender);var state=consent.getBySubject(snapshot.subjectId());
            if(!state.status().equals("GRANTED")){consent.finishMultiwriterProjection(snapshot.userId());return false;}
            jdbc.update("DELETE FROM eatwhat_account_multiwriter.transfer_page WHERE subject_id=? AND created_at<clock_timestamp()-interval '24 hours'",snapshot.subjectId());
            jdbc.update("INSERT INTO eatwhat_account_multiwriter.transfer_page(source_site,source_instance,transfer_id,page_index,page_count,subject_id,payload) VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",sender.siteId(),sender.instanceId(),digest,index,count,snapshot.subjectId(),payload);
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT payload=? AND page_count=? AND subject_id=? FROM eatwhat_account_multiwriter.transfer_page WHERE source_site=? AND source_instance=? AND transfer_id=? AND page_index=?",Boolean.class,payload,count,snapshot.subjectId(),sender.siteId(),sender.instanceId(),digest,index)))throw new IllegalArgumentException("Changed immutable account page");return true;
        });
        if(!authorized)return JSON.createObjectNode().put("status","APPLIED");
        var parts=jdbc.queryForList("SELECT payload FROM eatwhat_account_multiwriter.transfer_page WHERE source_site=? AND source_instance=? AND transfer_id=? ORDER BY page_index",byte[].class,sender.siteId(),sender.instanceId(),digest);
        if(parts.size()!=count)return JSON.createObjectNode().put("status","STAGED");
        var combined=new java.io.ByteArrayOutputStream();for(byte[] part:parts)combined.writeBytes(part);String json=combined.toString(java.nio.charset.StandardCharsets.UTF_8);
        if(!digest.equals(you.v50to.eatwhat.sync.EventCodec.sha256(json)))throw new IllegalArgumentException("Invalid account transfer digest");
        JsonNode complete=JSON.readTree(json);var completeSnapshot=(ObjectNode)complete.path("snapshot").deepCopy();completeSnapshot.putNull("data");if(!JSON.treeToValue(completeSnapshot,ConsentModels.Snapshot.class).equals(snapshot))throw new IllegalArgumentException("Account transfer subject mismatch");
        JsonNode result=receiveBatch(complete);jdbc.update("DELETE FROM eatwhat_account_multiwriter.transfer_page WHERE source_site=? AND source_instance=? AND transfer_id=?",sender.siteId(),sender.instanceId(),digest);return result;
    }
    /** Admit each page against current local consent; already admitted network I/O is in flight.
     * The transaction ends before network I/O so reciprocal site sends cannot deadlock local writes. */
    public JsonNode transmit(JsonNode page,java.util.function.Function<JsonNode,JsonNode> send){
        tx.required(()->{var snapshot=JSON.treeToValue(page.get("snapshot"),ConsentModels.Snapshot.class);
            var states=jdbc.queryForList("SELECT status,epoch,revision FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",snapshot.subjectId());
            if(states.size()!=1||!states.getFirst().get("status").equals(snapshot.status())||((Number)states.getFirst().get("epoch")).longValue()!=snapshot.epoch()||((Number)states.getFirst().get("revision")).longValue()!=snapshot.revision())throw new IllegalStateException("ACCOUNT_SEND_CONSENT_CHANGED");return null;});
        return send.apply(page);
    }
    public void synchronize(AccountChannel channel){
        phase="CAPABILITY";
        var capability=channel.call("multiwriter-capabilities",JSON.createObjectNode());
        if(capability.path("schemaVersion").asInt()!=2||!capability.path("active").asBoolean())return;
        phase="SUBJECT_LIST";
        for(long user:jdbc.queryForList("SELECT user_id FROM eatwhat_consent.subject WHERE status IN ('GRANTED','WITHDRAWN') ORDER BY user_id",Long.class)){
            try {
            phase="BUILD_BATCH";var pages=pages(user);
            for(int index=0;index<pages.size();index++){phase="SEND_BATCH";var result=transmit(pages.get(index),page->channel.call("multiwriter-exchange",page));String status=result.path("status").asText();if(!status.equals("APPLIED")&&!(index<pages.size()-1&&status.equals("STAGED")))throw new IllegalStateException("ACCOUNT_PEER_DEFERRED");}
            if(!pages.isEmpty()&&"GRANTED".equals(pages.getFirst().path("snapshot").path("status").asText()))consent.acknowledgeMultiwriterArchive(user);
            } catch(RuntimeException failure){failed(failure);}
        }
        phase="IDLE";lastFailure="";
    }
    public JsonNode receive(JsonNode body){
        try{return body.has("transferId")?receivePage(body):receiveBatch(body);}catch(RuntimeException failure){String sql="";for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException database){sql=Objects.toString(database.getSQLState(),"");break;}String object="";for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException database){var match=java.util.regex.Pattern.compile("permission denied for (?:schema|table|function|relation) ([A-Za-z0-9_.]+)").matcher(Objects.toString(database.getMessage(),""));if(match.find())object=match.group(1);break;}LOG.warn("Account operation receive delayed [RECEIVE:{}:{}:{}]",failure.getClass().getSimpleName(),sql,object);throw failure;}
    }
    private JsonNode receiveBatch(JsonNode body){
        if(body.path("schemaVersion").asInt()!=2||body.size()!=7)throw new IllegalArgumentException("Invalid account schema");
        var sender=AccountPeerScope.current();if(sender==null)throw new IllegalArgumentException("Authenticated account peer required");
        var snapshot=JSON.treeToValue(body.get("snapshot"),ConsentModels.Snapshot.class);
        if(!snapshot.sourceSite().equals(sender.siteId())||!snapshot.sourceInstance().equals(sender.instanceId())||!snapshot.policyVersion().equals(consent.policyVersion()))throw new IllegalArgumentException("Account source mismatch");
        var changes=List.of(JSON.treeToValue(body.get("changes"),MultiwriterAccounts.Change[].class));var witnesses=List.of(JSON.treeToValue(body.get("witnesses"),MultiwriterAccounts.Witness[].class));
        tx.required(()->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,snapshot.subjectId().toString());
            accounts.preserveLegacyWithdrawal(snapshot.userId());
            consent.prepareMultiwriterSubject(snapshot);
            // Merge consent before importing any personal rows. Old granted snapshots cannot bypass a tombstone.
            var consentChanges=changes.stream().filter(c->c.operation().field().equals("consent")).toList();
            var consentOperations=new ArrayList<>(accounts.operations(snapshot.userId()).stream().filter(o->o.field().equals("consent")).toList());consentOperations.addAll(consentChanges.stream().map(MultiwriterAccounts.Change::operation).toList());
            var consentState=new AccountOperationGraph(consentOperations).reduce(snapshot.subjectId(),"consent");
            if(!consentState.complete())throw new IllegalStateException("ACCOUNT_CONSENT_DEPENDENCY_PENDING");
            if(!"GRANTED".equals(consentState.value())){
                accounts.receive(snapshot.userId(),consentChanges,List.of(),sender);
                jdbc.update("UPDATE eatwhat_consent.subject SET epoch=GREATEST(epoch,?),revision=GREATEST(revision,?) WHERE subject_id=?",snapshot.epoch(),snapshot.revision(),snapshot.subjectId());
                consent.finishMultiwriterProjection(snapshot.userId());return null;
            }
            var existing=consent.getBySubject(snapshot.subjectId());
            if(existing!=null&&existing.userId()!=snapshot.userId())throw new IllegalArgumentException("Account identity collision");
            jdbc.update("UPDATE eatwhat_consent.subject SET status='GRANTED',identity_ready=true,source_archived=true,valid_until=clock_timestamp()+interval '100 years' WHERE subject_id=?",snapshot.subjectId());
            boolean hasUser=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,snapshot.userId()));
            if(!hasUser){
                if(snapshot.data()==null||snapshot.data().isNull())throw new IllegalStateException("ACCOUNT_GRANTED_SEED_PENDING");
                ObjectNode data=(ObjectNode)snapshot.data().deepCopy();var user=(ObjectNode)data.path("users").get(0);String origin=user.path("origin_site").asText();
                if(!snapshot.subjectId().toString().equals(user.path("global_id").asText())||snapshot.userId()!=user.path("id").asLong())throw new IllegalArgumentException("Account seed identity mismatch");
                user.put("nick_name","~mw~"+snapshot.userId());
                for(var contact:data.path("contacts")){((ObjectNode)contact).putNull("email");((ObjectNode)contact).putNull("phone");}
                jdbc.update("INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,revision,status,policy_version,identity_ready,backfill_status,source_archived,valid_until) VALUES(?,?,?,?,?,'GRANTED',?,true,'ACCOUNT_COMPLETE_DOMAINS_PENDING',true,clock_timestamp()+interval '100 years') ON CONFLICT(subject_id) DO NOTHING",snapshot.subjectId(),snapshot.userId(),origin,snapshot.epoch(),snapshot.revision(),snapshot.policyVersion());
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT eatwhat_consent.import_account(?,?,?::jsonb)",Boolean.class,snapshot.subjectId(),snapshot.userId(),JSON.writeValueAsString(data))))throw new IllegalStateException("ACCOUNT_SEED_CONFLICT");
            }
            accounts.receive(snapshot.userId(),changes,witnesses,sender);
            accounts.receiveDecisions(snapshot.userId(),List.of(JSON.treeToValue(body.get("reviewDecisions"),MultiwriterAccounts.ReviewDecision[].class)),sender);
            accounts.receiveSessions(List.of(JSON.treeToValue(body.get("sessionKeys"),SignedSessions.PublicKeyRecord[].class)),List.of(JSON.treeToValue(body.get("sessions"),SignedSessions.Fact[].class)),sender,snapshot.subjectId());
            jdbc.update("UPDATE eatwhat_consent.subject SET epoch=GREATEST(epoch,?),revision=GREATEST(revision,?) WHERE subject_id=?",snapshot.epoch(),snapshot.revision(),snapshot.subjectId());
            return null;
        });
        return JSON.createObjectNode().put("status","APPLIED");
    }
}
