package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.service.RecommendationSeenStore;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Redis: daily recommendation dedup keys, reset, expiry and fail-open behavior. */
class RecommendationSeenStoreIT {
    static GenericContainer<?> container;
    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;

    @BeforeAll static void start() {
        container = IsolatedContainers.redis("recommendation-seen");
        container.start();
        connections = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll static void stop() {
        if (connections != null) connections.destroy();
        if (container != null) container.stop();
    }

    @BeforeEach void clear() {
        try (var c = connections.getConnection()) { c.serverCommands().flushDb(); }
    }

    private RecommendationSeenStore store(StringRedisTemplate template, String site) {
        var store = new RecommendationSeenStore(template);
        ReflectionTestUtils.setField(store, "siteId", site);
        return store;
    }

    @Test void recordsAccumulateUntilResetAndExpireWithinTwoDays() {
        var store = store(redis, "campus");
        String key = store.key("u7", 1, null, LocalDate.of(2026, 10, 23));
        store.record(key, List.of(1L, 2L), false);
        store.record(key, List.of(3L), false);
        assertEquals(Set.of(1L, 2L, 3L), store.seen(key));
        long ttl = redis.getExpire(key);
        assertTrue(ttl > 47 * 3600 && ttl <= 48 * 3600, "ttl=" + ttl);

        store.record(key, List.of(4L), true);
        assertEquals(Set.of(4L), store.seen(key));
    }

    @Test void keysSeparateActorCampusCategoryDayAndSite() {
        var store = store(redis, "campus");
        LocalDate day = LocalDate.of(2026, 10, 23);
        String base = store.key("u7", 1, null, day);
        for (String other : List.of(store.key("u8", 1, null, day), store.key("dabcdefgh", 1, null, day),
                store.key("u7", 2, null, day), store.key("u7", 1, "staple", day), store.key("u7", 1, null, day.plusDays(1)),
                store(redis, "external").key("u7", 1, null, day)))
            assertNotEquals(base, other);
    }

    @Test void unavailableRedisIsTreatedAsNothingSeen() {
        var broken = new LettuceConnectionFactory("127.0.0.1", 1);
        broken.afterPropertiesSet();
        broken.start();
        try {
            var store = store(new StringRedisTemplate(broken), "campus");
            String key = store.key("u7", 1, null, LocalDate.of(2026, 10, 23));
            assertEquals(Set.of(), store.seen(key));
            store.record(key, List.of(1L), true);
        } finally {
            broken.destroy();
        }
    }
}
