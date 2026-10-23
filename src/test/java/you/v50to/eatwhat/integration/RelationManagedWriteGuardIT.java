package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.sync.ManagedDomainWriteGuard;
import you.v50to.eatwhat.data.dto.AddCustomFoodTagsDTO;
import you.v50to.eatwhat.data.dto.AddSystemFoodTagsDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.data.po.FoodTag;
import you.v50to.eatwhat.data.po.FoodTagging;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.FoodTagMapper;
import you.v50to.eatwhat.mapper.FoodTaggingMapper;
import you.v50to.eatwhat.service.FoodTagService;
import you.v50to.eatwhat.sync.relation.RelationKind;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL coverage for preserving managed relations when the sync feature is off. */
class RelationManagedWriteGuardIT {
    private static final GenericContainer<?> POSTGRES = IsolatedContainers.postgres("relation-managed-guard");

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void v22ProjectionOnlyRemainsManagedAfterPublicRelationAndTargetArePurged() {
        Fixture fixture = fixture();
        long tagging = fixture.db().admin().queryForObject("""
                INSERT INTO public.food_taggings(food_id,tag_id,account_id) VALUES(?,?,?) RETURNING id
                """, Long.class, fixture.foodId(), fixture.firstTagId(), fixture.actorId());
        String gid = identity(fixture, "food_taggings", tagging);
        String origin = fixture.db().admin().queryForObject("""
                SELECT origin_site FROM eatwhat_identity.entity_identity
                WHERE entity_type='food_taggings' AND numeric_id=?
                """, String.class, tagging);
        fixture.db().admin().update("""
                INSERT INTO eatwhat_sync.relation_projection(relation_key,entity_type,numeric_id,global_id,origin_site)
                VALUES(?,?,?,?,?)
                """, relationKey(fixture, fixture.firstTagId()), "food_taggings", tagging,
                UUID.fromString(gid), origin);

        assertEquals(0, count(fixture, "SELECT count(*) FROM eatwhat_sync.relation_operation"));
        assertEquals(0, count(fixture, "SELECT count(*) FROM eatwhat_sync.relation_intent"));
        assertEquals(0, count(fixture, "SELECT count(*) FROM eatwhat_sync.relation_causal_state"));
        ManagedDomainWriteGuard guard = new ManagedDomainWriteGuard(fixture.db().runtime());
        assertTrue(guard.relationManaged(RelationKind.tagging, fixture.actorId(), fixture.foodId(),
                fixture.firstTagId()), "The V22 projection alone is a managed-domain marker");

        fixture.db().admin().update("DELETE FROM public.food_taggings WHERE id=?", tagging);
        fixture.db().admin().update("DELETE FROM public.foods WHERE id=?", fixture.foodId());
        assertTrue(guard.relationManaged(RelationKind.tagging, fixture.actorId(), fixture.foodId(),
                fixture.firstTagId()), "Purging public rows must not erase the V22 projection key");
    }

    @Test
    void v23IntentAndV25CausalStateIndependentlyMarkRelationsManaged() {
        Fixture fixture = fixture();
        ManagedDomainWriteGuard guard = new ManagedDomainWriteGuard(fixture.db().runtime());
        UUID actor = UUID.fromString(identity(fixture, "users", fixture.actorId()));
        UUID target = UUID.fromString(identity(fixture, "foods", fixture.foodId()));
        UUID owner = UUID.fromString(identity(fixture, "users", fixture.ownerId()));
        UUID firstTag = UUID.fromString(identity(fixture, "food_tags", fixture.firstTagId()));
        UUID secondTag = UUID.fromString(identity(fixture, "food_tags", fixture.secondTagId()));

        UUID intentNaturalId = UUID.randomUUID();
        fixture.db().admin().update("""
                INSERT INTO eatwhat_sync.relation_intent
                    (natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,desired_present,
                     intent_revision,published_revision,row_id,row_gid,row_origin,local_intent)
                VALUES(?,'tagging',?,?,?,?,true,1,0,1,?,?::varchar,true)
                """, intentNaturalId, actor, target, owner, firstTag, UUID.randomUUID(), fixture.db().site().getId());

        UUID causalNaturalId = UUID.randomUUID();
        fixture.db().admin().update("""
                INSERT INTO eatwhat_sync.relation_causal_state
                    (natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,actor_epoch,owner_epoch,
                     row_id,row_gid,row_origin,seen,add_witnesses,remove_frontier,legacy_loaded)
                VALUES(?,'tagging',?,?,?,?,1,1,1,?,?::varchar,'{}'::jsonb,'[]'::jsonb,'[]'::jsonb,true)
                """, causalNaturalId, actor, target, owner, secondTag, UUID.randomUUID(), fixture.db().site().getId());

        assertTrue(guard.relationManaged(RelationKind.tagging, fixture.actorId(), fixture.foodId(),
                fixture.firstTagId()), "A V23 intent is a managed-domain marker");
        assertTrue(guard.relationManaged(RelationKind.tagging, fixture.actorId(), fixture.foodId(),
                fixture.secondTagId()), "A V25 causal-state row is a managed-domain marker");
        assertFalse(guard.relationManaged(RelationKind.tagging, fixture.actorId(), fixture.foodId(),
                fixture.thirdTagId()), "A relation with no historical sync evidence remains unmanaged");
    }

    @Test
    void systemTagBatchChecksEveryMemberBeforeAnyLegacyInsert() {
        Fixture fixture = fixture();
        insertProjectionOnlyMarker(fixture, fixture.secondTagId());
        FoodTagService service = foodTagService(fixture);
        FoodTag first = tag(fixture.firstTagId(), "system-a", "system", null);
        FoodTag second = tag(fixture.secondTagId(), "system-b", "system", null);
        FoodTagMapper tagMapper = mock(FoodTagMapper.class);
        ReflectionTestUtils.setField(service, "foodTagMapper", tagMapper);
        when(tagMapper.selectSystemTagsByIds(List.of(fixture.firstTagId(), fixture.secondTagId())))
                .thenReturn(List.of(first, second));

        AddSystemFoodTagsDTO dto = new AddSystemFoodTagsDTO();
        dto.setTagIds(List.of(fixture.firstTagId(), fixture.secondTagId()));
        var result = service.addSystemTagsToFood(fixture.foodId(), fixture.actorId(), dto);

        assertEquals(BizCode.DATA_SYNC_PENDING.getCode(), result.getCode());
        verify(serviceTaggingMapper(service), never()).insert(any(FoodTagging.class));
        assertEquals(0, count(fixture, "SELECT count(*) FROM public.food_taggings"));
    }

    @Test
    void customTagBatchRejectsBeforeCreatingAnUnmanagedNeighbor() {
        Fixture fixture = fixture();
        insertProjectionOnlyMarker(fixture, fixture.customTagId());
        FoodTagService service = foodTagService(fixture);
        FoodTag existing = tag(fixture.customTagId(), "Managed Existing", "custom", fixture.actorId());
        existing.setNormalizedName("managed existing");
        FoodTagMapper tagMapper = mock(FoodTagMapper.class);
        ReflectionTestUtils.setField(service, "foodTagMapper", tagMapper);
        when(tagMapper.selectCustomTagsByOwnerAndNormalizedNames(eq(fixture.actorId()), anyList()))
                .thenReturn(List.of(existing));
        long tagsBefore = count(fixture, "SELECT count(*) FROM public.food_tags");

        AddCustomFoodTagsDTO dto = new AddCustomFoodTagsDTO();
        dto.setNames(List.of("New Neighbor", "Managed Existing"));
        var result = service.addCustomTagsToFood(fixture.foodId(), fixture.actorId(), dto);

        assertEquals(BizCode.DATA_SYNC_PENDING.getCode(), result.getCode());
        assertEquals(tagsBefore, count(fixture, "SELECT count(*) FROM public.food_tags"),
                "The unmanaged custom tag must not be created before the batch guard completes");
        verify(serviceTaggingMapper(service), never()).insert(any(FoodTagging.class));
    }

    @Test
    void freshUnmanagedBatchKeepsLegacyMapperBehavior() {
        Fixture fixture = fixture();
        FoodTagService service = foodTagService(fixture);
        FoodTagMapper tagMapper = mock(FoodTagMapper.class);
        ReflectionTestUtils.setField(service, "foodTagMapper", tagMapper);
        when(tagMapper.selectSystemTagsByIds(List.of(fixture.firstTagId(), fixture.secondTagId())))
                .thenReturn(List.of(tag(fixture.firstTagId(), "system-a", "system", null),
                        tag(fixture.secondTagId(), "system-b", "system", null)));
        assertFalse(new ManagedDomainWriteGuard(fixture.db().runtime()).relationManaged(RelationKind.tagging,
                fixture.actorId(), fixture.foodId(), fixture.firstTagId()));

        AddSystemFoodTagsDTO dto = new AddSystemFoodTagsDTO();
        dto.setTagIds(List.of(fixture.firstTagId(), fixture.secondTagId()));
        var result = service.addSystemTagsToFood(fixture.foodId(), fixture.actorId(), dto);

        assertEquals(BizCode.SUCCESS.getCode(), result.getCode());
        verify(serviceTaggingMapper(service), times(2)).insert(any(FoodTagging.class));
    }

    private static Fixture fixture() {
        var db = EventFoundationTestSupport.createDatabase(POSTGRES);
        long actor = insertUser(db, "actor");
        long owner = insertUser(db, "owner");
        long restaurant = db.admin().queryForObject("""
                INSERT INTO public.restaurant(name,location,gcj_lng,gcj_lat,account_id)
                VALUES('managed guard fixture',ST_SetSRID(ST_Point(117,36),4326),117,36,?) RETURNING id
                """, Long.class, owner);
        long food = db.admin().queryForObject("""
                INSERT INTO public.foods(account_id,restaurant_id,name) VALUES(?,?,'managed guard fixture') RETURNING id
                """, Long.class, owner, restaurant);
        long firstTag = insertTag(db, "first system", "system", null);
        long secondTag = insertTag(db, "second system", "system", null);
        long thirdTag = insertTag(db, "third system", "system", null);
        long customTag = insertTag(db, "managed existing", "custom", actor);
        return new Fixture(db, actor, owner, food, firstTag, secondTag, thirdTag, customTag);
    }

    private static long insertUser(EventFoundationTestSupport.Database db, String prefix) {
        return db.admin().queryForObject("""
                INSERT INTO public.users(nick_name,password_hash)
                VALUES(?, 'synthetic-only') RETURNING id
                """, Long.class, prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private static long insertTag(EventFoundationTestSupport.Database db, String name, String type, Long ownerId) {
        return db.admin().queryForObject("""
                INSERT INTO public.food_tags(name,normalized_name,tag_type,owner_id)
                VALUES(?,?,?,?) RETURNING id
                """, Long.class, name, name.toLowerCase(), type, ownerId);
    }

    private static FoodTagService foodTagService(Fixture fixture) {
        FoodTagService service = new FoodTagService();
        ReflectionTestUtils.setField(service, "managedDomainWriteGuard",
                new ManagedDomainWriteGuard(fixture.db().runtime()));
        ReflectionTestUtils.setField(service, "foodMapper", mock(FoodMapper.class));
        ReflectionTestUtils.setField(service, "foodTagMapper", mock(FoodTagMapper.class));
        ReflectionTestUtils.setField(service, "foodTaggingMapper", mock(FoodTaggingMapper.class));
        ReflectionTestUtils.setField(service, "jdbc", fixture.db().runtime());
        when(((FoodMapper) ReflectionTestUtils.getField(service, "foodMapper")).selectById(fixture.foodId()))
                .thenReturn(new Food());
        return service;
    }

    private static FoodTaggingMapper serviceTaggingMapper(FoodTagService service) {
        return (FoodTaggingMapper) ReflectionTestUtils.getField(service, "foodTaggingMapper");
    }

    private static FoodTag tag(long id, String name, String type, Long owner) {
        FoodTag tag = new FoodTag();
        tag.setId(id);
        tag.setName(name);
        tag.setNormalizedName(name.toLowerCase());
        tag.setTagType(type);
        tag.setOwnerId(owner);
        return tag;
    }

    private static void insertProjectionOnlyMarker(Fixture fixture, long tagId) {
        long taggingId = fixture.db().admin().queryForObject("""
                INSERT INTO public.food_taggings(food_id,tag_id,account_id) VALUES(?,?,?) RETURNING id
                """, Long.class, fixture.foodId(), tagId, fixture.actorId());
        String gid = identity(fixture, "food_taggings", taggingId);
        String origin = fixture.db().admin().queryForObject("""
                SELECT origin_site FROM eatwhat_identity.entity_identity
                WHERE entity_type='food_taggings' AND numeric_id=?
                """, String.class, taggingId);
        fixture.db().admin().update("""
                INSERT INTO eatwhat_sync.relation_projection(relation_key,entity_type,numeric_id,global_id,origin_site)
                VALUES(?,?,?,?,?)
                """, relationKey(fixture, tagId), "food_taggings", taggingId, UUID.fromString(gid), origin);
        fixture.db().admin().update("DELETE FROM public.food_taggings WHERE id=?", taggingId);
    }

    private static String relationKey(Fixture fixture, long tagId) {
        return "tagging:" + fixture.actorId() + ":" + fixture.foodId() + ":" + tagId;
    }

    private static String identity(Fixture fixture, String type, long id) {
        return fixture.db().admin().queryForObject("""
                SELECT global_id::text FROM eatwhat_identity.entity_identity
                WHERE entity_type=? AND numeric_id=?
                """, String.class, type, id);
    }

    private static long count(Fixture fixture, String sql) {
        return fixture.db().admin().queryForObject(sql, Long.class);
    }

    private record Fixture(EventFoundationTestSupport.Database db, long actorId, long ownerId, long foodId,
                           long firstTagId, long secondTagId, long thirdTagId, long customTagId) { }
}
