package you.v50to.eatwhat.account;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.account.multiwriter.AccountOperationGraph;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiwriterAccountGraphTest {
    private final UUID subject=UUID.randomUUID();
    private AccountOperationGraph.Operation op(String field, String value, UUID... parents) {
        return new AccountOperationGraph.Operation(UUID.randomUUID(),subject,field,value,Set.of(parents));
    }
    @Test void concurrentPasswordEditsNeverPickAClockWinnerAndObservedResetResolvesBoth() {
        var base=op("password","hash0");var a=op("password","hashA",base.id());var b=op("password","hashB",base.id());
        for(var order:List.of(List.of(base,a,b),List.of(b,a,base))) {
            var graph=new AccountOperationGraph(order);
            assertTrue(graph.reduce(subject,"password").conflict());
            assertNull(graph.reduce(subject,"password").value());
        }
        var reset=op("password","hashReset",a.id(),b.id());
        assertEquals("hashReset",new AccountOperationGraph(List.of(b,reset,a,base)).reduce(subject,"password").value());
    }
    @Test void restrictionWinsConcurrencyButAnObservedLiftWorks() {
        for(String field:List.of("banned","consent","role")) {
            String deny=field.equals("banned")?"true":field.equals("consent")?"WITHDRAWN":"user";
            String allow=field.equals("banned")?"false":field.equals("consent")?"GRANTED":"admin";
            var base=op(field,allow);var a=op(field,deny,base.id());var b=op(field,allow,base.id());
            var graph=new AccountOperationGraph(List.of(base,b,a));
            assertEquals(deny,graph.reduce(subject,field).value());
            var lift=op(field,allow,a.id(),b.id());
            assertEquals(allow,new AccountOperationGraph(List.of(lift,a,base,b)).reduce(subject,field).value());
        }
    }
    @Test void missingDependenciesCannotAuthorizeAndCrossSubjectParentsAreRejected() {
        var unknown=op("role","admin",UUID.randomUUID());
        assertFalse(new AccountOperationGraph(List.of(unknown)).reduce(subject,"role").complete());
        var base=op("role","user");
        var forged=new AccountOperationGraph.Operation(UUID.randomUUID(),UUID.randomUUID(),"role","admin",Set.of(base.id()));
        assertThrows(IllegalArgumentException.class,()->new AccountOperationGraph(List.of(base,forged)));
    }
    @Test void duplicatesMustHaveExactlyTheSameImmutablePayload() {
        var a=op("role","user");
        assertEquals(1,new AccountOperationGraph(List.of(a,a)).reduce(subject,"role").heads().size());
        var forged=new AccountOperationGraph.Operation(a.id(),subject,"role","admin",Set.of());
        assertThrows(IllegalArgumentException.class,()->new AccountOperationGraph(List.of(a,forged)));
    }
}
