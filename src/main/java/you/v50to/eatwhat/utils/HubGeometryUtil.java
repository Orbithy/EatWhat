package you.v50to.eatwhat.utils;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.data.enums.BizCode;

public final class HubGeometryUtil {

    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HubGeometryUtil() {
    }

    public static MultiPolygon parseBoundaryGeoJson(String geoJson) {
        if (geoJson == null || geoJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(geoJson);
            String type = root.path("type").asText();
            JsonNode coordinates = root.path("coordinates");
            if ("Polygon".equals(type)) {
                return FACTORY.createMultiPolygon(new Polygon[]{parsePolygon(coordinates)});
            }
            if ("MultiPolygon".equals(type)) {
                Polygon[] polygons = new Polygon[coordinates.size()];
                for (int i = 0; i < coordinates.size(); i++) {
                    polygons[i] = parsePolygon(coordinates.get(i));
                }
                return FACTORY.createMultiPolygon(polygons);
            }
            throw new BizException(BizCode.PARAM_INVALID, "boundaryGeoJson 仅支持 Polygon 或 MultiPolygon");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizCode.PARAM_INVALID, "boundaryGeoJson 格式非法");
        }
    }

    private static Polygon parsePolygon(JsonNode polygonNode) {
        if (!polygonNode.isArray() || polygonNode.isEmpty()) {
            throw new BizException(BizCode.PARAM_INVALID, "Polygon 坐标不能为空");
        }
        LinearRing shell = parseLinearRing(polygonNode.get(0));
        LinearRing[] holes = new LinearRing[Math.max(0, polygonNode.size() - 1)];
        for (int i = 1; i < polygonNode.size(); i++) {
            holes[i - 1] = parseLinearRing(polygonNode.get(i));
        }
        return FACTORY.createPolygon(shell, holes);
    }

    private static LinearRing parseLinearRing(JsonNode ringNode) {
        if (!ringNode.isArray() || ringNode.size() < 4) {
            throw new BizException(BizCode.PARAM_INVALID, "边界环至少需要4个点");
        }
        Coordinate[] coordinates = new Coordinate[ringNode.size()];
        for (int i = 0; i < ringNode.size(); i++) {
            JsonNode pointNode = ringNode.get(i);
            if (!pointNode.isArray() || pointNode.size() < 2) {
                throw new BizException(BizCode.PARAM_INVALID, "边界点格式非法");
            }
            double gcjLng = pointNode.get(0).asDouble();
            double gcjLat = pointNode.get(1).asDouble();
            double[] wgs = CoordinateTransformUtil.gcj02ToWgs84(gcjLng, gcjLat);
            coordinates[i] = new Coordinate(wgs[0], wgs[1]);
        }
        closeRingIfNeeded(coordinates);
        return new LinearRing(new CoordinateArraySequence(coordinates), FACTORY);
    }

    private static void closeRingIfNeeded(Coordinate[] coordinates) {
        if (coordinates.length < 2) {
            return;
        }
        Coordinate first = coordinates[0];
        Coordinate last = coordinates[coordinates.length - 1];
        if (!first.equals2D(last)) {
            coordinates[coordinates.length - 1] = new Coordinate(first);
        }
    }
}
