package you.v50to.eatwhat.service;

import jakarta.annotation.Resource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.regex.Pattern;

@Slf4j
@Service
public class EmailService {

    /**
     * 邮件用途枚举
     */
    public enum EmailPurpose {
        VERIFICATION,  // 用于身份验证（SDU邮箱验证）
        BINDING,       // 用于绑定邮箱
        RECOVERY       // 本站账号密码恢复
    }

    @Resource
    private JavaMailSender mailSender;

    @Resource
    private StringRedisTemplate redis;

    @Value("${app.mail.from}")
    private String fromEmail;

    @Value("${app.mail.from-name:EatWhat}")
    private String fromName;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.site.id:legacy}")
    private String siteId = "legacy";

    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value or string.sub(value,1,string.len(ARGV[1])) ~= ARGV[1] then return nil end
            redis.call('DEL', KEYS[1])
            return string.sub(value,string.len(ARGV[1])+1)
            """, String.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);  // 验证链接有效期30分钟
    private static final Duration SEND_LIMIT_TTL = Duration.ofSeconds(60);  // 同邮箱发送间隔60秒
    private static final int TOKEN_LENGTH = 32;  // Token长度

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );

    // SDU 邮箱域名
    private static final String SDU_EMAIL_DOMAIN_1 = "@sdu.edu.cn";
    private static final String SDU_EMAIL_DOMAIN_2 = "@mail.sdu.edu.cn";

    /**
     * 发送邮箱验证链接
     *
     * @param email    邮箱地址
     * @param userId   用户ID
     * @param clientIp 客户端IP（可选，用于日志）
     * @param purpose  邮件用途（验证或绑定）
     */
    public void sendVerificationLink(String email, Long userId, String clientIp, EmailPurpose purpose) {
        if (userId == null || userId <= 0 || purpose == null) throw new BizException(BizCode.PARAM_INVALID);
        // 验证邮箱格式
        if (email == null || email.isBlank() || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new BizException(BizCode.EMAIL_INVALID);
        }

        // 仅在验证模式下检查是否为 SDU 邮箱
        if (purpose == EmailPurpose.VERIFICATION && !isSDUEmail(email)) {
            throw new BizException(BizCode.EMAIL_NOT_SDU);
        }

        // 发送频率限制
        String limitKey = keyLimit(email);
        Boolean ok = redis.opsForValue().setIfAbsent(limitKey, "1", SEND_LIMIT_TTL);
        if (ok == null || !ok) {
            throw new BizException(BizCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }

        // 生成验证token
        String token = generateToken();

        try {
            // 构建验证链接
            String verifyUrl = baseUrl + (purpose == EmailPurpose.RECOVERY
                    ? "/auth/password-recovery?token=" : "/auth/verifyEmail?token=") + token;

            // 发送邮件
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(fromEmail, fromName);  // 设置发件人邮箱和名称
            helper.setTo(email);

            // 根据用途设置不同的邮件主题和内容
            if (purpose == EmailPurpose.VERIFICATION) {
                helper.setSubject("SDU邮箱身份验证 - EatWhat");
                helper.setText(buildVerificationEmailContent(verifyUrl), true);
            } else if (purpose == EmailPurpose.RECOVERY) {
                helper.setSubject("账号密码恢复 - EatWhat");
                helper.setText("请在发起恢复的站点使用以下一次性令牌完成密码恢复（30分钟内有效）：\n" + token
                        + "\n如果不是您发起的操作，请忽略此邮件。", false);
            } else {
                helper.setSubject("邮箱绑定验证 - EatWhat");
                helper.setText(buildBindingEmailContent(verifyUrl), true);
            }

            redis.opsForValue().set(keyToken(token), purpose.name() + ":" + userId + ":" + email, TOKEN_TTL);
            mailSender.send(message);

            log.info("邮箱验证链接已发送: email={}, userId={}, ip={}", email, userId, clientIp);

        } catch (MessagingException e) {
            redis.delete(keyToken(token));
            redis.delete(limitKey);
            log.error("邮件发送失败: email={}, error={}", email, e.getMessage(), e);
            throw new BizException(BizCode.THIRD_PARTY_UNAVAILABLE, "邮件发送失败");
        } catch (Exception e) {
            redis.delete(keyToken(token));
            redis.delete(limitKey);
            log.error("邮件发送异常: email={}, error={}", email, e.getMessage(), e);
            throw new BizException(BizCode.UNKNOWN_ERROR, "邮件发送异常");
        }

    }

    /**
     * 验证邮箱token
     *
     * @param token 验证token
     * @return 包含userId和email的数组 [userId, email]
     */
    public EmailVerification verifyToken(String token) {
        return verifyToken(token, EmailPurpose.VERIFICATION);
    }

    /** Purpose is checked before atomic consumption; another site cannot address this challenge. */
    public EmailVerification verifyToken(String token, EmailPurpose expectedPurpose) {
        if (token == null || !token.matches("[0-9a-f]{64}") || expectedPurpose == null) {
            throw new BizException(BizCode.EMAIL_TOKEN_INVALID);
        }
        String data = redis.execute(CONSUME, List.of(keyToken(token)), expectedPurpose.name() + ":");
        if (data == null) throw new BizException(BizCode.EMAIL_TOKEN_INVALID);
        String[] parts = data.split(":", 2);
        try {
            if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException();
            long userId = Long.parseLong(parts[0]);
            if (userId <= 0) throw new IllegalArgumentException();
            return new EmailVerification(userId, parts[1]);
        } catch (IllegalArgumentException invalid) {
            throw new BizException(BizCode.EMAIL_TOKEN_INVALID);
        }
    }

    /**
     * 生成随机token
     */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 构建SDU邮箱验证邮件HTML内容
     */
    private String buildVerificationEmailContent(String verifyUrl) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                    <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                        <h2 style="color: #4CAF50;">SDU邮箱身份验证</h2>
                        <p>您好！</p>
                        <p>感谢您使用 EatWhat。请点击下面的链接验证您的山东大学邮箱身份：</p>
                        <p style="margin: 30px 0;">
                            <a href="%s" style="background-color: #4CAF50; color: white; padding: 12px 24px; text-decoration: none; border-radius: 4px; display: inline-block;">
                                验证身份
                            </a>
                        </p>
                """.formatted(verifyUrl) +
                """
                        <p style="color: #666; font-size: 14px;">或复制以下链接到浏览器打开：</p>
                        <p style="color: #666; font-size: 14px; word-break: break-all;">%s</p>
                        <p style="color: #999; font-size: 12px; margin-top: 30px;">
                            此链接30分钟内有效。如果这不是您的操作，请忽略此邮件。
                        </p>
                    </div>
                </body>
                </html>
                """.formatted(verifyUrl);
    }

    /**
     * 构建邮箱绑定验证邮件HTML内容
     */
    private String buildBindingEmailContent(String verifyUrl) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                    <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                        <h2 style="color: #4CAF50;">邮箱绑定验证</h2>
                        <p>您好！</p>
                        <p>您正在绑定此邮箱到您的 EatWhat 账户。请点击下面的链接完成验证：</p>
                        <p style="margin: 30px 0;">
                            <a href="%s" style="background-color: #4CAF50; color: white; padding: 12px 24px; text-decoration: none; border-radius: 4px; display: inline-block;">
                                验证邮箱
                            </a>
                        </p>
                """.formatted(verifyUrl) +
                """
                        <p style="color: #666; font-size: 14px;">或复制以下链接到浏览器打开：</p>
                        <p style="color: #666; font-size: 14px; word-break: break-all;">%s</p>
                        <p style="color: #999; font-size: 12px; margin-top: 30px;">
                            此链接30分钟内有效。如果这不是您的操作，请忽略此邮件。
                        </p>
                    </div>
                </body>
                </html>
                """.formatted(verifyUrl);
    }

    /**
     * 验证是否为 SDU 邮箱
     */
    private boolean isSDUEmail(String email) {
        if (email == null) {
            return false;
        }
        String lowerEmail = email.toLowerCase(Locale.ROOT);
        return lowerEmail.endsWith(SDU_EMAIL_DOMAIN_1) || lowerEmail.endsWith(SDU_EMAIL_DOMAIN_2);
    }

    // Redis keys
    private String keyToken(String token) {
        return "email:v2:" + siteId + ":token:" + token;
    }

    private String keyLimit(String email) {
        return "email:v2:" + siteId + ":limit:" + email.toLowerCase(Locale.ROOT);
    }

    public record EmailVerification(Long userId, String email) {}
}

