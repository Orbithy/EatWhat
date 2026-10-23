package you.v50to.eatwhat.service.storage.replication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Generic signed webhook provider (no vendor code). POSTs JSON {"taskId","reason","paths","timestamp"} where paths are
 * CDN-relative. Headers: X-Signature = lowercase hex HMAC-SHA256 of the raw request body with the configured secret;
 * X-Timestamp = the same epoch-seconds value that is inside the signed body (so replay windows can be enforced).
 * 2xx = Accepted (request id from the X-Request-Id header or a JSON "requestId" field, when present);
 * 429, 5xx, I/O errors and timeouts = Retryable; any other status = Permanent. Redirects are not followed.
 * The secret and the request/response bodies are never logged.
 *
 * <p>The whole call, response body included, is bounded by the configured timeout, and at most {@link #MAX_BODY_BYTES}
 * of the response body are ever read (the rest is dropped by closing the stream), so a hostile or broken endpoint can
 * neither hold the worker thread nor make it buffer unbounded data.
 */
@Slf4j
public final class HttpWebhookCdnProvider implements CdnInvalidationProvider {
    public static final String SIGNATURE_HEADER = "X-Signature";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    /** Upper bound of response body bytes read (only an optional request id is extracted from it). */
    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoint;
    private final byte[] secret;
    private final Duration timeout;
    private final HttpClient client;
    private final java.util.concurrent.ExecutorService bodyReader = java.util.concurrent.Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "cdn-webhook-body");
        thread.setDaemon(true);
        return thread;
    });

    public HttpWebhookCdnProvider(URI endpoint, String secret, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.secret = Objects.requireNonNull(secret, "secret").getBytes(StandardCharsets.UTF_8);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** HMAC-SHA256 hex of a raw body; exposed so tests and receivers can verify with the same code path. */
    public static String sign(byte[] secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    @Override
    public Result submit(List<String> cdnPaths, Reason reason, UUID taskId) {
        long timestamp = Instant.now().getEpochSecond();
        ObjectNode body = JSON.createObjectNode();
        body.put("taskId", taskId.toString());
        body.put("reason", reason.name());
        ArrayNode paths = body.putArray("paths");
        cdnPaths.forEach(paths::add);
        body.put("timestamp", timestamp);
        byte[] raw;
        try {
            raw = JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            return new Permanent("ENCODE_ERROR");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(SIGNATURE_HEADER, sign(secret, raw))
                .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
                .build();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            // The request timeout bounds the wait for the response HEADERS; the body is read under the same deadline below.
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (java.io.InputStream responseBody = response.body()) {
                if (status >= 200 && status < 300) {
                    return new Accepted(requestId(response.headers().firstValue("X-Request-Id").orElse(null), responseBody, deadline));
                }
                if (status == 429 || status >= 500) {
                    log.warn("CDN webhook for task {} answered {} (retryable)", taskId, status);
                    return new Retryable("HTTP_" + status);
                }
                log.warn("CDN webhook for task {} answered {} (permanent)", taskId, status);
                return new Permanent("HTTP_" + status);
            }
        } catch (HttpTimeoutException | java.util.concurrent.TimeoutException timedOut) {
            log.warn("CDN webhook for task {} timed out", taskId);
            return new Retryable("TIMEOUT");
        } catch (IOException io) {
            log.warn("CDN webhook for task {} failed with {}", taskId, io.getClass().getSimpleName());
            return new Retryable("IO_ERROR");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Retryable("INTERRUPTED");
        }
    }

    /**
     * Request id from the header, else from a capped, deadline-bound read of the body. A body that does not finish
     * within the deadline (slow drip) makes the call Retryable (TimeoutException); an oversized body is simply cut off
     * at the cap.
     */
    private String requestId(String header, java.io.InputStream body, long deadlineNanos)
            throws java.util.concurrent.TimeoutException, InterruptedException {
        if (header != null && !header.isBlank()) {
            return truncate(header.trim());
        }
        java.util.concurrent.Future<byte[]> read = bodyReader.submit(() -> body.readNBytes(MAX_BODY_BYTES));
        byte[] bytes;
        try {
            long remaining = Math.max(1L, deadlineNanos - System.nanoTime());
            bytes = read.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException | InterruptedException failure) {
            read.cancel(true);
            try {
                body.close(); // unblocks the reader thread
            } catch (IOException ignored) {
                // best effort
            }
            throw failure;
        } catch (java.util.concurrent.ExecutionException failure) {
            return null; // unreadable body: the 2xx status still means accepted
        }
        try {
            if (bytes.length > 0) {
                JsonNode node = JSON.readTree(bytes);
                JsonNode id = node == null ? null : node.get("requestId");
                if (id != null && id.isTextual() && !id.asText().isBlank()) {
                    return truncate(id.asText().trim());
                }
            }
        } catch (IOException notJson) {
            // No usable id in the body; the task is still accepted.
        }
        return null;
    }

    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
