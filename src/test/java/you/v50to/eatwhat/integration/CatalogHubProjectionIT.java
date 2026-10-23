package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.sync.catalog.CatalogKind;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies high precision Hub coordinates and nullable boundaries through real Inbox projection. */
class CatalogHubProjectionIT {
    private static final GenericContainer<?> PG = IsolatedContainers.postgres("catalog-hub-projection");

    @BeforeAll static void start() { PG.start(); }
    @AfterAll static void stop() { PG.stop(); }

    @Test
    void inboundHubProjectionPreservesCanonicalFloat8CoordinatesAndNullableBoundary() throws Exception {
        RelationReplicationIT.Pair pair = RelationReplicationIT.pair(PG);
        assertEquals(1, pair.a().db().runtime().update(
                "UPDATE public.users SET role='admin' WHERE id=?", pair.owner()));
        var commands = CatalogTerminalRelationIT.catalogCommands(pair.a(), pair.b());

        long withoutBoundary = createHub(pair.a(), commands, pair.owner(), "high precision without boundary", null);
        long withBoundary = createHub(pair.a(), commands, pair.owner(), "high precision with boundary",
                "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[117.01000000000001,36.01000000000001],[117.02000000000001,36.01000000000001],[117.02000000000001,36.02000000000001],[117.01000000000001,36.01000000000001]]]]}");

        CatalogTerminalRelationIT.deliverCatalog(pair, CatalogTerminalRelationIT.inboxWithCatalog(pair.b(), pair.a()));

        for (long id : new long[]{withoutBoundary, withBoundary}) {
            assertEquals(1, RelationReplicationIT.count(pair.b(), "SELECT count(*) FROM public.hub WHERE id=?", id));
            assertEquals(1, RelationReplicationIT.count(pair.b(), """
                    SELECT count(*) FROM eatwhat_sync.catalog_object
                    WHERE kind='HUB' AND numeric_id=? AND revision=1 AND NOT tombstone AND NOT rejected
                    """, id));
        }
        assertEquals(1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM public.hub WHERE id=? AND boundary IS NULL", withoutBoundary));
        assertEquals(1, RelationReplicationIT.count(pair.b(),
                "SELECT count(*) FROM public.hub WHERE id=? AND boundary IS NOT NULL", withBoundary));
    }

    private static long createHub(RelationReplicationIT.Site site,
                                  you.v50to.eatwhat.sync.catalog.CatalogCommands commands,
                                  long admin, String name, String boundary) {
        final Long[] inserted = new Long[1];
        var created = commands.create(CatalogKind.HUB, admin, null, null, Map.of(), () -> {
            inserted[0] = site.db().runtime().queryForObject("""
                    INSERT INTO public.hub(name,center,gcj_lng,gcj_lat,boundary)
                    VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?
                      ,CASE WHEN ?::text IS NULL THEN NULL ELSE
                        ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?),4326))::geometry(MultiPolygon,4326) END)
                    RETURNING id
                    """, Long.class, name, 117.01234567890123, 36.01234567890123,
                    117.02234567890123, 36.02234567890123, boundary, boundary);
            return inserted[0];
        }, () -> inserted[0], java.util.Objects::nonNull);
        assertEquals(you.v50to.eatwhat.sync.catalog.CatalogCommands.Status.APPLIED, created.status());
        return created.value();
    }
}
