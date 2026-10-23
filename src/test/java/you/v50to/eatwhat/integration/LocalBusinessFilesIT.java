package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.JsonNode;
import you.v50to.eatwhat.service.storage.replication.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real controller/service/transaction/S3/CORS/cache chain; no mocked storage or manufactured file acknowledgments. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class LocalBusinessFilesIT extends LocalBusinessFileFixture {
    @Test void aUploadBusinessFinalizeReleaseAndRestoreUseActualHttpAndImmutableBytes()throws Exception {
        var a=account(true);long restaurant=restaurant(a);byte[] bytes=png(98304);
        var signed=presign(a,bytes);String key=signed.path("key").asText();put(signed,bytes);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?",Integer.class,key));
        long food=createFood(a,restaurant,key);var f=file(key);
        assertEquals(1,live(key));assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),f.sha());
        var before=pictures(a,food);assertEquals(1,before.size());assertTrue(before.getFirst().contains("X-Amz-Signature="));
        assertArrayEquals(bytes,http.send(HttpRequest.newBuilder(URI.create(before.getFirst())).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).body());
        // The old staging signature can still overwrite staging, but cannot change the completed immutable revision.
        put(signed,png(32));var completed=ok("POST","/upload/complete",Map.of("key",key),a.token());
        assertEquals(f.id().toString(),completed.path("fileId").asText());assertEquals(f.sha(),completed.path("sha256").asText());
        assertEquals(f.sha(),localReplicaStore.readbackDigest(f.finalKey(),bytes.length).sha256Hex());
        replicate(f);
        assertTrue(pictures(a,food).getFirst().startsWith(cdn.base()+"/pub/verified/"));
        assertNotEquals(0,api("DELETE","/foods/delete/"+food,null,a.token()).path("code").asInt());assertEquals(1,live(key));
        makeAdmin(a);ok("DELETE","/foods/delete/"+food,null,a.token());assertEquals(0,live(key));
        assertNotNull(jdbc.queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?",java.sql.Timestamp.class,f.id()));
        long restored=createFood(a,restaurant,key);assertEquals(1,live(key));
        assertNull(jdbc.queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?",java.sql.Timestamp.class,f.id()));
        assertNotNull(jdbc.queryForObject("SELECT revived_at FROM eatwhat_file.file_tombstone WHERE file_id=?",java.sql.Timestamp.class,f.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_revision WHERE file_id=?",Integer.class,f.id()));
        assertFalse(pictures(a,restored).isEmpty());assertTrue(localReplicaStore.head(f.finalKey()).isPresent());
        assertEquals(1,cdnWorker.runOnce());
        assertEquals("SKIPPED",jdbc.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?",String.class,f.id()));
    }
    @Test void bUploadAuthenticationOwnershipAndContentValidationFailThroughHttp()throws Exception {
        var unverified=account(false);byte[] bytes=png(64);
        assertEquals(20001,api("POST","/upload/presign",Map.of("biz","avatar","fileName","x.png","contentType","image/png","fileSize",64),null).path("code").asInt());
        assertNotEquals(0,api("POST","/upload/presign",Map.of("biz","foods","fileName","x.png","contentType","image/png","fileSize",64),unverified.token()).path("code").asInt());
        var owner=account(true);var stranger=account(true);var signed=presign(owner,bytes);String key=signed.path("key").asText();put(signed,bytes);
        assertNotEquals(0,api("POST","/upload/complete",Map.of("key",key),stranger.token()).path("code").asInt());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM eatwhat_file.upload_intent WHERE logical_key=?",String.class,key));
        ok("POST","/upload/complete",Map.of("key",key),owner.token());var f=file(key);replicate(f);
        int before=jdbc.queryForObject("SELECT count(*) FROM foods WHERE account_id=?",Integer.class,stranger.id());
        assertNotEquals(0,api("POST","/foods/upload",Map.of("restaurantId",restaurant(stranger),"name","Unauthorized key","pictureUrl",List.of(key)),stranger.token()).path("code").asInt());
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM foods WHERE account_id=?",Integer.class,stranger.id()));assertEquals(0,live(key));
        var wrongSize=presign(owner,png(128));put(wrongSize,png(64));String wrongKey=wrongSize.path("key").asText();
        assertNotEquals(0,api("POST","/upload/complete",Map.of("key",wrongKey),owner.token()).path("code").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?",Integer.class,wrongKey));
        byte[] invalid="not a PNG".getBytes(StandardCharsets.UTF_8);var wrongType=presign(owner,invalid);put(wrongType,invalid);
        assertNotEquals(0,api("POST","/upload/complete",Map.of("key",wrongType.path("key").asText()),owner.token()).path("code").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?",Integer.class,wrongType.path("key").asText()));
    }
    @Test void cBusinessAndStorageCorsAllowOnlyConfiguredOriginAndHeaders()throws Exception {
        var a=account(true);String denied="https://untrusted.example.invalid";
        for(String path:List.of("/upload/presign","/upload/complete","/foods/upload","/admin/food-tags")){
            var allowed=preflight(base()+path,ORIGIN,"POST","content-type,eatwhat-test-token");
            assertEquals(200,allowed.statusCode(),"Allowed API preflight: "+path);
            assertEquals(ORIGIN,allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
            assertEquals("true",allowed.headers().firstValue("Access-Control-Allow-Credentials").orElse(""));
            assertEquals(403,preflight(base()+path,denied,"POST","content-type").statusCode(),"Denied API preflight: "+path);
        }
        var data=Map.of("biz","foods","fileName","cors.png","contentType","image/png","fileSize",64);
        var accepted=response("POST","/upload/presign",data,a.token(),ORIGIN);assertEquals(200,accepted.statusCode());
        assertEquals(ORIGIN,accepted.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertEquals(0,JSON.readTree(accepted.body()).path("code").asInt());
        assertEquals(403,response("POST","/upload/presign",data,a.token(),denied).statusCode());
        var signed=JSON.readTree(accepted.body()).path("data");String put=signed.path("putUrl").asText();
        var preflight=preflight(put,ORIGIN,"PUT","content-type");assertEquals(200,preflight.statusCode());
        assertEquals(ORIGIN,preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        var blocked=preflight(put,denied,"PUT","content-type");
        assertFalse(blocked.headers().firstValue("Access-Control-Allow-Origin").filter(x->x.equals("*")||x.equals(denied)).isPresent(),"Object-store CORS must not allow the untrusted origin");
        assertFalse(preflight(put,ORIGIN,"DELETE","content-type").headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("DELETE"));
    }
    HttpResponse<String> preflight(String url,String origin,String method,String headers)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Origin",origin)
                .header("Access-Control-Request-Method",method).header("Access-Control-Request-Headers",headers)
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void dLargeHttpUploadReplicatesWithRealMultipartAndApplicationReceipts()throws Exception {
        var a=account(true);byte[] bytes=png(11*1024*1024+17);var signed=presign(a,bytes);put(signed,bytes);
        String key=signed.path("key").asText();ok("POST","/upload/complete",Map.of("key",key),a.token());
        long food=createFood(a,restaurant(a),key);var f=file(key);replicate(f);
        assertEquals(1,peer.starts.get());assertEquals(List.of(1,2,3),peer.parts);assertEquals(1,peer.completes.get());
        assertNull(jdbc.queryForObject("SELECT multipart_upload_id FROM eatwhat_file.file_transfer_job WHERE file_id=?",String.class,f.id()));
        assertTrue(s3.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(peerBucket).prefix(f.finalKey()).build()).uploads().isEmpty());
        var image=http.send(HttpRequest.newBuilder(URI.create(pictures(a,food).getFirst())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,image.statusCode());assertArrayEquals(bytes,image.body());
    }
    @Test void eMultipartFailureAbortsItsUploadBeforeRetryAndCannotPublishPartialBytes()throws Exception {
        var a=account(true);byte[] bytes=png(6*1024*1024+19);var signed=presign(a,bytes);put(signed,bytes);String key=signed.path("key").asText();
        long food=createFood(a,restaurant(a),key);var f=file(key);peer.failAfterPart=true;
        assertEquals(1,new FileReplicationWorker(jobs,consent,localReplicaStore,peer,replication,new Random(1)).runOnce());
        assertEquals(1,peer.starts.get());assertEquals(List.of(1),peer.parts);assertEquals(0,peer.completes.get());
        assertTrue(peer.head(f.finalKey()).isEmpty());
        assertNull(jdbc.queryForObject("SELECT multipart_upload_id FROM eatwhat_file.file_transfer_job WHERE file_id=?",String.class,f.id()));
        assertTrue(s3.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(peerBucket).prefix(f.finalKey()).build()).uploads().isEmpty());
        assertNotEquals("AVAILABLE_GLOBAL",jdbc.queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=?",String.class,f.id()));
        assertTrue(pictures(a,food).getFirst().contains("X-Amz-Signature="));
        jdbc.update("UPDATE eatwhat_file.file_transfer_job SET next_attempt_at=clock_timestamp() WHERE file_id=?",f.id());replicate(f);
        assertEquals(2,peer.starts.get());assertEquals(List.of(1,1,2),peer.parts);assertEquals(1,peer.completes.get());
        assertTrue(pictures(a,food).getFirst().startsWith(cdn.base()));
    }
    @Test void fReferenceReplacementAndDeletionPurgeActualCacheOnlyAfterSignedWebhookSucceeds()throws Exception {
        var a=account(true);makeAdmin(a);long restaurant=restaurant(a);byte[] bytes=png(98304);
        var signed=presign(a,bytes);put(signed,bytes);String key=signed.path("key").asText();long food=createFood(a,restaurant,key);var f=file(key);replicate(f);
        String url=pictures(a,food).getFirst(),path=URI.create(url).getPath();
        var image=http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertArrayEquals(bytes,image.body());
        assertEquals("MISS",image.headers().firstValue("X-Test-Cache").orElse(""));
        var cached=http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals("HIT",cached.headers().firstValue("X-Test-Cache").orElse(""));assertTrue(cdn.cache.containsKey(path));
        int rejections=cdn.rejected.get();
        var wrongSigner=new HttpWebhookCdnProvider(URI.create(cdn.webhookBase()+"/invalidate"),"incorrect-test-secret",Duration.ofSeconds(2));
        assertInstanceOf(CdnInvalidationProvider.Permanent.class,wrongSigner.submit(List.of(path),CdnInvalidationProvider.Reason.REVOKE,UUID.randomUUID()));
        assertEquals(rejections+1,cdn.rejected.get());assertTrue(cdn.cache.containsKey(path),"An invalid signature cannot evict cached bytes");
        var replacement=presign(a,png(80));put(replacement,png(80));String newKey=replacement.path("key").asText();
        ok("PUT","/foods/edit/"+food,Map.of("pictureUrl",List.of(newKey)),a.token());assertEquals(0,live(key));assertEquals(1,live(newKey));
        assertFalse(pictures(a,food).contains(url));assertEquals("KEY_REPLACED",jdbc.queryForObject("SELECT reason FROM eatwhat_file.file_reference_event WHERE logical_key=? ORDER BY event_id DESC LIMIT 1",String.class,key));
        cdn.failNext.set(true);assertEquals(1,cdnWorker.runOnce());assertTrue(cdn.cache.containsKey(path),"A failed provider call must not be treated as purged");
        assertNotEquals("DONE",jdbc.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?",String.class,f.id()));
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET next_attempt_at=clock_timestamp() WHERE file_id=?",f.id());
        int accepted=cdn.accepted.get();assertEquals(1,cdnWorker.runOnce());assertEquals(accepted+1,cdn.accepted.get());assertFalse(cdn.cache.containsKey(path));
        assertEquals("DONE",jdbc.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?",String.class,f.id()));
        replicate(file(newKey));ok("DELETE","/foods/delete/"+food,null,a.token());assertEquals(0,live(newKey));assertEquals(1,cdnWorker.runOnce());
    }
    @Test void gRestaurantHttpEditsFinalizeNewKeysAndReleaseOnlyReplacedReferences()throws Exception {
        var a=account(true);makeAdmin(a);long restaurant=restaurant(a);var keys=new ArrayList<String>();
        for(int n=0;n<2;n++){
            byte[] bytes=png(256+n);
            var signed=ok("POST","/upload/presign",Map.of("biz","restaurants","fileName","restaurant.png","contentType","image/png","fileSize",bytes.length),a.token());
            put(signed,bytes);String key=signed.path("key").asText();keys.add(key);
            ok("PUT","/restaurant/edit/"+restaurant,Map.of("pictureUrl",List.of(key)),a.token());
            assertEquals("COMPLETE",jdbc.queryForObject("SELECT status FROM eatwhat_file.upload_intent WHERE logical_key=?",String.class,key));
            assertEquals(1,live(key));replicate(file(key));
        }
        assertEquals(0,live(keys.getFirst()));assertEquals(1,live(keys.getLast()));
        var detail=ok("GET","/restaurant/detail/"+restaurant,null,a.token());assertEquals(1,detail.path("pictureUrl").size());
        assertEquals(cdn.base()+"/pub/"+file(keys.getLast()).finalKey(),detail.path("pictureUrl").get(0).asText());
        ok("PUT","/restaurant/edit/"+restaurant,Map.of("name","Renamed local restaurant"),a.token());
        assertEquals(1,live(keys.getLast()),"Non-image edits keep the image reference live");
        ok("DELETE","/restaurant/delete/"+restaurant,null,a.token());assertEquals(0,live(keys.getLast()));
        assertEquals(2,cdnWorker.runOnce());
    }
    @Test void hFailedHttpEditRollsBackBusinessPictureProjectionAndReferenceRelease()throws Exception {
        var a=account(true);makeAdmin(a);var stranger=account(true);long restaurant=restaurant(a);
        var own=presign(a,png(256));put(own,png(256));String kept=own.path("key").asText();long food=createFood(a,restaurant,kept);replicate(file(kept));
        var foreign=presign(stranger,png(257));put(foreign,png(257));String forbidden=foreign.path("key").asText();
        ok("POST","/upload/complete",Map.of("key",forbidden),stranger.token());replicate(file(forbidden));
        assertNotEquals(0,api("PUT","/foods/edit/"+food,Map.of("pictureUrl",List.of(forbidden)),a.token()).path("code").asInt());
        assertEquals(kept,jdbc.queryForObject("SELECT picture_url[1] FROM foods WHERE id=?",String.class,food));
        assertEquals(1,live(kept));assertEquals(0,live(forbidden));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_event WHERE logical_key=?",Integer.class,kept));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?",Integer.class,file(kept).id()));
        assertEquals(1,pictures(a,food).size());
    }

}
