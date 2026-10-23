package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlBusinessRuntimeTest {
    Map<String,Object> header(){return Map.of("siteId","server-012345abcdef","instanceId",UUID.randomUUID().toString(),"mode","EXTERNAL","clusterId",UUID.randomUUID().toString(),"allocationSlot",1);}
    Map<String,String> database(){return Map.of("POSTGRES_USER","eatwhat_migration","POSTGRES_PASSWORD","fresh-migration","PG_DB","eatwhat","PG_APP_USER","eatwhat_app","PG_APP_PASSWORD","fresh-runtime","REDIS_PASSWORD","fresh-cache");}
    @Test void sourceDatabaseAndClusterSecretsCannotOverrideTheInstallationsOwnCredentials(){
        var source=Map.of("spring.datasource.password","source-runtime","spring.flyway.password","source-migrator","app.cluster.auth-store.password-file","/source/private-password",
                "app.control.deployment.identity-file","/source/ssh-key","app.jwt.key","shared-jwt","app.storage.s3.secret-key","site-storage-secret");
        var p=ControlBusinessRuntime.properties(header(),database(),"https://example.invalid/api",source::get);
        assertEquals("fresh-runtime",p.get("spring.datasource.password"));assertEquals("fresh-migration",p.get("spring.flyway.password"));assertEquals("fresh-cache",p.get("spring.data.redis.password"));
        assertEquals("shared-jwt",p.get("app.jwt.key"));assertEquals("site-storage-secret",p.get("app.storage.s3.secret-key"));
        assertFalse(p.containsKey("app.cluster.auth-store.password-file"));assertFalse(p.containsKey("app.control.deployment.identity-file"));
        assertEquals("https://example.invalid/api",p.get("app.base-url"));
    }
    @Test void externalInstallIsGatedAndCannotPrematurelyEnableReplicationOrDeployment(){
        var h=header();var p=ControlBusinessRuntime.properties(h,database(),"https://example.invalid/api",ignored->null);
        assertEquals("EXTERNAL",p.get("app.site.mode"));assertEquals(h.get("clusterId"),p.get("app.site.cluster-id"));assertEquals("1",p.get("app.site.allocation-slot"));
        assertEquals("true",p.get("app.control.enrollment-required"));
        for(String key:List.of("app.cluster.enabled","app.account.enabled","app.sync.transport-enabled","app.sync.capture-enabled","app.storage.files.replication.worker-enabled","app.control.deployment.enabled"))assertEquals("false",p.get(key),key);
        assertEquals("/config/control.token",p.get("app.control.node-token-file"));assertEquals("/state",p.get("app.control.state-directory"));
    }
    @Test void perSiteObjectStorageAndCdnSettingsArePreservedWithoutProviderFallback(){
        var source=Map.of("app.storage.s3.provider","R2","app.storage.s3.path-style","false","app.storage.s3.region","auto","app.storage.s3.endpoint","https://fixture.r2.cloudflarestorage.com",
                "app.storage.s3.public-base-url","https://cdn.example.invalid/uploads","app.storage.s3.bucket","eatwhat");
        var p=ControlBusinessRuntime.properties(header(),database(),"https://example.invalid/api",source::get);source.forEach((key,value)->assertEquals(value,p.get(key)));
    }
    @Test void renderingIsDeterministicAndBothRealParsersRecoverSecretsExactly()throws Exception{
        var values=Map.of("app.jwt.key","  #餐碗🍜\\secret\nnext=value:yes\t","spring.datasource.url","jdbc:postgresql://postgres:5432/eatwhat","spring.mail.password","!秘密\\line\r\n");
        String text=ControlBusinessRuntime.render(values);assertEquals(text,ControlBusinessRuntime.render(new HashMap<>(values)));assertTrue(text.chars().allMatch(c->c<128));assertFalse(text.startsWith("#"));
        var java=new Properties();java.load(new StringReader(text));values.forEach((key,value)->assertEquals(value,java.getProperty(key)));
        var spring=new PropertiesPropertySourceLoader().load("business",new ByteArrayResource(text.getBytes(StandardCharsets.US_ASCII)));assertEquals(1,spring.size());values.forEach((key,value)->assertEquals(value,spring.getFirst().getProperty(key)));
    }
    @Test void generatedDatabaseLinesStillSupportTheActualRestoreRehearsalReplacement()throws Exception{
        String text=ControlBusinessRuntime.render(ControlBusinessRuntime.properties(header(),database(),"https://example.invalid/api",ignored->null));var changed=new StringBuilder();
        text.lines().forEach(line->changed.append(line.startsWith("spring.datasource.url=")||line.startsWith("spring.flyway.url=")?line.replace("/eatwhat","/eatwhat_restore_fixture"):line).append('\n'));
        var p=new Properties();p.load(new StringReader(changed.toString()));assertEquals("jdbc:postgresql://postgres:5432/eatwhat_restore_fixture",p.getProperty("spring.datasource.url"));assertEquals(p.getProperty("spring.datasource.url"),p.getProperty("spring.flyway.url"));
    }
}
