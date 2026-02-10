package you.v50to.eatwhat.service.storage;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class S3CompatibleStorageService implements ObjectStorageService {

    private final S3StorageProperties properties;
    private volatile S3Presigner presigner;

    public S3CompatibleStorageService(S3StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String signGetUrl(String key) {
        if (!StringUtils.hasText(key)) {
            return key;
        }
        if (isHttpUrl(key)) {
            return key;
        }
        if (!isAllowedReadKey(key)) {
            return null;
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
        return presignedRequest.url().toString();
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
    public PresignedUpload presignPut(Long userId, String biz, String fileName, String contentType, Long fileSize) {
        validateUpload(contentType, fileSize);

        String normalizedBiz = normalizeBiz(biz);
        String key = buildObjectKey(normalizedBiz, userId, fileName);

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
        return new PresignedUpload(signed.url().toString(), key, expireAt);
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
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())
                ))
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
        if (allowedContentTypes != null && !allowedContentTypes.isEmpty() && !allowedContentTypes.contains(contentType)) {
            throw new BizException(BizCode.PARAM_INVALID, "不支持的文件类型");
        }
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

    private String normalizeBiz(String biz) {
        if (!StringUtils.hasText(biz)) {
            return "activity";
        }
        if ("activity".equals(biz) || "avatar".equals(biz)) {
            return biz;
        }
        throw new BizException(BizCode.PARAM_INVALID, "biz仅支持activity或avatar");
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
