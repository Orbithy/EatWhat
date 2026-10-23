package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlMemberConfigurationTest {
    @TempDir Path directory;
    final UUID server=UUID.randomUUID(),cluster=UUID.randomUUID();
    final ClusterMember authority=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0),external=new ClusterMember("external",UUID.randomUUID(),"EXTERNAL",2);
    ControlProperties settings;ClusterProfiles profiles;Path inventory,password,token;Map<String,Object> entry;
    @BeforeEach void prepare()throws Exception{
        directory=directory.toRealPath();
        settings=new ControlProperties();inventory=directory.resolve("members.json");password=directory.resolve("auth.password");token=directory.resolve("external.token");
        write(password,"synthetic-auth-secret");write(token,"n".repeat(64));Path campusToken=directory.resolve("campus.token");write(campusToken,"c".repeat(64));
        var c=new ClusterProperties();
        for(var m:List.of(authority,external)){var p=new ClusterProperties.Profile();p.setSiteId(m.siteId());p.setInstanceId(m.instanceId());p.setMode(m.mode());p.setAllocationSlot(m.allocationSlot());p.setOrigin("https://"+m.siteId()+".example.invalid");p.setApiPrefix("/api");p.setTokenFile(m.equals(authority)?campusToken:token);c.getProfiles().put(m.siteId(),p);}
        profiles=new ClusterProfiles(c);settings.getServers().setMemberConfigurationsFile(inventory);
        entry=new LinkedHashMap<>(Map.of("profileId","external","authJdbcOrigin","jdbc:postgresql://127.0.0.1:5432/eatwhat","authUsername","restricted_auth","authPasswordFile",password.toString(),"authCaFile",""));save();
    }
    void write(Path p,String s)throws Exception{Files.writeString(p,s);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));}
    void save()throws Exception{save(1);}
    void save(int version)throws Exception{write(inventory,JsonMapper.builder().build().writeValueAsString(Map.of("version",version,"clusterId",cluster,"servers",Map.of(server.toString(),entry))));}
    ControlMemberConfiguration.Prepared load(){return new ControlMemberConfiguration(settings).prepare(server,cluster,external,authority,profiles);}
    void rejected(){var failure=assertThrows(ControlFailure.class,this::load);assertEquals("PRIVATE_MEMBER_CONFIGURATION_REQUIRED",failure.code);assertFalse(failure.toString().contains("synthetic"));assertNull(failure.getCause());}
    @Test void exactAllocationAndApiPrefixAreRenderedWithoutPeerOrCredentialProperties(){var p=load();assertEquals(external,p.member());assertEquals("external",p.profileId());var values=p.properties();assertEquals("/api",values.get("app.cluster.authority-api-prefix"));assertEquals("2",values.get("app.site.allocation-slot"));assertEquals("true",values.get("app.control.enrollment-required"));assertEquals("false",values.get("app.account.synthetic-only"));assertEquals("false",values.get("app.sync.capture-enabled"));assertEquals("false",values.get("app.storage.files.gc.execute-enabled"));assertEquals("false",values.get("app.control.deployment.enabled"));assertTrue(values.keySet().stream().noneMatch(k->k.contains("peer")||k.endsWith("password")));assertEquals("/config/authority-auth.password",values.get("app.cluster.auth-store.password-file"));assertEquals("synthetic-auth-secret",p.authPassword());assertFalse(p.toString().contains("synthetic"));assertThrows(UnsupportedOperationException.class,()->values.put("x","y"));}
    @Test void enablingRuntimeKeepsAllSevenDomainsAndDisablesLegacyWorker(){var p=load().properties();for(String domain:List.of("activity","relation","profile","privacy","notification","catalog","files"))assertEquals("true",p.get("app.sync."+domain+".enabled"));assertEquals("false",p.get("app.cluster.coordinator"));assertEquals("false",p.get("app.cluster.control-lease.enabled"));assertEquals("false",p.get("app.storage.files.replication.worker-enabled"));}
    @Test void missingInventoryIsNotAnImplicitConfiguration(){settings.getServers().setMemberConfigurationsFile(null);rejected();}
    @Test void unknownServerIsRejected(){settings.getServers().setMemberConfigurationsFile(inventory);assertThrows(ControlFailure.class,()->new ControlMemberConfiguration(settings).prepare(UUID.randomUUID(),cluster,external,authority,profiles));}
    @Test void changedClusterOrProfileIdentityIsRejected(){assertThrows(ControlFailure.class,()->new ControlMemberConfiguration(settings).prepare(server,UUID.randomUUID(),external,authority,profiles));assertThrows(ControlFailure.class,()->new ControlMemberConfiguration(settings).prepare(server,cluster,new ClusterMember(external.siteId(),UUID.randomUUID(),"EXTERNAL",2),authority,profiles));}
    @Test void inventoryCannotCarryExtraOverrides()throws Exception{entry.put("properties",Map.of("app.control.deployment.enabled","true"));save();rejected();}
    @Test void malformedAndDuplicateJsonKeysAreRejected()throws Exception{write(inventory,"{\"version\":1,\"version\":1}");rejected();}
    @Test void worldReadablePrivateFilesAreRejected()throws Exception{for(Path p:List.of(inventory,password,token)){Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-r--r--"));rejected();Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));}}
    @Test void symlinksIncludingParentAliasesAreRejected()throws Exception{Path alias=directory.resolve("alias");Files.createSymbolicLink(alias,directory);settings.getServers().setMemberConfigurationsFile(alias.resolve("members.json"));rejected();}
    @Test void relativeSecretPathsAndMultilinePasswordsAreRejected()throws Exception{entry.put("authPasswordFile","auth.password");save();rejected();entry.put("authPasswordFile",password.toString());save();write(password,"first\nsecond");rejected();}
    @Test void jdbcOriginCannotSmuggleCredentialsOptionsOrAlternateDatabase()throws Exception{for(String value:List.of("jdbc:postgresql://u:p@db.invalid/eatwhat","jdbc:postgresql://db.invalid/eatwhat?sslmode=disable","jdbc:postgresql://db.invalid/eatwhat#x","jdbc:postgresql://db.invalid/../../other","jdbc:postgresql://db.invalid:65536/eatwhat","jdbc:postgresql://db.invalid:0/eatwhat")){entry.put("authJdbcOrigin",value);save();rejected();}}
    @Test void remoteDatabaseRequiresARealCaCertificate()throws Exception{entry.put("authJdbcOrigin","jdbc:postgresql://auth.example.invalid:5432/eatwhat");save();rejected();Path ca=directory.resolve("ca.pem");write(ca,"not a certificate");entry.put("authCaFile",ca.toString());save();rejected();}
    @Test void tlsTerminationMustBeExplicitAndCannotBypassCaOnLoopback()throws Exception{
        entry.put("authTransport","TLS_TERMINATED");save();rejected(); // v1 has no expanded semantics
        save(2);rejected(); // v2 still requires a real CA for a loopback relay
        for(String mode:List.of("", "disable", "tls_terminated", "TLS_TERMINATED&sslmode=disable")){entry.put("authTransport",mode);save(2);rejected();}
        entry.put("authTransport","DIRECT");save(2);assertFalse(load().privatePayload().containsKey("authTransport"));
        assertEquals("jdbc:postgresql://127.0.0.1:5432/eatwhat?connectTimeout=3&socketTimeout=5",load().properties().get("app.cluster.auth-store.jdbc-url"));
    }
    @Test void validPrivateCaCreatesBoundedVerifiedRemoteConnectionWithoutCopyingItsPath()throws Exception{
        Path keytool=Path.of(System.getProperty("java.home"),"bin/keytool"),keystore=directory.resolve("synthetic-ca.p12"),ca=directory.resolve("ca.pem");
        var generate=new ProcessBuilder(keytool.toString(),"-genkeypair","-alias","ca","-keyalg","EC","-groupname","secp256r1","-dname","CN=Synthetic Enrollment Test CA","-ext","bc:c","-validity","1","-storetype","PKCS12","-storepass","synthetic-test-only","-keystore",keystore.toString(),"-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertTrue(generate.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,generate.exitValue());
        var export=new ProcessBuilder(keytool.toString(),"-exportcert","-rfc","-alias","ca","-storepass","synthetic-test-only","-keystore",keystore.toString(),"-file",ca.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertTrue(export.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,export.exitValue());Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-------"));
        entry.put("authCaFile",ca.toString());entry.put("authJdbcOrigin","jdbc:postgresql://auth.example.invalid:5432/eatwhat");save();var value=load();String url=value.properties().get("app.cluster.auth-store.jdbc-url");assertEquals("jdbc:postgresql://auth.example.invalid:5432/eatwhat?connectTimeout=3&socketTimeout=5&sslmode=verify-full&sslrootcert=/config/authority-ca.pem",url);assertFalse(value.properties().toString().contains(directory.toString()));assertTrue(value.authCaPem().contains("BEGIN CERTIFICATE"));assertEquals("/config/authority-ca.pem",value.properties().get("app.cluster.node-ca-file"));assertEquals("/config/authority-ca.pem",value.properties().get("app.control.node-ca-file"));
        entry.put("authTransport","TLS_TERMINATED");
        for(String host:List.of("127.0.0.1","auth.example.invalid")){
            entry.put("authJdbcOrigin","jdbc:postgresql://"+host+":25443/eatwhat");save(2);var segmented=load();
            assertEquals("TLS_TERMINATED",segmented.privatePayload().get("authTransport"));
            assertEquals("jdbc:postgresql://"+host+":25443/eatwhat?connectTimeout=3&socketTimeout=5&sslmode=verify-full&sslrootcert=/config/authority-ca.pem&channelBinding=disable",segmented.properties().get("app.cluster.auth-store.jdbc-url"));
            assertEquals(value.authCaPem(),segmented.authCaPem());
        }
        write(ca,value.authCaPem()+"\n-----BEGIN PRIVATE KEY-----\nnot-for-transfer\n-----END PRIVATE KEY-----\n");rejected();
    }
    @Test void administratorOrSqlRoleSyntaxIsNotAcceptedInUsername()throws Exception{entry.put("authUsername","auth; GRANT ALL");save();rejected();}
    @Test void wrongAuthorityRoleOrTargetRoleCannotBeRendered(){assertThrows(ControlFailure.class,()->ControlMemberConfiguration.runtime(cluster,authority,authority,"https://campus.example.invalid","/api","jdbc:postgresql://127.0.0.1/eatwhat","auth"));assertThrows(ControlFailure.class,()->ControlMemberConfiguration.runtime(cluster,external,external,"https://campus.example.invalid","/api","jdbc:postgresql://127.0.0.1/eatwhat","auth"));}
}
