package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opted-in, read-only real-host SSH/sudo preflight. Never installs or restarts anything. */
@EnabledIfSystemProperty(named="eatwhat.remote-sudo.target-file",matches=".+")
class ControlRemoteSudoAcceptance {
    @TempDir Path state;
    @Test void genuinePinnedSshReportsActualPrivilegeAndInspectsFreshInstallationWithoutWrites()throws Exception{
        var json=JsonMapper.builder().build();
        Path input=Path.of(System.getProperty("eatwhat.remote-sudo.target-file"));
        var target=json.readValue(ControlMemberConfiguration.privateBytes(input,32768),Map.class);
        assertEquals(Set.of("host","port","username","hostKey","privateKeyFile","serverId"),target.keySet());
        assertNotEquals("root",target.get("username"));UUID id=UUID.fromString(target.get("serverId").toString());
        var store=new ControlServerStore(new ControlAudit(state));var settings=new ControlProperties();
        var ssh=new ControlSsh(store,settings);var server=new LinkedHashMap<String,Object>();
        for(String key:List.of("host","port","username","hostKey"))server.put(key,target.get(key));
        server.put("authentication","PRIVATE_KEY");server.put("credential",store.encrypt(new String(ControlMemberConfiguration.privateBytes(Path.of(target.get("privateKeyFile").toString()),16384),java.nio.charset.StandardCharsets.UTF_8)));
        var direct=ssh.call(server,ControlServers.resource("server-probe.py"),List.of(),out->{},45);
        assertEquals("eatwhat-server-probe-v1",direct.get("protocol"));assertEquals(false,direct.get("root"));
        server.put("privilege","SUDO");var elevated=ssh.call(server,ControlServers.resource("server-probe.py"),List.of(),out->{},45);
        assertEquals(true,elevated.get("root"));assertTrue(elevated.get("linux").toString().startsWith("Linux"));
        var preflight=ssh.call(server,ControlServers.resource("first-install.py"),List.of("inspect"),out->{out.write(json.writeValueAsBytes(Map.of("serverId",id.toString(),"httpPort",18090)));out.write('\n');},45);
        assertEquals("eatwhat-install-v1",preflight.get("protocol"));assertEquals(true,preflight.get("rootUser"));
        assertEquals(true,preflight.get("rootAvailable"));assertEquals(true,preflight.get("portAvailable"));
        assertTrue(Boolean.TRUE.equals(preflight.get("dockerReady"))||Boolean.TRUE.equals(preflight.get("canInstallDocker")));
        assertEquals("/opt/eatwhat-sites/"+id,preflight.get("root"));
        try(var files=Files.list(store.directory())){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("ssh-session-")));}
    }
}
