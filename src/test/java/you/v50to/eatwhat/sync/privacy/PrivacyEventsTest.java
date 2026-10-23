package you.v50to.eatwhat.sync.privacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivacyEventsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-4000-8000-000000000201");

    @Test
    void privacyChangeRoundTripsThePerFlagObservedContext() {
        var operation = new PrivacyEvents.Operation(SUBJECT, 3, "campus-test", 1,
                Map.of("following", false), Map.of("following", Map.of()));

        var decoded = PrivacyEvents.parse(PrivacyEvents.payload(operation));

        assertEquals(operation, decoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownRoot", "unknownPrivacyFlag", "nonBoolean", "missingContext"})
    void parserRejectsUnknownFieldsAndMalformedCausalOperations(String malformed) {
        var payload = JSON.readTree(PrivacyEvents.payload(new PrivacyEvents.Operation(SUBJECT, 3,
                "campus-test", 1, Map.of("following", false), Map.of("following", Map.of())))).deepCopy();
        var root = (tools.jackson.databind.node.ObjectNode) payload;
        switch (malformed) {
            case "unknownRoot" -> root.put("role", "admin");
            case "unknownPrivacyFlag" -> ((tools.jackson.databind.node.ObjectNode) root.get("changes")).put("password", true);
            case "nonBoolean" -> ((tools.jackson.databind.node.ObjectNode) root.get("changes")).put("following", "false");
            case "missingContext" -> ((tools.jackson.databind.node.ObjectNode) root.get("contexts")).remove("following");
            default -> throw new AssertionError(malformed);
        }

        assertThrows(IllegalArgumentException.class, () -> PrivacyEvents.parse(root.toString()));
    }

    @Test
    void stateBridgeRequiresAConsistentFalseFrontierAndAQualifyingTrueWitness() {
        var falseWithoutWitness = new PrivacyEvents.BridgeState(false,
                Map.of("campus-test", 1L), Map.of(), List.of());
        var invalidFalse = new PrivacyEvents.Operation(SUBJECT, 3, "campus-test", 2,
                Map.of(), Map.of(), Map.of("following", falseWithoutWitness));
        assertThrows(IllegalArgumentException.class, () -> PrivacyEvents.parse(PrivacyEvents.payload(invalidFalse)));

        var nonCoveringWitness = new PrivacyEvents.Witness(UUID.fromString("00000000-0000-4000-8000-000000000299"),
                Map.of("external-test", 1L));
        var trueAfterUnobservedFalse = new PrivacyEvents.BridgeState(true,
                Map.of("campus-test", 2L, "external-test", 1L), Map.of("campus-test", 1L),
                List.of(nonCoveringWitness));
        var invalidTrue = new PrivacyEvents.Operation(SUBJECT, 3, "campus-test", 2,
                Map.of(), Map.of(), Map.of("following", trueAfterUnobservedFalse));
        assertThrows(IllegalArgumentException.class, () -> PrivacyEvents.parse(PrivacyEvents.payload(invalidTrue)));

        var coveringWitness = new PrivacyEvents.Witness(UUID.fromString("00000000-0000-4000-8000-000000000298"),
                Map.of("campus-test", 2L));
        var validTrue = new PrivacyEvents.BridgeState(true,
                Map.of("campus-test", 2L, "external-test", 1L), Map.of("campus-test", 1L),
                List.of(coveringWitness));
        var accepted = new PrivacyEvents.Operation(SUBJECT, 3, "campus-test", 2,
                Map.of(), Map.of(), Map.of("following", validTrue));
        assertEquals(validTrue, PrivacyEvents.parse(PrivacyEvents.payload(accepted)).stateBridge().get("following"));
        assertTrue(PrivacyEvents.parse(PrivacyEvents.payload(accepted)).changes().isEmpty());
    }
    @Test void threeConcurrentTrueWitnessesRemainInTheBridge(){
        var sites=List.of("campus","external","third");var vector=new java.util.LinkedHashMap<String,Long>();var witnesses=new java.util.ArrayList<PrivacyEvents.Witness>();
        for(String site:sites){vector.put(site,1L);witnesses.add(new PrivacyEvents.Witness(UUID.randomUUID(),Map.of(site,1L)));}
        var state=new PrivacyEvents.BridgeState(true,vector,Map.of(),witnesses);var operation=new PrivacyEvents.Operation(SUBJECT,3,"campus",2,Map.of(),Map.of(),Map.of("following",state));
        assertEquals(3,PrivacyEvents.parse(PrivacyEvents.payload(operation)).stateBridge().get("following").trueWitnesses().size());
    }
}
