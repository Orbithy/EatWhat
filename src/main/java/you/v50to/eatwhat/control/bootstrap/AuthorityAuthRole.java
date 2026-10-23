package you.v50to.eatwhat.control.bootstrap;

import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Fixed per-target authority auth role. The caller owns a dedicated privileged
 * connection and private approved inputs. No runtime datasource is promoted,
 * existing role adopted, or user/session row modified. Only the approving
 * administrator's security fields are read for the authority fence.
 * Native orchestration and credential delivery are separate operations. */
public final class AuthorityAuthRole {
    public static final String PROTOCOL="eatwhat-authority-auth-role-v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> FULL_READ=Set.of("eatwhat_identity.allocation","eatwhat_cluster.state","eatwhat_cluster.local_state",
            "eatwhat_cluster.local_change","eatwhat_cluster.local_phase","eatwhat_cluster.recipient_grant","eatwhat_cluster.session_security_version",
            "eatwhat_cluster.login_session","eatwhat_cluster.session_withdraw_fence");
    private static final Map<String,Set<String>> PARTIAL_READ=Map.of("public.users",Set.of("id","password_hash","banned","role"),
            "eatwhat_identity.entity_identity",Set.of("entity_type","numeric_id","global_id"),
            "eatwhat_consent.subject",Set.of("subject_id","user_id","epoch","revision","status","withdrawal_request_id","origin_site"));
    private static final Set<String> INSERT=Set.of("eatwhat_cluster.login_session","eatwhat_cluster.session_withdraw_fence");
    private static final Set<String> UPDATE=Set.of("revoked","last_seen");
    private static final String REFERENCE_FLOOR=referenceFloorBody();
    private AuthorityAuthRole(){}
    public record Request(UUID id,UUID bootstrapId,UUID targetServerId,UUID clusterId,String authoritySiteId,UUID authorityInstanceId,
                          String authorityDatabaseFingerprint,long epoch,UUID stateId,UUID holderId,long generation,long actorId,
                          String adminBinding,String password,String salt,Instant validUntil) {
        public Request {
            for(UUID value:List.of(id,bootstrapId,targetServerId,clusterId,authorityInstanceId,stateId,holderId))require(!value.equals(new UUID(0,0)));
            require(id.equals(UUID.nameUUIDFromBytes((bootstrapId+":MEMBER_AUTH_ROLE").getBytes(StandardCharsets.UTF_8)))
                    &&authoritySiteId!=null&&authoritySiteId.matches("[A-Za-z0-9_-]{1,64}")&&authorityDatabaseFingerprint!=null&&authorityDatabaseFingerprint.matches("[a-f0-9]{32}")
                    &&epoch>0&&generation>0&&actorId>0&&adminBinding!=null&&adminBinding.matches("[A-Za-z0-9+/]{43}=")
                    &&password!=null&&password.matches("[A-Za-z0-9_-]{43,128}")&&salt!=null&&validUntil!=null&&validUntil.getNano()==0);
            byte[] bytes;try{bytes=Base64.getDecoder().decode(salt);}catch(Exception failure){throw refused();}
            require(bytes.length==16&&Base64.getEncoder().encodeToString(bytes).equals(salt));
        }
        public String username(){return "eatwhat_auth_"+targetServerId.toString().replace("-","");}
        @Override public String toString(){return "AuthorityAuthRoleRequest[redacted]";}
    }
    private static IllegalStateException refused(){return new IllegalStateException("AUTHORITY_AUTH_ROLE_POLICY_REQUIRED");}
    private static void require(boolean value){if(!value)throw refused();}
    private static String referenceFloorBody(){
        try(var input=AuthorityAuthRole.class.getResourceAsStream("/db/migration/V17__reference_dictionary_manifest.sql")){
            require(input!=null);String source=new String(input.readAllBytes(),StandardCharsets.UTF_8);int function=source.indexOf("CREATE FUNCTION public.advance_published_reference_sequences()");
            int start=source.indexOf("AS $$",function)+5,end=source.indexOf("$$;",start);require(function>=0&&start>=5&&end>start);return source.substring(start,end);
        }catch(Exception failure){throw refused();}
    }
    private static String sha(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception failure){throw refused();}}
    private static byte[] mac(byte[] key,String text){try{var m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(text.getBytes(StandardCharsets.UTF_8));}catch(Exception failure){throw refused();}}
    /** A precomputed SCRAM verifier keeps a clear password out of CREATE ROLE SQL. */
    public static String verifier(Request r){
        var key=new PBEKeySpec(r.password().toCharArray(),Base64.getDecoder().decode(r.salt()),4096,256);
        try{
            byte[] salted=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();
            try{
                byte[] client=mac(salted,"Client Key"),stored=MessageDigest.getInstance("SHA-256").digest(client),server=mac(salted,"Server Key");
                try{return "SCRAM-SHA-256$4096:"+r.salt()+"$"+Base64.getEncoder().encodeToString(stored)+":"+Base64.getEncoder().encodeToString(server);}
                finally{Arrays.fill(client,(byte)0);Arrays.fill(stored,(byte)0);Arrays.fill(server,(byte)0);}
            }finally{Arrays.fill(salted,(byte)0);}
        }catch(Exception failure){throw refused();}finally{key.clearPassword();}
    }
    private static String contract(Request r){
        return sha(JSON.writeValueAsBytes(List.of(r.id().toString(),r.bootstrapId().toString(),r.username(),r.clusterId().toString(),r.authoritySiteId(),
                r.authorityInstanceId().toString(),r.authorityDatabaseFingerprint(),r.epoch(),r.stateId().toString(),r.holderId().toString(),r.generation(),
                r.actorId(),r.adminBinding(),sha(verifier(r).getBytes(StandardCharsets.US_ASCII)),r.validUntil().toString())));
    }
    private static String comment(Request r){return PROTOCOL+":"+r.bootstrapId()+":"+r.id()+":"+contract(r);}
    private static PreparedStatement query(Connection c,String text,Object... values)throws SQLException{
        var statement=c.prepareStatement(text);statement.setQueryTimeout(5);int i=0;for(Object value:values)statement.setObject(++i,value);return statement;
    }
    private static boolean yes(Connection c,String text,Object... values)throws SQLException{
        try(var q=query(c,text,values);var rows=q.executeQuery()){require(rows.next());boolean value=rows.getBoolean(1);require(!rows.next());return value;}
    }
    private static void execute(Connection c,String text)throws SQLException{try(var statement=c.createStatement()){statement.setQueryTimeout(5);statement.execute(text);}}
    private static void physicalAuthority(Connection c,Request r)throws SQLException{
        require(yes(c,"""
            SELECT EXISTS(SELECT 1 FROM eatwhat_identity.node_state n JOIN eatwhat_identity.allocation a ON a.singleton
              JOIN eatwhat_cluster.state s ON s.singleton JOIN eatwhat_cluster.member m ON m.site_id=n.site_id AND m.instance_id=n.instance_id
              WHERE n.singleton AND n.site_id=? AND n.instance_id=? AND n.mode='CAMPUS'
                AND n.database_name=current_database() AND n.database_oid=(SELECT oid::bigint FROM pg_database WHERE datname=current_database())
                AND n.cluster_identifier=(SELECT system_identifier::text FROM pg_control_system())
                AND md5(n.cluster_identifier||':'||n.database_oid::text)=?
                AND a.cluster_id=? AND a.site_id=n.site_id AND a.instance_id=n.instance_id AND a.slot=0
                AND s.cluster_id=a.cluster_id AND s.coordinator_site=n.site_id AND s.coordinator_instance=n.instance_id
                AND m.mode='CAMPUS' AND m.allocation_slot=0 AND m.state='ACTIVE' AND m.database_fingerprint=?)
            """,r.authoritySiteId(),r.authorityInstanceId(),r.authorityDatabaseFingerprint(),r.clusterId(),r.authorityDatabaseFingerprint()));
    }
    private static void lease(Connection c,Request r,boolean lock)throws SQLException{
        require(yes(c,"SELECT cluster_id=? AND holder_id=? AND generation=? AND expires_at>clock_timestamp() FROM eatwhat_cluster.control_lease WHERE singleton"+(lock?" FOR SHARE":""),r.clusterId(),r.holderId(),r.generation()));
        require(yes(c,"SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.control_process WHERE holder_id=? AND cluster_id=? AND state_id=? AND deployment_protocol=1 AND expires_at>clock_timestamp())",r.holderId(),r.clusterId(),r.stateId()));
    }
    private static void fence(Connection c,Request r,byte[] sharedKey)throws SQLException{
        require(sharedKey!=null&&sharedKey.length==32);lease(c,r,true);
        try(var q=query(c,"SELECT u.id,u.nick_name,u.password_hash,coalesce(v.version,0) FROM public.users u LEFT JOIN eatwhat_control.credential_version v ON v.user_id=u.id WHERE u.id=? AND u.role='admin' AND NOT u.banned FOR SHARE OF u",r.actorId());var rows=q.executeQuery()){
            require(rows.next());byte[] encoded=JSON.writeValueAsBytes(List.of(rows.getLong(1),rows.getString(2),rows.getString(3),rows.getLong(4)));
            require(MessageDigest.isEqual(mac(sharedKey,new String(encoded,StandardCharsets.UTF_8)),Base64.getDecoder().decode(r.adminBinding()))&&!rows.next());
        }
        require(yes(c,"SELECT cluster_id=? AND epoch=? AND coordinator_site=? AND coordinator_instance=? FROM eatwhat_cluster.state WHERE singleton FOR SHARE",r.clusterId(),r.epoch(),r.authoritySiteId(),r.authorityInstanceId()));
        require(yes(c,"SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.local_state WHERE singleton AND cluster_id=? AND site_id=? AND instance_id=? AND epoch=? AND state='ACTIVE' AND pending_change IS NULL)",r.clusterId(),r.authoritySiteId(),r.authorityInstanceId(),r.epoch()));
        require(!yes(c,"SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.change WHERE state<>'APPLIED') OR EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE state='ACTIVE')"));
        allocation(c,r,true);physicalAuthority(c,r);
    }
    private static void allocation(Connection c,Request r,boolean unjoined)throws SQLException{
        UUID first=UUID.nameUUIDFromBytes((r.bootstrapId()+":BUSINESS_INSTALL").getBytes(StandardCharsets.UTF_8));
        require(yes(c,"SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.installation_allocation a WHERE a.server_id=? AND a.cluster_id=? AND a.first_job_id=? AND a.mode='EXTERNAL' AND a.allocation_slot BETWEEN 1 AND 1023"+
                (unjoined?" AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.member m WHERE m.site_id=a.site_id OR m.instance_id=a.instance_id OR m.allocation_slot=a.allocation_slot)":"")+")",r.targetServerId(),r.clusterId(),first));
    }
    /** Metadata/row-lock preflight only; rollback releases the fence. No role
     * is created as a test, even inside a transaction. */
    public static Map<String,Object> preflight(Connection c,Request r,byte[] sharedKey)throws SQLException{
        require(c.getAutoCommit()&&!c.isReadOnly());c.setAutoCommit(false);
        try{
            execute(c,"SET LOCAL statement_timeout='10s'");execute(c,"SET LOCAL lock_timeout='3s'");
            require(yes(c,"SELECT rolsuper FROM pg_roles WHERE rolname=current_user"));fence(c,r,sharedKey);validity(c,r);
            require(!yes(c,"SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=?)",r.username()));
            return Map.of("protocol",PROTOCOL,"id",r.id().toString(),"state","ROLE_ABSENT","policySha256",contract(r),"effectsStarted",false);
        }finally{c.rollback();c.setAutoCommit(true);}
    }
    private static void validity(Connection c,Request r)throws SQLException{
        require(yes(c,"SELECT ?::timestamptz>clock_timestamp()+interval '1 hour' AND ?::timestamptz<=clock_timestamp()+interval '366 days'",Timestamp.from(r.validUntil()),Timestamp.from(r.validUntil())));
    }
    /** All role/grant/comment effects are one transaction. No adoption/rotation. */
    public static Map<String,Object> create(Connection connection,Request r,byte[] sharedKey)throws SQLException{
        require(connection.getAutoCommit()&&!connection.isReadOnly());connection.setAutoCommit(false);
        try{
            require(yes(connection,"SELECT rolsuper FROM pg_roles WHERE rolname=current_user"));
            // Session-local settings revert on commit/rollback. Clear passwords
            // never enter SQL; suppress standard private-session statement,
            // error and duration logging (DBA audit extensions remain in force).
            execute(connection,"SET LOCAL log_statement='none'");execute(connection,"SET LOCAL log_min_error_statement='panic'");
            execute(connection,"SET LOCAL log_min_duration_statement=-1");execute(connection,"SET LOCAL log_min_duration_sample=-1");execute(connection,"SET LOCAL log_duration=off");
            execute(connection,"SET LOCAL statement_timeout='10s'");execute(connection,"SET LOCAL lock_timeout='3s'");
            fence(connection,r,sharedKey);
            validity(connection,r);
            require(!yes(connection,"SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=?)",r.username()));
            String role=r.username();String db=connection.getCatalog();require(db!=null&&db.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
            execute(connection,"CREATE ROLE "+role+" LOGIN PASSWORD '"+verifier(r)+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT NOBYPASSRLS CONNECTION LIMIT 8 VALID UNTIL '"+r.validUntil()+"'");
            execute(connection,"COMMENT ON ROLE "+role+" IS '"+comment(r)+"'");
            execute(connection,"GRANT CONNECT ON DATABASE \""+db+"\" TO "+role);
            execute(connection,"GRANT USAGE ON SCHEMA public,eatwhat_identity,eatwhat_consent,eatwhat_cluster TO "+role);
            for(var table:FULL_READ)execute(connection,"GRANT SELECT ON "+table+" TO "+role);
            for(var entry:PARTIAL_READ.entrySet())execute(connection,"GRANT SELECT("+String.join(",",new TreeSet<>(entry.getValue()))+") ON "+entry.getKey()+" TO "+role);
            for(String table:INSERT)execute(connection,"GRANT INSERT ON "+table+" TO "+role);
            execute(connection,"GRANT UPDATE(revoked,last_seen) ON eatwhat_cluster.login_session TO "+role);
            execute(connection,"ALTER ROLE "+role+" SET statement_timeout='10s'");execute(connection,"ALTER ROLE "+role+" SET lock_timeout='3s'");
            execute(connection,"ALTER ROLE "+role+" SET idle_in_transaction_session_timeout='15s'");
            var proof=verify(connection,r);lease(connection,r,false);connection.commit();return proof;
        }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}
        finally{connection.setAutoCommit(true);}
    }
    /** Own-role catalog inspection only. No grants, password resets or repairs. */
    public static Map<String,Object> inspect(Connection connection,Request r)throws SQLException{
        require(connection.getAutoCommit());boolean readOnly=connection.isReadOnly();connection.setReadOnly(true);connection.setAutoCommit(false);
        try{physicalAuthority(connection,r);allocation(connection,r,false);return verify(connection,r);}
        finally{connection.rollback();connection.setAutoCommit(true);connection.setReadOnly(readOnly);}
    }
    private static Map<String,Object> verify(Connection c,Request r)throws SQLException{
        // Check our ownership marker before reading even this role's verifier.
        require(yes(c,"SELECT shobj_description(oid,'pg_authid')=? FROM pg_roles WHERE rolname=?",comment(r),r.username()));
        require(yes(c,"""
            SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolinherit AND NOT rolbypassrls
              AND rolconnlimit=8 AND rolvaliduntil=? AND rolvaliduntil>clock_timestamp() AND rolpassword=?
              AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid)
              AND EXISTS(SELECT 1 FROM pg_db_role_setting s WHERE s.setrole=r.oid AND s.setdatabase=0
                AND s.setconfig @> ARRAY['statement_timeout=10s','lock_timeout=3s','idle_in_transaction_session_timeout=15s'] AND cardinality(s.setconfig)=3)
              AND NOT EXISTS(SELECT 1 FROM pg_db_role_setting s WHERE s.setrole=r.oid AND s.setdatabase<>0)
            FROM pg_authid r WHERE rolname=?
            """,Timestamp.from(r.validUntil()),verifier(r),r.username()));
        require(yes(c,"SELECT has_database_privilege(?,current_database(),'CONNECT') AND NOT has_database_privilege(?,current_database(),'CREATE')",r.username(),r.username()));
        var schemas=new HashSet<String>();
        try(var q=query(c,"SELECT nspname,has_schema_privilege(?,oid,'USAGE'),has_schema_privilege(?,oid,'CREATE') FROM pg_namespace WHERE nspname!~'^pg_' AND nspname<>'information_schema'",r.username(),r.username());var rows=q.executeQuery()){
            int count=0;while(rows.next()){require(++count<=128&&!rows.getBoolean(3));if(rows.getBoolean(2))schemas.add(rows.getString(1));}
        }require(schemas.containsAll(Set.of("public","eatwhat_identity","eatwhat_consent","eatwhat_cluster")));
        var expectedRead=new HashSet<>(FULL_READ);expectedRead.addAll(PARTIAL_READ.keySet());var found=new HashSet<String>();
        String relations=" FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname!~'^pg_' AND n.nspname<>'information_schema'"+
                " AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_class'::regclass AND d.objid=c.oid AND d.deptype='e')";
        // Extension-owned objects retain their existing extension policy; the
        // worker neither grants nor changes those privileges. All application
        // tables/views, including previously unknown schemas, are checked.
        var privileges=List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER");
        String checks=String.join(",",privileges.stream().map(p->"has_table_privilege(?,c.oid,'"+p+"')").toList());
        try(var q=query(c,"SELECT n.nspname||'.'||c.relname,"+checks+relations+" AND c.relkind IN ('r','p','v','m','f') ORDER BY c.oid",Collections.nCopies(7,r.username()).toArray())){
            q.setFetchSize(128);try(var rows=q.executeQuery()){
                int count=0;while(rows.next()){
                    require(++count<=1024);String name=rows.getString(1);if(expectedRead.contains(name))found.add(name);
                    for(int i=0;i<privileges.size();i++){
                        String privilege=privileges.get(i);boolean expected=privilege.equals("SELECT")&&FULL_READ.contains(name)||privilege.equals("INSERT")&&INSERT.contains(name);
                        require(rows.getBoolean(i+2)==expected);
                    }
                }
            }
        }
        var columns=List.of("SELECT","INSERT","UPDATE","REFERENCES");checks=String.join(",",columns.stream().map(p->"has_column_privilege(?,c.oid,a.attnum,'"+p+"')").toList());
        // Batch catalog predicates rather than one round trip per column.
        String columnRelations=relations.replaceFirst(" WHERE "," JOIN pg_attribute a ON a.attrelid=c.oid WHERE ");
        try(var q=query(c,"SELECT n.nspname||'.'||c.relname,a.attname,"+checks+columnRelations+" AND c.relkind IN ('r','p','v','m','f') AND a.attnum>0 AND NOT a.attisdropped ORDER BY c.oid,a.attnum",Collections.nCopies(4,r.username()).toArray())){
            q.setFetchSize(256);try(var rows=q.executeQuery()){
                int count=0;while(rows.next()){
                    require(++count<=16384);String name=rows.getString(1),column=rows.getString(2);
                    for(int i=0;i<columns.size();i++){
                        String privilege=columns.get(i);boolean expected=privilege.equals("SELECT")&&(FULL_READ.contains(name)||PARTIAL_READ.getOrDefault(name,Set.of()).contains(column))
                                ||privilege.equals("INSERT")&&INSERT.contains(name)||privilege.equals("UPDATE")&&name.equals("eatwhat_cluster.login_session")&&UPDATE.contains(column);
                        require(rows.getBoolean(i+3)==expected);
                    }
                }
            }
        }
        try(var q=query(c,"SELECT has_sequence_privilege(?,c.oid,'USAGE'),has_sequence_privilege(?,c.oid,'SELECT'),has_sequence_privilege(?,c.oid,'UPDATE')"+relations+" AND c.relkind='S'",r.username(),r.username(),r.username());var rows=q.executeQuery()){
            int count=0;while(rows.next())require(++count<=1024&&!rows.getBoolean(1)&&!rows.getBoolean(2)&&!rows.getBoolean(3));
        }require(found.equals(expectedRead));
        // A read privilege with GRANT OPTION is a stronger capability. Check
        // direct catalog ACLs as well as effective table/column privileges.
        require(yes(c,"""
            SELECT NOT EXISTS(SELECT 1 FROM (
              SELECT x.grantee,x.is_grantable FROM pg_class o CROSS JOIN LATERAL aclexplode(o.relacl) x
              UNION ALL SELECT x.grantee,x.is_grantable FROM pg_attribute o CROSS JOIN LATERAL aclexplode(o.attacl) x
              UNION ALL SELECT x.grantee,x.is_grantable FROM pg_namespace o CROSS JOIN LATERAL aclexplode(o.nspacl) x
              UNION ALL SELECT x.grantee,x.is_grantable FROM pg_database o CROSS JOIN LATERAL aclexplode(o.datacl) x
              UNION ALL SELECT x.grantee,x.is_grantable FROM pg_proc o CROSS JOIN LATERAL aclexplode(o.proacl) x
            ) acl WHERE acl.grantee=(SELECT oid FROM pg_roles WHERE rolname=?) AND acl.is_grantable)
            """,r.username()));
        // Table ACLs alone cannot detect a delegated SECURITY DEFINER entry
        // point. Preserve only the exact existing public forward-only sequence
        // floor function, including its no-argument body and search_path.
        try(var q=query(c,"""
            SELECT n.nspname,p.proname,p.pronargs,p.prosrc,p.proconfig FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
            WHERE p.prosecdef AND n.nspname!~'^pg_' AND n.nspname<>'information_schema' AND has_function_privilege(?,p.oid,'EXECUTE')
              AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=p.oid AND d.deptype='e')
            """,r.username());var rows=q.executeQuery()){
            int count=0;while(rows.next()){
                require(++count<=128&&rows.getString(1).equals("public")&&rows.getString(2).equals("advance_published_reference_sequences")
                        &&rows.getInt(3)==0&&REFERENCE_FLOOR.equals(rows.getString(4)));
                var config=rows.getArray(5);require(config!=null&&Arrays.equals((Object[])config.getArray(),new Object[]{"search_path=pg_catalog"}));config.free();
            }
        }
        return Map.of("protocol",PROTOCOL,"id",r.id().toString(),"targetServerId",r.targetServerId().toString(),"state","ROLE_READY",
                "role",r.username(),"policySha256",contract(r),"validUntil",r.validUntil().toString(),"userOrSessionDataChanged",false,
                "membershipChanged",false,"existingRoleAdopted",false);
    }
}
