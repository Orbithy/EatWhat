package you.v50to.eatwhat.sync.transport;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransportProtocolTest {
    private final TransportProtocol protocol = new TransportProtocol();
    private final NodeIdentity campus = new NodeIdentity("campus-test", UUID.randomUUID());
    private final NodeIdentity external = new NodeIdentity("external-test", UUID.randomUUID());
    private final TransportProtocol.Header header = new TransportProtocol.Header(1, UUID.randomUUID(), campus, external);

    @Test
    void framingRoundTripsWithoutSerializingTheCanonicalEnvelopeIntoADifferentShape() {
        var item = new TransportProtocol.EventItem(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), "{\"payloadJson\":{\"delta\":1}}");
        var request = new TransportProtocol.Request(header, List.of(item), List.of(), 0);
        assertEquals(request, protocol.decodeRequest(protocol.encodeRequest(request)));
        var ack = new TransportProtocol.Ack(item.eventId(), item.envelopeHash(), item.leaseToken(), "DURABLY_QUEUED");
        var response = new TransportProtocol.Response(header.reply(), List.of(), List.of(ack));
        assertEquals(response, protocol.decodeResponse(protocol.encodeResponse(response)));
        assertEquals(header.requestId(), response.header().requestId());
        assertEquals(external, response.header().sender());
        assertEquals(campus, response.header().receiver());
    }

    @Test
    void duplicateKeysTrailingRootsAndUnknownOrMissingFieldsFailWithSafeErrors() {
        String json = requestJson();
        for (String invalid : List.of(
                json.replace("\"limit\":0", "\"limit\":0,\"limit\":0"), json + " {}",
                json.replace("\"limit\":0", "\"limit\":0,\"secret\":\"never echo this\""),
                json.replace(",\"limit\":0", ""),
                json.replace("\"siteId\":\"campus-test\"", "\"siteId\":\"campus-test\",\"siteId\":\"other\""))) {
            TransportException failure = assertThrows(TransportException.class, () -> protocol.decodeRequest(bytes(invalid)));
            assertEquals(400, failure.httpStatus());
            assertEquals("INVALID_PROTOCOL", failure.code());
            assertEquals("INVALID_PROTOCOL", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void numericAndStringCoercionAndNonCanonicalUuidsAreRejected() {
        String json = requestJson();
        for (String invalid : List.of(json.replace("\"limit\":0", "\"limit\":\"0\""),
                json.replace("\"limit\":0", "\"limit\":0.0"),
                json.replace("\"version\":1", "\"version\":true"),
                json.replace(header.requestId().toString(), "1-1-1-1-1"),
                json.replace(header.requestId().toString(), "00000000-0000-0000-0000-000000000000"),
                json.replace("\"events\":[]", "\"events\":null"),
                json.replace("\"siteId\":\"campus-test\"", "\"siteId\":3"))) {
            assertThrows(TransportException.class, () -> protocol.decodeRequest(bytes(invalid)));
        }
    }

    @Test
    void listBodyAndEnvelopeLimitsAreEnforcedInBothDirections() {
        assertThrows(TransportException.class, () -> protocol.decodeRequest(new byte[TransportProtocol.MAX_BODY_BYTES + 1]));
        assertThrows(TransportException.class, () -> protocol.decodeRequest(new byte[0]));
        assertThrows(TransportException.class, () -> protocol.decodeRequest(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(TransportException.class, () -> protocol.decodeRequest(requestJson().getBytes(StandardCharsets.UTF_16LE)));
        assertThrows(TransportException.class, () -> new TransportProtocol.EventItem(UUID.randomUUID(), "a".repeat(64),
                UUID.randomUUID(), "x".repeat(256 * 1024 + 1)));
        var events = java.util.stream.IntStream.range(0, 9).mapToObj(i -> item()).toList();
        assertThrows(TransportException.class, () -> new TransportProtocol.Request(header, events, List.of(), 0));
        assertThrows(TransportException.class, () -> protocol.decodeRequest(bytes(requestJson().replace("\"limit\":0", "\"limit\":9"))));
        assertThrows(TransportException.class, () -> protocol.decodeRequest(bytes(requestJson().replace("\"limit\":0", "\"limit\":-1"))));
    }

    @Test
    void duplicateEventIdsCannotHideDifferentContentsOrAcknowledgements() {
        var item = item();
        var changed = new TransportProtocol.EventItem(item.eventId(), "b".repeat(64), UUID.randomUUID(), "{}");
        assertThrows(TransportException.class, () -> new TransportProtocol.Request(header, List.of(item, changed), List.of(), 0));
        var ack = new TransportProtocol.Ack(item.eventId(), item.envelopeHash(), item.leaseToken(), "APPLIED");
        var changedAck = new TransportProtocol.Ack(item.eventId(), "b".repeat(64), UUID.randomUUID(), "CONFLICT");
        assertThrows(TransportException.class, () -> new TransportProtocol.Response(header.reply(), List.of(), List.of(ack, changedAck)));
        String one = new String(protocol.encodeRequest(new TransportProtocol.Request(header, List.of(item), List.of(), 0)), StandardCharsets.UTF_8);
        int begin = one.indexOf("\"events\":[") + "\"events\":[".length();
        int end = one.indexOf("],\"acks\"");
        String duplicated = one.substring(0, end) + "," + one.substring(begin, end) + one.substring(end);
        assertThrows(TransportException.class, () -> protocol.decodeRequest(bytes(duplicated)));
    }

    @Test
    void acknowledgementsRequireExactHashesTokensAndKnownBoundedStatuses() {
        for (String hash : List.of("a".repeat(63), "A".repeat(64), "z".repeat(64))) {
            assertThrows(TransportException.class, () -> new TransportProtocol.Ack(UUID.randomUUID(), hash, UUID.randomUUID(), "APPLIED"));
        }
        for (String status : List.of("OK", "applied", "secret content", "X".repeat(1000))) {
            assertThrows(TransportException.class, () -> new TransportProtocol.Ack(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), status));
        }
        String response = new String(protocol.encodeResponse(new TransportProtocol.Response(header.reply(), List.of(), List.of())), StandardCharsets.UTF_8);
        assertThrows(TransportException.class, () -> protocol.decodeResponse(bytes(response.substring(0, response.length() - 1) + ",\"limit\":0}")));
    }

    private TransportProtocol.EventItem item() {
        return new TransportProtocol.EventItem(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), "{}");
    }
    private String requestJson() {
        return new String(protocol.encodeRequest(new TransportProtocol.Request(header, List.of(), List.of(), 0)), StandardCharsets.UTF_8);
    }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
