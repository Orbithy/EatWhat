package you.v50to.eatwhat.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import work.foofish.smsverify.SmsTemplate;
import you.v50to.eatwhat.EatwhatApplication;

/** Separate JVMs keep Sa-Token globals and Redis connections genuinely independent. Only mail/SMS are replaced. */
public final class ClusterNodeProcessMain {
    @TestConfiguration(proxyBeanMethods=false) static class Boundaries {
        @Bean JavaMailSender mail(){return org.mockito.Mockito.mock(JavaMailSender.class);}
        @Bean SmsTemplate sms(){return org.mockito.Mockito.mock(SmsTemplate.class);}
    }
    public static void main(String[] args){SpringApplication.run(new Class<?>[]{EatwhatApplication.class,Boundaries.class},new String[]{"--spring.config.location=classpath:/application-integration.properties,file:"+args[0]});}
}
