package you.v50to.eatwhat.sync.catalog;

/** Catalog aggregates supported by the directory synchronization protocol. */
public enum CatalogKind {
    HUB,
    RESTAURANT,
    FOOD,
    SYSTEM_TAG,
    CUSTOM_TAG,
    /** Administrator-managed activity definition (public.activities), keyed by its code. */
    ACTIVITY
}
