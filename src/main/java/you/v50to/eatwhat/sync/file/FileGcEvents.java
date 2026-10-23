package you.v50to.eatwhat.sync.file;

import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.*;

/** Minimal cleanup messages carry neither owner identity nor a caller-selected storage key. */
public final class FileGcEvents {
    public static final String TYPE="file.gc";
    public static final List<String> PHASES=List.of("PREPARE","PREPARED","COMMIT","DELETED");
    private FileGcEvents() { }
    public record Decision(UUID operationId,UUID fileId,int revision,String sha256,long sizeBytes,
                           NodeIdentity coordinator,NodeIdentity participant,String phase) {
        public Decision {
            SyncEvent.requireUuid(operationId,"operation"); SyncEvent.requireUuid(fileId,"file");
            Objects.requireNonNull(coordinator); Objects.requireNonNull(participant);
            if(revision<1||sha256==null||!sha256.matches("[0-9a-f]{64}")||sizeBytes<1
                    ||sizeBytes>5L*1024*1024*1024||!PHASES.contains(phase)
                    ||coordinator.siteId().equals(participant.siteId())
                    ||coordinator.instanceId().equals(participant.instanceId())) throw new IllegalArgumentException("INVALID_GC_DECISION");
        }
        public Decision withPhase(String next) { return new Decision(operationId,fileId,revision,sha256,sizeBytes,coordinator,participant,next); }
    }
    public static Decision decision(String payload) { return FileEvents.parse(payload,Decision.class); }
    public static SyncEvent envelope(Decision d,NodeIdentity origin) {
        UUID id=FileEvents.id(TYPE+":"+d.phase(),origin.siteId(),origin.instanceId(),d.operationId(),d.revision(),1);
        return new SyncEvent(id,origin.siteId(),origin.instanceId(),TYPE,id,0,TYPE,1,VersionVector.empty(),
                VersionVector.empty().increment(origin.siteId()),List.of(),null,"file-gc:"+id,id,FileEvents.payload(d));
    }
    public static void validate(SyncEvent e,NodeIdentity origin) {
        if(e==null||!TYPE.equals(e.aggregateType())||!TYPE.equals(e.eventType())||e.schemaVersion()!=1
                ||e.numericId()!=0||!e.originSite().equals(origin.siteId())||!e.originInstanceId().equals(origin.instanceId())
                ||!e.eventId().equals(e.aggregateId())||e.actorId()!=null||!e.dependencies().isEmpty()
                ||!e.baseVersion().equals(VersionVector.empty())||!e.nextVersion().equals(VersionVector.empty().increment(origin.siteId())))
            throw new IllegalArgumentException("INVALID_GC_EVENT");
        Decision d=decision(e.payloadJson());
        if(!origin.equals(d.coordinator())&&!origin.equals(d.participant())) throw new IllegalArgumentException("INVALID_GC_ORIGIN");
        if((d.phase().equals("PREPARE")||d.phase().equals("COMMIT"))&&!origin.equals(d.coordinator())
                ||d.phase().equals("PREPARED")&&!origin.equals(d.participant())
                ||!envelope(d,origin).eventId().equals(e.eventId())) throw new IllegalArgumentException("INVALID_GC_PHASE");
    }
}
