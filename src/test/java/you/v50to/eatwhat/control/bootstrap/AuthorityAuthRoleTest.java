package you.v50to.eatwhat.control.bootstrap;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Input/diagnostic checks; real login and privilege boundaries use PG integration. */
class AuthorityAuthRoleTest {
    AuthorityAuthRole.Request request(String site,String password,String salt){
        UUID bootstrap=UUID.randomUUID();return new AuthorityAuthRole.Request(UUID.nameUUIDFromBytes((bootstrap+":MEMBER_AUTH_ROLE").getBytes(StandardCharsets.UTF_8)),bootstrap,
                UUID.randomUUID(),UUID.randomUUID(),site,UUID.randomUUID(),"a".repeat(32),1,UUID.randomUUID(),UUID.randomUUID(),1,1,"a".repeat(43)+"=",password,salt,Instant.parse("2027-01-01T00:00:00Z"));
    }
    @Test void generated_name_is_bound_to_full_target_uuid_and_private_inputs_stay_redacted(){
        var r=request("campus","p".repeat(43),Base64.getEncoder().encodeToString(new byte[16]));
        assertEquals("eatwhat_auth_"+r.targetServerId().toString().replace("-",""),r.username());assertEquals("AuthorityAuthRoleRequest[redacted]",r.toString());
        String verifier=AuthorityAuthRole.verifier(r);assertTrue(verifier.startsWith("SCRAM-SHA-256$4096:"));assertFalse(verifier.contains(r.password()));assertEquals(verifier,AuthorityAuthRole.verifier(r));
    }
    @Test void sql_text_credentials_or_noncanonical_salt_cannot_enter_the_policy(){
        String salt=Base64.getEncoder().encodeToString(new byte[16]);
        for(String site:List.of("campus;DROP ROLE a","a'b",""))assertThrows(IllegalStateException.class,()->request(site,"p".repeat(43),salt));
        for(String password:List.of("short","x'"+"p".repeat(43),"p".repeat(43)+"\n"))assertThrows(IllegalStateException.class,()->request("campus",password,salt));
        for(String wrong:List.of("invalid","",Base64.getEncoder().encodeToString(new byte[32]),salt.substring(0,salt.length()-2)))assertThrows(IllegalStateException.class,()->request("campus","p".repeat(43),wrong));
    }
}
