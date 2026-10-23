package you.v50to.eatwhat.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings for the object-store inventory (report-only) and the physical GC executor. Everything defaults to off.
 * The inventory never deletes or modifies any object. The executor only ever deletes when execute-enabled=true,
 * dry-run=false, the object class is listed in execute-classes, the site is CAMPUS (or coordinated EXTERNAL revisions) and execute-ack is set; the
 * default is a dry run that records what it would do.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage.files.gc")
public class FileGcProperties {
    /** Enable the periodic report-only inventory. Defaults to false. */
    private boolean inventoryEnabled = false;

    /** Delay between inventory cycles. Defaults to 1 hour. */
    private Duration interval = Duration.ofHours(1);

    /** Objects listed per ListObjectsV2 page (1..1000). Defaults to 500. */
    private int pageSize = 500;

    /** Objects/rows modified more recently than now minus this window are ignored. Defaults to 1 hour. */
    private Duration safetyWindow = Duration.ofHours(1);

    /** Prefix of canonical, verified objects. */
    private String verifiedPrefix = "verified/";

    /** Prefix of staging (presigned upload) objects. */
    private String stagingPrefix = "staging/";

    /** Legacy key prefixes that bypass the file lifecycle; reported only. */
    private List<String> legacyPrefixes = new ArrayList<>();

    /** Number of file_replica rows HEAD-checked per batch. Defaults to 200. */
    private int replicaCheckBatch = 200;

    // ---- Physical GC executor (stage 07C). Needs gc.enabled AND inventory-enabled. ----

    /**
     * Master switch for the GC (candidate detection, evaluation and the runner). Defaults to false.
     * inventory-enabled alone only enables the report-only inventory; it never starts the GC loop.
     * Requires inventory-enabled=true.
     */
    private boolean enabled = false;

    /**
     * The single definition of a usable execute-ack: non-blank and not an unresolved placeholder such as "${X}".
     * Used by both the startup validator and the runtime gate so they can never disagree.
     */
    public static boolean isAckUsable(String ack) {
        return org.springframework.util.StringUtils.hasText(ack) && !ack.contains("${");
    }

    /** Allowed values of {@link #executeClasses}. */
    public static final Set<String> EXECUTE_CLASSES = Set.of("STAGING", "ORPHAN_CANDIDATE", "UNREFERENCED_REVISION", "STALE_MULTIPART");

    /** How long a revision must have been unreferenced before it can be deleted. Defaults to 30 days; minimum 7 days. */
    private Duration retention = Duration.ofDays(30);

    /** When true (default) the GC only evaluates and records ELIGIBLE/BLOCKED/REVIVED; it never touches the store. */
    private boolean dryRun = true;

    /** Master switch for physical deletion. Defaults to false. */
    private boolean executeEnabled = false;

    /** Explicit opt-in for durable bilateral deletion fences; requires the file event channel. */
    private boolean coordinatedEnabled = false;

    /**
     * Object classes the executor may delete: STAGING, ORPHAN_CANDIDATE, UNREFERENCED_REVISION, and STALE_MULTIPART
     * (abort of abandoned multipart uploads, which obeys every gate below). Defaults to none.
     */
    private Set<String> executeClasses = new LinkedHashSet<>();

    /** Identifier of the signed restore drill that authorises the first execute run. Never logged or stored. */
    @ToString.Exclude
    private String executeAck = "";

    /**
     * An in-progress multipart upload is reported (and, with the STALE_MULTIPART class enabled, aborted) once it is
     * older than max(multipart-ttl, safety-window) and neither a READY/LEASED transfer job nor an open upload intent
     * owns it. Minimum 1 hour; defaults to 24 hours.
     */
    private Duration multipartTtl = Duration.ofHours(24);

    /** Maximum objects deleted (plus multipart uploads aborted) per run (1..1000). Defaults to 100. */
    private int maxDeletesPerRun = 100;

    /** Delay between GC cycles (at least 1 minute). Also the re-check delay of BLOCKED candidates. Defaults to 1 hour. */
    private Duration gcInterval = Duration.ofHours(1);

    /** Lease a candidate claim holds (60s..15m). Defaults to 5 minutes. */
    private Duration claimLease = Duration.ofMinutes(5);

    /** A DELETING candidate that failed this many times raises one GC_DELETE_STUCK alert (>= 1). Defaults to 10. */
    private int stuckAlertAfterAttempts = 10;

    /** Candidates examined per run (1..10000). Defaults to 500. */
    private int candidateBatch = 500;
}
