package you.v50to.eatwhat.sync.transport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Direct HTTPS to one configured peer, full-body deadline, bounded allocation and no redirects. */
public final class SyncHttpClient implements TransportClient, AutoCloseable {
    private final URI base;
    private final Duration timeout;
    private final HttpClient http;
    private final TransportProtocol protocol = new TransportProtocol();

    public SyncHttpClient(URI peerBase, SSLContext tls, Duration timeout) {
        validateBaseUri(peerBase);
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid transport request timeout");
        }
        base = peerBase; this.timeout = timeout;
        var parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        http = HttpClient.newBuilder().sslContext(tls).sslParameters(parameters)
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build();
    }

    public static void validateBaseUri(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Peer base must be a fixed HTTPS origin without credentials or path");
        }
    }

    @Override
    public TransportProtocol.Response exchange(String path, TransportProtocol.Request request) throws Exception {
        if (!Set.of(TransportProtocol.PREFIX + "/push", TransportProtocol.PREFIX + "/pull",
                TransportProtocol.PREFIX + "/ack").contains(path)) throw new IllegalArgumentException("Invalid sync path");
        var outbound = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(protocol.encodeRequest(request))).build();
        var pending = http.sendAsync(outbound, ignored -> new LimitedBody());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IOException("Peer did not return a successful sync response");
            if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT)
                    .matches("application/json(?:\\s*;.*)?")) throw new IOException("Invalid sync response content type");
            return protocol.decodeResponse(response.body());
        } catch (InterruptedException interrupted) {
            pending.cancel(true); Thread.currentThread().interrupt();
            throw new IOException("Sync request interrupted");
        } catch (Exception failure) {
            pending.cancel(true);
            // Never retain a URL, TLS secret, untrusted response or parser message in a persisted diagnostic.
            throw new IOException("Sync exchange failed");
        }
    }

    @Override public void close() { http.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > TransportProtocol.MAX_BODY_BYTES) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Sync response exceeds byte limit")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(new IOException("Sync response read failed")); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
