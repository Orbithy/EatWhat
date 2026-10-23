package you.v50to.eatwhat.sync.relation;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncTransactions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable, bounded jobs for replaying local relationship intent after a grant or regrant. */
public final class RelationBackfillStore {
    private static final String HISTORY_SCAN_COMPLETE = "~";
    private final JdbcTemplate jdbc;
    private final SyncTransactions tx;
    private final RelationCommands commands;
    private final ConsentStore consent;

    public RelationBackfillStore(JdbcTemplate jdbc, SyncTransactions tx, RelationStore relations,
                                 RelationCausalStore causal, OutboxService outbox, EntityIdentityService identities,
                                 ConsentStore consent, SiteProperties site, String peer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.consent = consent;
        this.commands = new RelationCommands(jdbc, tx, relations, causal, outbox, identities, consent, site, peer);
    }

    /** Compatibility constructor for direct fixtures; application wiring shares one causal store bean. */
    public RelationBackfillStore(JdbcTemplate jdbc, SyncTransactions tx, RelationStore relations,
                                 OutboxService outbox, EntityIdentityService identities,
                                 ConsentStore consent, SiteProperties site, String peer) {
        this(jdbc, tx, relations, new RelationCausalStore(jdbc), outbox, identities, consent, site, peer);
    }

    public record Job(UUID id, UUID subject, long userId, long epoch, UUID token,
                      int phase, long cursor, String intentCursor, int attempts) { }
    public record Candidate(RelationKind kind, long rowId, long actor, long target,
                            Long owner, Long tag, boolean present) { }
    public record Result(int handled, boolean complete, boolean waiting, Job job) { }

    public void enqueueReadySubjects() {
        tx.required(() -> {
            jdbc.update("""
                    INSERT INTO eatwhat_sync.relation_backfill_job(subject_id,user_id,epoch)
                    SELECT subject_id,user_id,epoch FROM eatwhat_consent.subject
                    WHERE status='GRANTED' AND identity_ready AND withdrawal_request_id IS NULL
                    ON CONFLICT(subject_id,epoch) DO UPDATE SET
                      status=CASE WHEN eatwhat_sync.relation_backfill_job.status IN ('WAIT','DONE')
                                      AND EXISTS(SELECT 1 FROM eatwhat_sync.relation_intent i
                                        WHERE i.blocked_reason IS NULL
                                          AND (i.actor_gid=EXCLUDED.subject_id OR i.owner_gid=EXCLUDED.subject_id)
                                          AND (i.published_revision<i.intent_revision
                                            OR i.published_actor_epoch IS DISTINCT FROM
                                              (SELECT s.epoch FROM eatwhat_consent.subject s WHERE s.subject_id=i.actor_gid)
                                            OR i.published_owner_epoch IS DISTINCT FROM
                                              (SELECT s.epoch FROM eatwhat_consent.subject s WHERE s.subject_id=i.owner_gid)))
                                  THEN 'READY'
                                  WHEN eatwhat_sync.relation_backfill_job.status='WAIT'
                                  THEN 'READY' ELSE eatwhat_sync.relation_backfill_job.status END,
                      available_at=CASE WHEN eatwhat_sync.relation_backfill_job.status IN ('WAIT','DONE')
                                        THEN clock_timestamp() ELSE eatwhat_sync.relation_backfill_job.available_at END,
                      updated_at=clock_timestamp()
                    """);
            return null;
        });
    }

    public List<Job> claim(int limit, Duration lease) {
        if (limit < 1 || limit > 16 || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("Invalid relation backfill lease");
        }
        return tx.independent(() -> jdbc.query("""
                WITH candidates AS (
                  SELECT job_id FROM eatwhat_sync.relation_backfill_job
                  WHERE (status IN ('READY','WAIT') AND available_at<=clock_timestamp())
                     OR (status='LEASED' AND lease_until<=clock_timestamp())
                  ORDER BY available_at,job_id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE eatwhat_sync.relation_backfill_job j
                SET status='LEASED',lease_token=gen_random_uuid(),lease_until=clock_timestamp()+(? * interval '1 millisecond'),
                    attempts=attempts+1,updated_at=clock_timestamp()
                FROM candidates c WHERE j.job_id=c.job_id
                RETURNING j.job_id,j.subject_id,j.user_id,j.epoch,j.lease_token,j.phase,j.row_cursor,j.history_cursor,j.attempts
                """, (row, index) -> new Job(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getLong(3), row.getLong(4), row.getObject(5, UUID.class), row.getInt(6), row.getLong(7),
                row.getString(8), row.getInt(9)), limit, lease.toMillis()));
    }

    public Result process(Job job, int batchSize) {
        int handled = 0;
        Job current = job;
        for (int i = 0; i < batchSize; i++) {
            Job stepInput = current;
            Step step = tx.required(() -> processStep(stepInput));
            current = step.job();
            handled += step.handled();
            if (step.complete() || step.waiting()) {
                return new Result(handled, step.complete(), step.waiting(), current);
            }
        }
        Job toRelease = current;
        tx.required(() -> { release(toRelease); return null; });
        return new Result(handled, false, false, current);
    }

    private record Step(int handled, boolean complete, boolean waiting, Job job) { }

    private Step processStep(Job job) {
        if (!leaseCurrent(job)) return new Step(0, false, true, job);
        var actorState = consent.getBySubject(job.subject());
        if (actorState == null || actorState.epoch() != job.epoch() || !actorState.status().equals("GRANTED")
                || !actorState.identityReady()) {
            finish(job, "DONE", null);
            return new Step(0, true, false, job);
        }

        List<RelationStore.Intent> intents = HISTORY_SCAN_COMPLETE.equals(job.intentCursor())
                ? List.of() : new RelationStore(jdbc).pendingIntents(job.subject(), job.intentCursor());
        if (!intents.isEmpty()) {
            RelationStore.Intent intent = intents.getFirst();
            commands.publishPendingIntent(intent.naturalId());
            fenceUpdate(job, "UPDATE eatwhat_sync.relation_backfill_job SET history_cursor=?,updated_at=clock_timestamp() WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()",
                    intent.naturalId().toString(), job.id(), job.token());
            Job advanced = new Job(job.id(), job.subject(), job.userId(), job.epoch(), job.token(),
                    job.phase(), job.cursor(), intent.naturalId().toString(), job.attempts());
            return new Step(1, false, false, advanced);
        }
        if (!HISTORY_SCAN_COMPLETE.equals(job.intentCursor())) {
            job = new Job(job.id(), job.subject(), job.userId(), job.epoch(), job.token(),
                    job.phase(), job.cursor(), HISTORY_SCAN_COMPLETE, job.attempts());
        }

        if (job.phase() >= RelationKind.values().length) {
            if (!new RelationStore(jdbc).pendingIntents(job.subject(), null).isEmpty()) {
                waitJob(job, "DEPENDENCY_WAIT");
                return new Step(0, false, true, job);
            }
            finish(job, "DONE", null);
            return new Step(0, true, false, job);
        }
        {
            RelationKind kind = RelationKind.values()[job.phase()];
            Candidate candidate = nextCandidate(kind, actorState.userId(), job.cursor());
            if (candidate == null) {
                int nextPhase = job.phase() + 1;
                fenceUpdate(job, "UPDATE eatwhat_sync.relation_backfill_job SET phase=?,row_cursor=0,history_cursor=?,updated_at=clock_timestamp() WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()",
                        nextPhase, job.intentCursor(), job.id(), job.token());
                Job advanced = new Job(job.id(), job.subject(), job.userId(), job.epoch(), job.token(), nextPhase, 0,
                        job.intentCursor(), job.attempts());
                return new Step(0, false, false, advanced);
            }
            if (!commands.backfillCandidate(candidate.kind(), candidate.actor(), candidate.target(), candidate.owner(),
                    candidate.tag(), candidate.rowId(), candidate.present())) {
                waitJob(job, "DEPENDENCY_WAIT");
                return new Step(0, false, true, job);
            }
            fenceUpdate(job, """
                    UPDATE eatwhat_sync.relation_backfill_job SET row_cursor=?,history_cursor=?,updated_at=clock_timestamp()
                    WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                      AND phase=? AND row_cursor=?
                    """, candidate.rowId(), job.intentCursor(), job.id(), job.token(), job.phase(), job.cursor());
            Job advanced = new Job(job.id(), job.subject(), job.userId(), job.epoch(), job.token(), job.phase(),
                    candidate.rowId(), job.intentCursor(), job.attempts());
            return new Step(1, false, false, advanced);
        }
    }

    private void release(Job job) {
        fenceUpdate(job, "UPDATE eatwhat_sync.relation_backfill_job SET status='READY',available_at=clock_timestamp(),\n"
                + "lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()",
                job.id(), job.token());
    }

    private Candidate nextCandidate(RelationKind kind, long subjectUserId, long cursor) {
        String sql = switch (kind) {
            case food_like -> """
                    SELECT r.id,r.account_id,r.target_id,f.account_id,NULL::bigint,r.deleted_at IS NULL
                    FROM public.likes r JOIN public.foods f ON f.id=r.target_id
                    WHERE r.target_type='food' AND (r.account_id=? OR f.account_id=?) AND r.id>?
                    ORDER BY r.id LIMIT 1
                    """;
            case activity_food_like -> activityCandidate("food", "activity_foods");
            case activity_dinner_like -> activityCandidate("dinner", "activity_dinners");
            case activity_post_like -> activityCandidate("post", "activity_posts");
            case favorite -> """
                    SELECT r.id,r.account_id,r.food_id,f.account_id,NULL::bigint,true
                    FROM public.food_favorites r JOIN public.foods f ON f.id=r.food_id
                    WHERE (r.account_id=? OR f.account_id=?) AND r.id>? ORDER BY r.id LIMIT 1
                    """;
            case follow -> """
                    SELECT r.id,r.account_id,r.target_id,r.target_id,NULL::bigint,true
                    FROM public.follow r WHERE (r.account_id=? OR r.target_id=?) AND r.id>?
                    ORDER BY r.id LIMIT 1
                    """;
            case tagging -> """
                    SELECT r.id,r.account_id,r.food_id,f.account_id,r.tag_id,true
                    FROM public.food_taggings r JOIN public.foods f ON f.id=r.food_id
                    WHERE (r.account_id=? OR f.account_id=?) AND r.id>? ORDER BY r.id LIMIT 1
                    """;
        };
        return jdbc.query(sql, (row, index) -> new Candidate(kind, row.getLong(1), row.getLong(2), row.getLong(3),
                (Long) row.getObject(4), (Long) row.getObject(5), row.getBoolean(6)),
                subjectUserId, subjectUserId, cursor).stream().findFirst().orElse(null);
    }

    private static String activityCandidate(String targetType, String table) {
        return """
                SELECT r.id,r.account_id,r.activity_id,t.account_id,NULL::bigint,r.deleted_at IS NULL
                FROM public.activity_likes r JOIN public.%s t ON t.id=r.activity_id
                    WHERE r.target_type='%s' AND t.deleted_at IS NULL AND (r.account_id=? OR t.account_id=?) AND r.id>?
                ORDER BY r.id LIMIT 1
                """.formatted(table, targetType);
    }

    private boolean leaseCurrent(Job job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_sync.relation_backfill_job
                  WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp())
                """, Boolean.class, job.id(), job.token()));
    }

    private void waitJob(Job job, String reason) {
        fenceUpdate(job, """
                UPDATE eatwhat_sync.relation_backfill_job SET status='WAIT',available_at=clock_timestamp()+interval '15 seconds',
                  lease_token=NULL,lease_until=NULL,history_cursor=NULL,last_error=?,updated_at=clock_timestamp()
                WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                """, reason, job.id(), job.token());
    }

    private void finish(Job job, String status, String error) {
        fenceUpdate(job, """
                UPDATE eatwhat_sync.relation_backfill_job SET status=?,available_at=clock_timestamp(),
                  lease_token=NULL,lease_until=NULL,last_error=?,
                  history_cursor=CASE WHEN ?='DONE' THEN NULL ELSE history_cursor END,updated_at=clock_timestamp()
                WHERE job_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp()
                """, status, error, status, job.id(), job.token());
    }

    private void fenceUpdate(Job job, String sql, Object... args) {
        if (jdbc.update(sql, args) != 1) throw new IllegalStateException("Relation backfill lease lost");
    }
}
