package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.cluster.ClusterMember;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Three production Spring servers, databases, Redis namespaces and real RustFS buckets; no business UI. */
class ClusterSeamlessIT {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final GenericContainer<?> PG=IsolatedContainers.postgres("cluster-web"),AUTH_PG=IsolatedContainers.postgres("cluster-auth"),REDIS=IsolatedContainers.redis("cluster-web"),S3=IsolatedContainers.rustfs("cluster-web");
    @TempDir Path directory;
    final UUID cluster=UUID.randomUUID();final List<ClusterMember> members=List.of(new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0),new ClusterMember("external",UUID.randomUUID(),"EXTERNAL",1),new ClusterMember("third",UUID.randomUUID(),"EXTERNAL",2));
    final List<EventFoundationTestSupport.Database> databases=new ArrayList<>();final List<Process> processes=new ArrayList<>();final List<Integer> ports=new ArrayList<>();
    final List<String> buckets=new ArrayList<>();String authClientRole;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();String adminToken;
    final String apiPrefix=you.v50to.eatwhat.control.ControlService.apiPrefix(System.getProperty("eatwhat.acceptance.node-api-prefix",""));
    final boolean prefixProxy=Boolean.getBoolean("eatwhat.acceptance.node-api-proxy");
    boolean membersOnly=Boolean.getBoolean("eatwhat.acceptance.member-routing-only");
    final Map<Integer,com.sun.net.httpserver.HttpServer> gateways=new HashMap<>();
    final java.util.concurrent.ExecutorService gatewayThreads=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    final java.util.concurrent.atomic.AtomicInteger forwarded=new java.util.concurrent.atomic.AtomicInteger();
    @BeforeAll static void services(){PG.start();AUTH_PG.start();REDIS.start();S3.start();}
    @AfterAll static void cleanup(){S3.stop();REDIS.stop();AUTH_PG.stop();PG.stop();}
    @AfterEach void stop()throws Exception{try{for(var process:processes){process.destroy();if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}}finally{gateways.values().forEach(g->g.stop(0));gatewayThreads.shutdownNow();}}
    String origin(int node){
        if(!prefixProxy)return "http://127.0.0.1:"+ports.get(node);
        if(apiPrefix.isEmpty())throw new IllegalArgumentException("Proxy acceptance requires a nonempty API prefix");
        var gateway=gateways.computeIfAbsent(node,n->{try{
            var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(gatewayThreads);
            server.createContext("/",exchange->{
                try{
                    String path=exchange.getRequestURI().getRawPath();
                    if(path.equals("/")){byte[] bytes="<!doctype html><title>Business frontend fixture</title>".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);return;}
                    if(!path.startsWith(apiPrefix+"/")){exchange.sendResponseHeaders(404,-1);return;}
                    String query=exchange.getRequestURI().getRawQuery();
                    // Only this test's fixed backend port is reachable; strip the prefix exactly once.
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ports.get(n)+path.substring(apiPrefix.length())+(query==null?"":"?"+query))).timeout(Duration.ofSeconds(30));
                    exchange.getRequestHeaders().forEach((name,values)->{if(!Set.of("host","connection","content-length","upgrade","http2-settings","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))values.forEach(v->request.header(name,v));});
                    byte[] body=exchange.getRequestBody().readAllBytes();request.method(exchange.getRequestMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
                    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
                    response.headers().map().forEach((name,values)->{if(!Set.of("connection","content-length","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))exchange.getResponseHeaders().put(name,values);});
                    forwarded.incrementAndGet();exchange.sendResponseHeaders(response.statusCode(),response.body().length==0?-1:response.body().length);if(response.body().length>0)exchange.getResponseBody().write(response.body());
                }catch(Exception failure){try{exchange.sendResponseHeaders(502,-1);}catch(java.io.IOException ignored){}}
                finally{exchange.close();}
            });server.start();return server;
        }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}});
        return "http://127.0.0.1:"+gateway.getAddress().getPort();
    }
    String base(int node){return origin(node)+apiPrefix;}
    String machine(int node){return "synthetic-cluster-node-"+node+"-"+"x".repeat(48);}
    void prepare()throws Exception{prepare(false);}
    void prepare(boolean controlHa)throws Exception{
        String endpoint="http://"+S3.getHost()+":"+S3.getMappedPort(9000);
        for(var member:members){var site=new SiteProperties();site.setId(member.siteId());site.setInstanceId(member.instanceId());site.setMode(SiteProperties.Mode.valueOf(member.mode()));site.setClusterId(cluster);site.setAllocationSlot(member.allocationSlot());databases.add(EventFoundationTestSupport.createDatabase(member.allocationSlot()==0?AUTH_PG:PG,site));try(var socket=new java.net.ServerSocket(0)){ports.add(socket.getLocalPort());}}
        long admin=databases.getFirst().runtime().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES('meshadmin',?,'admin') RETURNING id",Long.class,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("synthetic-password"));
        authClientRole="auth_client_"+UUID.randomUUID().toString().replace("-","");
        var authAdmin=databases.getFirst().admin();authAdmin.execute("CREATE ROLE "+authClientRole+" LOGIN PASSWORD 'synthetic-test-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT");
        authAdmin.execute("GRANT USAGE ON SCHEMA public,eatwhat_identity,eatwhat_consent,eatwhat_cluster TO "+authClientRole);
        authAdmin.execute("GRANT SELECT(id,password_hash,banned,role) ON public.users TO "+authClientRole);
        authAdmin.execute("GRANT SELECT(entity_type,numeric_id,global_id) ON eatwhat_identity.entity_identity TO "+authClientRole);
        authAdmin.execute("GRANT SELECT ON eatwhat_identity.allocation,eatwhat_cluster.state,eatwhat_cluster.local_state,eatwhat_cluster.local_change,eatwhat_cluster.local_phase,eatwhat_cluster.recipient_grant,eatwhat_cluster.session_security_version TO "+authClientRole);
        authAdmin.execute("GRANT SELECT(subject_id,user_id,epoch,revision,status,withdrawal_request_id,origin_site) ON eatwhat_consent.subject TO "+authClientRole);
        authAdmin.execute("GRANT SELECT,INSERT,UPDATE(revoked,last_seen) ON eatwhat_cluster.login_session TO "+authClientRole);
        authAdmin.execute("GRANT SELECT,INSERT ON eatwhat_cluster.session_withdraw_fence TO "+authClientRole);
        assertFalse(Boolean.TRUE.equals(authAdmin.queryForObject("SELECT has_table_privilege(?,'public.users','UPDATE')",Boolean.class,authClientRole)));
        try(var client=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY))).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()){
            for(int n=0;n<3;n++){String bucket="mesh-"+UUID.randomUUID();buckets.add(bucket);client.createBucket(b->b.bucket(bucket));var token=directory.resolve("node-"+n+".token");Files.writeString(token,machine(n));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));var props=new Properties();var db=databases.get(n);var member=members.get(n);var peer=members.get(n==0?1:0);
                props.setProperty("spring.autoconfigure.exclude","work.foofish.smsverify.SmsAutoConfiguration,io.sentry.spring.boot.jakarta.SentryAutoConfiguration");
                props.setProperty("sa-token.cookie-auto-fill-prefix","true");
                if(!apiPrefix.isEmpty()&&!prefixProxy)props.setProperty("server.servlet.context-path",apiPrefix);
                props.setProperty("server.port",ports.get(n).toString());props.setProperty("spring.datasource.url",db.url());props.setProperty("spring.datasource.username",db.runtimeUsername());props.setProperty("spring.datasource.password",IsolatedContainers.PASSWORD);props.setProperty("spring.flyway.url",db.url());props.setProperty("spring.flyway.user",IsolatedContainers.USER);props.setProperty("spring.flyway.password",IsolatedContainers.PASSWORD);props.setProperty("spring.datasource.hikari.maximum-pool-size","8");
                props.setProperty("spring.data.redis.host",REDIS.getHost());props.setProperty("spring.data.redis.port",REDIS.getMappedPort(6379).toString());props.setProperty("spring.data.redis.database",Integer.toString(n));
                props.setProperty("app.site.mode",member.mode());props.setProperty("app.site.id",member.siteId());props.setProperty("app.site.instance-id",member.instanceId().toString());props.setProperty("app.site.cluster-id",cluster.toString());props.setProperty("app.site.allocation-slot",Integer.toString(n));
                props.setProperty("app.storage.s3.provider","RUSTFS");props.setProperty("app.storage.s3.endpoint",endpoint);props.setProperty("app.storage.s3.region","us-east-1");props.setProperty("app.storage.s3.bucket",bucket);props.setProperty("app.storage.s3.access-key",IsolatedContainers.RUSTFS_ACCESS_KEY);props.setProperty("app.storage.s3.secret-key",IsolatedContainers.RUSTFS_SECRET_KEY);props.setProperty("app.storage.s3.path-style","true");props.setProperty("app.storage.s3.public-base-url","https://cdn.example.invalid");props.setProperty("app.storage.s3.cdn-endpoint","");
                props.setProperty("app.storage.files.lifecycle-enabled","true");props.setProperty("app.storage.files.replication.enabled","true");props.setProperty("app.storage.files.replication.worker-enabled","false");
                props.setProperty("app.storage.files.gc.enabled","true");props.setProperty("app.storage.files.gc.inventory-enabled","true");props.setProperty("app.storage.files.gc.execute-enabled","true");props.setProperty("app.storage.files.gc.dry-run","false");props.setProperty("app.storage.files.gc.coordinated-enabled","true");props.setProperty("app.storage.files.gc.execute-ack","synthetic-isolated-restore-drill");props.setProperty("app.storage.files.gc.execute-classes","UNREFERENCED_REVISION");
                props.setProperty("app.cluster.automatic-gc-enabled","false");
                props.setProperty("app.account.enabled","true");props.setProperty("app.account.synthetic-only","true");props.setProperty("app.account.peer-site-id",peer.siteId());props.setProperty("app.account.peer-instance-id",peer.instanceId().toString());
                props.setProperty("app.sync.transport-enabled","true");props.setProperty("app.sync.synthetic-only","false");props.setProperty("app.sync.peer-site-id",peer.siteId());props.setProperty("app.sync.peer-instance-id",peer.instanceId().toString());
                for(String domain:List.of("activity","relation","profile","privacy","notification","catalog","files")){props.setProperty("app.sync."+domain+".enabled","true");props.setProperty("app.sync."+domain+".peer-site-id",peer.siteId());}
                props.setProperty("app.control.enabled","true");props.setProperty("app.control.node-enabled","true");props.setProperty("app.control.environment-kind","isolated-acceptance");props.setProperty("app.control.state-directory",directory.resolve("state-"+n).toString());props.setProperty("app.control.node-token-file",token.toString());props.setProperty("app.cluster.enabled","true");props.setProperty("app.cluster.coordinator",Boolean.toString(n==0));
                if(n==0&&controlHa){props.setProperty("app.cluster.control-lease.enabled","true");props.setProperty("app.cluster.control-lease.duration-seconds","5");}
                if(n==0)for(int i=0;i<3;i++){String prefix="app.cluster.profiles."+members.get(i).siteId()+".";props.setProperty(prefix+"site-id",members.get(i).siteId());props.setProperty(prefix+"instance-id",members.get(i).instanceId().toString());props.setProperty(prefix+"mode",members.get(i).mode());props.setProperty(prefix+"allocation-slot",Integer.toString(i));props.setProperty(prefix+"origin",origin(i));props.setProperty(prefix+"api-prefix",apiPrefix);props.setProperty(prefix+"token-file",directory.resolve("node-"+i+".token").toString());}
                else{props.setProperty("app.cluster.authority-origin",origin(0));props.setProperty("app.cluster.authority-api-prefix",apiPrefix);props.setProperty("app.cluster.authority-site-id",members.getFirst().siteId());props.setProperty("app.cluster.authority-instance-id",members.getFirst().instanceId().toString());
                    var authPassword=directory.resolve("auth-store-"+n+".password");Files.writeString(authPassword,IsolatedContainers.PASSWORD);Files.setPosixFilePermissions(authPassword,PosixFilePermissions.fromString("rw-------"));
                    props.setProperty("app.cluster.auth-store.enabled","true");props.setProperty("app.cluster.auth-store.jdbc-url",databases.getFirst().url());props.setProperty("app.cluster.auth-store.username",authClientRole);props.setProperty("app.cluster.auth-store.password-file",authPassword.toString());}
                if(membersOnly){
                    if(n>0){
                        // Run the actual managed enrollment renderer; only fixture-mounted paths and
                        // deliberate isolated GC execution differ from the managed-container values.
                        props.putAll(you.v50to.eatwhat.control.ControlMemberConfiguration.runtime(cluster,member,members.getFirst(),origin(0),apiPrefix,databases.getFirst().url().split("\\?",2)[0],authClientRole));
                        props.setProperty("app.cluster.auth-store.password-file",directory.resolve("auth-store-"+n+".password").toString());
                        props.setProperty("app.storage.files.gc.execute-enabled","true");
                    }
                    props.setProperty("app.account.synthetic-only","false");
                    props.keySet().removeIf(key->key.toString().matches("app\\.(account|sync(?:\\.[a-z]+)?)\\.peer-.*"));
                    assertTrue(props.stringPropertyNames().stream().noneMatch(key->key.matches("app\\.(account|sync(?:\\.[a-z]+)?)\\.peer-.*")),"No fixed peer setting may remain in member-routing acceptance");
                }
                configureNode(props,n);
                Path config=directory.resolve("node-"+n+".properties");try(var out=Files.newOutputStream(config)){props.store(out,"synthetic isolated cluster");}Files.setPosixFilePermissions(config,PosixFilePermissions.fromString("rw-------"));
            }
        }
        for(int n=0;n<3;n++){var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx384m","-cp",System.getProperty("surefire.test.class.path"),ClusterNodeProcessMain.class.getName(),directory.resolve("node-"+n+".properties").toString());command.redirectErrorStream(true);command.redirectOutput(directory.resolve("node-"+n+".log").toFile());processes.add(command.start());}
        for(int n=0;n<3;n++){final int index=n;await(90,()->{if(!processes.get(index).isAlive())throw new AssertionError("Node startup failed: "+Files.readString(directory.resolve("node-"+index+".log")));try{return machine(index,"GET","probe",null).statusCode()==200;}catch(Exception unavailable){return false;}});}
    }
    /** Test-specific opt-in configuration; production-like defaults above remain unchanged. */
    void configureNode(Properties properties,int node) { }
    HttpResponse<String> machine(int node,String method,String operation,Object input)throws Exception{return http.send(HttpRequest.newBuilder(URI.create(base(node)+"/internal/cluster/v1/"+operation)).timeout(Duration.ofSeconds(15)).header("X-Control-Node-Token",machine(node)).header("Content-Type","application/json").method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> request(int node,String method,String path,Object body,String token)throws Exception{var b=HttpRequest.newBuilder(URI.create(base(node)+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");if(token!=null)b.header("eatwhat-test-token","Bearer "+token);return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    String login(int n,String username)throws Exception{var r=request(n,"POST","/auth/login",Map.of("username",username,"password","synthetic-password","device","WEB"),null);var json=JSON.readTree(r.body());assertEquals(0,json.path("code").asInt(-1),r.body());return json.path("data").path("tokenValue").asText();}
    void join(int index,long epoch)throws Exception{String root="/admin/control/v1/cluster";var register=request(0,"POST",root+"/sites",Map.of("requestId",UUID.randomUUID(),"expectedEpoch",epoch,"profileId",members.get(index).siteId()),adminToken);assertEquals(201,register.statusCode(),register.body());var result=request(0,"POST",root+"/sites/"+members.get(index).siteId()+"/join",Map.of("requestId",UUID.randomUUID(),"expectedEpoch",epoch),adminToken);assertEquals(202,result.statusCode(),result.body());UUID change=UUID.fromString(JSON.readTree(result.body()).path("change_id").asText());var lastJoinState=new java.util.concurrent.atomic.AtomicReference<String>();try{await(90,()->{var state=request(0,"GET",root+"/changes/"+change,null,adminToken);lastJoinState.set(state.body());if(state.statusCode()==401){adminToken=login(0,"meshadmin");return false;}assertEquals(200,state.statusCode(),state.body());return JSON.readTree(state.body()).path("state").asText().equals("APPLIED");});}catch(AssertionError failure){throw new AssertionError("Join failed: "+lastJoinState.get()+" worker="+request(0,"GET",root,null,adminToken).body()+" retry="+request(0,"POST",root+"/changes/"+change+"/resume",Map.of(),adminToken).body(),failure);}}
    @FunctionalInterface interface Check{boolean test()throws Exception;}
    static void await(int seconds,Check check)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(System.nanoTime()<deadline){if(check.test())return;Thread.sleep(250);}fail("Cluster condition did not converge");}
    int startAuthorityStandby()throws Exception{
        var props=new Properties();try(var input=Files.newInputStream(directory.resolve("node-0.properties"))){props.load(input);}
        int standby=ports.size();try(var socket=new java.net.ServerSocket(0)){ports.add(socket.getLocalPort());}
        props.setProperty("server.port",ports.get(standby).toString());
        props.setProperty("app.cluster.profiles.campus.origin",origin(standby));
        Path config=directory.resolve("node-"+standby+".properties");try(var output=Files.newOutputStream(config)){props.store(output,"synthetic authority standby");}
        Files.setPosixFilePermissions(config,PosixFilePermissions.fromString("rw-------"));
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx384m","-cp",System.getProperty("surefire.test.class.path"),ClusterNodeProcessMain.class.getName(),config.toString());
        command.redirectErrorStream(true);command.redirectOutput(directory.resolve("node-"+standby+".log").toFile());processes.add(command.start());
        await(90,()->{if(!processes.get(standby).isAlive())throw new AssertionError("Standby startup failed: "+Files.readString(directory.resolve("node-"+standby+".log")));try{return request(standby,"GET","/admin/control/v1/cluster/leadership",null,adminToken).statusCode()==200;}catch(Exception unavailable){return false;}});
        return standby;
    }
    @Test void authorityBusinessRuntimeServesFullDataPlaneWithoutClaimingAnExistingControlLease()throws Exception{
        prepare(true);adminToken=login(0,"meshadmin");join(0,0);adminToken=login(0,"meshadmin");
        var db=databases.getFirst().admin();
        String identity=db.queryForObject("SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n WHERE singleton",String.class);
        String localState=db.queryForObject("SELECT row_to_json(s)::text FROM eatwhat_cluster.local_state s WHERE singleton",String.class);
        UUID holder=db.queryForObject("SELECT holder_id FROM eatwhat_cluster.control_lease WHERE singleton",UUID.class);
        long generation=db.queryForObject("SELECT generation FROM eatwhat_cluster.control_lease WHERE singleton",Long.class);
        var props=new Properties();try(var input=Files.newInputStream(directory.resolve("node-0.properties"))){props.load(input);}
        props.putAll(you.v50to.eatwhat.control.ControlAuthorityRuntime.business(cluster,members.getFirst(),false));
        props.keySet().removeIf(key->key.toString().matches("app\\.(account|sync(?:\\.[a-z]+)?)\\.peer-.*"));
        int business=ports.size();try(var socket=new java.net.ServerSocket(0)){ports.add(socket.getLocalPort());}
        props.setProperty("server.port",ports.get(business).toString());
        props.setProperty("app.control.state-directory",directory.resolve("authority-business-state").toString());
        props.setProperty("app.cluster.profiles.campus.origin",origin(business));
        Path config=directory.resolve("node-"+business+".properties");try(var output=Files.newOutputStream(config)){props.store(output,"synthetic candidate-false authority business runtime");}
        Files.setPosixFilePermissions(config,PosixFilePermissions.fromString("rw-------"));
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx384m","-cp",System.getProperty("surefire.test.class.path"),ClusterNodeProcessMain.class.getName(),config.toString());
        command.redirectErrorStream(true);command.redirectOutput(directory.resolve("node-"+business+".log").toFile());processes.add(command.start());
        await(90,()->{if(!processes.get(business).isAlive())throw new AssertionError("Authority business startup failed: "+Files.readString(directory.resolve("node-"+business+".log")));try{return request(business,"GET","/actuator/health/readiness",null,null).statusCode()==200;}catch(Exception unavailable){return false;}});
        // Same logical identity/token, deliberately a different application process.
        var response=http.send(HttpRequest.newBuilder(URI.create(base(business)+"/internal/cluster/v1/probe")).header("X-Control-Node-Token",machine(0)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());var probe=JSON.readTree(response.body());
        assertEquals("MULTI_SITE",probe.path("dataPlaneMode").asText());assertTrue(probe.path("dataPlaneReady").asBoolean());
        assertEquals(members.getFirst().siteId(),probe.path("siteId").asText());
        assertEquals(identity,db.queryForObject("SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n WHERE singleton",String.class));
        assertEquals(localState,db.queryForObject("SELECT row_to_json(s)::text FROM eatwhat_cluster.local_state s WHERE singleton",String.class));
        assertEquals(holder,db.queryForObject("SELECT holder_id FROM eatwhat_cluster.control_lease WHERE singleton",UUID.class));
        assertEquals(generation,db.queryForObject("SELECT generation FROM eatwhat_cluster.control_lease WHERE singleton",Long.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM eatwhat_cluster.control_process WHERE expires_at>clock_timestamp()",Integer.class));
        assertTrue(JSON.readTree(request(0,"GET","/admin/control/v1/cluster/leadership",null,adminToken).body()).path("localLeader").asBoolean());
        assertEquals(503,request(business,"POST","/admin/control/v1/cluster/profiles/reload",Map.of(),adminToken).statusCode());
    }
    @Test void administratorHandoffKeepsBothAuthorityProcessesOnline()throws Exception{
        prepare(true);adminToken=login(0,"meshadmin");int target=startAuthorityStandby();
        var leadership=JSON.readTree(request(target,"GET","/admin/control/v1/cluster/leadership",null,adminToken).body());
        UUID id=UUID.randomUUID();var body=Map.of("requestId",id,"expectedGeneration",leadership.path("generation").asLong(),"targetControllerId",leadership.path("localControllerId").asText());
        var accepted=request(target,"POST","/admin/control/v1/cluster/leadership/handoffs",body,adminToken);assertEquals(202,accepted.statusCode(),accepted.body());
        await(20,()->{var state=JSON.readTree(request(target,"GET","/admin/control/v1/cluster/leadership",null,adminToken).body());return state.path("localLeader").asBoolean()&&state.path("handoff").path("state").asText().equals("COMPLETED");});
        assertTrue(processes.getFirst().isAlive());assertTrue(processes.get(target).isAlive());
        assertFalse(JSON.readTree(request(0,"GET","/admin/control/v1/cluster/leadership",null,adminToken).body()).path("localLeader").asBoolean());
        assertEquals(503,request(0,"POST","/admin/control/v1/cluster/profiles/reload",Map.of(),adminToken).statusCode());
        assertEquals(200,request(target,"POST","/admin/control/v1/cluster/profiles/reload",Map.of(),adminToken).statusCode());
        var replay=request(0,"POST","/admin/control/v1/cluster/leadership/handoffs",body,adminToken);assertEquals(202,replay.statusCode(),replay.body());assertEquals("COMPLETED",JSON.readTree(replay.body()).path("state").asText());
    }
    @Test void authorityStandbyTakesOverControlWritesWithoutAdminRelogin()throws Exception{
        prepare(true);adminToken=login(0,"meshadmin");join(0,0);adminToken=login(0,"meshadmin");join(1,1);
        int standby=startAuthorityStandby();
        var before=JSON.readTree(request(standby,"GET","/admin/control/v1/cluster/leadership",null,adminToken).body());
        assertFalse(before.path("localLeader").asBoolean(),before.toString());
        assertEquals(503,request(standby,"POST","/admin/control/v1/cluster/sites",Map.of("requestId",UUID.randomUUID(),"expectedEpoch",2,"profileId","third"),adminToken).statusCode());
        processes.getFirst().destroy();assertTrue(processes.getFirst().waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        await(20,()->JSON.readTree(request(standby,"GET","/admin/control/v1/cluster/leadership",null,adminToken).body()).path("localLeader").asBoolean());
        var business=request(standby,"GET","/user/info",null,adminToken);
        assertEquals(200,business.statusCode(),business.body());
        assertEquals(0,JSON.readTree(business.body()).path("code").asInt(-1),business.body());
        var registered=request(standby,"POST","/admin/control/v1/cluster/sites",Map.of("requestId",UUID.randomUUID(),"expectedEpoch",2,"profileId","third"),adminToken);
        assertEquals(201,registered.statusCode(),registered.body());
        assertEquals("REGISTERED",databases.getFirst().admin().queryForObject("SELECT state FROM eatwhat_cluster.member WHERE site_id='third'",String.class));
        var joined=request(standby,"POST","/admin/control/v1/cluster/sites/third/join",Map.of("requestId",UUID.randomUUID(),"expectedEpoch",2),adminToken);
        assertEquals(202,joined.statusCode(),joined.body());UUID change=UUID.fromString(JSON.readTree(joined.body()).path("change_id").asText());
        await(90,()->JSON.readTree(request(standby,"GET","/admin/control/v1/cluster/changes/"+change,null,adminToken).body()).path("state").asText().equals("APPLIED"));
        assertEquals(3,databases.getFirst().admin().queryForObject("SELECT epoch FROM eatwhat_cluster.state WHERE singleton",Long.class));
        var status=request(standby,"GET","/admin/control/v1/cluster",null,adminToken);
        assertEquals(200,status.statusCode(),status.body());assertTrue(JSON.readTree(status.body()).path("leadership").path("localLeader").asBoolean());
    }
    @Test void sameBusinessTokenSurvivesThreeServerSwitchesSourceFailureAndRevokesEverywhere()throws Exception{
        prepare();adminToken=login(0,"meshadmin");join(0,0);adminToken=login(0,"meshadmin");join(1,1);join(2,2);
        if(prefixProxy){var root=http.send(HttpRequest.newBuilder(URI.create(origin(0)+"/")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,root.statusCode());assertTrue(root.body().contains("Business frontend fixture"));assertEquals(404,http.send(HttpRequest.newBuilder(URI.create(origin(0)+"/internal/cluster/v1/probe")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());}
        for(String path:List.of("/capabilities","/profiles","/channels","/observations","/changes","/gc","/gc/candidates","/audit")){var response=request(0,"GET","/admin/control/v1/cluster"+path,null,adminToken);assertEquals(200,response.statusCode(),path+":"+response.body());assertNotNull(JSON.readTree(response.body()));}
        assertEquals(400,request(0,"GET","/admin/control/v1/cluster/changes?limit=101",null,adminToken).statusCode());
        var registered=request(0,"POST","/auth/register",Map.of("username","meshuser","password","synthetic-password","device","WEB","crossBorderConsent",true,"consentPolicyVersion","cross-border-v1"),null);assertEquals(0,JSON.readTree(registered.body()).path("code").asInt(-1),registered.body());String token=JSON.readTree(registered.body()).path("data").path("tokenValue").asText();assertFalse(token.isBlank());
        await(60,()->databases.get(1).admin().queryForObject("SELECT count(*) FROM eatwhat_consent.subject WHERE status='GRANTED'",Long.class)==1);
        assertEquals(0,databases.get(2).admin().queryForObject("SELECT count(*) FROM eatwhat_consent.subject",Integer.class),"Joining must not silently expand personal-data recipients");
        assertEquals(409,request(2,"GET","/user/info",null,token).statusCode());
        var grant=request(0,"POST","/auth/consent/recipients/third",Map.of("policyVersion","cross-border-v1"),token);assertEquals(200,grant.statusCode(),grant.body());
        assertEquals(403,request(0,"GET","/admin/control/v1/cluster/gc",null,token).statusCode());
        for(int n:List.of(1,2)){final int target=n;var last=new java.util.concurrent.atomic.AtomicReference<String>();try{await(60,()->{var r=request(target,"GET","/auth/consent",null,token);last.set(r.statusCode()+":"+r.body());return r.statusCode()==200&&JSON.readTree(r.body()).path("code").asInt(-1)==0;});}catch(AssertionError failure){throw new AssertionError("target="+target+" last="+last.get()+" worker="+request(0,"GET","/admin/control/v1/cluster",null,adminToken).body()+" accounts="+databases.stream().map(db->db.admin().queryForList("SELECT user_id,status,epoch,revision,identity_ready,backfill_status FROM eatwhat_consent.subject")).toList()+" jobs="+databases.getFirst().admin().queryForList("SELECT kind,status,attempts FROM eatwhat_consent.job"),failure);}}
        for(int n=0;n<3;n++){var r=request(n,"GET","/user/info",null,token);assertEquals(200,r.statusCode(),r.body());assertEquals(0,JSON.readTree(r.body()).path("code").asInt(-1),r.body());}
        for(int source=0;source<3;source++){boolean value=source==1;var change=request(source,"POST","/user/changePrivacy",Map.of("following",value,"follower",value),token);assertEquals(0,JSON.readTree(change.body()).path("code").asInt(-1),change.body());await(45,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM privacy WHERE following=? AND follower=?",Long.class,value,value)==1));}
        assertEquals(3,databases.getFirst().admin().queryForObject("SELECT count(DISTINCT origin_site) FROM eatwhat_sync.privacy_operation",Integer.class));
        realFileAndThreePartyDeletion(token);
        var browser=HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).build();
        var loginReply=browser.send(HttpRequest.newBuilder(URI.create(base(1)+"/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username","meshuser","password","synthetic-password","device","WEB")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(0,JSON.readTree(loginReply.body()).path("code").asInt(-1),loginReply.body());String externalToken=JSON.readTree(loginReply.body()).path("data").path("tokenValue").asText();processes.get(1).destroy();assertTrue(processes.get(1).waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        for(int n:List.of(0,2)){var cookieReply=browser.send(HttpRequest.newBuilder(URI.create(base(n)+"/user/info")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,cookieReply.statusCode(),cookieReply.body());assertEquals(0,JSON.readTree(cookieReply.body()).path("code").asInt(-1),cookieReply.body());var r=request(n,"GET","/auth/consent",null,externalToken);assertEquals(200,r.statusCode(),r.body());assertEquals(0,JSON.readTree(r.body()).path("code").asInt(-1),r.body());}
        assertEquals(200,request(2,"POST","/auth/logout",Map.of(),externalToken).statusCode());assertEquals(401,request(0,"GET","/auth/consent",null,externalToken).statusCode());assertEquals(401,request(2,"GET","/auth/consent",null,externalToken).statusCode());
        assertEquals(0,databases.getFirst().admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.login_session WHERE token_hash=?",Integer.class,externalToken));
        String beforeBan=login(0,"meshuser");long user=databases.getFirst().admin().queryForObject("SELECT id FROM users WHERE nick_name='meshuser'",Long.class);
        var banned=request(0,"POST","/user/ban/"+user,Map.of("reason","synthetic isolation drill"),adminToken);assertEquals(0,JSON.readTree(banned.body()).path("code").asInt(-1),banned.body());assertEquals(401,request(2,"GET","/user/info",null,beforeBan).statusCode());
        var unbanned=request(0,"POST","/user/unban/"+user,Map.of(),adminToken);assertEquals(0,JSON.readTree(unbanned.body()).path("code").asInt(-1),unbanned.body());
        assertEquals(401,request(2,"GET","/user/info",null,beforeBan).statusCode(),"Unbanning must not revive an old login token");
        String beforePassword=login(0,"meshuser");var changed=request(0,"POST","/auth/changePassword",Map.of("oldPassword","synthetic-password","newPassword","synthetic-replaced-password"),beforePassword);assertEquals(0,JSON.readTree(changed.body()).path("code").asInt(-1),changed.body());
        assertEquals(401,request(0,"GET","/user/info",null,beforePassword).statusCode());assertEquals(401,request(2,"GET","/user/info",null,beforePassword).statusCode());
        if(prefixProxy)assertTrue(forwarded.get()>30,"Actual node, account and file traffic must pass through the fixed prefix-stripping proxies");
    }
    @Test void memberRoutingPublishesAllDomainsWithoutLegacyPeerConfiguration()throws Exception{
        membersOnly=true;prepare();
        for(int n:List.of(1,2)){
            var closed=request(n,"POST","/auth/login",Map.of("username","not-yet-joined","password","synthetic-password","device","WEB"),null);assertEquals(503,closed.statusCode(),closed.body());assertEquals("SITE_ENROLLMENT_REQUIRED",JSON.readTree(closed.body()).path("code").asText());
            var proof=machine(n,"GET","enrollment-proof",null);assertEquals(200,proof.statusCode(),proof.body());var evidence=JSON.readTree(proof.body());assertEquals("VERIFIED",evidence.path("authStoreBinding").asText());assertEquals("UNJOINED",evidence.path("membership").path("state").asText());assertTrue(evidence.path("dataPlaneReady").asBoolean());assertFalse(proof.body().contains(machine(n)));
            assertEquals(401,request(n,"GET","/internal/cluster/v1/enrollment-proof",null,null).statusCode());
        }
        adminToken=login(0,"meshadmin");join(0,0);adminToken=login(0,"meshadmin");join(1,1);join(2,2);
        var actorReply=request(0,"POST","/auth/register",Map.of("username","meshactor","password","synthetic-password","device","WEB","crossBorderConsent",false),null);
        assertEquals(0,JSON.readTree(actorReply.body()).path("code").asInt(-1),actorReply.body());String actorToken=JSON.readTree(actorReply.body()).path("data").path("tokenValue").asText();
        long actor=databases.getFirst().admin().queryForObject("SELECT id FROM users WHERE nick_name='meshactor'",Long.class);
        // Isolated verification fixture only; no PASS/real email service is exercised by this test.
        databases.getFirst().runtime().update("INSERT INTO verifications(account_id,method,verified,verified_email) VALUES(?,'school_email',true,'fixture@example.invalid')",actor);
        var consentRequired=request(2,"POST","/auth/login",Map.of("username","meshactor","password","synthetic-password","device","WEB"),null);assertEquals(20005,JSON.readTree(consentRequired.body()).path("code").asInt(-1),consentRequired.body());String proof=JSON.readTree(consentRequired.body()).path("data").path("consentProof").asText();assertFalse(proof.isBlank());
        var wrongAudience=request(1,"POST","/auth/consent/grant",Map.of("proof",proof,"policyVersion","cross-border-v1"),null);assertEquals(20008,JSON.readTree(wrongAudience.body()).path("code").asInt(-1),wrongAudience.body());
        var acceptedProof=request(2,"POST","/auth/consent/grant",Map.of("proof",proof,"policyVersion","cross-border-v1"),null);assertTrue(Set.of(0,20006).contains(JSON.readTree(acceptedProof.body()).path("code").asInt(-1)),acceptedProof.body());
        var consumedProof=request(2,"POST","/auth/consent/grant",Map.of("proof",proof,"policyVersion","cross-border-v1"),null);assertEquals(20008,JSON.readTree(consumedProof.body()).path("code").asInt(-1),consumedProof.body());
        var grant=request(0,"POST","/auth/consent/grant",Map.of("policyVersion","cross-border-v1"),actorToken);assertEquals(0,JSON.readTree(grant.body()).path("code").asInt(-1),grant.body());
        assertEquals(200,request(0,"POST","/auth/consent/recipients/third",Map.of("policyVersion","cross-border-v1"),actorToken).statusCode());
        var targetReply=request(0,"POST","/auth/register",Map.of("username","meshtarget","password","synthetic-password","device","WEB","crossBorderConsent",true,"consentPolicyVersion","cross-border-v1"),null);
        assertEquals(0,JSON.readTree(targetReply.body()).path("code").asInt(-1),targetReply.body());String targetToken=JSON.readTree(targetReply.body()).path("data").path("tokenValue").asText();
        long target=databases.getFirst().admin().queryForObject("SELECT id FROM users WHERE nick_name='meshtarget'",Long.class);
        assertEquals(200,request(0,"POST","/auth/consent/recipients/third",Map.of("policyVersion","cross-border-v1"),targetToken).statusCode());
        await(90,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM users WHERE nick_name IN ('meshactor','meshtarget')",Long.class)==2&&db.admin().queryForObject("SELECT count(*) FROM verifications WHERE account_id=?",Long.class,actor)==1));
        for(int source=0;source<3;source++){
            String signature="member-routing-"+source;var changed=request(source,"PUT","/user/updateInfo",Map.of("signature",signature),actorToken);assertEquals(0,JSON.readTree(changed.body()).path("code").asInt(-1),changed.body());
            await(60,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM user_info WHERE id=? AND signature=?",Long.class,actor,signature)==1));
            boolean privacy=source!=1;var privacyReply=request(source,"POST","/user/changePrivacy",Map.of("following",privacy,"follower",privacy),actorToken);assertEquals(0,JSON.readTree(privacyReply.body()).path("code").asInt(-1),privacyReply.body());
            await(60,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM privacy WHERE account_id=? AND following=? AND follower=?",Long.class,actor,privacy,privacy)==1));
        }
        var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",png);byte[] bytes=png.toByteArray();
        var signed=request(2,"POST","/upload/presign",Map.of("biz","activity","fileName","member.png","contentType","image/png","fileSize",bytes.length),actorToken);assertEquals(0,JSON.readTree(signed.body()).path("code").asInt(-1),signed.body());var image=JSON.readTree(signed.body()).path("data");String key=image.path("key").asText();
        assertEquals(200,http.send(HttpRequest.newBuilder(URI.create(image.path("putUrl").asText())).header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        var completed=request(2,"POST","/upload/complete",Map.of("key",key),actorToken);assertEquals(0,JSON.readTree(completed.body()).path("code").asInt(-1),completed.body());
        var definition=request(0,"POST","/admin/activities",Map.of("code","member-routing","name","Member routing fixture","fieldSchema",List.of()),adminToken);assertEquals(0,JSON.readTree(definition.body()).path("code").asInt(-1),definition.body());
        var opened=request(0,"POST","/admin/activities/"+JSON.readTree(definition.body()).path("data").path("id").asLong()+"/status",Map.of("status","ONLINE"),adminToken);assertEquals(0,JSON.readTree(opened.body()).path("code").asInt(-1),opened.body());
        await(90,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM activities WHERE code='member-routing' AND status='ONLINE'",Long.class)==1));
        var activity=request(2,"POST","/activity/member-routing/posts",Map.of("title","Member routing fixture","pictureUrl",List.of(key)),actorToken);assertEquals(0,JSON.readTree(activity.body()).path("code").asInt(-1),activity.body());
        await(90,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM activity_posts WHERE title='Member routing fixture'",Long.class)==1));
        var restaurant=request(2,"POST","/restaurant/add",Map.of("name","Member routing fixture","address","Isolated fixture address","gcjLng",117.0,"gcjLat",36.0),actorToken);assertEquals(0,JSON.readTree(restaurant.body()).path("code").asInt(-1),restaurant.body());
        await(90,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM restaurant WHERE name='Member routing fixture'",Long.class)==1));
        UUID catalogObject=databases.get(2).admin().queryForObject("SELECT e.global_id FROM eatwhat_identity.entity_identity e JOIN restaurant r ON e.entity_type='restaurant' AND e.numeric_id=r.id WHERE r.name='Member routing fixture'",UUID.class);
        await(90,()->Set.copyOf(databases.get(2).admin().queryForList("SELECT DISTINCT peer_site FROM eatwhat_sync.catalog_backfill_publication WHERE kind='RESTAURANT' AND object_gid=?",String.class,catalogObject)).equals(Set.of("campus","external")));
        var followed=request(2,"POST","/user/follow?userId="+target,Map.of(),actorToken);assertEquals(0,JSON.readTree(followed.body()).path("code").asInt(-1),followed.body());
        await(90,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM follow WHERE account_id=? AND target_id=?",Long.class,actor,target)==1&&db.admin().queryForObject("SELECT count(*) FROM notifications WHERE actor_id=? AND account_id=?",Long.class,actor,target)==1));
        long notice=databases.get(2).admin().queryForObject("SELECT id FROM notifications WHERE actor_id=? AND account_id=?",Long.class,actor,target);
        var read=request(2,"POST","/notification/read/"+notice,Map.of(),targetToken);assertEquals(0,JSON.readTree(read.body()).path("code").asInt(-1),read.body());
        await(90,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM notifications WHERE actor_id=? AND account_id=? AND read_at IS NOT NULL",Long.class,actor,target)==1));
        var seen=new HashSet<String>();for(var db:databases){seen.addAll(db.admin().queryForList("SELECT DISTINCT aggregate_type FROM eatwhat_sync.outbox",String.class));assertEquals(0,db.admin().queryForObject("SELECT count(*) FROM eatwhat_sync.delivery WHERE target_site NOT IN ('campus','external','third')",Integer.class));}
        assertTrue(seen.containsAll(Set.of("activity_posts","relation.operation","profile.operation","privacy.operation","notification.read.operation","catalog.operation")),seen.toString());
    }
    @Test void authorityApplicationCanStopWhileSharedAuthStoreKeepsSessionsAndRevocationsCurrent()throws Exception{
        prepare();adminToken=login(0,"meshadmin");join(0,0);adminToken=login(0,"meshadmin");join(1,1);join(2,2);
        var registered=request(0,"POST","/auth/register",Map.of("username","hauser","password","synthetic-password","device","WEB","crossBorderConsent",true,"consentPolicyVersion","cross-border-v1"),null);
        assertEquals(0,JSON.readTree(registered.body()).path("code").asInt(-1),registered.body());
        String first=JSON.readTree(registered.body()).path("data").path("tokenValue").asText();
        assertEquals(200,request(0,"POST","/auth/consent/recipients/third",Map.of("policyVersion","cross-border-v1"),first).statusCode());
        for(int n:List.of(1,2)){final int target=n;var last=new java.util.concurrent.atomic.AtomicReference<String>();try{await(60,()->{var r=request(target,"GET","/auth/consent",null,first);last.set(r.statusCode()+":"+r.body());return r.statusCode()==200&&JSON.readTree(r.body()).path("code").asInt(-1)==0;});}catch(AssertionError failure){var lines=Files.readAllLines(directory.resolve("node-"+target+".log"));throw new AssertionError("target="+target+" last="+last.get()+" local="+databases.get(target).admin().queryForList("SELECT user_id,status,epoch,revision,identity_ready,backfill_status FROM eatwhat_consent.subject")+" jobs="+databases.getFirst().admin().queryForList("SELECT kind,status,attempts FROM eatwhat_consent.job")+" nodeLogTail="+lines.subList(Math.max(0,lines.size()-12),lines.size()),failure);}}
        var browser=HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).build();
        var loginReply=browser.send(HttpRequest.newBuilder(URI.create(base(1)+"/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username","hauser","password","synthetic-password","device","WEB")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(0,JSON.readTree(loginReply.body()).path("code").asInt(-1),loginReply.body());String token=JSON.readTree(loginReply.body()).path("data").path("tokenValue").asText();
        long account=databases.getFirst().admin().queryForObject("SELECT id FROM users WHERE nick_name='hauser'",Long.class);
        var settled=new java.util.concurrent.atomic.AtomicReference<String>();var settledAt=new java.util.concurrent.atomic.AtomicLong();
        try{await(90,()->{
            var authority=databases.getFirst().admin().queryForMap("SELECT epoch,revision FROM eatwhat_consent.subject WHERE user_id=?",account);
            // A current UPSERT is intentionally requeued for TTL refresh; READY does not mean unsynchronized.
            for(int n:List.of(1,2)){var rows=databases.get(n).admin().queryForList("SELECT epoch,revision,identity_ready FROM eatwhat_consent.subject WHERE user_id=?",account);if(rows.size()!=1||!rows.getFirst().get("epoch").equals(authority.get("epoch"))||!rows.getFirst().get("revision").equals(authority.get("revision"))||!Boolean.TRUE.equals(rows.getFirst().get("identity_ready"))){settled.set(null);return false;}}
            String version=authority.get("epoch")+":"+authority.get("revision");if(!version.equals(settled.get())){settled.set(version);settledAt.set(System.nanoTime());return false;}
            return System.nanoTime()-settledAt.get()>=Duration.ofSeconds(12).toNanos();
        });}catch(AssertionError failure){throw new AssertionError("authority="+databases.getFirst().admin().queryForList("SELECT status,epoch,revision,backfill_status,effects_pending FROM eatwhat_consent.subject WHERE user_id=?",account)+" replicas="+List.of(1,2).stream().map(n->databases.get(n).admin().queryForList("SELECT status,epoch,revision,identity_ready,backfill_status FROM eatwhat_consent.subject WHERE user_id=?",account)).toList()+" jobs="+databases.getFirst().admin().queryForList("SELECT kind,status,attempts FROM eatwhat_consent.job WHERE user_id=?",account)+" worker="+request(0,"GET","/admin/control/v1/cluster",null,adminToken).body(),failure);}
        processes.getFirst().destroy();assertTrue(processes.getFirst().waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        for(int n:List.of(1,2)){
            var cookie=browser.send(HttpRequest.newBuilder(URI.create(base(n)+"/user/info")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,cookie.statusCode(),cookie.body());assertEquals(0,JSON.readTree(cookie.body()).path("code").asInt(-1),cookie.body());
            var bearer=request(n,"GET","/auth/consent",null,token);assertEquals(200,bearer.statusCode(),bearer.body());
        }
        assertEquals(200,request(2,"POST","/auth/logout",Map.of(),token).statusCode());
        for(int n:List.of(1,2))assertEquals(401,request(n,"GET","/user/info",null,token).statusCode());
        String newToken=login(2,"hauser");
        assertEquals(200,request(1,"GET","/user/info",null,newToken).statusCode());
        long user=databases.getFirst().admin().queryForObject("SELECT id FROM users WHERE nick_name='hauser'",Long.class);
        assertEquals(503,request(2,"POST","/auth/changePassword",Map.of("oldPassword","synthetic-password","newPassword","uncommitted-password"),newToken).statusCode(),"A secondary cannot commit a local-only security change");
        var withdrawn=request(2,"POST","/auth/consent/withdraw",Map.of(),newToken);
        assertEquals(200,withdrawn.statusCode(),withdrawn.body());assertEquals(0,JSON.readTree(withdrawn.body()).path("code").asInt(-1),withdrawn.body());
        assertEquals(1,databases.getFirst().admin().queryForObject("SELECT count(*) FROM eatwhat_cluster.session_withdraw_fence WHERE user_id=?",Integer.class,user));
        for(int n:List.of(1,2))assertEquals(401,request(n,"GET","/user/info",null,newToken).statusCode(),"A shared withdrawal fence must deny both survivors immediately");
        assertEquals(200,request(1,"GET","/actuator/health",null,null).statusCode());
        // The independent auth database loses its writer endpoint: no stale local token may be accepted.
        var authAdmin=databases.getFirst().admin();String database=databases.getFirst().name();
        authAdmin.execute("REVOKE CONNECT ON DATABASE "+database+" FROM PUBLIC");
        authAdmin.queryForList("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=? AND usename=? AND pid<>pg_backend_pid()",database,authClientRole);
        assertEquals(503,request(1,"GET","/user/info",null,newToken).statusCode());
        assertEquals(503,request(1,"GET","/actuator/health",null,null).statusCode());
        authAdmin.execute("GRANT CONNECT ON DATABASE "+database+" TO "+authClientRole);
        await(30,()->request(1,"GET","/actuator/health",null,null).statusCode()==200);
        assertEquals(401,request(1,"GET","/user/info",null,newToken).statusCode(),"Restored access must still honor the committed withdrawal");
    }
    void realFileAndThreePartyDeletion(String token)throws Exception{
        var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",png);byte[] bytes=png.toByteArray();
        var signed=request(0,"POST","/upload/presign",Map.of("biz","avatar","fileName","mesh.png","contentType","image/png","fileSize",bytes.length),token);assertEquals(200,signed.statusCode(),signed.body());var data=JSON.readTree(signed.body()).path("data");String key=data.path("key").asText();
        assertEquals(200,http.send(HttpRequest.newBuilder(URI.create(data.path("putUrl").asText())).header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        var completed=request(0,"POST","/upload/complete",Map.of("key",key),token);assertEquals(0,JSON.readTree(completed.body()).path("code").asInt(-1),completed.body());
        UUID file=databases.getFirst().admin().queryForObject("SELECT file_id FROM eatwhat_file.file_object WHERE logical_key=?",UUID.class,key);
        await(90,()->databases.stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL' AND state='AVAILABLE_LOCAL'",Long.class,file)==1));
        for(var db:databases)db.runtime().update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,'mesh-drill','held','PRIVATE')",key);
        for(var db:databases){db.runtime().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'mesh-drill','held','RELEASE','ENTITY_DELETED')",key);db.admin().update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp()-interval '31 days' WHERE file_id=?",file);}
        // A live reference at the last participant prevents a global commit after earlier nodes prepare.
        databases.get(2).runtime().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'mesh-drill','held','RESTORE','ADMIN')",key);
        UUID operation=UUID.randomUUID();var begun=request(0,"POST","/admin/control/v1/cluster/gc",Map.of("operationId",operation,"fileId",file,"revision",1,"expectedEpoch",3),adminToken);assertEquals(202,begun.statusCode(),begun.body());
        request(0,"POST","/admin/control/v1/cluster/gc/"+operation+"/resume",Map.of(),adminToken);assertEquals("PREPARING",databases.getFirst().admin().queryForObject("SELECT state FROM eatwhat_cluster.gc_operation WHERE operation_id=?",String.class,operation));
        assertEquals(0,databases.stream().mapToLong(db->db.admin().queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND state='DELETED'",Long.class,file)).sum());
        assertThrows(org.springframework.dao.DataAccessException.class,()->databases.getFirst().runtime().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'mesh-drill','held','RESTORE','ADMIN')",key));
        databases.get(2).runtime().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'mesh-drill','held','RELEASE','ENTITY_DELETED')",key);
        for(var db:databases)db.admin().update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp()-interval '31 days' WHERE file_id=?",file);
        await(90,()->{request(0,"POST","/admin/control/v1/cluster/gc/"+operation+"/resume",Map.of(),adminToken);return "DELETED".equals(databases.getFirst().admin().queryForObject("SELECT state FROM eatwhat_cluster.gc_operation WHERE operation_id=?",String.class,operation));});
        var snapshot=JSON.readTree(request(0,"GET","/admin/control/v1/cluster/gc/"+operation,null,adminToken).body());assertEquals(6,snapshot.path("votes").size());
        String physical=databases.getFirst().admin().queryForObject("SELECT final_key FROM eatwhat_file.file_revision WHERE file_id=?",String.class,file);
        for(String bucket:buckets)try(var store=new you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore("http://"+S3.getHost()+":"+S3.getMappedPort(9000),"us-east-1",bucket,IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY,true)){assertTrue(store.head(physical).isEmpty());}
    }
    @Test void emptySiteRetiresWithAllParticipantProofsAndKeepsItsIdentity()throws Exception{
        prepare();adminToken=login(0,"meshadmin");join(0,0);adminToken=login(0,"meshadmin");join(1,1);join(2,2);
        var response=request(0,"POST","/admin/control/v1/cluster/sites/third/retire",Map.of("requestId",UUID.randomUUID(),"expectedEpoch",3),adminToken);assertEquals(202,response.statusCode(),response.body());UUID change=UUID.fromString(JSON.readTree(response.body()).path("change_id").asText());
        await(90,()->JSON.readTree(request(0,"GET","/admin/control/v1/cluster/changes/"+change,null,adminToken).body()).path("state").asText().equals("APPLIED"));
        assertEquals("RETIRED",databases.get(2).admin().queryForObject("SELECT state FROM eatwhat_cluster.local_state WHERE singleton",String.class));
        assertEquals(423,request(2,"GET","/user/info",null,null).statusCode());
        var duplicate=request(0,"POST","/admin/control/v1/cluster/sites",Map.of("requestId",UUID.randomUUID(),"expectedEpoch",4,"profileId","third"),adminToken);assertEquals(409,duplicate.statusCode());assertEquals("SITE_ID_RETAINED",JSON.readTree(duplicate.body()).path("code").asText());
    }
}
