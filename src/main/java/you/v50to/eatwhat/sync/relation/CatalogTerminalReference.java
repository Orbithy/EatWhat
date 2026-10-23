package you.v50to.eatwhat.sync.relation;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Validates an absent catalog dependency against its durable identity and terminal ledger row. */
final class CatalogTerminalReference {
    private CatalogTerminalReference() { }

    static boolean isTerminal(JdbcTemplate jdbc, RelationKind kind, UUID targetGid, long targetId,
                              UUID ownerGid, UUID tagGid, long tagId, UUID actorGid) {
        if ("foods".equals(kind.targetTable)) {
            boolean ownedOrTerminal = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(
                      SELECT 1 FROM eatwhat_identity.entity_identity i
                      JOIN eatwhat_identity.entity_identity ui
                        ON ui.entity_type='users' AND ui.global_id=?
                      WHERE i.entity_type='foods' AND i.global_id=? AND i.numeric_id=?
                        AND (EXISTS(SELECT 1 FROM public.foods f
                                    WHERE f.id=i.numeric_id AND f.account_id=ui.numeric_id)
                          OR EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object c
                                    WHERE c.kind='FOOD' AND c.object_gid=i.global_id AND c.numeric_id=i.numeric_id
                                      AND c.origin_site=i.origin_site AND c.author_gid=?
                                      AND NOT EXISTS(SELECT 1 FROM public.foods f WHERE f.id=i.numeric_id)
                                      AND ((c.tombstone OR c.rejected) OR EXISTS(
                                        SELECT 1 FROM eatwhat_sync.catalog_object p
                                        JOIN eatwhat_identity.entity_identity pi
                                          ON pi.entity_type='restaurant' AND pi.global_id=p.object_gid
                                         AND pi.numeric_id=p.numeric_id AND pi.origin_site=p.origin_site
                                        WHERE p.kind='RESTAURANT' AND p.object_gid=c.parent_gid
                                          AND (p.tombstone OR p.rejected)))
                                      FOR KEY SHARE))
                    )
                    """, Boolean.class, ownerGid, targetGid, targetId, ownerGid));
            if (!ownedOrTerminal) return false;
            boolean foodTerminal = ownedOrTerminal && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(
                      SELECT 1 FROM eatwhat_identity.entity_identity i
                      JOIN eatwhat_sync.catalog_object c
                        ON c.kind='FOOD' AND c.object_gid=i.global_id AND c.numeric_id=i.numeric_id
                       AND c.origin_site=i.origin_site
                      WHERE i.entity_type='foods' AND i.global_id=? AND i.numeric_id=?
                        AND c.author_gid=? AND NOT EXISTS(SELECT 1 FROM public.foods f WHERE f.id=i.numeric_id)
                        AND ((c.tombstone OR c.rejected) OR EXISTS(
                          SELECT 1 FROM eatwhat_sync.catalog_object p
                          JOIN eatwhat_identity.entity_identity pi
                            ON pi.entity_type='restaurant' AND pi.global_id=p.object_gid
                           AND pi.numeric_id=p.numeric_id AND pi.origin_site=p.origin_site
                          WHERE p.kind='RESTAURANT' AND p.object_gid=c.parent_gid
                            AND (p.tombstone OR p.rejected)))
                      FOR KEY SHARE OF c)
                    """, Boolean.class, targetGid, targetId, ownerGid));
            if (tagGid == null) return foodTerminal;
            boolean publicFoodOwned = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM public.foods f
                      JOIN eatwhat_identity.entity_identity u ON u.entity_type='users' AND u.global_id=?
                      WHERE f.id=? AND f.account_id=u.numeric_id)
                    """, Boolean.class, ownerGid, targetId));
            return tagReferenceValid(jdbc, tagGid, tagId, actorGid)
                    && (foodTerminal || (publicFoodOwned && isTerminalTag(jdbc, tagGid, tagId, actorGid)));
        }
        if (tagGid == null) return false;
        return tagReferenceValid(jdbc, tagGid, tagId, actorGid)
                && isTerminalTag(jdbc, tagGid, tagId, actorGid);
    }

    static boolean tagReferenceValid(JdbcTemplate jdbc, UUID tagGid, long tagId, UUID actorGid) {
        if (tagGid == null || tagId <= 0) return false;
        boolean publicReference = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM eatwhat_identity.entity_identity i
                  WHERE i.entity_type='food_tags' AND i.global_id=? AND i.numeric_id=?
                    AND EXISTS(SELECT 1 FROM public.food_tags t WHERE t.id=i.numeric_id
                               AND (t.tag_type='system' OR (t.tag_type='custom' AND t.owner_id=(
                                 SELECT u.numeric_id FROM eatwhat_identity.entity_identity u
                                 WHERE u.entity_type='users' AND u.global_id=?))))
                )
                """, Boolean.class, tagGid, tagId, actorGid));
        return publicReference || isTerminalTag(jdbc, tagGid, tagId, actorGid);
    }

    private static boolean isTerminalTag(JdbcTemplate jdbc, UUID tagGid, long tagId, UUID actorGid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM eatwhat_identity.entity_identity i
                  JOIN eatwhat_sync.catalog_object c
                    ON c.object_gid=i.global_id AND c.numeric_id=i.numeric_id
                   AND c.origin_site=i.origin_site
                  WHERE i.entity_type='food_tags' AND i.global_id=? AND i.numeric_id=?
                    AND (c.tombstone OR c.rejected)
                    AND ((c.kind='SYSTEM_TAG' AND c.author_gid IS NULL)
                      OR (c.kind='CUSTOM_TAG' AND c.author_gid=?))
                    AND NOT EXISTS(SELECT 1 FROM public.food_tags t WHERE t.id=i.numeric_id)
                  FOR KEY SHARE OF c)
                """, Boolean.class, tagGid, tagId, actorGid));
    }

    /** Resolves only the durable identity after the public row was deleted; callers still verify terminal state. */
    static Long registeredNumericId(JdbcTemplate jdbc, String targetTable, UUID targetGid) {
        String entityType = switch (targetTable) {
            case "foods" -> "foods";
            case "food_tags" -> "food_tags";
            case "restaurant" -> "restaurant";
            case "hub" -> "hub";
            case "users" -> "users";
            default -> null;
        };
        if (entityType == null || targetGid == null) return null;
        return jdbc.query("SELECT numeric_id FROM eatwhat_identity.entity_identity WHERE entity_type=? AND global_id=?",
                (rs, row) -> rs.getLong(1), entityType, targetGid).stream().findFirst().orElse(null);
    }
}
