package you.v50to.eatwhat.integration;

import org.springframework.boot.SpringApplication;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.EatwhatApplication;
import you.v50to.eatwhat.cluster.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Isolated fixture importer, never packaged. Real installed manifest -> normal reservation transaction.
 * Does not fabricate membership/epoch/lease rows or expose any extra HTTP endpoint. */
public final class ControlRecoveryFixtureMain {
    public static void main(String[] args)throws Exception{
        Path config=Path.of(args[0]).toRealPath(),command=Path.of(args[1]).getParent().toRealPath().resolve(Path.of(args[1]).getFileName());
        if(!config.getParent().getFileName().toString().startsWith("eatwhat-member-ssh-compose-")||!command.getParent().equals(config.getParent()))throw new IllegalArgumentException("Fixture path required");
        var context=SpringApplication.run(new Class<?>[]{EatwhatApplication.class,ClusterNodeProcessMain.Boundaries.class},new String[]{"--spring.config.location=classpath:/application-integration.properties,file:"+config});
        var worker=new Thread(()->{
            try{
                long end=System.nanoTime()+java.time.Duration.ofMinutes(3).toNanos();
                while(!Files.exists(command)){if(System.nanoTime()>end)throw new IllegalStateException();Thread.sleep(100);}
                if(Files.isSymbolicLink(command)||!Files.getPosixFilePermissions(command).equals(PosixFilePermissions.fromString("rw-------")))throw new IllegalStateException();
                var value=JsonMapper.builder().build().readTree(Files.readAllBytes(command));
                var profile=context.getBean(ClusterProfiles.class).get(value.path("profileId").asText());
                context.getBean(ClusterControlLease.class).requireHeld();
                context.getBean(ClusterStore.class).reserveInstallation("isolated-fixture-import",UUID.fromString(value.path("jobId").asText()),UUID.fromString(value.path("serverId").asText()),profile.member());
                Path done=command.resolveSibling("reservation-complete.json");Files.createFile(done,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(done,"{\"state\":\"RESERVED_BY_NORMAL_CLUSTER_STORE\"}");
            }catch(Exception failure){try{Path failed=command.resolveSibling("reservation-failed.json");Files.createFile(failed,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(failed,"{\"state\":\"FAILED\",\"type\":\""+failure.getClass().getSimpleName()+"\"}");}catch(Exception ignored){}}
        },"isolated-installed-manifest-import");worker.setDaemon(true);worker.start();
    }
}
