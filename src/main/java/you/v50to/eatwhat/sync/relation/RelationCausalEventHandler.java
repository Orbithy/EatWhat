package you.v50to.eatwhat.sync.relation;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.EventHandler;
import you.v50to.eatwhat.sync.SyncEvent;

import java.util.UUID;

/** Applies V2 actions and regrant bridges to the natural-key causal state. */
public final class RelationCausalEventHandler implements EventHandler {
    private final String eventType;
    private final JdbcTemplate jdbc;
    private final RelationStore projections;
    private final RelationCausalStore causal;
    private final ConsentStore consent;
    private final java.util.Set<String> allowedNativeSites;

    public RelationCausalEventHandler(String eventType, JdbcTemplate jdbc, RelationStore projections,
                                      RelationCausalStore causal, ConsentStore consent) {
        this(eventType, jdbc, projections, causal, consent, null);
    }

    public RelationCausalEventHandler(String eventType, JdbcTemplate jdbc, RelationStore projections,
                                      RelationCausalStore causal, ConsentStore consent,
                                      java.util.Set<String> allowedNativeSites) {
        if (!RelationCausalEvents.ACTION.equals(eventType) && !RelationCausalEvents.BRIDGE.equals(eventType)) {
            throw new IllegalArgumentException("Unsupported relation causal event type");
        }
        this.eventType = eventType;
        this.jdbc = jdbc;
        this.projections = projections;
        this.causal = causal;
        this.consent = consent;
        this.allowedNativeSites = allowedNativeSites == null ? null : you.v50to.eatwhat.cluster.ClusterMembershipView.freezeOrLive(allowedNativeSites);
    }

    @Override public String aggregateType() { return RelationEvents.AGGREGATE; }
    @Override public String eventType() { return eventType; }
    @Override public int schemaVersion() { return RelationCausalEvents.SCHEMA_VERSION; }

    @Override
    public boolean dependenciesSatisfied(SyncEvent event) {
        RelationCausalEvents.validateEvent(event, allowedNativeSites);
        RelationCausalStore.Identity identity = identity(event);
        lockConsent(identity.actor(), identity.owner());
        if (stale(identity)) return true;

        Long actor = projections.resolve("users", identity.actor());
        Long target = projections.resolve(identity.kind().targetTable, identity.target());
        if (target == null) target = CatalogTerminalReference.registeredNumericId(jdbc,
                identity.kind().targetTable, identity.target());
        Long owner = projections.resolve("users", identity.owner());
        if (actor == null || target == null || owner == null) return false;
        boolean removing = RelationEvents.removalOnly(event);
        if (!(removing ? consent.removalReplicationAllowed(actor) && consent.removalReplicationAllowed(owner)
                : consent.replicationAllowed(actor) && consent.replicationAllowed(owner))) return false;
        if (consent.get(actor).epoch() != identity.actorEpoch()
                || consent.get(owner).epoch() != identity.ownerEpoch()) return false;

        jdbc.query("SELECT id FROM public.users WHERE id IN (?, ?) ORDER BY id FOR KEY SHARE",
                (row, index) -> row.getLong(1), actor, owner);
        Long actualOwner = ownerId(identity.kind(), target);
        if (actualOwner != null && !actualOwner.equals(owner)) throw new IllegalArgumentException("Relation owner mismatch");
        Long tag = identity.tag() == null ? null : projections.resolve("food_tags", identity.tag());
        if (tag == null && identity.tag() != null) {
            tag = CatalogTerminalReference.registeredNumericId(jdbc, "food_tags", identity.tag());
        }
        if (identity.tag() != null && (tag == null
                || !CatalogTerminalReference.tagReferenceValid(jdbc, identity.tag(), tag, identity.actor()))) return false;
        boolean terminal = CatalogTerminalReference.isTerminal(jdbc, identity.kind(), identity.target(), target,
                identity.owner(), identity.tag(), tag == null ? -1L : tag, identity.actor());
        if (projections.resolve(identity.kind().targetTable, identity.target()) == null && !terminal) return false;
        if (actualOwner == null && !terminal) return false;
        if (!lockTarget(identity.kind(), target) && !terminal) return false;
        if (!terminal && identity.tag() != null && (tag == null || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.food_tags
                  WHERE id=? AND (tag_type='system' OR (tag_type='custom' AND owner_id=?)))
                """, Boolean.class, tag, actor)))) return false;

        projections.lock(identity.kind(), actor, target, tag);
        boolean currentPresent = projections.present(identity.kind(), actor, target, tag);
        RelationCausalStore.State state = causal.ensure(identity, currentPresent,
                hasUnpublishedLocalIntent(identity.naturalId()), true);
        if (state.blockedReason() != null && !"MULTI_LEGACY".equals(state.blockedReason())) return false;
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            return causal.canApply(RelationCausalEvents.parseAction(event.payloadJson()));
        }
        return state.legacyLoaded() && causal.canApplyBridge(RelationCausalEvents.parseBridge(event.payloadJson()));
    }

    @Override
    public void apply(SyncEvent event) {
        RelationCausalEvents.validateEvent(event, allowedNativeSites);
        RelationCausalStore.Identity identity = identity(event);
        if (stale(identity)) return;
        long actor = projections.resolve("users", identity.actor());
        Long resolvedTarget = projections.resolve(identity.kind().targetTable, identity.target());
        if (resolvedTarget == null) resolvedTarget = CatalogTerminalReference.registeredNumericId(
                jdbc, identity.kind().targetTable, identity.target());
        if (resolvedTarget == null) throw new IllegalStateException("CATALOG_RELATION_TARGET_DISAPPEARED");
        long target = resolvedTarget;
        Long tag = identity.tag() == null ? null : projections.resolve("food_tags", identity.tag());
        if (tag == null && identity.tag() != null) {
            tag = CatalogTerminalReference.registeredNumericId(jdbc, "food_tags", identity.tag());
        }
        if (identity.tag() != null && (tag == null
                || !CatalogTerminalReference.tagReferenceValid(jdbc, identity.tag(), tag, identity.actor()))) {
            throw new IllegalStateException("CATALOG_RELATION_TAG_DISAPPEARED");
        }
        boolean terminal = CatalogTerminalReference.isTerminal(jdbc, identity.kind(), identity.target(), target,
                identity.owner(), identity.tag(), tag == null ? -1L : tag, identity.actor());
        if (!terminal && (!publicTargetExists(identity.kind().targetTable, target)
                || (identity.tag() != null && (tag == null || !publicTargetExists("food_tags", tag))))) {
            throw new IllegalStateException("CATALOG_RELATION_TARGET_DISAPPEARED");
        }
        if (terminal) {
            if (RelationCausalEvents.ACTION.equals(event.eventType())) {
                RelationCausalEvents.Action action = RelationCausalEvents.parseAction(event.payloadJson());
                causal.recordV1Evidence(event.eventId(), action.v1Evidence());
                causal.mergeAction(action, event.eventId());
            } else {
                causal.mergeBridge(RelationCausalEvents.parseBridge(event.payloadJson()));
            }
            return;
        }
        RelationStore.Intent prior = projections.readIntent(identity.naturalId()).orElse(null);
        boolean pendingLocal = prior != null && prior.localIntent() && prior.publishedRevision() < prior.revision();
        RelationCausalStore.State merged;
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            RelationCausalEvents.Action action = RelationCausalEvents.parseAction(event.payloadJson());
            causal.recordV1Evidence(event.eventId(), action.v1Evidence());
            merged = causal.mergeAction(action, event.eventId());
            if (merged == null) throw new IllegalStateException("Relation action predecessor disappeared");
        } else {
            merged = causal.mergeBridge(RelationCausalEvents.parseBridge(event.payloadJson()));
        }
        if (merged.blockedReason() != null) return;
        boolean present = merged.present();
        boolean projected = pendingLocal ? prior.desiredPresent() : present;
        RelationEvents.Operation projectionIdentity = projectionIdentity(identity, event.originSite(), present);
        RelationStore.Projection projection = projections.project(identity.kind(), actor, target, tag, projected,
                projectionIdentity, event.originSite());
        projections.markObserved(identity.kind(), identity.actor(), identity.target(), identity.owner(), identity.tag(),
                projection, present, identity.actorEpoch(), identity.ownerEpoch());
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            RelationEvents.Operation evidence = RelationCausalEvents.parseAction(event.payloadJson()).v1Evidence();
            if (evidence.notification() != null) {
                projections.notification(event.eventId(), actor,
                        identity.kind() == RelationKind.follow ? actor : target, evidence.notification(), event.originSite());
            }
        }
    }

    private RelationCausalStore.Identity identity(SyncEvent event) {
        if (RelationCausalEvents.ACTION.equals(event.eventType())) {
            var action = RelationCausalEvents.parseAction(event.payloadJson());
            return new RelationCausalStore.Identity(action.naturalId(), action.kind(), action.actor(), action.target(),
                    action.owner(), action.tag(), action.actorEpoch(), action.ownerEpoch(), action.rowId(),
                    action.rowGid(), action.rowOrigin());
        }
        var bridge = RelationCausalEvents.parseBridge(event.payloadJson());
        return new RelationCausalStore.Identity(bridge.naturalId(), bridge.kind(), bridge.actor(), bridge.target(),
                bridge.owner(), bridge.tag(), bridge.actorEpoch(), bridge.ownerEpoch(), bridge.rowId(),
                bridge.rowGid(), bridge.rowOrigin());
    }

    private void lockConsent(UUID actor, UUID owner) {
        jdbc.query("""
                SELECT user_id FROM eatwhat_consent.subject WHERE subject_id IN (?,?)
                ORDER BY user_id FOR SHARE
                """, (row, index) -> row.getLong(1), actor, owner);
    }

    private boolean stale(RelationCausalStore.Identity identity) {
        var actor = consent.getBySubject(identity.actor());
        var owner = consent.getBySubject(identity.owner());
        return (actor != null && (actor.epoch() > identity.actorEpoch()
                || actor.epoch() == identity.actorEpoch() && actor.status().equals("WITHDRAWN")))
                || (owner != null && (owner.epoch() > identity.ownerEpoch()
                || owner.epoch() == identity.ownerEpoch() && owner.status().equals("WITHDRAWN")));
    }

    private boolean hasUnpublishedLocalIntent(UUID naturalId) {
        RelationStore.Intent intent = projections.readIntent(naturalId).orElse(null);
        return intent != null && intent.localIntent() && intent.publishedRevision() < intent.revision();
    }

    private Long ownerId(RelationKind kind, long target) {
        String table = kind == RelationKind.follow ? "users" : kind.targetTable;
        String column = kind == RelationKind.follow ? "id" : "account_id";
        return jdbc.query("SELECT " + column + " FROM public." + table + " WHERE id=?",
                (row, index) -> row.getLong(1), target).stream().findFirst().orElse(null);
    }

    private boolean publicTargetExists(String table, long target) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public." + table + " WHERE id=?)",
                Boolean.class, target));
    }

    private boolean lockTarget(RelationKind kind, long target) {
        String lock = kind == RelationKind.follow ? "FOR KEY SHARE" : "FOR UPDATE";
        return !jdbc.query("SELECT id FROM public." + kind.targetTable + " WHERE id=? " + lock,
                (row, index) -> row.getLong(1), target).isEmpty();
    }

    private static RelationEvents.Operation projectionIdentity(RelationCausalStore.Identity i, String eventOrigin,
                                                               boolean adding) {
        return new RelationEvents.Operation(i.kind(), i.actor(), i.target(), i.owner(), i.tag(), i.actorEpoch(),
                i.ownerEpoch(), i.rowId(), i.rowGid(), i.rowOrigin(), java.util.Map.of(), eventOrigin, 1, adding, null);
    }
}
