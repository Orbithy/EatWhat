package you.v50to.eatwhat.service.storage.replication;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Interface for object store operations during file replication.
 * Implementations must support bounded-memory streaming with digest verification.
 */
public interface ReplicaObjectStore {
    /**
     * Query object metadata without downloading its content.
     *
     * @param key the object key
     * @return optional metadata if object exists, empty if not found (404)
     */
    Optional<ObjectMeta> head(String key);

    /**
     * Download an object to a temporary file, computing SHA-256 digest during transfer.
     * Stream in 64 KiB buffers.
     *
     * @param key the object key
     * @param tmp target file path (must not exist or be writable)
     * @param maxBytes maximum allowed size; throws ObjectTooLargeException if exceeded
     * @return digest result with final size and SHA-256 hex string
     * @throws ObjectNotFoundException if object does not exist (404)
     * @throws ObjectTooLargeException if size exceeds maxBytes during streaming
     */
    DigestResult downloadTo(String key, Path tmp, long maxBytes);

    /**
     * Upload a file as an immutable object.
     * Must not overwrite an existing object under the same key.
     * Sets user metadata sha256 to the provided hex value.
     *
     * @param key the object key
     * @param contentType the MIME type
     * @param src source file path
     * @param sha256Hex SHA-256 hash as hexadecimal string
     * @throws IllegalStateException if object already exists (implementation-defined behavior:
     *         either conditional write rejection or no-op with documentation)
     */
    void putFile(String key, String contentType, Path src, String sha256Hex);

    /**
     * Upload a file like {@link #putFile(String, String, Path, String)} and report the progress of a multipart upload
     * to the caller. The default delegates to the four-argument method and never calls the listener, so existing
     * implementations (and test decorators) keep working unchanged; only stores that really use multipart override it.
     *
     * @param listener receives multipart progress; it may throw {@link LeaseLostException} to abort the upload
     */
    default void putFile(String key, String contentType, Path src, String sha256Hex, UploadListener listener) {
        putFile(key, contentType, src, sha256Hex);
    }

    /**
     * Progress callbacks of a multipart upload. Called on the uploading thread. Any exception thrown aborts the
     * upload (the store cleans up its own in-flight multipart upload and rethrows); {@link LeaseLostException} is the
     * dedicated signal for "this caller no longer owns the work".
     */
    interface UploadListener {
        /** A listener that ignores everything. */
        UploadListener NONE = new UploadListener() { };

        /** The multipart upload exists; persist {@code uploadId} so a later sweep knows who owns it. */
        default void onMultipartStarted(String uploadId) { }

        /** One part was uploaded. {@code partNumber} is 1-based. */
        default void onPartUploaded(int partNumber, int totalParts) { }

        /**
         * All parts are uploaded and the destination was re-checked as still absent; the final call before the
         * store completes the upload. A last chance to verify the lease.
         */
        default void onBeforeComplete() { }
    }

    /**
     * Stream an object to a null sink, computing its digest and verifying size.
     * Used for post-upload verification without storing the file.
     *
     * @param key the object key
     * @param maxBytes maximum allowed size; throws ObjectTooLargeException if exceeded
     * @return digest result with final size and SHA-256 hex string
     * @throws ObjectNotFoundException if object does not exist (404)
     * @throws ObjectTooLargeException if size exceeds maxBytes during streaming
     */
    DigestResult readbackDigest(String key, long maxBytes);

    /**
     * Object metadata.
     *
     * @param size content length in bytes
     * @param contentType MIME type
     */
    record ObjectMeta(long size, String contentType) { }

    /**
     * Result of digest computation during streaming.
     *
     * @param size total bytes streamed
     * @param sha256Hex SHA-256 hash as hexadecimal string
     */
    record DigestResult(long size, String sha256Hex) { }
}
