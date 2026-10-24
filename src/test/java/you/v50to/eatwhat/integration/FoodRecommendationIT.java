package you.v50to.eatwhat.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.enums.FoodCategory;
import you.v50to.eatwhat.data.po.Food;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.RecommendationProfileMapper;
import you.v50to.eatwhat.service.RecommendationProfileService;
import you.v50to.eatwhat.data.dto.FoodPreferencesDTO;
import you.v50to.eatwhat.data.vo.RecommendationProfileVO;
import you.v50to.eatwhat.exception.BizException;
import java.math.BigDecimal;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL ranking and MyBatis mappings, on migrated test-owned data only. */
class FoodRecommendationIT {
    private static GenericContainer<?> postgres;
    private static SqlSessionFactory factory;
    private SqlSession session;
    private JdbcTemplate jdbc;
    private FoodMapper foods;
    private RecommendationProfileMapper profiles;
    private RecommendationProfileService profileService;
    private long me;
    private long other;
    private long uploader;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = IsolatedContainers.startedPostgresWithInspectRetry("food-recommendation");
        SiteProperties site = new SiteProperties();
        site.setMode(SiteProperties.Mode.LEGACY);
        var db = EventFoundationTestSupport.createDatabase(postgres, site);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(FoodMapper.class);
        configuration.addMapper(RecommendationProfileMapper.class);
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(db.dataSource());
        bean.setConfiguration(configuration);
        factory = bean.getObject();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void seedAccountsInRolledBackTransaction() throws Exception {
        session = factory.openSession(false);
        session.getConnection().setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        foods = session.getMapper(FoodMapper.class);
        profiles = session.getMapper(RecommendationProfileMapper.class);
        profileService = new RecommendationProfileService(profiles);
        me = user("requester");
        other = user("other-user");
        uploader = user("publisher");
    }

    @AfterEach
    void rollback() {
        if (session != null) {
            session.rollback(true);
            session.close();
        }
    }

    @Test
    void coldStartPreservesPopularOrderingMappingsAndImageFilter() {
        long low = food(null, 2, true);
        long high = food("staple", 10, true);
        long tie = food(null, 10, true);
        food(null, 100, false);
        long empty = food(null, 90, true);
        jdbc.update("UPDATE foods SET picture_url=ARRAY[]::text[] WHERE id=?", empty);
        long older = insert("""
                INSERT INTO foods(account_id,restaurant_id,name,likes_count,picture_url,updated_at)
                VALUES (?,?,'older',10,ARRAY['test.png'],now()-interval '1 day') RETURNING id
                """, uploader, restaurant());

        assertEquals(List.of(tie, high, older, low), ids(me, 20));
        assertEquals(List.of(tie), ids(me, 1));
        Food mapped = foods.selectRecommendationCandidates(me, 1L, true, null, 20).stream()
                .filter(f -> f.getId() == high).findFirst().orElseThrow();
        assertEquals(FoodCategory.STAPLE, mapped.getCategory());
        assertEquals("publisher", mapped.getUploaderName());
        assertNotNull(mapped.getRestaurantName());
        assertArrayEquals(new String[]{"test.png"}, mapped.getPictureUrl());
        assertNotNull(mapped.getCreatedAt());
        assertNotNull(mapped.getUpdatedAt());
    }

    @Test
    void ownTaggingsPromoteMatchingFoodsOutsideThePopularTopTwenty() {
        long seed = food(null, 0, false);
        long spicy = tag("spicy", null);
        tagging(seed, spicy, me);
        long matching = food(null, 0, true);
        tagging(matching, spicy, uploader);
        for (int i = 0; i < 25; i++) food(null, 100, true);

        assertEquals(matching, ids(me, 1).getFirst());
        assertNotEquals(matching, ids(other, 1).getFirst());
    }

    @Test
    void favoritesLikesAndFoodHistoryHaveDescendingInfluenceAndWithdrawalsTakeEffect() {
        long favoriteSeed = food(null, 0, false);
        long likeSeed = food(null, 0, false);
        long historySeed = food(null, 0, false);
        long favoriteMatch = matchingPair(favoriteSeed, "favorite");
        long likeMatch = matchingPair(likeSeed, "like");
        long historyMatch = matchingPair(historySeed, "history");
        long popular = food(null, 100, true);
        favorite(me, favoriteSeed);
        like(me, likeSeed);
        browse(me, "food", historySeed, 0);

        assertEquals(List.of(favoriteMatch, likeMatch, historyMatch, popular), ids(me, 20));
        jdbc.update("DELETE FROM food_favorites WHERE account_id=?", me);
        jdbc.update("UPDATE likes SET deleted_at=now() WHERE account_id=?", me);
        assertEquals(List.of(historyMatch, popular, likeMatch, favoriteMatch), ids(me, 20));
    }

    @Test
    void behaviorLearnsCategoryEvenWithoutAnyTags() {
        long seed = food("dessert", 0, false);
        long match = food("dessert", 0, true);
        food("staple", 100, true);
        like(me, seed);

        assertEquals(match, ids(me, 1).getFirst());
    }

    @Test
    void restaurantHistoryPromotesItsDishesAndExpiresAfterThirtyDays() {
        long restaurant = restaurant();
        long match = foodAt(restaurant, null, 0, true);
        long popular = food(null, 100, true);
        browse(me, "restaurant", restaurant, 0);
        assertEquals(match, ids(me, 1).getFirst());

        jdbc.update("DELETE FROM browse_history WHERE account_id=?", me);
        browse(me, "restaurant", restaurant, 31);
        assertEquals(popular, ids(me, 1).getFirst());
    }

    @Test
    void historyDecaysAndRepeatedViewsDoNotOutweighALike() {
        long freshSeed = food(null, 0, false);
        long oldSeed = food(null, 0, false);
        long likedSeed = food(null, 0, false);
        long freshMatch = matchingPair(freshSeed, "fresh");
        long oldMatch = matchingPair(oldSeed, "old");
        long likedMatch = matchingPair(likedSeed, "liked");
        for (int i = 0; i < 50; i++) browse(me, "food", freshSeed, 0);
        browse(me, "food", oldSeed, 20);
        like(me, likedSeed);

        assertEquals(List.of(likedMatch, freshMatch, oldMatch), ids(me, 20));
    }

    @Test
    void privateTagsAreScopedToTheirOwnerIncludingOnLikedFoodsAndCandidates() {
        long seed = food(null, 0, false);
        long privateTag = tag("secret", other);
        long publicTag = tag("secret", null);
        tagging(seed, privateTag, other);
        long match = food(null, 0, true);
        tagging(match, publicTag, uploader);
        long popular = food(null, 100, true);
        like(me, seed);
        assertEquals(popular, ids(me, 1).getFirst(), "Another user's private tag on a liked food is not a feature");
        assertEquals(match, ids(other, 1).getFirst(), "Its owner may match the private tag to a public tag by normalized name");

        long privateCandidate = food(null, 200, true);
        tagging(privateCandidate, privateTag, other);
        tagging(seed, publicTag, me);
        assertEquals(match, ids(me, 1).getFirst(), "Another user's private candidate tag cannot match my preference");
    }

    @Test
    void publicTagVotesDoNotMultiplyEitherProfileOrCandidateScores() {
        long seed = food(null, 0, false);
        long tag = tag("shared", null);
        tagging(seed, tag, uploader);
        long match = food(null, 0, true);
        tagging(match, tag, uploader);
        long otherSeed = food(null, 0, false);
        long strongerMatch = matchingPair(otherSeed, "stronger");
        like(me, seed);
        favorite(me, otherSeed);
        assertEquals(List.of(strongerMatch, match), ids(me, 20));
        tagging(seed, tag, other);
        tagging(match, tag, other);
        assertEquals(List.of(strongerMatch, match), ids(me, 20));
    }

    @Test
    void discoversUnseenFoodsFirstButStillFillsASmallCatalog() {
        long liked = food(null, 100, true);
        long viewed = food(null, 50, true);
        long fresh = food(null, 0, true);
        like(me, liked);
        browse(me, "food", viewed, 0);

        assertEquals(List.of(fresh, viewed, liked), ids(me, 20));
        assertEquals(3, ids(me, 20).stream().distinct().count());
    }

    @Test
    void recentSignalWindowsExcludeOldLikesFavoritesAndTaggings() {
        long excludedSeed = food(null, 0, false);
        long excluded = matchingPair(excludedSeed, "excluded");
        // Older rows sort behind 200 newer rows independently in every signal source.
        long oldTag = jdbc.queryForObject("SELECT id FROM food_tags WHERE normalized_name='excluded'", Long.class);
        jdbc.update("INSERT INTO food_taggings(food_id,tag_id,account_id,created_at) VALUES (?,?,?,now()-interval '1 day')",
                excludedSeed, oldTag, me);
        like(me, excludedSeed);
        favorite(me, excludedSeed);
        for (int i = 0; i < 200; i++) {
            long seed = food(null, 0, false);
            long tag = tag("recent-" + i, null);
            tagging(seed, tag, me);
            like(me, seed);
            favorite(me, seed);
        }
        long popular = food(null, 100, true);
        assertEquals(List.of(popular, excluded), ids(me, 20));
    }

    @Test
    void profileColdStartIsExplicitAndDoesNotInventInterests() {
        var profile = profile(me);
        assertTrue(profile.coldStart());
        assertEquals("CURRENT_SITE", profile.dataScope());
        assertNull(profile.lastBehaviorAt());
        assertNull(profile.manualPreferences().updatedAt());
        assertEquals(new RecommendationProfileVO.SignalCounts(0, 0, 0, 0, 0), profile.signalCounts());
        assertTrue(profile.tastePreferences().isEmpty());
        assertTrue(profile.categoryPreferences().isEmpty());
        assertTrue(profile.restaurantPreferences().isEmpty());
    }

    @Test
    void profileExposesTheSameFeaturesAndSourceContributionsUsedByRanking() {
        long seed = food("dessert", 0, false);
        long match = matchingPair(seed, "sweet");
        long sweet = jdbc.queryForObject("SELECT id FROM food_tags WHERE normalized_name='sweet'", Long.class);
        tagging(seed, sweet, me);
        tagging(seed, sweet, other); // Public votes do not multiply learned features.
        like(me, seed);
        favorite(me, seed);
        browse(me, "food", seed, 0);
        var profile = profile(me);
        assertFalse(profile.coldStart());
        assertEquals(new RecommendationProfileVO.SignalCounts(1, 1, 1, 1, 0), profile.signalCounts());
        assertNotNull(profile.lastBehaviorAt());
        var feature = profile.tastePreferences().getFirst();
        assertEquals("sweet", feature.key());
        assertEquals(0, new BigDecimal("13").compareTo(feature.weight()));
        assertEquals(0, new BigDecimal("4").compareTo(feature.evidence().tagging()));
        assertEquals(0, new BigDecimal("3").compareTo(feature.evidence().likes()));
        assertEquals(0, new BigDecimal("5").compareTo(feature.evidence().favorites()));
        assertEquals(0, BigDecimal.ONE.compareTo(feature.evidence().browsing()));
        assertEquals("甜品", profile.categoryPreferences().getFirst().label());
        assertNotNull(profile.restaurantPreferences().getFirst().targetId());
        assertEquals(match, ids(me, 1).getFirst());
        jdbc.update("DELETE FROM food_favorites WHERE account_id=?", me);
        jdbc.update("UPDATE likes SET deleted_at=now() WHERE account_id=?", me);
        assertEquals(0, profile(me).signalCounts().likes());
        assertEquals(0, profile(me).signalCounts().favorites());
    }

    @Test
    void privateTagEvidenceNeverLeaksIntoAnotherUsersProfile() {
        long seed = food(null, 0, false);
        long secret = tag("secret-interest", other);
        tagging(seed, secret, other);
        like(me, seed);
        assertTrue(profile(me).tastePreferences().isEmpty());
        assertEquals("secret-interest", profile(other).tastePreferences().getFirst().key());
    }

    @Test
    void profileDeduplicatesBrowseCountsAndExpiresOldHistory() {
        long seed = food("dessert", 0, false);
        for (int i = 0; i < 50; i++) browse(me, "food", seed, 0);
        assertEquals(1, profile(me).signalCounts().browsedFoods());
        assertEquals(0, BigDecimal.ONE.compareTo(profile(me).categoryPreferences().getFirst().weight()));
        jdbc.update("DELETE FROM browse_history WHERE account_id=?", me);
        browse(me, "food", seed, 31);
        assertTrue(profile(me).coldStart());
        assertNull(profile(me).lastBehaviorAt());
    }

    @Test
    void presentationTopTwentyDoesNotTruncateTheProfilesUsedByRanking() {
        long seed = food(null, 0, false);
        long match = food(null, 0, true);
        for (int i = 0; i < 25; i++) {
            long t = tag("feature-%02d".formatted(i), null);
            tagging(seed, t, me);
            if (i == 24) tagging(match, t, uploader);
        }
        long popular = food(null, 100, true);
        var profile = profile(me);
        assertEquals(20, profile.tastePreferences().size());
        assertEquals(25, profile.signalCounts().taggings());
        assertFalse(profile.tastePreferences().stream().anyMatch(f -> f.key().equals("feature-24")));
        assertEquals(List.of(match, popular), ids(me, 20));
    }

    @Test
    void manualTastesAreNormalizedPersistedAndImmediatelyChangeRecommendations() {
        long spicy = food(null, 0, true);
        tagging(spicy, tag("spicy", null), uploader);
        long popular = food(null, 100, true);
        var explicit = profileService.updatePreferences(me, new FoodPreferencesDTO(List.of(" Spicy ", "SPICY"), List.of())).getData();
        assertEquals(List.of("spicy"), explicit.likedTags());
        assertTrue(explicit.dislikedTags().isEmpty());
        assertNotNull(explicit.updatedAt());
        var profile = profile(me);
        assertEquals(List.of("spicy"), profile.manualPreferences().likedTags());
        assertNotNull(profile.manualPreferences().updatedAt());
        assertEquals(0, new BigDecimal("25").compareTo(profile.tastePreferences().getFirst().evidence().manual()));
        assertFalse(profile.coldStart());
        assertEquals(List.of(spicy, popular), ids(me, 20));
        assertTrue(profile(other).manualPreferences().likedTags().isEmpty());
        profileService.updatePreferences(me, new FoodPreferencesDTO(List.of(), List.of(" SPICY ")));
        assertTrue(profile(me).tastePreferences().isEmpty());
        assertFalse(profile(me).coldStart()); // Explicit filtering is personalization too.
        assertEquals(List.of(popular), ids(me, 20));
        profileService.clearPreferences(me);
        assertTrue(profile(me).coldStart());
        assertEquals(List.of(popular, spicy), ids(me, 20));
    }

    @Test
    void contradictoryNormalizedPreferencesAreRejectedWithoutLosingPreviousSettings() {
        profileService.updatePreferences(me, new FoodPreferencesDTO(List.of("sweet"), List.of()));
        assertThrows(BizException.class, () -> profileService.updatePreferences(me,
                new FoodPreferencesDTO(List.of(" SPICY"), List.of("spicy "))));
        assertEquals(List.of("sweet"), profile(me).manualPreferences().likedTags());
    }

    @Test
    void dislikedTagsRespectPrivateOwnershipAndCanExcludeEveryCandidate() {
        long candidate = food(null, 0, true);
        tagging(candidate, tag("private-taste", other), other);
        profileService.updatePreferences(me, new FoodPreferencesDTO(List.of(), List.of("private-taste")));
        assertEquals(List.of(candidate), ids(me, 20), "Other users' private tags must not affect filtering");
        tagging(candidate, tag("private-taste", me), me);
        assertTrue(ids(me, 20).isEmpty(), "Hard dislike exclusions must not be relaxed to fill limit");
        profileService.clearPreferences(me);
        assertEquals(List.of(candidate), ids(me, 20));
    }

    @Test
    void emptyManualReplacementAndAccountDeletionRemovePersistentSettings() {
        profileService.updatePreferences(me, new FoodPreferencesDTO(List.of("sweet"), List.of()));
        profileService.updatePreferences(me, new FoodPreferencesDTO(List.of(), List.of()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_food_preferences WHERE account_id=?", Integer.class, me));
        profileService.updatePreferences(me, new FoodPreferencesDTO(List.of("sweet"), List.of()));
        jdbc.update("DELETE FROM users WHERE id=?", me);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_food_preferences WHERE account_id=?", Integer.class, me));
    }

    private RecommendationProfileVO profile(long account) {
        session.clearCache();
        return profileService.getProfile(account).getData();
    }

    @Test void uploaderFilterSharesCountAndPaging() {
        long first=food("staple",1,true),second=food("drink",2,true),another=food("staple",3,true);
        jdbc.update("UPDATE foods SET account_id=? WHERE id IN (?,?)",other,first,second);
        var q=new you.v50to.eatwhat.data.dto.FoodQueryDTO();q.setUploaderId(other);
        assertEquals(2L,foods.countFoods(q,me,false,List.of()));
        var page=foods.selectFoods(q,me,false,List.of(),0,1);
        assertEquals(1,page.size());assertEquals(other,page.getFirst().getAccountId());
        assertFalse(foods.selectFoods(q,me,false,List.of(),0,20).stream().anyMatch(f->f.getId()==another));
        q.setUploaderId(Long.MAX_VALUE);assertEquals(0L,foods.countFoods(q,me,false,List.of()));
    }

    @Test
    void discoveryFiltersAcrossRestaurantsBeforePagingAndSharesCount() {
        long first = food("staple", 1, true), second = food("staple", 2, true);
        long expensive = food("staple", 9, true), drink = food("drink", 10, true);
        jdbc.update("UPDATE foods SET name='米饭',price=12.50 WHERE id IN (?,?)", first, second);
        jdbc.update("UPDATE foods SET name='米饭',price=25 WHERE id=?", expensive);
        jdbc.update("UPDATE foods SET name='米饭',price=10 WHERE id=?", drink);
        var q = new you.v50to.eatwhat.data.dto.FoodQueryDTO();
        q.setKeyword("米饭"); q.setCategory(FoodCategory.STAPLE); q.setMaxPrice(new BigDecimal("20"));
        q.setSort(you.v50to.eatwhat.data.dto.FoodQueryDTO.Sort.PRICE_ASC);
        assertEquals(2L, foods.countFoods(q, me, false, List.of()));
        assertEquals(List.of(second), foods.selectFoods(q, me, false, List.of(), 0, 1).stream().map(Food::getId).toList());
        var page = foods.selectFoods(q, me, false, List.of(), 1, 1);
        assertEquals(first, page.getFirst().getId());
        assertEquals(new BigDecimal("12.50"), page.getFirst().getPrice());
        assertEquals("test restaurant", page.getFirst().getRestaurantName());
        q.setRestaurantId(page.getFirst().getRestaurantId());
        assertEquals(1L, foods.countFoods(q, me, false, List.of()));
    }

    @Test
    void favoritesFilterAllPagesAndNeverExposeAnotherAccountsCustomTags() {
        long first = food("staple", 1, true), second = food("staple", 1, true);
        long excluded = food("staple", 1, true), notMine = food("staple", 1, true);
        for (long id : List.of(first, second, excluded)) favorite(me, id);
        favorite(other, notMine);
        long wanted = tag("想吃", me), otherWanted = tag("想吃", other), publicTag = tag("实惠", null);
        tagging(first, wanted, me); tagging(second, wanted, me);
        tagging(excluded, otherWanted, other); tagging(notMine, wanted, me);
        tagging(first, publicTag, me); tagging(second, publicTag, other);
        var q = new you.v50to.eatwhat.data.dto.FoodQueryDTO(); q.setMyCustomTagIds(List.of(wanted));
        q.setSystemTagIds(List.of(publicTag));
        assertEquals(2L, foods.countFoods(q, me, true, List.of("想吃")));
        assertEquals(List.of(second), foods.selectFoods(q, me, true, List.of("想吃"), 0, 1).stream().map(Food::getId).toList());
        assertEquals(List.of(first), foods.selectFoods(q, me, true, List.of("想吃"), 1, 1).stream().map(Food::getId).toList());
        q.setMyCustomTagIds(List.of(otherWanted));
        assertEquals(0L, foods.countFoods(q, me, true, List.of()));
        q.setMyCustomTagIds(null); q.setSystemTagIds(null);
        assertEquals(1L, foods.countFoods(q, other, true, List.of()));
    }

    @Test
    void searchTreatsWildcardsAndSqlAsLiteralTextAndUnknownPriceDoesNotMeetBudget() {
        long match = food(null, 1, true), unknown = food(null, 1, true);
        jdbc.update("UPDATE foods SET name='100%_饭',price=0 WHERE id=?", match);
        jdbc.update("UPDATE foods SET name='10000饭' WHERE id=?", unknown);
        var q = new you.v50to.eatwhat.data.dto.FoodQueryDTO(); q.setKeyword("%_");
        assertEquals(1L, foods.countFoods(q, me, false, List.of()));
        q.setKeyword("' OR 1=1 --");
        assertEquals(0L, foods.countFoods(q, me, false, List.of()));
        q.setKeyword(null); q.setMaxPrice(BigDecimal.ZERO);
        assertEquals(1L, foods.countFoods(q, me, false, List.of()));
    }

    private List<Long> ids(long account, int limit) {
        // JDBC mutations in this fixture bypass the mapper's local session cache.
        session.clearCache();
        return foods.selectRecommendationCandidates(account, 1L, true, null, limit).stream().map(Food::getId).toList();
    }

    private long user(String name) {
        return insert("INSERT INTO users(nick_name,password_hash) VALUES (?,'synthetic') RETURNING id", name);
    }

    @Test
    void candidatesAreScopedToTheCampusAndCategoryAndCarryShopLocation() {
        insert("INSERT INTO campus(id,name,gcj_lng,gcj_lat) VALUES (2,'中心校区',117.0,36.6) RETURNING id");
        long softwareHub = hub("软件园食堂", 1L);
        long otherHub = hub("中心校区食堂", 2L);
        long unassignedHub = hub("没分校区的商圈", null);
        long inSoftware = foodAt(restaurantIn(softwareHub), "staple", 1, true);
        long inOther = foodAt(restaurantIn(otherHub), "staple", 1, true);
        long inUnassigned = foodAt(restaurantIn(unassignedHub), "drink", 1, true);
        long withoutHub = food("staple", 1, true);

        session.clearCache();
        assertEquals(java.util.Set.of(inSoftware, inUnassigned, withoutHub), java.util.Set.copyOf(
                foods.selectRecommendationCandidates(me, 1L, true, null, 20).stream().map(Food::getId).toList()));
        assertEquals(List.of(inOther), foods.selectRecommendationCandidates(me, 2L, false, null, 20)
                .stream().map(Food::getId).toList());
        assertEquals(java.util.Set.of(inSoftware, withoutHub), java.util.Set.copyOf(
                foods.selectRecommendationCandidates(me, 1L, true, "staple", 20).stream().map(Food::getId).toList()));

        var row = foods.selectRecommendationCandidates(me, 1L, true, null, 20).stream()
                .filter(f -> f.getId() == inSoftware).findFirst().orElseThrow();
        assertEquals("软件园食堂", row.getHubName());
        assertEquals(softwareHub, row.getHubId());
        assertEquals("1 层", row.getRestaurantAddress());
        assertEquals(117.0, row.getRestaurantGcjLng());
        assertNotNull(row.getQualityScore());
        assertEquals(0.0, row.getPersonalScore());
        assertFalse(row.getOwnMark());
        assertArrayEquals(new String[0], row.getMealTimes());
    }

    @Test
    void qualityScoreRewardsLikesTagsAndFreshness() {
        long popular = food(null, 50, true);
        long tagged = food(null, 0, true);
        tagging(tagged, tag("皮薄", null), uploader);
        long old = food(null, 0, true);
        jdbc.update("UPDATE foods SET created_at=now()-interval '30 days' WHERE id=?", old);
        long fresh = food(null, 0, true);

        session.clearCache();
        var scores = new java.util.HashMap<Long, Double>();
        foods.selectRecommendationCandidates(null, 1L, true, null, 20).forEach(r -> scores.put(r.getId(), r.getQualityScore()));
        assertTrue(scores.get(popular) > scores.get(fresh));
        assertTrue(scores.get(tagged) > scores.get(fresh) - 0.01 && scores.get(tagged) > scores.get(old));
        assertTrue(scores.get(fresh) > scores.get(old));
    }

    private long hub(String name, Long campus) {
        return insert("""
                INSERT INTO hub(name,center,gcj_lng,gcj_lat,campus_id)
                VALUES (?,ST_SetSRID(ST_MakePoint(117,36),4326),117,36,?) RETURNING id
                """, name, campus);
    }

    private long restaurantIn(long hub) {
        return insert("""
                INSERT INTO restaurant(name,address,location,gcj_lng,gcj_lat,hub_id)
                VALUES ('hub restaurant','1 层',ST_SetSRID(ST_MakePoint(117,36),4326)::geography,117,36,?) RETURNING id
                """, hub);
    }

    private long restaurant() {
        return insert("""
                INSERT INTO restaurant(name,location,gcj_lng,gcj_lat)
                VALUES ('test restaurant',ST_SetSRID(ST_MakePoint(117,36),4326)::geography,117,36) RETURNING id
                """);
    }

    private long food(String category, int popularity, boolean picture) {
        return foodAt(restaurant(), category, popularity, picture);
    }

    private long foodAt(long restaurant, String category, int popularity, boolean picture) {
        return insert("""
                INSERT INTO foods(account_id,restaurant_id,name,category,likes_count,picture_url)
                VALUES (?,?,'test food',?,?,CASE WHEN ? THEN ARRAY['test.png'] ELSE NULL END) RETURNING id
                """, uploader, restaurant, category, popularity, picture);
    }

    private long tag(String name, Long owner) {
        return insert("""
                INSERT INTO food_tags(name,normalized_name,tag_type,owner_id)
                VALUES (?,?,?,?) RETURNING id
                """, name, name, owner == null ? "system" : "custom", owner);
    }

    private void tagging(long food, long tag, long account) {
        jdbc.update("INSERT INTO food_taggings(food_id,tag_id,account_id) VALUES (?,?,?)", food, tag, account);
    }

    private long matchingPair(long seed, String name) {
        long tag = tag(name, null);
        tagging(seed, tag, uploader);
        long match = food(null, 0, true);
        tagging(match, tag, uploader);
        return match;
    }

    private void like(long account, long food) {
        jdbc.update("INSERT INTO likes(account_id,target_type,target_id) VALUES (?,'food',?)", account, food);
    }

    private void favorite(long account, long food) {
        jdbc.update("INSERT INTO food_favorites(account_id,food_id) VALUES (?,?)", account, food);
    }

    private void browse(long account, String type, long target, int daysAgo) {
        jdbc.update("INSERT INTO browse_history(account_id,target_type,target_id,created_at) VALUES (?,?,?,now()-?*interval '1 day')",
                account, type, target, daysAgo);
    }

    private long insert(String sql, Object... values) {
        return jdbc.queryForObject(sql, Long.class, values);
    }
}
