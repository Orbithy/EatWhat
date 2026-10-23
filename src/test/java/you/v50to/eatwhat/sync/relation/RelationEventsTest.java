package you.v50to.eatwhat.sync.relation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RelationEventsTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final UUID ACTOR=UUID.randomUUID(),TARGET=UUID.randomUUID();
    static RelationEvents.Operation operation(){return new RelationEvents.Operation(RelationKind.follow,ACTOR,TARGET,TARGET,null,1,1,1000000001L,UUID.randomUUID(),"campus",Map.of(),"campus",1,true,null);}
    static SyncEvent event(RelationEvents.Operation op){UUID id=UUID.randomUUID();return new SyncEvent(id,"campus",UUID.randomUUID(),RelationEvents.AGGREGATE,id,1,RelationEvents.EVENT,1,VersionVector.empty(),VersionVector.empty().increment("campus"),op.dependencies(),ACTOR,"op:"+id,id,RelationEvents.payload(op));}
    @Test void roundTripAndTransportBindActorDependenciesAndOrigin(){var op=operation();var e=event(op);assertEquals(op,RelationEvents.parse(e.payloadJson()));new RelationEvents().validate(e,new NodeIdentity(e.originSite(),e.originInstanceId()));assertThrows(IllegalArgumentException.class,()->new RelationEvents().validate(e,new NodeIdentity("other",e.originInstanceId())));}
    @Test void positiveLegacyNumericIdsRemainWireRepresentableAndAreValidatedAgainstDatabaseIdentity(){
        var op=new RelationEvents.Operation(RelationKind.follow,ACTOR,TARGET,TARGET,null,1,1,3L,UUID.randomUUID(),"campus",Map.of(),"campus",1,true,null);
        assertEquals(3L,RelationEvents.parse(RelationEvents.payload(op)).rowId());
    }
    @ParameterizedTest @ValueSource(strings={"extra","actorEpoch","context","adding","rowId","notification","dotCounter","tag"})
    void malformedPayloadsAreRejected(String field){var n=JSON.readTree(RelationEvents.payload(operation())).deepCopy();
        var o=(tools.jackson.databind.node.ObjectNode)n;
        switch(field){case "extra"->o.put("passwordHash","forbidden");case "actorEpoch"->o.put(field,"1");case "context"->o.put(field,"{}");case "adding"->o.put(field,"true");case "rowId"->o.put(field,0);case "notification"->o.set(field,JSON.createObjectNode().put("globalId",UUID.randomUUID().toString()));case "dotCounter"->o.put(field,2);case "tag"->o.put(field,UUID.randomUUID().toString());}
        assertThrows(IllegalArgumentException.class,()->RelationEvents.parse(o.toString()));}
    @Test void identityKeyIgnoresPhysicalRowIdsAndPartitionsAuthorizationEpochs(){var op=operation();var alias=new RelationEvents.Operation(op.kind(),op.actor(),op.target(),op.owner(),null,1,1,1000000002L,UUID.randomUUID(),"external",Map.of(),"external",1,true,null);assertEquals(op.relationId(),alias.relationId());var newer=new RelationEvents.Operation(op.kind(),op.actor(),op.target(),op.owner(),null,2,1,op.rowId(),op.rowGid(),op.rowOrigin(),Map.of(),"campus",1,true,null);assertNotEquals(op.relationId(),newer.relationId());}
}
