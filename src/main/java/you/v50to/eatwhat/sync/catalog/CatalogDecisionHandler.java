package you.v50to.eatwhat.sync.catalog;

import you.v50to.eatwhat.sync.*;
import java.util.Set;

public final class CatalogDecisionHandler implements EventHandler {
    private final CatalogDecisionService decisions;private final Set<String> sites;
    public CatalogDecisionHandler(CatalogDecisionService decisions,Set<String> sites){this.decisions=decisions;this.sites=sites;}
    @Override public String aggregateType(){return CatalogDecisionEvents.AGGREGATE;}
    @Override public String eventType(){return CatalogDecisionEvents.ACTION;}
    @Override public int schemaVersion(){return 2;}
    @Override public boolean dependenciesSatisfied(SyncEvent event){return decisions.dependencies(CatalogDecisionEvents.validate(event,sites));}
    @Override public void apply(SyncEvent event){decisions.apply(CatalogDecisionEvents.validate(event,sites));}
}
