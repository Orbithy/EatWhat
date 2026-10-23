package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.sync.profile.ProfileStore;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL cascade and inbound projection paths; no service hook is required for release. */
class FileDeletionPathsIT {
    static final GenericContainer<?> PG=IsolatedContainers.postgres("file-deletion-paths");
    static JdbcTemplate jdbc;
    @BeforeAll static void start() {
        PG.start();String name="delete_"+UUID.randomUUID().toString().replace("-","");
        var server=new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(PG,IsolatedContainers.DATABASE),IsolatedContainers.USER,IsolatedContainers.PASSWORD));
        server.execute("CREATE DATABASE "+name+" TEMPLATE template0");String url=IsolatedContainers.jdbcUrl(PG,name);
        Flyway.configure().dataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD).load().migrate();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url,IsolatedContainers.USER,IsolatedContainers.PASSWORD));
    }
    @AfterAll static void stop() {PG.stop();}
    long user() { return jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES(?,'synthetic') RETURNING id",Long.class,"synthetic-"+UUID.randomUUID().toString().substring(0,12)); }
    String file(long user,String biz) {
        UUID owner=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,user);
        UUID file=UUID.randomUUID();String key=biz+"/"+user+"/"+file+".png";
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key) VALUES(?,?,?,?)",file,owner,biz,key);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,verified_at) VALUES(?,1,? ,?,12,'image/png','VERIFIED',clock_timestamp())",file,"verified/"+file,"a".repeat(64));
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES(?,?,1,?,?)",key,file,owner,biz);return key;
    }
    int live(String key) {return jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?",Integer.class,key);}
    long restaurant(long user,String key) {return jdbc.queryForObject("INSERT INTO restaurant(name,account_id,location,gcj_lng,gcj_lat,picture_url) VALUES(?,?,ST_SetSRID(ST_Point(117,36),4326),117,36,ARRAY[?]) RETURNING id",Long.class,"r-"+UUID.randomUUID(),user,key);}
    @Test void deletingUserReleasesAvatarFoodsAndActivitiesWhileRetainingSurvivingRestaurantReferenceAndUploadHistory() {
        long uid=user();String avatar=file(uid,"avatar"),food=file(uid,"foods"),act=file(uid,"activity"),dinner=file(uid,"activity"),rest=file(uid,"restaurants");
        jdbc.update("UPDATE users SET avatar=? WHERE id=?",avatar,uid);long rid=restaurant(uid,rest);
        jdbc.update("INSERT INTO foods(account_id,restaurant_id,name,picture_url) VALUES(?,?,'synthetic',ARRAY[?])",uid,rid,food);
        jdbc.update("INSERT INTO activity_foods(account_id,food_name,picture_url) VALUES(?,'synthetic',ARRAY[?])",uid,act);
        jdbc.update("INSERT INTO activity_dinners(account_id,picture_url) VALUES(?,ARRAY[?])",uid,dinner);
        UUID owner=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,uid);
        UUID intent=UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.upload_intent(intent_id,owner_id,owner_gid,biz,logical_key,staging_key,declared_size,declared_type,expires_at,status) VALUES(?,?,?,'avatar',?,?,12,'image/png',clock_timestamp()+interval '1 hour','COMPLETE')",intent,uid,owner,"avatar/"+intent,"staging/"+intent);
        for(String key:List.of(avatar,food,act,dinner,rest)) assertEquals(1,live(key));
        jdbc.update("DELETE FROM users WHERE id=?",uid);
        for(String key:List.of(avatar,food,act,dinner)) {
            assertEquals(0,live(key));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation c JOIN eatwhat_file.file_key_alias a USING(file_id,revision) WHERE a.logical_key=?",Integer.class,key));
        }
        assertEquals(1,live(rest),"ON DELETE SET NULL preserves the restaurant and its image reference");
        assertNull(jdbc.queryForObject("SELECT account_id FROM restaurant WHERE id=?",Long.class,rid));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.upload_intent WHERE intent_id=?",Integer.class,intent));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO eatwhat_file.upload_intent(intent_id,owner_id,owner_gid,biz,logical_key,staging_key,declared_size,declared_type,expires_at) VALUES(?,?,?,'avatar',?,?,12,'image/png',clock_timestamp()+interval '1 hour')",UUID.randomUUID(),uid,owner,"avatar/deleted-"+intent,"staging/deleted-"+intent));
    }
    @Test void inboundProfileStoreAvatarReplacementReleasesOnlyOldKeyAndRestoringCreatesANewTombstoneCycle() {
        long uid=user();String old=file(uid,"avatar"),fresh=file(uid,"avatar");var profile=new ProfileStore(jdbc);
        profile.applyPublicValue(uid,"avatar",old);assertEquals(1,live(old));
        profile.applyPublicValue(uid,"avatar",fresh);assertEquals(0,live(old));assertEquals(1,live(fresh));
        profile.applyPublicValue(uid,"avatar",old);assertEquals(1,live(old));assertEquals(0,live(fresh));
        assertNull(jdbc.queryForObject("SELECT t.unreferenced_since FROM eatwhat_file.file_tombstone t JOIN eatwhat_file.file_key_alias a USING(file_id,revision) WHERE a.logical_key=?",java.sql.Timestamp.class,old));
    }
    @Test void inboundSoftDeleteAndHardRestaurantCascadeReleaseReferencesWithoutLifecycleService() {
        long uid=user();String rest=file(uid,"restaurants"),food=file(uid,"foods"),act=file(uid,"activity");long rid=restaurant(uid,rest);
        jdbc.update("INSERT INTO foods(account_id,restaurant_id,name,picture_url) VALUES(?,?,'synthetic',ARRAY[?])",uid,rid,food);
        long aid=jdbc.queryForObject("INSERT INTO activity_foods(account_id,food_name,picture_url) VALUES(?,'synthetic',ARRAY[?]) RETURNING id",Long.class,uid,act);
        jdbc.update("UPDATE activity_foods SET deleted_at=clock_timestamp() WHERE id=?",aid);assertEquals(0,live(act));
        jdbc.update("DELETE FROM restaurant WHERE id=?",rid);assertEquals(0,live(rest));assertEquals(0,live(food));
    }
    @Test void anotherOwnersOrWrongBusinessAliasDoesNotBecomePublicFromRawProjection() {
        long owner=user(),other=user();String avatar=file(owner,"avatar"),wrong=file(other,"foods");
        jdbc.update("UPDATE users SET avatar=? WHERE id=?",avatar,other);assertEquals(0,live(avatar));
        jdbc.update("UPDATE users SET avatar=? WHERE id=?",wrong,other);assertEquals(0,live(wrong));
    }
    @Test void activeProfileAvatarHeadsPinFilesButDeletedAccountsDoNot() {
        long uid=user();String key=file(uid,"avatar");
        UUID owner=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,uid);
        jdbc.update("INSERT INTO eatwhat_sync.profile_field_state(subject_gid,field_group,value,head_set) VALUES(?,'avatar','null'::jsonb,jsonb_build_array(jsonb_build_object('id',?::text,'value',?::text,'vector',jsonb_build_object())))",owner,UUID.randomUUID(),key);
        String query=you.v50to.eatwhat.service.storage.replication.BusinessKeyColumns.PROFILE_AVATAR_SQL;
        assertEquals(1,jdbc.queryForList(query,(Object)new String[]{key}).size());
        jdbc.update("DELETE FROM users WHERE id=?",uid);
        assertEquals(0,jdbc.queryForList(query,(Object)new String[]{key}).size());
    }
    @Test void numericIdentityChecksStillRejectZeroForBusinessAndAllowMultipleUuidOnlyAggregates() {
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO eatwhat_sync.entity_state VALUES('users',?,0,'{}',NULL,false)",UUID.randomUUID()));
        for(int i=0;i<3;i++)jdbc.update("INSERT INTO eatwhat_sync.entity_state(aggregate_type,aggregate_id,numeric_id) VALUES('file.metadata',?,0)",UUID.randomUUID());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO eatwhat_sync.entity_state(aggregate_type,aggregate_id,numeric_id) VALUES('file.metadata',?,1)",UUID.randomUUID()));
    }
}
