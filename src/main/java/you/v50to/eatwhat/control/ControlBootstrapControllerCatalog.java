package you.v50to.eatwhat.control;

import java.util.*;

/** One frozen catalog shared by the target bundle, transfer driver and receipt
 * checks. Paths are derived on TARGET; local SOURCE paths never enter its map.
 * Version 2 is explicit. Existing sealed headers keep their two legacy blobs. */
final class ControlBootstrapControllerCatalog {
    private static final String ACCEPTANCE="/opt/eatwhat-control/acceptance/";
    private ControlBootstrapControllerCatalog(){}
    static boolean multiple(ControlBootstrapArtifacts.Frozen f){return f.releases().size()!=1||f.releases().get(f.releaseId()).rollbackCompatible();}
    static String releaseKind(ControlBootstrapArtifacts.Frozen f,ControlBootstrapArtifacts.Release release){
        return release.artifact().sha256().equals(f.business().sha256())?"BUSINESS":"RELEASE_"+release.artifact().sha256();
    }
    static Map<String,Object> metadata(ControlBootstrapArtifacts.Frozen f){
        var selected=f.releases().get(f.releaseId());
        if(selected==null||!selected.artifact().equals(f.business())||!selected.acceptanceEvidenceId().equals(f.businessAcceptanceEvidenceId()))
            throw new ControlFailure(409,"BOOTSTRAP_CONTROLLER_CATALOG_CHANGED");
        var result=new LinkedHashMap<String,Object>();result.put("version",multiple(f)?3:2);result.put("releaseId",f.releaseId());
        result.put("controllerAcceptanceEvidenceId",f.acceptanceEvidenceId());result.put("businessAcceptanceEvidenceId",f.businessAcceptanceEvidenceId());
        result.put("businessSha256",f.business().sha256());result.put("businessSize",f.business().size());
        result.put("manifestSha256",f.manifest().sha256());result.put("manifestSize",f.manifest().size());result.put("images",f.images());
        result.put("imageArchive",f.imageArchive()==null?null:Map.of("sha256",f.imageArchive().sha256(),"size",f.imageArchive().size()));
        if(multiple(f)){
            var releases=new TreeMap<String,Object>();f.releases().forEach((id,release)->releases.put(id,Map.of("sha256",release.artifact().sha256(),"size",release.artifact().size(),
                    "acceptanceEvidenceId",release.acceptanceEvidenceId(),"rollbackCompatible",release.rollbackCompatible())));result.put("releases",releases);
        }return result;
    }
    static LinkedHashMap<String,ControlBootstrapArtifacts.File> files(ControlBootstrapArtifacts.Frozen f,Map<String,Object> header){
        var result=new LinkedHashMap<String,ControlBootstrapArtifacts.File>();result.put("JAR",f.controller());result.put("UI",f.ui());
        if(!(header.get("bundle") instanceof Map<?,?> bundle)||!bundle.containsKey("catalog"))return result;
        if(!ControlBootstrapNativeJson.encode(metadata(f)).equals(ControlBootstrapNativeJson.encode(bundle.get("catalog"))))
            throw new ControlFailure(409,"BOOTSTRAP_CONTROLLER_CATALOG_CHANGED");
        result.put("BUSINESS",f.business());result.put("UI_MANIFEST",f.manifest());if(f.imageArchive()!=null)result.put("IMAGES",f.imageArchive());
        new TreeMap<>(f.releases()).values().forEach(release->{
            String kind=releaseKind(f,release);var prior=result.putIfAbsent(kind,release.artifact());
            if(prior!=null&&prior.size()!=release.artifact().size())throw new ControlFailure(409,"BOOTSTRAP_CONTROLLER_CATALOG_CHANGED");
        });return result;
    }
    static boolean streaming(Map<String,Object> header){return header.get("bundle") instanceof Map<?,?> bundle&&bundle.containsKey("catalog");}
    static int maximumChunk(Map<String,Object> header){return streaming(header)?16*1024*1024:512*1024;}
    static Map<String,String> properties(ControlBootstrapArtifacts.Frozen f){
        var result=new LinkedHashMap<String,String>();String prefix="app.control.controller-bootstrap.";
        result.put(prefix+"enabled","true");result.put(prefix+"controller-artifact",ACCEPTANCE+f.controller().sha256()+".jar");
        result.put(prefix+"controller-sha256",f.controller().sha256());result.put(prefix+"ui-archive",ACCEPTANCE+f.ui().sha256()+".tar.gz");
        result.put(prefix+"ui-sha256",f.ui().sha256());result.put(prefix+"ui-manifest",ACCEPTANCE+f.manifest().sha256()+".json");result.put(prefix+"acceptance-evidence-id",f.acceptanceEvidenceId());
        new TreeMap<>(f.releases()).forEach((id,entry)->{
            String release="app.control.releases["+id+"].";result.put(release+"artifact",ACCEPTANCE+entry.artifact().sha256()+".business.jar");
            result.put(release+"sha256",entry.artifact().sha256());result.put(release+"acceptance-evidence-id",entry.acceptanceEvidenceId());
            result.put(release+"rollback-compatible",Boolean.toString(entry.rollbackCompatible()));
        });
        result.put("app.control.deployment.controller-artifact",ACCEPTANCE+f.controller().sha256()+".jar");result.put("app.control.deployment.enabled","true");
        result.put("app.control.deployment.target-source","REGISTERED_SERVERS");result.put("app.control.servers.installation-enabled","true");result.put("app.control.servers.cluster-enrollment-enabled","true");
        f.images().forEach((name,value)->result.put("app.control.servers."+name+"-image",value));
        if(f.imageArchive()!=null){result.put("app.control.servers.image-archive",ACCEPTANCE+f.imageArchive().sha256()+".images.tar");result.put("app.control.servers.image-archive-sha256",f.imageArchive().sha256());}
        return result;
    }
}
