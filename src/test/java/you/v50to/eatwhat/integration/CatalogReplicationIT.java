package you.v50to.eatwhat.integration;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.service.HubService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.EventHandler;
import you.v50to.eatwhat.sync.EventHandlerRegistry;
import you.v50to.eatwhat.sync.InboxService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.DeliveryService;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.catalog.*;
import you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import you.v50to.eatwhat.sync.catalog.CatalogEvents;
import you.v50to.eatwhat.sync.catalog.CatalogCommands;
import you.v50to.eatwhat.sync.catalog.CatalogBackfillStore;
import you.v50to.eatwhat.sync.catalog.CatalogBackfillWorker;
import you.v50to.eatwhat.sync.catalog.CatalogConflictService;
import you.v50to.eatwhat.sync.catalog.CatalogDecision;
import you.v50to.eatwhat.sync.catalog.CatalogHubRebindResult;
import you.v50to.eatwhat.sync.catalog.CatalogKind;
import you.v50to.eatwhat.sync.catalog.CatalogOperation;
import you.v50to.eatwhat.sync.catalog.CatalogPayload;
import you.v50to.eatwhat.sync.catalog.CatalogProjection;
import you.v50to.eatwhat.sync.catalog.CatalogStore;
import you.v50to.eatwhat.sync.catalog.CatalogSyncProperties;
import you.v50to.eatwhat.sync.catalog.CatalogImportService;
import you.v50to.eatwhat.sync.catalog.CatalogHubImportRequest;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL natural-key arbitration under the restricted application role. */
class CatalogReplicationIT extends MvcContractSupport {
    private static final GenericContainer<?> PG = IsolatedContainers.postgres("catalog-replication");
    private static final String POLICY = "catalog-test-policy";
    private static final Duration LEASE = Duration.ofSeconds(20);

    @BeforeAll static void start() { PG.start(); }
    @AfterAll static void stop() { PG.stop(); }

    private record CatalogSite(EventFoundationTestSupport.Database db, ConsentStore consent,
                               CatalogStore store, CatalogCommands commands,
                               CatalogSyncProperties properties, InboxService inbox,
                               DeliveryService delivery, String peerSiteId) { }
    private record Pair(CatalogSite campus, CatalogSite external) { }

    @Test
    void offlineNormalizedFoodNameCollisionConvergesAndCanBeResolvedWithoutMergingIds() {
        Pair pair=pair();long owner=registerSharedUser(pair.campus,pair.external,"p1-food-owner");
        for(var site:List.of(pair.campus,pair.external))promoteCampusAdmin(site,owner);
        var restaurant=createRestaurant(pair.campus,owner,"p1-food-poi","P1 restaurant");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        var first=createFood(pair.campus,owner,restaurant.value(),"鲜虾 云吞");
        var second=createFood(pair.external,owner,restaurant.value(),"鲜虾　云吞");
        assertEquals(CatalogCommands.Status.APPLIED,first.status());assertEquals(CatalogCommands.Status.APPLIED,second.status());
        exchangeMulti(campus,external,owner);exchangeMulti(external,campus,owner);
        UUID conflict=pair.campus.db.runtime().queryForObject("SELECT conflict_id FROM eatwhat_sync.catalog_conflict WHERE kind='FOOD' AND status='OPEN'",UUID.class);
        for(var site:List.of(pair.campus,pair.external)) {
            assertEquals(1,site.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.catalog_conflict WHERE kind='FOOD' AND status='OPEN'",Integer.class));
            assertEquals(0,site.db.runtime().queryForObject("SELECT count(*) FROM foods WHERE restaurant_id=? AND catalog_visible",Integer.class,restaurant.value()));
            assertEquals(2,site.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.catalog_candidate WHERE kind='FOOD' AND status='PENDING'",Integer.class));
        }
        var candidates=campus.decisions.view(conflict).candidateOperations();assertEquals(2,candidates.size());
        UUID winner=pair.campus.db.runtime().queryForObject("SELECT candidate_id FROM eatwhat_sync.catalog_candidate WHERE object_gid=?",UUID.class,first.candidateGid());
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(conflict,candidates,List.of(),CatalogDecision.KEEP,winner,"Keep one dish",owner));
        exchangeMulti(campus,external,owner);
        for(var site:List.of(pair.campus,pair.external)) {
            assertEquals(List.of(first.value()),site.db.runtime().queryForList("SELECT id FROM foods WHERE restaurant_id=? AND catalog_visible",Long.class,restaurant.value()));
        }
    }

    @Test
    void multiwriterFirstHubCreationThroughHttpCapturesAuthenticatedActorAtBothSites() throws Exception {
        Pair pair=pair();long admin=registerSharedUser(pair.campus,pair.external,"mw-first-http-admin");
        for(var site:List.of(pair.campus,pair.external))promoteCampusAdmin(site,admin);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        for(var mw:List.of(campus,external)) {
            var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(you.v50to.eatwhat.mapper.HubMapper.class);
            var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(mw.site.db.dataSource());factory.setConfiguration(configuration);
            var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
            var service=serviceFor(mw.site);ReflectionTestUtils.setField(service,"hubMapper",session.getMapper(you.v50to.eatwhat.mapper.HubMapper.class));
            var controller=new you.v50to.eatwhat.controller.HubController();ReflectionTestUtils.setField(controller,"hubService",service);
            String token=loginAs(admin,"admin");
            mvc(controller).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/hub/add")
                .header(TOKEN_HEADER,"Bearer "+token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"First HTTP hub\",\"gcjLng\":117.12,\"gcjLat\":36.67}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0));
            assertEquals(1,mw.site.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.catalog_actor_evidence",Integer.class));
        }
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external))assertEquals(2,mw.site.db.runtime().queryForObject("SELECT count(*) FROM hub WHERE catalog_visible",Integer.class));
    }

    @Test
    void multiwriterHiddenParentAndPrivateTagAreExcludedFromRecommendationsAndCounts() throws Exception {
        Pair pair=pair();long owner=registerSharedUser(pair.campus,pair.external,"mw-query-owner");promoteCampusAdmin(pair.campus,owner);
        var restaurant=createRestaurant(pair.campus,owner,"mw-query-poi","Hidden parent");var food=createFood(pair.campus,owner,restaurant.value(),"Hidden food");
        var tag=createCustomTag(pair.campus,owner,"Hidden flavor");
        pair.campus.db.runtime().update("INSERT INTO food_taggings(food_id,tag_id,account_id) VALUES(?,?,?)",food.value(),tag.value(),owner);
        assertEquals(CatalogCommands.Status.APPLIED,pair.campus.commands.update(CatalogKind.FOOD,food.value(),owner,current->new CatalogCommands.UpdatePlan<>(restaurant.value(),Map.of("restaurantId",restaurant.value(),"name","Hidden food"),()->pair.campus.db.runtime().update("UPDATE foods SET picture_url=ARRAY['foods/query/test'] WHERE id=?",food.value()),n->n==1)).status());
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(you.v50to.eatwhat.mapper.FoodMapper.class);config.addMapper(you.v50to.eatwhat.mapper.RecommendationProfileMapper.class);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(pair.campus.db.dataSource());factory.setConfiguration(config);var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var foods=session.getMapper(you.v50to.eatwhat.mapper.FoodMapper.class);var profiles=session.getMapper(you.v50to.eatwhat.mapper.RecommendationProfileMapper.class);
        assertTrue(profiles.selectProfile(owner).stream().anyMatch(r->"taste".equals(r.getDimension())));
        multiSite(pair.campus);
        assertEquals(CatalogCommands.Status.APPLIED,pair.campus.commands.delete(CatalogKind.CUSTOM_TAG,tag.value(),owner,()->pair.campus.db.runtime().update("DELETE FROM food_tags WHERE id=?",tag.value()),n->n==1).status());
        assertFalse(profiles.selectProfile(owner).stream().anyMatch(r->"taste".equals(r.getDimension())));
        assertEquals(1,foods.selectRecommendationCandidates(owner, 1L, true, null, 20).size());
        assertEquals(CatalogCommands.Status.APPLIED,pair.campus.commands.delete(CatalogKind.RESTAURANT,restaurant.value(),owner,()->pair.campus.db.runtime().update("DELETE FROM restaurant WHERE id=?",restaurant.value()),n->n==1).status());
        assertEquals(0,foods.countTotal());assertEquals(0,foods.countByAccountId(owner));assertEquals(0,foods.countByRestaurantId(restaurant.value()));
        assertTrue(foods.selectRecommendationCandidates(owner, 1L, true, null, 20).isEmpty());
    }

    @Test
    void multiwriterExternalAuthorCanEditWithoutChangingOtherAuthorsPermissions() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus,pair.external,"multi-author");
        long stranger = registerSharedUser(pair.campus,pair.external,"multi-stranger");
        long restaurant = createRestaurant(pair.campus,author,"multi-poi","Multi restaurant").value();
        long food = createFood(pair.campus,author,restaurant,"Before").value();
        exchange(pair);
        for (CatalogSite site : List.of(pair.campus,pair.external)) activateMultiwriter(site);
        assertCatalogWriteDenied(pair.external,"FOOD",food,stranger,stranger,"UPDATE");
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.update(CatalogKind.FOOD,food,author,
            current -> new CatalogCommands.UpdatePlan<>(restaurant,Map.of("restaurantId",restaurant,"name","After"),
                () -> pair.external.db.runtime().update("UPDATE public.foods SET name='After' WHERE id=? AND account_id=?",food,author),n->n==1)).status());
    }

    @Test
    void multiwriterExternalUpdateReplicatesWithAccountWitness() {
        Pair pair=pair();long author=registerSharedUser(pair.campus,pair.external,"mw-wire-author");
        long restaurant=createRestaurant(pair.campus,author,"mw-wire-poi","Restaurant").value();
        long food=createFood(pair.campus,author,restaurant,"Before").value();exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.update(CatalogKind.FOOD,food,author,
            current -> new CatalogCommands.UpdatePlan<>(restaurant,Map.of("restaurantId",restaurant,"name","After remote"),
                () -> pair.external.db.runtime().update("UPDATE public.foods SET name='After remote' WHERE id=?",food),n->n==1)).status());
        exchangeMulti(external,campus,author);
        assertEquals("After remote",pair.campus.db.runtime().queryForObject("SELECT name FROM foods WHERE id=?",String.class,food),
            () -> pair.campus.db.runtime().queryForList("SELECT status,last_error FROM eatwhat_sync.inbox").toString());
    }

    @Test
    void foodDisplayFieldsReplicateWhileDefaultsKeepTheLegacyPayload() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "display-author");
        long restaurant = createRestaurant(pair.campus, author, "display-poi", "Display restaurant").value();
        long legacy = createFood(pair.campus, author, restaurant, "Legacy dish").value();
        long food = createFood(pair.campus, author, restaurant, "Display dish").value();
        assertEquals(CatalogCommands.Status.APPLIED, pair.campus.commands.update(CatalogKind.FOOD, food, author,
                current -> new CatalogCommands.UpdatePlan<>(restaurant, Map.of("restaurantId", restaurant, "name", "Display dish"),
                        () -> pair.campus.db.runtime().update("""
                                UPDATE public.foods SET meal_times=ARRAY['lunch','night'],stage_color='chili',
                                  stage_alternates=ARRAY['mango','soy'],cover_center_x=0.4,cover_center_y=0.55,
                                  cover_diameter=1,remove_background=true WHERE id=?
                                """, food), n -> n == 1)).status());

        Map<String, Object> legacyPayload = pair.campus.store.readPublic(CatalogKind.FOOD, legacy);
        assertEquals(Set.of("restaurantGid", "name", "description", "price", "category", "pictureKeys"), legacyPayload.keySet());
        Map<String, Object> payload = pair.campus.store.readPublic(CatalogKind.FOOD, food);
        assertEquals(List.of("lunch", "night"), payload.get("mealTimes"));
        assertEquals("chili", payload.get("stageColor"));
        assertEquals(true, payload.get("removeBackground"));

        exchange(pair);
        Map<String, Object> row = pair.external.db.runtime().queryForMap("""
                SELECT meal_times::text meal_times,stage_color,stage_alternates::text stage_alternates,
                       cover_center_x,cover_center_y,cover_diameter,remove_background FROM foods WHERE id=?
                """, food);
        assertEquals("{lunch,night}", row.get("meal_times"));
        assertEquals("chili", row.get("stage_color"));
        assertEquals("{mango,soy}", row.get("stage_alternates"));
        assertEquals(0, new BigDecimal("0.4").compareTo((BigDecimal) row.get("cover_center_x")));
        assertEquals(0, new BigDecimal("0.55").compareTo((BigDecimal) row.get("cover_center_y")));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) row.get("cover_diameter")));
        assertEquals(true, row.get("remove_background"));
        Map<String, Object> legacyRow = pair.external.db.runtime().queryForMap(
                "SELECT meal_times::text meal_times,stage_color,remove_background FROM foods WHERE id=?", legacy);
        assertEquals("{}", legacyRow.get("meal_times"));
        assertNull(legacyRow.get("stage_color"));
        assertEquals(false, legacyRow.get("remove_background"));
        // The receiver re-derives the same canonical payload from its projection.
        assertEquals(payload, pair.external.store.readPublic(CatalogKind.FOOD, food));
    }

    @Test
    void hubCampusAssignmentReplicatesAndUnassignedHubsKeepTheLegacyPayload() {
        Pair pair = pair();
        long admin = registerSharedUser(pair.campus, pair.external, "campus-admin");
        promoteCampusAdmin(pair.campus, admin);
        promoteCampusAdmin(pair.external, admin);
        long unassigned = createHub(pair.campus, admin, "Unassigned hub").value();
        long assigned = createHub(pair.campus, admin, "Software park hub").value();
        assertEquals(CatalogCommands.Status.APPLIED, pair.campus.commands.update(CatalogKind.HUB, assigned, admin,
                current -> new CatalogCommands.UpdatePlan<>(null, Map.of(),
                        () -> pair.campus.db.runtime().update("UPDATE public.hub SET campus_id=1 WHERE id=?", assigned), n -> n == 1)).status());

        assertFalse(pair.campus.store.readPublic(CatalogKind.HUB, unassigned).containsKey("campusId"));
        assertEquals(1L, ((Number) pair.campus.store.readPublic(CatalogKind.HUB, assigned).get("campusId")).longValue());
        exchange(pair);
        assertEquals(1L, pair.external.db.runtime().queryForObject("SELECT campus_id FROM hub WHERE id=?", Long.class, assigned));
        assertNull(pair.external.db.runtime().queryForObject("SELECT campus_id FROM hub WHERE id=?", Long.class, unassigned));
    }

    @Test
    void multiwriterHistoricalDeleteStillReplicatesWhenItsAuthorsLaterBanArrivesFirst() {
        Pair pair=pair();long owner=registerSharedUser(pair.campus,pair.external,"mw-delete-owner");
        long admin=registerSharedUser(pair.campus,pair.external,"mw-delete-admin");
        for(var site:List.of(pair.campus,pair.external))promoteCampusAdmin(site,admin);
        var restaurant=createRestaurant(pair.campus,owner,"delete-before-ban","Restaurant");
        var food=createFood(pair.campus,owner,restaurant.value(),"Before deletion");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.update(CatalogKind.FOOD,food.value(),owner,
            current->new CatalogCommands.UpdatePlan<>(restaurant.value(),Map.of(),
                ()->pair.external.db.runtime().update("UPDATE foods SET name='Updated before ban' WHERE id=?",food.value()),n->n==1)).status());
        exchangeMulti(external,campus,owner);
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.delete(CatalogKind.FOOD,food.value(),owner,
            ()->pair.external.db.runtime().update("DELETE FROM foods WHERE id=?",food.value()),n->n==1).status());
        var events=pair.external.db.runtime().queryForList("SELECT envelope::text FROM eatwhat_sync.outbox WHERE envelope->>'eventType'='catalog.action' AND envelope->>'schemaVersion'='2'",String.class)
            .stream().map(raw->new EventCodec().decode(raw)).toList();
        var deletion=events.stream().filter(e->CatalogEvents.parseAction(CatalogMultiwriterEvents.legacyShape(e).payloadJson()).operation()==CatalogOperation.DELETE).findFirst().orElseThrow();
        var update=events.stream().filter(e->CatalogEvents.parseAction(CatalogMultiwriterEvents.legacyShape(e).payloadJson()).operation()==CatalogOperation.UPDATE).findFirst().orElseThrow();
        // Ban is causally after the deletion witness, but its account channel arrives first.
        external.accounts.command(admin,owner,()->pair.external.db.runtime().update("UPDATE users SET banned=true WHERE id=?",owner));
        for(long actor:List.of(admin,owner))campus.accounts.receive(actor,external.accounts.changes(actor),external.accounts.witnesses(actor),new NodeIdentity(pair.external.db.site().getId(),pair.external.db.site().getInstanceId()));
        assertTrue(pair.campus.db.runtime().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,owner));
        campus.inbox.receive(deletion,pair.external.db.site().getId());
        for(var lease:campus.inbox.claim(100,LEASE))campus.inbox.process(lease);
        var policy=new CatalogEventPolicy(pair.external.consent,Set.of("catalog-campus","catalog-external"),"catalog-campus");
        assertAll(
            ()->assertEquals(you.v50to.eatwhat.sync.transport.EventPolicy.OutboundDecision.ALLOW,policy.outbound(deletion)),
            ()->assertNotEquals(you.v50to.eatwhat.sync.transport.EventPolicy.OutboundDecision.ALLOW,policy.outbound(update)),
            ()->assertNotEquals(you.v50to.eatwhat.sync.transport.EventPolicy.OutboundDecision.ALLOW,policy.outbound(CatalogMultiwriterEvents.legacyShape(deletion))),
            ()->assertEquals("APPLIED",pair.campus.db.runtime().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,deletion.eventId())),
            ()->assertTrue(pair.campus.store.findByNumeric(CatalogKind.FOOD,food.value()).tombstone()),
            ()->assertFalse(pair.campus.db.runtime().queryForObject("SELECT catalog_visible FROM foods WHERE id=?",Boolean.class,food.value())));
        pair.external.db.transactions().required(()->{
            pair.external.db.runtime().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN',epoch=epoch+1,identity_ready=false,valid_until=NULL WHERE user_id=?",owner);
            return null;
        });
        assertEquals(you.v50to.eatwhat.sync.transport.EventPolicy.OutboundDecision.POLICY_BLOCKED,policy.outbound(deletion),"Removal transport must never bypass consent withdrawal");
    }

    @Test
    void multiwriterDeleteConcurrentWithAuthorBanStillRequiresAuthorizationReview() {
        Pair pair=pair();long owner=registerSharedUser(pair.campus,pair.external,"mw-ban-race-owner");
        long admin=registerSharedUser(pair.campus,pair.external,"mw-ban-race-admin");
        for(var site:List.of(pair.campus,pair.external))promoteCampusAdmin(site,admin);
        var restaurant=createRestaurant(pair.campus,owner,"delete-concurrent-ban","Restaurant");
        var food=createFood(pair.campus,owner,restaurant.value(),"Disputed removal");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        campus.accounts.initialize(owner);external.accounts.initialize(owner);
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.delete(CatalogKind.FOOD,food.value(),owner,
            ()->pair.external.db.runtime().update("DELETE FROM foods WHERE id=?",food.value()),n->n==1).status());
        // This administrator has not observed the delete witness, unlike the historical-delete test.
        campus.accounts.command(admin,owner,()->pair.campus.db.runtime().update("UPDATE users SET banned=true WHERE id=?",owner));
        exchangeMulti(external,campus,owner);
        assertEquals(1,pair.campus.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.catalog_authorization_review WHERE kind='FOOD' AND object_gid=?",Integer.class,food.candidateGid()));
        assertEquals("AUTH_REVIEW",pair.campus.db.runtime().queryForObject("SELECT status FROM eatwhat_sync.catalog_projection_state WHERE kind='FOOD' AND object_gid=?",String.class,food.candidateGid()));
        assertFalse(pair.campus.store.findByNumeric(CatalogKind.FOOD,food.value()).tombstone(),"Concurrent revocation must not authorize a terminal delete");
        assertFalse(pair.campus.db.runtime().queryForObject("SELECT catalog_visible FROM foods WHERE id=?",Boolean.class,food.value()));
    }

    @Test
    void multiwriterObjectAndNaturalKeyDecisionsCannotOverrideEachOthersVisibility() {
        Pair pair=pair();long admin=registerSharedUser(pair.campus,pair.external,"mw-scope-admin");
        for(var site:List.of(pair.campus,pair.external))promoteCampusAdmin(site,admin);
        var restaurant=createRestaurant(pair.campus,admin,"combined-scope-poi","Original");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        for(var entry:List.of(Map.entry(campus,"Campus version"),Map.entry(external,"External version"))) {
            var site=entry.getKey().site;
            assertEquals(CatalogCommands.Status.APPLIED,site.commands.update(CatalogKind.RESTAURANT,restaurant.value(),admin,
                current->new CatalogCommands.UpdatePlan<>(null,Map.of(),
                    ()->site.db.runtime().update("UPDATE restaurant SET name=? WHERE id=?",entry.getValue(),restaurant.value()),n->n==1)).status());
        }
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        UUID objectConflict=CatalogStore.stableHeadConflictId(CatalogKind.RESTAURANT,restaurant.candidateGid());
        var objectCandidates=campus.decisions.view(objectConflict).candidateOperations();assertEquals(2,objectCandidates.size());
        var delayed=syntheticRestaurantCreate(pair.external,admin,UUID.randomUUID(),1_000_000_104L,"combined-scope-poi","Offline duplicate");
        var v1=syntheticCatalogActionEvent(pair.external,delayed,admin,"combined-scope-duplicate");
        var proof=new CatalogMultiwriterSupport(pair.external.db.runtime(),external.accounts).capture(admin);
        exchangeMulti(external,campus,admin);
        var event=new SyncEvent(v1.eventId(),v1.originSite(),v1.originInstanceId(),v1.aggregateType(),v1.aggregateId(),v1.numericId(),
            v1.eventType(),2,v1.baseVersion(),v1.nextVersion(),v1.dependencies(),v1.actorId(),v1.commandKey(),v1.correlationId(),
            CatalogMultiwriterEvents.actionPayload(delayed,proof));
        for(var mw:List.of(campus,external)) {
            mw.inbox.receive(event,pair.external.db.site().getId());
            for(var lease:mw.inbox.claim(100,LEASE))mw.inbox.process(lease);
            assertEquals("APPLIED",mw.site.db.runtime().queryForObject("SELECT status FROM eatwhat_sync.inbox WHERE event_id=?",String.class,event.eventId()),
                ()->mw.site.db.runtime().queryForList("SELECT status,last_error FROM eatwhat_sync.inbox").toString());
        }
        UUID keyConflict=pair.campus.db.runtime().queryForObject("SELECT conflict_id FROM eatwhat_sync.catalog_conflict WHERE kind='RESTAURANT'",UUID.class);
        var keyCandidates=campus.decisions.view(keyConflict).candidateOperations();assertEquals(3,keyCandidates.size());
        // Each site independently resolves a different scope to a different revision of the same object.
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(objectConflict,objectCandidates,List.of(),CatalogDecision.KEEP,objectCandidates.get(0),"Object scope",admin));
        assertFalse(pair.campus.db.runtime().queryForObject("SELECT catalog_visible FROM restaurant WHERE id=?",Boolean.class,restaurant.value()),"Natural-key scope is still open");
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(keyConflict,keyCandidates,List.of(),CatalogDecision.KEEP,objectCandidates.get(1),"Natural-key scope",admin));
        assertFalse(pair.external.db.runtime().queryForObject("SELECT catalog_visible FROM restaurant WHERE id=?",Boolean.class,restaurant.value()),"Object scope is still open");
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external)) {
            assertFalse(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM restaurant WHERE id=?",Boolean.class,restaurant.value()),"Resolved scopes disagree on the selected revision");
            assertEquals("CONFLICT",mw.site.db.runtime().queryForObject("SELECT status FROM eatwhat_sync.catalog_projection_state WHERE kind='RESTAURANT' AND object_gid=?",String.class,restaurant.candidateGid()));
        }
        var keyView=campus.decisions.view(keyConflict);
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(keyConflict,keyCandidates,keyView.decisionHeads(),CatalogDecision.KEEP,objectCandidates.get(0),"Agree across scopes",admin));
        exchangeMulti(campus,external,admin);
        String expected=(String)pair.campus.store.readRevision(objectCandidates.get(0)).action().canonicalPayload().get("name");
        for(var mw:List.of(campus,external)) {
            assertTrue(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM restaurant WHERE id=?",Boolean.class,restaurant.value()));
            assertEquals(expected,mw.site.db.runtime().queryForObject("SELECT name FROM restaurant WHERE id=?",String.class,restaurant.value()));
            assertEquals(1,mw.site.db.runtime().queryForObject("SELECT count(*) FROM restaurant WHERE poi='combined-scope-poi' AND catalog_visible",Integer.class));
        }
        var rejectedView=external.decisions.view(keyConflict);
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(keyConflict,keyCandidates,rejectedView.decisionHeads(),CatalogDecision.REJECT,null,"Reject duplicate key",admin));
        exchangeMulti(external,campus,admin);
        var objectView=campus.decisions.view(objectConflict);
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(objectConflict,objectCandidates,objectView.decisionHeads(),CatalogDecision.KEEP,objectCandidates.get(0),"Object-only reapproval",admin));
        exchangeMulti(campus,external,admin);
        for(var mw:List.of(campus,external)) {
            assertFalse(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM restaurant WHERE id=?",Boolean.class,restaurant.value()),"Object approval cannot bypass natural-key rejection");
            assertEquals("SUPPRESSED",mw.site.db.runtime().queryForObject("SELECT status FROM eatwhat_sync.catalog_projection_state WHERE kind='RESTAURANT' AND object_gid=?",String.class,restaurant.candidateGid()));
        }
    }

    @Test
    void multiwriterOppositeAdminDecisionsRemainConflictUntilBothHeadsAreObserved() {
        Pair pair=pair();long admin=registerSharedUser(pair.campus,pair.external,"mw-admin");
        promoteCampusAdmin(pair.campus,admin);promoteCampusAdmin(pair.external,admin);
        var hub=createHub(pair.campus,admin,"Original");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        for(var entry:List.of(Map.entry(campus,"Campus"),Map.entry(external,"External"))) {
            var site=entry.getKey().site;String name=entry.getValue();
            assertEquals(CatalogCommands.Status.APPLIED,site.commands.update(CatalogKind.HUB,hub.value(),admin,
                current -> new CatalogCommands.UpdatePlan<>(null,Map.of(),
                    () -> site.db.runtime().update("UPDATE public.hub SET name=? WHERE id=?",name,hub.value()),n->n==1)).status());
        }
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.HUB,hub.candidateGid());
        var heads=pair.campus.store.findByNumeric(CatalogKind.HUB,hub.value()).causalState();
        List<UUID> candidates=pair.campus.db.runtime().query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE kind='HUB' AND object_gid=? ORDER BY 1",(rs,n)->rs.getObject(1,UUID.class),hub.candidateGid());
        assertEquals(2,candidates.size());
        var conflictApi=conflictService(pair.campus);conflictApi.setDecisionService(campus.decisions);
        assertEquals(candidates,conflictApi.decisionSnapshot(conflict).candidateOperations());
        assertEquals(CatalogCommands.Status.APPLIED,conflictApi.resolveDecision(conflict,new CatalogConflictService.ResolveDecisionRequest(candidates,List.of(),CatalogDecision.KEEP,candidates.get(0),"Campus choice"),admin).status());
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(conflict,candidates,List.of(),CatalogDecision.KEEP,candidates.get(1),"External choice",admin));
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external)) {
            assertEquals("CONFLICT",mw.decisions.list().getFirst().status(),()->mw.site.db.runtime().queryForList("SELECT status,last_error FROM eatwhat_sync.inbox").toString());
            assertFalse(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
            assertEquals(2,mw.decisions.list().getFirst().decisionHeads().size());
        }
        var view=campus.decisions.list().getFirst();
        assertEquals(CatalogCommands.Status.CONFLICT,campus.decisions.resolve(conflict,candidates,List.of(),CatalogDecision.KEEP,candidates.get(0),"Stale",admin));
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(conflict,candidates,view.decisionHeads(),CatalogDecision.KEEP,candidates.get(0),"Observed both",admin));
        exchangeMulti(campus,external,admin);
        for(var mw:List.of(campus,external)) {
            assertEquals("RESOLVED",mw.decisions.list().getFirst().status());
            assertTrue(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
            assertEquals(candidates.get(0),mw.decisions.list().getFirst().winnerOperationId());
        }
        for(var mw:List.of(campus,external))mw.site.db.transactions().required(()->{mw.backfill.publish(CatalogKind.HUB,hub.candidateGid(),List.of());return null;});
        UUID campusBridge=pair.campus.db.runtime().queryForObject("SELECT event_id FROM eatwhat_sync.outbox WHERE envelope->>'eventType'='catalog.decision.bridge'",UUID.class);
        UUID externalBridge=pair.external.db.runtime().queryForObject("SELECT event_id FROM eatwhat_sync.outbox WHERE envelope->>'eventType'='catalog.decision.bridge'",UUID.class);
        assertNotEquals(campusBridge,externalBridge,"Independent publishers must never alias immutable transport identity");
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external))assertEquals(1,mw.site.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.inbox WHERE envelope->>'eventType'='catalog.decision.bridge' AND status='APPLIED'",Integer.class));
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.update(CatalogKind.HUB,hub.value(),admin,
            current -> new CatalogCommands.UpdatePlan<>(null,Map.of(),
                () -> pair.external.db.runtime().update("UPDATE public.hub SET name='After decision' WHERE id=?",hub.value()),n->n==1)).status());
        exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external)) {
            assertEquals("After decision",mw.site.db.runtime().queryForObject("SELECT name FROM hub WHERE id=?",String.class,hub.value()));
            assertTrue(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
        }
        var beforeConcurrentEdit=campus.decisions.view(conflict);
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.update(CatalogKind.HUB,hub.value(),admin,
            current->new CatalogCommands.UpdatePlan<>(null,Map.of(),()->pair.external.db.runtime().update("UPDATE hub SET name='Edit concurrent with later decision' WHERE id=?",hub.value()),n->n==1)).status());
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(conflict,beforeConcurrentEdit.candidateOperations(),beforeConcurrentEdit.decisionHeads(),CatalogDecision.KEEP,beforeConcurrentEdit.candidateOperations().getFirst(),"Independent later review",admin));
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external)) {
            assertEquals("OPEN",mw.decisions.view(conflict).status());
            assertFalse(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
        }
        var finalView=campus.decisions.view(conflict);
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(conflict,finalView.candidateOperations(),finalView.decisionHeads(),CatalogDecision.KEEP,finalView.candidateOperations().getFirst(),"Observe concurrent edit",admin));
        exchangeMulti(campus,external,admin);
        for(var mw:List.of(campus,external))assertEquals("RESOLVED",mw.decisions.view(conflict).status());
    }

    @Test
    void multiwriterDecisionBridgeCannotSilentlyApproveLocallyDiscoveredCandidateRevocation() {
        Pair pair=pair();long actor=registerSharedUser(pair.campus,pair.external,"mw-bridge-actor"),admin=registerSharedUser(pair.campus,pair.external,"mw-bridge-admin");
        for(var site:List.of(pair.campus,pair.external)){promoteCampusAdmin(site,actor);promoteCampusAdmin(site,admin);}
        var hub=createHub(pair.campus,actor,"Baseline");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        campus.accounts.initialize(actor);external.accounts.initialize(actor);
        assertEquals(CatalogCommands.Status.APPLIED,pair.campus.commands.update(CatalogKind.HUB,hub.value(),admin,
            current->new CatalogCommands.UpdatePlan<>(null,Map.of(),()->pair.campus.db.runtime().update("UPDATE hub SET name='Campus candidate' WHERE id=?",hub.value()),n->n==1)).status());
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.update(CatalogKind.HUB,hub.value(),actor,
            current->new CatalogCommands.UpdatePlan<>(null,Map.of(),()->pair.external.db.runtime().update("UPDATE hub SET name='Unreviewed candidate' WHERE id=?",hub.value()),n->n==1)).status());
        deliverType(campus,external,CatalogEvents.ACTION,admin);
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.HUB,hub.candidateGid());var snapshot=external.decisions.view(conflict);
        UUID selected=snapshot.candidateOperations().stream().filter(id->pair.external.store.readRevision(id).action().canonicalPayload().get("name").equals("Unreviewed candidate")).findFirst().orElseThrow();
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(conflict,snapshot.candidateOperations(),List.of(),CatalogDecision.KEEP,selected,"Decision before revocation was known",admin));
        campus.accounts.command(admin,actor,()->pair.campus.db.runtime().update("UPDATE users SET role='user' WHERE id=?",actor));
        pair.external.db.transactions().required(()->{external.backfill.publish(CatalogKind.HUB,hub.candidateGid(),List.of());return null;});
        campus.accounts.receive(actor,external.accounts.changes(actor),external.accounts.witnesses(actor),new NodeIdentity(pair.external.db.site().getId(),pair.external.db.site().getInstanceId()));
        deliverType(external,campus,CatalogDecisionBackfill.TYPE,admin);
        assertEquals("AUTH_REVIEW",campus.decisions.view(conflict).status(),()->pair.campus.db.runtime().queryForList("SELECT status,last_error FROM eatwhat_sync.inbox").toString());
        assertFalse(pair.campus.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
        exchangeMulti(campus,external,admin,actor);external.reviewer.runOnce();
        assertEquals("AUTH_REVIEW",external.decisions.view(conflict).status());
        var review=campus.decisions.view(conflict);
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(conflict,review.candidateOperations(),review.decisionHeads(),CatalogDecision.KEEP,selected,"Explicit review after observing revocation",admin));
        exchangeMulti(campus,external,admin);
        for(var mw:List.of(campus,external)) {
            assertEquals("RESOLVED",mw.decisions.view(conflict).status());
            assertTrue(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
        }
    }

    @Test
    void multiwriterDelayedOriginalActionCannotUndoDecisionAppliedAfterBridge() {
        Pair pair=pair();long admin=registerSharedUser(pair.campus,pair.external,"mw-delayed-admin");
        for(var site:List.of(pair.campus,pair.external))promoteCampusAdmin(site,admin);
        var hub=createHub(pair.campus,admin,"Baseline");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        for(var entry:List.of(Map.entry(campus,"Campus snapshot"),Map.entry(external,"External snapshot"))) {
            var site=entry.getKey().site;String name=entry.getValue();
            assertEquals(CatalogCommands.Status.APPLIED,site.commands.update(CatalogKind.HUB,hub.value(),admin,
                current->new CatalogCommands.UpdatePlan<>(null,Map.of(),()->site.db.runtime().update("UPDATE hub SET name=? WHERE id=?",name,hub.value()),n->n==1)).status());
            assertEquals(CatalogCommands.BackfillStatus.APPLIED,site.commands.backfillCatalogObject(CatalogKind.HUB,hub.value(),CatalogBackfillStore.SYSTEM_SUBJECT,0));
        }
        deliverType(campus,external,CatalogEvents.BRIDGE,admin);deliverType(external,campus,CatalogEvents.BRIDGE,admin);
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.HUB,hub.candidateGid());
        var snapshot=campus.decisions.view(conflict);assertEquals(2,snapshot.candidateOperations().size());
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(conflict,snapshot.candidateOperations(),List.of(),CatalogDecision.KEEP,snapshot.candidateOperations().getFirst(),"Select bridged candidate",admin));
        deliverType(campus,external,CatalogDecisionEvents.ACTION,admin);
        deliverType(campus,external,CatalogEvents.ACTION,admin);deliverType(external,campus,CatalogEvents.ACTION,admin);
        for(var mw:List.of(campus,external)) {
            assertEquals("RESOLVED",mw.decisions.view(conflict).status());
            assertTrue(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
        }
    }
    private static void deliverType(MultiSite from,MultiSite to,String type,long actor) {
        to.accounts.receive(actor,from.accounts.changes(actor),from.accounts.witnesses(actor),new NodeIdentity(from.site.db.site().getId(),from.site.db.site().getInstanceId()));
        var envelopes=from.site.db.runtime().queryForList("SELECT envelope::text FROM eatwhat_sync.outbox WHERE envelope->>'eventType'=? AND envelope->>'schemaVersion'='2'",String.class,type);
        for(String raw:envelopes)to.inbox.receive(new EventCodec().decode(raw),from.site.db.site().getId());
        for(int round=0;round<3;round++){to.inbox.requeueWaiting(100,Duration.ZERO);for(var lease:to.inbox.claim(100,LEASE))to.inbox.process(lease);}
    }

    @Test
    void multiwriterImportsReplicateIdempotencyAndSourceClaims() {
        Pair pair=pair();long admin=registerSharedUser(pair.campus,pair.external,"mw-import-admin");
        promoteCampusAdmin(pair.campus,admin);promoteCampusAdmin(pair.external,admin);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        var items=List.of(hubImportItem("source-1","Imported external"));
        var request=new CatalogHubImportRequest("mw-source",UUID.randomUUID(),hubImportHash(items),items);
        assertEquals(CatalogCommands.ImportStatus.APPLIED,importer(external).importHubs(request,admin).status());
        exchangeMulti(external,campus,admin);
        assertEquals(CatalogCommands.ImportStatus.REPLAYED,importer(campus).importHubs(request,admin).status());
        long externalId=pair.external.db.runtime().queryForObject("SELECT numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key='mw-source'",Long.class);
        assertEquals(externalId,pair.campus.db.runtime().queryForObject("SELECT numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key='mw-source'",Long.class));
        assertEquals(1,pair.campus.db.runtime().queryForObject("SELECT count(*) FROM hub",Integer.class));
        var changed=List.of(hubImportItem("source-1","Changed from campus"));
        var second=new CatalogHubImportRequest("mw-source",UUID.randomUUID(),hubImportHash(changed),changed);
        assertEquals(CatalogCommands.ImportStatus.APPLIED,importer(campus).importHubs(second,admin).status());
        exchangeMulti(campus,external,admin);
        assertEquals("Changed from campus",pair.external.db.runtime().queryForObject("SELECT name FROM hub WHERE id=?",String.class,externalId));
        var incompatible=new CatalogHubImportRequest(request.sourceKey(),request.requestId(),hubImportHash(changed),changed);
        assertEquals(CatalogCommands.ImportStatus.CONFLICT,importer(campus).importHubs(incompatible,admin).status());
    }
    @Test
    void multiwriterConcurrentImportsKeepBothIdentitiesAndCanBeDecidedAtEitherSite() {
        Pair pair=pair();long admin=registerSharedUser(pair.campus,pair.external,"mw-source-admin");
        promoteCampusAdmin(pair.campus,admin);promoteCampusAdmin(pair.external,admin);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        for(var entry:List.of(Map.entry(campus,"First"),Map.entry(external,"Second"))) {
            var items=List.of(hubImportItem("same-source-id",entry.getValue()));
            var request=new CatalogHubImportRequest("shared-source",UUID.randomUUID(),hubImportHash(items),items);
            assertEquals(CatalogCommands.ImportStatus.APPLIED,importer(entry.getKey()).importHubs(request,admin).status());
        }
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        var ids=pair.campus.db.runtime().queryForList("SELECT DISTINCT object_gid FROM eatwhat_sync.catalog_import_claim ORDER BY object_gid",UUID.class);
        assertEquals(2,ids.size());
        UUID conflict=pair.campus.db.runtime().queryForObject("SELECT conflict_id FROM eatwhat_sync.catalog_conflict WHERE kind='HUB'",UUID.class);
        List<UUID> heads=pair.campus.db.runtime().query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE kind='HUB' ORDER BY 1",(rs,n)->rs.getObject(1,UUID.class));
        for(var mw:List.of(campus,external)) {
            assertEquals(0,mw.site.db.runtime().queryForObject("SELECT count(*) FROM hub WHERE catalog_visible",Integer.class));
            assertEquals(2,mw.site.db.runtime().queryForObject("SELECT count(*) FROM hub",Integer.class));
        }
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(conflict,heads,List.of(),CatalogDecision.KEEP,heads.getFirst(),"Retain chosen source identity",admin));
        exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external)) {
            assertEquals(1,mw.site.db.runtime().queryForObject("SELECT count(*) FROM hub WHERE catalog_visible",Integer.class));
            assertEquals(2,mw.site.db.runtime().queryForObject("SELECT count(*) FROM hub",Integer.class));
        }
        assertEquals(pair.campus.db.runtime().queryForObject("SELECT object_gid FROM eatwhat_sync.catalog_hub_source",UUID.class),pair.external.db.runtime().queryForObject("SELECT object_gid FROM eatwhat_sync.catalog_hub_source",UUID.class));
        var replacement=List.of(hubImportItem("same-source-id","Imported after conflict resolution"));
        var followup=new CatalogHubImportRequest("shared-source",UUID.randomUUID(),hubImportHash(replacement),replacement);
        assertEquals(CatalogCommands.ImportStatus.APPLIED,importer(campus).importHubs(followup,admin).status());
        exchangeMulti(campus,external,admin);
        for(var mw:List.of(campus,external))assertEquals(1,mw.site.db.runtime().queryForObject("SELECT count(*) FROM hub WHERE catalog_visible AND name='Imported after conflict resolution'",Integer.class));
    }

    @Test
    void multiwriterConcurrentRevocationCanBeReviewedAndRejectedAtEitherSite() {
        Pair pair=pair();long actor=registerSharedUser(pair.campus,pair.external,"mw-revoked-admin"),admin=registerSharedUser(pair.campus,pair.external,"mw-review-admin");
        for(var site:List.of(pair.campus,pair.external)){promoteCampusAdmin(site,actor);promoteCampusAdmin(site,admin);}
        var hub=createHub(pair.campus,actor,"Before disputed edit");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        campus.accounts.initialize(actor);external.accounts.initialize(actor);
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.update(CatalogKind.HUB,hub.value(),actor,
            current -> new CatalogCommands.UpdatePlan<>(null,Map.of(),
                () -> pair.external.db.runtime().update("UPDATE hub SET name='Disputed edit' WHERE id=?",hub.value()),n->n==1)).status());
        campus.accounts.command(admin,actor,()->pair.campus.db.runtime().update("UPDATE users SET role='user' WHERE id=?",actor));
        exchangeMulti(external,campus,actor);
        exchangeMulti(campus,external,admin,actor);
        campus.reviewer.runOnce();external.reviewer.runOnce();
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.HUB,hub.candidateGid());
        List<UUID> candidates=pair.campus.db.runtime().query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE kind='HUB' AND object_gid=? ORDER BY 1",(rs,n)->rs.getObject(1,UUID.class),hub.candidateGid());
        assertEquals(2,candidates.size());
        UUID original=candidates.stream().filter(id->pair.campus.store.readRevision(id).action().canonicalPayload().get("name").equals("Before disputed edit")).findFirst().orElseThrow();
        for(var mw:List.of(campus,external)) {
            assertFalse(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
            assertEquals(1,mw.site.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.catalog_authorization_review",Integer.class));
        }
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(conflict,candidates,List.of(),CatalogDecision.KEEP,original,"Reject disputed edit",admin));
        exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external)) {
            assertEquals("Before disputed edit",mw.site.db.runtime().queryForObject("SELECT name FROM hub WHERE id=?",String.class,hub.value()));
            assertTrue(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
        }
    }

    @Test
    void multiwriterCreationDuringRevocationIsHiddenUntilExplicitReview() {
        Pair pair=pair();long actor=registerSharedUser(pair.campus,pair.external,"mw-create-actor"),admin=registerSharedUser(pair.campus,pair.external,"mw-create-admin");
        for(var site:List.of(pair.campus,pair.external)){promoteCampusAdmin(site,actor);promoteCampusAdmin(site,admin);}
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        campus.accounts.initialize(actor);external.accounts.initialize(actor);
        var hub=createHub(pair.external,actor,"Disputed new hub");
        campus.accounts.command(admin,actor,()->pair.campus.db.runtime().update("UPDATE users SET role='user' WHERE id=?",actor));
        exchangeMulti(external,campus,actor);exchangeMulti(campus,external,admin,actor);
        campus.reviewer.runOnce();external.reviewer.runOnce();
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.HUB,hub.candidateGid());
        List<UUID> candidates=pair.external.db.runtime().query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE kind='HUB' AND object_gid=? ORDER BY 1",(rs,n)->rs.getObject(1,UUID.class),hub.candidateGid());
        assertEquals(1,candidates.size());
        assertEquals("AUTH_REVIEW",pair.external.db.runtime().queryForObject("SELECT status FROM eatwhat_sync.catalog_projection_state WHERE kind='HUB' AND object_gid=?",String.class,hub.candidateGid()));
        assertFalse(pair.external.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(conflict,candidates,List.of(),CatalogDecision.KEEP,candidates.getFirst(),"Approve independent creation",admin));
        exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external))assertTrue(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()),()->mw.site.db.runtime().queryForList("SELECT status,last_error FROM eatwhat_sync.inbox").toString());
    }

    @Test
    void multiwriterDecisionMadeDuringConcurrentRevocationRequiresFreshAdministratorReview() {
        Pair pair=pair();long actor=registerSharedUser(pair.campus,pair.external,"mw-decision-actor"),admin=registerSharedUser(pair.campus,pair.external,"mw-decision-admin");
        for(var site:List.of(pair.campus,pair.external)){promoteCampusAdmin(site,actor);promoteCampusAdmin(site,admin);}
        var hub=createHub(pair.campus,actor,"Before conflict");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        for(var entry:List.of(Map.entry(campus,"Campus"),Map.entry(external,"External"))) {
            var site=entry.getKey().site;String name=entry.getValue();
            assertEquals(CatalogCommands.Status.APPLIED,site.commands.update(CatalogKind.HUB,hub.value(),admin,
                current->new CatalogCommands.UpdatePlan<>(null,Map.of(),()->site.db.runtime().update("UPDATE hub SET name=? WHERE id=?",name,hub.value()),n->n==1)).status());
        }
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        campus.accounts.initialize(actor);external.accounts.initialize(actor);
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.HUB,hub.candidateGid());
        List<UUID> candidates=pair.campus.db.runtime().query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE kind='HUB' AND object_gid=? ORDER BY 1",(rs,n)->rs.getObject(1,UUID.class),hub.candidateGid());
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(conflict,candidates,List.of(),CatalogDecision.KEEP,candidates.getFirst(),"Disconnected decision",actor));
        campus.accounts.command(admin,actor,()->pair.campus.db.runtime().update("UPDATE users SET role='user' WHERE id=?",actor));
        exchangeMulti(external,campus,actor);exchangeMulti(campus,external,admin,actor);
        campus.reviewer.runOnce();external.reviewer.runOnce();
        for(var mw:List.of(campus,external)) {
            assertEquals("AUTH_REVIEW",mw.decisions.list().getFirst().status());
            assertFalse(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM hub WHERE id=?",Boolean.class,hub.value()));
        }
        var view=external.decisions.list().getFirst();
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(conflict,candidates,view.decisionHeads(),CatalogDecision.KEEP,candidates.getFirst(),"Fresh independent review",admin));
        exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external))assertEquals("RESOLVED",mw.decisions.list().getFirst().status());
    }

    @Test
    void multiwriterDisputedRestaurantDeleteRetainsChildrenAndCanBeRestored() {
        Pair pair=pair();long actor=registerSharedUser(pair.campus,pair.external,"mw-delete-actor"),admin=registerSharedUser(pair.campus,pair.external,"mw-delete-admin");
        for(var site:List.of(pair.campus,pair.external)){promoteCampusAdmin(site,actor);promoteCampusAdmin(site,admin);}
        var restaurant=createRestaurant(pair.campus,actor,"mw-delete-poi","Retained parent");
        var food=createFood(pair.campus,actor,restaurant.value(),"Retained child");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        campus.accounts.initialize(actor);external.accounts.initialize(actor);
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.delete(CatalogKind.RESTAURANT,restaurant.value(),actor,
            ()->pair.external.db.runtime().update("DELETE FROM restaurant WHERE id=?",restaurant.value()),n->n==1).status());
        assertEquals(1,pair.external.db.runtime().queryForObject("SELECT count(*) FROM foods WHERE id=?",Integer.class,food.value()));
        assertFalse(pair.external.db.runtime().queryForObject("SELECT catalog_visible FROM restaurant WHERE id=?",Boolean.class,restaurant.value()));
        campus.accounts.command(admin,actor,()->pair.campus.db.runtime().update("UPDATE users SET role='user' WHERE id=?",actor));
        exchangeMulti(external,campus,actor);exchangeMulti(campus,external,admin,actor);
        campus.reviewer.runOnce();external.reviewer.runOnce();
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.RESTAURANT,restaurant.candidateGid());
        List<UUID> candidates=pair.campus.db.runtime().query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE kind='RESTAURANT' AND object_gid=? ORDER BY 1",(rs,n)->rs.getObject(1,UUID.class),restaurant.candidateGid());
        assertEquals(2,candidates.size());
        UUID original=candidates.stream().filter(id->pair.campus.store.readRevision(id).action().operation()!=CatalogOperation.DELETE).findFirst().orElseThrow();
        assertEquals(CatalogCommands.Status.APPLIED,external.decisions.resolve(conflict,candidates,List.of(),CatalogDecision.KEEP,original,"Reject disputed deletion",admin));
        exchangeMulti(external,campus,admin);
        for(var mw:List.of(campus,external)) {
            assertTrue(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM restaurant WHERE id=?",Boolean.class,restaurant.value()),()->mw.site.db.runtime().queryForList("SELECT status,last_error FROM eatwhat_sync.inbox").toString());
            assertEquals(1,mw.site.db.runtime().queryForObject("SELECT count(*) FROM foods WHERE id=?",Integer.class,food.value()));
            assertFalse(mw.site.store.findByNumeric(CatalogKind.RESTAURANT,restaurant.value()).tombstone());
        }
        assertEquals(CatalogCommands.Status.APPLIED,pair.campus.commands.delete(CatalogKind.RESTAURANT,restaurant.value(),admin,
            ()->pair.campus.db.runtime().update("DELETE FROM restaurant WHERE id=?",restaurant.value()),n->n==1).status());
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.update(CatalogKind.RESTAURANT,restaurant.value(),admin,
            current->new CatalogCommands.UpdatePlan<>(null,Map.of("poi","mw-delete-poi"),()->pair.external.db.runtime().update("UPDATE restaurant SET name='Concurrent edit after terminal deletion' WHERE id=?",restaurant.value()),n->n==1)).status());
        exchangeMulti(campus,external,admin);exchangeMulti(external,campus,admin);
        var terminal=campus.decisions.view(conflict);
        UUID update=terminal.candidateOperations().stream().filter(id->pair.campus.store.readRevision(id).action().operation()!=CatalogOperation.DELETE).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->campus.decisions.resolve(conflict,terminal.candidateOperations(),terminal.decisionHeads(),CatalogDecision.KEEP,update,"Old review must not authorize a different deletion",admin));
        for(var mw:List.of(campus,external))assertTrue(mw.site.store.findByNumeric(CatalogKind.RESTAURANT,restaurant.value()).tombstone());
    }

    @Test
    void bannedOwnerDoesNotHideAdministratorDeletionReviewOrBlockItsReplication() {
        Pair pair=pair();long owner=registerSharedUser(pair.campus,pair.external,"mw-banned-owner"),admin=registerSharedUser(pair.campus,pair.external,"mw-banned-reviewer");
        for(var site:List.of(pair.campus,pair.external))promoteCampusAdmin(site,admin);
        var restaurant=createRestaurant(pair.campus,owner,"mw-banned-poi","Review parent");
        var food=createFood(pair.campus,owner,restaurant.value(),"Review deletion");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        campus.accounts.initialize(owner);external.accounts.initialize(owner);
        assertEquals(CatalogCommands.Status.APPLIED,pair.external.commands.delete(CatalogKind.FOOD,food.value(),owner,
                ()->pair.external.db.runtime().update("DELETE FROM foods WHERE id=?",food.value()),n->n==1).status());
        campus.accounts.command(admin,owner,()->pair.campus.db.runtime().update("UPDATE users SET banned=true WHERE id=?",owner));
        exchangeMulti(external,campus,owner);exchangeMulti(campus,external,admin,owner);
        campus.reviewer.runOnce();external.reviewer.runOnce();
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.FOOD,food.candidateGid());
        for(var mw:List.of(campus,external)) {
            assertFalse(mw.site.consent.replicationAllowed(owner));assertTrue(mw.site.consent.removalReplicationAllowed(owner));
            var service=conflictService(mw.site);service.setDecisionService(mw.decisions);
            assertTrue(service.listAuthorizationReviews().stream().anyMatch(r->r.conflictId().equals(conflict)));
            assertNotNull(service.decisionSnapshot(conflict));
        }
        var service=conflictService(pair.external);service.setDecisionService(external.decisions);var before=service.decisionSnapshot(conflict);
        assertEquals(CatalogCommands.Status.APPLIED,service.resolveDecision(conflict,new CatalogConflictService.ResolveDecisionRequest(
                before.candidateOperations(),before.decisionHeads(),CatalogDecision.REJECT,null,"Approve synthetic deletion after concurrent ban"),admin).status());
        exchangeMulti(external,campus,admin,owner);
        for(var mw:List.of(campus,external)) {
            assertEquals("REJECTED",mw.decisions.view(conflict).status());
            assertFalse(mw.site.db.runtime().queryForObject("SELECT catalog_visible FROM foods WHERE id=?",Boolean.class,food.value()));
            assertTrue(mw.site.db.runtime().queryForObject("SELECT banned FROM users WHERE id=?",Boolean.class,owner));
        }
    }

    @Test
    void multiwriterWithdrawalErasesPrivateCatalogEvidenceAndRegrantRestoresDecisionHistory() {
        Pair pair=pair();long owner=registerSharedUser(pair.campus,pair.external,"mw-private-owner"),admin=registerSharedUser(pair.campus,pair.external,"mw-private-admin");
        promoteCampusAdmin(pair.campus,admin);promoteCampusAdmin(pair.external,admin);
        var tag=createCustomTag(pair.campus,owner,"Private baseline");exchange(pair);
        MultiSite campus=multiSite(pair.campus),external=multiSite(pair.external);
        assertEquals(CatalogCommands.Status.APPLIED,renameCustomTag(pair.campus,tag.value(),owner,"Private campus").status());
        assertEquals(CatalogCommands.Status.APPLIED,renameCustomTag(pair.external,tag.value(),owner,"Private external").status());
        exchangeMulti(campus,external,owner);exchangeMulti(external,campus,owner);
        UUID conflict=CatalogStore.stableHeadConflictId(CatalogKind.CUSTOM_TAG,tag.candidateGid());
        List<UUID> candidates=pair.campus.db.runtime().query("SELECT (h->>'operationId')::uuid FROM eatwhat_sync.catalog_object o CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h WHERE kind='CUSTOM_TAG' AND object_gid=? ORDER BY 1",(rs,n)->rs.getObject(1,UUID.class),tag.candidateGid());
        assertEquals(CatalogCommands.Status.APPLIED,campus.decisions.resolve(conflict,candidates,List.of(),CatalogDecision.KEEP,candidates.getFirst(),"Private decision reason",admin));
        exchangeMulti(campus,external,admin);
        UUID subject=pair.campus.store.userGid(owner);
        assertEquals(CatalogCommands.BackfillStatus.APPLIED,pair.campus.commands.backfillCatalogObject(CatalogKind.CUSTOM_TAG,tag.value(),subject,pair.campus.consent.getBySubject(subject).epoch()));
        exchangeMulti(campus,external,owner,admin);
        pair.external.db.transactions().required(()->{
            pair.external.db.runtime().update("UPDATE eatwhat_consent.subject SET status='WITHDRAWN',epoch=epoch+1,identity_ready=false,valid_until=NULL WHERE subject_id=?",subject);
            assertTrue(pair.external.db.runtime().queryForObject("SELECT eatwhat_sync.prepare_catalog_withdrawal_cleanup(?,?)",Boolean.class,subject,owner));return null;
        });
        assertEquals(0,pair.external.db.runtime().queryForObject("SELECT count(*) FROM food_tags WHERE id=?",Integer.class,tag.value()));
        assertEquals(0,pair.external.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE object_gid=?",Integer.class,tag.candidateGid()));
        assertEquals(0,pair.external.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_sync.catalog_decision_operation WHERE conflict_id=?",Integer.class,conflict));
        for(var site:List.of(pair.campus,pair.external))site.db.runtime().update("UPDATE eatwhat_consent.subject SET status='GRANTED',epoch=100,identity_ready=true,valid_until=clock_timestamp()+interval '1 day' WHERE subject_id=?",subject);
        assertEquals(CatalogCommands.BackfillStatus.APPLIED,pair.campus.commands.backfillCatalogObject(CatalogKind.CUSTOM_TAG,tag.value(),subject,100));
        exchangeMulti(campus,external,owner,admin);external.backfill.runOnce();
        assertEquals("RESOLVED",external.decisions.list().getFirst().status());
        assertEquals(pair.campus.db.runtime().queryForObject("SELECT name FROM food_tags WHERE id=?",String.class,tag.value()),pair.external.db.runtime().queryForObject("SELECT name FROM food_tags WHERE id=?",String.class,tag.value()));
    }

    private static CatalogImportService importer(MultiSite site) {
        var result=new CatalogImportService(site.site.db.runtime(),site.site.db.transactions(),site.site.commands,site.site.db.site());
        result.setReplication(site.imports);return result;
    }

    private record MultiSite(CatalogSite site,MultiwriterAccounts accounts,CatalogDecisionService decisions,CatalogImportReplication imports,CatalogAuthorizationReviewWorker reviewer,CatalogDecisionBackfill backfill,InboxService inbox) { }
    private static MultiSite multiSite(CatalogSite site) {
        site.db.admin().update("UPDATE eatwhat_sync.catalog_multiwriter_config SET enabled=true WHERE singleton");
        site.db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true WHERE singleton");
        var accounts=new MultiwriterAccounts(site.db.runtime(),site.db.transactions(),site.db.site(),true);
        var support=new CatalogMultiwriterSupport(site.db.runtime(),accounts);
        site.commands.setMultiwriterSupport(support);site.properties.setMultiWriterEnabled(true);
        var projection=new CatalogProjection(site.db.runtime(),site.store,new EntityIdentityService(site.db.runtime()),site.db.site());
        var outbox=new OutboxService(site.db.runtime(),site.db.transactions(),new EventCodec(),site.db.site());
        var decisions=new CatalogDecisionService(site.db.runtime(),site.db.transactions(),site.store,support,outbox,site.db.site(),site.commands,site.consent);
        String authority=site.db.site().getMode()==SiteProperties.Mode.CAMPUS?site.db.site().getId():site.peerSiteId;
        Set<String> sites=Set.of(site.db.site().getId(),site.peerSiteId);
        var handlers=new java.util.ArrayList<EventHandler>();
        for(String type:List.of(CatalogEvents.ACTION,CatalogEvents.BRIDGE)) {
            handlers.add(new CatalogEventHandler(type,site.db.runtime(),site.store,projection,site.consent,sites,authority));
            handlers.add(new CatalogMultiwriterHandler(new CatalogEventHandler(type,site.db.runtime(),site.store,projection,site.consent,sites,authority),support,site.db.runtime(),()->authority));
        }
        handlers.add(new CatalogDecisionHandler(decisions,sites));
        var imports=new CatalogImportReplication(site.db.runtime(),site.store,support,site.commands,outbox,site.db.site(),sites);handlers.add(imports);
        var staging=new CatalogEventHandler(CatalogEvents.BRIDGE,site.db.runtime(),site.store,projection,site.consent,sites,authority);
        var backfill=new CatalogDecisionBackfill(site.db.runtime(),site.db.transactions(),support,decisions,site.commands,site.consent,outbox,site.db.site(),sites,staging,site.store);handlers.add(backfill);
        var review=new CatalogAuthorizationReviewWorker(site.db.runtime(),site.db.transactions(),support,(CatalogMultiwriterHandler)handlers.get(1),decisions);
        review.setBridgeHandler((CatalogMultiwriterHandler)handlers.get(3));
        return new MultiSite(site,accounts,decisions,imports,review,backfill,new InboxService(site.db.runtime(),site.db.transactions(),new EventCodec(),new EventHandlerRegistry(handlers)));
    }
    private static void exchangeMulti(MultiSite from,MultiSite to,long... actors) {
        for(long actor:actors)to.accounts.receive(actor,from.accounts.changes(actor),from.accounts.witnesses(actor),new NodeIdentity(from.site.db.site().getId(),from.site.db.site().getInstanceId()));
        for(var lease:from.site.delivery.claimForAggregates(to.site.db.site().getId(),100,LEASE,Set.of(CatalogEvents.AGGREGATE,CatalogDecisionEvents.AGGREGATE,CatalogImportReplication.AGGREGATE))) {
            to.inbox.receive(lease.event(),from.site.db.site().getId());assertTrue(from.site.delivery.acknowledge(lease,"DURABLY_QUEUED"));
        }
        for(int round=0;round<3;round++) {
            to.inbox.requeueWaiting(100,Duration.ZERO);
            for(var lease:to.inbox.claim(100,LEASE))to.inbox.process(lease);
        }
    }

    @Test
    void campusFoodAuthorCanUpdateAndDeleteManagedFoodWithoutCatalogAdminPrivileges() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "food-owner");
        long stranger = registerSharedUser(pair.campus, pair.external, "food-stranger");
        var restaurant = createRestaurant(pair.campus, author, "owner-food-poi", "Owner restaurant");
        long food = createFood(pair.campus, author, restaurant.value(), "Owner food").value();
        exchange(pair);
        // Exercise the actual restricted-role SQL boundary, including spoofed author arguments.
        for (String operation : List.of("UPDATE", "DELETE", "HUB_ASSIGNMENT")) {
            assertCatalogWriteDenied(pair.campus, "RESTAURANT", restaurant.value(), author, author, operation);
            assertCatalogWriteDenied(pair.campus, "FOOD", food, stranger, stranger, operation);
            assertCatalogWriteDenied(pair.external, "FOOD", food, author, author, operation);
        }
        assertCatalogWriteDenied(pair.campus, "FOOD", food, author, author, "HUB_ASSIGNMENT");
        assertCatalogWriteDenied(pair.campus, "SYSTEM_TAG", food, author, author, "UPDATE");
        assertCatalogWriteDenied(pair.campus, "HUB", food, author, author, "UPDATE");
        assertEquals(CatalogCommands.Status.APPLIED, pair.campus.commands.update(
                CatalogKind.FOOD, food, author, current -> new CatalogCommands.UpdatePlan<>(
                        restaurant.value(), Map.of("restaurantId", restaurant.value(), "name", "Owner edited"),
                        () -> pair.campus.db.runtime().update("UPDATE public.foods SET name=? WHERE id=? AND account_id=?",
                                "Owner edited", food, author), rows -> rows == 1)).status());
        exchange(pair);
        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            assertEquals("Owner edited", site.db.runtime().queryForObject(
                    "SELECT name FROM public.foods WHERE id=?", String.class, food));
        }
        assertEquals(CatalogCommands.Status.APPLIED, pair.campus.commands.delete(CatalogKind.FOOD, food, author,
                () -> pair.campus.db.runtime().update("DELETE FROM public.foods WHERE id=? AND account_id=?", food, author),
                rows -> rows == 1).status());
        exchange(pair);
        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            assertEquals(0, site.db.runtime().queryForObject("SELECT count(*) FROM public.foods WHERE id=?", Integer.class, food));
            assertTrue(site.store.findByNumeric(CatalogKind.FOOD, food).tombstone());
        }
        promoteCampusAdmin(pair.campus, stranger);
        assertEquals(CatalogCommands.Status.APPLIED, pair.campus.commands.delete(CatalogKind.RESTAURANT,
                restaurant.value(), stranger, () -> pair.campus.db.runtime().update(
                        "DELETE FROM public.restaurant WHERE id=?", restaurant.value()), rows -> rows == 1).status());
    }

    private static you.v50to.eatwhat.sync.catalog.CatalogMultiwriterSupport activateMultiwriter(CatalogSite site) {
        site.db.admin().update("UPDATE eatwhat_sync.catalog_multiwriter_config SET enabled=true WHERE singleton");
        site.db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true WHERE singleton");
        var accounts=new you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts(site.db.runtime(),site.db.transactions(),site.db.site(),true);
        var support=new you.v50to.eatwhat.sync.catalog.CatalogMultiwriterSupport(site.db.runtime(),accounts);
        site.commands.setMultiwriterSupport(support);site.properties.setMultiWriterEnabled(true);
        return support;
    }

    private static void assertCatalogWriteDenied(CatalogSite site, String kind, long id, long actor,
                                                 long claimedAuthor, String operation) {
        var failure = assertThrows(org.springframework.dao.DataAccessException.class, () ->
                site.db.transactions().required(() -> site.db.runtime().queryForObject("""
                    SELECT eatwhat_sync.begin_catalog_business_write(?::text,?::bigint,?::bigint,
                        ?::bigint,NULL::bigint,?::text,false)
                    """, Boolean.class, kind, id, actor, claimedAuthor, operation)));
        assertInstanceOf(java.sql.SQLException.class, failure.getMostSpecificCause());
        assertEquals("42501", ((java.sql.SQLException) failure.getMostSpecificCause()).getSQLState());
    }

    @Test
    void samePoiCreatedAtBothSitesRetainsBothCandidatesWithoutReplacingEitherNumericIdentity() throws Exception {
        Pair pair = pair();
        long authorA = registerSharedUser(pair.campus, pair.external, "catalog-author-a");
        long authorB = registerSharedUser(pair.campus, pair.external, "catalog-author-b");
        var campusCreate = createRestaurant(pair.campus, authorA, "POI-exact-17", "Campus candidate");
        var externalCreate = createRestaurant(pair.external, authorB, "POI-exact-17", "External candidate");
        assertEquals(CatalogCommands.Status.APPLIED, campusCreate.status());
        assertEquals(CatalogCommands.Status.APPLIED, externalCreate.status());
        assertNotEquals(campusCreate.candidateGid(), externalCreate.candidateGid(),
                "A natural-key collision never merges distinct object identities");

        exchange(pair);

        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            assertEquals(1, site.db.runtime().queryForObject("""
                    SELECT count(*) FROM eatwhat_sync.catalog_conflict
                    WHERE kind='RESTAURANT' AND status='OPEN'
                    """, Integer.class), "Both sites must persist the unresolved natural-key collision");
            assertEquals(1, site.db.runtime().queryForObject(
                    "SELECT count(*) FROM public.restaurant WHERE poi='POI-exact-17'", Integer.class),
                    "An incoming collision cannot violate the legacy unique POI index or replace the local row");
            assertEquals(2, site.db.runtime().queryForObject("""
                    SELECT count(*) FROM eatwhat_sync.catalog_candidate c
                    JOIN eatwhat_sync.catalog_conflict f ON f.kind=c.kind AND f.key_hash=c.key_hash
                    WHERE f.status='OPEN' AND c.kind='RESTAURANT' AND c.status='PENDING'
                    """, Integer.class));
        }
        CatalogConflictService.ConflictView conflict = conflictService(pair.campus).listOpenConflicts().getFirst();
        assertEquals(CatalogKind.RESTAURANT, conflict.kind());
        assertTrue(conflict.naturalKey().contains("POI-exact-17"),
                "The conflict retains exact POI data in addition to its digest");
        assertEquals("OPEN", conflict.status());
        assertEquals(2, conflict.candidates().size());
        assertEquals(Set.of(pair.campus.db.site().getId(), pair.external.db.site().getId()),
                conflict.candidates().stream().map(CatalogConflictService.CandidateView::sourceSite)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(campusCreate.value(), conflict.candidates().stream()
                .filter(candidate -> candidate.gid().equals(campusCreate.candidateGid()))
                .findFirst().orElseThrow().numericId());
        assertEquals(externalCreate.value(), conflict.candidates().stream()
                .filter(candidate -> candidate.gid().equals(externalCreate.candidateGid()))
                .findFirst().orElseThrow().numericId());
        assertThrows(BizException.class, () -> conflictService(pair.external).listOpenConflicts(),
                "The external site may inspect durable ledger state but cannot use the campus admin API");
        assertEquals(campusCreate.candidateGid(), pair.campus.store.findByNumeric(CatalogKind.RESTAURANT,
                campusCreate.value()).gid());
        assertEquals(externalCreate.candidateGid(), pair.external.store.findByNumeric(CatalogKind.RESTAURANT,
                externalCreate.value()).gid());
    }

    @Test
    void keepResolutionRejectsStaleCandidateSetAndLateThirdCandidateReopensCompleteConflict() {
        Pair pair = pair();
        long campusAuthor = registerSharedUser(pair.campus, pair.external, "catalog-keep-campus");
        long externalAuthor = registerSharedUser(pair.campus, pair.external, "catalog-keep-external");
        promoteCampusAdmin(pair.campus, campusAuthor);
        long campusAdmin = campusAuthor;
        var campusCandidate = createRestaurant(pair.campus, campusAuthor, "POI-keep-late-third", "Campus winner");
        var externalCandidate = createRestaurant(pair.external, externalAuthor, "POI-keep-late-third", "External loser");
        var campusUpdate = pair.campus.commands.update(CatalogKind.RESTAURANT, campusCandidate.value(),
                campusAuthor, current -> new CatalogCommands.UpdatePlan<>(null, null,
                        () -> pair.campus.db.runtime().update(
                                "UPDATE public.restaurant SET name=? WHERE id=?",
                                "Campus candidate revised before KEEP", campusCandidate.value()), rows -> rows == 1));
        assertEquals(CatalogCommands.Status.APPLIED, campusUpdate.status());
        exchange(pair);

        CatalogConflictService conflicts = conflictService(pair.campus);
        List<CatalogConflictService.ConflictView> open = conflicts.listOpenConflicts();
        assertFalse(open.isEmpty(), "The create/update and peer collision must converge to an OPEN conflict. "
                + catalogConflictDiagnostics(pair));
        CatalogConflictService.ConflictView initial = open.getFirst();
        assertEquals(2, initial.candidates().size());
        CatalogConflictService.CandidateView campusVersion = initial.candidates().stream()
                .filter(candidate -> candidate.gid().equals(campusCandidate.candidateGid()))
                .findFirst().orElseThrow();
        assertEquals(pair.campus.store.findObject(CatalogKind.RESTAURANT, campusCandidate.candidateGid()).revision(),
                campusVersion.revision(),
                "CREATE followed by UPDATE must resolve the current revision/hash, not the stale CREATE head");
        var stale = new CatalogConflictService.ResolveRequest(initial.conflictId(), initial.revision(),
                List.of(new CatalogConflictService.CandidateVersion(campusCandidate.candidateGid(),
                        initial.candidates().stream().filter(c -> c.gid().equals(campusCandidate.candidateGid()))
                                .findFirst().orElseThrow().revision(),
                        initial.candidates().stream().filter(c -> c.gid().equals(campusCandidate.candidateGid()))
                                .findFirst().orElseThrow().contentHash())),
                CatalogDecision.KEEP, campusCandidate.candidateGid(), "stale incomplete set");
        assertEquals(CatalogCommands.Status.CONFLICT, conflicts.resolve(stale, campusAdmin).status(),
                "A resolution must compare the complete candidate set, not just its winner");
        assertEquals(2, conflicts.listOpenConflicts().getFirst().candidates().size());

        var versions = initial.candidates().stream().map(candidate ->
                new CatalogConflictService.CandidateVersion(candidate.gid(), candidate.revision(), candidate.contentHash()))
                .toList();
        var keep = new CatalogConflictService.ResolveRequest(initial.conflictId(), initial.revision(), versions,
                CatalogDecision.KEEP, externalCandidate.candidateGid(), "keep the unprojected external candidate");
        assertEquals(CatalogCommands.Status.APPLIED, conflicts.resolve(keep, campusAdmin).status());
        exchange(pair);
        assertEquals(0, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, campusCandidate.value()),
                "The resolution event must retire the rejected local projection");
        assertEquals(1, pair.campus.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, externalCandidate.value()),
                "A KEEP decision can project a peer candidate which was absent from the receiving public table");
        assertEquals(1, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, externalCandidate.value()));
        assertEquals(externalCandidate.candidateGid(), pair.external.store.findByNumeric(
                CatalogKind.RESTAURANT, externalCandidate.value()).gid(),
                "Keeping a peer candidate cannot rebind its stable numeric/GID identity");

        int revisionsBeforeBridge = revisionCount(pair.campus, externalCandidate.candidateGid());
        int bridgeEventsBefore = stateBridgeCount(pair.external);
        // This account was registered on CAMPUS, so CAMPUS remains the consent authority
        // even though the selected catalog candidate was authored on EXTERNAL.
        pair.campus.consent.withdraw(externalAuthor);
        var regrant = pair.campus.consent.grant(externalAuthor, POLICY);
        deliverConsentSnapshots(pair.campus, pair.external, externalAuthor);
        assertEquals(regrant.epoch(), pair.campus.consent.getBySubject(
                pair.external.store.userGid(externalAuthor)).epoch());
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, pair.external.commands.backfillCatalogObject(
                CatalogKind.RESTAURANT, externalCandidate.value(), pair.external.store.userGid(externalAuthor),
                regrant.epoch()));
        exchange(pair);
        assertKeptExternalCandidate(pair, campusCandidate, externalCandidate);
        assertEquals(revisionsBeforeBridge, revisionCount(pair.campus, externalCandidate.candidateGid()));
        assertEquals(bridgeEventsBefore + 1, stateBridgeCount(pair.external),
                "Re-grant publishes the selected revision and resolution proof without minting a revision");
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, pair.external.commands.backfillCatalogObject(
                CatalogKind.RESTAURANT, externalCandidate.value(), pair.external.store.userGid(externalAuthor),
                regrant.epoch()));
        assertEquals(bridgeEventsBefore + 1, stateBridgeCount(pair.external),
                "Repeated backfill for the same heads and consent epoch is idempotent");
        exchange(pair);
        assertKeptExternalCandidate(pair, campusCandidate, externalCandidate);

        UUID originalCreate = pair.external.db.runtime().queryForObject("""
                SELECT operation_id FROM eatwhat_sync.catalog_revision
                WHERE kind='RESTAURANT' AND object_gid=? AND operation='CREATE'
                  AND source_site=? ORDER BY revision LIMIT 1
                """, UUID.class, externalCandidate.candidateGid(), pair.external.db.site().getId());
        SyncEvent staleCreate = new EventCodec().decode(pair.external.db.runtime().queryForObject(
                "SELECT envelope::text FROM eatwhat_sync.outbox WHERE event_id=?", String.class, originalCreate));
        pair.campus.inbox.receive(staleCreate, pair.external.db.site().getId());
        process(pair.campus);
        assertKeptExternalCandidate(pair, campusCandidate, externalCandidate);

        UUID thirdGid = UUID.randomUUID();
        CatalogEvents.Action thirdAction = syntheticRestaurantCreate(pair.external, externalAuthor,
                thirdGid, 1_000_000_102L, "POI-keep-late-third", "Late offline third candidate");
        SyncEvent late = syntheticCatalogActionEvent(pair.external, thirdAction, externalAuthor,
                "catalog-late-offline-candidate");
        CatalogEvents.validateEvent(late, pair.external.db.site().getId(),
                Set.of(pair.campus.db.site().getId(), pair.external.db.site().getId()));
        pair.campus.inbox.receive(late, pair.external.db.site().getId());
        process(pair.campus);
        pair.external.inbox.receive(late, pair.external.db.site().getId());
        process(pair.external);
        assertEquals("APPLIED", pair.campus.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, late.eventId()));
        assertEquals("APPLIED", pair.external.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, late.eventId()));

        CatalogConflictService.ConflictView reopened = conflictService(pair.campus).listOpenConflicts().getFirst();
        assertEquals(initial.conflictId(), reopened.conflictId());
        assertTrue(reopened.revision() > initial.revision());
        assertEquals(Set.of(externalCandidate.candidateGid(), thirdGid),
                reopened.candidates().stream().map(CatalogConflictService.CandidateView::gid)
                        .collect(java.util.stream.Collectors.toSet()),
                "The kept peer winner and late offline candidate form the complete set; rejected loser stays out");
        assertEquals(campusCandidate.candidateGid(), pair.campus.store.findByNumeric(
                CatalogKind.RESTAURANT, campusCandidate.value()).gid());
        assertEquals(externalCandidate.candidateGid(), pair.campus.store.findByNumeric(
                CatalogKind.RESTAURANT, externalCandidate.value()).gid());

        var reopenedVersions = reopened.candidates().stream().map(candidate ->
                new CatalogConflictService.CandidateVersion(candidate.gid(), candidate.revision(), candidate.contentHash()))
                .toList();
        var secondKeep = new CatalogConflictService.ResolveRequest(reopened.conflictId(), reopened.revision(),
                reopenedVersions, CatalogDecision.KEEP, externalCandidate.candidateGid(),
                "keep the original external identity after the late candidate");
        assertEquals(CatalogCommands.Status.APPLIED, conflictService(pair.campus)
                .resolve(secondKeep, campusAdmin).status());
        exchange(pair);
        SyncEvent secondResolutionEvent = catalogEvents(pair.campus).stream()
                .filter(event -> CatalogEvents.ACTION.equals(event.eventType()))
                .filter(event -> CatalogEvents.parseAction(event.payloadJson()).resolutionProof() != null)
                .max(Comparator.comparingLong(event -> CatalogEvents.parseAction(event.payloadJson()).revision()))
                .orElseThrow();
        assertEquals("APPLIED", pair.external.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class,
                secondResolutionEvent.eventId()), "External must apply the second resolution before stale D1 replay");
        assertKeptExternalCandidate(pair, campusCandidate, externalCandidate);
        assertEquals(0, pair.campus.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, 1_000_000_102L),
                "The rejected late candidate must not receive a public row");

        UUID externalSubject = pair.external.store.userGid(externalAuthor);
        long currentExternalEpoch = pair.campus.consent.getBySubject(externalSubject).epoch();
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, pair.campus.commands.backfillCatalogObject(
                CatalogKind.RESTAURANT, externalCandidate.value(), externalSubject, currentExternalEpoch));
        exchange(pair);
        int bridgesAfterSecondDecision = stateBridgeCount(pair.campus);
        int factsAfterSecondDecision = revisionCount(pair.campus, externalCandidate.candidateGid());
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, pair.campus.commands.backfillCatalogObject(
                CatalogKind.RESTAURANT, externalCandidate.value(), externalSubject, currentExternalEpoch));
        exchange(pair);
        assertEquals(bridgesAfterSecondDecision, stateBridgeCount(pair.campus),
                "Relaying the second resolution proof is idempotent");
        assertEquals(factsAfterSecondDecision, revisionCount(pair.campus, externalCandidate.candidateGid()));
        assertKeptExternalCandidate(pair, campusCandidate, externalCandidate);
    }

    @Test
    void delayedFirstNaturalKeyKeepBridgeCannotUndoSecondKeepAfterLateThirdCandidate() {
        Pair pair = pair();
        long campusAuthor = registerSharedUser(pair.campus, pair.external, "catalog-natural-proof-campus");
        long externalAuthor = registerSharedUser(pair.campus, pair.external, "catalog-natural-proof-external");
        promoteCampusAdmin(pair.campus, campusAuthor);

        var campusCandidate = createRestaurant(pair.campus, campusAuthor,
                "POI-natural-proof-late-c", "Campus winner");
        var externalCandidate = createRestaurant(pair.external, externalAuthor,
                "POI-natural-proof-late-c", "External first loser");
        assertEquals(CatalogCommands.Status.APPLIED, campusCandidate.status());
        assertEquals(CatalogCommands.Status.APPLIED, externalCandidate.status());
        exchange(pair);

        CatalogConflictService conflicts = conflictService(pair.campus);
        var firstConflict = conflicts.listOpenConflicts().stream()
                .filter(item -> item.naturalKey().contains("POI-natural-proof-late-c"))
                .findFirst().orElseThrow(() -> new AssertionError(catalogConflictDiagnostics(pair)));
        var firstCandidates = firstConflict.candidates().stream().map(candidate ->
                new CatalogConflictService.CandidateVersion(candidate.gid(), candidate.revision(), candidate.contentHash()))
                .toList();
        var firstKeep = new CatalogConflictService.ResolveRequest(firstConflict.conflictId(), firstConflict.revision(),
                firstCandidates, CatalogDecision.KEEP, campusCandidate.candidateGid(), "D1 keep campus identity");
        assertEquals(CatalogCommands.Status.APPLIED, conflicts.resolve(firstKeep, campusAuthor).status());
        exchange(pair);
        assertEquals(campusCandidate.candidateGid(), pair.external.store.findByNumeric(
                CatalogKind.RESTAURANT, campusCandidate.value()).gid());
        assertEquals(0, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, externalCandidate.value()));

        SyncEvent firstResolutionEvent = catalogEvents(pair.campus).stream()
                .filter(event -> CatalogEvents.ACTION.equals(event.eventType()))
                .filter(event -> CatalogEvents.parseAction(event.payloadJson()).objectGid()
                        .equals(campusCandidate.candidateGid()))
                .filter(event -> CatalogEvents.parseAction(event.payloadJson()).resolutionProof() != null)
                .max(Comparator.comparingLong(event -> CatalogEvents.parseAction(event.payloadJson()).revision()))
                .orElseThrow();
        CatalogEvents.Action firstResolutionAction = CatalogEvents.parseAction(firstResolutionEvent.payloadJson());
        CatalogEvents.Head firstResolutionHead = catalogHead(pair.campus, firstResolutionAction);

        // This candidate was created while EXTERNAL was offline, before the first KEEP
        // reached it. Deliver that immutable CREATE history to both replicas; do not try
        // to create a duplicate public POI after the winner has already been projected.
        UUID lateGid = UUID.randomUUID();
        long lateNumericId = 1_000_000_104L;
        CatalogEvents.Action lateAction = syntheticRestaurantCreate(pair.external, externalAuthor,
                lateGid, lateNumericId, "POI-natural-proof-late-c", "Late external candidate");
        SyncEvent lateEvent = syntheticCatalogActionEvent(pair.external, lateAction, externalAuthor,
                "catalog-natural-key-offline-third");
        pair.campus.inbox.receive(lateEvent, pair.external.db.site().getId());
        process(pair.campus);
        pair.external.inbox.receive(lateEvent, pair.external.db.site().getId());
        process(pair.external);
        assertEquals("APPLIED", pair.campus.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, lateEvent.eventId()));
        assertEquals("APPLIED", pair.external.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, lateEvent.eventId()));
        var lateCandidate = new CatalogCommands.WriteOutcome<>(CatalogCommands.Status.APPLIED,
                lateNumericId, lateGid, null);
        var reopened = conflicts.listOpenConflicts().stream()
                .filter(item -> item.conflictId().equals(firstConflict.conflictId()))
                .findFirst().orElseThrow(() -> new AssertionError(catalogConflictDiagnostics(pair)));
        assertEquals(Set.of(campusCandidate.candidateGid(), lateCandidate.candidateGid()),
                reopened.candidates().stream().map(CatalogConflictService.CandidateView::gid)
                        .collect(java.util.stream.Collectors.toSet()));

        var secondCandidates = reopened.candidates().stream().map(candidate ->
                new CatalogConflictService.CandidateVersion(candidate.gid(), candidate.revision(), candidate.contentHash()))
                .toList();
        var secondKeep = new CatalogConflictService.ResolveRequest(reopened.conflictId(), reopened.revision(),
                secondCandidates, CatalogDecision.KEEP, campusCandidate.candidateGid(), "D2 keep original identity");
        assertEquals(CatalogCommands.Status.APPLIED, conflicts.resolve(secondKeep, campusAuthor).status());
        exchange(pair);

        SyncEvent secondResolutionEvent = catalogEvents(pair.campus).stream()
                .filter(event -> CatalogEvents.ACTION.equals(event.eventType()))
                .filter(event -> CatalogEvents.parseAction(event.payloadJson()).objectGid()
                        .equals(campusCandidate.candidateGid()))
                .filter(event -> CatalogEvents.parseAction(event.payloadJson()).resolutionProof() != null)
                .max(Comparator.comparingLong(event -> CatalogEvents.parseAction(event.payloadJson()).revision()))
                .orElseThrow();
        assertNotEquals(firstResolutionEvent.eventId(), secondResolutionEvent.eventId());
        assertEquals("APPLIED", pair.external.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class,
                secondResolutionEvent.eventId()), "Both sites must apply D2 before the delayed D1 bridge arrives");

        UUID authorGid = pair.campus.store.userGid(campusAuthor);
        long auditBefore = pair.campus.db.runtime().queryForObject("""
                SELECT count(*) FROM eatwhat_sync.catalog_conflict_audit WHERE conflict_id=?
                """, Long.class, firstConflict.conflictId());
        long conflictRevisionBefore = pair.campus.db.runtime().queryForObject("""
                SELECT revision FROM eatwhat_sync.catalog_conflict WHERE conflict_id=?
                """, Long.class, firstConflict.conflictId());
        pair.campus.consent.withdraw(campusAuthor);
        var currentGrant = pair.campus.consent.grant(campusAuthor, POLICY);
        deliverConsentSnapshots(pair.campus, pair.external, campusAuthor);
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, pair.external.commands.backfillCatalogObject(
                CatalogKind.RESTAURANT, campusCandidate.value(), authorGid, currentGrant.epoch()));
        exchange(pair);

        long revisionBeforeOldBridge = pair.campus.store.findObject(
                CatalogKind.RESTAURANT, campusCandidate.candidateGid()).revision();
        Set<UUID> d1Subjects = new LinkedHashSet<>();
        CatalogEvents.ResolutionProof d1Proof = firstResolutionHead.resolutionProof();
        assertNotNull(d1Proof);
        for (UUID operationId : d1Proof.expectedHeads()) {
            d1Subjects.addAll(pair.campus.store.subjectsForOperation(operationId));
        }
        if (d1Proof.winnerOperationId() != null) {
            d1Subjects.addAll(pair.campus.store.subjectsForOperation(d1Proof.winnerOperationId()));
        }
        List<CatalogEvents.ConsentEpoch> d1Epochs = d1Subjects.stream().sorted()
                .map(subject -> {
                    var grant = pair.external.consent.getBySubject(subject);
                    assertNotNull(grant, "D1 proof subject must have a current grant at the receiver");
                    return new CatalogEvents.ConsentEpoch(subject, grant.epoch());
                }).toList();
        var oldBridge = new CatalogEvents.StateBridge(CatalogKind.RESTAURANT, campusCandidate.candidateGid(),
                campusCandidate.value(), pair.campus.db.site().getId(), pair.campus.db.site().getId(), null,
                d1Epochs, List.of(firstResolutionHead));
        SyncEvent delayedD1 = bridgeEvent(pair.external, oldBridge);
        CatalogEvents.validateEvent(delayedD1, pair.external.db.site().getId(),
                Set.of(pair.campus.db.site().getId(), pair.external.db.site().getId()));
        pair.campus.inbox.receive(delayedD1, pair.external.db.site().getId());
        process(pair.campus);

        assertEquals("APPLIED", pair.campus.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, delayedD1.eventId()));
        assertEquals("Campus winner", pair.campus.db.runtime().queryForObject(
                "SELECT name FROM public.restaurant WHERE id=?", String.class, campusCandidate.value()));
        assertEquals("Campus winner", pair.external.db.runtime().queryForObject(
                "SELECT name FROM public.restaurant WHERE id=?", String.class, campusCandidate.value()));
        assertEquals(0, pair.campus.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, lateCandidate.value()));
        assertEquals(revisionBeforeOldBridge, pair.campus.store.findObject(
                CatalogKind.RESTAURANT, campusCandidate.candidateGid()).revision());
        assertEquals(conflictRevisionBefore, pair.campus.db.runtime().queryForObject("""
                SELECT revision FROM eatwhat_sync.catalog_conflict WHERE conflict_id=?
                """, Long.class, firstConflict.conflictId()));
        assertEquals(auditBefore, pair.campus.db.runtime().queryForObject("""
                SELECT count(*) FROM eatwhat_sync.catalog_conflict_audit WHERE conflict_id=?
                """, Long.class, firstConflict.conflictId()));
        assertKeptCampusCandidate(pair, campusCandidate, lateCandidate);
    }

    @Test
    void movingOrDeletingRestaurantReleasesItsOldPoiNaturalKey() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "catalog-natural-key-author");
        promoteCampusAdmin(pair.campus, author);

        var beforeUpdate = createRestaurant(pair.campus, author, "POI-released-by-update", "Before update");
        assertEquals(CatalogCommands.Status.APPLIED, beforeUpdate.status());
        var updated = pair.campus.commands.update(CatalogKind.RESTAURANT, beforeUpdate.value(), author, current ->
                new CatalogCommands.UpdatePlan<>(null, Map.of("poi", "POI-after-update"),
                        () -> pair.campus.db.runtime().update(
                                "UPDATE public.restaurant SET poi=? WHERE id=?",
                                "POI-after-update", beforeUpdate.value()),
                        changed -> changed == 1));
        assertEquals(CatalogCommands.Status.APPLIED, updated.status());
        assertEquals(0, openConflictCount(pair.campus, "POI-released-by-update"),
                "The former natural key must not retain a ghost candidate after an update");

        var replacementAfterUpdate = createRestaurant(pair.campus, author,
                "POI-released-by-update", "Replacement after update");
        assertEquals(CatalogCommands.Status.APPLIED, replacementAfterUpdate.status());

        var beforeDelete = createRestaurant(pair.campus, author, "POI-released-by-delete", "Before delete");
        assertEquals(CatalogCommands.Status.APPLIED, beforeDelete.status());
        var deleted = pair.campus.commands.delete(CatalogKind.RESTAURANT, beforeDelete.value(), author,
                () -> pair.campus.db.runtime().update("DELETE FROM public.restaurant WHERE id=?", beforeDelete.value()),
                changed -> changed == 1);
        assertEquals(CatalogCommands.Status.APPLIED, deleted.status());
        assertEquals(0, openConflictCount(pair.campus, "POI-released-by-delete"),
                "A terminal delete must not reserve the old natural key forever");

        var replacementAfterDelete = createRestaurant(pair.campus, author,
                "POI-released-by-delete", "Replacement after delete");
        assertEquals(CatalogCommands.Status.APPLIED, replacementAfterDelete.status());
        assertEquals(1, pair.campus.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE poi='POI-released-by-update'", Integer.class));
        assertEquals(1, pair.campus.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE poi='POI-released-by-delete'", Integer.class));
    }

    @Test
    void movingRestaurantBackToRetiredPoiReactivatesItsCandidateBeforeACompetingCreateArrives() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "catalog-poi-reactivate");
        promoteCampusAdmin(pair.campus, author);
        var original = createRestaurant(pair.campus, author, "POI-reactivated-key", "Original identity");
        exchange(pair);

        assertEquals(CatalogCommands.Status.APPLIED, pair.campus.commands.update(
                CatalogKind.RESTAURANT, original.value(), author,
                current -> new CatalogCommands.UpdatePlan<>(null, Map.of("poi", "POI-temporary-key"),
                        () -> pair.campus.db.runtime().update("UPDATE public.restaurant SET poi=? WHERE id=?",
                                "POI-temporary-key", original.value()), rows -> rows == 1)).status());
        exchange(pair);
        var competitor = createRestaurant(pair.external, author, "POI-reactivated-key", "Competing identity");
        assertEquals(CatalogCommands.Status.APPLIED, competitor.status());

        assertEquals(CatalogCommands.Status.APPLIED, pair.campus.commands.update(
                CatalogKind.RESTAURANT, original.value(), author,
                current -> new CatalogCommands.UpdatePlan<>(null, Map.of("poi", "POI-reactivated-key"),
                        () -> pair.campus.db.runtime().update("UPDATE public.restaurant SET poi=? WHERE id=?",
                                "POI-reactivated-key", original.value()), rows -> rows == 1)).status());
        exchange(pair);

        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            assertEquals(1, openConflictCount(site, "POI-reactivated-key"),
                    "A candidate returning to a retired natural key must rejoin the complete conflict set");
            assertEquals(2, site.db.runtime().queryForObject("""
                    SELECT count(*) FROM eatwhat_sync.catalog_candidate c
                    JOIN eatwhat_sync.catalog_natural_key k ON k.kind=c.kind AND k.key_hash=c.key_hash
                    WHERE c.kind='RESTAURANT' AND c.status='PENDING' AND k.canonical_key->>'poi'=?
                    """, Integer.class, "POI-reactivated-key"));
            assertEquals(original.candidateGid(), site.store.findByNumeric(
                    CatalogKind.RESTAURANT, original.value()).gid());
            assertEquals(competitor.candidateGid(), site.store.findByNumeric(
                    CatalogKind.RESTAURANT, competitor.value()).gid());
        }
    }

    @Test
    void foodCreateWaitsForItsRestaurantThenRecoversWithoutChangingEitherNumericIdentity() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "catalog-food-parent");
        var restaurant = createRestaurant(pair.campus, author, "POI-food-parent-wait", "Food parent");
        var food = createFood(pair.campus, author, restaurant.value(), "Child before parent arrives");
        assertEquals(CatalogCommands.Status.APPLIED, restaurant.status());
        assertEquals(CatalogCommands.Status.APPLIED, food.status());
        assertNotEquals(restaurant.candidateGid(), food.candidateGid());

        List<DeliveryService.Lease> leases = pair.campus.delivery.claimForAggregates(
                pair.external.db.site().getId(), 100, LEASE, Set.of(CatalogEvents.AGGREGATE));
        DeliveryService.Lease restaurantLease = null;
        DeliveryService.Lease foodLease = null;
        for (DeliveryService.Lease lease : leases) {
            var event = lease.event();
            CatalogEvents.Action action = CatalogEvents.parseAction(event.payloadJson());
            if (action.kind() == CatalogKind.FOOD) foodLease = lease;
            if (action.kind() == CatalogKind.RESTAURANT) restaurantLease = lease;
        }
        assertNotNull(restaurantLease);
        assertNotNull(foodLease);

        // Deliver the child first and leave the restaurant delivery leased at the sender.
        var childEvent = foodLease.event();
        pair.external.inbox.receive(childEvent, pair.campus.db.site().getId());
        assertTrue(pair.campus.delivery.acknowledge(foodLease, "DURABLY_QUEUED"));
        process(pair.external);
        assertEquals("WAITING_DEPENDENCY", pair.external.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, childEvent.eventId()));
        assertEquals(0, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.foods WHERE name=?", Integer.class, "Child before parent arrives"));

        // The parent's arrival resolves the dependency; retry keeps the source's assigned IDs intact.
        var parentEvent = restaurantLease.event();
        pair.external.inbox.receive(parentEvent, pair.campus.db.site().getId());
        assertTrue(pair.campus.delivery.acknowledge(restaurantLease, "DURABLY_QUEUED"));
        process(pair.external);
        assertEquals(1, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT' AND object_gid=?",
                Integer.class, restaurant.candidateGid()));
        assertTrue(pair.external.inbox.requeueWaiting(100, Duration.ZERO) > 0);
        process(pair.external);

        assertEquals("APPLIED", pair.external.db.runtime().queryForObject(
                "SELECT status FROM eatwhat_sync.inbox WHERE event_id=?", String.class, childEvent.eventId()));
        assertEquals(food.value(), pair.external.db.runtime().queryForObject(
                "SELECT id FROM public.foods WHERE name=?", Long.class, "Child before parent arrives"));
        assertEquals(food.candidateGid(), pair.external.store.findByNumeric(CatalogKind.FOOD, food.value()).gid());
        assertEquals(restaurant.value(), pair.external.db.runtime().queryForObject(
                "SELECT restaurant_id FROM public.foods WHERE id=?", Long.class, food.value()));
        assertEquals(restaurant.candidateGid(), pair.external.store.findByNumeric(
                CatalogKind.RESTAURANT, restaurant.value()).gid());
    }

    @Test
    void lateRestaurantCreateAfterHubDeleteKeepsRestaurantIdentityAndClearsParent() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "catalog-late-child-author");
        promoteCampusAdmin(pair.campus, author);
        var hub = createHub(pair.campus, author, "Hub deleted before child delivery");
        exchange(pair);

        var child = createRestaurantInHub(pair.external, author, hub.value(), "POI-late-after-hub-delete",
                "Restaurant created before learning about hub deletion");
        assertEquals(CatalogCommands.Status.APPLIED, child.status());
        var childLease = pair.external.delivery.claimForAggregates(pair.campus.db.site().getId(), 100, LEASE,
                Set.of(CatalogEvents.AGGREGATE)).stream()
                .filter(lease -> lease.event().eventType().equals(CatalogEvents.ACTION))
                .filter(lease -> CatalogEvents.parseAction(lease.event().payloadJson()).objectGid()
                        .equals(child.candidateGid()))
                .findFirst().orElseThrow();

        var deleted = pair.campus.commands.deleteHub(hub.value(), author,
                () -> pair.campus.db.runtime().update("DELETE FROM public.hub WHERE id=?", hub.value()),
                rows -> rows != null && rows == 1);
        assertEquals(CatalogCommands.Status.APPLIED, deleted.status());
        exchange(pair.campus, pair.external);
        process(pair.external);
        assertEquals(0, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.hub WHERE id=?", Integer.class, hub.value()));

        pair.campus.inbox.receive(childLease.event(), pair.external.db.site().getId());
        assertTrue(pair.external.delivery.acknowledge(childLease, "DURABLY_QUEUED"));
        process(pair.campus);

        assertEquals(1, pair.campus.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, child.value()),
                "A late child CREATE remains a restaurant even though its parent is terminal");
        assertNull(pair.campus.db.runtime().queryForObject(
                "SELECT hub_id FROM public.restaurant WHERE id=?", Long.class, child.value()));
        assertEquals(child.candidateGid(), pair.campus.store.findByNumeric(
                CatalogKind.RESTAURANT, child.value()).gid());
        assertEquals(1, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, child.value()));
        assertNull(pair.external.db.runtime().queryForObject(
                "SELECT hub_id FROM public.restaurant WHERE id=?", Long.class, child.value()));
    }

    @Test
    void concurrentRestaurantDeleteAndChildCreateDoNotResurrectEitherIdentity() throws Exception {
        Pair pair = pair();
        long campusAuthor = registerSharedUser(pair.campus, pair.external, "catalog-delete-child-race");
        promoteCampusAdmin(pair.campus, campusAuthor);
        var restaurant = createRestaurant(pair.campus, campusAuthor, "POI-delete-child-race", "Concurrent parent");
        assertEquals(CatalogCommands.Status.APPLIED, restaurant.status());
        exchange(pair);
        CatalogStore.Identity externalRestaurant = pair.external.store.findByNumeric(
                CatalogKind.RESTAURANT, restaurant.value());
        assertNotNull(externalRestaurant);
        assertEquals(restaurant.candidateGid(), externalRestaurant.gid());
        long externalAuthor = pair.external.consent.getBySubject(
                pair.campus.store.userGid(campusAuthor)).userId();

        ExecutorService writers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var deleteFuture = writers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("delete start timed out");
                return pair.campus.commands.delete(CatalogKind.RESTAURANT, restaurant.value(), campusAuthor,
                        () -> pair.campus.db.runtime().update(
                                "DELETE FROM public.restaurant WHERE id=?", restaurant.value()), rows -> rows == 1);
            });
            var createFuture = writers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("child start timed out");
                return createFood(pair.external, externalAuthor, externalRestaurant.numericId(),
                        "Concurrent child must be suppressed");
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both site writers must reach the barrier");
            start.countDown();
            assertEquals(CatalogCommands.Status.APPLIED, deleteFuture.get(20, TimeUnit.SECONDS).status());
            var food = createFuture.get(20, TimeUnit.SECONDS);
            assertEquals(CatalogCommands.Status.APPLIED, food.status());

            // Deliver the parent tombstone first, then the concurrently-created child action.
            var deleteLease = pair.campus.delivery.claimForAggregates(pair.external.db.site().getId(), 100, LEASE,
                            Set.of(CatalogEvents.AGGREGATE)).stream()
                    .filter(lease -> lease.event().eventType().equals(CatalogEvents.ACTION))
                    .filter(lease -> CatalogEvents.parseAction(lease.event().payloadJson()).objectGid()
                            .equals(restaurant.candidateGid()))
                    .findFirst().orElseThrow();
            var foodLease = pair.external.delivery.claimForAggregates(pair.campus.db.site().getId(), 100, LEASE,
                            Set.of(CatalogEvents.AGGREGATE)).stream()
                    .filter(lease -> lease.event().eventType().equals(CatalogEvents.ACTION))
                    .filter(lease -> CatalogEvents.parseAction(lease.event().payloadJson()).objectGid()
                            .equals(food.candidateGid()))
                    .findFirst().orElseThrow();

            pair.external.inbox.receive(deleteLease.event(), pair.campus.db.site().getId());
            assertTrue(pair.campus.delivery.acknowledge(deleteLease, "DURABLY_QUEUED"));
            process(pair.external);
            pair.campus.inbox.receive(foodLease.event(), pair.external.db.site().getId());
            assertTrue(pair.external.delivery.acknowledge(foodLease, "DURABLY_QUEUED"));
            process(pair.campus);
            exchange(pair);

            for (CatalogSite site : List.of(pair.campus, pair.external)) {
                assertEquals(0, site.db.runtime().queryForObject(
                        "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, restaurant.value()));
                assertEquals(0, site.db.runtime().queryForObject(
                        "SELECT count(*) FROM public.foods WHERE id=?", Integer.class, food.value()));
                CatalogStore.Identity parent = site.store.findByNumeric(CatalogKind.RESTAURANT, restaurant.value());
                CatalogStore.Identity child = site.store.findByNumeric(CatalogKind.FOOD, food.value());
                assertNotNull(parent);
                assertNotNull(child);
                assertEquals(restaurant.candidateGid(), parent.gid());
                assertTrue(parent.tombstone());
                assertEquals(food.candidateGid(), child.gid());
                assertEquals(1, site.db.runtime().queryForObject("""
                        SELECT count(*) FROM eatwhat_sync.catalog_revision
                        WHERE kind='FOOD' AND object_gid=?
                        """, Integer.class, food.candidateGid()),
                        "The original concurrent child fact remains in the ledger");
            }
            // Duplicate delivery and a fresh authorization snapshot cannot revive a child of a terminal parent.
            pair.campus.inbox.receive(foodLease.event(), pair.external.db.site().getId());
            process(pair.campus);
            for (CatalogSite site : List.of(pair.campus, pair.external)) {
                assertEquals(0, site.db.runtime().queryForObject(
                        "SELECT count(*) FROM public.foods WHERE id=?", Integer.class, food.value()));
                assertEquals(1, site.db.runtime().queryForObject("""
                        SELECT count(*) FROM eatwhat_sync.catalog_revision
                        WHERE kind='FOOD' AND object_gid=?
                        """, Integer.class, food.candidateGid()),
                        "Replaying the child CREATE cannot mint a synthetic child DELETE or another revision");
            }
        } finally {
            start.countDown();
            writers.shutdownNow();
            assertTrue(writers.awaitTermination(10, TimeUnit.SECONDS), "Concurrent writers must terminate");
        }
    }

    @Test
    void managedPublicWritesRequireCatalogCommandContextEvenWhenCaptureIsNotCalled() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "catalog-runtime-guard");
        promoteCampusAdmin(pair.campus, author);
        var restaurant = createRestaurant(pair.campus, author, "POI-runtime-guard", "Guarded row");
        assertEquals(CatalogCommands.Status.APPLIED, restaurant.status());

        assertThrows(RuntimeException.class, () -> pair.campus.db.runtime().update(
                "UPDATE public.restaurant SET name='forged direct update' WHERE id=?", restaurant.value()));
        assertThrows(RuntimeException.class, () -> pair.campus.db.runtime().update(
                "DELETE FROM public.restaurant WHERE id=?", restaurant.value()));
        assertThrows(RuntimeException.class, () -> pair.campus.db.runtime().update("""
                INSERT INTO eatwhat_sync.catalog_business_write_context
                    (transaction_id,backend_id,kind,numeric_id,actor_user_id,operation,revision_before,expect_outbox)
                VALUES (1,1,'RESTAURANT',?,?,'UPDATE',1,true)
                """, restaurant.value(), author));
        assertThrows(RuntimeException.class, () -> pair.campus.db.runtime().update("""
                INSERT INTO eatwhat_sync.catalog_business_write_pending
                    (transaction_id,backend_id,kind,numeric_id,operation,revision_before,expect_outbox,imported)
                VALUES (1,1,'RESTAURANT',?,'UPDATE',1,true,false)
                """, restaurant.value()));

        assertThrows(RuntimeException.class, () -> pair.campus.db.transactions().required(() -> {
            pair.campus.db.runtime().queryForObject("SELECT set_config(?,?,true)", String.class,
                    "eatwhat_sync.catalog_business_write_context", "authorized");
            pair.campus.db.runtime().update(
                    "UPDATE public.restaurant SET name='GUC must not authorize' WHERE id=?", restaurant.value());
            return null;
        }), "A user-controlled GUC cannot forge the transaction/backend-bound catalog context");
        assertEquals("Guarded row", pair.campus.db.runtime().queryForObject(
                "SELECT name FROM public.restaurant WHERE id=?", String.class, restaurant.value()));

        var accepted = pair.campus.commands.update(CatalogKind.RESTAURANT, restaurant.value(), author, current ->
                new CatalogCommands.UpdatePlan<>(null, Map.of(),
                        () -> pair.campus.db.runtime().update(
                                "UPDATE public.restaurant SET name='authorized catalog update' WHERE id=?",
                                restaurant.value()), rows -> rows == 1));
        assertEquals(CatalogCommands.Status.APPLIED, accepted.status());
        assertEquals("authorized catalog update", pair.campus.db.runtime().queryForObject(
                "SELECT name FROM public.restaurant WHERE id=?", String.class, restaurant.value()));
    }

    @Test
    void controlledHubImportIsIdempotentAndRollsBackTheWholeBatchOnSecondItemFailure() {
        Pair pair = pair();
        long campusAdmin = registerSharedUser(pair.campus, pair.external, "catalog-import-admin");
        promoteCampusAdmin(pair.campus, campusAdmin);
        CatalogImportService importer = new CatalogImportService(pair.campus.db.runtime(),
                pair.campus.db.transactions(), pair.campus.commands, pair.campus.db.site());
        var first = hubImportItem("campus-center", "Imported campus center");
        var second = hubImportItem("external-center", "Imported external center");
        List<CatalogHubImportRequest.HubItem> items = List.of(first, second);
        UUID requestId = UUID.randomUUID();
        String hash = hubImportHash(items);
        var request = new CatalogHubImportRequest("approved-directory", requestId, hash, items);

        var imported = importer.importHubs(request, campusAdmin);
        assertEquals(CatalogCommands.ImportStatus.APPLIED, imported.status());
        assertEquals(2, imported.created());
        assertEquals(0, imported.updated());
        Map<String, Long> stableIds = Map.of(
                "campus-center", pair.campus.db.runtime().queryForObject(
                        "SELECT numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key=? AND source_id=?",
                        Long.class, "approved-directory", "campus-center"),
                "external-center", pair.campus.db.runtime().queryForObject(
                        "SELECT numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key=? AND source_id=?",
                        Long.class, "approved-directory", "external-center"));

        var replay = importer.importHubs(request, campusAdmin);
        assertEquals(CatalogCommands.ImportStatus.REPLAYED, replay.status());
        assertEquals(imported.batchId(), replay.batchId());
        assertEquals(2, pair.campus.db.runtime().queryForObject(
                "SELECT count(*) FROM public.hub WHERE id IN (?,?)", Integer.class,
                stableIds.get("campus-center"), stableIds.get("external-center")));
        List<CatalogHubImportRequest.HubItem> changedItems = List.of(
                hubImportItem("campus-center", "Changed name with same request ID"), second);
        var changedHash = importer.importHubs(new CatalogHubImportRequest(
                "approved-directory", requestId, hubImportHash(changedItems), changedItems), campusAdmin);
        assertEquals(CatalogCommands.ImportStatus.CONFLICT, changedHash.status(),
                "A request ID cannot be reused with a different canonical payload hash");
        assertEquals(stableIds, Map.of(
                "campus-center", pair.campus.db.runtime().queryForObject(
                        "SELECT numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key=? AND source_id=?",
                        Long.class, "approved-directory", "campus-center"),
                "external-center", pair.campus.db.runtime().queryForObject(
                        "SELECT numeric_id FROM eatwhat_sync.catalog_hub_source WHERE source_key=? AND source_id=?",
                        Long.class, "approved-directory", "external-center")));

        pair.campus.db.admin().execute("""
                CREATE FUNCTION public.fail_second_catalog_hub_import() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.name='Import failure sentinel' THEN RAISE EXCEPTION 'synthetic second item failure'; END IF;
                  RETURN NEW;
                END $$
                """);
        pair.campus.db.admin().execute("""
                CREATE TRIGGER fail_second_catalog_hub_import BEFORE INSERT ON public.hub
                FOR EACH ROW EXECUTE FUNCTION public.fail_second_catalog_hub_import()
                """);
        try {
            List<CatalogHubImportRequest.HubItem> failingItems = List.of(
                    hubImportItem("rollback-first", "Would have committed first"),
                    hubImportItem("rollback-second", "Import failure sentinel"));
            UUID failingRequestId = UUID.randomUUID();
            var failingRequest = new CatalogHubImportRequest("approved-directory", failingRequestId,
                    hubImportHash(failingItems), failingItems);
            int hubsBefore = pair.campus.db.runtime().queryForObject("SELECT count(*) FROM public.hub", Integer.class);
            int objectsBefore = pair.campus.db.runtime().queryForObject(
                    "SELECT count(*) FROM eatwhat_sync.catalog_object WHERE kind='HUB'", Integer.class);
            assertThrows(RuntimeException.class, () -> importer.importHubs(failingRequest, campusAdmin));
            assertEquals(hubsBefore, pair.campus.db.runtime().queryForObject(
                    "SELECT count(*) FROM public.hub", Integer.class));
            assertEquals(objectsBefore, pair.campus.db.runtime().queryForObject(
                    "SELECT count(*) FROM eatwhat_sync.catalog_object WHERE kind='HUB'", Integer.class));
            assertEquals(0, pair.campus.db.runtime().queryForObject(
                    "SELECT count(*) FROM eatwhat_sync.catalog_import_batch WHERE source_key=? AND request_id=?",
                    Integer.class, "approved-directory", failingRequestId));
            assertEquals(0, pair.campus.db.runtime().queryForObject(
                    "SELECT count(*) FROM eatwhat_sync.catalog_hub_source WHERE source_key=? AND source_id IN (?,?)",
                    Integer.class, "approved-directory", "rollback-first", "rollback-second"));
        } finally {
            pair.campus.db.admin().execute("DROP TRIGGER IF EXISTS fail_second_catalog_hub_import ON public.hub");
            pair.campus.db.admin().execute("DROP FUNCTION IF EXISTS public.fail_second_catalog_hub_import()");
        }
    }

    @Test
    void catalogBackfillTracksLegacyRestaurantOnceAndFencesAnExpiredWorkerLease() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "catalog-legacy-backfill");
        long legacyRestaurantId = pair.campus.db.runtime().queryForObject("""
                INSERT INTO public.restaurant(name,address,location,gcj_lng,gcj_lat,account_id,poi,picture_url)
                VALUES('Legacy backfill row','Synthetic address',ST_SetSRID(ST_MakePoint(117.0,36.0),4326)::geography,
                       117.01,36.01,?,'POI-legacy-backfill',ARRAY['restaurants/catalog/legacy-key']) RETURNING id
                """, Long.class, author);
        UUID legacyGid = pair.campus.db.runtime().queryForObject("""
                SELECT global_id FROM eatwhat_identity.entity_identity
                WHERE entity_type='restaurant' AND numeric_id=?
                """, UUID.class, legacyRestaurantId);
        UUID authorGid = pair.campus.store.userGid(author);
        long authorEpoch = pair.campus.consent.getBySubject(authorGid).epoch();
        CatalogBackfillStore backfill = new CatalogBackfillStore(pair.campus.db.runtime(),
                pair.campus.db.transactions(), pair.campus.commands, pair.campus.consent,
                pair.campus.db.site(), pair.campus.properties());
        backfill.enqueueReadySubjects();
        var abandoned = backfill.claim(16, Duration.ofSeconds(30)).stream()
                .filter(job -> job.subject().equals(authorGid) && job.epoch() == authorEpoch
                        && job.scope().equals("AUTHOR"))
                .findFirst().orElseThrow();
        pair.campus.db.runtime().update("""
                UPDATE eatwhat_sync.catalog_backfill_job
                SET lease_until=clock_timestamp()-interval '1 second'
                WHERE subject_gid=? AND epoch=? AND scope=? AND lease_token=?
                """, abandoned.subject(), abandoned.epoch(), abandoned.scope(), abandoned.token());
        var replacement = backfill.claim(16, Duration.ofSeconds(30)).stream()
                .filter(job -> job.subject().equals(authorGid) && job.epoch() == authorEpoch
                        && job.scope().equals("AUTHOR"))
                .findFirst().orElseThrow();
        assertNotEquals(abandoned.token(), replacement.token());
        String beforeStale = pair.campus.db.runtime().queryForObject("""
                SELECT jsonb_build_object(
                  'objects',(SELECT count(*) FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT'),
                  'revisions',(SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE kind='RESTAURANT'),
                  'outbox',(SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='catalog.operation'),
                  'cursor_kind',cursor_kind,'cursor_gid',cursor_gid,'status',status,'lease_token',lease_token,
                  'attempts',attempts)::text
                FROM eatwhat_sync.catalog_backfill_job
                WHERE subject_gid=? AND epoch=? AND scope='AUTHOR'
                """, String.class, authorGid, authorEpoch);

        var stale = backfill.process(abandoned, 1);
        assertTrue(stale.waiting(), "An expired lease cannot advance the candidate or its cursor");
        String afterStale = pair.campus.db.runtime().queryForObject("""
                SELECT jsonb_build_object(
                  'objects',(SELECT count(*) FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT'),
                  'revisions',(SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE kind='RESTAURANT'),
                  'outbox',(SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='catalog.operation'),
                  'cursor_kind',cursor_kind,'cursor_gid',cursor_gid,'status',status,'lease_token',lease_token,
                  'attempts',attempts)::text
                FROM eatwhat_sync.catalog_backfill_job
                WHERE subject_gid=? AND epoch=? AND scope='AUTHOR'
                """, String.class, authorGid, authorEpoch);
        assertEquals(beforeStale, afterStale, "A stale worker must not mutate catalog facts or the current lease");

        var current = backfill.process(replacement, 1);
        assertEquals(1, current.handled());
        CatalogStore.Identity tracked = pair.campus.store.findByNumeric(CatalogKind.RESTAURANT, legacyRestaurantId);
        assertNotNull(tracked);
        assertEquals(legacyGid, tracked.gid());
        assertEquals(pair.campus.db.site().getId(), tracked.originSite());
        for (int i = 0; i < 40; i++) {
            new CatalogBackfillWorker(backfill, Duration.ofSeconds(30), 32).runOnce();
        }
        assertEquals(1, pair.campus.db.runtime().queryForObject("""
                SELECT count(*) FROM eatwhat_sync.catalog_backfill_job
                WHERE subject_gid=? AND epoch=? AND scope='AUTHOR' AND status='DONE'
                """, Integer.class, authorGid, authorEpoch));
        String stableFacts = pair.campus.db.runtime().queryForObject("""
                SELECT jsonb_build_object(
                  'gid',(SELECT object_gid FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT' AND numeric_id=?),
                  'revision',(SELECT revision FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT' AND numeric_id=?),
                  'facts',(SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE kind='RESTAURANT' AND numeric_id=?),
                  'events',(SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='catalog.operation'
                    AND ((envelope->>'eventType') IN ('catalog.action','catalog.state')))
                )::text
                """, String.class, legacyRestaurantId, legacyRestaurantId, legacyRestaurantId);
        for (int i = 0; i < 5; i++) new CatalogBackfillWorker(backfill, Duration.ofSeconds(30), 32).runOnce();
        assertEquals(stableFacts, pair.campus.db.runtime().queryForObject("""
                SELECT jsonb_build_object(
                  'gid',(SELECT object_gid FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT' AND numeric_id=?),
                  'revision',(SELECT revision FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT' AND numeric_id=?),
                  'facts',(SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE kind='RESTAURANT' AND numeric_id=?),
                  'events',(SELECT count(*) FROM eatwhat_sync.outbox WHERE aggregate_type='catalog.operation'
                    AND ((envelope->>'eventType') IN ('catalog.action','catalog.state')))
                )::text
                """, String.class, legacyRestaurantId, legacyRestaurantId, legacyRestaurantId),
                "Repeated replay cannot create a new logical catalog revision or duplicate event");
        exchange(pair.campus, pair.external);
        process(pair.external);
        assertEquals(legacyGid, pair.external.store.findByNumeric(CatalogKind.RESTAURANT, legacyRestaurantId).gid());
        assertEquals("Legacy backfill row", pair.external.db.runtime().queryForObject(
                "SELECT name FROM public.restaurant WHERE id=?", String.class, legacyRestaurantId));
    }

    @Test
    void concurrentCustomTagRenamesRemainOpenAndAPlainRenameCannotBypassTheConflict() {
        Pair pair = pair();
        long owner = registerSharedUser(pair.campus, pair.external, "catalog-custom-owner");
        var tag = createCustomTag(pair.campus, owner, "base tag");
        exchange(pair);

        CatalogStore.Identity externalTag = pair.external.store.findObject(CatalogKind.CUSTOM_TAG, tag.candidateGid());
        assertNotNull(externalTag);
        assertEquals(tag.value(), externalTag.numericId(), "The cross-site projection keeps the assigned numeric ID");
        long externalOwner = pair.external.consent.getBySubject(pair.campus.store.userGid(owner)).userId();

        var campusRename = renameCustomTag(pair.campus, tag.value(), owner, "campus rename");
        var externalRename = renameCustomTag(pair.external, externalTag.numericId(), externalOwner, "external rename");
        assertEquals(CatalogCommands.Status.APPLIED, campusRename.status());
        assertEquals(CatalogCommands.Status.APPLIED, externalRename.status());
        exchange(pair);

        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            CatalogStore.Identity current = site.store.findObject(CatalogKind.CUSTOM_TAG, tag.candidateGid());
            assertNotNull(current);
            Object rawHeads = current.causalState().get("heads");
            assertInstanceOf(List.class, rawHeads);
            assertEquals(2, ((List<?>) rawHeads).size(),
                    "Independent renames preserve both original heads instead of silently picking a winner");
        }

        long revisionBefore = pair.campus.db.runtime().queryForObject(
                "SELECT revision FROM eatwhat_sync.catalog_object WHERE kind='CUSTOM_TAG' AND object_gid=?",
                Long.class, tag.candidateGid());
        String visibleNameBefore = pair.campus.db.runtime().queryForObject(
                "SELECT name FROM public.food_tags WHERE id=?", String.class, tag.value());
        var ordinaryRename = renameCustomTag(pair.campus, tag.value(), owner, "must wait for decision");
        assertEquals(CatalogCommands.Status.BLOCKED, ordinaryRename.status(),
                "A later ordinary rename must not erase an unresolved pair of heads");
        assertEquals(revisionBefore, pair.campus.db.runtime().queryForObject(
                "SELECT revision FROM eatwhat_sync.catalog_object WHERE kind='CUSTOM_TAG' AND object_gid=?",
                Long.class, tag.candidateGid()));
        assertEquals(visibleNameBefore, pair.campus.db.runtime().queryForObject(
                "SELECT name FROM public.food_tags WHERE id=?", String.class, tag.value()));
    }

    @Test
    void customTagHeadKeepUsesCompleteCasAndSurvivesBidirectionalRegrantBridges() {
        Pair pair = pair();
        long owner = registerSharedUser(pair.campus, pair.external, "catalog-head-keep-owner");
        promoteCampusAdmin(pair.campus, owner);
        var tag = createCustomTag(pair.campus, owner, "head keep base");
        exchange(pair);

        CatalogStore.Identity externalTag = pair.external.store.findByNumeric(CatalogKind.CUSTOM_TAG, tag.value());
        assertNotNull(externalTag);
        long externalOwner = pair.external.consent.getBySubject(pair.campus.store.userGid(owner)).userId();
        var campusRename = renameCustomTag(pair.campus, tag.value(), owner, "head keep campus");
        var externalRename = renameCustomTag(pair.external, externalTag.numericId(), externalOwner,
                "head keep external");
        assertEquals(CatalogCommands.Status.APPLIED, campusRename.status());
        assertEquals(CatalogCommands.Status.APPLIED, externalRename.status());
        exchange(pair);

        CatalogConflictService conflicts = conflictService(pair.campus);
        var conflict = conflicts.listOpenObjectHeadConflicts().stream()
                .filter(item -> item.objectGid().equals(tag.candidateGid())).findFirst().orElseThrow();
        assertEquals(tag.value(), conflict.numericId());
        assertEquals(2, conflict.heads().size());
        var completeHeads = conflict.heads().stream().map(head ->
                new CatalogConflictService.HeadVersionRequest(head.operationId(), head.revision(), head.contentHash()))
                .toList();
        var incomplete = new CatalogConflictService.ResolveHeadRequest(conflict.revision(),
                completeHeads.subList(0, 1), CatalogDecision.KEEP, conflict.heads().getFirst().operationId(),
                "stale incomplete heads");
        assertEquals(CatalogCommands.Status.BLOCKED,
                conflicts.resolveObjectHeads(conflict.conflictId(), incomplete, owner).status(),
                "Resolution requires the whole current head set");

        UUID externalWinner = conflict.heads().stream()
                .filter(head -> head.sourceSite().equals(pair.external.db.site().getId()))
                .map(CatalogConflictService.ObjectHeadView::operationId).findFirst().orElseThrow();
        var keep = new CatalogConflictService.ResolveHeadRequest(conflict.revision(), completeHeads,
                CatalogDecision.KEEP, externalWinner, "keep the external concurrent rename");
        assertEquals(CatalogCommands.Status.APPLIED,
                conflicts.resolveObjectHeads(conflict.conflictId(), keep, owner).status());
        exchange(pair);
        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            assertEquals("head keep external", site.db.runtime().queryForObject(
                    "SELECT name FROM public.food_tags WHERE id=?", String.class, tag.value()));
            assertEquals(tag.candidateGid(), site.store.findByNumeric(CatalogKind.CUSTOM_TAG, tag.value()).gid());
            assertTrue(site.store.findObject(CatalogKind.CUSTOM_TAG, tag.candidateGid()).causalState()
                    .get("heads") instanceof List<?> heads && heads.size() == 1,
                    "The selected head closes the conflict without replacing the stable object identity");
        }

        int campusRevisions = revisionCount(pair.campus, tag.candidateGid());
        int externalRevisions = revisionCount(pair.external, tag.candidateGid());
        int campusBridges = stateBridgeCount(pair.campus);
        int externalBridges = stateBridgeCount(pair.external);
        UUID ownerGid = pair.campus.store.userGid(owner);
        pair.campus.consent.withdraw(owner);
        var grant = pair.campus.consent.grant(owner, POLICY);
        deliverConsentSnapshots(pair.campus, pair.external, owner);
        var campusSnapshot = pair.campus.consent.getBySubject(ownerGid);
        var externalSnapshot = pair.external.consent.getBySubject(ownerGid);
        assertEquals(grant.epoch(), campusSnapshot.epoch());
        assertEquals(grant.epoch(), externalSnapshot.epoch());

        assertEquals(CatalogCommands.BackfillStatus.APPLIED, pair.campus.commands.backfillCatalogObject(
                CatalogKind.CUSTOM_TAG, tag.value(), ownerGid, grant.epoch()));
        assertEquals(CatalogCommands.BackfillStatus.APPLIED, pair.external.commands.backfillCatalogObject(
                CatalogKind.CUSTOM_TAG, externalTag.numericId(), ownerGid, grant.epoch()));
        exchange(pair);
        assertEquals(campusBridges + 1, stateBridgeCount(pair.campus));
        assertEquals(externalBridges + 1, stateBridgeCount(pair.external));
        assertEquals(campusRevisions, revisionCount(pair.campus, tag.candidateGid()),
                "State bridges carry the existing resolution heads, not new logical edits");
        assertEquals(externalRevisions, revisionCount(pair.external, tag.candidateGid()));
        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            assertEquals("head keep external", site.db.runtime().queryForObject(
                    "SELECT name FROM public.food_tags WHERE id=?", String.class, tag.value()));
            assertEquals(tag.candidateGid(), site.store.findByNumeric(CatalogKind.CUSTOM_TAG, tag.value()).gid());
            assertTrue(conflictIsResolved(site, tag.candidateGid()));
        }
    }

    @Test
    void anOlderKeepBridgeArrivingAfterASecondResolutionCannotReopenOrReplaceTheWinner() {
        Pair pair = pair();
        long owner = registerSharedUser(pair.campus, pair.external, "catalog-old-head-proof");
        promoteCampusAdmin(pair.campus, owner);
        var tag = createCustomTag(pair.campus, owner, "old proof base");
        exchange(pair);
        long externalOwner = pair.external.consent.getBySubject(pair.campus.store.userGid(owner)).userId();

        assertEquals(CatalogCommands.Status.APPLIED,
                renameCustomTag(pair.campus, tag.value(), owner, "first campus head").status());
        assertEquals(CatalogCommands.Status.APPLIED,
                renameCustomTag(pair.external, tag.value(), externalOwner, "first external head").status());
        exchange(pair);
        var conflicts = conflictService(pair.campus);
        var firstConflict = conflicts.listOpenObjectHeadConflicts().stream()
                .filter(item -> item.objectGid().equals(tag.candidateGid())).findFirst().orElseThrow();
        var firstHeads = firstConflict.heads().stream().map(head ->
                new CatalogConflictService.HeadVersionRequest(head.operationId(), head.revision(), head.contentHash()))
                .toList();
        UUID firstCampusWinner = firstConflict.heads().stream()
                .filter(head -> head.sourceSite().equals(pair.campus.db.site().getId()))
                .map(CatalogConflictService.ObjectHeadView::operationId).findFirst().orElseThrow();
        var firstKeep = new CatalogConflictService.ResolveHeadRequest(firstConflict.revision(), firstHeads,
                CatalogDecision.KEEP, firstCampusWinner, "first exact-head resolution");
        assertEquals(CatalogCommands.Status.APPLIED,
                conflicts.resolveObjectHeads(firstConflict.conflictId(), firstKeep, owner).status());
        SyncEvent oldResolution = catalogEvents(pair.campus).stream()
                .filter(event -> CatalogEvents.ACTION.equals(event.eventType()))
                .filter(event -> CatalogEvents.parseAction(event.payloadJson()).objectGid().equals(tag.candidateGid()))
                .filter(event -> CatalogEvents.parseAction(event.payloadJson()).resolutionProof() != null)
                .max(Comparator.comparingLong(event -> CatalogEvents.parseAction(event.payloadJson()).revision()))
                .orElseThrow();
        CatalogEvents.Action oldAction = CatalogEvents.parseAction(oldResolution.payloadJson());
        CatalogEvents.Head oldResolutionHead = catalogHead(pair.campus, oldAction);
        String firstWinnerName = (String) oldAction.canonicalPayload().get("name");
        exchange(pair);

        // Generate a second, genuine concurrent head pair from the first resolved value.
        assertEquals(CatalogCommands.Status.APPLIED,
                renameCustomTag(pair.campus, tag.value(), owner, "second campus head").status());
        assertEquals(CatalogCommands.Status.APPLIED,
                renameCustomTag(pair.external, tag.value(), externalOwner, "second external head").status());
        exchange(pair);
        var secondConflict = conflicts.listOpenObjectHeadConflicts().stream()
                .filter(item -> item.objectGid().equals(tag.candidateGid())).findFirst().orElseThrow();
        var secondHeads = secondConflict.heads().stream().map(head ->
                new CatalogConflictService.HeadVersionRequest(head.operationId(), head.revision(), head.contentHash()))
                .toList();
        UUID secondCampusWinner = secondConflict.heads().stream()
                .filter(head -> head.sourceSite().equals(pair.campus.db.site().getId()))
                .map(CatalogConflictService.ObjectHeadView::operationId).findFirst().orElseThrow();
        var secondKeep = new CatalogConflictService.ResolveHeadRequest(secondConflict.revision(), secondHeads,
                CatalogDecision.KEEP, secondCampusWinner, "second exact-head resolution");
        assertEquals(CatalogCommands.Status.APPLIED,
                conflicts.resolveObjectHeads(secondConflict.conflictId(), secondKeep, owner).status());
        exchange(pair);

        long revisionBeforeOldBridge = pair.campus.store.findObject(CatalogKind.CUSTOM_TAG, tag.candidateGid()).revision();
        long auditBeforeOldBridge = pair.campus.db.runtime().queryForObject("""
                SELECT count(*) FROM eatwhat_sync.catalog_head_conflict_audit WHERE conflict_id=?
                """, Long.class, firstConflict.conflictId());
        var epoch = pair.external.consent.getBySubject(pair.campus.store.userGid(owner)).epoch();
        CatalogEvents.StateBridge staleBridge = new CatalogEvents.StateBridge(CatalogKind.CUSTOM_TAG,
                tag.candidateGid(), tag.value(), pair.campus.db.site().getId(), pair.campus.db.site().getId(), null,
                List.of(new CatalogEvents.ConsentEpoch(pair.campus.store.userGid(owner), epoch)),
                List.of(oldResolutionHead));
        SyncEvent delayed = bridgeEvent(pair.external, staleBridge);
        CatalogEvents.validateEvent(delayed, pair.external.db.site().getId(),
                Set.of(pair.campus.db.site().getId(), pair.external.db.site().getId()));
        pair.campus.inbox.receive(delayed, pair.external.db.site().getId());
        process(pair.campus);

        assertEquals("second campus head", pair.campus.db.runtime().queryForObject(
                "SELECT name FROM public.food_tags WHERE id=?", String.class, tag.value()));
        assertEquals(revisionBeforeOldBridge,
                pair.campus.store.findObject(CatalogKind.CUSTOM_TAG, tag.candidateGid()).revision());
        assertEquals(auditBeforeOldBridge, pair.campus.db.runtime().queryForObject("""
                SELECT count(*) FROM eatwhat_sync.catalog_head_conflict_audit WHERE conflict_id=?
                """, Long.class, firstConflict.conflictId()));
        assertTrue(conflictIsResolved(pair.campus, tag.candidateGid()));
        assertNotEquals(firstWinnerName, "second campus head");
        exchange(pair);
        assertEquals("second campus head", pair.external.db.runtime().queryForObject(
                "SELECT name FROM public.food_tags WHERE id=?", String.class, tag.value()));
    }

    @Test
    void customTagDeleteAfterObservedRenameMakesItsOldReplayUnableToResurrectTheTag() {
        Pair pair = pair();
        long owner = registerSharedUser(pair.campus, pair.external, "catalog-custom-delete-owner");
        var tag = createCustomTag(pair.campus, owner, "delete baseline");
        exchange(pair);

        CatalogStore.Identity externalTag = pair.external.store.findObject(CatalogKind.CUSTOM_TAG, tag.candidateGid());
        assertNotNull(externalTag);
        long externalOwner = pair.external.consent.getBySubject(pair.campus.store.userGid(owner)).userId();
        assertEquals(CatalogCommands.Status.APPLIED,
                renameCustomTag(pair.external, externalTag.numericId(), externalOwner, "old rename").status());
        DeliveryService.Lease oldRename = pair.external.delivery.claimForAggregates(
                        pair.campus.db.site().getId(), 100, LEASE, Set.of(CatalogEvents.AGGREGATE)).stream()
                .filter(lease -> CatalogEvents.parseAction(lease.event().payloadJson()).kind() == CatalogKind.CUSTOM_TAG)
                .findFirst().orElseThrow();
        pair.campus.inbox.receive(oldRename.event(), pair.external.db.site().getId());
        assertTrue(pair.external.delivery.acknowledge(oldRename, "DURABLY_QUEUED"));
        process(pair.campus);

        var deletion = pair.campus.commands.mutateCustomTag(tag.value(), owner, CatalogOperation.DELETE,
                Map.of(), () -> pair.campus.db.runtime().update(
                        "DELETE FROM public.food_tags WHERE id=? AND owner_id=?", tag.value(), owner), rows -> rows == 1);
        assertEquals(CatalogCommands.Status.APPLIED, deletion.status());
        exchange(pair);

        assertEquals(0, pair.campus.db.runtime().queryForObject(
                "SELECT count(*) FROM public.food_tags WHERE id=?", Integer.class, tag.value()));
        assertEquals(0, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.food_tags WHERE id=?", Integer.class, externalTag.numericId()));
        assertTrue(pair.campus.store.findObject(CatalogKind.CUSTOM_TAG, tag.candidateGid()).tombstone());
        assertTrue(pair.external.store.findObject(CatalogKind.CUSTOM_TAG, tag.candidateGid()).tombstone());

        // Replay the already-observed rename as a fresh transport receipt. The object revision is immutable,
        // and the tombstone must keep the public definition deleted on both stations.
        pair.external.inbox.receive(oldRename.event(), pair.external.db.site().getId());
        process(pair.external);
        assertEquals(0, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.food_tags WHERE id=?", Integer.class, externalTag.numericId()));
        assertEquals(tag.candidateGid(), pair.campus.store.findByNumeric(
                CatalogKind.CUSTOM_TAG, tag.value()).gid());
        assertEquals(tag.candidateGid(), pair.external.store.findByNumeric(
                CatalogKind.CUSTOM_TAG, externalTag.numericId()).gid());
    }

    @Test
    void regrantBridgeCarriesOldCatalogHeadButOldEpochActionCannotOverrideIt() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "catalog-regrant-author");
        var created = createRestaurant(pair.campus, author, "POI-regrant-bridge", "Retained across grant");
        assertEquals(CatalogCommands.Status.APPLIED, created.status());

        // Hold the old-epoch ACTION at the sender. The receiver will learn the new consent snapshot first.
        DeliveryService.Lease oldActionLease = pair.campus.delivery.claimForAggregates(
                        pair.external.db.site().getId(), 100, LEASE, Set.of(CatalogEvents.AGGREGATE)).stream()
                .filter(lease -> CatalogEvents.parseAction(lease.event().payloadJson()).objectGid()
                        .equals(created.candidateGid()))
                .findFirst().orElseThrow();
        CatalogEvents.Action oldAction = CatalogEvents.parseAction(oldActionLease.event().payloadJson());
        long oldEpoch = oldAction.consentEpochs().getFirst().epoch();

        pair.campus.consent.withdraw(author);
        var granted = pair.campus.consent.grant(author, POLICY);
        assertTrue(granted.epoch() > oldEpoch);
        deliverConsentSnapshots(pair.campus, pair.external, author);
        var externalConsent = pair.external.consent.getBySubject(oldAction.authorGid());
        assertNotNull(externalConsent);
        assertEquals(granted.epoch(), externalConsent.epoch());
        assertTrue(pair.external.consent.replicationAllowed(externalConsent.userId()));

        CatalogStore.Identity sourceIdentity = pair.campus.store.findByNumeric(CatalogKind.RESTAURANT, created.value());
        assertNotNull(sourceIdentity);
        CatalogEvents.Head originalHead = new CatalogEvents.Head(oldAction.operationId(), oldAction.dotSite(),
                oldAction.authoritySite(), oldAction.authorGid(), oldAction.parentGid(), oldAction.numericId(),
                oldAction.identityOrigin(), oldAction.revision(), oldAction.expectedRevision(), oldAction.operation(),
                oldAction.dotSite(), oldAction.dotCounter(), oldAction.context(), oldAction.consentEpochs(), false,
                oldAction.canonicalPayload(), oldAction.contentHash(), oldAction.resolutionProof());
        CatalogEvents.StateBridge bridge = new CatalogEvents.StateBridge(CatalogKind.RESTAURANT,
                created.candidateGid(), created.value(), sourceIdentity.originSite(), sourceIdentity.authoritySite(),
                sourceIdentity.parentGid(), List.of(new CatalogEvents.ConsentEpoch(oldAction.authorGid(), granted.epoch())),
                List.of(originalHead));
        SyncEvent bridgeEvent = bridgeEvent(pair.campus, bridge);
        CatalogEvents.validateEvent(bridgeEvent, pair.campus.db.site().getId(),
                Set.of(pair.campus.db.site().getId(), pair.external.db.site().getId()));
        pair.external.inbox.receive(bridgeEvent, pair.campus.db.site().getId());
        process(pair.external);

        assertEquals(1, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, created.value()));
        CatalogStore.Identity restored = pair.external.store.findByNumeric(CatalogKind.RESTAURANT, created.value());
        assertEquals(created.candidateGid(), restored.gid());
        assertEquals(sourceIdentity.originSite(), restored.originSite());
        assertEquals(1, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE object_gid=?", Integer.class,
                created.candidateGid()));

        // A new transport receipt of the same immutable bridge must not add a catalog revision or change identity.
        SyncEvent repeatedBridge = bridgeEvent(pair.campus, bridge);
        pair.external.inbox.receive(repeatedBridge, pair.campus.db.site().getId());
        process(pair.external);
        assertEquals(1, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE object_gid=?", Integer.class,
                created.candidateGid()));
        assertEquals(created.candidateGid(), pair.external.store.findByNumeric(
                CatalogKind.RESTAURANT, created.value()).gid());

        pair.external.inbox.receive(oldActionLease.event(), pair.campus.db.site().getId());
        process(pair.external);
        assertEquals(1, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, created.value()));
        assertEquals(1, pair.external.db.runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE object_gid=?", Integer.class,
                created.candidateGid()));
        assertTrue(pair.campus.delivery.acknowledge(oldActionLease, "DURABLY_QUEUED"));
    }

    @Test
    void hubRebindUnassignsDepartedRestaurantsAndAssignsNewlyCoveredOnes() {
        Pair pair = pair();
        long author = registerSharedUser(pair.campus, pair.external, "catalog-rebind-author");
        promoteCampusAdmin(pair.campus, author);
        var hub = createHub(pair.campus, author, "Test hub");
        var departed = createRestaurant(pair.campus, author, "POI-rebind-departed", "Departed restaurant");

        CatalogHubRebindResult first = pair.campus.commands.rebindHub(hub.value(), author);
        assertTrue(first.assignments().stream().anyMatch(assignment ->
                assignment.restaurantGid().equals(departed.candidateGid())));
        assertEquals(hub.value(), pair.campus.db.runtime().queryForObject(
                "SELECT hub_id FROM public.restaurant WHERE id=?", Long.class, departed.value()));
        var newlyCovered = createRestaurant(pair.campus, author, "POI-rebind-new", "Newly covered restaurant");
        var moveOutside = pair.campus.commands.update(CatalogKind.RESTAURANT, departed.value(), author, current ->
                new CatalogCommands.UpdatePlan<>(null, Map.of("poi", "POI-rebind-departed"),
                        () -> pair.campus.db.runtime().update("""
                                UPDATE public.restaurant
                                SET location=ST_SetSRID(ST_MakePoint(120.0,40.0),4326)::geography,
                                    gcj_lng=120.01,gcj_lat=40.01
                                WHERE id=?
                                """, departed.value()), rows -> rows == 1));
        assertEquals(CatalogCommands.Status.APPLIED, moveOutside.status());
        CatalogHubRebindResult second = pair.campus.commands.rebindHub(hub.value(), author);
        assertEquals(2, second.assignments().size(), "Old-bound and newly covered rows are both reconciled");
        assertTrue(second.assignments().stream().anyMatch(assignment ->
                assignment.restaurantGid().equals(departed.candidateGid()) && assignment.newHubGid() == null));
        assertTrue(second.assignments().stream().anyMatch(assignment ->
                assignment.restaurantGid().equals(newlyCovered.candidateGid())
                        && hub.candidateGid().equals(assignment.newHubGid())));
        assertNull(pair.campus.db.runtime().queryForObject(
                "SELECT hub_id FROM public.restaurant WHERE id=?", Long.class, departed.value()),
                "Rebinding must scan the old-bound ∪ newly-covered set and remove a restaurant that left the hub");
        assertEquals(hub.value(), pair.campus.db.runtime().queryForObject(
                "SELECT hub_id FROM public.restaurant WHERE id=?", Long.class, newlyCovered.value()));
        assertNull(pair.campus.db.runtime().queryForObject(
                "SELECT hub_id FROM public.restaurant WHERE id=?", Long.class, departed.value()));
        String adminToken = loginAs(author, "admin");
        bindToken(adminToken);
        try {
            assertEquals(1, serviceFor(pair.campus).rebindRestaurants(hub.value()).getData(),
                    "The legacy API continues returning the currently covered count");
        } finally {
            StpUtil.logout(author);
        }
    }

    private static int openConflictCount(CatalogSite site, String poi) {
        String keyHash = CatalogPayload.naturalKey(CatalogKind.RESTAURANT,
                Map.of("poi", poi), null, null).hash();
        return site.db.runtime().queryForObject("""
                SELECT count(*) FROM eatwhat_sync.catalog_conflict
                WHERE kind='RESTAURANT' AND key_hash=? AND status='OPEN'
                """, Integer.class, keyHash);
    }

    private static int revisionCount(CatalogSite site, UUID gid) {
        return site.db.runtime().queryForObject(
                "SELECT count(*) FROM eatwhat_sync.catalog_revision WHERE object_gid=?", Integer.class, gid);
    }

    private static boolean conflictIsResolved(CatalogSite site, UUID gid) {
        return site.db.runtime().queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_head_conflict
                              WHERE kind='CUSTOM_TAG' AND object_gid=? AND status='RESOLVED')
                """, Boolean.class, gid);
    }

    private static int stateBridgeCount(CatalogSite site) {
        return site.db.runtime().queryForObject("""
                SELECT count(*) FROM eatwhat_sync.outbox
                WHERE envelope->>'eventType'=? AND envelope->>'aggregateType'=?
                """, Integer.class, CatalogEvents.BRIDGE, CatalogEvents.AGGREGATE);
    }

    private static List<SyncEvent> catalogEvents(CatalogSite site) {
        EventCodec codec = new EventCodec();
        return site.db.runtime().query("""
                SELECT envelope::text FROM eatwhat_sync.outbox
                WHERE aggregate_type=? ORDER BY local_order
                """, (rs, row) -> codec.decode(rs.getString(1)), CatalogEvents.AGGREGATE);
    }

    private static CatalogEvents.Head catalogHead(CatalogSite site, CatalogEvents.Action action) {
        return new CatalogEvents.Head(action.operationId(), site.db.site().getId(), action.authoritySite(),
                action.authorGid(), action.parentGid(), action.numericId(), action.identityOrigin(),
                action.revision(), action.expectedRevision(), action.operation(), action.dotSite(),
                action.dotCounter(), action.context(), action.consentEpochs(),
                action.operation() == CatalogOperation.DELETE, action.canonicalPayload(), action.contentHash(),
                action.resolutionProof());
    }

    private static String catalogConflictDiagnostics(Pair pair) {
        return "campus=" + catalogConflictDiagnostics(pair.campus)
                + "; external=" + catalogConflictDiagnostics(pair.external);
    }

    private static String catalogConflictDiagnostics(CatalogSite site) {
        String outbox = site.db.runtime().queryForObject("""
                SELECT coalesce(jsonb_agg(jsonb_build_object(
                  'eventId',o.event_id,'status',d.status,'receipt',d.receipt_status,
                  'eventType',envelope->>'eventType','origin',origin_site,
                  'kind',envelope->'payloadJson'->>'kind','operation',envelope->'payloadJson'->>'operation',
                  'gid',envelope->'payloadJson'->>'objectGid','revision',envelope->'payloadJson'->>'revision',
                  'key',envelope->'payloadJson'->'naturalKey') ORDER BY local_order)::text,'[]')
                FROM eatwhat_sync.outbox o
                LEFT JOIN eatwhat_sync.delivery d ON d.event_id=o.event_id
                WHERE o.aggregate_type='catalog.operation'
                """, String.class);
        String inbox = site.db.runtime().queryForObject("""
                SELECT coalesce(jsonb_agg(jsonb_build_object(
                  'eventId',event_id,'status',status,'error',last_error,'origin',origin_site,
                  'eventType',envelope->>'eventType','kind',envelope->'payloadJson'->>'kind',
                  'operation',envelope->'payloadJson'->>'operation','gid',envelope->'payloadJson'->>'objectGid')
                  ORDER BY received_at)::text,'[]')
                FROM eatwhat_sync.inbox WHERE aggregate_type='catalog.operation'
                """, String.class);
        String ledger = site.db.runtime().queryForObject("""
                SELECT jsonb_build_object(
                  'conflicts',(SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.created_at),'[]'::jsonb)
                               FROM eatwhat_sync.catalog_conflict c),
                  'candidates',(SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.created_at),'[]'::jsonb)
                                FROM eatwhat_sync.catalog_candidate c
                                WHERE c.key_hash=(SELECT key_hash FROM eatwhat_sync.catalog_natural_key
                                                  WHERE kind='RESTAURANT' AND canonical_key->>'poi'='POI-keep-late-third')),
                  'objects',(SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.created_at),'[]'::jsonb)
                             FROM eatwhat_sync.catalog_object o WHERE kind='RESTAURANT')
                )::text
                """, String.class);
        return "site=" + site.db.site().getId() + " outbox=" + outbox + " inbox=" + inbox + " ledger=" + ledger;
    }

    private static void assertKeptExternalCandidate(Pair pair, CatalogCommands.WriteOutcome<Long> campusCandidate,
                                                    CatalogCommands.WriteOutcome<Long> externalCandidate) {
        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            assertEquals(0, site.db.runtime().queryForObject(
                    "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, campusCandidate.value()));
            assertEquals(1, site.db.runtime().queryForObject(
                    "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, externalCandidate.value()));
            assertEquals(campusCandidate.candidateGid(), site.store.findByNumeric(
                    CatalogKind.RESTAURANT, campusCandidate.value()).gid());
            assertEquals(externalCandidate.candidateGid(), site.store.findByNumeric(
                    CatalogKind.RESTAURANT, externalCandidate.value()).gid());
        }
    }

    private static void assertKeptCampusCandidate(Pair pair, CatalogCommands.WriteOutcome<Long> campusCandidate,
                                                   CatalogCommands.WriteOutcome<Long> externalCandidate) {
        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            assertEquals(1, site.db.runtime().queryForObject(
                    "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, campusCandidate.value()));
            assertEquals(0, site.db.runtime().queryForObject(
                    "SELECT count(*) FROM public.restaurant WHERE id=?", Integer.class, externalCandidate.value()));
            assertEquals(campusCandidate.candidateGid(), site.store.findByNumeric(
                    CatalogKind.RESTAURANT, campusCandidate.value()).gid());
            assertEquals(externalCandidate.candidateGid(), site.store.findByNumeric(
                    CatalogKind.RESTAURANT, externalCandidate.value()).gid());
        }
    }

    private static CatalogConflictService conflictService(CatalogSite site) {
        return new CatalogConflictService(site.db.runtime(), site.db.transactions(), site.store,
                site.commands, site.db.site());
    }

    private static HubService serviceFor(CatalogSite site) {
        HubService service = new HubService();
        ReflectionTestUtils.setField(service, "catalogCommands", site.commands);
        ReflectionTestUtils.setField(service, "siteProperties", site.db.site());
        return service;
    }

    private static Pair pair() {
        try {
            SiteProperties campus = siteProperties(SiteProperties.Mode.CAMPUS, "catalog-campus");
            SiteProperties external = siteProperties(SiteProperties.Mode.EXTERNAL, "catalog-external");
            var campusDb = EventFoundationTestSupport.createDatabase(PG, campus);
            var externalDb = EventFoundationTestSupport.createDatabase(PG, external);
            CatalogSite campusSite = catalogSite(campusDb, externalDb);
            CatalogSite externalSite = catalogSite(externalDb, campusDb);
            return new Pair(campusSite, externalSite);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not construct isolated catalog pair", failure);
        }
    }

    private static CatalogSite catalogSite(EventFoundationTestSupport.Database db,
                                           EventFoundationTestSupport.Database peer) {
        var consent = new ConsentStore(db.runtime(), db.transactions(), db.site(), POLICY,
                Duration.ofMinutes(5), Duration.ofMinutes(2), peer.site().getId(), peer.site().getInstanceId());
        CatalogSyncProperties properties = new CatalogSyncProperties();
        properties.setEnabled(true);
        properties.setPeerSiteId(peer.site().getId());
        properties.validate(db.site(), peer.site().getId());
        CatalogStore store = new CatalogStore(db.runtime());
        var outbox = new OutboxService(db.runtime(), db.transactions(), new EventCodec(), db.site());
        EntityIdentityService identities = new EntityIdentityService(db.runtime());
        CatalogProjection projection = new CatalogProjection(db.runtime(), store, identities, db.site());
        CatalogCommands commands = new CatalogCommands(db.runtime(), db.transactions(), outbox, store,
                identities, consent, db.site(), properties, projection);
        List<EventHandler> handlers = List.of(
                new CatalogEventHandler(CatalogEvents.ACTION, db.runtime(), store, projection, consent,
                        Set.of(db.site().getId(), peer.site().getId()),
                        db.site().getMode() == SiteProperties.Mode.CAMPUS ? db.site().getId() : peer.site().getId()),
                new CatalogEventHandler(CatalogEvents.BRIDGE, db.runtime(), store, projection, consent,
                        Set.of(db.site().getId(), peer.site().getId()),
                        db.site().getMode() == SiteProperties.Mode.CAMPUS ? db.site().getId() : peer.site().getId()));
        InboxService inbox = new InboxService(db.runtime(), db.transactions(), new EventCodec(),
                new EventHandlerRegistry(handlers));
        DeliveryService delivery = new DeliveryService(db.runtime(), db.transactions(), new EventCodec());
        return new CatalogSite(db, consent, store, commands, properties, inbox, delivery, peer.site().getId());
    }

    private static long registerSharedUser(CatalogSite campus, CatalogSite external, String name) {
        CatalogSite site = campus;
        long id = site.db.runtime().queryForObject("""
                INSERT INTO users(nick_name,password_hash) VALUES(?, 'catalog-synthetic') RETURNING id
                """, Long.class, "cat-" + name.replaceAll("[^A-Za-z0-9]", "").substring(0,
                        Math.min(12, name.replaceAll("[^A-Za-z0-9]", "").length())) + "-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        site.db.runtime().update("INSERT INTO privacy(account_id) VALUES(?)", id);
        site.consent.onRegistered(id, true, POLICY);
        var upsert = site.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == id && job.kind().equals("UPSERT"))
                .max(Comparator.comparingLong(you.v50to.eatwhat.account.ConsentModels.Job::epoch)).orElseThrow();
        assertEquals("APPLIED", external.consent.apply(site.consent.export(upsert)));
        site.consent.complete(upsert);
        assertTrue(site.consent.replicationAllowed(id));
        assertTrue(external.consent.replicationAllowed(id));
        return id;
    }

    private static CatalogCommands.WriteOutcome<Long> createRestaurant(CatalogSite site, long author,
                                                                        String poi, String name) {
        final Long[] inserted = new Long[1];
        return site.commands.create(CatalogKind.RESTAURANT, author, null, Map.of("poi", poi), () -> {
            inserted[0] = site.db.runtime().queryForObject("""
                    INSERT INTO public.restaurant(name,address,location,gcj_lng,gcj_lat,account_id,poi,picture_url)
                    VALUES(?, 'Synthetic address',ST_SetSRID(ST_MakePoint(117.0,36.0),4326)::geography,
                           117.01,36.01,?,?,ARRAY['restaurants/catalog/stable-key']) RETURNING id
                    """, Long.class, name, author, poi);
            return inserted[0];
        }, () -> inserted[0], value -> value != null);
    }

    private static CatalogCommands.WriteOutcome<Long> createRestaurantInHub(CatalogSite site, long author,
                                                                             long hubId, String poi,
                                                                             String name) {
        final Long[] inserted = new Long[1];
        return site.commands.create(CatalogKind.RESTAURANT, author, hubId, Map.of("poi", poi), () -> {
            inserted[0] = site.db.runtime().queryForObject("""
                    INSERT INTO public.restaurant(name,address,location,gcj_lng,gcj_lat,account_id,poi,picture_url,hub_id)
                    VALUES(?, 'Synthetic address',ST_SetSRID(ST_MakePoint(117.0,36.0),4326)::geography,
                           117.01,36.01,?,?,ARRAY['restaurants/catalog/stable-key'],?) RETURNING id
                    """, Long.class, name, author, poi, hubId);
            return inserted[0];
        }, () -> inserted[0], value -> value != null);
    }

    private static CatalogCommands.WriteOutcome<Long> createFood(CatalogSite site, long author,
                                                                   long restaurantId, String name) {
        final Long[] inserted = new Long[1];
        return site.commands.create(CatalogKind.FOOD, author, restaurantId,
                Map.of("restaurantId", restaurantId, "name", name), () -> {
                    inserted[0] = site.db.runtime().queryForObject("""
                            INSERT INTO public.foods(account_id,restaurant_id,name,description,price,category,picture_url)
                            VALUES(?,?,?,NULL,NULL,NULL,ARRAY[]::text[]) RETURNING id
                            """, Long.class, author, restaurantId, name);
                    return inserted[0];
                }, () -> inserted[0], value -> value != null);
    }

    private static CatalogCommands.WriteOutcome<Long> createCustomTag(CatalogSite site, long owner, String name) {
        String normalized = CatalogPayload.normalizedTagName(name);
        final Long[] inserted = new Long[1];
        return site.commands.create(CatalogKind.CUSTOM_TAG, owner, null,
                Map.of("normalizedName", normalized), () -> {
                    inserted[0] = site.db.runtime().queryForObject("""
                            INSERT INTO public.food_tags(name,tag_type,owner_id,normalized_name)
                            VALUES(?,'custom',?,?) RETURNING id
                            """, Long.class, name, owner, normalized);
                    return inserted[0];
                }, () -> inserted[0], value -> value != null);
    }

    private static CatalogCommands.WriteOutcome<Integer> renameCustomTag(CatalogSite site, long tagId,
                                                                          long owner, String name) {
        String normalized = CatalogPayload.normalizedTagName(name);
        return site.commands.mutateCustomTag(tagId, owner, CatalogOperation.UPDATE,
                Map.of("name", name, "normalizedName", normalized),
                () -> site.db.runtime().update("""
                        UPDATE public.food_tags SET name=?,normalized_name=?
                        WHERE id=? AND owner_id=? AND tag_type='custom'
                        """, name, normalized, tagId, owner), rows -> rows == 1);
    }

    private static CatalogCommands.WriteOutcome<Long> createHub(CatalogSite site, long adminUserId, String name) {
        final Long[] inserted = new Long[1];
        Map<String, Object> payload = Map.of("name", name,
                "centerWgs", Map.of("lng", 117.0, "lat", 36.0),
                "centerGcj", Map.of("lng", 117.01, "lat", 36.01),
                "boundaryGeoJson", "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[116,35],[118,35],[118,37],[116,37],[116,35]]]]}");
        return site.commands.create(CatalogKind.HUB, adminUserId, null, null, Map.of(), () -> {
            inserted[0] = site.db.runtime().queryForObject("""
                    INSERT INTO public.hub(name,center,gcj_lng,gcj_lat,boundary)
                    VALUES(?,ST_SetSRID(ST_MakePoint(117.0,36.0),4326),117.01,36.01,
                           ST_SetSRID(ST_GeomFromGeoJSON(?),4326)::geometry(MultiPolygon,4326)) RETURNING id
                    """, Long.class, name, payload.get("boundaryGeoJson"));
            return inserted[0];
        }, () -> inserted[0], value -> value != null);
    }

    private static CatalogHubImportRequest.HubItem hubImportItem(String sourceId, String name) {
        return new CatalogHubImportRequest.HubItem(sourceId, name,
                new CatalogHubImportRequest.Coordinate(117.0, 36.0),
                new CatalogHubImportRequest.Coordinate(117.01, 36.01),
                "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[116,35],[118,35],[118,37],[116,37],[116,35]]]]}",
                false);
    }

    private static String hubImportHash(List<CatalogHubImportRequest.HubItem> hubs) {
        List<Map<String, Object>> canonical = hubs.stream().map(hub -> {
            Map<String, Object> payload = new java.util.LinkedHashMap<>(hub.canonicalPayload());
            payload.put("boundaryGeoJson", EventCodec.canonicalJson(hub.boundaryGeoJson()));
            return payload;
        }).toList();
        String json = JsonMapper.builder().build().writeValueAsString(canonical);
        return EventCodec.sha256(EventCodec.canonicalJson(json));
    }

    private static void promoteCampusAdmin(CatalogSite campus, long userId) {
        assertEquals(1, campus.db.runtime().update("UPDATE public.users SET role='admin' WHERE id=?", userId));
    }

    private static CatalogEvents.Action syntheticRestaurantCreate(CatalogSite source, long author, UUID objectGid,
                                                                   long numericId, String poi, String name) {
        var identity = source.consent.getBySubject(source.store.userGid(author));
        assertNotNull(identity);
        Map<String, Object> payloadWithNullHub = new java.util.LinkedHashMap<>();
        payloadWithNullHub.put("name", name);
        payloadWithNullHub.put("address", "Synthetic offline address");
        payloadWithNullHub.put("cityId", 1L);
        payloadWithNullHub.put("poi", poi);
        payloadWithNullHub.put("locationWgs", Map.of("lng", 117.0, "lat", 36.0));
        payloadWithNullHub.put("locationGcj", Map.of("lng", 117.01, "lat", 36.01));
        payloadWithNullHub.put("hubGid", null);
        payloadWithNullHub.put("pictureKeys", List.of());
        Map<String, Object> payload = CatalogPayload.validate(CatalogKind.RESTAURANT, payloadWithNullHub);
        UUID operationId = UUID.randomUUID();
        String hash = EventCodec.sha256(EventCodec.canonicalJson(
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(payload)));
        return new CatalogEvents.Action(CatalogKind.RESTAURANT, objectGid, 1, 0, CatalogOperation.CREATE,
                "catalog-campus",
                source.store.userGid(author), null, numericId, source.db.site().getId(), payload, hash,
                operationId, source.db.site().getId(), 1, Map.of(),
                List.of(new CatalogEvents.ConsentEpoch(identity.subjectId(), identity.epoch())), null, null);
    }

    private static SyncEvent syntheticCatalogActionEvent(CatalogSite source, CatalogEvents.Action action,
                                                          long author, String correlation) {
        long transportNumericId = source.db.runtime().queryForObject(
                "SELECT eatwhat_sync.next_catalog_number()", Long.class);
        var empty = you.v50to.eatwhat.sync.VersionVector.empty();
        return new SyncEvent(action.operationId(), source.db.site().getId(), source.db.site().getInstanceId(),
                CatalogEvents.AGGREGATE, action.operationId(), transportNumericId, CatalogEvents.ACTION,
                CatalogEvents.SCHEMA_VERSION, empty, empty.increment(source.db.site().getId()), List.of(),
                source.store.userGid(author), correlation, action.operationId(), CatalogEvents.payload(action));
    }

    private static SiteProperties siteProperties(SiteProperties.Mode mode, String id) {
        SiteProperties properties = new SiteProperties();
        properties.setMode(mode);
        properties.setId(id);
        properties.setInstanceId(UUID.randomUUID());
        return properties;
    }

    private static void exchange(Pair pair) {
        exchange(pair.campus, pair.external);
        exchange(pair.external, pair.campus);
        for (CatalogSite site : List.of(pair.campus, pair.external)) {
            process(site);
            site.inbox.requeueWaiting(100, Duration.ZERO);
            process(site);
        }
    }

    private static void exchange(CatalogSite from, CatalogSite to) {
        var leases = from.delivery.claimForAggregates(to.db.site().getId(), 100, LEASE,
                java.util.Set.of(CatalogEvents.AGGREGATE));
        for (var lease : leases) {
            try {
                CatalogEvents.validateEvent(lease.event(), from.db.site().getId(),
                        java.util.Set.of(from.db.site().getId(), to.db.site().getId()));
            } catch (RuntimeException invalid) {
                throw new AssertionError("Invalid catalog transport event: type=" + lease.event().eventType()
                        + ", eventId=" + lease.event().eventId() + ", origin=" + lease.event().originSite()
                        + ", payload=" + lease.event().payloadJson(), invalid);
            }
            to.inbox.receive(lease.event(), from.db.site().getId());
            assertTrue(from.delivery.acknowledge(lease, "DURABLY_QUEUED"));
        }
    }

    private static void process(CatalogSite site) {
        for (var lease : site.inbox.claim(100, LEASE)) site.inbox.process(lease);
    }

    private static void deliverConsentSnapshots(CatalogSite from, CatalogSite to, long userId) {
        List<you.v50to.eatwhat.account.ConsentModels.Job> jobs = from.consent.claim(32, LEASE).stream()
                .filter(job -> job.userId() == userId)
                .sorted(Comparator.comparingLong(you.v50to.eatwhat.account.ConsentModels.Job::epoch))
                .toList();
        assertFalse(jobs.isEmpty(), "Withdraw and regrant must leave durable consent snapshots to deliver");
        for (var job : jobs) {
            var snapshot = from.consent.export(job);
            if (snapshot == null) {
                // A newer revision may supersede a leased snapshot before it is exported.
                from.consent.complete(job);
                continue;
            }
            try {
                to.consent.apply(snapshot);
            } catch (RuntimeException failure) {
                String sourceOrigin = from.db.runtime().queryForObject("""
                        SELECT origin_site FROM eatwhat_identity.entity_identity
                        WHERE entity_type='users' AND numeric_id=?
                        """, String.class, job.userId());
                var receiverState = to.consent.getBySubject(job.subjectId());
                throw new AssertionError("Consent regrant delivery failed: job=" + job.kind()
                        + ", subject=" + job.subjectId() + ", user=" + job.userId()
                        + ", epoch=" + job.epoch() + ", revision=" + job.revision()
                        + ", snapshotSource=" + snapshot.sourceSite() + ", snapshotOrigin=" + sourceOrigin
                        + ", receiverState=" + receiverState, failure);
            }
            from.consent.complete(job);
        }
    }

    private static SyncEvent bridgeEvent(CatalogSite from, CatalogEvents.StateBridge bridge) {
        UUID eventId = UUID.randomUUID();
        long transportNumericId = from.db.runtime().queryForObject(
                "SELECT eatwhat_sync.next_catalog_number()", Long.class);
        return new SyncEvent(eventId, from.db.site().getId(), from.db.site().getInstanceId(),
                CatalogEvents.AGGREGATE, eventId, transportNumericId, CatalogEvents.BRIDGE,
                CatalogEvents.SCHEMA_VERSION, you.v50to.eatwhat.sync.VersionVector.empty(),
                you.v50to.eatwhat.sync.VersionVector.empty().increment(from.db.site().getId()), List.of(),
                null, "catalog-bridge:" + eventId, eventId,
                CatalogEvents.bridgePayload(bridge));
    }
}
