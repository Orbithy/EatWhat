package you.v50to.eatwhat.service.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.storage.replication.FileReplicationEnqueuer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileLifecycleService {
    private final JdbcTemplate jdbc;
    private final FileObjectStorage objects;
    private final S3StorageProperties storage;
    private final FileLifecycleProperties settings;
    private final TransactionTemplate independentTransaction;
    private final ObjectProvider<FileReplicationEnqueuer> replicationEnqueuer;
    private final you.v50to.eatwhat.cluster.ClusterBarrier clusterBarrier;

    @Autowired
    public FileLifecycleService(JdbcTemplate jdbc, FileObjectStorage objects, S3StorageProperties storage,
                                FileLifecycleProperties settings, PlatformTransactionManager txManager,
                                ObjectProvider<FileReplicationEnqueuer> replicationEnqueuer) {
        this.jdbc = jdbc; this.objects = objects; this.storage = storage; this.settings = settings;
        this.clusterBarrier=new you.v50to.eatwhat.cluster.ClusterBarrier(jdbc);
        this.replicationEnqueuer = replicationEnqueuer;
        this.independentTransaction = new TransactionTemplate(txManager);
        this.independentTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    // Overloaded constructor for tests that don't use Spring DI
    public FileLifecycleService(JdbcTemplate jdbc, FileObjectStorage objects, S3StorageProperties storage,
                                FileLifecycleProperties settings, PlatformTransactionManager txManager) {
        this(jdbc, objects, storage, settings, txManager,
                new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(FileReplicationEnqueuer.class));
    }

    public ObjectStorageService.PresignedUpload presign(Long userId, UploadBiz biz, String fileName, String type, Long size) {
        requireEnabled();
        String mediaType=normalizeType(type);
        validateDeclaration(mediaType, size);
        UUID ownerGid = jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?", UUID.class, userId);
        enforcePresignLimits(ownerGid, size);
        UUID id = UUID.randomUUID();
        String key = buildLogicalKey(biz, userId, fileName);
        String staging = "staging/" + id + "/" + UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(settings.getIntentTtlSeconds());
        jdbc.update("""
            INSERT INTO eatwhat_file.upload_intent(intent_id,owner_id,owner_gid,biz,logical_key,staging_key,declared_size,declared_type,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, id,userId,ownerGid,biz.getValue(),key,staging,size,mediaType,Timestamp.from(expiry));
        long putExpiry=Instant.now().plusSeconds(storage.getSignPutTtlSeconds()).toEpochMilli();
        String putUrl=objects.presignStagingPut(staging,mediaType,storage.getSignPutTtlSeconds());
        return new ObjectStorageService.PresignedUpload(putUrl,key,putExpiry);
    }

    /**
     * Per-user presign limits (0 = unlimited, the default, so nothing changes unless configured). Both are soft,
     * unlocked checks on the database clock: concurrent presigns of one user can overshoot them slightly, which is
     * acceptable for an abuse limit. A rejection uses TOO_MANY_REQUESTS, the project's existing rate-limit code.
     */
    private void enforcePresignLimits(UUID ownerGid, long size) {
        int maxOpen = settings.getMaxOpenIntentsPerUser();
        if (maxOpen > 0) {
            Integer open = jdbc.queryForObject("""
                    SELECT count(*) FROM eatwhat_file.upload_intent
                    WHERE owner_gid=? AND status IN ('PENDING','CLAIMED') AND expires_at>clock_timestamp()
                    """, Integer.class, ownerGid);
            if (open != null && open >= maxOpen) {
                throw new BizException(BizCode.TOO_MANY_REQUESTS, "未完成的上传过多，请先完成或稍后再试");
            }
        }
        long maxBytes = settings.getMaxBytesPerHourPerUser();
        if (maxBytes > 0) {
            Long recent = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(declared_size),0) FROM eatwhat_file.upload_intent
                    WHERE owner_gid=? AND created_at>clock_timestamp()-interval '1 hour'
                    """, Long.class, ownerGid);
            if (recent != null && recent + size > maxBytes) {
                throw new BizException(BizCode.TOO_MANY_REQUESTS, "近一小时上传总量超出限制，请稍后再试");
            }
        }
    }

    public CompletedFile complete(Long userId, String key) {
        Intent intent = loadIntent(key);
        if (intent == null) throw invalid("上传登记不存在");
        if (!intent.ownerId.equals(userId)) throw new BizException(BizCode.NO_PERMISSION,"无权完成此上传");
        if ("COMPLETE".equals(intent.status)) return loadCompleted(key,userId);
        requireEnabled();
        if (!intent.expiresAt.isAfter(Instant.now())) throw invalid("上传登记已过期");
        Claim claim = independentTransaction.execute(status -> jdbc.query("""
            UPDATE eatwhat_file.upload_intent SET status='CLAIMED',fencing_token=fencing_token+1,
              lease_until=now()+(? * interval '1 second'),updated_at=now()
             WHERE intent_id=? AND expires_at>now() AND (status='PENDING' OR (status='CLAIMED' AND lease_until<now()))
            RETURNING fencing_token
            """, rs -> rs.next()?new Claim(rs.getLong(1)):null, settings.getClaimLeaseSeconds(),intent.id));
        if (claim == null) {
            if (isComplete(key)) return loadCompleted(key,userId);
            throw new BizException(BizCode.OP_FAILED,"上传正在处理中，请重试");
        }
        UUID candidate=UUID.randomUUID();
        String finalKey="verified/"+candidate.toString().replace("-","");
        independentTransaction.executeWithoutResult(status -> jdbc.update("INSERT INTO eatwhat_file.file_orphan_candidate(candidate_id,intent_id,fencing_token,object_key,state) VALUES (?,?,?,?,'WRITING')",
                candidate,intent.id,claim.token,finalKey));
        Path temp=null;
        try {
            FileObjectStorage.StagedMetadata head=objects.head(intent.stagingKey);
            if(head.contentLength()<0||head.contentLength()!=intent.size)throw invalid("对象大小与登记不符");
            if(StringUtils.hasText(head.contentType())&&!intent.type.equalsIgnoreCase(head.contentType().split(";",2)[0].trim()))throw invalid("对象类型与登记不符");
            temp=Files.createTempFile("eatwhat-upload-",".stage");
            FileObjectStorage.StagedMetadata metadata=objects.downloadToFile(intent.stagingKey,temp,storage.getMaxFileSizeBytes());
            long actual=Files.size(temp);
            if (actual!=intent.size) throw invalid("文件大小与登记不符");
            String actualType=sniff(temp);
            if (metadata.contentLength()>=0 && metadata.contentLength()!=actual) throw invalid("对象存储长度与读取内容不符");
            if (StringUtils.hasText(metadata.contentType()) && !actualType.equalsIgnoreCase(metadata.contentType().split(";",2)[0].trim())) throw invalid("对象存储类型与文件内容不符");
            if (!actualType.equalsIgnoreCase(intent.type)) throw invalid("文件内容类型与登记不符");
            validateDeclaration(actualType,actual);
            String hash=sha256(temp);
            objects.putImmutableFile(finalKey,actualType,temp);
            // Guarded: if the GC (07C) cleaned this candidate while the final PUT was in flight, stop here instead of
            // resurrecting a CLEANED row and committing a revision whose object was deleted.
            independentTransaction.executeWithoutResult(status -> {
                if(jdbc.update("UPDATE eatwhat_file.file_orphan_candidate SET state='WRITTEN',updated_at=now() WHERE candidate_id=? AND state='WRITING'",candidate)!=1)
                    throw new BizException(BizCode.OP_FAILED,"上传候选对象状态已变更，请重试");
            });
            CompletedFile record=new CompletedFile(key,null,1,hash,actual,actualType);
            try {
                independentTransaction.executeWithoutResult(s -> canonicalize(intent,claim,candidate,finalKey,record));
            } catch (RuntimeException failure) {
                try { independentTransaction.executeWithoutResult(s -> jdbc.update("UPDATE eatwhat_file.file_orphan_candidate SET state='ORPHANED',updated_at=now() WHERE candidate_id=? AND state='WRITTEN'",candidate)); }
                catch (RuntimeException markFailure) { failure.addSuppressed(markFailure); }
                throw failure;
            }
            return loadCompleted(key,userId);
        } catch (IOException e) {
            throw new BizException(BizCode.OP_FAILED,"无法校验上传文件，请重试");
        } finally { if (temp!=null) try { Files.deleteIfExists(temp); } catch(IOException ignored) {} }
    }

    public String finalizeIfPending(Long userId, UploadBiz biz, String key) {
        if (!StringUtils.hasText(key)) return key;
        Intent intent=loadIntent(key);
        if (intent==null) {
            if (isComplete(key)) {
                verifyAliasOwnerAndBiz(userId,biz,key);
                return key;
            }
            if (settings.isLifecycleEnabled() && !settings.isLegacyReferenceWriteEnabled()) throw invalid("业务图片必须来自已登记上传");
            return key;
        }
        if (!intent.biz.equals(biz.getValue()) || !intent.ownerId.equals(userId)) throw new BizException(BizCode.NO_PERMISSION,"上传归属或用途不匹配");
        complete(userId,key);
        return key;
    }

    public int releaseReferences(String refKind, String entityId, Collection<String> logicalKeysOrNullForAll, String reason) {
        clusterBarrier.requireWritable();
        // Validate reason
        Set<String> allowedReasons = Set.of("ENTITY_DELETED", "KEY_REPLACED", "PARENT_CASCADE", "ADMIN", "RECONCILE");
        if (!allowedReasons.contains(reason)) {
            throw new IllegalArgumentException("Invalid reason: " + reason + ". Allowed reasons: " + allowedReasons);
        }

        // If keys provided but empty, release nothing
        if (logicalKeysOrNullForAll != null && logicalKeysOrNullForAll.isEmpty()) {
            return 0;
        }

        // Build the WHERE clause to match ref_id = ? OR ref_id LIKE ? || ':%'
        // entityId '12' must match '12' and '12:0', '12:1' but NOT '123' or '12x'
        String escapedEntityId = entityId.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String refIdLike = escapedEntityId + ":%";

        // Insert RELEASE events for matching rows
        String sql;
        Object[] params;

        if (logicalKeysOrNullForAll == null) {
            // Release all matching references
            sql = """
                INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason)
                SELECT f.logical_key, f.ref_kind, f.ref_id, 'RELEASE', ?
                FROM eatwhat_file.file_reference_live f
                WHERE f.ref_kind = ? AND (f.ref_id = ? OR f.ref_id LIKE ? ESCAPE '\\')
                """;
            params = new Object[]{reason, refKind, entityId, refIdLike};
        } else {
            // Release only matching logical keys that exist in the provided collection
            sql = """
                INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason)
                SELECT f.logical_key, f.ref_kind, f.ref_id, 'RELEASE', ?
                FROM eatwhat_file.file_reference_live f
                WHERE f.ref_kind = ? AND (f.ref_id = ? OR f.ref_id LIKE ? ESCAPE '\\')
                  AND f.logical_key = ANY(?)
                """;
            params = new Object[]{reason, refKind, entityId, refIdLike, logicalKeysOrNullForAll.toArray(new String[0])};
        }

        return jdbc.update(sql, params);
    }

    public void finalizeAndReference(Long userId, UploadBiz biz, String key, String refKind, String refId) {
        finalizeAndReference(userId,biz,key,refKind,refId,"PRIVATE");
    }

    public void finalizeAndReference(Long userId, UploadBiz biz, String key, String refKind, String refId,String visibility) {
        if (!StringUtils.hasText(key)) return;
        finalizeIfPending(userId,biz,key);
        clusterBarrier.requireWritable();
        // A physically deleted object (GC) can never be referenced again. The V31 trigger enforces this under the
        // GC's row lock; this check only turns the common case into a clear validation error.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_key_alias a JOIN eatwhat_file.file_replica r ON r.file_id=a.file_id AND r.revision=a.revision AND r.site='LOCAL' WHERE a.logical_key=? AND r.state IN ('DELETING','DELETED'))",Boolean.class,key)))
            throw invalid("文件已被清理，请重新上传");
        // Database projection triggers may have already attached this exact business key. Reuse that live
        // reference after validating ownership above; do not manufacture a second reference for the same entity.
        String entityId=refId.split(":",2)[0];
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live WHERE logical_key=? AND ref_kind=? AND visibility=? AND (ref_id=? OR ref_id LIKE ?))",Boolean.class,key,refKind,visibility,entityId,entityId+":%"))) return;
        // Insert the reference; if it already exists, do nothing
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) SELECT ?,?,?,? WHERE EXISTS (SELECT 1 FROM eatwhat_file.file_key_alias WHERE logical_key=?) ON CONFLICT DO NOTHING",
                key,refKind,refId,visibility,key);

        // Re-referencing a released key revives it: append RESTORE only when the latest event is a RELEASE.
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) "
                        + "SELECT ?,?,?,'RESTORE','ADMIN' WHERE (SELECT e.action FROM eatwhat_file.file_reference_event e "
                        + "WHERE e.logical_key=? AND e.ref_kind=? AND e.ref_id=? ORDER BY e.event_id DESC LIMIT 1)='RELEASE'",
                key,refKind,refId,key,refKind,refId);
    }

    public void finalizeAndReferenceMany(Long userId, UploadBiz biz, Iterable<String> keys, String refKind, String refId) {
        finalizeAndReferenceMany(userId,biz,keys,refKind,refId,"PRIVATE");
    }

    public void finalizeAndReferenceMany(Long userId, UploadBiz biz, Iterable<String> keys, String refKind, String refId,String visibility) {
        if (keys == null) return;
        int index=0;
        for (String key:keys) finalizeAndReference(userId,biz,key,refKind,refId+":"+(index++),visibility);
    }

    public void finalizeAndReferenceManyExcluding(Long userId,UploadBiz biz,Iterable<String> keys,Iterable<String> alreadyReferenced,
                                                   String refKind,String refId,String visibility){
        java.util.Set<String> existing=new java.util.HashSet<>();
        if(alreadyReferenced!=null)alreadyReferenced.forEach(existing::add);
        java.util.List<String> newKeys=new java.util.ArrayList<>();
        if(keys!=null)for(String key:keys)if(key!=null&&!existing.contains(key))newKeys.add(key);
        finalizeAndReferenceMany(userId,biz,newKeys,refKind,refId,visibility);
    }

    public String resolveAlias(String key) {
        return jdbc.query("SELECT r.final_key FROM eatwhat_file.file_key_alias a JOIN eatwhat_file.file_revision r ON r.file_id=a.file_id AND r.revision=a.revision JOIN eatwhat_file.file_replica p ON p.file_id=r.file_id AND p.revision=r.revision AND p.site='LOCAL' WHERE a.logical_key=? AND r.state='VERIFIED' AND p.state='AVAILABLE_LOCAL' AND EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live f WHERE f.logical_key=a.logical_key AND f.visibility='PUBLIC')",
                rs -> rs.next()?rs.getString(1):null,key);
    }

    public String getBiz(String key) {
        Intent intent=loadIntent(key);
        if(intent==null) throw invalid("上传登记不存在");
        return intent.biz;
    }

    private void canonicalize(Intent i,Claim c,UUID candidate,String finalKey,CompletedFile f) {
        clusterBarrier.requireWritable();
        int won=jdbc.update("UPDATE eatwhat_file.upload_intent SET status='COMPLETE',lease_until=NULL,updated_at=now() WHERE intent_id=? AND status='CLAIMED' AND fencing_token=? AND lease_until>now() AND expires_at>now()",i.id,c.token);
        if(won!=1) throw new BizException(BizCode.OP_FAILED,"上传处理租约已失效，请重试");
        UUID file=UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id,owner_gid,biz,logical_key,canonical_revision) VALUES (?,?,?,?,1)",file,i.ownerGid,i.biz,i.key);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id,revision,final_key,sha256,size_bytes,content_type,state,verified_at) VALUES (?,1,?,?,?,?, 'VERIFIED',now())",file,finalKey,f.sha256,f.size,f.contentType);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id,revision,site,object_key,state,verified_sha256,verified_size,verified_at) VALUES (?,1,'LOCAL',?,'AVAILABLE_LOCAL',?,?,now())",file,finalKey,f.sha256,f.size);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key,file_id,revision,owner_gid,biz) VALUES (?,?,1,?,?)",i.key,file,i.ownerGid,i.biz);
        // The candidate must still be WRITTEN: if the GC (07C) cleaned it while this transaction was blocked on the
        // row lock, the object is gone, so this transaction must roll back instead of committing a revision for it.
        int canonical=jdbc.update("UPDATE eatwhat_file.file_orphan_candidate SET state='CANONICAL',updated_at=now() WHERE candidate_id=? AND state='WRITTEN'",candidate);
        if(canonical!=1) throw new BizException(BizCode.OP_FAILED,"上传候选对象状态已变更，请重试");
        // Hook: enqueue peer replication if enabled (same transaction)
        replicationEnqueuer.ifAvailable(e -> e.onRevisionVerified(file, 1, finalKey));
    }

    private Intent loadIntent(String key) {
        return jdbc.query("SELECT intent_id,owner_id,owner_gid,biz,logical_key,staging_key,declared_size,declared_type,status,expires_at FROM eatwhat_file.upload_intent WHERE logical_key=?",
                rs->rs.next()?new Intent(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getString(8),rs.getString(9),rs.getTimestamp(10).toInstant()):null,key);
    }
    private boolean isComplete(String key){ return jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?",Integer.class,key)>0; }
    private void verifyAliasOwnerAndBiz(Long userId,UploadBiz biz,String key){
        UUID expected=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,userId);
        Integer matches=jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=? AND owner_gid=? AND biz=?",Integer.class,key,expected,biz.getValue());
        if(matches==null||matches!=1)throw new BizException(BizCode.NO_PERMISSION,"文件归属或用途不匹配");
    }
    private CompletedFile loadCompleted(String key,Long uid){
        return jdbc.query("SELECT o.file_id,r.revision,r.sha256,r.size_bytes,r.content_type,o.owner_gid FROM eatwhat_file.file_key_alias a JOIN eatwhat_file.file_object o ON o.file_id=a.file_id JOIN eatwhat_file.file_revision r ON r.file_id=a.file_id AND r.revision=a.revision WHERE a.logical_key=? AND r.state='VERIFIED'",
            rs->{if(!rs.next())throw new BizException(BizCode.OP_FAILED,"文件尚未完成校验");
                UUID caller=jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?",UUID.class,uid);
                if(!caller.equals(rs.getObject(6,UUID.class)))throw new BizException(BizCode.NO_PERMISSION,"无权读取此上传");
                return new CompletedFile(key,rs.getObject(1,UUID.class),rs.getInt(2),rs.getString(3).trim(),rs.getLong(4),rs.getString(5));},key);
    }
    private void validateDeclaration(String type,Long size){
        String mediaType=normalizeType(type);
        if(!StringUtils.hasText(mediaType)||size==null||size<1||size>storage.getMaxFileSizeBytes())throw invalid("文件声明非法");
        if(storage.getAllowedContentTypes()!=null&&!storage.getAllowedContentTypes().isEmpty()&&storage.getAllowedContentTypes().stream().noneMatch(mediaType::equalsIgnoreCase))throw invalid("不支持的文件类型");
    }
    private String normalizeType(String type){return StringUtils.hasText(type)?type.split(";",2)[0].trim().toLowerCase(Locale.ROOT):null;}
    private String sniff(Path file)throws IOException{
        byte[] b=new byte[16];int n;try(var in=Files.newInputStream(file)){n=in.read(b);}
        if(n>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255)return "image/jpeg";
        if(n>=8&&(b[0]&255)==137&&b[1]=='P'&&b[2]=='N'&&b[3]=='G')return "image/png";
        if(n>=6&&b[0]=='G'&&b[1]=='I'&&b[2]=='F')return "image/gif";
        if(n>=12&&b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P')return "image/webp";
        throw invalid("无法识别文件内容类型");
    }
    private String sha256(Path file)throws IOException{
        try{MessageDigest d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private String buildLogicalKey(UploadBiz biz,Long uid,String name){
        if(uid==null||uid<=0)throw invalid("用户ID非法");String ext="";
        if(StringUtils.hasText(name)){int i=name.lastIndexOf('.');if(i>=0&&i<name.length()-1){ext=name.substring(i+1).toLowerCase(Locale.ROOT);if(!ext.matches("[a-z0-9]{1,8}"))throw invalid("文件后缀非法");}}
        return biz.getValue()+"/"+uid+"/"+YearMonth.now().toString().replace("-","")+"/"+UUID.randomUUID().toString().replace("-","")+(ext.isEmpty()?"":"."+ext);
    }
    private void requireEnabled(){if(!settings.isLifecycleEnabled())throw new BizException(BizCode.STATE_NOT_ALLOWED,"新上传流程尚未启用");}
    private BizException invalid(String message){return new BizException(BizCode.PARAM_INVALID,message);}
    public record CompletedFile(String key,UUID fileId,int revision,String sha256,long size,String contentType){}
    private record Claim(long token){}
    private record Intent(UUID id,Long ownerId,UUID ownerGid,String biz,String key,String stagingKey,long size,String type,String status,Instant expiresAt){}
}
