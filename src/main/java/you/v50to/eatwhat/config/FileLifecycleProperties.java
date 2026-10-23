package you.v50to.eatwhat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.storage.files")
public class FileLifecycleProperties {
    /** New staged upload flow is opt-in until storage and recovery checks are complete. */
    private boolean lifecycleEnabled = false;
    private boolean legacyReadEnabled = true;
    private boolean legacyReferenceWriteEnabled = false;
    private long intentTtlSeconds = 900;
    private long claimLeaseSeconds = 120;

    /**
     * Soft cap on a user's open upload intents (not completed, not expired). 0 (default) means unlimited. Checked at
     * presign time without a lock, so concurrent presigns of the same user can overshoot it slightly.
     */
    private int maxOpenIntentsPerUser = 0;

    /**
     * Soft cap on the sum of declared sizes of the intents a user created in the last hour (database clock). 0
     * (default) means unlimited. Same best-effort semantics as {@link #maxOpenIntentsPerUser}.
     */
    private long maxBytesPerHourPerUser = 0;
}
