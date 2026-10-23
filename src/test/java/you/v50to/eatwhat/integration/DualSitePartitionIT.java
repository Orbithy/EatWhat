package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Spring JVMs and local databases/Redis. Internal RPC and authority DB credentials are cut together. */
class DualSitePartitionIT {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
    private static final List<GenericContainer<?>> REDIS_NODES=List.of(
            IsolatedContainers.redis("partition-a"),IsolatedContainers.redis("partition-b"),IsolatedContainers.redis("partition-unused"));
    private final AtomicBoolean isolated=new AtomicBoolean();
    private final AtomicInteger rejectedInternal=new AtomicInteger();
    private ClusterSeamlessIT fixture;

    @BeforeAll static void startServices(){ClusterSeamlessIT.services();REDIS_NODES.forEach(GenericContainer::start);}
    @AfterAll static void stopServices(){REDIS_NODES.forEach(GenericContainer::stop);ClusterSeamlessIT.cleanup();}
    @AfterEach void stop() throws Exception {
        if(fixture!=null) {
            System.out.println("Dual-site fixture diagnostics: " + directory);
            for(int n=0;n<Math.min(2,fixture.databases.size());n++)try {
                var db=fixture.databases.get(n).admin();var diagnostics=new LinkedHashMap<String,Object>();diagnostics.put("node",n);
                diagnostics.put("inbox",db.queryForList("SELECT aggregate_type,status,last_error,count(*) FROM eatwhat_sync.inbox GROUP BY 1,2,3"));
                diagnostics.put("delivery",db.queryForList("SELECT o.aggregate_type,d.target_site,d.status,d.last_error,count(*),max(d.attempts) AS attempts FROM eatwhat_sync.delivery d JOIN eatwhat_sync.outbox o USING(event_id) GROUP BY 1,2,3,4"));
                diagnostics.put("replicas",db.queryForList("SELECT site,state,count(*) FROM eatwhat_file.file_replica GROUP BY 1,2"));
                diagnostics.put("activityIntents",db.queryForList("SELECT table_name,numeric_id,revision,published_revision,published_epoch,deleted_at FROM eatwhat_sync.activity_intent"));
                diagnostics.put("activityEvents",db.queryForList("SELECT o.event_id,o.aggregate_type,o.aggregate_id,d.target_site,d.status,d.last_error FROM eatwhat_sync.outbox o LEFT JOIN eatwhat_sync.delivery d USING(event_id) WHERE o.aggregate_type LIKE 'activity%'") );
                diagnostics.put("fileMetadata",db.queryForList("SELECT file_id,revision,state FROM eatwhat_file.file_revision"));
                diagnostics.put("foods",db.queryForList("SELECT id,catalog_visible FROM foods"));
                diagnostics.put("activity",db.queryForList("SELECT id,deleted_at IS NOT NULL AS deleted FROM activity_posts"));
                System.out.println("Dual-site state: "+ClusterSeamlessIT.JSON.writeValueAsString(diagnostics));
            }catch(Exception unavailable){System.out.println("Fixture diagnostic unavailable: "+unavailable.getClass().getSimpleName());}
            if(fixture.authClientRole!=null&&!fixture.databases.isEmpty())
                fixture.databases.getFirst().admin().execute("ALTER ROLE "+fixture.authClientRole+" LOGIN");
            fixture.stop();
        }
    }

    private void prepare() throws Exception {
        fixture=new ClusterSeamlessIT(){
            @Override String origin(int node){
                var gateway=gateways.computeIfAbsent(node,index->{
                    try {
                        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                        server.setExecutor(gatewayThreads);
                        server.createContext("/",exchange->{
                            try {
                                String path=exchange.getRequestURI().getRawPath();
                                if(isolated.get()&&path.startsWith("/internal/")) {
                                    rejectedInternal.incrementAndGet();exchange.sendResponseHeaders(503,-1);return;
                                }
                                var outbound=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ports.get(index)+exchange.getRequestURI()))
                                        .timeout(Duration.ofSeconds(30));
                                exchange.getRequestHeaders().forEach((name,values)->{
                                    if(!Set.of("host","connection","content-length","upgrade","http2-settings","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))
                                        values.forEach(value->outbound.header(name,value));
                                });
                                byte[] bytes=exchange.getRequestBody().readAllBytes();
                                var response=http.send(outbound.method(exchange.getRequestMethod(),bytes.length==0
                                        ?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofByteArray());
                                if(path.startsWith("/internal/")&&response.statusCode()!=200&&!isolated.get()) {
                                    String code="";try{code=ClusterSeamlessIT.JSON.readTree(response.body()).path("code").asText();}catch(Exception ignored){}
                                    System.out.println("Fixture internal failure node="+index+" path="+path+" status="+response.statusCode()+" code="+code);
                                }
                                response.headers().map().forEach((name,values)->{
                                    if(!Set.of("connection","content-length","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))exchange.getResponseHeaders().put(name,values);
                                });
                                exchange.sendResponseHeaders(response.statusCode(),response.body().length==0?-1:response.body().length);
                                if(response.body().length>0)exchange.getResponseBody().write(response.body());
                            } catch(Exception failure){System.out.println("Fixture proxy failed: "+failure.getClass().getSimpleName());try{exchange.sendResponseHeaders(502,-1);}catch(Exception ignored){}}
                            finally{exchange.close();}
                        });server.start();return server;
                    }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
                });
                return "http://127.0.0.1:"+gateway.getAddress().getPort();
            }

            @Override void configureNode(Properties p,int node){
                p.setProperty("app.account.multiwriter.enabled","true");
                p.setProperty("app.account.policy-version","cross-border-preferences-v3");
                for(String domain:List.of("browse","preferences")) {
                    p.setProperty("app.sync."+domain+".enabled","true");
                    p.setProperty("app.sync."+domain+".consent-policy-version","cross-border-preferences-v3");
                }
                p.setProperty("app.sync.catalog.multi-writer-enabled","true");
                p.setProperty("app.sync.profile.multiwriter-enabled","true");
                p.setProperty("app.sync.activity.durable-intents-enabled","true");
                p.setProperty("app.sync.notification.historical-backfill-enabled","true");
                var redis=REDIS_NODES.get(node);
                p.setProperty("spring.data.redis.host",redis.getHost());
                p.setProperty("spring.data.redis.port",redis.getMappedPort(6379).toString());
                p.setProperty("spring.data.redis.database","0");
                p.setProperty("spring.data.redis.url","redis://"+redis.getHost()+":"+redis.getMappedPort(6379)+"/0");
                databases.get(node).admin().execute("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
                databases.get(node).admin().execute("UPDATE eatwhat_sync.catalog_multiwriter_config SET enabled=true");
            }
        };
        fixture.directory=directory;fixture.prepare();
        fixture.adminToken=fixture.login(0,"meshadmin");fixture.join(0,0);
        fixture.adminToken=fixture.login(0,"meshadmin");fixture.join(1,1);
        System.out.println("Dual-site checkpoint: both members joined");
        ok(0,"POST","/auth/consent/grant",Map.of("policyVersion","cross-border-preferences-v3"),fixture.adminToken);
        assertEquals(1,fixture.databases.getFirst().admin().queryForObject(
                "SELECT count(*) FROM eatwhat_consent.subject s JOIN users u ON u.id=s.user_id WHERE u.nick_name='meshadmin' AND s.status='GRANTED'",Integer.class),
                "Granting a migrated account must create durable consent, not return success after updating zero rows");
        ClusterSeamlessIT.await(60,()->fixture.databases.get(1).admin().queryForObject(
                "SELECT count(*) FROM users WHERE nick_name='meshadmin' AND role='admin'",Integer.class)==1);
        fixture.adminToken=fixture.login(0,"meshadmin");
        System.out.println("Dual-site checkpoint: administrator synchronized");
    }

    @Test void bothSitesWriteWithoutAuthorityThenRecoverDataAndRevocations() throws Exception {
        prepare();
        String externalAdmin=fixture.login(1,"meshadmin");
        String author=register(0,"mwauthor");
        var campus=fixture.databases.getFirst();
        long authorId=campus.admin().queryForObject("SELECT id FROM users WHERE nick_name='mwauthor'",Long.class);
        // Provider delivery is mocked in this fixture; seed its successful verification through the actual command boundary.
        var accounts=new you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts(campus.runtime(),campus.transactions(),campus.site(),true);
        accounts.command(authorId,authorId,()->{campus.runtime().update("INSERT INTO verifications(account_id,method,verified,verified_email) VALUES(?,'school_email',true,'fixture@mail.sdu.edu.cn')",authorId);return null;});
        ClusterSeamlessIT.await(60,()->fixture.databases.get(1).admin().queryForObject(
                "SELECT count(*) FROM verifications WHERE account_id=?",Integer.class,authorId)==1);
        String remoteAuthor=fixture.login(1,"mwauthor");
        System.out.println("Dual-site checkpoint: author synchronized; disconnecting RPC and shared authentication database");
        disconnect();
        assertEquals(503,fixture.machine(1,"GET","probe",null).statusCode(),"The RPC fault must actually be active");
        fixture.databases.get(1).admin().execute("UPDATE eatwhat_consent.subject SET valid_until=clock_timestamp()-interval '1 hour' WHERE status='GRANTED'");
        author=fixture.login(0,"mwauthor");remoteAuthor=fixture.login(1,"mwauthor");
        ok(0,"PUT","/user/updateInfo",Map.of("signature","campus partition edit"),author);
        ok(1,"PUT","/user/updateInfo",Map.of("gender","other"),remoteAuthor);
        ok(1,"POST","/user/changePrivacy",Map.of("following",false,"follower",false),remoteAuthor);
        ok(1,"PUT","/user/recommendation-profile/preferences",Map.of("likedTags",List.of("offline"),"dislikedTags",List.of()),remoteAuthor);
        ok(0,"POST","/hub/add",Map.of("name","partition campus hub","gcjLng",117.1,"gcjLat",36.6),fixture.adminToken);
        ok(1,"POST","/hub/add",Map.of("name","partition external hub","gcjLng",117.2,"gcjLat",36.7),externalAdmin);
        String fresh=register(1,"mwnew");
        ok(1,"PUT","/user/updateInfo",Map.of("signature","registered while disconnected"),fresh);
        assertEquals(0,fixture.databases.getFirst().admin().queryForObject("SELECT count(*) FROM users WHERE nick_name='mwnew'",Integer.class));
        var extra=MultiwriterAdditionalHttpChecks.disconnected(fixture,List.of(fixture.adminToken,externalAdmin),List.of(author,remoteAuthor),authorId);
        var content=new ArrayList<Content>();
        content.add(createOfflineContent(0,author,fixture.adminToken));content.add(createOfflineContent(1,remoteAuthor,externalAdmin));
        restartExternalAndClearSessionCache();
        ok(1,"GET","/user/info",null,remoteAuthor);
        ok(1,"PUT","/user/updateInfo",Map.of("gender","other"),remoteAuthor);
        System.out.println("Dual-site checkpoint: disconnected writes and application/cache restart completed locally");
        reconnect();
        long recoveryStarted=System.nanoTime();
        ClusterSeamlessIT.await(300,()->fixture.databases.subList(0,2).stream().allMatch(db->
                db.admin().queryForObject("SELECT count(*) FROM hub WHERE name IN ('partition campus hub','partition external hub')",Integer.class)==2
                &&db.admin().queryForObject("SELECT count(*) FROM users WHERE nick_name='mwnew'",Integer.class)==1));
        for(var item:content) ClusterSeamlessIT.await(300,()->fixture.databases.subList(0,2).stream().allMatch(db->
                db.admin().queryForObject("SELECT count(*) FROM foods WHERE id=? AND name=?",Integer.class,item.food(),"edited offline "+item.node())==1
                &&db.admin().queryForObject("SELECT count(*) FROM activity_posts WHERE id=? AND deleted_at IS NULL",Integer.class,item.activity())==1
                &&db.admin().queryForObject("SELECT count(*) FROM eatwhat_file.file_replica r JOIN eatwhat_file.file_object o USING(file_id) WHERE o.logical_key IN (?,?) AND r.site='LOCAL' AND r.state='AVAILABLE_LOCAL'",Integer.class,item.foodKey(),item.activityKey())==2));
        for(int node:List.of(0,1)) for(var item:content) {
            String token=fixture.login(node,"mwauthor");
            ok(node,"GET","/foods/detail/"+item.food(),null,token);
        }
        // HTTP reads exercise authentication, the local projection and the service mapping after recovery.
        for(int node:List.of(0,1)) {
            String token=fixture.login(node,"mwauthor");
            var info=ok(node,"GET","/user/info",null,token).toString();
            assertTrue(info.contains("campus partition edit"),info);assertTrue(info.contains("other"),info);
            ClusterSeamlessIT.await(60,()->ok(node,"GET","/user/recommendation-profile/preferences",null,token).toString().contains("offline"));
        }
        long user=fixture.databases.getFirst().admin().queryForObject("SELECT id FROM users WHERE nick_name='mwauthor'",Long.class);
        ClusterSeamlessIT.await(60,()->fixture.databases.subList(0,2).stream().allMatch(db->
                db.admin().queryForObject("SELECT count(*) FROM privacy WHERE account_id=? AND NOT following AND NOT follower",Integer.class,user)==1));
        MultiwriterAdditionalHttpChecks.recovered(fixture,extra);
        System.out.println("Dual-site first recovery elapsedSeconds="+Duration.ofNanos(System.nanoTime()-recoveryStarted).toSeconds());
        author=fixture.login(0,"mwauthor");externalAdmin=fixture.login(1,"meshadmin");
        disconnect();
        for(var item:content) {
            int opposite=1-item.node();String token=fixture.login(opposite,"mwauthor");
            ok(opposite,"POST","/foods/"+item.food()+"/like",Map.of(),token);
            ok(opposite,"POST","/foods/"+item.food()+"/favorite",Map.of(),token);
            ok(opposite,"POST","/foods/"+item.food()+"/unlike",Map.of(),token);
            ok(opposite,"POST","/foods/"+item.food()+"/unfavorite",Map.of(),token);
            ok(opposite,"DELETE","/activity/posts/"+item.activity(),null,token);
            ok(opposite,"DELETE","/foods/my/"+item.food(),null,token);
        }
        ok(1,"POST","/user/ban/"+user,Map.of("reason","partition acceptance"),externalAdmin);
        var rejected=fixture.request(1,"POST","/activity/partition-n1/posts",Map.of("title","must not publish after ban","pictureUrl",List.of(content.get(1).activityKey())),remoteAuthor);
        assertEquals(401,rejected.statusCode(),"Banned actor must fail authentication before a valid content request: "+rejected.body());
        assertEquals(0,fixture.databases.get(1).admin().queryForObject("SELECT count(*) FROM activity_posts WHERE title='must not publish after ban'",Integer.class));
        ok(0,"GET","/user/info",null,author); // The accepted offline security semantics.
        reconnect();
        ClusterSeamlessIT.await(90,()->fixture.databases.subList(0,2).stream().allMatch(db->
                Boolean.TRUE.equals(db.admin().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,user))));
        for(var item:content) ClusterSeamlessIT.await(300,()->fixture.databases.subList(0,2).stream().allMatch(db->
                db.admin().queryForObject("SELECT count(*) FROM foods WHERE id=? AND catalog_visible",Integer.class,item.food())==0
                &&db.admin().queryForObject("SELECT count(*) FROM activity_posts WHERE id=? AND deleted_at IS NULL",Integer.class,item.activity())==0));
        assertNotEquals(0,ClusterSeamlessIT.JSON.readTree(fixture.request(0,"POST","/auth/login",
                Map.of("username","mwauthor","password","synthetic-password","device","WEB"),null).body()).path("code").asInt(-1));
        assertTrue(rejectedInternal.get()>0);
        System.out.println("Dual-site checkpoint: business data and revocation converged after reconnect");
    }

    private record Content(int node,long restaurant,long food,long activity,String foodKey,String activityKey) { }
    private Content createOfflineContent(int node,String token,String adminToken) throws Exception {
        long restaurant=ok(node,"POST","/restaurant/add",Map.of("name","offline restaurant "+node,"address","isolated fixture","gcjLng",117.1,"gcjLat",36.6),token).path("data").path("id").asLong();
        assertTrue(restaurant>0);
        String foodKey=upload(node,"foods",token),activityKey=upload(node,"activity",token);
        long food=ok(node,"POST","/foods/upload",Map.of("restaurantId",restaurant,"name","offline food "+node,"price",12.5,"pictureUrl",List.of(foodKey)),token).path("data").path("id").asLong();
        assertTrue(food>0);
        ok(node,"PUT","/foods/my/"+food,Map.of("name","edited offline "+node),token);
        // Each partitioned site's administrator opens a site-specific activity while disconnected.
        long definition=ok(node,"POST","/admin/activities",Map.of("code","partition-n"+node,"name","partition "+node,"fieldSchema",List.of()),adminToken).path("data").path("id").asLong();
        ok(node,"POST","/admin/activities/"+definition+"/status",Map.of("status","ONLINE"),adminToken);
        long activity=ok(node,"POST","/activity/partition-n"+node+"/posts",Map.of("title","offline activity "+node,"pictureUrl",List.of(activityKey)),token).path("data").path("id").asLong();
        return new Content(node,restaurant,food,activity,foodKey,activityKey);
    }
    private String upload(int node,String biz,String token) throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",bytes);
        var data=ok(node,"POST","/upload/presign",Map.of("biz",biz,"fileName","partition.png","contentType","image/png","fileSize",bytes.size()),token).path("data");
        var uploaded=fixture.http.send(HttpRequest.newBuilder(URI.create(data.path("putUrl").asText())).timeout(Duration.ofSeconds(5))
                .header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(200,uploaded.statusCode());String key=data.path("key").asText();
        ok(node,"POST","/upload/complete",Map.of("key",key),token);return key;
    }
    private void restartExternalAndClearSessionCache() throws Exception {
        var process=fixture.processes.get(1);process.destroyForcibly();assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        var redis=REDIS_NODES.get(1);var connection=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(redis.getHost(),redis.getMappedPort(6379));
        connection.afterPropertiesSet();connection.start();try(var c=connection.getConnection()){c.serverCommands().flushDb();}finally{connection.destroy();}
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx384m","-cp",System.getProperty("surefire.test.class.path"),ClusterNodeProcessMain.class.getName(),directory.resolve("node-1.properties").toString());
        command.redirectErrorStream(true);command.redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("node-1.log").toFile()));fixture.processes.set(1,command.start());
        ClusterSeamlessIT.await(90,()->{try{return fixture.request(1,"GET","/actuator/health/liveness",null,null).statusCode()==200;}catch(Exception unavailable){return false;}});
    }

    private String register(int node,String name) throws Exception {
        var reply=ok(node,"POST","/auth/register",Map.of("username",name,"password","synthetic-password",
                "device","WEB","crossBorderConsent",true,"consentPolicyVersion","cross-border-preferences-v3"),null);
        String token=reply.path("data").path("tokenValue").asText();assertFalse(token.isBlank(),"Local registration must issue a usable session");return token;
    }
    private JsonNode ok(int node,String method,String path,Object body,String token) throws Exception {
        long started=System.nanoTime();var response=fixture.request(node,method,path,body,token);
        assertEquals(200,response.statusCode(),path+": "+response.body());
        var value=ClusterSeamlessIT.JSON.readTree(response.body());assertEquals(0,value.path("code").asInt(-1),path+": "+response.body());
        if(isolated.get())assertTrue(System.nanoTime()-started<Duration.ofSeconds(5).toNanos(),"Offline operation waited for a remote authority: "+path);
        return value;
    }
    private void disconnect(){
        isolated.set(true);
        var db=fixture.databases.getFirst().admin();
        db.execute("ALTER ROLE "+fixture.authClientRole+" NOLOGIN");
        db.query("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename=?",row->{},fixture.authClientRole);
    }
    private void reconnect(){
        fixture.databases.getFirst().admin().execute("ALTER ROLE "+fixture.authClientRole+" LOGIN");
        isolated.set(false);
    }
}
