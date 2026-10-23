package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual domain self-demotion and HTTP password withdrawal; no role-change HTTP API is invented. */
class AdministratorWithdrawalCleanupIT {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
    private ClusterSeamlessIT fixture;
    private static final String NAME="synthetic_cleanup_admin", PASSWORD="synthetic-password", POLICY="synthetic-admin-cleanup-v1";
    @BeforeAll static void start(){ClusterSeamlessIT.services();}
    @AfterAll static void servicesStop(){ClusterSeamlessIT.cleanup();}
    @AfterEach void stop()throws Exception{
        if(fixture==null)return;
        for(int n=0;n<Math.min(2,fixture.databases.size());n++){
            var db=fixture.databases.get(n).admin();
            System.out.println("Cleanup state node="+n+" "+db.queryForList("SELECT status,cleanup_status,source_archived FROM eatwhat_consent.subject"));
        }
        fixture.stop();
    }
    @Test void independentSyntheticAdministratorIsDisabledBeforeWithdrawalAndErasedRemotely()throws Exception{
        fixture=new ClusterSeamlessIT(){
            @Override void configureNode(Properties p,int node){
                if(node==0){
                    var db=databases.getFirst();
                    assertFalse(db.admin().queryForObject("SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton",Boolean.class));
                    // The exact upgrade fixture: explicitly synthetic role exists before the first writer activation.
                    db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES(?,?,'admin')",NAME,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD));
                }
                p.setProperty("app.account.multiwriter.enabled","true");p.setProperty("app.account.policy-version",POLICY);
                databases.get(node).admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
            }
        };
        fixture.directory=directory;fixture.prepare();
        fixture.adminToken=fixture.login(0,"meshadmin");fixture.join(0,0);
        fixture.adminToken=fixture.login(0,"meshadmin");fixture.join(1,1);
        String initial=fixture.login(0,NAME);
        var grant=fixture.request(0,"POST","/auth/consent/grant",Map.of("policyVersion",POLICY),initial);
        assertEquals(0,ClusterSeamlessIT.JSON.readTree(grant.body()).path("code").asInt(-1),grant.body());
        var campus=fixture.databases.getFirst();long id=campus.admin().queryForObject("SELECT id FROM users WHERE nick_name=?",Long.class,NAME);
        var accounts=new MultiwriterAccounts(campus.runtime(),campus.transactions(),campus.site(),true);
        accounts.command(id,id,()->{campus.runtime().update("INSERT INTO contacts(account_id,email,phone) VALUES(?,'cleanup@example.invalid','13900000009')",id);return null;});
        ClusterSeamlessIT.await(60,()->fixture.databases.get(1).admin().queryForObject("SELECT count(*) FROM users u JOIN contacts c ON c.account_id=u.id JOIN eatwhat_consent.subject s ON s.user_id=u.id WHERE u.id=? AND u.role='admin' AND s.status='GRANTED'",Integer.class,id)==1);
        var oldTokens=List.of(fixture.login(0,NAME),fixture.login(1,NAME));
        accounts.command(id,id,()->{campus.runtime().update("UPDATE users SET role='user',banned=true WHERE id=?",id);return null;});
        ClusterSeamlessIT.await(60,()->fixture.databases.subList(0,2).stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM users WHERE id=? AND role='user' AND banned",Integer.class,id)==1));
        for(int node:List.of(0,1)){
            assertEquals(0,fixture.databases.get(node).admin().queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.authorization_review WHERE subject_id=(SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=?) AND state<>'APPROVED'",Integer.class,id));
            assertDenied(node,oldTokens.get(node));assertLoginCode(node,30002);
        }
        var wrong=fixture.request(0,"POST","/auth/consent/withdraw-with-password",Map.of("username",NAME,"password","incorrect-synthetic-password"),null);
        assertNotEquals(0,ClusterSeamlessIT.JSON.readTree(wrong.body()).path("code").asInt(-1),wrong.body());
        var withdraw=fixture.request(0,"POST","/auth/consent/withdraw-with-password",Map.of("username",NAME,"password",PASSWORD),null);
        assertEquals(0,ClusterSeamlessIT.JSON.readTree(withdraw.body()).path("code").asInt(-1),withdraw.body());
        ClusterSeamlessIT.await(90,()->fixture.databases.subList(0,2).stream().allMatch(db->db.admin().queryForObject("SELECT count(*) FROM eatwhat_consent.subject WHERE user_id=? AND status='WITHDRAWN'",Integer.class,id)==1)
                &&fixture.databases.get(1).admin().queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,id)==0);
        var remote=fixture.databases.get(1).admin();
        for(String table:List.of("contacts","verifications"))assertEquals(0,remote.queryForObject("SELECT count(*) FROM "+table+" WHERE account_id=?",Integer.class,id));
        assertEquals(0,remote.queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.operation WHERE subject_id=(SELECT subject_id FROM eatwhat_consent.subject WHERE user_id=?) AND field IN ('username','password','email','phone','verification')",Integer.class,id));
        assertEquals(0,remote.queryForObject("SELECT count(*) FROM eatwhat_account_multiwriter.session WHERE user_id=?",Integer.class,id));
        assertEquals(0,remote.queryForObject("SELECT count(*) FROM eatwhat_control.credential_version WHERE user_id=?",Integer.class,id));
        assertEquals(1,campus.admin().queryForObject("SELECT count(*) FROM users WHERE id=? AND role='user' AND banned",Integer.class,id));
        assertEquals(1,campus.admin().queryForObject("SELECT count(*) FROM eatwhat_control.credential_version WHERE user_id=?",Integer.class,id));
        for(int node:List.of(0,1)){
            assertEquals(0,fixture.databases.get(node).admin().queryForObject("SELECT count(*) FROM users WHERE id=? AND role='admin'",Integer.class,id));
            assertDenied(node,oldTokens.get(node));assertLoginCode(node,node==0?30002:30001);
        }
    }
    private void assertDenied(int node,String token)throws Exception{
        var result=fixture.request(node,"GET","/user/info",null,token);
        assertNotEquals(0,ClusterSeamlessIT.JSON.readTree(result.body()).path("code").asInt(-1),result.body());
    }
    private void assertLoginCode(int node,int code)throws Exception{
        var result=fixture.request(node,"POST","/auth/login",Map.of("username",NAME,"password",PASSWORD,"device","WEB"),null);
        assertEquals(code,ClusterSeamlessIT.JSON.readTree(result.body()).path("code").asInt(-1),result.body());
    }
}
