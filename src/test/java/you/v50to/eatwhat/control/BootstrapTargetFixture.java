package you.v50to.eatwhat.control;

import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import you.v50to.eatwhat.support.*;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;

/** Real SOURCE private files, target material rewrite and artifact policy;
 * explicit admission/registry/dependency fixtures, no SSH, TLS or installation. */
final class BootstrapTargetFixture {
    final Path root;final SourceMaterialFixture source;final UUID sourceId=UUID.randomUUID(),targetId=UUID.randomUUID();
    final ControlBootstrapAdmission admission;final ControlBootstrapArtifacts.Frozen artifacts;
    final ControlBootstrapTargetMaterials materials;final ControlBootstrapTargetNetwork network;
    ControlBootstrapAdmission.Frozen frozen;Map<String,Object> sourceServer,targetServer;
    BootstrapTargetFixture(Path directory)throws Exception{
        root=directory.toRealPath();source=new SourceMaterialFixture(Files.createDirectory(root.resolve("source")));
        source.properties.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:5432/eatwhat?sslmode=disable");source.properties.put("spring.datasource.username","fixture_runtime");
        source.properties.put("spring.data.redis.host","127.0.0.1");source.properties.put("spring.data.redis.port","6379");
        Path catalog=Files.createDirectory(root.resolve("catalog"));var settings=new ControlProperties();BootstrapArtifactFixture.configure(settings,catalog);
        artifacts=new ControlBootstrapArtifacts(settings,new ControlServerStore(new ControlAudit(catalog.resolve("state")))).freeze("fixture-business");
        admission=stub(ControlBootstrapAdmission.class);materials=new ControlBootstrapTargetMaterials(admission);network=new ControlBootstrapTargetNetwork(admission,materials);
        sourceServer=Map.of("id",sourceId.toString(),"host","127.0.0.1","port",22,"hostKey",hostKey(),"revision",1);
        targetServer=Map.of("id",targetId.toString(),"host","203.0.113.50","port",22,"hostKey",hostKey(),"revision",1);capture();
    }
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    static String hostKey(){byte[] wire=java.nio.ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(32).put(new byte[32]).array();return "ssh-ed25519 "+Base64.getEncoder().encodeToString(wire);}
    void capture()throws Exception{
        var c=source.capture();frozen=stub(ControlBootstrapAdmission.Frozen.class);
        var expected=new ControllerSourceSnapshot.Expected(source.cluster,"campus",source.instance,"a".repeat(32),source.state,1,"fixture-source",
                c.hashes().get("application.properties"),c.hashes().get("cluster-profiles.json"),ControllerSourceSnapshot.sha(new byte[32]));
        var spec=new ControlBootstrapJournal.Spec(UUID.randomUUID(),sourceId,1,targetId,1,source.cluster,1,artifacts.controller().sha256(),artifacts.releaseId(),artifacts.business().sha256(),2);
        when(frozen.spec()).thenReturn(spec);when(frozen.sourceRequest()).thenReturn(new ControlBootstrapSources.Request(artifacts.controller().sha256(),artifacts.controller().sha256(),"b".repeat(64),expected));
        when(frozen.sourceEvidence()).thenReturn(Map.of("fileHashes",c.hashes()));when(frozen.artifacts()).thenReturn(artifacts);when(frozen.targetRoute()).thenReturn("target-route");
        when(frozen.targetInitial()).thenReturn(new ControlBootstrapPreflights.Captured(targetId,1,"target-route",Map.of("targetServerId",targetId.toString(),"controllerArtifactSha256",artifacts.controller().sha256()),Map.of()));
        when(admission.privateSourcePayload(frozen)).thenReturn(Map.of("properties",c.properties(),"privateFiles",c.privateFiles()));
        when(admission.registeredServers(frozen)).thenReturn(Map.of(sourceId,sourceServer,targetId,targetServer));
    }
    @SuppressWarnings("unchecked") void extraProfiles()throws Exception{
        Path file=source.config.resolve("cluster-profiles.json");var profiles=source.json.readValue(Files.readAllBytes(file),Map.class);
        SourceMaterialFixture.write(source.config.resolve("external.token"),"e".repeat(43).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        profiles.put("external",Map.of("siteId","external","instanceId",UUID.randomUUID().toString(),"mode","EXTERNAL","allocationSlot",1,
                "origin","http://127.0.0.1:28091","apiPrefix","/api","tokenFile",source.config.resolve("external.token").toString()));
        profiles.put("public",Map.of("siteId","public","instanceId",UUID.randomUUID().toString(),"mode","EXTERNAL","allocationSlot",3,
                "origin","https://public.example.invalid","tokenFile",source.config.resolve("external.token").toString()));
        SourceMaterialFixture.write(file,source.json.writeValueAsBytes(profiles));capture();
    }
}
