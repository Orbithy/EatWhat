package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import java.util.*;

/** Shared database/setup helpers only. Test suites choose their own initial
 * epoch; inheriting a test must not bootstrap its authority a second time. */
abstract class ClusterMembershipFixture {
    static final GenericContainer<?> POSTGRES=IsolatedContainers.postgres("cluster-membership");
    @BeforeAll static void start(){POSTGRES.start();}
    @AfterAll static void stop(){POSTGRES.stop();}
    EventFoundationTestSupport.Database db;
    ClusterStore store;
    final UUID cluster=UUID.randomUUID();
    final ClusterMember campus=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0);
    final ClusterMember external=new ClusterMember("external",UUID.randomUUID(),"EXTERNAL",1);
    final ClusterMember third=new ClusterMember("third",UUID.randomUUID(),"EXTERNAL",2);
    @BeforeEach void setup(){
        db=database(campus,true);String role="\""+db.runtimeUsername()+"\"";
        db.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster,eatwhat_file TO "+role);
        db.admin().execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA eatwhat_cluster,eatwhat_file TO "+role);
        db.admin().execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_cluster,eatwhat_file TO "+role);
        store=new ClusterStore(db.runtime(),db.transactions(),cluster,campus.identity());store.initialize();
    }
    EventFoundationTestSupport.Database database(ClusterMember member,boolean clustered){var site=new SiteProperties();site.setId(member.siteId());site.setInstanceId(member.instanceId());site.setMode(SiteProperties.Mode.valueOf(member.mode()));if(clustered){site.setClusterId(cluster);site.setAllocationSlot(member.allocationSlot());}return EventFoundationTestSupport.createDatabase(POSTGRES,site);}
    void register(ClusterMember member,long epoch){store.register("admin",UUID.randomUUID(),epoch,member.siteId(),member);}
    UUID beginJoin(ClusterMember member,long epoch){register(member,epoch);UUID id=UUID.randomUUID();store.begin("admin",id,epoch,"JOIN",member.siteId(),fingerprint(member));return id;}
    String fingerprint(ClusterMember member){return (member==campus?"a":member==external?"b":"c").repeat(32);}
    void finish(UUID id){for(var p:store.participants(id))ClusterTestVotes.membership(store,id,p,"PREPARED");store.decide("admin",id);for(var p:store.participants(id))ClusterTestVotes.membership(store,id,p,"APPLIED");store.finish("admin",id);for(var p:store.participants(id))ClusterTestVotes.membership(store,id,p,"RELEASED");store.released("admin",id);}
}
