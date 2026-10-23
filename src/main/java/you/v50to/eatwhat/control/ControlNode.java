package you.v50to.eatwhat.control;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Local observations only. A failed observation is UNKNOWN, never a cached UP or an empty healthy queue. */
public final class ControlNode implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final SiteProperties site;
    private final ControlAudit audit;
    private final ControlHealthChecks health;
    private final ControlStorageCheck storage;
    private final String token;
    private final boolean faultsAllowed;
    private final Map<String,FaultProxy> proxies=new LinkedHashMap<>();
    private final Map<String,UUID> faultIds=new java.util.concurrent.ConcurrentHashMap<>();
    private final JsonMapper json=JsonMapper.builder().build();
    public ControlNode(ControlProperties config,JdbcTemplate jdbc,SiteProperties site,ControlAudit audit) {
        this(config,jdbc,site,audit,null,null);
    }
    public ControlNode(ControlProperties config,JdbcTemplate jdbc,SiteProperties site,ControlAudit audit,ControlHealthChecks health,ControlStorageCheck storage) {
        this.health=health;this.storage=storage;
        this.faultsAllowed=!"managed-deployment".equals(config.getEnvironmentKind());
        if(!faultsAllowed && !config.getProxies().isEmpty())throw new ControlFailure(503,"PRODUCTION_FAULT_PROXIES_FORBIDDEN");
        this.jdbc=jdbc;this.site=site;this.audit=audit;token=ControlAudit.token(config.getNodeTokenFile());
        try {config.getProxies().forEach((name,p)->{ControlService.identifier(name);proxies.put(name,new FaultProxy(p,
                ()->audit.append(faultIds.getOrDefault(name,UUID.randomUUID()),"expiry-timer","AUTO_RESTORE",name,"APPLIED")));});}
        catch(RuntimeException e){close();throw e;}
    }
    public void authenticate(String supplied) {
        if(supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ControlFailure(401,"NODE_AUTHENTICATION_REQUIRED");
    }
    public Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();
        result.put("siteId",site.getId());result.put("instanceId",site.getInstanceId());result.put("mode",site.getMode().name());
        result.put("observedAt",Instant.now().toString());result.put("deploymentExecutionEnabled",false);
        try {
            String raw=jdbc.execute((ConnectionCallback<String>)connection->{
                boolean auto=connection.getAutoCommit(),readOnly=connection.isReadOnly();
                try {
                    connection.setReadOnly(true);connection.setAutoCommit(false);
                    try(var statement=connection.createStatement()) {
                        statement.setQueryTimeout(3);
                        try(var rows=statement.executeQuery(SQL)) {if(!rows.next()) throw new IllegalStateException();return rows.getString(1);}
                    }
                } finally {connection.rollback();connection.setAutoCommit(auto);connection.setReadOnly(readOnly);}
            });
            var facts=json.readValue(raw,Map.class);
            if(!Objects.equals(facts.get("siteId"),site.getId()) || !Objects.equals(facts.get("instanceId"),String.valueOf(site.getInstanceId()))
                    || !Objects.equals(facts.get("mode"),site.getMode().name()) || !Boolean.TRUE.equals(facts.get("v35"))
                    || !Boolean.TRUE.equals(facts.get("restrictedRole")) || !Boolean.TRUE.equals(facts.get("coordinationGuard")))
                throw new IllegalStateException();
            result.put("observationStatus","OBSERVED");result.put("database",facts);
        } catch(Exception e) {result.put("observationStatus","UNKNOWN");result.put("reason","DATABASE_OBSERVATION_UNAVAILABLE");}
        var channels=new LinkedHashMap<String,Object>();proxies.forEach((k,p)->channels.put(k,p.state()));result.put("channels",channels);
        // DB observations do not prove application readiness, object-store connectivity or peer application.
        if(health==null){result.put("readiness","NOT_CHECKED");result.put("storageReachability","NOT_CHECKED");}
        else result.putAll(health.snapshot());
        result.put("storageWriteCheck",storage==null?Map.of("state","NOT_CHECKED"):storage.snapshot());
        return result;
    }
    /** Dependency diagnostics remain readable while the database identity observation is unavailable. */
    public Map<String,Object> healthSnapshot(){var result=new LinkedHashMap<String,Object>();result.put("siteId",site.getId());result.put("instanceId",site.getInstanceId());result.put("mode",site.getMode().name());result.put("observedAt",Instant.now().toString());result.put("observationStatus","DEPENDENCY_CHECKS_ONLY");if(health!=null)result.putAll(health.snapshot());result.put("storageWriteCheck",storage==null?Map.of("state","NOT_CHECKED"):storage.snapshot());return result;}
    public void refreshHealth(){if(health==null)throw new ControlFailure(409,"HEALTH_CHECKS_DISABLED");health.refresh();}
    public void storageCheck(String actor,UUID requestId){if(storage==null)throw new ControlFailure(409,"STORAGE_CHECKS_DISABLED");storage.start(actor,requestId);}
    public Map<String,Object> fault(String channel,String action,int seconds,UUID requestId) {
        if(!faultsAllowed)throw new ControlFailure(403,"FAULTS_REQUIRE_ISOLATED_ENVIRONMENT");
        var proxy=proxies.get(channel);if(proxy==null) throw new ControlFailure(404,"CHANNEL_NOT_CONFIGURED");
        if(!Set.of("BLOCK","RESTORE").contains(action)) throw new ControlFailure(400,"ACTION_NOT_ALLOWED");
        // Recovery remains possible when the database or peer is unavailable.
        if(action.equals("BLOCK") && !snapshot().get("observationStatus").equals("OBSERVED")) throw new ControlFailure(409,"NODE_IDENTITY_NOT_VERIFIED");
        audit.append(requestId,"authenticated-hub",action,channel,"REQUESTED",Map.of("seconds",seconds));
        if(action.equals("BLOCK")) {faultIds.put(channel,requestId);proxy.block(seconds);}else proxy.restore();
        audit.append(requestId,"authenticated-hub",action,channel,"APPLIED");return proxy.state();
    }
    @Override public void close(){proxies.values().forEach(FaultProxy::close);}
    static final String SQL="""
        SELECT json_build_object(
          'siteId',n.site_id,'instanceId',n.instance_id,'mode',n.mode,
          'databaseFingerprint',md5(n.cluster_identifier||':'||n.database_oid::text),
          'restrictedRole',NOT(r.rolsuper OR r.rolbypassrls),
          'v35',EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='35' AND success),
          'coordinationGuard',EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=to_regclass('eatwhat_file.file_gc_coordination') AND tgname='gc_coordination_guard' AND tgenabled IN ('O','A') AND NOT tgisinternal),
          'delivery',(SELECT COALESCE(json_object_agg(status,c),'{}'::json) FROM (SELECT status,count(*) c FROM eatwhat_sync.delivery GROUP BY status) q),
          'inbox',(SELECT COALESCE(json_object_agg(status,c),'{}'::json) FROM (SELECT status,count(*) c FROM eatwhat_sync.inbox GROUP BY status) q),
          'transfers',(SELECT COALESCE(json_object_agg(status,c),'{}'::json) FROM (SELECT status,count(*) c FROM eatwhat_file.file_transfer_job GROUP BY status) q),
          'replicas',(SELECT COALESCE(json_object_agg(state,c),'{}'::json) FROM (SELECT state,count(*) c FROM eatwhat_file.file_replica GROUP BY state) q),
          'publication',(SELECT COALESCE(json_object_agg(global_state,c),'{}'::json) FROM (SELECT global_state,count(*) c FROM eatwhat_file.file_publication GROUP BY global_state) q),
          'activeReferences',(SELECT count(*) FROM eatwhat_file.file_reference_live),
          'unpublishedReceipts',(SELECT count(*) FROM eatwhat_file.file_sync_receipt WHERE NOT published),
          'multipartJobs',(SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE multipart_upload_id IS NOT NULL),
          'cdn',(SELECT COALESCE(json_object_agg(status,c),'{}'::json) FROM (SELECT status,count(*) c FROM eatwhat_file.file_cdn_invalidation GROUP BY status) q),
          'gc',(SELECT COALESCE(json_object_agg(state,c),'{}'::json) FROM (SELECT state,count(*) c FROM eatwhat_file.file_gc_coordination GROUP BY state) q),
          'recentGc',COALESCE((SELECT json_agg(q) FROM (SELECT file_id,revision,operation_id,role,state,peer_prepared,peer_deleted,updated_at FROM eatwhat_file.file_gc_coordination ORDER BY updated_at DESC LIMIT 20) q),'[]'::json)
        ) FROM eatwhat_identity.node_state n JOIN pg_roles r ON r.rolname=current_user WHERE n.singleton
        """;
}
