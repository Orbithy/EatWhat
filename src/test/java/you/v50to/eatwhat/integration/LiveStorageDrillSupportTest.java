package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.model.S3Exception;
import you.v50to.eatwhat.service.storage.replication.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Safety checks for the opt-in harness's real-store mutation boundary. No remote I/O. */
class LiveStorageDrillSupportTest {
    private static final String KEY = "verified/11111111-1111-4111-8111-111111111111";

    @Test void unknownKeysAndBucketWideOperationsNeverReachTheSdk(@TempDir Path directory) {
        var sdk = mock(S3ReplicaObjectStore.class);
        var store = new LiveStorageDrillSupport.OwnedStore(sdk, Set.of(KEY));
        String other = "verified/22222222-2222-4222-8222-222222222222";
        assertThrows(IllegalArgumentException.class, () -> store.head(other));
        assertThrows(IllegalArgumentException.class, () -> store.readbackDigest(other, 10));
        assertThrows(IllegalArgumentException.class, () -> store.downloadTo(other, directory.resolve("body"), 10));
        assertThrows(IllegalArgumentException.class, () -> store.putFile(other, "image/png", directory.resolve("body"), "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> store.deleteObject(other));
        assertThrows(UnsupportedOperationException.class, () -> store.listObjects("", null, 1000));
        assertThrows(UnsupportedOperationException.class, () -> store.listMultipartUploads(""));
        assertThrows(UnsupportedOperationException.class, () -> store.abortMultipartUpload(KEY, "not-owned"));
        verifyNoInteractions(sdk);
    }

    @Test void lostDeleteResponseHappensAfterTheActualDeleteAndOnlyOnce() {
        var sdk = mock(S3ReplicaObjectStore.class);
        var store = new LiveStorageDrillSupport.OwnedStore(sdk, Set.of(KEY));
        store.loseNextDeleteResponse = true;
        assertThrows(IllegalStateException.class, () -> store.deleteObject(KEY));
        verify(sdk).deleteObject(KEY);
        assertDoesNotThrow(() -> store.deleteObject(KEY));
        verify(sdk, times(2)).deleteObject(KEY);
        assertEquals(2, store.deletes.get());
        var unavailableSdk = mock(S3ReplicaObjectStore.class);
        when(unavailableSdk.head(KEY)).thenReturn(Optional.of(new ReplicaObjectStore.ObjectMeta(12, "image/png")));
        var unavailable = new LiveStorageDrillSupport.OwnedStore(unavailableSdk, Set.of(KEY));
        unavailable.loseNextDeleteResponse = true; unavailable.blockConfirmationOnLostDelete = true;
        assertTrue(unavailable.head(KEY).isPresent(), "Probe failure must not precede the real delete");
        assertThrows(IllegalStateException.class, () -> unavailable.deleteObject(KEY));
        verify(unavailableSdk).deleteObject(KEY);
        assertThrows(IllegalStateException.class, () -> unavailable.head(KEY));
        verify(unavailableSdk, times(1)).head(KEY);
    }

    @Test void sdkFailuresAreRedactedWithoutChangingExpectedDomainExceptions() {
        var sdk = mock(S3ReplicaObjectStore.class);
        var store = new LiveStorageDrillSupport.OwnedStore(sdk, Set.of(KEY));
        when(sdk.head(KEY)).thenThrow(S3Exception.builder().message("private endpoint and credentials").build());
        var failure = assertThrows(IllegalStateException.class, () -> store.head(KEY));
        assertEquals("ACCEPTANCE_STORAGE_S3Exception", failure.getMessage()); assertNull(failure.getCause());
        var missing = new ObjectNotFoundException(KEY);
        when(sdk.readbackDigest(KEY, 10)).thenThrow(missing);
        assertSame(missing, assertThrows(ObjectNotFoundException.class, () -> store.readbackDigest(KEY, 10)));
    }

    @Test void realModeRequiresDedicatedBucketsRealR2AndExplicitCredentialSources(@TempDir Path directory) throws Exception {
        Path credentials = directory.resolve("credentials.properties");
        Files.writeString(credentials, "access-key=synthetic\nsecret-key=synthetic\n");
        var valid = new Properties();
        valid.setProperty("acceptance.dedicated-test-buckets", "true");
        for (String name : List.of("rustfs", "r2")) {
            valid.setProperty(name + ".endpoint", name.equals("r2") ? "https://" + "a".repeat(32) + ".r2.cloudflarestorage.com" : "https://rustfs-test.example.com");
            valid.setProperty(name + ".region", name.equals("r2") ? "auto" : "us-east-1");
            valid.setProperty(name + ".bucket", "acceptance-" + name);
            valid.setProperty(name + ".path-style", name.equals("rustfs") ? "true" : "false");
            valid.setProperty(name + ".credentials-file", credentials.toString());
        }
        assertDoesNotThrow(() -> LiveStorageDrillSupport.validate(valid, false));
        for (var override : List.of(Map.entry("acceptance.dedicated-test-buckets", "false"),
                Map.entry("r2.endpoint", "https://some-other-s3.example.com"), Map.entry("r2.region", "us-east-1"),
                Map.entry("rustfs.endpoint", valid.getProperty("r2.endpoint")), Map.entry("r2.credentials-profile", "also-selected"))) {
            var invalid = new Properties(); invalid.putAll(valid); invalid.setProperty(override.getKey(), override.getValue());
            assertThrows(IllegalArgumentException.class, () -> LiveStorageDrillSupport.validate(invalid, false));
        }
    }
}
