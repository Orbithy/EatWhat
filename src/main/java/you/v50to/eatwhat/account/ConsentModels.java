package you.v50to.eatwhat.account;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Account payloads never appear in diagnostic record rendering. */
public final class ConsentModels {
    private ConsentModels() { }

    public record State(long userId, UUID subjectId, long epoch, long revision, String status,
                        String policyVersion, Instant validUntil, boolean identityReady,
                        String backfillStatus, String cleanupStatus, UUID withdrawalRequestId) { }

    public record Snapshot(UUID subjectId, long userId, long epoch, long revision, String status,
                           String policyVersion, Instant validUntil, String sourceSite, UUID sourceInstance,
                           UUID withdrawalRequestId, JsonNode data) {
        @Override public String toString() { return "Snapshot[redacted]"; }
    }

    public record Job(UUID jobId, long userId, UUID subjectId, long epoch, long revision,
                      String kind, UUID leaseToken, int attempts) { }
}
