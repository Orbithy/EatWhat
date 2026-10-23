package you.v50to.eatwhat.sync.relation;

import you.v50to.eatwhat.sync.SyncEvent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Offline, owner-connection helper for binding a shared NULL-origin V18 baseline row. */
public final class RelationBaselineClaimService {
    private final Connection ownerConnection;

    public RelationBaselineClaimService(Connection ownerConnection) {
        this.ownerConnection = Objects.requireNonNull(ownerConnection, "ownerConnection");
    }

    public record Claim(RelationKind kind, long rowId, UUID rowGid, UUID actorGid,
                        UUID targetGid, UUID ownerGid, UUID tagGid, String declaredOrigin) { }

    /** The caller supplies an owner connection and commits only after checking the resulting claim. */
    public void claim(RelationKind kind, long rowId, UUID rowGid, UUID actorGid,
                      UUID targetGid, UUID ownerGid, UUID tagGid, String declaredOrigin) throws SQLException {
        claim(new Claim(kind, rowId, rowGid, actorGid, targetGid, ownerGid, tagGid, declaredOrigin));
    }

    public void claim(Claim claim) throws SQLException {
        Objects.requireNonNull(claim, "claim");
        if (claim.rowId() < 1 || claim.rowId() > 1_000_000_000L) {
            throw new IllegalArgumentException("Only an existing legacy numeric ID can be claimed");
        }
        SyncEvent.requireUuid(claim.rowGid(), "rowGid");
        SyncEvent.requireUuid(claim.actorGid(), "actorGid");
        SyncEvent.requireUuid(claim.targetGid(), "targetGid");
        SyncEvent.requireUuid(claim.ownerGid(), "ownerGid");
        if ((claim.kind() == RelationKind.tagging) != (claim.tagGid() != null)) {
            throw new IllegalArgumentException("Tag identity does not match relation kind");
        }
        if (claim.tagGid() != null) SyncEvent.requireUuid(claim.tagGid(), "tagGid");
        SyncEvent.requireName(claim.declaredOrigin(), "declaredOrigin", 64);
        try (var statement = ownerConnection.prepareStatement(
                "SELECT eatwhat_sync.claim_relation_baseline(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, claim.kind().name());
            statement.setLong(2, claim.rowId());
            statement.setObject(3, claim.rowGid());
            statement.setObject(4, claim.actorGid());
            statement.setObject(5, claim.targetGid());
            statement.setObject(6, claim.ownerGid());
            statement.setObject(7, claim.tagGid());
            statement.setString(8, claim.declaredOrigin());
            statement.execute();
        }
    }
}
