package you.v50to.eatwhat.account.multiwriter;

import java.util.*;

/** Immutable, field-scoped causal register. Wall clocks never select security state. */
public final class AccountOperationGraph {
    public static final Set<String> FIELDS=Set.of("password","username","email","phone","verification","banned","role","consent","display_name");
    public record Operation(UUID id,UUID subjectId,String field,String value,Set<UUID> parents) {
        public Operation {
            Objects.requireNonNull(id);Objects.requireNonNull(subjectId);Objects.requireNonNull(value);
            if(!FIELDS.contains(field)||value.length()>32768||parents==null||parents.size()>1024||parents.contains(id))throw new IllegalArgumentException("Invalid account operation");
            parents=Set.copyOf(parents);
        }
        @Override public String toString(){return "AccountOperation[redacted]";}
    }
    public record Reduced(String value,boolean conflict,boolean complete,Set<UUID> heads) { }
    private final Map<UUID,Operation> operations=new HashMap<>();
    public AccountOperationGraph(Collection<Operation> values) {
        for(var value:values){var previous=operations.putIfAbsent(value.id(),value);if(previous!=null&&!previous.equals(value))throw new IllegalArgumentException("Changed immutable account operation");}
        for(var value:operations.values())for(var parent:value.parents()){
            var ancestor=operations.get(parent);if(ancestor!=null&&(!ancestor.subjectId().equals(value.subjectId())||!ancestor.field().equals(value.field())))throw new IllegalArgumentException("Cross-domain account dependency");
        }
        // Kahn traversal is linear and avoids recursive stack exhaustion on long-lived histories.
        Map<UUID,Integer> incoming=new HashMap<>();Map<UUID,List<UUID>> children=new HashMap<>();
        for(var operation:operations.values()){int degree=0;for(UUID parent:operation.parents())if(operations.containsKey(parent)){degree++;children.computeIfAbsent(parent,k->new ArrayList<>()).add(operation.id());}incoming.put(operation.id(),degree);}
        ArrayDeque<UUID> ready=new ArrayDeque<>();incoming.forEach((id,degree)->{if(degree==0)ready.add(id);});int visited=0;
        while(!ready.isEmpty()){UUID id=ready.remove();visited++;for(UUID child:children.getOrDefault(id,List.of()))if(incoming.compute(child,(key,value)->value-1)==0)ready.add(child);}
        if(visited!=operations.size())throw new IllegalArgumentException("Cyclic account dependency");
    }
    public boolean descends(UUID child,UUID ancestor){
        Set<UUID> seen=new HashSet<>();ArrayDeque<UUID> pending=new ArrayDeque<>();pending.add(child);
        while(!pending.isEmpty()){UUID id=pending.remove();if(id.equals(ancestor))return true;if(seen.add(id)&&operations.containsKey(id))pending.addAll(operations.get(id).parents());}return false;
    }
    public Reduced reduce(UUID subject,String field){
        var relevant=operations.values().stream().filter(o->o.subjectId().equals(subject)&&o.field().equals(field)).toList();
        Set<UUID> heads=new HashSet<>();relevant.forEach(o->heads.add(o.id()));relevant.forEach(o->heads.removeAll(o.parents()));
        boolean complete=relevant.stream().allMatch(o->operations.keySet().containsAll(o.parents()));
        if(!complete||heads.isEmpty())return new Reduced(null,false,false,Set.copyOf(heads));
        Set<String> values=new HashSet<>();heads.forEach(id->values.add(operations.get(id).value()));
        String restrictive=switch(field){case "banned"->"true";case "consent"->"WITHDRAWN";case "role"->"user";default->null;};
        if(restrictive!=null&&values.contains(restrictive))return new Reduced(restrictive,false,true,Set.copyOf(heads));
        // A nickname carries no security meaning: concurrent edits converge on the same value at every site.
        if(field.equals("display_name"))return new Reduced(values.stream().max(Comparator.naturalOrder()).orElseThrow(),false,true,Set.copyOf(heads));
        return new Reduced(values.size()==1?values.iterator().next():null,values.size()>1,true,Set.copyOf(heads));
    }
}
