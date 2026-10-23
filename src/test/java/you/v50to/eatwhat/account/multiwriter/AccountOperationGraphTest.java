package you.v50to.eatwhat.account.multiwriter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountOperationGraphTest {
    private static final UUID SUBJECT = UUID.randomUUID();

    private static AccountOperationGraph.Operation op(String field, String value, UUID... parents) {
        return new AccountOperationGraph.Operation(UUID.randomUUID(), SUBJECT, field, value, Set.of(parents));
    }

    @Test
    void concurrentNicknamesConvergeOnTheSameValueRegardlessOfArrivalOrder() {
        var base = op("display_name", "null");
        var left = op("display_name", "\"甲\"", base.id());
        var right = op("display_name", "\"乙\"", base.id());
        var one = new AccountOperationGraph(List.of(base, left, right)).reduce(SUBJECT, "display_name");
        var other = new AccountOperationGraph(List.of(right, base, left)).reduce(SUBJECT, "display_name");
        assertFalse(one.conflict());
        assertTrue(one.complete());
        assertEquals(one.value(), other.value());
        assertEquals(Set.of(left.id(), right.id()), one.heads());
    }

    @Test
    void concurrentPasswordsStillConflict() {
        var base = op("password", "h0");
        var reduced = new AccountOperationGraph(List.of(base, op("password", "h1", base.id()), op("password", "h2", base.id())))
                .reduce(SUBJECT, "password");
        assertTrue(reduced.conflict());
        assertNull(reduced.value());
    }
}
