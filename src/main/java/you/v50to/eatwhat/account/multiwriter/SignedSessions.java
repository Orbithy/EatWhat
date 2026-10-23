package you.v50to.eatwhat.account.multiwriter;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;

/** Only token hashes cross the account channel; site signatures bind immutable session facts. */
final class SignedSessions {
    record PublicKeyRecord(String keyId,String sourceSite,UUID sourceInstance,String encoded,boolean revoked) { }
    record Fact(UUID eventId,String tokenHash,long userId,UUID subjectId,String sourceSite,UUID sourceInstance,String keyId,String securityHash,Instant expiresAt,boolean revoked,String signature) {
        @Override public String toString(){return "SignedSessionFact[redacted]";}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final SiteProperties site;
    SignedSessions(JdbcTemplate jdbc,SiteProperties site){this.jdbc=jdbc;this.site=site;}
    private Map<String,Object> key(){
        var keys=jdbc.queryForList("SELECT * FROM eatwhat_account_multiwriter.signing_key WHERE source_site=? AND source_instance=? AND private_key IS NOT NULL AND NOT retired AND NOT revoked ORDER BY created_at DESC,key_id LIMIT 1",site.getId(),site.getInstanceId());
        if(!keys.isEmpty())return keys.getFirst();
        try{var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] pub=pair.getPublic().getEncoded();String id=EventCodec.sha256(Base64.getEncoder().encodeToString(pub));
            jdbc.update("INSERT INTO eatwhat_account_multiwriter.signing_key(key_id,source_site,source_instance,public_key,private_key) VALUES(?,?,?,?,?)",id,site.getId(),site.getInstanceId(),pub,pair.getPrivate().getEncoded());
            return Map.of("key_id",id,"public_key",pub,"private_key",pair.getPrivate().getEncoded());
        }catch(GeneralSecurityException invalid){throw new IllegalStateException("SESSION_SIGNER_UNAVAILABLE");}
    }
    Fact sign(String tokenHash,long user,UUID subject,String securityHash,Instant expires,boolean revoked){
        var key=key();var unsigned=new Fact(UUID.randomUUID(),tokenHash,user,subject,site.getId(),site.getInstanceId(),(String)key.get("key_id"),securityHash,expires,revoked,"");
        try{var signature=Signature.getInstance("Ed25519");signature.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec((byte[])key.get("private_key"))));signature.update(bytes(unsigned));var fact=new Fact(unsigned.eventId(),tokenHash,user,subject,site.getId(),site.getInstanceId(),unsigned.keyId(),securityHash,expires,revoked,Base64.getEncoder().encodeToString(signature.sign()));persist(fact);return fact;}catch(GeneralSecurityException invalid){throw new IllegalStateException("SESSION_SIGNER_UNAVAILABLE");}
    }
    List<PublicKeyRecord> publicKeys(){return jdbc.query("SELECT key_id,source_site,source_instance,public_key,revoked FROM eatwhat_account_multiwriter.signing_key WHERE source_site=? AND source_instance=?",(r,n)->new PublicKeyRecord(r.getString(1),r.getString(2),r.getObject(3,UUID.class),Base64.getEncoder().encodeToString(r.getBytes(4)),r.getBoolean(5)),site.getId(),site.getInstanceId());}
    List<Fact> facts(UUID subject){return jdbc.query("SELECT fact::text FROM eatwhat_account_multiwriter.session_fact WHERE subject_id=? ORDER BY created_at,event_id",(r,n)->JSON.readValue(r.getString(1),Fact.class),subject);}
    void receive(List<PublicKeyRecord> keys,List<Fact> facts,NodeIdentity peer,UUID subject){
        for(var key:keys){
            byte[] encoded=Base64.getDecoder().decode(key.encoded());if(encoded.length>256||!key.sourceSite().equals(peer.siteId())||!key.sourceInstance().equals(peer.instanceId())||!key.keyId().equals(EventCodec.sha256(key.encoded())))throw new IllegalArgumentException("Invalid peer session key");
            jdbc.update("INSERT INTO eatwhat_account_multiwriter.signing_key(key_id,source_site,source_instance,public_key) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",key.keyId(),key.sourceSite(),key.sourceInstance(),encoded);
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT source_site=? AND source_instance=? AND public_key=? FROM eatwhat_account_multiwriter.signing_key WHERE key_id=?",Boolean.class,key.sourceSite(),key.sourceInstance(),encoded,key.keyId())))throw new IllegalArgumentException("Changed immutable session key");
            if(key.revoked())jdbc.queryForObject("SELECT eatwhat_account_multiwriter.revoke_received_session_key(?,?,?)",Boolean.class,key.keyId(),key.sourceSite(),key.sourceInstance());
        }
        for(var fact:facts){
            if(!fact.subjectId().equals(subject)||!fact.tokenHash().matches("[0-9a-f]{64}")||!fact.securityHash().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid session fact");
            var key=jdbc.queryForList("SELECT public_key FROM eatwhat_account_multiwriter.signing_key WHERE key_id=? AND source_site=? AND source_instance=?",fact.keyId(),fact.sourceSite(),fact.sourceInstance());
            if(key.size()!=1)throw new IllegalStateException("SESSION_SIGNER_DEPENDENCY_PENDING");
            try{var signature=Signature.getInstance("Ed25519");signature.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec((byte[])key.getFirst().get("public_key"))));signature.update(bytes(fact));if(!signature.verify(Base64.getDecoder().decode(fact.signature())))throw new IllegalArgumentException("Invalid session signature");}catch(GeneralSecurityException invalid){throw new IllegalArgumentException("Invalid session signature");}
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=? AND global_id=?)",Boolean.class,fact.userId(),subject)))throw new IllegalArgumentException("Session identity mismatch");
            persist(fact);
            if(fact.revoked()){jdbc.update("INSERT INTO eatwhat_account_multiwriter.session_revocation(token_hash) VALUES(?) ON CONFLICT DO NOTHING",fact.tokenHash());jdbc.update("UPDATE eatwhat_account_multiwriter.session SET revoked=true WHERE token_hash=?",fact.tokenHash());}
            else jdbc.update("INSERT INTO eatwhat_account_multiwriter.session(token_hash,user_id,subject_id,source_site,security_hash,expires_at,revoked) VALUES(?,?,?,?,?,?,EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.session_revocation WHERE token_hash=?)) ON CONFLICT DO NOTHING",fact.tokenHash(),fact.userId(),subject,fact.sourceSite(),fact.securityHash(),java.sql.Timestamp.from(fact.expiresAt()),fact.tokenHash());
        }
    }
    private void persist(Fact fact){
        String encoded=JSON.writeValueAsString(fact);int count=jdbc.update("INSERT INTO eatwhat_account_multiwriter.session_fact(event_id,subject_id,token_hash,fact) VALUES(?,?,?,?::jsonb) ON CONFLICT DO NOTHING",fact.eventId(),fact.subjectId(),fact.tokenHash(),encoded);
        if(count==0&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT fact=?::jsonb FROM eatwhat_account_multiwriter.session_fact WHERE event_id=?",Boolean.class,encoded,fact.eventId())))throw new IllegalArgumentException("Changed immutable session fact");
    }
    private static byte[] bytes(Fact f){return JSON.writeValueAsBytes(List.of(f.eventId().toString(),f.tokenHash(),f.userId(),f.subjectId().toString(),f.sourceSite(),f.sourceInstance().toString(),f.keyId(),f.securityHash(),f.expiresAt().toString(),f.revoked()));}
}
