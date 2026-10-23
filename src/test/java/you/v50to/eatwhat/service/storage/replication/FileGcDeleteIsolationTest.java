package you.v50to.eatwhat.service.storage.replication;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.config.FileGcProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Source-level guard: business-object deletion uses {@link DeletableObjectStore#deleteObject},
 * whose single caller is {@link FileGcService}, wired to the LOCAL store only. The explicit
 * control health probe may clean only its own UUID/instance-scoped temporary object;
 * ControlStorageCheckTest separately proves foreign ownership never invokes delete.
 * A plain text scan is used
 * (no ArchUnit): it is cheap and fails loudly if anybody adds another delete path.
 */
class FileGcDeleteIsolationTest {
    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        assumeTrue(Files.isDirectory(MAIN), "source tree not available from this working directory");
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static Set<String> filesContaining(String needle) throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path source : sources()) {
            if (Files.readString(source).contains(needle)) {
                found.add(source.getFileName().toString());
            }
        }
        return found;
    }

    @Test
    void businessDeletionRemainsGcOnlyAndTheExplicitHealthProbeHasOneOwnedCleanupPath() throws IOException {
        assertEquals(Set.of("DeletableObjectStore.java", "S3ReplicaObjectStore.java", "FileGcService.java", "ControlStorageCheck.java"),
                filesContaining("deleteObject("),
                "no deletion path beyond the business GC and explicit owned temporary health cleanup");
        assertEquals(Set.of("S3ReplicaObjectStore.java", "ControlStorageCheck.java"), filesContaining("DeleteObjectRequest"));
        assertEquals(Set.of(), filesContaining("DeleteObjectsRequest"), "no batch delete exists");
        assertEquals(Set.of(), filesContaining("deleteObjects("), "no batch delete exists");
        assertEquals(Set.of(), filesContaining("deleteBucket"), "buckets are never deleted");
        assertEquals(Set.of(), filesContaining("DeleteBucketRequest"));
        String probe=Files.readString(MAIN.resolve("you/v50to/eatwhat/control/ControlStorageCheck.java"));
        assertEquals(1,probe.split("client\\.deleteObject\\(",-1).length-1);
        assertTrue(probe.contains(".__eatwhat_control_health/"));
        assertTrue(probe.indexOf("throw new OwnershipFailure()")<probe.indexOf("client.deleteObject("));
        assertFalse(probe.contains("listObjects"),"health cleanup must never discover objects to delete");
    }

    @Test
    void abortingMultipartUploadsIsADeleteClassOperationWithTheSameIsolation() throws IOException {
        // The abort lives in the delete SPI, its S3 implementation (which also cleans up the upload it started itself)
        // and the GC service; the inventory, the replication worker and everything else can never abort an upload.
        assertEquals(Set.of("DeletableObjectStore.java", "S3ReplicaObjectStore.java", "FileGcService.java"),
                filesContaining("abortMultipartUpload("),
                "abortMultipartUpload( may appear only in the SPI, its S3 implementation and the GC service");
        assertEquals(Set.of("S3ReplicaObjectStore.java"), filesContaining("AbortMultipartUploadRequest"));
        // The read-only inventory SPI may list uploads but has no abort; the sweep reads through it only.
        Path inventorySpi = MAIN.resolve("you/v50to/eatwhat/service/storage/replication/InventoryObjectStore.java");
        assumeTrue(Files.isRegularFile(inventorySpi));
        assertFalse(Files.readString(inventorySpi).contains("abort"), "InventoryObjectStore must stay read-only");
        Path inventory = MAIN.resolve("you/v50to/eatwhat/service/storage/replication/FileInventoryService.java");
        assumeTrue(Files.isRegularFile(inventory));
        assertFalse(Files.readString(inventory).contains("abortMultipartUpload"), "the inventory only reports stale uploads");
        // The GC aborts only behind the execute gates: the call sits after the gate check of the STALE_MULTIPART class.
        String gc = Files.readString(MAIN.resolve("you/v50to/eatwhat/service/storage/replication/FileGcService.java"));
        int gate = gc.indexOf("executeRefusal(STALE_MULTIPART)");
        int abort = gc.indexOf("store.abortMultipartUpload(");
        assertTrue(gate >= 0 && abort > gate, "the abort must be preceded by the STALE_MULTIPART execute gate");
        assertEquals(1, gc.split("store\\.abortMultipartUpload\\(", -1).length - 1, "exactly one abort call site in the GC");
    }

    @Test
    void deleteCapabilityTypeIsReferencedOnlyByTheGcServiceTheS3StoreAndItself() throws IOException {
        assertEquals(Set.of("DeletableObjectStore.java", "S3ReplicaObjectStore.java", "FileGcService.java"),
                filesContaining("DeletableObjectStore"));
    }

    @Test
    void theGcServiceIsNeverWiredToThePeerStore() throws IOException {
        Path gc = MAIN.resolve("you/v50to/eatwhat/service/storage/replication/FileGcService.java");
        assumeTrue(Files.isRegularFile(gc));
        String source = Files.readString(gc);
        assertFalse(source.contains("peerReplicaStore"), "the GC must not receive the peer store");
        assertFalse(source.contains("peerStore"), "the GC must not receive the peer store");
        assertTrue(source.contains("@Qualifier(\"localReplicaStore\")"), "the GC is wired to the LOCAL store only");
    }

    @Test
    void everyOtherObjectStoreImplementationIsNotDeletable() throws IOException {
        // Implementations of the replication store interfaces other than the S3 store must not gain delete rights.
        Set<String> implementers = new TreeSet<>();
        for (Path source : sources()) {
            String text = Files.readString(source);
            if (text.matches("(?s).*\\bimplements\\b[^{]*\\b(ReplicaObjectStore|InventoryObjectStore|DeletableObjectStore)\\b.*")) {
                implementers.add(source.getFileName().toString());
            }
        }
        assertEquals(Set.of("S3ReplicaObjectStore.java"), implementers);
    }

    @Test
    void gcPropertiesDefaultToEverythingOff() {
        FileGcProperties defaults = new FileGcProperties();
        assertFalse(defaults.isInventoryEnabled());
        assertFalse(defaults.isEnabled(), "the GC has its own switch, off by default");
        assertTrue(defaults.isDryRun(), "dry run is the default");
        assertFalse(defaults.isExecuteEnabled());
        assertTrue(defaults.getExecuteClasses().isEmpty());
        assertEquals("", defaults.getExecuteAck());
        assertEquals(Duration.ofDays(30), defaults.getRetention());
        assertEquals(100, defaults.getMaxDeletesPerRun());
        assertEquals(Duration.ofHours(1), defaults.getGcInterval());
        assertEquals(Duration.ofMinutes(5), defaults.getClaimLease());
        assertEquals(Set.of("STAGING", "ORPHAN_CANDIDATE", "UNREFERENCED_REVISION", "STALE_MULTIPART"), FileGcProperties.EXECUTE_CLASSES);
        assertEquals(Duration.ofHours(24), defaults.getMultipartTtl());
    }

    @Test
    void theAckValueIsNeverPrintedByToString() {
        FileGcProperties properties = new FileGcProperties();
        properties.setExecuteAck("secret-drill-id-123");
        assertFalse(properties.toString().contains("secret-drill-id-123"));
        assertEquals(List.of(), properties.getExecuteClasses().stream().collect(Collectors.toList()));
    }
}
