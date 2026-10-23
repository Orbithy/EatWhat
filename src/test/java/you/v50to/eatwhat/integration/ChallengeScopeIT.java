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
    int race(Runnable action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(16)) {
            var latch=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<16;i++)futures.add(pool.submit(()->{latch.await();try{action.run();return true;}catch(BizException expected){return false;}}));
            latch.countDown();int successes=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))successes++;return successes;
        }
    }
}
