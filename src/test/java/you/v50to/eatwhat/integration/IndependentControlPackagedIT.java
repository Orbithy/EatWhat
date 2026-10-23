package you.v50to.eatwhat.integration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IndependentControlPackagedIT {
 @TempDir Path directory;
 @Test void actualBootJarDiscoversAndAppliesItsManagementMigration()throws Exception{
  try(var pg=IsolatedContainers.postgres("independent-packaged")){
   pg.start();var site=new you.v50to.eatwhat.config.SiteProperties();site.setMode(you.v50to.eatwhat.config.SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(UUID.randomUUID());
   var db=EventFoundationTestSupport.createDatabase(pg,site);var p=new Properties();p.setProperty("bootstrap.jdbc.url",db.url().split("\\?")[0].replace("localhost","127.0.0.1"));p.setProperty("bootstrap.jdbc.user",IsolatedContainers.USER);p.setProperty("bootstrap.jdbc.password",IsolatedContainers.PASSWORD);
   var plan=directory.resolve("plan.properties");Files.createFile(plan,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try(var output=Files.newOutputStream(plan)){p.store(output,"isolated fixture");}
   var log=directory.resolve("provision.log");var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Dloader.main=you.v50to.eatwhat.control.independent.IndependentControlProvisioning","-cp",System.getProperty("eatwhat.independent.jar"),"org.springframework.boot.loader.launch.PropertiesLauncher","--config",plan.toString(),"--schema-only").redirectErrorStream(true).redirectOutput(log.toFile()).start();
   assertTrue(process.waitFor(40,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));
   assertTrue(db.admin().queryForObject("SELECT to_regclass('eatwhat_controller.installation')IS NOT NULL",Boolean.class));
   assertEquals(1,db.admin().queryForObject("SELECT count(*)FROM eatwhat_controller.independent_schema_history WHERE version='1'AND success",Integer.class));
  }
 }
}
