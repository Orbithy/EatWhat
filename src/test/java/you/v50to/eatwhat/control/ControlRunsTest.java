package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ControlRunsTest {
    @TempDir Path root;
    @Test void noRunnerConfiguredMeansDisabledNotSyntheticSuccess(){var p=new ControlProperties();p.setEnvironmentKind("isolated-acceptance");var audit=new ControlAudit(root.resolve("state"));try(var runs=new ControlRuns(p,audit,new ControlService(p,audit))){assertTrue(runs.suites().stream().allMatch(s->s.get("enabled").equals(false)));assertEquals("TEST_RUNNER_NOT_CONFIGURED",assertThrows(ControlFailure.class,()->runs.start("1","business")).code);}}
    @Test void realFixedCommandIsSerializedPersistedAndNeverClaimsRemoteAcceptance()throws Exception {
        var repo=root.resolve("repo");Files.createDirectory(repo);var mvn=repo.resolve("mvnw");Files.writeString(mvn,"#!/bin/sh\nsleep 1\nexit 0\n");Files.setPosixFilePermissions(mvn,PosixFilePermissions.fromString("rwx------"));
        var p=new ControlProperties();p.setEnvironmentKind("isolated-acceptance");p.setRepository(repo);p.setJavaHome(Path.of(System.getProperty("java.home")));var audit=new ControlAudit(root.resolve("state"));var state=new ControlService(p,audit);
        UUID id;
        try(var runs=new ControlRuns(p,audit,state)) {
            assertTrue(runs.command("full-verify").contains("clean"));assertFalse(runs.command("business").contains("deploy"));
            id=UUID.fromString((String)runs.start("1","business").get("id"));assertThrows(ControlFailure.class,()->runs.start("1","coordinated-gc"));
            long deadline=System.currentTimeMillis()+5000;while(!runs.get(id).get("state").equals("COMMAND_SUCCEEDED")&&System.currentTimeMillis()<deadline)Thread.sleep(30);
            assertEquals("COMMAND_SUCCEEDED",runs.get(id).get("state"));assertEquals("NOT_RUN",runs.get(id).get("remoteAcceptance"));assertEquals("REVIEW_REQUIRED",runs.get(id).get("reportStatus"));
        }
        try(var restarted=new ControlRuns(p,audit,state)){assertEquals("COMMAND_SUCCEEDED",restarted.get(id).get("state"));assertEquals(1,restarted.list().size());}
    }
    @Test void restartDoesNotResurrectOldRunningProcess(){var p=new ControlProperties();p.setEnvironmentKind("isolated-acceptance");var audit=new ControlAudit(root.resolve("state"));var state=new ControlService(p,audit);var id=UUID.randomUUID();state.save("run-"+id,java.util.Map.of("id",id.toString(),"state","RUNNING","createdAt","2026-10-16T00:00:00Z"));try(var runs=new ControlRuns(p,audit,state)){assertEquals("INTERRUPTED",runs.get(id).get("state"));}}
    @Test void cancellationKeepsSerialSlotWhileOwnedChildIgnoresTerm()throws Exception {
        var repo=root.resolve("repo");Files.createDirectory(repo);var mvn=repo.resolve("mvnw");Files.writeString(mvn,"#!/bin/sh\nsh -c 'trap \"\" TERM; printf ready; sleep 60' &\nwait\n");Files.setPosixFilePermissions(mvn,PosixFilePermissions.fromString("rwx------"));
        var p=new ControlProperties();p.setEnvironmentKind("isolated-acceptance");p.setRepository(repo);p.setJavaHome(Path.of(System.getProperty("java.home")));var audit=new ControlAudit(root.resolve("state"));var state=new ControlService(p,audit);
        try(var runs=new ControlRuns(p,audit,state)){
            UUID id=UUID.fromString((String)runs.start("1","business").get("id"));Path log=audit.directory().resolve("run-"+id+".log");long end=System.currentTimeMillis()+4000;
            while((!Files.exists(log)||!Files.readString(log).contains("ready"))&&System.currentTimeMillis()<end)Thread.sleep(30);
            assertTrue(Files.readString(log).contains("ready"));runs.cancel("1",id);Thread.sleep(200);
            assertEquals("TEST_RUN_ALREADY_ACTIVE",assertThrows(ControlFailure.class,()->runs.start("1","business")).code);
            assertEquals("CANCELLED",runs.get(id).get("state"));
        }
    }
}
