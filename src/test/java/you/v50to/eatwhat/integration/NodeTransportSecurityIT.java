package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real production HTTPS/TLS classes, synthetic certificates, and no business database or host configuration. */
class NodeTransportSecurityIT {
    @TempDir static Path directory;
    private static TransportTlsFixture certificates;
    private static final NodeIdentity CAMPUS = new NodeIdentity("campus-security", UUID.randomUUID());
    private static final NodeIdentity EXTERNAL = new NodeIdentity("external-security", UUID.randomUUID());
    private static final String PUSH = TransportProtocol.PREFIX + "/push";
    private final TransportProtocol protocol = new TransportProtocol();

    @BeforeAll static void createCertificates() throws Exception {
        certificates = TransportTlsFixture.create(directory.resolve("tls"));
    }

    @Test
    void realMutualTlsAndProtocolReplyWorkWithoutSaTokenOrBusinessRoutes() throws Exception {
        try (var server = server(certificates.context(certificates.external(), certificates.campus()), Duration.ofSeconds(5));
             var client = new SyncHttpClient(base(server), certificates.context(certificates.campus(), certificates.external()), Duration.ofSeconds(5))) {
            var request = emptyPush(CAMPUS, EXTERNAL);
            var response = client.exchange(PUSH, request);
            assertEquals(request.header().reply(), response.header());
            assertTrue(response.events().isEmpty());
            assertTrue(response.acks().isEmpty());
            assertEquals(404, raw(server, certificates.context(certificates.campus(), certificates.external()),
                    "POST", "/auth/login", "application/json", "{}", false).statusCode());
        }
    }

    @Test
    void absentUntrustedOrTrustedButUnpinnedClientCertificatesNeverReachTheProtocol() throws Exception {
        try (var server = server(certificates.context(certificates.external(), certificates.campus()), Duration.ofSeconds(5))) {
            List<SSLContext> rejected = List.of(certificates.withoutClientCertificate(),
                    certificates.context(certificates.untrusted(), certificates.external()),
                    certificates.context(certificates.otherTrusted(), certificates.external()));
            for (SSLContext context : rejected) {
                assertThrows(IOException.class, () -> raw(server, context, "POST", PUSH,
                        "application/json", encoded(emptyPush(CAMPUS, EXTERNAL)), true));
            }
        }
    }

    @Test
    void clientPreservesCertificateAuthorityPinAndHostnameChecks() throws Exception {
        try (var server = server(certificates.context(certificates.external(), certificates.campus()), Duration.ofSeconds(5))) {
            try (var client = new SyncHttpClient(base(server), certificates.context(certificates.campus(), certificates.otherTrusted()), Duration.ofSeconds(5))) {
                assertThrows(IOException.class, () -> client.exchange(PUSH, emptyPush(CAMPUS, EXTERNAL)), "Wrong server pin");
            }
        }
        for (var serverIdentity : List.of(certificates.untrusted(), certificates.wrongHostname())) {
            try (var server = server(certificates.context(serverIdentity, certificates.campus()), Duration.ofSeconds(5));
                 var client = new SyncHttpClient(base(server), certificates.context(certificates.campus(), serverIdentity), Duration.ofSeconds(5))) {
                // The configured fingerprint is correct in both cases, so pinning must not replace PKIX or hostname validation.
                assertThrows(IOException.class, () -> client.exchange(PUSH, emptyPush(CAMPUS, EXTERNAL)));
            }
        }
    }

    @Test
    void explicitlyConfiguredSecondPeerPinPermitsCertificateRotation() throws Exception {
        SSLContext serverTls = NodeTls.create(certificates.external().keyStore(), TransportTlsFixture.PASSWORD.toCharArray(),
                certificates.trustStore(), TransportTlsFixture.PASSWORD.toCharArray(),
                Set.of(certificates.campus().pin(), certificates.otherTrusted().pin()));
        try (var server = server(serverTls, Duration.ofSeconds(5))) {
            for (var clientIdentity : List.of(certificates.campus(), certificates.otherTrusted())) {
                try (var client = new SyncHttpClient(base(server), certificates.context(clientIdentity, certificates.external()), Duration.ofSeconds(5))) {
                    assertEquals(EXTERNAL, client.exchange(PUSH, emptyPush(CAMPUS, EXTERNAL)).header().sender());
                }
            }
        }
    }

    @Test
    void forwardedHeadersCannotOverridePinnedNodeSiteInstanceOrReceiver() throws Exception {
        try (var server = server(certificates.context(certificates.external(), certificates.campus()), Duration.ofSeconds(5))) {
            var tls = certificates.context(certificates.campus(), certificates.external());
            List<TransportProtocol.Request> requests = List.of(
                    emptyPush(new NodeIdentity("another-site", CAMPUS.instanceId()), EXTERNAL),
                    emptyPush(new NodeIdentity(CAMPUS.siteId(), UUID.randomUUID()), EXTERNAL),
                    emptyPush(CAMPUS, new NodeIdentity(EXTERNAL.siteId(), UUID.randomUUID())));
            for (var request : requests) {
                var response = raw(server, tls, "POST", PUSH, "application/json", encoded(request), true);
                assertEquals(403, response.statusCode());
                assertFalse(response.body().contains(CAMPUS.instanceId().toString()));
            }
        }
    }

    @Test
    void methodsPathsContentTypesAndMalformedBodiesAreRejectedWithSafeHttpErrors() throws Exception {
        try (var server = server(certificates.context(certificates.external(), certificates.campus()), Duration.ofSeconds(5))) {
            SSLContext tls = certificates.context(certificates.campus(), certificates.external());
            assertEquals(405, raw(server, tls, "GET", PUSH, "application/json", "", false).statusCode());
            assertEquals(404, raw(server, tls, "POST", PUSH + "/", "application/json", "{}", false).statusCode());
            assertEquals(404, raw(server, tls, "POST", TransportProtocol.PREFIX + "/%70ush", "application/json", "{}", false).statusCode());
            assertEquals(400, raw(server, tls, "POST", PUSH + "?sender=campus", "application/json", "{}", false).statusCode());
            assertEquals(400, raw(server, tls, "POST", PUSH, "text/plain", "{}", false).statusCode());
            for (String malformed : List.of("{", "{\"header\":{},\"header\":{}}", "{} {}", "{\"secret-sentinel\":true}")) {
                var response = raw(server, tls, "POST", PUSH, "application/json", malformed, false);
                assertEquals(400, response.statusCode());
                assertFalse(response.body().contains("secret-sentinel"));
                assertTrue(response.body().length() < 100);
            }
        }
    }

    @Test
    void declaredOversizeBodyIsRejectedBeforeReadingAndIncompleteBodyIsClosedWithinDeadline() throws Exception {
        SSLContext tls = certificates.context(certificates.campus(), certificates.external());
        try (var server = server(certificates.context(certificates.external(), certificates.campus()), Duration.ofMillis(500))) {
            try (SSLSocket socket = connected(tls, server)) {
                sendHeaders(socket, TransportProtocol.MAX_BODY_BYTES + 1);
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                assertTrue(reader.readLine().contains("413"));
            }
            try (SSLSocket socket = connected(tls, server)) {
                sendHeaders(socket, 100);
                assertEquals(-1, socket.getInputStream().read(), "The body read must not keep the exchange open indefinitely");
            }
            try (var client = new SyncHttpClient(base(server), tls, Duration.ofSeconds(5))) {
                assertEquals(EXTERNAL, client.exchange(PUSH, emptyPush(CAMPUS, EXTERNAL)).header().sender(),
                        "The timed-out body must release its admission permit");
            }
        }
    }

    @Test
    void unknownLengthBodyCannotExceedTheStreamingByteLimit() throws Exception {
        SSLContext tls = certificates.context(certificates.campus(), certificates.external());
        try (var server = server(certificates.context(certificates.external(), certificates.campus()), Duration.ofSeconds(5));
             SSLSocket socket = connected(tls, server)) {
            var output = socket.getOutputStream();
            output.write(("POST " + PUSH + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                    + Integer.toHexString(TransportProtocol.MAX_BODY_BYTES + 1) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[TransportProtocol.MAX_BODY_BYTES + 1]);
            output.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertTrue(reader.readLine().contains("413"));
        }
    }

    private static NodeHttpsServer server(SSLContext tls, Duration timeout) {
        // These security tests use only empty push and pre-service rejection paths. A database access is a test failure.
        var dataSource = new DriverManagerDataSource("jdbc:invalid:security-tests-must-not-connect");
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new SyncTransactions(jdbc, new DataSourceTransactionManager(dataSource));
        var codec = new EventCodec();
        var endpoint = new NodeEndpoint(EXTERNAL, CAMPUS,
                new InboxService(jdbc, transactions, codec, new EventHandlerRegistry(List.of())),
                new DeliveryService(jdbc, transactions, codec), codec, Duration.ofSeconds(5), Duration.ofMillis(10));
        var server = new NodeHttpsServer(new InetSocketAddress("127.0.0.1", 0), tls, endpoint, timeout);
        server.start();
        return server;
    }

    private TransportProtocol.Request emptyPush(NodeIdentity sender, NodeIdentity receiver) {
        return new TransportProtocol.Request(new TransportProtocol.Header(1, UUID.randomUUID(), sender, receiver), List.of(), List.of(), 0);
    }

    private String encoded(TransportProtocol.Request request) {
        return new String(protocol.encodeRequest(request), StandardCharsets.UTF_8);
    }

    private static URI base(NodeHttpsServer server) { return URI.create("https://localhost:" + server.port()); }

    private static HttpResponse<String> raw(NodeHttpsServer server, SSLContext tls, String method, String path,
                                            String contentType, String body, boolean forwarded) throws Exception {
        try (var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build()) {
            var request = HttpRequest.newBuilder(base(server).resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (forwarded) request.header("X-Forwarded-Client-Cert", certificates.campus().pin())
                    .header("X-SSL-CERT", "synthetic-certificate-header")
                    .header("Forwarded", "for=127.0.0.1;proto=https");
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static SSLSocket connected(SSLContext tls, NodeHttpsServer server) throws Exception {
        SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket("localhost", server.port());
        socket.setSoTimeout(5_000);
        var parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    private static void sendHeaders(SSLSocket socket, int contentLength) throws IOException {
        socket.getOutputStream().write(("POST " + PUSH + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + contentLength + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
}
