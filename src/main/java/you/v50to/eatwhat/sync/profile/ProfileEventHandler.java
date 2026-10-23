package you.v50to.eatwhat.sync.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.EventHandler;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Field-aware profile replay. Disjoint groups merge independently; concurrent same-group values stay conflicts. */
public final class ProfileEventHandler implements EventHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final ProfileStore store;
    private final EntityIdentityService identities;
    private final ConsentStore consent;
    private final ProfileEvents policy;
    private final SiteProperties site;
    private final int version;
    private final you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts accounts;

    public ProfileEventHandler(JdbcTemplate jdbc, ProfileStore store, EntityIdentityService identities,
                               ConsentStore consent, SiteProperties site) {
        this(jdbc,store,identities,consent,site,1,null);
    }
    public ProfileEventHandler(JdbcTemplate jdbc,ProfileStore store,EntityIdentityService identities,
                               ConsentStore consent,SiteProperties site,int version,
                               you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts accounts) {
        if(version!=1&&version!=2)throw new IllegalArgumentException("PROFILE_VERSION");
        this.version=version;this.accounts=accounts;
        this.jdbc = jdbc;
        this.store = store;
        this.identities = identities;
        this.consent = consent;
        this.site = site;
        this.policy = new ProfileEvents(consent);
    }

    @Override public String aggregateType() { return ProfileEvents.AGGREGATE; }
    @Override public String eventType() { return ProfileEvents.EVENT; }
    @Override public int schemaVersion() { return version; }

    private ProfileEvents.Operation operation(SyncEvent event) {
        policy.validate(event, new NodeIdentity(event.originSite(), event.originInstanceId()));
        return ProfileEvents.operation(event);
    }

    @Override public boolean dependenciesSatisfied(SyncEvent event) {
        ProfileEvents.Operation op = operation(event);
        if(event.schemaVersion()==2&&op.resolution()!=null){
            if(accounts==null)return false;
            if(accounts.assess(ProfileV2Envelope.parse(event.payloadJson()).authorization())==you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.DEPENDENCY_PENDING)return false;
        }
        if(event.schemaVersion()==2) for(var proof:ProfileV2Envelope.parse(event.payloadJson()).resolutionAuthorizations().values()) {
            if(accounts==null||accounts.assess(proof)==you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.DEPENDENCY_PENDING)return false;
        }
        if(event.schemaVersion()==2&&op.resolution()!=null)for(UUID head:op.resolution().expectedHeads()) {
            String pattern=JSON.writeValueAsString(List.of(Map.of("id",head)));
            if(!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.profile_operation WHERE event_id=? AND subject_gid=?)
                 OR EXISTS(SELECT 1 FROM eatwhat_sync.profile_field_state WHERE subject_gid=? AND (head_id=? OR head_set @> ?::jsonb))
                 OR EXISTS(SELECT 1 FROM eatwhat_sync.profile_conflict WHERE subject_gid=? AND (heads @> ?::jsonb OR retired_heads @> ?::jsonb))
                """,Boolean.class,head,op.subject(),op.subject(),head,pattern,op.subject(),pattern,pattern)))return false;
        }
        // Same lock order as local writes and account withdrawal: subject before public user/profile rows.
        var subjects = jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",
                (row, index) -> row.getLong(1), op.subject());
        if (subjects.isEmpty()) return false;
        ConsentModels.State state = consent.getBySubject(op.subject());
        if (state == null) return false;
        if (state.epoch() > op.epoch() || (state.epoch() == op.epoch() && state.status().equals("WITHDRAWN"))) return true;
        Long userId = identities.findNumericId(EntityType.USERS, op.subject()).orElse(null);
        if (userId == null || !consent.replicationAllowed(userId) || state.epoch() != op.epoch()) return false;
        if (event.schemaVersion()==1 && site.getMode() == SiteProperties.Mode.EXTERNAL && store.baseline(op.subject()).isEmpty()
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT local_dirty FROM eatwhat_consent.subject WHERE subject_id=?",
                Boolean.class, op.subject()))) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.users WHERE id=?)",
                Boolean.class, userId));
    }

    @Override public void apply(SyncEvent event) {
        ProfileEvents.Operation op = operation(event);
        ConsentModels.State consentState = consent.getBySubject(op.subject());
        if (consentState == null || consentState.epoch() > op.epoch()
                || (consentState.epoch() == op.epoch() && consentState.status().equals("WITHDRAWN"))) return;
        Long userId = identities.findNumericId(EntityType.USERS, op.subject()).orElseThrow();
        if (consentState.epoch() != op.epoch() || !consent.replicationAllowed(userId)) return;

        if(event.schemaVersion()==2&&op.resolution()!=null){
            var proof=ProfileV2Envelope.parse(event.payloadJson()).authorization();
            if(accounts==null)throw new IllegalStateException("PROFILE_ACCOUNT_AUTHORITY_REQUIRED");
            var assessment=accounts.assess(proof);
            if(assessment==you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.DEPENDENCY_PENDING)throw new IllegalStateException("PROFILE_ACCOUNT_DEPENDENCY_PENDING");
            jdbc.update("INSERT INTO eatwhat_sync.profile_authorization(event_id,subject_gid,actor_gid,evidence,outcome,operation) VALUES(?,?,?,?::jsonb,?,?::jsonb) ON CONFLICT DO NOTHING",event.eventId(),op.subject(),event.actorId(),JSON.writeValueAsString(proof),assessment.name(),ProfileEvents.payload(op));
            if(assessment!=you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.AUTHORIZED&&assessment!=you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.HISTORICAL_VALID){
                new ProfileAuthorizationReviews(jdbc,store).record(event.eventId(),event.actorId(),op,assessment.name());
                return;
            }
        }
        ProfileStore.Baseline baseline = store.baseline(op.subject()).orElse(null);
        if(event.schemaVersion()==2&&baseline==null) { baseline=store.createIndependentBaseline(op.subject());store.insertInitialFieldValues(op.subject(),baseline.value()); }

        if (baseline == null) {
            if (site.getMode() == SiteProperties.Mode.EXTERNAL
                    && Boolean.TRUE.equals(jdbc.queryForObject("SELECT local_dirty FROM eatwhat_consent.subject WHERE subject_id=?",
                    Boolean.class, op.subject()))) {
                throw new IllegalStateException("PROFILE_BASELINE_BLOCKED_BY_ACCOUNT_DIRTY");
            }
            if (site.getMode() != SiteProperties.Mode.EXTERNAL || !op.baselineOrigin().equals(event.originSite())) {
                throw new IllegalArgumentException("PROFILE_BASELINE_ORIGIN_MISMATCH");
            }
            baseline = store.installBaseline(op.subject(), op.baselineId(), op.baselineOrigin(), op.baseline(), op.baselineHash());
            store.insertInitialFieldValues(op.subject(), op.baseline());
        } else if (event.schemaVersion()==1 && (!baseline.id().equals(op.baselineId()) || !baseline.origin().equals(op.baselineOrigin())
                || !baseline.hash().equals(op.baselineHash()) || !baseline.value().equals(op.baseline()))) {
            throw new IllegalArgumentException("PROFILE_BASELINE_CONFLICT");
        }

        if(event.schemaVersion()==2) {
            for(var entry:ProfileV2Envelope.parse(event.payloadJson()).resolutionAuthorizations().entrySet()) {
                var assessment=accounts.assess(entry.getValue());
                var original=ProfileV2Envelope.parse(event.payloadJson()).resolutionOperations().get(entry.getKey());
                jdbc.update("INSERT INTO eatwhat_sync.profile_authorization(event_id,subject_gid,actor_gid,evidence,outcome,operation) VALUES(?,?,?,?::jsonb,?,?::jsonb) ON CONFLICT DO NOTHING",entry.getKey(),op.subject(),entry.getValue().subjectId(),JSON.writeValueAsString(entry.getValue()),assessment.name(),ProfileEvents.payload(original));
                if(assessment!=you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.AUTHORIZED&&assessment!=you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.HISTORICAL_VALID){new ProfileAuthorizationReviews(jdbc,store).record(entry.getKey(),entry.getValue().subjectId(),original,assessment.name());return;}
            }
        }
        UUID subject = op.subject();
        VersionVector domainVector = store.beginEpoch(subject, op.epoch());
        domainVector = merge(domainVector, mergeHeadBridge(event, op, subject, baseline.value(),
                op.headBridge(), op.resolutionBridge()));
        for (var change : op.changes().entrySet()) {
            String group = change.getKey();
            VersionVector context = new VersionVector(op.contexts().get(group));
            VersionVector incoming = withDot(context, op.dotSite(), op.dotCounter());
            ProfileStore.FieldState current = store.field(subject, group, baseline.value().get(group));
            if (handleOpenConflict(event, op, userId, group, current, change.getValue(), incoming)) {
                domainVector = merge(domainVector, incoming);
                continue;
            }
            VersionVector.Relation relation = incoming.compare(current.vector());
            if (relation == VersionVector.Relation.BEFORE || relation == VersionVector.Relation.EQUAL) {
                // An older operation is already covered by the applied field frontier.
                continue;
            }
            if (relation == VersionVector.Relation.CONCURRENT && !java.util.Objects.equals(current.value(), change.getValue())) {
                persistConflict(event.eventId(), subject, group, current, change.getValue(), incoming,
                        op.dotSite(), op.dotCounter());
                domainVector = merge(domainVector, incoming);
                continue;
            }
            VersionVector mergedField = merge(current.vector(), incoming);
            store.applyPublicValue(userId, group, change.getValue());
            var fieldHeads = new LinkedHashMap<UUID, ProfileEvents.Head>();
            addCurrentHead(fieldHeads, subject, group, baseline.value().get(group));
            fieldHeads.putIfAbsent(event.eventId(), new ProfileEvents.Head(event.eventId(), change.getValue(), incoming.counters()));
            List<ProfileEvents.Head> maximal = maximalHeads(fieldHeads.values());
            store.applyFieldHeads(subject, group, change.getValue(), mergedField, maximal);
            domainVector = merge(domainVector, incoming);
        }
        jdbc.update("INSERT INTO eatwhat_sync.profile_operation(event_id,subject_gid,epoch,baseline_id,origin_site,dot_counter,context,changes,head_bridge,resolution_bridge,resolution)"
                        + " VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb) ON CONFLICT(event_id) DO NOTHING",
                event.eventId(), subject, op.epoch(), op.baselineId(), op.dotSite(), op.dotCounter(),
                you.v50to.eatwhat.sync.EventCodec.canonicalJson(JSON.writeValueAsString(op.contexts())),
                you.v50to.eatwhat.sync.EventCodec.canonicalJson(JSON.writeValueAsString(op.changes())),
                you.v50to.eatwhat.sync.EventCodec.canonicalJson(JSON.writeValueAsString(op.headBridge())),
                you.v50to.eatwhat.sync.EventCodec.canonicalJson(JSON.writeValueAsString(op.resolutionBridge())),
                op.resolution() == null ? null : you.v50to.eatwhat.sync.EventCodec.canonicalJson(JSON.writeValueAsString(op.resolution())));
        // Every replica exposes the same restricted projection while retaining all candidate values in heads.
        if(event.schemaVersion()==2) {
            for(String group:jdbc.queryForList("SELECT field_group FROM eatwhat_sync.profile_conflict WHERE subject_gid=? AND status='OPEN'",String.class,subject))
                store.applyPublicValue(userId,group,ProfileStore.emptyProfile().get(group));
        }
        if(event.schemaVersion()==2&&op.resolution()!=null)new ProfileAuthorizationReviews(jdbc,store).decided(subject,event.eventId(),op.resolution().expectedHeads());
        store.setDomainVector(subject, domainVector);
    }

    private VersionVector mergeHeadBridge(SyncEvent event, ProfileEvents.Operation operation,
                                          UUID subject, Map<String, Object> baseline,
                                          Map<String, java.util.List<ProfileEvents.Head>> bridge,
                                          Map<String, ProfileEvents.ResolutionProof> resolutionBridge) {
        VersionVector bridged = VersionVector.empty();
        for (var entry : bridge.entrySet()) {
            String group = entry.getKey();
            var rows = jdbc.query("""
                    SELECT conflict_id,status,heads::text,retired_heads::text FROM eatwhat_sync.profile_conflict
                    WHERE subject_gid=? AND field_group=? FOR UPDATE
                    """, (row, index) -> new Object[]{row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getString(4)},
                    subject, group);
            String status = rows.isEmpty() ? null : (String) rows.getFirst()[1];
            var candidates = new LinkedHashMap<UUID, ProfileEvents.Head>();
            java.util.Set<UUID> retiredIds = new java.util.HashSet<>();
            List<ProfileEvents.Head> retiredHeads = List.of();
            if (!rows.isEmpty() && "OPEN".equals(status)) {
                addHeads(candidates, JSON.readTree((String) rows.getFirst()[2]));
            } else if (!rows.isEmpty()) {
                JsonNode retired = JSON.readTree((String) rows.getFirst()[3]);
                retiredHeads = readHeads(retired);
                for (ProfileEvents.Head head : retiredHeads) retiredIds.add(head.id());
                if ("RESOLVED".equals(status)) addCurrentHead(candidates, subject, group, baseline.get(group));
            }
            if (rows.isEmpty() || "OPEN".equals(status)) addCurrentHead(candidates, subject, group, baseline.get(group));
            for (ProfileEvents.Head head : entry.getValue()) {
                boolean retiredAncestor = retiredIds.contains(head.id());
                for (ProfileEvents.Head retired : retiredHeads) {
                    VersionVector.Relation relation = new VersionVector(head.vector())
                            .compare(new VersionVector(retired.vector()));
                    if (relation == VersionVector.Relation.BEFORE || relation == VersionVector.Relation.EQUAL) {
                        retiredAncestor = true;
                        break;
                    }
                }
                if (retiredAncestor) continue;
                ProfileEvents.Head previous = candidates.putIfAbsent(head.id(), head);
                if (previous != null && (!java.util.Objects.equals(previous.value(), head.value())
                        || !previous.vector().equals(head.vector()))) {
                    throw new IllegalArgumentException("PROFILE_HEAD_IDENTITY_CONFLICT");
                }
            }

            List<ProfileEvents.Head> maximal = maximalHeads(candidates.values());
            if (maximal.isEmpty()) continue;
            VersionVector bridgeFrontier = VersionVector.empty();
            for (ProfileEvents.Head head : maximal) {
                bridgeFrontier = merge(bridgeFrontier, new VersionVector(head.vector()));
            }
            bridged = merge(bridged, bridgeFrontier);
            ProfileStore.FieldState current = store.field(subject, group, baseline.get(group));
            if (maximal.size() == 1) {
                // A singleton bridge is a complete value state, not a new profile edit.
                boolean openConflict = !rows.isEmpty() && "OPEN".equals(status);
                ProfileEvents.Head winner = maximal.getFirst();
                boolean resolutionProof = provesResolution(event, operation, winner, entry.getValue(),
                        resolutionBridge.get(group));
                if (openConflict && resolutionProof) {
                    Long userId = identities.findNumericId(EntityType.USERS, subject).orElse(null);
                    if (userId != null && !java.util.Objects.equals(current.value(), winner.value())) {
                        store.applyPublicValue(userId, group, winner.value());
                    }
                    jdbc.update("""
                            UPDATE eatwhat_sync.profile_conflict SET status='RESOLVED',resolved_head=?,
                              retired_heads=retired_heads || heads,updated_at=clock_timestamp() WHERE conflict_id=?
                            """, winner.id(), rows.getFirst()[0]);
                    store.applyFieldHeads(subject, group, winner.value(), bridgeFrontier, List.of(winner));
                } else if (!openConflict) {
                    VersionVector.Relation relation = bridgeFrontier.compare(current.vector());
                    if (relation == VersionVector.Relation.AFTER || relation == VersionVector.Relation.EQUAL) {
                        if (!java.util.Objects.equals(current.value(), winner.value())) {
                            Long userId = identities.findNumericId(EntityType.USERS, subject).orElse(null);
                            if (userId != null) store.applyPublicValue(userId, group, winner.value());
                        }
                        store.applyFieldHeads(subject, group, winner.value(), bridgeFrontier, List.of(winner));
                    }
                }
                continue;
            }
            boolean sameValue = maximal.stream().allMatch(head ->
                    java.util.Objects.equals(head.value(), maximal.getFirst().value()));
            if (sameValue) {
                // Equal concurrent writes have no user-visible ambiguity. Join their
                // frontier without manufacturing an operation or changing the selected value.
                Object value = maximal.getFirst().value();
                if (rows.isEmpty() || !"OPEN".equals(status)) {
                    Long userId = identities.findNumericId(EntityType.USERS, subject).orElse(null);
                    if (userId != null && !java.util.Objects.equals(current.value(), value)) {
                        store.applyPublicValue(userId, group, value);
                    }
                    store.applyFieldHeads(subject, group, value, bridgeFrontier, maximal);
                    continue;
                }
            }
            String json = you.v50to.eatwhat.sync.EventCodec.canonicalJson(JSON.writeValueAsString(maximal.stream()
                    .map(head -> head(head.id(), head.value(), head.vector())).toList()));
            if (rows.isEmpty()) {
                jdbc.update("""
                        INSERT INTO eatwhat_sync.profile_conflict(conflict_id,subject_gid,field_group,revision,heads,status)
                        VALUES(?,?,?,?,?::jsonb,'OPEN')
                        """, UUID.randomUUID(), subject, group, 1L, json);
            } else {
                List<ProfileEvents.Head> oldMaximal = maximalHeadsFromJson(JSON.readTree((String) rows.getFirst()[2]));
                String oldJson = you.v50to.eatwhat.sync.EventCodec.canonicalJson(JSON.writeValueAsString(oldMaximal.stream()
                        .map(head -> head(head.id(), head.value(), head.vector())).toList()));
                if (!json.equals(oldJson) || "RESOLVED".equals(status)) {
                    updateConflictHeads((UUID) rows.getFirst()[0], subject, group, maximal.stream()
                            .map(head -> head(head.id(), head.value(), head.vector())).toList());
                }
            }
        }
        return bridged;
    }

    private boolean provesResolution(SyncEvent event, ProfileEvents.Operation operation,
                                     ProfileEvents.Head winner,
                                     List<ProfileEvents.Head> incoming,
                                     ProfileEvents.ResolutionProof proof) {
        if (proof == null) return false;
        if(event.schemaVersion()==2) {
            var evidence=ProfileV2Envelope.parse(event.payloadJson()).resolutionAuthorizations().get(proof.eventId());
            if(evidence==null||accounts==null)return false;
            var assessment=accounts.assess(evidence);
            if(assessment!=you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.AUTHORIZED&&assessment!=you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.HISTORICAL_VALID)return false;
        } else if(!event.originSite().equals(operation.baselineOrigin()))return false;
        var byId = new LinkedHashMap<UUID, ProfileEvents.Head>();
        for (ProfileEvents.Head head : incoming) byId.put(head.id(), head);
        if (proof.expectedHeads().isEmpty() || !proof.expectedHeads().contains(proof.selectedHeadId())) return false;
        ProfileEvents.Head resolution = byId.get(proof.eventId());
        ProfileEvents.Head selected = byId.get(proof.selectedHeadId());
        if (resolution == null || selected == null || !java.util.Objects.equals(selected.value(), resolution.value())) return false;
        VersionVector resolutionVector = new VersionVector(resolution.vector());
        for (UUID expected : proof.expectedHeads()) {
            ProfileEvents.Head parent = byId.get(expected);
            if (parent == null || expected.equals(resolution.id())) return false;
            if (resolutionVector.compare(new VersionVector(parent.vector())) != VersionVector.Relation.AFTER) return false;
        }
        VersionVector.Relation descendant = new VersionVector(winner.vector()).compare(resolutionVector);
        if (descendant != VersionVector.Relation.AFTER && descendant != VersionVector.Relation.EQUAL) return false;
        return !winner.id().equals(resolution.id()) || java.util.Objects.equals(winner.value(), resolution.value());
    }

    private void addHeads(Map<UUID, ProfileEvents.Head> target, JsonNode json) {
        for (ProfileEvents.Head parsed : readHeads(json)) {
            UUID id = parsed.id();
            ProfileEvents.Head previous = target.putIfAbsent(id, parsed);
            if (previous != null && (!java.util.Objects.equals(previous.value(), parsed.value())
                    || !previous.vector().equals(parsed.vector()))) {
                throw new IllegalStateException("PROFILE_HEAD_IDENTITY_CONFLICT");
            }
        }
    }

    private List<ProfileEvents.Head> readHeads(JsonNode json) {
        var parsed = new ArrayList<ProfileEvents.Head>();
        var vectorType = JSON.getTypeFactory().constructMapType(Map.class,
                JSON.getTypeFactory().constructType(String.class), JSON.getTypeFactory().constructType(Long.class));
        for (JsonNode head : json) {
            UUID id = UUID.fromString(head.path("id").stringValue());
            Map<String, Long> vector = JSON.convertValue(head.path("vector"), vectorType);
            parsed.add(new ProfileEvents.Head(id, JSON.convertValue(head.get("value"), Object.class), Map.copyOf(vector)));
        }
        return parsed;
    }

    private void addCurrentHead(Map<UUID, ProfileEvents.Head> target, UUID subject, String group, Object defaultValue) {
        for (ProfileEvents.Head current : store.fieldHeads(subject, group, defaultValue)) {
            target.putIfAbsent(current.id(), current);
        }
    }

    private VersionVector operationVector(UUID subject, String group, UUID headId, VersionVector fallback) {
        var rows = jdbc.query("""
                SELECT origin_site,dot_counter,context->? AS field_context
                FROM eatwhat_sync.profile_operation WHERE event_id=? AND subject_gid=?
                """, (row, index) -> new Object[]{row.getString(1), row.getLong(2), row.getString(3)}, group, headId, subject);
        if (rows.isEmpty() || rows.getFirst()[2] == null) return fallback;
        VersionVector vector = you.v50to.eatwhat.sync.EventCodec.vectorFromJson((String) rows.getFirst()[2]);
        Map<String, Long> withDot = new LinkedHashMap<>(vector.counters());
        withDot.merge((String) rows.getFirst()[0], (Long) rows.getFirst()[1], Math::max);
        return new VersionVector(withDot);
    }

    private List<ProfileEvents.Head> maximalHeadsFromJson(JsonNode json) {
        return maximalHeads(readHeads(json));
    }

    private List<ProfileEvents.Head> maximalHeads(java.util.Collection<ProfileEvents.Head> heads) {
        var all = new ArrayList<>(heads);
        var maximal = new ArrayList<ProfileEvents.Head>();
        for (ProfileEvents.Head candidate : all) {
            VersionVector candidateVector = new VersionVector(candidate.vector());
            boolean dominated = all.stream().anyMatch(other -> !other.id().equals(candidate.id())
                    && new VersionVector(other.vector()).compare(candidateVector) == VersionVector.Relation.AFTER);
            if (!dominated) maximal.add(candidate);
        }
        maximal.sort(java.util.Comparator.comparing(head -> head.id().toString()));
        return List.copyOf(maximal);
    }

    /** Ordinary patches never resolve a conflict; only an explicit, complete-head resolution can do so. */
    private boolean handleOpenConflict(SyncEvent event, ProfileEvents.Operation op, long userId, String group,
                                       ProfileStore.FieldState current, Object value, VersionVector incoming) {
        var rows = jdbc.query("""
                SELECT conflict_id,heads::text,retired_heads::text FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND field_group=? AND status='OPEN' FOR UPDATE
                """, (row, index) -> new Object[]{row.getObject(1, UUID.class), row.getString(2), row.getString(3)}, op.subject(), group);
        if (rows.isEmpty()) return false;
        Object[] row = rows.getFirst();
        UUID conflictId = (UUID) row[0];
        JsonNode existing = JSON.readTree((String) row[1]);
        JsonNode retired = JSON.readTree((String) row[2]);
        java.util.Set<UUID> existingIds = new java.util.TreeSet<>();
        var candidates = new LinkedHashMap<UUID, ProfileEvents.Head>();
        for (JsonNode head : existing) {
            UUID id = UUID.fromString(head.path("id").stringValue());
            existingIds.add(id);
            addHeads(candidates, JSON.createArrayNode().add(head));
        }
        var historical = new LinkedHashMap<UUID, ProfileEvents.Head>();
        addHeads(historical, existing);
        addHeads(historical, retired);

        ProfileEvents.Resolution resolution = op.resolution();
        if (resolution != null && resolution.selectedHeadId() != null
                && (event.schemaVersion()==2 || op.baselineOrigin().equals(event.originSite()))) {
            ProfileEvents.Head selected = null;
            for (ProfileEvents.Head head : historical.values()) {
                if (resolution.selectedHeadId().equals(head.id())) {
                    selected = head;
                    break;
                }
            }
            boolean observesEveryExpectedHead = true;
            VersionVector resolved = withDot(new VersionVector(op.contexts().get(group)), op.dotSite(), op.dotCounter());
            boolean expectedHeadsKnown = !resolution.expectedHeads().isEmpty()
                    && resolution.expectedHeads().stream().distinct().count() == resolution.expectedHeads().size();
            for (UUID expectedHead : resolution.expectedHeads()) {
                ProfileEvents.Head parent = historical.get(expectedHead);
                if (parent == null || resolved.compare(new VersionVector(parent.vector()))
                        != VersionVector.Relation.AFTER) {
                    observesEveryExpectedHead = false;
                    break;
                }
            }
            if (expectedHeadsKnown && selected != null && observesEveryExpectedHead && java.util.Objects.equals(
                    selected.value(), value)) {
                boolean isCurrentResolution = resolution.expectedHeads().stream().map(historical::get)
                        .filter(java.util.Objects::nonNull).map(ProfileEvents.Head::id)
                        .collect(java.util.stream.Collectors.toSet()).equals(existingIds)
                        && existingIds.size() == resolution.expectedHeads().size();
                if (isCurrentResolution) {
                    jdbc.update("""
                            UPDATE eatwhat_sync.profile_conflict SET status='RESOLVED',resolved_head=?,
                              retired_heads=retired_heads || heads,
                              updated_at=clock_timestamp() WHERE conflict_id=? AND status='OPEN'
                            """, resolution.selectedHeadId(), conflictId);
                    store.applyPublicValue(userId, group, value);
                    store.applyField(op.subject(), group, value, merge(current.vector(), incoming), event.eventId());
                    return true;
                }
                if (existingIds.size() == 1) {
                    ProfileEvents.Head active = candidates.values().iterator().next();
                    VersionVector.Relation activeVsResolution = new VersionVector(active.vector()).compare(resolved);
                    if (activeVsResolution == VersionVector.Relation.AFTER) {
                        jdbc.update("""
                                UPDATE eatwhat_sync.profile_conflict SET status='RESOLVED',resolved_head=?,
                                  retired_heads=retired_heads || heads,updated_at=clock_timestamp()
                                WHERE conflict_id=? AND status='OPEN'
                                """, active.id(), conflictId);
                        if (!java.util.Objects.equals(current.value(), active.value())) {
                            store.applyPublicValue(userId, group, active.value());
                        }
                        store.applyFieldHeads(op.subject(), group, active.value(),
                                new VersionVector(active.vector()), List.of(active));
                        return true;
                    }
                }
            }
        }
        if (existingIds.contains(event.eventId())) return true;
        candidates.putIfAbsent(event.eventId(), new ProfileEvents.Head(event.eventId(), value, incoming.counters()));
        List<ProfileEvents.Head> maximal = maximalHeads(candidates.values());
        if (maximal.stream().noneMatch(head -> head.id().equals(event.eventId()))) return true;
        updateConflictHeads(conflictId, op.subject(), group, maximal.stream()
                .map(head -> head(head.id(), head.value(), head.vector())).toList());
        return true;
    }

    private void persistConflict(UUID eventId, UUID subject, String group, ProfileStore.FieldState current,
                                 Object incomingValue, VersionVector incomingVector, String dotSite, long dotCounter) {
        var existingRows = jdbc.query("""
                SELECT conflict_id,status,heads::text FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND field_group=? FOR UPDATE
                """, (row, index) -> new Object[]{row.getObject(1, UUID.class), row.getString(2), row.getString(3)},
                subject, group);

        var candidates = new LinkedHashMap<UUID, ProfileEvents.Head>();
        if (!existingRows.isEmpty() && "OPEN".equals(existingRows.getFirst()[1])) {
            addHeads(candidates, JSON.readTree((String) existingRows.getFirst()[2]));
        } else {
            List<ProfileEvents.Head> currentHeads = store.fieldHeads(subject, group, current.value());
            if (currentHeads.isEmpty()) {
                UUID baselineHead = UUID.nameUUIDFromBytes((subject + ":baseline:" + group)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                currentHeads = List.of(new ProfileEvents.Head(baselineHead, current.value(), current.vector().counters()));
            }
            for (ProfileEvents.Head head : currentHeads) candidates.putIfAbsent(head.id(), head);
        }
        candidates.putIfAbsent(eventId, new ProfileEvents.Head(eventId, incomingValue, incomingVector.counters()));
        List<ProfileEvents.Head> maximal = maximalHeads(candidates.values());
        if (maximal.stream().noneMatch(head -> head.id().equals(eventId))) return;
        List<Map<String, Object>> heads = maximal.stream()
                .map(head -> head(head.id(), head.value(), head.vector())).toList();
        String json = EventCodec.canonicalJson(JSON.writeValueAsString(heads));
        if (existingRows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO eatwhat_sync.profile_conflict(conflict_id,subject_gid,field_group,revision,heads,status)
                    VALUES(?,?,?,?,?::jsonb,'OPEN')
                    """, UUID.randomUUID(), subject, group, 1L, json);
        } else if ("OPEN".equals(existingRows.getFirst()[1])) {
            updateConflictHeads((UUID) existingRows.getFirst()[0], subject, group, heads);
        } else {
            Object[] row = existingRows.getFirst();
            jdbc.update("""
                    UPDATE eatwhat_sync.profile_conflict SET revision=revision+1,heads=?::jsonb,status='OPEN',
                      resolved_head=NULL,updated_at=clock_timestamp() WHERE conflict_id=?
                    """, json, row[0]);
        }
        if (existingRows.isEmpty() || !"OPEN".equals(existingRows.getFirst()[1])) {
            jdbc.update("UPDATE eatwhat_sync.profile_field_state SET conflict_revision=conflict_revision+1 WHERE subject_gid=? AND field_group=?",
                    subject, group);
        }
    }

    private static Map<String, Object> head(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", node.path("id").stringValue());
        result.put("value", JSON.convertValue(node.get("value"), Object.class));
        result.put("vector", EventCodec.vectorFromJson(node.path("vector").toString()).counters());
        return result;
    }

    private void updateConflictHeads(UUID conflictId, UUID subject, String group, List<Map<String, Object>> heads) {
        var oldRows = jdbc.query("SELECT heads::text,retired_heads::text FROM eatwhat_sync.profile_conflict WHERE conflict_id=? FOR UPDATE",
                (row, index) -> new String[]{row.getString(1), row.getString(2)}, conflictId);
        List<Map<String, Object>> retired = new ArrayList<>();
        if (!oldRows.isEmpty()) {
            JsonNode oldHeads = JSON.readTree(oldRows.getFirst()[0]);
            JsonNode priorRetired = JSON.readTree(oldRows.getFirst()[1]);
            var retainedIds = heads.stream().map(head -> head.get("id").toString()).collect(java.util.stream.Collectors.toSet());
            for (JsonNode old : priorRetired) retired.add(head(old));
            for (JsonNode old : oldHeads) {
                if (!retainedIds.contains(old.path("id").stringValue())) retired.add(head(old));
            }
        }
        jdbc.update("""
                UPDATE eatwhat_sync.profile_conflict SET revision=revision+1,heads=?::jsonb,retired_heads=?::jsonb,
                  status='OPEN',resolved_head=NULL,updated_at=clock_timestamp() WHERE conflict_id=?
                """, EventCodec.canonicalJson(JSON.writeValueAsString(heads)),
                EventCodec.canonicalJson(JSON.writeValueAsString(retired)), conflictId);
        jdbc.update("UPDATE eatwhat_sync.profile_field_state SET conflict_revision=conflict_revision+1 WHERE subject_gid=? AND field_group=?",
                subject, group);
    }

    private static Map<String, Object> head(UUID id, Object value, Map<String, Long> vector) {
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("id", id.toString());
        head.put("value", value);
        head.put("vector", vector);
        return head;
    }

    private static VersionVector withDot(VersionVector context, String site, long counter) {
        Map<String, Long> result = new LinkedHashMap<>(context.counters());
        if (counter <= result.getOrDefault(site, 0L)) throw new IllegalArgumentException("INVALID_PROFILE_EVENT");
        result.put(site, counter);
        return new VersionVector(result);
    }

    private static VersionVector merge(VersionVector left, VersionVector right) {
        Map<String, Long> merged = new LinkedHashMap<>(left.counters());
        right.counters().forEach((site, counter) -> merged.merge(site, counter, Math::max));
        return new VersionVector(merged);
    }
}
