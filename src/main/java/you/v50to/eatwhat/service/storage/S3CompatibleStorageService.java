package you.v50to.eatwhat.service.storage;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.storage.replication.FileUrlResolver;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class S3CompatibleStorageService implements ObjectStorageService, FileObjectStorage, AutoCloseable {

    private final S3StorageProperties properties;
    private final FileAliasResolver fileAliasResolver;
    private final FileLifecycleProperties fileLifecycleProperties;
    private volatile S3Presigner presigner;
    private volatile S3Client client;
    private volatile FileUrlResolver fileUrlResolver;

    @Autowired
    public S3CompatibleStorageService(S3StorageProperties properties, FileAliasResolver fileAliasResolver,
                                     FileLifecycleProperties fileLifecycleProperties) {
        this.properties = properties;
        this.fileAliasResolver = fileAliasResolver;
        this.fileLifecycleProperties = fileLifecycleProperties;
    }

    public S3CompatibleStorageService(S3StorageProperties properties, FileAliasResolver fileAliasResolver) {
        this(properties, fileAliasResolver, new FileLifecycleProperties());
    }

    public S3CompatibleStorageService(S3StorageProperties properties) {
        this(properties, null, new FileLifecycleProperties());
    }

    @Autowired(required = false)
    public void setFileUrlResolver(FileUrlResolver resolver) {
        this.fileUrlResolver = resolver;
    }

    @Override
    public String signGetUrl(String key) {
        if (!StringUtils.hasText(key)) {
            return key;
        }
        FileAliasResolver.Resolution managed = fileAliasResolver == null
                ? new FileAliasResolver.Resolution(false, null) : fileAliasResolver.resolve(key);
        if (managed.managed()) {
            if (!StringUtils.hasText(managed.readableObjectKey())) return null;
            // Use FileUrlResolver if available to check CDN eligibility
            if (fileUrlResolver != null) {
                FileUrlResolver.Resolution resolution = fileUrlResolver.describe(key);
                if (resolution.state() == FileUrlResolver.State.PENDING || !StringUtils.hasText(resolution.finalKey())) return null;
                if (resolution.cdnEligible() && StringUtils.hasText(properties.getPublicBaseUrl()) && usesProviderUrlPolicy()) {
                    return toPublicCdnUrl(resolution.finalKey());
                }
            }
            return signManagedGet(managed.readableObjectKey());
        }
        if (key.startsWith("staging/") || key.startsWith("verified/")) return null;
        if (!fileLifecycleProperties.isLegacyReadEnabled()) return null;
        if (isHttpUrl(key)) {
            return key;
        }
        if (!isAllowedReadKey(key)) {
            return null;
        }

        // 公开读前缀（如 avatar/）直接返回 CDN 公开 URL，无需签名
        if (isPublicReadKey(key)) {
            return toPublicCdnUrl(key);
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getSignGetTtlSeconds()))
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = getPresigner().presignGetObject(presignRequest);
        String originUrl = presignedRequest.url().toString();
        return usesProviderUrlPolicy() ? originUrl : toCdnUrl(originUrl);
    }

    private String signManagedGet(String finalKey) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(properties.getBucket()).key(finalKey).build();
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getSignGetTtlSeconds())).getObjectRequest(request).build();
        return getPresigner().presignGetObject(presign).url().toString();
    }

    @Override
    public List<String> signGetUrls(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        return keys.stream()
                .map(this::signGetUrl)
                .filter(StringUtils::hasText)
                .toList();
    }

    @Override
    public PresignedUpload presignPut(Long userId, UploadBiz biz, String fileName, String contentType, Long fileSize) {
        validateUpload(contentType, fileSize);

        String key = buildObjectKey(biz.getValue(), userId, fileName);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .build();

        long ttl = properties.getSignPutTtlSeconds();
        PutObjectPresignRequest putObjectPresignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttl))
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest signed = getPresigner().presignPutObject(putObjectPresignRequest);
        long expireAt = Instant.now().plusSeconds(ttl).toEpochMilli();
        // PUT 签名直接走源站（上传不走 CDN）
        return new PresignedUpload(signed.url().toString(), key, expireAt);
    }

    @Override
    public String presignStagingPut(String stagingKey, String contentType, long ttlSeconds) {
        PutObjectRequest put = PutObjectRequest.builder().bucket(properties.getBucket()).key(stagingKey)
                .contentType(contentType).build();
        return getPresigner().presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttlSeconds)).putObjectRequest(put).build()).url().toString();
    }

    @Override
    public FileObjectStorage.StagedMetadata head(String key) {
        var response = getClient().headObject(HeadObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
        return new FileObjectStorage.StagedMetadata(response.contentLength(), response.contentType());
    }

    @Override
    public FileObjectStorage.StagedMetadata downloadToFile(String key, java.nio.file.Path destination, long maxBytes) {
        try (var input = getClient().getObject(GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build())) {
            long declaredLength = input.response().contentLength() == null ? -1L : input.response().contentLength();
            String contentType = input.response().contentType();
            try (var output = java.nio.file.Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    total += count;
                    if (total > maxBytes) throw new BizException(BizCode.PARAM_INVALID, "文件大小超出限制");
                    output.write(buffer, 0, count);
                }
            }
            return new FileObjectStorage.StagedMetadata(declaredLength, contentType);
        } catch (java.io.IOException e) {
            throw new BizException(BizCode.OP_FAILED, "读取待校验文件失败");
        }
    }

    @Override
    public void putImmutableFile(String key, String contentType, java.nio.file.Path source) {
        getClient().putObject(PutObjectRequest.builder().bucket(properties.getBucket()).key(key)
                .contentType(contentType).build(), RequestBody.fromFile(source));
    }

    private S3Presigner getPresigner() {
        if (presigner != null) {
            return presigner;
        }
        synchronized (this) {
            if (presigner == null) {
                presigner = buildPresigner(properties);
            }
        }
        return presigner;
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (presigner != null) {
            presigner.close();
            presigner = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private S3Client getClient() {
        if (client != null) return client;
        synchronized (this) {
            if (client == null) {
                if (!StringUtils.hasText(properties.getEndpoint())) throw new BizException(BizCode.PARAM_INVALID, "对象存储配置不完整");
                client = S3Client.builder().endpointOverride(URI.create(properties.getEndpoint()))
                        .region(Region.of(properties.getRegion()))
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.isPathStyle()).build())
                        .build();
            }
        }
        return client;
    }

    private S3Presigner buildPresigner(S3StorageProperties cfg) {
        if (!StringUtils.hasText(cfg.getEndpoint()) || !StringUtils.hasText(cfg.getRegion())
                || !StringUtils.hasText(cfg.getAccessKey()) || !StringUtils.hasText(cfg.getSecretKey())
                || !StringUtils.hasText(cfg.getBucket())) {
            throw new BizException(BizCode.PARAM_INVALID, "对象存储配置不完整");
        }

        return S3Presigner.builder()
                .endpointOverride(URI.create(cfg.getEndpoint()))
                .region(Region.of(cfg.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.isPathStyle()).build())
                .build();
    }

    private void validateUpload(String contentType, Long fileSize) {
        if (!StringUtils.hasText(contentType)) {
            throw new BizException(BizCode.PARAM_INVALID, "contentType不能为空");
        }
        if (fileSize == null || fileSize <= 0) {
            throw new BizException(BizCode.PARAM_INVALID, "fileSize非法");
        }
        if (fileSize > properties.getMaxFileSizeBytes()) {
            throw new BizException(BizCode.PARAM_INVALID, "文件大小超出限制");
        }
        List<String> allowedContentTypes = properties.getAllowedContentTypes();
        if (allowedContentTypes != null && !allowedContentTypes.isEmpty()
                && !allowedContentTypes.contains(contentType)) {
            throw new BizException(BizCode.PARAM_INVALID, "不支持的文件类型");
        }
    }

    /**
     * 保留 GENERIC 模式原有的 CDN 替换行为。
     * RUSTFS / R2 不使用此路径，避免修改签名中的源站 host。
     */
    private String toCdnUrl(String originUrl) {
        String cdn = properties.getCdnEndpoint();
        if (!StringUtils.hasText(cdn)) {
            return originUrl;
        }
        String origin = properties.getEndpoint();
        if (!StringUtils.hasText(origin)) {
            return originUrl;
        }
        // 去掉末尾斜杠再替换，避免双斜杠
        String normalizedOrigin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        String normalizedCdn = cdn.endsWith("/") ? cdn.substring(0, cdn.length() - 1) : cdn;
        return originUrl.replace(normalizedOrigin, normalizedCdn);
    }

    private boolean isHttpUrl(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.startsWith("http://") || lowered.startsWith("https://");
    }

    private boolean isAllowedReadKey(String key) {
        List<String> prefixes = properties.getAllowedReadPrefixes();
        if (prefixes == null || prefixes.isEmpty()) {
            return true;
        }
        return prefixes.stream().anyMatch(key::startsWith);
    }

    private boolean isPublicReadKey(String key) {
        List<String> publicPrefixes = properties.getPublicReadPrefixes();
        if (publicPrefixes == null || publicPrefixes.isEmpty()) {
            return false;
        }
        return publicPrefixes.stream().anyMatch(key::startsWith);
    }

    private boolean usesProviderUrlPolicy() {
        return properties.getProvider() == S3StorageProperties.Provider.RUSTFS
                || properties.getProvider() == S3StorageProperties.Provider.R2;
    }

    /**
     * 拼接公开读的 CDN URL（无签名）。
     * GENERIC 保留 {endpoint}/{bucket}/{key}；RUSTFS / R2 使用显式公开对象根地址。
     */
    private String toPublicCdnUrl(String key) {
        if (usesProviderUrlPolicy()) {
            // Browsers and proxies normalize these segments to a different object path.
            // Reject the key rather than changing its meaning by normalizing it here.
            if (CdnPaths.hasDotSegment(key)) {
                return null;
            }
            String base = properties.getPublicBaseUrl();
            if (!StringUtils.hasText(base)) {
                throw new BizException(BizCode.PARAM_INVALID, "对象存储公开访问地址未配置");
            }
            return base.replaceAll("/+$", "") + "/" + CdnPaths.encodeKey(key);
        }
        String base = StringUtils.hasText(properties.getCdnEndpoint())
                ? properties.getCdnEndpoint()
                : properties.getEndpoint();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalizedBase + "/" + properties.getBucket() + "/" + key;
    }

    private String buildObjectKey(String biz, Long userId, String fileName) {
        if (userId == null || userId <= 0) {
            throw new BizException(BizCode.PARAM_INVALID, "用户ID非法");
        }
        String ext = extractFileExtension(fileName);
        YearMonth month = YearMonth.now();
        String folder = month.toString().replace("-", "");
        String key = biz + "/" + userId + "/" + folder + "/" + UUID.randomUUID().toString().replace("-", "");
        if (StringUtils.hasText(ext)) {
            key += "." + ext;
        }
        return key;
    }

    private String extractFileExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        String normalized = fileName.trim();
        int index = normalized.lastIndexOf('.');
        if (index < 0 || index == normalized.length() - 1) {
            return "";
        }
        String ext = normalized.substring(index + 1).toLowerCase(Locale.ROOT);
        if (!ext.matches("[a-z0-9]{1,8}")) {
            throw new BizException(BizCode.PARAM_INVALID, "文件后缀非法");
        }
        return ext;
    }
}
