SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE SCHEMA eatwhat_account_multiwriter;
REVOKE ALL ON SCHEMA eatwhat_account_multiwriter FROM PUBLIC;
CREATE TABLE eatwhat_account_multiwriter.configuration(singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),enabled boolean NOT NULL DEFAULT false);
INSERT INTO eatwhat_account_multiwriter.configuration VALUES(true,false);
CREATE TABLE eatwhat_account_multiwriter.operation(
  event_id uuid PRIMARY KEY,subject_id uuid NOT NULL,field varchar(32) NOT NULL,
  value text NOT NULL CHECK(length(value)<=32768),parents uuid[] NOT NULL DEFAULT '{}',
  source_site varchar(64) NOT NULL,source_instance uuid NOT NULL,actor_id bigint NOT NULL,
  security_heads uuid[] NOT NULL DEFAULT '{}',local_order bigserial UNIQUE NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK(field IN ('password','username','email','phone','verification','banned','role','consent')),
  CHECK(cardinality(parents)<=1024 AND NOT event_id=ANY(parents)));
CREATE INDEX account_mw_subject_field ON eatwhat_account_multiwriter.operation(subject_id,field);
CREATE TABLE eatwhat_account_multiwriter.command_context(transaction_id bigint NOT NULL,backend_id integer NOT NULL,actor_id bigint NOT NULL,user_id bigint NOT NULL,security_heads uuid[] NOT NULL,importing boolean NOT NULL DEFAULT false,PRIMARY KEY(transaction_id,backend_id,user_id));
CREATE TABLE eatwhat_account_multiwriter.session(token_hash char(64) PRIMARY KEY,user_id bigint NOT NULL,subject_id uuid NOT NULL,source_site varchar(64) NOT NULL,security_hash char(64) NOT NULL,expires_at timestamptz NOT NULL,revoked boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE eatwhat_account_multiwriter.delivery(peer_site varchar(64) NOT NULL,event_id uuid NOT NULL REFERENCES eatwhat_account_multiwriter.operation(event_id),PRIMARY KEY(peer_site,event_id));
CREATE TABLE eatwhat_account_multiwriter.alias_claim(subject_id uuid NOT NULL,kind varchar(16) NOT NULL,value text NOT NULL,PRIMARY KEY(subject_id,kind));
CREATE INDEX account_mw_alias ON eatwhat_account_multiwriter.alias_claim(kind,value);
CREATE TABLE eatwhat_account_multiwriter.conflict(subject_id uuid NOT NULL,field varchar(32) NOT NULL,heads uuid[] NOT NULL,PRIMARY KEY(subject_id,field));
CREATE FUNCTION eatwhat_account_multiwriter.heads(p_subject uuid,p_field text) RETURNS uuid[]
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT coalesce(array_agg(o.event_id ORDER BY o.event_id),'{}'::uuid[]) FROM eatwhat_account_multiwriter.operation o
 WHERE o.subject_id=p_subject AND o.field=p_field AND NOT EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.operation child WHERE child.subject_id=p_subject AND child.field=p_field AND o.event_id=ANY(child.parents))
$$;
CREATE FUNCTION eatwhat_account_multiwriter.begin_command(p_actor bigint,p_uid bigint) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE actor public.users%ROWTYPE; gid uuid; heads uuid[];
BEGIN
 IF NOT (SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton) THEN RAISE EXCEPTION 'Account multiwriter is disabled'; END IF;
 SELECT * INTO actor FROM public.users WHERE id=p_actor FOR SHARE;
 IF NOT FOUND OR (actor.banned AND p_actor<>p_uid) OR (p_actor<>p_uid AND actor.role<>'admin') THEN RAISE EXCEPTION 'Account actor not authorized' USING ERRCODE='42501'; END IF;
 SELECT global_id INTO gid FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=p_actor;
 heads:=eatwhat_account_multiwriter.heads(gid,'role')||eatwhat_account_multiwriter.heads(gid,'banned');
 PERFORM id FROM public.users WHERE id=p_uid FOR UPDATE;
 INSERT INTO eatwhat_account_multiwriter.command_context VALUES(txid_current(),pg_backend_pid(),p_actor,p_uid,heads,false) ON CONFLICT DO NOTHING;
 RETURN true;
END $$;
CREATE FUNCTION eatwhat_account_multiwriter.append_local(p_uid bigint,p_field text,p_value text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE gid uuid; node eatwhat_identity.node_state%ROWTYPE; ctx eatwhat_account_multiwriter.command_context%ROWTYPE; eid uuid:=gen_random_uuid();
BEGIN
 IF p_field='email' THEN p_value:=lower(p_value); END IF;
 IF p_field='username' AND (p_value LIKE '~mw~%' OR p_value LIKE 'id:%') THEN RAISE EXCEPTION 'Reserved account recovery namespace' USING ERRCODE='23514'; END IF;
 SELECT * INTO ctx FROM eatwhat_account_multiwriter.command_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid() AND user_id=p_uid;
 IF NOT FOUND OR ctx.importing THEN RAISE EXCEPTION 'Account command context required' USING ERRCODE='42501'; END IF;
 SELECT global_id INTO gid FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=p_uid;
 SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
 IF EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.operation WHERE event_id=ANY(ctx.security_heads) AND field='banned' AND value='true') AND (p_field<>'consent' OR p_value<>'WITHDRAWN') THEN RAISE EXCEPTION 'Banned accounts may only withdraw consent' USING ERRCODE='42501'; END IF;
 IF p_field IN ('role','banned') AND NOT EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.operation WHERE event_id=ANY(ctx.security_heads) AND field='role' AND value='admin') THEN RAISE EXCEPTION 'Account administration required' USING ERRCODE='42501'; END IF;
 INSERT INTO eatwhat_account_multiwriter.operation(event_id,subject_id,field,value,parents,source_site,source_instance,actor_id,security_heads)
 VALUES(eid,gid,p_field,p_value,eatwhat_account_multiwriter.heads(gid,p_field),node.site_id,node.instance_id,ctx.actor_id,ctx.security_heads);
 IF p_field IN ('username','email','phone') THEN
   DELETE FROM eatwhat_account_multiwriter.alias_claim WHERE subject_id=gid AND kind=p_field;
   IF p_value<>'null' THEN INSERT INTO eatwhat_account_multiwriter.alias_claim VALUES(gid,p_field,p_value); END IF;
 END IF;
 RETURN eid;
END $$;
CREATE FUNCTION eatwhat_account_multiwriter.capture_account() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid bigint; field text; before jsonb; after jsonb; value text; ctx eatwhat_account_multiwriter.command_context%ROWTYPE; gid uuid;
BEGIN
 IF NOT (SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton) THEN RETURN NULL; END IF;
 IF TG_TABLE_NAME='users' THEN uid:=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END; ELSE uid:=CASE WHEN TG_OP='DELETE' THEN OLD.account_id ELSE NEW.account_id END; END IF;
 SELECT global_id INTO gid FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=uid;
 IF EXISTS(SELECT 1 FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid() AND ((subject_id=gid) OR (TG_TABLE_NAME='users' AND entity_type='users' AND numeric_id=uid AND global_id=subject_id))) THEN RETURN NULL; END IF;
 SELECT * INTO ctx FROM eatwhat_account_multiwriter.command_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid() AND user_id=uid;
 IF ctx.importing THEN RETURN NULL; END IF;
 IF TG_TABLE_NAME='users' AND TG_OP='INSERT' THEN
   IF NEW.nick_name LIKE '~mw~%' OR NEW.nick_name LIKE 'id:%' THEN RAISE EXCEPTION 'Reserved account recovery namespace' USING ERRCODE='23514'; END IF;
   IF NEW.role<>'user' OR NEW.banned THEN RAISE EXCEPTION 'New accounts cannot assign privileges' USING ERRCODE='42501'; END IF;
   RETURN NULL;
 END IF;
 IF TG_OP='DELETE' THEN after:='{}'::jsonb; ELSE after:=to_jsonb(NEW); END IF;
 IF TG_OP='INSERT' THEN before:='{}'::jsonb; ELSE before:=to_jsonb(OLD); END IF;
 IF TG_TABLE_NAME='users' THEN
   FOREACH field IN ARRAY ARRAY['password_hash','nick_name','banned','role'] LOOP
     IF before->field IS DISTINCT FROM after->field THEN
       PERFORM eatwhat_account_multiwriter.append_local(uid,CASE field WHEN 'password_hash' THEN 'password' WHEN 'nick_name' THEN 'username' ELSE field END,coalesce(after->>field,'null'));
     END IF;
   END LOOP;
 ELSIF TG_TABLE_NAME='contacts' THEN
   FOREACH field IN ARRAY ARRAY['email','phone'] LOOP
     IF before->field IS DISTINCT FROM after->field THEN PERFORM eatwhat_account_multiwriter.append_local(uid,field,coalesce(after->>field,'null')); END IF;
   END LOOP;
 ELSE
   IF before-'id'-'created_at'-'updated_at' IS DISTINCT FROM after-'id'-'created_at'-'updated_at' THEN
     PERFORM eatwhat_account_multiwriter.append_local(uid,'verification',(after-'id'-'created_at'-'updated_at')::text);
   END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER account_multiwriter_capture AFTER INSERT OR UPDATE OR DELETE ON public.users FOR EACH ROW EXECUTE FUNCTION eatwhat_account_multiwriter.capture_account();
CREATE TRIGGER account_multiwriter_capture AFTER INSERT OR UPDATE OR DELETE ON public.contacts FOR EACH ROW EXECUTE FUNCTION eatwhat_account_multiwriter.capture_account();
CREATE TRIGGER account_multiwriter_capture AFTER INSERT OR UPDATE OR DELETE ON public.verifications FOR EACH ROW EXECUTE FUNCTION eatwhat_account_multiwriter.capture_account();
REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_account_multiwriter FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA eatwhat_account_multiwriter FROM PUBLIC;

-- New writes use immutable field operations; disabled mode keeps the exact V24 behavior.
CREATE OR REPLACE FUNCTION eatwhat_consent.track_account_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid bigint; s eatwhat_consent.subject%ROWTYPE; node eatwhat_identity.node_state%ROWTYPE; kind text;
  gid uuid; managed boolean;
BEGIN
  IF (SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' AND TG_TABLE_NAME NOT IN ('users','user_info') THEN
    IF NEW.account_id IS DISTINCT FROM OLD.account_id THEN
      RAISE EXCEPTION 'Account relationships cannot be reassigned' USING ERRCODE='23514';
    END IF;
  END IF;
  IF TG_TABLE_NAME IN ('users','user_info') THEN uid:=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
  ELSE uid:=CASE WHEN TG_OP='DELETE' THEN OLD.account_id ELSE NEW.account_id END; END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.command_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid() AND user_id=uid AND importing) THEN RETURN NULL; END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RETURN NULL; END IF;
  SELECT * INTO s FROM eatwhat_consent.subject WHERE user_id=uid FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_consent.import_context WHERE transaction_id=txid_current()
      AND backend_id=pg_backend_pid() AND subject_id=s.subject_id) THEN RETURN NULL; END IF;

  IF TG_TABLE_NAME='users' AND TG_OP='UPDATE' THEN
    IF (to_jsonb(NEW)-'avatar'-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'avatar'-'updated_at')
        AND EXISTS(SELECT 1 FROM eatwhat_sync.profile_domain d WHERE d.subject_gid=s.subject_id AND d.managed) THEN
      RETURN NULL;
    END IF;
  END IF;
  IF TG_TABLE_NAME='user_info' AND TG_OP IN ('INSERT','UPDATE') THEN
    IF EXISTS(SELECT 1 FROM eatwhat_sync.profile_domain d WHERE d.subject_gid=s.subject_id AND d.managed) THEN
      RETURN NULL;
    END IF;
  END IF;
  IF TG_TABLE_NAME='privacy' AND TG_OP IN ('INSERT','UPDATE') THEN
    IF EXISTS(SELECT 1 FROM eatwhat_sync.privacy_domain d WHERE d.subject_gid=s.subject_id AND d.managed) THEN
      RETURN NULL;
    END IF;
  END IF;

  IF TG_OP='DELETE' AND TG_TABLE_NAME='users' THEN
    UPDATE eatwhat_consent.subject SET epoch=epoch+CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END,
      revision=revision+1,status='WITHDRAWN',valid_until=NULL,identity_ready=false,backfill_status='WITHDRAWN',
      cleanup_status='PENDING',effects_pending=true,effects_version=effects_version+1,
      withdrawal_request_id=CASE WHEN node.mode='EXTERNAL' THEN gen_random_uuid() ELSE withdrawal_request_id END
      WHERE subject_id=s.subject_id RETURNING * INTO s;
    kind:=CASE WHEN node.mode='CAMPUS' THEN 'PURGE' ELSE 'WITHDRAW_REQUEST' END;
  ELSIF node.mode='CAMPUS' THEN
    UPDATE eatwhat_consent.subject SET revision=revision+1,
      backfill_status=CASE WHEN status='GRANTED' THEN 'ACCOUNT_PENDING' ELSE backfill_status END
      WHERE subject_id=s.subject_id RETURNING * INTO s;
    IF s.status='GRANTED' THEN kind:='UPSERT'; ELSIF s.status='WITHDRAWN' THEN kind:='PURGE'; END IF;
  ELSIF (s.status IN ('PROVISIONAL','CONFLICT') OR (s.status='WITHDRAWN' AND NOT s.source_archived)) AND s.origin_site=node.site_id THEN
    UPDATE eatwhat_consent.subject SET revision=revision+1,source_archived=false WHERE subject_id=s.subject_id RETURNING * INTO s;
    kind:='REGISTER';
  ELSIF s.status='GRANTED' THEN
    UPDATE eatwhat_consent.subject SET local_dirty=true,backfill_status='CONFLICT',revision=revision+1,
      effects_pending=true,effects_version=effects_version+1 WHERE subject_id=s.subject_id;
  END IF;
  IF kind IS NOT NULL THEN
    INSERT INTO eatwhat_consent.job(subject_id,user_id,epoch,revision,kind)
      VALUES(s.subject_id,s.user_id,s.epoch,s.revision,kind) ON CONFLICT DO NOTHING;
  END IF;
  RETURN NULL;
END $$;
CREATE FUNCTION eatwhat_account_multiwriter.initialize(p_uid bigint) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE u public.users%ROWTYPE; gid uuid; field_name text; val text; eid uuid;
BEGIN
 IF NOT (SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton) THEN RAISE EXCEPTION 'Account multiwriter is disabled'; END IF;
 SELECT * INTO u FROM public.users WHERE id=p_uid FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Account not found'; END IF;
 SELECT global_id INTO gid FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=p_uid;
 IF gid IS NULL THEN RAISE EXCEPTION 'Account identity required'; END IF;
 FOREACH field_name IN ARRAY ARRAY['password','username','banned','role','email','phone','verification','consent'] LOOP
   val:=CASE field_name WHEN 'password' THEN u.password_hash WHEN 'username' THEN u.nick_name WHEN 'banned' THEN u.banned::text WHEN 'role' THEN u.role
     WHEN 'email' THEN coalesce((SELECT lower(email::text) FROM public.contacts WHERE account_id=p_uid ORDER BY id LIMIT 1),'null')
     WHEN 'phone' THEN coalesce((SELECT phone FROM public.contacts WHERE account_id=p_uid ORDER BY id LIMIT 1),'null')
     WHEN 'verification' THEN coalesce((SELECT (to_jsonb(v)-'id'-'created_at'-'updated_at')::text FROM public.verifications v WHERE account_id=p_uid),'{}')
     WHEN 'consent' THEN (SELECT status FROM eatwhat_consent.subject WHERE user_id=p_uid) END;
   IF val IS NULL THEN CONTINUE; END IF;
   IF NOT EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.operation WHERE subject_id=gid AND operation.field=field_name) THEN
     eid:=md5(gid::text||':'||field_name||':'||val)::uuid;
     INSERT INTO eatwhat_account_multiwriter.operation(event_id,subject_id,field,value,source_site,source_instance,actor_id)
       VALUES(eid,gid,field_name,val,'baseline','00000000-0000-0000-0000-000000000000',p_uid);
   END IF;
 END LOOP;
 INSERT INTO eatwhat_account_multiwriter.alias_claim VALUES(gid,'username',u.nick_name) ON CONFLICT DO NOTHING;
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.initialize(bigint) FROM PUBLIC;
