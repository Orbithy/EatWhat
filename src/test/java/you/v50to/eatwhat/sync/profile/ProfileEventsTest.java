package you.v50to.eatwhat.sync.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileEventsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID BASELINE = UUID.fromString("00000000-0000-4000-8000-000000000102");

    @Test
    void profileWithNoOptionalValuesCanRoundTripAndStillCarryAFieldChange() {
        Map<String, Object> baseline = emptyBaseline();
        Map<String, Map<String, Long>> contexts = Map.of("signature", Map.of());
        ProfileEvents.Operation operation = operation(baseline);

        ProfileEvents.Operation decoded = ProfileEvents.parse(ProfileEvents.payload(operation));

        assertEquals(baseline, decoded.baseline());
        assertEquals(Map.of("signature", "changed"), decoded.changes());
        assertEquals(contexts, decoded.contexts());
    }

    @Test
    void groupedHometownRetainsLegacyProvinceOnlyAndCityOnlyStates() {
        for (Map<String, Object> hometown : List.of(hometown(7, null), hometown(null, 9))) {
            Map<String, Object> baseline = emptyBaseline();
            baseline.put("hometown", hometown);
            var operation = operation(baseline);

            ProfileEvents.Operation decoded = ProfileEvents.parse(ProfileEvents.payload(operation));

            assertEquals(hometown, decoded.baseline().get("hometown"));
        }
    }

    @Test
    void fieldContextIsTheBeforeVectorAndCanIncludeOtherSites() {
        Map<String, Object> baseline = emptyBaseline();
        ProfileEvents.Operation operation = new ProfileEvents.Operation(SUBJECT, 4, BASELINE, "campus-test",
                baselineHash(baseline), baseline, Map.of("signature", "changed"),
                Map.of("signature", Map.of("campus-test", 4L, "external-test", 2L)),
                "campus-test", 5);

        ProfileEvents.Operation decoded = ProfileEvents.parse(ProfileEvents.payload(operation));

        assertEquals(5, decoded.dotCounter());
        assertEquals(Map.of("campus-test", 4L, "external-test", 2L), decoded.contexts().get("signature"));
    }

    @Test
    void legacyMultilineSignatureRoundTripsWithoutChangingItsAcceptedCharacters() {
        String signature = "first line\nsecond line\twith tab";
        var operation = new ProfileEvents.Operation(SUBJECT, 4, BASELINE, "campus-test",
                baselineHash(emptyBaseline()), emptyBaseline(), Map.of("signature", signature),
                Map.of("signature", Map.of()), "campus-test", 1);

        ProfileEvents.Operation decoded = ProfileEvents.parse(ProfileEvents.payload(operation));

        assertEquals(signature, decoded.changes().get("signature"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "unknownField", "genderType", "partialHometown"})
    void parserRejectsUnknownKeysAndMalformedProfileValues(String invalidity) {
        var payload = JSON.readTree(ProfileEvents.payload(operation(emptyBaseline()))).deepCopy();
        var root = (tools.jackson.databind.node.ObjectNode) payload;
        var baseline = (tools.jackson.databind.node.ObjectNode) root.get("baseline");
        switch (invalidity) {
            case "root" -> root.put("unrecognizedRoot", true);
            case "unknownField" -> baseline.put("nickname", "not-syncable");
            case "genderType" -> baseline.put("gender", 17);
            case "partialHometown" -> ((tools.jackson.databind.node.ObjectNode) baseline.get("hometown")).remove("cityId");
            default -> throw new AssertionError(invalidity);
        }
        assertThrows(IllegalArgumentException.class, () -> ProfileEvents.parse(root.toString()));
    }

    private static Map<String, Object> emptyBaseline() {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("avatar", null);
        baseline.put("gender", null);
        baseline.put("birthday", null);
        baseline.put("signature", null);
        Map<String, Object> hometown = new LinkedHashMap<>();
        hometown.put("provinceId", null);
        hometown.put("cityId", null);
        baseline.put("hometown", hometown);
        return baseline;
    }

    private static Map<String, Object> hometown(Integer provinceId, Integer cityId) {
        Map<String, Object> hometown = new LinkedHashMap<>();
        hometown.put("provinceId", provinceId);
        hometown.put("cityId", cityId);
        return hometown;
    }

    private static ProfileEvents.Operation operation(Map<String, Object> baseline) {
        return new ProfileEvents.Operation(SUBJECT, 4, BASELINE, "campus-test", baselineHash(baseline), baseline,
                Map.of("signature", "changed"), Map.of("signature", Map.of()), "external-test", 1);
    }

    private static String baselineHash(Map<String, Object> baseline) {
        return EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(baseline)));
    }
}
