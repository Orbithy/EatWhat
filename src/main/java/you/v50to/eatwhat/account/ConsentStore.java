package you.v50to.eatwhat.account;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.SyncTransactions;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static you.v50to.eatwhat.account.ConsentModels.*;

/** Same-database account consent state. Account payloads exist only in memory and business tables. */
public final class ConsentStore {
    private ConsentPolicyService policySettings;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setPolicySettings(ConsentPolicyService settings){this.policySettings=settings;}
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts multiwriter;
    public boolean multiwriterEnabled(){return multiwriter!=null&&multiwriter.enabled();}
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> ACCOUNT_COLUMNS = Map.of(
            "users", "id,nick_name,display_name,password_hash,avatar,created_at,updated_at,banned,ban_reason,banned_at,role",
            "contacts", "id,account_id,email,phone,created_at,updated_at",
            "verifications", "id,account_id,method,verified,student_id,real_name,verified_email,created_at,updated_at",
            "user_info", "id,gender,birthday,signature,location_province_id,location_city_id,hometown_province_id,hometown_city_id,created_at,updated_at",
            "privacy", "id,account_id,following,follower,created_at");
    private final JdbcTemplate jdbc;
    private final SyncTransactions transactions;
    private final SiteProperties site;
    private final String policy;
    private final long ttlMillis;
    private final long proofMillis;
    private final String peerSite;
    private final UUID peerInstance;
    private final you.v50to.eatwhat.cluster.ClusterMembershipView membership;
    private final you.v50to.eatwhat.cluster.ClusterRecipientConsent recipients;

    public static final class ConsentException extends IllegalStateException {
        private final String code;
        public ConsentException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    public ConsentStore(JdbcTemplate jdbc, SyncTransactions transactions, SiteProperties site, String policyVersion,
                        Duration authorizationTtl, Duration proofTtl, String peerSiteId, UUID peerInstanceId) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        transactions.requireSameDataSource(jdbc);
        this.site = Objects.requireNonNull(site);
        SyncEvent.requireName(policyVersion, "policyVersion", 128);
        if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)||site.getMode()!=SiteProperties.Mode.CAMPUS||peerSiteId!=null||peerInstanceId!=null){
            SyncEvent.requireName(peerSiteId, "peerSiteId", 64);
            SyncEvent.requireUuid(peerInstanceId, "peerInstanceId");
        }
        this.policy = policyVersion;
        this.ttlMillis = millis(authorizationTtl, Duration.ofMinutes(5));
        this.proofMillis = millis(proofTtl, Duration.ofMinutes(5));
        this.peerSite = peerSiteId;
        this.peerInstance = peerInstanceId;
        this.membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
        this.recipients=new you.v50to.eatwhat.cluster.ClusterRecipientConsent(jdbc);
    }

    public State onRegistered(long userId, boolean grant, String acceptedPolicy) {
        if(multiwriterEnabled()) return transactions.required(()->{ if(external()&&!grant)throw failure("CONSENT_REQUIRED");if(grant)requirePolicy(acceptedPolicy);multiwriter.registered(userId,grant,policyVersion());return get(userId); });
        return transactions.required(() -> {
            if (external() && !grant) throw failure("CONSENT_REQUIRED");
            if (grant) requirePolicy(acceptedPolicy);
            State existing = locked(userId);
            if (existing != null) return existing;
            // Establish the initial revision boundary against writes that began before consent existed.
            // The parent row lock also serializes new child INSERT foreign-key checks.
            lockInitialAccount(userId);
            existing=locked(userId);
            if(existing!=null) return existing;
            Identity identity = identity(userId);
            String status = grant ? (external() ? "PROVISIONAL" : "GRANTED") : "NOT_GRANTED";
            int inserted = jdbc.update("""
                    INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,revision,status,policy_version,
                      valid_until,identity_ready,backfill_status,source_archived)
                    VALUES(?,?,?, ?,1,?,?,NULL,false,?,?) ON CONFLICT DO NOTHING
                    """, identity.gid(), userId, identity.origin(), grant ? 1L : 0L, status, policyVersion(),
                    grant ? "ACCOUNT_PENDING" : "NOT_GRANTED", !external());
            State state = locked(userId);
            if (inserted==0) return state;
            if (grant) queue(state, external() ? "REGISTER" : "UPSERT");
            return state;
        });
    }

    public State get(long userId) { return read("user_id=?", userId, false); }
    public State getBySubject(UUID subjectId) { return read("subject_id=?", subjectId, false); }
    public String configuredPolicyVersion() { return policy; }
    public String policyVersion() { return policySettings==null?policy:policySettings.current().version(); }

    public void prepareMultiwriterSubject(Snapshot snapshot) {
        validateSnapshot(snapshot);
        if(!createRemoteState(snapshot,(snapshot.data()==null||snapshot.data().isNull())?inferredOrigin(snapshot.userId()):origin(snapshot)))throw failure("IDENTITY_CONFLICT");
    }
    public void finishMultiwriterProjection(long userId) {
        State state=locked(userId);if(state==null||!state.status().equals("WITHDRAWN"))return;
        jdbc.update("DELETE FROM eatwhat_account_multiwriter.transfer_page WHERE subject_id=?",state.subjectId());
        if(external()) {jdbc.update("UPDATE eatwhat_consent.subject SET cleanup_status='PENDING' WHERE user_id=? AND cleanup_status NOT LIKE 'ACCOUNT_%'",userId);queue(state,"CLEANUP");}
    }
    public void acknowledgeMultiwriterArchive(long userId) {jdbc.update("UPDATE eatwhat_consent.subject SET source_archived=true WHERE user_id=? AND status='GRANTED'",userId);}
    public State grant(long userId, String acceptedPolicy) {
        return transactions.required(()->{
            requirePolicy(acceptedPolicy);
            if(multiwriterEnabled()){multiwriter.consent(userId,true,policyVersion());return get(userId);}
            authority(); return grantLocked(ensure(userId));
        });
    }

    private State grantLocked(State state) {
        requireActiveAccount(state.userId());
        if (state.status().equals("GRANTED") && state.policyVersion().equals(policyVersion())
                && state.withdrawalRequestId() == null) return state;
        jdbc.update("""
                UPDATE eatwhat_consent.subject SET epoch=epoch+1,revision=revision+1,status='GRANTED',policy_version=?,
                  valid_until=NULL,identity_ready=false,backfill_status='ACCOUNT_PENDING',cleanup_status='NOT_REQUIRED',
                  withdrawal_request_id=NULL,local_dirty=false WHERE subject_id=?
                """, policyVersion(), state.subjectId());
        State granted = get(state.userId()); queue(granted, "UPSERT"); return granted;
    }

    public State withdraw(long userId) {
        if(multiwriterEnabled())return transactions.required(()->{multiwriter.consent(userId,false,policyVersion());finishMultiwriterProjection(userId);return get(userId);});
        return transactions.required(() -> {
            State state = external() ? locked(userId) : ensure(userId);
            if (state == null) throw failure("ACCOUNT_UNAVAILABLE");
            if (state.status().equals("WITHDRAWN")) return state;
            UUID request = external() ? UUID.randomUUID() : null;
            jdbc.update("""
                    UPDATE eatwhat_consent.subject SET epoch=epoch+?,revision=revision+1,status='WITHDRAWN',valid_until=NULL,
                      identity_ready=false,backfill_status='WITHDRAWN',cleanup_status='PENDING',withdrawal_request_id=?,
                      effects_pending=?,effects_version=effects_version+1 WHERE subject_id=?
                    """, external() ? 0 : 1, request, external(), state.subjectId());
            State withdrawn = get(userId);
            queue(withdrawn, external() ? "WITHDRAW_REQUEST" : "PURGE");
            if (external()) {
                if (!archived(userId)) queue(withdrawn, "REGISTER");
                clearLocked(withdrawn);
            }
            return get(userId);
        });
    }

    public State acceptWithdrawal(UUID subjectId, UUID requestId) {
        authority(); SyncEvent.requireUuid(requestId, "requestId");
        return transactions.required(() -> {
            State state = read("subject_id=?", subjectId, true);
            if (state == null) throw failure("ACCOUNT_UNAVAILABLE");
            var old = jdbc.queryForList("SELECT subject_id FROM eatwhat_consent.withdrawal_receipt WHERE request_id=?", UUID.class, requestId);
            if (!old.isEmpty()) {
                if (!old.getFirst().equals(subjectId)) throw failure("IDENTITY_CONFLICT");
                return state;
            }
            if (!state.status().equals("WITHDRAWN")) {
                jdbc.update("""
                        UPDATE eatwhat_consent.subject SET epoch=epoch+1,revision=revision+1,status='WITHDRAWN',valid_until=NULL,
                          identity_ready=false,backfill_status='WITHDRAWN',cleanup_status='PENDING',withdrawal_request_id=? WHERE subject_id=?
                        """, requestId, subjectId);
            } else jdbc.update("UPDATE eatwhat_consent.subject SET withdrawal_request_id=? WHERE subject_id=?", requestId, subjectId);
            State withdrawn = getBySubject(subjectId);
            jdbc.update("INSERT INTO eatwhat_consent.withdrawal_receipt(request_id,subject_id,authority_epoch) VALUES(?,?,?)",
                    requestId, subjectId, withdrawn.epoch());
            queue(withdrawn, "PURGE"); return withdrawn;
        });
    }

    /** A replay acknowledges the original withdrawal, never a later regrant's epoch. */
    public long withdrawalEpoch(UUID subjectId, UUID requestId) {
        authority();
        var epochs=jdbc.queryForList("SELECT authority_epoch FROM eatwhat_consent.withdrawal_receipt WHERE subject_id=? AND request_id=?",
                Long.class,subjectId,requestId);
        if(epochs.isEmpty()) throw failure("IDENTITY_CONFLICT");
        return epochs.getFirst();
    }

    public String issueProof(long userId, String audience) {
        if(!multiwriterEnabled())authority(); requireAudience(audience);
        return transactions.required(() -> {
            State state = ensure(userId);
            String fingerprint = securityFingerprint(userId);
            byte[] entropy = new byte[32]; RANDOM.nextBytes(entropy);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            jdbc.update("""
                    INSERT INTO eatwhat_consent.proof(token_hash,subject_id,epoch,source_revision,policy_version,audience,security_fingerprint,expires_at)
                    VALUES(?,?,?,?,?,?,?,clock_timestamp()+(? * interval '1 millisecond'))
                    """, EventCodec.sha256(token), state.subjectId(), state.epoch(), state.revision(), policyVersion(), audience, fingerprint, proofMillis);
            return token;
        });
    }

    public State grantWithProof(String proof, String audience, String acceptedPolicy) {
        if(!multiwriterEnabled())authority(); requireAudience(audience);
        if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) throw failure("PROOF_INVALID");
        String hash = EventCodec.sha256(proof);
        return transactions.required(() -> {
            requirePolicy(acceptedPolicy);
            var subjects = jdbc.queryForList("SELECT subject_id FROM eatwhat_consent.proof WHERE token_hash=?", UUID.class, hash);
            if (subjects.isEmpty()) throw failure("PROOF_INVALID");
            State state = read("subject_id=?", subjects.getFirst(), true);
            var rows = jdbc.queryForList("""
                    SELECT epoch,source_revision,policy_version,audience,security_fingerprint,consumed_at,
                      expires_at>clock_timestamp() AS valid FROM eatwhat_consent.proof WHERE token_hash=? FOR UPDATE
                    """, hash);
            if (rows.isEmpty() || state == null) throw failure("PROOF_INVALID");
            Map<String, Object> row = rows.getFirst();
            if (row.get("consumed_at") != null || !Boolean.TRUE.equals(row.get("valid"))
                    || ((Number) row.get("epoch")).longValue() != state.epoch()
                    || ((Number) row.get("source_revision")).longValue() != state.revision() || !policyVersion().equals(row.get("policy_version"))
                    || !audience.equals(row.get("audience")) || !securityFingerprint(state.userId()).equals(row.get("security_fingerprint"))) {
                throw failure("PROOF_INVALID");
            }
            jdbc.update("UPDATE eatwhat_consent.proof SET consumed_at=clock_timestamp() WHERE token_hash=?", hash);
            if(multiwriterEnabled()){multiwriter.consent(state.userId(),true,policyVersion());return get(state.userId());}
            return grantAudience(grantLocked(state),audience);
        });
    }

    public boolean isAuthorized(long userId) {
        if (!external()) return true;
        if(multiwriterEnabled())return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_consent.subject s JOIN users u ON u.id=s.user_id WHERE s.user_id=? AND s.status='GRANTED' AND s.identity_ready AND s.withdrawal_request_id IS NULL AND s.policy_version=? AND NOT u.banned)",Boolean.class,userId,policyVersion()));
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_consent.subject s JOIN public.users u ON u.id=s.user_id
                  WHERE s.user_id=? AND s.status='GRANTED' AND s.identity_ready AND s.withdrawal_request_id IS NULL
                    AND s.policy_version=? AND s.valid_until>clock_timestamp() AND NOT u.banned)
                """, Boolean.class, userId, policyVersion()));
    }

    /**
     * Whether this user's domain content may cross sites. Unlike {@link #isAuthorized}, campus users need an
     * explicit, projected grant too: local use never implies cross-border consent.
     */
    public boolean replicationAllowed(long userId) {
        return replicationAllowed(userId,false);
    }

    /** Only for already-durable removals: a later ban must not strand a deletion on its peer. */
    public boolean removalReplicationAllowed(long userId) {
        return replicationAllowed(userId,true);
    }

    private boolean replicationAllowed(long userId,boolean removal) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_consent.subject s JOIN public.users u ON u.id=s.user_id
                  WHERE s.user_id=? AND s.status='GRANTED' AND s.identity_ready AND s.withdrawal_request_id IS NULL
                    AND s.policy_version=? AND (? OR NOT u.banned) AND (NOT ? OR s.valid_until>clock_timestamp()))
                """, Boolean.class, userId, policyVersion(), removal, external()&&!multiwriterEnabled()));
    }

    /** Both subjects are read in one statement snapshot for the final immutable-event send gate. */
    public record OutboundAuthorization(boolean allowed, boolean blocked) { }

    public OutboundAuthorization outboundAuthorization(UUID actor, long actorEpoch,
                                                        UUID owner, long ownerEpoch) {
        return outboundAuthorization(actor,actorEpoch,owner,ownerEpoch,false);
    }

    /** Immutable removal events still require current consent for every subject and the exact epoch. */
    public OutboundAuthorization outboundRemovalAuthorization(UUID actor,long actorEpoch,UUID owner,long ownerEpoch) {
        return outboundAuthorization(actor,actorEpoch,owner,ownerEpoch,true);
    }

    private OutboundAuthorization outboundAuthorization(UUID actor,long actorEpoch,UUID owner,long ownerEpoch,boolean removal) {
        String decision = jdbc.queryForObject("""
                WITH requested(subject_id, event_epoch) AS (
                  VALUES (?::uuid, ?::bigint), (?::uuid, ?::bigint)
                ), state AS (
                  SELECT requested.event_epoch, subject.epoch, subject.status, subject.identity_ready,
                         subject.withdrawal_request_id, subject.policy_version, subject.valid_until,
                         users.banned, users.id AS user_id
                  FROM requested
                  LEFT JOIN eatwhat_consent.subject AS subject USING (subject_id)
                  LEFT JOIN public.users AS users ON users.id=subject.user_id
                )
                SELECT CASE
                  WHEN bool_or(epoch > event_epoch OR (epoch = event_epoch AND
                       (withdrawal_request_id IS NOT NULL OR status='WITHDRAWN'))) THEN 'BLOCKED'
                  WHEN count(epoch)=2 AND bool_and(coalesce(
                       epoch=event_epoch AND status='GRANTED' AND identity_ready
                       AND withdrawal_request_id IS NULL AND policy_version=? AND user_id IS NOT NULL
                       AND (? OR NOT coalesce(banned,true))
                       AND (NOT ? OR valid_until>statement_timestamp()), false)) THEN 'ALLOW'
                  ELSE 'WAIT'
                END
                FROM state
                """, String.class, actor, actorEpoch, owner, ownerEpoch, policyVersion(), removal, external()&&!multiwriterEnabled());
        return new OutboundAuthorization("ALLOW".equals(decision), "BLOCKED".equals(decision));
    }

    public List<Job> claim(int limit, Duration lease) {
        if (limit<1 || limit>32) throw new IllegalArgumentException("Invalid consent batch limit");
        long leaseMillis = millis(lease, Duration.ofMinutes(5));
        return transactions.independent(() -> jdbc.query("""
                WITH candidates AS(SELECT job_id FROM eatwhat_consent.job
                  WHERE (status='READY' AND available_at<=clock_timestamp()) OR (status='LEASED' AND lease_until<=clock_timestamp())
                  ORDER BY available_at,job_id LIMIT ? FOR UPDATE SKIP LOCKED)
                UPDATE eatwhat_consent.job j SET status='LEASED',lease_token=gen_random_uuid(),
                  lease_until=clock_timestamp()+(? * interval '1 millisecond'),attempts=attempts+1
                FROM candidates c WHERE j.job_id=c.job_id
                RETURNING j.job_id,j.user_id,j.subject_id,j.epoch,j.revision,j.kind,j.lease_token,j.attempts
                """, (r,n) -> new Job(r.getObject(1,UUID.class),r.getLong(2),r.getObject(3,UUID.class),r.getLong(4),r.getLong(5),
                r.getString(6),r.getObject(7,UUID.class),r.getInt(8)), limit, leaseMillis));
    }

    public Snapshot export(Job job) {
        return transactions.independent(() -> {
            State state = locked(job.userId());
            if (state == null || !leaseCurrent(job)) return null;
            if (state.epoch()!=job.epoch() || state.revision()!=job.revision()) return null;
            if (job.kind().equals("WITHDRAW_REQUEST")) return snapshot(state, null, null);
            if (job.kind().equals("PURGE")) return state.status().equals("WITHDRAWN") ? snapshot(state, null, null) : null;
            if (external()) {
                if (!job.kind().equals("REGISTER") || !localOrigin(state.userId()) || archived(state.userId())) return null;
            } else if (!job.kind().equals("UPSERT") || !state.status().equals("GRANTED")) return null;
            if (!policyVersion().equals(state.policyVersion())) return null;
            Instant valid = external() ? null : dbNow().plusMillis(ttlMillis);
            return snapshot(state, valid, accountData(state.userId()));
        });
    }

    /** Authority catch-up projection; the caller must separately authorize the exact recipient. */
    public Snapshot exportCurrent(long userId) {
        authority();return transactions.independent(()->{
            var state=locked(userId);if(state==null||state.withdrawalRequestId()!=null||!policyVersion().equals(state.policyVersion()))return null;
            if(state.status().equals("WITHDRAWN"))return snapshot(state,null,null);
            if(!state.status().equals("GRANTED"))return null;
            return snapshot(state,dbNow().plusMillis(ttlMillis),accountData(userId));
        });
    }
    public Snapshot exportMultiwriter(long userId) {
        if(!multiwriterEnabled())throw failure("WRONG_SITE");
        return transactions.required(()->{var state=locked(userId);if(state==null||!policyVersion().equals(state.policyVersion()))return null;
            if(state.status().equals("WITHDRAWN"))return snapshot(state,null,null);
            if(!state.status().equals("GRANTED"))return null;
            return snapshot(state,null,accountData(userId));});
    }

    public String apply(Snapshot snapshot) {
        if (!external()) throw failure("WRONG_SITE");
        validateSnapshot(snapshot);
        return transactions.independent(() -> {
            State state = getBySubject(snapshot.subjectId());
            if (state == null) {
                if (snapshot.status().equals("WITHDRAWN")) {
                    // Minimal tombstone needs no placeholder business account.
                    if (!createRemoteState(snapshot, inferredOrigin(snapshot.userId()))) return conflictSnapshot(snapshot, "IDENTITY_COLLISION");
                } else if (!createRemoteState(snapshot, origin(snapshot))) return conflictSnapshot(snapshot, "IDENTITY_COLLISION");
            }
            state = read("subject_id=?", snapshot.subjectId(), true);
            if (state.userId()!=snapshot.userId()) return conflictSnapshot(snapshot,"IDENTITY_COLLISION");
            if(snapshot.status().equals("GRANTED")) {
                if(state.withdrawalRequestId()!=null || (state.status().equals("WITHDRAWN") && snapshot.epoch()<=state.epoch())) return "STALE";
                if(dirty(state.userId())) return conflictSnapshot(snapshot,"LOCAL_ACCOUNT_EDIT");
                if(localOrigin(state.userId()) && !archived(state.userId())) {
                    return state.backfillStatus().equals("CONFLICT")?"CONFLICT":"REJECTED";
                }
            }
            if (snapshot.epoch()<state.epoch() || (snapshot.epoch()==state.epoch() && snapshot.revision()<state.revision())) return "STALE";
            if (snapshot.status().equals("WITHDRAWN")) {
                jdbc.update("""
                        UPDATE eatwhat_consent.subject SET epoch=?,revision=?,status='WITHDRAWN',identity_ready=false,valid_until=NULL,
                          backfill_status='WITHDRAWN',cleanup_status='PENDING',source_archived=CASE WHEN origin_site=? THEN source_archived ELSE true END,
                          effects_pending=true,effects_version=effects_version+1,
                          withdrawal_request_id=CASE WHEN withdrawal_request_id=? AND source_archived THEN NULL ELSE withdrawal_request_id END WHERE subject_id=?
                        """, snapshot.epoch(), snapshot.revision(), site.getId(), snapshot.withdrawalRequestId(), snapshot.subjectId());
                clearLocked(getBySubject(snapshot.subjectId())); return "APPLIED";
            }
            if (!snapshot.status().equals("GRANTED") || !policyVersion().equals(snapshot.policyVersion()) || snapshot.validUntil()==null
                    || !snapshot.validUntil().isAfter(dbNow()) || snapshot.validUntil().isAfter(dbNow().plusMillis(ttlMillis+30_000))) return "REJECTED";
            if (state.withdrawalRequestId()!=null || (state.status().equals("WITHDRAWN") && snapshot.epoch()<=state.epoch())) return "STALE";
            if (dirty(state.userId())) return conflictSnapshot(snapshot,"LOCAL_ACCOUNT_EDIT");
            String payload=json(snapshot.data());
            String hash=EventCodec.sha256(EventCodec.canonicalJson(payload));
            String oldHash=jdbc.queryForObject("SELECT account_hash FROM eatwhat_consent.subject WHERE subject_id=?",String.class,snapshot.subjectId());
            if(state.epoch()==snapshot.epoch() && state.revision()==snapshot.revision() && oldHash!=null && !hash.equals(oldHash)) {
                if (managedDomainRenewal(state, snapshot.data())) {
                    // Domain events own these mutable fields. Renew authorization
                    // without importing them or replacing the original account hash.
                    refreshAuthorization(state, snapshot, true);
                    return "APPLIED";
                }
                return conflictSnapshot(snapshot,"SAME_VERSION_CHANGED");
            }
            boolean changed = state.epoch()!=snapshot.epoch() || state.revision()!=snapshot.revision() || !state.identityReady();
            if(!changed && hash.equals(oldHash) && state.status().equals("GRANTED")) {
                refreshAuthorization(state, snapshot, false);
                return "APPLIED";
            }
            jdbc.update("""
                    UPDATE eatwhat_consent.subject SET epoch=?,revision=?,status='GRANTED',policy_version=?,source_archived=true,
                      identity_ready=false,backfill_status='ACCOUNT_PENDING',effects_pending=effects_pending OR ?,
                      effects_version=effects_version+? WHERE subject_id=?
                    """, snapshot.epoch(),snapshot.revision(),policyVersion(),changed,changed?1:0,snapshot.subjectId());
            boolean imported = Boolean.TRUE.equals(jdbc.queryForObject("SELECT eatwhat_consent.import_account(?,?,?::jsonb)",Boolean.class,
                    snapshot.subjectId(),snapshot.userId(),payload));
            if (!imported) {
                jdbc.update("UPDATE eatwhat_consent.subject SET status='CONFLICT',backfill_status='CONFLICT',identity_ready=false WHERE subject_id=?",snapshot.subjectId());
                return conflictSnapshot(snapshot,"ACCOUNT_CONSTRAINT");
            }
            jdbc.update("""
                    UPDATE eatwhat_consent.subject SET identity_ready=true,backfill_status='ACCOUNT_COMPLETE_DOMAINS_PENDING',
                      cleanup_status='NOT_REQUIRED',account_hash=?,valid_until=LEAST(clock_timestamp()+(? * interval '1 millisecond'),
                        CASE WHEN ? THEN ?::timestamptz ELSE GREATEST(valid_until,?::timestamptz) END) WHERE subject_id=?
                    """,hash,ttlMillis,changed,Timestamp.from(snapshot.validUntil()),Timestamp.from(snapshot.validUntil()),snapshot.subjectId());
            return "APPLIED";
        });
    }

    private void refreshAuthorization(State state, Snapshot snapshot, boolean managedRenewal) {
        jdbc.update("""
                UPDATE eatwhat_consent.subject SET valid_until=LEAST(clock_timestamp()+(? * interval '1 millisecond'),
                  GREATEST(valid_until,?::timestamptz)),
                  backfill_status=CASE WHEN ? AND backfill_status='CONFLICT' THEN 'ACCOUNT_COMPLETE_DOMAINS_PENDING' ELSE backfill_status END
                WHERE subject_id=?
                """, ttlMillis, Timestamp.from(snapshot.validUntil()), managedRenewal, state.subjectId());
    }

    /** The existing local-dirty, epoch, policy and expiry guards have already
     * passed. Compare every account/identity/security field with the current
     * imported projection; omit only fields owned by established domain streams.
     * Nothing from the incoming mutable profile/privacy payload is imported. */
    private boolean managedDomainRenewal(State state, JsonNode incoming) {
        if (!state.identityReady() || !state.status().equals("GRANTED")) return false;
        boolean profile = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM eatwhat_sync.profile_domain WHERE subject_gid=? AND managed)", Boolean.class, state.subjectId()));
        boolean privacy = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM eatwhat_sync.privacy_domain WHERE subject_gid=? AND managed)", Boolean.class, state.subjectId()));
        if (!profile && !privacy) return false;
        return renewalAccountHash(incoming, profile, privacy).equals(
                renewalAccountHash(accountData(state.userId()), profile, privacy));
    }

    private String renewalAccountHash(JsonNode payload, boolean profile, boolean privacy) {
        var copy = (tools.jackson.databind.node.ObjectNode) payload.deepCopy();
        if (profile) {
            copy.remove("user_info");
            var users = copy.get("users");
            if (users == null || !users.isArray() || users.size() != 1 || !users.get(0).isObject())
                throw failure("ACCOUNT_UNAVAILABLE");
            var user = (tools.jackson.databind.node.ObjectNode) users.get(0);
            user.remove("avatar");
            user.remove("updated_at");
        }
        if (privacy) copy.remove("privacy");
        return EventCodec.sha256(EventCodec.canonicalJson(JSON.writeValueAsString(copy)));
    }

    public String acceptRegistration(Snapshot snapshot) {
        authority(); validateSnapshot(snapshot);
        if (!List.of("PROVISIONAL","CONFLICT","WITHDRAWN").contains(snapshot.status())
                || !policyVersion().equals(snapshot.policyVersion()) || !(membership.bound()?snapshot.sourceSite():peerSite).equals(origin(snapshot))) return "REJECTED";
        String payload=json(snapshot.data());
        String hash=EventCodec.sha256(EventCodec.canonicalJson(payload));
        if (!"user".equals(snapshot.data().path("users").path(0).path("role").stringValue())) return "REJECTED";
        return transactions.independent(() -> {
            if (getBySubject(snapshot.subjectId())==null && !createRemoteState(snapshot, membership.bound()?snapshot.sourceSite():peerSite)) return conflictSnapshot(snapshot,"IDENTITY_COLLISION");
            State state = read("subject_id=?",snapshot.subjectId(),true);
            if (state.userId()!=snapshot.userId()) return conflictSnapshot(snapshot,"IDENTITY_COLLISION");
            Long previous = jdbc.queryForObject("SELECT registration_revision FROM eatwhat_consent.subject WHERE subject_id=?",Long.class,snapshot.subjectId());
            if (previous!=null) {
                String priorHash=jdbc.queryForObject("SELECT registration_hash FROM eatwhat_consent.subject WHERE subject_id=?",String.class,snapshot.subjectId());
                if(snapshot.status().equals("WITHDRAWN")) {
                    acceptWithdrawal(snapshot.subjectId(),Objects.requireNonNull(snapshot.withdrawalRequestId()));
                    if(!hash.equals(priorHash)) return conflictSnapshot(snapshot,"UNARCHIVED_REGISTRATION_CHANGE");
                    jdbc.update("UPDATE eatwhat_consent.subject SET registration_revision=GREATEST(registration_revision,?) WHERE subject_id=?",
                            snapshot.revision(),snapshot.subjectId());
                    return "APPLIED";
                }
                if (snapshot.revision()<previous) return "STALE";
                if (snapshot.revision()==previous) {
                    return hash.equals(priorHash)?"APPLIED":conflictSnapshot(snapshot,"REGISTRATION_CHANGED");
                }
                // The authority may have changed the account since admission. Preserve both sides.
                return conflictSnapshot(snapshot,"REGISTRATION_CHANGED");
            }
            if (previous==null) {
                jdbc.update("UPDATE eatwhat_consent.subject SET status='PROVISIONAL' WHERE subject_id=?",snapshot.subjectId());
                boolean imported = Boolean.TRUE.equals(jdbc.queryForObject("SELECT eatwhat_consent.import_account(?,?,?::jsonb)",Boolean.class,
                        snapshot.subjectId(),snapshot.userId(),payload));
                if (!imported) {
                    jdbc.update("UPDATE eatwhat_consent.subject SET status='CONFLICT',backfill_status='CONFLICT' WHERE subject_id=?",snapshot.subjectId());
                    return conflictSnapshot(snapshot,"REGISTRATION_CONSTRAINT");
                }
            }
            jdbc.update("""
                    UPDATE eatwhat_consent.subject SET registration_revision=?,registration_hash=?,source_archived=true,status='NOT_GRANTED',
                      epoch=GREATEST(epoch,?),revision=GREATEST(revision,?) WHERE subject_id=?
                    """,snapshot.revision(),hash,snapshot.epoch(),snapshot.revision(),snapshot.subjectId());
            if (snapshot.status().equals("WITHDRAWN")) {
                acceptWithdrawal(snapshot.subjectId(), Objects.requireNonNull(snapshot.withdrawalRequestId()));
            } else grantAudience(grantLocked(getBySubject(snapshot.subjectId())),snapshot.sourceSite());
            return "APPLIED";
        });
    }

    public void complete(Job job) {
        transactions.independent(() -> {
            State state = locked(job.userId());
            if (!lockJob(job)) return null;
            boolean current = state!=null && state.epoch()==job.epoch() && state.revision()==job.revision();
            boolean refresh = !external() && current && job.kind().equals("UPSERT") && state.status().equals("GRANTED");
            int changed=jdbc.update("""
                    UPDATE eatwhat_consent.job SET status=?,available_at=clock_timestamp()+(? * interval '1 millisecond'),
                      lease_token=NULL,lease_until=NULL WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                    """,refresh?"READY":"DONE",Math.max(250,ttlMillis/2),job.jobId(),job.leaseToken());
            if(changed==1 && current && job.kind().equals("REGISTER")) {
                jdbc.update("""
                        UPDATE eatwhat_consent.subject SET source_archived=true,
                          withdrawal_request_id=CASE WHEN status='WITHDRAWN' THEN NULL ELSE withdrawal_request_id END WHERE subject_id=?
                        """,state.subjectId());
                if(state.status().equals("WITHDRAWN")) clearLocked(state);
            }
            if(changed==1 && refresh) jdbc.update("UPDATE eatwhat_consent.subject SET identity_ready=true,backfill_status='ACCOUNT_COMPLETE_DOMAINS_PENDING' WHERE subject_id=?",state.subjectId());
            if(changed==1 && current && job.kind().equals("PURGE")) jdbc.update("UPDATE eatwhat_consent.subject SET cleanup_status='REMOTE_CLEANUP_PENDING' WHERE subject_id=?",state.subjectId());
            return null;
        });
    }

    public void retry(Job job) {
        transactions.independent(() -> {
            if(lockJob(job)) jdbc.update("""
                    UPDATE eatwhat_consent.job SET status='READY',available_at=clock_timestamp()+(? * interval '1 millisecond'),
                      lease_token=NULL,lease_until=NULL WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                    """,Math.min(30_000,250L*(1L<<Math.min(7,Math.max(0,job.attempts()-1)))),job.jobId(),job.leaseToken());
            return null;
        });
    }

    /** Retire a superseded/unsendable task without asserting a peer received account material. */
    public void discard(Job job) {
        transactions.independent(() -> {
            if(lockJob(job)) jdbc.update("""
                    UPDATE eatwhat_consent.job SET status='DONE',lease_token=NULL,lease_until=NULL
                    WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                    """,job.jobId(),job.leaseToken());
            return null;
        });
    }

    public void conflict(Job job) {
        transactions.independent(() -> {
            State state=locked(job.userId());
            if(lockJob(job) && jdbc.update("""
                    UPDATE eatwhat_consent.job SET status='CONFLICT',lease_token=NULL,lease_until=NULL
                    WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                    """,job.jobId(),job.leaseToken())==1 && state!=null && state.epoch()==job.epoch() && state.revision()==job.revision()) {
                jdbc.update("UPDATE eatwhat_consent.subject SET backfill_status='CONFLICT' WHERE subject_id=?",state.subjectId());
            }
            return null;
        });
    }

    public void acknowledgeWithdrawal(UUID subjectId, UUID requestId, long authorityEpoch) {
        if(!external()) throw failure("WRONG_SITE");
        transactions.independent(() -> {
            State state=read("subject_id=?",subjectId,true);
            if(state!=null && requestId.equals(state.withdrawalRequestId()) && authorityEpoch>=state.epoch()) {
                jdbc.update("""
                        UPDATE eatwhat_consent.subject SET epoch=?,status='WITHDRAWN',
                          withdrawal_request_id=CASE WHEN source_archived THEN NULL ELSE withdrawal_request_id END,
                          identity_ready=false,valid_until=NULL,effects_pending=true,effects_version=effects_version+1 WHERE subject_id=?
                        """,authorityEpoch,subjectId);
                clearLocked(getBySubject(subjectId));
            }
            return null;
        });
    }

    public void clearLocalProjection(long userId) {
        if(!external()) throw failure("WRONG_SITE");
        transactions.required(() -> { State state=locked(userId); if(state==null || !state.status().equals("WITHDRAWN")) throw failure("CONSENT_REQUIRED"); clearLocked(state); return null; });
    }

    /** Runs only this withdrawal's local cleanup; an obsolete worker never clears a newer grant. */
    public String runCleanup(Job job) {
        if(!external() || !"CLEANUP".equals(job.kind())) throw failure("WRONG_SITE");
        return transactions.independent(() -> {
            State state=locked(job.userId());
            if(!lockJob(job) || !leaseCurrent(job)) return "DISCARD";
            if(state==null || !state.status().equals("WITHDRAWN") || state.epoch()!=job.epoch() || state.revision()!=job.revision()) return "DISCARD";
            clearLocked(state);
            if(!leaseCurrent(job)) throw new IllegalStateException("Cleanup lease expired");
            return get(job.userId()).cleanupStatus().equals("ACCOUNT_CLEARED_EFFECTS_PENDING")?"DONE":"RETRY";
        });
    }

    public List<Long> pendingEffects(int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid effects limit");
        return jdbc.queryForList("SELECT user_id FROM eatwhat_consent.subject WHERE effects_pending ORDER BY user_id LIMIT ?",Long.class,limit);
    }
    /** New sessions wait until durable cleanup has finished so it cannot revoke their freshly issued token. */
    public boolean hasPendingEffects(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_consent.subject WHERE user_id=? AND effects_pending)
                """, Boolean.class, userId));
    }

    public long effectsVersion(long userId) {
        return jdbc.queryForObject("SELECT effects_version FROM eatwhat_consent.subject WHERE user_id=?",Long.class,userId);
    }
    public void completeEffects(long userId,long expectedVersion) {
        transactions.required(() -> { locked(userId); jdbc.update("""
                UPDATE eatwhat_consent.subject SET effects_pending=false,
                  cleanup_status=CASE WHEN cleanup_status='ACCOUNT_CLEARED_EFFECTS_PENDING' THEN 'ACCOUNT_COMPLETE_DOMAINS_PENDING' ELSE cleanup_status END
                WHERE user_id=? AND effects_version=?
                """,userId,expectedVersion); return null; });
    }
    public void requireEffects(long userId) { jdbc.update("UPDATE eatwhat_consent.subject SET effects_pending=true,effects_version=effects_version+1 WHERE user_id=?",userId); }

    private void clearLocked(State state) {
        // Preserve catalog ledger/heads, but erase an EXTERNAL catalog projection only when
        // every current head has durable CAMPUS application evidence. Missing proof keeps the
        // account cleanup retryable and does not treat source_archived as catalog evidence.
        boolean catalogReady = !external() || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT eatwhat_sync.prepare_catalog_withdrawal_cleanup(?,?)", Boolean.class,
                state.subjectId(), state.userId()));
        boolean cleared = catalogReady && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT eatwhat_consent.purge_account(?,?)", Boolean.class, state.subjectId(), state.userId()));
        if(cleared&&multiwriterEnabled())jdbc.queryForObject("SELECT eatwhat_account_multiwriter.erase_withdrawn_projection(?)",Boolean.class,state.subjectId());
        jdbc.update("UPDATE eatwhat_consent.subject SET cleanup_status=?,effects_pending=true,effects_version=effects_version+1,local_dirty=CASE WHEN ? THEN false ELSE local_dirty END WHERE subject_id=?",
                cleared?"ACCOUNT_CLEARED_EFFECTS_PENDING":(!catalogReady || !archived(state.userId())?"AWAITING_CAMPUS":"BLOCKED_DEPENDENCIES"),cleared,state.subjectId());
        if(!cleared) {
            queue(state,"CLEANUP");
            if(!archived(state.userId()) && localOrigin(state.userId())) queue(state,"REGISTER");
        }
    }
    private State ensure(long userId) {
        State state=locked(userId);
        if(state!=null) return state;
        if(multiwriterEnabled())multiwriter.registered(userId,false,policyVersion());else onRegistered(userId,false,null);
        return locked(userId);
    }
    private State locked(long userId) { return read("user_id=?",userId,true); }
    private State read(String where,Object key,boolean lock) {
        var rows=jdbc.query("SELECT user_id,subject_id,epoch,revision,status,policy_version,valid_until,identity_ready,backfill_status,cleanup_status,withdrawal_request_id FROM eatwhat_consent.subject WHERE "+where+(lock?" FOR UPDATE":""),
                (r,n)->new State(r.getLong(1),r.getObject(2,UUID.class),r.getLong(3),r.getLong(4),r.getString(5),r.getString(6),
                        r.getTimestamp(7)==null?null:r.getTimestamp(7).toInstant(),r.getBoolean(8),r.getString(9),r.getString(10),r.getObject(11,UUID.class)),key);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void queue(State state,String kind) {
        jdbc.update("INSERT INTO eatwhat_consent.job(subject_id,user_id,epoch,revision,kind) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                state.subjectId(),state.userId(),state.epoch(),state.revision(),kind);
    }
    private boolean lockJob(Job job) {
        return !jdbc.queryForList("SELECT job_id FROM eatwhat_consent.job WHERE job_id=? AND status='LEASED' AND lease_token=? FOR UPDATE",UUID.class,job.jobId(),job.leaseToken()).isEmpty();
    }
    private boolean leaseCurrent(Job job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_consent.job WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp())",Boolean.class,job.jobId(),job.leaseToken()));
    }
    private boolean archived(long userId) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT source_archived FROM eatwhat_consent.subject WHERE user_id=?",Boolean.class,userId)); }
    private boolean dirty(long userId) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT local_dirty FROM eatwhat_consent.subject WHERE user_id=?",Boolean.class,userId)); }
    private boolean localOrigin(long userId) { return site.getId().equals(jdbc.queryForObject("SELECT origin_site FROM eatwhat_consent.subject WHERE user_id=?",String.class,userId)); }
    private void lockInitialAccount(long userId) {
        if(jdbc.queryForList("SELECT id FROM public.users WHERE id=? FOR UPDATE",Long.class,userId).isEmpty()) throw failure("ACCOUNT_UNAVAILABLE");
        for(String table:List.of("contacts","verifications","user_info","privacy")) {
            String key=table.equals("user_info")?"id":"account_id";
            jdbc.queryForList("SELECT id FROM public."+table+" WHERE "+key+"=? ORDER BY id FOR UPDATE",Long.class,userId);
        }
    }
    private Identity identity(long userId) {
        var rows=jdbc.query("""
                SELECT i.global_id,i.origin_site FROM eatwhat_identity.entity_identity i JOIN public.users u ON u.id=i.numeric_id
                WHERE i.entity_type='users' AND i.numeric_id=?
                """,(r,n)->new Identity(r.getObject(1,UUID.class),r.getString(2)),userId);
        if(rows.isEmpty() || rows.getFirst().origin()==null) throw failure("ACCOUNT_UNAVAILABLE");
        return rows.getFirst();
    }
    private String securityFingerprint(long userId) {
        var rows=jdbc.queryForList("SELECT password_hash,banned,role FROM public.users WHERE id=?",userId);
        if(rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().get("banned"))) throw failure("PROOF_INVALID");
        var row=rows.getFirst();
        return EventCodec.sha256(row.get("password_hash")+"\u0000"+row.get("banned")+"\u0000"+row.get("role"));
    }
    private void requireActiveAccount(long userId) { securityFingerprint(userId); }
    private Snapshot snapshot(State state,Instant valid,JsonNode data) {
        return new Snapshot(state.subjectId(),state.userId(),state.epoch(),state.revision(),state.status(),state.policyVersion(),valid,
                site.getId(),site.getInstanceId(),state.withdrawalRequestId(),data);
    }
    private JsonNode accountData(long userId) {
        var result=JSON.createObjectNode();
        for(String table:List.of("users","contacts","verifications","user_info","privacy")) {
            String columns=ACCOUNT_COLUMNS.get(table);
            String predicate=List.of("users","user_info").contains(table)?"id":"account_id";
            String metadata=table.equals("user_info")?"to_jsonb(b)":"to_jsonb(b)||jsonb_build_object('global_id',i.global_id,'origin_site',i.origin_site)";
            String join=table.equals("user_info")?"":" JOIN eatwhat_identity.entity_identity i ON i.entity_type='"+table+"' AND i.numeric_id=b.id";
            String sql="SELECT "+metadata+" FROM (SELECT "+columns+" FROM public."+table+" WHERE "+predicate+"=? ORDER BY id) b"+join;
            var array=result.putArray(table);
            jdbc.queryForList(sql,String.class,userId).forEach(value->array.add(JSON.readTree(value)));
            // Sites without V71 reject an unknown column; an unset nickname is simply left out.
            if(table.equals("users"))array.forEach(row->{if(row.path("display_name").isNull())((tools.jackson.databind.node.ObjectNode)row).remove("display_name");});
        }
        if(result.get("users").size()!=1) throw failure("ACCOUNT_UNAVAILABLE");
        if(JSON.writeValueAsBytes(result).length>180*1024) throw failure("ACCOUNT_UNAVAILABLE");
        return result;
    }
    private boolean createRemoteState(Snapshot snapshot,String origin) {
        int inserted=jdbc.update("""
                INSERT INTO eatwhat_consent.subject(subject_id,user_id,origin_site,epoch,revision,status,policy_version,backfill_status)
                VALUES(?,?,?,0,1,'NOT_GRANTED',?,'ACCOUNT_PENDING') ON CONFLICT DO NOTHING
                """,snapshot.subjectId(),snapshot.userId(),origin,policyVersion());
        State found=getBySubject(snapshot.subjectId());
        return inserted==1 || (found!=null && found.userId()==snapshot.userId());
    }
    private String conflictSnapshot(Snapshot snapshot,String reason) {
        jdbc.update("""
                INSERT INTO eatwhat_consent.conflict_receipt(subject_id,user_id,epoch,revision,reason) VALUES(?,?,?,?,?)
                ON CONFLICT(subject_id,epoch,revision) DO NOTHING
                """,snapshot.subjectId(),snapshot.userId(),snapshot.epoch(),snapshot.revision(),reason);
        jdbc.update("UPDATE eatwhat_consent.subject SET backfill_status='CONFLICT' WHERE subject_id=?",snapshot.subjectId());
        return "CONFLICT";
    }
    private String origin(Snapshot snapshot) {
        try {
            var rows=snapshot.data().get("users");
            String origin=rows.get(0).get("origin_site").stringValue();
            if(!origin.equals(site.getId()) && !origin.equals(peerSite)&&!(membership.bound()&&membership.knownSite(origin))) throw failure("IDENTITY_CONFLICT");
            return origin;
        } catch(RuntimeException invalid) { throw failure("IDENTITY_CONFLICT"); }
    }
    private String inferredOrigin(long userId) {
        if(membership.bound()){
            int slot=userId>=you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback.CLUSTER_ID_FLOOR?(int)(userId%1024):(userId%2==1?0:1);
            if(java.util.Objects.equals(site.getAllocationSlot(),slot))return site.getId();
            return membership.retained().stream().filter(m->m.allocationSlot()==slot).map(you.v50to.eatwhat.cluster.ClusterMember::siteId).findFirst().orElseThrow(()->failure("IDENTITY_CONFLICT"));
        }
        boolean localParity=userId%2==(external()?0:1);
        return localParity?site.getId():peerSite;
    }
    private void validateSnapshot(Snapshot snapshot) {
        boolean validPeer=snapshot!=null&&peerSite!=null&&peerSite.equals(snapshot.sourceSite())&&peerInstance.equals(snapshot.sourceInstance());
        if(snapshot!=null&&membership.bound()&&!external()){var authenticated=AccountPeerScope.current();validPeer=authenticated!=null&&authenticated.siteId().equals(snapshot.sourceSite())&&authenticated.instanceId().equals(snapshot.sourceInstance())&&membership.current(authenticated);}
        if(snapshot==null || !validPeer
                || snapshot.userId()<=1_000_000_000L || snapshot.userId()>9_007_199_254_740_991L
                || snapshot.epoch()<0 || snapshot.revision()<1) throw failure("IDENTITY_CONFLICT");
        SyncEvent.requireUuid(snapshot.subjectId(),"subjectId");
    }
    private String json(JsonNode value) {
        if(value==null || !value.isObject()) throw failure("IDENTITY_CONFLICT");
        String json=JSON.writeValueAsString(value);
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>180*1024) throw failure("IDENTITY_CONFLICT");
        return json;
    }
    private Instant dbNow() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    private boolean external() { return site.getMode()==SiteProperties.Mode.EXTERNAL; }
    private void authority() { if(site.getMode()!=SiteProperties.Mode.CAMPUS) throw failure("WRONG_SITE"); }
    private void requirePolicy(String accepted) { if(!policyVersion().equals(accepted)) throw failure("POLICY_CHANGED"); }
    private void requireAudience(String audience) {
        if(multiwriterEnabled()&&site.getId().equals(audience)&&AccountPeerScope.current()==null)return;
        if(membership.bound()){
            var authenticated=AccountPeerScope.current();if(authenticated==null||!authenticated.siteId().equals(audience)||!membership.current(authenticated))throw failure("PROOF_INVALID");
        }else if(!java.util.Objects.equals(peerSite,audience))throw failure("PROOF_INVALID");
    }
    public boolean recipientAllowed(State state,String audience){if(!membership.bound())return true;return membership.current().stream().filter(m->m.siteId().equals(audience)).anyMatch(m->recipients.allowed(state.subjectId(),state.epoch(),m));}
    public State grantRecipient(long userId,String audience,String acceptedPolicy){authority();requirePolicy(acceptedPolicy);return transactions.required(()->{var state=locked(userId);if(state==null||!state.status().equals("GRANTED")||state.withdrawalRequestId()!=null)throw failure("CONSENT_REQUIRED");return grantAudience(state,audience);});}
    private State grantAudience(State state,String audience){if(!membership.bound())return state;if(recipients.grant(state.subjectId(),state.epoch(),audience)){jdbc.update("UPDATE eatwhat_consent.subject SET revision=revision+1 WHERE subject_id=?",state.subjectId());state=get(state.userId());queue(state,"UPSERT");}return state;}
    private static long millis(Duration value,Duration maximum) {
        if(value==null || value.isNegative() || value.toMillis()<1 || value.compareTo(maximum)>0) throw new IllegalArgumentException("Invalid bounded consent duration");
        return value.toMillis();
    }
    private static ConsentException failure(String code) { return new ConsentException(code); }
    private record Identity(UUID gid,String origin) { }
}
