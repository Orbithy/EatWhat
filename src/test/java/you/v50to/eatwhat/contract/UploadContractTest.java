package you.v50to.eatwhat.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.controller.UploadController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.service.storage.S3CompatibleStorageService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Signs with synthetic credentials only: no application.yml, S3 requests, uploaded bytes or real secrets. */
class UploadContractTest extends MvcContractSupport {

    private static final long USER_ID = 7L;
    private MockMvc http;
    private S3CompatibleStorageService storage;
    private FileLifecycleService files;
    private FileLifecycleProperties lifecycle;

    @BeforeEach
    void createApi() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("https://s3.example.invalid");
        properties.setRegion("us-east-1");
        properties.setBucket("contract-bucket");
        properties.setAccessKey("contract-access-key");
        properties.setSecretKey("contract-secret-key");
        properties.setPathStyle(true);
        properties.setSignPutTtlSeconds(300);
        properties.setMaxFileSizeBytes(1024);
        properties.setAllowedContentTypes(List.of("image/png", "image/jpeg"));
        storage = spy(new S3CompatibleStorageService(properties));
        files = mock(FileLifecycleService.class);
        lifecycle = new FileLifecycleProperties();

        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "objectStorageService", storage);
        ReflectionTestUtils.setField(controller, "fileLifecycleService", files);
        ReflectionTestUtils.setField(controller, "fileLifecycleProperties", lifecycle);
        http = mvc(controller);
    }

    @Test
    void enabledLifecyclePresignKeepsTheExistingThreeResponseFields() throws Exception {
        lifecycle.setLifecycleEnabled(true);
        String key = "foods/7/202610/0123456789abcdef0123456789abcdef.png";
        when(files.presign(USER_ID, UploadBiz.FOODS, "image.png", "image/png", 12L))
                .thenReturn(new ObjectStorageService.PresignedUpload("https://objects.example.invalid/put", key, 1_792_000_000_000L));
        String token = loginAs(USER_ID, "verified");
        http.perform(post("/upload/presign").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"biz\":\"foods\",\"fileName\":\"image.png\",\"contentType\":\"image/png\",\"fileSize\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.putUrl").value("https://objects.example.invalid/put"))
                .andExpect(jsonPath("$.data.key").value(key))
                .andExpect(jsonPath("$.data.expireAt").value(1_792_000_000_000L))
                .andExpect(jsonPath("$.data", aMapWithSize(3)));
        verify(files).presign(USER_ID, UploadBiz.FOODS, "image.png", "image/png", 12L);
        verifyNoInteractions(storage);
    }

    @Test
    void completeRequiresLoginAndRechecksVerifiedBusinessPermission() throws Exception {
        lifecycle.setLifecycleEnabled(true);
        http.perform(post("/upload/complete").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"foods/7/k\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()));
        verifyNoInteractions(files);

        String ordinary = loginAs(USER_ID);
        when(files.getBiz("foods/7/k")).thenReturn("foods");
        http.perform(post("/upload/complete").header(TOKEN_HEADER, "Bearer " + ordinary)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"foods/7/k\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        verify(files).getBiz("foods/7/k");
        verifyNoMoreInteractions(files);
    }

    @Test
    void completeForwardsWrongOwnerDenialFromLifecycleService() throws Exception {
        lifecycle.setLifecycleEnabled(true);
        String key = "foods/8/202610/0123456789abcdef0123456789abcdef.png";
        String token = loginAs(USER_ID, "verified");
        when(files.getBiz(key)).thenReturn("foods");
        when(files.complete(USER_ID, key)).thenThrow(new BizException(BizCode.NO_PERMISSION, "无权完成此上传"));
        http.perform(post("/upload/complete").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + key + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()));
        verify(files).getBiz(key);
        verify(files).complete(USER_ID, key);
        verifyNoInteractions(storage);
    }

    @Test
    void legacyPresignPayloadDefaultsToActivityAndReturnsExistingThreeFields() throws Exception {
        String token = loginAs(USER_ID, "verified");
        long earliestExpiry = System.currentTimeMillis() + 300_000;

        http.perform(post("/upload/presign").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(fileJson("meal.png", 12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.data.putUrl", startsWith("https://s3.example.invalid/contract-bucket/activity/7/")))
                .andExpect(jsonPath("$.data.putUrl", containsString("X-Amz-Signature=")))
                .andExpect(jsonPath("$.data.key", matchesPattern("activity/7/[0-9]{6}/[a-f0-9]{32}\\.png")))
                .andExpect(jsonPath("$.data.expireAt", greaterThanOrEqualTo(earliestExpiry)))
                .andExpect(jsonPath("$.data.expireAt", lessThanOrEqualTo(System.currentTimeMillis() + 301_000)));

        verify(storage).presignPut(USER_ID, UploadBiz.ACTIVITY, "meal.png", "image/png", 12L);
        // Generating a signature only exercises the existing contract; it proves no upload or replication.
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatar", "activity", "foods", "restaurants"})
    void verifiedPrincipalCanPresignEachExistingBusinessPrefix(String biz) throws Exception {
        String token = loginAs(USER_ID, "verified");
        String body = "{\"biz\":\"" + biz + "\",\"fileName\":\"image.png\",\"contentType\":\"image/png\",\"fileSize\":12}";
        http.perform(post("/upload/presign").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.key", startsWith(biz + "/7/")));
        verify(storage).presignPut(USER_ID, UploadBiz.fromValue(biz), "image.png", "image/png", 12L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/upload/presign", "/upload/presign/batch"})
    void anonymousUploadIsRejectedBeforeStorage(String path) throws Exception {
        http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(uploadJson(path, "avatar")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.NOT_LOGIN.getCode()))
                .andExpect(jsonPath("$.msg").value("用户未登录"))
                .andExpect(jsonPath("$.data", is(nullValue())));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/upload/presign", "/upload/presign/batch"})
    void loggedInUnverifiedPrincipalCanPresignAvatar(String path) throws Exception {
        String token = loginAs(USER_ID);
        String itemPath = path.endsWith("/batch") ? "$.data.files[0]" : "$.data";
        http.perform(post(path).header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(uploadJson(path, "avatar")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath(itemPath + ".key", matchesPattern("avatar/7/[0-9]{6}/[a-f0-9]{32}\\.png")))
                .andExpect(jsonPath(itemPath + ".putUrl", startsWith("https://s3.example.invalid/contract-bucket/avatar/7/")))
                .andExpect(jsonPath(itemPath + ".expireAt").isNumber());
        verify(storage).presignPut(USER_ID, UploadBiz.AVATAR, "image.png", "image/png", 12L);
    }

    @ParameterizedTest(name = "{0}, biz={1}, role={2}")
    @MethodSource("restrictedUploadsWithoutVerification")
    void nonAvatarUploadsKeepVerifiedRoleErrorForOrdinaryAndAdminUsers(String path, String biz, String role)
            throws Exception {
        String token = "admin".equals(role) ? loginAs(USER_ID, "admin") : loginAs(USER_ID);
        http.perform(post(path).header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(uploadJson(path, biz)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.NO_PERMISSION.getCode()))
                .andExpect(jsonPath("$.msg").value("权限不足：用户未验证"))
                .andExpect(jsonPath("$.data", is(nullValue())));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @MethodSource("unverifiedInvalidUploads")
    void unverifiedAvatarAccessDoesNotBypassBusinessTypeOrFileValidation(String path, String body) throws Exception {
        String token = loginAs(USER_ID);
        http.perform(post(path).header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.data", is(nullValue())));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fileName\":\"\",\"contentType\":\"image/png\",\"fileSize\":12}",
            "{\"fileName\":\"meal.png\",\"contentType\":\"image/png\",\"fileSize\":0}",
            "{\"fileName\":\"meal.png\",\"contentType\":\"image/png\"}",
            "{\"biz\":null,\"fileName\":\"meal.png\",\"contentType\":\"image/png\",\"fileSize\":12}",
            "{\"biz\":\"unknown\",\"fileName\":\"meal.png\",\"contentType\":\"image/png\",\"fileSize\":12}"
    })
    void invalidPresignInputReturnsParameterErrorBeforeStorage(String body) throws Exception {
        String token = loginAs(USER_ID, "verified");
        http.perform(post("/upload/presign").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.data", is(nullValue())))
                .andExpect(jsonPath("$.msg").isString())
                .andExpect(jsonPath("$.timestamp").isNumber());
        verifyNoInteractions(storage);
    }

    @Test
    void configuredSizeLimitPropagatesExistingBusinessErrorEnvelope() throws Exception {
        String token = loginAs(USER_ID, "verified");
        http.perform(post("/upload/presign").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(fileJson("meal.png", 2048)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.msg").value("参数非法：文件大小超出限制"))
                .andExpect(jsonPath("$.data", is(nullValue())));
    }

    @Test
    void configuredContentTypeRestrictionPropagatesWithoutSigning() throws Exception {
        String token = loginAs(USER_ID, "verified");
        http.perform(post("/upload/presign").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"file.pdf\",\"contentType\":\"application/pdf\",\"fileSize\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.msg").value("参数非法：不支持的文件类型"));
    }

    @Test
    void batchPresignKeepsFilesArrayAndInputOrder() throws Exception {
        String token = loginAs(USER_ID, "verified");
        String body = "{\"biz\":\"foods\",\"files\":[" + fileJson("first.png", 12) + ","
                + fileJson("second.jpg", 24) + "]}";
        http.perform(post("/upload/presign/batch").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.files.length()").value(2))
                .andExpect(jsonPath("$.data.files[0].key", matchesPattern("foods/7/[0-9]{6}/[a-f0-9]{32}\\.png")))
                .andExpect(jsonPath("$.data.files[1].key", matchesPattern("foods/7/[0-9]{6}/[a-f0-9]{32}\\.jpg")))
                .andExpect(jsonPath("$.data.files[0].putUrl").isString())
                .andExpect(jsonPath("$.data.files[1].expireAt").isNumber());
        verify(storage).presignPut(USER_ID, UploadBiz.FOODS, "first.png", "image/png", 12L);
        verify(storage).presignPut(USER_ID, UploadBiz.FOODS, "second.jpg", "image/png", 24L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10})
    void batchCardinalityMustRemainBetweenOneAndNine(int count) throws Exception {
        String token = loginAs(USER_ID, "verified");
        String files = IntStream.range(0, count).mapToObj(i -> fileJson("image" + i + ".png", 12))
                .collect(Collectors.joining(","));
        http.perform(post("/upload/presign/batch").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"files\":[" + files + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verifyNoInteractions(storage);
    }

    @Test
    void nestedBatchFileValidationRunsBeforeAnySignatureIsIssued() throws Exception {
        String token = loginAs(USER_ID, "verified");
        String body = "{\"files\":[" + fileJson("first.png", 12) + "," + fileJson("", 12) + "]}";
        http.perform(post("/upload/presign/batch").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_INVALID.getCode()));
        verifyNoInteractions(storage);
    }

    private String fileJson(String name, long size) {
        return "{\"fileName\":\"" + name + "\",\"contentType\":\"image/png\",\"fileSize\":" + size + "}";
    }

    private String uploadJson(String path, String biz) {
        String businessField = "default".equals(biz) ? "" : "\"biz\":\"" + biz + "\",";
        String file = fileJson("image.png", 12);
        return path.endsWith("/batch")
                ? "{" + businessField + "\"files\":[" + file + "]}"
                : "{" + businessField + file.substring(1);
    }

    private static Stream<Arguments> restrictedUploadsWithoutVerification() {
        return Stream.of("/upload/presign", "/upload/presign/batch")
                .flatMap(path -> Stream.of("activity", "foods", "restaurants", "default")
                        .flatMap(biz -> Stream.of("ordinary", "admin")
                                .map(role -> Arguments.of(path, biz, role))));
    }

    private static Stream<Arguments> unverifiedInvalidUploads() {
        return Stream.of("/upload/presign", "/upload/presign/batch")
                .flatMap(path -> Stream.of("null", "\"unknown\"", "\"avatar\"")
                        .map(biz -> {
                            String fileName = "\"avatar\"".equals(biz) ? "" : "image.png";
                            String file = "{\"fileName\":\"" + fileName
                                    + "\",\"contentType\":\"image/png\",\"fileSize\":12}";
                            String body = path.endsWith("/batch")
                                    ? "{\"biz\":" + biz + ",\"files\":[" + file + "]}"
                                    : "{\"biz\":" + biz + "," + file.substring(1);
                            return Arguments.of(path, body);
                        }));
    }
}
