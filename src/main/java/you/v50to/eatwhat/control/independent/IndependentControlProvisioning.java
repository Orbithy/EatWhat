package you.v50to.eatwhat.control.independent;

import org.flywaydb.core.Flyway;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.*;
import java.util.*;

/** Operator-only local CLI. Reads an existing official management credential
 * from a private server-side plan; never grants a consumer role or logs input. */
public final class IndependentControlProvisioning {
    private static void require(boolean value){if(!value)throw new IllegalStateException("CONTROL_PROVISIONING_PRECONDITION_FAILED");}
    private static String quote(String value){require(value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));return "\""+value+"\"";}
    public static void main(String[] args){
        try{
            require(args.length==2||args.length==3);require(args[0].equals("--config"));
            boolean schemaOnly=args.length==3;require(!schemaOnly||args[2].equals("--schema-only"));
            Path path=Path.of(args[1]);require(path.isAbsolute()&&!Files.isSymbolicLink(path));
            require(Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)));
            var p=new Properties();try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){p.load(stream);}
            String url=p.getProperty("bootstrap.jdbc.url"),user=p.getProperty("bootstrap.jdbc.user"),password=p.getProperty("bootstrap.jdbc.password");
            require(url!=null&&url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/[A-Za-z0-9_]+"));
            var migration=Flyway.configure().dataSource(url,user,password).locations("classpath:db/control-independent")
                    .schemas("eatwhat_controller").table("independent_schema_history").cleanDisabled(true).load();
            migration.migrate();
            require(Arrays.stream(migration.info().applied()).anyMatch(m->"1".equals(String.valueOf(m.getVersion())))
                    &&migration.info().pending().length==0);
            if(schemaOnly){System.out.println("{\"state\":\"MANAGEMENT_SCHEMA_MIGRATED\"}");return;}
            try(var connection=DriverManager.getConnection(url,user,password)){
                connection.setAutoCommit(false);
                try(var statement=connection.createStatement()){
                    statement.execute("SET LOCAL lock_timeout='5s'");statement.execute("SET LOCAL statement_timeout='15s'");
                    statement.execute("LOCK TABLE eatwhat_controller.installation IN EXCLUSIVE MODE");
                }
                try(var insert=connection.prepareStatement("""
                    INSERT INTO eatwhat_controller.installation(singleton,installation_id,controller_id,cluster_id,source_site,source_instance,source_database_fingerprint,local_database_fingerprint)
                    SELECT true,?,?,a.cluster_id,a.site_id,a.instance_id,md5(n.cluster_identifier||':'||n.database_oid::text),eatwhat_controller.physical_fingerprint()
                    FROM eatwhat_identity.allocation a JOIN eatwhat_identity.node_state n ON n.singleton WHERE a.singleton ON CONFLICT DO NOTHING
                    """)){
                    insert.setObject(1,UUID.fromString(p.getProperty("bootstrap.installation-id")));insert.setObject(2,UUID.fromString(p.getProperty("bootstrap.controller-id")));require(insert.executeUpdate()<=1);
                }
                var properties=new IndependentControlProperties();properties.setInstallationId(UUID.fromString(p.getProperty("bootstrap.installation-id")));
                properties.setControllerId(UUID.fromString(p.getProperty("bootstrap.controller-id")));properties.setAuthoritySiteId(p.getProperty("bootstrap.authority-site-id"));
                properties.setAuthorityInstanceId(UUID.fromString(p.getProperty("bootstrap.authority-instance-id")));
                var site=new you.v50to.eatwhat.config.SiteProperties();site.setClusterId(UUID.fromString(p.getProperty("bootstrap.cluster-id")));
                site.setId(p.getProperty("bootstrap.source-site-id"));site.setInstanceId(UUID.fromString(p.getProperty("bootstrap.source-instance-id")));
                IndependentControlConfiguration.validateBinding(connection,properties,site);
                if(p.getProperty("bootstrap.management-account-id")!=null){
                    long id=Long.parseLong(p.getProperty("bootstrap.management-account-id"));String name=p.getProperty("bootstrap.management-username"),hash=p.getProperty("bootstrap.management-password-hash");
                    require(id>0&&name.matches("^(?![0-9]{11}$)[A-Za-z0-9_]{3,32}$")
                            &&hash.matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}"));
                    try(var insert=connection.prepareStatement("INSERT INTO eatwhat_controller.account(id,username,password_hash)VALUES(?,?,?)ON CONFLICT DO NOTHING")){
                        insert.setLong(1,id);insert.setString(2,name);insert.setString(3,hash);insert.executeUpdate();
                    }
                    try(var query=connection.prepareStatement("SELECT username=? AND password_hash=? AND NOT disabled FROM eatwhat_controller.account WHERE id=?")){
                        query.setString(1,name);query.setString(2,hash);query.setLong(3,id);
                        try(var rows=query.executeQuery()){require(rows.next()&&rows.getBoolean(1)&&!rows.next());}
                    }
                }
                String runtime=quote(p.getProperty("bootstrap.runtime-role"));
                try(var statement=connection.createStatement()){
                    statement.execute("GRANT USAGE ON SCHEMA eatwhat_controller TO "+runtime);
                    statement.execute("GRANT SELECT ON eatwhat_controller.installation TO "+runtime);
                    statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_controller.account TO "+runtime);
                    statement.execute("GRANT SELECT,INSERT ON eatwhat_controller.credential_change TO "+runtime);
                    statement.execute("GRANT SELECT,INSERT,UPDATE ON eatwhat_controller.handoff_outbox TO "+runtime);
                    statement.execute("GRANT SELECT ON eatwhat_controller.independent_schema_history TO "+runtime);
                    statement.execute("GRANT EXECUTE ON FUNCTION eatwhat_controller.physical_fingerprint() TO "+runtime);
                }
                connection.commit();
            }
            System.out.println("{\"state\":\"LOCAL_MANAGEMENT_INSTALLATION_BOUND\"}");
        }catch(Exception failure){
            System.err.println("{\"code\":\"CONTROL_PROVISIONING_PRECONDITION_FAILED\",\"errorType\":\""+failure.getClass().getSimpleName()+"\"}");System.exit(1);
        }
    }
}
