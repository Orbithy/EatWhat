package you.v50to.eatwhat.utils;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKTReader;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

@MappedTypes(MultiPolygon.class)
public class MultiPolygonTypeHandler extends BaseTypeHandler<MultiPolygon> {

    private final WKTReader wktReader = new WKTReader();
    private final WKBReader wkbReader = new WKBReader();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, MultiPolygon parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, toEwkt(parameter), Types.OTHER);
    }

    @Override
    public MultiPolygon getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseMultiPolygon(rs.getObject(columnName));
    }

    @Override
    public MultiPolygon getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseMultiPolygon(rs.getObject(columnIndex));
    }

    @Override
    public MultiPolygon getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseMultiPolygon(cs.getObject(columnIndex));
    }

    private MultiPolygon parseMultiPolygon(Object raw) throws SQLException {
        if (raw == null) {
            return null;
        }
        if (raw instanceof MultiPolygon multiPolygon) {
            return multiPolygon;
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
            Geometry geometry = isHexEncoded(value) ? wkbReader.read(hexToBytes(value)) : wktReader.read(value);
            if (geometry instanceof MultiPolygon multiPolygon) {
                return multiPolygon;
            }
            if (geometry instanceof Polygon polygon) {
                return polygon.getFactory().createMultiPolygon(new Polygon[]{polygon});
            }
            throw new SQLException("Expected MultiPolygon geometry but got " + geometry.getGeometryType());
        } catch (ParseException e) {
            throw new SQLException("Failed to parse PostGIS multipolygon value: " + value, e);
        }
    }

    private String toEwkt(MultiPolygon multiPolygon) {
        int srid = multiPolygon.getSRID() > 0 ? multiPolygon.getSRID() : 4326;
        return "SRID=" + srid + ";" + multiPolygon;
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
