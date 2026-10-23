package you.v50to.eatwhat.sync.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent per-subject privacy causal state, separate from account snapshots. */
public final class PrivacyStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public record Snapshot(boolean following, boolean follower, VersionVector vector,
                           VersionVector followingVector, VersionVector followerVector,
                           VersionVector followingFalseContext, VersionVector followerFalseContext,
                           boolean managed, boolean localDirty, long activeEpoch, long publishedEpoch) { }

    public PrivacyStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Boolean> readPublic(long uid) {
        return jdbc.query("SELECT following,follower FROM public.privacy WHERE account_id=?",
                (row, index) -> Map.of("following", row.getBoolean(1), "follower", row.getBoolean(2)), uid)
                .stream().findFirst().orElseGet(() -> Map.of("following", true, "follower", true));
    }

    public Snapshot ensure(UUID subject, Map<String, Boolean> initial) {
        boolean following = initial.getOrDefault("following", true);
        boolean follower = initial.getOrDefault("follower", true);
        VersionVector followingVector = following ? VersionVector.empty()
                : new VersionVector(Map.of(followingBase(subject), 1L));
        VersionVector followerVector = follower ? VersionVector.empty()
                : new VersionVector(Map.of(followerBase(subject), 1L));
        VersionVector initialVector = merge(followingVector, followerVector);
        jdbc.update("""
                INSERT INTO eatwhat_sync.privacy_domain(subject_gid,following_value,follower_value,vector,
                  following_vector,follower_vector,following_false_context,follower_false_context)
                VALUES(?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb) ON CONFLICT(subject_gid) DO NOTHING
                """, subject, following, follower, EventCodec.vectorJson(initialVector),
                EventCodec.vectorJson(followingVector), EventCodec.vectorJson(followerVector),
                EventCodec.vectorJson(following ? VersionVector.empty() : followingVector),
                EventCodec.vectorJson(follower ? VersionVector.empty() : followerVector));
        return snapshot(subject);
    }

    public Snapshot snapshot(UUID subject) {
        return jdbc.query("""
                SELECT following_value,follower_value,vector::text,following_vector::text,follower_vector::text,
                  following_false_context::text,follower_false_context::text,managed,local_dirty,active_epoch,published_epoch
                FROM eatwhat_sync.privacy_domain WHERE subject_gid=?
                """, (row, index) -> new Snapshot(row.getBoolean(1), row.getBoolean(2),
                vector(row.getString(3)), vector(row.getString(4)), vector(row.getString(5)),
                vector(row.getString(6)), vector(row.getString(7)), row.getBoolean(8), row.getBoolean(9),
                row.getLong(10), row.getLong(11)), subject)
                .stream().findFirst().orElse(null);
    }

    public Snapshot beginEpoch(UUID subject, long epoch) {
        Snapshot current = snapshot(subject);
        if (current == null) throw new IllegalStateException("PRIVACY_DOMAIN_REQUIRED");
        if (current.activeEpoch() != epoch) jdbc.update("""
                UPDATE eatwhat_sync.privacy_domain SET active_epoch=?,revision=revision+1,
                  updated_at=clock_timestamp() WHERE subject_gid=?
                """, epoch, subject);
        return snapshot(subject);
    }

    private static String followingBase(UUID subject) {
        return you.v50to.eatwhat.sync.EpochDotSite.of("privacy-following", subject.toString(), 0);
    }

    private static String followerBase(UUID subject) {
        return you.v50to.eatwhat.sync.EpochDotSite.of("privacy-follower", subject.toString(), 0);
    }

    public void apply(UUID subject, String field, boolean value, VersionVector vector, VersionVector falseContext) {
        String column = switch (field) { case "following" -> "following"; case "follower" -> "follower"; default -> throw new IllegalArgumentException(); };
        String valueColumn = column + "_value";
        String vectorColumn = column + "_vector";
        String falseColumn = column + "_false_context";
        jdbc.update("UPDATE eatwhat_sync.privacy_domain SET " + valueColumn + "=?," + vectorColumn + "=?::jsonb,"
                        + falseColumn + "=?::jsonb,revision=revision+1,updated_at=clock_timestamp() WHERE subject_gid=?",
                value, EventCodec.vectorJson(vector), EventCodec.vectorJson(falseContext), subject);
    }

    public void mergeVector(UUID subject, VersionVector vector, boolean localDirty) {
        jdbc.update("UPDATE eatwhat_sync.privacy_domain SET vector=?::jsonb,local_dirty=?,revision=revision+1,updated_at=clock_timestamp() WHERE subject_gid=?",
                EventCodec.vectorJson(vector), localDirty, subject);
    }

    public void markPublished(UUID subject, long epoch) {
        jdbc.update("UPDATE eatwhat_sync.privacy_domain SET local_dirty=false,published_epoch=?,revision=revision+1,updated_at=clock_timestamp() WHERE subject_gid=?",
                epoch, subject);
    }

    public void insertOperation(UUID eventId, PrivacyEvents.Operation operation) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.privacy_operation(event_id,subject_gid,epoch,origin_site,dot_counter,context,
                    following_value,following_context,follower_value,follower_context,state_bridge)
                VALUES(?,?,?,?,?,?::jsonb,? ,?::jsonb,? ,?::jsonb,?::jsonb)
                """, eventId, operation.subject(), operation.epoch(), operation.dotSite(), operation.dotCounter(),
                EventCodec.canonicalJson(JSON.writeValueAsString(operation.contexts())), operation.changes().get("following"),
                EventCodec.vectorJson(new VersionVector(operation.contexts().getOrDefault("following", Map.of()))),
                operation.changes().get("follower"),
                EventCodec.vectorJson(new VersionVector(operation.contexts().getOrDefault("follower", Map.of()))),
                EventCodec.canonicalJson(JSON.writeValueAsString(operation.stateBridge())));
    }

    /** All causally maximal true witnesses, including witnesses relayed by state bridges. */
    public java.util.List<PrivacyEvents.Witness> trueWitnesses(UUID subject, String field) {
        String valueColumn = field.equals("following") ? "following_value" : "follower_value";
        String contextColumn = field.equals("following") ? "following_context" : "follower_context";
        var candidates = new java.util.LinkedHashMap<UUID, PrivacyEvents.Witness>();
        jdbc.query("SELECT event_id,origin_site,dot_counter," + valueColumn + "," + contextColumn + "::text,state_bridge::text "
                        + "FROM eatwhat_sync.privacy_operation WHERE subject_gid=?",
                (RowCallbackHandler) row -> {
                    UUID eventId = row.getObject(1, UUID.class);
                    String origin = row.getString(2);
                    long counter = row.getLong(3);
                    Boolean value = row.getObject(4, Boolean.class);
                    String contextJson = row.getString(5);
                    if (Boolean.TRUE.equals(value)) {
                        VersionVector context = EventCodec.vectorFromJson(contextJson);
                        addWitness(candidates, new PrivacyEvents.Witness(eventId,
                                withDot(context, origin, counter).counters()));
                    }
                    JsonNode bridge = JSON.readTree(row.getString(6));
                    if (bridge.has(field)) {
                        JsonNode witnesses = bridge.path(field).path("trueWitnesses");
                        for (JsonNode witness : witnesses) {
                            UUID id = UUID.fromString(witness.path("id").stringValue());
                            Map<String, Long> vector = EventCodec.vectorFromJson(witness.path("vector").toString()).counters();
                            addWitness(candidates, new PrivacyEvents.Witness(id, vector));
                        }
                    }
                }, subject);
        var all = new java.util.ArrayList<>(candidates.values());
        return all.stream().filter(candidate -> all.stream().noneMatch(other -> !other.id().equals(candidate.id())
                && new VersionVector(other.vector()).compare(new VersionVector(candidate.vector()))
                == VersionVector.Relation.AFTER)).sorted(java.util.Comparator.comparing(w -> w.id().toString())).toList();
    }

    public Map<String, PrivacyEvents.BridgeState> stateBridge(UUID subject, Snapshot snapshot) {
        return Map.of(
                "following", new PrivacyEvents.BridgeState(snapshot.following(), snapshot.followingVector().counters(),
                        snapshot.followingFalseContext().counters(), trueWitnesses(subject, "following")),
                "follower", new PrivacyEvents.BridgeState(snapshot.follower(), snapshot.followerVector().counters(),
                        snapshot.followerFalseContext().counters(), trueWitnesses(subject, "follower")));
    }

    private static VersionVector withDot(VersionVector context, String site, long counter) {
        Map<String, Long> vector = new LinkedHashMap<>(context.counters());
        vector.merge(site, counter, Math::max);
        return new VersionVector(vector);
    }

    private static void addWitness(Map<UUID, PrivacyEvents.Witness> witnesses,
                                   PrivacyEvents.Witness candidate) {
        PrivacyEvents.Witness previous = witnesses.putIfAbsent(candidate.id(), candidate);
        if (previous != null && !previous.vector().equals(candidate.vector())) {
            throw new IllegalArgumentException("PRIVACY_WITNESS_IDENTITY_CONFLICT");
        }
    }

    public void upsertPublic(long uid, boolean following, boolean follower) {
        jdbc.update("""
                INSERT INTO public.privacy(account_id,following,follower) VALUES(?,?,?)
                ON CONFLICT(account_id) DO UPDATE SET following=EXCLUDED.following,follower=EXCLUDED.follower
                """, uid, following, follower);
    }

    static VersionVector vector(String json) { return EventCodec.vectorFromJson(json); }
    static VersionVector merge(VersionVector a, VersionVector b) {
        Map<String, Long> merged = new LinkedHashMap<>(a.counters());
        b.counters().forEach((site, counter) -> merged.merge(site, counter, Math::max));
        return new VersionVector(merged);
    }
}
