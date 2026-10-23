package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.availability.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.control.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG, Redis and RustFS: no production endpoint or credentials, no public ports/volumes. */
class ControlHealthIT {
 @TempDir Path root;
 static void until(java.util.function.BooleanSupplier done,int seconds)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(30);assertTrue(done.getAsBoolean());}
 S3StorageProperties props(String endpoint){var p=new S3StorageProperties();p.setEndpoint(endpoint);p.setBucket("health-fixture");p.setRegion("us-east-1");p.setAccessKey(IsolatedContainers.RUSTFS_ACCESS_KEY);p.setSecretKey(IsolatedContainers.RUSTFS_SECRET_KEY);return p;}
 S3Client client(S3StorageProperties p){return S3Client.builder().endpointOverride(URI.create(p.getEndpoint())).region(Region.of(p.getRegion())).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.getAccessKey(),p.getSecretKey()))).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(4)).apiCallAttemptTimeout(Duration.ofSeconds(4)).build()).build();}
 @Test void realDependenciesWriteReadCleanupDenialAndOutagesAreTruthful()throws Exception{
  try(var pg=IsolatedContainers.postgres("health");var redis=IsolatedContainers.redis("health");var rust=IsolatedContainers.rustfs("health")){
   pg.start();redis.start();rust.start();var props=props("http://"+rust.getHost()+":"+rust.getMappedPort(9000));
   var jdbc=new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(pg,IsolatedContainers.DATABASE),IsolatedContainers.USER,IsolatedContainers.PASSWORD));
   var factory=new LettuceConnectionFactory(redis.getHost(),redis.getMappedPort(6379));factory.afterPropertiesSet();factory.start();var template=new StringRedisTemplate(factory);
   var app=mock(ApplicationAvailability.class);when(app.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
   try(var admin=client(props);var health=new ControlHealthChecks(jdbc,template,app,props)){
    // Listening does not itself prove RustFS is ready for authenticated S3 operations.
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(true){try{admin.createBucket(CreateBucketRequest.builder().bucket(props.getBucket()).build());break;}catch(Exception e){if(System.nanoTime()>deadline)throw e;Thread.sleep(100);}}
    until(()->!health.snapshot().toString().contains("running=true"),8);health.refresh();until(()->"READY".equals(health.snapshot().get("readiness"))&&"REACHABLE".equals(health.snapshot().get("storageReachability")),12);
    var site=new SiteProperties();site.setInstanceId(UUID.randomUUID());var audit=new ControlAudit(root.resolve("state"));UUID id=UUID.randomUUID();
    try(var write=new ControlStorageCheck(audit,site,props)){write.start("fixture-admin",id);until(()->"PASSED".equals(write.snapshot().get("state")),15);assertEquals(true,write.snapshot().get("cleanupVerified"));assertTrue(admin.listObjectsV2(ListObjectsV2Request.builder().bucket(props.getBucket()).build()).contents().isEmpty());write.start("fixture-admin",id);assertTrue(admin.listObjectsV2(ListObjectsV2Request.builder().bucket(props.getBucket()).build()).contents().isEmpty());}
    var denied=props(props.getEndpoint());denied.setAccessKey("nonexistent-test-user");denied.setSecretKey("synthetic-wrong-secret");
    try(var bad=new ControlHealthChecks(jdbc,template,app,denied)){until(()->bad.snapshot().toString().contains("STORAGE_ACCESS_DENIED"),12);assertEquals("UNREACHABLE",bad.snapshot().get("storageReachability"));assertFalse(bad.snapshot().toString().contains("synthetic-wrong-secret"));}
    var missing=props(props.getEndpoint());missing.setBucket("missing-health-bucket");try(var bad=new ControlHealthChecks(jdbc,template,app,missing)){until(()->bad.snapshot().toString().contains("STORAGE_BUCKET_NOT_FOUND"),12);}
    var docker=rust.getDockerClient();docker.pauseContainerCmd(rust.getContainerId()).exec();try{health.refresh();until(()->health.snapshot().toString().contains("STORAGE_TIMEOUT")||health.snapshot().toString().contains("CHECK_TIMEOUT"),12);assertNotEquals("REACHABLE",health.snapshot().get("storageReachability"));}finally{docker.unpauseContainerCmd(rust.getContainerId()).exec();}
    health.refresh();until(()->"REACHABLE".equals(health.snapshot().get("storageReachability")),12);
    when(app.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);health.refresh();until(()->health.snapshot().toString().contains("APPLICATION_NOT_ACCEPTING_TRAFFIC"),12);assertEquals("NOT_READY",health.snapshot().get("readiness"));when(app.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
    redis.stop();health.refresh();until(()->!"CHECKING".equals(health.snapshot().get("readiness")),12);assertNotEquals("READY",health.snapshot().get("readiness"));assertTrue(health.snapshot().toString().contains("REDIS_UNAVAILABLE")||health.snapshot().toString().contains("CHECK_TIMEOUT"));
    pg.stop();health.refresh();until(()->health.snapshot().toString().contains("DATABASE_UNAVAILABLE"),15);assertNotEquals("READY",health.snapshot().get("readiness"));
   }finally{factory.destroy();}
  }
 }
}
