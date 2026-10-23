package you.v50to.eatwhat.sync.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.VersionVector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Same-database profile baseline and per-group causal state. */
public final class ProfileStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public record Baseline(UUID id, String origin, Map<String, Object> value, String hash) { }
    public record FieldState(Object value, VersionVector vector, UUID headId, boolean localDirty) { }

    public ProfileStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Baseline> baseline(UUID subject) {
        return jdbc.query("""
                SELECT baseline_id,baseline_origin,baseline_value::text,baseline_hash
                FROM eatwhat_sync.profile_domain WHERE subject_gid=?
                """, (row, index) -> new Baseline(row.getObject(1, UUID.class), row.getString(2),
                map(row.getString(3)), row.getString(4).trim()), subject).stream().findFirst();
    }

    public static Map<String,Object> emptyProfile() {
        Map<String,Object> value=new LinkedHashMap<>();
        for(String field:java.util.List.of("avatar","gender","birthday","signature"))value.put(field,null);
        Map<String,Object> hometown=new LinkedHashMap<>();hometown.put("provinceId",null);hometown.put("cityId",null);value.put("hometown",hometown);
        return java.util.Collections.unmodifiableMap(value);
    }
    public Baseline createIndependentBaseline(UUID subject) {
        var value=emptyProfile();return installBaseline(subject,
            UUID.nameUUIDFromBytes(("profile-v2:"+subject).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "profile-v2",value,baselineHash(value));
    }

    public Baseline createCampusBaseline(UUID subject, String site, Map<String, Object> value) {
        String canonical = EventCodec.canonicalJson(JSON.writeValueAsString(value));
        String hash = EventCodec.sha256(canonical);
        UUID baselineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO eatwhat_sync.profile_domain(subject_gid,baseline_id,baseline_origin,baseline_value,baseline_hash,vector)
                VALUES(?,?,?,?::jsonb,?,'{}'::jsonb) ON CONFLICT(subject_gid) DO NOTHING
                """, subject, baselineId, site, canonical, hash);
        return baseline(subject).orElseThrow(() -> new IllegalStateException("PROFILE_BASELINE_UNAVAILABLE"));
    }

    public Baseline installBaseline(UUID subject, UUID baselineId, String origin, Map<String, Object> value,
                                    String expectedHash) {
        String canonical = EventCodec.canonicalJson(JSON.writeValueAsString(value));
        String actualHash = EventCodec.sha256(canonical);
        if (!actualHash.equals(expectedHash)) throw new IllegalArgumentException("PROFILE_BASELINE_HASH_MISMATCH");
        jdbc.update("""
                INSERT INTO eatwhat_sync.profile_domain(subject_gid,baseline_id,baseline_origin,baseline_value,baseline_hash,vector)
                VALUES(?,?,?,?::jsonb,?,'{}'::jsonb) ON CONFLICT(subject_gid) DO NOTHING
                """, subject, baselineId, origin, canonical, actualHash);
        Baseline existing = baseline(subject).orElseThrow(() -> new IllegalStateException("PROFILE_BASELINE_UNAVAILABLE"));
        if (!existing.id().equals(baselineId) || !existing.origin().equals(origin)
                || !existing.hash().equals(actualHash) || !existing.value().equals(value)) {
            throw new IllegalStateException("PROFILE_BASELINE_CONFLICT");
        }
        return existing;
    }

    public String baselineHash(Map<String, Object> value) {
        return EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(value)));
    }

    public Map<String, Object> readPublicProfile(long userId) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                  'avatar',u.avatar,
                  'gender',i.gender,
                  'birthday',i.birthday,
                  'signature',i.signature,
                  'hometown',jsonb_build_object('provinceId',i.hometown_province_id,'cityId',i.hometown_city_id))
                FROM public.users u LEFT JOIN public.user_info i ON i.id=u.id WHERE u.id=?
                """, (row, index) -> map(row.getString(1)), userId);
    }

    public FieldState field(UUID subject, String group, Object defaultValue) {
        return jdbc.query("""
                SELECT value::text,applied_vector::text,head_id,local_dirty FROM eatwhat_sync.profile_field_state
                WHERE subject_gid=? AND field_group=?
                """, (row, index) -> new FieldState(JSON.readValue(row.getString(1), Object.class),
                EventCodec.vectorFromJson(row.getString(2)), row.getObject(3, UUID.class), row.getBoolean(4)), subject, group)
                .stream().findFirst().orElseGet(() -> new FieldState(defaultValue, VersionVector.empty(), null, false));
    }

    public java.util.List<ProfileEvents.Head> fieldHeads(UUID subject, String group, Object defaultValue) {
        var rows = jdbc.query("SELECT head_set::text FROM eatwhat_sync.profile_field_state WHERE subject_gid=? AND field_group=?",
                (row, index) -> row.getString(1), subject, group);
        if (!rows.isEmpty()) {
            var result = new java.util.ArrayList<ProfileEvents.Head>();
            for (var head : JSON.readTree(rows.getFirst())) {
                result.add(new ProfileEvents.Head(UUID.fromString(head.path("id").stringValue()),
                        JSON.convertValue(head.get("value"), Object.class),
                        EventCodec.vectorFromJson(head.path("vector").toString()).counters()));
            }
            if (!result.isEmpty()) return java.util.List.copyOf(result);
        }
        FieldState current = field(subject, group, defaultValue);
        if (current.headId() == null) return java.util.List.of();
        return java.util.List.of(new ProfileEvents.Head(current.headId(), current.value(), current.vector().counters()));
    }

    public VersionVector beginEpoch(UUID subject, long epoch) {
        Long active = jdbc.queryForObject("SELECT active_epoch FROM eatwhat_sync.profile_domain WHERE subject_gid=? FOR UPDATE",
                Long.class, subject);
        if (active == null) throw new IllegalStateException("PROFILE_BASELINE_REQUIRED");
        if (active != epoch) {
            jdbc.update("UPDATE eatwhat_sync.profile_domain SET active_epoch=?,revision=revision+1 WHERE subject_gid=?",
                    epoch, subject);
        }
        return domainVector(subject);
    }

    public VersionVector domainVector(UUID subject) {
        return jdbc.query("SELECT vector::text FROM eatwhat_sync.profile_domain WHERE subject_gid=?",
                (row, index) -> EventCodec.vectorFromJson(row.getString(1)), subject).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("PROFILE_BASELINE_REQUIRED"));
    }

    public void setDomainVector(UUID subject, VersionVector vector) {
        jdbc.update("UPDATE eatwhat_sync.profile_domain SET vector=?::jsonb,revision=revision+1 WHERE subject_gid=?",
                EventCodec.vectorJson(vector), subject);
    }

    public void applyPublicValue(long userId, String group, Object value) {
        switch (group) {
            case "avatar" -> jdbc.update("UPDATE public.users SET avatar=? WHERE id=?", value, userId);
            case "gender" -> {
                ensureUserInfo(userId);
                jdbc.update("UPDATE public.user_info SET gender=? WHERE id=?", value, userId);
            }
            case "birthday" -> {
                ensureUserInfo(userId);
                jdbc.update("UPDATE public.user_info SET birthday=?::date WHERE id=?", value, userId);
            }
            case "signature" -> {
                ensureUserInfo(userId);
                jdbc.update("UPDATE public.user_info SET signature=? WHERE id=?", value, userId);
            }
            case "hometown" -> {
                ensureUserInfo(userId);
                @SuppressWarnings("unchecked") Map<String, Object> town = (Map<String, Object>) value;
                validateHometownPair(town);
                jdbc.update("UPDATE public.user_info SET hometown_province_id=?::integer,hometown_city_id=?::integer WHERE id=?",
                        town.get("provinceId"), town.get("cityId"), userId);
            }
            default -> throw new IllegalArgumentException("Unsupported profile field");
        }
    }

    public void validateHometownPair(Map<String, Object> town) {
        Object province = town.get("provinceId");
        Object city = town.get("cityId");
        if (province == null || city == null) return;
        if (!hometownPairMatches(((Number) province).intValue(), ((Number) city).intValue())) {
            throw new IllegalArgumentException("PROFILE_HOMETOWN_CITY_PROVINCE_MISMATCH");
        }
    }

    public boolean hometownPairMatches(int provinceId, int cityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.cities WHERE id=? AND province_id=?)",
                Boolean.class, cityId, provinceId));
    }

    private void ensureUserInfo(long userId) {
        jdbc.update("INSERT INTO public.user_info(id) VALUES(?) ON CONFLICT(id) DO NOTHING", userId);
    }

    public void insertOperation(UUID eventId, ProfileEvents.Operation operation) {
        jdbc.update("""
                INSERT INTO eatwhat_sync.profile_operation(event_id,subject_gid,epoch,baseline_id,origin_site,dot_counter,context,changes,head_bridge,resolution_bridge,resolution)
                VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb)
                """, eventId, operation.subject(), operation.epoch(), operation.baselineId(), operation.dotSite(),
                operation.dotCounter(), EventCodec.canonicalJson(JSON.writeValueAsString(operation.contexts())),
                EventCodec.canonicalJson(JSON.writeValueAsString(operation.changes())),
                EventCodec.canonicalJson(JSON.writeValueAsString(operation.headBridge())),
                EventCodec.canonicalJson(JSON.writeValueAsString(operation.resolutionBridge())),
                operation.resolution() == null ? null : EventCodec.canonicalJson(JSON.writeValueAsString(operation.resolution())));
    }

    public void insertInitialFieldValues(UUID subject, Map<String, Object> value) {
        for (String group : value.keySet()) {
            Object groupValue = value.get(group);
            if (group.equals("hometown")) {
                applyField(subject, group, groupValue, VersionVector.empty(), null);
            } else {
                applyField(subject, group, groupValue, VersionVector.empty(), null);
            }
        }
    }

    public void applyField(UUID subject, String group, Object value, VersionVector vector, UUID headId) {
        String json = EventCodec.canonicalJson(JSON.writeValueAsString(value));
        String heads = headId == null ? "[]" : EventCodec.canonicalJson(JSON.writeValueAsString(
                java.util.List.of(new ProfileEvents.Head(headId, value, vector.counters()))));
        jdbc.update("""
                INSERT INTO eatwhat_sync.profile_field_state(subject_gid,field_group,value,applied_vector,head_id,head_set)
                VALUES(?,?,?::jsonb,?::jsonb,?,?::jsonb)
                ON CONFLICT(subject_gid,field_group) DO UPDATE SET value=EXCLUDED.value,
                  applied_vector=EXCLUDED.applied_vector,head_id=EXCLUDED.head_id,head_set=EXCLUDED.head_set,local_dirty=false
                """, subject, group, json, EventCodec.vectorJson(vector), headId, heads);
    }

    public void applyFieldHeads(UUID subject, String group, Object value, VersionVector vector,
                                java.util.List<ProfileEvents.Head> heads) {
        String json = EventCodec.canonicalJson(JSON.writeValueAsString(value));
        String headJson = EventCodec.canonicalJson(JSON.writeValueAsString(heads));
        UUID representative = heads.stream().map(ProfileEvents.Head::id).min(UUID::compareTo).orElse(null);
        jdbc.update("""
                INSERT INTO eatwhat_sync.profile_field_state(subject_gid,field_group,value,applied_vector,head_id,head_set)
                VALUES(?,?,?::jsonb,?::jsonb,?,?::jsonb)
                ON CONFLICT(subject_gid,field_group) DO UPDATE SET value=EXCLUDED.value,
                  applied_vector=EXCLUDED.applied_vector,head_id=EXCLUDED.head_id,head_set=EXCLUDED.head_set,local_dirty=false
                """, subject, group, json, EventCodec.vectorJson(vector), representative, headJson);
    }

    public void setFieldDirty(UUID subject, String group, boolean dirty) {
        jdbc.update("UPDATE eatwhat_sync.profile_field_state SET local_dirty=? WHERE subject_gid=? AND field_group=?",
                dirty, subject, group);
    }

    public static Map<String, Object> map(String json) {
        return JSON.readValue(json, JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }
}
