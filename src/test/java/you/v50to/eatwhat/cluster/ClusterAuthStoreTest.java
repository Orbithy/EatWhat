package you.v50to.eatwhat.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ClusterAuthStoreTest {
    @TempDir Path directory;

    @Test void remoteAuthStoreRequiresVerifiedTlsAndPrivatePasswordFile()throws Exception{
        var settings=new ClusterProperties();settings.setAuthoritySiteId("campus");settings.setAuthorityInstanceId(UUID.randomUUID());
        var config=settings.getAuthStore();config.setEnabled(true);config.setUsername("restricted_auth_client");
        var site=new SiteProperties();site.setClusterId(UUID.randomUUID());
        Path secret=directory.resolve("auth.password");Files.writeString(secret,"synthetic-only");
        Files.setPosixFilePermissions(secret,PosixFilePermissions.fromString("rw-------"));config.setPasswordFile(secret);
        config.setJdbcUrl("jdbc:postgresql://auth.example.invalid:5432/eatwhat?sslmode=disable");
        assertEquals("PRIVATE_AUTH_STORE_CONFIG_REQUIRED",assertThrows(ClusterFailure.class,()->new ClusterAuthStore(settings,site)).getMessage());
        config.setJdbcUrl("jdbc:postgresql://auth.example.invalid:5432/eatwhat?sslmode=verify-full&sslmode=disable");
        assertEquals("PRIVATE_AUTH_STORE_CONFIG_REQUIRED",assertThrows(ClusterFailure.class,()->new ClusterAuthStore(settings,site)).getMessage());
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:5432/eatwhat");
        Files.setPosixFilePermissions(secret,PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals("PRIVATE_AUTH_STORE_CONFIG_REQUIRED",assertThrows(ClusterFailure.class,()->new ClusterAuthStore(settings,site)).getMessage());
    }
}
