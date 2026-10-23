package you.v50to.eatwhat.service;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import org.springframework.web.client.ResourceAccessException;
import work.foofish.smsverify.SmsTemplate;
import work.foofish.smsverify.config.AliyunDefaultRequest;
import work.foofish.smsverify.core.SmsRequest;
import work.foofish.smsverify.core.SmsResponse;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.exception.BizException;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SmsService {

    private final StringRedisTemplate redis;

    private final SmsTemplate smsTemplate;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Duration CODE_TTL = Duration.ofMinutes(5);         // 验证码有效期
    private static final Duration SEND_LIMIT_TTL = Duration.ofSeconds(60);  // 同手机号发送间隔
    private static final int MAX_VERIFY_FAILS = 5;                          // 最大错误次数
    private static final int CODE_LEN = 6;
    private static final String ANONYMOUS_ACTOR = "anon";

    private static final boolean ENABLE_IP_LIMIT = true;
    private static final Duration IP_LIMIT_TTL = Duration.ofSeconds(60);
    private static final int IP_LIMIT_MAX = 20; // 60秒内同IP最多发送20次（按需调）

    private static final int GLOBAL_MAX_PER_MIN = 300; // 每分钟最高发送量（可选，全局限流）
    private static final long GLOBAL_WINDOW_SECONDS = 60;

    @Value("${app.site.id:legacy}")
    private String siteId = "legacy";
    /** Present only in a bound multi-site cluster; routes each challenge to the single site that owns its state. */
    @Autowired(required = false)
    private ChallengePeers peers;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DefaultRedisScript<Long> STORE = new DefaultRedisScript<>("""
            redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[2])
            redis.call('SET',KEYS[2],'0','EX',ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            local stored = redis.call('GET',KEYS[1])
            if not stored or stored ~= ARGV[1] then return -1 end
            local failures = tonumber(redis.call('GET',KEYS[2]) or '0')
            if failures >= tonumber(ARGV[3]) then return -2 end
            if stored ~= ARGV[2] then
                failures = redis.call('INCR',KEYS[2])
                redis.call('PEXPIRE',KEYS[2],math.max(1,redis.call('PTTL',KEYS[1])))
                if failures >= tonumber(ARGV[3]) then return -2 end
                return 0
            end
            redis.call('DEL',KEYS[1],KEYS[2])
            return 1
            """, Long.class);

    public SmsService(StringRedisTemplate redis, SmsTemplate smsTemplate) {
        this.redis = redis;
        this.smsTemplate = smsTemplate;
    }

    /**
     * 发送验证码
     *
     * @param scene    业务场景
     * @param mobile   手机号
     * @param clientIp 客户端IP
     */
    public void sendCode(Scene scene, String mobile, String clientIp) {
        sendCode(scene, mobile, clientIp, StpUtil.getLoginIdAsLong());
    }

    public void sendCode(Scene scene, String mobile, String clientIp, Long userId) {
        validateActor(userId);
        send(scene, mobile, clientIp, userId.toString());
    }

    /** 未登录时的手机号登录验证码，挑战只绑定场景和手机号。 */
    public void sendLoginCode(String mobile, String clientIp) {
        send(Scene.login, mobile, clientIp, ANONYMOUS_ACTOR);
    }

    private void send(Scene scene, String mobile, String clientIp, String actor) {
        validate(scene, mobile);
        applyGlobalLimit(scene);

        if (ENABLE_IP_LIMIT && clientIp != null && !clientIp.isBlank()) {
            applyIpRateLimit(scene, clientIp);
        }

        String owner = ownerSite(scene, mobile);

        // 同手机号发送频率限制：60秒一次（由验证码归属站点统一计数）
        if (!reserve(owner, scene, mobile)) {
            throw new BizException(BizCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }

        String code = gen6Digits();

        try {
            SmsRequest request = switch (scene) {
                case auth, login -> AliyunDefaultRequest.auth(mobile, code, String.valueOf(CODE_TTL.toMinutes()));
                case change -> AliyunDefaultRequest.change(mobile, code, String.valueOf(CODE_TTL.toMinutes()));
                case forget -> AliyunDefaultRequest.forget(mobile, code, String.valueOf(CODE_TTL.toMinutes()));
                case bind -> AliyunDefaultRequest.bind(mobile, code, String.valueOf(CODE_TTL.toMinutes()));
                case verifybind ->
                        AliyunDefaultRequest.verifyBind(mobile, code, String.valueOf(CODE_TTL.toMinutes()));
                };
            SmsResponse resp = null;

            try {
                resp = smsTemplate.send(request);
            } catch (ResourceAccessException e) {
                if (e.getCause() instanceof SocketTimeoutException) {
                    throw new BizException(
                            BizCode.THIRD_PARTY_TIMEOUT,
                            "短信服务请求超时"
                    );
                }
                log.error("短信服务访问异常", e);
                throw new BizException(
                        BizCode.THIRD_PARTY_UNAVAILABLE,
                        "短信服务不可用"
                );
            }

            if (resp == null || !resp.isSuccess()) {
                log.warn(
                        "短信发送失败，resp={}",
                        resp == null ? "null" : resp
                );
                throw new BizException(BizCode.THIRD_PARTY_BAD_RESPONSE, "短信发送失败：" + (resp == null ? "null response" : resp.getErrorMessage()));
            }
        } catch (RuntimeException e) {
            release(owner, scene, mobile);
            throw e;
        } catch (Exception e) {
            release(owner, scene, mobile);
            log.error("短信发送异常：{}", e.getMessage(), e);
            throw new BizException(BizCode.UNKNOWN_ERROR, "短信发送异常：" + e.getMessage());
        }

        String salt = UUID.randomUUID().toString();
        store(owner, scene, mobile, actor, salt + ":" + hashCode(owner, scene, mobile, actor, salt, code));
    }

    public boolean verifyCode(Scene scene, String mobile, String inputCode) {
        return verifyCode(scene, mobile, inputCode, StpUtil.getLoginIdAsLong());
    }

    public boolean verifyCode(Scene scene, String mobile, String inputCode, Long userId) {
        validateActor(userId);
        return verify(scene, mobile, inputCode, userId.toString());
    }

    public boolean verifyLoginCode(String mobile, String inputCode) {
        return verify(Scene.login, mobile, inputCode, ANONYMOUS_ACTOR);
    }

    private boolean verify(Scene scene, String mobile, String inputCode, String actor) {
        validate(scene, mobile);
        if (inputCode == null || inputCode.isBlank()) throw new BizException(BizCode.PARAM_MISSING, "验证码不能为空");
        String owner = ownerSite(scene, mobile);
        Long result = isRemote(owner) ? consumeRemote(owner, scene, mobile, actor, inputCode)
                : consumeLocal(scene, mobile, actor, inputCode);
        if (Long.valueOf(1).equals(result)) return true;
        if (Long.valueOf(-2).equals(result)) throw new BizException(BizCode.TOO_MANY_REQUESTS, "验证码错误次数过多，请稍后再试");
        if (Long.valueOf(0).equals(result)) throw new BizException(BizCode.VERIFY_CODE_ERROR);
        throw new BizException(BizCode.PARAM_INVALID, "验证码已过期或不存在");
    }

    private static void validateActor(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(BizCode.PARAM_INVALID, "验证码必须绑定当前账号");
    }

    private void applyIpRateLimit(Scene scene, String clientIp) {
        String ipKey = keyIpLimit(scene, clientIp);
        Long cnt = redis.opsForValue().increment(ipKey);
        if (cnt != null && cnt == 1L) {
            redis.expire(ipKey, IP_LIMIT_TTL.toSeconds(), TimeUnit.SECONDS);
        }
        if (cnt != null && cnt > IP_LIMIT_MAX) {
            throw new BizException(BizCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
    }

    private static String gen6Digits() {
        int n = RANDOM.nextInt(1_000_000);
        return String.format("%0" + CODE_LEN + "d", n);
    }

    private static void validate(Scene scene, String mobile) {
        if (scene == null) {
            throw new BizException(BizCode.PARAM_MISSING, "scene不能为空");
        }
        if (mobile == null || mobile.isBlank()) {
            throw new BizException(BizCode.PARAM_MISSING, "手机号不能为空");
        }
        if (mobile.length() < 6 || mobile.length() > 20) {
            throw new BizException(BizCode.PARAM_INVALID, "手机号格式不正确");
        }
    }

    // Both keys use the same Redis slot; account, purpose, destination and owning site are bound.
    // Anonymous login challenges use a non-numeric actor so they never collide with an account-bound one.
    private static String challengeKey(String site, Scene scene, String mobile, String actor) {
        return "sms:v2:{" + site + ":" + actor + ":" + scene + ":" + mobile + "}";
    }
    private static String keyCode(String site, Scene scene, String mobile, String actor) { return challengeKey(site,scene,mobile,actor) + ":code"; }
    private static String keyFailCount(String site, Scene scene, String mobile, String actor) { return challengeKey(site,scene,mobile,actor) + ":cnt"; }
    private static String keyLimit(String site, Scene scene, String mobile) { return "sms:v2:" + site + ":limit:" + scene + ":" + mobile; }
    private String keyIpLimit(Scene scene, String ip) { return "sms:v2:" + siteId + ":ip:" + scene + ":" + ip; }

    private static String hashCode(String site, Scene scene, String mobile, String actor, String salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = site + "|" + actor + "|" + scene + "|" + mobile + "|" + salt + "|" + code;
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- 验证码归属站点：状态（验证码、错误次数、发送间隔）只存在归属站点，另一站点经站点间通道转发 ----

    private String ownerSite(Scene scene, String mobile) {
        if (peers == null) return siteId;
        try {
            String owner = peers.owner(scene, mobile);
            if (owner == null || owner.isBlank()) throw new IllegalStateException("Missing challenge owner");
            return owner;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private boolean isRemote(String owner) { return peers != null && !owner.equals(siteId); }

    private static BizException unavailable(Exception cause) {
        log.warn("验证码归属站点不可用: {}", cause.getClass().getSimpleName());
        return new BizException(BizCode.THIRD_PARTY_UNAVAILABLE, "验证码服务暂不可用，请稍后再试");
    }

    private JsonNode callOwner(String owner, String operation, Scene scene, String mobile, String actor, String extraName, String extra) {
        var body = JSON.createObjectNode().put("scene", scene.name()).put("mobile", mobile);
        if (actor != null) body.put("actor", actor);
        if (extraName != null) body.put(extraName, extra);
        try {
            return peers.call(owner, operation, body);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private boolean reserve(String owner, Scene scene, String mobile) {
        if (isRemote(owner)) return callOwner(owner, "sms-reserve", scene, mobile, null, null, null).path("ok").asBoolean(false);
        return reserveLocal(scene, mobile);
    }

    private void release(String owner, Scene scene, String mobile) {
        if (!isRemote(owner)) { releaseLocal(scene, mobile); return; }
        try { callOwner(owner, "sms-release", scene, mobile, null, null, null); }
        catch (BizException e) { log.warn("释放验证码发送间隔失败"); }
    }

    private void store(String owner, Scene scene, String mobile, String actor, String value) {
        if (!isRemote(owner)) { storeLocal(scene, mobile, actor, value); return; }
        if (!callOwner(owner, "sms-store", scene, mobile, actor, "value", value).path("ok").asBoolean(false)) {
            throw new BizException(BizCode.THIRD_PARTY_UNAVAILABLE, "验证码服务暂不可用，请稍后再试");
        }
    }

    private Long consumeRemote(String owner, Scene scene, String mobile, String actor, String input) {
        JsonNode reply = callOwner(owner, "sms-verify", scene, mobile, actor, "code", input);
        return reply.path("result").isIntegralNumber() ? reply.path("result").asLong() : -1L;
    }

    private boolean reserveLocal(Scene scene, String mobile) {
        Boolean ok = redis.opsForValue().setIfAbsent(keyLimit(siteId, scene, mobile), "1", SEND_LIMIT_TTL);
        return ok != null && ok;
    }

    private void releaseLocal(Scene scene, String mobile) { redis.delete(keyLimit(siteId, scene, mobile)); }

    private void storeLocal(Scene scene, String mobile, String actor, String value) {
        redis.execute(STORE, List.of(keyCode(siteId, scene, mobile, actor), keyFailCount(siteId, scene, mobile, actor)),
                value, String.valueOf(CODE_TTL.toSeconds()));
    }

    /** 1 成功并消费，0 错误，-1 不存在或已过期，-2 错误次数已满。 */
    private Long consumeLocal(Scene scene, String mobile, String actor, String input) {
        String codeKey = keyCode(siteId, scene, mobile, actor);
        String stored = redis.opsForValue().get(codeKey);
        if (stored == null || !stored.contains(":")) return -1L;
        String salt = stored.substring(0, stored.indexOf(':'));
        return redis.execute(CONSUME, List.of(codeKey, keyFailCount(siteId, scene, mobile, actor)), stored,
                salt + ":" + hashCode(siteId, scene, mobile, actor, salt, input), String.valueOf(MAX_VERIFY_FAILS));
    }

    /** 站点间调用入口：只处理归属本站点的验证码，归属不一致一律拒绝，避免错误次数被拆到两个站点。 */
    public JsonNode handleChallenge(String operation, JsonNode body) {
        if (peers == null || body == null || !body.isObject()) throw new IllegalArgumentException("SMS challenge not active");
        Scene scene = Scene.valueOf(body.path("scene").asString(""));
        String mobile = body.path("mobile").asString("");
        validate(scene, mobile);
        String actor = body.has("actor") ? body.path("actor").asString("") : null;
        if (actor != null && !ANONYMOUS_ACTOR.equals(actor) && !actor.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid actor");
        if (!siteId.equals(peers.owner(scene, mobile))) throw new IllegalArgumentException("SMS challenge routed to the wrong site");
        var reply = JSON.createObjectNode();
        switch (operation) {
            case "sms-reserve" -> reply.put("ok", reserveLocal(scene, mobile));
            case "sms-release" -> { releaseLocal(scene, mobile); reply.put("ok", true); }
            case "sms-store" -> {
                String value = body.path("value").asString("");
                if (actor == null || !value.matches("[0-9a-f-]{36}:[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid challenge");
                storeLocal(scene, mobile, actor, value);
                reply.put("ok", true);
            }
            case "sms-verify" -> {
                String code = body.path("code").asString("");
                if (actor == null || code.isBlank() || code.length() > 16) throw new IllegalArgumentException("Invalid challenge");
                Long result = consumeLocal(scene, mobile, actor, code);
                reply.put("result", result == null ? -1L : result);
            }
            default -> throw new IllegalArgumentException("Unknown SMS challenge operation");
        }
        return reply;
    }

    private void applyGlobalLimit(Scene scene) {
        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String key = "sms:v2:" + siteId + ":global:" + scene + ":m:" + minute;

        Long cnt = redis.opsForValue().increment(key);
        if (cnt != null && cnt == 1L) {
            redis.expire(key, GLOBAL_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        if (cnt != null && cnt > GLOBAL_MAX_PER_MIN) {
            log.warn(
                    "达到全局速率限制，scene={}, cnt={}, max={}",
                    scene, cnt, GLOBAL_MAX_PER_MIN
            );

            throw new BizException(BizCode.TOO_MANY_REQUESTS, "短信发送过于频繁");
        }
    }
}
