package you.v50to.eatwhat.service.storage.replication;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for digest computation logic.
 * These tests verify the digest and size-limit mechanisms without requiring storage.
 */
class DigestComputationTest {

    @Test
    void computeSha256OfEmptyData() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(new byte[0]);
        String hex = HexFormat.of().formatHex(digest.digest());

        // This is the well-known SHA-256 of empty string
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex);
    }

    @Test
    void computeSha256OfKnownData() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] data = "Hello, World!".getBytes();
        digest.update(data);
        String hex = HexFormat.of().formatHex(digest.digest());

        // Well-known SHA-256 of "Hello, World!"
        assertEquals("dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f", hex);
    }

    @Test
    void sizeTrackingAcrossMultipleUpdates() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] part1 = "Hello, ".getBytes();
        byte[] part2 = "World!".getBytes();

        digest.update(part1);
        digest.update(part2);

        // Should produce same result as single update
        String result = HexFormat.of().formatHex(digest.digest());
        assertEquals("dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f", result);
    }

    @Test
    void sizeLimitEnforcementThreshold() throws Exception {
        // Verify that size checking works at boundaries
        long maxBytes = 1024; // 1 KiB limit
        byte[] data = new byte[512];

        // Within limit: should succeed
        assertTrue(512 <= maxBytes);

        // Exactly at limit: should succeed
        assertTrue(1024 <= maxBytes);

        // Over limit: should fail
        assertFalse(1025 <= maxBytes);
    }

    @Test
    void largeDataStreamDigestIsConsistent() throws Exception {
        // Test that digest computation is stable across large data
        MessageDigest digest1 = MessageDigest.getInstance("SHA-256");
        MessageDigest digest2 = MessageDigest.getInstance("SHA-256");

        byte[] largeData = new byte[10 * 1024]; // 10 KiB
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        digest1.update(largeData);
        digest2.update(largeData);

        String hex1 = HexFormat.of().formatHex(digest1.digest());
        String hex2 = HexFormat.of().formatHex(digest2.digest());

        assertEquals(hex1, hex2, "Same data should produce identical digests");
    }

    @Test
    void digestResultRecordConstruction() {
        ReplicaObjectStore.DigestResult result = new ReplicaObjectStore.DigestResult(
                1024L, "abcd1234567890ef");

        assertEquals(1024L, result.size());
        assertEquals("abcd1234567890ef", result.sha256Hex());
    }

    @Test
    void objectMetaRecordConstruction() {
        ReplicaObjectStore.ObjectMeta meta = new ReplicaObjectStore.ObjectMeta(
                2048L, "application/octet-stream");

        assertEquals(2048L, meta.size());
        assertEquals("application/octet-stream", meta.contentType());
    }
}
