package you.v50to.eatwhat.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.config.S3StorageProperties.Provider;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.exception.BizException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real AWS SDK signatures with synthetic credentials; no S3 client, endpoint probe or upload. */
class S3CompatibleStorageServiceTest {
    private static final String ACCESS_KEY = "synthetic-access-key";
    private static final String SECRET_KEY = "synthetic-secret-key";
    private static final String BUCKET = "contract-bucket";

    @Test
    void legacyPropertyNamesBindWithoutNewSettingsAndRetainDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "app.storage.s3.endpoint", "https://legacy.example.invalid",
                "app.storage.s3.region", "us-east-1",
                "app.storage.s3.bucket", BUCKET,
                "app.storage.s3.access-key", ACCESS_KEY,
                "app.storage.s3.secret-key", SECRET_KEY,
                "app.storage.s3.cdn-endpoint", "https://legacy-cdn.example.invalid"));
        S3StorageProperties properties = new Binder(source)
                .bind("app.storage.s3", Bindable.of(S3StorageProperties.class)).get();

        assertEquals(Provider.GENERIC, properties.getProvider());
        assertTrue(properties.isPathStyle());
        assertEquals(600, properties.getSignGetTtlSeconds());
        assertEquals(300, properties.getSignPutTtlSeconds());
        assertEquals(50L * 1024 * 1024, properties.getMaxFileSizeBytes());
        assertTrue(properties.getAllowedContentTypes().isEmpty());
        assertTrue(properties.getAllowedReadPrefixes().isEmpty());
        assertTrue(properties.getPublicReadPrefixes().isEmpty());
        assertNull(properties.getPublicBaseUrl());
        try (var service = new S3CompatibleStorageService(properties)) {
            URI download = URI.create(service.signGetUrl("foods/7/existing.png"));
            assertEquals("legacy-cdn.example.invalid", download.getHost());
            assertSignature(download, "us-east-1", 600);
        }
    }

    @Test
    void genericProviderRetainsSignedCdnAndPublicBucketPathEvenWhenNewPublicBaseIsPresent() {
        S3StorageProperties properties = properties(Provider.GENERIC);
        properties.setCdnEndpoint("https://legacy-cdn.example.invalid/");
        properties.setPublicBaseUrl("https://new-public.example.invalid/ignored");
        properties.setPublicReadPrefixes(List.of("avatar/"));
        try (var service = new S3CompatibleStorageService(properties)) {
            URI download = URI.create(service.signGetUrl("foods/7/existing.png"));
            assertEquals("legacy-cdn.example.invalid", download.getHost());
            assertEquals("/" + BUCKET + "/foods/7/existing.png", download.getPath());
            assertSignature(download, "us-east-1", 600);
            assertEquals("https://legacy-cdn.example.invalid/" + BUCKET + "/avatar/7/existing.png",
                    service.signGetUrl("avatar/7/existing.png"));
            assertEquals("generic.example.invalid", URI.create(service.presignPut(
                    7L, UploadBiz.AVATAR, "avatar.png", "image/png", 12L).putUrl()).getHost());
        }
    }

    @Test
    void genericPublicReadWithoutCdnStillUsesEndpointAndBucket() {
        S3StorageProperties properties = properties(Provider.GENERIC);
        properties.setPublicReadPrefixes(List.of("avatar/"));
        try (var service = new S3CompatibleStorageService(properties)) {
            assertEquals("https://generic.example.invalid/" + BUCKET + "/avatar/7/existing.png",
                    service.signGetUrl("avatar/7/existing.png"));
        }
    }

    @Test
    void legacyVirtualHostConfigurationRemainsSupported() {
        S3StorageProperties properties = properties(Provider.GENERIC);
        properties.setPathStyle(false);
        try (var service = new S3CompatibleStorageService(properties)) {
            URI download = URI.create(service.signGetUrl("foods/7/existing.png"));
            assertEquals(BUCKET + ".generic.example.invalid", download.getHost());
            assertEquals("/foods/7/existing.png", download.getPath());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = {"RUSTFS", "R2"})
    void privateGetKeepsSignedOriginDespiteConfiguredCdnAndPublicBase(Provider provider) {
        S3StorageProperties properties = properties(provider);
        properties.setCdnEndpoint("https://legacy-cdn.example.invalid");
        properties.setPublicBaseUrl("https://public.example.invalid");
        properties.setPublicReadPrefixes(List.of("avatar/"));
        try (var service = new S3CompatibleStorageService(properties)) {
            URI download = URI.create(service.signGetUrl("foods/7/a b.png"));
            assertEquals(URI.create(properties.getEndpoint()).getHost(), download.getHost());
            assertEquals("/" + BUCKET + "/foods/7/a b.png", download.getPath());
            assertTrue(download.getRawPath().contains("a%20b.png"));
            assertSignature(download, properties.getRegion(), properties.getSignGetTtlSeconds());
            assertTrue(List.of(query(download).get("X-Amz-SignedHeaders").split(";")).contains("host"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = {"RUSTFS", "R2"})
    void putKeepsOriginKeyContractContentTypeSignatureAndConfiguredExpiry(Provider provider) {
        S3StorageProperties properties = properties(provider);
        properties.setCdnEndpoint("https://legacy-cdn.example.invalid");
        properties.setPublicBaseUrl("https://public.example.invalid");
        properties.setSignPutTtlSeconds(123);
        try (var service = new S3CompatibleStorageService(properties)) {
            long before = System.currentTimeMillis();
            ObjectStorageService.PresignedUpload upload = service.presignPut(
                    7L, UploadBiz.FOODS, "meal.PNG", "image/png", 12L);
            long after = System.currentTimeMillis();
            URI put = URI.create(upload.putUrl());
            assertEquals(URI.create(properties.getEndpoint()).getHost(), put.getHost());
            assertTrue(upload.key().matches("foods/7/[0-9]{6}/[a-f0-9]{32}\\.png"));
            assertEquals("/" + BUCKET + "/" + upload.key(), put.getPath());
            assertSignature(put, properties.getRegion(), 123);
            List<String> signedHeaders = List.of(query(put).get("X-Amz-SignedHeaders").split(";"));
            assertTrue(signedHeaders.containsAll(List.of("host", "content-type")));
            assertTrue(upload.expireAt() >= before + 123_000);
            assertTrue(upload.expireAt() <= after + 123_000);
        }
    }

    @ParameterizedTest
    @MethodSource("publicRoots")
    void publicBaseIsTheCompleteObjectRootWithoutInferredBucket(Provider provider, String root) {
        S3StorageProperties properties = properties(provider);
        properties.setPublicReadPrefixes(List.of("avatar/"));
        properties.setPublicBaseUrl(root + "/");
        properties.setCdnEndpoint("https://ignored-cdn.example.invalid");
        try (var service = new S3CompatibleStorageService(properties)) {
            String url = service.signGetUrl("avatar/7/existing.png");
            assertEquals(root + "/avatar/7/existing.png", url);
            assertNull(URI.create(url).getRawQuery(), "Public URLs must not contain an S3 signature");
        }
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = {"RUSTFS", "R2"})
    void publicObjectKeysCannotBecomeUrlQueryOrFragment(Provider provider) {
        S3StorageProperties properties = properties(provider);
        properties.setPublicReadPrefixes(List.of("avatar/"));
        properties.setPublicBaseUrl("https://public.example.invalid/root/");
        try (var service = new S3CompatibleStorageService(properties)) {
            URI url = URI.create(service.signGetUrl("avatar/7/a b?#%.png"));
            assertEquals("/root/avatar/7/a b?#%.png", url.getPath());
            assertNull(url.getRawQuery());
            assertNull(url.getRawFragment());
            assertTrue(url.getRawPath().contains("a%20b%3F%23%25.png"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = {"RUSTFS", "R2"})
    void publicKeysWithDotSegmentsAreFilteredWithoutNormalizingToAnotherObject(Provider provider) {
        S3StorageProperties properties = properties(provider);
        properties.setPublicReadPrefixes(List.of("avatar/"));
        properties.setPublicBaseUrl("https://public.example.invalid/root/");
        try (var service = new S3CompatibleStorageService(properties)) {
            for (String key : publicKeysWithDotSegments()) {
                assertNull(service.signGetUrl(key), key);
            }
            assertEquals(List.of(
                            "https://public.example.invalid/root/avatar/7/first.png",
                            "https://public.example.invalid/root/avatar/7/last.png"),
                    service.signGetUrls(List.of("avatar/7/first.png", "avatar/7/../../other.png",
                            "avatar/./hidden.png", "avatar/7/last.png")));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = {"RUSTFS", "R2"})
    void publicKeysRetainDotsWithinNamesAndLiteralPercentEncodedSegments(Provider provider) {
        S3StorageProperties properties = properties(provider);
        properties.setPublicReadPrefixes(List.of("avatar/"));
        properties.setPublicBaseUrl("https://public.example.invalid/root/");
        try (var service = new S3CompatibleStorageService(properties)) {
            String key = "avatar/.hidden/.../a..b/%2e/%2E%2e/photo.png";
            URI url = URI.create(service.signGetUrl(key));
            assertEquals("/root/" + key, url.getPath());
            assertEquals("/root/avatar/.hidden/.../a..b/%252e/%252E%252e/photo.png", url.getRawPath());
            assertEquals(url, url.normalize(), "Object key segments must not change after URI normalization");
        }
    }

    @Test
    void genericPublicKeysRetainLegacyDotSegmentBehavior() {
        S3StorageProperties properties = properties(Provider.GENERIC);
        properties.setPublicReadPrefixes(List.of("avatar/"));
        try (var service = new S3CompatibleStorageService(properties)) {
            for (String key : publicKeysWithDotSegments()) {
                assertEquals("https://generic.example.invalid/" + BUCKET + "/" + key,
                        service.signGetUrl(key));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void readPrefixFilteringAndLegacyAbsoluteUrlsKeepExistingBehavior(Provider provider) {
        S3StorageProperties properties = properties(provider);
        properties.setAllowedReadPrefixes(List.of("foods/"));
        try (var service = new S3CompatibleStorageService(properties)) {
            String legacyUrl = "https://legacy-assets.example.invalid/old-avatar.png";
            assertEquals(legacyUrl, service.signGetUrl(legacyUrl));
            assertNull(service.signGetUrl("outside/secret.png"));
            assertNull(service.signGetUrl(null));
            assertEquals(" ", service.signGetUrl(" "));
            assertEquals(List.of(), service.signGetUrls(null));
            List<String> urls = service.signGetUrls(Arrays.asList(null, "", " ", "outside/secret.png",
                    legacyUrl, "foods/7/meal.png"));
            assertEquals(2, urls.size());
            assertEquals(legacyUrl, urls.getFirst());
            assertSignature(URI.create(urls.get(1)), properties.getRegion(), 600);
        }
    }

    @Test
    void configurationStringDoesNotExposeCredentials() {
        String description = properties(Provider.R2).toString();
        assertFalse(description.contains(ACCESS_KEY));
        assertFalse(description.contains(SECRET_KEY));
    }

    @Test
    void genericIncompleteConfigurationRetainsLazyBusinessError() {
        try (var service = new S3CompatibleStorageService(new S3StorageProperties())) {
            BizException failure = assertThrows(BizException.class,
                    () -> service.presignPut(7L, UploadBiz.AVATAR, "avatar.png", "image/png", 12L));
            assertEquals(BizCode.PARAM_INVALID, failure.getBizCode());
            assertEquals("参数非法：对象存储配置不完整", failure.getMessage());
        }
    }

    private static Stream<Arguments> publicRoots() {
        return Stream.of(Provider.RUSTFS, Provider.R2).flatMap(provider -> Stream.of(
                        "https://public.example.invalid", "https://hk.example.invalid/" + BUCKET)
                .map(root -> Arguments.of(provider, root)));
    }

    private static List<String> publicKeysWithDotSegments() {
        return List.of("avatar/./other.png", "avatar/7/../other.png", "avatar/7/../../other.png",
                "avatar/.", "avatar/..", "avatar//../other.png");
    }

    private S3StorageProperties properties(Provider provider) {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setProvider(provider);
        properties.setEndpoint(switch (provider) {
            case GENERIC -> "https://generic.example.invalid";
            case RUSTFS -> "http://rustfs.example.invalid:9000";
            case R2 -> "https://00000000000000000000000000000000.r2.cloudflarestorage.com";
        });
        properties.setRegion(provider == Provider.R2 ? "auto" : "us-east-1");
        properties.setBucket(BUCKET);
        properties.setAccessKey(ACCESS_KEY);
        properties.setSecretKey(SECRET_KEY);
        return properties;
    }

    private void assertSignature(URI url, String region, long ttlSeconds) {
        Map<String, String> query = query(url);
        assertEquals("AWS4-HMAC-SHA256", query.get("X-Amz-Algorithm"));
        assertEquals(Long.toString(ttlSeconds), query.get("X-Amz-Expires"));
        assertTrue(query.get("X-Amz-Credential").startsWith(ACCESS_KEY + "/"));
        assertTrue(query.get("X-Amz-Credential").endsWith("/" + region + "/s3/aws4_request"));
        assertTrue(query.get("X-Amz-Signature").matches("[a-f0-9]{64}"));
        assertFalse(url.toString().contains(SECRET_KEY));
    }

    private Map<String, String> query(URI uri) {
        assertNotNull(uri.getRawQuery());
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(part -> decode(part[0]), part -> decode(part[1])));
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
