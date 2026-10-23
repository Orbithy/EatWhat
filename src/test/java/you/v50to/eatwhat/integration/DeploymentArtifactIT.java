package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The deployable JAR must not silently embed the developer's ignored configuration. */
class DeploymentArtifactIT {
    @TempDir
    Path workingDirectory;

    @Test
    void packagedApplicationContainsStartupValidationAndMigrationsButNoLocalSecretsConfig() throws Exception {
        String configuredJar = System.getProperty("eatwhat.packaged-jar");
        assertNotNull(configuredJar, "Run this acceptance check with mvn -Pintegration verify");
        Path jar = Path.of(configuredJar);
        assertTrue(Files.isRegularFile(jar), "Packaging must finish before integration verification");
        try (var archive = new ZipFile(jar.toFile())) {
            assertNull(archive.getEntry("BOOT-INF/classes/application.yml"));
            assertNull(archive.getEntry("BOOT-INF/classes/application.example.yml"));
            assertNull(archive.getEntry("BOOT-INF/classes/.env"));
            assertNotNull(archive.getEntry("BOOT-INF/classes/application.properties"));
            assertNotNull(archive.getEntry("BOOT-INF/classes/pc.json"));
            assertNotNull(archive.getEntry("BOOT-INF/classes/reference/pc-v1.manifest.json"));
            assertNotNull(archive.getEntry("BOOT-INF/classes/you/v50to/eatwhat/config/identity/GlobalIdentityConfiguration.class"));
            // Spring Boot keeps META-INF at the outer JAR root when repackaging.
            var factories = archive.getEntry("META-INF/spring.factories");
            assertNotNull(factories);
            try (var input = archive.getInputStream(factories)) {
                assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("you.v50to.eatwhat.config.SiteConfigurationValidator"));
            }
            assertEquals(54, archive.stream().filter(entry -> entry.getName().startsWith("BOOT-INF/classes/db/migration/")
                    && entry.getName().endsWith(".sql")).count());
            for(String migration : java.util.List.of("V51__cluster_control_lease.sql", "V52__control_handoff.sql",
                    "V53__control_account_credentials.sql", "V54__cluster_deployment_fence.sql", "V55__retained_installation_allocations.sql")) {
                assertNotNull(archive.getEntry("BOOT-INF/classes/db/migration/"+migration), migration);
            }
            var enrollment=archive.getEntry("META-INF/eatwhat-member-configuration-v1");assertNotNull(enrollment);
            try(var input=archive.getInputStream(enrollment)){assertEquals("eatwhat-member-configuration-v1\n",new String(input.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII));}
            var manual=archive.getEntry("META-INF/eatwhat-manual-control-handoff-v1");assertNotNull(manual);
            try(var input=archive.getInputStream(manual)){assertEquals("eatwhat-manual-control-handoff-v1\n",new String(input.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII));}
            assertTrue(archive.stream().noneMatch(entry -> entry.getName().contains("DualModeTestApplication")),
                    "Test-only external-service replacements must never enter the runtime JAR");
        }
        // Verify the executable JAR's class loader actually discovers the early validator.
        Path log = workingDirectory.resolve("invalid-site.log");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-jar", jar.toAbsolutePath().toString(),
                "--spring.config.location=optional:file:" + workingDirectory.resolve("absent.properties"),
                "--app.site.mode=CAMPUS", "--app.storage.s3.provider=R2")
                .directory(workingDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "C.UTF-8");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "An invalid packaged deployment must fail promptly");
            assertNotEquals(0, process.exitValue());
            String output = Files.readString(log);
            assertTrue(output.contains("Invalid deployment configuration: app.site.mode / app.storage.s3.provider"),
                    "The executable JAR must run validation before creating datasource beans");
            assertFalse(output.contains("PSQLException"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
