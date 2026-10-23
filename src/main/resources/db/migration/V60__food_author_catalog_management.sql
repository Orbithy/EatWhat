-- Allow CAMPUS food authors to edit/delete their own persisted food rows.
-- Keep all other catalog authority, actor, managed-object and outbox guards intact.
CREATE OR REPLACE FUNCTION eatwhat_sync.begin_catalog_business_write(
  p_kind text,p_numeric bigint,p_actor_user_id bigint,p_author_user_id bigint,
  p_parent_numeric_id bigint,p_operation text,p_expect_outbox boolean
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_entity text; v_revision bigint; v_managed boolean; v_node text;
BEGIN
  IF p_kind NOT IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')
      OR p_operation NOT IN ('CREATE','UPDATE','DELETE','HUB_ASSIGNMENT')
      OR p_actor_user_id IS NULL OR p_expect_outbox IS NULL OR (p_numeric IS NOT NULL AND p_numeric<=0) THEN
    RAISE EXCEPTION 'Invalid catalog business write context' USING ERRCODE='23514';
  END IF;
  v_entity:=eatwhat_sync.catalog_entity_type(p_kind);
  SELECT mode INTO v_node FROM eatwhat_identity.node_state WHERE singleton;
  IF v_node IS NULL THEN RAISE EXCEPTION 'Catalog write requires explicit site identity' USING ERRCODE='55000'; END IF;
  IF p_kind IN ('HUB','SYSTEM_TAG') AND (v_node<>'CAMPUS' OR NOT EXISTS(
      SELECT 1 FROM public.users WHERE id=p_actor_user_id AND role='admin')) THEN
    RAISE EXCEPTION 'Catalog management requires a campus administrator' USING ERRCODE='42501';
  END IF;
  IF p_kind IN ('RESTAURANT','FOOD') AND p_operation IN ('UPDATE','DELETE','HUB_ASSIGNMENT')
      AND (v_node<>'CAMPUS' OR NOT (
        EXISTS(SELECT 1 FROM public.users WHERE id=p_actor_user_id AND role='admin')
        OR (p_kind='FOOD' AND p_operation IN ('UPDATE','DELETE')
          AND p_author_user_id=p_actor_user_id
          AND EXISTS(SELECT 1 FROM public.foods
            WHERE id=p_numeric AND account_id=p_actor_user_id)))) THEN
    RAISE EXCEPTION 'Catalog management requires a campus administrator' USING ERRCODE='42501';
  END IF;
  IF (p_kind IN ('RESTAURANT','FOOD') AND p_operation='CREATE'
      OR p_kind='CUSTOM_TAG') AND p_author_user_id IS DISTINCT FROM p_actor_user_id THEN
    RAISE EXCEPTION 'Catalog personal write actor mismatch' USING ERRCODE='42501';
  END IF;
  SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_managed_domain WHERE kind=p_kind)
      OR (p_numeric IS NOT NULL AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object
        WHERE kind=p_kind AND numeric_id=p_numeric AND managed)) INTO v_managed;
  IF NOT v_managed THEN RETURN false; END IF;
  IF p_numeric IS NULL AND p_operation<>'CREATE' THEN
    RAISE EXCEPTION 'Managed catalog update requires numeric identity' USING ERRCODE='23514';
  END IF;
  v_revision:=0;
  IF p_numeric IS NOT NULL THEN
    SELECT revision INTO v_revision FROM eatwhat_sync.catalog_object
      WHERE kind=p_kind AND numeric_id=p_numeric AND managed;
    IF NOT FOUND THEN
      SELECT COALESCE(max(revision),0) INTO v_revision FROM eatwhat_sync.catalog_revision
        WHERE kind=p_kind AND numeric_id=p_numeric;
    END IF;
    IF p_author_user_id IS NOT NULL AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        JOIN eatwhat_identity.entity_identity ui ON ui.entity_type='users' AND ui.numeric_id=p_author_user_id
        WHERE o.kind=p_kind AND o.numeric_id=p_numeric AND o.author_gid IS NOT NULL
          AND o.author_gid IS DISTINCT FROM ui.global_id) THEN
      RAISE EXCEPTION 'Catalog write author does not match managed object' USING ERRCODE='42501';
    END IF;
  END IF;
  INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
      actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
    VALUES(txid_current(),pg_backend_pid(),p_kind,COALESCE(p_numeric,0),p_actor_user_id,p_author_user_id,
      p_parent_numeric_id,p_operation,v_revision,p_expect_outbox)
    ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;

  -- Restaurant FK deletion cascades foods. Record exact current children without creating
  -- child catalog operations; deferred validation requires the parent tombstone.
  IF p_kind='RESTAURANT' AND p_operation='DELETE' AND p_numeric IS NOT NULL THEN
    INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
        actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
      SELECT txid_current(),pg_backend_pid(),'FOOD',f.id,p_actor_user_id,f.account_id,p_numeric,
        'DELETE',COALESCE(fo.revision,0),false
      FROM public.foods f LEFT JOIN eatwhat_sync.catalog_object fo ON fo.kind='FOOD'
        AND fo.numeric_id=f.id AND fo.managed
      WHERE f.restaurant_id=p_numeric
      ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
  END IF;
  RETURN true;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.begin_catalog_business_write(text,bigint,bigint,bigint,bigint,text,boolean) FROM PUBLIC;

