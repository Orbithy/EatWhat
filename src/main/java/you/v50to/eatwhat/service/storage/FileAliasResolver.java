package you.v50to.eatwhat.service.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FileAliasResolver {
    private final JdbcTemplate jdbc;

    public FileAliasResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Resolution resolve(String logicalKey) {
        return jdbc.query("""
                SELECT a.logical_key, r.final_key, r.state, p.state,
                       EXISTS (SELECT 1 FROM eatwhat_file.file_reference_live f WHERE f.logical_key=a.logical_key AND f.visibility='PUBLIC')
                  FROM eatwhat_file.file_key_alias a
                  JOIN eatwhat_file.file_revision r ON r.file_id=a.file_id AND r.revision=a.revision
                  LEFT JOIN eatwhat_file.file_replica p ON p.file_id=r.file_id AND p.revision=r.revision AND p.site='LOCAL'
                 WHERE a.logical_key=?
                UNION ALL
                SELECT i.logical_key, NULL, i.status, NULL, false
                  FROM eatwhat_file.upload_intent i
                 WHERE i.logical_key=? AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_key_alias a WHERE a.logical_key=i.logical_key)
                LIMIT 1
                """, rs -> rs.next() ? new Resolution(true,
                "VERIFIED".equals(rs.getString(3)) && "AVAILABLE_LOCAL".equals(rs.getString(4)) && rs.getBoolean(5) ? rs.getString(2) : null) : new Resolution(false, null),
                logicalKey, logicalKey);
    }

    public record Resolution(boolean managed, String readableObjectKey) { }
}
