package you.v50to.eatwhat.sync.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import cn.dev33.satoken.stp.StpUtil;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.CatalogImportResult;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.ImportCounts;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.ImportStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Validates and deduplicates the controlled HTTP hub import before calling the catalog domain. */
@Service
@ConditionalOnProperty(name = "app.sync.catalog.enabled", havingValue = "true")
public class CatalogImportService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BATCH_HUBS = 100;
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final CatalogCommands commands;
    private final SiteProperties site;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CatalogImportReplication replication;
    public void setReplication(CatalogImportReplication value){this.replication=value;}

    public CatalogImportService(JdbcTemplate jdbc, SyncTransactions transactions, CatalogCommands commands,
                                SiteProperties site) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.transactions.requireSameDataSource(jdbc);
        this.commands = Objects.requireNonNull(commands);
        this.site = Objects.requireNonNull(site);
    }

    public CatalogImportResult importHubs(CatalogHubImportRequest request) {
        return importHubs(request, StpUtil.getLoginIdAsLong());
    }

    /** Explicit actor overload for trusted adapters and integration tests; the HTTP path uses Sa-Token above. */
    public CatalogImportResult importHubs(CatalogHubImportRequest request, Long actorUserId) {
        if (site.getMode() != SiteProperties.Mode.CAMPUS && !commands.multiWriterEnabled()) throw new BizException(BizCode.NO_PERMISSION);
        if (actorUserId == null) throw new BizException(BizCode.NO_PERMISSION);
        Normalized normalized;
        String canonical;
        try {
            normalized = normalize(request);
            canonical = EventCodec.canonicalJson(JSON.writeValueAsString(normalized.payload()));
            if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES
                    || !EventCodec.sha256(canonical).equals(request.contentHash())) {
                return blocked(request.requestId());
            }
        } catch (RuntimeException invalid) {
            return blocked(request == null ? null : request.requestId());
        }

        try {
            return transactions.required(() -> applyIdempotently(request, normalized.hubs(), actorUserId));
        } catch (IllegalArgumentException invalidDomainInput) {
            return blocked(request.requestId());
        }
    }

    private CatalogImportResult applyIdempotently(CatalogHubImportRequest request,
                                                  List<CatalogHubImportRequest.HubItem> hubs, Long actorUserId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                "eatwhat.catalog.import:" + request.sourceKey() + ":" + request.requestId());
        if(commands.multiWriterEnabled()) {
            if(replication==null)throw new IllegalStateException("IMPORT_REPLICATION_NOT_READY");
            var replay=replication.replay(request);if(replay!=null)return replay;
            replication.requireUnambiguousSources(request);
        }
        List<Batch> existing = jdbc.query("""
                SELECT batch_id,content_hash,status,created_count,updated_count
                FROM eatwhat_sync.catalog_import_batch WHERE source_key=? AND request_id=? FOR UPDATE
                """, (row, index) -> new Batch(row.getObject("batch_id", UUID.class),
                row.getString("content_hash").trim(), row.getString("status"), row.getInt("created_count"),
                row.getInt("updated_count")), request.sourceKey(), request.requestId());
        if (!existing.isEmpty()) {
            Batch batch = existing.getFirst();
            if (!batch.hash().equals(request.contentHash())) {
                return new CatalogImportResult(batch.id(), ImportStatus.CONFLICT, batch.created(), batch.updated());
            }
            if (!"APPLIED".equals(batch.status())) return new CatalogImportResult(batch.id(), ImportStatus.BLOCKED, 0, 0);
            return new CatalogImportResult(batch.id(), ImportStatus.REPLAYED, batch.created(), batch.updated());
        }

        UUID batchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_import_batch
                  (batch_id,source_key,request_id,content_hash,status,kind)
                VALUES (?,?,?,?,'APPLYING','HUB')
                """, batchId, request.sourceKey(), request.requestId(), request.contentHash());
        ImportCounts counts = commands.importHubBatchInTransaction(batchId, hubs, actorUserId);
        if(commands.multiWriterEnabled())replication.publish(batchId,request,counts,actorUserId);
        int changed = jdbc.update("""
                UPDATE eatwhat_sync.catalog_import_batch
                SET status='APPLIED',created_count=?,updated_count=?,completed_at=clock_timestamp()
                WHERE batch_id=? AND status='APPLYING'
                """, counts.created(), counts.updated(), batchId);
        if (changed != 1) throw new IllegalStateException("CATALOG_IMPORT_BATCH_STATE_CHANGED");
        return new CatalogImportResult(batchId, ImportStatus.APPLIED, counts.created(), counts.updated());
    }

    private static Normalized normalize(CatalogHubImportRequest request) {
        if (request == null || request.sourceKey() == null || !request.sourceKey().matches("[A-Za-z0-9._:-]{1,128}")
                || request.requestId() == null || request.contentHash() == null
                || !request.contentHash().matches("[0-9a-f]{64}")
                || request.hubs() == null || request.hubs().isEmpty() || request.hubs().size() > MAX_BATCH_HUBS) {
            throw new IllegalArgumentException("CATALOG_IMPORT_REQUEST_INVALID");
        }
        HashSet<String> sourceIds = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>(request.hubs().size());
        List<CatalogHubImportRequest.HubItem> normalizedHubs = new ArrayList<>(request.hubs().size());
        for (CatalogHubImportRequest.HubItem hub : request.hubs()) {
            if (hub == null || hub.sourceId() == null || hub.sourceId().isBlank() || hub.sourceId().length() > 256
                    || !sourceIds.add(hub.sourceId()) || hub.name() == null || hub.name().isBlank()
                    || hub.name().length() > 120 || hub.centerWgs() == null || hub.centerGcj() == null
                    || hub.boundaryGeoJson() == null || hub.boundaryGeoJson().isBlank()) {
                throw new IllegalArgumentException("CATALOG_IMPORT_HUB_INVALID");
            }
            validateCoordinate(hub.centerWgs());
            validateCoordinate(hub.centerGcj());
            String boundary = EventCodec.canonicalJson(hub.boundaryGeoJson());
            Map<String, Object> normalized = new LinkedHashMap<>(hub.canonicalPayload());
            normalized.put("boundaryGeoJson", boundary);
            result.add(normalized);
            normalizedHubs.add(new CatalogHubImportRequest.HubItem(hub.sourceId(), hub.name(), hub.centerWgs(),
                    hub.centerGcj(), boundary, hub.rebindCoveredRestaurants()));
        }
        return new Normalized(List.copyOf(normalizedHubs), List.copyOf(result));
    }

    private static void validateCoordinate(CatalogHubImportRequest.Coordinate coordinate) {
        if (!Double.isFinite(coordinate.lng()) || !Double.isFinite(coordinate.lat())
                || coordinate.lng() < -180 || coordinate.lng() > 180
                || coordinate.lat() < -90 || coordinate.lat() > 90) {
            throw new IllegalArgumentException("CATALOG_IMPORT_COORDINATE_INVALID");
        }
    }

    private static CatalogImportResult blocked(UUID requestId) {
        return new CatalogImportResult(requestId, ImportStatus.BLOCKED, 0, 0);
    }

    private record Batch(UUID id, String hash, String status, int created, int updated) { }
    private record Normalized(List<CatalogHubImportRequest.HubItem> hubs, List<Map<String, Object>> payload) { }
}
