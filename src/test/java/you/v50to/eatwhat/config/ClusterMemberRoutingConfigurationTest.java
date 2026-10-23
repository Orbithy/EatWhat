package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.account.config.AccountConfigurationValidator;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

/** Cluster membership replaces legacy fixed peers; identity, consent and bounded workers remain required. */
class ClusterMemberRoutingConfigurationTest {
    ApplicationContextRunner runner(String mode,int slot){
        return new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator()).withInitializer(new AccountConfigurationValidator())
            .withPropertyValues("app.cluster.enabled=true","app.site.mode="+mode,"app.site.id=member-test",
                "app.site.instance-id=00000000-0000-4000-8000-000000000001","app.site.cluster-id=00000000-0000-4000-8000-000000000003","app.site.allocation-slot="+slot,
                "app.account.enabled=true","app.account.synthetic-only=true","app.sync.transport-enabled=true","app.sync.synthetic-only=false",
                "app.sync.activity.enabled=true","app.sync.relation.enabled=true","app.sync.profile.enabled=true","app.sync.privacy.enabled=true",
                "app.sync.notification.enabled=true","app.sync.catalog.enabled=true","app.sync.files.enabled=true","app.storage.files.replication.enabled=true");
    }
    @ParameterizedTest @CsvSource({"CAMPUS,0","EXTERNAL,1","EXTERNAL,2"})
    void allDomainsAcceptMembershipRoutingWithoutAnyFixedPeer(String mode,int slot){runner(mode,slot).run(context->assertThat(context).hasNotFailed());}
    @ParameterizedTest @CsvSource({"app.cluster.enabled,false","app.site.allocation-slot,1024","app.sync.synthetic-only,true","app.account.enabled,false","app.sync.relation.backfill-batch-size,0","app.sync.catalog.max-hub-assignments,501","app.sync.notification.worker-batch-size,129","app.sync.files.batch-size,101","app.sync.lease-duration,1s"})
    void missingRuntimeInvalidAllocationsAndWorkerBoundsFailBeforeDependencies(String key,String value){
        var initialized=new AtomicBoolean();runner("EXTERNAL",2).withPropertyValues(key+"="+value).withBean(Object.class,()->{initialized.set(true);return new Object();}).run(context->{assertThat(context).hasFailed();assertThat(initialized).isFalse();});
    }
    @Test void boundClusterAccountChannelCanUseConsentWithoutTheBilateralSyntheticSwitch(){runner("CAMPUS",0).withPropertyValues("app.account.synthetic-only=false").run(context->assertThat(context).hasNotFailed());}
}
