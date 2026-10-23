package you.v50to.eatwhat.sync.file;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.service.storage.replication.FileReplicaCatalog.PeerRevision;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileEventsTest {
    final NodeIdentity node=new NodeIdentity("campus",UUID.randomUUID());
    final UUID file=UUID.randomUUID(),owner=UUID.randomUUID();
    PeerRevision metadata() { return new PeerRevision(file,1,owner,"avatar","avatar/1/a.png","verified/"+file,"a".repeat(64),8,"image/png"); }
    @Test void zeroIsReservedForUuidFileFamiliesAndBusinessNumericRulesRemainStrict() {
        for(String type:FileEvents.TYPES) { assertTrue(SyncEvent.validNumericIdentity(type,0)); assertFalse(SyncEvent.validNumericIdentity(type,1)); }
        for(String type:List.of("users","synthetic.counter","file.unknown")) { assertFalse(SyncEvent.validNumericIdentity(type,0)); assertTrue(SyncEvent.validNumericIdentity(type,1)); }
        assertFalse(SyncEvent.validNumericIdentity("users",-1));
    }
    @Test void metadataRoundTripsThroughExistingEnvelopeAndBindsOwnerOriginAndEpoch() {
        var m=new FileEvents.Metadata(metadata(),1);
        var e=FileEvents.event(FileEvents.METADATA,node,file,1,1,owner,m);
        var codec=new EventCodec(); var decoded=codec.decode(codec.encode(e).json());
        FileEvents.validate(decoded,node); assertEquals(e.eventId(),decoded.eventId());
        assertEquals(m,FileEvents.metadata(decoded.payloadJson()));
        assertThrows(IllegalArgumentException.class,()->FileEvents.validate(e,new NodeIdentity("other",node.instanceId())));
        var wrongEpoch=FileEvents.event(FileEvents.METADATA,node,file,1,2,owner,m);
        assertThrows(IllegalArgumentException.class,()->FileEvents.validate(wrongEpoch,node));
    }
    @Test void referencesValidateCountsAndDoNotCarryOwnerOrBusinessKeys() {
        var r=new FileEvents.References(file,1,7,0,0,true);
        var e=FileEvents.event(FileEvents.REFERENCES,node,file,1,7,null,r);
        FileEvents.validate(e,node); assertEquals(r,FileEvents.references(e.payloadJson()));
        assertFalse(e.payloadJson().contains("logicalKey")); assertNull(e.actorId());
        assertThrows(IllegalArgumentException.class,()->new FileEvents.References(file,1,7,1,2,false));
        assertThrows(IllegalArgumentException.class,()->new FileEvents.References(file,1,7,1,0,true));
        assertThrows(IllegalArgumentException.class,()->FileEvents.references(e.payloadJson().replace("\"generation\":7","\"generation\":7.5")));
    }
    @Test void malformedMetadataAndUnexpectedFieldsAreRejected() {
        var m=new FileEvents.Metadata(metadata(),1); String json=FileEvents.payload(m);
        assertThrows(IllegalArgumentException.class,()->FileEvents.metadata(json.replace("avatar/1/a.png","avatar/../a.png")));
        assertThrows(IllegalArgumentException.class,()->FileEvents.metadata(json.replace("\"epoch\":1","\"epoch\":1,\"credential\":\"no\"")));
        assertThrows(IllegalArgumentException.class,()->FileEvents.metadata(json.replace("image/png","text/html")));
        assertThrows(IllegalArgumentException.class,()->FileEvents.metadata(json.replace("\"epoch\":1","\"epoch\":\"1\"")));
    }
    @Test void replicaAttestationsAndApplicationReceiptsHaveDistinctBoundIdentities() {
        var r=new FileEvents.Replica(file,1,owner,2,7,"b".repeat(64),32,true,true);
        FileEvents.validate(FileEvents.event(FileEvents.REPLICA,node,file,1,7,owner,r),node);
        UUID prior=UUID.randomUUID(); var a=new FileEvents.Applied(prior,"c".repeat(64),file,1,"APPLIED");
        var receipt=FileEvents.event(FileEvents.RECEIPT,node,prior,1,1,null,a); FileEvents.validate(receipt,node);
        assertEquals(a,FileEvents.applied(receipt.payloadJson()));
        assertThrows(IllegalArgumentException.class,()->new FileEvents.Applied(prior,"bad",file,1,"APPLIED"));
    }
}
