package you.v50to.eatwhat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Never serialize this bean: app and cloud API credentials are server-only. */
@Getter
@Setter
@Component
@ConfigurationProperties("app.captcha.tencent")
public class TencentCaptchaProperties {
    private boolean enabled = true;
    private Long appId;
    private String appSecretKey = "";
    private String secretId = "";
    private String secretKey = "";
    private String sessionToken = "";
    private Duration timeout = Duration.ofSeconds(5);
    /** Lifetime of each fresh encrypted app ID, not the lifetime of a solved CAPTCHA ticket. */
    private Duration aidEncryptedTtl = Duration.ofSeconds(120);
}
