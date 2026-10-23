package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.*;
import work.foofish.smsverify.SmsTemplate;
import you.v50to.eatwhat.EatwhatApplication;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.service.storage.replication.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real business HTTP and object bytes. Only mail/SMS and the CDN vendor boundary are test-owned fixtures. */
@SpringBootTest(classes = {EatwhatApplication.class, LocalBusinessFileFixture.Boundaries.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=classpath:/application-integration.properties", "spring.config.additional-location=",
        "spring.config.import=", "spring.autoconfigure.exclude=work.foofish.smsverify.SmsAutoConfiguration",
        "logging.config=classpath:logback-test.xml"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class LocalBusinessFileFixture {
    static final String ORIGIN = "http://localhost:5173";
    static final String PASSWORD = "synthetic-business-password";
    static final String WEBHOOK_SECRET = "synthetic-local-cdn-webhook-only";
    static final ObjectMapper JSON = new ObjectMapper();
    static final GenericContainer<?> REDIS = IsolatedContainers.redis("business-files");
    static final GenericContainer<?> RUSTFS = IsolatedContainers.rustfs("business-files");
    static FileEventChannelIT.Pair pair;
    static S3Client s3;
    static CdnFixture cdn;
    static String endpoint, localBucket, peerBucket;
    static Path certificates;
    static SSLContext previousDefaultTls;
    static ObservedStore peer;
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired FileTransferJobStore jobs;
    @Autowired ReplicationConsentGate consent;
    @Autowired FileReplicationProperties replication;
    @Autowired S3ReplicaObjectStore localReplicaStore;
    @Autowired FileCdnInvalidationRunner cdnRunner;
    @Autowired FileCdnInvalidationWorker cdnWorker;
    @MockitoBean JavaMailSender mail;
    @MockitoBean SmsTemplate sms;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        try {
            certificates=Files.createTempDirectory("eatwhat-business-file-tls-");
            FileEventChannelIT.certs=certificates; FileEventChannelIT.start();
            pair=new FileEventChannelIT.Pair();
            previousDefaultTls=SSLContext.getDefault();
            SSLContext.setDefault(FileEventChannelIT.tls.context(FileEventChannelIT.tls.campus(),FileEventChannelIT.tls.external()));
            // Pair's account fixture imported an explicit id; keep subsequent real HTTP registrations collision-free.
            pair.campus.db.admin().execute("SELECT setval(pg_get_serial_sequence('public.users','id'),1000000001,true)");
            REDIS.start(); RUSTFS.start();
            endpoint="http://"+RUSTFS.getHost()+":"+RUSTFS.getMappedPort(9000);
            s3=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY)))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
            localBucket="business-local-"+UUID.randomUUID();peerBucket="business-peer-"+UUID.randomUUID();
            for(String bucket:List.of(localBucket,peerBucket))s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            s3.putBucketCors(PutBucketCorsRequest.builder().bucket(localBucket).corsConfiguration(CORSConfiguration.builder().corsRules(
                    CORSRule.builder().allowedOrigins(ORIGIN).allowedMethods("GET","PUT","HEAD").allowedHeaders("content-type")
                            .exposeHeaders("ETag").maxAgeSeconds(60).build()).build()).build());
            cdn=new CdnFixture();
            peer=new ObservedStore(endpoint,peerBucket);
        } catch(Exception failure) { stopServices();throw failure; }
        var db=pair.campus.db;
        registry.add("spring.datasource.url",db::url);registry.add("spring.datasource.username",db::runtimeUsername);
        registry.add("spring.datasource.password",()->IsolatedContainers.PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size",()->8);
        registry.add("spring.flyway.url",db::url);registry.add("spring.flyway.user",()->IsolatedContainers.USER);
        registry.add("spring.flyway.password",()->IsolatedContainers.PASSWORD);
        registry.add("spring.data.redis.host",REDIS::getHost);registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.url",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379)+"/0");
        registry.add("spring.data.redis.username",()->"");registry.add("spring.data.redis.password",()->"");
        // Test fixtures create accounts through the closed-by-default username registration.
        registry.add("app.auth.username-registration-enabled",()->"true");
        registry.add("app.site.mode",()->"CAMPUS");registry.add("app.site.id",()->db.site().getId());
        registry.add("app.site.instance-id",()->db.site().getInstanceId().toString());
        registry.add("app.storage.s3.provider",()->"RUSTFS");registry.add("app.storage.s3.endpoint",()->endpoint);
        registry.add("app.storage.s3.region",()->"us-east-1");registry.add("app.storage.s3.bucket",()->localBucket);
        registry.add("app.storage.s3.access-key",()->IsolatedContainers.RUSTFS_ACCESS_KEY);
        registry.add("app.storage.s3.secret-key",()->IsolatedContainers.RUSTFS_SECRET_KEY);
        registry.add("app.storage.s3.path-style",()->true);registry.add("app.storage.s3.cdn-endpoint",()->"");
        registry.add("app.storage.s3.public-base-url",()->cdn.base()+"/pub");
        registry.add("app.storage.s3.allowed-content-types",()->"image/png,image/jpeg");
        registry.add("app.storage.s3.allowed-read-prefixes",()->"avatar/,foods/,restaurants/,activity/");
        registry.add("app.storage.s3.public-read-prefixes",()->"avatar/");
        registry.add("app.storage.s3.max-file-size-bytes",()->32*1024*1024);
        registry.add("app.storage.files.lifecycle-enabled",()->true);registry.add("app.storage.files.legacy-read-enabled",()->false);
        registry.add("app.storage.files.replication.enabled",()->true);registry.add("app.storage.files.replication.worker-enabled",()->false);
        registry.add("app.storage.files.replication.cdn-output-enabled",()->true);
        registry.add("app.storage.files.replication.multipart-threshold",()->5*1024*1024);
        registry.add("app.storage.files.replication.part-size",()->5*1024*1024);
        registry.add("app.storage.files.replication.peer.endpoint",()->endpoint);registry.add("app.storage.files.replication.peer.region",()->"us-east-1");
        registry.add("app.storage.files.replication.peer.bucket",()->peerBucket);
        registry.add("app.storage.files.replication.peer.access-key",()->IsolatedContainers.RUSTFS_ACCESS_KEY);
        registry.add("app.storage.files.replication.peer.secret-key",()->IsolatedContainers.RUSTFS_SECRET_KEY);
        registry.add("app.storage.files.replication.peer.path-style",()->true);
        registry.add("app.storage.files.cdn-invalidation.enabled",()->true);
        registry.add("app.storage.files.cdn-invalidation.provider",()->"WEBHOOK");
        registry.add("app.storage.files.cdn-invalidation.webhook-url",()->cdn.webhookBase()+"/invalidate");
        registry.add("app.storage.files.cdn-invalidation.webhook-secret",()->WEBHOOK_SECRET);
        registry.add("app.storage.files.cdn-invalidation.poll-interval",()->"1h");
    }
    @BeforeEach void pauseBackgroundAndResetObserver() {
        cdnRunner.stop();peer.reset();cdn.failNext.set(false);
        assertFalse(cdnRunner.isRunning());
        assertEquals(pair.campus.db.runtimeUsername(),jdbc.queryForObject("SELECT current_user",String.class));
    }
    @AfterEach void boundariesRemainIsolated() { verifyNoInteractions(mail,sms); }
    @AfterAll static void closeContextAndServices(@Autowired RedisConnectionFactory connections) throws Exception {
        // Stop Redis reconnects before removing the owned service; Spring closes its context afterwards.
        if (connections instanceof LettuceConnectionFactory lettuce) lettuce.destroy();
        stopServices();
    }
    static void stopServices() throws Exception {
        if(cdn!=null)cdn.close();if(peer!=null)peer.close();if(s3!=null)s3.close();
        if(pair!=null)pair.close();FileEventChannelIT.stop();REDIS.stop();RUSTFS.stop();
        if(previousDefaultTls!=null)SSLContext.setDefault(previousDefaultTls);
        if(certificates!=null)try(var paths=Files.walk(certificates)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Boundaries {
        // Real consent rules with synthetic authorization fixtures; account synchronization is outside this drill.
        @Bean ConsentStore localBusinessConsentStore(){return pair.campus.consent;}
    }
    String base(){return "http://127.0.0.1:"+port;}
    HttpResponse<String> response(String method,String path,Object body,String token,String origin)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(30));
        if(token!=null)b.header("eatwhat-test-token","Bearer "+token);
        if(origin!=null)b.header("Origin",origin);
        b.header("Content-Type","application/json");
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode api(String method,String path,Object body,String token)throws Exception {
        var r=response(method,path,body,token,null);assertEquals(200,r.statusCode(),"Business HTTP status");return JSON.readTree(r.body());
    }
    JsonNode ok(String method,String path,Object body,String token)throws Exception {
        var r=api(method,path,body,token);assertEquals(0,r.path("code").asInt(),()->"Business code: "+r.path("code").asInt()+" on "+path);return r.path("data");
    }
    Account account(boolean verified)throws Exception {
        String username="f"+UUID.randomUUID().toString().replace("-","").substring(0,8);
        ok("POST","/auth/register",Map.of("username",username,"password",PASSWORD,"device","WEB"),null);
        var login=ok("POST","/auth/login",Map.of("username",username,"password",PASSWORD,"device","WEB"),null);
        long id=jdbc.queryForObject("SELECT id FROM users WHERE nick_name=?",Long.class,username);
        UUID owner=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,id);
        if(verified){jdbc.update("INSERT INTO verifications(account_id,method,verified,verified_email) VALUES (?,'school_email',true,?)",id,username+"@example.invalid");redis.delete(List.of("auth:"+id,"role:"+id));}
        for(var side:List.of(pair.campus,pair.external)){
            if(side==pair.external){
                side.db.admin().execute("ALTER TABLE public.users DISABLE TRIGGER USER");
                try{side.db.admin().update("INSERT INTO users(id,nick_name,password_hash) VALUES(?,?,?)",id,username,"synthetic-peer-hash");}
                finally{side.db.admin().execute("ALTER TABLE public.users ENABLE TRIGGER USER");}
                side.db.admin().update("INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site) VALUES('users',?,?,?)",id,owner,pair.campus.node.siteId());
            }
            side.db.admin().update("INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,status,policy_version,identity_ready,backfill_status,valid_until,source_archived) VALUES(?,?,?,1,'GRANTED','p1',true,'DONE',clock_timestamp()+interval '5 min',true)",owner,id,pair.campus.node.siteId());
        }
        return new Account(id,login.path("tokenValue").asText(),owner);
    }
    void makeAdmin(Account a){jdbc.update("UPDATE users SET role='admin' WHERE id=?",a.id);redis.delete("role:"+a.id);}
    long restaurant(Account a){return jdbc.queryForObject("INSERT INTO restaurant(account_id,name,address,location,gcj_lng,gcj_lat) VALUES (?,'Synthetic business restaurant','Local fixture',ST_SetSRID(ST_MakePoint(117,36),4326),117,36) RETURNING id",Long.class,a.id);}
    byte[] png(int size) {
        try {
            var out=new java.io.ByteArrayOutputStream();out.write(new byte[]{(byte)137,'P','N','G',13,10,26,10});
            var header=new java.io.ByteArrayOutputStream();var h=new java.io.DataOutputStream(header);
            h.writeInt(1);h.writeInt(1);h.write(new byte[]{8,6,0,0,0});pngChunk(out,"IHDR",header.toByteArray());
            var compressed=new java.io.ByteArrayOutputStream();
            try(var deflate=new java.util.zip.DeflaterOutputStream(compressed)){deflate.write(new byte[]{0,(byte)255,0,0,(byte)255});}
            byte[] pixels=compressed.toByteArray();int normal=out.size()+12+pixels.length+12;
            if(size>=normal+12){byte[] padding=new byte[size-normal-12];new Random(29).nextBytes(padding);pngChunk(out,"eaTW",padding);}
            pngChunk(out,"IDAT",pixels);pngChunk(out,"IEND",new byte[0]);
            byte[] png=out.toByteArray();return size<png.length?Arrays.copyOf(png,size):png;
        } catch(java.io.IOException impossible){throw new java.io.UncheckedIOException(impossible);}
    }
    static void pngChunk(java.io.OutputStream out,String name,byte[] data)throws java.io.IOException {
        var d=new java.io.DataOutputStream(out);byte[] type=name.getBytes(StandardCharsets.US_ASCII);
        d.writeInt(data.length);d.write(type);d.write(data);var crc=new java.util.zip.CRC32();crc.update(type);crc.update(data);d.writeInt((int)crc.getValue());
    }
    JsonNode presign(Account a,byte[] bytes)throws Exception{return ok("POST","/upload/presign",Map.of("biz","foods","fileName","local.png","contentType","image/png","fileSize",bytes.length),a.token);}
    void put(JsonNode signed,byte[] bytes)throws Exception {
        var r=http.send(HttpRequest.newBuilder(URI.create(signed.path("putUrl").asText())).timeout(Duration.ofSeconds(30))
                .header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(200,r.statusCode(),"Presigned PUT must reach real RustFS");
    }
    long createFood(Account a,long restaurant,String key)throws Exception {
        String name="food-"+UUID.randomUUID();ok("POST","/foods/upload",Map.of("restaurantId",restaurant,"name",name,"pictureUrl",List.of(key)),a.token);
        return jdbc.queryForObject("SELECT id FROM foods WHERE name=?",Long.class,name);
    }
    File file(String key){return jdbc.queryForObject("SELECT a.file_id,r.final_key,r.sha256,r.size_bytes FROM eatwhat_file.file_key_alias a JOIN eatwhat_file.file_revision r ON r.file_id=a.file_id AND r.revision=a.revision WHERE a.logical_key=?",(rs,n)->new File(key,rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3).trim(),rs.getLong(4)),key);}
    int live(String key){return jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?",Integer.class,key);}
    void replicate(File f)throws Exception {
        assertTrue(new FileReplicationWorker(jobs,consent,localReplicaStore,peer,replication,new Random(1)).runOnce() >= 1);
        assertEquals("DONE",jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE file_id=?",String.class,f.id));
        pair.exchange();
        assertEquals("AVAILABLE_GLOBAL",jdbc.queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=?",String.class,f.id));
        assertNotNull(jdbc.queryForObject("SELECT peer_confirmed_at FROM eatwhat_file.file_publication WHERE file_id=?",java.sql.Timestamp.class,f.id));
        assertEquals(f.sha,peer.readbackDigest(f.finalKey,f.size).sha256Hex());
    }
    List<String> pictures(Account a,long food)throws Exception {
        var data=ok("GET","/foods/detail/"+food,null,a.token).path("pictureUrl");
        var urls=new ArrayList<String>();data.forEach(x->urls.add(x.asText()));return urls;
    }
    record Account(long id,String token,UUID owner){}
    record File(String key,UUID id,String finalKey,String sha,long size){}
    static final class ObservedStore extends S3ReplicaObjectStore {
        final List<Integer> parts=new CopyOnWriteArrayList<>();final AtomicInteger starts=new AtomicInteger(),completes=new AtomicInteger();
        volatile boolean failAfterPart;
        ObservedStore(String endpoint,String bucket){super(endpoint,"us-east-1",bucket,IsolatedContainers.RUSTFS_ACCESS_KEY,IsolatedContainers.RUSTFS_SECRET_KEY,true,new Tuning(5L*1024*1024,5L*1024*1024,ByteRateLimiter.unlimited()));}
        void reset(){parts.clear();starts.set(0);completes.set(0);failAfterPart=false;}
        @Override public void putFile(String key,String type,Path path,String sha,UploadListener listener){
            super.putFile(key,type,path,sha,new UploadListener(){
                public void onMultipartStarted(String id){listener.onMultipartStarted(id);starts.incrementAndGet();}
                public void onPartUploaded(int part,int total){listener.onPartUploaded(part,total);parts.add(part);if(failAfterPart){failAfterPart=false;throw new IllegalStateException("Synthetic part response failure");}}
                public void onBeforeComplete(){listener.onBeforeComplete();completes.incrementAndGet();}
            });
        }
    }
    static final class CdnFixture implements AutoCloseable {
        final HttpServer server;final HttpsServer webhook;final ExecutorService threads=Executors.newCachedThreadPool();
        final Map<String,byte[]> cache=new ConcurrentHashMap<>();final AtomicBoolean failNext=new AtomicBoolean();
        final AtomicInteger accepted=new AtomicInteger(),rejected=new AtomicInteger();
        CdnFixture()throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);
            webhook=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);webhook.setExecutor(threads);
            webhook.setHttpsConfigurator(new HttpsConfigurator(FileEventChannelIT.tls.context(FileEventChannelIT.tls.external(),FileEventChannelIT.tls.campus())));
            server.createContext("/pub/",e->{
                try{String path=e.getRequestURI().getPath(),key=path.substring("/pub/".length());
                    if(!key.matches("verified/[0-9a-f-]{32,36}")){e.sendResponseHeaders(404,-1);return;}
                    boolean hit=cache.containsKey(path);byte[] data=cache.computeIfAbsent(path,p->s3.getObjectAsBytes(GetObjectRequest.builder().bucket(peerBucket).key(key).build()).asByteArray());
                    if(ORIGIN.equals(e.getRequestHeaders().getFirst("Origin")))e.getResponseHeaders().set("Access-Control-Allow-Origin",ORIGIN);
                    e.getResponseHeaders().set("Content-Type","image/png");e.getResponseHeaders().set("X-Test-Cache",hit?"HIT":"MISS");
                    e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);
                }catch(Exception ex){e.sendResponseHeaders(502,-1);}finally{e.close();}
            });
            webhook.createContext("/invalidate",e->{
                try{byte[] raw=e.getRequestBody().readNBytes(65537);
                    String signature=e.getRequestHeaders().getFirst(HttpWebhookCdnProvider.SIGNATURE_HEADER);
                    String stamp=e.getRequestHeaders().getFirst(HttpWebhookCdnProvider.TIMESTAMP_HEADER);
                    var body=JSON.readTree(raw);
                    if(!"POST".equals(e.getRequestMethod())||raw.length>65536||signature==null||!MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),HttpWebhookCdnProvider.sign(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8),raw).getBytes(StandardCharsets.US_ASCII))
                            ||stamp==null||!stamp.equals(body.path("timestamp").asText())){rejected.incrementAndGet();e.sendResponseHeaders(401,-1);return;}
                    if(failNext.compareAndSet(true,false)){e.sendResponseHeaders(503,-1);return;}
                    for(var path:body.path("paths"))cache.remove(path.asText());accepted.incrementAndGet();
                    e.getResponseHeaders().set("X-Request-Id",UUID.randomUUID().toString());e.sendResponseHeaders(202,-1);
                }catch(Exception ex){e.sendResponseHeaders(400,-1);}finally{e.close();}
            });server.start();webhook.start();
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        String webhookBase(){return "https://127.0.0.1:"+webhook.getAddress().getPort();}
        @Override public void close(){server.stop(0);webhook.stop(0);threads.shutdownNow();}
    }
}
