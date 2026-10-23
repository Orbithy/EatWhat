package you.v50to.eatwhat.control;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ControlHealthChecksTest {
 static class MutableClock extends Clock {Instant time=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time;}}
 Map<String,Supplier<ControlHealthChecks.Outcome>> probes(){return Map.of("application",ControlHealthChecks.Outcome::pass,"database",ControlHealthChecks.Outcome::pass,"redis",ControlHealthChecks.Outcome::pass);}
 static void until(java.util.function.BooleanSupplier done)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertTrue(done.getAsBoolean());}
 @Test void genuineSuccessExpiresAndARefreshFailureCannotUseLastPass()throws Exception{
  var clock=new MutableClock();var s3=mock(S3Client.class);when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().name("bucket").contents(List.of()).build());var status=new AtomicBoolean(true);var p=new HashMap<>(probes());p.put("redis",()->status.get()?ControlHealthChecks.Outcome.pass():new ControlHealthChecks.Outcome("FAIL","REDIS_UNAVAILABLE"));
  try(var service=new ControlHealthChecks(clock,Duration.ofSeconds(1),Duration.ofSeconds(60),p,s3,"bucket",false)){
   assertEquals("UNKNOWN",service.snapshot().get("readiness"));service.refresh();until(()->"READY".equals(service.snapshot().get("readiness"))&&"REACHABLE".equals(service.snapshot().get("storageReachability")));assertEquals("REACHABLE",service.snapshot().get("storageReachability"));
   clock.time=clock.time.plusSeconds(61);assertEquals("STALE",service.snapshot().get("readiness"));assertEquals("STALE",service.snapshot().get("storageReachability"));status.set(false);service.refresh();until(()->"NOT_READY".equals(service.snapshot().get("readiness")));assertFalse(service.snapshot().toString().contains("secret"));
  }
 }
 @Test void hungDependencyTimesOutWithoutBlockingOrUnboundedRetries()throws Exception{
  var release=new CountDownLatch(1);var count=new AtomicInteger();var p=new HashMap<>(probes());p.put("database",()->{count.incrementAndGet();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return ControlHealthChecks.Outcome.pass();});
  try(var service=new ControlHealthChecks(Clock.systemUTC(),Duration.ofMillis(80),Duration.ofSeconds(60),p,null,null,false)){
   service.refresh();until(()->service.snapshot().toString().contains("CHECK_TIMEOUT"));for(int i=0;i<50;i++){service.snapshot();service.refresh();}assertEquals(1,count.get());release.countDown();until(()->!service.snapshot().toString().contains("running=true"));assertEquals("UNKNOWN",service.snapshot().get("readiness"));
  }finally{release.countDown();}
 }
 @Test void storageDeniedIsIndependentFromApplicationReadinessAndNeverLeaksProviderText()throws Exception{
  var s3=mock(S3Client.class);when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(S3Exception.builder().statusCode(403).message("secret endpoint access-key").build());when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(S3Exception.builder().statusCode(403).message("secret endpoint access-key").build());
  try(var service=new ControlHealthChecks(Clock.systemUTC(),Duration.ofSeconds(1),Duration.ofSeconds(60),probes(),s3,"bucket",false)){
   service.refresh();until(()->"READY".equals(service.snapshot().get("readiness"))&&"UNREACHABLE".equals(service.snapshot().get("storageReachability")));assertTrue(service.snapshot().toString().contains("STORAGE_ACCESS_DENIED"));assertFalse(service.snapshot().toString().contains("secret endpoint"));verify(s3,never()).putObject(any(PutObjectRequest.class),any(software.amazon.awssdk.core.sync.RequestBody.class));
  }
 }
 @Test void html404IsAnEndpointFailureAndHtml200CannotProveBucketAccess()throws Exception{
  var error=S3Exception.builder().statusCode(404).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder().sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder().statusCode(404).putHeader("Content-Type","text/html").build()).build()).build();assertEquals("STORAGE_ENDPOINT_NOT_S3",ControlHealthChecks.storageFailure(error).code());
  var s3=mock(S3Client.class);when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().build());
  try(var service=new ControlHealthChecks(Clock.systemUTC(),Duration.ofSeconds(1),Duration.ofSeconds(60),probes(),s3,"bucket",false)){service.refresh();until(()->service.snapshot().toString().contains("STORAGE_INVALID_S3_RESPONSE"));assertEquals("UNREACHABLE",service.snapshot().get("storageReachability"));}
 }

 @Test void actualSdkRejectsAnHtmlReverseProxyResponse()throws Exception{
  var status=new AtomicInteger(404);var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{byte[] body="<html><head><title>Not Found</title></head><body>edge</body></html>".getBytes();exchange.getResponseHeaders().add("Content-Type","text/html");exchange.sendResponseHeaders(status.get(),exchange.getRequestMethod().equals("HEAD")?-1:body.length);if(!exchange.getRequestMethod().equals("HEAD"))exchange.getResponseBody().write(body);exchange.close();});server.start();
  var p=new you.v50to.eatwhat.config.S3StorageProperties();p.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort());p.setRegion("us-east-1");p.setBucket("bucket");p.setAccessKey("synthetic-key");p.setSecretKey("synthetic-secret");
  try(var service=new ControlHealthChecks(Clock.systemUTC(),Duration.ofSeconds(5),Duration.ofSeconds(60),probes(),ControlHealthChecks.client(p),"bucket",false)){
   service.refresh();until(()->service.snapshot().toString().contains("STORAGE_ENDPOINT_NOT_S3"));status.set(200);until(()->!service.snapshot().toString().contains("running=true"));service.refresh();until(()->!"CHECKING".equals(service.snapshot().get("storageReachability")));assertNotEquals("REACHABLE",service.snapshot().get("storageReachability"));
  }finally{server.stop(0);}
 }

}
