package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.control.bootstrap.AuthorityAuthRole;
import you.v50to.eatwhat.control.bootstrap.AuthorityAuthRoleSource;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import you.v50to.eatwhat.control.bootstrap.BusinessDependencyRelay;
import you.v50to.eatwhat.support.SourceMaterialFixture;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real migrated private PG, new dedicated roles, passwords and DML/DDL denial.
 * No production database, native SSH/source discovery, bootstrap API or JOIN. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorityAuthRoleIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("authority-auth-role");
    static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path root;
    EventFoundationTestSupport.Database db;SiteProperties site;UUID state=UUID.randomUUID(),holder=UUID.randomUUID();long actor;String fingerprint;
    AtomicInteger slot=new AtomicInteger();byte[] key;ControlServerStore store;
    @BeforeAll void initialize(){
        PG.start();site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(UUID.randomUUID());site.setClusterId(UUID.randomUUID());site.setAllocationSlot(0);
        db=EventFoundationTestSupport.createDatabase(PG,site);
        fingerprint=db.admin().queryForObject("SELECT md5(cluster_identifier||':'||database_oid::text) FROM eatwhat_identity.node_state",String.class);
        db.admin().update("INSERT INTO eatwhat_cluster.state(cluster_id,coordinator_site,coordinator_instance,epoch) VALUES(?,'campus',?,1)",site.getClusterId(),site.getInstanceId());
        db.admin().update("INSERT INTO eatwhat_cluster.member(site_id,instance_id,profile_id,mode,allocation_slot,state,database_fingerprint,joined_epoch) VALUES('campus',?,'campus','CAMPUS',0,'ACTIVE',?,1)",site.getInstanceId(),fingerprint);
        db.admin().update("INSERT INTO eatwhat_cluster.local_state(cluster_id,epoch,site_id,instance_id,state,members) VALUES(?,1,'campus',?,'ACTIVE',?::jsonb)",site.getClusterId(),site.getInstanceId(),JSON.writeValueAsString(List.of(new ClusterMember("campus",site.getInstanceId(),"CAMPUS",0))));
        db.admin().update("INSERT INTO eatwhat_cluster.control_process(holder_id,cluster_id,name,state_id,lease_seconds,expires_at,deployment_protocol) VALUES(?,?,'fixture-primary',?,120,clock_timestamp()+interval '120s',1)",holder,site.getClusterId(),state);
        db.admin().update("INSERT INTO eatwhat_cluster.control_lease(cluster_id,holder_id,generation,expires_at) VALUES(?,?,1,clock_timestamp()+interval '120s')",site.getClusterId(),holder);
        actor=db.admin().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES('auth-role-admin','synthetic-admin-private','admin') RETURNING id",Long.class);
    }
    @AfterAll void close(){PG.close();}
    @BeforeEach void reset()throws Exception{
        root=root.toRealPath();store=new ControlServerStore(new ControlAudit(root));key=Files.readAllBytes(store.directory().resolve("servers.key"));
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET generation=1,expires_at=clock_timestamp()+interval '120s' WHERE singleton");
        db.admin().update("UPDATE eatwhat_cluster.control_process SET expires_at=clock_timestamp()+interval '120s' WHERE holder_id=?",holder);
        db.admin().update("UPDATE users SET banned=false,role='admin' WHERE id=?",actor);
    }
    AuthorityAuthRole.Request request(){
        UUID bootstrap=UUID.randomUUID(),target=UUID.randomUUID();int index=slot.incrementAndGet();
        UUID first=UUID.nameUUIDFromBytes((bootstrap+":BUSINESS_INSTALL").getBytes(StandardCharsets.UTF_8));
        db.admin().update("INSERT INTO eatwhat_cluster.installation_allocation(server_id,cluster_id,site_id,instance_id,mode,allocation_slot,first_job_id) VALUES(?,?,?,?,'EXTERNAL',?,?)",target,site.getClusterId(),"server-"+target.toString().replace("-","").substring(0,12),UUID.randomUUID(),index,first);
        var admin=db.admin().queryForMap("SELECT u.id,u.nick_name,u.password_hash,coalesce(v.version,0) AS version FROM users u LEFT JOIN eatwhat_control.credential_version v ON v.user_id=u.id WHERE u.id=?",actor);
        String binding=store.fingerprint(JSON.writeValueAsString(List.of(admin.get("id"),admin.get("nick_name"),admin.get("password_hash"),admin.get("version"))));
        byte[] salt=new byte[16];new java.security.SecureRandom().nextBytes(salt);
        return new AuthorityAuthRole.Request(UUID.nameUUIDFromBytes((bootstrap+":MEMBER_AUTH_ROLE").getBytes(StandardCharsets.UTF_8)),bootstrap,target,site.getClusterId(),"campus",site.getInstanceId(),fingerprint,1,state,holder,1,actor,
                binding,Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII)),Base64.getEncoder().encodeToString(salt),Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(30)));
    }
    Connection admin()throws Exception{return DriverManager.getConnection(db.url(),IsolatedContainers.USER,IsolatedContainers.PASSWORD);}
    Connection login(AuthorityAuthRole.Request r)throws Exception{return DriverManager.getConnection(db.url(),r.username(),r.password());}
    Map<String,Object> create(AuthorityAuthRole.Request r)throws Exception{try(var c=admin()){return AuthorityAuthRole.create(c,r,key);}}
    void absent(AuthorityAuthRole.Request r){assertEquals(0L,db.admin().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=?",Long.class,r.username()));}
    void denied(Connection c,String sql)throws Exception{try(var s=c.createStatement()){assertEquals("42501",assertThrows(SQLException.class,()->s.execute(sql)).getSQLState(),sql);}}
    @Test void preflight_checks_real_fences_without_creating_a_role_and_restores_connection()throws Exception{
        var r=request();try(var c=admin()){
            String statement=show(c,"statement_timeout"),lock=show(c,"lock_timeout");
            var result=AuthorityAuthRole.preflight(c,r,key);assertEquals("ROLE_ABSENT",result.get("state"));assertEquals(false,result.get("effectsStarted"));
            assertTrue(c.getAutoCommit());assertEquals(statement,show(c,"statement_timeout"));assertEquals(lock,show(c,"lock_timeout"));
            assertTrue(c.isValid(3));
        }absent(r);
        db.admin().update("UPDATE users SET banned=true WHERE id=?",actor);
        try(var c=admin()){assertThrows(IllegalStateException.class,()->AuthorityAuthRole.preflight(c,r,key));assertTrue(c.getAutoCommit());}absent(r);
        db.admin().update("UPDATE users SET banned=false WHERE id=?",actor);
        try(var c=DriverManager.getConnection(db.url(),db.runtimeUsername(),IsolatedContainers.PASSWORD)){
            assertThrows(IllegalStateException.class,()->AuthorityAuthRole.preflight(c,r,key));assertTrue(c.getAutoCommit());
        }absent(r);
    }
    @Test void actual_source_discovery_and_unpooled_socket_connection_preflight_then_create_original_role()throws Exception{
        var r=request();Path source=Files.createDirectory(root.resolve("source")),sites=Files.createDirectory(root.resolve("sites"));var f=new SourceMaterialFixture(source);
        f.properties.put("app.site.cluster-id",site.getClusterId().toString());f.properties.put("app.site.instance-id",site.getInstanceId().toString());
        f.properties.put("app.cluster.control-lease.controller-name","fixture-primary");f.properties.put("spring.datasource.url",db.url());
        f.properties.put("spring.datasource.username",db.runtimeUsername());f.properties.put("spring.datasource.password",IsolatedContainers.PASSWORD);
        f.properties.put("spring.flyway.user",IsolatedContainers.USER);f.properties.put("spring.flyway.password",IsolatedContainers.PASSWORD);
        SourceMaterialFixture.write(source.resolve("state/servers.key"),key);SourceMaterialFixture.write(source.resolve("state/controller-state-id"),state.toString().getBytes(StandardCharsets.US_ASCII));
        SourceMaterialFixture.write(f.config.resolve("cluster-profiles.json"),JSON.writeValueAsBytes(Map.of("campus",Map.of("siteId","campus","instanceId",site.getInstanceId().toString(),
                "mode","CAMPUS","allocationSlot",0,"origin","http://127.0.0.1:18090","tokenFile",f.properties.get("app.control.node-token-file")))));
        var text=new StringBuilder();f.properties.forEach((k,v)->text.append(k).append('=').append(v).append('\n'));
        SourceMaterialFixture.write(f.config.resolve("application.properties"),text.toString().getBytes(StandardCharsets.US_ASCII));
        var expected=new ControllerSourceSnapshot.Expected(site.getClusterId(),"campus",site.getInstanceId(),fingerprint,state,1,"fixture-primary",
                ControllerSourceSnapshot.sha(Files.readAllBytes(f.config.resolve("application.properties"))),ControllerSourceSnapshot.sha(Files.readAllBytes(f.config.resolve("cluster-profiles.json"))),ControllerSourceSnapshot.sha(key));
        var input=new AuthorityAuthRoleSource.Input(r,expected);var proof=AuthorityAuthRoleSource.inspect(source,sites,input);
        assertEquals("INSPECTED",proof.get("state"));assertEquals(false,proof.get("effectsStarted"));absent(r);
        for(String secret:List.of(r.password(),IsolatedContainers.PASSWORD,source.toString()))assertFalse(proof.toString().contains(secret));
        var material=AuthorityAuthRoleSource.discover(source,sites,expected);assertEquals(material.binding(),proof.get("credentialBinding"));
        assertEquals("SOURCE_CONFIG",material.kind());
        try(var connection=AuthorityAuthRoleSource.connect(material)){assertEquals("ROLE_READY",AuthorityAuthRole.create(connection,r,key).get("state"));}
        try(var c=login(r)){assertTrue(c.isValid(3));}
        assertThrows(IllegalStateException.class,()->AuthorityAuthRoleSource.inspect(source,sites,input));
        try(var c=admin()){assertEquals("ROLE_READY",AuthorityAuthRole.inspect(c,r).get("state"));}
    }
    @Test void real_scram_login_has_only_required_auth_reads_and_dml()throws Exception{
        var r=request();long users=db.admin().queryForObject("SELECT count(*) FROM users",Long.class),sessions=db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.login_session",Long.class);
        var proof=create(r);assertEquals("ROLE_READY",proof.get("state"));assertFalse(proof.toString().contains(r.password()));assertFalse(proof.toString().contains(r.salt()));
        assertEquals(users,db.admin().queryForObject("SELECT count(*) FROM users",Long.class));assertEquals(sessions,db.admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.login_session",Long.class));
        try(var c=login(r);var s=c.createStatement()){
            try(var rows=s.executeQuery("SELECT u.id,u.password_hash,u.banned,u.role,i.global_id FROM users u JOIN eatwhat_identity.entity_identity i ON i.entity_type='users' AND i.numeric_id=u.id WHERE u.id="+actor)){assertTrue(rows.next());}
            for(String sql:List.of("SELECT * FROM users","SELECT avatar FROM users","SELECT * FROM contacts","UPDATE users SET banned=true WHERE false","DELETE FROM eatwhat_cluster.login_session WHERE false",
                    "UPDATE eatwhat_cluster.state SET epoch=epoch WHERE false","SELECT * FROM eatwhat_cluster.member","SELECT * FROM eatwhat_cluster.control_lease","SELECT * FROM eatwhat_control.credential_version",
                    "CREATE TABLE public.auth_role_unapproved(id int)","CREATE ROLE auth_role_unapproved","SET ROLE "+db.runtimeUsername()))denied(c,sql);
            assertEquals("10s",show(c,"statement_timeout"));assertEquals("3s",show(c,"lock_timeout"));assertEquals("15s",show(c,"idle_in_transaction_session_timeout"));
        }
        Path password=root.resolve("auth.password");Files.writeString(password,r.password());Files.setPosixFilePermissions(password,PosixFilePermissions.fromString("rw-------"));
        var settings=new ClusterProperties();settings.setCoordinator(false);settings.setAuthoritySiteId("campus");settings.setAuthorityInstanceId(site.getInstanceId());settings.getAuthStore().setEnabled(true);
        settings.getAuthStore().setJdbcUrl(db.url());settings.getAuthStore().setUsername(r.username());settings.getAuthStore().setPasswordFile(password);
        var secondary=new SiteProperties();secondary.setClusterId(site.getClusterId());secondary.setMode(SiteProperties.Mode.EXTERNAL);
        try(var auth=new ClusterAuthStore(settings,secondary)){auth.verify();assertEquals(1,new ClusterMembershipView(auth.jdbc()).current().size());}
    }
    static String show(Connection c,String property)throws Exception{try(var s=c.createStatement();var rows=s.executeQuery("SHOW "+property)){assertTrue(rows.next());return rows.getString(1);}}
    @Test void real_session_and_withdrawal_writes_retain_immutable_security_guards()throws Exception{
        var r=request();create(r);String token=UUID.randomUUID().toString().replace("-","").repeat(2);UUID subject=UUID.randomUUID();
        try(var c=login(r)){
            try(var q=c.prepareStatement("INSERT INTO eatwhat_cluster.login_session(token_hash,subject_id,user_id,source_site,source_instance,consent_epoch,security_hash,expires_at,idle_seconds) VALUES(?,?,?,'campus',?,0,?,clock_timestamp()+interval '1 hour',60)")){
                q.setString(1,token);q.setObject(2,subject);q.setLong(3,actor);q.setObject(4,site.getInstanceId());q.setString(5,"a".repeat(64));assertEquals(1,q.executeUpdate());
            }
            try(var s=c.createStatement()){assertEquals(1,s.executeUpdate("UPDATE eatwhat_cluster.login_session SET last_seen=clock_timestamp(),revoked=true WHERE token_hash='"+token+"'"));
                assertEquals("23514",assertThrows(SQLException.class,()->s.executeUpdate("UPDATE eatwhat_cluster.login_session SET revoked=false WHERE token_hash='"+token+"'")).getSQLState());}
            denied(c,"UPDATE eatwhat_cluster.login_session SET security_hash='"+"b".repeat(64)+"' WHERE token_hash='"+token+"'");
            try(var q=c.prepareStatement("INSERT INTO eatwhat_cluster.session_withdraw_fence(subject_id,user_id,epoch) VALUES(?,?,0) ON CONFLICT DO NOTHING")){q.setObject(1,subject);q.setLong(2,actor);assertEquals(1,q.executeUpdate());}
            denied(c,"DELETE FROM eatwhat_cluster.session_withdraw_fence WHERE false");denied(c,"UPDATE eatwhat_cluster.session_withdraw_fence SET epoch=1 WHERE false");
        }
    }
    @Test void generated_member_tls_route_and_real_auth_store_use_the_new_restricted_role()throws Exception{
        var r=request();create(r);Path pkiRoot=root.resolve("pki");var pki=TransportTlsFixture.create(pkiRoot);
        var identity=BusinessDependencyRelay.serverContext(TransportTlsFixture.relayStore(pki.campus()),TransportTlsFixture.PASSWORD.toCharArray());
        String iface=NetworkInterface.getByInetAddress(InetAddress.getByName("127.0.0.1")).getName();
        var endpoint=new BusinessDependencyRelay.Endpoint("AUTHORITY_DATABASE",new InetSocketAddress("127.0.0.1",0),iface,"127.0.0.0/8",
                new InetSocketAddress("127.0.0.1",PG.getMappedPort(5432)),BusinessDependencyRelay.Protocol.POSTGRESQL_TLS,BusinessDependencyRelay.Upstream.CLEAR,"",
                new BusinessDependencyRelay.DatabaseIdentity(db.name(),r.username()));
        try(var relay=new BusinessDependencyRelay(identity,null,List.of(endpoint),8,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(10))){
            var installed=db.admin().queryForMap("SELECT site_id,instance_id,allocation_slot FROM eatwhat_cluster.installation_allocation WHERE server_id=?",r.targetServerId());
            var target=new ClusterMember(installed.get("site_id").toString(),(UUID)installed.get("instance_id"),"EXTERNAL",((Number)installed.get("allocation_slot")).intValue());
            var authority=new ClusterMember("campus",site.getInstanceId(),"CAMPUS",0);
            var values=ControlMemberConfiguration.runtime(site.getClusterId(),target,authority,"https://127.0.0.1:28443","",
                    "jdbc:postgresql://127.0.0.1:"+relay.addresses().getFirst().getPort()+"/"+db.name(),r.username(),ControlMemberConfiguration.AuthTransport.TLS_TERMINATED);
            String url=values.get("app.cluster.auth-store.jdbc-url").replace("sslrootcert=/config/authority-ca.pem","sslrootcert="+pkiRoot.resolve("ca.crt"));
            Path password=root.resolve("tls-auth.password");Files.writeString(password,r.password());Files.setPosixFilePermissions(password,PosixFilePermissions.fromString("rw-------"));
            var settings=new ClusterProperties();settings.setCoordinator(false);settings.setAuthoritySiteId("campus");settings.setAuthorityInstanceId(site.getInstanceId());settings.getAuthStore().setEnabled(true);
            settings.getAuthStore().setJdbcUrl(url);settings.getAuthStore().setUsername(r.username());settings.getAuthStore().setPasswordFile(password);
            var secondary=new SiteProperties();secondary.setClusterId(site.getClusterId());secondary.setMode(SiteProperties.Mode.EXTERNAL);
            try(var auth=new ClusterAuthStore(settings,secondary)){
                auth.verify();assertEquals(r.username(),auth.jdbc().queryForObject("SELECT current_user",String.class));assertEquals(1,new ClusterMembershipView(auth.jdbc()).current().size());
                assertThrows(DataAccessException.class,()->auth.jdbc().queryForList("SELECT * FROM public.users"));
            }
        }
    }
    @Test void same_role_is_not_recreated_and_foreign_role_is_not_adopted()throws Exception{
        var r=request();create(r);assertThrows(IllegalStateException.class,()->create(r));try(var c=admin()){assertEquals("ROLE_READY",AuthorityAuthRole.inspect(c,r).get("state"));}
        var foreign=request();db.admin().execute("CREATE ROLE "+foreign.username()+" NOLOGIN");assertThrows(IllegalStateException.class,()->create(foreign));
        assertEquals(false,db.admin().queryForObject("SELECT rolcanlogin FROM pg_roles WHERE rolname=?",Boolean.class,foreign.username()));
        assertNull(db.admin().queryForObject("SELECT shobj_description(oid,'pg_authid') FROM pg_roles WHERE rolname=?",String.class,foreign.username()));
    }
    @Test void broad_public_privilege_causes_atomic_rollback_without_changing_public_policy()throws Exception{
        var r=request();db.admin().execute("GRANT SELECT ON public.users TO PUBLIC");
        try{assertThrows(IllegalStateException.class,()->create(r));absent(r);assertTrue(db.admin().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(c.relacl) a WHERE c.oid='public.users'::regclass AND a.grantee=0 AND a.privilege_type='SELECT')",Boolean.class));}
        finally{db.admin().execute("REVOKE SELECT ON public.users FROM PUBLIC");}
    }
    @Test void public_grants_in_an_unrelated_schema_cannot_escape_effective_privilege_check()throws Exception{
        var r=request();db.admin().execute("CREATE SCHEMA fixture_exposed");db.admin().execute("CREATE TABLE fixture_exposed.secret(id int)");
        db.admin().execute("GRANT USAGE ON SCHEMA fixture_exposed TO PUBLIC");db.admin().execute("GRANT SELECT ON fixture_exposed.secret TO PUBLIC");
        try{assertThrows(IllegalStateException.class,()->create(r));absent(r);}
        finally{db.admin().execute("DROP SCHEMA fixture_exposed CASCADE");}
    }
    @Test void stale_admin_lease_epoch_or_shared_key_cannot_create_a_role()throws Exception{
        var adminRequest=request();db.admin().update("UPDATE users SET banned=true WHERE id=?",actor);assertThrows(IllegalStateException.class,()->create(adminRequest));absent(adminRequest);
        db.admin().update("UPDATE users SET banned=false WHERE id=?",actor);var leaseRequest=request();
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET generation=2 WHERE singleton");assertThrows(IllegalStateException.class,()->create(leaseRequest));absent(leaseRequest);
        db.admin().update("UPDATE eatwhat_cluster.control_lease SET generation=1 WHERE singleton");var original=request();
        var epochRequest=new AuthorityAuthRole.Request(original.id(),original.bootstrapId(),original.targetServerId(),original.clusterId(),original.authoritySiteId(),original.authorityInstanceId(),
                original.authorityDatabaseFingerprint(),2,original.stateId(),original.holderId(),original.generation(),original.actorId(),original.adminBinding(),original.password(),original.salt(),original.validUntil());
        assertThrows(IllegalStateException.class,()->create(epochRequest));absent(epochRequest);
        var keyRequest=request();try(var c=admin()){assertThrows(IllegalStateException.class,()->AuthorityAuthRole.create(c,keyRequest,new byte[32]));}absent(keyRequest);
    }
    @Test void genuine_lease_user_and_state_locks_are_held_until_commit_and_session_settings_restore()throws Exception{
        var r=request();try(var c=admin()){
            var properties=List.of("log_statement","log_min_error_statement","log_min_duration_statement","log_min_duration_sample","log_duration","statement_timeout","lock_timeout");var before=new HashMap<String,String>();for(String p:properties)before.put(p,show(c,p));
            AtomicInteger commits=new AtomicInteger();Connection guarded=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("commit")){
                    commits.incrementAndGet();for(String table:List.of("eatwhat_cluster.control_lease","eatwhat_cluster.state"))assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT singleton FROM "+table+" WHERE singleton FOR UPDATE NOWAIT"));
                    assertThrows(DataAccessException.class,()->db.admin().queryForList("SELECT id FROM users WHERE id=? FOR UPDATE NOWAIT",actor));
                }
                try{return method.invoke(c,args);}catch(InvocationTargetException e){throw e.getCause();}
            });
            AuthorityAuthRole.create(guarded,r,key);assertEquals(1,commits.get());assertTrue(c.getAutoCommit());for(String p:properties)assertEquals(before.get(p),show(c,p));
        }
    }
    @Test void lost_commit_ack_is_reconciled_by_original_role_proof_without_password_reset()throws Exception{
        var r=request();try(var c=admin()){
            Connection lost=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                try{Object result=method.invoke(c,args);if(method.getName().equals("commit"))throw new SQLException("synthetic lost commit acknowledgement");return result;}catch(InvocationTargetException e){throw e.getCause();}
            });assertThrows(SQLException.class,()->AuthorityAuthRole.create(lost,r,key));
        }
        try(var c=admin()){assertEquals("ROLE_READY",AuthorityAuthRole.inspect(c,r).get("state"));}try(var c=login(r)){assertTrue(c.isValid(3));}assertThrows(IllegalStateException.class,()->create(r));
    }
    @Test void later_privilege_drift_is_refused_without_repairing_the_role()throws Exception{
        var r=request();create(r);db.admin().execute("GRANT UPDATE(banned) ON public.users TO "+r.username());
        try(var c=admin()){assertThrows(IllegalStateException.class,()->AuthorityAuthRole.inspect(c,r));}
        assertTrue(db.admin().queryForObject("SELECT has_column_privilege(?,'public.users','banned','UPDATE')",Boolean.class,r.username()));
    }
    @Test void regular_application_connection_is_never_promoted_for_role_creation()throws Exception{
        var r=request();try(var c=DriverManager.getConnection(db.url(),db.runtimeUsername(),IsolatedContainers.PASSWORD)){assertThrows(IllegalStateException.class,()->AuthorityAuthRole.create(c,r,key));assertTrue(c.getAutoCommit());}absent(r);
    }
    @Test void delegation_or_an_extra_security_definer_entrypoint_is_refused_without_repair()throws Exception{
        var r=request();create(r);db.admin().execute("GRANT SELECT(id) ON public.users TO "+r.username()+" WITH GRANT OPTION");
        try(var c=admin()){assertThrows(IllegalStateException.class,()->AuthorityAuthRole.inspect(c,r));}
        assertTrue(db.admin().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_attribute a CROSS JOIN LATERAL aclexplode(a.attacl) x WHERE a.attrelid='public.users'::regclass AND x.grantee=(SELECT oid FROM pg_roles WHERE rolname=?) AND x.is_grantable)",Boolean.class,r.username()));
        var other=request();create(other);db.admin().execute("CREATE FUNCTION public.fixture_role_definer() RETURNS integer LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS 'SELECT 1'");
        try{
            try(var c=admin()){assertThrows(IllegalStateException.class,()->AuthorityAuthRole.inspect(c,other));}
            assertTrue(db.admin().queryForObject("SELECT has_function_privilege(?,'public.fixture_role_definer()','EXECUTE')",Boolean.class,other.username()));
        }finally{db.admin().execute("DROP FUNCTION public.fixture_role_definer()");}
    }
    @Test void missing_original_reservation_and_unbounded_credential_lifetime_reject_before_creation()throws Exception{
        var r=request();var missing=copy(r,UUID.randomUUID(),r.validUntil());assertThrows(IllegalStateException.class,()->create(missing));absent(missing);
        for(Instant until:List.of(Instant.now().truncatedTo(ChronoUnit.SECONDS),Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(400)))){
            var invalid=copy(r,r.targetServerId(),until);assertThrows(IllegalStateException.class,()->create(invalid));absent(invalid);
        }
    }
    AuthorityAuthRole.Request copy(AuthorityAuthRole.Request r,UUID target,Instant until){
        return new AuthorityAuthRole.Request(r.id(),r.bootstrapId(),target,r.clusterId(),r.authoritySiteId(),r.authorityInstanceId(),r.authorityDatabaseFingerprint(),r.epoch(),r.stateId(),r.holderId(),r.generation(),r.actorId(),r.adminBinding(),r.password(),r.salt(),until);
    }
}
