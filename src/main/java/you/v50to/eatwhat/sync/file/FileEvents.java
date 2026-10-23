package you.v50to.eatwhat.sync.file;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.service.storage.replication.FileReplicaCatalog.PeerRevision;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** UUID-only, independently applicable snapshots. Ordering is a per-installation durable generation. */
public final class FileEvents {
    public static final String METADATA="file.metadata", REFERENCES="file.references", RECEIPT="file.receipt", REPLICA="file.replica";
    public static final Set<String> TYPES=Set.of(METADATA,REFERENCES,RECEIPT,REPLICA);
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private FileEvents() { }
    public record Metadata(PeerRevision file, long epoch) {
        public Metadata {
            Objects.requireNonNull(file); SyncEvent.requireUuid(file.fileId(),"file");
            SyncEvent.requireUuid(file.ownerGid(),"owner");
            if(file.revision()<1||epoch<1||file.sizeBytes()<1||file.sizeBytes()>5L*1024*1024*1024
                    ||!Set.of("avatar","activity","foods","restaurants").contains(file.biz())
                    ||file.logicalKey()==null||file.logicalKey().length()>512
                    ||!file.logicalKey().startsWith(file.biz()+"/")||!safeKey(file.logicalKey())
                    ||file.finalKey()==null||!file.finalKey().matches("verified/[a-zA-Z0-9-]{16,64}")
                    ||file.sha256()==null||!file.sha256().matches("[0-9a-f]{64}")
                    ||!Set.of("image/jpeg","image/png","image/gif","image/webp").contains(file.contentType()))
                throw new IllegalArgumentException("INVALID_FILE_METADATA");
        }
    }
    public record References(UUID fileId,int revision,long generation,long liveCount,long publicCount,boolean tombstoned) {
        public References {
            SyncEvent.requireUuid(fileId,"file");
            if(revision<1||generation<1||generation>VersionVector.MAX_COUNTER||liveCount<0
                    ||liveCount>VersionVector.MAX_COUNTER||publicCount<0||publicCount>liveCount||tombstoned!=(liveCount==0))
                throw new IllegalArgumentException("INVALID_FILE_REFERENCE_STATE");
        }
    }
    public record Replica(UUID fileId,int revision,UUID ownerGid,long epoch,long generation,String sha256,long sizeBytes,
                          boolean localVerified,boolean peerVerified) {
        public Replica {
            SyncEvent.requireUuid(fileId,"file"); SyncEvent.requireUuid(ownerGid,"owner");
            if(revision<1||epoch<1||generation<1||sha256==null||!sha256.matches("[0-9a-f]{64}")||sizeBytes<1)
                throw new IllegalArgumentException("INVALID_FILE_REPLICA");
        }
    }
    public static Replica replica(String payload) { return parse(payload,Replica.class); }
    public record Applied(UUID eventId,String envelopeHash,UUID fileId,int revision,String outcome) {
        public Applied {
            SyncEvent.requireUuid(eventId,"event"); SyncEvent.requireUuid(fileId,"file");
            if(revision<1||envelopeHash==null||!envelopeHash.matches("[0-9a-f]{64}")
                    ||!Set.of("APPLIED","IGNORED").contains(outcome)) throw new IllegalArgumentException("INVALID_FILE_RECEIPT");
        }
    }
    public static boolean safeKey(String key) {
        return key.matches("[A-Za-z0-9_./-]+")&&!key.contains("..")&&!key.contains("//")&&!key.endsWith("/");
    }
    public static Metadata metadata(String payload) { return parse(payload,Metadata.class); }
    public static References references(String payload) { return parse(payload,References.class); }
    public static Applied applied(String payload) { return parse(payload,Applied.class); }
    static <T> T parse(String payload,Class<T> type) {
        try { return JSON.readValue(EventCodec.canonicalJson(payload),type); }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("INVALID_FILE_PAYLOAD",invalid); }
    }
    public static String payload(Object value) { return EventCodec.canonicalJson(JSON.writeValueAsString(value)); }
    public static UUID id(String type,String site,UUID instance,UUID file,int revision,long generation) {
        return UUID.nameUUIDFromBytes((type+":"+site+":"+instance+":"+file+":"+revision+":"+generation).getBytes(StandardCharsets.UTF_8));
    }
    public static SyncEvent event(String type,NodeIdentity origin,UUID file,int revision,long generation,UUID actor,Object payload) {
        UUID id=id(payload instanceof Replica r?type+":"+r.epoch():type,origin.siteId(),origin.instanceId(),file,revision,generation);
        return new SyncEvent(id,origin.siteId(),origin.instanceId(),type,id,0,type,1,VersionVector.empty(),
                VersionVector.empty().increment(origin.siteId()),actor==null?List.of():List.of(new SyncEvent.Dependency("users",actor)),
                actor,"file:"+id,id,payload(payload));
    }
    public static void validate(SyncEvent e,NodeIdentity origin) {
        if(e==null||!TYPES.contains(e.aggregateType())||!e.eventType().equals(e.aggregateType())||e.schemaVersion()!=1
                ||e.numericId()!=0||!e.originSite().equals(origin.siteId())||!e.originInstanceId().equals(origin.instanceId())
                ||!e.eventId().equals(e.aggregateId())||!e.baseVersion().equals(VersionVector.empty())
                ||!e.nextVersion().equals(VersionVector.empty().increment(origin.siteId()))) throw new IllegalArgumentException("INVALID_FILE_EVENT");
        UUID expected;
        if(METADATA.equals(e.aggregateType())) {
            Metadata m=metadata(e.payloadJson());
            if(!m.file.ownerGid().equals(e.actorId())||!e.dependencies().equals(List.of(new SyncEvent.Dependency("users",e.actorId()))))
                throw new IllegalArgumentException("INVALID_FILE_OWNER");
            expected=id(METADATA,e.originSite(),e.originInstanceId(),m.file.fileId(),m.file.revision(),m.epoch);
        } else if(REPLICA.equals(e.aggregateType())) {
            Replica r=replica(e.payloadJson());
            if(!r.ownerGid.equals(e.actorId())||!e.dependencies().equals(List.of(new SyncEvent.Dependency("users",e.actorId()))))
                throw new IllegalArgumentException("INVALID_FILE_OWNER");
            expected=id(REPLICA+":"+r.epoch,e.originSite(),e.originInstanceId(),r.fileId,r.revision,r.generation);
        } else {
            if(e.actorId()!=null||!e.dependencies().isEmpty()) throw new IllegalArgumentException("INVALID_FILE_EVENT");
            if(REFERENCES.equals(e.aggregateType())) {
                References r=references(e.payloadJson()); expected=id(REFERENCES,e.originSite(),e.originInstanceId(),r.fileId,r.revision,r.generation);
            } else {
                Applied a=applied(e.payloadJson()); expected=id(RECEIPT,e.originSite(),e.originInstanceId(),a.eventId,1,1);
            }
        }
        if(!expected.equals(e.eventId())) throw new IllegalArgumentException("INVALID_FILE_EVENT_ID");
    }
}
