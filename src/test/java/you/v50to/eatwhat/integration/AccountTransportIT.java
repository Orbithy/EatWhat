package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.AccountRequestHandler;
import you.v50to.eatwhat.account.AccountUnavailableException;
import you.v50to.eatwhat.account.transport.AccountNodeClient;
import you.v50to.eatwhat.account.transport.AccountNodeServer;
import you.v50to.eatwhat.account.transport.AccountProtocol;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Real production RPC/TLS classes with temporary certificates; no database, real configuration or external calls. */
class AccountTransportIT {
    @TempDir static Path directory;
    private static TransportTlsFixture certificates;
    private static final NodeIdentity CAMPUS = new NodeIdentity("campus-account", UUID.randomUUID());
    private static final NodeIdentity EXTERNAL = new NodeIdentity("external-account", UUID.randomUUID());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AccountProtocol PROTOCOL = new AccountProtocol();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @BeforeAll static void certificatesAndListenerBounds() throws Exception {
        certificates = TransportTlsFixture.create(directory.resolve("tls"));
        // Production configures process-wide JDK listener bounds before adversarial fixture servers are created.
        try (var ignored = server((operation, body) -> JSON.createObjectNode(), TIMEOUT)) { }
    }

    @Test
    void bothPrivateDirectionsUseActualMutualTlsAndPreserveOnlyTheSafeHandlerResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var campus = server((operation, body) -> {
            assertEquals("authenticate", operation);
            assertEquals("synthetic-password-canary", body.get("password").stringValue());
            calls.incrementAndGet();
            return JSON.createObjectNode().put("code", 20005).put("consentProof", "synthetic-one-use-proof");
        }, TIMEOUT);
             var outbound = client(campus)) {
            JsonNode result = outbound.call("authenticate", JSON.createObjectNode().put("identifier", "synthetic-alias")
                    .put("password", "synthetic-password-canary"));
            assertEquals(20005, result.get("code").intValue());
            assertFalse(result.toString().contains("password-canary"));
            assertEquals(1, calls.get());
        }
        try (var external = new AccountNodeServer(new InetSocketAddress("127.0.0.1", 0), externalTls(),
                EXTERNAL, CAMPUS, (operation, body) -> JSON.createObjectNode().put("status", "APPLIED"), TIMEOUT)) {
            external.start();
            try (var projection = new AccountNodeClient(base(external), campusTls(), CAMPUS, EXTERNAL, TIMEOUT)) {
                assertEquals("APPLIED", projection.call("project", JSON.createObjectNode()).get("status").stringValue());
            }
        }
    }

    @Test
    void missingUntrustedAndTrustedButUnpinnedClientCertificatesCannotReachHandlerEvenWithProxyHeaders() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = server((operation, body) -> { calls.incrementAndGet(); return JSON.createObjectNode(); }, TIMEOUT)) {
            for (SSLContext rejected : List.of(certificates.withoutClientCertificate(),
                    certificates.context(certificates.untrusted(), certificates.campus()),
                    certificates.context(certificates.otherTrusted(), certificates.campus()))) {
                assertThrows(IOException.class, () -> raw(base(server), rejected, "POST", AccountProtocol.PATH,
                        "application/json", PROTOCOL.encode(request()), true));
            }
            assertEquals(0, calls.get());
        }
    }

    @Test
    void clientRetainsCaPinAndHostnameVerification() throws Exception {
        try (var server = server((operation, body) -> JSON.createObjectNode(), TIMEOUT);
             var client = new AccountNodeClient(base(server), certificates.context(certificates.external(), certificates.otherTrusted()),
                     EXTERNAL, CAMPUS, TIMEOUT)) {
            assertSafeUnavailable(() -> client.call("authenticate", JSON.createObjectNode()));
        }
        for (var identity : List.of(certificates.untrusted(), certificates.wrongHostname())) {
            try (var server = new AccountNodeServer(new InetSocketAddress("127.0.0.1", 0),
                    certificates.context(identity, certificates.external()), CAMPUS, EXTERNAL,
                    (operation, body) -> JSON.createObjectNode(), TIMEOUT)) {
                server.start();
                try (var client = new AccountNodeClient(base(server), certificates.context(certificates.external(), identity),
                        EXTERNAL, CAMPUS, TIMEOUT)) {
                    assertSafeUnavailable(() -> client.call("authenticate", JSON.createObjectNode()));
                }
            }
        }
    }

    @Test
    void peerIdentityClockWindowAndRequestReplayAreCheckedBeforeBusinessHandler() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = server((operation, body) -> { calls.incrementAndGet(); return JSON.createObjectNode(); }, TIMEOUT)) {
            var request = request();
            for (var bad : List.of(
                    new AccountProtocol.Frame(1, UUID.randomUUID(), new NodeIdentity(EXTERNAL.siteId(), UUID.randomUUID()), CAMPUS,
                            Instant.now(), "authenticate", JSON.createObjectNode()),
                    new AccountProtocol.Frame(1, UUID.randomUUID(), EXTERNAL, new NodeIdentity("wrong", CAMPUS.instanceId()),
                            Instant.now(), "authenticate", JSON.createObjectNode()))) {
                assertEquals(403, post(server, PROTOCOL.encode(bad), true).statusCode());
            }
            for (Instant stale : List.of(Instant.now().minusSeconds(35), Instant.now().plusSeconds(35))) {
                var bad = new AccountProtocol.Frame(1, UUID.randomUUID(), EXTERNAL, CAMPUS, stale, "authenticate", JSON.createObjectNode());
                assertEquals(400, post(server, PROTOCOL.encode(bad), false).statusCode());
            }
            assertEquals(0, calls.get());
            var first = post(server, PROTOCOL.encode(request), false);
            assertEquals(200, first.statusCode());
            assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(PROTOCOL.decode(first.body()).isReplyTo(request));
            assertEquals(409, post(server, PROTOCOL.encode(request), false).statusCode());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void methodsExactPathContentTypeAndMalformedFramesHaveSmallNonSensitiveErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = server((operation, body) -> { calls.incrementAndGet(); return JSON.createObjectNode(); }, TIMEOUT)) {
            assertEquals(405, raw(base(server), externalTls(), "GET", AccountProtocol.PATH, "application/json", new byte[0], false).statusCode());
            for (String path : List.of("/auth/login", "/internal/sync/v1/push", AccountProtocol.PATH + "/", "/internal/account/v1/%72pc")) {
                assertEquals(404, raw(base(server), externalTls(), "POST", path, "application/json", new byte[0], false).statusCode());
            }
            assertEquals(400, raw(base(server), externalTls(), "POST", AccountProtocol.PATH + "?peer=campus", "application/json", new byte[0], false).statusCode());
            assertEquals(400, raw(base(server), externalTls(), "POST", AccountProtocol.PATH, "text/plain", PROTOCOL.encode(request()), false).statusCode());
            for (String invalid : List.of("{\"password-sentinel\":", "{} {}", "{\"body\":{},\"body\":{}}")) {
                var response = post(server, invalid.getBytes(StandardCharsets.UTF_8), false);
                assertEquals(400, response.statusCode());
                assertTrue(response.body().length < 100);
                assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("sentinel"));
            }
            assertEquals(0, calls.get());
        }
    }

    @Test
    void oversizedAndIncompleteRequestsAreBoundedAndPermitIsReleasedAfterTimeout() throws Exception {
        try (var server = server((operation, body) -> JSON.createObjectNode(), Duration.ofMillis(600))) {
            try (var socket = socket(server)) {
                headers(socket, AccountProtocol.MAX_BODY_BYTES + 1);
                assertTrue(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine().contains("413"));
            }
            try (var socket = socket(server)) {
                headers(socket, 100);
                assertEquals(-1, socket.getInputStream().read());
            }
            try (var client = client(server)) {
                assertEquals(JSON.createObjectNode(), client.call("authenticate", JSON.createObjectNode()));
            }
        }
    }

    @Test
    void fourConcurrentHandlersBoundAdmissionAndFailuresNeverExposeCredentialOrCause() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (var server = server((operation, body) -> {
            entered.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic-password-sentinel");
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            throw new IllegalArgumentException("synthetic-password-sentinel");
        }, Duration.ofSeconds(10));
             var http = http(externalTls())) {
            var pending = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>>();
            try {
                for (int i = 0; i < 4; i++) pending.add(http.sendAsync(request(base(server), "POST", AccountProtocol.PATH,
                        "application/json", PROTOCOL.encode(request()), false), HttpResponse.BodyHandlers.ofByteArray()));
                assertTrue(entered.await(6, TimeUnit.SECONDS));
                var rejected = post(server, PROTOCOL.encode(request()), false);
                assertEquals(429, rejected.statusCode());
                release.countDown();
                for (var future : pending) {
                    var response = future.get(5, TimeUnit.SECONDS);
                    assertEquals(503, response.statusCode());
                    assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("sentinel"));
                }
            } finally {
                release.countDown();
                pending.forEach(future -> future.cancel(true));
            }
        }
    }

    @Test
    void completeResponseDeadlineCoversHeadersPlusSlowBodyWithoutAutomaticRetry() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var peer = fake(exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }); var client = new AccountNodeClient(peer.base(), externalTls(), EXTERNAL, CAMPUS, Duration.ofMillis(1500))) {
            long began = System.nanoTime();
            try { assertSafeUnavailable(() -> client.call("authenticate", JSON.createObjectNode().put("password", "secret-canary"))); }
            finally { release.countDown(); }
            assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofSeconds(4)) < 0);
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void oversizedResponsesAndRedirectsAreNotAcceptedOrFollowed() throws Exception {
        AtomicInteger redirectTargetCalls = new AtomicInteger();
        try (var target = server((operation, body) -> { redirectTargetCalls.incrementAndGet(); return JSON.createObjectNode(); }, TIMEOUT);
             var redirect = fake(exchange -> {
                 exchange.getResponseHeaders().set("Location", base(target).resolve(AccountProtocol.PATH).toString());
                 exchange.sendResponseHeaders(307, -1);
             }); var client = new AccountNodeClient(redirect.base(), externalTls(), EXTERNAL, CAMPUS, TIMEOUT)) {
            assertSafeUnavailable(() -> client.call("authenticate", JSON.createObjectNode()));
            assertEquals(0, redirectTargetCalls.get());
        }
        try (var oversized = fake(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[AccountProtocol.MAX_BODY_BYTES + 1]);
        }); var client = new AccountNodeClient(oversized.base(), externalTls(), EXTERNAL, CAMPUS, TIMEOUT)) {
            assertSafeUnavailable(() -> client.call("authenticate", JSON.createObjectNode()));
        }
    }

    @Test
    void authenticatedPeerCannotSubstituteAnyResponseCorrelationField() throws Exception {
        List<UnaryOperator<AccountProtocol.Frame>> changes = List.of(
                f -> new AccountProtocol.Frame(1, UUID.randomUUID(), f.sender(), f.receiver(), f.issuedAt(), f.operation(), f.body()),
                f -> new AccountProtocol.Frame(1, f.requestId(), new NodeIdentity("wrong", f.sender().instanceId()), f.receiver(), f.issuedAt(), f.operation(), f.body()),
                f -> new AccountProtocol.Frame(1, f.requestId(), f.sender(), new NodeIdentity(f.receiver().siteId(), UUID.randomUUID()), f.issuedAt(), f.operation(), f.body()),
                f -> new AccountProtocol.Frame(1, f.requestId(), f.sender(), f.receiver(), f.issuedAt().plusNanos(1), f.operation(), f.body()),
                f -> new AccountProtocol.Frame(1, f.requestId(), f.sender(), f.receiver(), f.issuedAt(), "grant", f.body()));
        for (var change : changes) {
            try (var peer = fake(exchange -> {
                var request = PROTOCOL.decode(exchange.getRequestBody().readAllBytes());
                byte[] body = PROTOCOL.encode(change.apply(request.reply(JSON.createObjectNode().put("code", 0))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }); var client = new AccountNodeClient(peer.base(), externalTls(), EXTERNAL, CAMPUS, TIMEOUT)) {
                assertSafeUnavailable(() -> client.call("authenticate", JSON.createObjectNode()));
            }
        }
    }

    private static AccountNodeServer server(AccountRequestHandler handler, Duration timeout) {
        var server = new AccountNodeServer(new InetSocketAddress("127.0.0.1", 0), campusTls(), CAMPUS, EXTERNAL, handler, timeout);
        server.start();
        return server;
    }
    private static AccountNodeClient client(AccountNodeServer server) {
        return new AccountNodeClient(base(server), externalTls(), EXTERNAL, CAMPUS, TIMEOUT);
    }
    private static SSLContext campusTls() { return certificates.context(certificates.campus(), certificates.external()); }
    private static SSLContext externalTls() { return certificates.context(certificates.external(), certificates.campus()); }
    private static URI base(AccountNodeServer server) { return URI.create("https://localhost:" + server.port()); }
    private static AccountProtocol.Frame request() {
        return new AccountProtocol.Frame(1, UUID.randomUUID(), EXTERNAL, CAMPUS, Instant.now(), "authenticate", JSON.createObjectNode());
    }
    private static HttpResponse<byte[]> post(AccountNodeServer server, byte[] body, boolean forwarded) throws Exception {
        return raw(base(server), externalTls(), "POST", AccountProtocol.PATH, "application/json", body, forwarded);
    }
    private static HttpClient http(SSLContext tls) {
        return HttpClient.newBuilder().sslContext(tls).connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    private static HttpRequest request(URI base, String method, String path, String contentType, byte[] body, boolean forwarded) {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (forwarded) builder.header("X-Forwarded-Client-Cert", certificates.external().pin())
                .header("X-SSL-CERT", "synthetic-certificate").header("Forwarded", "for=127.0.0.1;proto=https");
        return builder.build();
    }
    private static HttpResponse<byte[]> raw(URI base, SSLContext tls, String method, String path, String contentType,
                                            byte[] body, boolean forwarded) throws Exception {
        try (var client = http(tls)) {
            return client.send(request(base, method, path, contentType, body, forwarded), HttpResponse.BodyHandlers.ofByteArray());
        }
    }
    private static SSLSocket socket(AccountNodeServer server) throws Exception {
        var socket = (SSLSocket) externalTls().getSocketFactory().createSocket("localhost", server.port());
        socket.setSoTimeout(5000);
        var parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }
    private static void headers(SSLSocket socket, int length) throws IOException {
        socket.getOutputStream().write(("POST " + AccountProtocol.PATH + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    private static void assertSafeUnavailable(org.junit.jupiter.api.function.Executable request) {
        var failure = assertThrows(AccountUnavailableException.class, request);
        assertEquals("Account channel unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
    @FunctionalInterface private interface ExchangeHandler { void handle(HttpExchange exchange) throws IOException; }
    private record FakePeer(HttpsServer server, ExecutorService workers) implements AutoCloseable {
        URI base() { return URI.create("https://localhost:" + server.getAddress().getPort()); }
        @Override public void close() { server.stop(0); workers.shutdownNow(); }
    }
    private static FakePeer fake(ExchangeHandler handler) throws Exception {
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setHttpsConfigurator(new HttpsConfigurator(campusTls()) {
            @Override public void configure(HttpsParameters parameters) {
                var ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        var workers = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "account-test-peer");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try { handler.handle(exchange); }
            finally { exchange.close(); }
        });
        server.start();
        return new FakePeer(server, workers);
    }
}
