package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import you.v50to.eatwhat.account.ConsentStore;

import java.util.Objects;
import java.util.UUID;

/**
 * JDBC-backed replication consent gate.
 * Maps owner_gid to numeric user ID via eatwhat_identity.entity_identity table,
 * then checks ConsentStore for replication permission and epoch.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
public class JdbcReplicationConsentGate implements ReplicationConsentGate {

    private final JdbcTemplate jdbc;
    private final ConsentStore consentStore;

    public JdbcReplicationConsentGate(JdbcTemplate jdbc, ConsentStore consentStore) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.consentStore = Objects.requireNonNull(consentStore, "ConsentStore");
    }

    @Override
    public boolean replicationAllowed(UUID ownerGid) {
        Objects.requireNonNull(ownerGid, "ownerGid");
        Long userId = resolveUserId(ownerGid);
        if (userId == null) {
            throw new IllegalArgumentException("IDENTITY_PENDING: owner " + ownerGid + " not yet synced");
        }
        return consentStore.replicationAllowed(userId);
    }

    @Override
    public Long currentEpoch(UUID ownerGid) {
        Objects.requireNonNull(ownerGid, "ownerGid");
        Long userId = resolveUserId(ownerGid);
        if (userId == null) {
            throw new IllegalArgumentException("IDENTITY_PENDING: owner " + ownerGid + " not yet synced");
        }
        var state = consentStore.get(userId);
        return state != null ? state.epoch() : null;
    }

    /**
     * Resolve a global_id to numeric user ID via entity_identity table.
     * @return numeric user ID, or null if not found (identity not yet synced)
     */
    private Long resolveUserId(UUID ownerGid) {
        try {
            return jdbc.queryForObject("""
                    SELECT numeric_id FROM eatwhat_identity.entity_identity
                    WHERE global_id=? AND entity_type='users'
                    """, Long.class, ownerGid);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }
}
