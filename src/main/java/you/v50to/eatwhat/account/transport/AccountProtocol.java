package you.v50to.eatwhat.account.transport;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Account messages are transient RPC frames, never event envelopes or durable retry payloads. */
public final class AccountProtocol {
    public static final int VERSION = 1;
    public static final int MAX_BODY_BYTES = 256 * 1024;
    public static final String PATH = "/internal/account/v1/rpc";
    private static final Set<String> OPERATIONS = Set.of("authenticate", "register", "grant", "withdraw", "project","session-register","session-authorize","session-revoke","multiwriter-capabilities","multiwriter-exchange","sms-reserve","sms-release","sms-store","sms-verify","email-reserve","email-release","email-store","email-discard","email-consume");
    private static final Set<String> FIELDS = Set.of("version", "requestId", "sender", "receiver", "issuedAt", "operation", "body");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                            .maxDocumentLength(MAX_BODY_BYTES).maxTokenCount(16384).maxNumberLength(32)
                            .maxStringLength(MAX_BODY_BYTES).maxNameLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public record Frame(int version, UUID requestId, NodeIdentity sender, NodeIdentity receiver,
                        Instant issuedAt, String operation, JsonNode body) {
        public Frame {
            if (version != VERSION || requestId == null || requestId.equals(new UUID(0, 0))
                    || sender == null || receiver == null || sender.equals(receiver) || issuedAt == null
                    || operation == null || !OPERATIONS.contains(operation) || body == null || !body.isObject()) {
                throw invalid();
            }
            body = body.deepCopy();
        }
        public Frame reply(JsonNode responseBody) {
            return new Frame(version, requestId, receiver, sender, issuedAt, operation, responseBody);
        }
        public boolean isReplyTo(Frame request) {
            return version == request.version && requestId.equals(request.requestId)
                    && sender.equals(request.receiver) && receiver.equals(request.sender)
                    && issuedAt.equals(request.issuedAt) && operation.equals(request.operation);
        }
        @Override public JsonNode body() { return body.deepCopy(); }
        @Override public String toString() { return "AccountFrame[redacted]"; }
    }

    public byte[] encode(Frame frame) {
        try {
            ObjectNode root = JSON.createObjectNode().put("version", frame.version())
                    .put("requestId", frame.requestId().toString()).put("issuedAt", frame.issuedAt().toString())
                    .put("operation", frame.operation());
            identity(root.putObject("sender"), frame.sender());
            identity(root.putObject("receiver"), frame.receiver());
            root.set("body", frame.body());
            byte[] bytes = JSON.writeValueAsBytes(root);
            // Sending and receiving use identical byte, depth, token and encoding limits.
            decode(bytes);
            return bytes;
        } catch (RuntimeException failure) { throw invalid(); }
    }

    public Frame decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw invalid();
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = JSON.readTree(text);
            fields(root, FIELDS);
            JsonNode version = root.get("version");
            if (!version.isIntegralNumber() || !version.canConvertToInt()) throw invalid();
            String time = text(root, "issuedAt");
            Instant issuedAt = Instant.parse(time);
            if (!issuedAt.toString().equals(time)) throw invalid();
            return new Frame(version.intValue(), uuid(root, "requestId"), identity(root.get("sender")),
                    identity(root.get("receiver")), issuedAt, text(root, "operation"), root.get("body"));
        } catch (RuntimeException | CharacterCodingException failure) { throw invalid(); }
    }

    private static void identity(ObjectNode node, NodeIdentity value) {
        node.put("siteId", value.siteId()).put("instanceId", value.instanceId().toString());
    }

    private static NodeIdentity identity(JsonNode node) {
        fields(node, Set.of("siteId", "instanceId"));
        return new NodeIdentity(text(node, "siteId"), uuid(node, "instanceId"));
    }

    private static UUID uuid(JsonNode node, String field) {
        String text = text(node, field);
        UUID value = UUID.fromString(text);
        if (!value.toString().equals(text) || value.equals(new UUID(0, 0))) throw invalid();
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw invalid();
        return value.stringValue();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        for (String field : expected) if (!node.has(field)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid account frame");
    }
}
