package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlBootstrapStorageTest {
    @TempDir Path root;ControlBootstrapStorage storage;ControlServerStore store;UUID id=UUID.randomUUID(),target=UUID.randomUUID();
    static final String P="app.storage.s3.";
    @BeforeEach void setup()throws Exception{store=new ControlServerStore(new ControlAudit(root.toRealPath()));storage=new ControlBootstrapStorage(store);}
    ControlBootstrapStorage.Selection r2(){return new ControlBootstrapStorage.Selection("R2","https://account.r2.example.invalid","ignored-region","eatwhat","target-private-access","target-private-secret",null,"https://cdn.example.invalid/public",null);}
    Map<String,String> source(){return Map.of(P+"provider","RUSTFS",P+"endpoint","https://campus-storage.example.invalid",P+"region","us-east-1",P+"bucket","campus",P+"access-key","source-private-access",P+"secret-key","source-private-secret");}
    @Test void targetSecretsAreEncryptedAndR2DefaultsDoNotReuseCampusEndpoints()throws Exception{
        var f=storage.freeze(id,target,2,r2(),source());var p=storage.open(f,id,target,2);
        assertEquals("R2",p.get(P+"provider"));assertEquals("auto",p.get(P+"region"));assertEquals("false",p.get(P+"path-style"));assertEquals("",p.get(P+"cdn-endpoint"));
        assertEquals("target-private-secret",p.get(P+"secret-key"));assertFalse(p.toString().contains("campus-storage"));
        String encoded=JsonMapper.builder().build().writeValueAsString(f);assertFalse(encoded.contains("target-private-secret"));assertFalse(encoded.contains("target-private-access"));assertEquals("BootstrapStorage[redacted]",f.toString());
        assertFalse(JsonMapper.builder().build().writeValueAsString(r2()).contains("target-private-secret"));assertEquals("CUSTOM",f.review().get("mode"));
    }
    @Test void sourceInheritanceIsExplicitAndUsesTheFrozenSourceBytes(){
        var f=storage.freeze(id,target,2,null,source());var p=storage.open(f,id,target,2);assertEquals("SOURCE",f.review().get("mode"));assertEquals("source-private-secret",p.get(P+"secret-key"));assertEquals("true",p.get(P+"path-style"));
        assertEquals("https://campus-storage.example.invalid",f.review().get("endpoint"));
    }
    @Test void requestFingerprintIncludesCredentialsAndSeparatesCustomFromInheritance(){
        var a=r2();var b=new ControlBootstrapStorage.Selection(a.provider(),a.endpoint(),a.region(),a.bucket(),a.accessKey(),"different-secret",a.pathStyle(),a.publicBaseUrl(),a.cdnEndpoint());
        assertEquals(storage.requestBinding(a),storage.requestBinding(r2()));assertNotEquals(storage.requestBinding(a),storage.requestBinding(b));assertNotEquals(storage.requestBinding(a),storage.requestBinding(null));
    }
    @Test void dynamicSpringPlaceholdersCannotChangeTheApprovedRuntimeCredential(){
        var a=r2();var value=new ControlBootstrapStorage.Selection(a.provider(),a.endpoint(),a.region(),a.bucket(),a.accessKey(),"${spring.datasource.password}",false,null,null);
        assertEquals("BOOTSTRAP_STORAGE_CONFIGURATION_REQUIRED",assertThrows(ControlFailure.class,()->storage.freeze(id,target,2,value,source())).code);
        var inherited=new HashMap<>(source());inherited.put(P+"access-key","${UNREVIEWED_ACCESS_KEY}");assertThrows(ControlFailure.class,()->storage.freeze(id,target,2,null,inherited));
    }
    @Test void anotherTargetRevisionOrBootstrapCannotReuseTheEncryptedSelection(){
        var f=storage.freeze(id,target,2,r2(),source());for(var wrong:List.of(new Object[]{UUID.randomUUID(),target,2L},new Object[]{id,UUID.randomUUID(),2L},new Object[]{id,target,3L}))
            assertEquals("BOOTSTRAP_STORAGE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->storage.open(f,(UUID)wrong[0],(UUID)wrong[1],(Long)wrong[2])).code);
        var changed=new ControlBootstrapStorage.Frozen(id,target,2,f.encryptedProperties(),Map.of("mode","SOURCE"),f.binding());assertThrows(ControlFailure.class,()->storage.open(changed,id,target,2));
    }
    @Test void invalidEndpointsMissingCredentialsAndArbitraryProvidersFailBeforeApproval(){
        for(String url:List.of("file:///etc/shadow","https://user:secret@example.invalid","https://example.invalid?token=x","https://example.invalid#fragment")){
            var a=r2();var s=new ControlBootstrapStorage.Selection(a.provider(),url,a.region(),a.bucket(),a.accessKey(),a.secretKey(),null,null,null);
            assertEquals("BOOTSTRAP_STORAGE_CONFIGURATION_REQUIRED",assertThrows(ControlFailure.class,()->storage.freeze(id,target,2,s,source())).code);
        }
        assertThrows(ControlFailure.class,()->storage.freeze(id,target,2,null,Map.of()));
        var a=r2();assertThrows(ControlFailure.class,()->storage.freeze(id,target,2,new ControlBootstrapStorage.Selection("CUSTOM_CODE",a.endpoint(),a.region(),a.bucket(),a.accessKey(),a.secretKey(),null,null,null),source()));
    }
}
