package you.v50to.eatwhat.control;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.util.*;

/** Target-specific S3 material is part of the original parent approval. It
 * never masquerades as SOURCE capture and cannot inject arbitrary properties. */
public final class ControlBootstrapStorage {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String PREFIX="app.storage.s3.";
    private static final Set<String> KEYS=Set.of("provider","endpoint","region","bucket","access-key","secret-key","path-style","public-base-url","cdn-endpoint");
    private final ControlServerStore store;
    public ControlBootstrapStorage(ControlServerStore store){this.store=store;}
    public record Selection(String provider,String endpoint,String region,String bucket,
                            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String accessKey,
                            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String secretKey,
                            Boolean pathStyle,String publicBaseUrl,String cdnEndpoint){
        @Override public String toString(){return "BootstrapStorageSelection[redacted]";}
    }
    public record Frozen(UUID bootstrapId,UUID serverId,long revision,String encryptedProperties,Map<String,Object> review,String binding){
        public Frozen {review=Map.copyOf(review);}
        @Override public String toString(){return "BootstrapStorage[redacted]";}
    }
    private static void require(boolean ok){if(!ok)throw new ControlFailure(409,"BOOTSTRAP_STORAGE_CONFIGURATION_REQUIRED");}
    private static String text(String value,int max){require(value!=null&&!value.isBlank()&&value.length()<=max&&value.equals(value.strip())&&!value.contains("${")&&value.chars().noneMatch(Character::isISOControl));return value;}
    private static String url(String value,boolean optional){
        if(optional&&(value==null||value.isBlank()))return "";
        try{var uri=URI.create(text(value,2048));require(Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null);return uri.toASCIIString();}
        catch(ControlFailure failure){throw failure;}catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_STORAGE_CONFIGURATION_REQUIRED");}
    }
    private static Map<String,String> properties(Selection s){
        require(s!=null);String provider=text(s.provider(),16).toUpperCase(Locale.ROOT);require(Set.of("GENERIC","RUSTFS","R2").contains(provider));
        var p=new TreeMap<String,String>();p.put(PREFIX+"provider",provider);p.put(PREFIX+"endpoint",url(s.endpoint(),false));
        p.put(PREFIX+"region",provider.equals("R2")?"auto":text(s.region(),128));p.put(PREFIX+"bucket",text(s.bucket(),128));
        p.put(PREFIX+"access-key",text(s.accessKey(),1024));p.put(PREFIX+"secret-key",text(s.secretKey(),4096));
        p.put(PREFIX+"path-style",Boolean.toString(s.pathStyle()!=null?s.pathStyle():!provider.equals("R2")));
        p.put(PREFIX+"public-base-url",url(s.publicBaseUrl(),true));p.put(PREFIX+"cdn-endpoint",url(s.cdnEndpoint(),true));return p;
    }
    private static Selection source(Map<String,String> p){
        String path=p.getOrDefault(PREFIX+"path-style","true");require(Set.of("true","false").contains(path));
        return new Selection(p.getOrDefault(PREFIX+"provider","GENERIC"),p.get(PREFIX+"endpoint"),p.get(PREFIX+"region"),p.get(PREFIX+"bucket"),
                p.get(PREFIX+"access-key"),p.get(PREFIX+"secret-key"),Boolean.parseBoolean(path),p.get(PREFIX+"public-base-url"),p.get(PREFIX+"cdn-endpoint"));
    }
    /** Includes secret bytes in a keyed request fingerprint, never in a public
     * request echo or an unkeyed password digest. */
    String requestBinding(Selection s){return store.fingerprint(encode(s==null?List.of("SOURCE"):List.of("CUSTOM",properties(s))));}
    public Frozen freeze(UUID id,UUID target,long revision,Selection selection,Map<String,String> source){
        require(id!=null&&target!=null&&revision>0);var p=properties(selection==null?source(source):selection);
        var review=new TreeMap<String,Object>();review.put("mode",selection==null?"SOURCE":"CUSTOM");
        for(String key:List.of("provider","endpoint","region","bucket","public-base-url","cdn-endpoint"))review.put(key,p.get(PREFIX+key));
        review.put("pathStyle",Boolean.parseBoolean(p.get(PREFIX+"path-style")));review.put("credentialsConfigured",true);
        String encrypted=store.encrypt(encode(p));String binding=bind(id,target,revision,p,review);
        return new Frozen(id,target,revision,encrypted,review,binding);
    }
    private String bind(UUID id,UUID target,long revision,Map<String,String> p,Map<String,Object> review){return store.fingerprint(encode(List.of(id.toString(),target.toString(),revision,new TreeMap<>(p),new TreeMap<>(review))));}
    private static String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_STORAGE_CONFIGURATION_REQUIRED");}}
    Map<String,String> open(Frozen f,UUID id,UUID target,long revision){
        try{
            require(f!=null&&id.equals(f.bootstrapId())&&target.equals(f.serverId())&&revision==f.revision());
            var raw=JSON.readValue(store.decrypt(f.encryptedProperties()),Map.class);var p=new TreeMap<String,String>();
            require(raw.size()==KEYS.size());raw.forEach((k,v)->{require(k instanceof String&&v instanceof String);String name=k.toString();require(name.startsWith(PREFIX)&&KEYS.contains(name.substring(PREFIX.length())));p.put(name,v.toString());});
            require(p.equals(properties(source(p)))&&bind(id,target,revision,p,f.review()).equals(f.binding()));return Map.copyOf(p);
        }catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_STORAGE_BINDING_CHANGED");}
    }
    static Frozen read(Object value){try{return JSON.readValue(JSON.writeValueAsBytes(value),Frozen.class);}catch(Exception invalid){throw new ControlFailure(409,"BOOTSTRAP_STORAGE_BINDING_CHANGED");}}
}
