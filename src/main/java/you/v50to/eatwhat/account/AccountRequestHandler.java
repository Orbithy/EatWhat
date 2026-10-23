package you.v50to.eatwhat.account;

import tools.jackson.databind.JsonNode;

/** Called only after node authentication and framing checks; the dispatcher enforces operation roles. */
@FunctionalInterface
public interface AccountRequestHandler {
    JsonNode handle(String operation, JsonNode body);
}
