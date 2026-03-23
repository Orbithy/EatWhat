package you.v50to.eatwhat.utils;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKTReader;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

@MappedTypes(Point.class)
public class PointTypeHandler extends BaseTypeHandler<Point> {

    private final WKTReader wktReader = new WKTReader();
    private final WKBReader wkbReader = new WKBReader();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Point parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, toEwkt(parameter), Types.OTHER);
    }

    @Override
    public Point getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parsePoint(rs.getObject(columnName));
    }

    @Override
    public Point getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parsePoint(rs.getObject(columnIndex));
    }

    @Override
    public Point getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parsePoint(cs.getObject(columnIndex));
    }

    private Point parsePoint(Object raw) throws SQLException {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Point point) {
            return point;
        }

        String value = raw.toString();
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.isEmpty()) {
            return null;
        }

        if (value.regionMatches(true, 0, "SRID=", 0, 5)) {
            int separator = value.indexOf(';');
            if (separator >= 0 && separator + 1 < value.length()) {
                value = value.substring(separator + 1).trim();
            }
        }

        try {
            Geometry geometry;
            if (isHexEncoded(value)) {
                geometry = wkbReader.read(hexToBytes(value));
            } else {
                geometry = wktReader.read(value);
            }
            if (geometry instanceof Point point) {
                return point;
            }
            throw new SQLException("Expected Point geometry but got " + geometry.getGeometryType());
        } catch (ParseException e) {
            throw new SQLException("Failed to parse PostGIS point value: " + value, e);
        }
    }

    private String toEwkt(Point point) {
        int srid = point.getSRID() > 0 ? point.getSRID() : 4326;
        return "SRID=" + srid + ";POINT(" + point.getX() + " " + point.getY() + ")";
    }

    private boolean isHexEncoded(String value) {
        return (value.length() & 1) == 0 && value.matches("^[0-9A-Fa-f]+$");
    }

    private byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return data;
    }
}
