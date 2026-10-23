package you.v50to.eatwhat.config;

import org.flywaydb.core.api.callback.Callback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class FileSchemaConfiguration {
    @Bean
    public Callback fileSchemaMigrationCallback(Environment environment) {
        return new FileSchemaMigrationCallback(environment.getProperty("spring.datasource.username"));
    }
}
