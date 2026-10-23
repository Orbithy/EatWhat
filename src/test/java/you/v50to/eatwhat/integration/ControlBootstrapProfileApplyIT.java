package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import you.v50to.eatwhat.support.SourceMaterialFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Real PostgreSQL authority/admin/lease locks, sealed originals, source file
 * capture, local private files and catalog cache. Source/target installation,
 * OS process, SSH and remote captures are explicit fixtures. This is not a
 * Linux/two-running-controller bootstrap or genuine member JOIN acceptance. */
class ControlBootstrapProfileApplyIT {
    @TempDir static Path pki;@TempDir Path root;static String ca;
    static final JsonMapper JSON=JsonMapper.builder().build();
    ControlBootstrapAdmissionIT base;
    @BeforeAll static void start()throws Exception{
        ControlBootstrapAdmissionIT.PG.start();Path keytool=Path.of(System.getProperty("java.home"),"bin/keytool"),store=pki.resolve("ca.p12"),cert=pki.resolve("ca.pem");
        for(var args:List.of(List.of("-genkeypair","-alias","ca","-keyalg","EC","-groupname","secp256r1","-dname","CN=Profile Apply Fixture CA","-ext","bc:c","-validity","2","-storetype","PKCS12","-keystore",store.toString(),"-storepass","fixture-only-password","-noprompt"),List.of("-exportcert","-rfc","-alias","ca","-keystore",store.toString(),"-storepass","fixture-only-password","-file",cert.toString()))){
            var cmd=new ArrayList<>(List.of(keytool.toString()));cmd.addAll(args);var p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertTrue(p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue());
        }ca=Files.readString(cert);
    }
    @AfterAll static void stop(){ControlBootstrapAdmissionIT.PG.stop();}
    @BeforeEach void setup()throws Exception{base=new ControlBootstrapAdmissionIT();base.root=root;base.setup();}
    @AfterEach void close(){if(base!=null)base.close();}
    class Case {
        final ControlBootstrapProfileState.Role role;final ControlBootstrapAdmission.Frozen frozen;final ControlBootstrapProfilePlans.Pair pair;
        final ControlBootstrapProfileState state;final ControlBootstrapProfileState.Accepted original;final ClusterProfiles catalog;final ControlBootstrapProfileReceiver receiver;
        final ControlBootstrapNativePhases nativePhases;
        final Map<String,Object> nativeBaseline;
        org.mockito.stubbing.Answer<Object> bridgeCall=RETURNS_DEFAULTS;
        final ClusterControlLease standby,sourceStandby;final ControlBootstrapAdmission localAdmission;final ClusterProperties receiverSettings;final Map<String,String> sourceProperties;final Map<String,Object> sourceProcess;
        Case(ControlBootstrapProfileState.Role role)throws Exception{this(role,true);}
        Case(ControlBootstrapProfileState.Role role,boolean autoClaim)throws Exception{this(role,autoClaim,false);}
        Case(ControlBootstrapProfileState.Role role,boolean autoClaim,boolean standbySource)throws Exception{
            this.role=role;Path control=base.root;Files.createDirectory(control.resolve("config"));Files.setPosixFilePermissions(control.resolve("config"),java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            sourceStandby=standbySource?new ClusterControlLease(base.db.runtime(),base.site.getClusterId(),120,true,false):null;
            if(sourceStandby!=null){new ClusterControlHandoff(base.db.runtime(),base.db.transactions(),sourceStandby,base.deployment,"fixture-source");assertFalse(sourceStandby.tick());}
            var template=new SourceMaterialFixture(Files.createDirectory(control.resolve("template")));sourceProperties=new TreeMap<>(template.properties);
            sourceProperties.putAll(Map.of("app.site.cluster-id",base.site.getClusterId().toString(),"app.site.id",base.site.getId(),"app.site.instance-id",base.site.getInstanceId().toString(),
                    "app.control.state-directory",base.store.directory().toString(),"app.cluster.profiles-file",control.resolve("config/cluster-profiles.json").toString(),
                    "app.control.node-token-file",control.resolve("config/authority.token").toString(),"app.cluster.control-lease.controller-name",standbySource?"fixture-source":"fixture-primary"));
            sourceProperties.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:5432/eatwhat");
            sourceProperties.put("app.control.controller-bootstrap.enabled","true");
            byte[] app=ControlBusinessRuntime.render(sourceProperties).getBytes(StandardCharsets.US_ASCII);
            String targetName="control-"+base.targetId.toString().replace("-","").substring(0,24);var targetProperties=new TreeMap<>(sourceProperties);targetProperties.put("app.cluster.control-lease.controller-name",targetName);
            var old=new LinkedHashMap<String,Object>(Map.of("siteId",base.site.getId(),"instanceId",base.site.getInstanceId().toString(),"mode","CAMPUS","allocationSlot",0,
                    "origin","http://127.0.0.1:18090","tokenFile",control.resolve("config/authority.token").toString()));
            byte[] before=JSON.writeValueAsBytes(Map.of("campus",old));var targetOld=new LinkedHashMap<>(old);targetOld.put("origin","http://127.0.0.1:28090");byte[] targetBefore=JSON.writeValueAsBytes(Map.of("campus",targetOld));
            SourceMaterialFixture.write(control.resolve("config/authority.token"),"t".repeat(43).getBytes(StandardCharsets.US_ASCII));
            SourceMaterialFixture.write(control.resolve("config/cluster-profiles.json"),before);SourceMaterialFixture.write(control.resolve("config/application.properties"),app);
            var expected=new ControllerSourceSnapshot.Expected(base.site.getClusterId(),base.site.getId(),base.site.getInstanceId(),base.fingerprint,base.deployment.stateIdentity(),1,sourceProperties.get("app.cluster.control-lease.controller-name"),
                    ControllerSourceSnapshot.sha(app),ControllerSourceSnapshot.sha(before),ControllerSourceSnapshot.sha(Files.readAllBytes(base.store.directory().resolve("servers.key"))));
            var capture=ControllerSourceSnapshot.capture(control,expected);
            base.sourceRequest=new ControlBootstrapSources.Request(base.acceptedArtifacts.controller().sha256(),base.acceptedArtifacts.controller().sha256(),"c".repeat(64),expected);
            sourceProcess=Map.of("pid",ProcessHandle.current().pid(),"startTicks","1234","argvSha256","e".repeat(64),"environmentSha256","f".repeat(64));
            base.sourceCaptured=new ControlBootstrapSources.Captured(base.sourceId,1,base.ssh.routeBinding(base.store.server(base.sourceId)),Map.of("captureBinding","d".repeat(64),"fileHashes",capture.hashes(),"sourceProcess",sourceProcess,
                    "proof",Map.of("generation",1L,"epoch",1L,"holderId",(sourceStandby==null?base.lease:sourceStandby).controllerId().toString())),Map.of("properties",capture.properties(),"privateFiles",capture.privateFiles()));
            when(base.sources.capture(base.sourceId,1,base.sourceRequest)).thenReturn(base.sourceCaptured);when(base.sources.observeExecuting(base.sourceId,1,base.sourceRequest)).thenReturn(base.sourceCaptured);
            base.sourceInitial=base.initial("SOURCE");base.targetInitial=base.initial("TARGET");base.cluster.getControlLease().setControllerName("fixture-primary");
            standby=new ClusterControlLease(base.db.runtime(),base.site.getClusterId(),120,true,false);new ClusterControlHandoff(base.db.runtime(),base.db.transactions(),standby,base.deployment,targetName);assertFalse(standby.tick());
            // The production runtime renews process presence independently of
            // artifact hashing. Keep that real heartbeat during this expensive
            // fixture setup too; a lease tick alone does not keep the five-second
            // control_process witness alive until the final admission fence.
            try(var heartbeat=new SetupHeartbeat(base.lease,standby,sourceStandby)){
            frozen=base.accepted();base.memberConfigurationClaimed(frozen,Map.of("holderId",standby.controllerId().toString(),"controllerName",targetName));
            var member=ControlBootstrapBusinessAllocation.identity(base.spec);var sourcePlan=ClusterProfilePublication.create(base.spec.id(),base.targetId,member,"10.253.74.3",control.resolve("config/cluster-profiles.json"),before,capture.hashes(),"x".repeat(64),ca);
            var targetHashes=new TreeMap<>(capture.hashes());targetHashes.put("cluster-profiles.json",ControllerSourceSnapshot.sha(targetBefore));
            var targetPlan=ClusterProfilePublication.create(base.spec.id(),base.targetId,member,"10.253.74.3",control.resolve("config/cluster-profiles.json"),targetBefore,targetHashes,"x".repeat(64),ca);
            var afterHashes=new TreeMap<>(capture.hashes());sourcePlan.afterManifest().forEach((p,h)->afterHashes.put(Path.of(p).getFileName().toString(),h));
            var afterExpected=new ControllerSourceSnapshot.Expected(expected.clusterId(),expected.authoritySiteId(),expected.authorityInstanceId(),expected.authorityDatabaseFingerprint(),expected.stateId(),expected.epoch(),expected.controllerName(),expected.applicationSha256(),sourcePlan.afterManifest().get(sourcePlan.profilesFile()),expected.sharedKeySha256());
            var afterRequest=new ControlBootstrapSources.Request(base.sourceRequest.sourceArtifactSha256(),base.sourceRequest.probeArtifactSha256(),base.sourceRequest.unitSha256(),afterExpected);
            pair=new ControlBootstrapProfilePlans.Pair(sourcePlan,targetPlan,afterHashes,afterRequest);
            var plans=mock(ControlBootstrapProfilePlans.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));when(plans.prepare(frozen)).thenReturn(pair);
            String approval=base.journal.job(base.spec.id()).get("requestHash").toString();
            if(role==ControlBootstrapProfileState.Role.TARGET){
                SourceMaterialFixture.write(control.resolve("config/cluster-profiles.json"),targetBefore);SourceMaterialFixture.write(control.resolve("config/application.properties"),ControlBusinessRuntime.render(targetProperties).getBytes(StandardCharsets.US_ASCII));
                nativeBaseline=servicesBaseline(control,targetName,approval);
            }else nativeBaseline=Map.of();
            var input=new ControlBootstrapNativeInputs.Sealed(base.spec.id(),CONTROLLER_CONFIG,approval,base.targetId,1,"fixture",Map.of("bundle",Map.of("properties",targetProperties)),"fixture","e".repeat(64));
            var businessInput=new ControlBootstrapNativeInputs.Sealed(base.spec.id(),BUSINESS_INSTALL,approval,base.targetId,1,"fixture",Map.of("id",ControlBootstrapJournal.phaseId(base.spec.id(),BUSINESS_INSTALL).toString(),"expectedServicesBaselineSha256",nativeBaseline.isEmpty()?"f".repeat(64):nativeDigest(nativeBaseline),"controllerBinding","c".repeat(64)),"fixture","d".repeat(64));
            nativePhases=mock(ControlBootstrapNativePhases.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).defaultAnswer(inv->switch(inv.getMethod().getName()){
                case "saved" -> inv.getArgument(1)==BUSINESS_INSTALL?businessInput:input;case "call" -> bridgeCall.answer(inv);default -> RETURNS_DEFAULTS.answer(inv);
            }));
            state=new ControlBootstrapProfileState(base.store,base.journal,base.admission,plans,nativePhases);original=state.ensure(frozen);
            if(autoClaim)assertTrue(state.claim(frozen,ControlBootstrapProfileState.Role.SOURCE).dispatchAllowed());
            when(base.sources.observeExecuting(base.sourceId,1,pair.sourceCaptureRequest())).thenReturn(postCapture(pair.sourceCaptureFileHashes(),sourceProcess));
            receiverSettings=new ClusterProperties();receiverSettings.setEnabled(true);receiverSettings.setCoordinator(true);receiverSettings.getControlLease().setEnabled(true);receiverSettings.getControlLease().setControllerName(role==ControlBootstrapProfileState.Role.SOURCE?sourceProperties.get("app.cluster.control-lease.controller-name"):targetName);
            receiverSettings.setProfilesFile(control.resolve("config/cluster-profiles.json"));
            ControlBootstrapAdmission receiverAdmission=base.admission;ClusterControlLease receiverLease=base.lease;
            if(role==ControlBootstrapProfileState.Role.TARGET){
                // Explicit remote SOURCE success fixture; TARGET uses a real
                // standby process and actual local file/cache/SQL application.
                state.observe(original,ControlBootstrapProfileState.Role.SOURCE,sourceReceipt());assertTrue(state.claim(frozen,role).dispatchAllowed());
                receiverAdmission=new ControlBootstrapAdmission(base.settings,base.site,receiverSettings,base.db.runtime(),base.db.transactions(),standby,base.deployment,base.store,base.servers,base.ssh,base.artifacts,base.preflights,base.sources);receiverLease=standby;
            }else if(sourceStandby!=null){
                receiverAdmission=new ControlBootstrapAdmission(base.settings,base.site,receiverSettings,base.db.runtime(),base.db.transactions(),sourceStandby,base.deployment,base.store,base.servers,base.ssh,base.artifacts,base.preflights,base.sources);receiverLease=sourceStandby;
            }
            localAdmission=receiverAdmission;
            catalog=new ClusterProfiles(receiverSettings);var beans=new StaticListableBeanFactory(Map.of("catalog",catalog));
            receiver=new ControlBootstrapProfileReceiver(base.store,state,receiverAdmission,receiverLease,receiverSettings,beans.getBeanProvider(ClusterProfiles.class),control);
            assertTrue(base.lease.tick());assertFalse(standby.tick());if(sourceStandby!=null)assertFalse(sourceStandby.tick());
            }
        }
        ControlBootstrapSources.Captured postCapture(Map<String,String> hashes,Map<String,Object> process){return new ControlBootstrapSources.Captured(base.sourceId,1,base.sourceCaptured.routeBinding(),Map.of("fileHashes",hashes,"sourceProcess",process),base.sourceCaptured.privatePayload());}
        Map<String,Object> sourceReceipt(){
            var fields=Map.<String,Object>ofEntries(Map.entry("protocol","eatwhat-controller-profile-publication-v1"),Map.entry("bootstrapId",base.spec.id().toString()),Map.entry("role","SOURCE"),Map.entry("binding",original.binding()),
                    Map.entry("planBinding",pair.source().binding()),Map.entry("controllerId",original.sourceControllerId().toString()),Map.entry("controllerName",sourceProperties.get("app.cluster.control-lease.controller-name")),Map.entry("applicationSha256",frozen.sourceRequest().expected().applicationSha256()),
                    Map.entry("state","RUNTIME_RELOADED"),Map.entry("observedBinding",pair.source().afterBinding()),Map.entry("joinAuthorized",false));
            return ReflectionTestUtils.invokeMethod(ControlBootstrapProfileState.class,"sign",base.store,fields);
        }
        Map<String,Object> apply(){assertTrue(base.lease.tick());assertFalse(standby.tick());if(sourceStandby!=null)assertFalse(sourceStandby.tick());return receiver.apply(base.spec.id(),role,original.binding());}
        void observe(Map<String,Object> receipt){state.observe(original,role,receipt);}
    }
    static final class SetupHeartbeat implements AutoCloseable {
        final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        SetupHeartbeat(ClusterControlLease... leases){
            worker.scheduleWithFixedDelay(()->{try{for(var lease:leases)if(lease!=null)lease.tick();}catch(Throwable unavailable){failure.compareAndSet(null,unavailable);}},0,250,java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        @Override public void close()throws InterruptedException{
            worker.shutdown();assertTrue(worker.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
            if(failure.get()!=null)throw new AssertionError("Fixture process heartbeat failed",failure.get());
        }
    }
    static String nativeDigest(Object value)throws Exception{return ReflectionTestUtils.invokeMethod(Class.forName("you.v50to.eatwhat.control.ControlBootstrapNativeJson"),"digest",value);}
    Map<String,Object> servicesBaseline(Path control,String name,String approval)throws Exception{
        var facts=new TreeMap<String,Object>();var entries=new ArrayList<String>();
        try(var files=Files.list(control.resolve("config"))){for(Path path:files.sorted().toList()){
            entries.add(path.getFileName().toString());facts.put(path.toString(),Map.of("uid",Files.getAttribute(path,"unix:uid"),"gid",Files.getAttribute(path,"unix:gid"),"mode",((Number)Files.getAttribute(path,"unix:mode")).intValue()&0777,
                    "size",Files.size(path),"sha256",ControllerSourceSnapshot.sha(Files.readAllBytes(path))));
        }}
        var directory=control.resolve("config");var directoryFact=Map.of("uid",Files.getAttribute(directory,"unix:uid"),"gid",Files.getAttribute(directory,"unix:gid"),"mode",0700,"entries",entries);
        return Map.of("protocol","eatwhat-business-controller-services-v1","controllerBinding","c".repeat(64),"approvalDigest",approval,
            "plan",Map.of("files",new ArrayList<>(facts.keySet()),"directories",List.of(directory.toString()),"units",List.of(),"listeners",Map.of(),"inactiveUnits",List.of(),"symlinks",Map.of(),"absent",List.of()),
            "observation",Map.of("files",facts,"directories",Map.of(directory.toString(),directoryFact),"units",Map.of()),
            "probes",Map.of("targetServerId",base.targetId.toString(),"ownerUid",Files.getAttribute(directory,"unix:uid"),"ownerGid",Files.getAttribute(directory,"unix:gid"),"nodeTokenFile",directory.resolve("authority.token").toString(),"database",Map.of("controllerName",name)));
    }
    @Test void sourceAppliesWithRealAuthorizationAndExactPostCaptureWithoutJoining()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);var result=c.apply();c.observe(result);assertEquals("RUNTIME_RELOADED",result.get("state"));
        var actual=ControllerSourceSnapshot.capture(base.root,c.pair.sourceCaptureRequest().expected());assertEquals(c.pair.sourceCaptureFileHashes(),actual.hashes());
        assertEquals(Map.of("SOURCE","SUCCEEDED","TARGET","PENDING"),c.state.progress(c.original));assertEquals(1L,base.db.runtime().queryForObject("SELECT epoch FROM eatwhat_cluster.state WHERE singleton",Long.class));
        assertEquals(1L,base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.member",Long.class));assertEquals(false,result.get("joinAuthorized"));
        assertTrue(c.state.claim(c.frozen,ControlBootstrapProfileState.Role.TARGET).dispatchAllowed());
    }
    @Test void standbyAppliesOnlyTheOriginalHoldersTaskWithoutBecomingLeader()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.TARGET);assertEquals(false,c.standby.status().get("localLeader"));var result=c.apply();c.observe(result);
        assertEquals("RUNTIME_RELOADED",result.get("state"));assertEquals(false,c.standby.status().get("localLeader"));assertEquals(true,base.lease.status().get("localLeader"));
        assertEquals(c.original.targetControllerId().toString(),result.get("controllerId"));assertEquals(Map.of("SOURCE","SUCCEEDED","TARGET","SUCCEEDED"),c.state.progress(c.original));
    }
    @Test void actualStandbyPublicationProducesNativeReadableWitnessAndFreshAuthenticatedStatus()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.TARGET);c.observe(c.apply());
        Path script=Path.of("src/main/resources/control/controller-bootstrap-business-services.py").toAbsolutePath();
        var nodeSettings=new ControlProperties();nodeSettings.setEnvironmentKind("managed-deployment");nodeSettings.setNodeTokenFile(Path.of(c.original.bridge().targetTokenFile()));
        try(var node=new ControlNode(nodeSettings,base.db.runtime(),base.site,new ControlAudit(base.store.directory()))){
            var controller=new ControlBootstrapProfileNodeController(node,c.receiver);var requests=new java.util.concurrent.atomic.AtomicInteger();
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/services-status",exchange->{try{
                requests.incrementAndGet();assertEquals("POST",exchange.getRequestMethod());var frame=JSON.readValue(exchange.getRequestBody().readAllBytes(),Map.class);
                byte[] raw=JSON.writeValueAsBytes(controller.servicesStatus(exchange.getRequestHeaders().getFirst("X-Control-Node-Token"),frame));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);
            }catch(Throwable failure){exchange.sendResponseHeaders(503,-1);}finally{exchange.close();}});server.start();
            try{
                String program="""
                    import json,pathlib,sys,types
                    data=json.load(sys.stdin);module=types.ModuleType('actual_java_profile_services')
                    exec(compile(pathlib.Path(data['script']).read_bytes(),data['script'],'exec'),module.__dict__)
                    module.CONTROL=pathlib.Path(data['control']);module.PROFILE_ENDPOINT=data['endpoint']
                    # Explicit non-Linux unit/network/NFS probes fixture. Real
                    # files, HMAC, HTTP and actual approved Java receiver remain.
                    module.listeners=lambda:[]
                    module.fresh_probes=lambda _: {'nativeServiceProbesFixture':True}
                    proof=module.verify(data['request'],data['baseline'])
                    assert proof['controllerProfilesSuccessorVerified'] is True and proof['joinAuthorized'] is False
                    assert proof['baselineSha256']==module.digest(data['baseline'])
                    print(json.dumps({'verified':True,'profilePlanBinding':proof['profilePlanBinding']}))
                    """;
                var request=Map.of("serverId",base.targetId.toString(),"jobId",c.original.services().businessJobId().toString(),"inputBinding",c.original.services().businessBinding(),"controllerBinding",c.original.services().controllerBinding(),"approvalDigest",c.original.approvalDigest());
                var process=new ProcessBuilder("/usr/bin/python3","-I","-c",program).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                try(var input=process.getOutputStream()){input.write(JSON.writeValueAsBytes(Map.of("script",script.toString(),"control",base.root.toString(),"endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/services-status","request",request,"baseline",c.nativeBaseline)));}
                assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
                var result=JSON.readValue(process.getInputStream().readAllBytes(),Map.class);assertEquals(true,result.get("verified"));assertEquals(c.pair.target().binding(),result.get("profilePlanBinding"));assertEquals(1,requests.get());
            }finally{server.stop(0);}
        }
        assertEquals(false,c.standby.status().get("localLeader"));assertEquals(1L,base.db.runtime().queryForObject("SELECT epoch FROM eatwhat_cluster.state WHERE singleton",Long.class));
    }
    @Test void lostOrChangedTargetServicesWitnessCannotBeRecreatedByStatusOrApply()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.TARGET);c.apply();Path file=base.root.resolve("config/profile-publications").resolve(base.targetId.toString()).resolve("services-successor.json");
        assertTrue(Files.exists(file));byte[] original=Files.readAllBytes(file);Files.writeString(file,"changed");
        assertEquals("BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED",assertThrows(ControlFailure.class,()->c.receiver.observe(base.spec.id(),c.role,c.original.binding())).code);
        Files.write(file,original);assertEquals("RUNTIME_RELOADED",c.receiver.observe(base.spec.id(),c.role,c.original.binding()).get("state"));Files.delete(file);
        for(var action:List.<Runnable>of(()->c.receiver.observe(base.spec.id(),c.role,c.original.binding()),()->c.receiver.apply(base.spec.id(),c.role,c.original.binding())))
            assertEquals("BOOTSTRAP_PROFILE_SERVICES_RECORD_MISSING",assertThrows(ControlFailure.class,action::run).code);
        assertFalse(Files.exists(file));
    }
    @Test void publicationHonorsTheActualAppendOnlyReservationPrivileges()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);
        assertTrue(base.db.runtime().queryForObject("SELECT has_table_privilege(current_user,'eatwhat_cluster.installation_allocation','SELECT')",Boolean.class));
        assertTrue(base.db.runtime().queryForObject("SELECT has_table_privilege(current_user,'eatwhat_cluster.installation_allocation','INSERT')",Boolean.class));
        assertFalse(base.db.runtime().queryForObject("SELECT has_table_privilege(current_user,'eatwhat_cluster.installation_allocation','UPDATE')",Boolean.class));
        assertFalse(base.db.runtime().queryForObject("SELECT has_table_privilege(current_user,'eatwhat_cluster.installation_allocation','DELETE')",Boolean.class));
        var denied=assertThrows(DataAccessException.class,()->base.db.runtime().queryForList("SELECT server_id FROM eatwhat_cluster.installation_allocation WHERE server_id=? FOR SHARE",base.targetId));
        assertInstanceOf(java.sql.SQLException.class,denied.getMostSpecificCause());assertEquals("42501",((java.sql.SQLException)denied.getMostSpecificCause()).getSQLState());
        assertEquals("RUNTIME_RELOADED",c.apply().get("state"));
        var retained=assertThrows(DataAccessException.class,()->base.db.admin().update("DELETE FROM eatwhat_cluster.installation_allocation WHERE server_id=?",base.targetId));
        assertInstanceOf(java.sql.SQLException.class,retained.getMostSpecificCause());assertEquals("P0001",((java.sql.SQLException)retained.getMostSpecificCause()).getSQLState());
        assertEquals(1L,base.db.runtime().queryForObject("SELECT count(*) FROM eatwhat_cluster.installation_allocation WHERE server_id=?",Long.class,base.targetId));
    }
    @Test void bridgeLostCommittedAcknowledgementOnlyObservesTheOriginalBytesAndClaim()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE,false);var sent=new ArrayList<String>();
        var driver=new ControlBootstrapProfilePhases(c.state,c.nativePhases);
        // SSH is an explicit fixture. The real journal, claimed original,
        // database authorization, file/cache publication and HMAC are exercised.
        c.bridgeCall=inv->{
            String agent=inv.getArgument(1);List<String> args=inv.getArgument(2);Map<String,Object> payload=inv.getArgument(3);
            assertEquals(c.original.bridge().agent(),agent);assertEquals(c.original.bridge().sha256(),ControllerSourceSnapshot.sha(agent.getBytes(StandardCharsets.UTF_8)));
            assertEquals(c.original.bridge().sourceTokenFile(),payload.get("tokenFile"));assertEquals(c.original.binding(),payload.get("binding"));
            // A retained JSON integer may deserialize as Integer instead of
            // the original PID Long. Compare the actual wire representation.
            assertEquals(JSON.readValue(JSON.writeValueAsBytes(c.sourceProcess),Map.class),payload.get("sourceProcess"));assertEquals(c.frozen.sourceRequest().sourceArtifactSha256(),payload.get("artifactSha256"));
            assertEquals(Set.of("id","role","binding","tokenFile","applicationSha256","artifactSha256","sourceProcess"),payload.keySet());sent.add(args.getFirst());
            assertEquals("CLAIMED",c.state.progress(c.original).get("SOURCE"));
            if(args.equals(List.of("apply"))){c.apply();throw new ControlFailure(503,"BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE");}
            return c.receiver.observe(base.spec.id(),c.role,c.original.binding());
        };
        assertThrows(ControlFailure.class,()->driver.advance(c.frozen,c.role));
        assertEquals(Map.of("SOURCE","CLAIMED","TARGET","PENDING"),c.state.progress(c.original));
        base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));
        var result=driver.advance(c.frozen,c.role);assertEquals(List.of("apply","status"),sent);
        assertEquals(false,result.get("joinAuthorized"));assertEquals(true,result.get("memberConfigurationPending"));
        assertEquals(Map.of("SOURCE","SUCCEEDED","TARGET","PENDING"),result.get("profileOperations"));
    }
    @Test void bridgeLostRequestBeforeEffectsNeverDispatchesAgainEvenWhenStatusIsUnknown()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE,false);var sent=new ArrayList<String>();var driver=new ControlBootstrapProfilePhases(c.state,c.nativePhases);
        c.bridgeCall=inv->{
            List<String> args=inv.getArgument(2);sent.add(args.getFirst());
            assertEquals("CLAIMED",c.state.progress(c.original).get("SOURCE"));
            if(args.equals(List.of("apply")))throw new ControlFailure(503,"BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE");
            return c.receiver.observe(base.spec.id(),c.role,c.original.binding());
        };
        assertThrows(ControlFailure.class,()->driver.advance(c.frozen,c.role));
        for(int i=0;i<2;i++)assertEquals(Map.of("SOURCE","CLAIMED","TARGET","PENDING"),driver.advance(c.frozen,c.role).get("profileOperations"));
        assertEquals(List.of("apply","status","status"),sent);assertFalse(Files.exists(base.root.resolve("config/profile-publications")));
        assertFalse(c.state.claim(c.frozen,c.role).dispatchAllowed());
    }
    @Test void restoringAdministratorFlagsCannotRestoreTheOldApproval()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,c::apply).code);assertFalse(Files.exists(base.root.resolve("config/profile-publications")));
        base.db.admin().update("UPDATE users SET banned=false WHERE id=?",Long.parseLong(base.actor));assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,c::apply).code);assertFalse(Files.exists(base.root.resolve("config/profile-publications")));
    }
    @Test void completedObservationIsReadonlyAfterAdministratorRevocation()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);var result=c.apply();c.observe(result);
        base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));assertEquals(result,c.receiver.observe(base.spec.id(),c.role,c.original.binding()));
        assertThrows(ControlFailure.class,()->c.state.claim(c.frozen,ControlBootstrapProfileState.Role.TARGET));
    }
    @Test void anAlreadyClaimedReceiverCannotBypassStickyReapproval()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.TARGET);ReflectionTestUtils.invokeMethod(base.journal,"controllerArtifactDrift",base.spec.id());
        assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,c::apply).code);assertFalse(Files.exists(base.root.resolve("config/profile-publications")));
    }
    @Test void aDifferentLeaseGenerationCannotApplyTheOriginalClaim()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);base.db.admin().update("UPDATE eatwhat_cluster.control_lease SET generation=generation+1 WHERE singleton");
        assertEquals("CONTROL_LEASE_NOT_HELD",assertThrows(ClusterFailure.class,c::apply).getMessage());assertFalse(Files.exists(base.root.resolve("config/profile-publications")));
    }
    @Test void realAuthorityAndUserLocksEncloseEffectWithoutReservationUpdatePrivilege()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);
        Supplier<Object> effect=()->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            for(String table:List.of("eatwhat_cluster.control_lease","eatwhat_cluster.state"))assertThrows(DataAccessException.class,()->base.db.admin().queryForList("SELECT singleton FROM "+table+" WHERE singleton FOR UPDATE NOWAIT"));
            assertThrows(DataAccessException.class,()->base.db.admin().queryForList("SELECT id FROM users WHERE id=? FOR UPDATE NOWAIT",Long.parseLong(base.actor)));
            // An immutable reservation needs no UPDATE-capable row lock.
            // The independent actor can inspect it, while authority/state
            // and administrator rows remain locked around the file effect.
            assertEquals(1,base.db.admin().queryForList("SELECT server_id FROM eatwhat_cluster.installation_allocation WHERE server_id=? FOR UPDATE NOWAIT",base.targetId).size());
            return c.catalog.publishAndReload(c.pair.source(),List.of(),Runnable::run);
        };
        var result=ReflectionTestUtils.invokeMethod(base.admission,"applyProfile",c.original,c.role,effect);assertNotNull(result);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());c.observe(c.receiver.observe(base.spec.id(),c.role,c.original.binding()));
    }
    @Test void lossOfBothPrivateRecordsCannotRecreateAnyDispatchPermission()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);Files.delete(base.store.directory().resolve("controller-profile-input-"+base.spec.id()+".json"));Files.delete(base.store.directory().resolve("controller-profile-operations-"+base.spec.id()+".json"));
        assertEquals("BOOTSTRAP_PROFILE_RECORD_MISSING",assertThrows(ControlFailure.class,()->c.state.ensure(c.frozen)).code);assertFalse(Files.exists(base.root.resolve("config/profile-publications")));
    }
    @Test void anOwnSuccessorMustMatchEveryFileAndTheOriginalSourceProcess()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);c.observe(c.apply());
        var hashes=new TreeMap<>(c.pair.sourceCaptureFileHashes());hashes.put("application.properties","0".repeat(64));when(base.sources.observeExecuting(base.sourceId,1,c.pair.sourceCaptureRequest())).thenReturn(c.postCapture(hashes,c.sourceProcess));
        assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",assertThrows(ControlFailure.class,()->c.state.claim(c.frozen,ControlBootstrapProfileState.Role.TARGET)).code);
        when(base.sources.observeExecuting(base.sourceId,1,c.pair.sourceCaptureRequest())).thenReturn(c.postCapture(c.pair.sourceCaptureFileHashes(),Map.of("pid",99999,"startTicks","9999")));
        assertThrows(ControlFailure.class,()->c.state.claim(c.frozen,ControlBootstrapProfileState.Role.TARGET));assertEquals("PENDING",c.state.progress(c.original).get("TARGET"));
    }
    @Test void changedProcessOrApplicationAndOuterTransactionsCannotStartAnEffect()throws Exception{
        var c=new Case(ControlBootstrapProfileState.Role.SOURCE);var old=Files.readAllBytes(base.root.resolve("config/application.properties"));SourceMaterialFixture.write(base.root.resolve("config/application.properties"),"changed".getBytes());
        assertThrows(ControlFailure.class,c::apply);SourceMaterialFixture.write(base.root.resolve("config/application.properties"),old);
        assertThrows(IllegalStateException.class,()->base.db.transactions().required(c::apply));assertFalse(Files.exists(base.root.resolve("config/profile-publications")));
        base.cluster.getControlLease().setControllerName("unrelated-controller");assertThrows(ControlFailure.class,c::apply);
    }
}
