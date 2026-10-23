package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.sync.relation.RelationKind;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogRelationVisibilityIT {
    private static GenericContainer<?> postgres;
    @BeforeAll static void start() { postgres=IsolatedContainers.startedPostgresWithInspectRetry("relation-visibility"); }
    @AfterAll static void stop() { if(postgres!=null)postgres.stop(); }

    @Test void conflictedFoodRejectsNewRelationsWithoutPreventingExistingCancellation() throws Exception {
        var pair=RelationReplicationIT.pair(postgres);var site=pair.a();
        UUID food=register(site,"FOOD","foods",pair.food());
        assertTrue(site.commands().change(RelationKind.food_like,pair.actor(),pair.food(),null,true));
        for(String status:List.of("CONFLICT","SUPPRESSED","AUTH_REVIEW")) {
            hide(site,"FOOD",food,status);
            long before=site.db().runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox",Long.class);
            assertThrows(BizException.class,()->site.commands().change(RelationKind.favorite,pair.actor(),pair.food(),null,true));
            assertEquals(before,site.db().runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.outbox",Long.class));
            assertEquals(1,site.db().runtime().queryForObject("SELECT count(*) FROM foods WHERE id=?",Integer.class,pair.food()));
        }
        assertTrue(site.commands().change(RelationKind.food_like,pair.actor(),pair.food(),null,false));
        assertEquals(0,site.db().runtime().queryForObject("SELECT count(*) FROM likes WHERE account_id=? AND target_id=? AND deleted_at IS NULL",Integer.class,pair.actor(),pair.food()));
    }

    @Test void hiddenParentAlsoPreventsNewRelationsToItsOtherwiseVisibleFood() throws Exception {
        var pair=RelationReplicationIT.pair(postgres);var site=pair.a();
        long restaurant=site.db().runtime().queryForObject("SELECT restaurant_id FROM foods WHERE id=?",Long.class,pair.food());
        UUID parent=register(site,"RESTAURANT","restaurant",restaurant);
        hide(site,"RESTAURANT",parent,"CONFLICT");
        assertThrows(BizException.class,()->site.commands().change(RelationKind.food_like,pair.actor(),pair.food(),null,true));
        assertEquals(0,site.db().runtime().queryForObject("SELECT count(*) FROM likes",Integer.class));
    }

    private UUID register(RelationReplicationIT.Site site,String kind,String table,long id) {
        UUID gid=site.db().runtime().queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type=? AND numeric_id=?",UUID.class,table,id);
        site.db().admin().update("INSERT INTO eatwhat_sync.catalog_object(kind,object_gid,numeric_id,origin_site,authority_site,managed) VALUES(?,?,?,?,?,false)",kind,gid,id,site.db().site().getId(),site.db().site().getId());
        return gid;
    }
    private void hide(RelationReplicationIT.Site site,String kind,UUID gid,String state) {
        site.db().admin().update("INSERT INTO eatwhat_sync.catalog_projection_state(kind,object_gid,status) VALUES(?,?,?) ON CONFLICT(kind,object_gid) DO UPDATE SET status=excluded.status",kind,gid,state);
    }
}
