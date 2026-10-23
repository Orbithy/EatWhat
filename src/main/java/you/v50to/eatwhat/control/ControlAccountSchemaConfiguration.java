package you.v50to.eatwhat.control;

import org.flywaydb.core.api.callback.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class ControlAccountSchemaConfiguration {
    @Bean Callback controlAccountPrivileges(Environment environment){return new BaseCallback(){
        public boolean supports(Event event,Context context){return event==Event.AFTER_MIGRATE;}
        public void handle(Event event,Context context){
            if(!environment.getProperty("app.control.enabled",Boolean.class,false))return;
            String username=environment.getProperty("spring.datasource.username");if(username==null||username.isBlank())return;
            try(var statement=context.getConnection().createStatement()){
                try(var rows=statement.executeQuery("SELECT to_regclass('eatwhat_control.credential_version') IS NOT NULL")){rows.next();if(!rows.getBoolean(1))return;}
                String role="\""+username.replace("\"","\"\"")+"\"";
                statement.execute("GRANT USAGE ON SCHEMA eatwhat_control TO "+role);
                statement.execute("GRANT SELECT ON eatwhat_control.credential_version TO "+role);
                statement.execute("GRANT INSERT ON eatwhat_control.credential_change TO "+role);
            }catch(java.sql.SQLException failure){throw new IllegalStateException("Control account privileges could not be configured",failure);}
        }
    };}
}
