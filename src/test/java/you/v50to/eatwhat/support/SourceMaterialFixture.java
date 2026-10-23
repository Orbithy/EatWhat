package you.v50to.eatwhat.support;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Disposable private files only. No SSH, actual TLS or database authority. */
public final class SourceMaterialFixture {
    public final Path root,config,privateDirectory;public final UUID cluster=UUID.randomUUID(),instance=UUID.randomUUID(),state=UUID.randomUUID(),server=UUID.randomUUID();
    public final Map<String,String> properties=new TreeMap<>();public final JsonMapper json=JsonMapper.builder().build();
    public Path credentials,key,memberConfig,password,ca,routes;
    public SourceMaterialFixture(Path root)throws Exception{
        this.root=root.toRealPath();config=directory(this.root.resolve("config"));privateDirectory=directory(this.root.resolve("private"));Path stateDirectory=directory(this.root.resolve("state"));
        properties.putAll(Map.ofEntries(Map.entry("app.site.cluster-id",cluster.toString()),Map.entry("app.site.id","campus"),Map.entry("app.site.instance-id",instance.toString()),
                Map.entry("app.site.mode","CAMPUS"),Map.entry("app.site.allocation-slot","0"),Map.entry("app.control.enabled","true"),Map.entry("app.control.node-enabled","true"),
                Map.entry("app.control.environment-kind","managed-deployment"),Map.entry("app.control.state-directory",stateDirectory.toString()),Map.entry("app.control.shared-state-required","false"),
                Map.entry("app.cluster.enabled","true"),Map.entry("app.cluster.coordinator","true"),Map.entry("app.cluster.single-site-only","false"),
                Map.entry("app.cluster.control-lease.enabled","true"),Map.entry("app.cluster.control-lease.controller-name","fixture-source"),
                Map.entry("app.cluster.profiles-file",config.resolve("cluster-profiles.json").toString()),Map.entry("app.control.node-token-file",config.resolve("authority.token").toString()),
                Map.entry("spring.datasource.password","runtime-private-secret"),Map.entry("spring.flyway.user","migrator"),Map.entry("spring.flyway.password","migration-private-secret")));
        write(config.resolve("cluster-profiles.json"),json.writeValueAsBytes(Map.of("campus",Map.of("siteId","campus","instanceId",instance.toString(),"mode","CAMPUS","allocationSlot",0,
                "origin","http://127.0.0.1:18090","tokenFile",properties.get("app.control.node-token-file")))));
        write(config.resolve("authority.token"),"t".repeat(43).getBytes(StandardCharsets.US_ASCII));write(stateDirectory.resolve("servers.key"),new byte[32]);
        write(stateDirectory.resolve("controller-state-id"),state.toString().getBytes(StandardCharsets.US_ASCII));
    }
    private static Path directory(Path path)throws Exception{return Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}
    public static void write(Path path,byte[] bytes)throws Exception{Files.write(path,bytes);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
    public void credentials()throws Exception{
        key=privateDirectory.resolve("server-key.pem");write(key,"-----BEGIN PRIVATE KEY-----\nsynthetic-private-ssh-key\n-----END PRIVATE KEY-----\n".getBytes(StandardCharsets.US_ASCII));
        credentials=config.resolve("ssh-credentials.json");write(credentials,json.writeValueAsBytes(Map.of("version",1,"credentials",Map.of("remote-admin",Map.of("label","Remote admin","authentication","PRIVATE_KEY","file",key.toString())))));
        properties.put("app.control.servers.credentials-file",credentials.toString());
    }
    public void members()throws Exception{
        password=privateDirectory.resolve("auth-password.txt");write(password,"auth-private-password\n".getBytes(StandardCharsets.US_ASCII));
        ca=privateDirectory.resolve("auth-ca.pem");write(ca,"-----BEGIN CERTIFICATE-----\nsynthetic-ca\n-----END CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII));
        memberConfig=config.resolve("member-configurations.json");write(memberConfig,json.writeValueAsBytes(Map.of("version",1,"clusterId",cluster.toString(),"servers",Map.of(server.toString(),
                Map.of("profileId","future-external","authJdbcOrigin","jdbc:postgresql://127.0.0.1:25432/eatwhat","authUsername","fixture_auth","authPasswordFile",password.toString(),"authCaFile",ca.toString())))));
        properties.put("app.control.servers.member-configurations-file",memberConfig.toString());
    }
    public void terminatedMembers()throws Exception{
        members();var entry=new LinkedHashMap<String,Object>(Map.of("profileId","future-external","authJdbcOrigin","jdbc:postgresql://127.0.0.1:25443/eatwhat","authUsername","fixture_auth","authPasswordFile",password.toString(),"authCaFile",ca.toString(),"authTransport","TLS_TERMINATED"));
        write(memberConfig,json.writeValueAsBytes(Map.of("version",2,"clusterId",cluster.toString(),"servers",Map.of(server.toString(),entry))));
    }
    public void routes()throws Exception{
        byte[] key=java.nio.ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".getBytes(StandardCharsets.US_ASCII)).putInt(32).put(new byte[32]).array();
        routes=config.resolve("ssh-routes.json");write(routes,json.writeValueAsBytes(Map.of("version",1,"routes",Map.of(server.toString(),
                Map.of("host","source-only.internal","port",2222,"logicalHost","127.0.0.1","logicalPort",22,"hostKey","ssh-ed25519 "+Base64.getEncoder().encodeToString(key))))));
        properties.put("app.control.servers.ssh-routes-file",routes.toString());
    }
    public ControllerSourceSnapshot.Captured capture()throws Exception{
        var app=new StringBuilder();properties.forEach((k,v)->app.append(k).append('=').append(v).append('\n'));byte[] raw=app.toString().getBytes(StandardCharsets.US_ASCII);write(config.resolve("application.properties"),raw);
        var expected=new ControllerSourceSnapshot.Expected(cluster,"campus",instance,"a".repeat(32),state,1,"fixture-source",ControllerSourceSnapshot.sha(raw),
                ControllerSourceSnapshot.sha(Files.readAllBytes(config.resolve("cluster-profiles.json"))),ControllerSourceSnapshot.sha(new byte[32]));
        return ControllerSourceSnapshot.capture(root,expected);
    }
}
