package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.catalog.*;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Low legacy numeric identities are accepted only when both site registries prove the same identity. */
class CatalogLegacyIdentityIT {
    private static final long LEGACY_ID = 731L;

    @BeforeAll
    static void startPostgres() {
        if (!RelationReplicationIT.PG.isRunning()) RelationReplicationIT.PG.start();
    }

    @Test
    void aCatalogBridgeRestoresTheSameLowNumericIdentityAfterProjectionPurge() throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
        UUID objectGid = UUID.randomUUID();
        seedLegacyRestaurant(pair.a(), pair.owner(), LEGACY_ID, "legacy low restaurant");
        seedLegacyRestaurant(pair.b(), pair.owner(), LEGACY_ID, "legacy low restaurant");
        for (RelationReplicationIT.Site site : List.of(pair.a(), pair.b())) {
            site.db().admin().update("""
                    INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
                    VALUES('restaurant',?,?,?)
                    """, LEGACY_ID, objectGid, pair.a().db().site().getId());
        }
        pair.a().db().admin().update(
                "UPDATE eatwhat_consent.subject SET identity_ready=true WHERE user_id=?", pair.owner());
        purgePublicRestaurant(pair.b(), LEGACY_ID);

        CatalogCommands commands = CatalogTerminalRelationIT.catalogCommands(pair.a(), pair.b());
        var subject = pair.a().consent().get(pair.owner());
        assertEquals(CatalogCommands.BackfillStatus.APPLIED,
                commands.backfillCatalogObject(CatalogKind.RESTAURANT, LEGACY_ID, subject.subjectId(), subject.epoch()));
        var receiver = CatalogTerminalRelationIT.inboxWithCatalog(pair.b(), pair.a());
        CatalogTerminalRelationIT.deliverCatalog(pair, receiver);

        assertEquals(LEGACY_ID, pair.b().db().runtime().queryForObject(
                "SELECT id FROM public.restaurant WHERE name='legacy low restaurant'", Long.class));
        assertEquals(LEGACY_ID, pair.b().db().runtime().queryForObject(
                "SELECT numeric_id FROM eatwhat_identity.entity_identity WHERE entity_type='restaurant' AND global_id=?",
                Long.class, objectGid));
        assertEquals(objectGid, pair.b().db().runtime().queryForObject(
                "SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='restaurant' AND numeric_id=?",
                UUID.class, LEGACY_ID));
        assertEquals(LEGACY_ID, new CatalogStore(pair.b().db().runtime())
                .findByNumeric(CatalogKind.RESTAURANT, LEGACY_ID).numericId());
    }

    @Test
    void aLowNumericCreateWithoutACompleteIdentityRegistryIsRejected() throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair();
        var receiver = CatalogTerminalRelationIT.inboxWithCatalog(pair.b(), pair.a());
        var subject = pair.a().consent().get(pair.owner());
        UUID authorGid = pair.a().db().runtime().queryForObject("""
                SELECT global_id FROM eatwhat_identity.entity_identity
                WHERE entity_type='users' AND numeric_id=?
                """, UUID.class, pair.owner());
        UUID objectGid = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("name", "unregistered low id");
        values.put("address", "Synthetic address");
        values.put("cityId", null);
        values.put("poi", "POI-unregistered-low-id");
        values.put("locationWgs", Map.of("lng", 117.0, "lat", 36.0));
        values.put("locationGcj", Map.of("lng", 117.01, "lat", 36.01));
        values.put("hubGid", null);
        values.put("pictureKeys", List.of());
        Map<String, Object> payload = CatalogPayload.validate(CatalogKind.RESTAURANT, values);
        String contentHash = EventCodec.sha256(EventCodec.canonicalJson(
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(payload)));
        CatalogEvents.Action action = new CatalogEvents.Action(CatalogKind.RESTAURANT, objectGid, 1, 0,
                CatalogOperation.CREATE, pair.a().db().site().getId(), authorGid, null, 732L,
                pair.a().db().site().getId(), payload, contentHash, operationId,
                pair.a().db().site().getId(), 1, Map.of(),
                List.of(new CatalogEvents.ConsentEpoch(subject.subjectId(), subject.epoch())), null, null);
        long transportId = pair.a().db().runtime().queryForObject("SELECT eatwhat_sync.next_catalog_number()", Long.class);
        SyncEvent event = new SyncEvent(operationId, pair.a().db().site().getId(),
                pair.a().db().site().getInstanceId(), CatalogEvents.AGGREGATE, operationId, transportId,
                CatalogEvents.ACTION, CatalogEvents.SCHEMA_VERSION, VersionVector.empty(),
                VersionVector.empty().increment(pair.a().db().site().getId()), List.of(), authorGid,
                "catalog-unregistered-low-id", operationId, CatalogEvents.payload(action));
        CatalogEvents.validateEvent(event, event.originSite(),
                Set.of(pair.a().db().site().getId(), pair.b().db().site().getId()));
        receiver.receive(event, event.originSite());

        assertThrows(RuntimeException.class, () -> {
            for (var lease : receiver.claim(10, java.time.Duration.ofSeconds(20))) receiver.process(lease);
        }, "A valid-looking low-ID catalog action cannot create its own identity proof");
        assertEquals(0, pair.b().db().runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.catalog_object WHERE object_gid=?", Integer.class, objectGid));
        assertEquals(0, pair.b().db().runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=732", Integer.class));
    }

    private static void seedLegacyRestaurant(RelationReplicationIT.Site site, long owner, long id, String name)
            throws Exception {
        try (Connection connection = site.db().admin().getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role='replica'");
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO public.restaurant(id,name,address,location,gcj_lng,gcj_lat,account_id)
                    VALUES(?,?,?,ST_SetSRID(ST_Point(117,36),4326)::geography,117,36,?)
                    """)) {
                statement.setLong(1, id);
                statement.setString(2, name);
                statement.setString(3, "Synthetic legacy address");
                statement.setLong(4, owner);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private static void purgePublicRestaurant(RelationReplicationIT.Site site, long id) throws Exception {
        try (Connection connection = site.db().admin().getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role='replica'");
            }
            try (var statement = connection.prepareStatement("DELETE FROM public.restaurant WHERE id=?")) {
                statement.setLong(1, id);
                assertEquals(1, statement.executeUpdate());
            }
            connection.commit();
        }
    }
}
