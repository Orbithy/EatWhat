package you.v50to.eatwhat.sync.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated node listener; it neither exposes nor changes the Spring MVC business authentication chain. */
public final class NodeHttpsServer implements AutoCloseable {
    private static final Set<String> PATHS = Set.of(TransportProtocol.PREFIX + "/push",
            TransportProtocol.PREFIX + "/pull", TransportProtocol.PREFIX + "/ack");
    private static final int THREADS = 8;
    private final HttpsServer server;
    private final NodeEndpoint endpoint;
    private final TransportProtocol protocol = new TransportProtocol();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Semaphore admissions = new Semaphore(4);
    private final Duration requestTimeout;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private double rateTokens = 32;
    private long rateUpdated = System.nanoTime();

    public NodeHttpsServer(InetSocketAddress bind, SSLContext tls, NodeEndpoint endpoint, Duration requestTimeout) {
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(tls, "tls");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (bind.isUnresolved() || requestTimeout == null || requestTimeout.isNegative()
                || requestTimeout.toMillis() < 1 || requestTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Invalid node listener bounds");
        }
        this.requestTimeout = requestTimeout;
        configureJdkBounds();
        workers = new ThreadPoolExecutor(THREADS, THREADS, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), threads("node-https"), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, threads("node-https-deadline"));
        deadlines.setRemoveOnCancelPolicy(true);
        try {
            server = HttpsServer.create(bind, 32);
            server.setHttpsConfigurator(new HttpsConfigurator(tls) {
                @Override public void configure(HttpsParameters parameters) {
                    SSLParameters ssl = tls.getDefaultSSLParameters();
                    ssl.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    ssl.setNeedClientAuth(true);
                    parameters.setSSLParameters(ssl);
                }
            });
            server.setExecutor(workers);
            server.createContext("/", this::handle);
        } catch (IOException | RuntimeException failure) {
            workers.shutdownNow();
            deadlines.shutdownNow();
            throw new IllegalStateException("Node HTTPS listener could not be created");
        }
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("Node HTTPS listener is closed");
        if (started.compareAndSet(false, true)) server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    /**
     * JDK HttpServer caches these process-wide properties on first use (they do not configure Tomcat).
     * Set the same bounds as JVM startup options if any other component may create a JDK server first.
     * OpenJDK 21 ServerImpl.getTimeMillis multiplies maxReqTime/maxRspTime by 1000: values are seconds,
     * despite the Java 21 module documentation describing those two properties as milliseconds.
     */
    static synchronized void configureJdkBounds() {
        boundProperty("jdk.httpserver.maxConnections", 32, false);
        boundProperty("sun.net.httpserver.maxReqTime", 30, false);
        boundProperty("sun.net.httpserver.maxRspTime", 30, false);
        boundProperty("sun.net.httpserver.maxReqHeaders", 32, false);
        boundProperty("sun.net.httpserver.maxReqHeaderSize", 16 * 1024, false);
        boundProperty("sun.net.httpserver.idleInterval", 5, false);
        boundProperty("sun.net.httpserver.clockTick", 1000, false);
        boundProperty("sun.net.httpserver.timerMillis", 1000, false);
        boundProperty("sun.net.httpserver.maxIdleConnections", 0, true);
        boundProperty("sun.net.httpserver.drainAmount", 0, true);
    }

    private static void boundProperty(String name, long maximum, boolean zeroAllowed) {
        String configured = System.getProperty(name);
        long value = maximum;
        if (configured != null) {
            try {
                long parsed = Long.parseLong(configured);
                if (parsed > 0 || (zeroAllowed && parsed == 0)) value = Math.min(parsed, maximum);
            } catch (NumberFormatException failure) {
                throw new IllegalStateException("Invalid JDK node listener resource bound");
            }
        }
        System.setProperty(name, Long.toString(value));
    }

    private void handle(HttpExchange exchange) {
        AtomicBoolean timedOut = new AtomicBoolean();
        var deadline = deadlines.schedule(() -> {
            timedOut.set(true);
            exchange.close();
        }, requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean admitted = false;
        try {
            if (!(exchange instanceof HttpsExchange secure) || secure.getSSLSession().getPeerPrincipal() == null) {
                fail(exchange, 403, "NODE_AUTH_REQUIRED");
                return;
            }
            if (!PATHS.contains(exchange.getRequestURI().getRawPath())) {
                fail(exchange, 404, "NOT_FOUND");
                return;
            }
            if (exchange.getRequestURI().getRawQuery() != null) {
                fail(exchange, 400, "INVALID_REQUEST");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                fail(exchange, 405, "METHOD_NOT_ALLOWED");
                return;
            }
            if (!takeRateToken() || !(admitted = admissions.tryAcquire())) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                fail(exchange, 429, "NODE_BUSY");
                return;
            }
            validateHeaders(exchange);
            byte[] body = exchange.getRequestBody().readNBytes(TransportProtocol.MAX_BODY_BYTES + 1);
            if (body.length > TransportProtocol.MAX_BODY_BYTES) throw new TransportException(413, "REQUEST_TOO_LARGE");
            if (timedOut.get()) return;
            TransportProtocol.Request request = protocol.decodeRequest(body);
            TransportProtocol.Response response = endpoint.handle(exchange.getRequestURI().getRawPath(), request);
            if (!timedOut.get()) send(exchange, 200, protocol.encodeResponse(response));
        } catch (TransportException failure) {
            if (!timedOut.get()) fail(exchange, failure.httpStatus(), failure.code());
        } catch (IllegalArgumentException failure) {
            if (!timedOut.get()) fail(exchange, 400, "INVALID_REQUEST");
        } catch (Exception failure) {
            if (!timedOut.get()) fail(exchange, 503, "NODE_UNAVAILABLE");
        } finally {
            deadline.cancel(false);
            if (admitted) admissions.release();
            exchange.close();
        }
    }

    private static void validateHeaders(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders();
        List<String> contentTypes = headers.get("Content-Type");
        if (contentTypes == null || contentTypes.size() != 1 || !contentTypes.getFirst().toLowerCase(Locale.ROOT)
                .matches("application/json(?:\\s*;\\s*charset\\s*=\\s*utf-8)?") || headers.containsKey("Content-Encoding")) {
            throw new TransportException(400, "INVALID_CONTENT_TYPE");
        }
        List<String> lengths = headers.get("Content-Length");
        if (lengths != null) {
            if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,18}")) {
                throw new TransportException(400, "INVALID_CONTENT_LENGTH");
            }
            if (Long.parseLong(lengths.getFirst()) > TransportProtocol.MAX_BODY_BYTES) {
                throw new TransportException(413, "REQUEST_TOO_LARGE");
            }
        }
    }

    private synchronized boolean takeRateToken() {
        long now = System.nanoTime();
        rateTokens = Math.min(32, rateTokens + (now - rateUpdated) / 1_000_000_000.0 * 16);
        rateUpdated = now;
        if (rateTokens < 1) return false;
        rateTokens -= 1;
        return true;
    }

    private static void fail(HttpExchange exchange, int status, String code) {
        try { send(exchange, status, ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { exchange.close(); }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (exchange.getResponseCode() != -1) return;
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            workers.shutdownNow();
            deadlines.shutdownNow();
        }
    }
}
