package you.v50to.eatwhat.control.bootstrap;

import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;

/** Separate read-only CLI in the reviewed JAR, runnable with a Java 21 JRE.
 * PropertiesLauncher selects this main without starting another Spring context.
 * Neither credentials nor driver errors are returned to the remote executor.
 */
public final class ControllerRuntimeProbe {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Path CONFIG=Path.of("/opt/eatwhat-control/config/application.properties");
    private static final Set<String> KEYS=Set.of("clusterId","authoritySiteId","authorityInstanceId",
            "authorityDatabaseFingerprint","stateId","controllerName","requireStandby");
    private ControllerRuntimeProbe(){}
    private static void require(boolean value){if(!value)throw new IllegalStateException("BOOTSTRAP_CONTROLLER_RUNTIME_PROOF_FAILED");}
    public static Map<String,Object> snapshot(Connection connection,Map<String,Object> expected)throws SQLException {
        require(expected.keySet().equals(KEYS)&&expected.get("requireStandby") instanceof Boolean);
        UUID cluster=UUID.fromString(expected.get("clusterId").toString());
        UUID instance=UUID.fromString(expected.get("authorityInstanceId").toString());
        UUID state=UUID.fromString(expected.get("stateId").toString());
        String site=expected.get("authoritySiteId").toString(),name=expected.get("controllerName").toString();
        require(site.matches("[A-Za-z0-9_-]{1,64}")&&name.matches("control-[a-f0-9]{24}")
                &&expected.get("authorityDatabaseFingerprint").toString().matches("[a-f0-9]{32}"));
        connection.setReadOnly(true);connection.setAutoCommit(false);
        try {
            long epoch;
            try(var statement=connection.prepareStatement("SELECT cluster_id,epoch FROM eatwhat_cluster.state WHERE singleton")){
                statement.setQueryTimeout(5);
                try(var rows=statement.executeQuery()){
                    require(rows.next()&&cluster.equals(rows.getObject(1,UUID.class)));
                    epoch=rows.getLong(2);require(epoch>0&&!rows.next());
                }
            }
            try(var statement=connection.prepareStatement("SELECT instance_id,mode,allocation_slot,state,database_fingerprint FROM eatwhat_cluster.member WHERE site_id=?")){
                statement.setQueryTimeout(5);statement.setString(1,site);
                try(var rows=statement.executeQuery()){
                    require(rows.next()&&instance.equals(rows.getObject(1,UUID.class))&&"CAMPUS".equals(rows.getString(2))
                            &&rows.getInt(3)==0&&"ACTIVE".equals(rows.getString(4))
                            &&expected.get("authorityDatabaseFingerprint").equals(rows.getString(5))&&!rows.next());
                }
            }
            // Check the actual connected database binding, not merely a member
            // row which might have been copied along with another database.
            try(var statement=connection.prepareStatement("SELECT site_id,mode,instance_id,database_name=current_database(),database_oid=(SELECT oid::bigint FROM pg_database WHERE datname=current_database()),md5(cluster_identifier||':'||database_oid::text) FROM eatwhat_identity.node_state WHERE singleton")){
                statement.setQueryTimeout(5);
                try(var rows=statement.executeQuery()){
                    require(rows.next()&&site.equals(rows.getString(1))&&"CAMPUS".equals(rows.getString(2))
                            &&instance.equals(rows.getObject(3,UUID.class))&&rows.getBoolean(4)&&rows.getBoolean(5)
                            &&expected.get("authorityDatabaseFingerprint").equals(rows.getString(6))&&!rows.next());
                }
            }
            try(var statement=connection.prepareStatement("SELECT cluster_id,site_id,instance_id,slot FROM eatwhat_identity.allocation WHERE singleton")){
                statement.setQueryTimeout(5);
                try(var rows=statement.executeQuery()){
                    require(rows.next()&&cluster.equals(rows.getObject(1,UUID.class))&&site.equals(rows.getString(2))
                            &&instance.equals(rows.getObject(3,UUID.class))&&rows.getInt(4)==0&&!rows.next());
                }
            }
            UUID holder;
            try(var statement=connection.prepareStatement("SELECT holder_id,state_id FROM eatwhat_cluster.control_process WHERE cluster_id=? AND name=? AND expires_at>clock_timestamp()")){
                statement.setQueryTimeout(5);statement.setObject(1,cluster);statement.setString(2,name);
                try(var rows=statement.executeQuery()){
                    require(rows.next());holder=rows.getObject(1,UUID.class);
                    require(state.equals(rows.getObject(2,UUID.class))&&!rows.next());
                }
            }
            boolean localLeader,leaseLive;String leaderName;
            try(var statement=connection.prepareStatement("SELECT l.holder_id,l.expires_at>clock_timestamp(),p.name,p.state_id FROM eatwhat_cluster.control_lease l JOIN eatwhat_cluster.control_process p ON p.holder_id=l.holder_id WHERE l.singleton AND l.cluster_id=?")){
                statement.setQueryTimeout(5);statement.setObject(1,cluster);
                try(var rows=statement.executeQuery()){
                    require(rows.next());localLeader=holder.equals(rows.getObject(1,UUID.class));leaseLive=rows.getBoolean(2);
                    leaderName=rows.getString(3);require(leaseLive&&state.equals(rows.getObject(4,UUID.class))&&!rows.next());
                }
            }
            require(!Boolean.TRUE.equals(expected.get("requireStandby"))||!localLeader);
            var result=new LinkedHashMap<String,Object>();
            for(String key:List.of("clusterId","authoritySiteId","authorityInstanceId","authorityDatabaseFingerprint","stateId","controllerName"))result.put(key,expected.get(key));
            result.put("epoch",epoch);result.put("holderId",holder.toString());result.put("leaseLive",leaseLive);
            result.put("localLeader",localLeader);result.put("leaderName",leaderName);result.put("readOnlyTransaction",true);result.put("actualDatabaseIdentityVerified",true);
            return result;
        } finally {connection.rollback();}
    }
    public static void main(String[] args){
        try {
            require(args.length==1&&Path.of(args[0]).equals(CONFIG));
            for(Path p=CONFIG;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p));
            require(Files.isRegularFile(CONFIG,LinkOption.NOFOLLOW_LINKS)&&Files.size(CONFIG)<=256*1024
                    &&Files.getPosixFilePermissions(CONFIG).equals(PosixFilePermissions.fromString("rw-------")));
            var properties=new Properties();try(var in=Files.newInputStream(CONFIG)){properties.load(in);}
            byte[] input=System.in.readNBytes(8193);require(input.length<=8192);
            @SuppressWarnings("unchecked") Map<String,Object> expected=JSON.readValue(input,Map.class);
            require(Objects.equals(properties.getProperty("app.cluster.control-lease.controller-name"),expected.get("controllerName"))
                    &&"true".equals(properties.getProperty("app.control.shared-state-required"))
                    &&"true".equals(properties.getProperty("app.cluster.control-lease.candidate"))
                    &&"false".equals(properties.getProperty("app.cluster.control-lease.automatic-takeover")));
            Class.forName("org.postgresql.Driver");DriverManager.setLoginTimeout(8);
            try(var connection=DriverManager.getConnection(properties.getProperty("spring.datasource.url"),
                    properties.getProperty("spring.datasource.username"),properties.getProperty("spring.datasource.password"))){
                System.out.println(JSON.writeValueAsString(snapshot(connection,expected)));
            }
        } catch(Exception failure){System.out.println("{\"code\":\"BOOTSTRAP_CONTROLLER_DATABASE_PROOF_UNAVAILABLE\"}");System.exit(1);}
    }
}
