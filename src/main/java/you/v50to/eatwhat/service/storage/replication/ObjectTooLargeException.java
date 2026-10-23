package you.v50to.eatwhat.service.storage.replication;

/**
 * Thrown when an object stream exceeds the maximum allowed size during download.
 */
public class ObjectTooLargeException extends RuntimeException {
    private final long maxBytes;
    private final long actualSize;

    public ObjectTooLargeException(long maxBytes, long actualSize) {
        super("Object size " + actualSize + " exceeds max allowed " + maxBytes + " bytes");
        this.maxBytes = maxBytes;
        this.actualSize = actualSize;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public long getActualSize() {
        return actualSize;
    }
}
