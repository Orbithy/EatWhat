package you.v50to.eatwhat.service.storage.replication;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Read-only view of {@code length} bytes of a file starting at {@code offset}, backed by positional reads of a shared
 * {@link FileChannel}. It never buffers more than the caller's own array, so a multipart part can be streamed to the
 * HTTP client without holding it in memory. Closing the view does not close the channel (the owner does); several
 * views over the same channel may be open at the same time because positional reads do not move a shared position.
 */
final class BoundedFileInputStream extends InputStream {
    private final FileChannel channel;
    private long position;
    private long remaining;

    BoundedFileInputStream(FileChannel channel, long offset, long length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset and length must be >= 0");
        }
        this.channel = channel;
        this.position = offset;
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (remaining <= 0) {
            return -1;
        }
        int wanted = (int) Math.min(length, remaining);
        int count = channel.read(ByteBuffer.wrap(target, offset, wanted), position);
        if (count < 0) {
            // The file is shorter than the length the part was announced with: fail the upload instead of sending a
            // truncated part under a larger Content-Length.
            throw new EOFException("Source file ended " + remaining + " bytes before the end of the part");
        }
        position += count;
        remaining -= count;
        return count;
    }

    @Override
    public int available() {
        return (int) Math.min(remaining, Integer.MAX_VALUE);
    }
}
