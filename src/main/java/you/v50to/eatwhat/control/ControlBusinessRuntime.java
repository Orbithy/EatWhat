package you.v50to.eatwhat.control;

import java.util.*;
import java.util.function.Function;

/** Shared first-install configuration for standalone and composite installers.
 * Inputs are server-owned configuration plus the installation's own fresh DB
 * credentials. SOURCE JDBC/migrator credentials and cluster grants never enter
 * this map. An EXTERNAL business remains gated until normal enrollment. */
public final class ControlBusinessRuntime {
    private ControlBusinessRuntime(){}
    private static final List<String> COPIED=List.of("app.auth.username-registration-enabled","app.oauth-url","app.oauth-callback-url","app.oauth-success-redirect","app.oauth-app-success-redirect","app.jwt.key","app.mail.from","app.mail.from-name",
            "spring.mail.host","spring.mail.port","spring.mail.username","spring.mail.password","spring.mail.properties.mail.smtp.auth",
            "spring.mail.properties.mail.smtp.ssl.enable","spring.mail.properties.mail.smtp.starttls.enable","sms.active","sms.aliyun.access-key-id",
            "sms.aliyun.access-key-secret","sms.aliyun.sign-name","sms.aliyun.interval","app.storage.s3.provider","app.storage.s3.endpoint",
            "app.storage.s3.region","app.storage.s3.bucket","app.storage.s3.access-key","app.storage.s3.secret-key","app.storage.s3.path-style",
            "app.storage.s3.cdn-endpoint","app.storage.s3.public-base-url","app.storage.s3.allowed-read-prefixes","app.storage.s3.public-read-prefixes","app.storage.s3.allowed-content-types");
    private static final List<String> DISABLED=List.of("app.account.enabled","app.sync.capture-enabled","app.sync.transport-enabled","app.sync.relation.enabled",
            "app.sync.profile.enabled","app.sync.browse.enabled","app.sync.preferences.enabled","app.sync.privacy.enabled","app.sync.notification.enabled","app.storage.files.replication.enabled",
            "app.storage.files.replication.worker-enabled","app.storage.files.gc.worker-enabled","app.storage.files.lifecycle.worker-enabled",
            "app.control.deployment.enabled","sentry.enabled","spring.mail.test-connection","management.health.mail.enabled");
    public static Map<String,String> properties(Map<String,Object> header,Map<String,String> database,String baseUrl,Function<String,String> configuration){
        var p=new TreeMap<String,String>();
        for(String name:COPIED){String value=configuration.apply(name);if(value!=null)p.put(name,value);}
        p.put("spring.application.name","eatwhat");p.put("server.port","8080");p.put("app.site.mode",Objects.toString(header.get("mode"),"CAMPUS"));
        p.put("app.site.id",header.get("siteId").toString());p.put("app.site.instance-id",header.get("instanceId").toString());p.put("app.cluster.enabled","false");
        if(header.containsKey("clusterId")){p.put("app.site.cluster-id",header.get("clusterId").toString());p.put("app.site.allocation-slot",header.get("allocationSlot").toString());p.put("app.control.enrollment-required","true");}
        String jdbc="jdbc:postgresql://postgres:5432/eatwhat";
        p.put("spring.datasource.url",jdbc);p.put("spring.datasource.username",database.get("PG_APP_USER"));p.put("spring.datasource.password",database.get("PG_APP_PASSWORD"));p.put("spring.datasource.hikari.maximum-pool-size","8");
        p.put("spring.flyway.url",jdbc);p.put("spring.flyway.user",database.get("POSTGRES_USER"));p.put("spring.flyway.password",database.get("POSTGRES_PASSWORD"));
        p.put("spring.flyway.enabled","true");p.put("spring.flyway.clean-disabled","true");p.put("spring.flyway.validate-on-migrate","true");
        p.put("spring.data.redis.host","redis");p.put("spring.data.redis.port","6379");p.put("spring.data.redis.database","0");p.put("spring.data.redis.password",database.get("REDIS_PASSWORD"));p.put("app.base-url",baseUrl);
        p.put("sa-token.token-prefix","Bearer");p.put("sa-token.is-share","false");p.put("sa-token.is-log","false");p.put("sa-token.is-read-cookie","false");
        DISABLED.forEach(name->p.put(name,"false"));
        p.put("app.control.enabled","true");p.put("app.control.node-enabled","true");p.put("app.control.environment-kind","managed-deployment");
        p.put("app.control.state-directory","/state");p.put("app.control.node-token-file","/config/control.token");
        p.put("management.endpoints.web.exposure.include","health");p.put("management.endpoint.health.probes.enabled","true");p.put("management.endpoint.health.show-details","never");p.put("management.endpoint.health.group.readiness.include","readinessState,db,redis");
        p.putIfAbsent("app.storage.s3.allowed-read-prefixes","uploads/");p.putIfAbsent("app.storage.s3.allowed-content-types","image/jpeg,image/png,image/webp");p.putIfAbsent("app.storage.s3.path-style","true");p.putIfAbsent("app.storage.s3.provider","RUSTFS");
        return Collections.unmodifiableMap(p);
    }
    /** Deterministic java.util.Properties/Spring-compatible ASCII. No timestamp
     * can change a sealed installation header or the restore-rehearsal input. */
    public static String render(Map<String,String> values){
        var result=new StringBuilder();new TreeMap<>(values).forEach((key,value)->result.append(escape(key,true)).append('=').append(escape(value,false)).append('\n'));return result.toString();
    }
    private static String escape(String text,boolean key){
        if(text==null||text.indexOf(0)>=0)throw new ControlFailure(503,"SERVER_INSTALLATION_UNAVAILABLE");var result=new StringBuilder();
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);switch(c){
                case '\\'->result.append("\\\\");case '\n'->result.append("\\n");case '\r'->result.append("\\r");case '\t'->result.append("\\t");case '\f'->result.append("\\f");
                default->{if(c<32||c>126)result.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                    else{if(" =:#!".indexOf(c)>=0&&(key||i==0))result.append('\\');result.append(c);}}
            }
        }return result.toString();
    }
}
