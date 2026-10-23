package you.v50to.eatwhat.cluster;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.support.SourceMaterialFixture;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual private files, exclusive publication, cache reload and source file
 * captures. The fence callback is explicit test authorization; no real SQL,
 * remote JVM, SSH, Linux worker, HTTPS connection or JOIN is claimed. */
class ClusterProfilePublicationTest {
    @TempDir static Path pki;@TempDir Path root;static String ca;
    SourceMaterialFixture source;ControllerSourceSnapshot.Captured captured;ClusterProfiles profiles;ClusterProfilePublication.Plan plan;
    UUID bootstrap,server;ClusterMember member;final JsonMapper json=JsonMapper.builder().build();
    @BeforeAll static void certificates()throws Exception{
        Path keytool=Path.of(System.getProperty("java.home"),"bin/keytool"),store=pki.resolve("ca.p12"),cert=pki.resolve("ca.pem");
        for(var args:List.of(List.of("-genkeypair","-alias","ca","-keyalg","EC","-groupname","secp256r1","-dname","CN=Publication Test CA","-ext","bc:c","-validity","2","-storetype","PKCS12","-keystore",store.toString(),"-storepass","fixture-only-password","-noprompt"),
                List.of("-exportcert","-rfc","-alias","ca","-keystore",store.toString(),"-storepass","fixture-only-password","-file",cert.toString()))){
            var cmd=new ArrayList<>(List.of(keytool.toString()));cmd.addAll(args);var process=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        }ca=Files.readString(cert);
    }
    @BeforeEach void setup()throws Exception{
        source=new SourceMaterialFixture(Files.createDirectory(root.resolve("control")));captured=source.capture();
        var p=new ClusterProperties();p.setProfilesFile(source.config.resolve("cluster-profiles.json"));profiles=new ClusterProfiles(p);
        bootstrap=UUID.randomUUID();server=UUID.randomUUID();member=member(bootstrap);
        plan=create(bootstrap,captured.hashes(),"x".repeat(64),ca);
    }
    ClusterMember member(UUID id){return new ClusterMember("server-"+server.toString().replace("-","").substring(0,12),UUID.nameUUIDFromBytes((id+":BUSINESS_INSTANCE").getBytes(java.nio.charset.StandardCharsets.UTF_8)),"EXTERNAL",2);}
    ClusterProfilePublication.Plan create(UUID id,Map<String,String> hashes,String token,String pem)throws Exception{
        return ClusterProfilePublication.create(id,server,member(id),"10.253.74.3",source.config.resolve("cluster-profiles.json"),Files.readAllBytes(source.config.resolve("cluster-profiles.json")),hashes,token,pem);
    }
    Path task(){return source.config.resolve("profile-publications").resolve(server.toString());}
    void publish(){assertEquals("FILES_PUBLISHED",ClusterProfilePublication.publish(plan,Runnable::run).get("state"));}
    @Test void exactFilePublicationAndCacheReloadPreserveTheAuthorityAndStayUnjoined()throws Exception{
        var old=profiles.get("campus");assertEquals(plan.beforeBinding(),profiles.publicationBinding());byte[] app=Files.readAllBytes(source.config.resolve("application.properties"));
        publish();assertThrows(ClusterFailure.class,()->profiles.get(member.siteId()));assertEquals(plan.beforeBinding(),profiles.publicationBinding());
        var receipt=profiles.reloadPublication(plan,List.of(old.member()));assertEquals("RUNTIME_RELOADED",receipt.get("state"));assertEquals(false,receipt.get("joinAuthorized"));
        assertEquals("https://10.253.74.3:28490",profiles.get(member.siteId()).baseUri().toString());assertEquals(member,profiles.get(member.siteId()).member());assertNotNull(profiles.get(member.siteId()).trust());
        assertEquals(old.member(),profiles.get("campus").member());assertEquals(old.origin(),profiles.get("campus").origin());assertEquals(old.tokenFile(),profiles.get("campus").tokenFile());
        assertEquals(plan.afterBinding(),profiles.publicationBinding());assertArrayEquals(app,Files.readAllBytes(source.config.resolve("application.properties")));
        assertFalse(receipt.toString().contains("x".repeat(64)));assertFalse(profiles.list().toString().contains("10.253"));assertFalse(plan.toString().contains("PRIVATE"));
    }
    @Test void aFreshJvmCatalogAcknowledgesPublishedFilesWithoutASecondReloadOrWrite()throws Exception{
        publish();var settings=new ClusterProperties();settings.setProfilesFile(Path.of(plan.profilesFile()));var restarted=new ClusterProfiles(settings);
        var entry=restarted.get(member.siteId());restarted.reloadPublication(plan,List.of(profiles.get("campus").member()));assertSame(entry,restarted.get(member.siteId()));
        var initial=Files.readAllBytes(task().resolve("plan.json"));Files.delete(task().resolve("published.json"));
        assertEquals("FILES_PUBLISHED",ClusterProfilePublication.publish(plan,effect->fail("must not dispatch again")).get("state"));assertArrayEquals(initial,Files.readAllBytes(task().resolve("plan.json")));assertFalse(Files.exists(task().resolve("published.json")));
    }
    @Test void sourceCaptureAcceptsOnlyTheExactPlannedProfileSuccessor()throws Exception{
        byte[] app=Files.readAllBytes(source.config.resolve("application.properties"));publish();
        var expected=new ControllerSourceSnapshot.Expected(source.cluster,"campus",source.instance,"a".repeat(32),source.state,1,"fixture-source",ControllerSourceSnapshot.sha(app),
                plan.afterManifest().get(plan.profilesFile()),ControllerSourceSnapshot.sha(new byte[32]));
        var after=ControllerSourceSnapshot.capture(source.root,expected);for(var e:captured.hashes().entrySet())if(!e.getKey().equals("cluster-profiles.json"))assertEquals(e.getValue(),after.hashes().get(e.getKey()));
        assertEquals(captured.hashes().size()+2,after.hashes().size());assertEquals(plan.afterManifest().get(plan.profilesFile()),after.hashes().get("cluster-profiles.json"));
        var original=new ControllerSourceSnapshot.Expected(source.cluster,"campus",source.instance,"a".repeat(32),source.state,1,"fixture-source",ControllerSourceSnapshot.sha(app),captured.hashes().get("cluster-profiles.json"),ControllerSourceSnapshot.sha(new byte[32]));
        assertThrows(ControllerSourceSnapshot.SourceConfigurationChanged.class,()->ControllerSourceSnapshot.capture(source.root,original));
    }
    @Test void changedOldTokenOrUnreviewedCacheCannotBeAdopted()throws Exception{
        var before=profiles.get("campus");SourceMaterialFixture.write(source.config.resolve("authority.token"),"q".repeat(64).getBytes());
        assertThrows(ClusterFailure.class,()->ClusterProfilePublication.publish(plan,Runnable::run));assertSame(before,profiles.get("campus"));assertFalse(Files.exists(task()));
    }
    @Test void failedReloadPreservesTheActualOldClientsAndRequiresExactFiles()throws Exception{
        var old=profiles.get("campus");publish();var caFile=Path.of(plan.additionsBase64().keySet().stream().filter(p->p.endsWith(".pem")).findFirst().orElseThrow());
        SourceMaterialFixture.write(caFile,(ca+"changed").getBytes());assertThrows(ClusterFailure.class,()->profiles.reloadPublication(plan,List.of(old.member())));
        assertSame(old,profiles.get("campus"));assertEquals(plan.beforeBinding(),profiles.publicationBinding());assertThrows(ClusterFailure.class,()->profiles.get(member.siteId()));
    }
    @Test void missingOrPartialPublicationIsUnknownAndNeverRepaired()throws Exception{
        publish();Path added=Path.of(plan.additionsBase64().keySet().stream().filter(p->p.endsWith(".token")).findFirst().orElseThrow());Files.delete(added);
        var bytes=Files.readAllBytes(task().resolve("intent.json"));assertEquals("OUTCOME_UNKNOWN",ClusterProfilePublication.publish(plan,effect->fail("must not retry")).get("state"));
        assertFalse(Files.exists(added));assertArrayEquals(bytes,Files.readAllBytes(task().resolve("intent.json")));assertThrows(ClusterFailure.class,()->profiles.reloadPublication(plan,List.of()));
    }
    @Test void newBootstrapCannotBypassAnExistingTargetPublication()throws Exception{
        var other=create(UUID.randomUUID(),captured.hashes(),"y".repeat(64),ca);publish();
        assertThrows(ClusterFailure.class,()->ClusterProfilePublication.publish(other,effect->fail("must not replace retained publication")));
        assertArrayEquals(Base64.getDecoder().decode(plan.afterBase64()),Files.readAllBytes(Path.of(plan.profilesFile())));
    }
    @Test void refusalInsideTheActualFenceLeavesNoPublicationEffect()throws Exception{
        AtomicInteger calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->ClusterProfilePublication.publish(plan,effect->{calls.incrementAndGet();assertFalse(Files.exists(task().getParent()));throw new IllegalStateException("denied fixture fence");}));
        assertEquals(1,calls.get());assertFalse(Files.exists(task()));assertArrayEquals(Base64.getDecoder().decode(plan.beforeBase64()),Files.readAllBytes(Path.of(plan.profilesFile())));
    }
    @Test void publicationRequiresAPrivateCaExactIdentityAndCapturedHashes()throws Exception{
        assertThrows(ClusterFailure.class,()->create(bootstrap,captured.hashes(),"x".repeat(64),"not a CA"));
        assertThrows(ClusterFailure.class,()->create(bootstrap,Map.of(),"x".repeat(64),ca));
        var changed=new ClusterProfilePublication.Plan(plan.bootstrapId(),plan.serverId(),plan.member(),plan.profilesFile(),plan.beforeBase64(),plan.afterBase64(),plan.beforeManifest(),plan.afterManifest(),plan.additionsBase64(),"a".repeat(64));
        assertThrows(ClusterFailure.class,()->ClusterProfilePublication.publish(changed,Runnable::run));
    }
    @Test void existingAdditionFileAndSymlinkAreNeverOverwritten()throws Exception{
        Path addition=Path.of(plan.additionsBase64().keySet().iterator().next());SourceMaterialFixture.write(addition,"existing".getBytes());assertThrows(ClusterFailure.class,()->ClusterProfilePublication.publish(plan,Runnable::run));assertEquals("existing",Files.readString(addition));
        Files.delete(addition);Files.createSymbolicLink(addition,source.config.resolve("authority.token"));assertThrows(ClusterFailure.class,()->ClusterProfilePublication.publish(plan,Runnable::run));assertTrue(Files.isSymbolicLink(addition));
    }
    @Test void serviceOwnedConfigCanPublishWithoutWritePermissionToApplicationHome()throws Exception{
        var original=Files.getPosixFilePermissions(source.root);
        try{
            Files.setPosixFilePermissions(source.root,PosixFilePermissions.fromString("r-xr-x---"));
            assertEquals("RUNTIME_RELOADED",profiles.publishAndReload(plan,List.of(),Runnable::run).get("state"));
            assertTrue(Files.isRegularFile(task().resolve("intent.json")));
            assertFalse(Files.exists(source.root.resolve("profile-publications")));
            assertEquals(PosixFilePermissions.fromString("r-xr-x---"),Files.getPosixFilePermissions(source.root));
        }finally{Files.setPosixFilePermissions(source.root,original);}
    }
    @Test void oldLayoutRetainedIntentCannotBeBypassedByMovingTheJournal()throws Exception{
        Path legacy=source.root.resolve("profile-publications");Files.createDirectory(legacy,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path retained=Files.createDirectory(legacy.resolve(server.toString()),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SourceMaterialFixture.write(retained.resolve("intent.json"),"original-retained-intent".getBytes());
        var failure=assertThrows(ClusterFailure.class,()->ClusterProfilePublication.publish(plan,effect->fail("must not claim a replacement layout")));
        assertEquals("PROFILE_PUBLICATION_RETAINED_LAYOUT",failure.getMessage());
        assertEquals("original-retained-intent",Files.readString(retained.resolve("intent.json")));
        assertFalse(Files.exists(task().getParent()));
        assertArrayEquals(Base64.getDecoder().decode(plan.beforeBase64()),Files.readAllBytes(Path.of(plan.profilesFile())));
    }
    @Test void aDifferentPrivateInventoryCannotReloadThisPublication()throws Exception{
        publish();var foreign=new ClusterProperties();foreign.setProfilesFile(source.config.resolve("different.json"));SourceMaterialFixture.write(foreign.getProfilesFile(),Base64.getDecoder().decode(plan.beforeBase64()));
        var other=new ClusterProfiles(foreign);assertThrows(ClusterFailure.class,()->other.reloadPublication(plan,List.of()));
    }
    @Test void combinedPublicationKeepsTheFileAndCacheEffectWithinOneFence(){
        var old=profiles.get("campus");AtomicInteger calls=new AtomicInteger();
        var result=profiles.publishAndReload(plan,List.of(old.member()),effect->{calls.incrementAndGet();assertEquals(plan.beforeBinding(),profiles.publicationBinding());effect.run();assertEquals(plan.afterBinding(),profiles.publicationBinding());});
        assertEquals("RUNTIME_RELOADED",result.get("state"));assertEquals(1,calls.get());var entry=profiles.get(member.siteId());
        assertEquals(result,profiles.publishAndReload(plan,List.of(old.member()),effect->fail("completed observation must not repeat a fence/effect")));assertSame(entry,profiles.get(member.siteId()));
    }
    @Test void fileSuccessWithPendingCacheReloadNeedsANewFenceAndNeverRewritesFiles()throws Exception{
        publish();byte[] original=Files.readAllBytes(task().resolve("intent.json"));var before=profiles.get("campus");
        assertThrows(IllegalStateException.class,()->profiles.publishAndReload(plan,List.of(before.member()),effect->{throw new IllegalStateException("denied fixture fence");}));assertSame(before,profiles.get("campus"));
        profiles.publishAndReload(plan,List.of(before.member()),Runnable::run);assertArrayEquals(original,Files.readAllBytes(task().resolve("intent.json")));assertEquals(member,profiles.get(member.siteId()).member());
    }
    @Test void waitingForAuthorityCannotHoldTheCatalogMonitorNeededByAdministrativeReload()throws Exception{
        var fence=new java.util.concurrent.locks.ReentrantLock();var entering=new java.util.concurrent.CountDownLatch(1);fence.lock();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var publish=executor.submit(()->profiles.publishAndReload(plan,List.of(),effect->{entering.countDown();fence.lock();try{effect.run();}finally{fence.unlock();}}));
            try{assertTrue(entering.await(3,java.util.concurrent.TimeUnit.SECONDS));var read=executor.submit(profiles::publicationBinding);assertEquals(plan.beforeBinding(),read.get(3,java.util.concurrent.TimeUnit.SECONDS));}
            finally{fence.unlock();}
            assertEquals("RUNTIME_RELOADED",publish.get(10,java.util.concurrent.TimeUnit.SECONDS).get("state"));
        }
    }
    @Test void delayedOrForeignThreadFenceCannotExecuteOutsideTheAuthorizingTransaction()throws Exception{
        var saved=new java.util.concurrent.atomic.AtomicReference<Runnable>();assertThrows(ClusterFailure.class,()->ClusterProfilePublication.publish(plan,saved::set));
        assertThrows(ClusterFailure.class,()->saved.get().run());assertFalse(Files.exists(task()));
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var code=new java.util.concurrent.atomic.AtomicReference<Throwable>();assertThrows(ClusterFailure.class,()->ClusterProfilePublication.publish(plan,effect->{try{executor.submit(()->{try{effect.run();}catch(Throwable failure){code.set(failure);}}).get();}catch(Exception failure){throw new IllegalStateException(failure);}}));
            assertInstanceOf(ClusterFailure.class,code.get());assertFalse(Files.exists(task()));
        }
    }
}
