package you.v50to.eatwhat.sync.relation;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.function.Supplier;

/** Domain commands for relation writes. Projection, notification, facts and Outbox share one transaction. */
public final class RelationCommands {
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final RelationStore store;
    private final RelationCausalStore causal;
    private final OutboxService outbox;
    private final EntityIdentityService identities;
    private final ConsentStore consent;
    private final SiteProperties site;
    private final String peer;
    private final RelationEvents eventPolicy = new RelationEvents();

    public RelationCommands(JdbcTemplate jdbc, SyncTransactions transactions, RelationStore store,
                            RelationCausalStore causal,
                            OutboxService outbox, EntityIdentityService identities, ConsentStore consent,
                            SiteProperties site, String peer) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.store = store;
        this.causal = causal;
        this.outbox = outbox;
        this.identities = identities;
        this.consent = consent;
        this.site = site;
        this.peer = peer;
    }

    /** Compatibility constructor for direct test fixtures; production wiring supplies the shared causal store. */
    public RelationCommands(JdbcTemplate jdbc, SyncTransactions transactions, RelationStore store,
                            OutboxService outbox, EntityIdentityService identities, ConsentStore consent,
                            SiteProperties site, String peer) {
        this(jdbc, transactions, store, new RelationCausalStore(jdbc), outbox, identities, consent, site, peer);
    }

    /** Execute a multi-relation operation atomically when relation sync is enabled. */
    public <T> T atomic(Supplier<T> action) {
        return transactions.required(action);
    }

    /** Acquire consent before a batch writes rows carrying a user foreign key. */
    public void lockConsentSubjects(long... userIds) {
        if (userIds == null || userIds.length == 0) return;
        long[] ordered = userIds.clone();
        Arrays.sort(ordered);
        String placeholders = Arrays.stream(ordered).mapToObj(ignored -> "?").collect(Collectors.joining(","));
        Object[] arguments = Arrays.stream(ordered).boxed().toArray();
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE user_id IN (" + placeholders
                        + ") ORDER BY user_id FOR SHARE",
                (row, index) -> row.getLong(1), arguments);
    }

    public boolean change(RelationKind kind, long actor, long target, Long tag, boolean adding) {
        return transactions.required(() -> changeInTransaction(kind, actor, target, tag, adding));
    }

    /** Publishes one persisted local intent without allocating a notice. */
    public boolean publishPendingIntent(UUID naturalId) {
        return transactions.required(() -> publishPendingIntentInTransaction(naturalId));
    }

    /** Seeds one current business row then publishes its persisted intent in this same transaction. */
    public boolean backfillCandidate(RelationKind kind, long actor, long target, Long owner, Long tag,
                                     long rowId, boolean desiredPresent) {
        return transactions.required(() -> {
            if (owner == null || (kind == RelationKind.follow && actor == target)) return false;
            lockConsentSubjects(actor, owner);
            jdbc.query("SELECT id FROM public.users WHERE id IN (?, ?) ORDER BY id FOR KEY SHARE",
                    (row, index) -> row.getLong(1), actor, owner);
            if (lockTarget(kind, target) == null) return false;
            if (tag != null && !tagOwnedByActor(tag, actor)) return false;
            store.lock(kind, actor, target, tag);
            UUID actorGid = globalId("users", actor);
            UUID targetGid = globalId(kind.targetTable, target);
            UUID ownerGid = globalId("users", owner);
            UUID tagGid = tag == null ? null : globalId("food_tags", tag);
            UUID naturalId = RelationStore.naturalId(kind, actorGid, targetGid, ownerGid, tagGid);
            RelationStore.Intent existing = store.readIntent(naturalId).orElse(null);
            if (existing != null && store.blocked(naturalId)) return true;
            if (existing != null) {
                boolean published = publishPendingIntentInTransaction(naturalId);
                return published || !consent.replicationAllowed(actor) || !consent.replicationAllowed(owner);
            }
            if (!sourceRelationExists(kind, rowId, actor, target, tag)
                    || store.present(kind, actor, target, tag) != desiredPresent) return true;
            RelationStore.Projection projection = store.project(kind, actor, target, tag, desiredPresent, null, site.getId());
            RelationStore.Intent intent = store.seedIntent(kind, actorGid, targetGid, ownerGid, tagGid,
                    desiredPresent, projection);
            // Existing explicit intent wins over a scan snapshot, including an unpublished REMOVE.
            boolean published = publishPendingIntentInTransaction(intent.naturalId());
            if (!published && (!consent.replicationAllowed(actor) || !consent.replicationAllowed(owner))) {
                return true; // durable intent remains pending; this candidate can advance without starvation
            }
            return published;
        });
    }

    private boolean sourceRelationExists(RelationKind kind, long rowId, long actor, long target, Long tag) {
        String sql = "SELECT EXISTS(SELECT 1 FROM public." + kind.table + " WHERE id=? AND account_id=? AND "
                + kind.targetColumn + "=?" + (kind.targetType == null ? "" : " AND target_type=?")
                + (tag == null ? "" : " AND tag_id=?") + ")";
        if (kind.targetType != null && tag != null) {
            return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, rowId, actor, target, kind.targetType, tag));
        }
        if (kind.targetType != null) return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, rowId, actor, target, kind.targetType));
        if (tag != null) return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, rowId, actor, target, tag));
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, rowId, actor, target));
    }

    private boolean publishPendingIntentInTransaction(UUID naturalId) {
        if (store.blocked(naturalId)) return true;
        RelationStore.Intent intent = store.readIntent(naturalId).orElse(null);
        if (intent == null) return true;
        Long actor = identities.findNumericId(EntityType.USERS, intent.actor()).orElse(null);
        Long target = identities.findNumericId(EntityType.fromTableName(intent.kind().targetTable), intent.target()).orElse(null);
        Long owner = identities.findNumericId(EntityType.USERS, intent.owner()).orElse(null);
        Long tag = intent.tag() == null ? null : identities.findNumericId(EntityType.FOOD_TAGS, intent.tag()).orElse(null);
        if (actor == null || target == null || owner == null || (intent.tag() != null && tag == null)) return false;
        lockConsentSubjects(actor, owner);

        jdbc.query("SELECT id FROM public.users WHERE id IN (?, ?) ORDER BY id FOR KEY SHARE",
                (row, index) -> row.getLong(1), actor, owner);
        boolean activePublicationAllowed = consent.replicationAllowed(actor) && consent.replicationAllowed(owner);
        if (!activePublicationAllowed && (intent.desiredPresent()
                || !consent.removalReplicationAllowed(actor) || !consent.removalReplicationAllowed(owner))) return false;
        if (lockTarget(intent.kind(), target) == null) return false;
        store.lock(intent.kind(), actor, target, tag);
        intent = store.readIntent(naturalId).orElse(null);
        if (intent == null) return true;
        var actorConsent = consent.get(actor);
        var ownerConsent = consent.get(owner);
        if (actorConsent == null || ownerConsent == null || !actorConsent.identityReady() || !ownerConsent.identityReady()) return false;
        if (!intent.needsPublish(actorConsent.epoch(), ownerConsent.epoch())) return true;

        RelationStore.Projection projection = store.project(intent.kind(), actor, target, tag,
                store.present(intent.kind(), actor, target, tag), null, site.getId());
        RelationCausalStore.Identity identity = identity(intent, actorConsent.epoch(), ownerConsent.epoch(), projection);
        boolean pending = intent.localIntent() && intent.publishedRevision() < intent.revision();
        RelationCausalStore.State state = causal.ensure(identity,
                store.present(intent.kind(), actor, target, tag), pending);
        if (state.blockedReason() != null) {
            store.blockIntent(naturalId, state.blockedReason());
            return true;
        }
        boolean effective = state.present();
        // A historical REMOVE intent cannot authorize a bridge containing a later active ADD.
        if (!activePublicationAllowed && !RelationEvents.removalOnly(causal.bridge(state))) return false;
        store.project(intent.kind(), actor, target, tag, effective, null, site.getId());
        UUID eventId = UUID.randomUUID();
        appendCausalEvent(eventId, RelationCausalEvents.BRIDGE, intent.actor(),
                RelationCausalEvents.actionDependencies(intent.kind(), intent.actor(), intent.target(),
                        intent.owner(), intent.tag()), RelationCausalEvents.payload(causal.bridge(state)));
        store.markPublished(intent, actorConsent.epoch(), ownerConsent.epoch(), effective);
        return true;
    }

    private boolean changeInTransaction(RelationKind kind, long actor, long target, Long tag, boolean adding) {
        Long owner = ownerId(kind, target);
        if (owner == null || (kind == RelationKind.follow && actor == target)) {
            return false;
        }

        // Withdrawal takes an exclusive subject-row lock. Always lock both subjects by ID first.
        lockConsentSubjects(actor, owner);

        // A dispute suppresses publication, not the stored object or the user's ability to undo a relation.
        // Match catalog's consent -> domain -> row lock order to avoid a concurrent resolution race.
        if (adding && kind.targetTable.equals("foods") && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('eatwhat_sync.catalog_projection_state') IS NOT NULL", Boolean.class))) {
            new you.v50to.eatwhat.sync.catalog.CatalogStore(jdbc).lockDomain();
            var foodKind = you.v50to.eatwhat.sync.catalog.CatalogKind.FOOD;
            if (!you.v50to.eatwhat.sync.catalog.CatalogVisibility.visible(jdbc, foodKind, target)
                    || (tag != null && (!you.v50to.eatwhat.sync.catalog.CatalogVisibility.visible(jdbc,
                            you.v50to.eatwhat.sync.catalog.CatalogKind.SYSTEM_TAG, tag)
                        || !you.v50to.eatwhat.sync.catalog.CatalogVisibility.visible(jdbc,
                            you.v50to.eatwhat.sync.catalog.CatalogKind.CUSTOM_TAG, tag)))) {
                throw new BizException(BizCode.DATA_SYNC_PENDING, "内容存在待处理的同步冲突");
            }
        }

        // Keep the dependency alive through projection. These locks also serialize with soft/hard delete.
        jdbc.query("SELECT id FROM public.users WHERE id IN (?, ?) ORDER BY id FOR KEY SHARE",
                (row, index) -> row.getLong(1), actor, owner);
        if (lockTarget(kind, target) == null) {
            return false;
        }
        if (kind == RelationKind.tagging && !tagOwnedByActor(tag, actor)) {
            return false;
        }

        store.lock(kind, actor, target, tag);
        boolean wasPresent = store.present(kind, actor, target, tag);
        if (!adding && kind == RelationKind.tagging && !wasPresent) {
            return false;
        }
        // Resolve the stable row identity against the pre-command projection first.
        RelationStore.Projection priorProjection = store.project(kind, actor, target, tag, wasPresent, null,
                site.getId());
        UUID actorGid = globalId("users", actor);
        UUID targetGid = globalId(kind.targetTable, target);
        UUID ownerGid = globalId("users", owner);
        UUID tagGid = tag == null ? null : globalId("food_tags", tag);
        var actorConsent = consent.get(actor);
        var ownerConsent = consent.get(owner);
        long actorEpoch = actorConsent == null ? 0 : actorConsent.epoch();
        long ownerEpoch = ownerConsent == null ? 0 : ownerConsent.epoch();
        UUID naturalId = RelationStore.naturalId(kind, actorGid, targetGid, ownerGid, tagGid);
        RelationCausalStore.Identity causalIdentity = new RelationCausalStore.Identity(naturalId, kind, actorGid,
                targetGid, ownerGid, tagGid, actorEpoch, ownerEpoch, priorProjection.rowId(),
                priorProjection.rowGid(), priorProjection.rowOrigin());
        RelationCausalStore.State before = causal.ensure(causalIdentity, wasPresent,
                store.readIntent(naturalId).map(i -> i.localIntent() && i.publishedRevision() < i.revision())
                        .orElse(false));
        String nativeStream = RelationCausalEvents.nativeStream(site.getId());
        boolean firstAbsentRemove = !adding && !wasPresent && before.removes().stream()
                .noneMatch(dot -> dot.stream().equals(nativeStream));
        boolean ambiguousHistory = "LOCAL_CONTEXT_UNKNOWN".equals(before.blockedReason())
                || "MULTI_LEGACY".equals(before.blockedReason());
        // A repeated explicit REMOVE is normally a no-op. While legacy state is blocked,
        // however, it is a real user confirmation whose context includes the full known state.
        // Tagging's missing-row API failure returned above and therefore never reaches here.
        boolean resolvesAmbiguousHistory = ambiguousHistory && (!adding || wasPresent);
        boolean actionRequired = wasPresent != adding || before.present() != adding || firstAbsentRemove
                || resolvesAmbiguousHistory;
        boolean authorized = actorConsent != null && ownerConsent != null
                && actorConsent.identityReady() && ownerConsent.identityReady()
                && consent.replicationAllowed(actor) && consent.replicationAllowed(owner);
        RelationStore.Projection projection = priorProjection;
        if (!actionRequired) {
            if (before.blockedReason() != null) {
                store.blockIntent(naturalId, before.blockedReason());
            } else if (wasPresent && authorized) {
                RelationStore.Intent seed = store.seedIntent(kind, actorGid, targetGid, ownerGid, tagGid,
                        before.present(), projection);
                if (seed.needsPublish(actorEpoch, ownerEpoch)) publishPendingIntentInTransaction(naturalId);
            }
            return wasPresent != adding;
        }
        if (!causal.canCaptureLocal(before, nativeStream, adding)) {
            throw new BizException(BizCode.DATA_SYNC_PENDING);
        }
        projection = store.project(kind, actor, target, tag, adding, null, site.getId());
        RelationStore.Intent intent = store.recordLocalIntent(kind, actorGid, targetGid, ownerGid, tagGid,
                adding, projection);

        UUID eventId = UUID.randomUUID();
        RelationEvents.Notice localNotification = adding && !wasPresent
                ? createNotification(kind, actor, owner, target) : null;
        // Keep the pre-sync local notification behavior. An unconsented action stores no V1
        // notice proof and emits no event; a future state bridge never recreates this notice.
        RelationEvents.Notice notification = authorized ? localNotification : null;
        RelationEvents.Operation prototype = new RelationEvents.Operation(
                kind, actorGid, targetGid, ownerGid, tagGid, Math.max(1, actorEpoch), Math.max(1, ownerEpoch),
                projection.rowId(), projection.rowGid(), projection.rowOrigin(), Map.of(), site.getId(), 1,
                adding, notification);
        VersionVector current = store.state(prototype.relationId());
        RelationEvents.Operation operation = new RelationEvents.Operation(
                prototype.kind(), prototype.actor(), prototype.target(), prototype.owner(), prototype.tag(),
                prototype.actorEpoch(), prototype.ownerEpoch(), prototype.rowId(), prototype.rowGid(),
                prototype.rowOrigin(), current.counters(), site.getId(),
                current.counters().getOrDefault(site.getId(), 0L) + 1, adding, notification);
        if (authorized && !causal.canCaptureLocal(causalIdentity, before, nativeStream, adding, eventId,
                operation)) {
            throw new BizException(BizCode.DATA_SYNC_PENDING);
        }
        RelationCausalEvents.Action action = causal.captureLocal(causalIdentity, before, nativeStream, eventId,
                adding, operation);
        causal.recordV1Evidence(eventId, operation);
        boolean present = causal.load(naturalId).present();
        store.project(kind, actor, target, tag, present, operation, site.getId());
        if (authorized) {
            if (notification != null) store.notification(eventId, actor,
                    kind == RelationKind.follow ? actor : target, notification, site.getId());
            appendCausalEvent(eventId, RelationCausalEvents.ACTION, actorGid, operation.dependencies(),
                    RelationCausalEvents.payload(action));
            store.markPublished(intent, actorEpoch, ownerEpoch, present);
        }
        return wasPresent != adding;
    }

    private RelationCausalStore.Identity identity(RelationStore.Intent intent, long actorEpoch, long ownerEpoch,
                                                   RelationStore.Projection projection) {
        return new RelationCausalStore.Identity(intent.naturalId(), intent.kind(), intent.actor(), intent.target(),
                intent.owner(), intent.tag(), actorEpoch, ownerEpoch, projection.rowId(), projection.rowGid(),
                projection.rowOrigin());
    }

    private void appendCausalEvent(UUID eventId, String eventType, UUID actor,
                                   List<SyncEvent.Dependency> dependencies, String payload) {
        long number = jdbc.queryForObject("SELECT eatwhat_sync.next_relation_number()", Long.class);
        SyncEvent event = new SyncEvent(eventId, site.getId(), site.getInstanceId(), RelationEvents.AGGREGATE,
                eventId, number, eventType, RelationCausalEvents.SCHEMA_VERSION, VersionVector.empty(),
                VersionVector.empty().increment(site.getId()), dependencies, actor,
                "relation:" + eventId, eventId, payload);
        eventPolicy.validate(event, new NodeIdentity(site.getId(), site.getInstanceId()));
        outbox.append(event, you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peer));
    }

    private Long ownerId(RelationKind kind, long target) {
        if (kind == RelationKind.follow) {
            return jdbc.query("SELECT id FROM public.users WHERE id=?", (row, index) -> row.getLong(1), target)
                    .stream().findFirst().orElse(null);
        }
        return jdbc.query("SELECT account_id FROM public." + kind.targetTable + " WHERE id=?",
                        (row, index) -> row.getLong(1), target)
                .stream().findFirst().orElse(null);
    }

    private Long lockTarget(RelationKind kind, long target) {
        String table = kind.targetTable;
        if (kind == RelationKind.follow) {
            return jdbc.query("SELECT id FROM public.users WHERE id=? FOR KEY SHARE",
                            (row, index) -> row.getLong(1), target)
                    .stream().findFirst().orElse(null);
        }
        String activeFilter = table.equals("activity_foods") || table.equals("activity_dinners") || table.equals("activity_posts")
                ? " AND deleted_at IS NULL" : "";
        return jdbc.query("SELECT id FROM public." + table + " WHERE id=?" + activeFilter + " FOR UPDATE",
                        (row, index) -> row.getLong(1), target)
                .stream().findFirst().orElse(null);
    }

    private boolean tagOwnedByActor(Long tag, long actor) {
        if (tag == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM public.food_tags
                  WHERE id=? AND (tag_type='system' OR (tag_type='custom' AND owner_id=?))
                )
                """, Boolean.class, tag, actor));
    }

    private UUID globalId(String table, long id) {
        return identities.findGlobalId(EntityType.fromTableName(table), id).orElseThrow();
    }

    private RelationEvents.Notice createNotification(RelationKind kind, long actor, long owner, long target) {
        if (actor == owner || !(kind == RelationKind.follow
                || kind == RelationKind.activity_food_like || kind == RelationKind.activity_dinner_like
                || kind == RelationKind.activity_post_like)) {
            return null;
        }
        String notificationType = kind == RelationKind.follow ? "follow" : "like";
        String targetType = kind == RelationKind.follow ? "user" : kind.targetType;
        long notificationTargetId = kind == RelationKind.follow ? actor : target;
        long createdAt = jdbc.queryForObject("SELECT (extract(epoch FROM clock_timestamp())*1000)::bigint",
                Long.class);
        long expiresAt = createdAt + Duration.ofDays(30).toMillis();
        String data = kind == RelationKind.follow ? "{}"
                : "{\"targetType\":\"" + targetType + "\",\"targetId\":" + target + "}";
        long id = jdbc.queryForObject("""
                INSERT INTO public.notifications(account_id, actor_id, type, target_type, target_id,
                                                 data, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, to_timestamp(? / 1000.0), to_timestamp(? / 1000.0))
                RETURNING id
                """, Long.class, owner, actor, notificationType, targetType, notificationTargetId,
                data, createdAt, expiresAt);
        return new RelationEvents.Notice(id, globalId("notifications", id), globalId("users", owner),
                site.getId(), notificationType, targetType, createdAt, expiresAt);
    }
}
