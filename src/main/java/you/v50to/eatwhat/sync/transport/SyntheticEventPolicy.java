package you.v50to.eatwhat.sync.transport;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;

/** PR-04 permits only bounded synthetic counters, with no actor or business dependencies. */
public final class SyntheticEventPolicy {
    public static final String AGGREGATE_TYPE = "synthetic.counter";
    public static final String EVENT_TYPE = "counter.incremented";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SyntheticEventPolicy() { }

    public static void validate(SyncEvent event, NodeIdentity expectedOrigin) {
        if (event == null || expectedOrigin == null || !AGGREGATE_TYPE.equals(event.aggregateType())
                || !EVENT_TYPE.equals(event.eventType()) || !expectedOrigin.siteId().equals(event.originSite())
                || !expectedOrigin.instanceId().equals(event.originInstanceId()) || event.actorId() != null
                || !event.dependencies().isEmpty()) {
            throw new IllegalArgumentException("SYNTHETIC_EVENT_REQUIRED");
        }
        var payload = JSON.readTree(EventCodec.canonicalJson(event.payloadJson()));
        var delta = payload.get("delta");
        if (!payload.isObject() || payload.size() != 1 || delta == null || !delta.isIntegralNumber()
                || !delta.canConvertToInt() || delta.intValue() < 1 || delta.intValue() > 1000) {
            throw new IllegalArgumentException("INVALID_SYNTHETIC_PAYLOAD");
        }
    }
}
