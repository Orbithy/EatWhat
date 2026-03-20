package you.v50to.eatwhat.config;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.po.Verification;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.mapper.VerificationMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private VerificationMapper verificationMapper;
    @Resource
    private UserMapper userMapper;
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
        String authKey = "auth:" + userId;
        String roleKey = "role:" + userId;

        String method = redis.opsForValue().get(authKey);
        String cachedRole = redis.opsForValue().get(roleKey);
        if (method != null && cachedRole != null) {
            return buildRoleList(!"NONE".equals(method), cachedRole);
        }

        Verification v = verificationMapper.selectOne(
                new LambdaQueryWrapper<Verification>()
                        .select(Verification::getMethod)
                        .eq(Verification::getAccountId, userId)
        );
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .select(User::getRole)
                        .eq(User::getId, userId)
        );

        String role = (user != null && user.getRole() != null) ? user.getRole() : "user";

        if (v == null || v.getMethod() == null) {
            redis.opsForValue().set(authKey, "NONE", Duration.ofMinutes(2));
            redis.opsForValue().set(roleKey, role, TTL);
            return buildRoleList(false, role);
        }

        redis.opsForValue().set(authKey, v.getMethod(), TTL);
        redis.opsForValue().set(roleKey, role, TTL);

        return buildRoleList(true, role);
    }

    /**
     * 构建角色列表：verified 表示已认证，admin 表示管理员
     */
    private List<String> buildRoleList(boolean verified, String role) {
        List<String> roles = new ArrayList<>();
        if (verified) {
            roles.add("verified");
        }
        if ("admin".equals(role)) {
            roles.add("admin");
        }
        return roles;
    }
}
