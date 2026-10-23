package you.v50to.eatwhat.service.storage.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class BoundedFileInputStreamTest {
    @TempDir
    Path dir;

    private Path file(int size) throws IOException {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31 + 7);
        Path path = dir.resolve("data.bin");
        Files.write(path, bytes);
        return path;
    }

    @Test
    void readsExactlyTheRequestedWindowAndThenEnds() throws Exception {
        Path path = file(1000);
        byte[] all = Files.readAllBytes(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var in = new BoundedFileInputStream(channel, 100, 300);
            byte[] read = in.readAllBytes();
            assertEquals(300, read.length);
            assertArrayEquals(java.util.Arrays.copyOfRange(all, 100, 400), read);
            assertEquals(-1, in.read());
            assertEquals(0, in.available());
        }
    }

    @Test
    void severalViewsOverOneChannelAreIndependentAndRepeatable() throws Exception {
        Path path = file(64);
        byte[] all = Files.readAllBytes(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var first = new BoundedFileInputStream(channel, 0, 32);
            var second = new BoundedFileInputStream(channel, 32, 32);
            assertEquals(all[0] & 0xFF, first.read());
            assertEquals(all[32] & 0xFF, second.read());
            assertArrayEquals(java.util.Arrays.copyOfRange(all, 32, 64), new BoundedFileInputStream(channel, 32, 32).readAllBytes());
            assertArrayEquals(java.util.Arrays.copyOfRange(all, 32, 64), new BoundedFileInputStream(channel, 32, 32).readAllBytes());
        }
    }

    @Test
    void aFileShorterThanTheAnnouncedPartFailsInsteadOfSendingATruncatedPart() throws Exception {
        Path path = file(10);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var in = new BoundedFileInputStream(channel, 5, 20);
            assertThrows(EOFException.class, in::readAllBytes);
        }
    }

    @Test
    void invalidWindowsAreRejected() throws Exception {
        Path path = file(10);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            assertThrows(IllegalArgumentException.class, () -> new BoundedFileInputStream(channel, -1, 1));
            assertThrows(IllegalArgumentException.class, () -> new BoundedFileInputStream(channel, 0, -1));
        }
    }
}
