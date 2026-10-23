package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.catalog.*;
import you.v50to.eatwhat.sync.relation.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL terminal-catalog checks against the relation event handlers. */
class CatalogTerminalRelationIT {
    private static final Duration LEASE = Duration.ofSeconds(20);

    @BeforeAll
    static void startPostgres() {
        if (!RelationReplicationIT.PG.isRunning()) RelationReplicationIT.PG.start();
    }

    @Test
    void aHeldFoodLikeActionCannotRecreateProjectionAfterCatalogDelete() throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
        InboxService receiver = inboxWithCatalog(pair.b(), pair.a());
        promoteCampusAdmin(pair, pair.owner());

        RelationReplicationIT.change(pair, pair.a(), RelationKind.food_like, true);
        SyncEvent heldAction = RelationReplicationIT.events(pair.a()).getFirst();
        assertEquals(RelationCausalEvents.ACTION, heldAction.eventType());
        RelationReplicationIT.change(pair, pair.a(), RelationKind.favorite, true);
        SyncEvent favoriteAction = RelationReplicationIT.events(pair.a()).stream()
                .filter(event -> RelationReplicationIT.v1Evidence(event).kind() == RelationKind.favorite)
                .findFirst().orElseThrow();
        SyncEvent heldV1 = RelationReplicationIT.legacyV1Wire(pair.a(),
                RelationReplicationIT.v1Evidence(favoriteAction));

        CatalogCommands commands = catalogCommands(pair.a(), pair.b());
        var deleted = commands.delete(CatalogKind.FOOD, pair.food(), pair.owner(),
                () -> pair.a().db().runtime().update("DELETE FROM foods WHERE id=?", pair.food()),
                rows -> rows == 1);
        assertEquals(CatalogCommands.Status.APPLIED, deleted.status());

        var catalogDelivery = pair.a().delivery().claimForAggregates(pair.b().db().site().getId(), 100, LEASE,
                Set.of(CatalogEvents.AGGREGATE));
        assertFalse(catalogDelivery.isEmpty());
        for (var lease : catalogDelivery) {
            receiver.receive(lease.event(), pair.a().db().site().getId());
            assertTrue(pair.a().delivery().acknowledge(lease, "DURABLY_QUEUED"));
        }
        process(receiver);
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM foods WHERE id=?", pair.food()));

        new RelationEvents().validate(heldAction,
                new NodeIdentity(heldAction.originSite(), heldAction.originInstanceId()));
        receiver.receive(heldAction, heldAction.originSite());
        receiver.receive(heldV1, heldV1.originSite());
        process(receiver);
        assertEquals("APPLIED", inboxStatus(pair.b(), heldAction), inboxDiagnostic(pair.b(), heldAction));
        assertEquals("APPLIED", inboxStatus(pair.b(), heldV1), inboxDiagnostic(pair.b(), heldV1));

        assertEquals(1, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.relation_operation WHERE event_id=?",
                heldAction.eventId()), "Terminal suppression must retain the incoming causal fact");
        RelationCausalEvents.Action causalEvidence = RelationCausalEvents.parseAction(heldAction.payloadJson());
        assertEquals(1, RelationReplicationIT.count(pair.b(), """
                SELECT count(*) FROM eatwhat_sync.relation_causal_fact
                WHERE event_id=? AND stream_key=? AND dot_counter=? AND adding=true
                """, heldAction.eventId(), causalEvidence.stream(), causalEvidence.counter()),
                "The original V2 ADD dot is retained without synthesizing a REMOVE");
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM likes WHERE account_id=? AND target_type='food' AND target_id=? AND deleted_at IS NULL",
                pair.actor(), pair.food()), "A terminal target must not regain a public relation row");
        assertEquals(1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation WHERE event_id=?", heldV1.eventId()),
                "A held V1 operation remains immutable evidence after terminal suppression");
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM food_favorites WHERE account_id=? AND food_id=?",
                pair.actor(), pair.food()));
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?",
                RelationEvents.AGGREGATE), "Receiving a fact must not synthesize a public REMOVE or notification event");
        assertEquals(0, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM eatwhat_sync.relation_notification"),
                "Terminal suppression must not create relation notifications");
    }

    @Test
    void aHeldCausalBridgeCannotRestoreADeletedFoodRelation() throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
        InboxService receiver = inboxWithCatalog(pair.b(), pair.a());
        RelationReplicationIT.change(pair, pair.a(), RelationKind.food_like, true);
        SyncEvent action = RelationReplicationIT.events(pair.a()).getFirst();
        RelationCausalEvents.Action causalAction = RelationCausalEvents.parseAction(action.payloadJson());
        SyncEvent heldBridge = bridgeEvent(pair.a(), causalAction);

        deleteFood(pair);
        deliverCatalog(pair, receiver);
        receiver.receive(heldBridge, heldBridge.originSite());
        process(receiver);
        assertEquals("APPLIED", inboxStatus(pair.b(), heldBridge), inboxDiagnostic(pair.b(), heldBridge));

        assertEquals(1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE event_id=? AND adding=true",
                action.eventId()));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM likes WHERE account_id=? AND target_type='food' AND target_id=? AND deleted_at IS NULL",
                pair.actor(), pair.food()));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.relation_notification"));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?", RelationEvents.AGGREGATE));
    }

    @Test
    void terminalSystemAndCustomTagsDoNotRestoreHeldTaggingActions() throws Exception {
        for (boolean custom : List.of(false, true)) {
            RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
            InboxService receiver = inboxWithCatalog(pair.b(), pair.a());
            promoteCampusAdmin(pair, pair.owner());
            long tag = pair.tag();
            CatalogKind catalogKind = CatalogKind.SYSTEM_TAG;
            if (custom) {
                tag = pair.a().db().runtime().queryForObject("""
                        INSERT INTO food_tags(name,normalized_name,tag_type,owner_id)
                        VALUES('terminal custom','terminal custom','custom',?) RETURNING id
                        """, Long.class, pair.owner());
                RelationReplicationIT.mirror(pair.a().db(), pair.b().db(), "food_tags", tag);
                catalogKind = CatalogKind.CUSTOM_TAG;
            }
            long relationActor = custom ? pair.owner() : pair.actor();
            assertTrue(pair.a().commands().change(RelationKind.tagging, relationActor, pair.food(), tag, true));
            SyncEvent heldAction = RelationReplicationIT.events(pair.a()).getFirst();
            assertEquals(RelationCausalEvents.ACTION, heldAction.eventType());

            long terminalTag = tag;
            CatalogKind terminalKind = catalogKind;
            CatalogCommands commands = catalogCommands(pair.a(), pair.b());
            var deleted = custom
                    ? commands.mutateCustomTag(terminalTag, pair.owner(), CatalogOperation.DELETE, Map.of(),
                            () -> pair.a().db().runtime().update(
                                    "DELETE FROM food_tags WHERE id=? AND owner_id=?", terminalTag, pair.owner()),
                            rows -> rows == 1)
                    : commands.delete(terminalKind, terminalTag, pair.owner(),
                            () -> pair.a().db().runtime().update("DELETE FROM food_tags WHERE id=?", terminalTag),
                            rows -> rows == 1);
            assertEquals(CatalogCommands.Status.APPLIED, deleted.status());
            deliverCatalog(pair, receiver);
            receiver.receive(heldAction, heldAction.originSite());
            process(receiver);
            assertEquals("APPLIED", inboxStatus(pair.b(), heldAction), inboxDiagnostic(pair.b(), heldAction));

            RelationEvents.Operation evidence = RelationReplicationIT.v1Evidence(heldAction);
            assertEquals(1, RelationReplicationIT.count(pair.b(),
                    "SELECT count(*) FROM eatwhat_sync.relation_operation WHERE event_id=? AND adding=true AND dot_site=? AND dot_counter=?",
                    heldAction.eventId(), evidence.dotSite(), evidence.dotCounter()),
                    "Terminal suppression retains the original V1 evidence");
            assertEquals(0, RelationReplicationIT.count(pair.b(),
                    "SELECT count(*) FROM food_taggings WHERE account_id=? AND food_id=? AND tag_id=?",
                    relationActor, pair.food(), terminalTag));
            assertEquals(0, RelationReplicationIT.count(pair.b(),
                    "SELECT count(*) FROM eatwhat_sync.relation_notification"));
            assertTrue(evidence.adding(), "The held fact remains the original ADD");
        }
    }

    @Test
    void terminalRestaurantDoesNotMintAChildFoodDeleteButSuppressesHeldFoodLike() throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
        InboxService receiver = inboxWithCatalog(pair.b(), pair.a());
        promoteCampusAdmin(pair, pair.owner());
        CatalogCommands commands = catalogCommands(pair.a(), pair.b());
        long restaurantId = pair.a().db().runtime().queryForObject(
                "SELECT restaurant_id FROM foods WHERE id=?", Long.class, pair.food());
        pair.a().db().admin().update(
                "UPDATE eatwhat_consent.subject SET identity_ready=true WHERE user_id=?", pair.owner());
        var subject = pair.a().consent().get(pair.owner());
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, commands.backfillCatalogObject(CatalogKind.RESTAURANT,
                restaurantId, subject.subjectId(), subject.epoch()));
        deliverCatalog(pair, receiver);
        final Long[] childId = new Long[1];
        var childCreated = commands.create(CatalogKind.FOOD, pair.owner(), restaurantId,
                Map.of("restaurantId", restaurantId, "name", "terminal tracked child"), () -> {
                    childId[0] = pair.a().db().runtime().queryForObject("""
                            INSERT INTO foods(account_id,restaurant_id,name,picture_url)
                            VALUES(?,?,?,ARRAY[]::text[]) RETURNING id
                            """, Long.class, pair.owner(), restaurantId, "terminal tracked child");
                    return childId[0];
                }, () -> childId[0], Objects::nonNull);
        assertEquals(CatalogCommands.Status.APPLIED, childCreated.status());
        long terminalFoodId = childCreated.value();
        deliverCatalog(pair, receiver);
        assertNotNull(new CatalogStore(pair.b().db().runtime()).findByNumeric(CatalogKind.FOOD, terminalFoodId),
                "The receiver needs the child identity and parent link to classify the terminal parent case");

        assertTrue(pair.a().commands().change(RelationKind.food_like, pair.actor(), terminalFoodId, null, true));
        SyncEvent heldAction = RelationReplicationIT.events(pair.a()).getFirst();
        var deleted = commands.delete(CatalogKind.RESTAURANT, restaurantId,
                pair.owner(), () -> pair.a().db().runtime().update("DELETE FROM restaurant WHERE id=(SELECT restaurant_id FROM foods WHERE id=?)",
                        terminalFoodId), rows -> rows == 1);
        assertEquals(CatalogCommands.Status.APPLIED, deleted.status());
        deliverCatalog(pair, receiver);
        receiver.receive(heldAction, heldAction.originSite());
        process(receiver);
        assertEquals("APPLIED", inboxStatus(pair.b(), heldAction), inboxDiagnostic(pair.b(), heldAction));

        CatalogStore.Identity child = new CatalogStore(pair.b().db().runtime())
                .findByNumeric(CatalogKind.FOOD, terminalFoodId);
        assertNotNull(child);
        assertFalse(child.tombstone(), "A parent DELETE must not fabricate a child FOOD tombstone");
        assertEquals(0, RelationReplicationIT.count(pair.b(), """
                SELECT count(*) FROM eatwhat_sync.catalog_revision
                WHERE kind='FOOD' AND operation='DELETE' AND numeric_id=?
                """, terminalFoodId));
        assertEquals(1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation WHERE event_id=?", heldAction.eventId()));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM likes WHERE account_id=? AND target_type='food' AND target_id=? AND deleted_at IS NULL",
                pair.actor(), terminalFoodId));

        // Regrant republishes the parent tombstone as a pure state bridge. It must not
        // manufacture a child delete or make the held relation visible again.
        pair.a().consent().withdraw(pair.owner());
        var purge = pair.a().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.owner() && job.kind().equals("PURGE"))
                .max((left, right) -> Long.compare(left.epoch(), right.epoch())).orElseThrow();
        assertEquals("APPLIED", pair.b().consent().apply(pair.a().consent().export(purge)));
        pair.a().consent().complete(purge);
        pair.a().consent().grant(pair.owner(), "relation-test-policy");
        var upsert = pair.a().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.owner() && job.kind().equals("UPSERT"))
                .max((left, right) -> Long.compare(left.epoch(), right.epoch())).orElseThrow();
        assertEquals("APPLIED", pair.b().consent().apply(pair.a().consent().export(upsert)));
        pair.a().consent().complete(upsert);
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, commands.backfillCatalogObject(
                CatalogKind.RESTAURANT, restaurantId, upsert.subjectId(), upsert.epoch()));
        var bridgeLeases = pair.a().delivery().claimForAggregates(pair.b().db().site().getId(), 100, LEASE,
                Set.of(CatalogEvents.AGGREGATE));
        var parentIdentity = new CatalogStore(pair.a().db().runtime())
                .findByNumeric(CatalogKind.RESTAURANT, restaurantId);
        SyncEvent parentBridge = bridgeLeases.stream().map(DeliveryService.Lease::event)
                .filter(event -> CatalogEvents.BRIDGE.equals(event.eventType()))
                .filter(event -> CatalogEvents.parseBridge(event.payloadJson()).objectGid().equals(parentIdentity.gid()))
                .findFirst().orElseThrow();
        for (var lease : bridgeLeases) {
            receiver.receive(lease.event(), pair.a().db().site().getId());
            assertTrue(pair.a().delivery().acknowledge(lease, "DURABLY_QUEUED"));
        }
        process(receiver);
        assertEquals("APPLIED", inboxStatus(pair.b(), parentBridge), inboxDiagnostic(pair.b(), parentBridge));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM likes WHERE account_id=? AND target_type='food' AND target_id=? AND deleted_at IS NULL",
                pair.actor(), terminalFoodId));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE kind='FOOD' AND operation='DELETE' AND numeric_id=?",
                terminalFoodId));
    }

    @Test
    void firstParentStateBridgeSeedsLegacyChildIdentityBeforeHeldFoodLikeArrives() throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
        InboxService receiver = inboxWithCatalog(pair.b(), pair.a());
        promoteCampusAdmin(pair, pair.owner());
        long restaurantId = pair.a().db().runtime().queryForObject(
                "SELECT restaurant_id FROM foods WHERE id=?", Long.class, pair.food());
        CatalogCommands commands = catalogCommands(pair.a(), pair.b());

        var registered = pair.a().consent().get(pair.owner());
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, commands.backfillCatalogObject(
                CatalogKind.RESTAURANT, restaurantId, registered.subjectId(), registered.epoch()));
        deliverCatalog(pair, receiver);
        assertNull(new CatalogStore(pair.b().db().runtime()).findByNumeric(CatalogKind.FOOD, pair.food()),
                "The peer child starts as a legacy row without a catalog ledger entry");

        RelationReplicationIT.change(pair, pair.a(), RelationKind.food_like, true);
        SyncEvent heldAction = RelationReplicationIT.events(pair.a()).getFirst();
        var deleted = commands.delete(CatalogKind.RESTAURANT, restaurantId, pair.owner(),
                () -> pair.a().db().runtime().update("DELETE FROM restaurant WHERE id=?", restaurantId),
                rows -> rows == 1);
        assertEquals(CatalogCommands.Status.APPLIED, deleted.status());
        assertEquals(1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM foods WHERE id=?", pair.food()),
                "No catalog event has reached the peer yet");

        pair.a().consent().withdraw(pair.owner());
        var purge = pair.a().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.owner() && job.kind().equals("PURGE"))
                .max((left, right) -> Long.compare(left.epoch(), right.epoch())).orElseThrow();
        assertEquals("APPLIED", pair.b().consent().apply(pair.a().consent().export(purge)));
        pair.a().consent().complete(purge);
        pair.a().consent().grant(pair.owner(), "relation-test-policy");
        var upsert = pair.a().consent().claim(32, LEASE).stream()
                .filter(job -> job.userId() == pair.owner() && job.kind().equals("UPSERT"))
                .max((left, right) -> Long.compare(left.epoch(), right.epoch())).orElseThrow();
        assertEquals("APPLIED", pair.b().consent().apply(pair.a().consent().export(upsert)));
        pair.a().consent().complete(upsert);
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, commands.backfillCatalogObject(
                CatalogKind.RESTAURANT, restaurantId, upsert.subjectId(), upsert.epoch()));

        var leases = pair.a().delivery().claimForAggregates(pair.b().db().site().getId(), 100, LEASE,
                Set.of(CatalogEvents.AGGREGATE));
        SyncEvent bridge = leases.stream().map(DeliveryService.Lease::event)
                .filter(event -> CatalogEvents.BRIDGE.equals(event.eventType()))
                .filter(event -> CatalogEvents.parseBridge(event.payloadJson()).numericId() == restaurantId)
                .findFirst().orElseThrow();
        for (var lease : leases) {
            SyncEvent event = lease.event();
            if (!event.eventId().equals(bridge.eventId())) continue;
            receiver.receive(event, pair.a().db().site().getId());
            assertTrue(pair.a().delivery().acknowledge(lease, "DURABLY_QUEUED"));
        }
        process(receiver);
        assertEquals("APPLIED", inboxStatus(pair.b(), bridge), inboxDiagnostic(pair.b(), bridge));
        assertNotNull(new CatalogStore(pair.b().db().runtime()).findByNumeric(CatalogKind.FOOD, pair.food()),
                "The bridge must preserve a stable child identity after the parent cascade");
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM public.foods WHERE id=?", pair.food()));

        receiver.receive(heldAction, heldAction.originSite());
        process(receiver);
        assertEquals("APPLIED", inboxStatus(pair.b(), heldAction), inboxDiagnostic(pair.b(), heldAction));
        assertEquals(0, RelationReplicationIT.count(pair.b(), """
                SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE event_id=?
                """, heldAction.eventId()), "The old-epoch action is acknowledged as a stale no-op");

        RelationCausalEvents.Action relation = RelationCausalEvents.parseAction(heldAction.payloadJson());
        long currentOwnerEpoch = pair.a().consent().get(pair.owner()).epoch();
        SyncEvent currentEpochBridge = bridgeEvent(pair.a(), relation, relation.actorEpoch(), currentOwnerEpoch);
        receiver.receive(currentEpochBridge, currentEpochBridge.originSite());
        process(receiver);
        assertEquals("APPLIED", inboxStatus(pair.b(), currentEpochBridge),
                inboxDiagnostic(pair.b(), currentEpochBridge));
        assertEquals(1, RelationReplicationIT.count(pair.b(), """
                SELECT count(*) FROM eatwhat_sync.relation_causal_fact
                WHERE event_id=? AND stream_key=? AND dot_counter=? AND adding=true
                """, heldAction.eventId(), relation.stream(), relation.counter()),
                "The fresh-epoch bridge carries the preserved original ADD fact");
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM likes WHERE account_id=? AND target_type='food' AND target_id=? AND deleted_at IS NULL",
                pair.actor(), pair.food()));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE kind='FOOD' AND operation='DELETE' AND numeric_id=?",
                pair.food()));
    }

    @ParameterizedTest
    @EnumSource(CatalogDecision.class)
    void keepOrRejectParentConflictSeedsLegacyChildrenOnBothSitesAndSuppressesHeldRelation(
            CatalogDecision decision) throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
        promoteCampusAdmin(pair, pair.owner());
        InboxService campusInbox = inboxWithCatalog(pair.a(), pair.b());
        InboxService externalInbox = inboxWithCatalog(pair.b(), pair.a());
        long restaurantId = pair.a().db().runtime().queryForObject(
                "SELECT restaurant_id FROM foods WHERE id=?", Long.class, pair.food());
        UUID restaurantGid = pair.a().db().runtime().queryForObject("""
                SELECT global_id FROM eatwhat_identity.entity_identity
                WHERE entity_type='restaurant' AND numeric_id=?
                """, UUID.class, restaurantId);
        UUID foodGid = pair.a().db().runtime().queryForObject("""
                SELECT global_id FROM eatwhat_identity.entity_identity
                WHERE entity_type='foods' AND numeric_id=?
                """, UUID.class, pair.food());
        assertNull(new CatalogStore(pair.a().db().runtime()).findObject(CatalogKind.FOOD, foodGid),
                "The existing child is deliberately a legacy public row with no catalog revision");
        assertNull(new CatalogStore(pair.b().db().runtime()).findObject(CatalogKind.FOOD, foodGid));

        String poi = "POI-legacy-child-conflict-" + decision.name().toLowerCase();
        CatalogCommands campusCommands = catalogCommands(pair.a(), pair.b());
        CatalogCommands externalCommands = catalogCommands(pair.b(), pair.a());
        var changed = campusCommands.update(CatalogKind.RESTAURANT, restaurantId, pair.owner(), current ->
                new CatalogCommands.UpdatePlan<>(null, Map.of("poi", poi),
                        () -> pair.a().db().runtime().update("UPDATE restaurant SET poi=? WHERE id=?",
                                poi, restaurantId), rows -> rows == 1));
        assertEquals(CatalogCommands.Status.APPLIED, changed.status());

        final Long[] competitorId = new Long[1];
        var competitor = externalCommands.create(CatalogKind.RESTAURANT, pair.owner(), null,
                Map.of("poi", poi), () -> {
                    competitorId[0] = pair.b().db().runtime().queryForObject("""
                            INSERT INTO restaurant(name,address,location,gcj_lng,gcj_lat,account_id,poi,picture_url)
                            VALUES('conflicting campus restaurant','Synthetic address',
                              ST_SetSRID(ST_MakePoint(117.0,36.0),4326)::geography,117.01,36.01,?,
                              ?,ARRAY['restaurants/catalog/legacy-conflict']) RETURNING id
                            """, Long.class, pair.owner(), poi);
                    return competitorId[0];
                }, () -> competitorId[0], Objects::nonNull);
        assertEquals(CatalogCommands.Status.APPLIED, competitor.status());

        deliverCatalogFrom(pair, pair.b(), pair.a(), campusInbox);
        deliverCatalogFrom(pair, pair.a(), pair.b(), externalInbox);
        CatalogStore campusStore = new CatalogStore(pair.a().db().runtime());
        CatalogStore externalStore = new CatalogStore(pair.b().db().runtime());
        CatalogStore.Identity campusCandidate = campusStore.findByNumeric(CatalogKind.RESTAURANT, restaurantId);
        CatalogStore.Identity externalCandidate = externalStore.findByNumeric(CatalogKind.RESTAURANT,
                competitor.value());
        assertNotNull(campusCandidate);
        assertNotNull(externalCandidate);

        assertTrue(pair.a().commands().change(RelationKind.food_like, pair.actor(), pair.food(), null, true));
        SyncEvent heldRelation = RelationReplicationIT.events(pair.a()).stream()
                .filter(event -> RelationCausalEvents.ACTION.equals(event.eventType()))
                .findFirst().orElseThrow();

        CatalogConflictService conflicts = new CatalogConflictService(pair.a().db().runtime(),
                pair.a().db().transactions(), campusStore, campusCommands, pair.a().db().site());
        CatalogConflictService.ConflictView conflict = conflicts.listOpenConflicts().stream()
                .filter(view -> view.kind() == CatalogKind.RESTAURANT && view.naturalKey().contains(poi))
                .findFirst().orElseThrow();
        var versions = conflict.candidates().stream().map(candidate ->
                new CatalogConflictService.CandidateVersion(candidate.gid(), candidate.revision(),
                        candidate.contentHash())).toList();
        UUID keptGid = decision == CatalogDecision.KEEP ? externalCandidate.gid() : null;
        var resolution = conflicts.resolve(new CatalogConflictService.ResolveRequest(conflict.conflictId(),
                conflict.revision(), versions, decision, keptGid,
                "Verify legacy child identity survives parent conflict resolution"), pair.owner());
        assertEquals(CatalogCommands.Status.APPLIED, resolution.status());
        deliverCatalogFrom(pair, pair.a(), pair.b(), externalInbox);

        for (RelationReplicationIT.Site site : List.of(pair.a(), pair.b())) {
            CatalogStore store = new CatalogStore(site.db().runtime());
            CatalogStore.Identity child = store.findByNumeric(CatalogKind.FOOD, pair.food());
            assertNotNull(child, "Both sides retain the legacy child identity after parent cascade");
            assertEquals(foodGid, child.gid());
            assertEquals(restaurantGid, child.parentGid());
            assertEquals(0, child.revision(), "Legacy child preservation must not fabricate a FOOD action");
            assertFalse(child.tombstone());
            assertEquals(0, RelationReplicationIT.count(site, "SELECT count(*) FROM public.foods WHERE id=?", pair.food()));
            assertEquals(0, RelationReplicationIT.count(site, """
                    SELECT count(*) FROM eatwhat_sync.catalog_revision
                    WHERE kind='FOOD' AND numeric_id=? AND operation='DELETE'
                    """, pair.food()));
        }

        pair.b().inbox().receive(heldRelation, heldRelation.originSite());
        process(pair.b().inbox());
        assertEquals("APPLIED", pair.b().db().runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, heldRelation.eventId()));
        assertEquals(1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.relation_causal_fact WHERE event_id=? AND adding=true",
                heldRelation.eventId()));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM likes WHERE account_id=? AND target_type='food' AND target_id=? AND deleted_at IS NULL",
                pair.actor(), pair.food()));
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.relation_notification"));
    }

    @Test
    void unknownTargetWaitsAndWrongOwnerIsRejectedBeforeCausalPersistence() throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
        RelationReplicationIT.change(pair, pair.a(), RelationKind.food_like, true);
        RelationEvents.Operation base = RelationReplicationIT.v1Evidence(
                RelationReplicationIT.events(pair.a()).getFirst());
        RelationEventHandler handler = new RelationEventHandler(pair.b().db().runtime(), pair.b().store(),
                new RelationCausalStore(pair.b().db().runtime()), pair.b().consent());

        RelationEvents.Operation unknownTarget = new RelationEvents.Operation(base.kind(), base.actor(),
                java.util.UUID.randomUUID(), base.owner(), base.tag(), base.actorEpoch(), base.ownerEpoch(),
                base.rowId(), base.rowGid(), base.rowOrigin(), base.context(), base.dotSite(), base.dotCounter(),
                base.adding(), base.notification());
        SyncEvent unknownEvent = RelationReplicationIT.legacyV1Wire(pair.a(), unknownTarget);
        new RelationEvents().validate(unknownEvent,
                new NodeIdentity(unknownEvent.originSite(), unknownEvent.originInstanceId()));
        assertFalse(handler.dependenciesSatisfied(unknownEvent), "An unregistered target remains waiting");

        RelationEvents.Operation unknownTag = new RelationEvents.Operation(RelationKind.tagging,
                base.actor(), base.target(), base.owner(), java.util.UUID.randomUUID(), base.actorEpoch(),
                base.ownerEpoch(), base.rowId(), base.rowGid(), base.rowOrigin(), base.context(),
                base.dotSite(), base.dotCounter(), true, null);
        SyncEvent unknownTagEvent = RelationReplicationIT.legacyV1Wire(pair.a(), unknownTag);
        new RelationEvents().validate(unknownTagEvent,
                new NodeIdentity(unknownTagEvent.originSite(), unknownTagEvent.originInstanceId()));
        assertFalse(handler.dependenciesSatisfied(unknownTagEvent),
                "A terminal food must not make an unknown tag identity eligible for causal persistence");

        RelationEvents.Operation wrongOwner = new RelationEvents.Operation(base.kind(), base.actor(), base.target(),
                base.actor(), base.tag(), base.actorEpoch(), base.actorEpoch(), base.rowId(), base.rowGid(),
                base.rowOrigin(), base.context(), base.dotSite(), base.dotCounter(), base.adding(), null);
        SyncEvent wrongOwnerEvent = RelationReplicationIT.legacyV1Wire(pair.a(), wrongOwner);
        assertThrows(IllegalArgumentException.class, () -> handler.dependenciesSatisfied(wrongOwnerEvent),
                "A known target cannot be authorized under a different owner");
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.relation_operation WHERE event_id IN (?,?)",
                unknownEvent.eventId(), wrongOwnerEvent.eventId()),
                "Neither invalid event may persist a relation fact");
        assertEquals(0, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type=?", RelationEvents.AGGREGATE));
    }

    private static SyncEvent bridgeEvent(RelationReplicationIT.Site origin,
                                         RelationCausalEvents.Action action) {
        return bridgeEvent(origin, action, action.actorEpoch(), action.ownerEpoch());
    }

    private static SyncEvent bridgeEvent(RelationReplicationIT.Site origin,
                                         RelationCausalEvents.Action action,
                                         long actorEpoch, long ownerEpoch) {
        String site = origin.db().site().getId();
        java.util.UUID id = java.util.UUID.randomUUID();
        long numericId = origin.db().runtime().queryForObject("SELECT eatwhat_sync.next_relation_number()", Long.class);
        RelationCausalEvents.Bridge old = action.state();
        RelationCausalEvents.Bridge state = new RelationCausalEvents.Bridge(old.naturalId(), old.kind(), old.actor(),
                old.target(), old.owner(), old.tag(), actorEpoch, ownerEpoch, old.rowId(), old.rowGid(),
                old.rowOrigin(), old.seen(), old.addWitnesses(), old.removeFrontier(), old.blockedReason());
        return new SyncEvent(id, site, origin.db().site().getInstanceId(), RelationEvents.AGGREGATE, id,
                numericId, RelationCausalEvents.BRIDGE, RelationCausalEvents.SCHEMA_VERSION,
                VersionVector.empty(), VersionVector.empty().increment(site), actionDependencies(action),
                action.actor(), "relation:" + id, id, RelationCausalEvents.payload(state));
    }

    private static List<SyncEvent.Dependency> actionDependencies(RelationCausalEvents.Action action) {
        return new RelationEvents.Operation(action.kind(), action.actor(), action.target(), action.owner(),
                action.tag(), action.actorEpoch(), action.ownerEpoch(), action.rowId(), action.rowGid(),
                action.rowOrigin(), action.context(), action.v1Evidence().dotSite(),
                action.v1Evidence().dotCounter(), action.adding(), action.v1Evidence().notification()).dependencies();
    }

    private static void deleteFood(RelationReplicationIT.Pair pair) {
        promoteCampusAdmin(pair, pair.owner());
        CatalogCommands commands = catalogCommands(pair.a(), pair.b());
        var deleted = commands.delete(CatalogKind.FOOD, pair.food(), pair.owner(),
                () -> pair.a().db().runtime().update("DELETE FROM foods WHERE id=?", pair.food()),
                rows -> rows == 1);
        assertEquals(CatalogCommands.Status.APPLIED, deleted.status());
    }

    private static void promoteCampusAdmin(RelationReplicationIT.Pair pair, long userId) {
        assertEquals(1, pair.a().db().runtime().update(
                "UPDATE users SET role='admin' WHERE id=?", userId));
    }

    static void deliverCatalog(RelationReplicationIT.Pair pair, InboxService receiver) {
        var deliveries = pair.a().delivery().claimForAggregates(pair.b().db().site().getId(), 100, LEASE,
                Set.of(CatalogEvents.AGGREGATE));
        assertFalse(deliveries.isEmpty());
        for (var lease : deliveries) {
            receiver.receive(lease.event(), pair.a().db().site().getId());
            assertTrue(pair.a().delivery().acknowledge(lease, "DURABLY_QUEUED"));
        }
        process(receiver);
    }

    private static void deliverCatalogFrom(RelationReplicationIT.Pair pair, RelationReplicationIT.Site source,
                                           RelationReplicationIT.Site target, InboxService receiver) {
        var deliveries = source.delivery().claimForAggregates(target.db().site().getId(), 100, LEASE,
                Set.of(CatalogEvents.AGGREGATE));
        for (var lease : deliveries) {
            receiver.receive(lease.event(), source.db().site().getId());
            assertTrue(source.delivery().acknowledge(lease, "DURABLY_QUEUED"));
        }
        process(receiver);
    }

    static InboxService inboxWithCatalog(RelationReplicationIT.Site site,
                                         RelationReplicationIT.Site peer) {
        CatalogStore catalogStore = new CatalogStore(site.db().runtime());
        var identities = new EntityIdentityService(site.db().runtime());
        CatalogProjection projection = new CatalogProjection(site.db().runtime(), catalogStore, identities,
                site.db().site());
        List<EventHandler> handlers = List.of(
                new RelationEventHandler(site.db().runtime(), site.store(), new RelationCausalStore(site.db().runtime()),
                        site.consent()),
                new RelationCausalEventHandler(RelationCausalEvents.ACTION, site.db().runtime(), site.store(),
                        new RelationCausalStore(site.db().runtime()), site.consent()),
                new RelationCausalEventHandler(RelationCausalEvents.BRIDGE, site.db().runtime(), site.store(),
                        new RelationCausalStore(site.db().runtime()), site.consent()),
                new CatalogEventHandler(CatalogEvents.ACTION, site.db().runtime(), catalogStore, projection,
                        site.consent(), Set.of(site.db().site().getId(), peer.db().site().getId()),
                        site.db().site().getMode() == SiteProperties.Mode.CAMPUS
                                ? site.db().site().getId() : peer.db().site().getId()),
                new CatalogEventHandler(CatalogEvents.BRIDGE, site.db().runtime(), catalogStore, projection,
                        site.consent(), Set.of(site.db().site().getId(), peer.db().site().getId()),
                        site.db().site().getMode() == SiteProperties.Mode.CAMPUS
                                ? site.db().site().getId() : peer.db().site().getId()));
        return new InboxService(site.db().runtime(), site.db().transactions(), RelationReplicationIT.CODEC,
                new EventHandlerRegistry(handlers));
    }

    static CatalogCommands catalogCommands(RelationReplicationIT.Site site,
                                           RelationReplicationIT.Site peer) {
        CatalogStore store = new CatalogStore(site.db().runtime());
        var identities = new EntityIdentityService(site.db().runtime());
        var outbox = new OutboxService(site.db().runtime(), site.db().transactions(), RelationReplicationIT.CODEC,
                site.db().site());
        CatalogSyncProperties properties = new CatalogSyncProperties();
        properties.setEnabled(true);
        properties.setPeerSiteId(peer.db().site().getId());
        properties.validate(site.db().site(), peer.db().site().getId());
        CatalogProjection projection = new CatalogProjection(site.db().runtime(), store, identities, site.db().site());
        return new CatalogCommands(site.db().runtime(), site.db().transactions(), outbox, store, identities,
                site.consent(), site.db().site(), properties, projection);
    }

    private static void process(InboxService inbox) {
        for (var lease : inbox.claim(100, LEASE)) inbox.process(lease);
    }

    private static String inboxStatus(RelationReplicationIT.Site site, SyncEvent event) {
        return site.db().runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, event.eventId());
    }

    private static String inboxDiagnostic(RelationReplicationIT.Site site, SyncEvent event) {
        return site.db().runtime().queryForObject("""
                SELECT jsonb_build_object('status',status,'last_error',last_error,
                  'event_type',envelope->>'eventType','event_id',event_id)::text
                FROM eatwhat_sync.inbox WHERE event_id=?
                """, String.class, event.eventId());
    }
}
