package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Native PostgreSQL logical projection is asynchronous; reads and management
 * credentials must remain local after the publisher actually disappears. */
class IndependentControlProjectionIT {
    @Test void persistedLocalProjectionAndOwnCredentialsRemainReadableAfterPublisherStops()throws Exception{
        GenericContainer<?> source=IsolatedContainers.postgres("independent-projection-source").withCommand("postgres","-c","wal_level=logical");
        GenericContainer<?> target=IsolatedContainers.postgres("independent-projection-target");
        try{
            source.start();target.start();
            JdbcTemplate a=jdbc(source),b=jdbc(target);
            a.execute("CREATE TABLE public.business_projection(id bigint PRIMARY KEY,value text NOT NULL)");
            b.execute("CREATE TABLE public.business_projection(id bigint PRIMARY KEY,value text NOT NULL)");
            a.execute("CREATE TABLE public.source_private_management(id bigint PRIMARY KEY,credential text NOT NULL)");
            b.execute("CREATE TABLE public.local_management(id bigint PRIMARY KEY,credential text NOT NULL)");
            a.update("INSERT INTO business_projection VALUES(1,'original')");
            a.update("INSERT INTO source_private_management VALUES(1,'synthetic-source-only')");
            b.update("INSERT INTO local_management VALUES(1,'synthetic-local-only')");
            a.execute("CREATE PUBLICATION independent_test_business FOR TABLE public.business_projection");
            String ip=source.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
            assertTrue(ip.matches("[0-9.]+"));
            String connection="host="+ip+" port=5432 dbname="+IsolatedContainers.DATABASE+" user="+IsolatedContainers.USER+" password="+IsolatedContainers.PASSWORD+" connect_timeout=5";
            b.execute("CREATE SUBSCRIPTION independent_test_projection CONNECTION '"+connection+"' PUBLICATION independent_test_business WITH(copy_data=true,create_slot=true,enabled=true)");
            await(()->b.queryForObject("SELECT count(*)FROM business_projection",Long.class)==1);
            a.update("UPDATE business_projection SET value='replicated'WHERE id=1");
            await(()->"replicated".equals(b.queryForObject("SELECT value FROM business_projection WHERE id=1",String.class)));
            assertNull(b.queryForObject("SELECT to_regclass('public.source_private_management')::text",String.class));
            source.stop();
            assertEquals("replicated",b.queryForObject("SELECT value FROM business_projection WHERE id=1",String.class));
            assertEquals("synthetic-local-only",b.queryForObject("SELECT credential FROM local_management WHERE id=1",String.class));
        }finally{target.stop();source.stop();}
    }
    private static JdbcTemplate jdbc(GenericContainer<?> container){return new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(container,IsolatedContainers.DATABASE),IsolatedContainers.USER,IsolatedContainers.PASSWORD));}
    private static void await(java.util.function.BooleanSupplier assertion)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
        while(System.nanoTime()<deadline){if(assertion.getAsBoolean())return;Thread.sleep(50);}fail("Local projection did not converge");
    }
}
