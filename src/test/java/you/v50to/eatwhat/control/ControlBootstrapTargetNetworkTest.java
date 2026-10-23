package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlBootstrapTargetNetworkTest {
    @TempDir Path root;
    @Test void sourcePortsAreDerivedAndLoopbackAdministrationHasItsOwnPinnedRoute()throws Exception{
        var f=new BootstrapTargetFixture(root);var plan=f.network.plan(f.frozen);var ports=new TreeMap<String,Integer>();
        plan.forwards().forEach(row->ports.put(row.get("purpose").toString(),(Integer)row.get("remotePort")));
        assertEquals(Map.of("CONTROL_DATABASE",5432,"CONTROL_CACHE",6379,"AUTHORITY_HTTP",18090,"SOURCE_ADMIN_SSH",22),ports);
        var route=(Map<?,?>)plan.sshRoutes().get(f.sourceId.toString());assertEquals("127.0.0.1",route.get("host"));assertEquals(28300,route.get("port"));
        assertEquals(22,route.get("logicalPort"));assertEquals(BootstrapTargetFixture.hostKey(),route.get("hostKey"));assertEquals("http://127.0.0.1:28090",plan.authorityOrigin());
    }
    @Test void eachPrivateMemberGetsASeparateForwardWhilePublicTlsOriginAndIdentityStayExact()throws Exception{
        var f=new BootstrapTargetFixture(root);f.extraProfiles();var plan=f.network.plan(f.frozen);
        assertEquals(3,plan.profiles().size());var external=(Map<?,?>)plan.profiles().get("external");assertEquals("http://127.0.0.1:28100",external.get("origin"));assertEquals("/api",external.get("apiPrefix"));
        assertEquals("https://public.example.invalid",((Map<?,?>)plan.profiles().get("public")).get("origin"));
        assertTrue(plan.forwards().contains(Map.of("purpose","MEMBER_HTTP_1","localPort",28100,"remotePort",28091)));
        String review=f.source.json.writeValueAsString(plan);assertTrue(review.contains("CONNECTIVITY_UNVERIFIED"));assertFalse(review.contains("runtime-private-secret"));assertFalse(review.contains(root.toString()));
    }
    @Test void aThirdHostsSourceOnlySshRouteIsNotCopiedOrGuessed()throws Exception{
        var f=new BootstrapTargetFixture(root);f.source.routes();f.capture();
        assertEquals("BOOTSTRAP_TARGET_THIRD_HOST_SSH_ROUTE_REQUIRED",assertThrows(ControlFailure.class,()->f.network.plan(f.frozen)).code);
    }
    @Test void remoteDatabaseAndRedisUrlCannotBeTurnedIntoUnprovedLocalServices()throws Exception{
        var f=new BootstrapTargetFixture(root);f.source.properties.put("spring.datasource.url","jdbc:postgresql://remote.invalid/eatwhat");f.capture();
        assertEquals("BOOTSTRAP_TARGET_DATABASE_ROUTE_REQUIRED",assertThrows(ControlFailure.class,()->f.network.plan(f.frozen)).code);
        f.source.properties.put("spring.datasource.url","jdbc:postgresql://127.0.0.1/eatwhat");f.source.properties.put("spring.data.redis.url","redis://:private@127.0.0.1:6379");f.capture();
        assertEquals("BOOTSTRAP_TARGET_CACHE_URL_MATERIAL_REQUIRED",assertThrows(ControlFailure.class,()->f.network.plan(f.frozen)).code);
    }
}
