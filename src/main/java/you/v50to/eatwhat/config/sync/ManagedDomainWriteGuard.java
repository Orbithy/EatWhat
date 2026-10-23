package you.v50to.eatwhat.config.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.catalog.CatalogKind;
import you.v50to.eatwhat.sync.relation.RelationKind;

/** Prevents legacy mappers from silently changing relation/profile/privacy/read domains already sync-managed. */
public final class ManagedDomainWriteGuard {
    private final JdbcTemplate jdbc;

    public ManagedDomainWriteGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean profileManaged(long userId) {
        return exists("profile_domain", userId);
    }

    public boolean privacyManaged(long userId) {
        return exists("privacy_domain", userId);
    }

    public boolean notificationManaged(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity i
                  JOIN eatwhat_sync.notification_read_domain d ON d.recipient_gid=i.global_id
                  WHERE i.entity_type=? AND i.numeric_id=?)
                """, Boolean.class, EntityType.USERS.tableName(), userId));
    }

    /** Detects relation ownership across V22 projections/facts, V23 intents, and V25 causal state. */
    public boolean relationManaged(RelationKind kind, long actorId, long targetId, Long tagId) {
        if ((kind == RelationKind.tagging) != (tagId != null)) return false;
        if (tagId != null && Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity
                  WHERE entity_type='food_tags' AND numeric_id=?)
                """, Boolean.class, tagId))) return false;
        String tagGlobalId = tagId == null ? "NULL::uuid"
                : "(SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='food_tags' AND numeric_id=?)";
        // Do not derive ownership from the public target row: withdrawal purge can remove it.
        String relationKey = """
                SELECT actor_identity.global_id AS actor_gid,target_identity.global_id AS target_gid,%s AS tag_gid
                FROM eatwhat_identity.entity_identity actor_identity
                JOIN eatwhat_identity.entity_identity target_identity
                  ON target_identity.entity_type=? AND target_identity.numeric_id=?
                WHERE actor_identity.entity_type='users' AND actor_identity.numeric_id=?
                """.formatted(tagGlobalId);
        Object[] arguments = tagId == null
                ? new Object[]{kind.targetTable, targetId, actorId}
                : new Object[]{tagId, kind.targetTable, targetId, actorId};
        String sql = """
                WITH natural_key AS (%s)
                SELECT EXISTS(
                  SELECT 1 FROM natural_key k JOIN eatwhat_sync.relation_causal_state c
                    ON c.kind=? AND c.actor_gid=k.actor_gid AND c.target_gid=k.target_gid
                   AND c.tag_gid IS NOT DISTINCT FROM k.tag_gid
                  UNION ALL
                  SELECT 1 FROM natural_key k JOIN eatwhat_sync.relation_intent i
                    ON i.kind=? AND i.actor_gid=k.actor_gid AND i.target_gid=k.target_gid
                   AND i.tag_gid IS NOT DISTINCT FROM k.tag_gid
                  UNION ALL
                  SELECT 1 FROM natural_key k JOIN eatwhat_sync.relation_operation o
                    ON o.payload->>'kind'=? AND (o.payload->>'actor')::uuid=k.actor_gid
                   AND (o.payload->>'target')::uuid=k.target_gid
                   AND nullif(o.payload->>'tag','null')::uuid IS NOT DISTINCT FROM k.tag_gid
                  UNION ALL
                  SELECT 1 FROM eatwhat_sync.relation_projection p
                    WHERE p.relation_key=?
                )
                """.formatted(relationKey);
        var args = new java.util.ArrayList<Object>(java.util.Arrays.asList(arguments));
        args.add(kind.name()); args.add(kind.name()); args.add(kind.name());
        args.add(kind.name() + ":" + actorId + ":" + targetId + ":" + (tagId == null ? 0 : tagId));
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args.toArray()));
    }

    /** Once a catalog kind has been managed, legacy writes stay blocked even while capture is disabled. */
    public boolean catalogManaged(CatalogKind kind, Long numericId) {
        if (kind == null) return false;
        boolean domainManaged = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_managed_domain WHERE kind=?)
                """, Boolean.class, kind.name()));
        if (domainManaged || numericId == null) return domainManaged;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind=? AND numeric_id=?)
                """, Boolean.class, kind.name(), numericId));
    }

    public boolean customTagManaged(Long tagId, Long ownerUserId) {
        if (tagId == null || ownerUserId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM eatwhat_sync.catalog_object c
                  JOIN eatwhat_identity.entity_identity tag_identity
                    ON tag_identity.entity_type='food_tags' AND tag_identity.global_id=c.object_gid
                  JOIN eatwhat_identity.entity_identity owner_identity
                    ON owner_identity.entity_type='users' AND owner_identity.numeric_id=?
                  WHERE c.kind='CUSTOM_TAG' AND c.numeric_id=? AND c.author_gid=owner_identity.global_id
                ) OR EXISTS(SELECT 1 FROM eatwhat_sync.catalog_managed_domain WHERE kind='CUSTOM_TAG')
                """, Boolean.class, ownerUserId, tagId));
    }

    private boolean exists(String table, long userId) {
        // Table name is internal and selected from these two constants only.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity i
                  JOIN eatwhat_sync.%s d ON d.subject_gid=i.global_id
                  WHERE i.entity_type=? AND i.numeric_id=? AND d.managed)
                """.formatted(table), Boolean.class, EntityType.USERS.tableName(), userId));
    }
}
