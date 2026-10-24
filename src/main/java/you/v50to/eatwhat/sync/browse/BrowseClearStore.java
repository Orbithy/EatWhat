package you.v50to.eatwhat.sync.browse;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.Map;
import java.util.TreeMap;

/** Delete wins over concurrent/unobserved visits; visits made after observing the frontier survive. */
public final class BrowseClearStore {
    private final JdbcTemplate jdbc;
    public BrowseClearStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock(long actor) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", row -> {}, "browse:" + actor);
    }

    public VersionVector context(long actor, String type) {
        BrowseCommands.table(type);
        lock(actor);
        jdbc.update("INSERT INTO eatwhat_sync.browse_clear(user_id,target_type) VALUES(?,?) ON CONFLICT DO NOTHING", actor, type);
        return EventCodec.vectorFromJson(jdbc.queryForObject(
                "SELECT vector::text FROM eatwhat_sync.browse_clear WHERE user_id=? AND target_type=? FOR UPDATE",
                String.class, actor, type));
    }

    public VersionVector merge(long actor, String type, Map<String, Long> incoming) {
        VersionVector current = context(actor, type);
        var merged = new TreeMap<>(current.counters());
        new VersionVector(incoming).counters().forEach((site, value) -> merged.merge(site, value, Math::max));
        VersionVector next = new VersionVector(merged);
        if (!current.equals(next)) jdbc.update("UPDATE eatwhat_sync.browse_clear SET vector=?::jsonb,dirty=true WHERE user_id=? AND target_type=?",
                EventCodec.vectorJson(next), actor, type);
        // Physical deletion removes targets and viewing times; only the causal frontier remains.
        jdbc.update("""
                DELETE FROM public.browse_history b WHERE account_id=? AND target_type=? AND EXISTS(
                  SELECT 1 FROM jsonb_each_text(?::jsonb) f
                  WHERE coalesce((b.sync_clear_context->>f.key)::bigint,0)<f.value::bigint)
                """, actor, type, EventCodec.vectorJson(next));
        return next;
    }

    public static boolean covers(Map<String, Long> observed, VersionVector frontier) {
        var relation = new VersionVector(observed).compare(frontier);
        return relation == VersionVector.Relation.EQUAL || relation == VersionVector.Relation.AFTER;
    }
}
