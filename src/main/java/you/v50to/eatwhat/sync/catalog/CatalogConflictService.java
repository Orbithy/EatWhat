package you.v50to.eatwhat.sync.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.catalog.CatalogCommands.Status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** CAMPUS-admin candidate listing and revision-checked resolution. */
@Service
@ConditionalOnProperty(name = "app.sync.catalog.enabled", havingValue = "true")
public class CatalogConflictService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Comparator<CandidateVersion> VERSION_ORDER = Comparator.comparing(CandidateVersion::gid);

    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final CatalogStore store;
    private final CatalogCommands commands;
    private final SiteProperties site;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CatalogDecisionService decisions;
    public void setDecisionService(CatalogDecisionService value) { decisions=value; }
    public List<CatalogDecisionService.DecisionView> listDecisions() { requireCampus(); return decisions==null?List.of():decisions.list(); }

    public record AuthorizationReviewView(UUID eventId,UUID conflictId,CatalogKind kind,UUID objectGid,String reason,String status) { }
    public List<AuthorizationReviewView> listAuthorizationReviews() {
        requireCampus();return transactions.required(()->jdbc.query("SELECT r.event_id,c.conflict_id,r.kind,r.object_gid,r.reason,coalesce(d.status,'AUTH_REVIEW') status FROM eatwhat_sync.catalog_authorization_review r JOIN eatwhat_sync.catalog_head_conflict c ON c.kind=r.kind AND c.object_gid=r.object_gid LEFT JOIN eatwhat_sync.catalog_decision_state d ON d.conflict_id=c.conflict_id ORDER BY r.created_at",(rs,n)->new AuthorizationReviewView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),CatalogKind.valueOf(rs.getString(3)),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6))).stream().filter(v->commands.canDiscloseObjectHeadConflict(v.conflictId())).toList());
    }

    public record ResolveDecisionRequest(List<UUID> candidateOperations,List<UUID> decisionHeads,CatalogDecision decision,UUID winnerOperationId,String reason) { }
    public CatalogDecisionService.DecisionView decisionSnapshot(UUID id) { requireCampus();return decisions==null?null:decisions.view(id); }
    public ResolutionResult resolveDecision(UUID id,ResolveDecisionRequest request,long actor) {
        requireCampus();if(decisions==null||!commands.multiWriterEnabled())return new ResolutionResult(Status.BLOCKED,0);
        return new ResolutionResult(decisions.resolve(id,request.candidateOperations(),request.decisionHeads(),request.decision(),request.winnerOperationId(),request.reason(),actor),0);
    }

    public record CandidateView(UUID gid, long numericId, String sourceSite, long revision,
                                String contentHash, Map<String, Object> summary) { }

    public record ConflictView(UUID conflictId, CatalogKind kind, String naturalKey, String naturalKeyHash,
                               long revision, String status, List<CandidateView> candidates) { }

    public record CandidateVersion(UUID gid, long revision, String contentHash) { }

    private record CascadeFood(long restaurantId, long foodId, long authorUserId) { }

    public record ResolveRequest(UUID conflictId, long expectedRevision, List<CandidateVersion> candidates,
                                 CatalogDecision decision, UUID winnerGid, String reason, List<UUID> decisionHeads) {
        public ResolveRequest(UUID conflictId,long expectedRevision,List<CandidateVersion> candidates,CatalogDecision decision,UUID winnerGid,String reason) { this(conflictId,expectedRevision,candidates,decision,winnerGid,reason,List.of()); }
    }

    public record ResolutionResult(Status status, long revision) { }

    public record ObjectHeadView(UUID operationId, long revision, String contentHash, String sourceSite) { }
    public record ObjectHeadConflictView(UUID conflictId, UUID objectGid, long numericId, long revision,
                                         String status, List<ObjectHeadView> heads) { }
    public record HeadVersionRequest(UUID operationId, long revision, String contentHash) { }
    public record ResolveHeadRequest(long expectedRevision, List<HeadVersionRequest> heads,
                                     CatalogDecision decision, UUID winnerOperationId, String reason, List<UUID> decisionHeads) {
        public ResolveHeadRequest(long expectedRevision,List<HeadVersionRequest> heads,CatalogDecision decision,UUID winnerOperationId,String reason) { this(expectedRevision,heads,decision,winnerOperationId,reason,List.of()); }
    }

    private record ConflictBase(UUID id, CatalogKind kind, String naturalKey, String hash,
                                long revision, String status) { }

    public CatalogConflictService(JdbcTemplate jdbc, SyncTransactions transactions, CatalogStore store,
                                  CatalogCommands commands, SiteProperties site) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.transactions.requireSameDataSource(jdbc);
        this.store = Objects.requireNonNull(store);
        this.commands = Objects.requireNonNull(commands);
        this.site = Objects.requireNonNull(site);
    }

    public List<ConflictView> listOpenConflicts() {
        requireCampus();
        return transactions.required(this::listOpenConflictsInTransaction);
    }

    public List<ObjectHeadConflictView> listOpenObjectHeadConflicts() {
        requireCampus();
        return transactions.required(() -> {
            List<ObjectHeadConflictView> result = jdbc.query("""
                    SELECT c.conflict_id,c.object_gid,o.numeric_id,c.revision,c.status,c.candidates::text
                    FROM eatwhat_sync.catalog_head_conflict c
                    JOIN eatwhat_sync.catalog_object o ON o.kind=c.kind AND o.object_gid=c.object_gid
                    WHERE c.status='OPEN' ORDER BY c.updated_at,c.object_gid
                    """, (rs, row) -> {
                List<CatalogEvents.Head> heads = JSON.readValue(rs.getString("candidates"), JSON.getTypeFactory()
                        .constructCollectionType(List.class, CatalogEvents.Head.class));
                return new ObjectHeadConflictView(rs.getObject("conflict_id", UUID.class),
                        rs.getObject("object_gid", UUID.class), rs.getLong("numeric_id"), rs.getLong("revision"),
                        rs.getString("status"), heads.stream().map(head -> new ObjectHeadView(head.operationId(),
                                head.revision(), head.contentHash(), head.sourceSite())).toList());
            });
            return result.stream().filter(conflict -> commands.canDiscloseObjectHeadConflict(conflict.conflictId()))
                    .toList();
        });
    }

    public ResolutionResult resolveObjectHeads(UUID conflictId, ResolveHeadRequest request, long actorUserId) {
        requireCampus();
        if (request == null || request.expectedRevision() < 1 || request.heads() == null
                || request.heads().isEmpty() || request.decision() == null || request.reason() == null
                || request.reason().isBlank() || (request.decision() == CatalogDecision.KEEP)
                != (request.winnerOperationId() != null)) return new ResolutionResult(Status.BLOCKED, 0);
        List<CatalogCommands.HeadVersion> heads = request.heads().stream().map(v -> v == null ? null
                : new CatalogCommands.HeadVersion(v.operationId(), v.revision(), v.contentHash())).toList();
        if (heads.stream().anyMatch(Objects::isNull)) return new ResolutionResult(Status.BLOCKED, 0);
        UUID actorGid;
        try { actorGid = store.userGid(actorUserId); }
        catch (RuntimeException missingIdentity) { return new ResolutionResult(Status.BLOCKED, request.expectedRevision()); }
        if(commands.multiWriterEnabled()) return transactions.required(() -> {
            store.lockDomain();
            var revisions=jdbc.queryForList("SELECT revision FROM eatwhat_sync.catalog_head_conflict WHERE conflict_id=?",Long.class,conflictId);
            if(revisions.size()!=1 || revisions.getFirst()!=request.expectedRevision())return new ResolutionResult(Status.CONFLICT,request.expectedRevision());
            if(decisions==null)return new ResolutionResult(Status.BLOCKED,request.expectedRevision());
            for(var head:request.heads()) {
                var row=store.readRevision(head.operationId());
                if(row==null||row.action().revision()!=head.revision()||!row.action().contentHash().equals(head.contentHash()))
                    return new ResolutionResult(Status.CONFLICT,request.expectedRevision());
            }
            Status status=decisions.resolve(conflictId,request.heads().stream().map(HeadVersionRequest::operationId).toList(),
                    request.decisionHeads()==null?List.of():request.decisionHeads(),request.decision(),request.winnerOperationId(),request.reason(),actorUserId);
            return new ResolutionResult(status,request.expectedRevision());
        });
        Status status = commands.resolveObjectHeadConflict(conflictId, request.expectedRevision(), heads,
                request.decision(), request.winnerOperationId(), request.reason(), actorUserId, actorGid);
        long revision = jdbc.query("SELECT revision FROM eatwhat_sync.catalog_head_conflict WHERE conflict_id=?",
                (rs, row) -> rs.getLong(1), conflictId).stream().findFirst().orElse(request.expectedRevision());
        return new ResolutionResult(status, revision);
    }

    private List<ConflictView> listOpenConflictsInTransaction() {
        List<ConflictBase> open = jdbc.query("""
                SELECT c.conflict_id,c.kind,k.canonical_key::text,c.key_hash,c.revision,c.status
                FROM eatwhat_sync.catalog_conflict c
                JOIN eatwhat_sync.catalog_natural_key k ON k.kind=c.kind AND k.key_hash=c.key_hash
                WHERE c.status='OPEN' ORDER BY c.updated_at,c.kind,c.key_hash
                """, (row, index) -> new ConflictBase(row.getObject("conflict_id", UUID.class),
                CatalogKind.valueOf(row.getString("kind")), row.getString("canonical_key"),
                row.getString("key_hash").trim(), row.getLong("revision"), row.getString("status")));
        List<ConflictView> result = new ArrayList<>();
        for (ConflictBase conflict : open) {
            List<CatalogStore.Candidate> beforeLock = readActiveCandidates(conflict.kind(), conflict.hash(), false);
            if (beforeLock.isEmpty()) continue;
            store.lockCandidateSubjects(conflict.kind(), beforeLock);
            store.lockNaturalKey(conflict.kind(), conflict.hash());
            List<ConflictBase> current = jdbc.query("""
                    SELECT c.conflict_id,c.kind,k.canonical_key::text,c.key_hash,c.revision,c.status
                    FROM eatwhat_sync.catalog_conflict c
                    JOIN eatwhat_sync.catalog_natural_key k ON k.kind=c.kind AND k.key_hash=c.key_hash
                    WHERE c.conflict_id=?
                    """, (row, index) -> new ConflictBase(row.getObject("conflict_id", UUID.class),
                    CatalogKind.valueOf(row.getString("kind")), row.getString("canonical_key"),
                    row.getString("key_hash").trim(), row.getLong("revision"), row.getString("status")),
                    conflict.id());
            if (current.isEmpty() || !"OPEN".equals(current.getFirst().status())) continue;
            conflict = current.getFirst();
            List<CandidateView> candidates = readActiveCandidates(conflict.kind(), conflict.hash(), false)
                    .stream().map(candidate -> new CandidateView(candidate.gid(), candidate.numericId(),
                            candidate.sourceSite(), candidate.revision(), candidate.contentHash(), candidate.summary()))
                    .toList();
            // The domain check evaluates every active candidate and its FOOD parent author. Never disclose a partial set.
            if (candidates.isEmpty() || !commands.canDiscloseConflict(conflict.id())) continue;
            result.add(new ConflictView(conflict.id(), conflict.kind(), conflict.naturalKey(), conflict.hash(),
                    conflict.revision(), conflict.status(), candidates));
        }
        return List.copyOf(result);
    }

    public ResolutionResult resolve(ResolveRequest request, long actorUserId) {
        requireCampus();
        validateRequest(request);
        if(commands.multiWriterEnabled()) {
            if(decisions==null)return new ResolutionResult(Status.BLOCKED,request.expectedRevision());
            return transactions.required(()->{
                store.lockDomain();
                var scopes=jdbc.query("SELECT kind,revision FROM eatwhat_sync.catalog_conflict WHERE conflict_id=?",(rs,n)->Map.entry(CatalogKind.valueOf(rs.getString(1)),rs.getLong(2)),request.conflictId());
                if(scopes.size()!=1||scopes.getFirst().getValue()!=request.expectedRevision())return new ResolutionResult(Status.CONFLICT,request.expectedRevision());
                List<UUID> operations=new ArrayList<>();UUID winner=null;
                for(var candidate:request.candidates()) {
                    UUID operation=store.latestOperationId(scopes.getFirst().getKey(),candidate.gid());
                    var revision=operation==null?null:store.readRevision(operation);
                    if(revision==null||revision.action().revision()!=candidate.revision()||!revision.action().contentHash().equals(candidate.contentHash()))
                        return new ResolutionResult(Status.CONFLICT,request.expectedRevision());
                    operations.add(operation);if(candidate.gid().equals(request.winnerGid()))winner=operation;
                }
                return new ResolutionResult(decisions.resolve(request.conflictId(),operations,
                        request.decisionHeads()==null?List.of():request.decisionHeads(),request.decision(),winner,request.reason(),actorUserId),request.expectedRevision());
            });
        }
        try {
            return transactions.required(() -> {
            List<ConflictBase> found = jdbc.query("""
                    SELECT c.conflict_id,c.kind,k.canonical_key::text,c.key_hash,c.revision,c.status
                    FROM eatwhat_sync.catalog_conflict c
                    JOIN eatwhat_sync.catalog_natural_key k ON k.kind=c.kind AND k.key_hash=c.key_hash
                    WHERE c.conflict_id=?
                    """, (row, index) -> new ConflictBase(row.getObject("conflict_id", UUID.class),
                    CatalogKind.valueOf(row.getString("kind")), row.getString("canonical_key"),
                    row.getString("key_hash").trim(), row.getLong("revision"), row.getString("status")),
                    request.conflictId());
            if (found.isEmpty()) return new ResolutionResult(Status.BLOCKED, 0);
            ConflictBase identity = found.getFirst();

            List<CatalogStore.Candidate> beforeLock = readActiveCandidates(identity.kind(), identity.hash(), false);
            if (beforeLock.isEmpty()) return new ResolutionResult(Status.BLOCKED, identity.revision());
            List<Long> cascadeRestaurants = conflictLoserRestaurants(identity.kind(), beforeLock, request);
            List<CascadeFood> cascadeBefore = cascadeFoods(cascadeRestaurants);
            List<Long> cascadeAuthors = cascadeBefore.stream().map(CascadeFood::authorUserId).distinct().sorted().toList();
            store.lockCandidateSubjects(identity.kind(), beforeLock, cascadeAuthors);
            store.lockNaturalKey(identity.kind(), identity.hash());
            for (Long restaurantId : cascadeRestaurants) store.lockPublicRow(CatalogKind.RESTAURANT, restaurantId);
            if (!cascadeBefore.equals(cascadeFoods(cascadeRestaurants))) {
                return new ResolutionResult(Status.CONFLICT, identity.revision());
            }
            List<CatalogStore.Candidate> afterDomainLock = readActiveCandidates(identity.kind(), identity.hash(), false);
            if (!sameVersions(versions(beforeLock), versions(afterDomainLock))) {
                return new ResolutionResult(Status.CONFLICT, identity.revision());
            }
            List<ConflictBase> locked = jdbc.query("""
                    SELECT c.conflict_id,c.kind,k.canonical_key::text,c.key_hash,c.revision,c.status
                    FROM eatwhat_sync.catalog_conflict c
                    JOIN eatwhat_sync.catalog_natural_key k ON k.kind=c.kind AND k.key_hash=c.key_hash
                    WHERE c.conflict_id=? FOR UPDATE OF c
                    """, (row, index) -> new ConflictBase(row.getObject("conflict_id", UUID.class),
                    CatalogKind.valueOf(row.getString("kind")), row.getString("canonical_key"),
                    row.getString("key_hash").trim(), row.getLong("revision"), row.getString("status")),
                    request.conflictId());
            if (locked.isEmpty()) return new ResolutionResult(Status.BLOCKED, 0);
            ConflictBase conflict = locked.getFirst();
            if (!conflict.kind().equals(identity.kind()) || !conflict.hash().equals(identity.hash())) {
                return new ResolutionResult(Status.CONFLICT, conflict.revision());
            }

            List<CatalogStore.Candidate> active = readActiveCandidates(conflict.kind(), conflict.hash(), true);
            List<CandidateVersion> current = versions(active);
            if (!"OPEN".equals(conflict.status()) || conflict.revision() != request.expectedRevision()
                    || !sameVersions(current, request.candidates())) {
                return new ResolutionResult(Status.CONFLICT, conflict.revision());
            }
            if (!commands.canDiscloseConflict(conflict.id())) return new ResolutionResult(Status.BLOCKED, conflict.revision());
            UUID actorGid;
            try {
                actorGid = store.userGid(actorUserId);
            } catch (RuntimeException missingIdentity) {
                return new ResolutionResult(Status.BLOCKED, conflict.revision());
            }
            if (actorGid == null) return new ResolutionResult(Status.BLOCKED, conflict.revision());

            List<UUID> expectedHeadOperations = active.stream()
                    .map(candidate -> store.latestOperationId(conflict.kind(), candidate.gid())).toList();
            if (expectedHeadOperations.stream().anyMatch(Objects::isNull)) {
                return new ResolutionResult(Status.CONFLICT, conflict.revision());
            }
            UUID winnerOperationId = request.decision() == CatalogDecision.KEEP
                    ? active.stream().filter(candidate -> candidate.gid().equals(request.winnerGid()))
                    .findFirst().map(candidate -> store.latestOperationId(conflict.kind(), candidate.gid()))
                    .orElse(null) : null;
            CatalogEvents.ResolutionProof proof = new CatalogEvents.ResolutionProof(conflict.id(),
                    expectedHeadOperations, request.decision(), winnerOperationId);

            commands.seedConflictLoserFoods(cascadeRestaurants);
            Status applied = commands.applyConflictResolution(request, actorUserId, actorGid, active);
            if (applied != Status.APPLIED) throw new ResolutionAbort(applied, conflict.revision());

            String terminalStatus = request.decision() == CatalogDecision.KEEP ? "RESOLVED" : "REJECTED";
            long nextRevision = conflict.revision() + 1;
            String expectedJson = EventCodec.canonicalJson(JSON.writeValueAsString(versionPayload(current)));
            String proofJson = EventCodec.canonicalJson(JSON.writeValueAsString(proof));
            jdbc.update("""
                    INSERT INTO eatwhat_sync.catalog_resolution_receipt(
                      conflict_id,decision,winner_gid,source_site,proof,expected_candidates,proof_hash,conflict_revision)
                    VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)
                    """, conflict.id(), request.decision().name(), request.winnerGid(), site.getId(), proofJson,
                    expectedJson, EventCodec.sha256(proofJson), nextRevision);
            jdbc.update("""
                    INSERT INTO eatwhat_sync.catalog_conflict_audit
                      (audit_id,conflict_id,conflict_revision,expected_candidates,decision,winner_gid,actor_gid,reason)
                    VALUES (?,?,?,?::jsonb,?,?,?,?)
                    """, UUID.randomUUID(), conflict.id(), conflict.revision(), expectedJson,
                    request.decision().name(), request.winnerGid(), actorGid, request.reason());
            int changed = jdbc.update("""
                    UPDATE eatwhat_sync.catalog_conflict
                    SET status=?,revision=?,updated_at=clock_timestamp()
                    WHERE conflict_id=? AND status='OPEN' AND revision=?
                    """, terminalStatus, nextRevision, conflict.id(), conflict.revision());
            if (changed != 1) throw new IllegalStateException("CATALOG_CONFLICT_CAS_LOST_AFTER_LOCK");
            return new ResolutionResult(Status.APPLIED, nextRevision);
            });
        } catch (ResolutionAbort abort) {
            return new ResolutionResult(abort.status, abort.revision);
        }
    }

    private List<CatalogStore.Candidate> readActiveCandidates(CatalogKind kind, String hash, boolean lock) {
        String sql = """
                SELECT object_gid,numeric_id,source_site,author_gid,revision,content_hash,summary::text
                FROM eatwhat_sync.catalog_candidate
                WHERE kind=? AND key_hash=? AND status NOT IN ('REJECTED','SUPERSEDED')
                ORDER BY object_gid
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (row, index) -> new CatalogStore.Candidate(
                row.getObject("object_gid", UUID.class), row.getLong("numeric_id"), row.getString("source_site"),
                row.getObject("author_gid", UUID.class), row.getLong("revision"), row.getString("content_hash").trim(),
                readObject(row.getString("summary"))), kind.name(), hash);
    }

    private List<Long> conflictLoserRestaurants(CatalogKind kind, List<CatalogStore.Candidate> candidates,
                                                ResolveRequest request) {
        if (kind != CatalogKind.RESTAURANT) return List.of();
        return candidates.stream()
                .filter(candidate -> request.decision() == CatalogDecision.REJECT
                        || !candidate.gid().equals(request.winnerGid()))
                .map(CatalogStore.Candidate::numericId).distinct().sorted().toList();
    }

    private List<CascadeFood> cascadeFoods(List<Long> restaurantIds) {
        List<CascadeFood> result = new ArrayList<>();
        for (Long restaurantId : restaurantIds) {
            result.addAll(jdbc.query("""
                    SELECT restaurant_id,id,account_id FROM public.foods
                    WHERE restaurant_id=? ORDER BY id
                    """, (rs, row) -> new CascadeFood(rs.getLong("restaurant_id"), rs.getLong("id"),
                    rs.getLong("account_id")), restaurantId));
        }
        return List.copyOf(result);
    }

    private static final class ResolutionAbort extends RuntimeException {
        private final Status status;
        private final long revision;
        private ResolutionAbort(Status status, long revision) {
            this.status = status;
            this.revision = revision;
        }
    }

    private static List<CandidateVersion> versions(List<CatalogStore.Candidate> candidates) {
        return candidates.stream().map(c -> new CandidateVersion(c.gid(), c.revision(), c.contentHash()))
                .sorted(VERSION_ORDER).toList();
    }

    private static boolean sameVersions(List<CandidateVersion> left, List<CandidateVersion> right) {
        return right != null && left.equals(right.stream().sorted(VERSION_ORDER).toList());
    }

    private static List<Map<String, Object>> versionPayload(List<CandidateVersion> versions) {
        return versions.stream().sorted(VERSION_ORDER).map(version -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gid", version.gid().toString());
            row.put("revision", version.revision());
            row.put("contentHash", version.contentHash());
            return row;
        }).toList();
    }

    private Map<String, Object> readObject(String json) {
        return JSON.readValue(json, JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }

    private static void validateRequest(ResolveRequest request) {
        if (request == null || request.conflictId() == null || request.expectedRevision() < 1
                || request.candidates() == null || request.candidates().isEmpty() || request.candidates().size() > 100
                || request.decision() == null || request.reason() == null || request.reason().isBlank()
                || request.reason().length() > 1000) {
            throw new BizException(BizCode.PARAM_INVALID);
        }
        if (request.candidates().stream().anyMatch(Objects::isNull)) {
            throw new BizException(BizCode.PARAM_INVALID);
        }
        List<UUID> gids = request.candidates().stream().map(CandidateVersion::gid).toList();
        if (gids.stream().anyMatch(Objects::isNull)
                || gids.stream().distinct().count() != gids.size()
                || request.candidates().stream().anyMatch(candidate -> candidate.revision() < 1
                || candidate.contentHash() == null || !candidate.contentHash().matches("[0-9a-f]{64}"))) {
            throw new BizException(BizCode.PARAM_INVALID);
        }
        boolean winnerListed = request.winnerGid() != null && gids.contains(request.winnerGid());
        if ((request.decision() == CatalogDecision.KEEP && !winnerListed)
                || (request.decision() == CatalogDecision.REJECT && request.winnerGid() != null)) {
            throw new BizException(BizCode.PARAM_INVALID);
        }
    }

    private void requireCampus() {
        if (site.getMode() != SiteProperties.Mode.CAMPUS && !commands.multiWriterEnabled()) throw new BizException(BizCode.NO_PERMISSION);
    }
}
