package you.v50to.eatwhat.sync;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Version-one canonical JSON profile; independent of frontend serializers. Not RFC 8785/JCS. */
public final class EventCodec {
    public static final int MAX_ENVELOPE_BYTES = 256 * 1024;
    public static final int MAX_PAYLOAD_BYTES = 128 * 1024;
    private static final Set<String> FIELDS = Set.of("eventId", "originSite", "originInstanceId",
            "aggregateType", "aggregateId", "numericId", "eventType", "schemaVersion", "baseVersion",
            "nextVersion", "dependencies", "actorId", "commandKey", "correlationId", "payloadJson");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                            .maxDocumentLength(MAX_ENVELOPE_BYTES).maxTokenCount(30_000)
                            .maxNumberLength(256).maxStringLength(MAX_ENVELOPE_BYTES).maxNameLength(256).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public record Encoded(String json, String hash) { }

    public Encoded encode(SyncEvent event) {
        var root = JSON.createObjectNode();
        root.put("eventId", event.eventId().toString());
        root.put("originSite", event.originSite());
        root.put("originInstanceId", event.originInstanceId().toString());
        root.put("aggregateType", event.aggregateType());
        root.put("aggregateId", event.aggregateId().toString());
        root.put("numericId", event.numericId());
        root.put("eventType", event.eventType());
        root.put("schemaVersion", event.schemaVersion());
        root.set("baseVersion", vectorNode(event.baseVersion()));
        root.set("nextVersion", vectorNode(event.nextVersion()));
        var dependencies = root.putArray("dependencies");
        for (var dependency : event.dependencies()) {
            dependencies.addObject().put("aggregateType", dependency.aggregateType())
                    .put("aggregateId", dependency.aggregateId().toString());
        }
        if (event.actorId() == null) root.putNull("actorId");
        else root.put("actorId", event.actorId().toString());
        root.put("commandKey", event.commandKey());
        root.put("correlationId", event.correlationId().toString());
        checkSize(event.payloadJson(), MAX_PAYLOAD_BYTES);
        JsonNode payload = parse(event.payloadJson());
        if (!payload.isObject()) throw new IllegalArgumentException("Event payload must be a JSON object");
        checkSize(canonical(payload), MAX_PAYLOAD_BYTES);
        root.set("payloadJson", payload);
        // Parse the final envelope too, so its combined depth and token count obey the same profile.
        String json = canonicalJson(JSON.writeValueAsString(root));
        return new Encoded(json, sha256(json));
    }

    public SyncEvent decode(String json) {
        JsonNode root = parse(json);
        requireFields(root, FIELDS);
        var dependencies = root.get("dependencies");
        if (!dependencies.isArray() || dependencies.size() > 100) {
            throw new IllegalArgumentException("Invalid event dependencies");
        }
        var entries = new ArrayList<SyncEvent.Dependency>();
        for (JsonNode dependency : dependencies) {
            requireFields(dependency, Set.of("aggregateType", "aggregateId"));
            entries.add(new SyncEvent.Dependency(text(dependency, "aggregateType"), uuid(dependency, "aggregateId")));
        }
        long version = integer(root, "schemaVersion");
        if (version <= 0 || version > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid schema version");
        JsonNode payload = root.get("payloadJson");
        if (!payload.isObject()) throw new IllegalArgumentException("Event payload must be a JSON object");
        String payloadJson = canonical(payload);
        checkSize(payloadJson, MAX_PAYLOAD_BYTES);
        return new SyncEvent(uuid(root, "eventId"), text(root, "originSite"), uuid(root, "originInstanceId"),
                text(root, "aggregateType"), uuid(root, "aggregateId"), integer(root, "numericId"),
                text(root, "eventType"), (int) version, vector(root.get("baseVersion")), vector(root.get("nextVersion")),
                entries, root.get("actorId").isNull() ? null : uuid(root, "actorId"), text(root, "commandKey"),
                uuid(root, "correlationId"), payloadJson);
    }

    public static String vectorJson(VersionVector vector) { return canonical(vectorNode(vector)); }
    public static VersionVector vectorFromJson(String json) { return vector(parse(json)); }

    public static String canonicalJson(String json) {
        String canonical = canonical(parse(json));
        checkSize(canonical, MAX_ENVELOPE_BYTES);
        return canonical;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static JsonNode parse(String json) {
        checkSize(json, MAX_ENVELOPE_BYTES);
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || node.isMissingNode()) throw new IllegalArgumentException("Empty JSON");
            return node;
        } catch (RuntimeException failure) {
            // Do not expose payloads through parser messages or source locations.
            throw new IllegalArgumentException("Invalid or oversized event JSON");
        }
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(field -> fields.put(field.getKey(), field.getValue()));
            var parts = new ArrayList<String>();
            for (var field : fields.entrySet()) parts.add(quoted(field.getKey()) + ":" + canonical(field.getValue()));
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) {
            var parts = new ArrayList<String>();
            node.forEach(value -> parts.add(canonical(value)));
            return "[" + String.join(",", parts) + "]";
        }
        if (node.isString()) return quoted(node.stringValue());
        if (node.isNumber()) {
            BigDecimal decimal = node.decimalValue().stripTrailingZeros();
            if (decimal.precision() > 256 || Math.abs((long) decimal.scale()) > 1000) {
                throw new IllegalArgumentException("JSON number is outside canonical limits");
            }
            String plain = decimal.signum() == 0 ? "0" : decimal.toPlainString();
            if (plain.length() > 256) throw new IllegalArgumentException("Canonical number exceeds the parser limit");
            return plain;
        }
        if (node.isBoolean()) return node.booleanValue() ? "true" : "false";
        if (node.isNull()) return "null";
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static String quoted(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) throw new IllegalArgumentException("NUL is not supported by PostgreSQL JSONB");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Unpaired Unicode surrogate");
                }
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unpaired Unicode surrogate");
        }
        return JSON.writeValueAsString(value);
    }

    private static ObjectNode vectorNode(VersionVector vector) {
        var node = JSON.createObjectNode();
        vector.counters().forEach(node::put);
        return node;
    }

    private static VersionVector vector(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Invalid version vector");
        var values = new TreeMap<String, Long>();
        node.properties().forEach(entry -> values.put(entry.getKey(), integer(node, entry.getKey())));
        return new VersionVector(values);
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Invalid integer field: " + field);
        }
        return value.longValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException("Invalid string field: " + field);
        return value.stringValue();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw new IllegalArgumentException("UUID must use canonical lowercase form");
        return parsed;
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (!node.isObject() || !Set.copyOf(node.propertyNames()).equals(expected)) {
            throw new IllegalArgumentException("Unexpected event fields");
        }
    }

    private static void checkSize(String value, int limit) {
        if (value == null || value.length() > limit || value.getBytes(StandardCharsets.UTF_8).length > limit) {
            throw new IllegalArgumentException("Event JSON exceeds its byte limit");
        }
    }
}
