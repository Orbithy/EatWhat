package you.v50to.eatwhat.utils;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

public class GeoUtil {

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    public static Point createPoint(double lng, double lat) {
        return FACTORY.createPoint(new Coordinate(lng, lat));
    }

    public static Point gcjToWgsPoint(double lng, double lat) {
        double[] wgs = CoordinateTransformUtil.gcj02ToWgs84(lng, lat);
        return createPoint(wgs[0], wgs[1]);
    }
}