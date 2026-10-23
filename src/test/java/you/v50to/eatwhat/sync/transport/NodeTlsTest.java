package you.v50to.eatwhat.sync.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NodeTlsTest {
    @TempDir Path directory;

    @Test
    void missingStoreAndMalformedPinsFailWithoutRetainingSecretsOrProviderDetails() {
        char[] password = "synthetic-sensitive-sentinel".toCharArray();
        for (Set<String> pins : java.util.List.of(Set.<String>of(), Set.of("not-a-fingerprint"), Set.of("a".repeat(64)))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> NodeTls.create(
                    directory.resolve("private-key-sentinel.p12"), password,
                    directory.resolve("trust.p12"), password, pins));
            assertEquals("Node TLS configuration could not be loaded", failure.getMessage());
            assertNull(failure.getCause());
            assertArrayEquals("synthetic-sensitive-sentinel".toCharArray(), password,
                    "The helper clears only its own password copies");
        }
    }

    @Test
    void unlimitedPinSetsAreRejectedBeforeStoreAccess() {
        assertThrows(IllegalStateException.class, () -> NodeTls.create(directory.resolve("keys.p12"),
                new char[]{'x'}, directory.resolve("trust.p12"), new char[]{'x'},
                Set.of("a".repeat(64), "b".repeat(64), "c".repeat(64))));
    }
}
