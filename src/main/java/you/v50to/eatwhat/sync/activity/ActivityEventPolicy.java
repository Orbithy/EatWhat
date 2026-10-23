package you.v50to.eatwhat.sync.activity;

import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.List;
import java.util.Set;

/** Strict transport allowlist for activity creation and author-only deletion; nothing else is carried. */
public final class ActivityEventPolicy implements EventPolicy {
    @Override public Set<String> aggregateTypes() { return ActivityEvents.TABLES; }

    @Override public void validate(SyncEvent event, NodeIdentity expectedOrigin) {
        if (event == null || expectedOrigin == null || !ActivityEvents.supportsTable(event.aggregateType())
                || !expectedOrigin.siteId().equals(event.originSite())
                || !expectedOrigin.instanceId().equals(event.originInstanceId())
                || event.schemaVersion() != ActivityEvents.SCHEMA_VERSION || event.actorId() == null
                || event.dependencies().size() != 1
                || !event.dependencies().equals(List.of(new SyncEvent.Dependency("users", event.actorId())))) {
            throw new IllegalArgumentException("ACTIVITY_EVENT_REQUIRED");
        }
        switch (event.eventType()) {
            case ActivityEvents.CREATED -> {
                var created = ActivityEvents.parseCreated(event.aggregateType(), event.payloadJson());
                if (!event.baseVersion().equals(VersionVector.empty())
                        || !event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))
                        || !created.globalId().equals(event.aggregateId()) || created.numericId() != event.numericId()
                        || !created.originSite().equals(event.originSite())
                        || !created.authorGlobalId().equals(event.actorId())) {
                    throw new IllegalArgumentException("INVALID_ACTIVITY_EVENT");
                }
            }
            case ActivityEvents.DELETED -> {
                ActivityEvents.parseDeleted(event.payloadJson());
                if (event.baseVersion().equals(VersionVector.empty())) throw new IllegalArgumentException("INVALID_ACTIVITY_EVENT");
            }
            default -> throw new IllegalArgumentException("ACTIVITY_EVENT_REQUIRED");
        }
    }
}
