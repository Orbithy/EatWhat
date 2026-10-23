package you.v50to.eatwhat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 当天推荐过哪些菜（产品文档第 8 节「不重复」）。按 用户或设备、校区、分类、日期 分开记，保留 48 小时。
 * Redis 不可用时当作什么都没看过，推荐照常返回。
 */
@Slf4j
@Component
public class RecommendationSeenStore {
    private static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redis;

    @Value("${app.site.id:legacy}")
    private String siteId = "legacy";

    public RecommendationSeenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String key(String actor, long campusId, String category, LocalDate day) {
        return "rec:seen:v1:" + siteId + ":" + actor + ":" + campusId + ":" + (category == null ? "all" : category) + ":" + day;
    }

    public Set<Long> seen(String key) {
        try {
            Set<String> members = redis.opsForSet().members(key);
            return members == null ? Set.of() : members.stream().map(Long::valueOf).collect(Collectors.toSet());
        } catch (RuntimeException unavailable) {
            log.warn("推荐去重记录读取失败，本次不去重: {}", unavailable.toString());
            return Set.of();
        }
    }

    /** reset 为 true 时先清空，表示能推荐的菜都看过一遍，从这一批重新开始记。 */
    public void record(String key, Collection<Long> ids, boolean reset) {
        if (ids.isEmpty() && !reset) return;
        try {
            if (reset) redis.delete(key);
            if (!ids.isEmpty()) {
                redis.opsForSet().add(key, ids.stream().map(String::valueOf).toArray(String[]::new));
                redis.expire(key, TTL);
            }
        } catch (RuntimeException unavailable) {
            log.warn("推荐去重记录写入失败: {}", unavailable.toString());
        }
    }
}
