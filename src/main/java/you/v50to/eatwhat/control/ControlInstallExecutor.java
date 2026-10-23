package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import you.v50to.eatwhat.cluster.ClusterMember;
import you.v50to.eatwhat.cluster.ClusterStore;
import you.v50to.eatwhat.cluster.ClusterFailure;

/** First-install and single-server updates share the deployment/handoff lock. Never guesses an interrupted result. */
public final class ControlInstallExecutor implements AutoCloseable {
    private final ControlServerStore store;private final ControlSsh ssh;private final ControlServers servers;private final ControlProperties settings;private final ControlAudit audit;private final Environment environment;private final JdbcTemplate jdbc;private final JsonMapper json=JsonMapper.builder().build();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Set<UUID> owned=ConcurrentHashMap.newKeySet();private boolean closed;
    private final ObjectProvider<ClusterStore> membership;
    public ControlInstallExecutor(ControlServerStore store,ControlSsh ssh,ControlServers servers,ControlProperties settings,ControlAudit audit,Environment environment,JdbcTemplate jdbc){this(store,ssh,servers,settings,audit,environment,jdbc,new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(ClusterStore.class));}
    public ControlInstallExecutor(ControlServerStore store,ControlSsh ssh,ControlServers servers,ControlProperties settings,ControlAudit audit,Environment environment,JdbcTemplate jdbc,ObjectProvider<ClusterStore> membership){this.store=store;this.ssh=ssh;this.servers=servers;this.settings=settings;this.audit=audit;this.environment=environment;this.jdbc=jdbc;this.membership=membership;}
    private Map<String,Object> identity(ControlServerController.InstallInput input,Map<String,Object> installation,String kind){
        if(kind.equals("INSTALL")&&environment.getProperty("app.cluster.enabled",Boolean.class,false)&&input.siteMode()==null)throw new ControlFailure(400,"SITE_ROLE_REQUIRED");
        String mode=kind.equals("UPDATE")?Objects.toString(installation.get("mode"),"CAMPUS"):Objects.toString(input.siteMode(),"CAMPUS");
        if(!Set.of("CAMPUS","EXTERNAL").contains(mode))throw new ControlFailure(400,"INVALID_SITE_MODE");
        if(kind.equals("UPDATE")){
            if(input.siteMode()!=null&&!mode.equals(input.siteMode())||input.allocationSlot()!=null&&!input.allocationSlot().equals(installation.get("allocationSlot")))throw new ControlFailure(409,"INSTALLED_SITE_IDENTITY_IMMUTABLE");
            var result=new LinkedHashMap<String,Object>();result.put("mode",mode);for(String key:List.of("clusterId","allocationSlot"))if(installation.containsKey(key))result.put(key,installation.get(key));return result;
        }
        Integer slot=input.allocationSlot();if(slot==null){if(mode.equals("EXTERNAL"))throw new ControlFailure(400,"SITE_ALLOCATION_REQUIRED");if(input.siteMode()==null)return Map.of("mode",mode);slot=0;}
        if(input.siteMode()==null||slot<0||slot>1023||mode.equals("CAMPUS")&&slot!=0||mode.equals("EXTERNAL")&&slot==0)throw new ControlFailure(400,"CLUSTER_ROLE_SLOT_MISMATCH");
        String cluster;try{cluster=UUID.fromString(environment.getProperty("app.site.cluster-id","")).toString();if(cluster.equals(new UUID(0,0).toString()))throw new Exception();}catch(Exception e){throw new ControlFailure(409,"INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED");}
        return Map.of("mode",mode,"allocationSlot",slot,"clusterId",cluster);
    }
    public boolean enabled(){return settings.getServers().isInstallationEnabled();}
    public boolean clusterEnrollmentEnabled(){return settings.getServers().isClusterEnrollmentEnabled();}
    public boolean installationIdentityRequired(){return environment.getProperty("app.cluster.enabled",Boolean.class,false);}
    public List<Map<String,Object>> releases(){return settings.getReleases().entrySet().stream().map(e->{var r=e.getValue();var row=new LinkedHashMap<String,Object>();row.put("id",e.getKey());row.put("sha256",r.getSha256());row.put("acceptanceEvidenceId",Objects.toString(r.getAcceptanceEvidenceId(),""));return (Map<String,Object>)row;}).toList();}
    @SuppressWarnings("unchecked") public Map<String,Object> plan(String actor,UUID serverId,ControlServerController.InstallInput input,String kind){
        servers.requireWrite();var server=store.server(serverId);ControlServers.expected(server,input.expectedRevision());ControlService.identifier(input.releaseId());if(input.httpPort()<1024||input.httpPort()>65535||Set.of(18080,18081).contains(input.httpPort()))throw new ControlFailure(400,"INVALID_BUSINESS_PORT");
        URI base;try{base=URI.create(input.appBaseUrl());if(!Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||base.toString().length()>512)throw new Exception();}catch(Exception e){throw new ControlFailure(400,"INVALID_APPLICATION_URL");}
        var release=settings.getReleases().get(input.releaseId());if(release==null)throw new ControlFailure(404,"RELEASE_NOT_CONFIGURED");
        var installation=(Map<String,Object>)server.get("installation");var identity=identity(input,installation,kind);var blockers=new ArrayList<String>();if(!enabled())blockers.add("SERVER_INSTALLATION_DISABLED");
        var cluster=membership.getIfAvailable();if(identity.containsKey("clusterId")&&cluster==null)blockers.add("INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED");
        if(release.getAcceptanceEvidenceId()==null||release.getAcceptanceEvidenceId().isBlank())blockers.add("RELEASE_ACCEPTANCE_REQUIRED");
        try{if(!release.getSha256().equals(DeploymentExecutor.artifactSha(release.getArtifact())))blockers.add("ARTIFACT_SHA256_MISMATCH");}catch(Exception e){blockers.add("ARTIFACT_UNAVAILABLE");}
        UUID id=UUID.randomUUID();String root="/opt/eatwhat-sites/"+serverId;
        var p=new LinkedHashMap<String,Object>();p.put("id",id.toString());p.put("kind",kind);p.put("serverId",serverId.toString());p.put("serverRevision",input.expectedRevision());p.put("releaseId",input.releaseId());p.put("artifactSha256",release.getSha256());p.put("httpPort",input.httpPort());p.put("appBaseUrl",input.appBaseUrl());p.put("root",root);p.put("services",List.of("app","postgres","redis"));p.put("sameMachine",server.get("location").equals("CONTROL_HOST"));p.put("clusterMembership","NOT_JOINED");p.put("expiresAt",Instant.now().plusSeconds(900).toString());p.put("installDocker",input.installDocker());p.put("_artifact",release.getArtifact().toString());p.put("_configurationHash",configurationHash(release));p.put("_server",store.encrypt(json.writeValueAsString(server)));
        p.putAll(identity);
        if(kind.equals("INSTALL")){
            if(!"NOT_INSTALLED".equals(installation.get("state")))blockers.add("SERVER_ALREADY_INSTALLED_OR_UNRESOLVED");
            var header=new LinkedHashMap<String,Object>();header.put("serverId",serverId.toString());header.put("httpPort",input.httpPort());header.put("jobId",id.toString());header.put("siteId","server-"+serverId.toString().replace("-","").substring(0,12));header.put("instanceId",UUID.randomUUID().toString());p.put("_siteId",header.get("siteId"));p.put("_instanceId",header.get("instanceId"));header.put("artifactSha256",release.getSha256());try{header.put("artifactBytes",Files.size(release.getArtifact()));}catch(Exception e){blockers.add("ARTIFACT_UNAVAILABLE");}header.put("installDocker",input.installDocker());
            header.putAll(identity);
            if(identity.containsKey("clusterId")&&cluster!=null){var reserved=cluster.installationAllocation(serverId);if(!reserved.isEmpty()){
                if(!Objects.equals(reserved.get("cluster_id").toString(),identity.get("clusterId"))||!Objects.equals(reserved.get("mode"),identity.get("mode"))||((Number)reserved.get("allocation_slot")).intValue()!=((Number)identity.get("allocationSlot")).intValue()||!Objects.equals(reserved.get("site_id"),header.get("siteId")))throw new ControlFailure(409,"INSTALLATION_ALLOCATION_RETAINED");
                header.put("instanceId",reserved.get("instance_id").toString());p.put("_instanceId",header.get("instanceId"));
            }}
            try{var checked=ssh.call(server,ControlServers.resource("first-install.py"),List.of("inspect"),out->writeHeader(out,header),45);if(!"eatwhat-install-v1".equals(checked.get("protocol"))||!root.equals(checked.get("root"))||!serverId.toString().equals(checked.get("serverId")))throw new Exception();
                if(Boolean.FALSE.equals(checked.get("projectAvailable")))blockers.add("DOCKER_PROJECT_IN_USE");else if(Boolean.TRUE.equals(checked.get("dockerReady"))&&!Boolean.TRUE.equals(checked.get("projectAvailable")))blockers.add("DOCKER_PROJECT_INSPECTION_UNAVAILABLE");if(!Boolean.TRUE.equals(checked.get("rootAvailable")))blockers.add("INSTALLATION_DIRECTORY_EXISTS");if(!Boolean.TRUE.equals(checked.get("portAvailable")))blockers.add("BUSINESS_PORT_IN_USE");if(!Boolean.TRUE.equals(checked.get("rootUser")))blockers.add("FIRST_INSTALL_ROOT_REQUIRED");if(!Boolean.TRUE.equals(checked.get("dockerReady"))&&!(input.installDocker()&&Boolean.TRUE.equals(checked.get("canInstallDocker"))))blockers.add("DOCKER_COMPOSE_REQUIRED");if(((Number)checked.getOrDefault("diskFreeMiB",0)).longValue()<3072)blockers.add("INSUFFICIENT_DISK_SPACE");p.put("environment",checked);
            }catch(Exception e){blockers.add("SSH_INSPECTION_FAILED");}
            var database=Map.of("POSTGRES_USER","eatwhat_migration","POSTGRES_PASSWORD",random(),"PG_DB","eatwhat","PG_APP_USER","eatwhat_app","PG_APP_PASSWORD",random(),"REDIS_PASSWORD",random());String node=random()+random().substring(0,16);if(settings.getServers().getImageArchive()!=null){try{validateArchive();var archive=settings.getServers().getImageArchive();header.put("imageArchiveBytes",Files.size(archive));header.put("imageArchiveSha256",settings.getServers().getImageArchiveSha256());p.put("_imageArchive",archive.toString());p.put("offlineImages",true);}catch(Exception e){blockers.add("IMAGE_ARCHIVE_UNAVAILABLE");}}
            header.put("database",database);header.put("nodeToken",node);header.put("properties",properties(header,database,node,input.appBaseUrl()));header.put("images",images());header.put("releaseAgent",ControlServers.resource("remote-release.py"));header.put("initdb",ControlServers.resource("initdb/20-create-application-db.sh"));
            var administrators=jdbc.queryForList("SELECT nick_name,password_hash FROM users WHERE id=? AND role='admin' AND NOT banned",Long.parseLong(actor));
            if(administrators.size()!=1)throw new ControlFailure(403,"ADMIN_REQUIRED");var admin=administrators.getFirst();if(identity.get("mode").equals("CAMPUS")){header.put("administrator",Map.of("username",admin.get("nick_name"),"passwordHash",admin.get("password_hash")));p.put("administratorUsername",admin.get("nick_name"));p.put("administratorAction","INITIALIZE_OPERATOR_ADMINISTRATOR");}else p.put("administratorAction","AUTHORITY_ACCOUNTS_AFTER_JOIN");p.put("_actor",actor);p.put("_adminCheck",store.fingerprint(admin.get("nick_name")+"\0"+admin.get("password_hash")));p.put("storage",Map.of("endpoint",environment.getProperty("app.storage.s3.endpoint",""),"bucket",environment.getProperty("app.storage.s3.bucket","")));p.put("_payload",store.encrypt(json.writeValueAsString(header)));
        }else{
            if(!"SUCCEEDED".equals(installation.get("state")))blockers.add("SERVER_NOT_INSTALLED");
            if(installation.containsKey("clusterId"))blockers.add("ALLOCATED_SITE_MEMBERSHIP_REQUIRED");
            if(!"NOT_JOINED".equals(installation.get("clusterMembership")))blockers.add("JOINED_SERVER_CLUSTER_DEPLOYMENT_REQUIRED");
            if(!release.isRollbackCompatible()||!release.getCompatibleFromSha256().contains(installation.get("currentSha256")))blockers.add("UPGRADE_COMPATIBILITY_NOT_REVIEWED");
            if(!installation.containsKey("restoreEvidenceId"))blockers.add("RESTORE_REHEARSAL_REQUIRED");p.put("_installation",new LinkedHashMap<>(installation));p.put("fromSha256",installation.getOrDefault("currentSha256",""));
            if(((Number)installation.getOrDefault("httpPort",-1)).intValue()!=input.httpPort())throw new ControlFailure(409,"INSTALLED_SERVER_PORT_IMMUTABLE");
            if(!Objects.equals(installation.get("appBaseUrl"),input.appBaseUrl()))throw new ControlFailure(409,"INSTALLED_SERVER_URL_IMMUTABLE");
        }
        servers.requireWrite();p.put("blockers",blockers);p.put("state",blockers.isEmpty()?"READY":"BLOCKED");ControlServers.expected(store.server(serverId),input.expectedRevision());return store.mutate(data->{var current=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(serverId.toString());if(current==null)throw new ControlFailure(404,"SERVER_NOT_FOUND");ControlServers.expected(current,input.expectedRevision());audit.append(id,actor,"SERVER_"+kind+"_PLAN",serverId.toString(),blockers.isEmpty()?"READY":"BLOCKED");store.saveJson(planPath(id),p);return publicPlan(p);});
    }
    private Map<String,String> images(){return Map.of("app",settings.getServers().getJreImage(),"postgres",settings.getServers().getPostgresImage(),"redis",settings.getServers().getRedisImage());}
    private static String sha(String source){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new ControlFailure(503,"SERVER_AGENT_UNAVAILABLE");}}
    private String recoveryAgent(){return ControlServers.resource("first-install-recovery.py").replace("__EATWHAT_BOOTSTRAP_BASE64__",Base64.getEncoder().encodeToString(ControlServers.resource("first-install.py").getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private String recoveryConfigurationHash(){return sha(enabled()+"\n"+recoveryAgent()+"\n"+ControlServers.resource("initdb/20-create-application-db.sh"));}
    @SuppressWarnings("unchecked") public Map<String,Object> recoveryPlan(String actor,UUID serverId,ControlServerController.RecoveryInput input){
        servers.requireWrite();var server=store.server(serverId);ControlServers.expected(server,input.expectedRevision());var installation=(Map<String,Object>)server.get("installation");
        if(!"FAILED".equals(installation.get("state"))||!input.sourceJobId().toString().equals(installation.get("jobId")))throw new ControlFailure(409,"RECOVERY_SOURCE_RECONCILIATION_REQUIRED");
        var source=job(input.sourceJobId());if(!"FAILED".equals(source.get("state"))||!serverId.toString().equals(source.get("serverId"))||!Set.of("INSTALL","RECOVER").contains(source.get("kind"))||!(source.get("result") instanceof Map<?,?>))throw new ControlFailure(409,"RECOVERY_SOURCE_NOT_FAILED");
        var result=(Map<String,Object>)source.get("result");UUID id=UUID.randomUUID();var payload=new LinkedHashMap<String,Object>();for(String key:List.of("serverId","siteId","instanceId","artifactSha256")){if(!(result.get(key) instanceof String value)||value.isBlank())throw new ControlFailure(409,"RECOVERY_SOURCE_IDENTITY_UNAVAILABLE");payload.put(key,result.get(key));}
        payload.put("jobId",id.toString());payload.put("sourceJobId",input.sourceJobId().toString());String mode=Objects.toString(result.get("mode"),"CAMPUS");if(!Set.of("CAMPUS","EXTERNAL").contains(mode))throw new ControlFailure(409,"RECOVERY_SOURCE_IDENTITY_UNAVAILABLE");payload.put("mode",mode);for(String key:List.of("clusterId","allocationSlot"))if(result.containsKey(key))payload.put(key,result.get(key));payload.put("initdbSha256",sha(ControlServers.resource("initdb/20-create-application-db.sh")));
        var rows=jdbc.queryForList("SELECT nick_name,password_hash FROM users WHERE id=? AND role='admin' AND NOT banned",Long.parseLong(actor));if(rows.size()!=1)throw new ControlFailure(403,"ADMIN_REQUIRED");var admin=rows.getFirst();if(mode.equals("CAMPUS"))payload.put("administrator",Map.of("username",admin.get("nick_name"),"passwordHash",admin.get("password_hash")));
        var p=new LinkedHashMap<String,Object>();p.put("id",id.toString());p.put("kind","RECOVER");p.put("serverId",serverId.toString());p.put("serverRevision",input.expectedRevision());p.put("sourceJobId",input.sourceJobId().toString());p.put("root","/opt/eatwhat-sites/"+serverId);p.put("artifactSha256",result.get("artifactSha256"));p.put("siteId",result.get("siteId"));p.put("instanceId",result.get("instanceId"));p.put("dataPreserved",true);p.put("passwordsPreserved",true);p.put("clusterMembership","NOT_JOINED");p.put("expiresAt",Instant.now().plusSeconds(900).toString());p.put("_installation",new LinkedHashMap<>(installation));p.put("_actor",actor);p.put("_adminCheck",store.fingerprint(admin.get("nick_name")+"\0"+admin.get("password_hash")));p.put("_configurationHash",recoveryConfigurationHash());p.put("_server",store.encrypt(json.writeValueAsString(server)));
        p.put("mode",mode);for(String key:List.of("clusterId","allocationSlot"))if(payload.containsKey(key))p.put(key,payload.get(key));
        var blockers=new ArrayList<String>();if(!enabled())blockers.add("SERVER_INSTALLATION_DISABLED");
        try{
            var checked=ssh.call(server,recoveryAgent(),List.of("inspect"),out->writeHeader(out,payload),45);
            if(!"eatwhat-install-recovery-v1".equals(checked.get("protocol"))||!serverId.toString().equals(checked.get("serverId"))||!input.sourceJobId().toString().equals(checked.get("sourceJobId")))throw new Exception();
            if(Boolean.TRUE.equals(checked.get("eligible"))){
                validateIdentityReply(checked,p);
                for(String key:List.of("siteId","instanceId","artifactSha256","root"))if(!Objects.equals(p.get(key),checked.get(key)))throw new Exception();
                if(!(checked.get("freezeHash") instanceof String freeze)||!freeze.matches("[0-9a-f]{64}"))throw new Exception();payload.put("freezeHash",freeze);
                String original="eatwhat-install-v1".equals(result.get("protocol"))?input.sourceJobId().toString():Objects.toString(result.get("originalInstallJobId"),"");if(!original.equals(checked.get("originalInstallJobId"))||!UUID.fromString(original).toString().equals(original))throw new Exception();
                if(!(checked.get("httpPort") instanceof Number port)||port.intValue()<1024||port.intValue()>65535||Set.of(18080,18081).contains(port.intValue()))throw new Exception();
                URI url=URI.create(Objects.toString(checked.get("appBaseUrl"),""));if(!Set.of("http","https").contains(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||url.getQuery()!=null||url.getFragment()!=null||url.toString().length()>512)throw new Exception();
                p.put("httpPort",port.intValue());p.put("appBaseUrl",url.toString());p.put("originalInstallJobId",original);p.put("administratorAction",mode.equals("CAMPUS")?"PRESERVE_EXISTING_OR_INITIALIZE_ONLY_IF_EMPTY":"AUTHORITY_ACCOUNTS_AFTER_JOIN");if(checked.get("sourceStage") instanceof String stage&&stage.matches("[A-Z][A-Z0-9_]{0,100}"))p.put("sourceStage",stage);if(checked.containsKey("restoreEvidenceId"))p.put("_restoreEvidenceId",checked.get("restoreEvidenceId"));
                p.put("_freezeHash",freeze);
            }else if(checked.get("blockers") instanceof List<?> values){for(Object value:values)if(value instanceof String code&&code.matches("[A-Z][A-Z0-9_]{0,100}"))blockers.add(code);if(blockers.isEmpty())blockers.add("RECOVERY_INSPECTION_FAILED");}else throw new Exception();
        }catch(Exception e){blockers.add("RECOVERY_INSPECTION_FAILED");}
        p.put("blockers",blockers);p.put("state",blockers.isEmpty()?"READY":"BLOCKED");p.put("_payload",store.encrypt(json.writeValueAsString(payload)));servers.requireWrite();
        return store.mutate(data->{var current=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(serverId.toString());if(current==null)throw new ControlFailure(404,"SERVER_NOT_FOUND");ControlServers.expected(current,input.expectedRevision());if(!installation.equals(current.get("installation")))throw new ControlFailure(409,"SERVER_PLAN_CHANGED");audit.append(id,actor,"SERVER_RECOVER_PLAN",serverId.toString(),p.get("state").toString());store.saveJson(planPath(id),p);return publicPlan(p);});
    }
    private String configurationHash(ControlProperties.Release release){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(Map.of("release",release,"images",images(),"enabled",enabled(),"imageArchive",Objects.toString(settings.getServers().getImageArchive(),""),"imageArchiveSha256",Objects.toString(settings.getServers().getImageArchiveSha256(),"")))));}catch(Exception e){throw new ControlFailure(503,"SERVER_INSTALLATION_UNAVAILABLE");}}
    private void validateArchive(){var path=settings.getServers().getImageArchive();try{if(path==null||!path.isAbsolute()||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>2147483648L||!Objects.equals(settings.getServers().getImageArchiveSha256(),DeploymentExecutor.artifactSha(path)))throw new Exception();}catch(Exception e){throw new ControlFailure(409,"IMAGE_ARCHIVE_UNAVAILABLE");}}
    private static String random(){byte[] value=new byte[32];new SecureRandom().nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private String properties(Map<String,Object> header,Map<String,String> database,String node,String baseUrl){
        return ControlBusinessRuntime.render(ControlBusinessRuntime.properties(header,database,baseUrl,environment::getProperty));
    }
    private void writeHeader(OutputStream out,Object value)throws Exception{out.write(json.writeValueAsBytes(value));out.write('\n');}
    private Path planPath(UUID id){return store.directory().resolve("server-plan-"+id+".json");}private Path jobPath(UUID id){return store.directory().resolve("server-job-"+id+".json");}
    public static Map<String,Object> publicPlan(Map<String,Object> p){var result=new LinkedHashMap<String,Object>();p.forEach((k,v)->{if(!k.startsWith("_"))result.put(k,v);});return result;}
    public Map<String,Object> getPlan(UUID id){if(!Files.exists(planPath(id)))throw new ControlFailure(404,"SERVER_PLAN_NOT_FOUND");return publicPlan(store.readJson(planPath(id)));}
    private void validatePlan(Map<String,Object> p){
        if(!enabled())throw new ControlFailure(423,"SERVER_INSTALLATION_DISABLED");if(!"READY".equals(p.get("state")))throw new ControlFailure(409,"SERVER_PLAN_BLOCKED");if(Instant.now().isAfter(Instant.parse((String)p.get("expiresAt"))))throw new ControlFailure(409,"SERVER_PLAN_EXPIRED");
        var server=store.server(UUID.fromString((String)p.get("serverId")));ControlServers.expected(server,((Number)p.get("serverRevision")).longValue());
        if("RECOVER".equals(p.get("kind"))){if(!Objects.equals(p.get("_configurationHash"),recoveryConfigurationHash())||!Objects.equals(p.get("_installation"),server.get("installation")))throw new ControlFailure(409,"SERVER_PLAN_CHANGED");}
        else{var release=settings.getReleases().get(p.get("releaseId"));if(release==null||!p.get("_configurationHash").equals(configurationHash(release))||!p.get("artifactSha256").equals(DeploymentExecutor.artifactSha(release.getArtifact())))throw new ControlFailure(409,"SERVER_PLAN_CHANGED");}
        if(p.containsKey("_imageArchive"))validateArchive();
        if(Set.of("INSTALL","RECOVER").contains(p.get("kind"))){var rows=jdbc.queryForList("SELECT nick_name,password_hash FROM users WHERE id=? AND role='admin' AND NOT banned",Long.parseLong(p.get("_actor").toString()));if(rows.size()!=1||!p.get("_adminCheck").equals(store.fingerprint(rows.getFirst().get("nick_name")+"\0"+rows.getFirst().get("password_hash"))))throw new ControlFailure(409,"SERVER_PLAN_CHANGED");}
        if(p.get("kind").equals("UPDATE")&&!Objects.equals(p.get("_installation"),server.get("installation")))throw new ControlFailure(409,"SERVER_PLAN_CHANGED");
    }
    private Path freezeArtifact(UUID id,Map<String,Object> plan)throws Exception{
        Path target=store.directory().resolve("server-artifact-"+id+".jar");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(target)))throw new ControlFailure(409,"SERVER_ARTIFACT_UNAVAILABLE");
        Path temp=Files.createTempFile(store.directory(),"server-upload-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{
            Files.copy(Path.of(plan.get("_artifact").toString()),temp,StandardCopyOption.REPLACE_EXISTING);Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));
            try(var file=FileChannel.open(temp,StandardOpenOption.WRITE)){file.force(true);}
            if(!plan.get("artifactSha256").equals(DeploymentExecutor.artifactSha(temp)))throw new ControlFailure(409,"ARTIFACT_SHA256_MISMATCH");
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);try(var directory=FileChannel.open(store.directory(),StandardOpenOption.READ)){directory.force(true);}return target;
        }finally{Files.deleteIfExists(temp);}
    }
    @SuppressWarnings("unchecked") public synchronized Map<String,Object> execute(String actor,UUID id){
        servers.requireWrite();if(Files.exists(jobPath(id)))return job(id);if(closed)throw new ControlFailure(503,"SERVER_INSTALLATION_UNAVAILABLE");var plan=store.readJson(planPath(id));validatePlan(plan);
        FileChannel channel=null;FileLock lock=null;
        try{
            channel=FileChannel.open(store.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock==null)throw new ControlFailure(409,"DEPLOYMENT_RUNNING");
            if(Files.exists(jobPath(id)))return job(id);servers.requireWrite();validatePlan(plan);
            try(var files=Files.list(store.directory())){for(Path file:files.filter(p->p.getFileName().toString().matches("(?:deployment|server-job|server-cluster-job|controller-job)-[0-9a-f-]{36}\\.json")).toList())if(Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(store.readJson(file).get("state")))throw new ControlFailure(409,"DEPLOYMENT_RECONCILIATION_REQUIRED");}
            Path artifact="RECOVER".equals(plan.get("kind"))?null:freezeArtifact(id,plan);
            if(plan.get("kind").equals("INSTALL")&&plan.containsKey("clusterId")){
                var cluster=membership.getIfAvailable();if(cluster==null)throw new ControlFailure(409,"INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED");
                if(!plan.get("clusterId").equals(cluster.status().get("clusterId")))throw new ControlFailure(409,"INSTALLATION_CLUSTER_CONFIGURATION_REQUIRED");
                cluster.reserveInstallation(actor,id,UUID.fromString(plan.get("serverId").toString()),new ClusterMember(plan.get("_siteId").toString(),UUID.fromString(plan.get("_instanceId").toString()),plan.get("mode").toString(),((Number)plan.get("allocationSlot")).intValue()));
            }
            var job=new LinkedHashMap<String,Object>();job.put("id",id.toString());job.put("serverId",plan.get("serverId"));job.put("kind",plan.get("kind"));job.put("state","QUEUED");job.put("createdAt",Instant.now().toString());job.put("artifactSha256",plan.get("artifactSha256"));job.put("_plan",plan);audit.append(id,actor,"SERVER_"+plan.get("kind"),plan.get("serverId").toString(),"ACCEPTED");store.saveJson(jobPath(id),job);markInstallation(plan,"QUEUED");
            FileChannel held=channel;FileLock heldLock=lock;Path frozenArtifact=artifact;owned.add(id);worker.submit(()->run(actor,id,job,plan,frozenArtifact,held,heldLock));channel=null;lock=null;return publicPlan(job);
        }catch(ControlFailure e){throw e;}catch(ClusterFailure e){throw new ControlFailure(e.status(),e.getMessage());}catch(Exception e){throw new ControlFailure(503,"SERVER_INSTALLATION_UNAVAILABLE");}finally{try{if(lock!=null)lock.close();if(channel!=null)channel.close();}catch(Exception ignored){}}
    }
    @SuppressWarnings("unchecked") private void run(String actor,UUID id,Map<String,Object> job,Map<String,Object> plan,Path artifact,FileChannel channel,FileLock lock){
        try{
            servers.requireExecuting();job.put("state","RUNNING");store.saveJson(jobPath(id),job);markInstallation(plan,"RUNNING");var server=json.readValue(store.decrypt((String)plan.get("_server")),Map.class);Map<String,Object> reply;
            if(plan.get("kind").equals("INSTALL")){
                var payload=json.readValue(store.decrypt((String)plan.get("_payload")),Map.class);reply=ssh.call(server,ControlServers.resource("first-install.py"),List.of("install"),out->{writeHeader(out,payload);Files.copy(artifact,out);if(plan.containsKey("_imageArchive"))Files.copy(Path.of(plan.get("_imageArchive").toString()),out);},1800);validateInstallReply(reply,plan,id);
                job.put("state",reply.get("state"));job.put("result",reply);if("SUCCEEDED".equals(reply.get("state")))installed(plan,reply);
            }else if(plan.get("kind").equals("RECOVER")){
                var payload=json.readValue(store.decrypt((String)plan.get("_payload")),Map.class);reply=ssh.call(server,recoveryAgent(),List.of("recover"),out->writeHeader(out,payload),1800);validateRecoveryReply(reply,plan,id);job.put("state",reply.get("state"));job.put("result",reply);if("SUCCEEDED".equals(reply.get("state")))installed(plan,reply);
            }else{
                var prior=(Map<String,Object>)plan.get("_installation");reply=updateCall(server,plan,prior,id,"prepare",artifact);verifyUpdate(reply,plan,prior,id,"PREPARED");servers.requireExecuting();reply=updateCall(server,plan,prior,id,"apply",artifact);String state=(String)reply.get("state");if(!Set.of("SUCCEEDED","ROLLED_BACK","ROLLBACK_FAILED","FAILED").contains(state))throw new Exception();verifyUpdate(reply,plan,prior,id,state);job.put("state",state.equals("SUCCEEDED")?"SUCCEEDED":state.equals("ROLLBACK_FAILED")?"OUTCOME_UNKNOWN":"FAILED");job.put("result",reply);if(state.equals("SUCCEEDED")){var result=new LinkedHashMap<>(prior);result.put("artifactSha256",plan.get("artifactSha256"));installed(plan,result);}
            }
            if(!job.get("state").equals("SUCCEEDED"))markInstallation(plan,Boolean.TRUE.equals(((Map<?,?>)job.getOrDefault("result",Map.of())).get("retryable"))?"NOT_INSTALLED":job.get("state").toString());clearCompletedSecrets(job,plan);store.saveJson(jobPath(id),job);audit.append(id,actor,"SERVER_"+plan.get("kind")+"_RESULT",plan.get("serverId").toString(),job.get("state").toString());
        }catch(Exception e){job.put("state","OUTCOME_UNKNOWN");job.put("code","SERVER_TASK_OUTCOME_UNKNOWN");try{markInstallation(plan,"OUTCOME_UNKNOWN");store.saveJson(jobPath(id),job);}catch(Exception ignored){}}
        finally{owned.remove(id);try{lock.close();channel.close();}catch(Exception ignored){}try{if(artifact!=null)Files.deleteIfExists(artifact);}catch(Exception ignored){}}
    }
    @SuppressWarnings("unchecked") private void markInstallation(Map<String,Object> plan,String state){
        if(!Set.of("INSTALL","RECOVER").contains(plan.get("kind")))return;
        store.mutate(data->{var server=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(plan.get("serverId"));if(server==null)throw new ControlFailure(409,"SERVER_REVISION_CHANGED");ControlServers.expected(server,((Number)plan.get("serverRevision")).longValue());server.put("installation",Map.of("state",state,"jobId",plan.get("id")));return null;});
    }
    private void clearCompletedSecrets(Map<String,Object> job,Map<String,Object> plan){
        if(Set.of("SUCCEEDED","FAILED").contains(job.get("state"))){plan.remove("_server");plan.remove("_payload");store.saveJson(planPath(UUID.fromString(plan.get("id").toString())),plan);}
    }
    private void validateInstallReply(Map<String,Object> reply,Map<String,Object> plan,UUID id)throws Exception{
        validateIdentityReply(reply,plan);
        if(!"eatwhat-install-v1".equals(reply.get("protocol"))||!id.toString().equals(reply.get("jobId"))||!plan.get("serverId").equals(reply.get("serverId"))||!plan.get("artifactSha256").equals(reply.get("artifactSha256"))||!plan.get("root").equals(reply.get("root"))||!plan.get("_siteId").equals(reply.get("siteId"))||!plan.get("_instanceId").equals(reply.get("instanceId"))||!Set.of("SUCCEEDED","FAILED").contains(reply.get("state")))throw new Exception();
        if("SUCCEEDED".equals(reply.get("state"))&&(!(reply.get("restoreEvidenceId") instanceof String)||!reply.get("restoreEvidenceId").equals("fresh-install-restore-"+id)||!reply.get("httpPort").toString().equals(plan.get("httpPort").toString())))throw new Exception();
    }
    private void validateRecoveryReply(Map<String,Object> reply,Map<String,Object> plan,UUID id)throws Exception{
        validateIdentityReply(reply,plan);
        if(!"eatwhat-install-recovery-v1".equals(reply.get("protocol"))||!id.toString().equals(reply.get("jobId"))||!Set.of("SUCCEEDED","FAILED").contains(reply.get("state")))throw new Exception();
        for(String key:List.of("serverId","sourceJobId","siteId","instanceId","artifactSha256","root","originalInstallJobId"))if(!Objects.equals(plan.get(key),reply.get(key)))throw new Exception();
        if("SUCCEEDED".equals(reply.get("state"))){Object evidence=reply.get("restoreEvidenceId");if(!(evidence instanceof String)||!List.of("fresh-install-restore-"+id,"fresh-install-restore-"+plan.get("originalInstallJobId"),Objects.toString(plan.get("_restoreEvidenceId"),"")).contains(evidence)||!Objects.toString(plan.get("httpPort")).equals(Objects.toString(reply.get("httpPort"))))throw new Exception();}
    }
    private void validateIdentityReply(Map<String,Object> reply,Map<String,Object> plan)throws Exception{
        if(!Objects.equals(Objects.toString(reply.get("mode"),"CAMPUS"),Objects.toString(plan.get("mode"),"CAMPUS")))throw new Exception();
        for(String key:List.of("clusterId","allocationSlot"))if(!Objects.equals(plan.get(key),reply.get(key)))throw new Exception();
    }
    @SuppressWarnings("unchecked") private void installed(Map<String,Object> plan,Map<String,Object> reply){store.mutate(data->{var server=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(plan.get("serverId"));if(server==null)throw new ControlFailure(409,"SERVER_REVISION_CHANGED");ControlServers.expected(server,((Number)plan.get("serverRevision")).longValue());var result=new LinkedHashMap<String,Object>();for(String key:List.of("siteId","instanceId","httpPort","root","restoreEvidenceId"))result.put(key,reply.get(key));result.put("mode",Objects.toString(plan.get("mode"),"CAMPUS"));for(String key:List.of("clusterId","allocationSlot"))if(plan.containsKey(key))result.put(key,plan.get(key));result.put("state","SUCCEEDED");result.put("currentSha256",reply.get("artifactSha256"));result.put("appBaseUrl",plan.get("appBaseUrl"));result.put("jobId",plan.get("id"));result.put("clusterMembership",plan.get("kind").equals("UPDATE")?Objects.toString(((Map<?,?>)server.get("installation")).get("clusterMembership"),"NOT_JOINED"):"NOT_JOINED");server.put("installation",result);return null;});}
    private Map<String,Object> updateCall(Map<String,Object> server,Map<String,Object> plan,Map<String,Object> prior,UUID id,String action,Path artifact)throws Exception{return ssh.call(server,ControlServers.resource("server-update.py"),List.of(plan.get("serverId").toString(),action,id.toString(),plan.get("artifactSha256").toString(),prior.get("siteId").toString(),prior.get("instanceId").toString(),prior.get("currentSha256").toString()),out->{if(action.equals("apply"))Files.copy(artifact,out);},900);}
    private void verifyUpdate(Map<String,Object> reply,Map<String,Object> plan,Map<String,Object> prior,UUID id,String state)throws Exception{if(!id.toString().equals(reply.get("id"))||!plan.get("artifactSha256").equals(reply.get("artifactSha256"))||!prior.get("siteId").equals(reply.get("siteId"))||!prior.get("instanceId").equals(reply.get("instanceId"))||!state.equals(reply.get("state")))throw new Exception();}
    @SuppressWarnings("unchecked") public Map<String,Object> job(UUID id){if(!Files.exists(jobPath(id)))throw new ControlFailure(404,"SERVER_JOB_NOT_FOUND");var job=store.readJson(jobPath(id));if(!owned.contains(id)&&Set.of("QUEUED","RUNNING").contains(job.get("state"))){job.put("state","OUTCOME_UNKNOWN");job.put("code","SERVER_TASK_OUTCOME_UNKNOWN");}return publicPlan(job);}
    public synchronized Map<String,Object> reconcile(String actor,UUID id){
        servers.requireWrite();if(owned.contains(id))return job(id);
        try(var channel=FileChannel.open(store.directory().resolve("deployment.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new ControlFailure(409,"DEPLOYMENT_RUNNING");}if(lock==null)throw new ControlFailure(409,"DEPLOYMENT_RUNNING");try(lock){return reconcileLocked(actor,id);}
        }catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"SERVER_INSTALLATION_UNAVAILABLE");}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> reconcileLocked(String actor,UUID id){
        var job=store.readJson(jobPath(id));var plan=(Map<String,Object>)job.get("_plan");if(!"OUTCOME_UNKNOWN".equals(job(id).get("state")))return job(id);
        var server=store.server(UUID.fromString((String)plan.get("serverId")));ControlServers.expected(server,((Number)plan.get("serverRevision")).longValue());
        try{
            Map<String,Object> reply;
            if(plan.get("kind").equals("INSTALL")){reply=ssh.call(server,ControlServers.resource("first-install.py"),List.of("status"),out->writeHeader(out,Map.of("serverId",plan.get("serverId"),"jobId",id.toString())),30);
                if("NOT_STARTED".equals(reply.get("state"))&&"RELEASED".equals(reply.get("lockProof"))&&Boolean.TRUE.equals(reply.get("noInstallationDirectory"))&&"eatwhat-install-v1".equals(reply.get("protocol"))&&id.toString().equals(reply.get("jobId"))&&plan.get("serverId").equals(reply.get("serverId"))&&plan.get("root").equals(reply.get("root"))){
                    // The new bundled agent holds this lock before its first receipt/effect. A released
                    // lock plus an absent installation is positive no-start evidence; old absent journals aren't.
                    reply=new LinkedHashMap<>(reply);reply.put("observedState","NOT_STARTED");reply.put("state","FAILED");reply.put("code","FIRST_INSTALL_NOT_STARTED_PROVEN");reply.put("retryable",true);reply.put("identityProof","NOT_CREATED");reply.put("siteId",plan.get("_siteId"));reply.put("instanceId",plan.get("_instanceId"));reply.put("artifactSha256",plan.get("artifactSha256"));
                }
                validateInstallReply(reply,plan,id);if("SUCCEEDED".equals(reply.get("state")))installed(plan,reply);}
            else if(plan.get("kind").equals("RECOVER")){reply=ssh.call(server,recoveryAgent(),List.of("status"),out->writeHeader(out,Map.of("serverId",plan.get("serverId"),"jobId",id.toString())),30);
                if("NOT_STARTED".equals(reply.get("state"))&&"RELEASED".equals(reply.get("lockProof"))&&"eatwhat-install-recovery-v1".equals(reply.get("protocol"))&&id.toString().equals(reply.get("jobId"))&&plan.get("serverId").equals(reply.get("serverId"))){var proven=new LinkedHashMap<String,Object>();for(String key:List.of("serverId","sourceJobId","siteId","instanceId","artifactSha256","root","originalInstallJobId"))proven.put(key,plan.get(key));proven.putAll(reply);proven.put("state","FAILED");proven.put("code","RECOVERY_NOT_STARTED_PROVEN");reply=proven;}
                validateRecoveryReply(reply,plan,id);if("SUCCEEDED".equals(reply.get("state")))installed(plan,reply);}
            else{var prior=(Map<String,Object>)plan.get("_installation");reply=updateCall(server,plan,prior,id,"status",null);String state=(String)reply.get("state");if(!Set.of("SUCCEEDED","ROLLED_BACK","FAILED","NOT_STARTED").contains(state))throw new Exception();verifyUpdate(reply,plan,prior,id,state);if(state.equals("SUCCEEDED")){var result=new LinkedHashMap<>(prior);result.put("artifactSha256",plan.get("artifactSha256"));installed(plan,result);}}
            String state=(String)reply.get("state");job.put("state",state.equals("SUCCEEDED")?"SUCCEEDED":"FAILED");job.put("result",reply);job.remove("code");if(!job.get("state").equals("SUCCEEDED"))markInstallation(plan,Boolean.TRUE.equals(reply.get("retryable"))?"NOT_INSTALLED":"FAILED");clearCompletedSecrets(job,plan);store.saveJson(jobPath(id),job);audit.append(id,actor,"SERVER_TASK_RECONCILE",plan.get("serverId").toString(),job.get("state").toString());return publicPlan(job);
        }catch(Exception e){throw new ControlFailure(409,"SERVER_TASK_RECONCILIATION_REQUIRED");}
    }
    @Override public synchronized void close(){closed=true;worker.shutdown();try{if(!worker.awaitTermination(2,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException e){worker.shutdownNow();Thread.currentThread().interrupt();}}
}
