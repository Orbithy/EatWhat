package you.v50to.eatwhat.sync.relation;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.EventHandler;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

/** Replay merges causal operations; it never calls an HTTP command or creates a new notification. */
public final class RelationEventHandler implements EventHandler {
    private final JdbcTemplate jdbc;
    private final RelationStore store;
    private final RelationCausalStore causal;
    private final ConsentStore consent;
    private final RelationEvents eventPolicy = new RelationEvents();

    public RelationEventHandler(JdbcTemplate jdbc, RelationStore store, RelationCausalStore causal, ConsentStore consent) {
        this.jdbc = jdbc;
        this.store = store;
        this.causal = causal;
        this.consent = consent;
    }

    /** Compatibility constructor for V1-only direct fixtures. Production registers the shared causal store. */
    public RelationEventHandler(JdbcTemplate jdbc, RelationStore store, ConsentStore consent) {
        this(jdbc, store, new RelationCausalStore(jdbc), consent);
    }

    @Override
    public String aggregateType() {
        return RelationEvents.AGGREGATE;
    }

    @Override
    public String eventType() {
        return RelationEvents.EVENT;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    private RelationEvents.Operation operation(SyncEvent event) {
        eventPolicy.validate(event, new NodeIdentity(event.originSite(), event.originInstanceId()));
        return RelationEvents.parse(event.payloadJson());
    }

    private boolean stale(RelationEvents.Operation operation) {
        var actor = consent.getBySubject(operation.actor());
        var owner = consent.getBySubject(operation.owner());
        return (actor != null && (actor.epoch() > operation.actorEpoch()
                || (actor.epoch() == operation.actorEpoch() && actor.status().equals("WITHDRAWN"))))
                || (owner != null && (owner.epoch() > operation.ownerEpoch()
                || (owner.epoch() == operation.ownerEpoch() && owner.status().equals("WITHDRAWN"))));
    }

    @Override
    public boolean dependenciesSatisfied(SyncEvent event) {
        RelationEvents.Operation operation = operation(event);

        // Lock authorization versions for the whole Inbox transaction, even for stale disposal.
        jdbc.query("""
                SELECT user_id FROM eatwhat_consent.subject
                WHERE subject_id IN (?, ?) ORDER BY user_id FOR SHARE
                """, (row, index) -> row.getLong(1), operation.actor(), operation.owner());
        if (stale(operation)) {
            return true;
        }

        Long actor = store.resolve("users", operation.actor());
        Long target = store.resolve(operation.kind().targetTable, operation.target());
        if (target == null) target = CatalogTerminalReference.registeredNumericId(jdbc,
                operation.kind().targetTable, operation.target());
        Long owner = store.resolve("users", operation.owner());
        if (actor == null || target == null || owner == null) return false;
        if (!(operation.adding() ? consent.replicationAllowed(actor) && consent.replicationAllowed(owner)
                : consent.removalReplicationAllowed(actor) && consent.removalReplicationAllowed(owner))) {
            return false;
        }
        if (consent.get(actor).epoch() != operation.actorEpoch()
                || consent.get(owner).epoch() != operation.ownerEpoch()) {
            return false;
        }

        jdbc.query("SELECT id FROM public.users WHERE id IN (?, ?) ORDER BY id FOR KEY SHARE",
                (row, index) -> row.getLong(1), actor, owner);
        Long actualOwner = ownerId(operation.kind(), target);
        if (actualOwner != null && !actualOwner.equals(owner)) {
            throw new IllegalArgumentException("Relation owner mismatch");
        }
        Long tag = operation.tag() == null ? null : store.resolve("food_tags", operation.tag());
        if (tag == null && operation.tag() != null) {
            tag = CatalogTerminalReference.registeredNumericId(jdbc, "food_tags", operation.tag());
        }
        if (operation.tag() != null && (tag == null
                || !CatalogTerminalReference.tagReferenceValid(jdbc, operation.tag(), tag, operation.actor()))) return false;
        boolean terminal = CatalogTerminalReference.isTerminal(jdbc, operation.kind(), operation.target(), target,
                operation.owner(), operation.tag(), tag == null ? -1L : tag, operation.actor());
        if (store.resolve(operation.kind().targetTable, operation.target()) == null && !terminal) return false;
        if (actualOwner == null && !terminal) return false;
        if (!lockTarget(operation.kind(), target) && !terminal) return false;
        if (!terminal && operation.tag() != null && (tag == null || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM public.food_tags
                  WHERE id=? AND (tag_type='system' OR (tag_type='custom' AND owner_id=?))
                )
                """, Boolean.class, tag, actor)))) {
            return false;
        }

        store.lock(operation.kind(), actor, target, tag);
        // Check the original V1 predecessor before importing or recording any evidence. In
        // particular, a WAIT must not advance the V1 vector and make a missing predecessor
        // appear satisfied on retry.
        if (!store.satisfied(operation)) return false;
        if (terminal) {
            RelationCausalStore.Identity terminalIdentity = new RelationCausalStore.Identity(
                    RelationStore.naturalId(operation.kind(), operation.actor(), operation.target(),
                            operation.owner(), operation.tag()), operation.kind(), operation.actor(),
                    operation.target(), operation.owner(), operation.tag(), operation.actorEpoch(),
                    operation.ownerEpoch(), operation.rowId(), operation.rowGid(), operation.rowOrigin());
            causal.ensure(terminalIdentity, false, hasUnpublishedLocalIntent(terminalIdentity.naturalId()), true);
            RelationCausalStore.State archived = causal.prepareLegacy(event.eventId(), operation);
            return archived.blockedReason() == null || "MULTI_LEGACY".equals(archived.blockedReason());
        }
        java.util.UUID naturalId = RelationStore.naturalId(operation.kind(), operation.actor(),
                operation.target(), operation.owner(), operation.tag());
        RelationCausalStore.Identity identity = new RelationCausalStore.Identity(naturalId, operation.kind(),
                operation.actor(), operation.target(), operation.owner(), operation.tag(), operation.actorEpoch(),
                operation.ownerEpoch(), operation.rowId(), operation.rowGid(), operation.rowOrigin());
        RelationCausalStore.State causalState = causal.ensure(identity,
                store.present(operation.kind(), actor, target, tag), hasUnpublishedLocalIntent(naturalId), true);
        if (causalState.blockedReason() != null
                && !"MULTI_LEGACY".equals(causalState.blockedReason())) return false;
        causalState = causal.prepareLegacy(event.eventId(), operation);
        return causalState.blockedReason() == null;
    }

    @Override
    public void apply(SyncEvent event) {
        RelationEvents.Operation operation = operation(event);
        if (stale(operation)) {
            return;
        }

        long actor = store.resolve("users", operation.actor());
        Long resolvedTarget = store.resolve(operation.kind().targetTable, operation.target());
        if (resolvedTarget == null) resolvedTarget = CatalogTerminalReference.registeredNumericId(
                jdbc, operation.kind().targetTable, operation.target());
        if (resolvedTarget == null) throw new IllegalStateException("CATALOG_RELATION_TARGET_DISAPPEARED");
        long target = resolvedTarget;
        Long tag = operation.tag() == null ? null : store.resolve("food_tags", operation.tag());
        if (tag == null && operation.tag() != null) {
            tag = CatalogTerminalReference.registeredNumericId(jdbc, "food_tags", operation.tag());
        }
        if (operation.tag() != null && (tag == null
                || !CatalogTerminalReference.tagReferenceValid(jdbc, operation.tag(), tag, operation.actor()))) {
            throw new IllegalStateException("CATALOG_RELATION_TAG_DISAPPEARED");
        }
        Long actualOwner = ownerId(operation.kind(), target);
        boolean terminal = CatalogTerminalReference.isTerminal(jdbc, operation.kind(), operation.target(), target,
                operation.owner(), operation.tag(), tag == null ? -1L : tag, operation.actor());
        if (!terminal && (!publicTargetExists(operation.kind().targetTable, target)
                || (operation.tag() != null && (tag == null || !publicTargetExists("food_tags", tag))))) {
            throw new IllegalStateException("CATALOG_RELATION_TARGET_DISAPPEARED");
        }
        if (terminal) {
            store.lock(operation.kind(), actor, target, tag);
            store.merge(event.eventId(), operation);
            causal.mergeLegacy(operation, event.eventId());
            return;
        }
        java.util.UUID naturalId = RelationStore.naturalId(operation.kind(), operation.actor(), operation.target(),
                operation.owner(), operation.tag());
        RelationStore.Intent priorIntent = store.readIntent(naturalId).orElse(null);
        boolean pendingLocalIntent = priorIntent != null && priorIntent.localIntent()
                && priorIntent.publishedRevision() < priorIntent.revision();
        RelationCausalStore.Identity identity = new RelationCausalStore.Identity(naturalId, operation.kind(),
                operation.actor(), operation.target(), operation.owner(), operation.tag(), operation.actorEpoch(),
                operation.ownerEpoch(), operation.rowId(), operation.rowGid(), operation.rowOrigin());
        RelationCausalStore.State before = causal.ensure(identity,
                store.present(operation.kind(), actor, target, tag), pendingLocalIntent, true);
        if (before.blockedReason() != null) return;
        store.merge(event.eventId(), operation);
        RelationCausalStore.State causalState = causal.mergeLegacy(operation, event.eventId());
        if (causalState.blockedReason() != null) return;
        boolean present = causalState.present();
        boolean projectionPresent = pendingLocalIntent ? priorIntent.desiredPresent() : present;
        RelationStore.Projection projection = store.project(operation.kind(), actor, target, tag, projectionPresent,
                operation, event.originSite());
        store.markObserved(operation.kind(), operation.actor(), operation.target(), operation.owner(),
                operation.tag(), projection, present, operation.actorEpoch(), operation.ownerEpoch());
        if (operation.notification() != null) {
            store.notification(event.eventId(), actor,
                    operation.kind() == RelationKind.follow ? actor : target,
                    operation.notification(), event.originSite());
        }
    }

    private boolean hasUnpublishedLocalIntent(java.util.UUID naturalId) {
        RelationStore.Intent intent = store.readIntent(naturalId).orElse(null);
        return intent != null && intent.localIntent() && intent.publishedRevision() < intent.revision();
    }

    private Long ownerId(RelationKind kind, long target) {
        String table = kind == RelationKind.follow ? "users" : kind.targetTable;
        String column = kind == RelationKind.follow ? "id" : "account_id";
        return jdbc.query("SELECT " + column + " FROM public." + table + " WHERE id=?",
                        (row, index) -> row.getLong(1), target)
                .stream().findFirst().orElse(null);
    }

    private boolean publicTargetExists(String table, long target) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public." + table + " WHERE id=?)",
                Boolean.class, target));
    }

    private boolean lockTarget(RelationKind kind, long target) {
        if (kind == RelationKind.follow) {
            return !jdbc.query("SELECT id FROM public.users WHERE id=? FOR KEY SHARE",
                    (row, index) -> row.getLong(1), target).isEmpty();
        }
        return !jdbc.query("SELECT id FROM public." + kind.targetTable + " WHERE id=? FOR UPDATE",
                        (row, index) -> row.getLong(1), target)
                .isEmpty();
    }
}
