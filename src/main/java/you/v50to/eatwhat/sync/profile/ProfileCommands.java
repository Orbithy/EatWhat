package you.v50to.eatwhat.sync.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentModels;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.dto.UpdateUserInfoDTO;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.identity.EntityType;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.VersionVector;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

/** Captures only user-edited profile fields; username and security/account fields stay account-owned. */
public final class ProfileCommands {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private boolean multiwriter;
    private UUID authorizationScanCursor;
    private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts accounts;
    public void setMultiwriter(boolean enabled,you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts accounts) { this.multiwriter=enabled;this.accounts=accounts; }
    public boolean isMultiwriter() { return multiwriter; }
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final ProfileStore store;
    private final OutboxService outbox;
    private final EntityIdentityService identities;
    private final ConsentStore consent;
    private final SiteProperties site;
    private final String peer;
    private final ProfileEvents events;
    private final java.util.concurrent.atomic.AtomicBoolean workerActive = new java.util.concurrent.atomic.AtomicBoolean();

    private record ReadySubject(long userId, UUID subject, long epoch) { }

    public ProfileCommands(JdbcTemplate jdbc, SyncTransactions transactions, ProfileStore store,
                          OutboxService outbox, EntityIdentityService identities, ConsentStore consent,
                          SiteProperties site, String peer) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.store = store;
        this.outbox = outbox;
        this.identities = identities;
        this.consent = consent;
        this.site = site;
        this.peer = peer;
        this.events = new ProfileEvents(consent);
    }

    /** Read before the legacy mapper mutation so the common baseline never includes the user's first patch. */
    public Map<String, Object> snapshotBeforeUpdate(long userId) {
        return store.readPublicProfile(userId);
    }

    public boolean effectiveHometownValid(UpdateUserInfoDTO dto, Map<String, Object> before) {
        if (dto.getHometownProvinceId() == null && dto.getHometownCityId() == null) return true;
        @SuppressWarnings("unchecked") Map<String, Object> previous = (Map<String, Object>) before.get("hometown");
        Object province = dto.getHometownProvinceId() == null ? previous.get("provinceId") : dto.getHometownProvinceId();
        Object city = dto.getHometownCityId() == null ? previous.get("cityId") : dto.getHometownCityId();
        if (province == null || city == null) return true;
        return store.hometownPairMatches(((Number) province).intValue(), ((Number) city).intValue());
    }

    public <T> T atomic(Supplier<T> operation) { return transactions.required(operation); }

    public boolean canEdit(long userId) {
        UUID subject = identities.findGlobalId(EntityType.USERS, userId).orElse(null);
        return subject != null && (multiwriter || site.getMode() == SiteProperties.Mode.CAMPUS || store.baseline(subject).isPresent());
    }

    /** Establish the CAMPUS-owned baseline before the legacy mapper fires account tracking triggers. */
    public void prepareEdit(long userId, Map<String, Object> before) {
        if(multiwriter) { initializeIndependent(userId,before); return; }
        if (site.getMode() != SiteProperties.Mode.CAMPUS) return;
        UUID subject = identities.findGlobalId(EntityType.USERS, userId)
                .orElseThrow(() -> new IllegalStateException("PROFILE_IDENTITY_REQUIRED"));
        if (store.baseline(subject).isEmpty()) {
            store.createCampusBaseline(subject, site.getId(), before);
            store.insertInitialFieldValues(subject, before);
        }
    }

    public static boolean hasProfileFields(UpdateUserInfoDTO dto) {
        return dto.getAvatar() != null || dto.getGender() != null || dto.getBirthday() != null
                || dto.getSignature() != null || dto.getHometownProvinceId() != null || dto.getHometownCityId() != null;
    }

    public boolean hasOpenConflict(long userId, UpdateUserInfoDTO dto) {
        UUID subject = identities.findGlobalId(EntityType.USERS, userId).orElse(null);
        if (subject == null) return false;
        if (dto.getAvatar() != null && hasConflict(subject, "avatar")) return true;
        if (dto.getGender() != null && hasConflict(subject, "gender")) return true;
        if (dto.getBirthday() != null && hasConflict(subject, "birthday")) return true;
        if (dto.getSignature() != null && hasConflict(subject, "signature")) return true;
        return (dto.getHometownProvinceId() != null || dto.getHometownCityId() != null)
                && hasConflict(subject, "hometown");
    }

    private boolean hasConflict(UUID subject, String group) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.profile_conflict
                  WHERE subject_gid=? AND field_group=? AND status='OPEN')
                """, Boolean.class, subject, group));
    }

    public void lockSubject(long userId) {
        jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), userId);
    }

    /** Must be called in the same business transaction as the existing updateUserInfo mapper writes. */
    public void capture(long userId, UpdateUserInfoDTO dto, Map<String, Object> before) {
        transactions.required(() -> {
            if(multiwriter) initializeIndependent(userId,before);
            var identity = identities.findGlobalId(EntityType.USERS, userId)
                    .orElseThrow(() -> new IllegalStateException("PROFILE_IDENTITY_REQUIRED"));
            jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=? FOR UPDATE",
                    (row, index) -> row.getObject(1, UUID.class), userId);
            ProfileStore.Baseline baseline = store.baseline(identity).orElse(null);
            if (baseline == null) {
                if (site.getMode() != SiteProperties.Mode.CAMPUS) {
                    throw new IllegalStateException("PROFILE_BASELINE_REQUIRED");
                }
                baseline = store.createCampusBaseline(identity, site.getId(), before);
                store.insertInitialFieldValues(identity, before);
            }

            Map<String, Object> after = store.readPublicProfile(userId);
            if (dto.getHometownProvinceId() != null || dto.getHometownCityId() != null) {
                @SuppressWarnings("unchecked") Map<String, Object> hometown = (Map<String, Object>) after.get("hometown");
                store.validateHometownPair(hometown);
            }
            Map<String, Object> requested = requestedGroups(dto, after);
            Map<String, Object> changes = new LinkedHashMap<>();
            Map<String, Map<String, Long>> contexts = new LinkedHashMap<>();
            ConsentModels.State state = consent.get(userId);
            long epoch = state == null ? 0 : state.epoch();
            VersionVector current = store.beginEpoch(identity, epoch);
            String dotSite = site.getId();
            long counter = current.counters().getOrDefault(dotSite, 0L) + 1;
            for (var entry : requested.entrySet()) {
                String group = entry.getKey();
                if (hasConflict(identity, group)) {
                    throw new IllegalStateException("PROFILE_CONFLICT_REQUIRES_ADMIN_RESOLUTION");
                }
                ProfileStore.FieldState prior = store.field(identity, group, baseline.value().get(group));
                if (Objects.equals(prior.value(), entry.getValue())) continue;
                changes.put(group, entry.getValue());
                Map<String, Long> fieldContext = new LinkedHashMap<>(prior.vector().counters());
                fieldContext.put(dotSite, counter - 1);
                contexts.put(group, Map.copyOf(fieldContext));
            }
            if (changes.isEmpty()) return null;

            if (state == null) {
                UUID pendingEventId = UUID.randomUUID();
                ProfileEvents.Operation pending = new ProfileEvents.Operation(identity, epoch, baseline.id(),
                        baseline.origin(), baseline.hash(), baseline.value(), changes, contexts, dotSite, counter);
                store.insertOperation(pendingEventId, pending);
                VersionVector next = current.increment(dotSite);
                for (var change : changes.entrySet()) {
                    VersionVector fieldNext = new VersionVector(contexts.get(change.getKey())).increment(dotSite);
                    store.applyField(identity, change.getKey(), change.getValue(), fieldNext, pendingEventId);
                    store.setFieldDirty(identity, change.getKey(), true);
                }
                store.setDomainVector(identity, next);
                jdbc.update("UPDATE eatwhat_sync.profile_domain SET local_dirty=true WHERE subject_gid=?", identity);
                return null;
            }

            UUID eventId = UUID.randomUUID();
            ProfileEvents.Operation operation = new ProfileEvents.Operation(identity, epoch, baseline.id(),
                    baseline.origin(), baseline.hash(), baseline.value(), changes, contexts, dotSite, counter);
            store.insertOperation(eventId, operation);
            VersionVector next = current.increment(dotSite);
            for (var change : changes.entrySet()) {
                VersionVector fieldNext = new VersionVector(contexts.get(change.getKey())).increment(dotSite);
                store.applyField(identity, change.getKey(), change.getValue(), fieldNext, eventId);
            }
            store.setDomainVector(identity, next);

            if (!consent.replicationAllowed(userId)) {
                for (String group : changes.keySet()) store.setFieldDirty(identity, group, true);
                jdbc.update("UPDATE eatwhat_sync.profile_domain SET local_dirty=true WHERE subject_gid=?", identity);
                return null;
            }
            append(eventId, userId, operation);
            for (String group : changes.keySet()) store.setFieldDirty(identity, group, false);
            jdbc.update("UPDATE eatwhat_sync.profile_domain SET local_dirty=false,published_epoch=? WHERE subject_gid=?",
                    epoch, identity);
            jdbc.update("UPDATE eatwhat_sync.profile_domain SET local_dirty=false WHERE subject_gid=?", identity);
            return null;
        });
    }

    /** Reconstructs the current profile bridge for every newly granted consent epoch. */
    public void runOnce() {
        if (Thread.currentThread().isInterrupted() || !workerActive.compareAndSet(false, true)) return;
        try {
            if(accounts!=null&&accounts.enabled())reassessAuthorizations();
            List<ReadySubject> ready = jdbc.query("""
                    SELECT s.user_id,s.subject_id,s.epoch FROM eatwhat_consent.subject s
                    JOIN public.users u ON u.id=s.user_id
                    LEFT JOIN eatwhat_sync.profile_domain d ON d.subject_gid=s.subject_id
                    WHERE s.status='GRANTED' AND s.identity_ready AND s.withdrawal_request_id IS NULL
                      AND s.policy_version=? AND NOT u.banned AND (NOT ? OR s.valid_until>clock_timestamp())
                      AND (NOT ? OR d.subject_gid IS NOT NULL)
                      AND (d.subject_gid IS NULL OR d.published_epoch<s.epoch OR d.local_dirty)
                    ORDER BY s.subject_id LIMIT 64
                    """, (row, index) -> new ReadySubject(row.getLong(1), row.getObject(2, UUID.class), row.getLong(3)),
                    consent.policyVersion(), site.getMode() == SiteProperties.Mode.EXTERNAL && !consent.multiwriterEnabled(),
                    site.getMode() == SiteProperties.Mode.EXTERNAL && !multiwriter);
            for (ReadySubject subject : ready) {
                if (Thread.currentThread().isInterrupted()) return;
                try { transactions.required(() -> publishCurrent(subject)); }
                catch (RuntimeException retryLater) { /* A later account cycle retries the durable domain state. */ }
            }
        } finally { workerActive.set(false); }
    }

    private void reassessAuthorizations() {
        var rows=jdbc.queryForList("SELECT event_id,actor_gid,evidence::text,operation::text FROM eatwhat_sync.profile_authorization WHERE operation IS NOT NULL AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.profile_authorization_review r WHERE r.event_id=profile_authorization.event_id) AND (?::uuid IS NULL OR event_id>?::uuid) ORDER BY event_id LIMIT 64",authorizationScanCursor,authorizationScanCursor);
        authorizationScanCursor=rows.isEmpty()?null:(UUID)rows.getLast().get("event_id");
        for(var row:rows)transactions.required(()->{
            var proof=JSON.readValue((String)row.get("evidence"),you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.AuthorizationEvidence.class);
            var assessment=accounts.assess(proof);
            if(assessment==you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.CONCURRENT_REVOCATION_REVIEW||assessment==you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.KNOWN_REVOKED){
                var op=ProfileEvents.parse((String)row.get("operation"));
                jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",r->{},op.subject());
                new ProfileAuthorizationReviews(jdbc,store).record((UUID)row.get("event_id"),(UUID)row.get("actor_gid"),op,assessment.name());
            }return null;
        });
    }

    private Void publishCurrent(ReadySubject candidate) {
        jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",
                (row, index) -> row.getLong(1), candidate.subject());
        ConsentModels.State state = consent.getBySubject(candidate.subject());
        if (state == null || state.epoch() != candidate.epoch() || !consent.replicationAllowed(candidate.userId())) return null;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.users WHERE id=?)",
                Boolean.class, candidate.userId()))) return null;

        ProfileStore.Baseline baseline = store.baseline(candidate.subject()).orElse(null);
        Map<String, Object> snapshot = store.readPublicProfile(candidate.userId());
        if (baseline == null && multiwriter) { initializeIndependent(candidate.userId(),snapshot); baseline=store.baseline(candidate.subject()).orElseThrow(); }
        if (baseline == null) {
            if (site.getMode() != SiteProperties.Mode.CAMPUS) return null;
            baseline = store.createCampusBaseline(candidate.subject(), site.getId(), snapshot);
            store.insertInitialFieldValues(candidate.subject(), snapshot);
        }
        VersionVector current = store.beginEpoch(candidate.subject(), state.epoch());
        Map<String, Object> changes = Map.of();
        Map<String, Map<String, Long>> contexts = Map.of();
        Map<String, List<ProfileEvents.Head>> headBridge = completeProfileBridge(candidate.subject(), baseline.value(),
                conflictHeads(candidate.subject()));
        Map<String, ProfileEvents.ResolutionProof> resolutionBridge = resolutionBridge(candidate.subject());
        String dotSite = site.getId();
        long counter = Math.max(1L, current.counters().getOrDefault(dotSite, 0L));
        UUID eventId = UUID.randomUUID();
        ProfileEvents.Operation operation = new ProfileEvents.Operation(candidate.subject(), state.epoch(), baseline.id(),
                baseline.origin(), baseline.hash(), baseline.value(), changes, contexts, dotSite, counter, null,
                headBridge, resolutionBridge);
        store.insertOperation(eventId, operation);
        append(eventId, candidate.userId(), operation);
        jdbc.update("UPDATE eatwhat_sync.profile_field_state SET local_dirty=false WHERE subject_gid=?", candidate.subject());
        jdbc.update("UPDATE eatwhat_sync.profile_domain SET active_epoch=?,published_epoch=?,local_dirty=false WHERE subject_gid=?",
                state.epoch(), state.epoch(), candidate.subject());
        return null;
    }

    private Map<String, List<ProfileEvents.Head>> conflictHeads(UUID subject) {
        return jdbc.query("""
                SELECT field_group,heads::text FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND status='OPEN' ORDER BY field_group
                """, result -> {
            Map<String, List<ProfileEvents.Head>> heads = new LinkedHashMap<>();
            while (result.next()) {
                String group = result.getString(1);
                JsonNode rows = JSON.readTree(result.getString(2));
                List<ProfileEvents.Head> groupHeads = new java.util.ArrayList<>();
                var vectorType = JSON.getTypeFactory().constructMapType(Map.class,
                        JSON.getTypeFactory().constructType(String.class),
                        JSON.getTypeFactory().constructType(Long.class));
                for (JsonNode head : rows) {
                    Map<String, Long> vector = JSON.convertValue(head.path("vector"), vectorType);
                    groupHeads.add(new ProfileEvents.Head(UUID.fromString(head.path("id").stringValue()),
                            JSON.convertValue(head.get("value"), Object.class), Map.copyOf(vector)));
                }
                heads.put(group, List.copyOf(groupHeads));
            }
            return heads;
        }, subject);
    }

    private Map<String, List<ProfileEvents.Head>> completeProfileBridge(UUID subject, Map<String, Object> baseline,
                                                                         Map<String, List<ProfileEvents.Head>> conflicts) {
        Map<String, List<ProfileEvents.Head>> result = new LinkedHashMap<>();
        for (String group : List.of("avatar", "gender", "birthday", "signature", "hometown")) {
            List<ProfileEvents.Head> current = conflicts.get(group);
            if (current == null || current.isEmpty()) current = store.fieldHeads(subject, group, baseline.get(group));
            var combined = new java.util.LinkedHashMap<UUID, ProfileEvents.Head>();
            for (ProfileEvents.Head head : latestRetiredHeads(subject, group)) combined.put(head.id(), head);
            ProfileEvents.Head resolutionHead = latestResolutionHead(subject, group);
            if (resolutionHead != null) combined.put(resolutionHead.id(), resolutionHead);
            for (ProfileEvents.Head head : current) combined.put(head.id(), head);
            if (combined.isEmpty()) {
                UUID baselineHead = UUID.nameUUIDFromBytes((subject + ":baseline:" + group)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                combined.put(baselineHead, new ProfileEvents.Head(baselineHead, baseline.get(group), Map.of()));
            }
            result.put(group, List.copyOf(combined.values()));
        }
        return result;
    }

    private List<ProfileEvents.Head> latestRetiredHeads(UUID subject, String group) {
        var rows = jdbc.query("""
                SELECT retired_heads::text FROM eatwhat_sync.profile_conflict
                WHERE subject_gid=? AND field_group=?
                """, (row, index) -> row.getString(1), subject, group);
        if (rows.isEmpty()) return List.of();
        JsonNode json = JSON.readTree(rows.getFirst());
        var result = new java.util.ArrayList<ProfileEvents.Head>();
        var vectorType = JSON.getTypeFactory().constructMapType(Map.class,
                JSON.getTypeFactory().constructType(String.class), JSON.getTypeFactory().constructType(Long.class));
        for (JsonNode head : json) {
            result.add(new ProfileEvents.Head(UUID.fromString(head.path("id").stringValue()),
                    JSON.convertValue(head.get("value"), Object.class),
                    JSON.convertValue(head.path("vector"), vectorType)));
        }
        return List.copyOf(result);
    }

    private Map<String, ProfileEvents.ResolutionProof> resolutionBridge(UUID subject) {
        record Decision(String group,ProfileEvents.ResolutionProof proof,VersionVector vector) { }
        var decisions=jdbc.query("""
            SELECT o.event_id,o.origin_site,o.dot_counter,o.resolution::text,o.context::text,
              jsonb_object_keys(o.changes) AS field_group
            FROM eatwhat_sync.profile_operation o WHERE o.subject_gid=? AND o.resolution IS NOT NULL
            """,(r,n)->{
                var resolution=JSON.readTree(r.getString(4));String group=r.getString(6);
                var context=EventCodec.vectorFromJson(JSON.readTree(r.getString(5)).path(group).toString());
                var vector=new LinkedHashMap<>(context.counters());vector.put(r.getString(2),r.getLong(3));
                var expected=new java.util.ArrayList<UUID>();for(var head:resolution.path("expectedHeads"))expected.add(UUID.fromString(head.stringValue()));
                return new Decision(group,new ProfileEvents.ResolutionProof(r.getObject(1,UUID.class),UUID.fromString(resolution.path("selectedHeadId").stringValue()),List.copyOf(expected)),new VersionVector(vector));
            },subject);
        var result=new LinkedHashMap<String,ProfileEvents.ResolutionProof>();
        for(String group:List.of("avatar","gender","birthday","signature","hometown")) {
            var maximal=decisions.stream().filter(d->d.group().equals(group)).filter(d->decisions.stream().noneMatch(other->other.group().equals(group)&&other.vector().compare(d.vector())==VersionVector.Relation.AFTER)).toList();
            if(maximal.size()==1)result.put(group,maximal.getFirst().proof());
        }
        return result;
    }

    private ProfileEvents.Head latestResolutionHead(UUID subject, String group) {
        var rows = jdbc.query("""
                SELECT a.event_id,o.origin_site,o.dot_counter,o.context->? AS field_context,
                       o.changes->? AS field_value
                FROM eatwhat_sync.profile_conflict c
                JOIN eatwhat_sync.profile_conflict_audit a ON a.conflict_id=c.conflict_id
                JOIN eatwhat_sync.profile_operation o ON o.event_id=a.event_id
                WHERE c.subject_gid=? AND c.field_group=? AND o.resolution IS NOT NULL
                ORDER BY a.expected_revision DESC LIMIT 1
                """, (row, index) -> new Object[]{row.getObject(1, UUID.class), row.getString(2), row.getLong(3),
                row.getString(4), row.getString(5)}, group, group, subject, group);
        if (rows.isEmpty() || rows.getFirst()[3] == null || rows.getFirst()[4] == null) return null;
        Object[] row = rows.getFirst();
        VersionVector context = EventCodec.vectorFromJson((String) row[3]);
        Map<String, Long> counters = new LinkedHashMap<>(context.counters());
        counters.merge((String) row[1], (Long) row[2], Math::max);
        Object value = JSON.convertValue(JSON.readTree((String) row[4]), Object.class);
        return new ProfileEvents.Head((UUID) row[0], value, new VersionVector(counters).counters());
    }

    public boolean resolveConflict(UUID conflictId, long expectedRevision, UUID selectedHeadId, long administratorId) {
        if (!multiwriter && site.getMode() != SiteProperties.Mode.CAMPUS) throw new IllegalStateException("PROFILE_RESOLUTION_CAMPUS_ONLY");
        return transactions.required(() -> {
            UUID subject = jdbc.query("""
                    SELECT subject_gid FROM eatwhat_sync.profile_conflict
                    WHERE conflict_id=? AND status='OPEN'
                    """, (row, index) -> row.getObject(1, UUID.class), conflictId)
                    .stream().findFirst().orElse(null);
            if (subject == null) return false;
            // Use the same lock order as local edits and inbound profile operations.
            jdbc.query("SELECT user_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",
                    (row, index) -> row.getLong(1), subject);
            var rows = jdbc.query("""
                    SELECT subject_gid,field_group,revision,heads::text FROM eatwhat_sync.profile_conflict
                    WHERE conflict_id=? AND subject_gid=? AND status='OPEN' FOR UPDATE
                    """, (row, index) -> new Object[]{row.getObject(1, UUID.class), row.getString(2),
                    row.getLong(3), row.getString(4)}, conflictId, subject);
            if (rows.isEmpty()) return false;
            Object[] conflict = rows.getFirst();
            String group = (String) conflict[1];
            long revision = (Long) conflict[2];
            if (revision != expectedRevision) return false;
            ConsentModels.State consentState = consent.getBySubject(subject);
            if (consentState == null || !consent.replicationAllowed(consentState.userId())) return false;
            ProfileStore.Baseline baseline = store.baseline(subject)
                    .orElseThrow(() -> new IllegalStateException("PROFILE_BASELINE_REQUIRED"));
            JsonNode heads = JSON.readTree((String) conflict[3]);
            JsonNode selected = null;
            VersionVector context = store.beginEpoch(subject, consentState.epoch());
            java.util.List<UUID> expectedHeads = new java.util.ArrayList<>();
            for (JsonNode head : heads) {
                UUID headId = UUID.fromString(head.path("id").stringValue());
                expectedHeads.add(headId);
                if (selectedHeadId.equals(headId)) selected = head;
                if (!head.path("vector").isObject()) throw new IllegalStateException("PROFILE_CONFLICT_CORRUPT");
                context = merge(context, EventCodec.vectorFromJson(head.path("vector").toString()));
            }
            if (selected == null || !selected.has("value")) return false;
            Object selectedValue = JSON.convertValue(selected.get("value"), Object.class);
            UUID eventId = UUID.randomUUID();
            String dotSite = site.getId();
            long counter = context.counters().getOrDefault(dotSite, 0L) + 1;
            context = new VersionVector(context.counters());
            Map<String, Object> changes = new LinkedHashMap<>(); changes.put(group, selectedValue);
            Map<String, Map<String, Long>> contexts = new LinkedHashMap<>(); contexts.put(group, context.counters());
            ProfileEvents.Operation operation = new ProfileEvents.Operation(subject, consentState.epoch(), baseline.id(),
                    baseline.origin(), baseline.hash(), baseline.value(), changes, contexts, dotSite, counter,
                    new ProfileEvents.Resolution(conflictId, expectedRevision, selectedHeadId,
                            expectedHeads.stream().distinct().sorted().toList()));
            store.applyPublicValue(consentState.userId(), group, selectedValue);
            VersionVector next = context.increment(dotSite);
            store.applyField(subject, group, selectedValue, next, eventId);
            store.setDomainVector(subject, next);
            if (jdbc.update("""
                    UPDATE eatwhat_sync.profile_conflict SET status='RESOLVED',resolved_head=?,
                      retired_heads=retired_heads || heads,updated_at=clock_timestamp()
                    WHERE conflict_id=? AND status='OPEN' AND revision=?
                    """, selectedHeadId, conflictId, expectedRevision) != 1) return false;
            jdbc.update("""
                    INSERT INTO eatwhat_sync.profile_conflict_audit(audit_id,conflict_id,expected_revision,selected_head,administrator_id,event_id)
                    VALUES(?,?,?,?,?,?)
                    """, UUID.randomUUID(), conflictId, expectedRevision, selectedHeadId, administratorId, eventId);
            store.insertOperation(eventId, operation);
            append(eventId, consentState.userId(), operation, administratorId);
            if(multiwriter)new ProfileAuthorizationReviews(jdbc,store).decided(subject,eventId,expectedHeads);
            return true;
        });
    }

    private static VersionVector merge(VersionVector left, VersionVector right) {
        Map<String, Long> values = new LinkedHashMap<>(left.counters());
        right.counters().forEach((key, value) -> values.merge(key, value, Math::max));
        return new VersionVector(values);
    }

    private void append(UUID eventId, long userId, ProfileEvents.Operation operation) { append(eventId,userId,operation,null); }
    private void append(UUID eventId, long userId, ProfileEvents.Operation operation, Long administratorId) {
        UUID actor=operation.subject();String payload=ProfileEvents.payload(operation);
        if(multiwriter){
            you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.AuthorizationEvidence proof=null;
            if(administratorId!=null){
                if(accounts==null)throw new IllegalStateException("PROFILE_ACCOUNT_AUTHORITY_REQUIRED");
                proof=accounts.capture(administratorId);
                var decision=accounts.assess(proof);
                if(!proof.role().equals("admin")||proof.banned()||decision!=you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.Assessment.AUTHORIZED)
                    throw new IllegalStateException("PROFILE_ADMIN_AUTHORIZATION_REQUIRED");
                actor=proof.subjectId();
                jdbc.update("INSERT INTO eatwhat_sync.profile_authorization(event_id,subject_gid,actor_gid,evidence,outcome,operation) VALUES(?,?,?,?::jsonb,'AUTHORIZED',?::jsonb)",eventId,operation.subject(),actor,JSON.writeValueAsString(proof),ProfileEvents.payload(operation));
            }
            var authorizations=new LinkedHashMap<UUID,you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.AuthorizationEvidence>();
            var resolutionOperations=new LinkedHashMap<UUID,ProfileEvents.Operation>();
            if(!operation.headBridge().isEmpty())jdbc.query("SELECT event_id,evidence::text,operation::text FROM eatwhat_sync.profile_authorization WHERE subject_gid=? AND operation IS NOT NULL AND outcome IN ('AUTHORIZED','HISTORICAL_VALID')",r->{UUID id=r.getObject(1,UUID.class);if(operation.resolutionBridge().values().stream().anyMatch(p->p.eventId().equals(id))){authorizations.put(id,JSON.readValue(r.getString(2),you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.AuthorizationEvidence.class));resolutionOperations.put(id,ProfileEvents.parse(r.getString(3)));}},operation.subject());
            payload=new ProfileV2Envelope(operation,proof,Map.copyOf(authorizations),Map.copyOf(resolutionOperations)).payload();
        }

        long numericId = jdbc.queryForObject("SELECT eatwhat_sync.next_relation_number()", Long.class);
        SyncEvent event = new SyncEvent(eventId, site.getId(), site.getInstanceId(), ProfileEvents.AGGREGATE,
                eventId, numericId, ProfileEvents.EVENT, multiwriter?2:ProfileEvents.SCHEMA_VERSION, VersionVector.empty(),
                VersionVector.empty().increment(site.getId()),
                ProfileEvents.dependencies(operation,actor), actor,
                "profile:" + eventId, eventId, payload);
        events.validate(event, new NodeIdentity(site.getId(), site.getInstanceId()));
        outbox.append(event, you.v50to.eatwhat.cluster.ClusterRouting.legacyRecipients(site,peer));
    }

    private void initializeIndependent(long userId,Map<String,Object> before) {
        UUID subject=identities.findGlobalId(EntityType.USERS,userId).orElseThrow();
        if(store.baseline(subject).isPresent())return;
        var baseline=store.createIndependentBaseline(subject);store.insertInitialFieldValues(subject,baseline.value());
        var state=consent.get(userId);long epoch=state==null?0:state.epoch();
        var changes=new LinkedHashMap<String,Object>();var contexts=new LinkedHashMap<String,Map<String,Long>>();
        for(var e:before.entrySet())if(!Objects.equals(e.getValue(),baseline.value().get(e.getKey()))) { changes.put(e.getKey(),e.getValue());contexts.put(e.getKey(),Map.of()); }
        if(changes.isEmpty())return;
        UUID event=UUID.randomUUID();var op=new ProfileEvents.Operation(subject,epoch,baseline.id(),baseline.origin(),baseline.hash(),baseline.value(),changes,contexts,site.getId(),1);
        store.insertOperation(event,op);var vector=VersionVector.empty().increment(site.getId());store.setDomainVector(subject,vector);
        for(var change:changes.entrySet()){store.applyField(subject,change.getKey(),change.getValue(),vector,event);store.setFieldDirty(subject,change.getKey(),true);}
        jdbc.update("UPDATE eatwhat_sync.profile_domain SET local_dirty=true WHERE subject_gid=?",subject);
        if(consent.replicationAllowed(userId))append(event,userId,op);
    }

    private static Map<String, Object> requestedGroups(UpdateUserInfoDTO dto, Map<String, Object> after) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (dto.getAvatar() != null) result.put("avatar", after.get("avatar"));
        if (dto.getGender() != null) result.put("gender", after.get("gender"));
        if (dto.getBirthday() != null) result.put("birthday", after.get("birthday"));
        if (dto.getSignature() != null) result.put("signature", after.get("signature"));
        if (dto.getHometownProvinceId() != null || dto.getHometownCityId() != null) {
            result.put("hometown", after.get("hometown"));
        }
        return result;
    }
}
