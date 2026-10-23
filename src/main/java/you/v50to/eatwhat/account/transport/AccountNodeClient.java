package you.v50to.eatwhat.account.transport;

import tools.jackson.databind.JsonNode;
import you.v50to.eatwhat.account.AccountChannel;
import you.v50to.eatwhat.account.AccountUnavailableException;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Direct, single-attempt online RPC. No redirects, proxy, retries, event capture or payload diagnostics. */
public final class AccountNodeClient implements AccountChannel, AutoCloseable {
    private final URI target;
    private final NodeIdentity local;
    private final NodeIdentity peer;
    private final Duration timeout;
    private final HttpClient http;
    private final AccountProtocol protocol = new AccountProtocol();
    private final Semaphore requests = new Semaphore(8);

    public AccountNodeClient(URI baseUri, SSLContext tls, NodeIdentity local, NodeIdentity peer, Duration timeout) {
        validateBaseUri(baseUri);
        validateTimeout(timeout);
        this.local = Objects.requireNonNull(local);
        this.peer = Objects.requireNonNull(peer);
        if (local.siteId().equals(peer.siteId()) || local.instanceId().equals(peer.instanceId())) {
            throw new IllegalArgumentException("Account peer must be a distinct node");
        }
        this.target = baseUri.resolve(AccountProtocol.PATH);
        this.timeout = timeout;
        var parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        http = HttpClient.newBuilder().sslContext(Objects.requireNonNull(tls)).sslParameters(parameters)
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build();
    }

    public static void validateBaseUri(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Account peer must be a fixed HTTPS origin");
        }
    }

    static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid account request timeout");
        }
    }

    @Override public JsonNode call(String operation, JsonNode body) {
        if (!requests.tryAcquire()) throw new AccountUnavailableException();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var frame = new AccountProtocol.Frame(AccountProtocol.VERSION, UUID.randomUUID(), local, peer,
                    Instant.now(), operation, body);
            var request = HttpRequest.newBuilder(target).timeout(timeout).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("Cache-Control", "no-store")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(protocol.encode(frame))).build();
            pending = http.sendAsync(request, ignored -> new LimitedBody());
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200 || response.headers().allValues("Content-Type").size() != 1
                    || !response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT)
                    .matches("application/json(?:\\s*;\\s*charset\\s*=\\s*utf-8)?")
                    || response.headers().firstValue("Content-Encoding").isPresent()) {
                throw new AccountUnavailableException();
            }
            var reply = protocol.decode(response.body());
            if (!reply.isReplyTo(frame)) throw new AccountUnavailableException();
            return reply.body();
        } catch (InterruptedException failure) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new AccountUnavailableException();
        } catch (Exception failure) {
            if (pending != null) pending.cancel(true);
            throw new AccountUnavailableException();
        } finally {
            requests.release();
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
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > AccountProtocol.MAX_BODY_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(new AccountUnavailableException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(new AccountUnavailableException()); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
