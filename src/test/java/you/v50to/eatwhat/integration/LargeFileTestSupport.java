package you.v50to.eatwhat.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.SplittableRandom;

/** Bounded-memory helpers for the large-file ITs: synthetic content is streamed, never held in a byte[] of file size. */
final class LargeFileTestSupport {
    static final long MIB = 1024L * 1024;

    private LargeFileTestSupport() { }

    /** A generated file and the SHA-256 computed while it was written. */
    record Generated(Path file, long size, String sha256) { }

    /**
     * Stream {@code size} pseudo-random bytes (deterministic for a seed, incompressible) into a new temp file in
     * {@code dir}, computing the SHA-256 on the way. Memory use is one 1 MiB buffer whatever the size.
     */
    static Generated generate(Path dir, String name, long size, long seed) throws IOException {
        Path file = Files.createTempFile(dir, name + "-", ".bin");
        MessageDigest digest = sha256();
        SplittableRandom random = new SplittableRandom(seed);
        byte[] buffer = new byte[(int) MIB];
        try (OutputStream out = Files.newOutputStream(file)) {
            long remaining = size;
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                random.nextBytes(buffer);
                out.write(buffer, 0, chunk);
                digest.update(buffer, 0, chunk);
                remaining -= chunk;
            }
        }
        return new Generated(file, size, HexFormat.of().formatHex(digest.digest()));
    }

    static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (var in = Files.newInputStream(file)) {
            int count;
            while ((count = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
