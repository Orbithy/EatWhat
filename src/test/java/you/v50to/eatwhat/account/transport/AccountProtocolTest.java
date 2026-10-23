package you.v50to.eatwhat.account.transport;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.AccountUnavailableException;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AccountProtocolTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AccountProtocol protocol = new AccountProtocol();
    private final NodeIdentity campus = new NodeIdentity("campus-account", UUID.randomUUID());
    private final NodeIdentity external = new NodeIdentity("external-account", UUID.randomUUID());
    private final Instant now = Instant.parse("2026-10-12T09:00:00Z");

    @Test
    void everyOperationRoundTripsAndSensitiveFrameAndFailureStringsAreRedacted() {
        for (String operation : List.of("authenticate", "register", "grant", "withdraw", "project")) {
            var body = json.createObjectNode().put("password", "synthetic-password-sentinel");
            var frame = new AccountProtocol.Frame(1, UUID.randomUUID(), campus, external, now, operation, body);
            body.put("password", "changed-after-construction");
            var decoded = protocol.decode(protocol.encode(frame));
            assertEquals(frame, decoded);
            assertEquals("synthetic-password-sentinel", decoded.body().get("password").stringValue());
            ((tools.jackson.databind.node.ObjectNode) decoded.body()).removeAll();
            assertTrue(decoded.body().has("password"));
            assertEquals("AccountFrame[redacted]", frame.toString());
            assertTrue(frame.reply(json.createObjectNode().put("code", 20005)).isReplyTo(frame));
        }
        var failure = new AccountUnavailableException();
        assertEquals("Account channel unavailable", failure.getMessage());
        assertNull(failure.getCause());
        failure.addSuppressed(new RuntimeException("sensitive"));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void duplicateUnknownMissingFieldsTrailingRootsAndCoercionsFailWithoutEchoingInput() {
        String frame = text(protocol.encode(frame()));
        for (String invalid : List.of(frame + " {}", frame.replace("\"version\":1", "\"version\":1,\"version\":1"),
                frame.replace("\"version\":1", "\"version\":1,\"secret-sentinel\":true"),
                frame.replace("\"version\":1,", ""), frame.replace("\"version\":1", "\"version\":\"1\""),
                frame.replace("\"version\":1", "\"version\":1.0"), frame.replace("\"version\":1", "\"version\":2"),
                frame.replace("\"authenticate\"", "\"unknown\""), frame.replace("\"body\":{}", "\"body\":null"),
                frame.replace("\"body\":{}", "\"body\":{\"password\":1,\"password\":2}"))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> protocol.decode(bytes(invalid)));
            assertEquals("Invalid account frame", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void canonicalIdentityTimeUtf8AndSizeLimitsApplyInBothDirections() {
        var original = frame();
        String frame = text(protocol.encode(original));
        for (String invalid : List.of(frame.replace(original.requestId().toString(), "1-1-1-1-1"),
                frame.replace(original.requestId().toString(), "00000000-0000-0000-0000-000000000000"),
                frame.replace(now.toString(), "2026-10-12T09:00:00+00:00"),
                frame.replace("\"issuedAt\":\"" + now + "\"", "\"issuedAt\":0"),
                frame.replace("\"siteId\":\"campus-account\"", "\"siteId\":5"))) {
            assertThrows(IllegalArgumentException.class, () -> protocol.decode(bytes(invalid)));
        }
        for (byte[] invalid : List.of(new byte[0], new byte[AccountProtocol.MAX_BODY_BYTES + 1],
                new byte[]{(byte) 0xc3, 0x28}, frame.getBytes(StandardCharsets.UTF_16LE))) {
            assertThrows(IllegalArgumentException.class, () -> protocol.decode(invalid));
        }
        var oversized = json.createObjectNode().put("password", "x".repeat(AccountProtocol.MAX_BODY_BYTES));
        assertThrows(IllegalArgumentException.class, () -> protocol.encode(original.reply(oversized)));
        var deeplyNested = json.createObjectNode();
        var leaf = deeplyNested;
        for (int i = 0; i < 20; i++) leaf = leaf.putObject("nested");
        assertThrows(IllegalArgumentException.class, () -> protocol.encode(original.reply(deeplyNested)));
    }

    @Test
    void responseMustMatchAllRequestCorrelationFields() {
        var request = frame();
        var body = json.createObjectNode();
        var other = new NodeIdentity("other-account", UUID.randomUUID());
        List<AccountProtocol.Frame> invalid = List.of(
                new AccountProtocol.Frame(1, UUID.randomUUID(), external, campus, now, "authenticate", body),
                new AccountProtocol.Frame(1, request.requestId(), other, campus, now, "authenticate", body),
                new AccountProtocol.Frame(1, request.requestId(), external, other, now, "authenticate", body),
                new AccountProtocol.Frame(1, request.requestId(), external, campus, now.plusNanos(1), "authenticate", body),
                new AccountProtocol.Frame(1, request.requestId(), external, campus, now, "grant", body));
        for (var reply : invalid) assertFalse(reply.isReplyTo(request));
        assertTrue(request.reply(body).isReplyTo(request));
    }

    @Test
    void fixedTargetCannotContainCredentialsPathsOrRedirectingSchemes() {
        for (String target : List.of("http://localhost", "https://user:password@localhost", "https://localhost/other",
                "https://localhost?target=other", "https://localhost#fragment", "https://localhost:0", "https://localhost:65536")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> AccountNodeClient.validateBaseUri(URI.create(target)));
            assertEquals("Account peer must be a fixed HTTPS origin", failure.getMessage());
        }
        AccountNodeClient.validateBaseUri(URI.create("https://localhost:9443/"));
    }

    @Test
    void replayWindowIsSingleUseBoundedAndDoesNotExpireOnMonotonicTimeAloneAfterClockRollback() {
        var window = new AccountReplayWindow();
        UUID id = UUID.randomUUID();
        assertEquals(AccountReplayWindow.Reservation.STALE, window.reserve(id, now.minusSeconds(31), now, 0));
        assertEquals(AccountReplayWindow.Reservation.STALE, window.reserve(id, now.plusSeconds(31), now, 0));
        assertEquals(AccountReplayWindow.Reservation.ACCEPTED, window.reserve(id, now.plusSeconds(30), now, 0));
        assertEquals(AccountReplayWindow.Reservation.REPLAY, window.reserve(id, now.plusSeconds(30), now, 62_000_000_000L));
        for (int i = 1; i < AccountReplayWindow.CAPACITY; i++) {
            assertEquals(AccountReplayWindow.Reservation.ACCEPTED, window.reserve(UUID.randomUUID(), now, now, 0));
        }
        assertEquals(AccountReplayWindow.Reservation.FULL, window.reserve(UUID.randomUUID(), now, now, 1));
        assertEquals(AccountReplayWindow.Reservation.ACCEPTED,
                window.reserve(UUID.randomUUID(), now.plusSeconds(62), now.plusSeconds(62), 62_000_000_000L));
    }

    @Test
    void concurrentReplayReservationAdmitsExactlyOneHandler() throws Exception {
        var window = new AccountReplayWindow();
        UUID id = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<AccountReplayWindow.Reservation>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                assertTrue(start.await(3, TimeUnit.SECONDS));
                return window.reserve(id, now, now, 0);
            }));
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(3, TimeUnit.SECONDS) == AccountReplayWindow.Reservation.ACCEPTED) accepted++;
            assertEquals(1, accepted);
        }
    }

    private AccountProtocol.Frame frame() {
        return new AccountProtocol.Frame(1, UUID.randomUUID(), campus, external, now, "authenticate", json.createObjectNode());
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
}
