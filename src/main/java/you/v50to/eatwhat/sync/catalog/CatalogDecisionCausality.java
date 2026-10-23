package you.v50to.eatwhat.sync.catalog;

import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.VersionVector;
import java.util.*;

/** Immutable causal decisions. A database revision is deliberately not a cross-site ordering clock. */
public final class CatalogDecisionCausality {
    private CatalogDecisionCausality() { }
    public enum Status { OPEN, RESOLVED, REJECTED, CONFLICT }
    public record Decision(UUID operationId, String site, long counter, Map<String,Long> context,
                           List<UUID> candidateOperations, CatalogDecision decision, UUID winnerOperationId) {
        public Decision {
            SyncEvent.requireUuid(operationId,"catalog decision");
            SyncEvent.requireName(site,"decision site",64);
            if (context == null || context.size()>VersionVector.MAX_SITES || context.entrySet().stream()
                    .anyMatch(e -> e.getKey()==null || e.getValue()==null || e.getValue()<1 || e.getValue()>VersionVector.MAX_COUNTER))
                throw new IllegalArgumentException("Invalid decision context");
            context.forEach((key,value)->SyncEvent.requireName(key,"decision context site",64));
            context=Map.copyOf(context);
            if(counter<1 || counter>VersionVector.MAX_COUNTER || context.getOrDefault(site,0L)+1!=counter)
                throw new IllegalArgumentException("Invalid decision dot");
            if(candidateOperations==null || candidateOperations.isEmpty() || candidateOperations.size()>32
                    || candidateOperations.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(candidateOperations).size()!=candidateOperations.size())
                throw new IllegalArgumentException("Invalid decision candidates");
            candidateOperations=candidateOperations.stream().sorted().toList();
            Objects.requireNonNull(decision);
            if((decision==CatalogDecision.KEEP)!=(winnerOperationId!=null)
                    || winnerOperationId!=null&&!candidateOperations.contains(winnerOperationId))
                throw new IllegalArgumentException("Invalid decision winner");
        }
    }
    public record Outcome(Status status, UUID winnerOperationId, List<Decision> heads) { }
    public static List<Decision> merge(Collection<Decision> current, Collection<Decision> incoming) {
        // Validate all immutable evidence before removing dominated heads; an equivocation must
        // not disappear merely because another operation happens to causally supersede it.
        Map<UUID,Decision> byId=new HashMap<>();
        Map<String,Decision> byDot=new HashMap<>();
        for(var group:List.of(current,incoming)) for(Decision next:group) {
            Objects.requireNonNull(next);
            Decision existing=byId.putIfAbsent(next.operationId(),next);
            if(existing!=null&&!existing.equals(next)) throw new IllegalArgumentException("CATALOG_DECISION_IDENTITY_CONFLICT");
            existing=byDot.putIfAbsent(next.site()+":"+next.counter(),next);
            if(existing!=null&&!existing.equals(next)) throw new IllegalArgumentException("CATALOG_DECISION_DOT_CONFLICT");
        }
        List<Decision> all=List.copyOf(byId.values());
        return all.stream().filter(candidate->all.stream().noneMatch(other->!other.operationId().equals(candidate.operationId())
                &&other.context().getOrDefault(candidate.site(),0L)>=candidate.counter()))
                .sorted(Comparator.comparing(Decision::operationId)).toList();
    }
    public static Outcome evaluate(Collection<Decision> decisions) {
        List<Decision> heads=merge(List.of(),decisions);
        if(heads.isEmpty()) return new Outcome(Status.OPEN,null,heads);
        Decision first=heads.getFirst();
        boolean same=heads.stream().allMatch(h->h.decision()==first.decision()
                &&Objects.equals(h.winnerOperationId(),first.winnerOperationId())
                &&h.candidateOperations().equals(first.candidateOperations()));
        return new Outcome(!same?Status.CONFLICT:first.decision()==CatalogDecision.KEEP?Status.RESOLVED:Status.REJECTED,
                same?first.winnerOperationId():null,heads);
    }
}
