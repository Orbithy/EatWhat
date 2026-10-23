package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlBootstrapControllerCatalogTest {
    ControlBootstrapArtifacts.Frozen artifact(boolean images){return new ControlBootstrapArtifacts.Frozen(
            new ControlBootstrapArtifacts.File("/source/controller.jar","a".repeat(64),101),new ControlBootstrapArtifacts.File("/source/ui.tar.gz","b".repeat(64),102),
            new ControlBootstrapArtifacts.File("/source/ui.json","c".repeat(64),103),Map.of(),"controller-acceptance","Business_A",new ControlBootstrapArtifacts.File("/source/business.jar","d".repeat(64),104),
            "business-acceptance",Map.of("jre","eclipse-temurin:21-jre","postgres","postgis/postgis:16-3.5","redis","redis:7.4-alpine"),
            images?new ControlBootstrapArtifacts.File("/source/images.tar","e".repeat(64),2147483648L):null,"fixture-catalog");}
    ControlBootstrapArtifacts.Frozen multiple(boolean images){
        var f=artifact(images);var releases=new TreeMap<>(f.releases());
        releases.put("Rollback_A",new ControlBootstrapArtifacts.Release(new ControlBootstrapArtifacts.File("/source/old.jar","f".repeat(64),88),"rollback-proof",true));
        // Case-distinct identities can share bytes without clashing in native
        // filenames (which are lowercase). Transfer kinds are content-addressed.
        releases.put("rollback_a",releases.get("Rollback_A"));
        return new ControlBootstrapArtifacts.Frozen(f.controller(),f.ui(),f.manifest(),f.uiFiles(),f.acceptanceEvidenceId(),f.releaseId(),f.business(),
                f.businessAcceptanceEvidenceId(),f.images(),f.imageArchive(),releases,f.binding());
    }
    @Test void legacyCannotSilentlyGainTheNewBlobKinds(){
        assertEquals(Set.of("JAR","UI"),ControlBootstrapControllerCatalog.files(artifact(true),Map.of("bundle",Map.of())).keySet());
        assertEquals(512*1024,ControlBootstrapControllerCatalog.maximumChunk(Map.of("bundle",Map.of())));
    }
    @Test void completeCatalogIsBoundToEveryFrozenMetadataField(){
        var f=artifact(true);var metadata=ControlBootstrapControllerCatalog.metadata(f);var header=Map.<String,Object>of("bundle",Map.of("catalog",metadata));
        assertEquals(Set.of("JAR","UI","BUSINESS","UI_MANIFEST","IMAGES"),ControlBootstrapControllerCatalog.files(f,header).keySet());
        assertEquals(16*1024*1024,ControlBootstrapControllerCatalog.maximumChunk(header));metadata.put("businessSize",105L);
        assertEquals("BOOTSTRAP_CONTROLLER_CATALOG_CHANGED",assertThrows(ControlFailure.class,()->ControlBootstrapControllerCatalog.files(f,header)).code);
    }
    @Test void generatedPropertiesUseTargetPathsAndDoNotClaimRollbackAcceptance(){
        var values=ControlBootstrapControllerCatalog.properties(artifact(true));
        assertFalse(values.toString().contains("/source/"));assertEquals("false",values.get("app.control.releases[Business_A].rollback-compatible"));
        assertEquals("REGISTERED_SERVERS",values.get("app.control.deployment.target-source"));assertEquals("true",values.get("app.control.controller-bootstrap.enabled"));
        assertTrue(values.get("app.control.servers.image-archive").endsWith(".images.tar"));
    }
    @Test void javaAndNativePolicyGenerateIdenticalCatalogAndPaths()throws Exception{
        for(var f:List.of(artifact(false),artifact(true),multiple(false),multiple(true))){
            String source=ControlServers.resource("controller-bootstrap-controller-policy.py");
            String program="import json,sys\nx=json.load(sys.stdin);n={'__name__':'catalog_test'};exec(compile(x['source'],'<policy>','exec'),n);print(json.dumps(n['catalog_properties'](x['bundle'])))";
            var bundle=Map.of("catalog",ControlBootstrapControllerCatalog.metadata(f),"controllerSha256",f.controller().sha256(),"controllerSize",f.controller().size(),"uiSha256",f.ui().sha256(),"uiSize",f.ui().size());
            var json=JsonMapper.builder().build();byte[] input=json.writeValueAsBytes(Map.of("source",source,"bundle",bundle));
            byte[] result=ControlSsh.process(List.of("/usr/bin/python3","-I","-c",program),Map.of(),out->out.write(input),10,16384,true);
            assertEquals(ControlBootstrapControllerCatalog.properties(f),json.readValue(result,Map.class));
        }
    }
    @Test void allVersionsAndRollbackFlagsHaveTargetPathsAndDeduplicatedTransfers(){
        var f=multiple(false);var metadata=ControlBootstrapControllerCatalog.metadata(f);assertEquals(3,metadata.get("version"));
        var files=ControlBootstrapControllerCatalog.files(f,Map.of("bundle",Map.of("catalog",metadata)));
        assertEquals(Set.of("JAR","UI","BUSINESS","UI_MANIFEST","RELEASE_"+"f".repeat(64)),files.keySet());
        var values=ControlBootstrapControllerCatalog.properties(f);
        for(String id:List.of("Rollback_A","rollback_a")){
            assertEquals("true",values.get("app.control.releases["+id+"].rollback-compatible"));
            assertEquals("/opt/eatwhat-control/acceptance/"+"f".repeat(64)+".business.jar",values.get("app.control.releases["+id+"].artifact"));
        }
        assertFalse(values.toString().contains("/source/"));
    }
}
