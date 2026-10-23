package you.v50to.eatwhat.sync.transport;

/** Fixed-peer transport; implementations never derive a destination from event payloads. */
@FunctionalInterface
public interface TransportClient {
    TransportProtocol.Response exchange(String path, TransportProtocol.Request request) throws Exception;
}
