package you.v50to.eatwhat.service.storage;

import java.nio.file.Path;

/** S3 operations used by the file lifecycle state machine. */
public interface FileObjectStorage {
    String presignStagingPut(String stagingKey, String contentType, long ttlSeconds);

    StagedMetadata head(String key);

    StagedMetadata downloadToFile(String key, Path destination, long maxBytes);

    void putImmutableFile(String key, String contentType, Path source);

    record StagedMetadata(long contentLength, String contentType) { }
}
