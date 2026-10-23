package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts;
import you.v50to.eatwhat.config.SiteProperties;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Each side owns its database; these tests have no authority RPC or shared auth store. */
class MultiwriterAccountsIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("multiwriter-accounts");
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}
    record Site(EventFoundationTestSupport.Database db,MultiwriterAccounts accounts) { }
    Site site(String name,SiteProperties.Mode mode){
        var p=new SiteProperties();p.setId(name);p.setInstanceId(UUID.randomUUID());p.setMode(mode);
        var db=EventFoundationTestSupport.createDatabase(PG,p);
        db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
        var accounts=new MultiwriterAccounts(db.runtime(),db.transactions(),p,true);
        return new Site(db,accounts);
    }
    long create(Site s,String name){return s.db.transactions().required(()->{
        long id=s.db.runtime().queryForObject("INSERT INTO users(nick_name,password_hash) VALUES(?,'initial-hash') RETURNING id",Long.class,name);
        s.accounts.initialize(id);return id;
    });}
    @Test void existingLegacyWithdrawalMustFenceLateGrantedGraph() {
        var a=site("campus-legacy-review",SiteProperties.Mode.CAMPUS);var b=site("external-legacy-review",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long u=create(a,"legacy_review");x.consent.onRegistered(u,true,"synthetic-v2");send(x,y,u);
        // Exact legacy state: durable withdrawal exists, but the v2 table was just installed empty.
        a.db.admin().update("DELETE FROM eatwhat_account_multiwriter.authorization_witness");
        a.db.admin().update("DELETE FROM eatwhat_account_multiwriter.delivery");
        a.db.admin().update("DELETE FROM eatwhat_account_multiwriter.operation");
        a.db.admin().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN',epoch=epoch+1,identity_ready=false,valid_until=NULL WHERE user_id=?",u);
        assertEquals("WITHDRAWN",x.consent.get(u).status());assertTrue(a.accounts.operations(u).isEmpty());
        b.db.admin().update("DELETE FROM eatwhat_account_multiwriter.authorization_witness");
        b.db.admin().update("DELETE FROM eatwhat_account_multiwriter.delivery");
        b.db.admin().update("DELETE FROM eatwhat_account_multiwriter.operation");
        b.accounts.initialize(u); // The peer has only a migrated GRANTED baseline, no forged actor witnesses.
        send(y,x,u);
        assertEquals("WITHDRAWN",x.consent.get(u).status(),"a late granted graph must not replace the legacy withdrawal tombstone");
        x.consent.grant(u,"synthetic-v2");send(x,y,u);
        assertEquals("GRANTED",x.consent.get(u).status());assertEquals("GRANTED",y.consent.get(u).status(),"an explicit grant observing the withdrawal remains possible");
    }
    @Test void legacyWithdrawalWithoutUserExportsOnlyADurableConsentBaseline() {
        var a=site("campus-erased-review",SiteProperties.Mode.CAMPUS);var b=site("external-erased-review",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long u=create(a,"legacy_erased");x.consent.onRegistered(u,true,"synthetic-v2");send(x,y,u);
        a.db.admin().update("DELETE FROM eatwhat_account_multiwriter.authorization_witness");a.db.admin().update("DELETE FROM eatwhat_account_multiwriter.delivery");a.db.admin().update("DELETE FROM eatwhat_account_multiwriter.operation");
        a.db.admin().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN',epoch=epoch+1,identity_ready=false,valid_until=NULL WHERE user_id=?",u);
        a.db.admin().execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.execute("SET LOCAL session_replication_role=replica");statement.execute("DELETE FROM users WHERE id="+u);}connection.commit();return null;});
        var batch=x.exchange.batch(u);assertTrue(batch.path("snapshot").path("data").isNull());
        assertEquals(1,batch.path("changes").size());assertEquals("WITHDRAWN",batch.path("changes").get(0).path("operation").path("value").asText());
        send(x,y,u);assertEquals("WITHDRAWN",y.consent.get(u).status());
        x.exchange.batch(u);assertEquals(1,a.accounts.operations(u).size());assertEquals(0L,a.db.admin().queryForObject("SELECT count(*) FROM users WHERE id=?",Long.class,u));
    }
    @Test void pagedGrantCannotStageDataOverAnErasedLegacyWithdrawalAndInitializationIsIdempotent() throws Exception {
        var a=site("campus-page-review",SiteProperties.Mode.CAMPUS);var b=site("external-page-review",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long u=create(a,"legacy_pages");x.consent.onRegistered(u,true,"synthetic-v2");send(x,y,u);
        b.db.admin().update("DELETE FROM eatwhat_account_multiwriter.authorization_witness");b.db.admin().update("DELETE FROM eatwhat_account_multiwriter.delivery");b.db.admin().update("DELETE FROM eatwhat_account_multiwriter.operation");
        b.db.admin().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN',epoch=epoch+1,identity_ready=false,valid_until=NULL WHERE user_id=?",u);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var start=new java.util.concurrent.CountDownLatch(1);var tasks=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<8;i++){boolean initialize=i%2==0;tasks.add(pool.submit(()->{try{start.await();}catch(InterruptedException interrupted){throw new RuntimeException(interrupted);}if(initialize)b.accounts.initialize(u);else b.accounts.preserveLegacyWithdrawal(u);}));}
            start.countDown();for(var task:tasks)task.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1L,b.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.operation WHERE field='consent'",Long.class));
        // Clear only the test baseline again to verify the page receiver itself preserves an erased legacy tombstone.
        b.db.admin().update("DELETE FROM eatwhat_account_multiwriter.operation");
        b.db.admin().execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.execute("SET LOCAL session_replication_role=replica");statement.execute("DELETE FROM users WHERE id="+u);}connection.commit();return null;});
        a.db.runtime().update("INSERT INTO eatwhat_account_multiwriter.authorization_witness(witness_id,subject_id,security_heads,source_site,source_instance) SELECT gen_random_uuid(),s.subject_id,ARRAY[]::uuid[],?,? FROM eatwhat_consent.subject s CROSS JOIN generate_series(1,1400) WHERE s.user_id=?",a.db.site().getId(),a.db.site().getInstanceId(),u);
        var pages=x.exchange.pages(u);assertTrue(pages.size()>1);var sender=new you.v50to.eatwhat.sync.transport.NodeIdentity(a.db.site().getId(),a.db.site().getInstanceId());
        for(var page:pages)assertEquals("APPLIED",you.v50to.eatwhat.account.AccountPeerScope.run(sender,()->y.exchange.receive(page)).path("status").asText());
        assertEquals("WITHDRAWN",y.consent.get(u).status());assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?",Long.class,u));
        assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.transfer_page",Long.class));
    }
    @Test void localMutationAndIntentRollbackTogetherAndRuntimeCannotRewriteHistory(){
        var s=site("campus-mw",SiteProperties.Mode.CAMPUS);long id=create(s,"rollback");
        long before=s.accounts.operations(id).size();
        assertThrows(IllegalStateException.class,()->s.accounts.command(id,id,()->{
            s.db.runtime().update("UPDATE users SET password_hash='changed' WHERE id=?",id);
            throw new IllegalStateException("rollback");
        }));
        assertEquals("initial-hash",s.db.runtime().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,id));
        assertEquals(before,s.accounts.operations(id).size());
        assertThrows(org.springframework.dao.DataAccessException.class,()->s.db.runtime().update("UPDATE eatwhat_account_multiwriter.operation SET value='tampered'"));
    }
    @Test void existingAccountCanChangePasswordAtEitherSiteAndConcurrencyIsVisible(){
        var s=site("external-mw",SiteProperties.Mode.EXTERNAL);long id=create(s,"independent");
        s.accounts.command(id,id,()->{s.db.runtime().update("UPDATE users SET password_hash='local-new-hash' WHERE id=?",id);return null;});
        assertEquals("local-new-hash",s.accounts.state(id,"password").value());
        assertFalse(s.accounts.state(id,"password").conflict());
        assertTrue(s.accounts.operations(id).size()>4);
        assertThrows(org.springframework.dao.DataAccessException.class,()->s.db.runtime().update("UPDATE users SET password_hash='bypass' WHERE id=?",id));
    }
    @Test void campusWithdrawalRetainsLocalAuthenticationAndLocalGrantProofIsSingleUse(){
        var a=site("campus-proof",SiteProperties.Mode.CAMPUS);var b=site("external-proof",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long campus=create(a,"campus_local");x.consent.onRegistered(campus,true,"synthetic-v2");x.consent.withdraw(campus);
        assertEquals(campus,a.accounts.registerSession("synthetic_after_withdraw_012345678901234567890123456789",campus,3600).userId());
        long external=create(b,"external_local");y.consent.onRegistered(external,true,"synthetic-v2");y.consent.withdraw(external);
        String proof=y.consent.issueProof(external,b.db.site().getId());assertEquals("GRANTED",y.consent.grantWithProof(proof,b.db.site().getId(),"synthetic-v2").status());
        assertThrows(RuntimeException.class,()->y.consent.grantWithProof(proof,b.db.site().getId(),"synthetic-v2"));
    }
    @Test void passwordConflictRecoveryRequiresExistingVerifiedMailboxAndFreshChallenge(){
        var a=site("campus-recovery",SiteProperties.Mode.CAMPUS);var b=site("external-recovery",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);long user=create(a,"recover_user");
        a.accounts.command(user,user,()->{a.db.runtime().update("INSERT INTO contacts(account_id,email) VALUES(?,'synthetic@example.invalid')",user);return null;});x.consent.grant(user,"synthetic-v2");send(x,y,user);
        a.accounts.command(user,user,()->{a.db.runtime().update("UPDATE users SET password_hash='left-hash' WHERE id=?",user);return null;});b.accounts.command(user,user,()->{b.db.runtime().update("UPDATE users SET password_hash='right-hash' WHERE id=?",user);return null;});send(y,x,user);assertTrue(a.accounts.state(user,"password").conflict());
        var mail=org.mockito.Mockito.mock(you.v50to.eatwhat.service.EmailService.class);var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        var recovery=new you.v50to.eatwhat.account.multiwriter.AccountPasswordRecovery(a.db.runtime(),a.accounts,mail,encoder,a.db.transactions());
        recovery.request("id:"+user,"127.0.0.1");org.mockito.Mockito.verify(mail).sendVerificationLink("synthetic@example.invalid",user,"127.0.0.1",you.v50to.eatwhat.service.EmailService.EmailPurpose.RECOVERY);
        org.mockito.Mockito.when(mail.verifyToken("synthetic-recovery-proof",you.v50to.eatwhat.service.EmailService.EmailPurpose.RECOVERY)).thenReturn(new you.v50to.eatwhat.service.EmailService.EmailVerification(user,"synthetic@example.invalid"));
        recovery.reset("synthetic-recovery-proof","synthetic-reset-password");assertFalse(a.accounts.state(user,"password").conflict());assertTrue(encoder.matches("synthetic-reset-password",a.accounts.state(user,"password").value()));
        assertThrows(RuntimeException.class,()->recovery.reset("synthetic-recovery-proof","another-password"));
        send(x,y,user);assertEquals(a.accounts.state(user,"password").heads(),b.accounts.state(user,"password").heads());
    }
    @Test void administratorRecoveryRequiresCurrentAdminAndRecordsTrueActor(){
        var a=site("campus-manual-recovery",SiteProperties.Mode.CAMPUS);var b=site("external-manual-recovery",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long admin=administrator(a,"recovery_admin"),user=create(a,"manual_recovery_user");for(long id:List.of(admin,user)){x.consent.grant(id,"synthetic-v2");send(x,y,id);}
        a.accounts.command(user,user,()->{a.db.runtime().update("UPDATE users SET password_hash='left-password' WHERE id=?",user);return null;});b.accounts.command(user,user,()->{b.db.runtime().update("UPDATE users SET password_hash='right-password' WHERE id=?",user);return null;});send(y,x,user);
        var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);var recovery=new you.v50to.eatwhat.account.multiwriter.AccountPasswordRecovery(a.db.runtime(),a.accounts,org.mockito.Mockito.mock(you.v50to.eatwhat.service.EmailService.class),encoder,a.db.transactions());
        assertThrows(RuntimeException.class,()->recovery.resetByAdministrator(user,user,"new-manual-password"));assertTrue(a.accounts.state(user,"password").conflict());
        recovery.resetByAdministrator(admin,user,"new-manual-password");assertFalse(a.accounts.state(user,"password").conflict());assertTrue(encoder.matches("new-manual-password",a.accounts.state(user,"password").value()));
        var head=a.accounts.state(user,"password").heads().iterator().next();assertEquals(admin,a.db.runtime().queryForObject("SELECT actor_id FROM eatwhat_account_multiwriter.operation WHERE event_id=?",Long.class,head));
        send(x,y,admin);send(x,y,user);assertEquals(a.accounts.state(user,"password").heads(),b.accounts.state(user,"password").heads());
    }
    @Test void durableSessionSurvivesProcessReplacementAndLocalPasswordChangeRevokesIt(){
        var s=site("external-session",SiteProperties.Mode.EXTERNAL);long id=create(s,"sessions");
        String token="synthetic_session_012345678901234567890123456789";
        s.accounts.registerSession(token,id,3600);
        var restarted=new MultiwriterAccounts(s.db.runtime(),s.db.transactions(),s.db.site(),true);
        assertEquals(id,restarted.authorizeSession(token).userId());
        restarted.command(id,id,()->{s.db.runtime().update("UPDATE users SET password_hash='rotated' WHERE id=?",id);return null;});
        assertThrows(RuntimeException.class,()->restarted.authorizeSession(token));
    }
    record Exchange(Site site,you.v50to.eatwhat.account.ConsentStore consent,you.v50to.eatwhat.account.multiwriter.MultiwriterExchange exchange) { }
    Exchange exchange(Site site,Site peer){
        var c=new you.v50to.eatwhat.account.ConsentStore(site.db.runtime(),site.db.transactions(),site.db.site(),"synthetic-v2",java.time.Duration.ofMinutes(5),java.time.Duration.ofMinutes(1),peer.db.site().getId(),peer.db.site().getInstanceId());
        org.springframework.test.util.ReflectionTestUtils.setField(c,"multiwriter",site.accounts);
        return new Exchange(site,c,new you.v50to.eatwhat.account.multiwriter.MultiwriterExchange(site.db.runtime(),site.db.transactions(),c,site.accounts,site.db.site()));
    }
    void send(Exchange from,Exchange to,long user){
        var node=new you.v50to.eatwhat.sync.transport.NodeIdentity(from.site.db.site().getId(),from.site.db.site().getInstanceId());
        assertEquals("APPLIED",you.v50to.eatwhat.account.AccountPeerScope.run(node,()->to.exchange.receive(from.exchange.batch(user))).path("status").asText());
    }
    long administrator(Site site,String name){site.db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=false");long id=site.db.admin().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES(?,'synthetic-hash','admin') RETURNING id",Long.class,name);site.db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");return id;}
    @Test void delayedAdminChangeBeforeObservedRevocationRemainsValidButConcurrentChangeIsReviewed(){
        for(boolean historical:List.of(true,false)){
            var a=site("campus-causal",SiteProperties.Mode.CAMPUS);var b=site("external-causal",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
            long admin=administrator(a,"admin_one"),moderator=administrator(a,"admin_two"),user=create(a,"target_user");
            for(long id:List.of(admin,moderator,user)){x.consent.grant(id,"synthetic-v2");send(x,y,id);}
            a.accounts.command(admin,user,()->{a.db.runtime().update("UPDATE users SET banned=true WHERE id=?",user);return null;});
            if(historical)send(x,y,admin);
            b.accounts.command(moderator,admin,()->{b.db.runtime().update("UPDATE users SET role='user' WHERE id=?",admin);return null;});
            if(!historical)send(x,y,admin);
            send(x,y,user);
            assertEquals(historical,b.db.runtime().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,user));
            assertEquals(historical?0L:1L,b.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.authorization_review WHERE state='REVIEW'",Long.class));
            if(!historical){
                send(y,x,moderator);send(y,x,admin);
                assertFalse(a.db.runtime().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,user),"late revocation must isolate an already locally applied concurrent command");
                var review=b.accounts.reviews().stream().filter(r->r.subjectId().equals(y.consent.get(user).subjectId())).findFirst().orElseThrow();
                b.accounts.decideReview(moderator,review.eventId(),true);assertTrue(b.db.runtime().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,user));
                send(y,x,moderator);send(y,x,user);assertTrue(a.db.runtime().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,user));
                assertEquals("APPROVED",a.accounts.reviews().stream().filter(r->r.eventId().equals(review.eventId())).findFirst().orElseThrow().state());
            }
        }
    }
    @Test void concurrentRevocationOfReviewerReopensAlreadyAppliedDecision(){
        var a=site("campus-reviewer",SiteProperties.Mode.CAMPUS);var b=site("external-reviewer",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long admin=administrator(a,"original_admin"),reviewer=administrator(a,"review_admin"),auditor=administrator(a,"audit_admin"),user=create(a,"review_target");
        for(long id:List.of(admin,reviewer,auditor,user)){x.consent.grant(id,"synthetic-v2");send(x,y,id);}
        a.accounts.command(admin,user,()->{a.db.runtime().update("UPDATE users SET banned=true WHERE id=?",user);return null;});
        b.accounts.command(reviewer,admin,()->{b.db.runtime().update("UPDATE users SET role='user' WHERE id=?",admin);return null;});
        send(x,y,admin);send(x,y,user);
        var review=b.accounts.reviews().stream().filter(r->r.subjectId().equals(y.consent.get(user).subjectId())).findFirst().orElseThrow();
        b.accounts.decideReview(reviewer,review.eventId(),true);assertTrue(b.db.runtime().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,user));
        // Site A has not observed the reviewer's approval witness, so this revocation is concurrent.
        a.accounts.command(auditor,reviewer,()->{a.db.runtime().update("UPDATE users SET role='user' WHERE id=?",reviewer);return null;});
        send(x,y,auditor);send(x,y,reviewer);
        assertEquals("REVIEW",b.accounts.reviews().stream().filter(r->r.eventId().equals(review.eventId())).findFirst().orElseThrow().state());
        assertFalse(b.db.runtime().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,user));
    }
    @Test void repeatedAdministratorProjectionDoesNotAdvanceControlCredentialFence(){
        var a=site("campus-idempotent",SiteProperties.Mode.CAMPUS);var b=site("external-idempotent",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);long admin=administrator(a,"idempotent_admin");x.consent.grant(admin,"synthetic-v2");send(x,y,admin);send(y,x,admin);
        Long before=a.db.admin().queryForObject("SELECT coalesce(max(version),0) FROM eatwhat_control.credential_version WHERE user_id=?",Long.class,admin);
        send(y,x,admin);send(y,x,admin);
        assertEquals(before,a.db.admin().queryForObject("SELECT coalesce(max(version),0) FROM eatwhat_control.credential_version WHERE user_id=?",Long.class,admin));
        assertEquals("idempotent_admin",a.db.runtime().queryForObject("SELECT nick_name FROM users WHERE id=?",String.class,admin));
    }
    @Test void importedLegacyAdministratorUsesProtectedSeedContextBeforeIdentityTrigger(){
        var a=site("campus-admin-seed",SiteProperties.Mode.CAMPUS);var b=site("external-admin-seed",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        a.db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=false");
        long user=a.db.admin().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES('legacy_admin','synthetic-hash','admin') RETURNING id",Long.class);
        a.db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");x.consent.grant(user,"synthetic-v2");send(x,y,user);
        assertEquals("admin",b.db.runtime().queryForObject("SELECT role FROM users WHERE id=?",String.class,user));
    }
    @Test void firstGrantOfExistingAccountCreatesDurableSubjectAndSynchronizes(){
        var a=site("campus-existing",SiteProperties.Mode.CAMPUS);var b=site("external-existing",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long user=create(a,"legacy_account");assertNull(x.consent.get(user));
        var granted=x.consent.grant(user,"synthetic-v2");assertNotNull(granted);assertEquals("GRANTED",granted.status());
        send(x,y,user);assertTrue(y.consent.isAuthorized(user));
        long count=a.accounts.operations(user).size();x.consent.grant(user,"synthetic-v2");assertEquals(count,a.accounts.operations(user).size());
    }
    @Test void independentRegistrationAndBidirectionalPasswordChangesConvergeWithoutAuthority(){
        var a=site("campus-pair",SiteProperties.Mode.CAMPUS);var b=site("external-pair",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long user=create(b,"remote_registration");y.consent.onRegistered(user,true,"synthetic-v2");
        assertTrue(y.consent.isAuthorized(user));send(y,x,user);
        assertEquals("remote_registration",a.db.runtime().queryForObject("SELECT nick_name FROM users WHERE id=?",String.class,user));
        a.accounts.command(user,user,()->{a.db.runtime().update("UPDATE users SET password_hash='campus-edit' WHERE id=?",user);return null;});
        b.accounts.command(user,user,()->{b.db.runtime().update("UPDATE users SET password_hash='external-edit' WHERE id=?",user);return null;});
        send(x,y,user);send(y,x,user);
        assertTrue(a.accounts.state(user,"password").conflict());assertTrue(b.accounts.state(user,"password").conflict());
        assertEquals(a.accounts.state(user,"password").heads(),b.accounts.state(user,"password").heads());
    }
    String nickname(Site s,long user){return s.db.runtime().queryForObject("SELECT display_name FROM users WHERE id=?",String.class,user);}
    void rename(Site s,long user,String value){s.accounts.command(user,user,()->{s.db.runtime().update("UPDATE users SET display_name=? WHERE id=?",value,user);return null;});}
    @Test void nicknameFollowsTheAccountBothWaysAndConcurrentEditsConvergeWithoutConflict(){
        var a=site("campus-nickname",SiteProperties.Mode.CAMPUS);var b=site("external-nickname",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long user=b.db.transactions().required(()->{
            long id=b.db.runtime().queryForObject("INSERT INTO users(nick_name,display_name,password_hash) VALUES('p_mwnick','同学0003','initial-hash') RETURNING id",Long.class);
            b.accounts.initialize(id);return id;
        });
        y.consent.onRegistered(user,true,"synthetic-v2");send(y,x,user);
        assertEquals("同学0003",nickname(a,user));
        rename(a,user,"校内改名");send(x,y,user);
        assertEquals("校内改名",nickname(b,user));
        rename(a,user,"甲");rename(b,user,"乙");send(x,y,user);send(y,x,user);
        assertEquals(nickname(a,user),nickname(b,user));
        assertFalse(a.accounts.state(user,"display_name").conflict());
        assertEquals(0L,a.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.conflict WHERE field='display_name'",Long.class));
        // A literal "null" nickname is a value, not a cleared nickname.
        rename(b,user,"null");send(y,x,user);
        assertEquals("null",nickname(a,user));
        // A nickname is not a credential: renaming keeps durable sessions valid.
        var session=a.accounts.registerSession("nickname-session-token",user,600);
        rename(a,user,"又改了");assertEquals(session.userId(),a.accounts.authorizeSession("nickname-session-token").userId());
    }
    @Test void duplicateAliasesKeepTwoIdentitiesAndDoNotPickAnAccountByArrivalOrder(){
        var a=site("campus-alias",SiteProperties.Mode.CAMPUS);var b=site("external-alias",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long u=create(a,"same_alias"),v=create(b,"same_alias");x.consent.onRegistered(u,true,"synthetic-v2");y.consent.onRegistered(v,true,"synthetic-v2");
        send(x,y,u);send(y,x,v);
        for(var site:List.of(a,b)){
            assertEquals(2L,site.db.runtime().queryForObject("SELECT count(*) FROM users",Long.class));
            assertEquals(0L,site.db.runtime().queryForObject("SELECT count(*) FROM users WHERE nick_name='same_alias'",Long.class));
            assertEquals(2L,site.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.alias_claim WHERE kind='username' AND value='same_alias'",Long.class));
            assertNull(site.accounts.resolveLogin("same_alias"));
            assertEquals(u,site.accounts.resolveLogin("id:"+u));assertEquals(v,site.accounts.resolveLogin("id:"+v));
        }
    }
    @Test void conflictingPhoneProofCannotCreateAThirdAccount() {
        var a=site("campus-phone",SiteProperties.Mode.CAMPUS);var b=site("external-phone",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long u=create(a,"phone_a"),v=create(b,"phone_b");String mobile="13800000001";
        a.accounts.command(u,u,()->a.db.runtime().update("INSERT INTO contacts(account_id,phone) VALUES(?,?)",u,mobile));
        b.accounts.command(v,v,()->b.db.runtime().update("INSERT INTO contacts(account_id,phone) VALUES(?,?)",v,mobile));
        x.consent.onRegistered(u,true,"synthetic-v2");y.consent.onRegistered(v,true,"synthetic-v2");send(x,y,u);send(y,x,v);
        for(var site:List.of(a,b)) {
            assertEquals(2L,site.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.alias_claim WHERE kind='phone' AND value=?",Long.class,mobile));
            var sms=org.mockito.Mockito.mock(you.v50to.eatwhat.service.SmsService.class);
            org.mockito.Mockito.when(sms.verifyLoginCode(mobile,"123456")).thenReturn(true);
            var users=org.mockito.Mockito.mock(you.v50to.eatwhat.mapper.UserMapper.class);
            var service=new you.v50to.eatwhat.service.AuthService();
            org.springframework.test.util.ReflectionTestUtils.setField(service,"multiwriter",site.accounts);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"smsService",sms);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"userMapper",users);
            var dto=new you.v50to.eatwhat.data.dto.PhoneLoginDTO();dto.setMobile(mobile);dto.setCode("123456");dto.setDevice(you.v50to.eatwhat.data.enums.DeviceType.WEB);
            var failure=assertThrows(you.v50to.eatwhat.exception.BizException.class,()->service.phoneLogin(dto));
            assertEquals(you.v50to.eatwhat.data.enums.BizCode.ACCOUNT_SECURITY_CONFLICT,failure.getBizCode());
            org.mockito.Mockito.verifyNoInteractions(users);
            assertEquals(2L,site.db.runtime().queryForObject("SELECT count(*) FROM users",Long.class));
        }
    }
    @Test void withdrawalClearsExternalPrivateHistoryAndOldGrantCannotResurrectThenObservedRegrantWorks(){
        var a=site("campus-withdraw",SiteProperties.Mode.CAMPUS);var b=site("external-withdraw",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long user=create(a,"withdrawal");x.consent.onRegistered(user,true,"synthetic-v2");var old=x.exchange.batch(user);send(x,y,user);
        x.consent.withdraw(user);send(x,y,user);assertEquals("WITHDRAWN",y.consent.get(user).status());
        for(var job:y.consent.claim(10,java.time.Duration.ofMinutes(1)))if(job.kind().equals("CLEANUP")){assertEquals("DONE",y.consent.runCleanup(job));y.consent.complete(job);}
        assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?",Long.class,user));
        assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.operation WHERE subject_id=? AND field IN ('username','password','email','phone','verification')",Long.class,y.consent.get(user).subjectId()));
        var sender=new you.v50to.eatwhat.sync.transport.NodeIdentity(a.db.site().getId(),a.db.site().getInstanceId());you.v50to.eatwhat.account.AccountPeerScope.run(sender,()->y.exchange.receive(old));
        assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?",Long.class,user));assertEquals("WITHDRAWN",y.consent.get(user).status());
        x.consent.grant(user,"synthetic-v2");send(x,y,user);assertTrue(y.consent.isAuthorized(user));assertEquals("withdrawal",b.db.runtime().queryForObject("SELECT nick_name FROM users WHERE id=?",String.class,user));
    }
    @Test void withdrawalArrivingBeforeRegistrationCreatesOnlyATombstone(){
        var a=site("campus-tombstone",SiteProperties.Mode.CAMPUS);var b=site("external-tombstone",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long user=create(a,"unseen");x.consent.onRegistered(user,true,"synthetic-v2");var old=x.exchange.batch(user);x.consent.withdraw(user);send(x,y,user);
        assertEquals("WITHDRAWN",y.consent.get(user).status());assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM users",Long.class));
        var sender=new you.v50to.eatwhat.sync.transport.NodeIdentity(a.db.site().getId(),a.db.site().getInstanceId());you.v50to.eatwhat.account.AccountPeerScope.run(sender,()->y.exchange.receive(old));assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM users",Long.class));
    }
    @Test void largeAccountHistoryIsStagedAcrossRestartAndAppliedOnlyAfterAllPages(){
        var a=site("campus-pages",SiteProperties.Mode.CAMPUS);var b=site("external-pages",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long user=create(a,"large_history");x.consent.onRegistered(user,true,"synthetic-v2");
        a.db.runtime().update("INSERT INTO eatwhat_account_multiwriter.authorization_witness(witness_id,subject_id,security_heads,source_site,source_instance) SELECT gen_random_uuid(),s.subject_id,ARRAY[]::uuid[],?,? FROM eatwhat_consent.subject s CROSS JOIN generate_series(1,1400) WHERE s.user_id=?",a.db.site().getId(),a.db.site().getInstanceId(),user);
        var pages=x.exchange.pages(user);assertTrue(pages.size()>1);var sender=new you.v50to.eatwhat.sync.transport.NodeIdentity(a.db.site().getId(),a.db.site().getInstanceId());
        assertEquals("STAGED",you.v50to.eatwhat.account.AccountPeerScope.run(sender,()->y.exchange.receive(pages.getFirst())).path("status").asText());
        assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?",Long.class,user));
        var restarted=exchange(b,a);
        for(int n=1;n<pages.size();n++){var page=pages.get(n);var result=you.v50to.eatwhat.account.AccountPeerScope.run(sender,()->restarted.exchange.receive(page));assertEquals(n==pages.size()-1?"APPLIED":"STAGED",result.path("status").asText());}
        assertEquals(1L,b.db.runtime().queryForObject("SELECT count(*) FROM users WHERE id=?",Long.class,user));
        assertTrue(b.accounts.witnesses(user).size()>=1400);
        x.consent.withdraw(user);send(x,y,user);
        assertEquals("APPLIED",you.v50to.eatwhat.account.AccountPeerScope.run(sender,()->restarted.exchange.receive(pages.getFirst())).path("status").asText());
        assertEquals(0L,b.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.transfer_page",Long.class));
    }
    @Test void ownerKeyRotationPreservesOldSessionsAndEmergencyRevocationPropagates(){
        var a=site("campus-keys",SiteProperties.Mode.CAMPUS);var b=site("external-keys",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long user=create(a,"rotation");x.consent.onRegistered(user,true,"synthetic-v2");String old="synthetic_old_signing_key_012345678901234567890123456789",fresh="synthetic_new_signing_key_012345678901234567890123456789";
        a.accounts.registerSession(old,user,3600);send(x,y,user);
        assertThrows(org.springframework.dao.DataAccessException.class,()->a.db.runtime().queryForObject("SELECT eatwhat_account_multiwriter.rotate_session_key(false)",Integer.class));
        a.db.admin().queryForObject("SELECT eatwhat_account_multiwriter.rotate_session_key(false)",Integer.class);a.accounts.registerSession(fresh,user,3600);send(x,y,user);
        assertEquals(2L,a.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.signing_key",Long.class));assertEquals(user,b.accounts.authorizeSession(old).userId());assertEquals(user,b.accounts.authorizeSession(fresh).userId());
        a.db.admin().queryForObject("SELECT eatwhat_account_multiwriter.rotate_session_key(true)",Integer.class);send(x,y,user);
        assertThrows(RuntimeException.class,()->a.accounts.authorizeSession(old));assertThrows(RuntimeException.class,()->b.accounts.authorizeSession(fresh));
    }
    @Test void signedSessionsFollowTheUserAndLogoutTombstoneSurvivesOldFactReplay(){
        var a=site("campus-session",SiteProperties.Mode.CAMPUS);var b=site("external-session",SiteProperties.Mode.EXTERNAL);var x=exchange(a,b);var y=exchange(b,a);
        long user=create(a,"shared_session");x.consent.onRegistered(user,true,"synthetic-v2");
        String token="synthetic_shared_session_012345678901234567890123456789";a.accounts.registerSession(token,user,3600);
        var beforeLogout=x.exchange.batch(user);send(x,y,user);assertEquals(user,b.accounts.authorizeSession(token).userId());
        b.accounts.revokeSession(token);send(y,x,user);assertThrows(RuntimeException.class,()->a.accounts.authorizeSession(token));
        var sender=new you.v50to.eatwhat.sync.transport.NodeIdentity(a.db.site().getId(),a.db.site().getInstanceId());
        you.v50to.eatwhat.account.AccountPeerScope.run(sender,()->y.exchange.receive(beforeLogout));
        assertThrows(RuntimeException.class,()->b.accounts.authorizeSession(token));
    }
}
