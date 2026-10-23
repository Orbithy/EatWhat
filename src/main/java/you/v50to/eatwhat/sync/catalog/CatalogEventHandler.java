package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.EventHandler;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Merges catalog actions and state bridges in the Inbox transaction. */
public final class CatalogEventHandler implements EventHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String eventType;
    private CatalogMultiwriterSupport multiwriter;
    void enableMultiwriterReader(CatalogMultiwriterSupport value) { multiwriter=Objects.requireNonNull(value); }
    private final JdbcTemplate jdbc;
    private final CatalogStore store;
    private final CatalogProjection projection;
    private final ConsentStore consent;
    private final Set<String> allowedSites;
    private final java.util.function.Supplier<String> campusSiteId;

    public CatalogEventHandler(String eventType, JdbcTemplate jdbc, CatalogStore store,
                               CatalogProjection projection, ConsentStore consent, Set<String> allowedSites) {
        this(eventType, jdbc, store, projection, consent, allowedSites, null);
    }

    public CatalogEventHandler(String eventType, JdbcTemplate jdbc, CatalogStore store,
                               CatalogProjection projection, ConsentStore consent, Set<String> allowedSites,
                               String campusSiteId) {
        this(eventType,jdbc,store,projection,consent,()->campusSiteId,allowedSites);
    }
    public CatalogEventHandler(String eventType, JdbcTemplate jdbc, CatalogStore store,
                               CatalogProjection projection, ConsentStore consent,java.util.function.Supplier<String> campusSiteId,
                               Set<String> allowedSites) {
        if (!CatalogEvents.ACTION.equals(eventType) && !CatalogEvents.BRIDGE.equals(eventType)) {
            throw new IllegalArgumentException("Unsupported catalog event type");
        }
        this.eventType = eventType;
        this.jdbc = Objects.requireNonNull(jdbc);
        this.store = Objects.requireNonNull(store);
        this.projection = Objects.requireNonNull(projection);
        this.consent = Objects.requireNonNull(consent);
        this.allowedSites = you.v50to.eatwhat.cluster.ClusterMembershipView.freezeOrLive(allowedSites);
        this.campusSiteId = Objects.requireNonNull(campusSiteId);
    }

    void validateEnvelope(SyncEvent event) { CatalogEvents.validateEvent(event, event.originSite(), allowedSites,multiwriter!=null); }

    @Override public String aggregateType() { return CatalogEvents.AGGREGATE; }
    @Override public String eventType() { return eventType; }
    @Override public int schemaVersion() { return CatalogEvents.SCHEMA_VERSION; }

    @Override
    public boolean dependenciesSatisfied(SyncEvent event) {
        CatalogEvents.validateEvent(event, event.originSite(), allowedSites,multiwriter!=null);
        if(multiwriter==null) CatalogEvents.validateAuthoritySite(event, campusSiteId.get());
        List<CatalogEvents.ConsentEpoch> epochs = eventEpochs(event);
        if (!epochCoverageSatisfied(event)) return false;
        List<Long> localCascadeUsers = localCascadeAuthorUsers(event);
        lockSubjects(epochs, localCascadeUsers);
        if (!epochCoverageSatisfied(event)) return false;
        if (stale(epochs)) return true;
        if (!epochsReady(epochs,authenticatedRemoval(event))) return false;

        CatalogKind kind;
        UUID gid;
        UUID parent;
        boolean deleting;
        if (CatalogEvents.ACTION.equals(eventType)) {
            CatalogEvents.Action action = CatalogEvents.parseAction(event.payloadJson());
            kind = action.kind();
            gid = action.objectGid();
            parent = action.parentGid();
            deleting = action.operation() == CatalogOperation.DELETE;
            CatalogStore.Identity current = store.findObject(kind, gid);
            if (current == null && action.operation() != CatalogOperation.CREATE
                    && isLegacyRevZeroAction(action, false) == null) return false;
            if (current == null && !action.context().isEmpty()) return false;
            if (current != null && !contextCovered(current.causalState(), action.context())) return false;
            if (!proofsReady(action.resolutionProof())) return false;
        } else {
            CatalogEvents.StateBridge bridge = CatalogEvents.parseBridge(event.payloadJson());
            kind = bridge.kind();
            gid = bridge.objectGid();
            parent = bridge.parentGid();
            deleting = bridge.heads().stream().anyMatch(CatalogEvents.Head::tombstone);
            for (CatalogEvents.Head head : bridge.heads()) {
                if (!proofsReady(head.resolutionProof())) return false;
                if(multiwriter!=null && !head.tombstone() && !parentStateReady(kind,head.parentGid()))return false;
            }
        }
        if (deleting) return true;
        return parentStateReady(kind, parent);
    }

    @Override
    public void apply(SyncEvent event) {
        CatalogEvents.validateEvent(event, event.originSite(), allowedSites,multiwriter!=null);
        if(multiwriter==null) CatalogEvents.validateAuthoritySite(event, campusSiteId.get());
        List<CatalogEvents.ConsentEpoch> epochs = eventEpochs(event);
        if (!epochCoverageSatisfied(event)) throw new IllegalStateException("CATALOG_CONSENT_SUBJECTS_UNAVAILABLE");
        List<Long> localCascadeUsers = localCascadeAuthorUsers(event);
        lockSubjects(epochs, localCascadeUsers);
        if (!epochCoverageSatisfied(event)) throw new IllegalStateException("CATALOG_CONSENT_SUBJECTS_CHANGED");
        if (stale(epochs)) return;
        if (!epochsReady(epochs,authenticatedRemoval(event))) throw new IllegalStateException("CATALOG_CONSENT_DEPENDENCY_CHANGED");

        // Consent subjects are always locked before the shared catalog lock. All catalog writers
        // use the same domain lock before object/public rows, so withdrawal cannot deadlock here.
        store.lockDomain();
        List<Long> cascadeRestaurantIds = cascadeRestaurantIds(event);
        if (!cascadeRestaurantIds.isEmpty()) {
            for (Long restaurantId : cascadeRestaurantIds) store.lockPublicRow(CatalogKind.RESTAURANT, restaurantId);
            if (!localCascadeUsers.equals(localCascadeAuthorUsers(event))) {
                throw new IllegalStateException("CATALOG_CASCADE_SUBJECTS_CHANGED");
            }
            for (Long restaurantId : cascadeRestaurantIds) {
                seedLegacyRestaurantChildren(restaurantId, cascadeAuthority(event, restaurantId));
            }
        }
        if (CatalogEvents.ACTION.equals(eventType)) {
            CatalogEvents.Action action = CatalogEvents.parseAction(event.payloadJson());
            if (action.operation() != CatalogOperation.CREATE && store.findObject(action.kind(), action.objectGid()) == null) {
                CatalogStore.Identity legacy = isLegacyRevZeroAction(action, true);
                if (legacy == null) throw new IllegalStateException("CATALOG_PREDECESSOR_DISAPPEARED");
                seedLegacyParent(action);
                store.insertObject(legacy);
            }
            applyAction(action);
        } else {
            CatalogEvents.StateBridge bridge = CatalogEvents.parseBridge(event.payloadJson());
            if (bridge.kind() == CatalogKind.FOOD && bridge.parentGid() != null
                    && store.findObject(CatalogKind.RESTAURANT, bridge.parentGid()) == null
                    && publicParentExists(bridge.parentGid())) {
                CatalogEvents.Head parentEvidence = bridge.heads().stream().findFirst().orElse(null);
                if (parentEvidence == null) throw new IllegalStateException("CATALOG_PARENT_IDENTITY_UNAVAILABLE");
                seedLegacyParent(asAction(bridge, parentEvidence));
            }
            applyBridge(bridge);
        }
    }

    /** Restore immutable decision candidates without projecting a partial history. */
    void stageDecisionCandidates(List<CatalogEvents.Action> candidates) {
        store.lockDomain();
        for(var action:candidates) {
            CatalogStore.Identity current=store.lockObject(action.kind(),action.objectGid());
            if(current==null){current=new CatalogStore.Identity(action.kind(),action.objectGid(),action.numericId(),action.identityOrigin(),action.authoritySite(),action.authorGid(),action.parentGid(),0,false,false,emptyState());store.insertObject(current);}
            else verifyIdentity(current,action.kind(),action.objectGid(),action.numericId(),action.identityOrigin(),action.authorGid());
            if(!hasRevision(action.operationId()))store.appendRevision(action,action.dotSite());
            var merged=merge(current.causalState(),List.of(head(action)));var effective=heads(merged);
            if(new java.util.HashSet<>(heads(current.causalState())).equals(new java.util.HashSet<>(effective)))continue;
            store.updateObject(new CatalogStore.Identity(current.kind(),current.gid(),current.numericId(),current.originSite(),current.authoritySite(),current.authorGid(),current.parentGid(),Math.max(current.revision(),action.revision()),current.tombstone(),current.rejected(),merged));
            jdbc.update("INSERT INTO eatwhat_sync.catalog_head_conflict(conflict_id,kind,object_gid,revision,status,candidates) VALUES(?,?,?,1,'OPEN',?::jsonb) ON CONFLICT(conflict_id) DO UPDATE SET candidates=EXCLUDED.candidates,updated_at=clock_timestamp()",CatalogStore.stableHeadConflictId(current.kind(),current.gid()),current.kind().name(),current.gid(),JSON.writeValueAsString(effective));
            if(effective.size()>1)multiwriter.projectionState(current.kind(),current.gid(),"CONFLICT",List.of());
            if(action.operation()!=CatalogOperation.DELETE)registerCandidate(action);
        }
    }

    boolean currentReviewConsent(SyncEvent event) {
        var epochs=eventEpochs(event);
        lockSubjects(epochs,localCascadeAuthorUsers(event));
        return epochCoverageSatisfied(event) && !stale(epochs) && epochsReady(epochs,authenticatedRemoval(event));
    }

    /** Record disputed authority without executing the public mutation or a delete cascade. */
    void stageAuthorizationReview(SyncEvent event) {
        if(multiwriter==null || !dependenciesSatisfied(event))throw new IllegalStateException("CATALOG_REVIEW_DEPENDENCY_CHANGED");
        if(!currentReviewConsent(event))return;
        store.lockDomain();
        List<CatalogEvents.Action> actions;
        if(CatalogEvents.ACTION.equals(event.eventType()))actions=List.of(CatalogEvents.parseAction(event.payloadJson()));
        else {var bridge=CatalogEvents.parseBridge(event.payloadJson());actions=bridge.heads().stream().map(h->asAction(bridge,h)).toList();}
        boolean changed=false;CatalogStore.Identity current=null;
        for(var action:actions) {
            current=store.lockObject(action.kind(),action.objectGid());
            if(current==null) {
                current=new CatalogStore.Identity(action.kind(),action.objectGid(),action.numericId(),action.identityOrigin(),action.authoritySite(),action.authorGid(),action.parentGid(),0,false,false,emptyState());
                store.insertObject(current);
            } else verifyIdentity(current,action.kind(),action.objectGid(),action.numericId(),action.identityOrigin(),action.authorGid());
            if(!hasRevision(action.operationId()))store.appendRevision(action,action.dotSite());
            var retained=new ArrayList<>(heads(current.causalState()));var next=head(action);
            if(retained.stream().anyMatch(h->!h.operationId().equals(next.operationId()) && observes(h.context(),next)))continue;
            boolean localChange=false;
            if(retained.stream().noneMatch(h->h.operationId().equals(next.operationId()))) {
                if(retained.stream().anyMatch(h->h.dotSite().equals(next.dotSite())&&h.dotCounter()==next.dotCounter()))throw new IllegalArgumentException("CATALOG_DOT_IDENTITY_CONFLICT");
                retained.add(next);localChange=true;
            }
            // A locally applied operation may already have consumed its predecessors before the remote revocation arrived.
            var predecessors=jdbc.queryForList("SELECT operation_id FROM eatwhat_sync.catalog_revision WHERE kind=? AND object_gid=?",UUID.class,action.kind().name(),action.objectGid()).stream().map(store::readRevision).map(CatalogStore.ImmutableRevision::action)
                    .filter(a->action.context().getOrDefault(a.dotSite(),0L)>=a.dotCounter()).map(this::head).toList();
            for(var predecessor:heads(merge(emptyState(),predecessors)))if(retained.stream().noneMatch(h->h.operationId().equals(predecessor.operationId()) || !h.operationId().equals(next.operationId()) && observes(h.context(),predecessor))){retained.add(predecessor);localChange=true;}
            if(!localChange) {
                boolean decided=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_decision_state s JOIN eatwhat_sync.catalog_decision_operation d ON jsonb_exists(s.heads,d.operation_id::text) WHERE s.kind=? AND s.object_gid=? AND s.status IN ('RESOLVED','REJECTED') AND jsonb_exists(d.payload->'reviewedOperations',?))",Boolean.class,action.kind().name(),action.objectGid(),action.operationId().toString()));
                if(!decided)changed=true;
                continue;
            }
            if(retained.size()>32)throw new IllegalStateException("CATALOG_REVIEW_HEAD_LIMIT");
            var observed=seen(current.causalState());next.context().forEach((k,v)->observed.merge(k,v,Math::max));observed.merge(next.dotSite(),next.dotCounter(),Math::max);
            current=new CatalogStore.Identity(current.kind(),current.gid(),current.numericId(),current.originSite(),current.authoritySite(),current.authorGid(),current.parentGid(),Math.max(current.revision(),action.revision()),current.tombstone(),current.rejected(),Map.of("seen",observed,"heads",retained.stream().map(this::headMap).toList()));
            store.updateObject(current);changed=true;
        }
        if(changed && current!=null) {
            String candidates=EventCodec.canonicalJson(JSON.writeValueAsString(heads(current.causalState())));
            jdbc.update("INSERT INTO eatwhat_sync.catalog_head_conflict(conflict_id,kind,object_gid,revision,status,candidates) VALUES(?,?,?,1,'OPEN',?::jsonb) ON CONFLICT(conflict_id) DO UPDATE SET revision=catalog_head_conflict.revision+1,status='OPEN',candidates=EXCLUDED.candidates,updated_at=clock_timestamp()",CatalogStore.stableHeadConflictId(current.kind(),current.gid()),current.kind().name(),current.gid(),candidates);
            multiwriter.projectionState(current.kind(),current.gid(),"AUTH_REVIEW",List.of());
        }
    }

    private void applyAction(CatalogEvents.Action action) {
        CatalogStore.Identity current = store.lockObject(action.kind(), action.objectGid());
        if (current == null) {
            if (action.operation() != CatalogOperation.CREATE) {
                throw new IllegalStateException("CATALOG_PREDECESSOR_DISAPPEARED");
            }
            current = new CatalogStore.Identity(action.kind(), action.objectGid(), action.numericId(),
                    action.identityOrigin(), action.authoritySite(), action.authorGid(), action.parentGid(),
                    0, false, false, emptyState());
            store.insertObject(current);
        } else {
            verifyIdentity(current, action.kind(), action.objectGid(), action.numericId(), action.identityOrigin(),
                    action.authorGid());
            if (hasRevision(action.operationId())) return;
        }

        Map<String, Object> state = merge(current.causalState(), List.of(head(action)));
        List<CatalogEvents.Head> effectiveHeads = heads(state);
        boolean actionIsEffective = effectiveHeads.stream().anyMatch(h -> h.operationId().equals(action.operationId()));
        boolean parentRejected = action.operation() != CatalogOperation.DELETE
                && parentTerminal(action.kind(), action.parentGid());
        CatalogEvents.Head deleteHead = terminalDelete(current, effectiveHeads);
        CatalogEvents.Head soleHead = effectiveHeads.size() == 1 ? effectiveHeads.getFirst() : null;
        CatalogStore.Identity next = new CatalogStore.Identity(action.kind(), action.objectGid(),
                action.numericId(), current.originSite(),
                soleHead == null ? current.authoritySite() : soleHead.authoritySite(),
                soleHead == null ? current.authorGid() : soleHead.authorGid(),
                soleHead == null ? current.parentGid() : soleHead.parentGid(),
                Math.max(current.revision(), action.revision()),
                current.tombstone() || (!current.rejected() && deleteHead != null),
                current.rejected() || multiwriter==null && parentRejected, state);

        store.appendRevision(action, action.dotSite());
        store.updateObject(next);
        if (effectiveHeads.size() > 1) observeObjectHeadConflict(action.kind(), action.objectGid(), effectiveHeads);
        if (action.operation() == CatalogOperation.HUB_ASSIGNMENT) {
            jdbc.update("INSERT INTO eatwhat_sync.catalog_assignment(operation_id,restaurant_gid,expected_revision,hub_gid,source_site) VALUES (?,?,?,?,?)",
                    action.operationId(), action.objectGid(), action.expectedRevision(), action.parentGid(), action.dotSite());
        }
        if (parentRejected) {
            registerCandidate(action);
            if(multiwriter==null)rejectCandidate(action.kind(), action.objectGid());
            return;
        }

        // Rejected candidates retain their evidence but are never made visible by a later replay.
        if (current.rejected()) return;

        applyResolutionProof(action.resolutionProof(), action.kind(), action.dotSite(), action);
        if (action.resolutionProof() != null && action.resolutionProof().decision() == CatalogDecision.REJECT) return;
        if (store.findObject(action.kind(), action.objectGid()).rejected()) return;
        boolean naturalConflict = actionIsEffective && registerCandidate(action);
        retireCandidatesOutsideHeads(action.kind(), action.objectGid(), effectiveHeads);
        if (next.tombstone()) {
            CatalogEvents.Head tombstone = deleteHead;
            if (tombstone != null) applyProjection(asAction(action.kind(), action.objectGid(), tombstone));
            return;
        }
        if (!naturalConflict && !parentRejected && !effectiveHeads.isEmpty()) {
            CatalogEvents.Head winner = effectiveHeads.getFirst();
            if (effectiveHeads.size() > 1) return;
            CatalogProjection.ProjectionResult result = projection.apply(asAction(action.kind(), action.objectGid(), winner));
            if (result == CatalogProjection.ProjectionResult.WAIT_PARENT) {
                throw new IllegalStateException("CATALOG_PARENT_CHANGED_DURING_APPLY");
            }
        }
    }

    private void applyBridge(CatalogEvents.StateBridge bridge) {
        CatalogStore.Identity current = store.lockObject(bridge.kind(), bridge.objectGid());
        if (current != null) {
            verifyIdentity(current, bridge.kind(), bridge.objectGid(), bridge.numericId(),
                    bridge.identityOrigin(), null);
        } else {
            CatalogEvents.Head first = bridge.heads().getFirst();
            current = new CatalogStore.Identity(bridge.kind(), bridge.objectGid(), bridge.numericId(),
                    bridge.identityOrigin(), bridge.authoritySite(), first.authorGid(), bridge.parentGid(),
                    0, false, false, emptyState());
            store.insertObject(current);
        }

        for (CatalogEvents.Head head : bridge.heads()) {
            if (!hasRevision(head.operationId())) store.appendRevision(asAction(bridge, head), head.sourceSite());
        }
        Map<String, Object> merged = merge(current.causalState(), bridge.heads());
        List<CatalogEvents.Head> effectiveHeads = heads(merged);
        CatalogEvents.Head winner = effectiveHeads.size() == 1 ? effectiveHeads.getFirst() : null;
        boolean terminalParent = winner != null && !winner.tombstone()
                && parentTerminal(bridge.kind(), winner.parentGid());
        CatalogEvents.Head deleteHead = terminalDelete(current, effectiveHeads);
        boolean tombstone = current.tombstone() || (!current.rejected() && deleteHead != null);
        CatalogStore.Identity next = new CatalogStore.Identity(bridge.kind(), bridge.objectGid(), bridge.numericId(),
                bridge.identityOrigin(), bridge.authoritySite(), winner == null ? current.authorGid() : winner.authorGid(),
                winner == null ? bridge.parentGid() : winner.parentGid(),
                Math.max(current.revision(), bridge.heads().stream().mapToLong(CatalogEvents.Head::revision).max().orElse(0)),
                tombstone, current.rejected() || multiwriter==null && terminalParent, merged);
        store.updateObject(next);
        if (effectiveHeads.size() > 1) observeObjectHeadConflict(bridge.kind(), bridge.objectGid(), effectiveHeads);

        if (current.rejected()) return;

        boolean conflict = false;
        Set<UUID> effectiveIds = effectiveHeads.stream().map(CatalogEvents.Head::operationId)
                .collect(java.util.stream.Collectors.toSet());
        for (CatalogEvents.Head head : effectiveHeads) {
            if (!head.tombstone() && head.resolutionProof() == null && effectiveIds.contains(head.operationId())) {
                conflict |= registerCandidate(asAction(bridge, head));
            }
        }
        for (CatalogEvents.Head head : bridge.heads()) {
            applyResolutionProof(head.resolutionProof(), bridge.kind(), head.sourceSite(), asAction(bridge, head));
            if (head.resolutionProof() != null
                    && head.resolutionProof().decision() == CatalogDecision.KEEP
                    && effectiveIds.contains(head.operationId()) && !head.tombstone()) {
                conflict |= registerCandidate(asAction(bridge, head));
            }
        }
        CatalogStore.Identity afterProof = store.findObject(bridge.kind(), bridge.objectGid());
        if (afterProof != null && afterProof.rejected()) return;
        retireCandidatesOutsideHeads(bridge.kind(), bridge.objectGid(), effectiveHeads);
        if (tombstone) {
            if (deleteHead != null) applyProjection(asAction(bridge, deleteHead));
            return;
        }
        if (terminalParent) {
            if (winner != null && multiwriter==null) rejectCandidate(bridge.kind(), bridge.objectGid());
            return;
        }
        if (winner == null || conflict) return;
        CatalogProjection.ProjectionResult result = projection.apply(asAction(bridge, winner));
        if (result == CatalogProjection.ProjectionResult.WAIT_PARENT) {
            throw new IllegalStateException("CATALOG_PARENT_CHANGED_DURING_BRIDGE");
        }
    }

    private void applyResolutionProof(CatalogEvents.ResolutionProof proof, CatalogKind eventKind, String sourceSite,
                                     CatalogEvents.Action resolutionAction) {
        if (proof == null) return;
        if (campusSiteId.get() == null || !campusSiteId.get().equals(sourceSite)) {
            throw new IllegalStateException("CATALOG_RESOLUTION_NOT_FROM_CAMPUS");
        }
        List<RevisionRef> references = new ArrayList<>();
        List<CatalogEvents.Action> evidence = new ArrayList<>();
        for (UUID operationId : proof.expectedHeads()) {
            CatalogStore.ImmutableRevision revision = store.readRevision(operationId);
            if (revision == null) throw new IllegalStateException("CATALOG_RESOLUTION_PREDECESSOR_MISSING");
            CatalogEvents.Action action = revision.action();
            if (action.kind() != eventKind || action.operation() == CatalogOperation.DELETE) {
                throw new IllegalStateException("CATALOG_RESOLUTION_KIND_MISMATCH");
            }
            CatalogPayload.NaturalKey key = CatalogPayload.naturalKey(action.kind(), action.canonicalPayload(),
                    action.authorGid() == null ? null : action.authorGid().toString(),
                    action.parentGid() == null ? null : action.parentGid().toString());
            if (key == null) throw new IllegalStateException("CATALOG_RESOLUTION_KEY_MISSING");
            references.add(new RevisionRef(action.kind(), action.objectGid(), action.numericId(), key.hash(),
                    action.revision(), action.contentHash()));
            evidence.add(action);
        }
        if (references.isEmpty()) throw new IllegalStateException("CATALOG_RESOLUTION_EMPTY");
        RevisionRef first = references.getFirst();
        boolean objectHeadResolution = eventKind == CatalogKind.CUSTOM_TAG
                && references.stream().allMatch(ref -> ref.kind() == eventKind && ref.gid().equals(first.gid()))
                && proof.conflictId().equals(CatalogStore.stableHeadConflictId(eventKind, first.gid()));
        if (objectHeadResolution) {
            applyObjectHeadResolutionProof(proof, references, evidence, sourceSite, resolutionAction);
            return;
        }
        if (!proof.conflictId().equals(CatalogStore.stableConflictId(first.kind(), first.keyHash()))
                || references.stream().anyMatch(ref -> ref.kind() != first.kind()
                || !ref.keyHash().equals(first.keyHash()))) {
            throw new IllegalStateException("CATALOG_RESOLUTION_KEY_MISMATCH");
        }
        RevisionRef winnerRef = null;
        CatalogEvents.Action winnerAction = null;
        if (proof.decision() == CatalogDecision.KEEP) {
            int winnerIndex = proof.expectedHeads().indexOf(proof.winnerOperationId());
            if (winnerIndex < 0) throw new IllegalStateException("CATALOG_RESOLUTION_WINNER_NOT_CANDIDATE");
            winnerRef = references.get(winnerIndex);
            winnerAction = evidence.get(winnerIndex);
        }
        CatalogStore.Identity resolutionObject = store.findObject(resolutionAction.kind(), resolutionAction.objectGid());
        boolean resolutionEffective = resolutionObject != null && heads(resolutionObject.causalState()).stream()
                .anyMatch(head -> head.operationId().equals(resolutionAction.operationId()));
        if (!resolutionEffective) {
            verifyHistoricalResolution(proof, references, winnerRef, sourceSite);
            return;
        }
        ConflictState conflict = jdbc.query("SELECT status,revision FROM eatwhat_sync.catalog_conflict WHERE conflict_id=? AND kind=? AND key_hash=? FOR UPDATE",
                (rs, row) -> new ConflictState(rs.getString("status"), rs.getLong("revision")),
                proof.conflictId(), first.kind().name(), first.keyHash()).stream().findFirst().orElse(null);
        if (conflict == null) throw new IllegalStateException("CATALOG_RESOLUTION_CONFLICT_MISSING");
        if (!"OPEN".equals(conflict.status())) {
            verifyTerminalResolution(proof, references, conflict.status(), conflict.revision(), winnerRef);
            return;
        }
        List<CatalogStore.CandidateRevision> active = store.activeCandidateRevisions(first.kind(), first.keyHash());
        List<CatalogStore.CandidateRevision> expected = references.stream()
                .map(ref -> new CatalogStore.CandidateRevision(ref.gid(),
                        proof.expectedHeads().get(references.indexOf(ref)), ref.revision(), ref.contentHash()))
                .sorted(java.util.Comparator.comparing(row -> row.gid().toString())).toList();
        if (!active.equals(expected)) throw new IllegalStateException("CATALOG_RESOLUTION_CANDIDATE_SET_CHANGED");

        for (RevisionRef ref : references) {
            boolean winner = winnerRef != null && winnerRef.gid().equals(ref.gid());
            if (!winner) rejectCandidate(ref.kind(), ref.gid());
            jdbc.update("UPDATE eatwhat_sync.catalog_candidate SET status=?,updated_at=clock_timestamp() WHERE kind=? AND key_hash=? AND object_gid=?",
                    winner ? "KEPT" : "REJECTED", ref.kind().name(), ref.keyHash(), ref.gid());
        }
        if (winnerRef != null) {
            CatalogStore.Identity identity = store.lockObject(winnerRef.kind(), winnerRef.gid());
            if (identity == null || identity.rejected() || identity.tombstone()) {
                throw new IllegalStateException("CATALOG_RESOLUTION_WINNER_UNAVAILABLE");
            }
            CatalogProjection.ProjectionResult projected = projection.applyWinner(identity,
                    winnerAction.canonicalPayload());
            if (projected != CatalogProjection.ProjectionResult.APPLIED) {
                throw new IllegalStateException("CATALOG_RESOLUTION_WINNER_PROJECTION_BLOCKED");
            }
        }
        jdbc.update("UPDATE eatwhat_sync.catalog_conflict SET status=?,revision=revision+1,updated_at=clock_timestamp() WHERE conflict_id=? AND status='OPEN'",
                proof.decision() == CatalogDecision.KEEP ? "RESOLVED" : "REJECTED", proof.conflictId());
        String proofJson = EventCodec.canonicalJson(JSON.writeValueAsString(proof));
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_resolution_receipt(
                  conflict_id,decision,winner_gid,source_site,proof,expected_candidates,proof_hash,conflict_revision)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)
                """, proof.conflictId(), proof.decision().name(), winnerRef == null ? null : winnerRef.gid(),
                sourceSite, proofJson, EventCodec.canonicalJson(JSON.writeValueAsString(resolutionCandidates(references))),
                EventCodec.sha256(proofJson), conflict.revision() + 1);
    }

    private void verifyTerminalResolution(CatalogEvents.ResolutionProof proof, List<RevisionRef> refs,
                                         String status, long currentRevision, RevisionRef winner) {
        String expectedStatus = proof.decision() == CatalogDecision.KEEP ? "RESOLVED" : "REJECTED";
        if (!expectedStatus.equals(status)) throw new IllegalStateException("CATALOG_RESOLUTION_ALREADY_DECIDED");
        List<ResolutionReceipt> receipts = jdbc.query("""
                SELECT decision,winner_gid,source_site,proof::text,expected_candidates::text,proof_hash,conflict_revision
                FROM eatwhat_sync.catalog_resolution_receipt WHERE conflict_id=? AND proof_hash=?
                  AND conflict_revision=?
                """, (rs, row) -> new ResolutionReceipt(rs.getString("decision"),
                rs.getObject("winner_gid", UUID.class), rs.getString("source_site"), rs.getString("proof"),
                JSON.readValue(rs.getString("expected_candidates"), JSON.getTypeFactory()
                        .constructCollectionType(List.class, JSON.getTypeFactory()
                                .constructMapType(Map.class, String.class, Object.class))),
                rs.getString("proof_hash").trim(), rs.getLong("conflict_revision")), proof.conflictId(),
                EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(proof))), currentRevision);
        if (!receipts.isEmpty()) {
            ResolutionReceipt receipt = receipts.getFirst();
            String canonicalProof = EventCodec.canonicalJson(JSON.writeValueAsString(proof));
            CatalogEvents.ResolutionProof savedProof = JSON.readValue(receipt.proof(), CatalogEvents.ResolutionProof.class);
            if (!receipt.decision().equals(proof.decision().name()) || !savedProof.equals(proof)
                    || !receipt.proofHash().equals(EventCodec.sha256(canonicalProof))
                    || receipt.conflictRevision() != currentRevision
                    || !campusSiteId.get().equals(receipt.sourceSite())
                    || !Objects.equals(receipt.winnerGid(), winner == null ? null : winner.gid())
                    || !matchesResolutionCandidates(receipt.expectedCandidates(), refs)) {
                throw new IllegalStateException("CATALOG_RESOLUTION_RECEIPT_MISMATCH");
            }
            return;
        }
        List<AuditResolution> audits = jdbc.query("""
                SELECT expected_candidates::text,winner_gid,conflict_revision FROM eatwhat_sync.catalog_conflict_audit
                WHERE conflict_id=? AND decision=? ORDER BY conflict_revision DESC LIMIT 1
                """, (rs, row) -> new AuditResolution(JSON.readValue(rs.getString("expected_candidates"), JSON.getTypeFactory()
                        .constructCollectionType(List.class, JSON.getTypeFactory()
                        .constructMapType(Map.class, String.class, Object.class))),
                        rs.getObject("winner_gid", UUID.class), rs.getLong("conflict_revision")),
                proof.conflictId(), proof.decision().name());
        if (audits.isEmpty() || audits.getFirst().expectedCandidates().size() != refs.size()) {
            throw new IllegalStateException("CATALOG_RESOLUTION_AUDIT_MISMATCH");
        }
        List<Map<String, Object>> audit = audits.getFirst().expectedCandidates();
        for (RevisionRef ref : refs) {
            boolean found = audit.stream().anyMatch(row -> ref.gid().toString().equals(row.get("gid"))
                    && ((Number) row.get("revision")).longValue() == ref.revision()
                    && ref.contentHash().equals(row.get("contentHash")));
            if (!found) throw new IllegalStateException("CATALOG_RESOLUTION_AUDIT_MISMATCH");
        }
        if (!Objects.equals(audits.getFirst().winnerGid(), winner == null ? null : winner.gid())
                || audits.getFirst().conflictRevision() + 1 != currentRevision) {
            throw new IllegalStateException("CATALOG_RESOLUTION_WINNER_MISMATCH");
        }
    }

    private void verifyHistoricalResolution(CatalogEvents.ResolutionProof proof, List<RevisionRef> refs,
                                            RevisionRef winner, String sourceSite) {
        String proofJson = EventCodec.canonicalJson(JSON.writeValueAsString(proof));
        String proofHash = EventCodec.sha256(proofJson);
        List<ResolutionReceipt> receipts = jdbc.query("""
                SELECT decision,winner_gid,source_site,proof::text,expected_candidates::text,proof_hash,conflict_revision
                FROM eatwhat_sync.catalog_resolution_receipt WHERE conflict_id=? AND proof_hash=?
                """, (rs, row) -> new ResolutionReceipt(rs.getString("decision"),
                rs.getObject("winner_gid", UUID.class), rs.getString("source_site"), rs.getString("proof"),
                JSON.readValue(rs.getString("expected_candidates"), JSON.getTypeFactory()
                        .constructCollectionType(List.class, JSON.getTypeFactory()
                                .constructMapType(Map.class, String.class, Object.class))),
                rs.getString("proof_hash").trim(), rs.getLong("conflict_revision")), proof.conflictId(), proofHash);
        if (receipts.isEmpty()) throw new IllegalStateException("CATALOG_RESOLUTION_RECEIPT_MISMATCH");
        ResolutionReceipt receipt = receipts.getFirst();
        CatalogEvents.ResolutionProof savedProof = JSON.readValue(receipt.proof(), CatalogEvents.ResolutionProof.class);
        if (!receipt.decision().equals(proof.decision().name()) || !savedProof.equals(proof)
                || !receipt.proofHash().equals(proofHash) || !campusSiteId.get().equals(sourceSite)
                || !campusSiteId.get().equals(receipt.sourceSite())
                || !Objects.equals(receipt.winnerGid(), winner == null ? null : winner.gid())
                || !matchesResolutionCandidates(receipt.expectedCandidates(), refs)) {
            throw new IllegalStateException("CATALOG_RESOLUTION_RECEIPT_MISMATCH");
        }
    }

    private void applyObjectHeadResolutionProof(CatalogEvents.ResolutionProof proof, List<RevisionRef> refs,
                                                List<CatalogEvents.Action> evidence, String sourceSite,
                                                CatalogEvents.Action resolutionAction) {
        if (refs.size() < 2 || refs.stream().anyMatch(ref -> ref.kind() != CatalogKind.CUSTOM_TAG
                || !ref.gid().equals(refs.getFirst().gid()))) {
            throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_SCOPE_MISMATCH");
        }
        UUID gid = refs.getFirst().gid();
        if (!proof.conflictId().equals(CatalogStore.stableHeadConflictId(CatalogKind.CUSTOM_TAG, gid))
                || resolutionAction == null || !resolutionAction.objectGid().equals(gid)
                || resolutionAction.kind() != CatalogKind.CUSTOM_TAG
                || resolutionAction.operation() == CatalogOperation.DELETE
                || !Objects.equals(resolutionAction.resolutionProof(), proof)) {
            throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_PROOF_INVALID");
        }
        int winnerIndex = proof.decision() == CatalogDecision.KEEP
                ? proof.expectedHeads().indexOf(proof.winnerOperationId()) : -1;
        if (proof.decision() == CatalogDecision.KEEP && winnerIndex < 0) {
            throw new IllegalStateException("CATALOG_RESOLUTION_WINNER_NOT_CANDIDATE");
        }
        if (proof.decision() == CatalogDecision.KEEP
                && !resolutionAction.canonicalPayload().equals(evidence.get(winnerIndex).canonicalPayload())) {
            throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_WINNER_PAYLOAD_MISMATCH");
        }
        CatalogStore.Identity currentObject = store.findObject(CatalogKind.CUSTOM_TAG, gid);
        boolean resolutionEffective = currentObject != null && heads(currentObject.causalState()).stream()
                .anyMatch(head -> head.operationId().equals(resolutionAction.operationId()));
        if (!resolutionEffective) {
            verifyHistoricalHeadResolution(proof, refs, sourceSite);
            return;
        }
        List<HeadConflictState> rows = jdbc.query("""
                SELECT status,revision,candidates::text FROM eatwhat_sync.catalog_head_conflict
                WHERE conflict_id=? AND kind='CUSTOM_TAG' AND object_gid=? FOR UPDATE
                """, (rs, row) -> new HeadConflictState(rs.getString("status"), rs.getLong("revision"),
                JSON.readValue(rs.getString("candidates"), JSON.getTypeFactory()
                        .constructCollectionType(List.class, CatalogEvents.Head.class))), proof.conflictId(), gid);
        if (rows.isEmpty()) throw new IllegalStateException("CATALOG_HEAD_CONFLICT_MISSING");
        HeadConflictState conflict = rows.getFirst();
        if (!"OPEN".equals(conflict.status())) {
            verifyTerminalHeadResolution(proof, refs, conflict);
            return;
        }
        if (!matchesHeadVersions(conflict.candidates(), refs, proof.expectedHeads())) {
            throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_CANDIDATE_SET_CHANGED");
        }

        if (proof.decision() == CatalogDecision.REJECT) {
            rejectCandidate(CatalogKind.CUSTOM_TAG, gid);
        } else {
            for (int i = 0; i < evidence.size(); i++) {
                CatalogEvents.Action candidate = evidence.get(i);
                CatalogPayload.NaturalKey key = CatalogPayload.naturalKey(candidate.kind(), candidate.canonicalPayload(),
                        candidate.authorGid() == null ? null : candidate.authorGid().toString(),
                        candidate.parentGid() == null ? null : candidate.parentGid().toString());
                if (key != null) jdbc.update("""
                        UPDATE eatwhat_sync.catalog_candidate SET status=?,updated_at=clock_timestamp()
                        WHERE kind='CUSTOM_TAG' AND key_hash=? AND object_gid=?
                        """, candidate.operationId().equals(proof.winnerOperationId()) ? "PENDING" : "SUPERSEDED",
                        key.hash(), gid);
            }
        }
        int changed = jdbc.update("""
                UPDATE eatwhat_sync.catalog_head_conflict SET status=?,revision=revision+1,
                  updated_at=clock_timestamp() WHERE conflict_id=? AND status='OPEN' AND revision=?
                """, proof.decision() == CatalogDecision.KEEP ? "RESOLVED" : "REJECTED",
                proof.conflictId(), conflict.revision());
        if (changed != 1) throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_CAS_LOST");
        String proofJson = EventCodec.canonicalJson(JSON.writeValueAsString(proof));
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_head_resolution_receipt(
                  conflict_id,decision,winner_operation_id,source_site,proof,expected_heads,proof_hash,conflict_revision)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)
                """, proof.conflictId(), proof.decision().name(), proof.winnerOperationId(), sourceSite,
                proofJson, EventCodec.canonicalJson(JSON.writeValueAsString(conflict.candidates())),
                EventCodec.sha256(proofJson), conflict.revision() + 1);
    }

    private void verifyTerminalHeadResolution(CatalogEvents.ResolutionProof proof, List<RevisionRef> refs,
                                              HeadConflictState conflict) {
        String expectedStatus = proof.decision() == CatalogDecision.KEEP ? "RESOLVED" : "REJECTED";
        if (!expectedStatus.equals(conflict.status())) {
            throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_ALREADY_DECIDED");
        }
        String proofHash = EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(proof)));
        List<HeadResolutionReceipt> receipts = jdbc.query("""
                SELECT decision,winner_operation_id,source_site,proof::text,expected_heads::text,proof_hash,conflict_revision
                FROM eatwhat_sync.catalog_head_resolution_receipt
                WHERE conflict_id=? AND proof_hash=? AND conflict_revision=?
                """, (rs, row) -> new HeadResolutionReceipt(rs.getString("decision"),
                rs.getObject("winner_operation_id", UUID.class),
                rs.getString("source_site"),
                JSON.readValue(rs.getString("proof"), CatalogEvents.ResolutionProof.class),
                JSON.readValue(rs.getString("expected_heads"), JSON.getTypeFactory()
                        .constructCollectionType(List.class, CatalogEvents.Head.class)),
                rs.getString("proof_hash").trim(), rs.getLong("conflict_revision")),
                proof.conflictId(), proofHash, conflict.revision());
        if (receipts.isEmpty()) throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_RECEIPT_MISMATCH");
        HeadResolutionReceipt receipt = receipts.getFirst();
        if (!receipt.decision().equals(proof.decision().name()) || !receipt.proof().equals(proof)
                || !Objects.equals(receipt.winnerOperationId(), proof.winnerOperationId())
                || !receipt.proofHash().equals(proofHash) || receipt.conflictRevision() != conflict.revision()
                || !campusSiteId.get().equals(receipt.sourceSite())
                || !matchesHeadVersions(receipt.expectedHeads(), refs, proof.expectedHeads())) {
            throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_RECEIPT_MISMATCH");
        }
    }

    private void verifyHistoricalHeadResolution(CatalogEvents.ResolutionProof proof, List<RevisionRef> refs,
                                                String sourceSite) {
        String proofJson = EventCodec.canonicalJson(JSON.writeValueAsString(proof));
        String proofHash = EventCodec.sha256(proofJson);
        List<HeadResolutionReceipt> receipts = jdbc.query("""
                SELECT decision,winner_operation_id,source_site,proof::text,expected_heads::text,proof_hash,conflict_revision
                FROM eatwhat_sync.catalog_head_resolution_receipt
                WHERE conflict_id=? AND proof_hash=?
                """, (rs, row) -> new HeadResolutionReceipt(rs.getString("decision"),
                rs.getObject("winner_operation_id", UUID.class),
                rs.getString("source_site"),
                JSON.readValue(rs.getString("proof"), CatalogEvents.ResolutionProof.class),
                JSON.readValue(rs.getString("expected_heads"), JSON.getTypeFactory()
                        .constructCollectionType(List.class, CatalogEvents.Head.class)),
                rs.getString("proof_hash").trim(), rs.getLong("conflict_revision")), proof.conflictId(), proofHash);
        if (receipts.isEmpty()) throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_RECEIPT_MISMATCH");
        HeadResolutionReceipt receipt = receipts.getFirst();
        if (!receipt.decision().equals(proof.decision().name()) || !receipt.proof().equals(proof)
                || !Objects.equals(receipt.winnerOperationId(), proof.winnerOperationId())
                || !receipt.proofHash().equals(proofHash) || !campusSiteId.get().equals(sourceSite)
                || !campusSiteId.get().equals(receipt.sourceSite())
                || !matchesHeadVersions(receipt.expectedHeads(), refs, proof.expectedHeads())) {
            throw new IllegalStateException("CATALOG_HEAD_RESOLUTION_RECEIPT_MISMATCH");
        }
    }

    private void retireCandidatesOutsideHeads(CatalogKind kind, UUID gid, List<CatalogEvents.Head> effectiveHeads) {
        Set<String> currentKeys = new java.util.HashSet<>();
        for (CatalogEvents.Head head : effectiveHeads) {
            if (head.tombstone()) continue;
            CatalogPayload.NaturalKey key = CatalogPayload.naturalKey(kind, head.canonicalPayload(),
                    head.authorGid() == null ? null : head.authorGid().toString(),
                    head.parentGid() == null ? null : head.parentGid().toString());
            if (key != null) currentKeys.add(key.hash());
        }
        List<String> savedKeys = jdbc.query("""
                SELECT key_hash FROM eatwhat_sync.catalog_candidate
                WHERE kind=? AND object_gid=? AND status<>'REJECTED'
                """, (rs, row) -> rs.getString(1).trim(), kind.name(), gid);
        for (String key : savedKeys) {
            if (!currentKeys.contains(key)) jdbc.update("""
                    UPDATE eatwhat_sync.catalog_candidate SET status='SUPERSEDED',updated_at=clock_timestamp()
                    WHERE kind=? AND object_gid=? AND key_hash=? AND status<>'REJECTED'
                    """, kind.name(), gid, key);
        }
    }

    private boolean matchesHeadVersions(List<CatalogEvents.Head> heads, List<RevisionRef> refs,
                                        List<UUID> expectedOperations) {
        if (heads.size() != refs.size() || heads.size() != expectedOperations.size()) return false;
        Map<UUID, RevisionRef> byOperation = new java.util.HashMap<>();
        for (int i = 0; i < refs.size(); i++) byOperation.put(expectedOperations.get(i), refs.get(i));
        for (CatalogEvents.Head head : heads) {
            RevisionRef ref = byOperation.get(head.operationId());
            if (ref == null || !ref.gid().equals(headOperationObject(refs, expectedOperations, head.operationId()))
                    || ref.revision() != head.revision() || !ref.contentHash().equals(head.contentHash())) return false;
        }
        return true;
    }

    private UUID headOperationObject(List<RevisionRef> refs, List<UUID> operations, UUID operation) {
        int index = operations.indexOf(operation);
        return index < 0 ? null : refs.get(index).gid();
    }

    private void observeObjectHeadConflict(CatalogKind kind, UUID gid, List<CatalogEvents.Head> heads) {
        if ((multiwriter==null && kind != CatalogKind.CUSTOM_TAG) || heads.size() < 2
                || heads.stream().anyMatch(head -> head.tombstone())) return;
        List<CatalogEvents.Head> ordered = heads.stream()
                .sorted(java.util.Comparator.comparing(head -> head.operationId().toString())).toList();
        String candidates = EventCodec.canonicalJson(JSON.writeValueAsString(ordered));
        if(multiwriter!=null) multiwriter.projectionState(kind,gid,"CONFLICT",List.of());
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_head_conflict(conflict_id,kind,object_gid,revision,status,candidates)
                VALUES (?,? ,?,1,'OPEN',?::jsonb)
                ON CONFLICT(conflict_id) DO UPDATE SET
                  revision=CASE WHEN catalog_head_conflict.candidates IS DISTINCT FROM EXCLUDED.candidates
                                THEN catalog_head_conflict.revision+1 ELSE catalog_head_conflict.revision END,
                  status=CASE WHEN catalog_head_conflict.candidates IS DISTINCT FROM EXCLUDED.candidates
                              THEN 'OPEN' ELSE catalog_head_conflict.status END,
                  candidates=EXCLUDED.candidates,updated_at=clock_timestamp()
                """, CatalogStore.stableHeadConflictId(kind, gid), kind.name(), gid, candidates);
    }

    private void rejectCandidate(CatalogKind kind, UUID gid) {
        CatalogStore.Identity identity = store.lockObject(kind, gid);
        if (identity != null) {
            if (!identity.rejected()) {
                store.updateObject(new CatalogStore.Identity(identity.kind(), identity.gid(), identity.numericId(),
                        identity.originSite(), identity.authoritySite(), identity.authorGid(), identity.parentGid(),
                        identity.revision(), identity.tombstone(), true, identity.causalState()));
            }
            if (publicExists(kind, identity.numericId())) projection.clearRejected(kind, identity.numericId());
        }
        jdbc.update("UPDATE eatwhat_sync.catalog_candidate SET status='REJECTED',updated_at=clock_timestamp() WHERE kind=? AND object_gid=? AND status NOT IN ('REJECTED','SUPERSEDED')",
                kind.name(), gid);
    }

    private boolean registerCandidate(CatalogEvents.Action action) {
        if (action.operation() == CatalogOperation.DELETE) return false;
        CatalogPayload.NaturalKey key = CatalogPayload.naturalKey(action.kind(), action.canonicalPayload(),
                action.authorGid() == null ? null : action.authorGid().toString(),
                action.parentGid() == null ? null : action.parentGid().toString());
        if (key == null) return false;
        jdbc.update("INSERT INTO eatwhat_sync.catalog_natural_key(kind,key_hash,canonical_key) VALUES (?,?,?::jsonb) ON CONFLICT(kind,key_hash) DO NOTHING",
                action.kind().name(), key.hash(), key.canonical());
        String canonical = jdbc.queryForObject("SELECT canonical_key::text FROM eatwhat_sync.catalog_natural_key WHERE kind=? AND key_hash=? FOR UPDATE",
                String.class, action.kind().name(), key.hash());
        if (!EventCodec.canonicalJson(canonical).equals(key.canonical())) throw new IllegalStateException("CATALOG_NATURAL_KEY_HASH_COLLISION");
        String summary = EventCodec.canonicalJson(JSON.writeValueAsString(Map.of(
                "name", action.canonicalPayload().getOrDefault("name", ""), "contentHash", action.contentHash())));
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_candidate(candidate_id,kind,key_hash,object_gid,numeric_id,source_site,author_gid,revision,content_hash,summary,status)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,'PENDING')
                ON CONFLICT(kind,key_hash,object_gid) DO UPDATE SET
                  candidate_id=CASE WHEN EXCLUDED.revision>=catalog_candidate.revision
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
                action.dotSite(), action.authorGid(), action.revision(), action.contentHash(), summary);
        List<UUID> candidates = jdbc.query("""
                SELECT object_gid FROM eatwhat_sync.catalog_candidate WHERE kind=? AND key_hash=? AND status NOT IN ('REJECTED','SUPERSEDED') ORDER BY object_gid FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), action.kind().name(), key.hash());
        if (candidates.size() < 2) return false;
        if(multiwriter!=null) for(UUID candidate:candidates) multiwriter.projectionState(action.kind(),candidate,"CONFLICT",List.of());
        UUID conflictId = jdbc.query("SELECT conflict_id FROM eatwhat_sync.catalog_conflict WHERE kind=? AND key_hash=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), action.kind().name(), key.hash()).stream().findFirst()
                .orElseGet(() -> CatalogStore.stableConflictId(action.kind(), key.hash()));
        String candidateJson = EventCodec.canonicalJson(JSON.writeValueAsString(candidates));
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_conflict(conflict_id,kind,key_hash,revision,status,candidates)
                VALUES (?,?,?,1,'OPEN',?::jsonb)
                ON CONFLICT(kind,key_hash) DO UPDATE SET revision=eatwhat_sync.catalog_conflict.revision+1,
                  status='OPEN',candidates=EXCLUDED.candidates,updated_at=clock_timestamp()
                """, conflictId, action.kind().name(), key.hash(), candidateJson);
        return true;
    }

    private void lockSubjects(List<CatalogEvents.ConsentEpoch> epochs, List<Long> additionalUsers) {
        Set<Long> unique = new java.util.TreeSet<>();
        epochs.stream().map(epoch -> consent.getBySubject(epoch.subjectGid()))
                .filter(Objects::nonNull).map(ConsentModels.State::userId).forEach(unique::add);
        unique.addAll(additionalUsers);
        List<Long> userIds = List.copyOf(unique);
        for (Long userId : userIds) {
            jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=? FOR SHARE",
                    (rs, row) -> rs.getObject(1, UUID.class), userId);
        }
    }

    private boolean proofsReady(CatalogEvents.ResolutionProof proof) {
        if (proof == null) return true;
        for (UUID id : proof.expectedHeads()) if (!hasRevision(id)) return false;
        return proof.decision() != CatalogDecision.KEEP || hasRevision(proof.winnerOperationId());
    }

    private CatalogEvents.Head terminalDelete(CatalogStore.Identity current,
                                                List<CatalogEvents.Head> effectiveHeads) {
        if (current.tombstone()) {
            return heads(current.causalState()).stream().filter(CatalogEvents.Head::tombstone)
                    .findFirst().orElseGet(() -> effectiveHeads.stream().filter(CatalogEvents.Head::tombstone)
                            .findFirst().orElse(null));
        }
        return effectiveHeads.stream().filter(CatalogEvents.Head::tombstone).findFirst().orElse(null);
    }

    private CatalogProjection.ProjectionResult applyProjection(CatalogEvents.Action action) {
        if(multiwriter!=null && action.operation()==CatalogOperation.DELETE) {
            multiwriter.projectionState(action.kind(),action.objectGid(),"SUPPRESSED",List.of());
            return CatalogProjection.ProjectionResult.APPLIED;
        }
        CatalogProjection.ProjectionResult result = projection.apply(action);
        if (result == CatalogProjection.ProjectionResult.WAIT_PARENT) {
            throw new IllegalStateException("CATALOG_PARENT_CHANGED_DURING_APPLY");
        }
        return result;
    }

    private CatalogEvents.Action asAction(CatalogKind kind, UUID gid, CatalogEvents.Head head) {
        return new CatalogEvents.Action(kind, gid, head.revision(), head.expectedRevision(),
                head.operation(), head.authoritySite(), head.authorGid(), head.parentGid(), head.numericId(),
                head.identityOrigin(), head.canonicalPayload(), head.contentHash(), head.operationId(),
                head.dotSite(), head.dotCounter(), head.context(), head.operationConsentEpochs(), null,
                head.resolutionProof());
    }

    private boolean stale(List<CatalogEvents.ConsentEpoch> epochs) {
        for (CatalogEvents.ConsentEpoch expected : epochs) {
            ConsentModels.State current = consent.getBySubject(expected.subjectGid());
            if (current != null && (current.epoch() > expected.epoch()
                    || (current.epoch() == expected.epoch() && "WITHDRAWN".equals(current.status())))) return true;
        }
        return false;
    }

    private boolean authenticatedRemoval(SyncEvent event) {
        // This delegate is enabled only by the v2 wrapper, which checks the actor's immutable
        // authorization witness before dependencies, mutation, or concurrent-revocation review.
        return multiwriter!=null && CatalogEvents.ACTION.equals(event.eventType())
                && CatalogEvents.parseAction(event.payloadJson()).operation()==CatalogOperation.DELETE;
    }

    private boolean epochsReady(List<CatalogEvents.ConsentEpoch> epochs,boolean removal) {
        for (CatalogEvents.ConsentEpoch expected : epochs) {
            ConsentModels.State current = consent.getBySubject(expected.subjectGid());
            if (current == null || current.epoch() < expected.epoch() || current.epoch() != expected.epoch()
                    || !(removal ? consent.removalReplicationAllowed(current.userId()) : consent.replicationAllowed(current.userId()))) return false;
        }
        return true;
    }

    private List<CatalogEvents.ConsentEpoch> eventEpochs(SyncEvent event) {
        return CatalogEvents.ACTION.equals(eventType)
                ? CatalogEvents.parseAction(event.payloadJson()).consentEpochs()
                : CatalogEvents.parseBridge(event.payloadJson()).consentEpochs();
    }

    /** Wire epochs are claims; the object and immutable proof ledger determine the required principals. */
    private boolean epochCoverageSatisfied(SyncEvent event) {
        if (CatalogEvents.ACTION.equals(eventType)) {
            CatalogEvents.Action action = CatalogEvents.parseAction(event.payloadJson());
            Set<UUID> required = actionSubjectSet(action);
            if (required == null) return false;
            requireExactEpochSubjects(action.consentEpochs(), required);
            return true;
        }
        CatalogEvents.StateBridge bridge = CatalogEvents.parseBridge(event.payloadJson());
        Set<UUID> required = new java.util.TreeSet<>(java.util.Comparator.comparing(UUID::toString));
        for (CatalogEvents.Head head : bridge.heads()) {
            Set<UUID> headSubjects = headSubjectSet(bridge.kind(), head, true);
            if (headSubjects == null) return false;
            requireExactEpochSubjects(head.operationConsentEpochs(), headSubjects);
            required.addAll(headSubjects);
        }
        requireExactEpochSubjects(bridge.consentEpochs(), required);
        return true;
    }

    private Set<UUID> actionSubjectSet(CatalogEvents.Action action) {
        CatalogEvents.Head head = head(action);
        return headSubjectSet(action.kind(), head, true);
    }

    private Set<UUID> headSubjectSet(CatalogKind kind, CatalogEvents.Head head, boolean includeProof) {
        Set<UUID> subjects = new java.util.TreeSet<>(java.util.Comparator.comparing(UUID::toString));
        if (kind == CatalogKind.RESTAURANT || kind == CatalogKind.FOOD || kind == CatalogKind.CUSTOM_TAG) {
            if (head.authorGid() == null) throw new IllegalArgumentException("CATALOG_AUTHOR_REQUIRED");
            subjects.add(head.authorGid());
        }
        if (kind == CatalogKind.FOOD) {
            CatalogStore.Identity parent = store.findObject(CatalogKind.RESTAURANT, head.parentGid());
            UUID parentAuthor = parent == null ? publicRestaurantAuthor(head.parentGid()) : parent.authorGid();
            if (parentAuthor == null) return null;
            subjects.add(parentAuthor);
        }
        if (includeProof && head.resolutionProof() != null) {
            List<UUID> operations = new ArrayList<>(head.resolutionProof().expectedHeads());
            if (head.resolutionProof().winnerOperationId() != null) {
                operations.add(head.resolutionProof().winnerOperationId());
            }
            for (UUID operation : operations) {
                if (!store.hasOperation(operation)) return null;
                List<UUID> proofSubjects = store.subjectsForOperation(operation);
                subjects.addAll(proofSubjects);
            }
        }
        return subjects;
    }

    private static void requireExactEpochSubjects(List<CatalogEvents.ConsentEpoch> epochs, Set<UUID> required) {
        Set<UUID> claimed = epochs.stream().map(CatalogEvents.ConsentEpoch::subjectGid)
                .collect(java.util.stream.Collectors.toSet());
        if (!claimed.equals(required)) throw new IllegalArgumentException("CATALOG_CONSENT_SUBJECT_SET_MISMATCH");
    }

    private boolean parentStateReady(CatalogKind kind, UUID parentGid) {
        if (parentGid == null || (kind != CatalogKind.FOOD && kind != CatalogKind.RESTAURANT)) return true;
        CatalogKind parentKind = kind == CatalogKind.FOOD ? CatalogKind.RESTAURANT : CatalogKind.HUB;
        CatalogStore.Identity parent = store.findObject(parentKind, parentGid);
        if (parent != null && (parent.tombstone() || parent.rejected())) return true;
        if (parent == null) {
            if (kind == CatalogKind.FOOD) return legacyRestaurantExists(parentGid);
            return false;
        }
        return publicExists(parentKind, parent.numericId());
    }

    /** Admit only a genuine first revision over an already registered legacy public identity. */
    private CatalogStore.Identity isLegacyRevZeroAction(CatalogEvents.Action action, boolean lockPublic) {
        if (action.operation() == CatalogOperation.CREATE || action.revision() != 1
                || action.expectedRevision() != 0 || !action.context().isEmpty()
                || action.resolutionProof() != null
                || campusSiteId.get() == null || !campusSiteId.get().equals(action.authoritySite())
                || hasRevisionForObject(action.kind(), action.objectGid())) return null;
        CatalogStore.Identity registered = registeredPublicIdentity(action, lockPublic);
        return registered;
    }

    private boolean publicParentExists(UUID parentGid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity i
                  JOIN public.restaurant r ON r.id=i.numeric_id
                  WHERE i.entity_type='restaurant' AND i.global_id=?)
                """, Boolean.class, parentGid));
    }

    private CatalogStore.Identity registeredPublicIdentity(CatalogEvents.Action action, boolean lockPublic) {
        String entityType = switch (action.kind()) {
            case HUB -> "hub";
            case RESTAURANT -> "restaurant";
            case FOOD -> "foods";
            case SYSTEM_TAG, CUSTOM_TAG -> "food_tags";
            case ACTIVITY -> "activities";
        };
        String table = switch (action.kind()) {
            case HUB -> "hub";
            case RESTAURANT -> "restaurant";
            case FOOD -> "foods";
            case SYSTEM_TAG, CUSTOM_TAG -> "food_tags";
            case ACTIVITY -> "activities";
        };
        if (lockPublic) jdbc.query("SELECT id FROM public." + table + " WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), action.numericId());
        String authorExpr = switch (action.kind()) {
            case RESTAURANT -> "(SELECT global_id FROM eatwhat_identity.entity_identity u WHERE u.entity_type='users' AND u.numeric_id=p.account_id)";
            case FOOD -> "(SELECT global_id FROM eatwhat_identity.entity_identity u WHERE u.entity_type='users' AND u.numeric_id=p.account_id)";
            case CUSTOM_TAG -> "(SELECT global_id FROM eatwhat_identity.entity_identity u WHERE u.entity_type='users' AND u.numeric_id=p.owner_id)";
            default -> "NULL::uuid";
        };
        String parentExpr = switch (action.kind()) {
            case FOOD -> "(SELECT global_id FROM eatwhat_identity.entity_identity r WHERE r.entity_type='restaurant' AND r.numeric_id=p.restaurant_id)";
            case RESTAURANT -> "(SELECT global_id FROM eatwhat_identity.entity_identity h WHERE h.entity_type='hub' AND h.numeric_id=p.hub_id)";
            default -> "NULL::uuid";
        };
        String tagPredicate = action.kind() == CatalogKind.SYSTEM_TAG ? " AND p.tag_type='system'"
                : action.kind() == CatalogKind.CUSTOM_TAG ? " AND p.tag_type='custom'" : "";
        String sql = "SELECT i.global_id,i.origin_site," + authorExpr + " AS author_gid," + parentExpr
                + " AS parent_gid,p.id FROM eatwhat_identity.entity_identity i JOIN public." + table
                + " p ON p.id=i.numeric_id WHERE i.entity_type=? AND i.numeric_id=? AND i.global_id=?" + tagPredicate;
        List<CatalogStore.Identity> rows = jdbc.query(sql,
                (rs, row) -> new CatalogStore.Identity(action.kind(), rs.getObject("global_id", UUID.class),
                        rs.getLong("id"), rs.getString("origin_site"), action.authoritySite(),
                        rs.getObject("author_gid", UUID.class), rs.getObject("parent_gid", UUID.class),
                        0, false, false, emptyState()), entityType, action.numericId(), action.objectGid());
        if (rows.isEmpty()) return null;
        CatalogStore.Identity identity = rows.getFirst();
        if (!Objects.equals(identity.originSite(), action.identityOrigin())
                || !Objects.equals(identity.authorGid(), action.authorGid())) return null;
        if ((action.kind() == CatalogKind.FOOD || action.operation() == CatalogOperation.DELETE)
                && !Objects.equals(identity.parentGid(), action.parentGid())) return null;
        if (action.operation() == CatalogOperation.DELETE && action.kind() == CatalogKind.FOOD
                && !legacyRestaurantExists(action.parentGid())) return null;
        return identity;
    }

    private boolean hasRevisionForObject(CatalogKind kind, UUID gid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision WHERE kind=? AND object_gid=?)",
                Boolean.class, kind.name(), gid));
    }

    private boolean legacyRestaurantExists(UUID gid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity i
                  JOIN public.restaurant r ON r.id=i.numeric_id
                  WHERE i.entity_type='restaurant' AND i.global_id=? AND i.origin_site IS NOT NULL)
                """, Boolean.class, gid));
    }

    private UUID publicRestaurantAuthor(UUID gid) {
        return jdbc.query("""
                SELECT u.global_id FROM eatwhat_identity.entity_identity i
                JOIN public.restaurant r ON r.id=i.numeric_id
                JOIN eatwhat_identity.entity_identity u ON u.entity_type='users' AND u.numeric_id=r.account_id
                WHERE i.entity_type='restaurant' AND i.global_id=?
                """, (rs, row) -> rs.getObject(1, UUID.class), gid).stream().findFirst().orElse(null);
    }

    private List<Long> localCascadeAuthorUsers(SyncEvent event) {
        Set<Long> users = new java.util.TreeSet<>();
        for (Long restaurantId : cascadeRestaurantIds(event)) {
            users.addAll(jdbc.query("SELECT DISTINCT account_id FROM public.foods WHERE restaurant_id=? ORDER BY account_id",
                    (rs, row) -> rs.getLong(1), restaurantId));
        }
        return List.copyOf(users);
    }

    private List<Long> cascadeRestaurantIds(SyncEvent event) {
        Set<Long> ids = new java.util.TreeSet<>();
        if (CatalogEvents.ACTION.equals(eventType)) {
            CatalogEvents.Action action = CatalogEvents.parseAction(event.payloadJson());
            if (action.kind() == CatalogKind.RESTAURANT && action.operation() == CatalogOperation.DELETE) {
                ids.add(action.numericId());
            }
            addRestaurantResolutionLosers(ids, action.resolutionProof());
        } else {
            CatalogEvents.StateBridge bridge = CatalogEvents.parseBridge(event.payloadJson());
            if (bridge.kind() == CatalogKind.RESTAURANT
                    && bridge.heads().stream().anyMatch(CatalogEvents.Head::tombstone)) ids.add(bridge.numericId());
            for (CatalogEvents.Head head : bridge.heads()) {
                addRestaurantResolutionLosers(ids, head.resolutionProof());
            }
        }
        return List.copyOf(ids);
    }

    private void addRestaurantResolutionLosers(Set<Long> ids, CatalogEvents.ResolutionProof proof) {
        if (proof == null) return;
        UUID winnerGid = null;
        if (proof.decision() == CatalogDecision.KEEP && proof.winnerOperationId() != null) {
            CatalogStore.ImmutableRevision winner = store.readRevision(proof.winnerOperationId());
            if (winner != null) winnerGid = winner.action().objectGid();
        }
        for (UUID operationId : proof.expectedHeads()) {
            CatalogStore.ImmutableRevision revision = store.readRevision(operationId);
            if (revision != null && revision.action().kind() == CatalogKind.RESTAURANT
                    && (winnerGid == null || !winnerGid.equals(revision.action().objectGid()))) {
                ids.add(revision.action().numericId());
            }
        }
    }

    private String cascadeAuthority(SyncEvent event, long restaurantId) {
        if (campusSiteId.get() == null) throw new IllegalStateException("CATALOG_CASCADE_AUTHORITY_UNAVAILABLE");
        if (CatalogEvents.ACTION.equals(eventType)) {
            CatalogEvents.Action action = CatalogEvents.parseAction(event.payloadJson());
            if (action.kind() == CatalogKind.RESTAURANT && action.numericId() == restaurantId
                    && !campusSiteId.get().equals(action.authoritySite())) {
                throw new IllegalStateException("CATALOG_CASCADE_AUTHORITY_UNVERIFIED");
            }
        } else {
            CatalogEvents.StateBridge bridge = CatalogEvents.parseBridge(event.payloadJson());
            if (bridge.kind() == CatalogKind.RESTAURANT && bridge.numericId() == restaurantId
                    && !campusSiteId.get().equals(bridge.authoritySite())) {
                throw new IllegalStateException("CATALOG_CASCADE_AUTHORITY_UNVERIFIED");
            }
        }
        return campusSiteId.get();
    }

    private void seedLegacyRestaurantChildren(long restaurantId, String authoritySite) {
        List<CatalogStore.Identity> children = jdbc.query("""
                SELECT fi.global_id,fi.numeric_id,fi.origin_site,u.global_id AS author_gid,
                       ri.global_id AS parent_gid
                FROM public.foods f
                JOIN eatwhat_identity.entity_identity fi ON fi.entity_type='foods' AND fi.numeric_id=f.id
                JOIN eatwhat_identity.entity_identity u ON u.entity_type='users' AND u.numeric_id=f.account_id
                JOIN eatwhat_identity.entity_identity ri ON ri.entity_type='restaurant' AND ri.numeric_id=f.restaurant_id
                WHERE f.restaurant_id=? ORDER BY f.id FOR UPDATE OF f
                """, (rs, row) -> new CatalogStore.Identity(CatalogKind.FOOD,
                rs.getObject("global_id", UUID.class), rs.getLong("numeric_id"), rs.getString("origin_site"),
                authoritySite, rs.getObject("author_gid", UUID.class),
                rs.getObject("parent_gid", UUID.class), 0, false, false, emptyState()), restaurantId);
        for (CatalogStore.Identity child : children) {
            if (child.originSite() == null) throw new IllegalStateException("CATALOG_CASCADE_IDENTITY_MISSING");
            CatalogStore.Identity existing = store.lockObject(CatalogKind.FOOD, child.gid());
            if (existing == null) {
                if (hasRevisionForObject(CatalogKind.FOOD, child.gid())) {
                    throw new IllegalStateException("CATALOG_CASCADE_HISTORY_WITHOUT_IDENTITY");
                }
                store.insertObject(child);
            } else if (existing.numericId() != child.numericId()
                    || !Objects.equals(existing.originSite(), child.originSite())
                    || !Objects.equals(existing.authorGid(), child.authorGid())
                    || !Objects.equals(existing.parentGid(), child.parentGid())) {
                throw new IllegalStateException("CATALOG_CASCADE_IDENTITY_CONFLICT");
            }
        }
        if (!children.isEmpty()) store.markManaged(CatalogKind.FOOD);
    }

    private void seedLegacyParent(CatalogEvents.Action action) {
        if (action.kind() != CatalogKind.FOOD || action.parentGid() == null
                || store.findObject(CatalogKind.RESTAURANT, action.parentGid()) != null) return;
        List<CatalogStore.Identity> parents = jdbc.query("""
                SELECT i.global_id,i.numeric_id,i.origin_site,u.global_id AS author_gid,h.global_id AS hub_gid
                FROM eatwhat_identity.entity_identity i JOIN public.restaurant r ON r.id=i.numeric_id
                JOIN eatwhat_identity.entity_identity u ON u.entity_type='users' AND u.numeric_id=r.account_id
                LEFT JOIN eatwhat_identity.entity_identity h ON h.entity_type='hub' AND h.numeric_id=r.hub_id
                WHERE i.entity_type='restaurant' AND i.global_id=? FOR UPDATE OF r
                """, (rs, row) -> new CatalogStore.Identity(CatalogKind.RESTAURANT,
                rs.getObject("global_id", UUID.class), rs.getLong("numeric_id"), rs.getString("origin_site"),
                action.authoritySite(), rs.getObject("author_gid", UUID.class), rs.getObject("hub_gid", UUID.class),
                0, false, false, emptyState()),
                action.parentGid());
        if (parents.isEmpty()) throw new IllegalStateException("CATALOG_PARENT_IDENTITY_UNAVAILABLE");
        store.insertObject(parents.getFirst());
        store.markManaged(CatalogKind.RESTAURANT);
    }

    private boolean parentTerminal(CatalogKind kind, UUID parentGid) {
        // A terminal restaurant suppresses its foods. A terminal hub only unbinds restaurants;
        // the public FK's ON DELETE SET NULL behavior and immutable assignment heads preserve them.
        if (parentGid == null || kind != CatalogKind.FOOD) return false;
        CatalogStore.Identity parent = store.findObject(CatalogKind.RESTAURANT, parentGid);
        return parent != null && (parent.tombstone() || parent.rejected());
    }

    private boolean publicExists(CatalogKind kind, long id) {
        String table = switch (kind) {
            case HUB -> "hub";
            case RESTAURANT -> "restaurant";
            case FOOD -> "foods";
            case SYSTEM_TAG, CUSTOM_TAG -> "food_tags";
            case ACTIVITY -> "activities";
        };
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public." + table + " WHERE id=?)",
                Boolean.class, id));
    }

    private boolean contextCovered(Map<String, Object> rawState, Map<String, Long> context) {
        Map<String, Long> seen = seen(rawState);
        return context.entrySet().stream().allMatch(entry -> seen.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    private boolean hasRevision(UUID operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision WHERE operation_id=?)",
                Boolean.class, operationId));
    }

    private void verifyIdentity(CatalogStore.Identity identity, CatalogKind kind, UUID gid, long numericId,
                                String origin, UUID author) {
        if (identity.kind() != kind || !identity.gid().equals(gid) || identity.numericId() != numericId
                || !identity.originSite().equals(origin)
                || (author != null && !Objects.equals(identity.authorGid(), author))) {
            throw new IllegalArgumentException("CATALOG_IDENTITY_CONFLICT");
        }
    }

    private CatalogEvents.Head head(CatalogEvents.Action action) {
        return new CatalogEvents.Head(action.operationId(), action.dotSite(), action.authoritySite(),
                action.authorGid(), action.parentGid(), action.numericId(), action.identityOrigin(),
                action.revision(), action.expectedRevision(), action.operation(), action.dotSite(),
                action.dotCounter(), action.context(), action.consentEpochs(),
                action.operation() == CatalogOperation.DELETE, action.canonicalPayload(),
                action.contentHash(), action.resolutionProof());
    }

    private CatalogEvents.Action asAction(CatalogEvents.StateBridge bridge, CatalogEvents.Head head) {
        return new CatalogEvents.Action(bridge.kind(), bridge.objectGid(), head.revision(), head.expectedRevision(),
                head.operation(), head.authoritySite(), head.authorGid(), head.parentGid(), head.numericId(),
                head.identityOrigin(), head.canonicalPayload(), head.contentHash(), head.operationId(), head.dotSite(),
                head.dotCounter(), head.context(), head.operationConsentEpochs(), null, head.resolutionProof());
    }

    private Map<String, Object> merge(Map<String, Object> current, Collection<CatalogEvents.Head> incoming) {
        List<CatalogEvents.Head> heads = heads(current);
        Map<String, Long> seen = seen(current);
        for (CatalogEvents.Head next : incoming) {
            boolean duplicate = false;
            for (CatalogEvents.Head existing : heads) {
                if (existing.operationId().equals(next.operationId())) {
                    if (!existing.equals(next)) throw new IllegalArgumentException("CATALOG_HEAD_IDENTITY_CONFLICT");
                    duplicate = true;
                    break;
                }
                if (existing.dotSite().equals(next.dotSite()) && existing.dotCounter() == next.dotCounter()) {
                    throw new IllegalArgumentException("CATALOG_DOT_IDENTITY_CONFLICT");
                }
            }
            next.context().forEach((site, counter) -> seen.merge(site, counter, Math::max));
            seen.merge(next.dotSite(), next.dotCounter(), Math::max);
            if (duplicate) continue;
            boolean dominated = heads.stream().anyMatch(existing -> observes(existing.context(), next));
            if (dominated) continue;
            heads.removeIf(existing -> observes(next.context(), existing));
            heads.add(next);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seen", seen);
        result.put("heads", heads.stream().map(this::headMap).toList());
        return result;
    }

    private static boolean observes(Map<String, Long> context, CatalogEvents.Head head) {
        return context.getOrDefault(head.dotSite(), 0L) >= head.dotCounter();
    }

    private static Map<String, Long> seen(Map<String, Object> state) {
        Map<String, Long> result = new TreeMap<>();
        Object raw = state == null ? null : state.get("seen");
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> {
            if (key instanceof String site && value instanceof Number count && count.longValue() > 0) {
                result.put(site, count.longValue());
            }
        });
        return result;
    }

    private List<CatalogEvents.Head> heads(Map<String, Object> state) {
        List<CatalogEvents.Head> result = new ArrayList<>();
        Object raw = state == null ? null : state.get("heads");
        if (raw instanceof List<?> list) for (Object value : list) {
            result.add(JSON.readValue(JSON.writeValueAsString(value), CatalogEvents.Head.class));
        }
        return result;
    }

    private Map<String, Object> headMap(CatalogEvents.Head head) {
        return JSON.readValue(JSON.writeValueAsString(head), JSON.getTypeFactory()
                .constructMapType(Map.class, String.class, Object.class));
    }

    private static Map<String, Object> emptyState() {
        return Map.of("seen", Map.of(), "heads", List.of());
    }

    private record RevisionRef(CatalogKind kind, UUID gid, long numericId, String keyHash,
                               long revision, String contentHash) { }
    private record ConflictState(String status, long revision) { }
    private record ResolutionReceipt(String decision, UUID winnerGid, String sourceSite, String proof,
                                     List<Map<String, Object>> expectedCandidates, String proofHash,
                                     long conflictRevision) { }
    private record AuditResolution(List<Map<String, Object>> expectedCandidates, UUID winnerGid,
                                   long conflictRevision) { }
    private record HeadConflictState(String status, long revision, List<CatalogEvents.Head> candidates) { }
    private record HeadResolutionReceipt(String decision, UUID winnerOperationId, String sourceSite,
                                         CatalogEvents.ResolutionProof proof,
                                         List<CatalogEvents.Head> expectedHeads,
                                         String proofHash, long conflictRevision) { }

    private List<Map<String, Object>> resolutionCandidates(List<RevisionRef> references) {
        return references.stream().sorted(java.util.Comparator.comparing(ref -> ref.gid().toString()))
                .map(ref -> Map.<String, Object>of("gid", ref.gid().toString(), "revision", ref.revision(),
                        "contentHash", ref.contentHash())).toList();
    }

    private boolean matchesResolutionCandidates(List<Map<String, Object>> candidates, List<RevisionRef> refs) {
        if (candidates.size() != refs.size()) return false;
        for (RevisionRef ref : refs) {
            boolean found = candidates.stream().anyMatch(row -> ref.gid().toString().equals(row.get("gid"))
                    && row.get("revision") instanceof Number revision && revision.longValue() == ref.revision()
                    && ref.contentHash().equals(row.get("contentHash")));
            if (!found) return false;
        }
        return true;
    }
}
