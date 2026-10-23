package you.v50to.eatwhat.account;

import tools.jackson.databind.JsonNode;

/** Online, request-scoped account RPC. Implementations must never persist the request body. */
@FunctionalInterface
public interface AccountChannel {
    JsonNode call(String operation, JsonNode body) throws AccountUnavailableException;
}
