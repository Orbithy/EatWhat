package you.v50to.eatwhat.control;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import you.v50to.eatwhat.config.S3StorageProperties;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded, single-flight dependency probes. A timeout never becomes a cached PASS. No provider error text escapes. */
public final class ControlHealthChecks implements AutoCloseable {
    record Outcome(String status,String code) { static Outcome pass(){return new Outcome("PASS","OK");} }
    private final Clock clock; private final Duration timeout,ttl;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(threads("control-health-timer"));
    private final ExecutorService workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),threads("control-health-probe"),new ThreadPoolExecutor.AbortPolicy());
    private final LinkedHashMap<String,Check> checks=new LinkedHashMap<>();
    private final S3Client s3; private volatile boolean closed;
    private static ThreadFactory threads(String name){return r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;};}
    private final class Check {
        final String kind; final Supplier<Outcome> probe; boolean busy; long generation;
        Map<String,Object> value;
        Check(String kind,Supplier<Outcome> probe){this.kind=kind;this.probe=probe;value=Map.of("status","UNKNOWN","code","NOT_CHECKED","checkKind",kind,"running",false);}
        synchronized Map<String,Object> view(){var copy=new LinkedHashMap<>(value);copy.put("running",busy);Object until=copy.get("validUntil");if(until!=null&&!clock.instant().isBefore(Instant.parse(until.toString()))) {copy.put("status","STALE");copy.put("code","CHECK_RESULT_EXPIRED");}return copy;}
        synchronized void start(){
            if(busy||closed)return;busy=true;long id=++generation;Instant began=clock.instant();
            value=Map.of("status","CHECKING","code","CHECK_IN_PROGRESS","checkKind",kind,"startedAt",began.toString(),"running",true);
            var deadline=timer.schedule(()->finish(id,began,new Outcome("UNKNOWN","CHECK_TIMEOUT")),timeout.toMillis(),TimeUnit.MILLISECONDS);
            try{workers.execute(()->{try{Outcome result;try{result=probe.get();}catch(Exception e){result=new Outcome("UNKNOWN","DEPENDENCY_CHECK_FAILED");}if(clock.instant().isBefore(began.plus(timeout)))finish(id,began,result);else finish(id,began,new Outcome("UNKNOWN","CHECK_TIMEOUT"));}finally{deadline.cancel(false);synchronized(this){busy=false;}}});}
            catch(RejectedExecutionException e){deadline.cancel(false);busy=false;finish(id,began,new Outcome("UNKNOWN","CHECK_CAPACITY_EXCEEDED"));}
        }
        synchronized void finish(long id,Instant began,Outcome outcome){if(id!=generation||!"CHECKING".equals(value.get("status")))return;Instant end=clock.instant();value=Map.of("status",outcome.status(),"code",outcome.code(),"checkKind",kind,"startedAt",began.toString(),"checkedAt",end.toString(),"validUntil",end.plus(ttl).toString(),"durationMs",Math.max(0,Duration.between(began,end).toMillis()),"running",busy);}
    }
    public ControlHealthChecks(JdbcTemplate jdbc,StringRedisTemplate redis,ApplicationAvailability availability,S3StorageProperties props){
        this(Clock.systemUTC(),Duration.ofSeconds(5),Duration.ofSeconds(60),probes(jdbc,redis,availability),client(props),props.getBucket(),true);
    }
    // Package constructor permits deterministic expiry/timeout tests without simulating production health results.
    ControlHealthChecks(Clock clock,Duration timeout,Duration ttl,Map<String,Supplier<Outcome>> probes,S3Client client,String bucket,boolean automatic){
        this.clock=clock;this.timeout=timeout;this.ttl=ttl;this.s3=client;
        probes.forEach((name,probe)->checks.put(name,new Check(switch(name){case "application"->"APPLICATION_ACCEPTING_TRAFFIC";case "database"->"POSTGRES_SELECT_1";case "redis"->"REDIS_PING";default->throw new IllegalArgumentException();},probe)));
        checks.put("storage",new Check("S3_HEAD_BUCKET_AND_EMPTY_LIST",()->{
            if(client==null||bucket==null||bucket.isBlank())return new Outcome("UNKNOWN","STORAGE_NOT_CONFIGURED");
            try{
                // HEAD alone can be a static HTML success from a misrouted reverse proxy. A zero-key listing
                // requires the same bucket-list permission and proves a structured S3 response without exposing objects.
                var listing=client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).maxKeys(0).build());
                if(!bucket.equals(listing.name())||!listing.contents().isEmpty())return new Outcome("FAIL","STORAGE_INVALID_S3_RESPONSE");
                try{client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());}
                catch(S3Exception e){if(e.statusCode()==404)return new Outcome("FAIL","STORAGE_INVALID_S3_RESPONSE");throw e;}
                return Outcome.pass();}catch(Exception e){return storageFailure(e);}
        }));
        if(automatic)timer.scheduleWithFixedDelay(this::refresh,0,30,TimeUnit.SECONDS);
    }
    private static Map<String,Supplier<Outcome>> probes(JdbcTemplate jdbc,StringRedisTemplate redis,ApplicationAvailability availability){
        var p=new LinkedHashMap<String,Supplier<Outcome>>();
        p.put("application",()->availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC?Outcome.pass():new Outcome("FAIL","APPLICATION_NOT_ACCEPTING_TRAFFIC"));
        p.put("database",()->{try{Boolean ok=jdbc.execute((ConnectionCallback<Boolean>)c->{try(var s=c.createStatement()){s.setQueryTimeout(3);try(var r=s.executeQuery("SELECT 1")){return r.next()&&r.getInt(1)==1;}}});return Boolean.TRUE.equals(ok)?Outcome.pass():new Outcome("FAIL","DATABASE_NOT_READY");}catch(Exception e){return new Outcome("FAIL","DATABASE_UNAVAILABLE");}});
        p.put("redis",()->{try(var connection=Objects.requireNonNull(redis.getConnectionFactory()).getConnection()){return "PONG".equalsIgnoreCase(connection.ping())?Outcome.pass():new Outcome("FAIL","REDIS_NOT_READY");}catch(Exception e){return new Outcome("FAIL","REDIS_UNAVAILABLE");}});return p;
    }
    static S3Client client(S3StorageProperties p){
        if(p.getEndpoint()==null||p.getEndpoint().isBlank()||p.getRegion()==null||p.getBucket()==null||p.getAccessKey()==null||p.getSecretKey()==null)return null;
        try{return S3Client.builder().endpointOverride(URI.create(p.getEndpoint())).region(Region.of(p.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.getAccessKey(),p.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(p.isPathStyle()).build())
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(4)).apiCallAttemptTimeout(Duration.ofSeconds(4)).retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.none()).build()).build();}
        catch(Exception e){return null;}
    }
    static Outcome storageFailure(Exception e){
        if(e instanceof S3Exception s){
            var details=s.awsErrorDetails();
            if(details!=null){
                String contentType=details.sdkHttpResponse()==null?"":details.sdkHttpResponse().firstMatchingHeader("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                String body=details.rawResponse()==null?"":details.rawResponse().asUtf8String().stripLeading().toLowerCase(Locale.ROOT);
                if(contentType.contains("text/html")||body.startsWith("<html")||body.startsWith("<!doctype html"))return new Outcome("FAIL","STORAGE_ENDPOINT_NOT_S3");
            }
            return new Outcome("FAIL",switch(s.statusCode()){case 401,403->"STORAGE_ACCESS_DENIED";case 404->"STORAGE_BUCKET_NOT_FOUND";case 429,503->"STORAGE_TEMPORARILY_UNAVAILABLE";default->"STORAGE_REQUEST_FAILED";});
        }
        return new Outcome("UNKNOWN",e instanceof software.amazon.awssdk.core.exception.ApiCallTimeoutException||e instanceof software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException?"STORAGE_TIMEOUT":"STORAGE_UNREACHABLE");
    }
    public void refresh(){checks.values().forEach(Check::start);}
    public Map<String,Object> snapshot(){
        var items=new LinkedHashMap<String,Object>();checks.forEach((k,c)->items.put(k,c.view()));
        List<String> states=List.of("application","database","redis").stream().map(k->Objects.toString(((Map<?,?>)items.get(k)).get("status"))).toList();
        String readiness=states.contains("FAIL")?"NOT_READY":states.contains("STALE")?"STALE":states.contains("CHECKING")?"CHECKING":states.stream().allMatch("PASS"::equals)?"READY":"UNKNOWN";
        String storage=Objects.toString(((Map<?,?>)items.get("storage")).get("status"));
        return Map.of("readiness",readiness,"storageReachability",switch(storage){case "PASS"->"REACHABLE";case "FAIL"->"UNREACHABLE";default->storage;},"healthChecks",items);
    }
    @Override public void close(){closed=true;timer.shutdownNow();workers.shutdownNow();if(s3!=null)s3.close();}
}
