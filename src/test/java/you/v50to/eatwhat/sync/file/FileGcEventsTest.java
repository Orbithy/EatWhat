package you.v50to.eatwhat.sync.file;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileGcEventsTest {
    final NodeIdentity c=new NodeIdentity("campus",UUID.randomUUID()),p=new NodeIdentity("external",UUID.randomUUID());
    FileGcEvents.Decision decision(String phase) {return new FileGcEvents.Decision(UUID.randomUUID(),UUID.randomUUID(),1,"a".repeat(64),12,c,p,phase);}
    @Test void phasesBindOperationRevisionAndBothInstallationsWithoutUserOrObjectKey() {
        var d=decision("PREPARE");var codec=new EventCodec();Set<UUID> ids=new HashSet<>();
        for(String phase:FileGcEvents.PHASES) {
            var next=d.withPhase(phase);var sender=phase.equals("PREPARED")?p:c;
            var e=FileGcEvents.envelope(next,sender);FileGcEvents.validate(codec.decode(codec.encode(e).json()),sender);
            assertEquals(next,FileGcEvents.decision(e.payloadJson()));assertTrue(ids.add(e.eventId()));
            assertFalse(e.payloadJson().contains("logicalKey"));assertFalse(e.payloadJson().contains("owner"));assertNull(e.actorId());
        }
        assertTrue(SyncEvent.validNumericIdentity(FileGcEvents.TYPE,0));assertFalse(SyncEvent.validNumericIdentity(FileGcEvents.TYPE,1));
    }
    @Test void participantCannotPrepareOrCommitAndCoordinatorCannotClaimPeerPrepared() {
        assertThrows(IllegalArgumentException.class,()->FileGcEvents.validate(FileGcEvents.envelope(decision("PREPARE"),p),p));
        assertThrows(IllegalArgumentException.class,()->FileGcEvents.validate(FileGcEvents.envelope(decision("COMMIT"),p),p));
        assertThrows(IllegalArgumentException.class,()->FileGcEvents.validate(FileGcEvents.envelope(decision("PREPARED"),c),c));
        assertThrows(IllegalArgumentException.class,()->FileGcEvents.validate(FileGcEvents.envelope(decision("DELETED"),c),new NodeIdentity("campus",UUID.randomUUID())));
    }
    @Test void unknownFieldsCoercionAndArbitraryStorageKeysAreRejected() {
        String json=FileEvents.payload(decision("PREPARE"));
        assertThrows(IllegalArgumentException.class,()->FileGcEvents.decision(json.replace("\"revision\":1","\"revision\":\"1\"")));
        assertThrows(IllegalArgumentException.class,()->FileGcEvents.decision(json.replace("\"revision\":1","\"revision\":1,\"objectKey\":\"verified/arbitrary\"")));
        assertThrows(IllegalArgumentException.class,()->FileGcEvents.decision(json.replace("PREPARE","ABORT")));
    }
    @Test void cleanupPolicyDoesNotDependOnLiveOwnerConsent() {
        var e=FileGcEvents.envelope(decision("PREPARE"),c);var policy=new FileEventPolicy(null);
        policy.validate(e,c);assertTrue(policy.aggregateTypes().contains(FileGcEvents.TYPE));
        assertEquals(EventPolicy.OutboundDecision.ALLOW,policy.outbound(e));
    }
}
