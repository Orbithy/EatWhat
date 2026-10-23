package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.control.DeploymentExecutor;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine old/new Boot processes and PG/Redis. One logical site; no SSH/Compose release acceptance. */
@EnabledIfSystemProperty(named="eatwhat.test.previous-artifact",matches=".+")
class ClusterReleaseMixedVersionIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("mixed-release"),REDIS=IsolatedContainers.redis("mixed-release");
    @BeforeAll static void start(){PG.start();REDIS.start();}@AfterAll static void stop(){PG.stop();REDIS.stop();}
    @TempDir Path root;final JsonMapper json=JsonMapper.builder().build();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    @Test void schema54CanBeAppliedWhileTheReviewed53ArtifactRunsAndThatArtifactCanRestartWithoutDatabaseRestore()throws Exception{
        Path previous=Path.of(System.getProperty("eatwhat.test.previous-artifact")).toAbsolutePath();String expected=System.getProperty("eatwhat.test.previous-artifact-sha256");assertNotNull(expected,"A reviewed old artifact SHA is required");assertEquals(expected,DeploymentExecutor.artifactSha(previous));
        Path candidate=Path.of("target/eatwhat-0.0.1-SNAPSHOT.jar").toAbsolutePath();assertTrue(Files.isRegularFile(candidate));assertNotEquals(expected,DeploymentExecutor.artifactSha(candidate));
        UUID cluster=UUID.randomUUID(),instance=UUID.randomUUID();var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("mixed-campus");site.setInstanceId(instance);site.setClusterId(cluster);site.setAllocationSlot(0);var db=EventFoundationTestSupport.createDatabaseAtVersion(PG,site,"53");
        String identity=db.admin().queryForObject("SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n",String.class);long admin=db.admin().queryForObject("INSERT INTO users(nick_name,password_hash,role) VALUES('mixed-fixture-admin','synthetic-preserved-hash','admin') RETURNING id",Long.class);
        assertEquals("53",db.admin().queryForObject("SELECT max(version::integer)::text FROM public.flyway_schema_history WHERE success",String.class));
        Path token=root.resolve("node.token");Files.writeString(token,"synthetic_"+"a".repeat(48));Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
        int oldPort=port(),newPort=port();Path oldConfig=config("old",oldPort,site,db,token),newConfig=config("new",newPort,site,db,token);Process old=null,newer=null;
        try{
            old=launch(previous,oldConfig,root.resolve("old.log"));awaitReady(old,oldPort);assertObserved(oldPort,token,instance);
            newer=launch(candidate,newConfig,root.resolve("new.log"));awaitReady(newer,newPort);assertObserved(newPort,token,instance);assertEquals("54",db.admin().queryForObject("SELECT max(version::integer)::text FROM public.flyway_schema_history WHERE success",String.class));
            assertObserved(oldPort,token,instance);assertEquals(identity,db.admin().queryForObject("SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n",String.class));
            stop(newer);newer=null;stop(old);old=null;
            old=launch(previous,oldConfig,root.resolve("restarted-old.log"));awaitReady(old,oldPort);assertObserved(oldPort,token,instance);
            assertEquals(identity,db.admin().queryForObject("SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n",String.class));assertEquals("synthetic-preserved-hash",db.admin().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,admin));
            assertEquals(1L,db.admin().queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='54' AND success",Long.class));
        }finally{stop(newer);stop(old);}
    }
    Path config(String name,int port,SiteProperties site,EventFoundationTestSupport.Database db,Path token)throws Exception{
        var props=new Properties();try(var input=getClass().getResourceAsStream("/application-integration.properties")){assertNotNull(input);props.load(input);}props.remove("spring.autoconfigure.exclude");props.remove("logging.config");
        var values=new LinkedHashMap<String,Object>();values.put("server.address","127.0.0.1");values.put("server.port",port);values.put("spring.datasource.url",db.url());values.put("spring.datasource.username",db.runtimeUsername());values.put("spring.datasource.password",IsolatedContainers.PASSWORD);values.put("spring.flyway.url",db.url());values.put("spring.flyway.user",IsolatedContainers.USER);values.put("spring.flyway.password",IsolatedContainers.PASSWORD);values.put("spring.data.redis.host",REDIS.getHost());values.put("spring.data.redis.port",REDIS.getMappedPort(6379));
        values.put("app.site.mode","CAMPUS");values.put("app.site.id",site.getId());values.put("app.site.instance-id",site.getInstanceId());values.put("app.site.cluster-id",site.getClusterId());values.put("app.site.allocation-slot",0);values.put("app.control.enabled",true);values.put("app.control.node-enabled",true);values.put("app.control.environment-kind","managed-deployment");values.put("app.control.state-directory",root.resolve(name+"-state"));values.put("app.control.node-token-file",token);values.put("app.cluster.enabled",true);values.put("app.cluster.coordinator",true);values.put("app.cluster.single-site-only",true);values.put("app.cluster.control-lease.enabled",true);values.put("app.cluster.control-lease.candidate",false);values.put("app.cluster.automatic-gc-enabled",false);values.put("app.account.enabled",false);values.put("app.sync.capture-enabled",false);values.put("app.sync.transport-enabled",false);values.put("app.storage.s3.endpoint","http://127.0.0.1:1");values.put("app.storage.s3.cdn-endpoint","");values.put("app.storage.s3.provider","RUSTFS");values.put("sms.active","aliyun");values.put("sms.aliyun.access-key-id","synthetic");values.put("sms.aliyun.access-key-secret","synthetic");values.put("sms.aliyun.sign-name","synthetic");values.put("sms.aliyun.interval",60);values.put("management.endpoint.health.probes.enabled",true);values.put("management.endpoint.health.group.readiness.include","readinessState,db,redis");values.put("logging.level.root","ERROR");
        values.forEach((key,value)->props.setProperty(key,value.toString()));Path path=root.resolve(name+".properties");Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try(var out=Files.newOutputStream(path)){props.store(out,"Synthetic isolated mixed-version fixture only");}return path;
    }
    Process launch(Path jar,Path config,Path log)throws Exception{return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xms64m","-Xmx384m","-jar",jar.toString(),"--spring.config.location=file:"+config,"--spring.config.additional-location=","--spring.config.import=").redirectErrorStream(true).redirectOutput(log.toFile()).start();}
    void awaitReady(Process process,int port)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(System.nanoTime()<deadline){assertTrue(process.isAlive(),"Mixed-version fixture process exited; inspect private fixture logs");try{var reply=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());if(reply.statusCode()==200&&"UP".equals(json.readTree(reply.body()).path("status").asText()))return;}catch(java.io.IOException ignored){}Thread.sleep(100);}fail("Mixed-version fixture readiness timed out");}
    void assertObserved(int port,Path token,UUID instance)throws Exception{var reply=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/control/v1/snapshot")).timeout(Duration.ofSeconds(5)).header("X-Control-Node-Token",Files.readString(token)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,reply.statusCode());assertEquals("OBSERVED",json.readTree(reply.body()).path("observationStatus").asText());assertEquals(instance.toString(),json.readTree(reply.body()).path("instanceId").asText());}
    static int port()throws Exception{try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    static void stop(Process process)throws Exception{if(process==null)return;process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
}
