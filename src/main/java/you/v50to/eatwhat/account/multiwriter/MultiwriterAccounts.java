package you.v50to.eatwhat.account.multiwriter;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.util.*;
import java.util.function.Supplier;

/** Local durable account command boundary. No network or authority data source is used here. */
public final class MultiwriterAccounts {
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    public record Change(AccountOperationGraph.Operation operation,String sourceSite,UUID sourceInstance,long actorId,List<UUID> securityHeads,List<UUID> observedWitnesses,UUID actorWitnessId) { @Override public String toString(){return "AccountChange[redacted]";} }
    public record ReviewDecision(UUID decisionId,UUID eventId,UUID subjectId,long actorId,AuthorizationEvidence authorization,boolean approved,Set<UUID> parents,String sourceSite,UUID sourceInstance) { }
    public record ReviewSummary(UUID eventId,UUID subjectId,String field,String state) { }
    public record Witness(UUID witnessId,UUID subjectId,List<UUID> securityHeads,String sourceSite,UUID sourceInstance) { }
    public record AuthorizationEvidence(UUID subjectId,List<UUID> securityHeads,String role,boolean banned,UUID witnessId) {
        public AuthorizationEvidence { Objects.requireNonNull(subjectId);securityHeads=List.copyOf(securityHeads);if(securityHeads.size()>1024||!Set.of("admin","user").contains(role))throw new IllegalArgumentException("Invalid security evidence"); }
        public AuthorizationEvidence(UUID subjectId,List<UUID> securityHeads,String role,boolean banned){this(subjectId,securityHeads,role,banned,null);}
    }
    public enum Assessment { AUTHORIZED,HISTORICAL_VALID,CONCURRENT_REVOCATION_REVIEW,KNOWN_REVOKED,DEPENDENCY_PENDING }
    public record Session(long userId,UUID subjectId,long expiresInSeconds) { }
    private final JdbcTemplate jdbc; private final SyncTransactions transactions;private final SiteProperties site;private final boolean enabled;
    private final SignedSessions signedSessions;
    private final ThreadLocal<Boolean> commandActive=ThreadLocal.withInitial(()->false);
    public MultiwriterAccounts(JdbcTemplate jdbc,SyncTransactions transactions,SiteProperties site,boolean enabled){this.jdbc=jdbc;this.transactions=transactions;this.site=site;this.enabled=enabled;this.signedSessions=new SignedSessions(jdbc,site);transactions.requireSameDataSource(jdbc);}
    public boolean enabled(){return enabled;}
    public boolean inCommand(){return commandActive.get();}
    /** Include aliases hidden by conflict projection; ambiguity does not mean a name is free. */
    public boolean usernameClaimed(String username){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.alias_claim WHERE kind='username' AND value=?)",Boolean.class,username));
    }
    /** Caller must atomically consume a subject-bound recovery challenge before invoking this boundary. */
    public void resetVerifiedPassword(long user,String encodedPassword){
        if(encodedPassword==null||encodedPassword.isBlank()||encodedPassword.length()>500)throw new IllegalArgumentException("Invalid encoded password");
        command(user,user,()->{jdbc.update("UPDATE users SET password_hash=? WHERE id=?",encodedPassword,user);return null;});
    }
    public Long resolveLogin(String identifier){
        if(identifier.matches("id:[1-9][0-9]{0,15}")){long id=Long.parseLong(identifier.substring(3));return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,id))?id:null;}
        if(identifier.startsWith("~mw~"))return null;
        String kind=identifier.contains("@")?"email":identifier.matches("[0-9]{11}")?"phone":"username";
        var ids=jdbc.queryForList("SELECT i.numeric_id FROM eatwhat_account_multiwriter.alias_claim c JOIN eatwhat_identity.entity_identity i ON i.global_id=c.subject_id AND i.entity_type='users' WHERE c.kind=? AND "+(kind.equals("email")?"lower(c.value)=lower(?)":"c.value=?"),Long.class,kind,identifier);
        if(ids.size()>1)return null;if(ids.size()==1)return ids.getFirst();
        // A migrated account may not have issued a v2 command yet. A missing claim does not hide its unchanged alias.
        String sql=kind.equals("username")?"SELECT id FROM users WHERE nick_name=?":"SELECT account_id FROM contacts WHERE "+kind+"=?";
        var legacy=jdbc.queryForList(sql,Long.class,identifier);return legacy.size()==1?legacy.getFirst():null;
    }
    /** An ambiguous verified phone is an existing-account conflict, never a registration invitation. */
    public Long resolvePhoneLogin(String mobile){
        Long claims=jdbc.queryForObject("SELECT count(DISTINCT subject_id) FROM eatwhat_account_multiwriter.alias_claim WHERE kind='phone' AND value=?",Long.class,mobile);
        if(claims!=null&&claims>1)throw new you.v50to.eatwhat.exception.BizException(you.v50to.eatwhat.data.enums.BizCode.ACCOUNT_SECURITY_CONFLICT);
        return resolveLogin(mobile);
    }
    public void initialize(long userId){transactions.required(()->{preserveLegacyWithdrawal(userId);jdbc.queryForObject("SELECT eatwhat_account_multiwriter.initialize(?)",Boolean.class,userId);return null;});}
    /** Preserve a legacy withdrawal before any v2 merge, including tombstones whose users row was erased. */
    public void preserveLegacyWithdrawal(long userId){transactions.required(()->{
        var subjects=jdbc.queryForList("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=? AND status='WITHDRAWN' FOR UPDATE",UUID.class,userId);
        if(!subjects.isEmpty()){
            UUID subject=subjects.getFirst();
            jdbc.update("""
                INSERT INTO eatwhat_account_multiwriter.operation(event_id,subject_id,field,value,source_site,source_instance,actor_id)
                SELECT md5(?::text||':consent:WITHDRAWN')::uuid,?::uuid,'consent','WITHDRAWN','baseline','00000000-0000-0000-0000-000000000000'::uuid,?
                WHERE NOT EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.operation WHERE subject_id=? AND field='consent')
                ON CONFLICT DO NOTHING
                """,subject,subject,userId,subject);
        }
        return null;
    });}
    public <T> T command(long actor,long user,Supplier<T> action){
        if(!enabled)return action.get();
        return transactions.required(()->{initialize(actor);initialize(user);var evidence=capture(actor);jdbc.queryForObject("SELECT eatwhat_account_multiwriter.begin_command(?,?,?)",Boolean.class,actor,user,evidence.witnessId());
            boolean previous=commandActive.get();commandActive.set(true);try{T result=action.get();reviewAccountCommands(user);if(!previous)jdbc.queryForObject("SELECT eatwhat_account_multiwriter.end_command()",Boolean.class);return result;}finally{commandActive.set(previous);}});
    }
    private UUID subject(long user){return jdbc.queryForObject("SELECT coalesce((SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?),(SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=?))",UUID.class,user,user);}
    public List<AccountOperationGraph.Operation> operations(long user){return operations(subject(user));}
    private List<AccountOperationGraph.Operation> operations(UUID subject){return jdbc.query("SELECT event_id,subject_id,field,value,parents FROM eatwhat_account_multiwriter.operation WHERE subject_id=? ORDER BY local_order",(r,n)->new AccountOperationGraph.Operation(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),Set.of((UUID[])r.getArray(5).getArray())),subject);}
    public AccountOperationGraph.Reduced state(long user,String field){return new AccountOperationGraph(effectiveOperations(subject(user))).reduce(subject(user),field);}
    private List<AccountOperationGraph.Operation> effectiveOperations(UUID subject){
        var all=operations(subject);Set<UUID> blocked=new HashSet<>(jdbc.queryForList("SELECT event_id FROM eatwhat_account_multiwriter.authorization_review WHERE subject_id=? AND state<>'APPROVED'",UUID.class,subject));
        boolean changed;do{changed=false;for(var op:all)if(!blocked.contains(op.id())&&op.parents().stream().anyMatch(blocked::contains))changed|=blocked.add(op.id());}while(changed);
        return all.stream().filter(op->!blocked.contains(op.id())).toList();
    }
    private void reviewAccountCommands(long actor){
        for(UUID target:jdbc.queryForList("SELECT DISTINCT subject_id FROM eatwhat_account_multiwriter.operation WHERE actor_id=? AND source_site<>'baseline'",UUID.class,actor)){
            var ids=jdbc.queryForList("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=?",Long.class,target);if(ids.isEmpty())continue;long user=ids.getFirst();boolean changed=false;
            for(var change:changes(user)){
                var op=change.operation();if(change.actorId()!=actor||change.sourceSite().equals("baseline")||actor==user&&!Set.of("role","banned").contains(op.field()))continue;
                var assessment=assess(new AuthorizationEvidence(subject(actor),change.securityHeads(),"admin",false,change.actorWitnessId()));
                if(assessment==Assessment.CONCURRENT_REVOCATION_REVIEW||assessment==Assessment.KNOWN_REVOKED){
                    changed|=jdbc.update("INSERT INTO eatwhat_account_multiwriter.authorization_review(event_id,subject_id,field,change,state) VALUES(?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",op.id(),target,op.field(),JSON.writeValueAsString(change),assessment==Assessment.KNOWN_REVOKED?"REJECTED":"REVIEW")>0;
                }
            }
            // A decision can arrive before the revocation that creates its local review row.
            if(changed)projectDecisions(user);
        }
        // Revocation of a reviewer also re-evaluates decisions that already took effect locally.
        for(long targetUser:jdbc.queryForList("SELECT DISTINCT s.user_id FROM eatwhat_account_multiwriter.review_decision d JOIN eatwhat_consent.subject s ON s.subject_id=d.subject_id WHERE (d.decision->>'actorId')::bigint=?",Long.class,actor))projectDecisions(targetUser);
    }
    public AuthorizationEvidence capture(long actor){
        if(!enabled)throw new IllegalStateException("ACCOUNT_MULTIWRITER_DISABLED");
        return transactions.required(()->{initialize(actor);UUID subject=subject(actor);var graph=new AccountOperationGraph(effectiveOperations(subject));var role=graph.reduce(subject,"role");var banned=graph.reduce(subject,"banned");
        if(!role.complete()||role.conflict()||!banned.complete()||banned.conflict())throw new IllegalStateException("ACCOUNT_SECURITY_PENDING");
        Set<UUID> heads=new TreeSet<>(role.heads());heads.addAll(banned.heads());
        UUID witness=UUID.randomUUID();jdbc.update("INSERT INTO eatwhat_account_multiwriter.authorization_witness(witness_id,subject_id,security_heads,source_site,source_instance) VALUES(?,?,?::uuid[],?,?)",witness,subject,array(heads),site.getId(),site.getInstanceId());
        return new AuthorizationEvidence(subject,List.copyOf(heads),role.value(),Boolean.parseBoolean(banned.value()),witness);});
    }
    public Assessment assess(AuthorizationEvidence evidence){
        var all=operations(evidence.subjectId());Map<UUID,AccountOperationGraph.Operation> byId=new HashMap<>();all.forEach(o->byId.put(o.id(),o));
        if(evidence.securityHeads().isEmpty()||!byId.keySet().containsAll(evidence.securityHeads()))return Assessment.DEPENDENCY_PENDING;
        if(evidence.witnessId()==null)return Assessment.KNOWN_REVOKED;
        var witnesses=jdbc.queryForList("SELECT subject_id,security_heads FROM eatwhat_account_multiwriter.authorization_witness WHERE witness_id=?",evidence.witnessId());
        if(witnesses.size()!=1)return Assessment.DEPENDENCY_PENDING;
        try{if(!witnesses.getFirst().get("subject_id").equals(evidence.subjectId())||!Set.of((UUID[])((java.sql.Array)witnesses.getFirst().get("security_heads")).getArray()).equals(Set.copyOf(evidence.securityHeads())))return Assessment.KNOWN_REVOKED;}catch(java.sql.SQLException invalid){throw new IllegalStateException("ACCOUNT_WITNESS_UNAVAILABLE");}
        if(evidence.securityHeads().stream().anyMatch(h->!Set.of("role","banned").contains(byId.get(h).field())))return Assessment.KNOWN_REVOKED;
        Set<UUID> ancestors=new HashSet<>();for(UUID id:evidence.securityHeads())collect(id,byId,ancestors);
        var observed=new AccountOperationGraph(all.stream().filter(o->ancestors.contains(o.id())).toList());
        var role=observed.reduce(evidence.subjectId(),"role");var banned=observed.reduce(evidence.subjectId(),"banned");
        if(!role.complete()||!banned.complete())return Assessment.DEPENDENCY_PENDING;
        if(!Objects.equals(role.value(),evidence.role())||!Objects.equals(banned.value(),Boolean.toString(evidence.banned()))||evidence.banned())return Assessment.KNOWN_REVOKED;
        var current=new AccountOperationGraph(effectiveOperations(evidence.subjectId()));var currentRole=current.reduce(evidence.subjectId(),"role");var currentBan=current.reduce(evidence.subjectId(),"banned");
        if(!currentRole.complete()||!currentBan.complete())return Assessment.DEPENDENCY_PENDING;
        if("true".equals(currentBan.value())||("admin".equals(evidence.role())&&!"admin".equals(currentRole.value()))){
            Set<UUID> restrictions=new HashSet<>();if("true".equals(currentBan.value()))currentBan.heads().stream().filter(h->"true".equals(byId.get(h).value())).forEach(restrictions::add);
            if("admin".equals(evidence.role())&&!"admin".equals(currentRole.value()))currentRole.heads().stream().filter(h->!"admin".equals(byId.get(h).value())).forEach(restrictions::add);
            boolean historical=restrictions.stream().allMatch(id->Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?=ANY(observed_witnesses) FROM eatwhat_account_multiwriter.operation WHERE event_id=?",Boolean.class,evidence.witnessId(),id)));
            return historical?Assessment.HISTORICAL_VALID:Assessment.CONCURRENT_REVOCATION_REVIEW;
        }
        return Assessment.AUTHORIZED;
    }
    private static void collect(UUID id,Map<UUID,AccountOperationGraph.Operation> all,Set<UUID> result){ArrayDeque<UUID> pending=new ArrayDeque<>();pending.add(id);while(!pending.isEmpty()){UUID current=pending.remove();if(result.add(current)&&all.containsKey(current))pending.addAll(all.get(current).parents());}}
    private String securityHash(long user){
        UUID subject=subject(user);var graph=new AccountOperationGraph(effectiveOperations(subject));var values=new ArrayList<String>();
        for(String field:List.of("password","banned","role","verification")){
            var state=graph.reduce(subject,field);if(!state.complete()||state.conflict()||field.equals("banned")&&"true".equals(state.value()))throw new IllegalStateException("ACCOUNT_SECURITY_CONFLICT_OR_REVOKED");
            values.add(field+":"+new TreeSet<>(state.heads()));
        }
        var username=graph.reduce(subject,"username");if(!username.complete())throw new IllegalStateException("ACCOUNT_IDENTITY_PENDING");values.add("username:"+new TreeSet<>(username.heads()));
        var consent=graph.reduce(subject,"consent");if(site.getMode()==SiteProperties.Mode.EXTERNAL&&consent.complete()&&"WITHDRAWN".equals(consent.value()))throw new IllegalStateException("ACCOUNT_CONSENT_WITHDRAWN");
        values.add("consent:"+new TreeSet<>(consent.heads()));return EventCodec.sha256(String.join(";",values));
    }
    public Session registerSession(String token,long user,long ttl){
        if(ttl<1||ttl>2592000)throw new IllegalArgumentException("Invalid session lifetime");
        return transactions.required(()->{initialize(user);var subject=subject(user);String hash=securityHash(user);
            int inserted=jdbc.update("INSERT INTO eatwhat_account_multiwriter.session(token_hash,user_id,subject_id,source_site,security_hash,expires_at) VALUES(?,?,?,?,?,clock_timestamp()+(? * interval '1 second')) ON CONFLICT DO NOTHING",EventCodec.sha256(token),user,subject,site.getId(),hash,ttl);
            if(inserted==1){var expiry=jdbc.queryForObject("SELECT expires_at FROM eatwhat_account_multiwriter.session WHERE token_hash=?",java.sql.Timestamp.class,EventCodec.sha256(token));signedSessions.sign(EventCodec.sha256(token),user,subject,hash,expiry.toInstant(),false);}
            return authorizeSession(token);
        });
    }
    public Session authorizeSession(String token){
        var rows=jdbc.queryForList("SELECT user_id,subject_id,security_hash,extract(epoch FROM expires_at-clock_timestamp())::bigint AS ttl FROM eatwhat_account_multiwriter.session WHERE token_hash=? AND NOT revoked AND expires_at>clock_timestamp()",EventCodec.sha256(token));
        if(rows.size()!=1)throw new IllegalStateException("LOCAL_SESSION_INVALID");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.session_fact f JOIN eatwhat_account_multiwriter.signing_key k ON k.key_id=f.fact->>'keyId' WHERE f.token_hash=? AND k.revoked AND NOT (f.fact->>'revoked')::boolean)",Boolean.class,EventCodec.sha256(token))))throw new IllegalStateException("LOCAL_SESSION_SIGNER_REVOKED");
        var row=rows.getFirst();long user=((Number)row.get("user_id")).longValue();
        if(!securityHash(user).equals(row.get("security_hash")))throw new IllegalStateException("LOCAL_SESSION_REVOKED");
        return new Session(user,(UUID)row.get("subject_id"),Math.max(1,((Number)row.get("ttl")).longValue()));
    }
    public void revokeSession(String token){transactions.required(()->{String hash=EventCodec.sha256(token);var rows=jdbc.queryForList("SELECT user_id,subject_id,security_hash,expires_at FROM eatwhat_account_multiwriter.session WHERE token_hash=?",hash);if(rows.size()==1){var row=rows.getFirst();signedSessions.sign(hash,((Number)row.get("user_id")).longValue(),(UUID)row.get("subject_id"),(String)row.get("security_hash"),((java.sql.Timestamp)row.get("expires_at")).toInstant(),true);jdbc.update("INSERT INTO eatwhat_account_multiwriter.session_revocation(token_hash) VALUES(?) ON CONFLICT DO NOTHING",hash);}jdbc.update("UPDATE eatwhat_account_multiwriter.session SET revoked=true WHERE token_hash=?",hash);return null;});}
    List<SignedSessions.PublicKeyRecord> sessionKeys(){return signedSessions.publicKeys();}
    List<SignedSessions.Fact> sessionFacts(long user){return signedSessions.facts(subject(user));}
    void receiveSessions(List<SignedSessions.PublicKeyRecord> keys,List<SignedSessions.Fact> facts,you.v50to.eatwhat.sync.transport.NodeIdentity peer,UUID subject){signedSessions.receive(keys,facts,peer,subject);}
    public void registered(long user,boolean grant,String policy){transactions.required(()->{
        initialize(user);UUID subject=subject(user);
        jdbc.update("INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,revision,status,policy_version,valid_until,identity_ready,backfill_status,source_archived) VALUES(?,?,?,1,1,?,?,clock_timestamp()+interval '100 years',true,'ACCOUNT_COMPLETE_DOMAINS_PENDING',?)",subject,user,jdbc.queryForObject("SELECT origin_site FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",String.class,user),grant?"GRANTED":"NOT_GRANTED",policy,site.getMode()==SiteProperties.Mode.CAMPUS);
        command(user,user,()->{jdbc.queryForObject("SELECT eatwhat_account_multiwriter.append_local(?,?,?)",UUID.class,user,"consent",grant?"GRANTED":"NOT_GRANTED");return null;});return null;
    });}
    public void consent(long user,boolean granted,String policy){transactions.required(()->{
        // Upgraded accounts have identities but no consent subject until their first grant.
        jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_consent.subject WHERE user_id=?)",Boolean.class,user)))registered(user,false,policy);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT status=? AND policy_version=? FROM eatwhat_consent.subject WHERE user_id=?",Boolean.class,granted?"GRANTED":"WITHDRAWN",policy,user)))return null;
        return command(user,user,()->{
        jdbc.queryForObject("SELECT eatwhat_account_multiwriter.append_local(?,?,?)",UUID.class,user,"consent",granted?"GRANTED":"WITHDRAWN");
        jdbc.update("UPDATE eatwhat_consent.subject SET epoch=epoch+1,revision=revision+1,status=?,policy_version=?,valid_until=CASE WHEN ? THEN clock_timestamp()+interval '100 years' ELSE NULL END,identity_ready=?,withdrawal_request_id=NULL,local_dirty=false,backfill_status=?,effects_pending=true,effects_version=effects_version+1 WHERE user_id=?",granted?"GRANTED":"WITHDRAWN",policy,granted,granted,granted?"ACCOUNT_COMPLETE_DOMAINS_PENDING":"WITHDRAWN",user);
        if(!granted)jdbc.update("UPDATE eatwhat_account_multiwriter.session SET revoked=true WHERE user_id=?",user);return null;
        });
    });}
    public boolean healthy(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton",Boolean.class));}
    public List<Change> changes(long user){return jdbc.query("SELECT event_id,subject_id,field,value,parents,source_site,source_instance,actor_id,security_heads,observed_witnesses,actor_witness FROM eatwhat_account_multiwriter.operation WHERE subject_id=? ORDER BY local_order",(r,n)->new Change(new AccountOperationGraph.Operation(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),Set.of((UUID[])r.getArray(5).getArray())),r.getString(6),r.getObject(7,UUID.class),r.getLong(8),List.of((UUID[])r.getArray(9).getArray()),List.of((UUID[])r.getArray(10).getArray()),r.getObject(11,UUID.class)),subject(user));}
    public List<Witness> witnesses(long user){return jdbc.query("SELECT witness_id,subject_id,security_heads,source_site,source_instance FROM eatwhat_account_multiwriter.authorization_witness WHERE subject_id=? ORDER BY local_order",(r,n)->new Witness(r.getObject(1,UUID.class),r.getObject(2,UUID.class),List.of((UUID[])r.getArray(3).getArray()),r.getString(4),r.getObject(5,UUID.class)),subject(user));}
    public void receive(long user,List<Change> changes,List<Witness> witnesses,you.v50to.eatwhat.sync.transport.NodeIdentity sender){transactions.required(()->{
        UUID subject=subject(user);var previous=operations(subject);List<AccountOperationGraph.Operation> combined=new ArrayList<>(previous);combined.addAll(changes.stream().map(Change::operation).toList());var graph=new AccountOperationGraph(combined);
        for(var witness:witnesses){
            if(!witness.subjectId().equals(subject)||witness.securityHeads().size()>1024)throw new IllegalArgumentException("Invalid witness scope");
            boolean peerWitness=witness.sourceSite().equals(sender.siteId())&&witness.sourceInstance().equals(sender.instanceId());
            boolean returnedLocalWitness=witness.sourceSite().equals(site.getId())&&witness.sourceInstance().equals(site.getInstanceId())&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.authorization_witness WHERE witness_id=?)",Boolean.class,witness.witnessId()));
            if(!peerWitness&&!returnedLocalWitness)throw new IllegalArgumentException("Invalid witness origin");
            int inserted=jdbc.update("INSERT INTO eatwhat_account_multiwriter.authorization_witness(witness_id,subject_id,security_heads,source_site,source_instance) VALUES(?,?,?::uuid[],?,?) ON CONFLICT DO NOTHING",witness.witnessId(),subject,array(witness.securityHeads()),witness.sourceSite(),witness.sourceInstance());
            if(inserted==0&&!witnesses(user).contains(witness))throw new IllegalArgumentException("Changed immutable witness");
        }
        for(var change:changes){var op=change.operation();
            if(!op.subjectId().equals(subject))throw new IllegalArgumentException("Wrong account subject");
            if(previous.contains(op))continue;
            if(!graph.reduce(subject,op.field()).complete())throw new IllegalStateException("ACCOUNT_DEPENDENCY_PENDING");
            boolean baseline=change.sourceSite().equals("baseline")&&change.sourceInstance().equals(new UUID(0,0))&&change.actorId()==user&&change.actorWitnessId()==null&&change.securityHeads().isEmpty()&&op.parents().isEmpty()&&op.id().equals(UUID.fromString(md5(subject+":"+op.field()+":"+op.value())));
            if(!baseline&&(!change.sourceSite().equals(sender.siteId())||!change.sourceInstance().equals(sender.instanceId())))throw new IllegalArgumentException("Wrong account source");
            if(!baseline){
                if(change.actorId()!=user||Set.of("role","banned").contains(op.field())){
                    UUID actorSubject=subject(change.actorId());if(actorSubject==null)throw new IllegalStateException("ACCOUNT_ACTOR_DEPENDENCY_PENDING");
                    var assessment=assess(new AuthorizationEvidence(actorSubject,change.securityHeads(),"admin",false,change.actorWitnessId()));
                    if(assessment==Assessment.DEPENDENCY_PENDING)throw new IllegalStateException("ACCOUNT_ACTOR_DEPENDENCY_PENDING");
                    if(assessment==Assessment.CONCURRENT_REVOCATION_REVIEW||assessment==Assessment.KNOWN_REVOKED){
                        jdbc.update("INSERT INTO eatwhat_account_multiwriter.authorization_review(event_id,subject_id,field,change,state) VALUES(?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",op.id(),subject,op.field(),JSON.writeValueAsString(change),assessment==Assessment.KNOWN_REVOKED?"REJECTED":"REVIEW");continue;
                    }
                }
            }
            jdbc.update("INSERT INTO eatwhat_account_multiwriter.operation(event_id,subject_id,field,value,parents,source_site,source_instance,actor_id,security_heads,observed_witnesses,actor_witness) VALUES(?,?,?,?,?::uuid[],?,?,?,?::uuid[],?::uuid[],?)",op.id(),subject,op.field(),op.value(),array(op.parents()),change.sourceSite(),change.sourceInstance(),change.actorId(),array(change.securityHeads()),array(change.observedWitnesses()),change.actorWitnessId());
        }
        graph=new AccountOperationGraph(effectiveOperations(subject));
        for(String field:AccountOperationGraph.FIELDS){var value=graph.reduce(subject,field);if(!value.complete())continue;
            if(value.conflict())jdbc.update("INSERT INTO eatwhat_account_multiwriter.conflict(subject_id,field,heads) VALUES(?,?,?::uuid[]) ON CONFLICT(subject_id,field) DO UPDATE SET heads=EXCLUDED.heads",subject,field,array(value.heads()));
            else {jdbc.update("DELETE FROM eatwhat_account_multiwriter.conflict WHERE subject_id=? AND field=?",subject,field);
                if(Set.of("password","username","role","banned","email","phone","verification","display_name").contains(field)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,user)))jdbc.queryForObject("SELECT eatwhat_account_multiwriter.project_field(?,?,?)",Boolean.class,user,field,value.value());
                if(field.equals("consent"))jdbc.update("UPDATE eatwhat_consent.subject SET status=?,identity_ready=?,valid_until=CASE WHEN ? THEN clock_timestamp()+interval '100 years' ELSE NULL END,withdrawal_request_id=NULL,local_dirty=false,source_archived=true WHERE user_id=?",value.value(),value.value().equals("GRANTED"),value.value().equals("GRANTED"),user);
            }
        }
        reviewAccountCommands(user);
        jdbc.queryForObject("SELECT eatwhat_account_multiwriter.end_command()",Boolean.class);return null;
    });}
    public List<ReviewSummary> reviews(){return jdbc.query("SELECT event_id,subject_id,field,state FROM eatwhat_account_multiwriter.authorization_review ORDER BY created_at LIMIT 500",(r,n)->new ReviewSummary(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4)));}
    public List<ReviewDecision> decisions(long user){return jdbc.query("SELECT decision::text FROM eatwhat_account_multiwriter.review_decision WHERE subject_id=? ORDER BY created_at,decision_id",(r,n)->JSON.readValue(r.getString(1),ReviewDecision.class),subject(user));}
    public void decideReview(long actor,UUID event,boolean approved){transactions.required(()->{
        var proof=capture(actor);if(!proof.role().equals("admin")||proof.banned())throw new IllegalStateException("ACCOUNT_ADMIN_REQUIRED");
        var rows=jdbc.queryForList("SELECT subject_id FROM eatwhat_account_multiwriter.authorization_review WHERE event_id=? FOR UPDATE",UUID.class,event);if(rows.size()!=1)throw new IllegalArgumentException("Account review missing");UUID subject=rows.getFirst();
        long user=jdbc.queryForObject("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=?",Long.class,subject);var known=decisions(user).stream().filter(d->d.eventId().equals(event)).toList();Set<UUID> heads=new HashSet<>();known.forEach(d->heads.add(d.decisionId()));known.forEach(d->heads.removeAll(d.parents()));
        var decision=new ReviewDecision(UUID.randomUUID(),event,subject,actor,proof,approved,Set.copyOf(heads),site.getId(),site.getInstanceId());persistDecision(decision);projectDecisions(user);return null;
    });}
    void receiveDecisions(long user,List<ReviewDecision> values,you.v50to.eatwhat.sync.transport.NodeIdentity sender){
        for(var decision:values){
            if(!decision.subjectId().equals(subject(user))||decision.parents().size()>1024||decision.parents().contains(decision.decisionId())||!decision.authorization().subjectId().equals(subject(decision.actorId())))throw new IllegalArgumentException("Invalid account review decision");
            var existing=jdbc.queryForList("SELECT decision::text FROM eatwhat_account_multiwriter.review_decision WHERE decision_id=?",String.class,decision.decisionId());
            if(existing.isEmpty()&&(!decision.sourceSite().equals(sender.siteId())||!decision.sourceInstance().equals(sender.instanceId())))throw new IllegalArgumentException("Account decision source mismatch");
            if(assess(decision.authorization())==Assessment.DEPENDENCY_PENDING)throw new IllegalStateException("ACCOUNT_DECISION_DEPENDENCY_PENDING");
            persistDecision(decision);
        }
        projectDecisions(user);
    }
    private void persistDecision(ReviewDecision decision){String encoded=JSON.writeValueAsString(decision);int inserted=jdbc.update("INSERT INTO eatwhat_account_multiwriter.review_decision(decision_id,event_id,subject_id,decision) VALUES(?,?,?,?::jsonb) ON CONFLICT DO NOTHING",decision.decisionId(),decision.eventId(),decision.subjectId(),encoded);
        if(inserted==0&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT decision=?::jsonb FROM eatwhat_account_multiwriter.review_decision WHERE decision_id=?",Boolean.class,encoded,decision.decisionId())))throw new IllegalArgumentException("Changed immutable review decision");}
    private void projectDecisions(long user){
        var decisions=decisions(user);Map<UUID,ReviewDecision> byId=new HashMap<>();decisions.forEach(d->byId.put(d.decisionId(),d));
        for(UUID event:decisions.stream().map(ReviewDecision::eventId).collect(java.util.stream.Collectors.toSet())){
            var candidates=decisions.stream().filter(d->d.eventId().equals(event)).toList();new AccountOperationGraph(candidates.stream().map(d->new AccountOperationGraph.Operation(d.decisionId(),d.subjectId(),"role",d.approved()?"admin":"user",d.parents())).toList());Set<UUID> heads=new HashSet<>();candidates.forEach(d->heads.add(d.decisionId()));
            for(var candidate:candidates)for(UUID parent:candidate.parents()){if(!byId.containsKey(parent))throw new IllegalStateException("ACCOUNT_DECISION_PARENT_PENDING");if(!byId.get(parent).eventId().equals(event))throw new IllegalArgumentException("Cross-review decision parent");heads.remove(parent);}
            if(heads.isEmpty())throw new IllegalArgumentException("Cyclic account decision");
            boolean approval=true,review=false;for(UUID head:heads){var candidate=byId.get(head);var assessment=assess(candidate.authorization());if(!candidate.authorization().role().equals("admin")||assessment==Assessment.KNOWN_REVOKED)approval=false;else if(assessment==Assessment.CONCURRENT_REVOCATION_REVIEW||assessment==Assessment.DEPENDENCY_PENDING)review=true;approval&=candidate.approved();}
            var rows=jdbc.queryForList("SELECT change::text FROM eatwhat_account_multiwriter.authorization_review WHERE event_id=?",String.class,event);if(rows.isEmpty())continue;
            jdbc.update("UPDATE eatwhat_account_multiwriter.authorization_review SET state=? WHERE event_id=?",review?"REVIEW":approval?"APPROVED":"REJECTED",event);
            if(approval&&!review){var change=JSON.readValue(rows.getFirst(),Change.class);var op=change.operation();jdbc.update("INSERT INTO eatwhat_account_multiwriter.operation(event_id,subject_id,field,value,parents,source_site,source_instance,actor_id,security_heads,observed_witnesses,actor_witness) VALUES(?,?,?,?,?::uuid[],?,?,?,?::uuid[],?::uuid[],?) ON CONFLICT DO NOTHING",op.id(),op.subjectId(),op.field(),op.value(),array(op.parents()),change.sourceSite(),change.sourceInstance(),change.actorId(),array(change.securityHeads()),array(change.observedWitnesses()),change.actorWitnessId());}
        }
        var graph=new AccountOperationGraph(effectiveOperations(subject(user)));for(String field:List.of("password","username","email","phone","verification","role","banned","display_name")){var state=graph.reduce(subject(user),field);if(state.complete()&&!state.conflict())jdbc.queryForObject("SELECT eatwhat_account_multiwriter.project_field(?,?,?)",Boolean.class,user,field,state.value());}
        jdbc.queryForObject("SELECT eatwhat_account_multiwriter.end_command()",Boolean.class);
    }
    private static String md5(String input){try{String h=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));return h.substring(0,8)+"-"+h.substring(8,12)+"-"+h.substring(12,16)+"-"+h.substring(16,20)+"-"+h.substring(20);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String array(Collection<UUID> ids){return "{"+ids.stream().sorted().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))+"}";}
}
