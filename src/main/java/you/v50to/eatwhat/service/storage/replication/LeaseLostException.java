package you.v50to.eatwhat.service.storage.replication;

/**
 * Raised by an {@link ReplicaObjectStore.UploadListener} to abort a long-running upload because the job's lease or
 * fencing token is gone: another worker now owns the job. The store cleans up its own in-flight upload and rethrows;
 * the replication worker maps it to {@code Outcome.LOST_LEASE} and writes nothing else to the job.
 */
public class LeaseLostException extends RuntimeException {
    public LeaseLostException(String message) {
        super(message);
    }
}
