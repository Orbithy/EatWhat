package you.v50to.eatwhat.sync.profile;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.AuthorizationEvidence;
import you.v50to.eatwhat.sync.EventCodec;
import java.util.*;

/** Schema 2 keeps the true administrator outside the subject's field operation. */
public record ProfileV2Envelope(ProfileEvents.Operation operation, AuthorizationEvidence authorization, Map<UUID,AuthorizationEvidence> resolutionAuthorizations,Map<UUID,ProfileEvents.Operation> resolutionOperations) {
    public ProfileV2Envelope(ProfileEvents.Operation operation,AuthorizationEvidence authorization){this(operation,authorization,Map.of(),Map.of());}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public String payload(){var n=JSON.createObjectNode();n.set("operation",JSON.readTree(ProfileEvents.payload(operation)));
        n.set("authorization",JSON.valueToTree(authorization));n.set("resolutionAuthorizations",JSON.valueToTree(resolutionAuthorizations));
        var operations=JSON.createObjectNode();resolutionOperations.forEach((id,op)->operations.set(id.toString(),JSON.readTree(ProfileEvents.payload(op))));n.set("resolutionOperations",operations);return EventCodec.canonicalJson(n.toString());}
    public static ProfileV2Envelope parse(String text){
        var n=JSON.readTree(EventCodec.canonicalJson(text));
        if(!n.isObject()||!Set.copyOf(n.propertyNames()).equals(Set.of("operation","authorization","resolutionAuthorizations","resolutionOperations")))throw new IllegalArgumentException("INVALID_PROFILE_V2");
        var op=ProfileEvents.parse(n.path("operation").toString());
        var proof=n.path("authorization");
        if(!proof.isNull()&&(!proof.isObject()||!Set.copyOf(proof.propertyNames()).equals(Set.of("subjectId","securityHeads","role","banned","witnessId"))))throw new IllegalArgumentException("INVALID_PROFILE_PROOF");
        var authorization=proof.isNull()?null:JSON.convertValue(proof,AuthorizationEvidence.class);
        if(op.resolution()!=null&&(authorization==null||!"admin".equals(authorization.role())||authorization.banned()))throw new IllegalArgumentException("PROFILE_ADMIN_REQUIRED");
        if(op.resolution()==null&&authorization!=null)throw new IllegalArgumentException("UNEXPECTED_PROFILE_ADMIN");
        var bridges=n.path("resolutionAuthorizations");
        if(!bridges.isObject()||bridges.size()>1024)throw new IllegalArgumentException("INVALID_PROFILE_AUTHORIZATIONS");
        var map=new LinkedHashMap<UUID,AuthorizationEvidence>();
        for(String id:bridges.propertyNames()) {
            var value=bridges.get(id);
            if(!value.isObject()||!Set.copyOf(value.propertyNames()).equals(Set.of("subjectId","securityHeads","role","banned","witnessId")))throw new IllegalArgumentException("INVALID_PROFILE_PROOF");
            var evidence=JSON.convertValue(value,AuthorizationEvidence.class);
            if(!"admin".equals(evidence.role())||evidence.banned())throw new IllegalArgumentException("PROFILE_ADMIN_REQUIRED");
            map.put(UUID.fromString(id),evidence);
        }
        var original=n.path("resolutionOperations");
        if(!original.isObject()||!Set.copyOf(original.propertyNames()).equals(map.keySet().stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet())))throw new IllegalArgumentException("PROFILE_RESOLUTION_PROVENANCE_REQUIRED");
        var operations=new LinkedHashMap<UUID,ProfileEvents.Operation>();
        for(String id:original.propertyNames()) {
            var parsed=ProfileEvents.parse(original.path(id).toString());
            if(parsed.resolution()==null||!parsed.subject().equals(op.subject()))throw new IllegalArgumentException("PROFILE_RESOLUTION_SCOPE");
            operations.put(UUID.fromString(id),parsed);
        }
        return new ProfileV2Envelope(op,authorization,Map.copyOf(map),Map.copyOf(operations));
    }
}
