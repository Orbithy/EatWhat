package you.v50to.eatwhat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** An explicit site cannot start successfully without its own authentication/session store. */
@Component
@RequiredArgsConstructor
public class SiteDependencyVerifier implements ApplicationRunner {
    private final SiteProperties site;
    private final StringRedisTemplate redis;

    @Override
    public void run(ApplicationArguments args) {
        if (site.getMode() == SiteProperties.Mode.LEGACY) {
            return;
        }
        try {
            String response = redis.execute((RedisCallback<String>) connection -> connection.ping());
            if (!"PONG".equals(response)) {
                throw new IllegalStateException();
            }
        } catch (RuntimeException unavailable) {
            // A connection URI may contain credentials; keep it out of the startup failure message.
            throw new IllegalStateException("Explicit site startup requires reachable local Redis (spring.data.redis.*)");
        }
    }
}
