package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine packaged Boot loading; this is not a running management HTTP server or remote release. */
class PackagedDeploymentRuntimeIT {
    @TempDir Path root;
    @Test void actualBootArtifactDeterminesControllerCompatibilityInsteadOfAnOperatorSuppliedFile()throws Exception{
        Path jar=Path.of("target/eatwhat-0.0.1-SNAPSHOT.jar").toAbsolutePath();assertTrue(Files.isRegularFile(jar));
        Path output=root.resolve("probe-output.json"),java=Path.of(System.getProperty("java.home"),"bin/java");
        var process=new ProcessBuilder(List.of(java.toString(),"-Dloader.main=you.v50to.eatwhat.control.PackagedDeploymentRuntimeProbe","-Dloader.path="+Path.of("target/test-classes").toAbsolutePath(),"-cp",jar.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher",root.toString(),jar.toString())).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Packaged runtime probe timed out");assertEquals(0,process.exitValue(),Files.readString(output));String report=Files.readString(output);assertTrue(report.contains("\"status\":\"PASSED\""),report);assertTrue(report.contains(you.v50to.eatwhat.control.DeploymentExecutor.artifactSha(jar)),report);}finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}}
    }
}
