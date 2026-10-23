package you.v50to.eatwhat.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal, read-only identity lookup. No controller, business PO or response DTO exposes this data. */
@Service
public class EntityIdentityService {
    private final JdbcTemplate jdbc;

    public EntityIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findGlobalId(EntityType type, long numericId) {
        Objects.requireNonNull(type, "type");
        if (numericId <= 0) {
            throw new IllegalArgumentException("numericId must be positive");
        }
        return jdbc.query("""
                SELECT global_id FROM eatwhat_identity.entity_identity
                WHERE entity_type=? AND numeric_id=?
                """, (result, row) -> result.getObject(1, UUID.class),
                type.identityType().tableName(), numericId).stream().findFirst();
    }

    public Optional<Long> findNumericId(EntityType type, UUID globalId) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(globalId, "globalId");
        return jdbc.query("""
                SELECT numeric_id FROM eatwhat_identity.entity_identity
                WHERE entity_type=? AND global_id=?
                """, (result, row) -> result.getLong(1),
                type.identityType().tableName(), globalId).stream().findFirst();
    }
}
