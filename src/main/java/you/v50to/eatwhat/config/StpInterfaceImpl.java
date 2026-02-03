package you.v50to.eatwhat.config;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import you.v50to.eatwhat.data.po.Verification;
import you.v50to.eatwhat.mapper.VerificationMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component    // 保证此类被 SpringBoot 扫描，完成 Sa-Token 的自定义权限验证扩展
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private VerificationMapper verificationMapper;
    @Resource
    private StringRedisTemplate redis;

    public static final Duration TTL = Duration.ofMinutes(20);

    /**
     * 返回一个账号所拥有的权限码集合
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        String key = "auth:" + userId;

        String method = redis.opsForValue().get(key);
        if (method != null) {
            return "NONE".equals(method)
                    ? Collections.emptyList()
                    : List.of("verify:" + method);
        }

        Verification v = verificationMapper.selectOne(
                new LambdaQueryWrapper<Verification>()
                        .select(Verification::getMethod)
                        .eq(Verification::getAccountId, userId)
        );

        if (v == null || v.getMethod() == null) {
            redis.opsForValue().set(key, "NONE", Duration.ofMinutes(2));
            return Collections.emptyList();
        }

        redis.opsForValue().set(key, v.getMethod(), TTL);
        return List.of("verify:" + v.getMethod());
    }

    /**
     * 返回一个账号所拥有的角色标识集合 (权限与角色可分开校验)
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        String key = "auth:" + userId;

        String method = redis.opsForValue().get(key);
        if (method != null) {
            return "NONE".equals(method)
                    ? Collections.emptyList()
                    : List.of("verified");
        }
        Verification v = verificationMapper.selectOne(
                new LambdaQueryWrapper<Verification>()
                        .select(Verification::getMethod)
                        .eq(Verification::getAccountId, userId)
        );

        if (v == null || v.getMethod() == null) {
            redis.opsForValue().set(key, "NONE", Duration.ofMinutes(2));
            return Collections.emptyList();
        }

        redis.opsForValue().set(key, v.getMethod());
        redis.expire(key, TTL);
        return List.of("verified");
    }
}
