package you.v50to.eatwhat.sync.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** Shared read/relationship guard; absent state is the backwards-compatible visible state. */
public final class CatalogVisibility {
    private CatalogVisibility() { }
    public static boolean visible(JdbcTemplate jdbc, CatalogKind kind, long numericId) {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(kind);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM eatwhat_sync.catalog_object o "
                + "LEFT JOIN eatwhat_sync.catalog_projection_state s ON s.kind=o.kind AND s.object_gid=o.object_gid "
                + "WHERE o.kind=? AND o.numeric_id=? AND (o.tombstone OR o.rejected OR COALESCE(s.status,'VISIBLE')<>'VISIBLE')) "
                + (kind==CatalogKind.FOOD ? "AND NOT EXISTS (SELECT 1 FROM public.foods f JOIN eatwhat_sync.catalog_object p "
                + "ON p.kind='RESTAURANT' AND p.numeric_id=f.restaurant_id LEFT JOIN eatwhat_sync.catalog_projection_state ps "
                + "ON ps.kind=p.kind AND ps.object_gid=p.object_gid WHERE f.id=? "
                + "AND (p.tombstone OR p.rejected OR COALESCE(ps.status,'VISIBLE')<>'VISIBLE'))":""),
                Boolean.class,kind==CatalogKind.FOOD?new Object[]{kind.name(),numericId,numericId}:new Object[]{kind.name(),numericId}));
    }
    /** SQL identifiers are code-owned, never user input. */
    public static String predicate(CatalogKind kind,String idExpression) {
        if(!idExpression.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?"))
            throw new IllegalArgumentException("Invalid catalog identifier");
        return "NOT EXISTS (SELECT 1 FROM eatwhat_sync.catalog_object cv_o "
                +"LEFT JOIN eatwhat_sync.catalog_projection_state cv_s ON cv_s.kind=cv_o.kind AND cv_s.object_gid=cv_o.object_gid "
                +"WHERE cv_o.kind='"+kind.name()+"' AND cv_o.numeric_id="+idExpression
                +" AND (cv_o.tombstone OR cv_o.rejected OR COALESCE(cv_s.status,'VISIBLE')<>'VISIBLE'))";
    }
}
