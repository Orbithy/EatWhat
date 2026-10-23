package you.v50to.eatwhat.sync.activity;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.EventHandler;
import you.v50to.eatwhat.sync.SyncEvent;

import java.util.Objects;
import java.util.function.LongPredicate;

/** Same-database replay of one (table, event type) pair; all writes go through owner-controlled functions. */
public final class ActivityEventHandler implements EventHandler {
    private final String table;
    private final String eventType;
    private final JdbcTemplate jdbc;
    private final LongPredicate authorMayReplicate;

    public ActivityEventHandler(String table, String eventType, JdbcTemplate jdbc, LongPredicate authorMayReplicate) {
        if (!ActivityEvents.supportsTable(table)
                || !(ActivityEvents.CREATED.equals(eventType) || ActivityEvents.DELETED.equals(eventType))) {
            throw new IllegalArgumentException("Unsupported activity handler");
        }
        this.table = table;
        this.eventType = eventType;
        this.jdbc = Objects.requireNonNull(jdbc);
        this.authorMayReplicate = Objects.requireNonNull(authorMayReplicate);
    }

    @Override public String aggregateType() { return table; }
    @Override public String eventType() { return eventType; }
    @Override public int schemaVersion() { return ActivityEvents.SCHEMA_VERSION; }

    @Override public boolean dependenciesSatisfied(SyncEvent event) {
        Long author = localAuthor(event);
        if (ActivityEvents.CREATED.equals(eventType)) {
            if (author == null || !authorMayReplicate.test(author)) return false;
            var created = ActivityEvents.parseCreated(table, event.payloadJson());
            if (ActivityEvents.POSTS.equals(table) && !ActivityEvents.activityDefinitionPresent(jdbc, created.activityGlobalId())) {
                return false;
            }
            if (created.provinceId() == null && created.cityId() == null) return true;
            // Dictionaries are identical on both sites; a missing or mismatched entry is a real prerequisite gap.
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT (?::int IS NULL OR EXISTS(SELECT 1 FROM public.provinces WHERE id=?))
                       AND (?::int IS NULL OR EXISTS(SELECT 1 FROM public.cities c WHERE c.id=?
                              AND (?::int IS NULL OR c.province_id=?)))
                    """, Boolean.class, created.provinceId(), created.provinceId(), created.cityId(), created.cityId(),
                    created.provinceId(), created.provinceId()));
        }
        // The activity must be known here. If its replica was purged (its author withdrew) the identity tombstone
        // remains and the deletion is a harmless no-op; otherwise only the activity's own author may delete it.
        var rows = jdbc.query("""
                SELECT a.account_id FROM eatwhat_identity.entity_identity i
                    LEFT JOIN public.%s a ON a.id=i.numeric_id
                  WHERE i.entity_type=? AND i.numeric_id=? AND i.global_id=?
                """.formatted(table), (row, index) -> row.getObject(1, Long.class), table, event.numericId(), event.aggregateId());
        if (rows.isEmpty()) return false;
        Long owner = rows.getFirst();
        if (owner == null) return true;
        return author != null && author.equals(owner) && authorMayReplicate.test(author);
    }

    @Override public void apply(SyncEvent event) {
        if (ActivityEvents.CREATED.equals(eventType)) {
            jdbc.queryForObject("SELECT eatwhat_sync.apply_activity_created(?, ?::jsonb)", String.class,
                    table, event.payloadJson());
        } else {
            long deletedAt = ActivityEvents.parseDeleted(event.payloadJson());
            jdbc.queryForObject("SELECT eatwhat_sync.apply_activity_deleted(?, ?, ?, to_timestamp(?/1000.0))",
                    String.class, table, event.numericId(), event.aggregateId(), deletedAt);
        }
    }

    private Long localAuthor(SyncEvent event) {
        var ids = jdbc.query("""
                SELECT i.numeric_id FROM eatwhat_identity.entity_identity i JOIN public.users u ON u.id=i.numeric_id
                WHERE i.entity_type='users' AND i.global_id=?
                """, (row, index) -> row.getLong(1), event.actorId());
        return ids.isEmpty() ? null : ids.getFirst();
    }
}
