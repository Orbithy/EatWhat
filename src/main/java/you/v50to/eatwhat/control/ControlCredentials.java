package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Operator-owned key references. HTTP clients can select identifiers, never filesystem paths. */
final class ControlCredentials {
    private record Entry(String label,Path file) {}
    private final Map<String,Entry> entries;
    ControlCredentials(ControlProperties settings){
        Path file=settings.getServers().getCredentialsFile();
        if(file==null){entries=Map.of();return;}
        try{
            var json=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            var root=json.readTree(ControlMemberConfiguration.privateBytes(file,65536));
            if(!root.isObject()||!Set.copyOf(root.propertyNames()).equals(Set.of("version","credentials"))
                    ||!root.path("version").isIntegralNumber()||root.path("version").asInt()!=1
                    ||!root.path("credentials").isObject()||root.path("credentials").size()>64)throw new Exception();
            var parsed=new TreeMap<String,Entry>();
            for(String id:root.path("credentials").propertyNames()){
                if(!validId(id))throw new Exception();var value=root.path("credentials").path(id);
                if(!value.isObject()||!Set.copyOf(value.propertyNames()).equals(Set.of("label","authentication","file")))throw new Exception();
                for(String key:List.of("label","authentication","file"))if(!value.path(key).isString())throw new Exception();
                String label=value.path("label").asText();
                if(label.isBlank()||label.length()>64||label.chars().anyMatch(Character::isISOControl)
                        ||!value.path("authentication").asText().equals("PRIVATE_KEY"))throw new Exception();
                Path key=Path.of(value.path("file").asText());
                if(!key.isAbsolute()||!key.equals(key.normalize()))throw new Exception();
                for(Path p=key;p!=null;p=p.getParent())if(java.nio.file.Files.isSymbolicLink(p))throw new Exception();
                parsed.put(id,new Entry(label,key));
            }
            entries=Collections.unmodifiableMap(parsed);
        }catch(Exception invalid){throw new ControlFailure(503,"PRIVATE_SSH_CREDENTIALS_INVALID");}
    }
    static boolean validId(String id){return id!=null&&id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}");}
    List<Map<String,String>> metadata(){return entries.entrySet().stream().map(e->Map.of("id",e.getKey(),"label",e.getValue().label(),"authentication","PRIVATE_KEY")).toList();}
    String resolve(String id,String authentication){
        if(!validId(id)||!"PRIVATE_KEY".equals(authentication))throw new ControlFailure(400,"INVALID_SSH_CREDENTIAL_REFERENCE");
        Entry entry=entries.get(id);if(entry==null)throw new ControlFailure(400,"SSH_CREDENTIAL_REFERENCE_UNAVAILABLE");
        try{
            String key=new String(ControlMemberConfiguration.privateBytes(entry.file(),16384),StandardCharsets.UTF_8);
            if(!key.startsWith("-----BEGIN ")||!key.contains("PRIVATE KEY-----")||key.indexOf(0)>=0)throw new Exception();
            return key;
        }catch(Exception invalid){throw new ControlFailure(503,"SSH_CREDENTIAL_REFERENCE_UNAVAILABLE");}
    }
}
