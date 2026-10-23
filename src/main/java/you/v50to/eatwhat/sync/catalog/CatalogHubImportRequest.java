package you.v50to.eatwhat.sync.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Idempotent, bounded request for importing a hub catalogue from the controlled adapter. */
public record CatalogHubImportRequest(String sourceKey, UUID requestId, String contentHash,
                                      List<HubItem> hubs) {
    public record Coordinate(double lng, double lat) { }

    public record HubItem(String sourceId, String name, Coordinate centerWgs, Coordinate centerGcj,
                          String boundaryGeoJson, boolean rebindCoveredRestaurants) {
        /** Stable field set used by the Java EventCodec hash and import implementation. */
        public Map<String, Object> canonicalPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sourceId", sourceId);
            payload.put("name", name);
            payload.put("centerWgs", coordinate(centerWgs));
            payload.put("centerGcj", coordinate(centerGcj));
            payload.put("boundaryGeoJson", boundaryGeoJson);
            payload.put("rebindCoveredRestaurants", rebindCoveredRestaurants);
            return payload;
        }

        private static Map<String, Object> coordinate(Coordinate value) {
            if (value == null) return null;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("lng", value.lng());
            point.put("lat", value.lat());
            return point;
        }
    }
}
