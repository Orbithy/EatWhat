package you.v50to.eatwhat.sync.relation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Each immutable operation has its own transport aggregate; causal merge belongs to the business key. */
public final class RelationEvents implements EventPolicy {
    public static final String AGGREGATE="relation.operation", EVENT="relation.changed";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ConsentStore consent;
    private final Set<String> allowedNativeSites;

    public RelationEvents() {
        this(null);
    }

    public RelationEvents(ConsentStore consent) {
        this(consent, null);
    }

    public RelationEvents(ConsentStore consent, Set<String> allowedNativeSites) {
        this.consent = consent;
        this.allowedNativeSites = allowedNativeSites == null ? null : you.v50to.eatwhat.cluster.ClusterMembershipView.freezeOrLive(allowedNativeSites);
    }
    public record Notice(long numericId, UUID globalId, UUID recipientGid, String originSite,
                         String kind, String targetType, long createdAt, long expiresAt) { }
    public record Operation(RelationKind kind, UUID actor, UUID target, UUID owner, UUID tag,
                            long actorEpoch, long ownerEpoch, long rowId, UUID rowGid, String rowOrigin,
                            Map<String,Long> context, String dotSite, long dotCounter, boolean adding, Notice notification) {
        public VersionVector vector() { return new VersionVector(context); }
        public UUID relationId() {
            return UUID.nameUUIDFromBytes((kind+":"+actor+":"+target+":"+tag+":"+actorEpoch+":"+ownerEpoch)
                    .getBytes(StandardCharsets.UTF_8));
        }
        public List<SyncEvent.Dependency> dependencies() {
            var set=new HashSet<SyncEvent.Dependency>();
            set.add(new SyncEvent.Dependency("users",actor)); set.add(new SyncEvent.Dependency(kind.targetTable,target));
            set.add(new SyncEvent.Dependency("users",owner));
            if(tag!=null) set.add(new SyncEvent.Dependency("food_tags",tag));
            return set.stream().sorted(Comparator.comparing(SyncEvent.Dependency::aggregateType)
                    .thenComparing(d->d.aggregateId().toString())).toList();
        }
    }
    public static String payload(Operation op) { return EventCodec.canonicalJson(JSON.writeValueAsString(op)); }
    public static Operation parse(String payload) {
        try {
            return parseChecked(payload);
        } catch (RuntimeException invalid) {
            // Parser diagnostics may contain behavioral data; the wire boundary exposes a fixed code only.
            throw new IllegalArgumentException("INVALID_RELATION_EVENT");
        }
    }
    private static Operation parseChecked(String payload) {
        JsonNode n=JSON.readTree(EventCodec.canonicalJson(payload));
        exact(n,Operation.class);
        for(String f:List.of("actor","target","owner","rowGid")) uuid(n.get(f));
        if(!n.path("tag").isNull()) uuid(n.get("tag"));
        for(String f:List.of("actorEpoch","ownerEpoch","rowId","dotCounter")) positive(n.get(f));
        if(!n.path("adding").isBoolean() || !n.path("kind").isString()
                || !n.path("rowOrigin").isString() || !n.path("dotSite").isString()) invalid();
        EventCodec.vectorFromJson(n.path("context").toString());
        Operation op=JSON.readValue(n.toString(),Operation.class);
        SyncEvent.requireName(op.rowOrigin(),"row origin",64); SyncEvent.requireName(op.dotSite(),"dot site",64);
        if(op.vector().counters().getOrDefault(op.dotSite(),0L)+1!=op.dotCounter()
                || (op.kind()==RelationKind.tagging)!=(op.tag()!=null)
                || (op.kind()==RelationKind.follow && (!op.target().equals(op.owner()) || op.actor().equals(op.target())))) invalid();
        if(op.notification()!=null) {
            JsonNode nn=n.get("notification"); exact(nn,Notice.class);
            for(String f:List.of("numericId","createdAt","expiresAt")) positive(nn.get(f));
            uuid(nn.get("globalId"));uuid(nn.get("recipientGid"));
            Notice note=op.notification();
            String expectedType=op.kind()==RelationKind.follow?"user":op.kind().targetType;
            if(!op.adding() || op.actor().equals(op.owner()) || !note.recipientGid().equals(op.owner())
                    || !(op.kind()==RelationKind.follow || op.kind()==RelationKind.activity_food_like || op.kind()==RelationKind.activity_dinner_like
                        || op.kind()==RelationKind.activity_post_like)
                    || !Objects.equals(expectedType,note.targetType())
                    || !Objects.equals(op.kind()==RelationKind.follow?"follow":"like",note.kind())
                    || note.numericId()<=1_000_000_000 || note.expiresAt()-note.createdAt()!=DurationMillis.THIRTY_DAYS) invalid();
            SyncEvent.requireName(note.originSite(),"notice origin",64);
        }
        return op;
    }
    private static final class DurationMillis { static final long THIRTY_DAYS=2_592_000_000L; }
    private static void exact(JsonNode n,Class<?> type) {
        var fields=new HashSet<String>();for(var c:type.getRecordComponents())fields.add(c.getName());
        if(!n.isObject() || !fields.equals(Set.copyOf(n.propertyNames()))) invalid();
    }
    private static void uuid(JsonNode n) {
        if(n==null || !n.isString()) invalid();
        UUID u=UUID.fromString(n.stringValue()); SyncEvent.requireUuid(u,"relation identity");
        if(!u.toString().equals(n.stringValue())) invalid();
    }
    private static void positive(JsonNode n) {
        if(n==null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue()<1 || n.longValue()>VersionVector.MAX_COUNTER) invalid();
    }
    private static void invalid() { throw new IllegalArgumentException("INVALID_RELATION_EVENT"); }
    @Override public Set<String> aggregateTypes() { return Set.of(AGGREGATE); }
    @Override public Map<String,Set<Integer>> supportedSchemaVersions() { return Map.of(AGGREGATE,Set.of(1,2)); }

    /** Only cancellation-only snapshots may bypass a later ban; active ADD witnesses stay gated. */
    public static boolean removalOnly(SyncEvent event) {
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            var action = RelationCausalEvents.parseAction(event.payloadJson());
            return !action.adding() && removalOnly(action.state());
        }
        if (RelationCausalEvents.BRIDGE.equals(event.eventType()))
            return removalOnly(RelationCausalEvents.parseBridge(event.payloadJson()));
        return !parse(event.payloadJson()).adding();
    }

    static boolean removalOnly(RelationCausalEvents.Bridge state) {
        return state.blockedReason() == null && !state.removeFrontier().isEmpty()
                && state.addWitnesses().stream().noneMatch(add -> state.removeFrontier().stream()
                    .allMatch(remove -> add.context().getOrDefault(remove.stream(), 0L) >= remove.counter()));
    }

    @Override public OutboundDecision outbound(SyncEvent event) {
        if (!AGGREGATE.equals(event.aggregateType())) return OutboundDecision.ALLOW;
        if (consent == null) return OutboundDecision.WAIT;
        if (RelationCausalEvents.ACTION.equals(event.eventType()) || RelationCausalEvents.BRIDGE.equals(event.eventType())) {
            RelationCausalEvents.validateEvent(event, allowedNativeSites);
            UUID actor;
            UUID owner;
            long actorEpoch;
            long ownerEpoch;
            if (RelationCausalEvents.ACTION.equals(event.eventType())) {
                var operation = RelationCausalEvents.parseAction(event.payloadJson());
                actor = operation.actor(); owner = operation.owner();
                actorEpoch = operation.actorEpoch(); ownerEpoch = operation.ownerEpoch();
            } else {
                var bridge = RelationCausalEvents.parseBridge(event.payloadJson());
                actor = bridge.actor(); owner = bridge.owner();
                actorEpoch = bridge.actorEpoch(); ownerEpoch = bridge.ownerEpoch();
            }
            ConsentStore.OutboundAuthorization authorization = (removalOnly(event)
                    ? consent.outboundRemovalAuthorization(actor, actorEpoch, owner, ownerEpoch)
                    : consent.outboundAuthorization(actor, actorEpoch, owner, ownerEpoch));
            if (authorization.blocked()) return OutboundDecision.POLICY_BLOCKED;
            return authorization.allowed() ? OutboundDecision.ALLOW : OutboundDecision.WAIT;
        }
        Operation op = parse(event.payloadJson());
        ConsentStore.OutboundAuthorization authorization = (op.adding()
                ? consent.outboundAuthorization(op.actor(), op.actorEpoch(), op.owner(), op.ownerEpoch())
                : consent.outboundRemovalAuthorization(op.actor(), op.actorEpoch(), op.owner(), op.ownerEpoch()));
        if (authorization.blocked()) return OutboundDecision.POLICY_BLOCKED;
        return authorization.allowed() ? OutboundDecision.ALLOW : OutboundDecision.WAIT;
    }

    @Override public void validate(SyncEvent event,NodeIdentity origin) {
        if (RelationCausalEvents.ACTION.equals(event.eventType()) || RelationCausalEvents.BRIDGE.equals(event.eventType())) {
            if (!event.originSite().equals(origin.siteId()) || !event.originInstanceId().equals(origin.instanceId())) invalid();
            RelationCausalEvents.validateEvent(event, allowedNativeSites);
            return;
        }
        if(!event.originSite().equals(origin.siteId()) || !event.originInstanceId().equals(origin.instanceId())
                || !AGGREGATE.equals(event.aggregateType()) || !EVENT.equals(event.eventType()) || event.schemaVersion()!=1
                || !event.eventId().equals(event.aggregateId()) || !event.baseVersion().equals(VersionVector.empty())) invalid();
        Operation op=parse(event.payloadJson());
        if(!op.dotSite().equals(event.originSite()) || !op.actor().equals(event.actorId())
                || !op.dependencies().equals(event.dependencies())
                || (op.notification()!=null && !op.notification().originSite().equals(event.originSite()))) invalid();
    }
}
