package you.v50to.eatwhat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import you.v50to.eatwhat.config.TencentCaptchaProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TencentCaptchaServiceTest {
    private static final String PASS = "{\"Response\":{\"CaptchaCode\":1,\"EvilLevel\":0,\"RequestId\":\"synthetic-request\"}}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private TencentCaptchaProperties properties;
    private TencentCaptchaService.VerificationClient client;
    private TencentCaptchaService service;

    @BeforeEach void configure() {
        properties = new TencentCaptchaProperties();
        properties.setAppId(123456L);
        properties.setAppSecretKey("synthetic-app-secret");
        properties.setSecretId("synthetic-api-id");
        properties.setSecretKey("synthetic-api-secret");
        properties.setSessionToken("synthetic-session-token");
        client = mock(TencentCaptchaService.VerificationClient.class);
        service = new TencentCaptchaService(properties, client);
    }

    @Test void officialSdkSignsJsonPostToInternationalEndpointWithCommonParameters() throws Exception {
        var captured = new AtomicReference<Request>();
        var http = new OkHttpClient.Builder().addInterceptor(chain -> {
            captured.set(chain.request());
            return response(chain.request(), 200, PASS);
        }).build();
        var liveService = new TencentCaptchaService(properties, TencentCaptchaService.createClient(properties, http));
        liveService.verify("synthetic-ticket", "@synthetic-randstr", "203.0.113.7");
        var request = captured.get();
        assertNotNull(request);
        assertEquals("https://captcha.intl.tencentcloudapi.com/", request.url().toString());
        assertEquals("POST", request.method());
        assertEquals("DescribeCaptchaResult", request.header("X-TC-Action"));
        assertEquals("2019-07-22", request.header("X-TC-Version"));
        assertEquals("synthetic-session-token", request.header("X-TC-Token"));
        assertNotNull(request.header("X-TC-Timestamp"));
        assertTrue(request.header("Content-Type").startsWith("application/json"));
        var authorization = request.header("Authorization");
        assertTrue(authorization.startsWith("TC3-HMAC-SHA256 Credential=synthetic-api-id/"));
        assertTrue(authorization.contains("/captcha/tc3_request"));
        assertTrue(authorization.contains("SignedHeaders=content-type;host"));
        assertFalse(authorization.contains("synthetic-api-secret"));
        var buffer = new Buffer();
        request.body().writeTo(buffer);
        var payload = JSON.readTree(buffer.readUtf8());
        assertEquals(123456L, payload.path("CaptchaAppId").longValue());
        assertEquals(9, payload.path("CaptchaType").intValue());
        assertEquals(1, payload.path("NeedGetCaptchaTime").intValue());
        assertEquals("203.0.113.7", payload.path("UserIp").textValue());
        assertEquals("synthetic-ticket", payload.path("Ticket").textValue());
        assertEquals("@synthetic-randstr", payload.path("Randstr").textValue());
        assertEquals("synthetic-app-secret", payload.path("AppSecretKey").textValue());
        assertFalse(payload.has("SecretKey"));
        assertFalse(payload.has("SecretId"));
    }

    @Test void encryptedAppIdMatchesTencentPublishedCbcVector() {
        // Public documentation fixture only. Fixed IVs must never be used outside this compatibility test.
        properties.setAppId(123456789L);
        properties.setAppSecretKey("1234567891011121314151516");
        properties.setAidEncryptedTtl(Duration.ofSeconds(86400));
        var fixedIv = new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) {
                System.arraycopy("0123456789012345".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, bytes.length);
            }
        };
        var fixedService = new TencentCaptchaService(properties, client,
                Clock.fixed(Instant.ofEpochSecond(1710144972), ZoneOffset.UTC), fixedIv);
        var config = fixedService.publicConfig();
        assertEquals("MDEyMzQ1Njc4OTAxMjM0NWvZ11atw+1uzYmoIyt5rAQVPyMK9ZDavskPw5hcayeT", config.aidEncrypted());
        assertEquals("cbc", config.aidEncryptedType());
        assertEquals(1710144972L + 86400, config.expiresAt());
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings = {"short-synthetic-key", "0123456789abcdefghijklmnopqrstuv", "synthetic-密钥"})
    void encryptionUsesTheUtf8KeyRepeatedToExactly32Bytes(String secret) throws Exception {
        properties.setAppSecretKey(secret);
        properties.setAidEncryptedTtl(Duration.ofSeconds(75));
        var fixedService = new TencentCaptchaService(properties, client,
                Clock.fixed(Instant.ofEpochSecond(1800000000), ZoneOffset.UTC), new SecureRandom());
        var config = fixedService.publicConfig();
        assertEquals("123456&1800000000&75", decrypt(config.aidEncrypted(), secret));
        assertEquals(1800000075L, config.expiresAt());
        assertFalse(config.toString().contains(config.aidEncrypted()));
        verifyNoInteractions(client);
    }

    @Test void everyConfigReadHasAFreshRandomIvEvenWithinTheSameSecond() throws Exception {
        var fixedService = new TencentCaptchaService(properties, client,
                Clock.fixed(Instant.ofEpochSecond(1800000000), ZoneOffset.UTC), new SecureRandom());
        var ivs = new HashSet<String>();
        for (int i = 0; i < 64; i++) {
            var config = fixedService.publicConfig();
            byte[] bytes = Base64.getDecoder().decode(config.aidEncrypted());
            assertTrue(ivs.add(Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, 16))));
            assertEquals("123456&1800000000&120", decrypt(config.aidEncrypted(), properties.getAppSecretKey()));
        }
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings = {"PT0S", "PT0.5S", "PT1.5S", "PT86401S", "PT-1S"})
    void invalidEncryptedAppIdLifetimeFailsClosed(String value) {
        properties.setAidEncryptedTtl(Duration.parse(value));
        fails(BizCode.CAPTCHA_UNAVAILABLE, service::publicConfig);
        verifyNoInteractions(client);
    }

    @Test void missingLifetimeAndOversizedSecretNeverLeakInvalidConfiguration() {
        properties.setAidEncryptedTtl(null);
        fails(BizCode.CAPTCHA_UNAVAILABLE, service::publicConfig);
        properties.setAidEncryptedTtl(Duration.ofSeconds(120));
        properties.setAppSecretKey("synthetic-oversized-app-secret-key-value");
        fails(BizCode.CAPTCHA_UNAVAILABLE, service::publicConfig);
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\t"})
    void ticketAndRandstrAreBothRequired(String value) {
        fails(BizCode.CAPTCHA_REQUIRED, () -> service.verify(value, "randstr", "203.0.113.7"));
        fails(BizCode.CAPTCHA_REQUIRED, () -> service.verify("ticket", value, "203.0.113.7"));
        verifyNoInteractions(client);
    }

    @Test void nullAndOversizedInputsNeverCallProvider() {
        fails(BizCode.CAPTCHA_REQUIRED, () -> service.verify(null, "randstr", "203.0.113.7"));
        fails(BizCode.CAPTCHA_REQUIRED, () -> service.verify("ticket", null, "203.0.113.7"));
        fails(BizCode.CAPTCHA_INVALID, () -> service.verify("x".repeat(4097), "randstr", "203.0.113.7"));
        fails(BizCode.CAPTCHA_INVALID, () -> service.verify("ticket", "x".repeat(257), "203.0.113.7"));
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(ints = {0, 7, 8, 9, 15, 16, 21, 99})
    void failedMismatchedExpiredAndReusedTicketsCannotAuthorizeSending(int code) throws Exception {
        when(client.verify(anyString())).thenReturn("{\"Response\":{\"CaptchaCode\":" + code + "}}");
        fails(BizCode.CAPTCHA_INVALID, () -> service.verify("ticket", "randstr", "203.0.113.7"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "not-json synthetic-provider-secret", "null", "{}", "[]", "{\"Response\":{}}",
            "{\"Response\":{\"CaptchaCode\":\"1\"}}", "{\"Response\":{\"CaptchaCode\":1.0}}",
            "{\"Response\":{\"CaptchaCode\":4294967297}}",
            "{\"retcode\":0,\"retmsg\":\"success\"}",
            "{\"Response\":{\"CaptchaCode\":100}}",
            "{\"Response\":{\"CaptchaCode\":1,\"Error\":{\"Code\":\"AuthFailure\",\"Message\":\"synthetic-provider-secret\"}}}"
    })
    void malformedResponsesApiErrorsAndKeyMismatchFailClosed(String response) throws Exception {
        when(client.verify(anyString())).thenReturn(response);
        fails(BizCode.CAPTCHA_UNAVAILABLE, () -> service.verify("ticket", "randstr", "203.0.113.7"));
    }

    @ParameterizedTest @ValueSource(strings = {"100", "1", "\"0\"", "4294967296"})
    void maliciousAndMalformedRiskResultsAreRejected(String risk) throws Exception {
        when(client.verify(anyString())).thenReturn("{\"Response\":{\"CaptchaCode\":1,\"EvilLevel\":" + risk + "}}");
        fails(BizCode.CAPTCHA_INVALID, () -> service.verify("ticket", "randstr", "203.0.113.7"));
    }

    @Test void providerTimeoutAndCredentialErrorsNeverLeakProviderDetails() throws Exception {
        when(client.verify(anyString())).thenThrow(new TencentCloudSDKException("synthetic-private-provider-message"));
        fails(BizCode.CAPTCHA_UNAVAILABLE, () -> service.verify("ticket", "randstr", "203.0.113.7"));
    }

    @Test void sdkHttpFailureIsRejected() {
        var http = new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(), 503, "synthetic-private-message")).build();
        var liveService = new TencentCaptchaService(properties, TencentCaptchaService.createClient(properties, http));
        fails(BizCode.CAPTCHA_UNAVAILABLE, () -> liveService.verify("ticket", "randstr", "203.0.113.7"));
    }

    @Test void disasterRecoveryTicketDoesNotBypassHumanVerification() {
        fails(BizCode.CAPTCHA_UNAVAILABLE, () -> service.verify("trerror_1001_synthetic", "randstr", "203.0.113.7"));
        verifyNoInteractions(client);
    }

    @Test void acceptedTicketsAreReverifiedInsteadOfReusingCachedSuccess() throws Exception {
        when(client.verify(anyString())).thenReturn(PASS, "{\"Response\":{\"CaptchaCode\":9}}");
        service.verify("ticket", "randstr", "203.0.113.7");
        fails(BizCode.CAPTCHA_INVALID, () -> service.verify("ticket", "randstr", "203.0.113.7"));
        verify(client, times(2)).verify(anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"appId", "appSecretKey", "secretId", "secretKey"})
    void enabledByDefaultAndMissingCredentialsBlockSending(String field) {
        assertTrue(new TencentCaptchaProperties().isEnabled());
        org.springframework.test.util.ReflectionTestUtils.setField(properties, field, null);
        fails(BizCode.CAPTCHA_UNAVAILABLE, () -> service.verify("ticket", "randstr", "203.0.113.7"));
        fails(BizCode.CAPTCHA_UNAVAILABLE, service::publicConfig);
        verifyNoInteractions(client);
    }

    @Test void unresolvedCredentialsBlockSending() {
        properties.setSecretKey("${UNRESOLVED_SECRET}");
        fails(BizCode.CAPTCHA_UNAVAILABLE, () -> service.verify("ticket", "randstr", "203.0.113.7"));
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "example.test", "203.0.113.7, 127.0.0.1", "999.0.0.1", "203.0.113", ":::"})
    void ipMustBeAServerObtainedLiteralAddress(String ip) {
        fails(BizCode.CAPTCHA_UNAVAILABLE, () -> service.verify("ticket", "randstr", ip));
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings = {"203.0.113.7", "2001:db8::7"})
    void validIpv4AndIpv6AreSentToProvider(String ip) throws Exception {
        when(client.verify(anyString())).thenReturn(PASS);
        service.verify("ticket", "randstr", ip);
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).verify(payload.capture());
        assertEquals(ip, JSON.readTree(payload.getValue()).path("UserIp").textValue());
    }

    @Test void explicitDisableAllowsIsolatedTestsWithoutCredentials() {
        properties.setEnabled(false);
        properties.setAppId(null);
        service.verify(null, null, null);
        assertEquals(new TencentCaptchaService.PublicConfig(false, "tencent-cloud-intl", null, null, null, null, null), service.publicConfig());
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings = {"PT0S", "PT0.5S", "PT11S"})
    void unreasonableTimeoutIsRejected(String timeout) {
        properties.setTimeout(Duration.parse(timeout));
        assertThrows(IllegalArgumentException.class, () -> new TencentCaptchaService(properties));
    }

    private Response response(Request request, int code, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                .body(ResponseBody.create(MediaType.get("application/json"), body)).build();
    }

    private String decrypt(String encrypted, String secret) throws Exception {
        byte[] original = secret.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = original[i % original.length];
        byte[] bytes = Base64.getDecoder().decode(encrypted);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(bytes, 0, 16));
        return new String(cipher.doFinal(bytes, 16, bytes.length - 16), StandardCharsets.UTF_8);
    }

    private void fails(BizCode code, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(BizException.class, action);
        assertEquals(code, failure.getBizCode());
        assertEquals(code.getMsg(), failure.getMessage());
        assertNull(failure.getCause());
    }
}
