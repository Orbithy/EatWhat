package you.v50to.eatwhat.sync.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.EventCodec;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Applies validated catalog state through the narrow SECURITY DEFINER projection boundary. */
@Component
@ConditionalOnProperty(name = "app.sync.catalog.enabled", havingValue = "true")
public final class CatalogProjection {
    public enum ProjectionResult { APPLIED, KEY_CONFLICT, WAIT_PARENT, TERMINAL }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final CatalogStore store;
    private final EntityIdentityService identities;
    private final SiteProperties site;

    public CatalogProjection(JdbcTemplate jdbc, CatalogStore store, EntityIdentityService identities,
                             SiteProperties site) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.store = Objects.requireNonNull(store);
        this.identities = Objects.requireNonNull(identities);
        this.site = Objects.requireNonNull(site);
    }

    /** The caller owns the inbox transaction and has validated consent, identity and causal state. */
    public ProjectionResult apply(CatalogEvents.Action action) {
        requireTransaction();
        Objects.requireNonNull(action, "action");
        String payload = action.operation() == CatalogOperation.DELETE
                ? "{}" : canonicalPayload(action.kind(), action.canonicalPayload());
        return invoke(action.kind(), action.objectGid(), action.numericId(), action.identityOrigin(),
                action.authorGid(), action.parentGid(), action.operation(), payload);
    }

    /** Rebuilds a selected winner from its immutable revision, preserving the original public numeric ID. */
    public ProjectionResult applyWinner(CatalogStore.Identity identity, Map<String, Object> payload) {
        requireTransaction();
        Objects.requireNonNull(identity, "identity");
        if (identity.tombstone() || identity.rejected()) return ProjectionResult.TERMINAL;
        CatalogOperation operation = identities.findNumericId(entityType(identity.kind()), identity.gid()).isPresent()
                ? CatalogOperation.UPDATE : CatalogOperation.CREATE;
        UUID parentGid = identity.kind() == CatalogKind.RESTAURANT
                ? nullableUuid(payload.get("hubGid"))
                : identity.kind() == CatalogKind.FOOD ? nullableUuid(payload.get("restaurantGid"))
                : identity.parentGid();
        return invoke(identity.kind(), identity.gid(), identity.numericId(), identity.originSite(),
                identity.authorGid(), parentGid, operation, canonicalPayload(identity.kind(), payload));
    }

    /** Removes a rejected candidate's visible row while retaining its identity and revision history. */
    public void clearRejected(CatalogKind kind, long numericId) {
        requireTransaction();
        if (numericId <= 0) throw new IllegalArgumentException("Invalid catalog numeric ID");
        CatalogStore.Identity identity = store.findByNumeric(kind, numericId);
        if (identity == null) return;
        jdbc.queryForList("SELECT eatwhat_sync.clear_catalog_projection(?,?,?)",
                kind.name(), numericId, identity.gid());
    }

    private ProjectionResult invoke(CatalogKind kind, UUID gid, long numericId, String originSite,
                                    UUID authorGid, UUID parentGid, CatalogOperation operation, String payload) {
        String result = jdbc.queryForObject("""
                SELECT eatwhat_sync.apply_catalog_projection(?,?,?,?,?,?,?,?::jsonb)
                """, String.class, kind.name(), gid, numericId, originSite, authorGid, parentGid,
                operation.name(), payload);
        try {
            return ProjectionResult.valueOf(result);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("UNKNOWN_CATALOG_PROJECTION_RESULT", invalid);
        }
    }

    private static String canonicalPayload(CatalogKind kind, Map<String, Object> payload) {
        Map<String, Object> checked = CatalogPayload.validate(kind, payload);
        return EventCodec.canonicalJson(JSON.writeValueAsString(checked));
    }

    private static UUID nullableUuid(Object raw) {
        if (raw == null) return null;
        return raw instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(raw));
    }

    private static EntityType entityType(CatalogKind kind) {
        return switch (kind) {
            case HUB -> EntityType.HUB;
            case RESTAURANT -> EntityType.RESTAURANT;
            case FOOD -> EntityType.FOODS;
            case SYSTEM_TAG, CUSTOM_TAG -> EntityType.FOOD_TAGS;
            case ACTIVITY -> EntityType.ACTIVITIES;
        };
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("CATALOG_PROJECTION_REQUIRES_TRANSACTION");
        }
        if (site.getId() == null) throw new IllegalStateException("CATALOG_PROJECTION_SITE_UNCONFIGURED");
    }
}
