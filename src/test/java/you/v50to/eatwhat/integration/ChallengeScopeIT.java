package you.v50to.eatwhat.integration;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import work.foofish.smsverify.SmsTemplate;
import work.foofish.smsverify.core.*;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Redis, mock delivery only: verify atomic consumption and scope under concurrent callers. */
class ChallengeScopeIT {
    static GenericContainer<?> container;
    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;
    @BeforeAll static void start() {
        container=IsolatedContainers.redis("challenge-scope");container.start();
        connections=new LettuceConnectionFactory(container.getHost(),container.getMappedPort(6379));
        connections.afterPropertiesSet();connections.start();redis=new StringRedisTemplate(connections);
    }
    @AfterAll static void stop(){if(connections!=null)connections.destroy();if(container!=null)container.stop();}
    @BeforeEach void clear(){try(var c=connections.getConnection()){c.serverCommands().flushDb();}}

    EmailService email(String site) {
        var mail=mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(i->new MimeMessage(Session.getInstance(new Properties())));
        var result=new EmailService();ReflectionTestUtils.setField(result,"redis",redis);
        ReflectionTestUtils.setField(result,"mailSender",mail);ReflectionTestUtils.setField(result,"siteId",site);
        ReflectionTestUtils.setField(result,"fromEmail","noreply@example.test");ReflectionTestUtils.setField(result,"fromName","Test");
        ReflectionTestUtils.setField(result,"baseUrl","https://example.test");return result;
    }
    String token(){String key=redis.keys("email:v2:*:token:*").iterator().next();return key.substring(key.lastIndexOf(':')+1);}

    @Test void recoveryEmailIsBoundToIssuerPurposeAndAccountAndConsumedExactlyOnce() throws Exception {
        var campus=email("campus");var external=email("external");
        campus.sendVerificationLink("owner@example.test",71L,"127.0.0.1",EmailService.EmailPurpose.RECOVERY);
        String token=token();
        assertThrows(BizException.class,()->external.verifyToken(token,EmailService.EmailPurpose.RECOVERY));
        assertThrows(BizException.class,()->campus.verifyToken(token));
        int successes=race(()->{var v=campus.verifyToken(token,EmailService.EmailPurpose.RECOVERY);assertEquals(71L,v.userId());assertEquals("owner@example.test",v.email());});
        assertEquals(1,successes);assertTrue(redis.keys("email:v2:*:token:*").isEmpty());
    }

    SmsService sms(String site,java.util.concurrent.atomic.AtomicReference<String> code) {
        var delivery=mock(SmsTemplate.class);
        when(delivery.send(any(SmsRequest.class))).thenAnswer(i->{SmsRequest req=i.getArgument(0);
            code.set(req.getParams().values().stream().filter(v->v.matches("\\d{6}")).findFirst().orElseThrow());
            var reply=mock(SmsResponse.class);when(reply.isSuccess()).thenReturn(true);return reply;});
        var svc=new SmsService(redis,delivery);ReflectionTestUtils.setField(svc,"siteId",site);return svc;
    }
    @Test void smsCannotCrossAccountSiteOrPurposeAndConcurrentSuccessIsSingleUse() throws Exception {
        var code=new java.util.concurrent.atomic.AtomicReference<String>();var campus=sms("campus",code);var external=sms("external",code);
        campus.sendCode(Scene.bind,"13800000001","127.0.0.1",71L);
        assertThrows(BizException.class,()->external.verifyCode(Scene.bind,"13800000001",code.get(),71L));
        assertThrows(BizException.class,()->campus.verifyCode(Scene.bind,"13800000001",code.get(),72L));
        assertThrows(BizException.class,()->campus.verifyCode(Scene.change,"13800000001",code.get(),71L));
        assertEquals(1,race(()->assertTrue(campus.verifyCode(Scene.bind,"13800000001",code.get(),71L))));
    }
    @Test void concurrentWrongGuessesCannotBypassTheAttemptLimit() throws Exception {
        var code=new java.util.concurrent.atomic.AtomicReference<String>();var svc=sms("campus",code);
        svc.sendCode(Scene.bind,"13800000002","127.0.0.1",71L);
        String wrong=code.get().equals("000000")?"999999":"000000";
        assertEquals(0,race(()->svc.verifyCode(Scene.bind,"13800000002",wrong,71L)));
        assertThrows(BizException.class,()->svc.verifyCode(Scene.bind,"13800000002",code.get(),71L));
        assertEquals("5",redis.opsForValue().get("sms:v2:{campus:71:bind:13800000002}:cnt"));
    }
    @Test void anonymousLoginChallengeIsBoundToPhoneSiteAndPurposeAndConsumedOnce() throws Exception {
        var code=new java.util.concurrent.atomic.AtomicReference<String>();var campus=sms("campus",code);var external=sms("external",code);
        campus.sendLoginCode("13800000003","127.0.0.1");
        assertThrows(BizException.class,()->external.verifyLoginCode("13800000003",code.get()));
        assertThrows(BizException.class,()->campus.verifyLoginCode("13800000004",code.get()));
        assertThrows(BizException.class,()->campus.verifyCode(Scene.change,"13800000003",code.get(),71L));
        assertThrows(BizException.class,()->campus.verifyCode(Scene.bind,"13800000003",code.get(),71L));
        assertEquals(1,race(()->assertTrue(campus.verifyLoginCode("13800000003",code.get()))));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("sms:v2:{campus:anon:login:13800000003}:code")));
    }

    /** Two sites sharing one owner map; each call is routed in-process the way the cluster channel routes it. */
    static final class TwoSites implements ChallengePeers {
        final Map<String,SmsService> services=new HashMap<>();final Map<String,EmailService> mails=new HashMap<>();final List<String> trace=new CopyOnWriteArrayList<>();
        public String ownerOf(String key){return (key.hashCode()&1)==0?"campus":"external";}
        public tools.jackson.databind.JsonNode call(String site,String operation,tools.jackson.databind.JsonNode body){trace.add(site+":"+operation);return operation.startsWith("email-")?mails.get(site).handleChallenge(operation,body):services.get(site).handleChallenge(operation,body);}
    }
    String mobileOwnedBy(TwoSites sites,String owner,Scene scene){
        for(int i=0;i<100;i++){String m="1390000"+String.format("%04d",i);if(sites.owner(scene,m).equals(owner))return m;}
        throw new AssertionError();
    }
    TwoSites twoSites(java.util.concurrent.atomic.AtomicReference<String> code){
        var sites=new TwoSites();
        for(String name:List.of("campus","external")){var svc=sms(name,code);ReflectionTestUtils.setField(svc,"peers",sites);sites.services.put(name,svc);
            var mail=email(name);ReflectionTestUtils.setField(mail,"peers",sites);sites.mails.put(name,mail);}
        return sites;
    }
    @Test void attemptsAreCountedOnceAcrossBothSitesWhoeverIssuedAndWhoeverVerifies() throws Exception {
        for(String owner:List.of("campus","external")){
            clear();
            var code=new java.util.concurrent.atomic.AtomicReference<String>();var sites=twoSites(code);
            String other=owner.equals("campus")?"external":"campus";String mobile=mobileOwnedBy(sites,owner,Scene.bind);
            sites.services.get(other).sendCode(Scene.bind,mobile,"127.0.0.1",71L);
            String wrong=code.get().equals("000000")?"999999":"000000";
            for(int i=0;i<3;i++)assertThrows(BizException.class,()->sites.services.get("campus").verifyCode(Scene.bind,mobile,wrong,71L));
            for(int i=0;i<2;i++)assertThrows(BizException.class,()->sites.services.get("external").verifyCode(Scene.bind,mobile,wrong,71L));
            var locked=assertThrows(BizException.class,()->sites.services.get(owner).verifyCode(Scene.bind,mobile,code.get(),71L));
            assertEquals(BizCode.TOO_MANY_REQUESTS,locked.getBizCode());
            assertEquals("5",redis.opsForValue().get("sms:v2:{"+owner+":71:bind:"+mobile+"}:cnt"));
            assertTrue(redis.keys("sms:v2:{"+other+":*").isEmpty());
        }
    }
    @Test void sendIntervalAndSingleUseAreSharedAcrossSites() throws Exception {
        var code=new java.util.concurrent.atomic.AtomicReference<String>();var sites=twoSites(code);
        String mobile=mobileOwnedBy(sites,"external",Scene.bind);
        sites.services.get("campus").sendCode(Scene.bind,mobile,"127.0.0.1",71L);
        assertThrows(BizException.class,()->sites.services.get("external").sendCode(Scene.bind,mobile,"127.0.0.1",71L));
        assertThrows(BizException.class,()->sites.services.get("campus").sendCode(Scene.bind,mobile,"127.0.0.1",71L));
        var verifiers=List.of(sites.services.get("campus"),sites.services.get("external"));var turn=new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(1,race(()->assertTrue(verifiers.get(turn.getAndIncrement()%2).verifyCode(Scene.bind,mobile,code.get(),71L))));
    }
    @Test void phoneSendIntervalIsSharedAcrossAccountsButCodesRemainAccountBound() {
        for(String owner:List.of("campus","external"))for(long actor:List.of(71L,72L)){
            clear();
            var code=new java.util.concurrent.atomic.AtomicReference<String>();var sites=twoSites(code);
            String other=owner.equals("campus")?"external":"campus";
            String mobile=mobileOwnedBy(sites,owner,Scene.bind);long another=actor==71L?72L:71L;
            sites.services.get(other).sendCode(Scene.bind,mobile,"127.0.0.1",actor);
            for(var service:sites.services.values()){
                var limited=assertThrows(BizException.class,()->service.sendCode(Scene.bind,mobile,"127.0.0.1",another));
                assertEquals(BizCode.TOO_MANY_REQUESTS,limited.getBizCode());
                assertThrows(BizException.class,()->service.verifyCode(Scene.bind,mobile,code.get(),another));
            }
            assertTrue(sites.services.get(owner).verifyCode(Scene.bind,mobile,code.get(),actor));
        }
    }
    @Test void anonymousLoginCodesCanBeConsumedOnceFromEitherSite() throws Exception {
        for(String owner:List.of("campus","external")){
            clear();
            var code=new java.util.concurrent.atomic.AtomicReference<String>();var sites=twoSites(code);
            String other=owner.equals("campus")?"external":"campus";
            String mobile=mobileOwnedBy(sites,owner,Scene.login);
            sites.services.get(other).sendLoginCode(mobile,"127.0.0.1");
            var limited=assertThrows(BizException.class,()->sites.services.get(owner).sendLoginCode(mobile,"127.0.0.1"));
            assertEquals(BizCode.TOO_MANY_REQUESTS,limited.getBizCode());
            var turn=new java.util.concurrent.atomic.AtomicInteger();var verifiers=List.copyOf(sites.services.values());
            assertEquals(1,race(()->assertTrue(verifiers.get(turn.getAndIncrement()%2).verifyLoginCode(mobile,code.get()))));
        }
    }
    @Test void missingOwnerDoesNotFallBackToLocalChallengeState() {
        var sites=twoSites(new java.util.concurrent.atomic.AtomicReference<>());
        var missing=new ChallengePeers(){public String ownerOf(String key){return null;}
            public tools.jackson.databind.JsonNode call(String site,String op,tools.jackson.databind.JsonNode body){throw new AssertionError("No owner");}};
        var sms=sites.services.get("campus");var mail=sites.mails.get("campus");
        ReflectionTestUtils.setField(sms,"peers",missing);ReflectionTestUtils.setField(mail,"peers",missing);
        var smsFailure=assertThrows(BizException.class,()->sms.sendCode(Scene.bind,"13800000001","127.0.0.1",71L));
        var mailFailure=assertThrows(BizException.class,()->mail.sendVerificationLink("owner@example.test",71L,"127.0.0.1",EmailService.EmailPurpose.RECOVERY));
        assertEquals(BizCode.THIRD_PARTY_UNAVAILABLE,smsFailure.getBizCode());
        assertEquals(BizCode.THIRD_PARTY_UNAVAILABLE,mailFailure.getBizCode());
        assertTrue(redis.keys("sms:v2:*:code").isEmpty());assertTrue(redis.keys("email:v2:*:token:*").isEmpty());
    }
    @Test void unreachableOwnerFailsClosed() {
        var code=new java.util.concurrent.atomic.AtomicReference<String>();var sites=twoSites(code);
        String mobile=mobileOwnedBy(sites,"external",Scene.bind);
        var peers=new ChallengePeers(){public String ownerOf(String key){return sites.ownerOf(key);}
            public tools.jackson.databind.JsonNode call(String site,String op,tools.jackson.databind.JsonNode body){throw new IllegalStateException("down");}};
        ReflectionTestUtils.setField(sites.services.get("campus"),"peers",peers);
        assertThrows(BizException.class,()->sites.services.get("campus").sendCode(Scene.bind,mobile,"127.0.0.1",71L));
        assertThrows(BizException.class,()->sites.services.get("campus").verifyCode(Scene.bind,mobile,"123456",71L));
    }
    @Test void misroutedChallengeIsRejectedByTheNonOwner() {
        var code=new java.util.concurrent.atomic.AtomicReference<String>();var sites=twoSites(code);
        String mobile=mobileOwnedBy(sites,"external",Scene.bind);
        var body=new tools.jackson.databind.json.JsonMapper().createObjectNode().put("scene","bind").put("mobile",mobile).put("actor","71").put("code","123456");
        assertThrows(IllegalArgumentException.class,()->sites.services.get("campus").handleChallenge("sms-verify",body));
    }

    @Test void emailTokenIsSingleUseAndIntervalSharedAcrossSites() throws Exception {
        var sites=twoSites(new java.util.concurrent.atomic.AtomicReference<>());var campus=sites.mails.get("campus");var external=sites.mails.get("external");
        campus.sendVerificationLink("owner@example.test",71L,"127.0.0.1",EmailService.EmailPurpose.RECOVERY);
        assertThrows(BizException.class,()->external.sendVerificationLink("owner@example.test",71L,"127.0.0.1",EmailService.EmailPurpose.RECOVERY));
        assertThrows(BizException.class,()->campus.sendVerificationLink("owner@example.test",71L,"127.0.0.1",EmailService.EmailPurpose.RECOVERY));
        var differentlyCased=assertThrows(BizException.class,()->external.sendVerificationLink("OWNER@EXAMPLE.TEST",72L,"127.0.0.1",EmailService.EmailPurpose.BINDING));
        assertEquals(BizCode.TOO_MANY_REQUESTS,differentlyCased.getBizCode());
        String token=token();
        assertThrows(BizException.class,()->external.verifyToken(token,EmailService.EmailPurpose.VERIFICATION));
        var turn=new java.util.concurrent.atomic.AtomicInteger();var verifiers=List.of(campus,external);
        assertEquals(1,race(()->{var v=verifiers.get(turn.getAndIncrement()%2).verifyToken(token,EmailService.EmailPurpose.RECOVERY);assertEquals(71L,v.userId());assertEquals("owner@example.test",v.email());}));
        assertTrue(redis.keys("email:v2:*:token:*").isEmpty());
    }
    @Test void emailTokenIsStoredOnlyOnItsOwner() {
        var sites=twoSites(new java.util.concurrent.atomic.AtomicReference<>());
        sites.mails.get("campus").sendVerificationLink("owner@example.test",71L,"127.0.0.1",EmailService.EmailPurpose.BINDING);
        String key=redis.keys("email:v2:*:token:*").iterator().next();String token=key.substring(key.lastIndexOf(':')+1);
        assertEquals(1,redis.keys("email:v2:*:token:*").size());
        assertTrue(key.startsWith("email:v2:"+sites.ownerOf("email-token|"+token)+":token:"));
    }
    @Test void emailOwnerOutageAndMisroutingFailClosed() {
        var sites=twoSites(new java.util.concurrent.atomic.AtomicReference<>());var campus=sites.mails.get("campus");
        String token="a".repeat(64);String owner=sites.ownerOf("email-token|"+token);String other=owner.equals("campus")?"external":"campus";
        var down=new ChallengePeers(){public String ownerOf(String k){return sites.ownerOf(k);}
            public tools.jackson.databind.JsonNode call(String site,String op,tools.jackson.databind.JsonNode body){throw new IllegalStateException("down");}};
        ReflectionTestUtils.setField(sites.mails.get(other),"peers",down);
        assertThrows(BizException.class,()->sites.mails.get(other).verifyToken(token,EmailService.EmailPurpose.RECOVERY));
        var body=new tools.jackson.databind.json.JsonMapper().createObjectNode().put("token",token).put("purpose","RECOVERY");
        assertThrows(IllegalArgumentException.class,()->sites.mails.get(other).handleChallenge("email-consume",body));
    }
    int race(Runnable action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(16)) {
            var latch=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<16;i++)futures.add(pool.submit(()->{latch.await();try{action.run();return true;}catch(BizException expected){return false;}}));
            latch.countDown();int successes=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))successes++;return successes;
        }
    }
}
