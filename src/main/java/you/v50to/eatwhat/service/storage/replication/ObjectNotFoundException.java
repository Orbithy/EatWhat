package you.v50to.eatwhat.service.storage.replication;

/**
 * Thrown when an object does not exist in the store (404 response).
 */
public class ObjectNotFoundException extends RuntimeException {
    private final String key;

    public ObjectNotFoundException(String key) {
        super("Object not found: " + key);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
