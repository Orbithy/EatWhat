package you.v50to.eatwhat.sync.preferences;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PreferenceEventsTest {
    @Test void settingsAndClearRoundTripWithoutRenderingPrivateValues(){
        for(var liked:List.of(List.of("spicy"),List.<String>of())){
            var op=new PreferenceEvents.Snapshot(UUID.randomUUID(),1,1,liked,List.of(),1750000000000000L);
            UUID id=UUID.randomUUID(),instance=UUID.randomUUID();var event=new SyncEvent(id,"campus",instance,PreferenceEvents.AGGREGATE,id,1,PreferenceEvents.EVENT,1,VersionVector.empty(),VersionVector.empty().increment("campus"),op.dependencies(),op.subject(),"preferences:"+id,id,PreferenceEvents.payload(op));
            var policy=new PreferenceEvents(null);policy.validate(event,new NodeIdentity("campus",instance));
            assertEquals(op,PreferenceEvents.parse(event.payloadJson()));assertEquals("PreferenceSnapshot[redacted]",op.toString());
            assertEquals(EventPolicy.OutboundDecision.WAIT,policy.outbound(event));
            assertThrows(IllegalArgumentException.class,()->policy.validate(event,new NodeIdentity("other",instance)));
        }
    }
    @Test void rejectsUnknownFieldsCoercionOverlapUnnormalizedAndUnboundedValues(){
        var op=new PreferenceEvents.Snapshot(UUID.randomUUID(),1,1,List.of("spicy"),List.of("sweet"),1750000000000000L);String payload=PreferenceEvents.payload(op);
        for(var bad:List.of(payload.replaceFirst("\\{","{\"profile\":\"private\","),payload.replace("\"clock\":1","\"clock\":\"1\""),
                payload.replace("\"epoch\":1","\"epoch\":0"),payload.replace("\"sweet\"","\"spicy\""),payload.replace("\"spicy\"","\"Spicy\""),
                payload.replace("\"spicy\"","null"),payload.replace("1750000000000000","1.5"),payload.replace("\"spicy\"","\""+"x".repeat(33)+"\""))){
            var failure=assertThrows(IllegalArgumentException.class,()->PreferenceEvents.parse(bad));assertEquals("INVALID_PREFERENCE_EVENT",failure.getMessage());assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class,()->PreferenceEvents.validateTags(List.of("z","a")));
        assertThrows(IllegalArgumentException.class,()->PreferenceEvents.validateTags(Collections.nCopies(21,"spicy")));
    }
}
