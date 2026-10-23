package you.v50to.eatwhat.service.storage.replication;

import java.util.List;

/**
 * Registry of every business column that stores a managed object key (a file alias logical key) and can be written
 * WITHOUT going through file_reference (for example by inbound sync or catalog conflict resolution). The GC refuses to
 * delete a revision while any of these columns still holds one of its alias keys (BUSINESS_REFERENCE_PRESENT). This is a
 * belt-and-braces check on top of the reference/tombstone model.
 *
 * <p>Keep this list reviewable and complete: an integration test compares it with every column in the public schema whose
 * name suggests an image or file key, so adding such a column without registering it fails the build.
 */
public final class BusinessKeyColumns {
    private BusinessKeyColumns() {
    }

    /**
     * @param table schema-qualified table
     * @param column column name
     * @param array true for text[] columns (overlap test), false for text (equality test)
     * @param activeFilter extra predicate selecting rows that still count (null = all rows)
     */
    public record Column(String table, String column, boolean array, String activeFilter) {
        /** Query returning a row when any row holds one of the keys ({@code ?} is a text[] of alias logical keys). */
        public String existsSql() {
            String match = array ? column + " && ?::text[]" : column + " = ANY (?)";
            return "SELECT 1 FROM " + table + " WHERE " + match
                    + (activeFilter == null ? "" : " AND " + activeFilter) + " LIMIT 1";
        }
    }

    /**
     * Activity rows are soft-deleted (deleted_at); a soft-deleted post is semantically gone and its release already
     * happened, so it does not pin the file forever. Hard-deleted tables have no filter.
     */
    /** Active avatar conflict heads can still be projected after a merge. Retained history of deleted users does
     * not pin files forever. This is a separate SQL guard because the key is embedded in JSON, not a public column. */
    public static final String PROFILE_AVATAR_SQL = """
            SELECT 1 FROM eatwhat_file.file_profile_avatar_keys WHERE logical_key=ANY(?::text[]) LIMIT 1
            """;

    public static final List<Column> ALL = List.of(
            new Column("public.users", "avatar", false, null),
            new Column("public.restaurant", "picture_url", true, null),
            new Column("public.foods", "picture_url", true, null),
            new Column("public.activity_foods", "picture_url", true, "deleted_at IS NULL"),
            new Column("public.activity_dinners", "picture_url", true, "deleted_at IS NULL"),
            new Column("public.activity_posts", "picture_url", true, "deleted_at IS NULL"));
}
