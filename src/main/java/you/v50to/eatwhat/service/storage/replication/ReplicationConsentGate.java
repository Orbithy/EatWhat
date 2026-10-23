package you.v50to.eatwhat.service.storage.replication;

import java.util.UUID;

/**
 * Gate for replication consent checks: whether a file owner has granted
 * replication consent and what consent epoch they are at.
 * Implementations may return IDENTITY_PENDING to indicate the owner's
 * identity cannot be resolved yet (e.g., not yet synced from peer).
 */
public interface ReplicationConsentGate {
    /**
     * Check if replication is allowed for the given owner.
     * @return true if allowed, false if denied; throws IllegalArgumentException if identity unknown
     * @throws IllegalArgumentException if owner identity cannot be resolved (IDENTITY_PENDING condition)
     */
    boolean replicationAllowed(UUID ownerGid);

    /**
     * Get the current consent epoch for the owner.
     * @return consent epoch, or null if no consent state exists
     * @throws IllegalArgumentException if owner identity cannot be resolved (IDENTITY_PENDING condition)
     */
    Long currentEpoch(UUID ownerGid);
}
