package you.v50to.eatwhat.support;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Synthetic non-executable JAR entries and tiny UI bytes for policy/guard tests.
 * This fixture never proves Java startup, frontend behavior or real deployment. */
public final class BootstrapArtifactFixture {
    private BootstrapArtifactFixture(){}
    public static void configure(ControlProperties settings,Path directory)throws Exception{
        Path root=directory.toRealPath();Path jar=root.resolve("controller.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(jar))){
            for(String marker:List.of("eatwhat-controller-bootstrap-controller-v1","eatwhat-manual-control-handoff-v1","eatwhat-controller-existing-authority-schema-v1","eatwhat-controller-source-snapshot-v1","eatwhat-controller-catalog-v2","eatwhat-controller-catalog-v3","eatwhat-controller-target-factory-v1","eatwhat-cluster-profile-ca-v1","eatwhat-controller-profile-receiver-v1","eatwhat-controller-profile-services-successor-v1","eatwhat-controller-bootstrap-membership-v1","eatwhat-controller-bootstrap-membership-v2","eatwhat-controller-bootstrap-target-storage-v1","eatwhat-controller-bootstrap-managed-workflow-v1")){
                zip.putNextEntry(new ZipEntry("META-INF/"+marker));zip.write((marker+"\n").getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("META-INF/eatwhat-controller-bootstrap-verify-v1"));zip.write("eatwhat-controller-bootstrap-verify-v1\n".getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
            for(String name:List.of("ControlBootstrapVerify","ControlBootstrapMemberRuntime","ControlBootstrapStorage","ControlBootstrapPlans","ControlBootstrapWorkflow","ControlBootstrapWorker","ControlBootstrapPlanController")){
                zip.putNextEntry(new ZipEntry("BOOT-INF/classes/you/v50to/eatwhat/control/"+name+".class"));zip.write("synthetic fixture, not executable".getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("BOOT-INF/classes/you/v50to/eatwhat/config/NodeHttpClients$ScopedTrust.class"));zip.write("synthetic fixture, not executable".getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("BOOT-INF/classes/you/v50to/eatwhat/cluster/ClusterMembershipCoordinator$Boundary.class"));zip.write("synthetic fixture, not executable".getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
            for(String name:List.of("ControlUiController","ControlBootstrapControllerCatalog","ControlBootstrapControllerFactory","ControlBootstrapProfileNodeController","ControlBootstrapProfileReceiver","ControlBootstrapProfileState","ControlBootstrapProfileServices","ControlBootstrapMembership","ControlBootstrapMembershipNodeController","bootstrap/ControllerRuntimeProbe","bootstrap/ControllerExistingAuthoritySchema","bootstrap/ControllerSourceSnapshot")){
                zip.putNextEntry(new ZipEntry("BOOT-INF/classes/you/v50to/eatwhat/control/"+name+".class"));zip.write("synthetic fixture, not executable".getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
            }
        }
        Files.setPosixFilePermissions(jar,PosixFilePermissions.fromString("rw-------"));
        Path contents=Files.createDirectory(root.resolve("ui-content"));var files=new TreeMap<String,String>();
        for(String name:List.of("index.html","api.js","vendor/opencc/t2cn.js","vendor/opencc/LICENSE","vendor/opencc/THIRD_PARTY_LICENSES.md")){
            Path path=contents.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,"synthetic policy fixture "+name+"\n");files.put(name,DeploymentExecutor.artifactSha(path));
        }
        Path ui=root.resolve("ui.tar.gz");
        String script="import pathlib,sys,tarfile\nroot=pathlib.Path(sys.argv[1])\nwith tarfile.open(sys.argv[2],'w:gz') as archive:\n for p in sorted(root.rglob('*')):\n  if p.is_file():archive.add(p,arcname=p.relative_to(root).as_posix(),recursive=False)\n";
        var process=new ProcessBuilder("/usr/bin/python3","-I","-c",script,contents.toString(),ui.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Synthetic UI archive fixture unavailable");
        Path manifest=root.resolve("ui.json");Files.writeString(manifest,JsonMapper.builder().build().writeValueAsString(Map.of("sha256",DeploymentExecutor.artifactSha(ui),"files",files)));
        Path business=root.resolve("business.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(business))){zip.putNextEntry(new ZipEntry("BOOT-INF/classes/you/v50to/eatwhat/EatwhatApplication.class"));
            zip.write("synthetic business class, not executable".getBytes(StandardCharsets.US_ASCII));zip.closeEntry();}
        for(Path file:List.of(ui,manifest,business))Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        var c=settings.getControllerBootstrap();c.setControllerArtifact(jar);c.setControllerSha256(DeploymentExecutor.artifactSha(jar));
        c.setUiArchive(ui);c.setUiSha256(DeploymentExecutor.artifactSha(ui));c.setUiManifest(manifest);c.setAcceptanceEvidenceId("synthetic-controller-policy-proof");
        var r=new ControlProperties.Release();r.setArtifact(business);r.setSha256(DeploymentExecutor.artifactSha(business));r.setAcceptanceEvidenceId("synthetic-business-proof");settings.getReleases().put("fixture-business",r);
    }
}
