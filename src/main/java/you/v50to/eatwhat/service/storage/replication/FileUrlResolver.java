package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.config.FileReplicationProperties;

import java.util.Objects;
import java.util.UUID;

/**
 * Resolves the URL accessibility state of a file key based on replica and publication status.
 * Determines whether a managed file is readable locally, globally, or paused.
 * Calculates CDN eligibility based on replication state, publication, and consent.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
public class FileUrlResolver {

    @org.springframework.beans.factory.annotation.Value("${app.account.multiwriter.enabled:false}")
    private boolean multiwriter;
    private final JdbcTemplate jdbc;
    private final FileReplicationProperties properties;
    private final ReplicationConsentGate consentGate;

    public FileUrlResolver(JdbcTemplate jdbc, FileReplicationProperties properties, ReplicationConsentGate consentGate) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.properties = Objects.requireNonNull(properties, "FileReplicationProperties");
        this.consentGate = Objects.requireNonNull(consentGate, "ReplicationConsentGate");
    }

    /**
     * Determine the resolution state of a file by its logical key.
     * Returns a Resolution with state, finalKey, publicReference flag, and CDN eligibility.
     * Never throws for a missing key; returns UNMANAGED state instead.
     *
     * @param logicalKey the logical file key
     * @return Resolution describing the file's accessibility state
     */
    public Resolution describe(String logicalKey) {
        Objects.requireNonNull(logicalKey, "logicalKey");

        // Single query to join alias -> revision, LEFT JOIN replicas and publication, check for public reference
        // Extracts: final_key, revision state, local replica state, publication state, public ref exists, owner_gid
        var result = jdbc.query("""
                SELECT
                    r.final_key,
                    r.state,
                    lr.state,
                    fp.global_state,
                    EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live f
                           WHERE f.logical_key=a.logical_key AND f.visibility='PUBLIC') as has_public_ref,
                    fo.owner_gid,
                    (? AND EXISTS(SELECT 1 FROM eatwhat_identity.node_state WHERE singleton AND mode='EXTERNAL')
                       AND EXISTS(SELECT 1 FROM eatwhat_consent.subject c WHERE c.subject_id=fo.owner_gid AND c.status='WITHDRAWN')) AS withdrawn
                FROM eatwhat_file.file_key_alias a
                JOIN eatwhat_file.file_revision r ON r.file_id=a.file_id AND r.revision=a.revision
                LEFT JOIN eatwhat_file.file_replica lr ON lr.file_id=r.file_id AND lr.revision=r.revision AND lr.site='LOCAL'
                LEFT JOIN eatwhat_file.file_publication fp ON fp.file_id=r.file_id AND fp.revision=r.revision
                JOIN eatwhat_file.file_object fo ON fo.file_id=a.file_id
                WHERE a.logical_key=?
                LIMIT 1
                """, rs -> {
            if (!rs.next()) {
                // No alias row: unmanaged key
                return null;
            }

            if (rs.getBoolean(7)) return new Resolution(State.PENDING, null, false, false);
            String finalKey = rs.getString(1);
            String revisionState = rs.getString(2);
            String localReplicaState = rs.getString(3);
            String publicationState = rs.getString(4);
            boolean hasPublicRef = rs.getBoolean(5);
            UUID ownerGid = (UUID) rs.getObject(6);

            // Determine state based on readability and publication
            State state;
            boolean cdnEligible;

            // Check readability: revision VERIFIED + LOCAL replica AVAILABLE_LOCAL
            boolean isReadableLocally = "VERIFIED".equals(revisionState) && "AVAILABLE_LOCAL".equals(localReplicaState);

            if (!isReadableLocally) {
                // Not readable locally: PENDING
                state = State.PENDING;
                cdnEligible = false;
            } else if ("PAUSED".equals(publicationState)) {
                // Readable locally but publication is PAUSED
                state = State.PAUSED;
                cdnEligible = false;
            } else if ("AVAILABLE_GLOBAL".equals(publicationState)) {
                // Readable locally and publication is AVAILABLE_GLOBAL: check CDN eligibility
                state = State.AVAILABLE_GLOBAL;
                // CDN eligible if: cdnOutputEnabled AND publicReference exists AND consent allowed
                cdnEligible = calculateCdnEligibility(hasPublicRef, ownerGid);
            } else {
                // Readable locally but publication is LOCAL_ONLY or PEER_PENDING or missing
                state = State.AVAILABLE_LOCAL;
                cdnEligible = false;
            }

            // Never expose the object key of a revision that is not readable yet.
            return new Resolution(state, state == State.PENDING ? null : finalKey, hasPublicRef, cdnEligible);
        }, multiwriter, logicalKey);

        return result != null ? result : new Resolution(State.UNMANAGED, null, false, false);
    }

    /**
     * Calculate CDN eligibility with consent check.
     * Returns true only if: cdnOutputEnabled=true AND ownerGid consent allowed AND public reference exists.
     * Swallows IllegalArgumentException from unknown identity (IDENTITY_PENDING) and returns false.
     */
    private boolean calculateCdnEligibility(boolean hasPublicRef, UUID ownerGid) {
        if (!properties.isCdnOutputEnabled() || !hasPublicRef) {
            return false;
        }

        try {
            return consentGate.replicationAllowed(ownerGid);
        } catch (IllegalArgumentException e) {
            // Identity unknown (IDENTITY_PENDING): not eligible for CDN yet
            return false;
        }
    }

    /**
     * Resolution state describing a file's accessibility for URL generation.
     * UNMANAGED: no alias row (legacy key)
     * PENDING: revision not VERIFIED or LOCAL replica not AVAILABLE_LOCAL (not readable)
     * AVAILABLE_LOCAL: readable locally but publication LOCAL_ONLY/PEER_PENDING (no global access)
     * AVAILABLE_GLOBAL: readable locally and publication AVAILABLE_GLOBAL (eligible for CDN if conditions met)
     * PAUSED: readable locally but publication PAUSED
     */
    public enum State {
        UNMANAGED, PENDING, AVAILABLE_LOCAL, AVAILABLE_GLOBAL, PAUSED
    }

    public record Resolution(State state, String finalKey, boolean publicReference, boolean cdnEligible) {
    }
}
