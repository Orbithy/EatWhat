package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.EventCodec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Comparator;
import java.util.Collection;
import java.nio.charset.StandardCharsets;

/** Same-database catalog ledger access. Business projections remain in the existing public tables. */
public final class CatalogStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public record Identity(CatalogKind kind, UUID gid, long numericId, String originSite,
                           String authoritySite, UUID authorGid, UUID parentGid,
                           long revision, boolean tombstone, boolean rejected,
                           Map<String, Object> causalState) { }

    public record Candidate(UUID gid, long numericId, String sourceSite, UUID authorGid,
                            long revision, String contentHash, Map<String, Object> summary) { }

    /** Exact immutable evidence needed to relay a selected catalog head without inventing a revision. */
    public record ImmutableRevision(CatalogEvents.Action action, String sourceSite) { }

    public static UUID stableConflictId(CatalogKind kind, String keyHash) {
        Objects.requireNonNull(kind, "kind");
        if (keyHash == null || !keyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid catalog key hash");
        }
        return UUID.nameUUIDFromBytes(("eatwhat.catalog.conflict:" + kind.name() + ":" + keyHash)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static UUID stableHeadConflictId(CatalogKind kind, UUID objectGid) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectGid, "objectGid");
        return UUID.nameUUIDFromBytes(("eatwhat.catalog.head-conflict:" + kind.name() + ":" + objectGid)
                .getBytes(StandardCharsets.UTF_8));
    }

    public CatalogStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public JdbcTemplate jdbc() { return jdbc; }

    /** Serializes catalog projection, claim, conflict, and resolution writes for one database. */
    public void lockDomain() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('eatwhat.catalog.domain',0))", Object.class);
    }

    /** Shared natural-key lock used by candidate creation and conflict resolution. */
    public void lockNaturalKey(CatalogKind kind, String keyHash) {
        Objects.requireNonNull(kind);
        if (keyHash == null || !keyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid catalog key hash");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                kind.name() + ":" + keyHash);
    }

    /** Resolves and locks all known authors before taking the shared catalog-domain lock. */
    public void lockCandidateSubjects(CatalogKind kind, Collection<Candidate> candidates) {
        lockCandidateSubjects(kind, candidates, List.of());
    }

    /** Locks candidate owners and local cascade authors in one globally sorted subject pass. */
    public void lockCandidateSubjects(CatalogKind kind, Collection<Candidate> candidates,
                                      Collection<Long> additionalUserIds) {
        var userIds = new java.util.TreeSet<Long>();
        if (additionalUserIds != null) userIds.addAll(additionalUserIds);
        for (Candidate candidate : candidates) {
            if (candidate.authorGid() != null) addSubjectUserId(userIds, candidate.authorGid());
            Identity object = findByNumeric(kind, candidate.numericId());
            if (object != null && object.parentGid() != null) {
                Identity parent = findObject(CatalogKind.RESTAURANT, object.parentGid());
                if (parent != null && parent.authorGid() != null) addSubjectUserId(userIds, parent.authorGid());
            }
        }
        for (Long userId : userIds) {
            jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=? FOR UPDATE",
                    (rs, row) -> rs.getObject(1, UUID.class), userId);
        }
        lockDomain();
    }

    private void addSubjectUserId(Collection<Long> userIds, UUID subjectId) {
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=?",
                (rs, row) -> rs.getLong(1), subjectId).stream().findFirst().ifPresent(userIds::add);
    }

    public Identity lockObject(CatalogKind kind, UUID gid) {
        List<Identity> rows = jdbc.query("""
                SELECT kind,object_gid,numeric_id,origin_site,authority_site,author_gid,parent_gid,
                       revision,tombstone,rejected,causal_state::text
                FROM eatwhat_sync.catalog_object WHERE kind=? AND object_gid=? FOR UPDATE
                """, identityMapper(), kind.name(), gid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Identity findObject(CatalogKind kind, UUID gid) {
        List<Identity> rows = jdbc.query("""
                SELECT kind,object_gid,numeric_id,origin_site,authority_site,author_gid,parent_gid,
                       revision,tombstone,rejected,causal_state::text
                FROM eatwhat_sync.catalog_object WHERE kind=? AND object_gid=?
                """, identityMapper(), kind.name(), gid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Identity findByNumeric(CatalogKind kind, long numericId) {
        List<Identity> rows = jdbc.query("""
                SELECT kind,object_gid,numeric_id,origin_site,authority_site,author_gid,parent_gid,
                       revision,tombstone,rejected,causal_state::text
                FROM eatwhat_sync.catalog_object WHERE kind=? AND numeric_id=?
                """, identityMapper(), kind.name(), numericId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void markManaged(CatalogKind kind) {
        jdbc.update("INSERT INTO eatwhat_sync.catalog_managed_domain(kind) VALUES (?) ON CONFLICT(kind) DO NOTHING",
                kind.name());
    }

    public boolean isManaged(CatalogKind kind) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_managed_domain WHERE kind=?)", Boolean.class,
                kind.name()));
    }

    /** Records the exact object heads and authorization vector covered by a durable state bridge. */
    public void recordBackfillPublication(CatalogKind kind, UUID objectGid, UUID subjectGid,
                                          String peerSite, long consentEpoch,
                                          List<CatalogEvents.ConsentEpoch> consentEpochs, UUID bridgeEventId) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(objectGid);
        Objects.requireNonNull(subjectGid);
        Objects.requireNonNull(peerSite);
        Objects.requireNonNull(consentEpochs);
        Objects.requireNonNull(bridgeEventId);
        String epochJson = EventCodec.canonicalJson(JSON.writeValueAsString(consentEpochs));
        int changed = jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_backfill_publication
                  (kind,object_gid,subject_gid,peer_site,consent_epoch,object_revision,
                   published_heads,consent_epochs,bridge_event_id,published_at)
                SELECT o.kind,o.object_gid,?,?,?,o.revision,o.causal_state->'heads',?::jsonb,?,clock_timestamp()
                FROM eatwhat_sync.catalog_object o WHERE o.kind=? AND o.object_gid=?
                ON CONFLICT(kind,object_gid,subject_gid,peer_site) DO UPDATE SET
                  consent_epoch=EXCLUDED.consent_epoch,object_revision=EXCLUDED.object_revision,
                  published_heads=EXCLUDED.published_heads,consent_epochs=EXCLUDED.consent_epochs,
                  bridge_event_id=EXCLUDED.bridge_event_id,published_at=EXCLUDED.published_at
                """, subjectGid, peerSite, consentEpoch, epochJson, bridgeEventId, kind.name(), objectGid);
        if (changed != 1) throw new IllegalStateException("CATALOG_BACKFILL_PUBLICATION_OBJECT_MISSING");
    }

    public boolean backfillPublicationMatches(CatalogKind kind, UUID objectGid, UUID subjectGid,
                                               String peerSite, long consentEpoch,
                                               List<CatalogEvents.ConsentEpoch> consentEpochs) {
        String epochJson = EventCodec.canonicalJson(JSON.writeValueAsString(consentEpochs));
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM eatwhat_sync.catalog_backfill_publication p
                  JOIN eatwhat_sync.catalog_object o ON o.kind=p.kind AND o.object_gid=p.object_gid
                  WHERE p.kind=? AND p.object_gid=? AND p.subject_gid=? AND p.peer_site=?
                    AND p.consent_epoch=? AND p.object_revision=o.revision
                    AND p.published_heads=o.causal_state->'heads' AND p.consent_epochs=?::jsonb)
                """, Boolean.class, kind.name(), objectGid, subjectGid, peerSite, consentEpoch, epochJson));
    }

    public List<Candidate> activeCandidates(CatalogKind kind, UUID conflictId) {
        return jdbc.query("""
                SELECT c.object_gid,c.numeric_id,c.source_site,c.author_gid,c.revision,c.content_hash,c.summary::text
                FROM eatwhat_sync.catalog_candidate c
                JOIN eatwhat_sync.catalog_conflict x ON x.kind=c.kind AND x.key_hash=c.key_hash
                WHERE x.conflict_id=? AND c.kind=? AND c.status NOT IN ('REJECTED','SUPERSEDED')
                ORDER BY c.object_gid
                """, (rs, row) -> new Candidate(rs.getObject("object_gid", UUID.class),
                rs.getLong("numeric_id"), rs.getString("source_site"), rs.getObject("author_gid", UUID.class),
                rs.getLong("revision"), rs.getString("content_hash").trim(),
                JSON.readValue(rs.getString("summary"), Map.class)), conflictId, kind.name());
    }

    public record CandidateRevision(UUID gid, UUID operationId, long revision, String contentHash) { }

    public List<CandidateRevision> activeCandidateRevisions(CatalogKind kind, String keyHash) {
        return jdbc.query("""
                SELECT object_gid,candidate_id,revision,content_hash FROM eatwhat_sync.catalog_candidate
                WHERE kind=? AND key_hash=? AND status NOT IN ('REJECTED','SUPERSEDED') ORDER BY object_gid
                """, (rs, row) -> new CandidateRevision(rs.getObject("object_gid", UUID.class),
                rs.getObject("candidate_id", UUID.class), rs.getLong("revision"),
                rs.getString("content_hash").trim()), kind.name(), keyHash);
    }

    public List<UUID> disclosureSubjects(CatalogKind kind, UUID objectGid) {
        List<Identity> rows = jdbc.query("""
                SELECT kind,object_gid,numeric_id,origin_site,authority_site,author_gid,parent_gid,
                       revision,tombstone,rejected,causal_state::text
                FROM eatwhat_sync.catalog_object WHERE kind=? AND object_gid=?
                """, identityMapper(), kind.name(), objectGid);
        if (rows.isEmpty()) return List.of();
        var values = new java.util.TreeSet<UUID>(Comparator.comparing(UUID::toString));
        Identity identity = rows.getFirst();
        if (identity.authorGid() != null) values.add(identity.authorGid());
        if (identity.parentGid() != null && kind == CatalogKind.FOOD) {
            Identity parent = findObject(CatalogKind.RESTAURANT, identity.parentGid());
            if (parent != null && parent.authorGid() != null) values.add(parent.authorGid());
        }
        return List.copyOf(values);
    }

    public UUID latestOperationId(CatalogKind kind, UUID objectGid) {
        List<UUID> ids = jdbc.query("""
                SELECT operation_id FROM eatwhat_sync.catalog_revision
                WHERE kind=? AND object_gid=? ORDER BY revision DESC,created_at DESC LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class), kind.name(), objectGid);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public ImmutableRevision readRevision(UUID operationId) {
        List<ImmutableRevision> rows = jdbc.query("""
                SELECT kind,object_gid,revision,expected_revision,operation,authority_site,author_gid,parent_gid,
                  numeric_id,identity_origin,payload::text,content_hash,operation_id,dot_site,dot_counter,
                  context::text,consent_epochs::text,NULL::text AS source_key,resolution_proof::text,source_site
                FROM eatwhat_sync.catalog_revision WHERE operation_id=?
                """, (rs, row) -> {
            CatalogEvents.Action action = new CatalogEvents.Action(
                    CatalogKind.valueOf(rs.getString("kind")), rs.getObject("object_gid", UUID.class),
                    rs.getLong("revision"), rs.getLong("expected_revision"),
                    CatalogOperation.valueOf(rs.getString("operation")), rs.getString("authority_site"),
                    rs.getObject("author_gid", UUID.class), rs.getObject("parent_gid", UUID.class),
                    rs.getLong("numeric_id"), rs.getString("identity_origin"),
                    JSON.readValue(rs.getString("payload"), Map.class), rs.getString("content_hash").trim(),
                    rs.getObject("operation_id", UUID.class), rs.getString("dot_site"), rs.getLong("dot_counter"),
                    JSON.readValue(rs.getString("context"), JSON.getTypeFactory()
                            .constructMapType(Map.class, String.class, Long.class)),
                    JSON.readValue(rs.getString("consent_epochs"), JSON.getTypeFactory()
                            .constructCollectionType(List.class, CatalogEvents.ConsentEpoch.class)),
                    rs.getString("source_key"), rs.getString("resolution_proof") == null ? null
                            : JSON.readValue(rs.getString("resolution_proof"), CatalogEvents.ResolutionProof.class));
            return new ImmutableRevision(action, rs.getString("source_site"));
        }, operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Returns every consent principal attached to an immutable revision and its food parent. */
    public List<UUID> subjectsForOperation(UUID operationId) {
        List<RevisionIdentity> rows = jdbc.query("""
                SELECT kind,object_gid FROM eatwhat_sync.catalog_revision WHERE operation_id=?
                """, (rs, row) -> new RevisionIdentity(CatalogKind.valueOf(rs.getString(1)),
                rs.getObject(2, UUID.class)), operationId);
        if (rows.isEmpty()) return List.of();
        return disclosureSubjects(rows.getFirst().kind(), rows.getFirst().gid());
    }

    public boolean hasOperation(UUID operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision WHERE operation_id=?)",
                Boolean.class, operationId));
    }

    private record RevisionIdentity(CatalogKind kind, UUID gid) { }

    public void markRejected(CatalogKind kind, UUID objectGid) {
        jdbc.update("UPDATE eatwhat_sync.catalog_object SET rejected=true,updated_at=clock_timestamp() WHERE kind=? AND object_gid=?",
                kind.name(), objectGid);
    }

    public UUID globalId(EntityType type, long numericId) {
        List<UUID> ids = jdbc.query("""
                SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type=? AND numeric_id=?
                """, (rs, row) -> rs.getObject(1, UUID.class), type.identityType().tableName(), numericId);
        if (ids.isEmpty()) throw new IllegalStateException("CATALOG_IDENTITY_UNAVAILABLE");
        return ids.getFirst();
    }

    public String identityOrigin(EntityType type, long numericId) {
        List<String> origins = jdbc.query("""
                SELECT origin_site FROM eatwhat_identity.entity_identity WHERE entity_type=? AND numeric_id=?
                """, (rs, row) -> rs.getString(1), type.identityType().tableName(), numericId);
        if (origins.isEmpty() || origins.getFirst() == null) throw new IllegalStateException("CATALOG_IDENTITY_UNAVAILABLE");
        return origins.getFirst();
    }

    public Long numericId(EntityType type, UUID gid) {
        List<Long> ids = jdbc.query("""
                SELECT numeric_id FROM eatwhat_identity.entity_identity WHERE entity_type=? AND global_id=?
                """, (rs, row) -> rs.getLong(1), type.identityType().tableName(), gid);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public UUID userGid(Long userId) {
        return userId == null ? null : globalId(EntityType.USERS, userId);
    }

    /** Reads canonical public fields without exposing computed URLs, counters, or private data. */
    public Map<String, Object> readPublic(CatalogKind kind, long numericId) {
        List<Map<String, Object>> rows = jdbc.query(publicPayloadSql(kind), (rs, row) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            switch (kind) {
                case HUB -> {
                    p.put("name", rs.getString("name"));
                    p.put("centerWgs", point(rs.getObject("center_lng"), rs.getObject("center_lat")));
                    p.put("centerGcj", point(rs.getObject("gcj_lng"), rs.getObject("gcj_lat")));
                    p.put("boundaryGeoJson", rs.getString("boundary_json"));
                    Long campusId = nullableLong(rs, "campus_id");
                    if (campusId != null) p.put("campusId", campusId);
                }
                case RESTAURANT -> {
                    p.put("name", rs.getString("name"));
                    p.put("address", rs.getString("address"));
                    p.put("cityId", nullableLong(rs, "city_id"));
                    p.put("poi", rs.getString("poi"));
                    p.put("locationWgs", point(rs.getObject("location_lng"), rs.getObject("location_lat")));
                    p.put("locationGcj", point(rs.getObject("gcj_lng"), rs.getObject("gcj_lat")));
                    p.put("hubGid", rs.getObject("hub_gid") == null ? null : rs.getString("hub_gid"));
                    p.put("pictureKeys", stringList(rs.getArray("picture_url")));
                }
                case FOOD -> {
                    p.put("restaurantGid", rs.getString("restaurant_gid"));
                    p.put("name", rs.getString("name"));
                    p.put("description", rs.getString("description"));
                    p.put("price", rs.getBigDecimal("price"));
                    p.put("category", rs.getString("category"));
                    p.put("pictureKeys", stringList(rs.getArray("picture_url")));
                    // Defaults stay out of the payload; see CatalogPayload.optionalFields.
                    List<String> mealTimes = stringList(rs.getArray("meal_times"));
                    if (!mealTimes.isEmpty()) p.put("mealTimes", mealTimes);
                    if (rs.getString("stage_color") != null) {
                        p.put("stageColor", rs.getString("stage_color"));
                        p.put("stageAlternates", stringList(rs.getArray("stage_alternates")));
                    }
                    if (rs.getBigDecimal("cover_center_x") != null) {
                        p.put("coverCrop", Map.of("centerX", rs.getBigDecimal("cover_center_x"),
                                "centerY", rs.getBigDecimal("cover_center_y"), "diameter", rs.getBigDecimal("cover_diameter")));
                    }
                    if (rs.getBoolean("remove_background")) p.put("removeBackground", true);
                }
                case SYSTEM_TAG, CUSTOM_TAG -> {
                    p.put("name", rs.getString("name"));
                    p.put("normalizedName", rs.getString("normalized_name"));
                    p.put("tagType", rs.getString("tag_type"));
                    if (kind == CatalogKind.CUSTOM_TAG) p.put("ownerGid", rs.getString("owner_gid"));
                }
                case ACTIVITY -> {
                    p.put("code", rs.getString("code"));
                    p.put("name", rs.getString("name"));
                    p.put("description", rs.getString("description"));
                    p.put("coverUrl", rs.getString("cover_url"));
                    p.put("status", rs.getString("status"));
                    p.put("startsAt", nullableLong(rs, "starts_at_ms"));
                    p.put("endsAt", nullableLong(rs, "ends_at_ms"));
                    p.put("sort", rs.getInt("sort"));
                    p.put("requireLocation", rs.getBoolean("require_location"));
                    p.put("maxPictures", rs.getInt("max_pictures"));
                    p.put("fieldSchema", JSON.readValue(rs.getString("field_schema"), List.class));
                }
            }
            return CatalogPayload.validate(kind, p);
        }, numericId);
        if (rows.isEmpty()) return null;
        return rows.getFirst();
    }

    public List<Map<String, Object>> readPublicBatch(CatalogKind kind, int limit) {
        String sql = switch (kind) {
            case HUB -> "SELECT id FROM public.hub ORDER BY id LIMIT ?";
            case RESTAURANT -> "SELECT id FROM public.restaurant ORDER BY id LIMIT ?";
            case FOOD -> "SELECT id FROM public.foods ORDER BY id LIMIT ?";
            case SYSTEM_TAG -> "SELECT id FROM public.food_tags WHERE tag_type='system' ORDER BY id LIMIT ?";
            case CUSTOM_TAG -> "SELECT id FROM public.food_tags WHERE tag_type='custom' ORDER BY id LIMIT ?";
            case ACTIVITY -> "SELECT id FROM public.activities ORDER BY id LIMIT ?";
        };
        List<Long> ids = jdbc.query(sql, (rs, row) -> rs.getLong(1), limit);
        List<Map<String, Object>> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            var payload = readPublic(kind, id);
            if (payload != null) result.add(payload);
        }
        return List.copyOf(result);
    }

    public void insertObject(Identity identity) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_object(kind,object_gid,numeric_id,origin_site,authority_site,
                  author_gid,parent_gid,revision,tombstone,rejected,managed,causal_state)
                VALUES (?,?,?,?,?,?,?,?,?,?,true,?::jsonb)
                """, identity.kind().name(), identity.gid(), identity.numericId(), identity.originSite(),
                identity.authoritySite(), identity.authorGid(), identity.parentGid(), identity.revision(),
                identity.tombstone(), identity.rejected(), JSON.writeValueAsString(identity.causalState()));
    }

    public void updateObject(Identity identity) {
        int changed = jdbc.update("""
                UPDATE eatwhat_sync.catalog_object SET authority_site=?,author_gid=?,parent_gid=?,revision=?,
                  tombstone=?,rejected=?,causal_state=?::jsonb,updated_at=clock_timestamp()
                WHERE kind=? AND object_gid=? AND numeric_id=? AND origin_site=?
                """, identity.authoritySite(), identity.authorGid(), identity.parentGid(), identity.revision(),
                identity.tombstone(), identity.rejected(), JSON.writeValueAsString(identity.causalState()),
                identity.kind().name(), identity.gid(), identity.numericId(), identity.originSite());
        if (changed != 1) throw new IllegalStateException("CATALOG_IDENTITY_CONFLICT");
    }

    public void appendRevision(CatalogEvents.Action action, String sourceSite) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.catalog_revision(operation_id,kind,object_gid,revision,expected_revision,
                  operation,source_site,authority_site,author_gid,parent_gid,numeric_id,identity_origin,
                  dot_site,dot_counter,context,consent_epochs,tombstone,payload,content_hash,resolution_proof)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,?,?::jsonb)
                """, action.operationId(), action.kind().name(), action.objectGid(), action.revision(),
                action.expectedRevision(), action.operation().name(), sourceSite, action.authoritySite(),
                action.authorGid(), action.parentGid(), action.numericId(), action.identityOrigin(), action.dotSite(),
                action.dotCounter(), JSON.writeValueAsString(action.context()),
                JSON.writeValueAsString(action.consentEpochs()), action.operation() == CatalogOperation.DELETE,
                JSON.writeValueAsString(action.canonicalPayload()), action.contentHash(),
                action.resolutionProof() == null ? null : JSON.writeValueAsString(action.resolutionProof()));
    }

    public boolean terminal(CatalogKind kind, UUID gid) {
        Identity value = findObject(kind, gid);
        return value != null && (value.tombstone() || value.rejected());
    }

    public boolean parentRejected(UUID gid) {
        List<Boolean> values = jdbc.query("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object
                  WHERE object_gid=? AND (tombstone OR rejected))
                """, (rs, row) -> rs.getBoolean(1), gid);
        return !values.isEmpty() && values.getFirst();
    }

    public List<Long> boundRestaurantIds(long hubId) {
        return jdbc.query("SELECT id FROM public.restaurant WHERE hub_id=? ORDER BY id",
                (rs, row) -> rs.getLong(1), hubId);
    }

    public void lockPublicRow(CatalogKind kind, long numericId) {
        String table = switch (kind) {
            case HUB -> "hub";
            case RESTAURANT -> "restaurant";
            case FOOD -> "foods";
            case SYSTEM_TAG, CUSTOM_TAG -> "food_tags";
            case ACTIVITY -> "activities";
        };
        jdbc.query("SELECT id FROM public." + table + " WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), numericId);
    }

    private RowMapper<Identity> identityMapper() {
        return (rs, row) -> new Identity(CatalogKind.valueOf(rs.getString("kind")),
                rs.getObject("object_gid", UUID.class), rs.getLong("numeric_id"), rs.getString("origin_site"),
                rs.getString("authority_site"), rs.getObject("author_gid", UUID.class),
                rs.getObject("parent_gid", UUID.class), rs.getLong("revision"), rs.getBoolean("tombstone"),
                rs.getBoolean("rejected"), JSON.readValue(rs.getString("causal_state"), Map.class));
    }

    private static String publicPayloadSql(CatalogKind kind) {
        return switch (kind) {
            case HUB -> """
                    SELECT h.name,ST_X(h.center) center_lng,ST_Y(h.center) center_lat,h.gcj_lng,h.gcj_lat,
                           ST_AsGeoJSON(h.boundary) boundary_json,h.campus_id FROM public.hub h WHERE h.id=?
                    """;
            case RESTAURANT -> """
                    SELECT r.name,r.address,r.city_id,r.poi,ST_X(r.location::geometry) location_lng,
                           ST_Y(r.location::geometry) location_lat,r.gcj_lng,r.gcj_lat,
                           hi.global_id::text hub_gid,r.picture_url FROM public.restaurant r
                    LEFT JOIN eatwhat_identity.entity_identity hi ON hi.entity_type='hub' AND hi.numeric_id=r.hub_id
                    WHERE r.id=?
                    """;
            case FOOD -> """
                    SELECT ri.global_id::text restaurant_gid,f.name,f.description,f.price,f.category,f.picture_url,
                           f.meal_times,f.stage_color,f.stage_alternates,f.cover_center_x,f.cover_center_y,
                           f.cover_diameter,f.remove_background
                    FROM public.foods f JOIN eatwhat_identity.entity_identity ri
                      ON ri.entity_type='restaurant' AND ri.numeric_id=f.restaurant_id WHERE f.id=?
                    """;
            case SYSTEM_TAG, CUSTOM_TAG -> """
                    SELECT t.name,t.normalized_name,t.tag_type,oi.global_id::text owner_gid
                    FROM public.food_tags t LEFT JOIN eatwhat_identity.entity_identity oi
                      ON oi.entity_type='users' AND oi.numeric_id=t.owner_id WHERE t.id=?
                    """;
            case ACTIVITY -> """
                    SELECT a.code,a.name,a.description,a.cover_url,a.status,
                           (extract(epoch FROM a.starts_at)*1000)::bigint starts_at_ms,
                           (extract(epoch FROM a.ends_at)*1000)::bigint ends_at_ms,
                           a.sort,a.require_location,a.max_pictures,a.field_schema::text field_schema
                    FROM public.activities a WHERE a.id=?
                    """;
        };
    }

    private static Map<String, Object> point(Object lng, Object lat) {
        return Map.of("lng", decimal(lng), "lat", decimal(lat));
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        throw new IllegalStateException("CATALOG_GEOMETRY_UNAVAILABLE");
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static List<String> stringList(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        List<String> result = new ArrayList<>(values.length);
        for (Object value : values) result.add(String.valueOf(value));
        return List.copyOf(result);
    }
}
