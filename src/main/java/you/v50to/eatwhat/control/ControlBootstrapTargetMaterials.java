package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceMaterials;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Private material part of the future target bundle factory. Rewrites file
 * references, not network endpoints. SOURCE routes are returned separately,
 * never installed unchanged or advertised as proven TARGET connectivity. */
public final class ControlBootstrapTargetMaterials {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final String CONFIG="/opt/eatwhat-control/config/";
    private final ControlBootstrapAdmission admission;
    public ControlBootstrapTargetMaterials(ControlBootstrapAdmission admission){this.admission=admission;}
    public record Prepared(@com.fasterxml.jackson.annotation.JsonIgnore Map<String,String> privateFiles,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<String,String> pathOverrides,
                           @com.fasterxml.jackson.annotation.JsonIgnore Map<String,Object> sourceRoutes,
                           @com.fasterxml.jackson.annotation.JsonIgnore List<String> profilesRequiringRouteReview) {
        public Prepared {privateFiles=Map.copyOf(privateFiles);pathOverrides=Map.copyOf(pathOverrides);sourceRoutes=Map.copyOf(sourceRoutes);profilesRequiringRouteReview=List.copyOf(profilesRequiringRouteReview);}
        @Override public String toString(){return "BootstrapTargetMaterials[redacted]";}
        @com.fasterxml.jackson.annotation.JsonValue public Map<String,Object> review(){return Map.of("status","MATERIALS_RELOCATED_ENDPOINTS_UNRESOLVED",
                "privateFileCount",privateFiles.size(),"pathSettingCount",pathOverrides.size(),"sourceRouteCount",sourceRoutes.size(),
                "profilesRequiringRouteReview",profilesRequiringRouteReview.size(),"targetRoutesStillRequireGeneration",true);}
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_PRIVATE_MATERIAL_UNAVAILABLE");}}
    private static Map<String,String> strings(Object value){
        require(value instanceof Map<?,?>,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");var result=new TreeMap<String,String>();
        ((Map<?,?>)value).forEach((key,item)->{require(key instanceof String&&item instanceof String,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");result.put(key.toString(),item.toString());});return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(byte[] raw){
        try{return JSON.readValue(raw,LinkedHashMap.class);}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");}
    }
    private static byte[] decoded(Map<String,String> files,String name){
        require(files.containsKey(name),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_REQUIRED");
        try{return Base64.getDecoder().decode(files.get(name));}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");}
    }
    public Prepared prepare(ControlBootstrapAdmission.Frozen frozen){
        try{return prepareBound(frozen);}
        catch(ControlFailure failure){throw failure;}
        catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");}
    }
    private Prepared prepareBound(ControlBootstrapAdmission.Frozen frozen){
        admission.requireBound(frozen);var payload=admission.privateSourcePayload(frozen);
        require(payload.keySet().equals(Set.of("properties","privateFiles")),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
        var properties=strings(payload.get("properties"));var files=strings(payload.get("privateFiles"));
        require(frozen.sourceEvidence().get("fileHashes") instanceof Map<?,?>,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
        var hashes=(Map<?,?>)frozen.sourceEvidence().get("fileHashes");
        files.forEach((name,value)->require(sha(decoded(files,name)).equals(hashes.get(name)),"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED"));
        var replacements=index(properties,files);var output=new TreeMap<>(files);output.remove(ControllerSourceMaterials.INDEX);
        var overrides=new TreeMap<String,String>();
        for(String setting:ControllerSourceMaterials.PATH_SETTINGS)if(properties.containsKey(setting))
            overrides.put(setting,CONFIG+lookup(replacements,properties.get(setting)));
        // No caller can accidentally install SOURCE's physical-route table as
        // a working target table. The full factory has to generate and prove it.
        var sourceRoutes=new LinkedHashMap<String,Object>();String routeSetting="app.control.servers.ssh-routes-file";
        if(properties.containsKey(routeSetting)){
            String name=lookup(replacements,properties.get(routeSetting));var inventory=object(decoded(files,name));
            require(inventory.keySet().equals(Set.of("version","routes"))&&Integer.valueOf(1).equals(inventory.get("version"))&&inventory.get("routes") instanceof Map<?,?>,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
            ((Map<?,?>)inventory.get("routes")).forEach((key,value)->sourceRoutes.put(key.toString(),value));output.remove(name);overrides.remove(routeSetting);
        }
        var profiles=object(decoded(files,"cluster-profiles.json"));require(!profiles.isEmpty(),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
        for(Object raw:profiles.values()){
            require(raw instanceof Map<?,?>,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
            @SuppressWarnings("unchecked") var profile=(Map<String,Object>)raw;require(profile.get("tokenFile") instanceof String,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
            profile.put("tokenFile",CONFIG+lookup(replacements,profile.get("tokenFile").toString()));
            if(profile.get("caFile")!=null){require(profile.get("caFile") instanceof String,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");profile.put("caFile",CONFIG+lookup(replacements,profile.get("caFile").toString()));}
        }output.put("cluster-profiles.json",Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(profiles)));
        String credentials=properties.get("app.control.servers.credentials-file");
        if(credentials!=null){
            String name=lookup(replacements,credentials);var inventory=object(decoded(files,name));
            require(inventory.keySet().equals(Set.of("version","credentials"))&&Integer.valueOf(1).equals(inventory.get("version"))&&inventory.get("credentials") instanceof Map<?,?>,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
            for(Object raw:((Map<?,?>)inventory.get("credentials")).values())rewrite(raw,Set.of("file"),replacements,false);
            output.put(name,Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(inventory)));
        }
        String members=properties.get("app.control.servers.member-configurations-file");
        if(members!=null){
            String name=lookup(replacements,members);var inventory=object(decoded(files,name));
            require(inventory.keySet().equals(Set.of("version","clusterId","servers"))&&Set.of(1,2).contains(inventory.get("version"))&&inventory.get("servers") instanceof Map<?,?>,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
            for(Object raw:((Map<?,?>)inventory.get("servers")).values())rewrite(raw,Set.of("authPasswordFile","authCaFile"),replacements,true);
            output.put(name,Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(inventory)));
        }
        String jdbc=properties.get("spring.datasource.url");
        if(jdbc!=null&&!ControllerSourceMaterials.jdbcFiles(jdbc).isEmpty())overrides.put("spring.datasource.url",jdbc(jdbc,replacements));
        // Files have no user-controlled target path. Keep the native byte/count
        // envelope explicit; do not silently omit larger inventories.
        long bytes=0;require(output.size()>0&&output.size()<=128,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_TOO_LARGE");
        for(var entry:output.entrySet()){
            require(entry.getKey().matches("[a-z0-9][a-z0-9_.-]{0,95}")&&!entry.getKey().equals("application.properties"),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
            int n=decoded(output,entry.getKey()).length;require(n>0&&n<=256*1024,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_TOO_LARGE");bytes+=n;
        }require(bytes<=512*1024,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_TOO_LARGE");
        return new Prepared(output,overrides,sourceRoutes,new ArrayList<>(profiles.keySet()));
    }
    private static Map<String,String> index(Map<String,String> properties,Map<String,String> files){
        if(files.containsKey(ControllerSourceMaterials.INDEX)){
            var root=object(decoded(files,ControllerSourceMaterials.INDEX));require(root.keySet().equals(Set.of("version","files"))&&Integer.valueOf(1).equals(root.get("version")),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
            var index=strings(root.get("files"));var names=new HashSet<String>();
            for(var entry:index.entrySet()){
                Path source=Path.of(entry.getKey());require(source.isAbsolute()&&source.equals(source.normalize())&&files.containsKey(entry.getValue())&&names.add(entry.getValue()),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
            }
            var expected=new HashSet<>(files.keySet());expected.remove(ControllerSourceMaterials.INDEX);require(names.equals(expected),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");return index;
        }
        // Old captures contain only flat config tokens/CAs. There is no fallback
        // which reads a SOURCE path from this manager's own filesystem.
        String profile=properties.get("app.cluster.profiles-file");require(profile!=null,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_REQUIRED");
        Path config=Path.of(profile).getParent();require(config!=null&&config.isAbsolute(),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");
        var index=new TreeMap<String,String>();files.keySet().forEach(name->index.put(config.resolve(name).toString(),name));return index;
    }
    private static String lookup(Map<String,String> index,String source){
        String name=index.get(source);require(name!=null,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_REQUIRED");return name;
    }
    @SuppressWarnings("unchecked") private static void rewrite(Object raw,Set<String> keys,Map<String,String> replacements,boolean empty){
        require(raw instanceof Map<?,?>,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");var value=(Map<String,Object>)raw;
        for(String key:keys){require(value.get(key) instanceof String,"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");String source=value.get(key).toString();
            if(empty&&source.isEmpty()){require(key.equals("authCaFile"),"BOOTSTRAP_SOURCE_PRIVATE_MATERIAL_INVALID");continue;}
            value.put(key,CONFIG+lookup(replacements,source));
        }
    }
    private static String jdbc(String source,Map<String,String> replacements){
        int query=source.indexOf('?');String[] parts=source.substring(query+1).split("&",-1);var rewritten=new ArrayList<String>();
        for(String part:parts){
            String[] pair=part.split("=",2);String name=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            if(Set.of("sslrootcert","sslcert","sslkey").contains(name)){
                String old=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);rewritten.add(pair[0]+"="+URLEncoder.encode(CONFIG+lookup(replacements,old),StandardCharsets.UTF_8).replace("+","%20"));
            }else rewritten.add(part);
        }return source.substring(0,query+1)+String.join("&",rewritten);
    }
}
