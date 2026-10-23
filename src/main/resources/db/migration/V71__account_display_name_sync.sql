SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
-- 昵称（users.display_name）随账号同步到其他站点。
-- 快照同步：导入接受可选的 display_name；快照里没有这个键（来自未升级的站点）时保留本地昵称。
-- 多写：display_name 作为普通账号字段记录操作，值是 JSON 文本（"昵称" 或 null），投影时写回 users。
-- 新版站点导出的快照和操作会被未升级的站点拒收，两站都升级后才能互通。

DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
      WHERE conrelid='eatwhat_account_multiwriter.operation'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%''consent''%' LOOP
    EXECUTE format('ALTER TABLE eatwhat_account_multiwriter.operation DROP CONSTRAINT %I',c.conname);
  END LOOP;
END $$;
ALTER TABLE eatwhat_account_multiwriter.operation ADD CONSTRAINT operation_field_check
  CHECK(field IN ('password','username','email','phone','verification','banned','role','consent','display_name'));

CREATE OR REPLACE FUNCTION eatwhat_consent.import_account(p_subject uuid, p_uid bigint, payload jsonb) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s eatwhat_consent.subject%ROWTYPE; node eatwhat_identity.node_state%ROWTYPE;
  entity text; item jsonb; fields text; allowed text[]; column_name text; numeric bigint; gid uuid; origin text;
  present boolean; same boolean; updates text; row_fields text;
BEGIN
  SELECT * INTO s FROM eatwhat_consent.subject WHERE subject_id=p_subject AND user_id=p_uid FOR UPDATE;
  IF NOT FOUND OR s.status NOT IN ('GRANTED','PROVISIONAL','WITHDRAWN') THEN
    RAISE EXCEPTION 'Account import requires a current consent operation' USING ERRCODE='23514'; END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND OR jsonb_typeof(payload)<>'object' OR (SELECT count(*) FROM jsonb_object_keys(payload))<>5 THEN
    RAISE EXCEPTION 'Invalid account snapshot' USING ERRCODE='23514'; END IF;
  BEGIN
    FOREACH entity IN ARRAY ARRAY['users','contacts','verifications','user_info','privacy'] LOOP
      IF NOT(payload ? entity) OR jsonb_typeof(payload->entity)<>'array' OR jsonb_array_length(payload->entity)>64 THEN
        RAISE EXCEPTION 'Invalid bounded account scope' USING ERRCODE='23514'; END IF;
      IF (entity='users' AND jsonb_array_length(payload->entity)<>1)
        OR (entity IN ('verifications','user_info','privacy') AND jsonb_array_length(payload->entity)>1) THEN
        RAISE EXCEPTION 'Invalid account cardinality' USING ERRCODE='23514'; END IF;
      fields:=CASE entity
        WHEN 'users' THEN 'id,nick_name,password_hash,avatar,created_at,updated_at,banned,ban_reason,banned_at,role'
        WHEN 'contacts' THEN 'id,account_id,email,phone,created_at,updated_at'
        WHEN 'verifications' THEN 'id,account_id,method,verified,student_id,real_name,verified_email,created_at,updated_at'
        WHEN 'user_info' THEN 'id,gender,birthday,signature,location_province_id,location_city_id,hometown_province_id,hometown_city_id,created_at,updated_at'
        WHEN 'privacy' THEN 'id,account_id,following,follower,created_at' END;
      allowed:=string_to_array(fields,',');
      FOR item IN SELECT value FROM jsonb_array_elements(payload->entity) LOOP
        IF jsonb_typeof(item)<>'object' THEN RAISE EXCEPTION 'Invalid account row' USING ERRCODE='23514'; END IF;
        FOR column_name IN SELECT jsonb_object_keys(item) LOOP
          IF NOT(column_name=ANY(allowed)) AND NOT(entity<>'user_info' AND column_name IN ('global_id','origin_site'))
            AND NOT(entity='users' AND column_name='display_name') THEN
            RAISE EXCEPTION 'Unknown account field' USING ERRCODE='23514'; END IF;
        END LOOP;
        numeric:=(item->>'id')::bigint;
        IF numeric IS NULL OR numeric<=1000000000 OR numeric>9007199254740991 THEN
          RAISE EXCEPTION 'Invalid imported numeric identity' USING ERRCODE='23514'; END IF;
        IF entity IN ('users','user_info') THEN
          IF numeric<>p_uid THEN RAISE EXCEPTION 'Wrong account p_subject' USING ERRCODE='23514'; END IF;
        ELSIF (item->>'account_id')::bigint IS DISTINCT FROM p_uid THEN
          RAISE EXCEPTION 'Wrong account relationship' USING ERRCODE='23514'; END IF;
        IF entity<>'user_info' THEN
          gid:=(item->>'global_id')::uuid; origin:=item->>'origin_site';
          IF gid IS NULL OR gid='00000000-0000-0000-0000-000000000000'::uuid OR origin IS NULL
            OR (entity='users' AND (gid<>p_subject OR origin<>s.origin_site)) THEN
            RAISE EXCEPTION 'Invalid imported global identity' USING ERRCODE='23514'; END IF;
          SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=entity AND numeric_id=numeric),
            EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=entity AND numeric_id=numeric AND global_id=gid AND origin_site=origin)
            INTO present,same;
          IF (present AND NOT same) OR EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE global_id=gid AND (entity_type<>entity OR numeric_id<>numeric)) THEN
            RAISE EXCEPTION 'Imported identity collision' USING ERRCODE='23505'; END IF;
          IF NOT present AND (origin=node.site_id OR NOT eatwhat_cluster.valid_origin_number(numeric,origin)) THEN
            RAISE EXCEPTION 'Cannot reserve new identities in the local allocation domain' USING ERRCODE='23514'; END IF;
          INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),entity,numeric,gid,origin,p_subject);
        END IF;
        -- Existing children must already belong to this account; a numeric collision is never a reassignment.
        IF entity NOT IN ('users','user_info') THEN
          EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1 AND account_id<>$2)',entity) INTO present USING numeric,p_uid;
          IF present THEN RAISE EXCEPTION 'Account child belongs to another p_subject' USING ERRCODE='23505'; END IF;
        END IF;
        -- Snapshots from sites without V71 carry no display_name: keep the local nickname instead of clearing it.
        row_fields:=CASE WHEN entity='users' AND item ? 'display_name' THEN fields||',display_name' ELSE fields END;
        SELECT string_agg(format('%1$I=EXCLUDED.%1$I',v),',') INTO updates FROM unnest(string_to_array(row_fields,',')) v WHERE v<>'id';
        EXECUTE format('INSERT INTO public.%1$I (%2$s) SELECT %2$s FROM jsonb_populate_record(NULL::public.%1$I,$1) ON CONFLICT(id) DO UPDATE SET %3$s',entity,row_fields,updates)
          USING item-'global_id'-'origin_site';
      END LOOP;
    END LOOP;
    -- Remove stale account aliases/profile/verification from an older authorized snapshot only.
    FOREACH entity IN ARRAY ARRAY['contacts','verifications','privacy'] LOOP
      EXECUTE format('DELETE FROM public.%I WHERE account_id=$1 AND id NOT IN (SELECT (value->>''id'')::bigint FROM jsonb_array_elements($2))',entity)
        USING p_uid,payload->entity;
    END LOOP;
    IF jsonb_array_length(payload->'user_info')=0 THEN DELETE FROM public.user_info WHERE id=p_uid; END IF;
    DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
    RETURN true;
  EXCEPTION WHEN unique_violation OR foreign_key_violation THEN RETURN false;
  END;
END $$;

CREATE OR REPLACE FUNCTION eatwhat_account_multiwriter.capture_account() RETURNS trigger
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
   -- JSON text keeps a literal nickname "null" distinct from no nickname.
   IF before->'display_name' IS DISTINCT FROM after->'display_name' THEN
     PERFORM eatwhat_account_multiwriter.append_local(uid,'display_name',coalesce(after->'display_name','null'::jsonb)::text);
   END IF;
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

CREATE OR REPLACE FUNCTION eatwhat_account_multiwriter.initialize(p_uid bigint) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE u public.users%ROWTYPE; gid uuid; field_name text; val text; eid uuid;
BEGIN
 IF NOT (SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton) THEN RAISE EXCEPTION 'Account multiwriter is disabled'; END IF;
 SELECT * INTO u FROM public.users WHERE id=p_uid FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Account not found'; END IF;
 SELECT global_id INTO gid FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=p_uid;
 IF gid IS NULL THEN RAISE EXCEPTION 'Account identity required'; END IF;
 FOREACH field_name IN ARRAY ARRAY['password','username','banned','role','email','phone','verification','consent','display_name'] LOOP
   val:=CASE field_name WHEN 'password' THEN u.password_hash WHEN 'username' THEN u.nick_name WHEN 'banned' THEN u.banned::text WHEN 'role' THEN u.role
     WHEN 'email' THEN coalesce((SELECT lower(email::text) FROM public.contacts WHERE account_id=p_uid ORDER BY id LIMIT 1),'null')
     WHEN 'phone' THEN coalesce((SELECT phone FROM public.contacts WHERE account_id=p_uid ORDER BY id LIMIT 1),'null')
     WHEN 'verification' THEN coalesce((SELECT (to_jsonb(v)-'id'-'created_at'-'updated_at')::text FROM public.verifications v WHERE account_id=p_uid),'{}')
     WHEN 'consent' THEN (SELECT status FROM eatwhat_consent.subject WHERE user_id=p_uid)
     WHEN 'display_name' THEN to_jsonb(u.display_name)::text END;
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

CREATE OR REPLACE FUNCTION eatwhat_account_multiwriter.project_field(p_uid bigint,p_field text,p_value text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE gid uuid; target bigint; claim record; count_claims bigint; affected bigint[]; previous_value text; v jsonb;
BEGIN
 SELECT global_id INTO gid FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=p_uid;
 IF gid IS NULL OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=p_uid) THEN RAISE EXCEPTION 'Account identity missing'; END IF;
 INSERT INTO eatwhat_account_multiwriter.command_context VALUES(txid_current(),pg_backend_pid(),p_uid,p_uid,'{}',true)
 ON CONFLICT(transaction_id,backend_id,user_id) DO UPDATE SET importing=true;
 IF p_field='password' THEN
   UPDATE public.users SET password_hash=p_value WHERE id=p_uid AND password_hash IS DISTINCT FROM p_value;
 ELSIF p_field='role' THEN
   IF p_value NOT IN ('admin','user') THEN RAISE EXCEPTION 'Invalid account role'; END IF;
   UPDATE public.users SET role=p_value WHERE id=p_uid AND role IS DISTINCT FROM p_value;
 ELSIF p_field='banned' THEN
   IF p_value NOT IN ('true','false') THEN RAISE EXCEPTION 'Invalid account ban'; END IF;
   UPDATE public.users SET banned=p_value::boolean WHERE id=p_uid AND banned IS DISTINCT FROM p_value::boolean;
 ELSIF p_field IN ('username','email','phone') THEN
   IF p_field='email' THEN p_value:=lower(p_value); END IF;
   -- Upgrade untouched local aliases before testing a remote claim against unique public columns.
   IF p_field='username' THEN
     INSERT INTO eatwhat_account_multiwriter.alias_claim(subject_id,kind,value) SELECT i.global_id,'username',u.nick_name FROM public.users u JOIN eatwhat_identity.entity_identity i ON i.entity_type='users' AND i.numeric_id=u.id WHERE u.nick_name NOT LIKE '~mw~%' ON CONFLICT DO NOTHING;
   ELSE
     INSERT INTO eatwhat_account_multiwriter.alias_claim(subject_id,kind,value) SELECT i.global_id,p_field,CASE WHEN p_field='email' THEN lower(c.email::text) ELSE c.phone END FROM public.contacts c JOIN eatwhat_identity.entity_identity i ON i.entity_type='users' AND i.numeric_id=c.account_id WHERE CASE WHEN p_field='email' THEN c.email::text ELSE c.phone END IS NOT NULL ON CONFLICT DO NOTHING;
   END IF;
   SELECT value INTO previous_value FROM eatwhat_account_multiwriter.alias_claim WHERE subject_id=gid AND kind=p_field;
   SELECT coalesce(array_agg(DISTINCT i.numeric_id),'{}'::bigint[])||p_uid INTO affected FROM eatwhat_account_multiwriter.alias_claim c JOIN eatwhat_identity.entity_identity i ON i.global_id=c.subject_id AND i.entity_type='users' WHERE c.kind=p_field AND c.value IN (p_value,previous_value);
   INSERT INTO eatwhat_account_multiwriter.command_context SELECT DISTINCT txid_current(),pg_backend_pid(),p_uid,u,'{}'::uuid[],true FROM unnest(affected) u ON CONFLICT(transaction_id,backend_id,user_id) DO UPDATE SET importing=true;
   DELETE FROM eatwhat_account_multiwriter.alias_claim WHERE subject_id=gid AND kind=p_field;
   IF p_value<>'null' THEN INSERT INTO eatwhat_account_multiwriter.alias_claim VALUES(gid,p_field,p_value); END IF;
   -- All ambiguous aliases are removed from the unique login projection, while claims retain both identities.
   IF p_field='username' THEN
     UPDATE public.users u SET nick_name='~mw~'||u.id WHERE u.id=ANY(affected) AND u.nick_name IS DISTINCT FROM '~mw~'||u.id AND EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.alias_claim c JOIN eatwhat_identity.entity_identity i ON i.global_id=c.subject_id AND i.entity_type='users' WHERE c.kind='username' AND i.numeric_id=u.id AND (c.value IS DISTINCT FROM u.nick_name OR (SELECT count(*) FROM eatwhat_account_multiwriter.alias_claim x WHERE x.kind='username' AND x.value=c.value)>1));
     FOR claim IN SELECT c.*,i.numeric_id FROM eatwhat_account_multiwriter.alias_claim c JOIN eatwhat_identity.entity_identity i ON i.global_id=c.subject_id AND i.entity_type='users' WHERE c.kind='username' AND i.numeric_id=ANY(affected) LOOP
       SELECT count(*) INTO count_claims FROM eatwhat_account_multiwriter.alias_claim WHERE kind='username' AND value=claim.value;
       IF count_claims=1 THEN UPDATE public.users SET nick_name=claim.value WHERE id=claim.numeric_id AND nick_name IS DISTINCT FROM claim.value; END IF;
     END LOOP;
   ELSE
     IF p_value<>'null' AND NOT EXISTS(SELECT 1 FROM public.contacts WHERE account_id=p_uid) THEN INSERT INTO public.contacts(account_id) VALUES(p_uid); END IF;
     IF p_field='email' THEN UPDATE public.contacts SET email=NULL WHERE account_id=ANY(affected); ELSE UPDATE public.contacts SET phone=NULL WHERE account_id=ANY(affected); END IF;
     FOR claim IN SELECT c.*,i.numeric_id FROM eatwhat_account_multiwriter.alias_claim c JOIN eatwhat_identity.entity_identity i ON i.global_id=c.subject_id AND i.entity_type='users' WHERE c.kind=p_field AND i.numeric_id=ANY(affected) LOOP
       SELECT count(*) INTO count_claims FROM eatwhat_account_multiwriter.alias_claim WHERE kind=p_field AND value=claim.value;
       IF count_claims=1 THEN
         IF p_field='email' THEN UPDATE public.contacts SET email=claim.value WHERE account_id=claim.numeric_id;
         ELSE UPDATE public.contacts SET phone=claim.value WHERE account_id=claim.numeric_id; END IF;
       END IF;
     END LOOP;
   END IF;
 ELSIF p_field='verification' THEN
   v:=p_value::jsonb;
   IF v='{}'::jsonb THEN DELETE FROM public.verifications WHERE account_id=p_uid;
   ELSE
     IF (v->>'account_id')::bigint IS DISTINCT FROM p_uid THEN RAISE EXCEPTION 'Invalid verification owner'; END IF;
     IF EXISTS(SELECT 1 FROM public.verifications WHERE account_id=p_uid) THEN
       UPDATE public.verifications SET method=v->>'method',verified=(v->>'verified')::boolean,student_id=v->>'student_id',real_name=v->>'real_name',verified_email=v->>'verified_email' WHERE account_id=p_uid;
     ELSE INSERT INTO public.verifications(account_id,method,verified,student_id,real_name,verified_email) VALUES(p_uid,v->>'method',(v->>'verified')::boolean,v->>'student_id',v->>'real_name',v->>'verified_email'); END IF;
   END IF;
 ELSIF p_field='display_name' THEN
   v:=p_value::jsonb;
   IF jsonb_typeof(v) NOT IN ('string','null') OR length(v#>>'{}')>32 THEN RAISE EXCEPTION 'Invalid account display name'; END IF;
   UPDATE public.users SET display_name=v#>>'{}' WHERE id=p_uid AND display_name IS DISTINCT FROM v#>>'{}';
 ELSE RAISE EXCEPTION 'Unsupported account projection field'; END IF;
 RETURN true;
END $$;

REVOKE ALL ON FUNCTION eatwhat_consent.import_account(uuid,bigint,jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.capture_account() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.initialize(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.project_field(bigint,text,text) FROM PUBLIC;
