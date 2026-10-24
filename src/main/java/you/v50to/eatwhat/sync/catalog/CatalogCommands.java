package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Transactional catalog write boundary. Existing service suppliers keep their public API semantics. */
public final class CatalogCommands {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public enum Status { APPLIED, CONFLICT, BLOCKED }
    public enum ImportStatus { APPLIED, REPLAYED, CONFLICT, BLOCKED }
    public enum BackfillStatus { APPLIED, WAIT, STALE }
    public record HeadVersion(UUID operationId, long revision, String contentHash) { }
    public record WriteOutcome<T>(Status status, T value, UUID candidateGid, UUID conflictId) { }
    public record UpdatePlan<T>(Long newParentNumericId, Map<String, Object> newNaturalKeyFields,
                                Supplier<T> mutation, Predicate<T> succeeded) { }
    public record DeletePlan<T>(Supplier<T> mutation, Predicate<T> succeeded) { }
    public record CreateSpec(CatalogKind kind, Long authorUserId, Long parentNumericId,
                             Map<String, Object> naturalKeyFields, Supplier<Long> numericId) { }
    public record CatalogImportResult(UUID batchId, ImportStatus status, int created, int updated) { }
    public record ImportCounts(int created, int updated) { }
    private record ImportedHub(boolean inserted, int assignments) { }

    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final OutboxService outbox;
    private final CatalogStore store;
    private final EntityIdentityService identities;
    private final ConsentStore consent;
    private final SiteProperties site;
    private final CatalogSyncProperties properties;
    private final CatalogProjection projection;
    private CatalogMultiwriterSupport multiwriter;
    public void setMultiwriterSupport(CatalogMultiwriterSupport value) { this.multiwriter=value; }
    private CatalogDecisionBackfill decisionBackfill;
    public void setDecisionBackfill(CatalogDecisionBackfill value){decisionBackfill=value;}
    public boolean multiWriterEnabled() { return properties.isMultiWriterEnabled() && multiwriter!=null && multiwriter.ready(); }
    private void appendLocalRevision(CatalogEvents.Action action) {
        store.appendRevision(action,site.getId());
        if(properties.isMultiWriterEnabled()) {
            if(!multiWriterEnabled()) throw new IllegalStateException("CATALOG_MULTIWRITER_NOT_ACTIVATED");
            multiwriter.captureCommand(action);
        }
    }

    public CatalogCommands(JdbcTemplate jdbc, SyncTransactions transactions, OutboxService outbox,
                           CatalogStore store, EntityIdentityService identities, ConsentStore consent,
                           SiteProperties site, CatalogSyncProperties properties) {
        this(jdbc, transactions, outbox, store, identities, consent, site, properties, null);
    }

    public CatalogCommands(JdbcTemplate jdbc, SyncTransactions transactions, OutboxService outbox,
                           CatalogStore store, EntityIdentityService identities, ConsentStore consent,
                           SiteProperties site, CatalogSyncProperties properties, CatalogProjection projection) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.transactions.requireSameDataSource(jdbc);
        this.outbox = Objects.requireNonNull(outbox);
        this.store = Objects.requireNonNull(store);
        this.identities = Objects.requireNonNull(identities);
        this.consent = Objects.requireNonNull(consent);
        this.site = Objects.requireNonNull(site);
        this.properties = Objects.requireNonNull(properties);
        this.projection = projection;
    }

    public <T> T atomic(Supplier<T> work) {
        return transactions.required(work);
    }

    public String peerSiteId() { return properties.getPeerSiteId(); }
    public java.util.List<String> recipients(){
        var membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
        return membership.bound()?membership.participants().stream().map(you.v50to.eatwhat.cluster.ClusterMember::siteId).filter(id->!id.equals(site.getId())).toList():java.util.List.of(properties.getPeerSiteId());
    }
    private void recordPublication(CatalogKind kind,UUID object,UUID subject,long epoch,java.util.List<CatalogEvents.ConsentEpoch> epochs,UUID event){
        for(String recipient:recipients())store.recordBackfillPublication(kind,object,subject,recipient,epoch,epochs,event);
    }

    /**
     * Publishes durable current catalog heads for one authorized subject. Existing revisions are
     * relayed verbatim; only an old public row with no ledger history receives a deterministic baseline.
     */
    public BackfillStatus backfillCatalogObject(CatalogKind kind, long numericId, UUID subjectGid,
                                                 long expectedEpoch) {
        if (kind == null || numericId < 1 || subjectGid == null || expectedEpoch < 0) return BackfillStatus.STALE;
        return transactions.required(() -> {
            boolean system = CatalogBackfillStore.SYSTEM_SUBJECT.equals(subjectGid);
            if (system && ((site.getMode() != SiteProperties.Mode.CAMPUS && !multiWriterEnabled()) || expectedEpoch != 0
                    || (kind != CatalogKind.HUB && kind != CatalogKind.SYSTEM_TAG && kind != CatalogKind.ACTIVITY))) return BackfillStatus.STALE;
            if (!system) {
                ConsentModels.State requested = consent.getBySubject(subjectGid);
                if (requested == null || requested.epoch() != expectedEpoch
                        || !"GRANTED".equals(requested.status()) || requested.withdrawalRequestId() != null) {
                    return BackfillStatus.STALE;
                }
                if (!requested.identityReady() || !consent.replicationAllowed(requested.userId())) return BackfillStatus.WAIT;
            }

            CatalogStore.Identity preliminary = store.findByNumeric(kind, numericId);
            Map<String, Object> publicPayload = store.readPublic(kind, numericId);
            if (preliminary == null && publicPayload == null) return BackfillStatus.STALE;
            Long authorUserId = preliminary == null ? authorNumericId(kind, numericId)
                    : localUserId(preliminary.authorGid());
            Long parentId = preliminary == null ? parentNumericId(kind, publicPayload)
                    : parentNumericId(preliminary);
            Long parentAuthorId = parentAuthorUserId(kind, parentId);
            List<CatalogEvents.Head> observedHeads = preliminary == null ? List.of() : readState(preliminary).heads();
            if (!system) {
                List<Long> lockedUsers = new ArrayList<>(subjectUsers(observedHeads));
                if (authorUserId != null) lockedUsers.add(authorUserId);
                if (parentAuthorId != null) lockedUsers.add(parentAuthorId);
                if (kind == CatalogKind.FOOD) {
                    addParentAuthorUser(lockedUsers, preliminary == null ? null : preliminary.parentGid());
                    for (CatalogEvents.Head head : observedHeads) addParentAuthorUser(lockedUsers, head.parentGid());
                }
                lockSubjects(lockedUsers);
                ConsentModels.State current = consent.getBySubject(subjectGid);
                if (current == null || current.epoch() != expectedEpoch
                        || !"GRANTED".equals(current.status()) || current.withdrawalRequestId() != null) {
                    return BackfillStatus.STALE;
                }
                if (!current.identityReady() || !consent.replicationAllowed(current.userId())) return BackfillStatus.WAIT;
            }
            store.lockDomain();
            CatalogStore.Identity preParent = kind == CatalogKind.FOOD && preliminary != null
                    && preliminary.parentGid() != null
                    ? store.findObject(CatalogKind.RESTAURANT, preliminary.parentGid()) : null;
            boolean terminalParent = preParent != null && (preParent.tombstone() || preParent.rejected());
            if (!terminalParent) lockParent(kind, parentId);
            CatalogStore.Identity identity = preliminary == null
                    ? ensureTracked(kind, numericId, true) : store.lockObject(kind, preliminary.gid());
            if (identity == null) return BackfillStatus.STALE;
            if (!system && preliminary != null) {
                ObjectState lockedState = readState(identity);
                if (!sameHeadSet(observedHeads, lockedState.heads())) return BackfillStatus.WAIT;
            }
            CatalogStore.Identity parentIdentity = kind == CatalogKind.FOOD && identity.parentGid() != null
                    ? store.findObject(CatalogKind.RESTAURANT, identity.parentGid()) : null;
            if (kind == CatalogKind.FOOD && identity.parentGid() != null && parentIdentity == null) {
                return BackfillStatus.WAIT;
            }
            if (!system) {
                try {
                    List<CatalogEvents.ConsentEpoch> required = consentEpochs(kind, identity.gid(),
                            identity.authorGid(), identity.parentGid(), null);
                    boolean requesterOwnsScope = required.stream().anyMatch(epoch -> epoch.subjectGid().equals(subjectGid)
                            && epoch.epoch() == expectedEpoch);
                    if (!requesterOwnsScope) return BackfillStatus.STALE;
                    if (!authorized(required)) return BackfillStatus.WAIT;
                } catch (IllegalStateException dependencyUnavailable) {
                    return BackfillStatus.WAIT;
                }
            }

            ObjectState state = readState(identity);
            if (state.heads().isEmpty()) {
                if (identity.revision() != 0 || identity.tombstone() || identity.rejected()
                        || state.payload().isEmpty()) return BackfillStatus.WAIT;
                // A headless row can only be seeded where its durable global identity proves this site as origin.
                if (!site.getId().equals(identity.originSite())) return BackfillStatus.WAIT;
                CatalogEvents.Action baseline = baselineAction(identity, state.payload());
                store.appendRevision(baseline, site.getId());
                identity = advance(identity, baseline).identity();
                store.updateObject(identity);
                registerCandidate(baseline);
                state = readState(identity);
            }

            List<CatalogEvents.Head> reboundHeads = new ArrayList<>();
            Map<UUID, CatalogEvents.ConsentEpoch> bridgeEpochs = new TreeMap<>(Comparator.comparing(UUID::toString));
            for (CatalogEvents.Head head : state.heads()) {
                List<CatalogEvents.ConsentEpoch> currentEpochs;
                try {
                    currentEpochs = system ? List.of() : consentEpochs(kind, identity.gid(),
                            head.authorGid(), head.parentGid(), head.resolutionProof());
                } catch (IllegalStateException dependencyUnavailable) {
                    return BackfillStatus.WAIT;
                }
                if (!system && !authorized(currentEpochs)) return BackfillStatus.WAIT;
                for (CatalogEvents.ConsentEpoch value : currentEpochs) bridgeEpochs.put(value.subjectGid(), value);
                reboundHeads.add(head);
            }
            List<CatalogEvents.ConsentEpoch> outerEpochs = List.copyOf(bridgeEpochs.values());
            if (!system && outerEpochs.stream().noneMatch(e -> e.subjectGid().equals(subjectGid)
                    && e.epoch() == expectedEpoch)) return BackfillStatus.STALE;
            String signature = bridgeSignature(identity, subjectGid, expectedEpoch, reboundHeads, outerEpochs);
            UUID eventId = UUID.nameUUIDFromBytes(("eatwhat.catalog.bridge:" + signature)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(multiWriterEnabled() && decisionBackfill!=null)decisionBackfill.publish(kind,identity.gid(),outerEpochs);
            if (bridgeAlreadyPublished(signature)) {
                recordPublication(kind, identity.gid(), subjectGid,expectedEpoch, outerEpochs, eventId);
                return BackfillStatus.APPLIED;
            }

            CatalogEvents.StateBridge bridge = new CatalogEvents.StateBridge(kind, identity.gid(), identity.numericId(),
                    identity.originSite(), identity.authoritySite(), identity.parentGid(), outerEpochs, reboundHeads);
            long transportNumericId = jdbc.queryForObject("SELECT eatwhat_sync.next_catalog_number()", Long.class);
            SyncEvent event = new SyncEvent(eventId, site.getId(), site.getInstanceId(), CatalogEvents.AGGREGATE,
                    eventId, transportNumericId, CatalogEvents.BRIDGE, multiWriterEnabled()?2:CatalogEvents.SCHEMA_VERSION,
                    VersionVector.empty(), VersionVector.empty().increment(site.getId()), List.of(), null,
                    "catalog-bridge:" + eventId, eventId, multiWriterEnabled()?CatalogMultiwriterEvents.bridgePayload(bridge,multiwriter.bridgeEvidence(bridge)):CatalogEvents.bridgePayload(bridge));
            outbox.append(event, recipients());
            recordPublication(kind, identity.gid(), subjectGid,expectedEpoch, outerEpochs, eventId);
            store.markManaged(kind);
            return BackfillStatus.APPLIED;
        });
    }

    /** Locks candidate owners before the shared catalog and natural-key locks during CAS resolution. */
    public void lockConflictSubjects(CatalogKind kind, List<CatalogStore.Candidate> candidates) {
        store.lockCandidateSubjects(kind, candidates);
    }

    public boolean canDiscloseConflict(UUID conflictId) {
        List<CatalogKind> kinds = jdbc.query("SELECT kind FROM eatwhat_sync.catalog_conflict WHERE conflict_id=?",
                (rs, row) -> CatalogKind.valueOf(rs.getString(1)), conflictId);
        if (kinds.isEmpty()) return false;
        CatalogKind kind = kinds.getFirst();
        List<CatalogStore.Candidate> candidates = store.activeCandidates(kind, conflictId);
        if (candidates.isEmpty()) return false;
        for (CatalogStore.Candidate candidate : candidates) {
            List<UUID> subjects = store.disclosureSubjects(kind, candidate.gid());
            for (UUID subject : subjects) {
                ConsentModels.State state = consent.getBySubject(subject);
                if (state == null || !disclosureAllowed(state.userId())) return false;
            }
        }
        return true;
    }

    public boolean canDiscloseObjectHeadConflict(UUID conflictId) {
        List<java.util.Map.Entry<CatalogKind,UUID>> gids = jdbc.query("SELECT kind,object_gid FROM eatwhat_sync.catalog_head_conflict WHERE conflict_id=? AND status='OPEN'",
                (rs, row) -> java.util.Map.entry(CatalogKind.valueOf(rs.getString(1)),rs.getObject(2, UUID.class)), conflictId);
        if (gids.isEmpty()) return false;
        CatalogStore.Identity identity = store.findObject(gids.getFirst().getKey(), gids.getFirst().getValue());
        if (identity == null) return false;
        ObjectState state = readState(identity);
        lockSubjects(subjectUsers(state.heads()));
        store.lockDomain();
        CatalogStore.Identity locked = store.lockObject(gids.getFirst().getKey(), gids.getFirst().getValue());
        if (locked == null || !sameHeadSet(state.heads(), readState(locked).heads())) return false;
        try {
            for (CatalogEvents.Head head : state.heads()) {
                List<CatalogEvents.ConsentEpoch> epochs = consentEpochs(locked.kind(),
                        locked.gid(), head.authorGid(), head.parentGid(), head.resolutionProof());
                for (CatalogEvents.ConsentEpoch epoch : epochs) {
                    ConsentModels.State grant = consent.getBySubject(epoch.subjectGid());
                    if (grant == null || grant.epoch() != epoch.epoch() || !disclosureAllowed(grant.userId())) return false;
                }
            }
            return true;
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    /** An administrator must be able to review a banned author's existing operations while consent remains valid. */
    private boolean disclosureAllowed(long userId) {
        return multiWriterEnabled() ? consent.removalReplicationAllowed(userId) : consent.replicationAllowed(userId);
    }

    /** Applies the already-CAS-locked decision within CatalogConflictService's transaction. */
    public Status applyConflictResolution(CatalogConflictService.ResolveRequest request, long actorUserId,
                                          UUID actorGid, List<CatalogStore.Candidate> lockedCandidates) {
        if (lockedCandidates == null || lockedCandidates.size() < 2) return Status.BLOCKED;
        return transactions.required(() -> {
            List<CatalogKind> conflictKinds = jdbc.query(
                    "SELECT kind FROM eatwhat_sync.catalog_conflict WHERE conflict_id=?",
                    (rs, row) -> CatalogKind.valueOf(rs.getString(1)), request.conflictId());
            if (conflictKinds.isEmpty()) return Status.BLOCKED;
            CatalogKind conflictKind = conflictKinds.getFirst();
            if (site.getMode() != SiteProperties.Mode.CAMPUS || projection == null) return Status.BLOCKED;
            store.lockDomain();
            CatalogStore.Candidate anchor = request.decision() == CatalogDecision.KEEP
                    ? lockedCandidates.stream().filter(row -> row.gid().equals(request.winnerGid())).findFirst().orElse(null)
                    : lockedCandidates.getFirst();
            if (anchor == null) return Status.CONFLICT;
            CatalogStore.Identity identity = store.lockObject(conflictKind, anchor.gid());
            if (identity == null || identity.tombstone() || identity.rejected()) return Status.BLOCKED;
            List<UUID> expectedHeads = new ArrayList<>();
            for (CatalogStore.Candidate candidate : lockedCandidates) {
                UUID operation = store.latestOperationId(conflictKind, candidate.gid());
                if (operation == null) return Status.CONFLICT;
                expectedHeads.add(operation);
            }
            UUID winningOperation = request.decision() == CatalogDecision.KEEP
                    ? store.latestOperationId(conflictKind, anchor.gid()) : null;
            CatalogStore.ImmutableRevision selectedRevision = store.readRevision(
                    request.decision() == CatalogDecision.KEEP ? winningOperation : expectedHeads.getFirst());
            if (selectedRevision == null || selectedRevision.action().kind() != conflictKind) return Status.CONFLICT;
            Map<String, Object> payload = selectedRevision.action().canonicalPayload();
            CatalogEvents.ResolutionProof proof = new CatalogEvents.ResolutionProof(request.conflictId(),
                    expectedHeads, request.decision(), winningOperation);
            CatalogEvents.Action action = nextAction(identity, CatalogOperation.UPDATE, payload, proof);
            appendLocalRevision(action);
            store.updateObject(advance(identity, action).identity());
            for (CatalogStore.Candidate candidate : lockedCandidates) {
                boolean winner = request.decision() == CatalogDecision.KEEP && candidate.gid().equals(request.winnerGid());
                jdbc.update("""
                        UPDATE eatwhat_sync.catalog_candidate SET
                          candidate_id=CASE WHEN ? THEN ? ELSE candidate_id END,
                          revision=CASE WHEN ? THEN ? ELSE revision END,
                          content_hash=CASE WHEN ? THEN ? ELSE content_hash END,
                          status=?,updated_at=clock_timestamp()
                        WHERE kind=? AND key_hash=(SELECT key_hash FROM eatwhat_sync.catalog_conflict WHERE conflict_id=?)
                          AND object_gid=?
                        """, winner, action.operationId(), winner, action.revision(), winner, action.contentHash(),
                        winner ? "KEPT" : "REJECTED", conflictKind.name(), request.conflictId(), candidate.gid());
                if (!winner) {
                    store.markRejected(conflictKind, candidate.gid());
                    CatalogStore.Identity loser = store.findByNumeric(conflictKind, candidate.numericId());
                    if (loser != null && store.readPublic(conflictKind, candidate.numericId()) != null) {
                        projection.clearRejected(conflictKind, candidate.numericId());
                    }
                }
            }
            if (request.decision() == CatalogDecision.KEEP) {
                CatalogStore.Identity winner = store.lockObject(conflictKind, request.winnerGid());
                if (winner == null) return Status.CONFLICT;
                // The SQL projection guard requires a durable proof before it permits the public-row write.
                // The event and projection share this transaction, so either both commit or neither does.
                publishIfAuthorized(action, identity.authorGid(), identity.parentGid());
                CatalogProjection.ProjectionResult projected = projection.applyWinner(winner, payload);
                if (projected != CatalogProjection.ProjectionResult.APPLIED) return Status.BLOCKED;
            } else {
                publishIfAuthorized(action, identity.authorGid(), identity.parentGid());
            }
            return Status.APPLIED;
        });
    }

    /** Resolves every current causal head for one custom-tag identity using a full-head CAS. */
    public Status resolveObjectHeadConflict(UUID conflictId, long expectedRevision, List<HeadVersion> expectedHeads,
                                            CatalogDecision decision, UUID winnerOperationId, String reason,
                                            long actorUserId, UUID actorGid) {
        if (conflictId == null || expectedRevision < 1 || expectedHeads == null || expectedHeads.size() < 2
                || expectedHeads.size() > 32 || decision == null || actorUserId < 1 || actorGid == null
                || reason == null || reason.isBlank() || reason.length() > 1000
                || expectedHeads.stream().anyMatch(Objects::isNull)
                || expectedHeads.stream().map(HeadVersion::operationId).distinct().count() != expectedHeads.size()
                || (decision == CatalogDecision.KEEP) != (winnerOperationId != null)
                || (winnerOperationId != null && expectedHeads.stream()
                .noneMatch(head -> head.operationId().equals(winnerOperationId)))) return Status.BLOCKED;
        try {
            return transactions.required(() -> {
                if (site.getMode() != SiteProperties.Mode.CAMPUS || projection == null
                        || !Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM public.users WHERE id=? AND role='admin')", Boolean.class,
                        actorUserId)) || !Objects.equals(store.userGid(actorUserId), actorGid)) return Status.BLOCKED;
                HeadConflictSeed seed = jdbc.query("""
                        SELECT c.kind,c.object_gid,o.numeric_id FROM eatwhat_sync.catalog_head_conflict c
                        JOIN eatwhat_sync.catalog_object o ON o.kind=c.kind AND o.object_gid=c.object_gid
                        WHERE c.conflict_id=?
                        """, (rs, row) -> new HeadConflictSeed(CatalogKind.valueOf(rs.getString("kind")),
                        rs.getObject("object_gid", UUID.class), rs.getLong("numeric_id")), conflictId)
                        .stream().findFirst().orElse(null);
                if (seed == null || seed.kind() != CatalogKind.CUSTOM_TAG
                        || !conflictId.equals(CatalogStore.stableHeadConflictId(seed.kind(), seed.objectGid()))) {
                    return Status.CONFLICT;
                }
                CatalogStore.Identity preliminary = store.findObject(seed.kind(), seed.objectGid());
                if (preliminary == null || preliminary.numericId() != seed.numericId()) return Status.CONFLICT;
                ObjectState preliminaryState = readState(preliminary);
                lockSubjects(subjectUsers(preliminaryState.heads()));
                store.lockDomain();
                List<HeadConflictState> conflicts = jdbc.query("""
                        SELECT revision,status,candidates::text FROM eatwhat_sync.catalog_head_conflict
                        WHERE conflict_id=? AND kind='CUSTOM_TAG' AND object_gid=? FOR UPDATE
                        """, (rs, row) -> new HeadConflictState(rs.getLong("revision"), rs.getString("status"),
                        JSON.readValue(rs.getString("candidates"), JSON.getTypeFactory()
                                .constructCollectionType(List.class, CatalogEvents.Head.class))),
                        conflictId, seed.objectGid());
                if (conflicts.isEmpty()) return Status.CONFLICT;
                HeadConflictState conflict = conflicts.getFirst();
                CatalogStore.Identity identity = store.lockObject(seed.kind(), seed.objectGid());
                if (identity == null || identity.tombstone() || identity.rejected()) return Status.BLOCKED;
                ObjectState current = readState(identity);
                if (!"OPEN".equals(conflict.status()) || conflict.revision() != expectedRevision
                        || !sameHeadVersions(current.heads(), expectedHeads)
                        || !sameHeadVersions(conflict.candidates(), expectedHeads)) return Status.CONFLICT;
                CatalogEvents.Head winner = decision == CatalogDecision.KEEP
                        ? current.heads().stream().filter(head -> head.operationId().equals(winnerOperationId))
                        .findFirst().orElse(null) : null;
                if (decision == CatalogDecision.KEEP && winner == null) return Status.CONFLICT;
                CatalogEvents.ResolutionProof proof = new CatalogEvents.ResolutionProof(conflictId,
                        expectedHeads.stream().map(HeadVersion::operationId).toList(), decision, winnerOperationId);
                Map<String, Object> payload = (winner == null ? current.heads().getFirst() : winner).canonicalPayload();
                CatalogEvents.Action action = nextAction(identity, CatalogOperation.UPDATE, payload, proof);
                if (!authorized(action.consentEpochs())) return Status.BLOCKED;

                appendLocalRevision(action);
                CatalogStore.Identity advanced = advance(identity, action).identity();
                store.updateObject(advanced);
                if (decision == CatalogDecision.KEEP) {
                    for (CatalogEvents.Head head : current.heads()) {
                        CatalogPayload.NaturalKey key = CatalogPayload.naturalKey(CatalogKind.CUSTOM_TAG,
                                head.canonicalPayload(),
                                head.authorGid() == null ? null : head.authorGid().toString(), null);
                        if (key != null) jdbc.update("""
                                UPDATE eatwhat_sync.catalog_candidate SET status=?,updated_at=clock_timestamp()
                                WHERE kind='CUSTOM_TAG' AND key_hash=? AND object_gid=?
                                """, head.operationId().equals(winnerOperationId) ? "PENDING" : "SUPERSEDED",
                                key.hash(), identity.gid());
                    }
                    registerCandidate(action);
                    publishIfAuthorized(action, action.authorGid(), action.parentGid());
                    CatalogStore.Identity selected = store.lockObject(seed.kind(), seed.objectGid());
                    CatalogProjection.ProjectionResult projected = projection.applyWinner(selected, payload);
                    if (projected != CatalogProjection.ProjectionResult.APPLIED) throw new HeadResolutionAbort(Status.BLOCKED);
                } else {
                    store.markRejected(seed.kind(), seed.objectGid());
                    if (store.readPublic(seed.kind(), seed.numericId()) != null) {
                        projection.clearRejected(seed.kind(), seed.numericId());
                    }
                    publishIfAuthorized(action, action.authorGid(), action.parentGid());
                }
                String status = decision == CatalogDecision.KEEP ? "RESOLVED" : "REJECTED";
                int changed = jdbc.update("""
                        UPDATE eatwhat_sync.catalog_head_conflict SET status=?,revision=revision+1,
                          updated_at=clock_timestamp() WHERE conflict_id=? AND status='OPEN' AND revision=?
                        """, status, conflictId, expectedRevision);
                if (changed != 1) throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_CAS_LOST");
                String proofJson = EventCodec.canonicalJson(JSON.writeValueAsString(proof));
                long resolvedRevision = expectedRevision + 1;
                String expectedHeadsJson = EventCodec.canonicalJson(toJson(current.heads()));
                jdbc.update("""
                        INSERT INTO eatwhat_sync.catalog_head_conflict_audit(
                          audit_id,conflict_id,conflict_revision,expected_heads,decision,winner_operation_id,actor_gid,reason)
                        VALUES (?,?,?,?::jsonb,?,?,?,?)
                        """, UUID.randomUUID(), conflictId, expectedRevision,
                        expectedHeadsJson, decision.name(), winnerOperationId,
                        actorGid, reason);
                jdbc.update("""
                        INSERT INTO eatwhat_sync.catalog_head_resolution_receipt(
                          conflict_id,decision,winner_operation_id,source_site,proof,expected_heads,proof_hash,conflict_revision)
                        VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)
                        """, conflictId, decision.name(), winnerOperationId, site.getId(), proofJson,
                        expectedHeadsJson, EventCodec.sha256(proofJson), resolvedRevision);
                return Status.APPLIED;
            });
        } catch (HeadResolutionAbort abort) {
            return abort.status;
        }
    }

    /** Called inside the import service's idempotent batch transaction. */
    public ImportCounts importHubBatchInTransaction(UUID batchId, List<CatalogHubImportRequest.HubItem> hubs) {
        return importHubBatchInTransaction(batchId, hubs, null);
    }

    /** The HTTP adapter supplies the authenticated CAMPUS admin explicitly; commands stay web-agnostic. */
    public ImportCounts importHubBatchInTransaction(UUID batchId, List<CatalogHubImportRequest.HubItem> hubs,
                                                   Long actorUserId) {
        if (batchId == null || hubs == null || hubs.isEmpty() || hubs.size() > properties.getMaxHubImportBatchSize()) {
            throw new IllegalArgumentException("CATALOG_IMPORT_BATCH_INVALID");
        }
        if (actorUserId == null) throw new IllegalStateException("CATALOG_MANAGEMENT_AUTHORITY_REQUIRED");
        String sourceKey = jdbc.queryForObject("SELECT source_key FROM eatwhat_sync.catalog_import_batch WHERE batch_id=? FOR UPDATE",
                String.class, batchId);
        List<Long> affectedAuthors = new ArrayList<>();
        for (CatalogHubImportRequest.HubItem item : hubs) {
            if (!item.rebindCoveredRestaurants()) continue;
            List<Long> existingHubIds = jdbc.query("SELECT numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key=? AND source_id=?",
                    (rs, row) -> rs.getLong(1), sourceKey, item.sourceId());
            Long existingHubId = existingHubIds.isEmpty() ? null : existingHubIds.getFirst();
            List<Long> owners = jdbc.query("""
                    SELECT r.account_id FROM public.restaurant r
                    WHERE (?::bigint IS NOT NULL AND r.hub_id=?)
                       OR (?::text IS NOT NULL AND ST_Covers(
                         ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?),4326))::geometry(MultiPolygon,4326),
                         r.location::geometry))
                    ORDER BY r.id LIMIT ?
                    """, (rs, row) -> rs.getLong(1), existingHubId, existingHubId,
                    item.boundaryGeoJson(), item.boundaryGeoJson(), properties.getMaxHubAssignments() + 1);
            if (owners.size() > properties.getMaxHubAssignments()) {
                throw new IllegalArgumentException("HUB_ASSIGNMENT_LIMIT");
            }
            affectedAuthors.addAll(owners);
            if (affectedAuthors.stream().distinct().count() > properties.getMaxHubAssignments()) {
                throw new IllegalArgumentException("HUB_ASSIGNMENT_LIMIT");
            }
        }
        // Hub reassignment mutates restaurant catalog objects. Lock their consent subjects before
        // the shared domain lock, matching the normal rebind path's subject -> domain ordering.
        lockSubjects(affectedAuthors);
        store.lockDomain();
        // Once the first item establishes managed HUB semantics, every remaining public mutation in
        // this batch must have an exact, authenticated command context as well.
        store.markManaged(CatalogKind.HUB);
        int created = 0;
        int updated = 0;
        int assignments = 0;
        for (CatalogHubImportRequest.HubItem item : hubs) {
            ImportedHub result = applyImportedHub(batchId, item, actorUserId);
            assignments += result.assignments();
            if (assignments > properties.getMaxHubAssignments()) throw new IllegalArgumentException("HUB_ASSIGNMENT_LIMIT");
            if (result.inserted()) created++;
            else updated++;
        }
        return new ImportCounts(created, updated);
    }

    public <T> WriteOutcome<T> create(CatalogKind kind, Long authorUserId, Long parentNumericId,
                                      Map<String, Object> naturalKeyFields, Supplier<T> mutation,
                                      LongSupplier insertedNumericId, Predicate<T> succeeded) {
        return create(kind, authorUserId, authorUserId, parentNumericId, naturalKeyFields, mutation,
                insertedNumericId, succeeded);
    }

    public <T> WriteOutcome<T> create(CatalogKind kind, Long actorUserId, Long authorUserId,
                                      Long parentNumericId, Map<String, Object> naturalKeyFields,
                                      Supplier<T> mutation, LongSupplier insertedNumericId,
                                      Predicate<T> succeeded) {
        return transactions.required(() -> {
            lockSubjects(authorUserId, parentAuthorUserId(kind, parentNumericId));
            store.lockDomain();
            requireCreateAuthority(kind);
            lockParent(kind, parentNumericId);
            requireVisibleParent(kind,parentNumericId);
            ensureParentTracked(kind, parentNumericId);
            lockNaturalKey(kind, naturalKeyFields, authorUserId, parentNumericId);
            beginCatalogBusinessWrite(kind, null, actorUserId, authorUserId, parentNumericId, "CREATE");
            T value = mutation.get();
            if (!succeeded.test(value)) return new WriteOutcome<>(Status.BLOCKED, value, null, null);
            long numericId = insertedNumericId.getAsLong();
            if (numericId <= 0) throw new IllegalStateException("CATALOG_NUMERIC_ID_UNAVAILABLE");
            Map<String, Object> payload = store.readPublic(kind, numericId);
            if (payload == null) throw new IllegalStateException("CATALOG_PUBLIC_ROW_UNAVAILABLE");
            CatalogStore.Identity identity = localIdentity(kind, numericId, authorUserId, parentNumericId, 0, false, false);
            store.insertObject(identity);
            CatalogEvents.Action action = nextAction(identity, CatalogOperation.CREATE, payload, null);
            appendLocalRevision(action);
            ObjectState state = advance(identity, action);
            store.updateObject(state.identity());
            UUID conflictId = registerCandidate(action);
            publishIfAuthorized(action, identity.authorGid(), identity.parentGid());
            store.markManaged(kind);
            return new WriteOutcome<>(conflictId == null ? Status.APPLIED : Status.CONFLICT,
                    value, identity.gid(), conflictId);
        });
    }

    public <T> WriteOutcome<T> createBatch(List<CreateSpec> specs, Supplier<T> mutation, Predicate<T> succeeded) {
        Long actor = specs == null || specs.isEmpty() ? null : specs.getFirst().authorUserId();
        return createBatch(specs, actor, mutation, succeeded);
    }

    public <T> WriteOutcome<T> createBatch(List<CreateSpec> specs, Long actorUserId,
                                            Supplier<T> mutation, Predicate<T> succeeded) {
        return transactions.required(() -> {
            List<CreateSpec> ordered = List.copyOf(specs).stream()
                    .sorted(Comparator.comparing(spec -> spec.kind().name() + ":" + spec.naturalKeyFields()))
                    .toList();
            List<Long> subjects = new ArrayList<>();
            for (CreateSpec spec : ordered) {
                if (spec.authorUserId() != null) subjects.add(spec.authorUserId());
                Long parentAuthor = parentAuthorUserId(spec.kind(), spec.parentNumericId());
                if (parentAuthor != null) subjects.add(parentAuthor);
            }
            lockSubjects(subjects);
            store.lockDomain();
            ordered.forEach(spec -> requireCreateAuthority(spec.kind()));
            for (CreateSpec spec : ordered) {
                lockParent(spec.kind(), spec.parentNumericId());
                requireVisibleParent(spec.kind(),spec.parentNumericId());
                ensureParentTracked(spec.kind(), spec.parentNumericId());
                lockNaturalKey(spec.kind(), spec.naturalKeyFields(), spec.authorUserId(), spec.parentNumericId());
            }
            for (CreateSpec spec : ordered) {
                beginCatalogBusinessWrite(spec.kind(), null, actorUserId, spec.authorUserId(),
                        spec.parentNumericId(), "CREATE");
            }
            // The supplier creates all missing tag definitions and taggings in the same business transaction.
            T value = mutation.get();
            if (!succeeded.test(value)) return new WriteOutcome<>(Status.BLOCKED, value, null, null);
            UUID firstCandidate = null;
            UUID firstConflict = null;
            for (CreateSpec spec : ordered) {
                Long id = spec.numericId().get();
                if (id == null) continue;
                var payload = store.readPublic(spec.kind(), id);
                if (payload == null) continue;
                CatalogStore.Identity identity = localIdentity(spec.kind(), id, spec.authorUserId(),
                        spec.parentNumericId(), 0, false, false);
                store.insertObject(identity);
                CatalogEvents.Action action = nextAction(identity, CatalogOperation.CREATE, payload, null);
                appendLocalRevision(action);
                store.updateObject(advance(identity, action).identity());
                UUID conflict = registerCandidate(action);
                publishIfAuthorized(action, identity.authorGid(), identity.parentGid());
                store.markManaged(spec.kind());
                if (firstCandidate == null) firstCandidate = identity.gid();
                if (firstConflict == null) firstConflict = conflict;
            }
            return new WriteOutcome<>(firstConflict == null ? Status.APPLIED : Status.CONFLICT,
                    value, firstCandidate, firstConflict);
        });
    }

    public <T> WriteOutcome<T> update(CatalogKind kind, long numericId,
                                      Function<Map<String, Object>, UpdatePlan<T>> planner) {
        return update(kind, numericId, null, planner);
    }

    public <T> WriteOutcome<T> update(CatalogKind kind, long numericId, Long actorUserId,
                                      Function<Map<String, Object>, UpdatePlan<T>> planner) {
        return transactions.required(() -> {
            CatalogStore.Identity preliminary = store.findByNumeric(kind, numericId);
            Map<String, Object> preliminaryPayload = preliminary == null ? store.readPublic(kind, numericId) : null;
            if (preliminary == null && preliminaryPayload == null) return new WriteOutcome<>(Status.BLOCKED, null, null, null);
            Long authorUserId = preliminary == null ? authorNumericId(kind, numericId)
                    : preliminary.authorGid() == null ? null : localUserId(preliminary.authorGid());
            Long oldParentId = preliminary == null ? parentNumericId(kind, preliminaryPayload)
                    : parentNumericId(preliminary);
            lockSubjects(authorUserId, parentAuthorUserId(kind, oldParentId));
            store.lockDomain();
            lockParent(kind, oldParentId);
            ensureParentTracked(kind, oldParentId);
            CatalogStore.Identity identity = preliminary == null
                    ? ensureTracked(kind, numericId, true) : store.lockObject(kind, preliminary.gid());
            if (identity == null || identity.tombstone() || identity.rejected()) {
                return new WriteOutcome<>(Status.BLOCKED, null, null, null);
            }
            store.lockPublicRow(kind, numericId);
            requireManagementAuthority(identity);
            ObjectState before = readState(identity);
            if(multiWriterEnabled() && (!multiwriter.editable(kind,identity.gid()) || before.heads().size()>1 && multiwriter.decisionHeads(kind,identity.gid()).isEmpty())) return new WriteOutcome<>(Status.CONFLICT,null,identity.gid(),CatalogStore.stableHeadConflictId(kind,identity.gid()));
            UpdatePlan<T> plan = planner.apply(store.readPublic(kind, numericId));
            if (plan == null) return new WriteOutcome<>(Status.BLOCKED, null, identity.gid(), null);
            lockParent(kind, plan.newParentNumericId());
            requireVisibleParent(kind,plan.newParentNumericId());
            Map<String, Object> oldKey = naturalFields(identity.kind(), before.payload());
            lockNaturalKey(kind, oldKey, null, null);
            lockNaturalKey(kind, plan.newNaturalKeyFields(), null, plan.newParentNumericId());
            beginCatalogBusinessWrite(kind, numericId, actorUserId != null ? actorUserId : authorUserId,
                    authorUserId, plan.newParentNumericId(), "UPDATE");
            T value = plan.mutation().get();
            if (!plan.succeeded().test(value)) return new WriteOutcome<>(Status.BLOCKED, value, identity.gid(), null);
            Map<String, Object> payload = store.readPublic(kind, numericId);
            if (payload == null) return new WriteOutcome<>(Status.BLOCKED, value, identity.gid(), null);
            UUID nextParent = parentGid(kind, payload, plan.newParentNumericId());
            CatalogStore.Identity updated = new CatalogStore.Identity(kind, identity.gid(), numericId,
                    identity.originSite(), authoritySite(kind), identity.authorGid(), nextParent,
                    identity.revision(), false, false, identity.causalState());
            CatalogEvents.Action action = nextAction(updated, CatalogOperation.UPDATE, payload, null);
            appendLocalRevision(action);
            retireNaturalKey(identity, before.payload());
            UUID conflict = registerCandidate(action);
            store.updateObject(advance(updated, action).identity());
            publishIfAuthorized(action, updated.authorGid(), updated.parentGid());
            store.markManaged(kind);
            return new WriteOutcome<>(conflict == null ? Status.APPLIED : Status.CONFLICT, value, identity.gid(), conflict);
        });
    }

    public <T> WriteOutcome<T> delete(CatalogKind kind, long numericId, Supplier<T> mutation,
                                      Predicate<T> succeeded) {
        return delete(kind, numericId, (Long) null,
                (Function<Map<String, Object>, DeletePlan<T>>) ignored -> new DeletePlan<>(mutation, succeeded));
    }

    public <T> WriteOutcome<T> delete(CatalogKind kind, long numericId, Long actorUserId,
                                      Supplier<T> mutation, Predicate<T> succeeded) {
        return delete(kind, numericId, actorUserId,
                (Function<Map<String, Object>, DeletePlan<T>>) ignored -> new DeletePlan<>(mutation, succeeded));
    }

    public <T> WriteOutcome<T> delete(CatalogKind kind, long numericId,
                                      Function<Map<String, Object>, DeletePlan<T>> planner) {
        return delete(kind, numericId, null, planner);
    }

    public <T> WriteOutcome<T> delete(CatalogKind kind, long numericId, Long actorUserId,
                                      Function<Map<String, Object>, DeletePlan<T>> planner) {
        return transactions.required(() -> {
            CatalogStore.Identity preliminary = store.findByNumeric(kind, numericId);
            Map<String, Object> preliminaryPayload = preliminary == null ? store.readPublic(kind, numericId) : null;
            if (preliminary == null && preliminaryPayload == null) return new WriteOutcome<>(Status.BLOCKED, null, null, null);
            Long authorUserId = preliminary == null ? authorNumericId(kind, numericId)
                    : preliminary.authorGid() == null ? null : localUserId(preliminary.authorGid());
            Long oldParentId = preliminary == null ? parentNumericId(kind, preliminaryPayload)
                    : parentNumericId(preliminary);
            List<Long> cascadedFoods = kind == CatalogKind.RESTAURANT ? foodIdsForRestaurant(numericId) : List.of();
            List<Long> subjectUsers = new ArrayList<>();
            if (authorUserId != null) subjectUsers.add(authorUserId);
            Long parentAuthor = parentAuthorUserId(kind, oldParentId);
            if (parentAuthor != null) subjectUsers.add(parentAuthor);
            for (Long foodId : cascadedFoods) {
                CatalogStore.Identity food = store.findByNumeric(CatalogKind.FOOD, foodId);
                Long foodAuthor = food == null ? authorNumericId(CatalogKind.FOOD, foodId)
                        : localUserId(food.authorGid());
                if (foodAuthor != null) subjectUsers.add(foodAuthor);
            }
            lockSubjects(subjectUsers);
            store.lockDomain();
            if (kind == CatalogKind.RESTAURANT && !cascadedFoods.equals(foodIdsForRestaurant(numericId))) {
                throw new IllegalStateException("CATALOG_CASCADE_SUBJECTS_CHANGED");
            }
            lockParent(kind, oldParentId);
            ensureParentTracked(kind, oldParentId);
            // Preserve the real identity and owner evidence before PostgreSQL cascades legacy foods.
            if (kind == CatalogKind.RESTAURANT) {
                store.lockPublicRow(CatalogKind.RESTAURANT, numericId);
                if (!cascadedFoods.equals(foodIdsForRestaurant(numericId))) {
                    throw new IllegalStateException("CATALOG_CASCADE_SUBJECTS_CHANGED");
                }
                for (Long foodId : cascadedFoods) ensureTracked(CatalogKind.FOOD, foodId, true);
            }
            CatalogStore.Identity identity = preliminary == null
                    ? ensureTracked(kind, numericId, true) : store.lockObject(kind, preliminary.gid());
            if (identity == null || identity.tombstone() || identity.rejected()) {
                return new WriteOutcome<>(Status.BLOCKED, null, null, null);
            }
            store.lockPublicRow(kind, numericId);
            requireManagementAuthority(identity);
            ObjectState before = readState(identity);
            DeletePlan<T> plan = planner.apply(before.payload());
            if (plan == null) return new WriteOutcome<>(Status.BLOCKED, null, identity.gid(), null);
            beginCatalogBusinessWrite(kind, numericId, actorUserId != null ? actorUserId : authorUserId,
                    authorUserId, oldParentId, "DELETE");
            T value = multiWriterEnabled()?retainDeleteProjection(plan.mutation()):plan.mutation().get();
            if (!plan.succeeded().test(value)) return new WriteOutcome<>(Status.BLOCKED, value, identity.gid(), null);
            CatalogStore.Identity tombstone = new CatalogStore.Identity(kind, identity.gid(), numericId,
                    identity.originSite(), authoritySite(kind), identity.authorGid(), identity.parentGid(),
                    identity.revision(), true, false, identity.causalState());
            CatalogEvents.Action action = nextAction(tombstone, CatalogOperation.DELETE, Map.of(), null);
            appendLocalRevision(action);
            retireNaturalKey(identity, before.payload());
            store.updateObject(advance(tombstone, action).identity());
            publishIfAuthorized(action, identity.authorGid(), identity.parentGid());
            if(multiWriterEnabled())multiwriter.projectionState(kind,identity.gid(),"SUPPRESSED",List.of());
            store.markManaged(kind);
            return new WriteOutcome<>(Status.APPLIED, value, identity.gid(), null);
        });
    }

    /** Catalog delete adapters are SQL-only. Keep their typed affected-row result while retaining FK evidence. */
    private <T> T retainDeleteProjection(Supplier<T> mutation) {
        jdbc.execute("SAVEPOINT catalog_retained_delete");
        try {return mutation.get();}
        finally {jdbc.execute("ROLLBACK TO SAVEPOINT catalog_retained_delete");jdbc.execute("RELEASE SAVEPOINT catalog_retained_delete");}
    }

    public <T> WriteOutcome<T> deleteHub(long hubId, Supplier<T> hubDelete, Predicate<T> succeeded) {
        return deleteHub(hubId, null, hubDelete, succeeded);
    }

    public <T> WriteOutcome<T> deleteHub(long hubId, Long actorUserId,
                                          Supplier<T> hubDelete, Predicate<T> succeeded) {
        return transactions.required(() -> {
            CatalogStore.Identity preliminaryHub = store.findByNumeric(CatalogKind.HUB, hubId);
            List<Long> restaurants = store.boundRestaurantIds(hubId);
            List<Long> subjectIds = new ArrayList<>();
            for (Long restaurantId : restaurants) {
                CatalogStore.Identity existing = store.findByNumeric(CatalogKind.RESTAURANT, restaurantId);
                Long author = existing == null ? authorNumericId(CatalogKind.RESTAURANT, restaurantId)
                        : existing.authorGid() == null ? null : localUserId(existing.authorGid());
                if (author != null) subjectIds.add(author);
            }
            lockSubjects(subjectIds);
            store.lockDomain();
            store.lockPublicRow(CatalogKind.HUB, hubId);
            for (Long restaurantId : restaurants) store.lockPublicRow(CatalogKind.RESTAURANT, restaurantId);
            CatalogStore.Identity hub = preliminaryHub == null
                    ? ensureTracked(CatalogKind.HUB, hubId, true)
                    : store.lockObject(CatalogKind.HUB, preliminaryHub.gid());
            if (hub == null || hub.tombstone()) return new WriteOutcome<>(Status.BLOCKED, null, null, null);
            requireManagementAuthority(hub);
            if(multiWriterEnabled()&&!multiwriter.editable(CatalogKind.HUB,hub.gid()))throw new IllegalArgumentException("CATALOG_HUB_CONFLICT");
            if (restaurants.size() > properties.getMaxHubAssignments()) {
                return new WriteOutcome<>(Status.BLOCKED, null, hub.gid(), null);
            }
            beginCatalogBusinessWrite(CatalogKind.HUB, hubId, actorUserId, null, null, "DELETE");
            List<Map<String, Object>> priorPayloads = new ArrayList<>();
            List<CatalogStore.Identity> restaurantIdentities = new ArrayList<>();
            for (Long restaurantId : restaurants) {
                CatalogStore.Identity restaurant = ensureTracked(CatalogKind.RESTAURANT, restaurantId, true);
                if (restaurant == null) throw new IllegalStateException("CATALOG_ASSIGNMENT_IDENTITY_UNAVAILABLE");
                if(multiWriterEnabled()&&!multiwriter.editable(CatalogKind.RESTAURANT,restaurant.gid()))throw new IllegalArgumentException("CATALOG_ASSIGNMENT_CONFLICT");
                beginCatalogBusinessWrite(CatalogKind.RESTAURANT, restaurantId, actorUserId,
                        restaurant.authorGid() == null ? null : localUserId(restaurant.authorGid()), hubId,
                        "HUB_ASSIGNMENT");
                priorPayloads.add(store.readPublic(CatalogKind.RESTAURANT, restaurantId));
                restaurantIdentities.add(restaurant);
            }
            T value = multiWriterEnabled()?retainDeleteProjection(hubDelete):hubDelete.get();
            if (!succeeded.test(value)) return new WriteOutcome<>(Status.BLOCKED, value, hub.gid(), null);
            for (int index = 0; index < restaurants.size(); index++) {
                Long restaurantId = restaurants.get(index);
                CatalogStore.Identity restaurant = restaurantIdentities.get(index);
                Map<String, Object> payload = store.readPublic(CatalogKind.RESTAURANT, restaurantId);
                if (payload == null) throw new IllegalStateException("HUB_ASSIGNMENT_PROJECTION_MISSING");
                if (Objects.equals(payload.get("hubGid"), hub.gid().toString())) {
                    jdbc.update("UPDATE public.restaurant SET hub_id=NULL WHERE id=?", restaurantId);
                    payload = store.readPublic(CatalogKind.RESTAURANT, restaurantId);
                }
                if (Objects.equals(priorPayloads.get(index).get("hubGid"), payload.get("hubGid"))) continue;
                CatalogStore.Identity changed = new CatalogStore.Identity(CatalogKind.RESTAURANT, restaurant.gid(), restaurantId,
                        restaurant.originSite(), authoritySite(CatalogKind.RESTAURANT), restaurant.authorGid(), null,
                        restaurant.revision(), false, false, restaurant.causalState());
                appendAndAdvance(nextAction(changed, CatalogOperation.HUB_ASSIGNMENT, payload, null), changed);
            }
            CatalogStore.Identity tombstone = new CatalogStore.Identity(CatalogKind.HUB, hub.gid(), hubId,
                    hub.originSite(), authoritySite(CatalogKind.HUB), null, null, hub.revision(), true, false, hub.causalState());
            CatalogEvents.Action action = nextAction(tombstone, CatalogOperation.DELETE, Map.of(), null);
            appendAndAdvance(action, tombstone);
            if(multiWriterEnabled())multiwriter.projectionState(CatalogKind.HUB,hub.gid(),"SUPPRESSED",List.of());
            store.markManaged(CatalogKind.HUB);
            return new WriteOutcome<>(Status.APPLIED, value, hub.gid(), null);
        });
    }

    public CatalogHubRebindResult rebindHub(long hubId) {
        return rebindHub(hubId, null);
    }

    public CatalogHubRebindResult rebindHub(long hubId, Long actorUserId) {
        return transactions.required(() -> {
            List<Long> prior = store.boundRestaurantIds(hubId);
            List<Long> covered = jdbc.query("""
                    SELECT r.id FROM public.restaurant r JOIN public.hub h ON h.id=?
                    WHERE h.catalog_visible AND r.catalog_visible AND h.boundary IS NOT NULL AND ST_Covers(h.boundary,r.location::geometry)
                    ORDER BY r.id
                    """, (rs, row) -> rs.getLong(1), hubId);
            java.util.TreeSet<Long> allIds = new java.util.TreeSet<>(prior);
            allIds.addAll(covered);
            if (allIds.size() > properties.getMaxHubAssignments()) throw new IllegalArgumentException("HUB_ASSIGNMENT_LIMIT");
            List<Long> subjectIds = new ArrayList<>();
            for (Long id : allIds) {
                CatalogStore.Identity existing = store.findByNumeric(CatalogKind.RESTAURANT, id);
                Long author = existing == null ? authorNumericId(CatalogKind.RESTAURANT, id)
                        : existing.authorGid() == null ? null : localUserId(existing.authorGid());
                if (author != null) subjectIds.add(author);
            }
            lockSubjects(subjectIds);
            store.lockDomain();
            store.lockPublicRow(CatalogKind.HUB, hubId);
            for (Long id : allIds) store.lockPublicRow(CatalogKind.RESTAURANT, id);
            CatalogStore.Identity hub = ensureTracked(CatalogKind.HUB, hubId, true);
            if (hub == null || hub.tombstone() || hub.rejected()) throw new IllegalArgumentException("HUB_NOT_FOUND");
            requireManagementAuthority(hub);
            if(multiWriterEnabled()&&!multiwriter.editable(CatalogKind.HUB,hub.gid()))throw new IllegalArgumentException("CATALOG_HUB_CONFLICT");
            Set<Long> coveredSet = Set.copyOf(covered);
            List<CatalogHubRebindResult.Assignment> assignments = new ArrayList<>();
            for (Long id : allIds) {
                CatalogStore.Identity restaurant = ensureTracked(CatalogKind.RESTAURANT, id, true);
                Map<String, Object> old = store.readPublic(CatalogKind.RESTAURANT, id);
                if (old == null) continue;
                if(multiWriterEnabled()&&!multiwriter.editable(CatalogKind.RESTAURANT,restaurant.gid()))throw new IllegalArgumentException("CATALOG_ASSIGNMENT_CONFLICT");
                boolean shouldBind = coveredSet.contains(id);
                boolean currentlyBound = Objects.equals(old.get("hubGid"), hub.gid().toString());
                if (shouldBind == currentlyBound) continue;
                beginCatalogBusinessWrite(CatalogKind.RESTAURANT, id, actorUserId,
                        restaurant.authorGid() == null ? null : localUserId(restaurant.authorGid()),
                        shouldBind ? hubId : null, "HUB_ASSIGNMENT");
                if (shouldBind) jdbc.update("UPDATE public.restaurant SET hub_id=? WHERE id=?", hubId, id);
                else jdbc.update("UPDATE public.restaurant SET hub_id=NULL WHERE id=?", id);
                Map<String, Object> next = store.readPublic(CatalogKind.RESTAURANT, id);
                CatalogStore.Identity changed = new CatalogStore.Identity(CatalogKind.RESTAURANT, restaurant.gid(), id,
                        restaurant.originSite(), authoritySite(CatalogKind.RESTAURANT), restaurant.authorGid(),
                        shouldBind ? hub.gid() : null,
                        restaurant.revision(), false, false, restaurant.causalState());
                CatalogEvents.Action action = nextAction(changed, CatalogOperation.HUB_ASSIGNMENT, next, null);
                appendAndAdvance(action, changed);
                assignments.add(new CatalogHubRebindResult.Assignment(restaurant.gid(), restaurant.revision(),
                        shouldBind ? hub.gid() : null));
            }
            return new CatalogHubRebindResult(hubId, hub.gid(), hub.revision(), covered.size(),
                    List.copyOf(assignments));
        });
    }

    public <T> WriteOutcome<T> mutateCustomTag(long tagId, long ownerUserId, CatalogOperation operation,
                                               Map<String, Object> patch, Supplier<T> mutation, Predicate<T> succeeded) {
        return transactions.required(() -> {
            lockSubjects(ownerUserId, null);
            store.lockDomain();
            CatalogStore.Identity identity = ensureTracked(CatalogKind.CUSTOM_TAG, tagId, true);
            if (identity == null || !Objects.equals(identity.authorGid(), store.userGid(ownerUserId))) {
                return new WriteOutcome<>(Status.BLOCKED, null, null, null);
            }
            store.lockPublicRow(CatalogKind.CUSTOM_TAG, tagId);
            if (identity.tombstone() || identity.rejected()) return new WriteOutcome<>(Status.BLOCKED, null, identity.gid(), null);
            Map<String, Object> before = store.readPublic(CatalogKind.CUSTOM_TAG, tagId);
            if (before == null) return new WriteOutcome<>(Status.BLOCKED, null, identity.gid(), null);
            Map<String, Object> payload = new TreeMap<>(before);
            payload.putAll(patch);
            if (operation != CatalogOperation.DELETE) payload = CatalogPayload.validate(CatalogKind.CUSTOM_TAG, payload);
            lockNaturalKey(CatalogKind.CUSTOM_TAG, naturalFields(CatalogKind.CUSTOM_TAG, before), ownerUserId, null);
            lockNaturalKey(CatalogKind.CUSTOM_TAG, naturalFields(CatalogKind.CUSTOM_TAG, payload), ownerUserId, null);
            ObjectState state = readState(identity);
            if (state.heads().size() > 1 || state.heads().stream().anyMatch(CatalogEvents.Head::tombstone)) {
                return new WriteOutcome<>(Status.BLOCKED, null, identity.gid(), null);
            }
            beginCatalogBusinessWrite(CatalogKind.CUSTOM_TAG, tagId, ownerUserId, ownerUserId, null,
                    operation.name());
            T value = mutation.get();
            if (!succeeded.test(value)) return new WriteOutcome<>(Status.BLOCKED, value, identity.gid(), null);
            boolean deleting = operation == CatalogOperation.DELETE;
            CatalogStore.Identity changed = new CatalogStore.Identity(CatalogKind.CUSTOM_TAG, identity.gid(), tagId,
                    identity.originSite(), identity.authoritySite(), identity.authorGid(), null,
                    identity.revision(), deleting, false, identity.causalState());
            CatalogEvents.Action action = nextAction(changed, operation, deleting ? Map.of() : payload, null);
            retireNaturalKey(identity, before);
            appendAndAdvance(action, changed);
            UUID conflict = deleting ? null : registerCandidate(action);
            store.markManaged(CatalogKind.CUSTOM_TAG);
            return new WriteOutcome<>(conflict == null ? Status.APPLIED : Status.CONFLICT,
                    value, identity.gid(), conflict);
        });
    }

    public <T> WriteOutcome<T> createSystemTag(CatalogKind kind, Long author, Map<String, Object> fields,
                                                Supplier<T> mutation, LongSupplier id, Predicate<T> succeeded) {
        return create(kind, author, null, fields, mutation, id, succeeded);
    }

    public <T> WriteOutcome<T> createSystemTag(CatalogKind kind, Long actorUserId, Long author,
                                                Map<String, Object> fields, Supplier<T> mutation,
                                                LongSupplier id, Predicate<T> succeeded) {
        return create(kind, actorUserId, author, null, fields, mutation, id, succeeded);
    }

    private boolean beginCatalogBusinessWrite(CatalogKind kind, Long numericId, Long actorUserId,
                                              Long authorUserId, Long parentNumericId, String operation) {
        boolean expectOutbox = switch (kind) {
            case HUB, SYSTEM_TAG, ACTIVITY -> true;
            case RESTAURANT, CUSTOM_TAG -> authorUserId != null && consent.replicationAllowed(authorUserId);
            case FOOD -> authorUserId != null && consent.replicationAllowed(authorUserId)
                    && parentAuthorUserId(kind, parentNumericId) != null
                    && consent.replicationAllowed(parentAuthorUserId(kind, parentNumericId));
        };
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT eatwhat_sync.begin_catalog_business_write(
                  ?::text, ?::bigint, ?::bigint, ?::bigint, ?::bigint, ?::text, ?::boolean)
                """, Boolean.class, kind.name(), numericId, actorUserId, authorUserId, parentNumericId,
                operation, expectOutbox));
    }

    private void appendAndAdvance(CatalogEvents.Action action, CatalogStore.Identity identity) {
        appendLocalRevision(action);
        store.updateObject(advance(identity, action).identity());
        publishIfAuthorized(action, action.authorGid(), action.parentGid());
    }

    private CatalogStore.Identity ensureTracked(CatalogKind kind, long numericId, boolean lock) {
        UUID gid;
        try { gid = store.globalId(entityType(kind), numericId); }
        catch (IllegalStateException missing) { return null; }
        CatalogStore.Identity known = lock ? store.lockObject(kind, gid) : store.findObject(kind, gid);
        if (known != null) {
            if (known.numericId() != numericId) throw new IllegalStateException("CATALOG_IDENTITY_CONFLICT");
            return known;
        }
        Map<String, Object> payload = store.readPublic(kind, numericId);
        if (payload == null) return null;
        String origin = store.identityOrigin(entityType(kind), numericId);
        Long authorId = authorNumericId(kind, numericId);
        UUID author = authorId == null ? null : store.userGid(authorId);
        UUID parent = parentGid(kind, payload, null);
        CatalogStore.Identity seeded = new CatalogStore.Identity(kind, gid, numericId, origin,
                authoritySite(kind), author, parent, 0, false, false, Map.of("seen", Map.of(), "heads", List.of()));
        store.insertObject(seeded);
        store.markManaged(kind);
        return lock ? store.lockObject(kind, gid) : seeded;
    }

    void seedConflictLoserFoods(List<Long> restaurantIds) {
        for (Long restaurantId : restaurantIds) {
            List<Long> foodIds = jdbc.query("SELECT id FROM public.foods WHERE restaurant_id=? ORDER BY id",
                    (rs, row) -> rs.getLong(1), restaurantId);
            for (Long foodId : foodIds) {
                if (ensureTracked(CatalogKind.FOOD, foodId, true) == null) {
                    throw new IllegalStateException("CATALOG_CASCADE_IDENTITY_MISSING");
                }
            }
        }
    }

    private CatalogEvents.Action baselineAction(CatalogStore.Identity identity, Map<String, Object> payload) {
        Map<String, Object> canonical = CatalogPayload.validate(identity.kind(), payload);
        List<CatalogEvents.ConsentEpoch> epochs = consentEpochs(identity.kind(), identity.gid(),
                identity.authorGid(), identity.parentGid(), null);
        String hash = EventCodec.sha256(EventCodec.canonicalJson(toJson(canonical)));
        UUID operationId = UUID.nameUUIDFromBytes(("eatwhat.catalog.baseline:" + identity.kind() + ":"
                + identity.gid() + ":" + hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new CatalogEvents.Action(identity.kind(), identity.gid(), 1, 0, CatalogOperation.CREATE,
                identity.authoritySite(), identity.authorGid(), identity.parentGid(), identity.numericId(),
                identity.originSite(), canonical, hash, operationId, identity.originSite(), 1, Map.of(),
                epochs, null, null);
    }

    private String bridgeSignature(CatalogStore.Identity identity, UUID subject, long epoch,
                                   List<CatalogEvents.Head> heads,
                                   List<CatalogEvents.ConsentEpoch> epochs) {
        String bridge = CatalogEvents.bridgePayload(new CatalogEvents.StateBridge(identity.kind(), identity.gid(),
                identity.numericId(), identity.originSite(), identity.authoritySite(), identity.parentGid(), epochs, heads));
        // A state snapshot can be produced independently by both sites for the same consent epoch.
        // The transport event identity is origin-scoped so their local transport numeric IDs never
        // alias one global event aggregate in the peer inbox.
        return EventCodec.sha256(bridge + ":subject=" + subject + ":epoch=" + epoch
                + ":origin=" + site.getId() + ":instance=" + site.getInstanceId());
    }

    private boolean bridgeAlreadyPublished(String signature) {
        UUID eventId = UUID.nameUUIDFromBytes(("eatwhat.catalog.bridge:" + signature)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.outbox WHERE event_id=?)",
                Boolean.class, eventId));
    }

    private CatalogStore.Identity localIdentity(CatalogKind kind, long id, Long authorUserId,
                                                Long parentNumericId, long revision, boolean tombstone, boolean rejected) {
        UUID gid = store.globalId(entityType(kind), id);
        String origin = store.identityOrigin(entityType(kind), id);
        UUID authorGid = authorUserId == null ? null : store.userGid(authorUserId);
        Map<String, Object> payload = store.readPublic(kind, id);
        UUID parent = parentGid(kind, payload == null ? Map.of() : payload, parentNumericId);
        return new CatalogStore.Identity(kind, gid, id, origin, authoritySite(kind), authorGid,
                parent, revision, tombstone, rejected, Map.of("seen", Map.of(), "heads", List.of()));
    }

    private CatalogEvents.Action nextAction(CatalogStore.Identity identity, CatalogOperation operation,
                                            Map<String, Object> payload, CatalogEvents.ResolutionProof proof) {
        ObjectState state = readState(identity);
        String dotSite = site.getId();
        long counter = state.seen().getOrDefault(dotSite, 0L) + 1;
        if (counter > VersionVector.MAX_COUNTER) throw new IllegalStateException("CATALOG_CAUSAL_LIMIT");
        Map<String, Long> context = new TreeMap<>(state.seen());
        Map<String, Object> canonicalPayload = operation == CatalogOperation.DELETE ? Map.of()
                : CatalogPayload.validate(identity.kind(), payload);
        long revision = identity.revision() + 1;
        UUID operationId = UUID.randomUUID();
        List<CatalogEvents.ConsentEpoch> epochs = consentEpochs(identity.kind(), identity.gid(),
                identity.authorGid(), identity.parentGid(), proof);
        String hash = EventCodec.sha256(EventCodec.canonicalJson(toJson(canonicalPayload)));
        return new CatalogEvents.Action(identity.kind(), identity.gid(), revision, identity.revision(), operation,
                identity.authoritySite(), identity.authorGid(), identity.parentGid(), identity.numericId(),
                identity.originSite(), canonicalPayload, hash, operationId, dotSite, counter, context, epochs,
                null, proof);
    }

    private ObjectState advance(CatalogStore.Identity identity, CatalogEvents.Action action) {
        ObjectState current = readState(identity);
        Map<String, Long> seen = new TreeMap<>(current.seen());
        seen.merge(action.dotSite(), action.dotCounter(), Math::max);
        List<CatalogEvents.Head> heads = new ArrayList<>(current.heads());
        heads.removeIf(head -> observedBy(action.context(), head.dotSite(), head.dotCounter()));
        heads.add(new CatalogEvents.Head(action.operationId(), site.getId(), action.authoritySite(),
                action.authorGid(), action.parentGid(), action.numericId(), action.identityOrigin(), action.revision(),
                action.expectedRevision(), action.operation(), action.dotSite(), action.dotCounter(), action.context(),
                action.consentEpochs(), action.operation() == CatalogOperation.DELETE,
                action.canonicalPayload(), action.contentHash(), action.resolutionProof()));
        List<Map<String, Object>> headMaps = heads.stream().map(CatalogCommands::toMap).toList();
        Map<String, Object> state = Map.of("seen", seen, "heads", headMaps);
        CatalogStore.Identity next = new CatalogStore.Identity(identity.kind(), identity.gid(), identity.numericId(),
                identity.originSite(), identity.authoritySite(), identity.authorGid(), identity.parentGid(),
                action.revision(), action.operation() == CatalogOperation.DELETE, identity.rejected(), state);
        return new ObjectState(next, Map.copyOf(seen), List.copyOf(heads), current.payload());
    }

    private void publishIfAuthorized(CatalogEvents.Action action, UUID authorGid, UUID parentGid) {
        if (!authorized(action.consentEpochs())) return;
        long transportNumericId = jdbc.queryForObject("SELECT eatwhat_sync.next_catalog_number()", Long.class);
        UUID instance = site.getInstanceId();
        SyncEvent event = new SyncEvent(action.operationId(), site.getId(), instance, CatalogEvents.AGGREGATE,
                action.operationId(), transportNumericId, CatalogEvents.ACTION, multiWriterEnabled()?2:CatalogEvents.SCHEMA_VERSION,
                VersionVector.empty(), VersionVector.empty().increment(site.getId()), List.of(), action.authorGid(),
                "catalog:" + action.operationId(), action.operationId(), multiWriterEnabled()?CatalogMultiwriterEvents.actionPayload(action, Objects.requireNonNull(multiwriter.find(action.operationId()))):CatalogEvents.payload(action));
        outbox.append(event, recipients());
    }

    private boolean authorized(List<CatalogEvents.ConsentEpoch> epochs) {
        for (CatalogEvents.ConsentEpoch epoch : epochs) {
            ConsentModels.State state = consent.getBySubject(epoch.subjectGid());
            if (state == null || state.epoch() != epoch.epoch() || !consent.replicationAllowed(state.userId())) return false;
        }
        return true;
    }

    private List<CatalogEvents.ConsentEpoch> consentEpochs(CatalogKind kind, UUID objectGid, UUID author, UUID parent,
                                                           CatalogEvents.ResolutionProof proof) {
        Map<UUID, CatalogEvents.ConsentEpoch> values = new TreeMap<>(Comparator.comparing(UUID::toString));
        if (author != null) addEpoch(values, author);
        if (kind == CatalogKind.FOOD) {
            CatalogStore.Identity previous = store.findObject(CatalogKind.FOOD, objectGid);
            addFoodParentAuthor(values, previous == null ? null : previous.parentGid());
            addFoodParentAuthor(values, parent);
        }
        if (proof != null) {
            for (UUID operationId : proof.expectedHeads()) {
                if (!store.hasOperation(operationId)) throw new IllegalStateException("CATALOG_RESOLUTION_PREDECESSOR_MISSING");
                List<UUID> subjects = store.subjectsForOperation(operationId);
                for (UUID subject : subjects) addEpoch(values, subject);
            }
        }
        if (values.size() > CatalogEvents.MAX_CONSENT_SUBJECTS) {
            throw new IllegalStateException("CATALOG_CONSENT_SET_TOO_LARGE");
        }
        return List.copyOf(values.values());
    }

    private void addFoodParentAuthor(Map<UUID, CatalogEvents.ConsentEpoch> values, UUID parentGid) {
        CatalogStore.Identity parent = parentGid == null ? null : store.findObject(CatalogKind.RESTAURANT, parentGid);
        if (parent == null || parent.authorGid() == null) {
            throw new IllegalStateException("CATALOG_PARENT_AUTHOR_UNAVAILABLE");
        }
        addEpoch(values, parent.authorGid());
    }

    private void addEpoch(Map<UUID, CatalogEvents.ConsentEpoch> result, UUID subject) {
        if (result.containsKey(subject)) return;
        ConsentModels.State state = consent.getBySubject(subject);
        result.put(subject, new CatalogEvents.ConsentEpoch(subject, state == null ? 0 : state.epoch()));
    }

    private UUID registerCandidate(CatalogEvents.Action action) {
        CatalogPayload.NaturalKey key = CatalogPayload.naturalKey(action.kind(), action.canonicalPayload(),
                action.authorGid() == null ? null : action.authorGid().toString(),
                action.parentGid() == null ? null : action.parentGid().toString());
        if (key == null) return null;
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_natural_key(kind,key_hash,canonical_key)
                VALUES (?, ?, ?::jsonb) ON CONFLICT(kind,key_hash) DO NOTHING
                """, action.kind().name(), key.hash(), key.canonical());
        String savedKey = jdbc.queryForObject("SELECT canonical_key::text FROM eatwhat_sync.catalog_natural_key WHERE kind=? AND key_hash=? FOR UPDATE",
                String.class, action.kind().name(), key.hash());
        if (!EventCodec.canonicalJson(savedKey).equals(key.canonical())) throw new IllegalStateException("CATALOG_NATURAL_KEY_HASH_COLLISION");
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_candidate(candidate_id,kind,key_hash,object_gid,numeric_id,
                  source_site,author_gid,revision,content_hash,summary,status)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,'PENDING') ON CONFLICT(kind,key_hash,object_gid) DO UPDATE
                  SET candidate_id=CASE WHEN EXCLUDED.revision>=catalog_candidate.revision
                                        THEN EXCLUDED.candidate_id ELSE catalog_candidate.candidate_id END,
                      revision=GREATEST(catalog_candidate.revision,EXCLUDED.revision),
                      content_hash=CASE WHEN EXCLUDED.revision>=catalog_candidate.revision
                                        THEN EXCLUDED.content_hash ELSE catalog_candidate.content_hash END,
                      summary=CASE WHEN EXCLUDED.revision>=catalog_candidate.revision
                                   THEN EXCLUDED.summary ELSE catalog_candidate.summary END,
                      status=CASE WHEN EXCLUDED.revision>=catalog_candidate.revision
                                        AND catalog_candidate.status='SUPERSEDED'
                                   THEN 'PENDING' ELSE catalog_candidate.status END,
                      updated_at=clock_timestamp()
                """, action.operationId(), action.kind().name(), key.hash(), action.objectGid(), action.numericId(),
                site.getId(), action.authorGid(), action.revision(), action.contentHash(),
                EventCodec.canonicalJson(toJson(Map.of("name", action.canonicalPayload().getOrDefault("name", "")))));
        List<UUID> gids = jdbc.query("""
                SELECT object_gid FROM eatwhat_sync.catalog_candidate WHERE kind=? AND key_hash=? AND status NOT IN ('REJECTED','SUPERSEDED')
                ORDER BY object_gid FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), action.kind().name(), key.hash());
        if (gids.size() < 2) return null;
        UUID conflictId = jdbc.query("SELECT conflict_id FROM eatwhat_sync.catalog_conflict WHERE kind=? AND key_hash=? FOR UPDATE",
                    (rs, row) -> rs.getObject(1, UUID.class), action.kind().name(), key.hash()).stream().findFirst()
                    .orElseGet(() -> CatalogStore.stableConflictId(action.kind(), key.hash()));
        List<Map<String, Object>> candidates = jdbc.query("""
                SELECT object_gid::text gid,numeric_id,source_site,revision,content_hash FROM eatwhat_sync.catalog_candidate
                WHERE kind=? AND key_hash=? AND status NOT IN ('REJECTED','SUPERSEDED') ORDER BY object_gid
                """, (rs, row) -> Map.of("gid", rs.getString("gid"), "numericId", rs.getLong("numeric_id"),
                "sourceSite", rs.getString("source_site"), "revision", rs.getLong("revision"),
                "contentHash", rs.getString("content_hash")), action.kind().name(), key.hash());
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_conflict(conflict_id,kind,key_hash,revision,status,candidates)
                VALUES (?,?,?,1,'OPEN',?::jsonb)
                ON CONFLICT(kind,key_hash) DO UPDATE SET revision=eatwhat_sync.catalog_conflict.revision+1,
                  status='OPEN',candidates=EXCLUDED.candidates,updated_at=clock_timestamp()
                """, conflictId, action.kind().name(), key.hash(), EventCodec.canonicalJson(toJson(candidates)));
        return conflictId;
    }

    private void lockNaturalKey(CatalogKind kind, Map<String, Object> fields, Long ownerUserId, Long parentId) {
        if (kind == CatalogKind.HUB || fields == null || fields.isEmpty()) return;
        Map<String, Object> key = new TreeMap<>(fields);
        if (kind == CatalogKind.CUSTOM_TAG && ownerUserId != null) key.put("ownerGid", store.userGid(ownerUserId).toString());
        if (kind == CatalogKind.FOOD && parentId != null) {
            key.put("restaurantGid", store.globalId(EntityType.RESTAURANT, parentId).toString());
            key.remove("restaurantId");
            key.put("name", you.v50to.eatwhat.utils.FoodNames.key((String) key.get("name")));
        }
        String serialized = EventCodec.canonicalJson(toJson(key));
        store.lockNaturalKey(kind, EventCodec.sha256(serialized));
    }

    private void lockSubjects(Long authorUserId, Long parentAuthorId) {
        List<Long> ids = new ArrayList<>();
        if (authorUserId != null) ids.add(authorUserId);
        if (parentAuthorId != null) ids.add(parentAuthorId);
        lockSubjects(ids);
    }

    private void lockSubjects(List<Long> userIds) {
        List<Long> ids = new ArrayList<>(userIds);
        ids = ids.stream().distinct().sorted().toList();
        for (Long id : ids) jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), id);
    }

    private List<Long> subjectUsers(List<CatalogEvents.Head> heads) {
        Set<Long> users = new java.util.TreeSet<>();
        for (CatalogEvents.Head head : heads) {
            if (head.authorGid() != null) {
                Long author = localUserId(head.authorGid());
                if (author != null) users.add(author);
            }
            CatalogEvents.ResolutionProof proof = head.resolutionProof();
            if (proof != null) for (UUID operationId : proof.expectedHeads()) {
                for (UUID subject : store.subjectsForOperation(operationId)) {
                    Long user = localUserId(subject);
                    if (user != null) users.add(user);
                }
            }
        }
        return List.copyOf(users);
    }

    private List<Long> foodIdsForRestaurant(long restaurantId) {
        return jdbc.query("SELECT id FROM public.foods WHERE restaurant_id=? ORDER BY id",
                (rs, row) -> rs.getLong(1), restaurantId);
    }

    private void addParentAuthorUser(List<Long> userIds, UUID parentGid) {
        if (parentGid == null) return;
        CatalogStore.Identity parent = store.findObject(CatalogKind.RESTAURANT, parentGid);
        if (parent == null || parent.authorGid() == null) return;
        Long authorUserId = localUserId(parent.authorGid());
        if (authorUserId != null) userIds.add(authorUserId);
    }

    private boolean sameHeadVersions(List<CatalogEvents.Head> actual, List<HeadVersion> expected) {
        List<HeadVersion> versions = actual.stream().map(head -> new HeadVersion(head.operationId(),
                        head.revision(), head.contentHash()))
                .sorted(Comparator.comparing(head -> head.operationId().toString())).toList();
        List<HeadVersion> requested = expected.stream()
                .sorted(Comparator.comparing(head -> head.operationId().toString())).toList();
        return versions.equals(requested);
    }

    private boolean sameHeadSet(List<CatalogEvents.Head> left, List<CatalogEvents.Head> right) {
        List<HeadVersion> leftVersions = left.stream().map(head -> new HeadVersion(head.operationId(),
                head.revision(), head.contentHash())).toList();
        List<HeadVersion> rightVersions = right.stream().map(head -> new HeadVersion(head.operationId(),
                head.revision(), head.contentHash())).toList();
        Comparator<HeadVersion> order = Comparator.comparing(head -> head.operationId().toString());
        return leftVersions.stream().sorted(order).toList().equals(rightVersions.stream().sorted(order).toList());
    }

    private void requireVisibleParent(CatalogKind child,Long id) {
        if(!multiWriterEnabled()||id==null)return;
        CatalogKind parent=child==CatalogKind.FOOD?CatalogKind.RESTAURANT:child==CatalogKind.RESTAURANT?CatalogKind.HUB:null;
        if(parent!=null&&!CatalogVisibility.visible(jdbc,parent,id))throw new IllegalStateException("CATALOG_PARENT_CONFLICT");
    }

    private void lockParent(CatalogKind kind, Long parentNumericId) {
        if (parentNumericId == null) return;
        String table = kind == CatalogKind.FOOD ? "restaurant" : kind == CatalogKind.RESTAURANT ? "hub" : null;
        if (table != null) {
            List<Long> found = jdbc.query("SELECT id FROM public." + table + " WHERE id=? FOR KEY SHARE",
                    (rs, row) -> rs.getLong(1), parentNumericId);
            if (found.isEmpty()) throw new IllegalStateException("CATALOG_PARENT_UNAVAILABLE");
        }
    }

    private Long parentAuthorUserId(CatalogKind kind, Long parentId) {
        if (kind != CatalogKind.FOOD || parentId == null) return null;
        return jdbc.query("SELECT account_id FROM public.restaurant WHERE id=?",
                (rs, row) -> rs.getLong(1), parentId).stream().findFirst().orElse(null);
    }

    private void ensureParentTracked(CatalogKind childKind, Long parentId) {
        if (childKind == CatalogKind.FOOD && parentId != null
                && ensureTracked(CatalogKind.RESTAURANT, parentId, true) == null) {
            throw new IllegalStateException("CATALOG_PARENT_IDENTITY_UNAVAILABLE");
        }
    }

    private Long parentNumericId(CatalogKind kind, Map<String, Object> payload) {
        if (payload == null) return null;
        String key = kind == CatalogKind.FOOD ? "restaurantGid" : kind == CatalogKind.RESTAURANT ? "hubGid" : null;
        if (key == null || payload.get(key) == null) return null;
        EntityType type = kind == CatalogKind.FOOD ? EntityType.RESTAURANT : EntityType.HUB;
        return store.numericId(type, UUID.fromString(String.valueOf(payload.get(key))));
    }

    private Long authorNumericId(CatalogKind kind, long id) {
        String sql = switch (kind) {
            case RESTAURANT -> "SELECT account_id FROM public.restaurant WHERE id=?";
            case FOOD -> "SELECT account_id FROM public.foods WHERE id=?";
            case CUSTOM_TAG -> "SELECT owner_id FROM public.food_tags WHERE id=? AND tag_type='custom'";
            default -> null;
        };
        return sql == null ? null : jdbc.query(sql, (rs, row) -> rs.getLong(1), id).stream().findFirst().orElse(null);
    }

    private Long localUserId(UUID authorGid) { return store.numericId(EntityType.USERS, authorGid); }

    private UUID parentGid(CatalogKind kind, Map<String, Object> payload, Long parentId) {
        if (kind == CatalogKind.FOOD && payload.get("restaurantGid") != null) return UUID.fromString(String.valueOf(payload.get("restaurantGid")));
        if (kind == CatalogKind.RESTAURANT) {
            Object hub = payload.get("hubGid");
            return hub == null ? null : UUID.fromString(String.valueOf(hub));
        }
        if (kind == CatalogKind.FOOD && parentId != null) return store.globalId(EntityType.RESTAURANT, parentId);
        return null;
    }

    private EntityType entityType(CatalogKind kind) {
        return switch (kind) {
            case HUB -> EntityType.HUB;
            case RESTAURANT -> EntityType.RESTAURANT;
            case FOOD -> EntityType.FOODS;
            case SYSTEM_TAG, CUSTOM_TAG -> EntityType.FOOD_TAGS;
            case ACTIVITY -> EntityType.ACTIVITIES;
        };
    }

    private String authoritySite(CatalogKind kind) {
        return switch (kind) {
            case HUB, SYSTEM_TAG, ACTIVITY -> multiWriterEnabled()?you.v50to.eatwhat.cluster.ClusterRouting.authority(new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc),site,properties.getPeerSiteId()):site.getId();
            case RESTAURANT, FOOD -> you.v50to.eatwhat.cluster.ClusterRouting.authority(new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc),site,properties.getPeerSiteId());
            case CUSTOM_TAG -> you.v50to.eatwhat.cluster.ClusterRouting.authority(new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc),site,properties.getPeerSiteId());
        };
    }

    private ObjectState readState(CatalogStore.Identity identity) {
        Map<String, Object> raw = identity.causalState() == null ? Map.of() : identity.causalState();
        Map<String, Long> seen = new TreeMap<>();
        Object rawSeen = raw.get("seen");
        if (rawSeen instanceof Map<?, ?> map) map.forEach((siteId, count) -> {
            if (siteId instanceof String s && count instanceof Number n) seen.put(s, n.longValue());
        });
        List<CatalogEvents.Head> heads = new ArrayList<>();
        Object rawHeads = raw.get("heads");
        if (rawHeads instanceof List<?> list) for (Object value : list) {
            if (value instanceof Map<?, ?> map) {
                heads.add(JSON.readValue(JSON.writeValueAsString(map), CatalogEvents.Head.class));
            }
        }
        Map<String, Object> payload = store.readPublic(identity.kind(), identity.numericId());
        return new ObjectState(identity, Map.copyOf(seen), List.copyOf(heads), payload == null ? Map.of() : payload);
    }

    private record ObjectState(CatalogStore.Identity identity, Map<String, Long> seen,
                               List<CatalogEvents.Head> heads, Map<String, Object> payload) { }
    private record HeadConflictSeed(CatalogKind kind, UUID objectGid, long numericId) { }
    private record HeadConflictState(long revision, String status, List<CatalogEvents.Head> candidates) { }
    private static final class HeadResolutionAbort extends RuntimeException {
        private final Status status;
        private HeadResolutionAbort(Status status) { this.status = status; }
    }

    private static boolean observedBy(Map<String, Long> context, String siteId, long counter) {
        return context.getOrDefault(siteId, 0L) >= counter;
    }

    private static Map<String, Object> naturalFields(CatalogKind kind, Map<String, Object> payload) {
        Map<String, Object> values = new TreeMap<>();
        switch (kind) {
            case RESTAURANT -> values.put("poi", payload.get("poi"));
            case FOOD -> { values.put("name", payload.get("name")); values.put("restaurantGid", payload.get("restaurantGid")); }
            case SYSTEM_TAG -> values.put("normalizedName", payload.get("normalizedName"));
            case CUSTOM_TAG -> { values.put("normalizedName", payload.get("normalizedName")); values.put("ownerGid", payload.get("ownerGid")); }
            case ACTIVITY -> values.put("code", payload.get("code"));
            case HUB -> { }
        }
        return values;
    }

    private static String toJson(Object value) { return JSON.writeValueAsString(value); }

    private static Map<String, Object> toMap(Object value) {
        try {
            return JSON.readValue(toJson(value), JSON.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("CATALOG_JSON_ENCODING_FAILED", failure);
        }
    }

    private Long parentNumericId(CatalogStore.Identity identity) {
        if (identity.parentGid() == null) return null;
        if (identity.kind() == CatalogKind.FOOD) return store.numericId(EntityType.RESTAURANT, identity.parentGid());
        if (identity.kind() == CatalogKind.RESTAURANT) return store.numericId(EntityType.HUB, identity.parentGid());
        return null;
    }

    private void requireManagementAuthority(CatalogStore.Identity identity) {
        if(multiWriterEnabled()) return;
        if ((identity.kind() == CatalogKind.HUB || identity.kind() == CatalogKind.SYSTEM_TAG
                || identity.kind() == CatalogKind.ACTIVITY)
                && !site.getId().equals(identity.authoritySite())) {
            throw new IllegalStateException("CATALOG_MANAGEMENT_AUTHORITY_REQUIRED");
        }
        if ((identity.kind() == CatalogKind.RESTAURANT || identity.kind() == CatalogKind.FOOD)
                && site.getMode() == SiteProperties.Mode.EXTERNAL) {
            throw new IllegalStateException("CATALOG_MANAGEMENT_AUTHORITY_REQUIRED");
        }
    }

    private void requireCreateAuthority(CatalogKind kind) {
        if(multiWriterEnabled()) return;
        if ((kind == CatalogKind.HUB || kind == CatalogKind.SYSTEM_TAG || kind == CatalogKind.ACTIVITY)
                && site.getMode() != SiteProperties.Mode.CAMPUS) {
            throw new IllegalStateException("CATALOG_MANAGEMENT_AUTHORITY_REQUIRED");
        }
    }

    private void retireNaturalKey(CatalogStore.Identity identity, Map<String, Object> previousPayload) {
        CatalogPayload.NaturalKey previous = CatalogPayload.naturalKey(identity.kind(), previousPayload,
                identity.authorGid() == null ? null : identity.authorGid().toString(),
                identity.parentGid() == null ? null : identity.parentGid().toString());
        if (previous != null) {
            jdbc.update("UPDATE eatwhat_sync.catalog_candidate SET status='SUPERSEDED',updated_at=clock_timestamp() WHERE kind=? AND key_hash=? AND object_gid=? AND status<>'REJECTED'",
                    identity.kind().name(), previous.hash(), identity.gid());
        }
    }

    private ImportedHub applyImportedHub(UUID batchId, CatalogHubImportRequest.HubItem item, long actorUserId) {
        String sourceKey = jdbc.queryForObject("SELECT source_key FROM eatwhat_sync.catalog_import_batch WHERE batch_id=? FOR UPDATE",
                String.class, batchId);
        List<Long> known = jdbc.query("SELECT numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key=? AND source_id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), sourceKey, item.sourceId());
        long id;
        boolean inserted = known.isEmpty();
        if (inserted) {
            beginCatalogBusinessWrite(CatalogKind.HUB, null, actorUserId, null, null, "CREATE");
            id = jdbc.queryForObject("""
                    INSERT INTO public.hub(name,center,gcj_lng,gcj_lat,boundary)
                    VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?,
                      CASE WHEN ? IS NULL THEN NULL ELSE ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?),4326))::geometry(MultiPolygon,4326) END)
                    RETURNING id
                    """, Long.class, item.name(), item.centerWgs().lng(), item.centerWgs().lat(),
                    item.centerGcj().lng(), item.centerGcj().lat(), item.boundaryGeoJson(), item.boundaryGeoJson());
            id = Objects.requireNonNull(id);
            UUID gid = store.globalId(EntityType.HUB, id);
            CatalogStore.Identity identity = localIdentity(CatalogKind.HUB, id, null, null, 0, false, false);
            store.insertObject(identity);
            Map<String, Object> payload = store.readPublic(CatalogKind.HUB, id);
            CatalogEvents.Action action = nextAction(identity, CatalogOperation.CREATE, payload, null);
            appendAndAdvance(action, identity);
            jdbc.update("""
                    INSERT INTO eatwhat_sync.catalog_hub_source(source_key,source_id,object_gid,numeric_id,content_hash)
                    VALUES (?,?,?,?,?)
                    """, sourceKey, item.sourceId(), gid, id, EventCodec.sha256(EventCodec.canonicalJson(toJson(item.canonicalPayload()))));
            store.markManaged(CatalogKind.HUB);
        } else {
            id = known.getFirst();
            CatalogStore.Identity identity = ensureTracked(CatalogKind.HUB, id, true);
            if (identity == null || identity.tombstone() || identity.rejected()) {
                throw new IllegalStateException("CATALOG_HUB_SOURCE_TERMINAL");
            }
            if(multiWriterEnabled() && (!multiwriter.editable(CatalogKind.HUB,identity.gid()) || readState(identity).heads().size()>1 && multiwriter.decisionHeads(CatalogKind.HUB,identity.gid()).isEmpty()))throw new IllegalArgumentException("IMPORT_OBJECT_CONFLICT");
            store.lockPublicRow(CatalogKind.HUB, id);
            beginCatalogBusinessWrite(CatalogKind.HUB, id, actorUserId, null, null, "UPDATE");
            jdbc.update("""
                    UPDATE public.hub SET name=?,center=ST_SetSRID(ST_MakePoint(?,?),4326),gcj_lng=?,gcj_lat=?,
                      boundary=CASE WHEN ? IS NULL THEN NULL ELSE ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?),4326))::geometry(MultiPolygon,4326) END
                    WHERE id=?
                    """, item.name(), item.centerWgs().lng(), item.centerWgs().lat(), item.centerGcj().lng(),
                    item.centerGcj().lat(), item.boundaryGeoJson(), item.boundaryGeoJson(), id);
            CatalogStore.Identity updated = new CatalogStore.Identity(identity.kind(), identity.gid(), identity.numericId(),
                    identity.originSite(), authoritySite(CatalogKind.HUB), null, null, identity.revision(), false, false, identity.causalState());
            CatalogEvents.Action action = nextAction(updated, CatalogOperation.UPDATE, store.readPublic(CatalogKind.HUB, id), null);
            appendAndAdvance(action, updated);
            jdbc.update("UPDATE eatwhat_sync.catalog_hub_source SET content_hash=?,updated_at=clock_timestamp() WHERE source_key=? AND source_id=?",
                    EventCodec.sha256(EventCodec.canonicalJson(toJson(item.canonicalPayload()))), sourceKey, item.sourceId());
        }
        int assignments = item.rebindCoveredRestaurants()
                ? rebindHub(id, actorUserId).assignments().size() : 0;
        return new ImportedHub(inserted, assignments);
    }
}
