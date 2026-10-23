package you.v50to.eatwhat.service;

import tools.jackson.databind.JsonNode;
import you.v50to.eatwhat.data.enums.Scene;

/** Routes one-time challenge state (SMS codes, e-mail tokens) to the single site that owns it, so limits are counted once cluster-wide. */
public interface ChallengePeers {
    /** Same answer on every member for a given routing key; never depends on which members are currently reachable. */
    String ownerOf(String routingKey);

    /** The phone's send interval is shared across accounts; challenge keys still bind the actor. */
    default String owner(Scene scene, String mobile) {
        return ownerOf("sms-owner|" + scene + "|" + mobile);
    }

    /** Online, single-attempt call to the owning site. Throws when it cannot be reached; callers fail closed. */
    JsonNode call(String siteId, String operation, JsonNode body);
}
