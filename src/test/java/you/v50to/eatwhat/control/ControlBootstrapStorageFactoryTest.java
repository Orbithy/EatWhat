package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual factories/journal/sealed inputs with explicit native controller,
 * allocation and SSH fixtures. No actual host or object-store acceptance. */
class ControlBootstrapStorageFactoryTest {
    @TempDir Path root;ControlBootstrapBusinessPhasesTest fixture;
    @BeforeEach void setup()throws Exception{
        fixture=new ControlBootstrapBusinessPhasesTest();fixture.root=root;
        fixture.setup(new ControlBootstrapStorage.Selection("R2","https://target-s3.example.invalid","auto","eatwhat","target-private-access","target-private-secret",false,"https://objects.example.invalid",null));
    }
    @AfterEach void close(){if(fixture!=null)fixture.close();}
    @Test @SuppressWarnings("unchecked") void targetControllerAndBusinessUseTheSameOriginalStorageWithoutChangingSource()throws Exception{
        var c=new ControlBootstrapControllerFactory(fixture.fixture.network,null,null,null,fixture.journal);var f=fixture.f;
        var controller=(Map<String,String>)c.bundle(f,fixture.fixture.network.plan(f)).get("properties");
        fixture.factory.ensure(f);var business=(Map<String,String>)((Map<?,?>)fixture.original().header().get("business")).get("properties");
        for(String key:List.of("provider","endpoint","region","bucket","access-key","secret-key","path-style","public-base-url","cdn-endpoint"))assertEquals(controller.get("app.storage.s3."+key),business.get("app.storage.s3."+key),key);
        assertEquals("R2",business.get("app.storage.s3.provider"));assertEquals("target-private-secret",business.get("app.storage.s3.secret-key"));assertEquals("false",business.get("app.storage.s3.path-style"));
        assertFalse(fixture.fixture.network.sourceProperties(f).toString().contains("target-private-secret"));
        assertFalse(Files.readString(root.resolve("manager/controller-job-"+f.spec().id()+".json")).contains("target-private-secret"));
        String before=fixture.original().binding();fixture.restart();fixture.factory.ensure(f);assertEquals(before,fixture.original().binding());assertEquals(1,fixture.inspections);
    }
    @Test @SuppressWarnings("unchecked") void alteredPublicStorageReviewCannotCreateBusinessInputs(){
        var id=fixture.f.spec().id();var path=fixture.store.directory().resolve("controller-job-"+id+".json");var job=fixture.store.readJson(path);
        ((Map<String,Object>)job.get("storage")).put("endpoint","https://unapproved.example.invalid");fixture.store.saveJson(path,job);
        assertEquals("BOOTSTRAP_STORAGE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->fixture.factory.ensure(fixture.f)).code);assertEquals(0,fixture.inspections);
    }
}
