package you.v50to.eatwhat.sync.catalog;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import java.util.*;

/** V2 retains the action fields used by SQL proof checks and adds explicit actor evidence. */
public final class CatalogMultiwriterEvents {
    public static final int SCHEMA_VERSION=2;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private CatalogMultiwriterEvents() { }
    public static String actionPayload(CatalogEvents.Action action,CatalogActorVerifier.Evidence evidence) {
        Map<String,Object> payload=object(CatalogEvents.payload(action));
        payload.put("actorEvidence",Objects.requireNonNull(evidence));
        return EventCodec.canonicalJson(JSON.writeValueAsString(payload));
    }
    public static String bridgePayload(CatalogEvents.StateBridge bridge,Map<UUID,CatalogActorVerifier.Evidence> evidence) {
        Map<String,Object> payload=object(CatalogEvents.bridgePayload(bridge));
        Map<String,Object> proofs=new TreeMap<>();
        evidence.forEach((id,proof)->proofs.put(id.toString(),proof));
        if(!bridge.heads().stream().map(CatalogEvents.Head::operationId).collect(java.util.stream.Collectors.toSet()).containsAll(evidence.keySet()))
            throw new IllegalArgumentException("CATALOG_EXTRA_ACTOR_EVIDENCE");
        payload.put("actorEvidence",proofs);
        return EventCodec.canonicalJson(JSON.writeValueAsString(payload));
    }
    public static SyncEvent legacyShape(SyncEvent event) {
        if(event.schemaVersion()!=2) throw new IllegalArgumentException("CATALOG_V2_REQUIRED");
        Map<String,Object> payload=object(event.payloadJson());
        if(payload.remove("actorEvidence")==null) throw new IllegalArgumentException("CATALOG_ACTOR_EVIDENCE_REQUIRED");
        return new SyncEvent(event.eventId(),event.originSite(),event.originInstanceId(),event.aggregateType(),event.aggregateId(),
                event.numericId(),event.eventType(),1,event.baseVersion(),event.nextVersion(),event.dependencies(),event.actorId(),
                event.commandKey(),event.correlationId(),EventCodec.canonicalJson(JSON.writeValueAsString(payload)));
    }
    public static Map<UUID,CatalogActorVerifier.Evidence> evidence(SyncEvent event) {
        Map<String,Object> payload=object(event.payloadJson());
        Object raw=payload.get("actorEvidence");
        if(raw==null) throw new IllegalArgumentException("CATALOG_ACTOR_EVIDENCE_REQUIRED");
        Map<UUID,CatalogActorVerifier.Evidence> proofs=new HashMap<>();
        if(CatalogEvents.ACTION.equals(event.eventType())) {
            proofs.put(event.eventId(),JSON.convertValue(raw,CatalogActorVerifier.Evidence.class));
        } else if(CatalogEvents.BRIDGE.equals(event.eventType())) {
            if(!(raw instanceof Map<?,?> map)) throw new IllegalArgumentException("CATALOG_ACTOR_EVIDENCE_INVALID");
            map.forEach((id,proof)->proofs.put(UUID.fromString(id.toString()),JSON.convertValue(proof,CatalogActorVerifier.Evidence.class)));
            var bridge=CatalogEvents.parseBridge(legacyShape(event).payloadJson());
            if(!bridge.heads().stream().map(CatalogEvents.Head::operationId).collect(java.util.stream.Collectors.toSet()).containsAll(proofs.keySet()))
                throw new IllegalArgumentException("CATALOG_EXTRA_ACTOR_EVIDENCE");
        } else throw new IllegalArgumentException("CATALOG_V2_EVENT_TYPE_INVALID");
        return Map.copyOf(proofs);
    }
    private static Map<String,Object> object(String value) {
        Map<String,Object> parsed=JSON.readValue(value,JSON.getTypeFactory().constructMapType(Map.class,String.class,Object.class));
        return new TreeMap<>(parsed);
    }
}
