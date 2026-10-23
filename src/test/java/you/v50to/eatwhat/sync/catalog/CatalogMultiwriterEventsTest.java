package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogMultiwriterEventsTest {
    @Test void explicitActorIsPreservedWithoutChangingTheLegacyImmutableAction() {
        UUID author=UUID.randomUUID(),operation=UUID.randomUUID();
        var action=new CatalogEvents.Action(CatalogKind.FOOD,UUID.randomUUID(),2,1,CatalogOperation.DELETE,
                "campus",author,UUID.randomUUID(),1000000002L,"external",Map.of(),EventCodec.sha256("{}"),operation,
                "external",2,Map.of("external",1L),List.of(new CatalogEvents.ConsentEpoch(author,1)),null,null);
        var proof=new CatalogActorVerifier.Evidence(UUID.randomUUID(),Map.of("role","admin","banned",false));
        var event=new SyncEvent(operation,"external",UUID.randomUUID(),CatalogEvents.AGGREGATE,operation,1000000004L,
                CatalogEvents.ACTION,2,VersionVector.empty(),VersionVector.empty().increment("external"),List.of(),author,
                "catalog:"+operation,operation,CatalogMultiwriterEvents.actionPayload(action,proof));
        assertEquals(action,CatalogEvents.parseAction(CatalogMultiwriterEvents.legacyShape(event).payloadJson()));
        assertEquals(proof,CatalogMultiwriterEvents.evidence(event).get(operation));
        assertNotEquals(author,proof.actorGid());
        CatalogEvents.validateEvent(CatalogMultiwriterEvents.legacyShape(event),"external",Set.of("campus","external"));
        assertThrows(IllegalArgumentException.class,()->CatalogEvents.validateAuthoritySite(CatalogMultiwriterEvents.legacyShape(event),"campus"));
    }
    @Test void newVersionCannotOmitActorEvidenceOrHideUnknownActionFields() {
        UUID id=UUID.randomUUID();
        var event=new SyncEvent(id,"external",UUID.randomUUID(),CatalogEvents.AGGREGATE,id,1000000004L,
                CatalogEvents.ACTION,2,VersionVector.empty(),VersionVector.empty().increment("external"),List.of(),null,
                "catalog:"+id,id,"{}");
        assertThrows(IllegalArgumentException.class,()->CatalogMultiwriterEvents.legacyShape(event));
        assertThrows(IllegalArgumentException.class,()->CatalogMultiwriterEvents.evidence(event));
    }
}
