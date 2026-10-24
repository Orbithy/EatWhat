package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Launch the exact scoped JAR, including its real Spring browse configuration and old consent API. */
@EnabledIfSystemProperty(named="eatwhat.p1.artifact",matches=".+")
class P1PackagedStartupIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("p1-packaged"),REDIS=ClusterReleaseMixedVersionIT.REDIS;
    @BeforeAll static void start(){PG.start();REDIS.start();}
    @AfterAll static void stop(){PG.stop();REDIS.stop();}
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path root;
    @Test void exactJarUpgradesStartsWithBrowsingEnabledAndRestartsAtV78()throws Exception{
        Path jar=Path.of(System.getProperty("eatwhat.p1.artifact")).toAbsolutePath();assertTrue(Files.isRegularFile(jar));
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("p1-packaged-campus");site.setInstanceId(UUID.randomUUID());site.setClusterId(UUID.randomUUID());site.setAllocationSlot(0);
        var db=EventFoundationTestSupport.createDatabaseAtVersion(PG,site,"74.2");
        Path token=root.resolve("node.token");Files.writeString(token,"synthetic_"+"p".repeat(48));Files.setPosixFilePermissions(token,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        int port=ClusterReleaseMixedVersionIT.port();var fixture=new ClusterReleaseMixedVersionIT();fixture.root=root;
        Path config=fixture.config("p1",port,site,db,token);var properties=new Properties();try(var in=Files.newInputStream(config)){properties.load(in);}
        properties.setProperty("spring.data.redis.host",REDIS.getHost());properties.setProperty("spring.data.redis.port",REDIS.getMappedPort(6379).toString());
        properties.setProperty("app.account.enabled","true");properties.setProperty("app.account.policy-version","cross-border-browse-v2");
        properties.setProperty("app.cluster.single-site-only","false");
        properties.setProperty("app.sync.browse.enabled","true");properties.setProperty("app.sync.browse.consent-policy-version","cross-border-browse-v2");
        try(var out=Files.newOutputStream(config)){properties.store(out,"Synthetic packaged P1 startup only");}
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();var json=JsonMapper.builder().build();
        for(int attempt=0;attempt<2;attempt++){
            Path log=root.resolve("startup-"+attempt+".log");var process=fixture.launch(jar,config,log);
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);boolean ready=false;
                while(System.nanoTime()<deadline){
                    if(!process.isAlive())fail("Exact P1 JAR exited; inspect "+log);
                    try{var r=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()==200&&json.readTree(r.body()).path("status").asString().equals("UP")){ready=true;break;}}catch(java.io.IOException ignored){}
                    Thread.sleep(200);
                }
                assertTrue(ready,"Exact P1 JAR readiness timed out; inspect "+log);
                assertEquals("78",db.admin().queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",String.class));
                // This startup fixture has not joined a real cluster; its enrollment barrier remains closed.
                // Permission and route behavior are verified separately against joined fixtures and production.
                for(String path:List.of("/foods/list?uploaderId=1","/foods/by-user/1")){
                    var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(423,response.statusCode());assertTrue(json.readTree(response.body()).path("code").isString());
                }
            }finally{ClusterReleaseMixedVersionIT.stop(process);}
        }
    }
}
