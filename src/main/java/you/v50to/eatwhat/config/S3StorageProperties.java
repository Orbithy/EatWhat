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
    private long signGetTtlSeconds = 600;
    private long signPutTtlSeconds = 300;
    private long maxFileSizeBytes = 5L * 1024 * 1024;
    private List<String> allowedContentTypes = new ArrayList<>();
    private List<String> allowedReadPrefixes = new ArrayList<>();
}
