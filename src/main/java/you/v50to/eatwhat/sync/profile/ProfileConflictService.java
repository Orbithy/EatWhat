package you.v50to.eatwhat.sync.profile;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.sync.OutboxService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CAMPUS administrator review for immutable conflicting profile heads. */
public final class ProfileConflictService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final ProfileCommands commands;
    private final SiteProperties site;

    public record Conflict(UUID conflictId, UUID subjectId, String fieldGroup, long revision,
                           List<Map<String, Object>> heads) { }

    public ProfileConflictService(JdbcTemplate jdbc, ProfileStore store, ProfileCommands commands,
                                  ConsentStore consent, OutboxService outbox, SiteProperties site, String peer) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.site = site;
    }

    public List<Conflict> openConflicts() {
        requireCampus();
        return jdbc.query("""
                SELECT c.conflict_id,c.subject_gid,c.field_group,c.revision,c.heads::text
                FROM eatwhat_sync.profile_conflict c
                JOIN eatwhat_consent.subject s ON s.subject_id=c.subject_gid
                WHERE c.status='OPEN' AND s.status='GRANTED' AND s.identity_ready
                ORDER BY c.subject_gid,c.field_group
                """, (row, index) -> new Conflict(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getString(3), row.getLong(4), JSON.readValue(row.getString(5),
                JSON.getTypeFactory().constructCollectionType(List.class,
                        JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class)))));
    }

    public boolean resolve(UUID conflictId, long expectedRevision, UUID selectedHeadId) {
        requireCampus();
        return commands.resolveConflict(conflictId, expectedRevision, selectedHeadId, StpUtil.getLoginIdAsLong());
    }

    public record AuthorizationReview(UUID eventId,UUID actorId,String reason,Conflict conflict) { }
    public List<AuthorizationReview> authorizationReviews() {
        requireCampus();
        return jdbc.query("""
            SELECT r.event_id,r.actor_gid,r.reason,c.conflict_id,c.subject_gid,c.field_group,c.revision,c.heads::text
            FROM eatwhat_sync.profile_authorization_review r JOIN eatwhat_sync.profile_conflict c
              ON c.subject_gid=r.subject_gid AND jsonb_exists(r.operation->'changes',c.field_group)
            JOIN eatwhat_consent.subject s ON s.subject_id=r.subject_gid
            WHERE c.status='OPEN' AND s.status='GRANTED' AND s.identity_ready
              AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.profile_authorization_review_decision d WHERE d.review_event_id=r.event_id)
            ORDER BY r.created_at,r.event_id,c.field_group
            """,(r,n)->new AuthorizationReview(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),new Conflict(r.getObject(4,UUID.class),r.getObject(5,UUID.class),r.getString(6),r.getLong(7),JSON.readValue(r.getString(8),JSON.getTypeFactory().constructCollectionType(List.class,JSON.getTypeFactory().constructMapType(Map.class,String.class,Object.class))))));
    }
    public boolean resolveAuthorizationReview(UUID eventId,long revision,UUID selectedHead) {
        requireCampus();
        var review=authorizationReviews().stream().filter(r->r.eventId().equals(eventId)).findFirst().orElse(null);
        return review!=null&&commands.resolveConflict(review.conflict().conflictId(),revision,selectedHead,StpUtil.getLoginIdAsLong());
    }

    private void requireCampus() {
        if (!commands.isMultiwriter() && site.getMode() != SiteProperties.Mode.CAMPUS) throw new BizException(BizCode.NO_PERMISSION);
    }
}
