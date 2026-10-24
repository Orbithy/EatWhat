package you.v50to.eatwhat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.common.AbstractClient;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.config.TencentCaptchaProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** International Tencent CAPTCHA ticket verification; only CaptchaCode=1 permits sending. */
@Service
public class TencentCaptchaService {
    public static final String TICKET_HEADER = "X-Captcha-Ticket";
    public static final String RANDSTR_HEADER = "X-Captcha-Randstr";
    public static final String SCRIPT_URL = "https://ca.turing.captcha.qcloud.com/TJNCaptcha-global.js";
    static final String HOST = "captcha.intl.tencentcloudapi.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TencentCaptchaProperties properties;
    private final VerificationClient client;
    private final Clock clock;
    private final SecureRandom random;

    @FunctionalInterface
    interface VerificationClient {
        String verify(String payload) throws TencentCloudSDKException;
    }

    @Autowired
    public TencentCaptchaService(TencentCaptchaProperties properties) {
        this(properties, createClient(properties, httpClient(properties)));
    }

    TencentCaptchaService(TencentCaptchaProperties properties, VerificationClient client) {
        this(properties, client, Clock.systemUTC(), new SecureRandom());
    }

    TencentCaptchaService(TencentCaptchaProperties properties, VerificationClient client, Clock clock, SecureRandom random) {
        this.properties = properties;
        this.client = client;
        this.clock = clock;
        this.random = random;
    }

    public record PublicConfig(boolean enabled, String provider, String appId, String scriptUrl,
                               String aidEncrypted, String aidEncryptedType, Long expiresAt) {
        @Override public String toString() {
            return "TencentCaptchaPublicConfig[enabled=" + enabled + ", encryptedParameters=redacted]";
        }
    }

    public PublicConfig publicConfig() {
        if (!properties.isEnabled()) return new PublicConfig(false, "tencent-cloud-intl", null, null, null, null, null);
        requireConfiguration();
        Duration ttl = properties.getAidEncryptedTtl();
        if (ttl == null || ttl.getNano() != 0 || ttl.getSeconds() < 1 || ttl.getSeconds() > 86400) throw unavailable();
        long now = clock.instant().getEpochSecond();
        String aidEncrypted = encryptAppId(now, ttl.getSeconds());
        return new PublicConfig(true, "tencent-cloud-intl", properties.getAppId().toString(), SCRIPT_URL,
                aidEncrypted, "cbc", now + ttl.getSeconds());
    }

    /** Tencent protocol: Base64(random 16-byte IV || AES-256-CBC(appId&unixSeconds&ttlSeconds)). */
    private String encryptAppId(long issuedAt, long ttlSeconds) {
        byte[] secret = properties.getAppSecretKey().getBytes(StandardCharsets.UTF_8);
        // Tencent keys shorter than 32 bytes repeat to 32; longer keys are a configuration error.
        if (secret.length > 32) {
            Arrays.fill(secret, (byte) 0);
            throw unavailable();
        }
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = secret[i % secret.length];
        byte[] iv = new byte[16];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal((properties.getAppId() + "&" + issuedAt + "&" + ttlSeconds)
                    .getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(iv, iv.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException failure) {
            throw unavailable();
        } finally {
            Arrays.fill(secret, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    public void verify(String ticket, String randstr, String clientIp) {
        if (!properties.isEnabled()) return;
        if (ticket == null || ticket.isBlank() || randstr == null || randstr.isBlank()) {
            throw new BizException(BizCode.CAPTCHA_REQUIRED);
        }
        if (ticket.length() > 4096 || randstr.length() > 256) throw new BizException(BizCode.CAPTCHA_INVALID);
        // Frontend disaster-recovery tickets do not prove a successful human challenge.
        if (ticket.startsWith("trerror_")) throw unavailable();
        requireConfiguration();
        if (!literalIp(clientIp)) throw unavailable();
        var payload = JSON.createObjectNode();
        payload.put("CaptchaAppId", properties.getAppId());
        payload.put("CaptchaType", 9);
        payload.put("NeedGetCaptchaTime", 1);
        payload.put("Randstr", randstr);
        payload.put("UserIp", clientIp);
        payload.put("Ticket", ticket);
        payload.put("AppSecretKey", properties.getAppSecretKey());
        try {
            var body = JSON.readTree(client.verify(payload.toString()));
            if (body == null || !body.isObject()) throw unavailable();
            var result = body.path("Response");
            if (!result.isObject() || result.has("Error")
                    || !result.path("CaptchaCode").isIntegralNumber()
                    || !result.path("CaptchaCode").canConvertToInt()) throw unavailable();
            int code = result.path("CaptchaCode").intValue();
            if (code == 100) throw unavailable(); // Application key / app ID configuration mismatch.
            if (code != 1) throw new BizException(BizCode.CAPTCHA_INVALID);
            var evil = result.get("EvilLevel");
            if (evil != null && !evil.isNull()
                    && (!evil.isIntegralNumber() || !evil.canConvertToInt() || evil.intValue() != 0)) {
                throw new BizException(BizCode.CAPTCHA_INVALID);
            }
        } catch (TencentCloudSDKException | IOException | IllegalArgumentException failure) {
            // Never propagate provider messages, request bodies, credentials, tickets or exception causes.
            throw unavailable();
        }
    }

    private void requireConfiguration() {
        if (properties.getAppId() == null || properties.getAppId() <= 0
                || !resolved(properties.getAppSecretKey()) || !resolved(properties.getSecretId())
                || !resolved(properties.getSecretKey())
                || properties.getSessionToken() != null && properties.getSessionToken().contains("${")) {
            throw unavailable();
        }
    }

    private static boolean resolved(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
    }

    private static boolean literalIp(String ip) {
        if (ip == null || ip.length() > 45 || !ip.matches("[0-9a-fA-F:.]+")) return false;
        if (!ip.contains(":")) {
            String[] parts = ip.split("\\.", -1);
            if (parts.length != 4) return false;
            for (String part : parts) {
                if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
            }
            return true;
        }
        try { InetAddress.getByName(ip); return true; }
        catch (java.net.UnknownHostException invalid) { return false; }
    }

    private static Duration validTimeout(TencentCaptchaProperties properties) {
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("app.captcha.tencent.timeout must be between 1s and 10s");
        }
        return timeout;
    }

    private static OkHttpClient httpClient(TencentCaptchaProperties properties) {
        Duration timeout = validTimeout(properties);
        return new OkHttpClient.Builder().connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout)
                .callTimeout(timeout).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build();
    }

    /** The official common SDK supplies TC3 signing, timestamp, version and optional session token. */
    static VerificationClient createClient(TencentCaptchaProperties properties, OkHttpClient httpClient) {
        var http = new HttpProfile();
        http.setEndpoint(HOST);
        http.setProtocol(HttpProfile.REQ_HTTPS);
        http.setHttpClient(httpClient);
        var profile = new ClientProfile(ClientProfile.SIGN_TC3_256, http);
        profile.setDebug(false);
        var credential = new Credential(properties.getSecretId(), properties.getSecretKey(), properties.getSessionToken());
        var sdk = new AbstractClient(HOST, "2019-07-22", credential, "", profile) { };
        return payload -> sdk.call("DescribeCaptchaResult", payload);
    }

    private static BizException unavailable() {
        return new BizException(BizCode.CAPTCHA_UNAVAILABLE);
    }
}
