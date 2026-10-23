SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE TABLE eatwhat_account_multiwriter.authorization_witness(
 witness_id uuid PRIMARY KEY,subject_id uuid NOT NULL,security_heads uuid[] NOT NULL,
 source_site varchar(64) NOT NULL,source_instance uuid NOT NULL,local_order bigserial NOT NULL UNIQUE);
ALTER TABLE eatwhat_account_multiwriter.operation ADD COLUMN observed_witnesses uuid[] NOT NULL DEFAULT '{}';
ALTER TABLE eatwhat_account_multiwriter.operation ADD COLUMN actor_witness uuid;
ALTER TABLE eatwhat_account_multiwriter.command_context ADD COLUMN actor_witness uuid;
CREATE TABLE eatwhat_account_multiwriter.authorization_review(
 event_id uuid PRIMARY KEY,subject_id uuid NOT NULL,field text NOT NULL,change jsonb NOT NULL,
 state text NOT NULL CHECK(state IN ('REVIEW','REJECTED','APPROVED')),created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE FUNCTION eatwhat_account_multiwriter.begin_command(p_actor bigint,p_uid bigint,p_witness uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 PERFORM eatwhat_account_multiwriter.begin_command(p_actor,p_uid);
 IF NOT EXISTS(SELECT 1 FROM eatwhat_account_multiwriter.authorization_witness w
   JOIN eatwhat_identity.entity_identity i ON i.global_id=w.subject_id AND i.entity_type='users'
   JOIN eatwhat_account_multiwriter.command_context c ON c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid() AND c.user_id=p_uid
   WHERE w.witness_id=p_witness AND i.numeric_id=p_actor AND w.security_heads @> c.security_heads AND w.security_heads <@ c.security_heads)
 THEN RAISE EXCEPTION 'Account command authorization witness mismatch' USING ERRCODE='42501'; END IF;
 UPDATE eatwhat_account_multiwriter.command_context SET actor_witness=p_witness WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid() AND user_id=p_uid;
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.begin_command(bigint,bigint,uuid) FROM PUBLIC;
REVOKE ALL ON eatwhat_account_multiwriter.authorization_review FROM PUBLIC;

CREATE FUNCTION eatwhat_account_multiwriter.observe_witnesses() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.source_site=(SELECT site_id FROM eatwhat_identity.node_state WHERE singleton) THEN
   NEW.actor_witness:=(SELECT c.actor_witness FROM eatwhat_account_multiwriter.command_context c JOIN eatwhat_identity.entity_identity i ON i.entity_type='users' AND i.numeric_id=c.user_id WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid() AND i.global_id=NEW.subject_id);
   NEW.observed_witnesses:=coalesce((SELECT array_agg(witness_id ORDER BY witness_id) FROM eatwhat_account_multiwriter.authorization_witness WHERE subject_id=NEW.subject_id),'{}'::uuid[]);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER account_mw_observation BEFORE INSERT ON eatwhat_account_multiwriter.operation FOR EACH ROW EXECUTE FUNCTION eatwhat_account_multiwriter.observe_witnesses();
CREATE FUNCTION eatwhat_account_multiwriter.project_field(p_uid bigint,p_field text,p_value text) RETURNS boolean
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
 ELSE RAISE EXCEPTION 'Unsupported account projection field'; END IF;
 RETURN true;
END $$;
REVOKE ALL ON eatwhat_account_multiwriter.authorization_witness FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.observe_witnesses(),eatwhat_account_multiwriter.project_field(bigint,text,text) FROM PUBLIC;
CREATE FUNCTION eatwhat_account_multiwriter.end_command() RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 DELETE FROM eatwhat_account_multiwriter.command_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.end_command() FROM PUBLIC;

CREATE TABLE eatwhat_account_multiwriter.review_decision(decision_id uuid PRIMARY KEY,event_id uuid NOT NULL,subject_id uuid NOT NULL,decision jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
REVOKE ALL ON eatwhat_account_multiwriter.review_decision FROM PUBLIC;
