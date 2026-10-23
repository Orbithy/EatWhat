package you.v50to.eatwhat.service.storage.replication;

import java.time.Instant;
import java.util.List;

/**
 * Read-only listing extension of {@link ReplicaObjectStore} used by the report-only inventory.
 * Deliberately offers no delete or write operation.
 */
public interface InventoryObjectStore extends ReplicaObjectStore {
    /**
     * List one page of objects under a prefix in key order.
     *
     * @param prefix key prefix (may be empty for the whole bucket)
     * @param continuationToken token from the previous page, or null for the first page
     * @param maxKeys maximum number of entries to return in this page
     */
    ObjectPage listObjects(String prefix, String continuationToken, int maxKeys);

    /**
     * List every in-progress multipart upload whose key starts with the prefix (the store pages internally). Read-only.
     * The default reports none, so stores without multipart support need no change.
     */
    default List<MultipartUploadEntry> listMultipartUploads(String prefix) {
        return List.of();
    }

    /** @param initiated store-reported start time of the multipart upload (may be null if the store omits it) */
    record MultipartUploadEntry(String key, String uploadId, Instant initiated) { }

    record ObjectEntry(String key, long size, Instant lastModified) { }

    /** @param nextToken continuation token for the next page, or null when listing is complete */
    record ObjectPage(List<ObjectEntry> entries, String nextToken) { }
}
