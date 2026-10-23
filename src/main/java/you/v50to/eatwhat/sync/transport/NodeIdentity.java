package you.v50to.eatwhat.sync.transport;

import you.v50to.eatwhat.sync.SyncEvent;

import java.util.UUID;

/** Pinned application identity, checked in addition to the TLS peer certificate. */
public record NodeIdentity(String siteId, UUID instanceId) {
    public NodeIdentity {
        SyncEvent.requireName(siteId, "siteId", 64);
        SyncEvent.requireUuid(instanceId, "instanceId");
    }
}
