package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.relation.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** A ban may overtake an already-authorized cancellation; it must not strand the remote relation. */
class RelationBannedRemovalIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("banned-relation-removal");
    @BeforeAll static void start(){PG.start();}
    @AfterAll static void stop(){PG.stop();}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationArrivingAfterBanRemovesButDelayedAddStillWaits(boolean legacy) throws Exception {
        var p=RelationReplicationIT.pair(PG);
        RelationReplicationIT.change(p,p.a(),RelationKind.follow,true);
        RelationReplicationIT.exchange(p);
        RelationReplicationIT.change(p,p.a(),RelationKind.follow,false);
        var remove=RelationReplicationIT.events(p.a()).getLast();
        RelationReplicationIT.change(p,p.a(),RelationKind.favorite,true);
        var add=RelationReplicationIT.events(p.a()).getLast();
        if(legacy){remove=RelationReplicationIT.legacyV1Wire(remove);add=RelationReplicationIT.legacyV1Wire(add);}
        for(var site:List.of(p.a(),p.b()))site.db().admin().update("UPDATE users SET banned=true WHERE id IN (?,?)",p.actor(),p.owner());
        var policy=new RelationEvents(p.a().consent());
        assertEquals(EventPolicy.OutboundDecision.ALLOW,policy.outbound(remove),"Previously captured REMOVE survives later actor/owner ban");
        assertEquals(EventPolicy.OutboundDecision.WAIT,policy.outbound(add),"ADD must retain strict banned-account gate");
        RelationReplicationIT.receive(p.b(),remove);RelationReplicationIT.receive(p.b(),add);RelationReplicationIT.process(p.b());
        assertFalse(RelationReplicationIT.present(p,p.b(),RelationKind.follow));
        assertFalse(RelationReplicationIT.present(p,p.b(),RelationKind.favorite));
        assertEquals("APPLIED",p.b().db().admin().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,remove.eventId()));
        assertEquals("WAITING_DEPENDENCY",p.b().db().admin().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,add.eventId()));
        p.a().consent().withdraw(p.actor());
        assertEquals(EventPolicy.OutboundDecision.POLICY_BLOCKED,policy.outbound(remove),"Ban exception cannot bypass withdrawal or epoch change");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationDoesNotRequireDeliveringAnAddAfterBan(boolean legacy) throws Exception {
        var p=RelationReplicationIT.pair(PG);
        RelationReplicationIT.change(p,p.a(),RelationKind.follow,true);
        RelationReplicationIT.change(p,p.a(),RelationKind.follow,false);
        var remove=RelationReplicationIT.events(p.a()).getLast();
        if(legacy)remove=RelationReplicationIT.legacyV1Wire(remove);
        for(var site:List.of(p.a(),p.b()))site.db().admin().update("UPDATE users SET banned=true WHERE id=?",p.actor());
        RelationReplicationIT.receive(p.b(),remove);RelationReplicationIT.process(p.b());
        assertEquals(legacy ? "WAITING_DEPENDENCY" : "APPLIED",p.b().db().admin().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,remove.eventId()));
        if (legacy) {
            // Never waive V1 predecessor integrity; an authenticated absent V2 bridge carries recovery state.
            p.a().db().admin().update("UPDATE eatwhat_sync.relation_intent SET published_actor_epoch=0 WHERE kind='follow'");
            UUID natural=p.a().db().admin().queryForObject("SELECT natural_id FROM eatwhat_sync.relation_intent WHERE kind='follow'",UUID.class);
            assertTrue(p.a().commands().publishPendingIntent(natural));
            var bridge=RelationReplicationIT.events(p.a()).getLast();
            RelationReplicationIT.receive(p.b(),bridge);RelationReplicationIT.process(p.b());
            assertEquals("APPLIED",p.b().db().admin().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,bridge.eventId()));
        }
        assertFalse(RelationReplicationIT.present(p,p.b(),RelationKind.follow));
        assertEquals(0,RelationReplicationIT.count(p.b(),"SELECT count(*) FROM notifications"));
    }

    @Test void bannedBackfillCanPublishOnlyAbsentRemovalState() throws Exception {
        var p=RelationReplicationIT.pair(PG);
        RelationReplicationIT.change(p,p.a(),RelationKind.follow,true);RelationReplicationIT.exchange(p);
        RelationReplicationIT.change(p,p.a(),RelationKind.follow,false);
        RelationReplicationIT.change(p,p.a(),RelationKind.favorite,true);
        RelationReplicationIT.change(p,p.a(),RelationKind.favorite,false);
        RelationReplicationIT.change(p,p.a(),RelationKind.favorite,true);
        var activeAdd=RelationReplicationIT.events(p.a()).getLast();
        UUID activeNatural=p.a().db().admin().queryForObject("SELECT natural_id FROM eatwhat_sync.relation_intent WHERE kind='favorite'",UUID.class);
        p.a().db().admin().update("UPDATE eatwhat_sync.relation_intent SET published_actor_epoch=0 WHERE kind='favorite'");
        assertTrue(p.a().commands().publishPendingIntent(activeNatural));
        var activeBridge=RelationReplicationIT.events(p.a()).getLast();
        assertFalse(RelationCausalEvents.parseBridge(activeBridge.payloadJson()).removeFrontier().isEmpty());
        for(var site:List.of(p.a(),p.b()))site.db().admin().update("UPDATE users SET banned=true WHERE id=?",p.actor());
        p.a().db().admin().update("UPDATE eatwhat_sync.relation_intent SET published_actor_epoch=0 WHERE kind IN ('follow','favorite')");
        UUID removed=p.a().db().admin().queryForObject("SELECT natural_id FROM eatwhat_sync.relation_intent WHERE kind='follow'",UUID.class);
        UUID active=p.a().db().admin().queryForObject("SELECT natural_id FROM eatwhat_sync.relation_intent WHERE kind='favorite'",UUID.class);
        assertTrue(p.a().commands().publishPendingIntent(removed),"Durable cancellation must not be stranded by ban");
        var bridge=RelationReplicationIT.events(p.a()).getLast();
        assertEquals(RelationCausalEvents.BRIDGE,bridge.eventType());
        assertEquals(EventPolicy.OutboundDecision.ALLOW,new RelationEvents(p.a().consent()).outbound(bridge));
        assertFalse(p.a().commands().publishPendingIntent(active),"Backfill cannot publish active state for banned actor");
        assertEquals(EventPolicy.OutboundDecision.WAIT,new RelationEvents(p.a().consent()).outbound(activeAdd));
        assertEquals(EventPolicy.OutboundDecision.WAIT,new RelationEvents(p.a().consent()).outbound(activeBridge),
                "An active bridge cannot use an old remove frontier to bypass the ban");
        RelationReplicationIT.receive(p.b(),bridge);RelationReplicationIT.process(p.b());
        assertEquals("APPLIED",p.b().db().admin().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,bridge.eventId()));
        assertFalse(RelationReplicationIT.present(p,p.b(),RelationKind.follow));
        assertFalse(RelationReplicationIT.present(p,p.b(),RelationKind.favorite));
    }
}
