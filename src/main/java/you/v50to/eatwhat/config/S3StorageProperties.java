package you.v50to.eatwhat.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.storage.s3")
public class S3StorageProperties {
    public enum Provider {
        GENERIC, RUSTFS, R2
    }

    private Provider provider = Provider.GENERIC;
    private String endpoint;
    private String region;
    private String bucket;
    @ToString.Exclude
    private String accessKey;
    @ToString.Exclude
    private String secretKey;
    private boolean pathStyle = true;
    private String cdnEndpoint; // 可选，CDN 访问域名，如 https://sgp1.cdn.digitaloceanspaces.com
    private String publicBaseUrl; // RUSTFS / R2 的公开对象根地址；需要的 bucket / 路径由配置者写入
    private long signGetTtlSeconds = 600;
    private long signPutTtlSeconds = 300;
    /**
     * Largest single upload accepted by presign/complete. 50 MiB: one presigned PUT carries the whole object (S3 and R2
     * accept a single PUT far beyond this), so no client-side multipart is needed at this size (client multipart presign
     * endpoints are deliberately deferred). complete() streams the staged object to a temp file in 64 KiB chunks, so
     * memory stays bounded; only temp disk (one file at a time) grows with this limit. Server-side replication uses
     * multipart for large copies (see FileReplicationProperties.multipartThreshold) and requires
     * max-file-size-bytes / part-size to stay at or below the 10000-part limit.
     */
    private long maxFileSizeBytes = 50L * 1024 * 1024;
    private List<String> allowedContentTypes = new ArrayList<>();
    private List<String> allowedReadPrefixes = new ArrayList<>();
    private List<String> publicReadPrefixes = new ArrayList<>(); // 公开读前缀，命中则直接返回 CDN 公开 URL，无需签名
}
