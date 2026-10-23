-- Private, site-local settings. Derived behavior profiles are computed rather than stored.
CREATE TABLE public.user_food_preferences (
    account_id bigint PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    liked_tags text[] NOT NULL DEFAULT ARRAY[]::text[],
    disliked_tags text[] NOT NULL DEFAULT ARRAY[]::text[],
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (cardinality(liked_tags) <= 20 AND cardinality(disliked_tags) <= 20),
    CHECK (array_position(liked_tags, NULL) IS NULL AND array_position(disliked_tags, NULL) IS NULL),
    CHECK (NOT liked_tags && disliked_tags)
);

-- Extend the existing guarded purge with one explicitly account-owned, site-local
-- table. All authorization, archive, delivery and unknown-dependency guards remain.
CREATE OR REPLACE FUNCTION eatwhat_consent.purge_account(p_subject uuid, p_uid bigint) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s eatwhat_consent.subject%ROWTYPE; dependency record; blocked boolean;
BEGIN
  SELECT * INTO s FROM eatwhat_consent.subject WHERE subject_id=p_subject AND user_id=p_uid FOR UPDATE;
  IF NOT FOUND OR s.status<>'WITHDRAWN' OR (SELECT mode FROM eatwhat_identity.node_state WHERE singleton) IS DISTINCT FROM 'EXTERNAL' THEN
    RAISE EXCEPTION 'Only withdrawn external account projections may be purged' USING ERRCODE='23514'; END IF;
  IF s.origin_site=(SELECT site_id FROM eatwhat_identity.node_state WHERE singleton) AND NOT s.source_archived THEN RETURN false; END IF;
  PERFORM id FROM public.users WHERE id=p_uid FOR UPDATE;
  IF EXISTS(SELECT 1 FROM eatwhat_sync.outbox o JOIN eatwhat_sync.delivery d ON d.event_id=o.event_id
      WHERE d.status<>'ACKED' AND ((o.aggregate_type='activity_foods' AND o.numeric_id IN (SELECT id FROM public.activity_foods WHERE account_id=p_uid))
        OR (o.aggregate_type='activity_dinners' AND o.numeric_id IN (SELECT id FROM public.activity_dinners WHERE account_id=p_uid)))) THEN
    RETURN false; END IF;
  -- Do not remove the only source of unacknowledged relationship operations.
  IF EXISTS(SELECT 1 FROM eatwhat_sync.outbox o JOIN eatwhat_sync.delivery d ON d.event_id=o.event_id
      WHERE d.status<>'ACKED'
        AND NOT(d.status='QUARANTINED' AND d.last_error='CONSENT_EPOCH_BLOCKED')
        AND o.aggregate_type='relation.operation'
        AND (o.envelope->'payloadJson'->>'actor'=p_subject::text OR o.envelope->'payloadJson'->>'owner'=p_subject::text)) THEN
    RETURN false; END IF;
  DELETE FROM public.activity_likes WHERE account_id=p_uid
      OR (target_type='food' AND activity_id IN (SELECT id FROM public.activity_foods WHERE account_id=p_uid))
      OR (target_type='dinner' AND activity_id IN (SELECT id FROM public.activity_dinners WHERE account_id=p_uid));
  DELETE FROM public.likes WHERE account_id=p_uid;
  DELETE FROM public.food_favorites WHERE account_id=p_uid;
  DELETE FROM public.food_taggings WHERE account_id=p_uid;
  DELETE FROM public.follow WHERE account_id=p_uid OR target_id=p_uid;
  DELETE FROM public.notifications WHERE account_id=p_uid OR actor_id=p_uid;
  DELETE FROM public.activity_foods WHERE account_id=p_uid;
  DELETE FROM public.activity_dinners WHERE account_id=p_uid;
  FOR dependency IN
    SELECT n.nspname,c.relname,a.attname FROM pg_constraint fk JOIN pg_class c ON c.oid=fk.conrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=fk.conkey[1]
    WHERE fk.contype='f' AND fk.confrelid='public.users'::regclass
      AND NOT(n.nspname='public' AND c.relname IN ('contacts','verifications','privacy','user_info','user_food_preferences'))
  LOOP
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.%I WHERE %I=$1)',dependency.nspname,dependency.relname,dependency.attname) INTO blocked USING p_uid;
    IF blocked THEN RETURN false; END IF;
  END LOOP;
  INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),'users',p_uid,p_subject,s.origin_site,p_subject);
  DELETE FROM public.contacts WHERE account_id=p_uid;
  DELETE FROM public.verifications WHERE account_id=p_uid;
  DELETE FROM public.privacy WHERE account_id=p_uid;
  DELETE FROM public.user_info WHERE id=p_uid;
  DELETE FROM public.users WHERE id=p_uid;
  DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
  RETURN true;
END $$;

REVOKE ALL ON FUNCTION eatwhat_consent.purge_account(uuid,bigint) FROM PUBLIC;
