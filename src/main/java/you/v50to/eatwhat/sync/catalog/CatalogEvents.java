package you.v50to.eatwhat.sync.catalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Wire contracts for independent catalog operations and immutable state bridges. */
public final class CatalogEvents {
    public static final String AGGREGATE = "catalog.operation";
    public static final String ACTION = "catalog.action";
    public static final String BRIDGE = "catalog.state";
    public static final int SCHEMA_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> ACTION_FIELDS = Set.of("kind", "objectGid", "revision", "expectedRevision",
            "operation", "authoritySite", "authorGid", "parentGid", "numericId", "identityOrigin",
            "canonicalPayload", "contentHash", "operationId", "dotSite", "dotCounter", "context",
            "consentEpochs", "sourceKey", "resolutionProof");
    private static final Set<String> BRIDGE_FIELDS = Set.of("kind", "objectGid", "numericId", "identityOrigin",
            "authoritySite", "parentGid", "consentEpochs", "heads");
    private static final Set<String> HEAD_FIELDS = Set.of("operationId", "sourceSite", "authoritySite", "authorGid",
            "parentGid", "numericId", "identityOrigin", "revision", "expectedRevision", "operation",
            "dotSite", "dotCounter", "context", "operationConsentEpochs", "tombstone", "canonicalPayload",
            "contentHash", "resolutionProof");
    private static final Set<String> RESOLUTION_FIELDS = Set.of("conflictId", "expectedHeads", "decision", "winnerOperationId");
    private static final Set<String> CONSENT_EPOCH_FIELDS = Set.of("subjectGid", "epoch");

    private CatalogEvents() { }

    public record Action(CatalogKind kind, UUID objectGid, long revision, long expectedRevision,
                         CatalogOperation operation, String authoritySite, UUID authorGid, UUID parentGid,
                         long numericId, String identityOrigin,
                         Map<String, Object> canonicalPayload, String contentHash, UUID operationId,
                         String dotSite, long dotCounter, Map<String, Long> context,
                         List<ConsentEpoch> consentEpochs, String sourceKey,
                         ResolutionProof resolutionProof) {
        public Action {
            Objects.requireNonNull(kind, "kind");
            SyncEvent.requireUuid(objectGid, "catalog object GID");
            if (revision < 1 || revision > VersionVector.MAX_COUNTER || expectedRevision < 0
                    || expectedRevision > VersionVector.MAX_COUNTER) {
                throw new IllegalArgumentException("Invalid catalog revision");
            }
            Objects.requireNonNull(operation, "operation");
            validateRevisionPair(operation, revision, expectedRevision);
            SyncEvent.requireName(authoritySite, "catalog authority site", 64);
            if (authorGid != null) SyncEvent.requireUuid(authorGid, "catalog author GID");
            if (parentGid != null) SyncEvent.requireUuid(parentGid, "catalog parent GID");
            if (numericId < 1 || numericId > VersionVector.MAX_COUNTER) invalid();
            SyncEvent.requireName(identityOrigin, "catalog identity origin", 64);
            canonicalPayload = immutableNullableMap(canonicalPayload);
            if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid catalog content hash");
            }
            SyncEvent.requireUuid(operationId, "catalog operation ID");
            SyncEvent.requireName(dotSite, "catalog dot site", 64);
            if (dotCounter < 1 || dotCounter > VersionVector.MAX_COUNTER) {
                throw new IllegalArgumentException("Invalid catalog dot counter");
            }
            context = immutableVector(context);
            if (context.size() > VersionVector.MAX_SITES || context.getOrDefault(dotSite, 0L) + 1 != dotCounter) {
                throw new IllegalArgumentException("Invalid catalog causal dot");
            }
            consentEpochs = immutableConsentEpochs(consentEpochs);
            if (sourceKey != null) SyncEvent.requireName(sourceKey, "catalog source key", 128);
            if (operation == CatalogOperation.DELETE && !canonicalPayload.isEmpty()) {
                throw new IllegalArgumentException("Delete actions cannot carry a replacement payload");
            }
            if (((kind == CatalogKind.HUB || kind == CatalogKind.ACTIVITY) && parentGid != null)
                    || (kind == CatalogKind.FOOD && parentGid == null)) {
                throw new IllegalArgumentException("Invalid catalog parent relationship");
            }
        }
    }

    /** A head is the original immutable revision identity; bridges never mint replacement heads. */
    public record Head(UUID operationId, String sourceSite, String authoritySite, UUID authorGid,
                       UUID parentGid, long numericId, String identityOrigin, long revision,
                       long expectedRevision, CatalogOperation operation,
                       String dotSite, long dotCounter, Map<String, Long> context,
                       List<ConsentEpoch> operationConsentEpochs, boolean tombstone,
                       Map<String, Object> canonicalPayload, String contentHash, ResolutionProof resolutionProof) {
        public Head {
            SyncEvent.requireUuid(operationId, "catalog head operation ID");
            SyncEvent.requireName(sourceSite, "catalog source site", 64);
            SyncEvent.requireName(authoritySite, "catalog authority site", 64);
            if (authorGid != null) SyncEvent.requireUuid(authorGid, "catalog author GID");
            if (parentGid != null) SyncEvent.requireUuid(parentGid, "catalog parent GID");
            if (numericId < 1 || numericId > VersionVector.MAX_COUNTER) invalid();
            SyncEvent.requireName(identityOrigin, "catalog identity origin", 64);
            if (revision < 1 || revision > VersionVector.MAX_COUNTER || expectedRevision < 0
                    || expectedRevision > VersionVector.MAX_COUNTER) invalid();
            Objects.requireNonNull(operation, "catalog head operation");
            validateRevisionPair(operation, revision, expectedRevision);
            SyncEvent.requireName(dotSite, "catalog head dot site", 64);
            if (dotCounter < 1 || dotCounter > VersionVector.MAX_COUNTER) invalid();
            context = immutableVector(context);
            if (context.size() > VersionVector.MAX_SITES || context.getOrDefault(dotSite, 0L) + 1 != dotCounter) invalid();
            operationConsentEpochs = immutableConsentEpochs(operationConsentEpochs);
            canonicalPayload = immutableNullableMap(canonicalPayload);
            if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid catalog head hash");
            }
            if (tombstone != (operation == CatalogOperation.DELETE)
                    || (tombstone && !canonicalPayload.isEmpty())) {
                throw new IllegalArgumentException("Tombstone heads cannot carry a replacement payload");
            }
        }
    }

    public record ResolutionProof(UUID conflictId, List<UUID> expectedHeads,
                                  CatalogDecision decision, UUID winnerOperationId) {
        public ResolutionProof {
            SyncEvent.requireUuid(conflictId, "catalog conflict ID");
            expectedHeads = List.copyOf(expectedHeads);
            if (expectedHeads.size() < 2 || expectedHeads.size() > 32
                    || expectedHeads.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(expectedHeads).size() != expectedHeads.size()) invalid();
            Objects.requireNonNull(decision, "decision");
            if ((decision == CatalogDecision.KEEP) != (winnerOperationId != null)) invalid();
            if (winnerOperationId != null) SyncEvent.requireUuid(winnerOperationId, "winning operation ID");
        }
    }

    public record StateBridge(CatalogKind kind, UUID objectGid, long numericId, String identityOrigin,
                              String authoritySite, UUID parentGid, List<ConsentEpoch> consentEpochs,
                              List<Head> heads) {
        public StateBridge {
            Objects.requireNonNull(kind, "kind");
            SyncEvent.requireUuid(objectGid, "catalog object GID");
            if (numericId < 1 || numericId > VersionVector.MAX_COUNTER) invalid();
            SyncEvent.requireName(identityOrigin, "catalog identity origin", 64);
            SyncEvent.requireName(authoritySite, "catalog authority site", 64);
            if (parentGid != null) SyncEvent.requireUuid(parentGid, "catalog parent GID");
            consentEpochs = immutableConsentEpochs(consentEpochs);
            heads = List.copyOf(heads);
            if (heads.isEmpty() || heads.size() > 32 || heads.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Invalid catalog state bridge heads");
            }
            if (heads.stream().anyMatch(head -> head.numericId() != numericId
                    || !head.identityOrigin().equals(identityOrigin)
                    || !head.authoritySite().equals(authoritySite))) invalid();
        }
    }

    public record ConsentEpoch(UUID subjectGid, long epoch) {
        public ConsentEpoch {
            SyncEvent.requireUuid(subjectGid, "catalog consent subject");
            if (epoch < 0 || epoch > VersionVector.MAX_COUNTER) invalid();
        }
    }

    public static String payload(Action action) {
        return EventCodec.canonicalJson(JSON.writeValueAsString(action));
    }

    public static String bridgePayload(StateBridge bridge) {
        return EventCodec.canonicalJson(JSON.writeValueAsString(bridge));
    }

    public static Action parseAction(String payload) {
        try {
            JsonNode root = JSON.readTree(EventCodec.canonicalJson(payload));
            requireExact(root, ACTION_FIELDS);
            if (!root.get("canonicalPayload").isObject()) invalid();
            if (!root.get("resolutionProof").isNull()) requireExact(root.get("resolutionProof"), RESOLUTION_FIELDS);
            requireConsentEpochArray(root.get("consentEpochs"));
            Action action = JSON.treeToValue(root, Action.class);
            if (action.canonicalPayload().size() > 64) invalid();
            Map<String, Object> checked = action.operation() == CatalogOperation.DELETE
                    ? Map.of() : CatalogPayload.validate(action.kind(), action.canonicalPayload());
            if (!EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(checked)))
                    .equals(action.contentHash())) invalid();
            validateOwnershipFields(action);
            return action;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_CATALOG_EVENT");
        }
    }

    public static StateBridge parseBridge(String payload) {
        try {
            JsonNode root = JSON.readTree(EventCodec.canonicalJson(payload));
            requireExact(root, BRIDGE_FIELDS);
            if (!root.get("heads").isArray()) invalid();
            root.get("heads").forEach(head -> {
                requireExact(head, HEAD_FIELDS);
                requireConsentEpochArray(head.get("operationConsentEpochs"));
                if (!head.get("resolutionProof").isNull()) requireExact(head.get("resolutionProof"), RESOLUTION_FIELDS);
            });
            requireConsentEpochArray(root.get("consentEpochs"));
            StateBridge bridge = JSON.treeToValue(root, StateBridge.class);
            Set<UUID> ids = new HashSet<>();
            for (Head head : bridge.heads()) {
                if (!ids.add(head.operationId()) || !bridge.authoritySite().equals(head.authoritySite())) invalid();
                Map<String, Object> checked = head.tombstone() ? Map.of()
                        : CatalogPayload.validate(bridge.kind(), head.canonicalPayload());
                if (!EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(checked)))
                        .equals(head.contentHash())) invalid();
                validateHeadOwnership(bridge.kind(), head);
            }
            return bridge;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_CATALOG_EVENT");
        }
    }

    public static void validateEvent(SyncEvent event, String eventOrigin, Set<String> allowedSites) {
        validateEvent(event,eventOrigin,allowedSites,false);
    }
    public static void validateEvent(SyncEvent event, String eventOrigin, Set<String> allowedSites, boolean multiwriter) {
        Objects.requireNonNull(event, "event");
        if (!AGGREGATE.equals(event.aggregateType()) || !event.originSite().equals(eventOrigin)
                || !event.eventId().equals(event.aggregateId()) || !event.baseVersion().equals(VersionVector.empty())
                || !event.dependencies().isEmpty()) invalid();
        if (allowedSites != null && !allowedSites.contains(event.originSite())) invalid();
        if (ACTION.equals(event.eventType())) {
            if (event.schemaVersion() != SCHEMA_VERSION) invalid();
            Action action = parseAction(event.payloadJson());
            if (!action.dotSite().equals(event.originSite())
                    || !action.operationId().equals(event.eventId())
                    || !event.baseVersion().equals(VersionVector.empty())
                    || !event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))
                    || (allowedSites != null && (!allowedSites.contains(action.dotSite())
                    || !allowedSites.contains(action.authoritySite())
                    || !allowedSites.contains(action.identityOrigin())
                    || !allowedSites.containsAll(action.context().keySet())))) invalid();
            validateOwnershipFields(action);
            if (!Objects.equals(action.authorGid(), event.actorId())) invalid();
            return;
        }
        if (BRIDGE.equals(event.eventType())) {
            if (event.schemaVersion() != SCHEMA_VERSION || event.actorId() != null) invalid();
            StateBridge bridge = parseBridge(event.payloadJson());
            if(!multiwriter && bridge.heads().stream().anyMatch(h -> !Objects.equals(h.parentGid(),bridge.parentGid()))) invalid();
            if (!event.baseVersion().equals(VersionVector.empty())
                    || !event.nextVersion().equals(VersionVector.empty().increment(event.originSite()))) invalid();
            if (allowedSites != null && (!allowedSites.contains(bridge.identityOrigin())
                    || bridge.heads().stream().anyMatch(head ->
                    !allowedSites.contains(head.sourceSite()) || !allowedSites.contains(head.dotSite())
                            || !allowedSites.contains(head.authoritySite())
                            || !allowedSites.contains(head.identityOrigin())
                            || !allowedSites.containsAll(head.context().keySet())))) invalid();
            return;
        }
        invalid();
    }

    /** Enforces the authority policy shared by transport policy and direct Inbox handlers. */
    public static void validateAuthoritySite(SyncEvent event, String campusSiteId) {
        if (ACTION.equals(event.eventType())) {
            Action action = parseAction(event.payloadJson());
            boolean protectedOperation = authorityRequired(action.kind(), action.operation())
                    || action.resolutionProof() != null;
            if (campusSiteId == null || !campusSiteId.equals(action.authoritySite())) {
                throw new IllegalArgumentException("CATALOG_AUTHORITY_NOT_FROM_CAMPUS");
            }
            if (protectedOperation && !campusSiteId.equals(action.dotSite())) {
                throw new IllegalArgumentException("CATALOG_MANAGEMENT_NOT_FROM_CAMPUS");
            }
            if (action.resolutionProof() != null
                    && (campusSiteId == null || !campusSiteId.equals(action.dotSite()))) {
                throw new IllegalArgumentException("CATALOG_RESOLUTION_NOT_FROM_CAMPUS");
            }
            return;
        }
        if (BRIDGE.equals(event.eventType())) {
            StateBridge bridge = parseBridge(event.payloadJson());
            for (Head head : bridge.heads()) {
                if (!head.sourceSite().equals(head.dotSite())) {
                    throw new IllegalArgumentException("CATALOG_HEAD_SOURCE_DOT_MISMATCH");
                }
                boolean protectedOperation = authorityRequired(bridge.kind(), head.operation())
                        || head.resolutionProof() != null;
                if (campusSiteId == null || !campusSiteId.equals(head.authoritySite())) {
                    throw new IllegalArgumentException("CATALOG_AUTHORITY_NOT_FROM_CAMPUS");
                }
                if (protectedOperation && !campusSiteId.equals(head.dotSite())) {
                    throw new IllegalArgumentException("CATALOG_MANAGEMENT_NOT_FROM_CAMPUS");
                }
                if (head.resolutionProof() != null
                        && (campusSiteId == null || !campusSiteId.equals(head.sourceSite()))) {
                    throw new IllegalArgumentException("CATALOG_RESOLUTION_NOT_FROM_CAMPUS");
                }
            }
        }
    }

    private static boolean authorityRequired(CatalogKind kind, CatalogOperation operation) {
        return kind == CatalogKind.HUB || kind == CatalogKind.SYSTEM_TAG || kind == CatalogKind.ACTIVITY
                || ((kind == CatalogKind.RESTAURANT || kind == CatalogKind.FOOD)
                && operation != CatalogOperation.CREATE);
    }

    private static Map<String, Object> immutableNullableMap(Map<String, Object> value) {
        Objects.requireNonNull(value, "catalog payload");
        return java.util.Collections.unmodifiableMap(new TreeMap<>(value));
    }

    private static Map<String, Long> immutableVector(Map<String, Long> vector) {
        Objects.requireNonNull(vector, "catalog context");
        var sorted = new TreeMap<String, Long>();
        vector.forEach((site, counter) -> {
            SyncEvent.requireName(site, "catalog context site", 64);
            if (counter == null || counter < 1 || counter > VersionVector.MAX_COUNTER) invalid();
            sorted.put(site, counter);
        });
        return java.util.Collections.unmodifiableMap(sorted);
    }

    private static List<ConsentEpoch> immutableConsentEpochs(List<ConsentEpoch> epochs) {
        if (epochs == null || epochs.size() > MAX_CONSENT_SUBJECTS || epochs.stream().anyMatch(Objects::isNull)) invalid();
        var sorted = epochs.stream().sorted(java.util.Comparator.comparing(e -> e.subjectGid().toString())).toList();
        if (sorted.stream().map(ConsentEpoch::subjectGid).distinct().count() != sorted.size()) invalid();
        return List.copyOf(sorted);
    }

    private static void requireExact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || !expected.equals(Set.copyOf(node.propertyNames()))) invalid();
    }

    private static void requireConsentEpochArray(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > MAX_CONSENT_SUBJECTS) invalid();
        node.forEach(epoch -> requireExact(epoch, CONSENT_EPOCH_FIELDS));
    }

    /** Bound resolution events while allowing all candidate authors and parent authors to be gated. */
    public static final int MAX_CONSENT_SUBJECTS = 64;

    private static void validateOwnershipFields(Action action) {
        if (action.kind() == CatalogKind.FOOD && action.operation() != CatalogOperation.DELETE) {
            if (action.parentGid() == null
                    || !action.parentGid().toString().equals(action.canonicalPayload().get("restaurantGid"))) invalid();
        }
        if (action.kind() == CatalogKind.RESTAURANT && action.operation() != CatalogOperation.DELETE) {
            Object hub = action.canonicalPayload().get("hubGid");
            if (!Objects.equals(action.parentGid() == null ? null : action.parentGid().toString(), hub)) invalid();
        }
        if (action.kind() == CatalogKind.CUSTOM_TAG && action.operation() != CatalogOperation.DELETE) {
            if (action.authorGid() == null
                    || !action.authorGid().toString().equals(action.canonicalPayload().get("ownerGid"))) invalid();
        }
        if ((action.kind() == CatalogKind.RESTAURANT || action.kind() == CatalogKind.FOOD)
                && action.authorGid() == null) invalid();
        if ((action.kind() == CatalogKind.HUB || action.kind() == CatalogKind.SYSTEM_TAG
                || action.kind() == CatalogKind.ACTIVITY)
                && (action.authorGid() != null || !action.consentEpochs().isEmpty())) invalid();
        if ((action.kind() == CatalogKind.RESTAURANT || action.kind() == CatalogKind.CUSTOM_TAG)
                && action.consentEpochs().stream().noneMatch(epoch -> epoch.subjectGid().equals(action.authorGid()))) invalid();
        if (action.kind() == CatalogKind.FOOD
                && action.consentEpochs().stream().noneMatch(epoch -> epoch.subjectGid().equals(action.authorGid()))) invalid();
        validateEpochCardinality(action.kind(), action.authorGid(), action.consentEpochs(), action.resolutionProof());
    }

    private static void validateHeadOwnership(CatalogKind kind, Head head) {
        if (kind == CatalogKind.FOOD && head.parentGid() == null) invalid();
        if (kind == CatalogKind.CUSTOM_TAG && head.authorGid() == null) invalid();
        if ((kind == CatalogKind.RESTAURANT || kind == CatalogKind.FOOD) && head.authorGid() == null) invalid();
        if ((kind == CatalogKind.HUB || kind == CatalogKind.SYSTEM_TAG || kind == CatalogKind.ACTIVITY)
                && head.authorGid() != null) invalid();
        if ((kind == CatalogKind.RESTAURANT || kind == CatalogKind.FOOD || kind == CatalogKind.CUSTOM_TAG)
                && head.operationConsentEpochs().stream().noneMatch(epoch -> epoch.subjectGid().equals(head.authorGid()))) invalid();
        if (kind == CatalogKind.FOOD && !head.tombstone()
                && !head.parentGid().toString().equals(head.canonicalPayload().get("restaurantGid"))) invalid();
        if (kind == CatalogKind.RESTAURANT && !head.tombstone()) {
            Object hub = head.canonicalPayload().get("hubGid");
            if (!Objects.equals(head.parentGid() == null ? null : head.parentGid().toString(), hub)) invalid();
        }
        if (kind == CatalogKind.CUSTOM_TAG && !head.tombstone()
                && !head.authorGid().toString().equals(head.canonicalPayload().get("ownerGid"))) invalid();
        validateEpochCardinality(kind, head.authorGid(), head.operationConsentEpochs(), head.resolutionProof());
    }

    private static void validateEpochCardinality(CatalogKind kind, UUID author,
                                                  List<ConsentEpoch> epochs, ResolutionProof proof) {
        boolean personal = kind == CatalogKind.RESTAURANT || kind == CatalogKind.FOOD
                || kind == CatalogKind.CUSTOM_TAG;
        if (!personal) {
            if (!epochs.isEmpty()) invalid();
            return;
        }
        if (author == null || epochs.stream().noneMatch(epoch -> epoch.subjectGid().equals(author))) invalid();
        if (proof == null && (kind != CatalogKind.FOOD && epochs.size() != 1
                || kind == CatalogKind.FOOD && (epochs.isEmpty() || epochs.size() > 2))) invalid();
    }

    private static void validateRevisionPair(CatalogOperation operation, long revision, long expectedRevision) {
        if (operation == CatalogOperation.CREATE) {
            if (revision != 1 || expectedRevision != 0) invalid();
        } else if (revision != expectedRevision + 1) {
            invalid();
        }
    }

    private static void invalid() {
        throw new IllegalArgumentException("INVALID_CATALOG_EVENT");
    }
}
