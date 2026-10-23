package you.v50to.eatwhat.cluster;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.ControlAudit;
import you.v50to.eatwhat.control.ControlService;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Hot reload of one operator-owned private file. HTTP accepts profile IDs, never endpoints or file paths. */
public final class ClusterProfiles {
    public record Entry(String id,ClusterMember member,java.net.URI origin,Path tokenFile,Map<String,Integer> channels,String apiPrefix,you.v50to.eatwhat.config.NodeHttpClients.ScopedTrust trust){
        public Entry{channels=Map.copyOf(channels);origin=ControlService.origin(origin.toString());apiPrefix=ControlService.apiPrefix(apiPrefix);if(trust!=null&&!origin.getScheme().equals("https"))throw new ClusterFailure(503,"PROFILE_CA_REQUIRES_HTTPS");}
        public Entry(String id,ClusterMember member,java.net.URI origin,Path tokenFile,Map<String,Integer> channels,String apiPrefix){this(id,member,origin,tokenFile,channels,apiPrefix,null);}
        public Entry(String id,ClusterMember member,java.net.URI origin,Path tokenFile,Map<String,Integer> channels){this(id,member,origin,tokenFile,channels,"");}
        public Entry(String id,ClusterMember member,java.net.URI origin,Path tokenFile){this(id,member,origin,tokenFile,Map.of());}
        public java.net.URI baseUri(){return ControlService.nodeBase(origin.toString(),apiPrefix);}
        public java.net.http.HttpClient client(java.net.http.HttpClient fallback){return trust==null?fallback:trust.client();}
        @Override public String toString(){return "ClusterProfileEntry["+id+"]";}
    }
    private final ClusterProperties properties;
    private volatile Map<String,Entry> entries=Map.of();
    private Map<String,String> loadedManifest;
    private record Loaded(Map<String,Entry> entries,Map<String,String> manifest){}
    public ClusterProfiles(ClusterProperties properties){this.properties=properties;reload(List.of());}
    public synchronized List<Map<String,Object>> reload(List<ClusterMember> retained){
        var loaded=load(retained);entries=loaded.entries();loadedManifest=loaded.manifest();return list();
    }
    private Map<String,String> publicationManifest(){
        try{return properties.getProfilesFile()==null?null:ClusterProfilePublication.manifest(properties.getProfilesFile());}
        catch(Exception unsupported){return null;} // Legacy inline/non-flat catalogs remain valid; they cannot use additive publication.
    }
    private Loaded load(List<ClusterMember> retained){
        var before=publicationManifest();
        Map<String,ClusterProperties.Profile> source=properties.getProfiles();
        if(properties.getProfilesFile()!=null){
            Path file=properties.getProfilesFile();try{
                if(!file.isAbsolute()||Files.isSymbolicLink(file)||Files.size(file)>256*1024||!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")))throw new Exception();
                var json=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
                var tree=json.readTree(Files.readString(file));if(!tree.isObject()||tree.size()>1024)throw new Exception();
                var loaded=new LinkedHashMap<String,ClusterProperties.Profile>();for(String key:tree.propertyNames())loaded.put(key,json.treeToValue(tree.get(key),ClusterProperties.Profile.class));source=loaded;
            }catch(Exception failure){throw new ClusterFailure(503,"PRIVATE_CLUSTER_PROFILES_REQUIRED");}
        }
        var next=new LinkedHashMap<String,Entry>();var identities=new HashSet<String>();var instances=new HashSet<UUID>();var origins=new HashSet<java.net.URI>();var slots=new HashSet<Integer>();
        source.forEach((id,profile)->{
            ClusterStore.name(id);var member=profile.member();var origin=ControlService.origin(profile.getOrigin());ControlAudit.token(profile.getTokenFile());
            if(!identities.add(member.siteId())||!instances.add(member.instanceId())||!origins.add(origin)||!slots.add(member.allocationSlot()))throw new ClusterFailure(409,"DUPLICATE_CLUSTER_PROFILE");
            if(profile.getChannels()==null||profile.getChannels().size()>32)throw new ClusterFailure(503,"INVALID_CHANNEL_CATALOG");profile.getChannels().forEach((channel,max)->{ClusterStore.name(channel);if(max==null||max<1||max>300)throw new ClusterFailure(503,"INVALID_CHANNEL_CATALOG");});
            next.put(id,new Entry(id,member,origin,profile.getTokenFile(),profile.getChannels(),profile.getApiPrefix(),
                    profile.getCaFile()==null?null:you.v50to.eatwhat.config.NodeHttpClients.scoped(profile.getCaFile())));
        });
        for(var member:retained){var replacement=next.values().stream().filter(e->e.member().siteId().equals(member.siteId())).findFirst().orElseThrow(()->new ClusterFailure(409,"RETAINED_PROFILE_REQUIRED"));if(!replacement.member().equals(member))throw new ClusterFailure(409,"RETAINED_PROFILE_IDENTITY_CHANGED");}
        var after=publicationManifest();if(!Objects.equals(before,after))throw new ClusterFailure(409,"PROFILE_PUBLICATION_FILES_CHANGED");
        return new Loaded(Collections.unmodifiableMap(next),after);
    }
    /** Private publication proof, never an endpoint/token/path inventory. */
    public synchronized String publicationBinding(){if(loadedManifest==null)throw new ClusterFailure(409,"PROFILE_PUBLICATION_BASELINE_REQUIRED");return ClusterProfilePublication.digest(loadedManifest);}
    /** Compare the actual loaded cache and exact post-publication files before
     * switching clients. Repeating an acknowledged/lost reload only observes.
     * The caller supplies real current authority/administrator authorization. */
    public synchronized Map<String,Object> reloadPublication(ClusterProfilePublication.Plan plan,List<ClusterMember> retained){
        ClusterProfilePublication.validate(plan);
        if(properties.getProfilesFile()==null||!properties.getProfilesFile().toString().equals(plan.profilesFile())||loadedManifest==null)
            throw new ClusterFailure(409,"PROFILE_PUBLICATION_BASELINE_REQUIRED");
        try{
            if(!ClusterProfilePublication.manifest(properties.getProfilesFile()).equals(plan.afterManifest()))throw new ClusterFailure(409,"PROFILE_PUBLICATION_FILES_CHANGED");
            if(!loadedManifest.equals(plan.afterManifest())){
                if(!loadedManifest.equals(plan.beforeManifest()))throw new ClusterFailure(409,"PROFILE_PUBLICATION_CACHE_CHANGED");
                var next=load(retained);if(!plan.afterManifest().equals(next.manifest()))throw new ClusterFailure(409,"PROFILE_PUBLICATION_FILES_CHANGED");
                entries=next.entries();loadedManifest=next.manifest();
            }else for(var member:retained)if(entries.values().stream().noneMatch(e->e.member().equals(member)))throw new ClusterFailure(409,"RETAINED_PROFILE_REQUIRED");
            return Map.of("protocol","eatwhat-cluster-profile-publication-v1","bootstrapId",plan.bootstrapId().toString(),"serverId",plan.serverId().toString(),
                    "binding",plan.binding(),"state","RUNTIME_RELOADED","beforeBinding",plan.beforeBinding(),"afterBinding",plan.afterBinding(),"joinAuthorized",false);
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new ClusterFailure(503,"PROFILE_PUBLICATION_RELOAD_UNAVAILABLE");}
    }
    /** Files and live clients change within the same caller-provided authority
     * fence. This is an internal primitive; no new authenticated route is added.
     * A completed lost acknowledgement is read-only. A retained file result may
     * finish its pending cache reload only after a fresh real fence. */
    public Map<String,Object> publishAndReload(ClusterProfilePublication.Plan plan,List<ClusterMember> retained,java.util.function.Consumer<Runnable> fencedEffect){
        ClusterProfilePublication.validate(plan);
        synchronized(this){
            if(properties.getProfilesFile()==null||!properties.getProfilesFile().toString().equals(plan.profilesFile())||loadedManifest==null)
                throw new ClusterFailure(409,"PROFILE_PUBLICATION_BASELINE_REQUIRED");
            if(loadedManifest.equals(plan.afterManifest())){
                if(!"FILES_PUBLISHED".equals(ClusterProfilePublication.observe(plan).get("state")))throw new ClusterFailure(409,"PROFILE_PUBLICATION_FILES_CHANGED");
                return reloadPublication(plan,retained);
            }
            if(!loadedManifest.equals(plan.beforeManifest()))throw new ClusterFailure(409,"PROFILE_PUBLICATION_CACHE_CHANGED");
        }
        return ClusterProfilePublication.fenced(fencedEffect,()->{synchronized(this){
            if(!loadedManifest.equals(plan.beforeManifest())&&!loadedManifest.equals(plan.afterManifest()))throw new ClusterFailure(409,"PROFILE_PUBLICATION_CACHE_CHANGED");
            var files=ClusterProfilePublication.publish(plan,Runnable::run);
            if(!"FILES_PUBLISHED".equals(files.get("state")))throw new ClusterFailure(409,"PROFILE_PUBLICATION_OUTCOME_UNKNOWN");
            return reloadPublication(plan,retained);
        }});
    }
    public Entry get(String id){var entry=entries.get(id);if(entry==null)throw new ClusterFailure(404,"CLUSTER_PROFILE_NOT_FOUND");return entry;}
    public List<Map<String,Object>> list(){return entries.values().stream().map(e->Map.<String,Object>of("id",e.id(),"siteId",e.member().siteId(),"instanceId",e.member().instanceId(),"mode",e.member().mode(),"allocationSlot",e.member().allocationSlot())).toList();}
}
