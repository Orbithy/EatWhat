package you.v50to.eatwhat.account.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import you.v50to.eatwhat.account.AccountRequestHandler;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Private mTLS listener, isolated from business MVC and the PR04 event transport. */
public final class AccountNodeServer implements AutoCloseable {
    private final HttpsServer server;
    private final NodeIdentity local;
    private final NodeIdentity peer;
    private final AccountRequestHandler handler;
    private final Duration timeout;
    private final AccountProtocol protocol = new AccountProtocol();
    private final AccountReplayWindow replay = new AccountReplayWindow();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Semaphore admissions = new Semaphore(4);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private double rateTokens = 32;
    private long rateUpdated = System.nanoTime();

    public AccountNodeServer(InetSocketAddress bind, SSLContext tls, NodeIdentity local, NodeIdentity peer,
                             AccountRequestHandler handler, Duration timeout) {
        Objects.requireNonNull(bind);
        Objects.requireNonNull(tls);
        this.local = Objects.requireNonNull(local);
        this.peer = Objects.requireNonNull(peer);
        this.handler = Objects.requireNonNull(handler);
        AccountNodeClient.validateTimeout(timeout);
        if (bind.isUnresolved() || local.siteId().equals(peer.siteId()) || local.instanceId().equals(peer.instanceId())) {
            throw new IllegalArgumentException("Invalid account listener identity or bind address");
        }
        this.timeout = timeout;
        configureJdkBounds();
        workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), threads("account-https"), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, threads("account-https-deadline"));
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
            throw new IllegalStateException("Account listener could not be created");
        }
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("Account listener is closed");
        if (started.compareAndSet(false, true)) server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) {
        AtomicBoolean expired = new AtomicBoolean();
        var deadline = deadlines.schedule(() -> {
            expired.set(true);
            exchange.close();
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean admitted = false;
        try {
            if (!(exchange instanceof HttpsExchange secure) || secure.getSSLSession().getPeerPrincipal() == null) {
                fail(exchange, 403, "NODE_AUTH_REQUIRED"); return;
            }
            if (!AccountProtocol.PATH.equals(exchange.getRequestURI().getRawPath())) {
                fail(exchange, 404, "NOT_FOUND"); return;
            }
            if (exchange.getRequestURI().getRawQuery() != null) { fail(exchange, 400, "INVALID_REQUEST"); return; }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                fail(exchange, 405, "METHOD_NOT_ALLOWED"); return;
            }
            if (!takeRateToken() || !(admitted = admissions.tryAcquire())) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                fail(exchange, 429, "NODE_BUSY"); return;
            }
            int headerFailure = validateHeaders(exchange);
            if (headerFailure != 0) { fail(exchange, headerFailure, "INVALID_REQUEST"); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(AccountProtocol.MAX_BODY_BYTES + 1);
            if (bytes.length > AccountProtocol.MAX_BODY_BYTES) { fail(exchange, 413, "REQUEST_TOO_LARGE"); return; }
            if (expired.get()) return;
            AccountProtocol.Frame request;
            try { request = protocol.decode(bytes); }
            catch (IllegalArgumentException failure) { fail(exchange, 400, "INVALID_REQUEST"); return; }
            if (!request.sender().equals(peer) || !request.receiver().equals(local)) {
                fail(exchange, 403, "NODE_IDENTITY_MISMATCH"); return;
            }
            switch (replay.reserve(request.requestId(), request.issuedAt(), Instant.now(), System.nanoTime())) {
                case STALE -> { fail(exchange, 400, "STALE_REQUEST"); return; }
                case REPLAY -> { fail(exchange, 409, "REQUEST_REPLAY"); return; }
                case FULL -> { fail(exchange, 503, "NODE_BUSY"); return; }
                case ACCEPTED -> { }
            }
            if (expired.get()) return;
            // No generic record serialization or exception message is permitted on this path.
            // A deadline ends network waiting; a committed handler action requires its own domain idempotency.
            var response = handler.handle(request.operation(), request.body());
            if (!expired.get()) send(exchange, 200, protocol.encode(request.reply(response)));
        } catch (Exception failure) {
            if (!expired.get()) fail(exchange, 503, "ACCOUNT_UNAVAILABLE");
        } finally {
            deadline.cancel(false);
            if (admitted) admissions.release();
            exchange.close();
        }
    }

    private static int validateHeaders(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders();
        List<String> contentTypes = headers.get("Content-Type");
        if (contentTypes == null || contentTypes.size() != 1 || !contentTypes.getFirst().toLowerCase(Locale.ROOT)
                .matches("application/json(?:\\s*;\\s*charset\\s*=\\s*utf-8)?") || headers.containsKey("Content-Encoding")) return 400;
        List<String> lengths = headers.get("Content-Length");
        if (lengths != null) {
            if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,18}")) return 400;
            if (Long.parseLong(lengths.getFirst()) > AccountProtocol.MAX_BODY_BYTES) return 413;
        }
        return 0;
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

    private static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        if (exchange.getResponseCode() != -1) return;
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    // Same process-wide JDK resource bounds as PR04. Values are cached by JDK HttpServer on first creation.
    // Configure these at JVM startup when another library may construct a JDK listener first.
    private static synchronized void configureJdkBounds() {
        bound("jdk.httpserver.maxConnections", 32, false);
        bound("sun.net.httpserver.maxReqTime", 30, false);
        bound("sun.net.httpserver.maxRspTime", 30, false);
        bound("sun.net.httpserver.maxReqHeaders", 32, false);
        bound("sun.net.httpserver.maxReqHeaderSize", 16 * 1024, false);
        bound("sun.net.httpserver.idleInterval", 5, false);
        bound("sun.net.httpserver.clockTick", 1000, false);
        bound("sun.net.httpserver.timerMillis", 1000, false);
        bound("sun.net.httpserver.maxIdleConnections", 0, true);
        bound("sun.net.httpserver.drainAmount", 0, true);
    }

    private static void bound(String name, long maximum, boolean zeroAllowed) {
        long value = maximum;
        String configured = System.getProperty(name);
        if (configured != null) {
            try {
                long parsed = Long.parseLong(configured);
                if (parsed > 0 || (zeroAllowed && parsed == 0)) value = Math.min(parsed, maximum);
            } catch (NumberFormatException failure) { throw new IllegalStateException("Invalid account listener resource bound"); }
        }
        System.setProperty(name, Long.toString(value));
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
