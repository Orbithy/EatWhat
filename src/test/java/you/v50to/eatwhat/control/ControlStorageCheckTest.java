package you.v50to.eatwhat.control;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import you.v50to.eatwhat.config.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ControlStorageCheckTest {
 @TempDir Path root;S3Client s3;ControlAudit audit;SiteProperties site;S3StorageProperties props;AtomicReference<PutObjectRequest> object;byte[] bytes;AtomicBoolean denyDelete,foreign,corrupt,ambiguousPut;
 @BeforeEach void setup()throws Exception{
  s3=mock(S3Client.class);audit=new ControlAudit(root.resolve("state"));site=new SiteProperties();site.setInstanceId(UUID.randomUUID());props=new S3StorageProperties();props.setEndpoint("https://synthetic.invalid");props.setRegion("us-east-1");props.setBucket("fixture");object=new AtomicReference<>();denyDelete=new AtomicBoolean();foreign=new AtomicBoolean();corrupt=new AtomicBoolean();ambiguousPut=new AtomicBoolean();
  when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(inv->{object.set(inv.getArgument(0));bytes=((RequestBody)inv.getArgument(1)).contentStreamProvider().newStream().readAllBytes();if(ambiguousPut.get())throw new IOException("secret ambiguous transport error");return PutObjectResponse.builder().build();});
  when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(inv->new ResponseInputStream<>(GetObjectResponse.builder().build(),AbortableInputStream.create(new ByteArrayInputStream(corrupt.get()?new byte[]{1}:bytes))));
  when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(inv->{if(object.get()==null)throw S3Exception.builder().statusCode(404).build();return HeadObjectResponse.builder().metadata(foreign.get()?Map.of("eatwhat-probe","foreign"):object.get().metadata()).build();});
  when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv->{if(denyDelete.get())throw S3Exception.builder().statusCode(403).message("secret credentials").build();object.set(null);return DeleteObjectResponse.builder().build();});
 }
 @Test void writeReadAndConfirmedCleanupAreRequiredAndUuidDoesNotRepeatPut()throws Exception{
  try(var service=new ControlStorageCheck(audit,site,props,s3)){UUID id=UUID.randomUUID();service.start("admin",id);ControlHealthChecksTest.until(()->"PASSED".equals(service.snapshot().get("state")));service.start("admin",id);verify(s3,times(1)).putObject(any(PutObjectRequest.class),any(RequestBody.class));assertNull(object.get());assertFalse(service.snapshot().containsKey("key"));assertFalse(service.snapshot().toString().contains("payload"));assertTrue((Boolean)service.snapshot().get("readVerified"));assertTrue((Boolean)service.snapshot().get("cleanupVerified"));}
 }
 @Test void failedCleanupPersistsAcrossRestartAndOnlyCleanupRepeats()throws Exception{
  UUID id=UUID.randomUUID();denyDelete.set(true);
  try(var service=new ControlStorageCheck(audit,site,props,s3)){service.start("admin",id);ControlHealthChecksTest.until(()->service.snapshot().toString().contains("STORAGE_PROBE_CLEANUP_FAILED"));assertEquals("CLEANUP_PENDING",service.snapshot().get("state"));assertEquals(409,assertThrows(ControlFailure.class,()->service.start("admin",UUID.randomUUID())).status);assertFalse(service.snapshot().toString().contains("secret"));}
  denyDelete.set(false);try(var recovered=new ControlStorageCheck(audit,site,props,s3)){ControlHealthChecksTest.until(()->"PASSED".equals(recovered.snapshot().get("state")));assertNull(object.get());verify(s3,times(1)).putObject(any(PutObjectRequest.class),any(RequestBody.class));}
 }
 @Test void ambiguousPutIsCleanedButNeverClaimsVerifiedReadWrite()throws Exception{
  ambiguousPut.set(true);try(var service=new ControlStorageCheck(audit,site,props,s3)){service.start("admin",UUID.randomUUID());ControlHealthChecksTest.until(()->"FAILED".equals(service.snapshot().get("state")));assertTrue((Boolean)service.snapshot().get("cleanupVerified"));assertFalse((Boolean)service.snapshot().get("writeVerified"));assertNull(object.get());}
 }
 @Test void foreignObjectIsNeverDeleted()throws Exception{
  foreign.set(true);try(var service=new ControlStorageCheck(audit,site,props,s3)){service.start("admin",UUID.randomUUID());ControlHealthChecksTest.until(()->service.snapshot().toString().contains("OWNERSHIP_MISMATCH"));verify(s3,never()).deleteObject(any(DeleteObjectRequest.class));assertEquals("CLEANUP_PENDING",service.snapshot().get("state"));}
 }
 @Test void incorrectReadBodyCannotPassEvenAfterSuccessfulCleanup()throws Exception{
  corrupt.set(true);try(var service=new ControlStorageCheck(audit,site,props,s3)){service.start("admin",UUID.randomUUID());ControlHealthChecksTest.until(()->"FAILED".equals(service.snapshot().get("state")));assertEquals(false,service.snapshot().get("readVerified"));assertEquals("STORAGE_READ_MISMATCH",service.snapshot().get("code"));assertNull(object.get());}
 }
}
