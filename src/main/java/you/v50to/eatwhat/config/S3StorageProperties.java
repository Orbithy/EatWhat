package you.v50to.eatwhat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.storage.s3")
public class S3StorageProperties {
    private String endpoint;
    private String region;
    private String bucket;
    private String accessKey;
    private String secretKey;
    private boolean pathStyle = true;
    private String cdnEndpoint; // 可选，CDN 访问域名，如 https://sgp1.cdn.digitaloceanspaces.com
    private long signGetTtlSeconds = 600;
    private long signPutTtlSeconds = 300;
    private long maxFileSizeBytes = 5L * 1024 * 1024;
    private List<String> allowedContentTypes = new ArrayList<>();
    private List<String> allowedReadPrefixes = new ArrayList<>();
    private List<String> publicReadPrefixes = new ArrayList<>(); // 公开读前缀，命中则直接返回 CDN 公开 URL，无需签名
}
