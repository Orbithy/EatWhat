package you.v50to.eatwhat.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.function.Supplier;

/** Validates explicit deployments before bean creation, Flyway, or any dependency connection. */
public class SiteConfigurationValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        boolean independentControl=environment.getProperty("app.control.independent.enabled",Boolean.class,false);
        if(independentControl||"independent-local".equals(environment.getProperty("app.control.database-mode")))
            require(independentControl&&"independent-local".equals(environment.getProperty("app.control.database-mode")),
                    "app.control.database-mode","independent-local requires explicitly enabled independent management");
        Binder binder = Binder.get(environment);
        SiteProperties site = bind(binder, "app.site", SiteProperties.class, SiteProperties::new);
        S3StorageProperties storage = bind(binder, "app.storage.s3", S3StorageProperties.class, S3StorageProperties::new);

        // Validate file replication configuration (done early, even for LEGACY mode).
        FileReplicationProperties replication = bind(binder, "app.storage.files.replication", FileReplicationProperties.class, FileReplicationProperties::new);
        validateFileReplication(replication, site, storage);

        // Validate the report-only inventory settings (done early, for every site mode).
        FileGcProperties gc = bind(binder, "app.storage.files.gc", FileGcProperties.class, FileGcProperties::new);
        FileLifecycleProperties lifecycle = bind(binder, "app.storage.files", FileLifecycleProperties.class, FileLifecycleProperties::new);
        validateFileGc(gc, lifecycle, storage, site);
        if(gc.isCoordinatedEnabled()) {
            require(environment.getProperty("app.sync.files.enabled",Boolean.class,false),"app.storage.files.gc.coordinated-enabled","requires app.sync.files.enabled=true");
            require(gc.isEnabled(),"app.storage.files.gc.coordinated-enabled","requires gc.enabled=true");
            require(site.getMode()==SiteProperties.Mode.CAMPUS||site.getMode()==SiteProperties.Mode.EXTERNAL,"app.storage.files.gc.coordinated-enabled","requires an explicit paired site");
        }
        validateFileLifecycleLimits(lifecycle);

        // Validate the CDN invalidation worker settings (done early, for every site mode).
        FileCdnInvalidationProperties cdn = bind(binder, "app.storage.files.cdn-invalidation",
                FileCdnInvalidationProperties.class, FileCdnInvalidationProperties::new);
        validateCdnInvalidation(cdn, storage, replication);

        if (site.getMode() == SiteProperties.Mode.LEGACY && storage.getProvider() == S3StorageProperties.Provider.GENERIC) {
            return; // Preserve the original lazy storage configuration and single-site behavior.
        }
        boolean paired = (site.getMode() == SiteProperties.Mode.CAMPUS && storage.getProvider() == S3StorageProperties.Provider.RUSTFS)
                || (site.getMode() == SiteProperties.Mode.EXTERNAL && storage.getProvider() == S3StorageProperties.Provider.R2);
        if(site.getClusterId()!=null)paired=(site.getMode()==SiteProperties.Mode.CAMPUS||site.getMode()==SiteProperties.Mode.EXTERNAL)&&(storage.getProvider()==S3StorageProperties.Provider.RUSTFS||storage.getProvider()==S3StorageProperties.Provider.R2);
        require(paired, "app.site.mode / app.storage.s3.provider", "must be CAMPUS/RUSTFS or EXTERNAL/R2 together");
        require(site.getId() != null && site.getId().matches("[a-z][a-z0-9-]{0,62}"), "app.site.id",
                "must be a stable lowercase site identifier (1-63 letters, digits or hyphens, starting with a letter)");
        require(site.getInstanceId() != null
                        && (site.getInstanceId().getMostSignificantBits() != 0 || site.getInstanceId().getLeastSignificantBits() != 0),
                "app.site.instance-id", "must be a nonzero UUID kept stable for this database installation");
        require("true".equalsIgnoreCase(environment.getProperty("spring.flyway.enabled", "true")),
                "spring.flyway.enabled", "must remain enabled for explicit site identity initialization and validation");

        String database = environment.getProperty("spring.datasource.url");
        require(hasValue(database) && database.startsWith("jdbc:postgresql://"), "spring.datasource.url",
                "must explicitly select this site's PostgreSQL database");
        String migrationDatabase = environment.getProperty("spring.flyway.url");
        require(!StringUtils.hasText(migrationDatabase) || databaseAddress(database).equals(databaseAddress(migrationDatabase)),
                "spring.flyway.url", "must select the same PostgreSQL database as spring.datasource.url");

        URI endpoint = httpUri(storage.getEndpoint(), "app.storage.s3.endpoint");
        require(endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty() || endpoint.getRawPath().equals("/"),
                "app.storage.s3.endpoint", "must be an S3 API origin without a path");
        required(storage.getRegion(), "app.storage.s3.region");
        required(storage.getBucket(), "app.storage.s3.bucket");
        required(storage.getAccessKey(), "app.storage.s3.access-key");
        required(storage.getSecretKey(), "app.storage.s3.secret-key");
        require(storage.getSignGetTtlSeconds() > 0 && storage.getSignGetTtlSeconds() <= 604800,
                "app.storage.s3.sign-get-ttl-seconds", "must be between 1 and 604800");
        require(storage.getSignPutTtlSeconds() > 0 && storage.getSignPutTtlSeconds() <= 604800,
                "app.storage.s3.sign-put-ttl-seconds", "must be between 1 and 604800");
        require(storage.getMaxFileSizeBytes() > 0, "app.storage.s3.max-file-size-bytes", "must be positive");
        nonblankEntries(storage.getAllowedContentTypes(), "app.storage.s3.allowed-content-types");
        nonblankEntries(storage.getAllowedReadPrefixes(), "app.storage.s3.allowed-read-prefixes");
        nonblankEntries(storage.getPublicReadPrefixes(), "app.storage.s3.public-read-prefixes");

        if (storage.getProvider() == S3StorageProperties.Provider.R2) {
            require("https".equalsIgnoreCase(endpoint.getScheme()) && (endpoint.getPort() == -1 || endpoint.getPort() == 443)
                            && endpoint.getHost().toLowerCase(Locale.ROOT)
                            .matches("[a-f0-9]{32}(?:\\.(?:eu|us|fedramp))?\\.r2\\.cloudflarestorage\\.com"),
                    "app.storage.s3.endpoint", "must be a Cloudflare R2 HTTPS S3 API origin");
            // A project convention, not a claim that R2 rejects its documented region aliases.
            require("auto".equals(storage.getRegion()), "app.storage.s3.region", "must be auto for this project's R2 mode");
        } else {
            require(!endpoint.getHost().toLowerCase(Locale.ROOT).endsWith(".r2.cloudflarestorage.com"),
                    "app.storage.s3.endpoint", "must not select Cloudflare R2 in RUSTFS mode");
        }
        if (storage.getPublicReadPrefixes() != null && !storage.getPublicReadPrefixes().isEmpty()) {
            required(storage.getPublicBaseUrl(), "app.storage.s3.public-base-url");
        }
        if (StringUtils.hasText(storage.getPublicBaseUrl())) {
            URI publicBase = httpUri(storage.getPublicBaseUrl(), "app.storage.s3.public-base-url");
            require(Arrays.stream(publicBase.getPath().split("/", -1)).noneMatch(part -> part.equals(".") || part.equals("..")),
                    "app.storage.s3.public-base-url", "must not contain dot path segments");
        }
    }

    private static void validateFileReplication(FileReplicationProperties replication, SiteProperties site,
                                                S3StorageProperties storage) {
        // If worker is enabled, enabled must also be true.
        require(!replication.isWorkerEnabled() || replication.isEnabled(),
                "app.storage.files.replication.worker-enabled",
                "true requires app.storage.files.replication.enabled=true");

        // Worker can only run on CAMPUS (has credentials for both LOCAL and PEER stores).
        if (replication.isWorkerEnabled()) {
            require(site.getMode() == SiteProperties.Mode.CAMPUS,
                    "app.storage.files.replication.worker-enabled",
                    "only supported in CAMPUS mode; EXTERNAL and LEGACY sites cannot reach both object stores");
        }

        // EXTERNAL catalogs metadata and deletes only its own R2 copy. It never needs campus credentials.
        // CAMPUS retains fail-fast peer validation, even while its copy runner is paused.
        if (replication.isEnabled() && site.getMode()!=SiteProperties.Mode.EXTERNAL && site.getClusterId()==null) {
            FileReplicationProperties.PeerConfig peer = replication.getPeer();
            require(hasValue(peer.getEndpoint()), "app.storage.files.replication.peer.endpoint",
                    "is required when replication is enabled");
            URI peerUri = httpUri(peer.getEndpoint(), "app.storage.files.replication.peer.endpoint");
            require(peerUri.getRawPath() == null || peerUri.getRawPath().isEmpty() || peerUri.getRawPath().equals("/"),
                    "app.storage.files.replication.peer.endpoint", "must be an S3 API origin without a path");
            required(peer.getBucket(), "app.storage.files.replication.peer.bucket");
            required(peer.getAccessKey(), "app.storage.files.replication.peer.access-key");
            required(peer.getSecretKey(), "app.storage.files.replication.peer.secret-key");
        }

        // Validate lease-seconds: 10 to 3600.
        require(replication.getLeaseSeconds() >= 10 && replication.getLeaseSeconds() <= 3600,
                "app.storage.files.replication.lease-seconds",
                "must be between 10 and 3600 seconds");

        // Validate batch: 1 to 100.
        require(replication.getBatch() >= 1 && replication.getBatch() <= 100,
                "app.storage.files.replication.batch",
                "must be between 1 and 100");

        // Validate retry backoff parameters.
        require(replication.getRetryBase().toMillis() > 0,
                "app.storage.files.replication.retry-base",
                "must be greater than 0");
        require(replication.getRetryMax().toMillis() >= replication.getRetryBase().toMillis(),
                "app.storage.files.replication.retry-max",
                "must be >= retry-base");
        require(replication.getJitter() >= 0.0 && replication.getJitter() <= 0.5,
                "app.storage.files.replication.jitter",
                "must be between 0.0 and 0.5");

        // Validate poll interval: at least 100ms.
        require(replication.getPollInterval().toMillis() >= 100,
                "app.storage.files.replication.poll-interval",
                "must be at least 100 milliseconds");

        // Validate alert threshold: at least 1 attempt.
        require(replication.getAlertAfterAttempts() >= 1,
                "app.storage.files.replication.alert-after-attempts",
                "must be at least 1");

        // Verify mode is always FULL_READBACK (only enum value).
        require(replication.getVerifyMode() == FileReplicationProperties.VerifyMode.FULL_READBACK,
                "app.storage.files.replication.verify-mode",
                "must be FULL_READBACK");

        // Multipart copy settings (stage 07E). Checked for every deployment so a bad value cannot sit dormant.
        long minPart = 5L * 1024 * 1024;
        long maxPart = 1024L * 1024 * 1024;
        require(replication.getPartSize() >= minPart && replication.getPartSize() <= maxPart,
                "app.storage.files.replication.part-size",
                "must be between 5 MiB (5242880) and 1 GiB (1073741824) bytes");
        require(replication.getMultipartThreshold() >= replication.getPartSize(),
                "app.storage.files.replication.multipart-threshold",
                "must be >= app.storage.files.replication.part-size");
        long maxFile = storage.getMaxFileSizeBytes();
        require(maxFile <= 0 || replication.getPartSize() <= 0
                        || (maxFile + replication.getPartSize() - 1) / replication.getPartSize() <= 10_000L,
                "app.storage.files.replication.part-size",
                "is too small: app.storage.s3.max-file-size-bytes / part-size must not exceed 10000 parts");
        require(replication.getTempReserveBytes() >= 0,
                "app.storage.files.replication.temp-reserve-bytes", "must be >= 0");
        require(replication.getMaxBytesPerSecond() >= 0,
                "app.storage.files.replication.max-bytes-per-second", "must be >= 0 (0 means unlimited)");
    }

    private static void validateFileLifecycleLimits(FileLifecycleProperties lifecycle) {
        require(lifecycle.getMaxOpenIntentsPerUser() >= 0,
                "app.storage.files.max-open-intents-per-user", "must be >= 0 (0 means unlimited)");
        require(lifecycle.getMaxBytesPerHourPerUser() >= 0,
                "app.storage.files.max-bytes-per-hour-per-user", "must be >= 0 (0 means unlimited)");
    }

    private static void validateCdnInvalidation(FileCdnInvalidationProperties cdn, S3StorageProperties storage,
                                                FileReplicationProperties replication) {
        String prefix = "app.storage.files.cdn-invalidation.";
        require(cdn.getProvider() != null, prefix + "provider", "must be NOOP or WEBHOOK");
        // The runner waits 35 seconds for a graceful stop, so a call (which includes the body read) must end well within it.
        require(cdn.getTimeout() != null && cdn.getTimeout().compareTo(java.time.Duration.ofSeconds(1)) >= 0
                        && cdn.getTimeout().compareTo(java.time.Duration.ofSeconds(30)) <= 0,
                prefix + "timeout", "must be between 1 second and 30 seconds");
        require(cdn.getBatch() >= 1 && cdn.getBatch() <= 100, prefix + "batch", "must be between 1 and 100");
        require(cdn.getLease() != null && cdn.getLease().compareTo(java.time.Duration.ofSeconds(30)) >= 0
                        && cdn.getLease().compareTo(java.time.Duration.ofMinutes(15)) <= 0,
                prefix + "lease", "must be between 30 seconds and 15 minutes");
        require(cdn.getLease().compareTo(cdn.getTimeout()) > 0, prefix + "lease", "must be longer than the timeout");
        require(cdn.getRetryBase() != null && cdn.getRetryBase().toMillis() > 0, prefix + "retry-base", "must be greater than 0");
        require(cdn.getRetryMax() != null && cdn.getRetryMax().compareTo(cdn.getRetryBase()) >= 0
                        && cdn.getRetryMax().compareTo(java.time.Duration.ofHours(24)) <= 0,
                prefix + "retry-max", "must be >= retry-base and at most 24 hours");
        require(cdn.getJitter() >= 0.0 && cdn.getJitter() <= 0.5, prefix + "jitter", "must be between 0.0 and 0.5");
        require(cdn.getPollInterval() != null && cdn.getPollInterval().toMillis() >= 100,
                prefix + "poll-interval", "must be at least 100 milliseconds");
        require(cdn.getAlertAfterAttempts() >= 1, prefix + "alert-after-attempts", "must be at least 1");
        if (cdn.isEnabled() && cdn.getProvider() == FileCdnInvalidationProperties.Provider.NOOP && !cdn.isAssumeNoCdn()) {
            // NOOP marks every revocation SKIPPED/NO_PROVIDER: only acceptable when there is no CDN to invalidate.
            require(!StringUtils.hasText(storage.getPublicBaseUrl()) && !replication.isCdnOutputEnabled(),
                    prefix + "provider",
                    "NOOP cannot be used while app.storage.s3.public-base-url or replication.cdn-output-enabled is set; "
                            + "configure WEBHOOK, or set " + prefix + "assume-no-cdn=true to confirm there is no CDN");
        }
        if (cdn.isEnabled() && cdn.getProvider() == FileCdnInvalidationProperties.Provider.WEBHOOK) {
            URI webhook = httpUri(cdn.getWebhookUrl(), prefix + "webhook-url");
            require("https".equalsIgnoreCase(webhook.getScheme()), prefix + "webhook-url", "must be an https URL");
            require(StringUtils.hasText(webhook.getHost()), prefix + "webhook-url", "must have a host");
            // httpUri already rejected userinfo, query and fragment: no credentials or tokens inside the URL.
            require(hasValue(cdn.getWebhookSecret()) && cdn.getWebhookSecret().length() >= 16, prefix + "webhook-secret",
                    "is required, must be resolved and at least 16 characters when the WEBHOOK provider is enabled");
        }
    }

    private static void validateFileGc(FileGcProperties gc, FileLifecycleProperties lifecycle, S3StorageProperties storage,
                                       SiteProperties site) {
        require(gc.getPageSize() >= 1 && gc.getPageSize() <= 1000,
                "app.storage.files.gc.page-size", "must be between 1 and 1000");
        require(gc.getReplicaCheckBatch() >= 1 && gc.getReplicaCheckBatch() <= 10000,
                "app.storage.files.gc.replica-check-batch", "must be between 1 and 10000");
        require(gc.getInterval() != null && gc.getInterval().compareTo(java.time.Duration.ofMinutes(1)) >= 0,
                "app.storage.files.gc.interval", "must be at least 1 minute");
        require(gc.getSafetyWindow() != null && !gc.getSafetyWindow().isNegative(),
                "app.storage.files.gc.safety-window", "must be a non-negative duration");
        nonblankEntries(gc.getLegacyPrefixes(), "app.storage.files.gc.legacy-prefixes");

        // Physical GC executor settings. These are checked for every deployment so that a bad value can never
        // sit dormant behind an execute flag that is flipped later.
        require(gc.getRetention() != null && gc.getRetention().compareTo(java.time.Duration.ofDays(7)) >= 0,
                "app.storage.files.gc.retention", "must be at least 7 days");
        require(gc.getGcInterval() != null && gc.getGcInterval().compareTo(java.time.Duration.ofMinutes(1)) >= 0,
                "app.storage.files.gc.gc-interval", "must be at least 1 minute");
        require(gc.getClaimLease() != null && gc.getClaimLease().compareTo(java.time.Duration.ofSeconds(60)) >= 0
                        && gc.getClaimLease().compareTo(java.time.Duration.ofMinutes(15)) <= 0,
                "app.storage.files.gc.claim-lease", "must be between 60 seconds and 15 minutes");
        require(gc.getMaxDeletesPerRun() >= 1 && gc.getMaxDeletesPerRun() <= 1000,
                "app.storage.files.gc.max-deletes-per-run", "must be between 1 and 1000");
        require(gc.getStuckAlertAfterAttempts() >= 1, "app.storage.files.gc.stuck-alert-after-attempts", "must be at least 1");
        require(gc.getCandidateBatch() >= 1 && gc.getCandidateBatch() <= 10000,
                "app.storage.files.gc.candidate-batch", "must be between 1 and 10000");
        require(gc.getExecuteClasses() != null
                        && gc.getExecuteClasses().stream().allMatch(c -> c != null && FileGcProperties.EXECUTE_CLASSES.contains(c)),
                "app.storage.files.gc.execute-classes",
                "may only contain STAGING, ORPHAN_CANDIDATE, UNREFERENCED_REVISION and STALE_MULTIPART");
        require(gc.getMultipartTtl() != null && gc.getMultipartTtl().compareTo(java.time.Duration.ofHours(1)) >= 0,
                "app.storage.files.gc.multipart-ttl", "must be at least 1 hour");
        require(!gc.isEnabled() || gc.isInventoryEnabled(), "app.storage.files.gc.enabled",
                "true requires app.storage.files.gc.inventory-enabled=true");
        if (gc.isExecuteEnabled()) {
            require(gc.isEnabled(), "app.storage.files.gc.execute-enabled",
                    "true requires app.storage.files.gc.enabled=true");
            require(gc.isInventoryEnabled(), "app.storage.files.gc.execute-enabled",
                    "true requires app.storage.files.gc.inventory-enabled=true");
            require(!gc.isDryRun(), "app.storage.files.gc.dry-run",
                    "must be false when app.storage.files.gc.execute-enabled=true");
            require(site.getMode() == SiteProperties.Mode.CAMPUS || (site.getMode()==SiteProperties.Mode.EXTERNAL&&gc.isCoordinatedEnabled()), "app.storage.files.gc.execute-enabled",
                    "true is only supported when app.site.mode=CAMPUS (the site that owns the primary object store)");
            if(site.getMode()==SiteProperties.Mode.EXTERNAL) require(gc.getExecuteClasses().equals(java.util.Set.of("UNREFERENCED_REVISION")),"app.storage.files.gc.execute-classes","EXTERNAL coordinated GC only permits UNREFERENCED_REVISION");
            require(FileGcProperties.isAckUsable(gc.getExecuteAck()), "app.storage.files.gc.execute-ack",
                    "is required and must be resolved when app.storage.files.gc.execute-enabled=true (restore drill id)");
            require(!gc.getExecuteClasses().isEmpty(), "app.storage.files.gc.execute-classes",
                    "must name at least one object class when app.storage.files.gc.execute-enabled=true");
        }
        if (!gc.isInventoryEnabled()) {
            return;
        }
        // The window must outlive every way a not-yet-registered object can legitimately appear:
        // an unexpired presigned PUT, an upload intent, and a signed GET, plus a 15 minute lease floor.
        long windowSeconds = gc.getSafetyWindow().toSeconds();
        long floorSeconds = Math.max(15 * 60L, Math.max(lifecycle.getIntentTtlSeconds(),
                Math.max(storage.getSignPutTtlSeconds(), storage.getSignGetTtlSeconds())));
        require(windowSeconds >= floorSeconds,
                "app.storage.files.gc.safety-window",
                "must be at least 15 minutes and not shorter than the upload intent TTL and presigned URL TTLs ("
                        + floorSeconds + " seconds), otherwise in-flight uploads could be reported as stale");
    }

    private static <T> T bind(Binder binder, String prefix, Class<T> type, Supplier<T> defaults) {
        try {
            return binder.bind(prefix, Bindable.of(type)).orElseGet(defaults);
        } catch (BindException failure) {
            // Spring conversion errors retain rejected values in their cause chain.
            throw invalid(failure.getName().toString(), "cannot be bound to its expected configuration type");
        }
    }

    private static URI httpUri(String value, String key) {
        required(value, key);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ignored) {
            throw invalid(key, "must be a valid HTTP(S) URL");
        }
        require(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && StringUtils.hasText(uri.getHost()) && uri.getRawUserInfo() == null
                        && uri.getRawQuery() == null && uri.getRawFragment() == null,
                key, "must be an HTTP(S) URL without userinfo, query or fragment");
        return uri;
    }

    private static String databaseAddress(String value) {
        return value.split("\\?", 2)[0];
    }

    private static void nonblankEntries(List<String> values, String key) {
        require(values == null || values.stream().allMatch(SiteConfigurationValidator::hasValue), key,
                "must not contain blank or unresolved entries");
    }

    private static boolean hasValue(String value) {
        return StringUtils.hasText(value) && !value.contains("${");
    }

    private static void required(String value, String key) {
        require(hasValue(value), key, "is required and must be resolved");
    }

    private static void require(boolean condition, String key, String explanation) {
        if (!condition) {
            throw invalid(key, explanation);
        }
    }

    private static IllegalStateException invalid(String key, String explanation) {
        // Do not include values or parsing causes: endpoint URLs and credentials can contain secrets.
        return new IllegalStateException("Invalid deployment configuration: " + key + " " + explanation);
    }
}
