package you.v50to.eatwhat.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Settings for the CDN invalidation worker (stage 07D). Everything defaults to off. Tasks are enqueued by the database
 * whenever a revision that once had a PUBLIC reference becomes unreferenced; the worker only runs when enabled=true.
 * No vendor-specific provider exists: NOOP (marks tasks SKIPPED/NO_PROVIDER) or a generic signed WEBHOOK.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage.files.cdn-invalidation")
public class FileCdnInvalidationProperties {
    public enum Provider { NOOP, WEBHOOK }

    /** Enable the invalidation worker. Defaults to false. */
    private boolean enabled = false;

    /** NOOP (default) or WEBHOOK. */
    private Provider provider = Provider.NOOP;

    /** WEBHOOK: https endpoint that receives {taskId, reason, paths, timestamp}. No credentials in the URL. */
    private String webhookUrl;

    /** WEBHOOK: HMAC-SHA256 secret for the X-Signature header. Never logged. */
    @ToString.Exclude
    private String webhookSecret;

    /**
     * Acknowledge that the site has no CDN in front of the object store. Required to run the NOOP provider while a
     * public base URL or CDN output is configured (NOOP would otherwise mark revocations SKIPPED although cached
     * copies may exist). Defaults to false.
     */
    private boolean assumeNoCdn = false;

    /** Whole-call deadline of one webhook call, response body included (1s..30s). Defaults to 10 seconds. */
    private Duration timeout = Duration.ofSeconds(10);

    /** Tasks claimed per cycle (1..100). Defaults to 8. */
    private int batch = 8;

    /** Task lease (30s..15m); must be longer than the webhook timeout. Defaults to 2 minutes. */
    private Duration lease = Duration.ofMinutes(2);

    /** Initial retry delay. Defaults to 5 seconds. */
    private Duration retryBase = Duration.ofSeconds(5);

    /** Maximum retry delay. Defaults to 30 minutes. */
    private Duration retryMax = Duration.ofMinutes(30);

    /** Jitter factor for backoff (0.0-0.5). Defaults to 0.2. */
    private double jitter = 0.2;

    /** Delay between worker cycles (at least 100 ms). Defaults to 5 seconds. */
    private Duration pollInterval = Duration.ofSeconds(5);

    /** Attempts after which ONE alert is written; the task keeps retrying. Defaults to 50. */
    private int alertAfterAttempts = 50;
}
