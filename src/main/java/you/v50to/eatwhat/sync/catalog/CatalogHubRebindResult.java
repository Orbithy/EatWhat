package you.v50to.eatwhat.sync.catalog;

import java.util.List;
import java.util.UUID;

/** Source-computed restaurant assignment changes produced while rebinding one hub. */
public record CatalogHubRebindResult(long hubId, UUID hubGid, long hubRevision, int coveredCount,
                                     List<Assignment> assignments) {
    public CatalogHubRebindResult(long hubId, UUID hubGid, long hubRevision, List<Assignment> assignments) {
        this(hubId, hubGid, hubRevision, assignments.size(), assignments);
    }

    public record Assignment(UUID restaurantGid, long expectedRestaurantRevision, UUID newHubGid) { }
}
