package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogDecisionCausalityTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private CatalogDecisionCausality.Decision decision(String site, UUID winner) {
        return new CatalogDecisionCausality.Decision(UUID.randomUUID(), site, 1, Map.of(),
                List.of(a,b), CatalogDecision.KEEP, winner);
    }
    @Test void independentOppositeDecisionsRemainConflictInEveryDeliveryOrder() {
        var x = decision("campus", a); var y = decision("external", b);
        var one = CatalogDecisionCausality.merge(List.of(x), List.of(y));
        var two = CatalogDecisionCausality.merge(List.of(y), List.of(x));
        assertEquals(one, two);
        assertEquals(CatalogDecisionCausality.Status.CONFLICT, CatalogDecisionCausality.evaluate(one).status());
        assertEquals(one, CatalogDecisionCausality.merge(one,List.of(x,y,x)));
    }
    @Test void sameChoiceConvergesWithoutDiscardingIndependentAuditHeads() {
        var heads = CatalogDecisionCausality.merge(List.of(decision("campus",a)), List.of(decision("external",a)));
        assertEquals(2,heads.size());
        assertEquals(CatalogDecisionCausality.Status.RESOLVED, CatalogDecisionCausality.evaluate(heads).status());
        assertEquals(a, CatalogDecisionCausality.evaluate(heads).winnerOperationId());
    }
    @Test void laterDecisionMustObserveBothSitesAndOldReplayCannotUndoIt() {
        var x=decision("campus",a); var y=decision("external",b);
        var resolved=new CatalogDecisionCausality.Decision(UUID.randomUUID(),"campus",2,
                Map.of("campus",1L,"external",1L),List.of(a,b),CatalogDecision.KEEP,b);
        assertEquals(List.of(resolved),CatalogDecisionCausality.merge(List.of(x,y),List.of(resolved)));
        assertEquals(List.of(resolved),CatalogDecisionCausality.merge(List.of(resolved),List.of(y,x)));
    }
    @Test void conflictingPayloadAtSameOperationOrDotIsRejected() {
        var x=decision("campus",a);
        var y=new CatalogDecisionCausality.Decision(x.operationId(),"campus",1,Map.of(),List.of(a,b),CatalogDecision.KEEP,b);
        assertThrows(IllegalArgumentException.class,()->CatalogDecisionCausality.merge(List.of(x),List.of(y)));
        var z=decision("campus",b);
        assertThrows(IllegalArgumentException.class,()->CatalogDecisionCausality.merge(List.of(x),List.of(z)));
    }
    @Test void differentCandidateSetsCannotPretendToBeTheSameDecision() {
        var x=decision("campus",a);
        var y=new CatalogDecisionCausality.Decision(UUID.randomUUID(),"external",1,Map.of(),List.of(a,b,UUID.randomUUID()),CatalogDecision.KEEP,a);
        assertEquals(CatalogDecisionCausality.Status.CONFLICT,CatalogDecisionCausality.evaluate(List.of(x,y)).status());
    }
}
