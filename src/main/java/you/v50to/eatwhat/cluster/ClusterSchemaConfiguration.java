package you.v50to.eatwhat.cluster;

import org.flywaydb.core.api.callback.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class ClusterSchemaConfiguration {
    @Bean Callback clusterSchemaPrivileges(Environment environment){return new BaseCallback(){
        @Override public boolean supports(Event event,Context context){return event==Event.AFTER_MIGRATE;}
        @Override public void handle(Event event,Context context){
            String username=environment.getProperty("spring.datasource.username");if(username==null||username.isBlank())return;
            boolean enabled=environment.getProperty("app.cluster.enabled",Boolean.class,false);
            if(!enabled&&!environment.getProperty("app.control.enrollment-required",Boolean.class,false))return;
            try(var statement=context.getConnection().createStatement()){
                try(var rows=statement.executeQuery("SELECT to_regclass('eatwhat_cluster.state') IS NOT NULL")){rows.next();if(!rows.getBoolean(1))return;}
                String role="\""+username.replace("\"","\"\"")+"\"";
                statement.execute("GRANT USAGE ON SCHEMA eatwhat_cluster TO "+role);
                if(!enabled){
                    // Only the startup proof can read retained membership while awaiting enrollment.
                    statement.execute("GRANT SELECT ON eatwhat_cluster.state,eatwhat_cluster.member,eatwhat_cluster.change,eatwhat_cluster.local_state,eatwhat_cluster.local_change TO "+role);
                    return;
                }
                statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.state,eatwhat_cluster.member,eatwhat_cluster.change,eatwhat_cluster.local_state,eatwhat_cluster.gc_operation,eatwhat_cluster.gc_fence TO "+role);
                statement.execute("GRANT SELECT,INSERT ON eatwhat_cluster.change_vote,eatwhat_cluster.gc_vote,eatwhat_cluster.audit,eatwhat_cluster.command TO "+role);
                statement.execute("GRANT SELECT,INSERT ON eatwhat_cluster.local_change,eatwhat_cluster.local_phase TO "+role);
                statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.replay_cursor TO "+role);
                statement.execute("GRANT SELECT,INSERT ON eatwhat_cluster.recipient_grant TO "+role);
                statement.execute("GRANT USAGE,SELECT ON SEQUENCE eatwhat_cluster.recipient_grant_grant_id_seq TO "+role);
                statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.replica_fact,eatwhat_cluster.replica_manifest TO "+role);
                statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.recipient_replay TO "+role);
                statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.login_session TO "+role);
                statement.execute("GRANT SELECT ON eatwhat_cluster.session_security_version TO "+role);
                boolean fencePresent;try(var fence=statement.executeQuery("SELECT to_regclass('eatwhat_cluster.session_withdraw_fence') IS NOT NULL")){fence.next();fencePresent=fence.getBoolean(1);}
                if(fencePresent)statement.execute("GRANT SELECT,INSERT ON eatwhat_cluster.session_withdraw_fence TO "+role);
                if(environment.getProperty("app.cluster.coordinator",Boolean.class,false)){
                    boolean leasePresent;try(var lease=statement.executeQuery("SELECT to_regclass('eatwhat_cluster.control_lease') IS NOT NULL")){lease.next();leasePresent=lease.getBoolean(1);}
                    if(leasePresent)statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.control_lease TO "+role);
                    boolean handoffPresent;try(var rows=statement.executeQuery("SELECT to_regclass('eatwhat_cluster.control_handoff') IS NOT NULL")){rows.next();handoffPresent=rows.getBoolean(1);}
                    if(handoffPresent)statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.control_process,eatwhat_cluster.control_handoff TO "+role);
                    boolean deploymentsPresent;try(var rows=statement.executeQuery("SELECT to_regclass('eatwhat_cluster.deployment_fence') IS NOT NULL")){rows.next();deploymentsPresent=rows.getBoolean(1);}
                    if(deploymentsPresent)statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.deployment_fence TO "+role);
                    boolean allocationsPresent;try(var rows=statement.executeQuery("SELECT to_regclass('eatwhat_cluster.installation_allocation') IS NOT NULL")){rows.next();allocationsPresent=rows.getBoolean(1);}
                    if(allocationsPresent)statement.execute("GRANT SELECT,INSERT ON eatwhat_cluster.installation_allocation TO "+role);
                }
                statement.execute("GRANT USAGE,SELECT ON SEQUENCE eatwhat_cluster.audit_id_seq TO "+role);
                statement.execute("GRANT SELECT ON eatwhat_identity.allocation TO "+role);
            }catch(java.sql.SQLException failure){throw new IllegalStateException("Cluster schema privileges could not be configured",failure);}
        }
    };}
}
