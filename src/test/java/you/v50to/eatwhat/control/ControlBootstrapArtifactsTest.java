package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.support.BootstrapArtifactFixture;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class ControlBootstrapArtifactsTest {
    @TempDir Path root;
    ControlProperties settings;ControlBootstrapArtifacts artifacts;
    @BeforeEach void setup()throws Exception{
        root=root.toRealPath();settings=new ControlProperties();BootstrapArtifactFixture.configure(settings,root);
        Path state=root.resolve("state");artifacts=new ControlBootstrapArtifacts(settings,new ControlServerStore(new ControlAudit(state)));
    }
    @Test void actualJarAndTarPolicyChecksAreStableAndReviewOmitsPaths(){
        var f=artifacts.freeze("fixture-business");assertEquals(5,f.uiFiles().size());artifacts.requireUnchanged(f);
        assertFalse(f.review().toString().contains(root.toString()));assertEquals("BootstrapArtifacts[redacted]",f.toString());
    }
    @Test void publishedControllerCatalogRequiresReceiverMarkerAndActualCompiledReceiverEntries()throws Exception{
        var settingsArtifact=settings.getControllerBootstrap();Path original=settingsArtifact.getControllerArtifact();int index=0;
        for(String absent:java.util.List.of("META-INF/eatwhat-controller-profile-receiver-v1","BOOT-INF/classes/you/v50to/eatwhat/control/ControlBootstrapProfileReceiver.class",
                "META-INF/eatwhat-controller-profile-services-successor-v1","BOOT-INF/classes/you/v50to/eatwhat/control/ControlBootstrapProfileServices.class")){
            Path candidate=root.resolve("receiver-missing-"+(index++)+".jar");
            try(var source=new java.util.zip.ZipFile(original.toFile());var out=new java.util.zip.ZipOutputStream(Files.newOutputStream(candidate))){
                for(var entry:java.util.Collections.list(source.entries()))if(!entry.getName().equals(absent)){
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));try(var in=source.getInputStream(entry)){in.transferTo(out);}out.closeEntry();
                }
            }
            Files.setPosixFilePermissions(candidate,PosixFilePermissions.fromString("rw-------"));settingsArtifact.setControllerArtifact(candidate);settingsArtifact.setControllerSha256(DeploymentExecutor.artifactSha(candidate));
            assertEquals("BOOTSTRAP_ARTIFACT_PROTOCOL_OR_UI_INVALID",assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business")).code);
        }
    }
    @Test void modifiedBusinessOrControllerBytesInvalidateApproval()throws Exception{
        var f=artifacts.freeze("fixture-business");Files.writeString(settings.getReleases().get("fixture-business").getArtifact(),"changed");
        assertEquals("BOOTSTRAP_ARTIFACT_CHANGED",assertThrows(ControlFailure.class,()->artifacts.requireUnchanged(f)).code);
        Files.writeString(settings.getControllerBootstrap().getControllerArtifact(),"not a JAR");
        assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business"));
    }
    @Test void configurationAndAcceptanceDriftAreNotSilentlyAdopted(){
        var f=artifacts.freeze("fixture-business");settings.getServers().setJreImage("other:21");
        assertEquals("BOOTSTRAP_ARTIFACT_CHANGED",assertThrows(ControlFailure.class,()->artifacts.requireUnchanged(f)).code);
        settings.getControllerBootstrap().setAcceptanceEvidenceId("");assertEquals("BOOTSTRAP_ARTIFACT_ACCEPTANCE_REQUIRED",assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business")).code);
    }
    @Test void catalogRejectsSymlinksAndWritableArtifacts()throws Exception{
        var c=settings.getControllerBootstrap();Path link=root.resolve("untrusted.jar");Files.createSymbolicLink(link,c.getControllerArtifact());c.setControllerArtifact(link);
        assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business"));c.setControllerArtifact(root.resolve("controller.jar"));
        Files.setPosixFilePermissions(c.getControllerArtifact(),PosixFilePermissions.fromString("rw-rw-rw-"));assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business"));
    }
    @Test void corruptUiCannotPassByUpdatingOnlyItsArchiveHash()throws Exception{
        var c=settings.getControllerBootstrap();Files.writeString(c.getUiArchive(),"not a gzip archive");c.setUiSha256(DeploymentExecutor.artifactSha(c.getUiArchive()));
        String prior=Files.readString(c.getUiManifest());var json=tools.jackson.databind.json.JsonMapper.builder().build();var manifest=json.readValue(prior,java.util.Map.class);
        manifest.put("sha256",c.getUiSha256());Files.writeString(c.getUiManifest(),json.writeValueAsString(manifest));
        assertEquals("BOOTSTRAP_ARTIFACT_PROTOCOL_OR_UI_INVALID",assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business")).code);
    }
    @Test void duplicateOrUnsafeManifestEntriesRefuseBeforeAnyExtraction()throws Exception{
        var c=settings.getControllerBootstrap();Files.writeString(c.getUiManifest(),"{\"sha256\":\""+c.getUiSha256()+"\",\"files\":{},\"files\":{}}");
        assertEquals("BOOTSTRAP_UI_MANIFEST_INVALID",assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business")).code);
    }
    void rollback()throws Exception{
        Path jar=root.resolve("rollback.jar");Files.copy(settings.getReleases().get("fixture-business").getArtifact(),jar);
        var release=new ControlProperties.Release();release.setArtifact(jar);release.setSha256(DeploymentExecutor.artifactSha(jar));
        release.setAcceptanceEvidenceId("accepted-rollback");release.setRollbackCompatible(true);settings.getReleases().put("Rollback_A",release);
    }
    @Test void everyConfiguredVersionAndRollbackDecisionIsFrozenAndReviewed()throws Exception{
        rollback();var f=artifacts.freeze("fixture-business");assertEquals(2,f.releases().size());assertTrue(f.releases().get("Rollback_A").rollbackCompatible());
        assertTrue(f.review().toString().contains("Rollback_A"));assertFalse(f.review().toString().contains(root.toString()));artifacts.requireUnchanged(f);
        settings.getReleases().get("Rollback_A").setRollbackCompatible(false);
        assertEquals("BOOTSTRAP_ARTIFACT_CHANGED",assertThrows(ControlFailure.class,()->artifacts.requireUnchanged(f)).code);
    }
    @Test void anUnselectedArtifactCannotDisappearOrChangeWithoutInvalidatingTheApproval()throws Exception{
        rollback();var f=artifacts.freeze("fixture-business");Files.delete(settings.getReleases().get("Rollback_A").getArtifact());
        assertThrows(ControlFailure.class,()->artifacts.requireUnchanged(f));assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business"));
    }
    @Test void allVersionsMustBeBusinessJarsBeforeAnyTargetEffect()throws Exception{
        rollback();Path jar=settings.getReleases().get("Rollback_A").getArtifact();Files.writeString(jar,"not a business jar");
        settings.getReleases().get("Rollback_A").setSha256(DeploymentExecutor.artifactSha(jar));
        assertEquals("BOOTSTRAP_ARTIFACT_PROTOCOL_OR_UI_INVALID",assertThrows(ControlFailure.class,()->artifacts.freeze("fixture-business")).code);
    }
    @Test void sourceCatalogCannotLoseAVersionOrChangeItsAcceptanceWhilePathsCanDiffer()throws Exception{
        rollback();var f=artifacts.freeze("fixture-business");var source=new java.util.TreeMap<String,String>();
        var rollback=f.releases().get("Rollback_A");String prefix="app.control.releases[Rollback_A].";
        source.put(prefix+"artifact","/physical-source/old.jar");source.put(prefix+"sha256",rollback.artifact().sha256());
        source.put(prefix+"acceptance-evidence-id",rollback.acceptanceEvidenceId());source.put(prefix+"rollback-compatible","true");
        ControlBootstrapArtifacts.requireSourceCatalog(f,source);
        source.put(prefix+"acceptance-evidence-id","different-review");
        assertEquals("BOOTSTRAP_SOURCE_RELEASE_CATALOG_CHANGED",assertThrows(ControlFailure.class,()->ControlBootstrapArtifacts.requireSourceCatalog(f,source)).code);
        source.put(prefix+"acceptance-evidence-id",rollback.acceptanceEvidenceId());source.put("app.control.releases.Rollback_A.sha256",rollback.artifact().sha256());
        assertEquals("BOOTSTRAP_SOURCE_RELEASE_CATALOG_INVALID",assertThrows(ControlFailure.class,()->ControlBootstrapArtifacts.requireSourceCatalog(f,source)).code);
        source.remove("app.control.releases.Rollback_A.sha256");source.keySet().removeIf(key->key.endsWith(".artifact"));
        assertThrows(ControlFailure.class,()->ControlBootstrapArtifacts.requireSourceCatalog(f,source));
    }
}
