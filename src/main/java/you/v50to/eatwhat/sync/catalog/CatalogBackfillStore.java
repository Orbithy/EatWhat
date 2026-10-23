package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncTransactions;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Durable, leased replay of catalog heads after a subject is granted or re-granted. */
public final class CatalogBackfillStore {
    /** Stable local-only cursor key for CAMPUS-managed directory definitions without a user owner. */
    public static final UUID SYSTEM_SUBJECT = UUID.nameUUIDFromBytes(
            "eatwhat.catalog.system-backfill".getBytes(StandardCharsets.UTF_8));

    private final JdbcTemplate jdbc;
    private final SyncTransactions tx;
    private final CatalogCommands commands;
    private final CatalogStore store;
    private final ConsentStore consent;
    private final SiteProperties site;
    private final CatalogSyncProperties properties;

    public CatalogBackfillStore(JdbcTemplate jdbc, SyncTransactions tx, CatalogCommands commands,
                                ConsentStore consent, SiteProperties site, CatalogSyncProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tx.requireSameDataSource(jdbc);
        this.commands = commands;
        this.store = new CatalogStore(jdbc);
        this.consent = consent;
        this.site = site;
        this.properties = properties;
    }

    public record Job(UUID subject, long epoch, String scope, String cursorKind, UUID cursorGid,
                      UUID token, int attempts) { }
    public record Candidate(CatalogKind kind, long numericId, UUID gid) { }
    public record Result(int handled, boolean complete, boolean waiting, Job job) { }

    /** Creates one independent cursor per consent role, allowing recovery at any point in the scan. */
    public void enqueueReadySubjects() {
        tx.required(() -> {
            jdbc.update("""
                    INSERT INTO eatwhat_sync.catalog_backfill_job(subject_gid,epoch,scope,status)
                    SELECT s.subject_id,s.epoch,scope.name,'READY'
                    FROM eatwhat_consent.subject s
                    CROSS JOIN (VALUES ('AUTHOR'),('PARENT'),('OWNER')) AS scope(name)
                    WHERE s.status='GRANTED' AND s.identity_ready AND s.withdrawal_request_id IS NULL
                    UNION ALL SELECT ?::uuid,0,'SYSTEM','READY' WHERE ?='CAMPUS'
                    ON CONFLICT(subject_gid,epoch,scope) DO UPDATE SET
                      status=CASE WHEN eatwhat_sync.catalog_backfill_job.status='WAIT' THEN 'READY'
                                  ELSE eatwhat_sync.catalog_backfill_job.status END,
                      available_at=CASE WHEN eatwhat_sync.catalog_backfill_job.status='WAIT'
                                        THEN clock_timestamp()
                                        ELSE eatwhat_sync.catalog_backfill_job.available_at END,
                      updated_at=clock_timestamp()
                    """, SYSTEM_SUBJECT, site.getMode().name());
            List<Job> completed = jdbc.query("""
                    SELECT subject_gid,epoch,scope,cursor_kind,cursor_gid,NULL::uuid AS lease_token,attempts
                    FROM eatwhat_sync.catalog_backfill_job WHERE status='DONE'
                    ORDER BY subject_gid,epoch,scope
                    FOR UPDATE
                    """, (row, index) -> new Job(row.getObject(1, UUID.class), row.getLong(2), row.getString(3),
                    row.getString(4), row.getObject(5, UUID.class), row.getObject(6, UUID.class), row.getInt(7)));
            completed.stream().map(Job::subject).filter(subject -> !SYSTEM_SUBJECT.equals(subject)).distinct()
                    .sorted(java.util.Comparator.comparing(UUID::toString)).forEach(subject ->
                            jdbc.query("SELECT subject_id FROM eatwhat_consent.subject WHERE subject_id=? FOR UPDATE",
                                    (row, index) -> row.getObject(1, UUID.class), subject));
            // Match catalog command locking: every subject first, then the shared domain lock.
            store.lockDomain();
            for (Job job : completed) {
                if (!SYSTEM_SUBJECT.equals(job.subject()) && !currentConsentReady(job)) continue;
                if (hasUnpublishedCandidate(job)) {
                    jdbc.update("""
                            UPDATE eatwhat_sync.catalog_backfill_job SET status='READY',cursor_kind=NULL,cursor_gid=NULL,
                              available_at=clock_timestamp(),last_error='NEW_UNPUBLISHED_VERSION',updated_at=clock_timestamp()
                            WHERE subject_gid=? AND epoch=? AND scope=? AND status='DONE'
                            """, job.subject(), job.epoch(), job.scope());
                }
            }
            return null;
        });
    }

    private boolean currentConsentReady(Job job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_consent.subject
                  WHERE subject_id=? AND epoch=? AND status='GRANTED' AND identity_ready
                    AND withdrawal_request_id IS NULL)
                """, Boolean.class, job.subject(), job.epoch()));
    }

    public List<Job> claim(int limit, Duration lease) {
        if (limit < 1 || limit > 16 || lease == null || lease.isZero() || lease.isNegative()
                || lease.toMillis() < 1 || lease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid catalog backfill lease");
        }
        return tx.independent(() -> jdbc.query("""
                WITH candidates AS (
                  SELECT subject_gid,epoch,scope FROM eatwhat_sync.catalog_backfill_job
                  WHERE ((status IN ('READY','WAIT') AND available_at<=clock_timestamp())
                      OR (status='LEASED' AND lease_until<=clock_timestamp()))
                  ORDER BY available_at,subject_gid,epoch,scope LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE eatwhat_sync.catalog_backfill_job j
                SET status='LEASED',lease_token=gen_random_uuid(),
                    lease_until=clock_timestamp()+(? * interval '1 millisecond'),
                    attempts=attempts+1,updated_at=clock_timestamp()
                FROM candidates c
                WHERE j.subject_gid=c.subject_gid AND j.epoch=c.epoch AND j.scope=c.scope
                RETURNING j.subject_gid,j.epoch,j.scope,j.cursor_kind,j.cursor_gid,j.lease_token,j.attempts
                """, (row, index) -> new Job(row.getObject(1, UUID.class), row.getLong(2), row.getString(3),
                row.getString(4), row.getObject(5, UUID.class), row.getObject(6, UUID.class), row.getInt(7)),
                limit, lease.toMillis()));
    }

    public Result process(Job job, int batchSize) {
        if (batchSize < 1 || batchSize > 128) throw new IllegalArgumentException("Invalid catalog backfill batch");
        int handled = 0;
        Job current = job;
        for (int i = 0; i < batchSize; i++) {
            if (Thread.currentThread().isInterrupted()) return new Result(handled, false, true, current);
            Job input = current;
            Step step = tx.required(() -> {
                Step completed = processStep(input);
                if (Thread.currentThread().isInterrupted()) throw new BackfillInterruptedException();
                return completed;
            });
            current = step.job();
            handled += step.handled();
            if (step.complete() || step.waiting()) return new Result(handled, step.complete(), step.waiting(), current);
        }
        Job release = current;
        tx.required(() -> { release(release); return null; });
        return new Result(handled, false, false, current);
    }

    private record Step(int handled, boolean complete, boolean waiting, Job job) { }

    private Step processStep(Job job) {
        if (!leaseCurrent(job)) return new Step(0, false, true, job);
        if ("SYSTEM".equals(job.scope())) {
            if (site.getMode() != SiteProperties.Mode.CAMPUS || !SYSTEM_SUBJECT.equals(job.subject()) || job.epoch() != 0) {
                finish(job, "DONE", "SYSTEM_SCOPE_NOT_AUTHORIZED");
                return new Step(0, true, false, job);
            }
        } else {
            var state = consent.getBySubject(job.subject());
            if (state == null || state.epoch() != job.epoch() || !"GRANTED".equals(state.status())
                    || state.withdrawalRequestId() != null) {
                finish(job, "DONE", "CONSENT_EPOCH_CHANGED");
                return new Step(0, true, false, job);
            }
            if (!state.identityReady()) {
                waitJob(job, "IDENTITY_NOT_READY");
                return new Step(0, false, true, job);
            }
        }

        Candidate candidate = nextCandidate(job);
        if (candidate == null) {
            finish(job, "DONE", null);
            return new Step(0, true, false, job);
        }
        CatalogCommands.BackfillStatus result = commands.backfillCatalogObject(
                candidate.kind(), candidate.numericId(), job.subject(), job.epoch());
        if (Thread.currentThread().isInterrupted()) throw new BackfillInterruptedException();
        if (result == CatalogCommands.BackfillStatus.STALE) {
            finish(job, "DONE", "CONSENT_EPOCH_CHANGED");
            return new Step(0, true, false, job);
        }
        if (result != CatalogCommands.BackfillStatus.APPLIED) {
            waitJob(job, "DEPENDENCY_WAIT");
            return new Step(0, false, true, job);
        }
        fenceUpdate(job, """
                UPDATE eatwhat_sync.catalog_backfill_job
                SET cursor_kind=?,cursor_gid=?,updated_at=clock_timestamp()
                WHERE subject_gid=? AND epoch=? AND scope=? AND status='LEASED'
                  AND lease_token=? AND lease_until>clock_timestamp()
                """, candidate.kind().name(), candidate.gid(), job.subject(), job.epoch(), job.scope(), job.token());
        Job advanced = new Job(job.subject(), job.epoch(), job.scope(), candidate.kind().name(), candidate.gid(),
                job.token(), job.attempts());
        return new Step(1, false, false, advanced);
    }

    private Candidate nextCandidate(Job job) {
        if ("SYSTEM".equals(job.scope())) {
            return jdbc.query("""
                    WITH candidates AS (
                      SELECT c.kind,c.numeric_id,c.object_gid FROM eatwhat_sync.catalog_object c
                      WHERE c.kind IN ('HUB','SYSTEM_TAG','ACTIVITY') AND c.managed
                      UNION
                      SELECT 'HUB',h.id,hi.global_id FROM public.hub h
                        JOIN eatwhat_identity.entity_identity hi ON hi.entity_type='hub' AND hi.numeric_id=h.id
                      UNION
                      SELECT 'SYSTEM_TAG',t.id,ti.global_id FROM public.food_tags t
                        JOIN eatwhat_identity.entity_identity ti ON ti.entity_type='food_tags' AND ti.numeric_id=t.id
                      WHERE lower(t.tag_type)='system'
                      UNION
                      SELECT 'ACTIVITY',a.id,ai.global_id FROM public.activities a
                        JOIN eatwhat_identity.entity_identity ai ON ai.entity_type='activities' AND ai.numeric_id=a.id
                    )
                    SELECT kind,numeric_id,object_gid FROM candidates
                    WHERE (?::text IS NULL OR
                      CASE kind WHEN 'HUB' THEN 1 WHEN 'SYSTEM_TAG' THEN 2 ELSE 3 END >
                        CASE ?::text WHEN 'HUB' THEN 1 WHEN 'SYSTEM_TAG' THEN 2 WHEN 'ACTIVITY' THEN 3 ELSE 0 END
                      OR (kind=? AND object_gid>?))
                    ORDER BY CASE kind WHEN 'HUB' THEN 1 WHEN 'SYSTEM_TAG' THEN 2 ELSE 3 END,object_gid LIMIT 1
                    """, (row, index) -> new Candidate(CatalogKind.valueOf(row.getString(1)), row.getLong(2),
                    row.getObject(3, UUID.class)), job.cursorKind(), job.cursorKind(), job.cursorKind(),
                    job.cursorGid()).stream().findFirst().orElse(null);
        }
        String scopeFilter = switch (job.scope()) {
            case "AUTHOR" -> "c.kind IN ('RESTAURANT','FOOD') AND c.author_gid=s.subject_id";
            case "PARENT" -> "c.kind='FOOD' AND c.author_gid IS DISTINCT FROM s.subject_id AND EXISTS ("
                    + "SELECT 1 FROM eatwhat_sync.catalog_object p WHERE p.kind='RESTAURANT' "
                    + "AND p.object_gid=c.parent_gid AND p.author_gid=s.subject_id)";
            case "OWNER" -> "c.kind='CUSTOM_TAG' AND c.author_gid=s.subject_id";
            default -> throw new IllegalArgumentException("Invalid catalog backfill scope");
        };
        String sql = """
                WITH candidates AS (
                  SELECT c.kind,c.numeric_id,c.object_gid
                  FROM eatwhat_sync.catalog_object c
                  JOIN eatwhat_consent.subject s ON s.subject_id=?
                  WHERE %s AND c.managed
                  UNION
                  SELECT 'RESTAURANT',r.id,ri.global_id
                  FROM public.restaurant r JOIN eatwhat_identity.entity_identity ri
                    ON ri.entity_type='restaurant' AND ri.numeric_id=r.id
                  JOIN eatwhat_consent.subject s ON s.subject_id=?
                  JOIN eatwhat_identity.entity_identity ui ON ui.entity_type='users' AND ui.numeric_id=r.account_id
                  WHERE ?='AUTHOR' AND ui.global_id=s.subject_id
                    AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='RESTAURANT' AND c.numeric_id=r.id AND (c.tombstone OR c.rejected))
                  UNION
                  SELECT 'FOOD',f.id,fi.global_id
                  FROM public.foods f JOIN eatwhat_identity.entity_identity fi
                    ON fi.entity_type='foods' AND fi.numeric_id=f.id
                  JOIN eatwhat_consent.subject s ON s.subject_id=?
                  JOIN eatwhat_identity.entity_identity ui ON ui.entity_type='users' AND ui.numeric_id=f.account_id
                  LEFT JOIN public.restaurant p ON p.id=f.restaurant_id
                  LEFT JOIN eatwhat_identity.entity_identity pi ON pi.entity_type='restaurant' AND pi.numeric_id=p.id
                  LEFT JOIN eatwhat_identity.entity_identity pu ON pu.entity_type='users' AND pu.numeric_id=p.account_id
                  WHERE ((?='AUTHOR' AND ui.global_id=s.subject_id)
                      OR (?='PARENT' AND pu.global_id=s.subject_id AND ui.global_id IS DISTINCT FROM s.subject_id))
                    AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='FOOD' AND c.numeric_id=f.id AND (c.tombstone OR c.rejected))
                  UNION
                  SELECT 'CUSTOM_TAG',t.id,ti.global_id
                  FROM public.food_tags t JOIN eatwhat_identity.entity_identity ti
                    ON ti.entity_type='food_tags' AND ti.numeric_id=t.id
                  JOIN eatwhat_consent.subject s ON s.subject_id=?
                  JOIN eatwhat_identity.entity_identity ui ON ui.entity_type='users' AND ui.numeric_id=t.owner_id
                  WHERE ?='OWNER' AND lower(t.tag_type)='custom' AND ui.global_id=s.subject_id
                    AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='CUSTOM_TAG' AND c.numeric_id=t.id AND (c.tombstone OR c.rejected))
                )
                SELECT kind,numeric_id,object_gid FROM candidates
                WHERE (?::text IS NULL OR
                  CASE kind WHEN 'RESTAURANT' THEN 1 WHEN 'FOOD' THEN 2 ELSE 3 END >
                    CASE ?::text WHEN 'RESTAURANT' THEN 1 WHEN 'FOOD' THEN 2 WHEN 'CUSTOM_TAG' THEN 3 ELSE 0 END
                  OR (kind=? AND object_gid>?))
                ORDER BY CASE kind WHEN 'RESTAURANT' THEN 1 WHEN 'FOOD' THEN 2 ELSE 3 END,
                  object_gid LIMIT 1
                """.formatted(scopeFilter);
        List<Candidate> result = jdbc.query(sql, (row, index) -> new Candidate(
                        CatalogKind.valueOf(row.getString(1)), row.getLong(2), row.getObject(3, UUID.class)),
                job.subject(), job.subject(), job.scope(), job.subject(), job.scope(), job.scope(), job.subject(),
                job.scope(), job.cursorKind(), job.cursorKind(), job.cursorKind(), job.cursorGid());
        return result.stream().findFirst().orElse(null);
    }

    /** A DONE cursor is reopened only when an object/head/consent vector lacks a matching receipt. */
    private boolean hasUnpublishedCandidate(Job job) {
        for(String recipient:commands.recipients())if(hasUnpublishedCandidate(job,recipient))return true;
        return false;
    }
    private boolean hasUnpublishedCandidate(Job job,String recipient) {
        String candidates;
        List<Object> args = new java.util.ArrayList<>();
        if ("SYSTEM".equals(job.scope())) {
            candidates = """
                    SELECT c.kind,c.object_gid,c.revision,c.causal_state->'heads' AS heads,
                           c.author_gid,NULL::uuid AS parent_author_gid
                    FROM eatwhat_sync.catalog_object c WHERE c.kind IN ('HUB','SYSTEM_TAG','ACTIVITY') AND c.managed
                    UNION
                    SELECT 'HUB',hi.global_id,0,'[]'::jsonb,NULL::uuid,NULL::uuid FROM public.hub h
                    JOIN eatwhat_identity.entity_identity hi ON hi.entity_type='hub' AND hi.numeric_id=h.id
                    WHERE NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='HUB' AND c.numeric_id=h.id)
                    UNION
                    SELECT 'SYSTEM_TAG',ti.global_id,0,'[]'::jsonb,NULL::uuid,NULL::uuid FROM public.food_tags t
                    JOIN eatwhat_identity.entity_identity ti ON ti.entity_type='food_tags' AND ti.numeric_id=t.id
                    WHERE lower(t.tag_type)='system' AND NOT EXISTS(
                      SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='SYSTEM_TAG' AND c.numeric_id=t.id)
                    UNION
                    SELECT 'ACTIVITY',ai.global_id,0,'[]'::jsonb,NULL::uuid,NULL::uuid FROM public.activities a
                    JOIN eatwhat_identity.entity_identity ai ON ai.entity_type='activities' AND ai.numeric_id=a.id
                    WHERE NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='ACTIVITY' AND c.numeric_id=a.id)
                    """;
        } else {
            String ledgerFilter = switch (job.scope()) {
                case "AUTHOR" -> "c.kind IN ('RESTAURANT','FOOD') AND c.author_gid=?";
                case "PARENT" -> "c.kind='FOOD' AND c.author_gid IS DISTINCT FROM ? AND EXISTS("
                        + "SELECT 1 FROM eatwhat_sync.catalog_object p WHERE p.kind='RESTAURANT' "
                        + "AND p.object_gid=c.parent_gid AND p.author_gid=?)";
                case "OWNER" -> "c.kind='CUSTOM_TAG' AND c.author_gid=?";
                default -> throw new IllegalArgumentException("Invalid catalog backfill scope");
            };
            if ("PARENT".equals(job.scope())) {
                args.add(job.subject()); args.add(job.subject());
            } else args.add(job.subject());
            candidates = """
                    SELECT c.kind,c.object_gid,c.revision,c.causal_state->'heads' AS heads,c.author_gid,
                           p.author_gid AS parent_author_gid
                    FROM eatwhat_sync.catalog_object c
                    LEFT JOIN eatwhat_sync.catalog_object p ON p.kind='RESTAURANT' AND p.object_gid=c.parent_gid
                    WHERE c.managed AND %s
                    UNION
                    SELECT 'RESTAURANT',ri.global_id,0,'[]'::jsonb,ui.global_id,NULL::uuid
                    FROM public.restaurant r
                    JOIN eatwhat_identity.entity_identity ri ON ri.entity_type='restaurant' AND ri.numeric_id=r.id
                    JOIN eatwhat_identity.entity_identity ui ON ui.entity_type='users' AND ui.numeric_id=r.account_id
                    WHERE '%s'='AUTHOR' AND ui.global_id=? AND NOT EXISTS(
                      SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='RESTAURANT' AND c.numeric_id=r.id)
                    UNION
                    SELECT 'FOOD',fi.global_id,0,'[]'::jsonb,ui.global_id,pu.global_id
                    FROM public.foods f
                    JOIN eatwhat_identity.entity_identity fi ON fi.entity_type='foods' AND fi.numeric_id=f.id
                    JOIN eatwhat_identity.entity_identity ui ON ui.entity_type='users' AND ui.numeric_id=f.account_id
                    LEFT JOIN public.restaurant pr ON pr.id=f.restaurant_id
                    LEFT JOIN eatwhat_identity.entity_identity pu ON pu.entity_type='users' AND pu.numeric_id=pr.account_id
                    WHERE ((('%s'='AUTHOR' AND ui.global_id=?) OR ('%s'='PARENT' AND pu.global_id=?
                            AND ui.global_id IS DISTINCT FROM pu.global_id)))
                      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='FOOD' AND c.numeric_id=f.id)
                    UNION
                    SELECT 'CUSTOM_TAG',ti.global_id,0,'[]'::jsonb,ui.global_id,NULL::uuid
                    FROM public.food_tags t
                    JOIN eatwhat_identity.entity_identity ti ON ti.entity_type='food_tags' AND ti.numeric_id=t.id
                    JOIN eatwhat_identity.entity_identity ui ON ui.entity_type='users' AND ui.numeric_id=t.owner_id
                    WHERE '%s'='OWNER' AND lower(t.tag_type)='custom' AND ui.global_id=? AND NOT EXISTS(
                      SELECT 1 FROM eatwhat_sync.catalog_object c WHERE c.kind='CUSTOM_TAG' AND c.numeric_id=t.id)
                    """.formatted(ledgerFilter, job.scope(), job.scope(), job.scope(), job.scope());
            if ("AUTHOR".equals(job.scope())) {
                args.add(job.subject()); args.add(job.subject()); args.add(job.subject()); args.add(job.subject());
            } else if ("PARENT".equals(job.scope())) {
                args.add(job.subject()); args.add(job.subject()); args.add(job.subject()); args.add(job.subject());
            } else {
                args.add(job.subject()); args.add(job.subject()); args.add(job.subject()); args.add(job.subject());
            }
        }
        String sql = """
                WITH candidates AS (%s), expected AS (
                  SELECT c.*,
                    (SELECT COALESCE(jsonb_agg(jsonb_build_object('subjectGid',s.subject_gid,'epoch',
                                      COALESCE(cs.epoch,0)) ORDER BY s.subject_gid::text),'[]'::jsonb)
                     FROM (
                       SELECT c.author_gid AS subject_gid WHERE c.author_gid IS NOT NULL
                       UNION SELECT c.parent_author_gid WHERE c.parent_author_gid IS NOT NULL
                       UNION
                       SELECT r.author_gid
                       FROM jsonb_array_elements(c.heads) h
                       CROSS JOIN LATERAL (
                         SELECT value AS operation_id
                         FROM jsonb_array_elements_text(COALESCE(h->'resolutionProof'->'expectedHeads','[]'::jsonb))
                       ) proof
                       JOIN eatwhat_sync.catalog_revision r ON r.operation_id=proof.operation_id::uuid
                       WHERE r.author_gid IS NOT NULL
                       UNION
                       SELECT parent.author_gid
                       FROM jsonb_array_elements(c.heads) h
                       CROSS JOIN LATERAL (
                         SELECT value AS operation_id
                         FROM jsonb_array_elements_text(COALESCE(h->'resolutionProof'->'expectedHeads','[]'::jsonb))
                       ) proof
                       JOIN eatwhat_sync.catalog_revision r ON r.operation_id=proof.operation_id::uuid
                       JOIN eatwhat_sync.catalog_object parent
                         ON parent.kind='RESTAURANT' AND parent.object_gid=r.parent_gid
                       WHERE r.kind='FOOD' AND parent.author_gid IS NOT NULL
                     ) s
                     LEFT JOIN eatwhat_consent.subject cs ON cs.subject_id=s.subject_gid) AS expected_epochs
                  FROM candidates c
                )
                SELECT EXISTS(SELECT 1 FROM expected c
                  LEFT JOIN eatwhat_sync.catalog_backfill_publication p
                    ON p.kind=c.kind AND p.object_gid=c.object_gid AND p.subject_gid=?
                      AND p.peer_site=? AND p.consent_epoch=?
                  WHERE p.kind IS NULL OR p.object_revision<>c.revision
                     OR p.published_heads IS DISTINCT FROM c.heads
                     OR p.consent_epochs IS DISTINCT FROM c.expected_epochs)
                """.formatted(candidates);
        args.add(job.subject());
        args.add(recipient);
        args.add(job.epoch());
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args.toArray()));
    }

    private boolean leaseCurrent(Job job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_backfill_job
                  WHERE subject_gid=? AND epoch=? AND scope=? AND status='LEASED'
                    AND lease_token=? AND lease_until>clock_timestamp())
                """, Boolean.class, job.subject(), job.epoch(), job.scope(), job.token()));
    }

    private void waitJob(Job job, String reason) {
        fenceUpdate(job, """
                UPDATE eatwhat_sync.catalog_backfill_job SET status='WAIT',available_at=clock_timestamp()+interval '15 seconds',
                  lease_token=NULL,lease_until=NULL,last_error=?,updated_at=clock_timestamp()
                WHERE subject_gid=? AND epoch=? AND scope=? AND status='LEASED'
                  AND lease_token=? AND lease_until>clock_timestamp()
                """, reason, job.subject(), job.epoch(), job.scope(), job.token());
    }

    private void finish(Job job, String status, String error) {
        fenceUpdate(job, """
                UPDATE eatwhat_sync.catalog_backfill_job SET status=?,available_at=clock_timestamp(),
                  lease_token=NULL,lease_until=NULL,last_error=?,updated_at=clock_timestamp()
                WHERE subject_gid=? AND epoch=? AND scope=? AND status='LEASED'
                  AND lease_token=? AND lease_until>clock_timestamp()
                """, status, error, job.subject(), job.epoch(), job.scope(), job.token());
    }

    private void release(Job job) {
        fenceUpdate(job, """
                UPDATE eatwhat_sync.catalog_backfill_job SET status='READY',available_at=clock_timestamp(),
                  lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                WHERE subject_gid=? AND epoch=? AND scope=? AND status='LEASED'
                  AND lease_token=? AND lease_until>clock_timestamp()
                """, job.subject(), job.epoch(), job.scope(), job.token());
    }

    private void fenceUpdate(Job job, String sql, Object... args) {
        if (jdbc.update(sql, args) != 1) throw new IllegalStateException("Catalog backfill lease lost");
    }

    static final class BackfillInterruptedException extends RuntimeException { }
}
