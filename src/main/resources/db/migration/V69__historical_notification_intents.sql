SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE TABLE eatwhat_sync.notification_source_proof (
 notification_gid uuid PRIMARY KEY,origin_site text NOT NULL,content_sha256 char(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$')
);
REVOKE ALL ON eatwhat_sync.notification_source_proof FROM PUBLIC;
CREATE TABLE eatwhat_sync.notification_history (
 notification_gid uuid PRIMARY KEY,numeric_id bigint NOT NULL UNIQUE,origin_site text NOT NULL,
 actor_gid uuid NOT NULL,recipient_gid uuid NOT NULL,payload jsonb NOT NULL,
 published_actor_epoch bigint NOT NULL DEFAULT 0,published_recipient_epoch bigint NOT NULL DEFAULT 0,
 attempted_at timestamptz
);
CREATE TABLE eatwhat_sync.notification_history_read (
 notification_gid uuid PRIMARY KEY,recipient_gid uuid NOT NULL,read_at bigint NOT NULL CHECK(read_at>0),
 published_actor_epoch bigint NOT NULL DEFAULT 0,published_recipient_epoch bigint NOT NULL DEFAULT 0,
 attempted_at timestamptz
);
CREATE TABLE eatwhat_sync.notification_history_issue (
 numeric_id bigint NOT NULL,reason text NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(numeric_id,reason)
);
-- Stable historical origin is attested by the source node transport. No arbitrary context grant is exposed.
CREATE FUNCTION eatwhat_sync.import_notification_history(p jsonb) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE nid bigint:=(p->>'id')::bigint; gid uuid:=(p->>'gid')::uuid;
 origin text:=p->>'origin'; actor bigint; recipient bigint; target bigint; existing public.notifications%ROWTYPE;
 expected jsonb; identity eatwhat_identity.entity_identity%ROWTYPE;
BEGIN
 IF nid<1 OR gid IS NULL OR origin IS NULL OR (p->>'kind') NOT IN ('follow','like')
   OR (p->>'targetType') NOT IN ('user','food','dinner')
   OR (p->>'createdAt')::bigint<1 OR (p->>'expiresAt')::bigint<=(p->>'createdAt')::bigint THEN
   RAISE EXCEPTION 'Invalid historical notice' USING ERRCODE='23514'; END IF;
 SELECT numeric_id INTO actor FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=(p->>'actor')::uuid;
 SELECT numeric_id INTO recipient FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=(p->>'recipient')::uuid;
 SELECT numeric_id INTO target FROM eatwhat_identity.entity_identity WHERE entity_type=CASE p->>'targetType' WHEN 'user' THEN 'users' WHEN 'food' THEN 'activity_foods' ELSE 'activity_dinners' END AND global_id=(p->>'target')::uuid;
 IF actor IS NULL OR recipient IS NULL OR target IS NULL OR actor=recipient OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=actor) OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=recipient) THEN
   RAISE EXCEPTION 'Historical notice dependencies unavailable' USING ERRCODE='23503'; END IF;
 IF p->>'kind'='follow' AND (p->>'targetType'<>'user' OR target<>actor)
    OR p->>'kind'='like' AND p->>'targetType' NOT IN ('food','dinner') THEN
    RAISE EXCEPTION 'Historical notice target mismatch' USING ERRCODE='23514'; END IF;
 expected:=CASE WHEN p->>'kind'='follow' THEN '{}'::jsonb ELSE jsonb_build_object('targetType',p->>'targetType','targetId',target) END;
 SELECT * INTO identity FROM eatwhat_identity.entity_identity WHERE entity_type='notifications' AND numeric_id=nid;
 IF FOUND AND (identity.global_id<>gid OR identity.origin_site<>origin) THEN RAISE EXCEPTION 'Historical notice identity collision' USING ERRCODE='23505'; END IF;
 IF EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE global_id=gid AND (entity_type<>'notifications' OR numeric_id<>nid)) THEN RAISE EXCEPTION 'Historical notice identity collision' USING ERRCODE='23505'; END IF;
 IF nid>1000000000 AND NOT eatwhat_cluster.valid_origin_number(nid,origin) THEN RAISE EXCEPTION 'Historical notice allocation mismatch' USING ERRCODE='23514'; END IF;
 INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site) VALUES('notifications',nid,gid,origin) ON CONFLICT DO NOTHING;
 IF (p->>'expiresAt')::bigint<=(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RETURN 'EXPIRED'; END IF;
 SELECT * INTO existing FROM public.notifications WHERE id=nid;
 IF FOUND THEN
   IF existing.account_id<>recipient OR existing.actor_id<>actor OR existing.type<>p->>'kind' OR existing.target_type<>p->>'targetType' OR existing.target_id<>target OR existing.data<>expected
    OR (extract(epoch FROM existing.created_at)*1000)::bigint<>(p->>'createdAt')::bigint OR (extract(epoch FROM existing.expires_at)*1000)::bigint<>(p->>'expiresAt')::bigint THEN
    RAISE EXCEPTION 'Historical notice content collision' USING ERRCODE='23505'; END IF;
   RETURN 'ALREADY_APPLIED';
 END IF;
 INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),'notifications',nid,gid,origin,(p->>'recipient')::uuid);
 INSERT INTO public.notifications(id,account_id,actor_id,type,target_type,target_id,data,created_at,expires_at)
 VALUES(nid,recipient,actor,p->>'kind',p->>'targetType',target,expected,to_timestamp((p->>'createdAt')::bigint/1000.0),to_timestamp((p->>'expiresAt')::bigint/1000.0));
 DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
 RETURN 'APPLIED';
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.import_notification_history(jsonb) FROM PUBLIC;
REVOKE ALL ON eatwhat_sync.notification_history,eatwhat_sync.notification_history_read,eatwhat_sync.notification_history_issue FROM PUBLIC;

-- Minimal immutable receipts survive erasure; no personal envelope is retained here.
CREATE TABLE eatwhat_sync.purged_personal_event (
 event_id uuid PRIMARY KEY,envelope_hash char(64) NOT NULL CHECK(envelope_hash ~ '^[0-9a-f]{64}$')
);
REVOKE ALL ON eatwhat_sync.purged_personal_event FROM PUBLIC;
CREATE TRIGGER purged_personal_event_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
 ON eatwhat_sync.purged_personal_event FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();

-- Only domain owners are privacy subjects. A public catalog administrator is not its owner.
CREATE FUNCTION eatwhat_sync.personal_event_subjects(e jsonb)
RETURNS TABLE(subject_id uuid,consent_epoch bigint,legacy_unscoped boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE p jsonb:=CASE WHEN jsonb_typeof(e->'payloadJson')='string' THEN (e->>'payloadJson')::jsonb ELSE e->'payloadJson' END;
 a text:=e->>'aggregateType'; op jsonb;
BEGIN
 IF a IN ('profile.operation','privacy.operation','preference.operation') THEN
  op:=CASE WHEN a='profile.operation' AND p ? 'operation' THEN p->'operation' ELSE p END;
  RETURN QUERY SELECT (op->>'subject')::uuid,(op->>'epoch')::bigint,false;
 ELSIF a IN ('relation.operation','browse.observation') THEN
  RETURN QUERY SELECT (p->>'actor')::uuid,(p->>'actorEpoch')::bigint,false UNION ALL SELECT (p->>'owner')::uuid,(p->>'ownerEpoch')::bigint,false;
 ELSIF a='notification.read.operation' THEN
  RETURN QUERY SELECT (p->>'actorGid')::uuid,(p->>'actorEpoch')::bigint,false UNION ALL SELECT (p->>'recipientGid')::uuid,(p->>'recipientEpoch')::bigint,false;
 ELSIF a='notification.history' THEN
  RETURN QUERY SELECT (p->'notice'->>'actor')::uuid,(p->>'actorEpoch')::bigint,false UNION ALL SELECT (p->'notice'->>'recipient')::uuid,(p->>'recipientEpoch')::bigint,false;
 ELSIF a='activity_state' THEN
  RETURN QUERY SELECT (p->'created'->>'author_global_id')::uuid,(p->>'epoch')::bigint,false;
 ELSIF a IN ('activity_foods','activity_dinners') THEN
  RETURN QUERY SELECT coalesce(p->>'author_global_id',e->>'actorId')::uuid,NULL::bigint,true;
 ELSIF a IN ('catalog.operation','catalog.decision') THEN
  RETURN QUERY SELECT (v->>'subjectGid')::uuid,(v->>'epoch')::bigint,false FROM jsonb_array_elements(coalesce(p->'consentEpochs','[]'::jsonb)) v;
 ELSIF a='file.metadata' THEN
  RETURN QUERY SELECT (p->'file'->>'ownerGid')::uuid,(p->>'epoch')::bigint,false;
 ELSIF a='file.replica' THEN
  RETURN QUERY SELECT (p->>'ownerGid')::uuid,(p->>'epoch')::bigint,false;
 END IF;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.personal_event_subjects(jsonb) FROM PUBLIC;

-- Called in the receive transaction before its event-id advisory lock. Never stores rejected content.
CREATE FUNCTION eatwhat_sync.personal_event_retention_blocked(e jsonb) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s record; gid uuid;
BEGIN
 IF (SELECT mode FROM eatwhat_identity.node_state WHERE singleton)<>'EXTERNAL'
  OR NOT (SELECT enabled FROM eatwhat_account_multiwriter.configuration WHERE singleton) THEN RETURN false; END IF;
 -- A missing subject row still has a lock: account import uses this same creation fence.
 -- Acquire every creation fence before any row/event lock, in deterministic UUID order.
 FOR gid IN SELECT DISTINCT p.subject_id FROM eatwhat_sync.personal_event_subjects(e) p WHERE p.subject_id IS NOT NULL ORDER BY p.subject_id LOOP
  PERFORM pg_advisory_xact_lock(hashtextextended(gid::text,0));
 END LOOP;
 FOR s IN SELECT c.subject_id,c.status,c.epoch FROM eatwhat_consent.subject c
   WHERE c.subject_id IN (SELECT p.subject_id FROM eatwhat_sync.personal_event_subjects(e) p)
   ORDER BY c.subject_id FOR UPDATE
 LOOP
  IF s.status='WITHDRAWN' OR EXISTS(SELECT 1 FROM eatwhat_sync.personal_event_subjects(e) p
    WHERE p.subject_id=s.subject_id AND (p.consent_epoch<s.epoch OR p.legacy_unscoped AND EXISTS(
     SELECT 1 FROM eatwhat_account_multiwriter.operation o WHERE o.subject_id=s.subject_id AND o.field='consent' AND o.value='WITHDRAWN'
     UNION ALL SELECT 1 FROM eatwhat_consent.withdrawal_receipt w WHERE w.subject_id=s.subject_id
     UNION ALL SELECT 1 FROM eatwhat_consent.job j WHERE j.subject_id=s.subject_id AND j.kind IN ('PURGE','CLEANUP','WITHDRAW_REQUEST')))) THEN RETURN true; END IF;
 END LOOP;
 RETURN false;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.personal_event_retention_blocked(jsonb) FROM PUBLIC;

CREATE FUNCTION eatwhat_sync.erase_withdrawn_business_payload(p_subject uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s eatwhat_consent.subject%ROWTYPE; events uuid[]; e record; rows jsonb; name text;
BEGIN
 SELECT * INTO s FROM eatwhat_consent.subject WHERE subject_id=p_subject FOR UPDATE;
 IF NOT FOUND OR s.status<>'WITHDRAWN' OR (SELECT mode FROM eatwhat_identity.node_state WHERE singleton)<>'EXTERNAL'
   OR EXISTS(SELECT 1 FROM public.users WHERE id=s.user_id) THEN
  RAISE EXCEPTION 'Only a cleared withdrawn projection may be erased' USING ERRCODE='42501'; END IF;
 SELECT coalesce(array_agg(DISTINCT x.event_id),'{}'::uuid[]) INTO events FROM (
   SELECT event_id,envelope FROM eatwhat_sync.inbox UNION ALL SELECT event_id,envelope FROM eatwhat_sync.outbox) x
  WHERE EXISTS(SELECT 1 FROM eatwhat_sync.personal_event_subjects(x.envelope) p WHERE p.subject_id=p_subject);
 FOR e IN SELECT x.event_id,x.envelope_hash FROM (
   SELECT event_id,envelope_hash FROM eatwhat_sync.inbox UNION SELECT event_id,envelope_hash FROM eatwhat_sync.outbox) x
   WHERE x.event_id=ANY(events) ORDER BY x.event_id
 LOOP
  PERFORM pg_advisory_xact_lock(hashtextextended(e.event_id::text,20261021));
  INSERT INTO eatwhat_sync.purged_personal_event VALUES(e.event_id,e.envelope_hash) ON CONFLICT DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.purged_personal_event p WHERE p.event_id=e.event_id AND p.envelope_hash=e.envelope_hash) THEN
   RAISE EXCEPTION 'Personal event receipt identity collision' USING ERRCODE='23514'; END IF;
 END LOOP;
 FOREACH name IN ARRAY ARRAY['delivery','pending','conflict','outbox','inbox'] LOOP
  EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t)),''[]''::jsonb) FROM eatwhat_sync.%I t WHERE event_id=ANY($1)',name) INTO rows USING events;
  PERFORM eatwhat_sync.erase_scoped_rows(name,rows);
 END LOOP;
 -- Personal relationship payloads and causal witnesses are erased; numeric/global aliases remain reserved.
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.relation_notification t
  WHERE recipient_gid=p_subject OR source_event_id=ANY(events) OR source_event_id IN(
    SELECT event_id FROM eatwhat_sync.relation_operation WHERE payload->>'actor'=p_subject::text OR payload->>'owner'=p_subject::text);
 PERFORM eatwhat_sync.erase_scoped_rows('relation_notification',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.relation_operation t
  WHERE payload->>'actor'=p_subject::text OR payload->>'owner'=p_subject::text;
 PERFORM eatwhat_sync.erase_scoped_rows('relation_operation',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.relation_causal_fact t
  WHERE natural_id IN(SELECT natural_id FROM eatwhat_sync.relation_causal_state WHERE actor_gid=p_subject OR owner_gid=p_subject);
 PERFORM eatwhat_sync.erase_scoped_rows('relation_causal_fact',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.relation_causal_state t WHERE actor_gid=p_subject OR owner_gid=p_subject;
 PERFORM eatwhat_sync.erase_scoped_rows('relation_causal_state',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.profile_conflict_audit t
  WHERE conflict_id IN(SELECT conflict_id FROM eatwhat_sync.profile_conflict WHERE subject_gid=p_subject);
 PERFORM eatwhat_sync.erase_scoped_rows('profile_conflict_audit',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.profile_authorization_review_decision t
  WHERE review_event_id IN(SELECT event_id FROM eatwhat_sync.profile_authorization_review WHERE subject_gid=p_subject);
 PERFORM eatwhat_sync.erase_scoped_rows('profile_authorization_review_decision',rows);
 FOREACH name IN ARRAY ARRAY['profile_authorization_review','profile_authorization','profile_conflict','profile_field_state','profile_operation','profile_domain','privacy_operation','privacy_domain','preference_state'] LOOP
  EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t)),''[]''::jsonb) FROM eatwhat_sync.%I t WHERE subject_gid=$1',name) INTO rows USING p_subject;
  PERFORM eatwhat_sync.erase_scoped_rows(name,rows);
 END LOOP;
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.notification_history_read t WHERE recipient_gid=p_subject
  OR notification_gid IN(SELECT notification_gid FROM eatwhat_sync.notification_history WHERE actor_gid=p_subject OR recipient_gid=p_subject);
 PERFORM eatwhat_sync.erase_scoped_rows('notification_history_read',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.notification_read_member t WHERE actor_gid=p_subject
  OR batch_id IN(SELECT batch_id FROM eatwhat_sync.notification_read_batch WHERE recipient_gid=p_subject);
 PERFORM eatwhat_sync.erase_scoped_rows('notification_read_member',rows);
 FOREACH name IN ARRAY ARRAY['notification_history','notification_read_operation'] LOOP
  EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t)),''[]''::jsonb) FROM eatwhat_sync.%I t WHERE actor_gid=$1 OR recipient_gid=$1',name) INTO rows USING p_subject;
  PERFORM eatwhat_sync.erase_scoped_rows(name,rows);
 END LOOP;
 FOREACH name IN ARRAY ARRAY['notification_read_state','notification_read_domain','notification_read_batch'] LOOP
  EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t)),''[]''::jsonb) FROM eatwhat_sync.%I t WHERE recipient_gid=$1',name) INTO rows USING p_subject;
  PERFORM eatwhat_sync.erase_scoped_rows(name,rows);
 END LOOP;
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.erase_withdrawn_business_payload(uuid) FROM PUBLIC;

-- These facts keep UPDATE/TRUNCATE protection, while exact owner-scoped erasure can delete rows.
DO $$ DECLARE r record; extra text; BEGIN
 FOR r IN SELECT t.tgname,c.relname,t.tgtype,p.proname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid
  WHERE n.nspname='eatwhat_sync' AND NOT t.tgisinternal AND (t.tgtype::int & 8)<>0
   AND c.relname IN ('profile_operation','profile_conflict_audit','privacy_operation','notification_read_operation','relation_operation','relation_causal_fact','relation_notification')
 LOOP
  EXECUTE format('DROP TRIGGER %I ON eatwhat_sync.%I',r.tgname,r.relname);
  EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON eatwhat_sync.%I FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.scoped_erasure_guard()',r.tgname,r.relname);
  extra:=concat_ws(' OR ',CASE WHEN (r.tgtype::int & 16)<>0 THEN 'UPDATE' END,CASE WHEN (r.tgtype::int & 32)<>0 THEN 'TRUNCATE' END);
  IF extra<>'' THEN EXECUTE format('CREATE TRIGGER %I BEFORE %s ON eatwhat_sync.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.%I()',left(r.tgname,48)||'_other',extra,r.relname,r.proname); END IF;
 END LOOP;
END $$;
