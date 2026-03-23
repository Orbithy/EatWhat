package you.v50to.eatwhat.utils;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;
import java.time.OffsetDateTime;

/**
 * PostgreSQL timestamptz 与 Java Long（毫秒时间戳）互转
 */
@MappedTypes(Long.class)
public class TimestampTypeHandler extends BaseTypeHandler<Long> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Long parameter, JdbcType jdbcType) throws SQLException {
        ps.setTimestamp(i, new Timestamp(parameter));
    }

    @Override
    public Long getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toEpochMilli(rs.getObject(columnName, OffsetDateTime.class));
    }

    @Override
    public Long getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toEpochMilli(rs.getObject(columnIndex, OffsetDateTime.class));
    }

    @Override
    public Long getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toEpochMilli(cs.getObject(columnIndex, OffsetDateTime.class));
    }

    private Long toEpochMilli(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant().toEpochMilli();
    }
}

