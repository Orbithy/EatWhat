package you.v50to.eatwhat.sync.transport;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded transport framing. Event envelopes retain their independent canonical codec. */
public final class TransportProtocol {
    public static final int VERSION = 1;
    public static final int MAX_BATCH = 8;
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    public static final String PREFIX = "/internal/sync/v1";
    private static final Set<String> STATUSES = Set.of("APPLIED", "ALREADY_APPLIED", "DURABLY_QUEUED", "CONFLICT",
            "REJECTED", "RETRYABLE_ERROR", "ACKED", "ALREADY_ACKED", "RETRIED", "QUARANTINED", "STALE_LEASE");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                            .maxDocumentLength(MAX_BODY_BYTES).maxTokenCount(2048).maxNumberLength(16)
                            .maxStringLength(EventCodec.MAX_ENVELOPE_BYTES).maxNameLength(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public record Header(int version, UUID requestId, NodeIdentity sender, NodeIdentity receiver) {
        public Header {
            SyncEvent.requireUuid(requestId, "requestId");
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(receiver, "receiver");
        }
        public Header reply() { return new Header(version, requestId, receiver, sender); }
    }

    public record EventItem(UUID eventId, String envelopeHash, UUID leaseToken, String envelope) {
        public EventItem {
            requireCorrelation(eventId, envelopeHash, leaseToken);
            if (envelope == null || envelope.isBlank()
                    || envelope.getBytes(StandardCharsets.UTF_8).length > EventCodec.MAX_ENVELOPE_BYTES) {
                throw invalid();
            }
        }
    }

    public record Ack(UUID eventId, String envelopeHash, UUID leaseToken, String status) {
        public Ack {
            requireCorrelation(eventId, envelopeHash, leaseToken);
            if (!STATUSES.contains(Objects.requireNonNull(status))) throw invalid();
        }
    }

    public record Request(Header header, List<EventItem> events, List<Ack> acks, int limit) {
        public Request {
            Objects.requireNonNull(header, "header");
            events = eventList(events);
            acks = ackList(acks);
            if (limit < 0 || limit > MAX_BATCH) throw invalid();
        }
    }

    public record Response(Header header, List<EventItem> events, List<Ack> acks) {
        public Response {
            Objects.requireNonNull(header, "header");
            events = eventList(events);
            acks = ackList(acks);
        }
    }

    public byte[] encodeRequest(Request request) {
        try {
            ObjectNode root = frame(request.header(), request.events(), request.acks());
            root.put("limit", request.limit());
            return bytes(root);
        } catch (RuntimeException failure) { throw invalid(); }
    }

    public Request decodeRequest(byte[] bytes) {
        try {
            JsonNode root = parse(bytes);
            fields(root, Set.of("header", "events", "acks", "limit"));
            return new Request(header(root.get("header")), events(root.get("events")), acks(root.get("acks")), integer(root, "limit"));
        } catch (RuntimeException failure) { throw invalid(); }
    }

    public byte[] encodeResponse(Response response) {
        try { return bytes(frame(response.header(), response.events(), response.acks())); }
        catch (RuntimeException failure) { throw invalid(); }
    }

    public Response decodeResponse(byte[] bytes) {
        try {
            JsonNode root = parse(bytes);
            fields(root, Set.of("header", "events", "acks"));
            return new Response(header(root.get("header")), events(root.get("events")), acks(root.get("acks")));
        } catch (RuntimeException failure) { throw invalid(); }
    }

    private static ObjectNode frame(Header header, List<EventItem> events, List<Ack> acks) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode h = root.putObject("header").put("version", header.version()).put("requestId", header.requestId().toString());
        identity(h.putObject("sender"), header.sender());
        identity(h.putObject("receiver"), header.receiver());
        var e = root.putArray("events");
        for (EventItem item : events) {
            e.addObject().put("eventId", item.eventId().toString()).put("envelopeHash", item.envelopeHash())
                    .put("leaseToken", item.leaseToken().toString()).put("envelope", item.envelope());
        }
        var a = root.putArray("acks");
        for (Ack ack : acks) {
            a.addObject().put("eventId", ack.eventId().toString()).put("envelopeHash", ack.envelopeHash())
                    .put("leaseToken", ack.leaseToken().toString()).put("status", ack.status());
        }
        return root;
    }

    private static void identity(ObjectNode node, NodeIdentity identity) {
        node.put("siteId", identity.siteId()).put("instanceId", identity.instanceId().toString());
    }

    private static Header header(JsonNode node) {
        fields(node, Set.of("version", "requestId", "sender", "receiver"));
        return new Header(integer(node, "version"), uuid(node, "requestId"), identity(node.get("sender")), identity(node.get("receiver")));
    }

    private static NodeIdentity identity(JsonNode node) {
        fields(node, Set.of("siteId", "instanceId"));
        return new NodeIdentity(text(node, "siteId"), uuid(node, "instanceId"));
    }

    private static List<EventItem> events(JsonNode array) {
        array(array);
        var result = new ArrayList<EventItem>();
        for (JsonNode node : array) {
            fields(node, Set.of("eventId", "envelopeHash", "leaseToken", "envelope"));
            result.add(new EventItem(uuid(node, "eventId"), text(node, "envelopeHash"), uuid(node, "leaseToken"), text(node, "envelope")));
        }
        return result;
    }

    private static List<Ack> acks(JsonNode array) {
        array(array);
        var result = new ArrayList<Ack>();
        for (JsonNode node : array) {
            fields(node, Set.of("eventId", "envelopeHash", "leaseToken", "status"));
            result.add(new Ack(uuid(node, "eventId"), text(node, "envelopeHash"), uuid(node, "leaseToken"), text(node, "status")));
        }
        return result;
    }

    private static void array(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > MAX_BATCH) throw invalid();
    }

    private static List<EventItem> eventList(List<EventItem> values) {
        List<EventItem> result = List.copyOf(values);
        if (result.size() > MAX_BATCH) throw invalid();
        Set<UUID> ids = new HashSet<>();
        for (EventItem value : result) if (!ids.add(value.eventId())) throw invalid();
        return result;
    }

    private static List<Ack> ackList(List<Ack> values) {
        List<Ack> result = List.copyOf(values);
        if (result.size() > MAX_BATCH) throw invalid();
        Set<UUID> ids = new HashSet<>();
        for (Ack value : result) if (!ids.add(value.eventId())) throw invalid();
        return result;
    }

    private static void requireCorrelation(UUID eventId, String hash, UUID token) {
        SyncEvent.requireUuid(eventId, "eventId");
        SyncEvent.requireUuid(token, "leaseToken");
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw invalid();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        for (String field : expected) if (!node.has(field)) throw invalid();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw invalid();
        return value.stringValue();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw invalid();
        SyncEvent.requireUuid(id, field);
        return id;
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    private static JsonNode parse(byte[] value) {
        if (value == null || value.length == 0 || value.length > MAX_BODY_BYTES) throw invalid();
        // Only UTF-8 is part of the wire protocol; reject malformed bytes, UTF-16/32 and BOM auto-detection.
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)).toString();
            return JSON.readTree(json);
        } catch (java.nio.charset.CharacterCodingException failure) { throw invalid(); }
    }

    private static byte[] bytes(ObjectNode value) {
        byte[] result = JSON.writeValueAsBytes(value);
        if (result.length > MAX_BODY_BYTES) throw invalid();
        // Enforce the same structural bounds when sending and receiving.
        parse(result);
        return result;
    }

    private static TransportException invalid() { return new TransportException(400, "INVALID_PROTOCOL"); }
}
