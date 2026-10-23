package you.v50to.eatwhat.control;

import java.nio.file.*;
import java.util.*;

/** Test entry point loaded by Boot from the packaged JAR. No Spring context or SSH operations. */
public final class PackagedDeploymentRuntimeProbe {
    public static void main(String[] args)throws Exception{
        Path directory=Path.of(args[0]),jar=Path.of(args[1]);String actual=DeploymentExecutor.artifactSha(jar);
        var settings=new ControlProperties();settings.setEnvironmentKind("managed-deployment");
        settings.getDeployment().setEnabled(true);settings.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);
        var release=new ControlProperties.Release();Path candidate=directory.resolve("candidate.jar");Files.writeString(candidate,"synthetic-candidate-bytes");release.setArtifact(candidate);release.setSha256(DeploymentExecutor.artifactSha(candidate));release.setRollbackCompatible(true);release.setAcceptanceEvidenceId("synthetic-runtime-proof-only");release.setCompatibleFromSha256(List.of("a".repeat(64)));settings.getReleases().put("candidate",release);
        var target=new ControlProperties.DeploymentTarget();target.setSiteId("fixture");target.setInstanceId(UUID.randomUUID());target.setCurrentSha256("a".repeat(64));target.setRestoreEvidenceId("synthetic-runtime-proof-only");var targets=List.of(new DeploymentExecutor.Target("fixture","fixture",target.getInstanceId()));
        try(var executor=new DeploymentExecutor(settings,new ControlAudit(directory.resolve("state")))){
            executor.registeredTransport(new DeploymentExecutor.RegisteredTransport(){
                public boolean available(){return true;}
                public ControlProperties.DeploymentTarget resolve(DeploymentExecutor.Target ignored){return target;}
                public Map<String,Object> call(String action,UUID id,ControlProperties.DeploymentTarget t,Path artifact,String sha){throw new AssertionError("Runtime proof must not execute SSH");}
                public void record(UUID id,ControlProperties.DeploymentTarget t,String sha,Map<String,Object> reply){throw new AssertionError("Runtime proof must not change a server");}
            });
            require("VERIFIED_AT_STARTUP".equals(executor.runtimeArtifact().get("status")));
            require(actual.equals(executor.runtimeArtifact().get("sha256")));
            require(executor.blockers("candidate",targets).equals(List.of("CONTROLLER_VERSION_COMPATIBILITY_NOT_REVIEWED")));
            release.setCompatibleFromSha256(List.of("a".repeat(64),actual));require(executor.blockers("candidate",targets).isEmpty());
            settings.getDeployment().setControllerArtifact(candidate);require(executor.blockers("candidate",targets).equals(List.of("CONTROLLER_ARTIFACT_RUNTIME_MISMATCH")));
            settings.getDeployment().setControllerArtifact(jar);require(executor.blockers("candidate",targets).isEmpty());
            // Changing a configured candidate cannot change the identity of the already running code.
            Files.writeString(candidate,"replacement-candidate-bytes");require(actual.equals(executor.runtimeArtifact().get("sha256")));
            System.out.println("{\"status\":\"PASSED\",\"runtimeSha256\":\""+actual+"\",\"scope\":\"BOOT_PACKAGED_CLASSLOADER_AND_COMPATIBILITY_GATES\"}");
        }
    }
    private static void require(boolean condition){if(!condition)throw new AssertionError("Packaged runtime identity proof failed");}
}
