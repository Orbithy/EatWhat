package you.v50to.eatwhat.service.storage.replication;

/**
 * The only object-store capability that can remove objects. It is deliberately a separate interface so that
 * nothing receives delete rights by accident: {@link FileGcService} is the sole caller and is wired to the LOCAL
 * store only (never the peer store).
 */
public interface DeletableObjectStore extends InventoryObjectStore {
    /**
     * Delete one object. Idempotent: deleting an absent key must not throw.
     *
     * @param key the exact object key (never a prefix); must not be blank
     */
    void deleteObject(String key);

    /**
     * Abort one in-progress multipart upload, discarding its uploaded parts. Idempotent: an upload that no longer
     * exists must not throw. This is a delete-class operation: {@link FileGcService} is its only caller (besides a
     * store cleaning up a multipart upload it started itself), and only behind every GC gate.
     *
     * @param key the exact object key of the upload
     * @param uploadId the store's upload id; must not be blank
     */
    default void abortMultipartUpload(String key, String uploadId) {
        throw new UnsupportedOperationException("This object store cannot abort multipart uploads");
    }
}
