package you.v50to.eatwhat.service.storage.replication;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.service.storage.replication.InventoryObjectStore.ObjectEntry;
import you.v50to.eatwhat.service.storage.replication.InventoryObjectStore.ObjectPage;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Report-only reconciliation of object stores against the file lifecycle database.
 *
 * <p>This service only lists and HEADs objects (and lists in-progress multipart uploads) and writes rows to file_gc_run,
 * file_inventory_finding and (for size mismatches) file_replica_alert. It never deletes or writes
 * an object, never changes replica, transfer-job or revision state, and never enqueues repairs.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.gc.inventory-enabled", havingValue = "true")
public class FileInventoryService {
    public static final String LOCAL = "LOCAL";
    public static final String PEER = "PEER";

    static final String MISSING_OBJECT = "MISSING_OBJECT";
    static final String SIZE_MISMATCH = "SIZE_MISMATCH";
    static final String UNKNOWN_VERIFIED = "UNKNOWN_VERIFIED";
    static final String STALE_STAGING = "STALE_STAGING";
    static final String ORPHAN_CANDIDATE = "ORPHAN_CANDIDATE";
    static final String LEGACY_KEY = "LEGACY_KEY";
    static final String STALE_MULTIPART = "STALE_MULTIPART";

    private static final int MAX_ERROR_TEXT = 2000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param runId run identifier, or null when the site was skipped
     * @param listed number of objects listed from the store
     * @param findings number of findings by kind
     * @param errors number of per-page/per-object errors recorded
     * @param error failure text when the run failed (for example the store listing failed), else null
     */
    public record InventoryResult(UUID runId, long listed, Map<String, Long> findings, long errors, String error) {
        public long totalFindings() {
            return findings.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private record Finding(String key, String kind, Instant lastModified, String detail) { }

    private record RevisionInfo(UUID fileId, int revision, long sizeBytes) { }

    private final JdbcTemplate jdbc;
    private final FileGcProperties props;
    private final Supplier<ReplicaObjectStore> localStore;
    private final Supplier<ReplicaObjectStore> peerStore;
    private final Object fallbackLock = new Object();
    private S3ReplicaObjectStore fallbackLocal;

    @Autowired
    public FileInventoryService(JdbcTemplate jdbc,
                                FileGcProperties props,
                                S3StorageProperties s3Properties,
                                @Qualifier("localReplicaStore") ObjectProvider<ReplicaObjectStore> localProvider,
                                @Qualifier("peerReplicaStore") ObjectProvider<ReplicaObjectStore> peerProvider) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.props = Objects.requireNonNull(props, "FileGcProperties");
        this.localStore = () -> {
            ReplicaObjectStore bean = localProvider.getIfAvailable();
            return bean != null ? bean : lazyLocalFallback(s3Properties);
        };
        this.peerStore = peerProvider::getIfAvailable;
    }

    /** Direct wiring for tests and embedding; a supplier returns null when its site is unavailable. */
    public FileInventoryService(JdbcTemplate jdbc,
                                FileGcProperties props,
                                Supplier<ReplicaObjectStore> localStore,
                                Supplier<ReplicaObjectStore> peerStore) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.props = Objects.requireNonNull(props, "FileGcProperties");
        this.localStore = Objects.requireNonNull(localStore, "localStore");
        this.peerStore = Objects.requireNonNull(peerStore, "peerStore");
    }

    /** Builds the local store from S3StorageProperties on first use, exactly as ReplicationStorageConfiguration does. */
    private ReplicaObjectStore lazyLocalFallback(S3StorageProperties s3) {
        synchronized (fallbackLock) {
            if (fallbackLocal == null) {
                log.info("Inventory: building local store lazily from S3StorageProperties");
                fallbackLocal = new S3ReplicaObjectStore(s3.getEndpoint(), s3.getRegion(), s3.getBucket(),
                        s3.getAccessKey(), s3.getSecretKey(), s3.isPathStyle());
            }
            return fallbackLocal;
        }
    }

    @PreDestroy
    void closeFallback() {
        synchronized (fallbackLock) {
            if (fallbackLocal != null) {
                fallbackLocal.close();
                fallbackLocal = null;
            }
        }
    }

    /** Run one report-only inventory for the given site (LOCAL or PEER). */
    public InventoryResult runInventory(String site) {
        if (!LOCAL.equals(site) && !PEER.equals(site)) {
            throw new IllegalArgumentException("site must be LOCAL or PEER");
        }
        ReplicaObjectStore resolved = LOCAL.equals(site) ? localStore.get() : peerStore.get();
        if (resolved == null) {
            log.info("Inventory skipped for site {}: object store is not available", site);
            return new InventoryResult(null, 0, new LinkedHashMap<>(), 0, null);
        }
        if (!(resolved instanceof InventoryObjectStore store)) {
            log.warn("Inventory skipped for site {}: object store does not support listing", site);
            return new InventoryResult(null, 0, new LinkedHashMap<>(), 0, null);
        }

        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_gc_run(run_id, site, mode, config_snapshot) VALUES (?, ?, 'INVENTORY', ?::jsonb)",
                runId, site, snapshot(site));
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        Instant cutoff = now.minus(props.getSafetyWindow());

        Run run = new Run(runId, site, store, now, cutoff);
        String failure = null;
        try {
            scanStaging(run);
            scanVerified(run);
            for (String legacy : props.getLegacyPrefixes()) {
                scanLegacy(run, legacy);
            }
            checkReplicas(run);
            scanStaleMultipart(run);
        } catch (RuntimeException listingFailure) {
            failure = truncate(listingFailure.getClass().getSimpleName() + ": " + listingFailure.getMessage());
            log.error("Inventory run {} for site {} failed", runId, site, listingFailure);
        }
        String error = failure != null ? failure : run.firstError;
        long findingTotal = run.findings.values().stream().mapToLong(Long::longValue).sum();
        jdbc.update("""
                UPDATE eatwhat_file.file_gc_run
                SET finished_at=clock_timestamp(), listed_count=?, finding_count=?, error_count=?, error=?
                WHERE run_id=?
                """, run.listed, findingTotal, run.errors + (failure != null ? 1 : 0), error, runId);
        log.info("Inventory run {} site={} listed={} findings={} errors={}", runId, site, run.listed, run.findings, run.errors);
        return new InventoryResult(runId, run.listed, run.findings, run.errors + (failure != null ? 1 : 0), failure);
    }

    private final class Run {
        final UUID runId;
        final String site;
        final InventoryObjectStore store;
        final Instant now;
        final Instant cutoff;
        long listed;
        long errors;
        String firstError;
        final Map<String, Long> findings = new LinkedHashMap<>();

        Run(UUID runId, String site, InventoryObjectStore store, Instant now, Instant cutoff) {
            this.runId = runId;
            this.site = site;
            this.store = store;
            this.now = now;
            this.cutoff = cutoff;
        }

        void recordError(String where, Exception failure) {
            errors++;
            if (firstError == null) {
                firstError = truncate(where + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            log.warn("Inventory run {} error at {}", runId, where, failure);
        }
    }

    // ---- store -> DB direction ----

    /** Pages one prefix; a store listing failure propagates and fails the run. */
    private void scanPrefix(Run run, String prefix, PageHandler handler) {
        String token = null;
        do {
            ObjectPage page = run.store.listObjects(prefix, token, props.getPageSize());
            List<ObjectEntry> entries = page.entries().stream().filter(e -> !e.key().equals(prefix)).toList();
            run.listed += entries.size();
            List<ObjectEntry> old = entries.stream()
                    .filter(e -> e.lastModified() == null || !e.lastModified().isAfter(run.cutoff)).toList();
            if (!old.isEmpty()) {
                try {
                    handler.handle(run, old);
                } catch (RuntimeException failure) {
                    run.recordError("page of prefix " + prefix, failure);
                }
            }
            token = page.nextToken();
        } while (token != null);
    }

    private interface PageHandler {
        void handle(Run run, List<ObjectEntry> entries);
    }

    private void scanStaging(Run run) {
        scanPrefix(run, props.getStagingPrefix(), (r, entries) -> {
            Map<String, Instant> expiry = new HashMap<>();
            queryByKeys("SELECT staging_key, expires_at FROM eatwhat_file.upload_intent WHERE staging_key = ANY (?)",
                    entries, rs -> expiry.put(rs.getString(1), rs.getTimestamp(2).toInstant()));
            List<Finding> out = new ArrayList<>();
            for (ObjectEntry e : entries) {
                Instant expiresAt = expiry.get(e.key());
                if (expiresAt == null) {
                    out.add(new Finding(e.key(), STALE_STAGING, e.lastModified(), "no upload_intent for staging object"));
                } else if (expiresAt.isBefore(r.cutoff)) {
                    out.add(new Finding(e.key(), STALE_STAGING, e.lastModified(), "upload_intent expired at " + expiresAt));
                }
            }
            insertFindings(r, out);
        });
    }

    private void scanVerified(Run run) {
        scanPrefix(run, props.getVerifiedPrefix(), (r, entries) -> {
            Map<String, RevisionInfo> revisions = new HashMap<>();
            queryByKeys("SELECT final_key, file_id, revision, size_bytes FROM eatwhat_file.file_revision WHERE final_key = ANY (?)",
                    entries, rs -> revisions.put(rs.getString(1),
                            new RevisionInfo(rs.getObject(2, UUID.class), rs.getInt(3), rs.getLong(4))));
            List<ObjectEntry> unknown = entries.stream().filter(e -> !revisions.containsKey(e.key())).toList();
            Set<String> orphans = new HashSet<>();
            if (!unknown.isEmpty()) {
                queryByKeys("""
                        SELECT object_key FROM eatwhat_file.file_orphan_candidate
                        WHERE object_key = ANY (?) AND state IN ('WRITING','WRITTEN','ORPHANED')
                        """, unknown, rs -> orphans.add(rs.getString(1)));
            }
            List<Finding> out = new ArrayList<>();
            for (ObjectEntry e : entries) {
                RevisionInfo rev = revisions.get(e.key());
                if (rev == null) {
                    out.add(orphans.contains(e.key())
                            ? new Finding(e.key(), ORPHAN_CANDIDATE, e.lastModified(), "object matches an open file_orphan_candidate")
                            : new Finding(e.key(), UNKNOWN_VERIFIED, e.lastModified(), "no file_revision references this verified object"));
                } else if (rev.sizeBytes() != e.size()) {
                    String message = "object size " + e.size() + " differs from recorded size " + rev.sizeBytes() + " for key " + e.key();
                    out.add(new Finding(e.key(), SIZE_MISMATCH, e.lastModified(), message));
                    jdbc.update("""
                            INSERT INTO eatwhat_file.file_replica_alert(alert_id, file_id, revision, site, alert_type, message)
                            SELECT ?, ?, ?, ?, 'SIZE_MISMATCH', ?
                            WHERE NOT EXISTS (SELECT 1 FROM eatwhat_file.file_replica_alert
                                              WHERE file_id=? AND revision=? AND site=? AND alert_type='SIZE_MISMATCH')
                            """, UUID.randomUUID(), rev.fileId(), rev.revision(), r.site, message,
                            rev.fileId(), rev.revision(), r.site);
                }
            }
            insertFindings(r, out);
        });
    }

    private void scanLegacy(Run run, String prefix) {
        if (prefix == null || prefix.isBlank()
                || prefix.startsWith(props.getStagingPrefix()) || props.getStagingPrefix().startsWith(prefix)
                || prefix.startsWith(props.getVerifiedPrefix()) || props.getVerifiedPrefix().startsWith(prefix)) {
            log.warn("Inventory ignores legacy prefix '{}': blank or overlapping the staging/verified prefixes", prefix);
            return;
        }
        scanPrefix(run, prefix, (r, entries) -> insertFindings(r, entries.stream()
                .map(e -> new Finding(e.key(), LEGACY_KEY, e.lastModified(), "object under legacy prefix " + prefix))
                .toList()));
    }

    // ---- DB -> store direction ----

    private void checkReplicas(Run run) {
        String state = LOCAL.equals(run.site) ? "AVAILABLE_LOCAL" : "PEER_VERIFIED";
        Timestamp cutoff = Timestamp.from(run.cutoff);
        UUID lastFile = null;
        int lastRevision = 0;
        while (true) {
            List<Map<String, Object>> rows;
            try {
                if (lastFile == null) {
                    rows = jdbc.queryForList("""
                            SELECT file_id, revision, object_key FROM eatwhat_file.file_replica
                            WHERE site=? AND state=? AND updated_at <= ?
                            ORDER BY file_id, revision LIMIT ?
                            """, run.site, state, cutoff, props.getReplicaCheckBatch());
                } else {
                    rows = jdbc.queryForList("""
                            SELECT file_id, revision, object_key FROM eatwhat_file.file_replica
                            WHERE site=? AND state=? AND updated_at <= ? AND (file_id, revision) > (?, ?)
                            ORDER BY file_id, revision LIMIT ?
                            """, run.site, state, cutoff, lastFile, lastRevision, props.getReplicaCheckBatch());
                }
            } catch (RuntimeException failure) {
                run.recordError("replica batch query", failure);
                return;
            }
            if (rows.isEmpty()) return;
            List<Finding> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String key = (String) row.get("object_key");
                try {
                    if (run.store.head(key).isEmpty()) {
                        out.add(new Finding(key, MISSING_OBJECT, null,
                                "file_replica " + row.get("file_id") + "#" + row.get("revision") + " is " + state + " but the object is absent"));
                    }
                } catch (RuntimeException failure) {
                    run.recordError("head " + key, failure);
                }
            }
            try {
                insertFindings(run, out);
            } catch (RuntimeException failure) {
                run.recordError("replica findings insert", failure);
            }
            Map<String, Object> last = rows.get(rows.size() - 1);
            lastFile = (UUID) last.get("file_id");
            lastRevision = ((Number) last.get("revision")).intValue();
            if (rows.size() < props.getReplicaCheckBatch()) return;
        }
    }

    // ---- multipart uploads ----

    /**
     * Reports abandoned multipart uploads (never aborts them: only the GC can, behind its gates). A listing failure
     * (for example a store that cannot list uploads) is recorded as a run error instead of failing the whole run.
     */
    private void scanStaleMultipart(Run run) {
        Instant cutoff = run.now.minus(StaleMultipartPolicy.minimumAge(props));
        for (String prefix : new String[]{props.getStagingPrefix(), props.getVerifiedPrefix()}) {
            if (prefix == null || prefix.isBlank()) continue;
            List<InventoryObjectStore.MultipartUploadEntry> uploads;
            try {
                uploads = run.store.listMultipartUploads(prefix);
            } catch (RuntimeException failure) {
                run.recordError("list multipart uploads under " + prefix, failure);
                continue;
            }
            List<Finding> out = new ArrayList<>();
            for (InventoryObjectStore.MultipartUploadEntry upload : uploads) {
                try {
                    if (upload.initiated() == null || upload.initiated().isAfter(cutoff)
                            || !StaleMultipartPolicy.isManagedKey(props, upload.key())
                            || StaleMultipartPolicy.isOwned(jdbc, upload.key(), upload.uploadId())) {
                        continue;
                    }
                    out.add(new Finding(upload.key(), STALE_MULTIPART, upload.initiated(),
                            "uploadId=" + upload.uploadId() + " initiated=" + upload.initiated()));
                } catch (RuntimeException failure) {
                    run.recordError("classify multipart upload of " + upload.key(), failure);
                }
            }
            try {
                insertFindings(run, out);
            } catch (RuntimeException failure) {
                run.recordError("multipart findings insert", failure);
            }
        }
    }

    // ---- helpers ----

    private interface RowSink {
        void accept(java.sql.ResultSet rs) throws java.sql.SQLException;
    }

    private void queryByKeys(String sql, List<ObjectEntry> entries, RowSink sink) {
        String[] keys = entries.stream().map(ObjectEntry::key).toArray(String[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            Array array = con.createArrayOf("text", keys);
            ps.setArray(1, array);
            return ps;
        }, (org.springframework.jdbc.core.RowCallbackHandler) sink::accept);
    }

    private void insertFindings(Run run, List<Finding> findings) {
        if (findings.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO eatwhat_file.file_inventory_finding(run_id, site, object_key, kind, object_last_modified, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """, findings, findings.size(), (ps, f) -> {
            ps.setObject(1, run.runId);
            ps.setString(2, run.site);
            ps.setString(3, f.key());
            ps.setString(4, f.kind());
            ps.setTimestamp(5, f.lastModified() == null ? null : Timestamp.from(f.lastModified()));
            ps.setString(6, f.detail());
        });
        for (Finding f : findings) {
            run.findings.merge(f.kind(), 1L, Long::sum);
        }
    }

    private String snapshot(String site) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("site", site);
        config.put("pageSize", props.getPageSize());
        config.put("safetyWindowSeconds", props.getSafetyWindow().toSeconds());
        config.put("intervalSeconds", props.getInterval().toSeconds());
        config.put("verifiedPrefix", props.getVerifiedPrefix());
        config.put("stagingPrefix", props.getStagingPrefix());
        config.put("legacyPrefixes", props.getLegacyPrefixes());
        config.put("replicaCheckBatch", props.getReplicaCheckBatch());
        config.put("multipartTtlSeconds", props.getMultipartTtl().toSeconds());
        try {
            return JSON.writeValueAsString(config);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return "{}";
        }
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_ERROR_TEXT ? text : text.substring(0, MAX_ERROR_TEXT);
    }
}
