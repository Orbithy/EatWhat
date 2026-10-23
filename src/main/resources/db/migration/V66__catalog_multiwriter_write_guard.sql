-- Both explicit sites may manage catalog objects only after deployment owner activation.
-- Actor ownership, admin role, managed-object and durable-outbox checks remain mandatory.
CREATE OR REPLACE FUNCTION eatwhat_sync.begin_catalog_business_write(
  p_kind text,p_numeric bigint,p_actor_user_id bigint,p_author_user_id bigint,
  p_parent_numeric_id bigint,p_operation text,p_expect_outbox boolean
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_entity text; v_revision bigint; v_managed boolean; v_node text; v_multi boolean;
BEGIN
  IF p_kind NOT IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')
      OR p_operation NOT IN ('CREATE','UPDATE','DELETE','HUB_ASSIGNMENT')
      OR p_actor_user_id IS NULL OR p_expect_outbox IS NULL OR (p_numeric IS NOT NULL AND p_numeric<=0) THEN
    RAISE EXCEPTION 'Invalid catalog business write context' USING ERRCODE='23514';
  END IF;
  v_entity:=eatwhat_sync.catalog_entity_type(p_kind);
  SELECT mode INTO v_node FROM eatwhat_identity.node_state WHERE singleton;
  IF v_node IS NULL THEN RAISE EXCEPTION 'Catalog write requires explicit site identity' USING ERRCODE='55000'; END IF;
  SELECT enabled INTO v_multi FROM eatwhat_sync.catalog_multiwriter_config WHERE singleton;
  IF p_kind IN ('HUB','SYSTEM_TAG') AND ((v_node<>'CAMPUS' AND NOT COALESCE(v_multi,false)) OR NOT EXISTS(
      SELECT 1 FROM public.users WHERE id=p_actor_user_id AND role='admin')) THEN
    RAISE EXCEPTION 'Catalog management requires a campus administrator' USING ERRCODE='42501';
  END IF;
  IF p_kind IN ('RESTAURANT','FOOD') AND p_operation IN ('UPDATE','DELETE','HUB_ASSIGNMENT')
      AND ((v_node<>'CAMPUS' AND NOT COALESCE(v_multi,false)) OR NOT (
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
  IF NOT v_managed AND NOT COALESCE(v_multi,false) THEN RETURN false; END IF;
  IF COALESCE(v_multi,false) THEN
    INSERT INTO eatwhat_sync.catalog_managed_domain(kind) VALUES(p_kind) ON CONFLICT(kind) DO NOTHING;
  END IF;
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


CREATE OR REPLACE FUNCTION eatwhat_sync.catalog_public_write_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_kind text; v_id bigint; v_operation text; v_ctx eatwhat_sync.catalog_business_write_context%ROWTYPE;
  v_withdrawal_ctx eatwhat_sync.catalog_withdrawal_write_context%ROWTYPE;
  v_imported boolean:=false; v_withdrawal boolean:=false; v_count bigint;
BEGIN
  v_kind:=CASE TG_TABLE_NAME WHEN 'hub' THEN 'HUB' WHEN 'restaurant' THEN 'RESTAURANT'
    WHEN 'foods' THEN 'FOOD' WHEN 'food_tags' THEN CASE
      WHEN COALESCE(to_jsonb(NEW)->>'tag_type',to_jsonb(OLD)->>'tag_type')='system' THEN 'SYSTEM_TAG' ELSE 'CUSTOM_TAG' END END;
  v_id:=COALESCE((to_jsonb(NEW)->>'id')::bigint,(to_jsonb(OLD)->>'id')::bigint);
  IF TG_OP='UPDATE' AND (to_jsonb(NEW)->'catalog_visible') IS DISTINCT FROM (to_jsonb(OLD)->'catalog_visible') THEN
    IF (to_jsonb(NEW)-'catalog_visible') IS DISTINCT FROM (to_jsonb(OLD)-'catalog_visible')
      OR NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_visibility_context c
        WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid()
          AND c.kind=v_kind AND c.numeric_id=v_id AND c.target_visible=(to_jsonb(NEW)->>'catalog_visible')::boolean) THEN
      RAISE EXCEPTION 'Catalog visibility requires controlled ledger projection' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
  END IF;
  IF TG_OP='INSERT' AND NOT (to_jsonb(NEW)->>'catalog_visible')::boolean THEN
    RAISE EXCEPTION 'Catalog insert cannot forge hidden projection' USING ERRCODE='42501';
  END IF;

  IF TG_OP='UPDATE' AND TG_TABLE_NAME='foods'
      AND (to_jsonb(NEW)-'likes_count'-'updated_at')=(to_jsonb(OLD)-'likes_count'-'updated_at')
      AND (to_jsonb(NEW)->>'likes_count') IS DISTINCT FROM (to_jsonb(OLD)->>'likes_count') THEN
    SELECT count(*) INTO v_count FROM public.likes l WHERE l.target_type='food' AND l.target_id=v_id AND l.deleted_at IS NULL;
    IF (to_jsonb(NEW)->>'likes_count')::bigint=v_count THEN RETURN NEW; END IF;
  END IF;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_managed_domain WHERE kind=v_kind)
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind=v_kind AND numeric_id=v_id AND managed) THEN
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_OP='DELETE' THEN
    SELECT * INTO v_withdrawal_ctx FROM eatwhat_sync.catalog_withdrawal_write_context c
      WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid()
        AND c.kind=v_kind AND c.numeric_id=v_id FOR UPDATE;
    v_withdrawal:=FOUND;
  END IF;
  IF v_withdrawal THEN
    NULL;
  ELSIF EXISTS(SELECT 1 FROM eatwhat_consent.import_context c WHERE c.transaction_id=txid_current()
      AND c.backend_id=pg_backend_pid() AND c.entity_type=TG_TABLE_NAME AND c.numeric_id=v_id) THEN
    v_imported:=true;
  ELSE
    SELECT * INTO v_ctx FROM eatwhat_sync.catalog_business_write_context c
      WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid()
        AND c.kind=v_kind AND (c.numeric_id=v_id OR c.numeric_id=0)
        AND ((TG_OP='INSERT' AND c.operation='CREATE') OR c.operation=TG_OP
          OR (TG_OP='UPDATE' AND c.operation='HUB_ASSIGNMENT'))
      ORDER BY (c.numeric_id IS NOT NULL) DESC LIMIT 1 FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Managed catalog row requires catalog command context' USING ERRCODE='42501'; END IF;
    IF v_ctx.numeric_id=0 THEN
      IF TG_OP<>'INSERT' OR (v_ctx.author_user_id IS NULL AND v_kind NOT IN ('HUB','SYSTEM_TAG')) THEN
        RAISE EXCEPTION 'Catalog create context does not match inserted row' USING ERRCODE='42501'; END IF;
    END IF;
    UPDATE eatwhat_sync.catalog_business_write_context SET touched=true
      WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid()
        AND kind=v_ctx.kind AND numeric_id=v_ctx.numeric_id AND operation=v_ctx.operation;
  END IF;
  v_operation:=CASE WHEN TG_OP='DELETE' THEN 'DELETE' WHEN TG_OP='INSERT' THEN 'CREATE' ELSE 'UPDATE' END;
  IF v_withdrawal THEN
    INSERT INTO eatwhat_sync.catalog_business_write_pending(transaction_id,backend_id,kind,numeric_id,
      operation,revision_before,expect_outbox,imported,withdrawal)
      VALUES(txid_current(),pg_backend_pid(),v_kind,v_id,'DELETE',v_withdrawal_ctx.revision_before,false,false,true)
      ON CONFLICT DO NOTHING;
  ELSIF v_imported THEN
    INSERT INTO eatwhat_sync.catalog_business_write_pending(transaction_id,backend_id,kind,numeric_id,
      operation,revision_before,expect_outbox,imported)
      VALUES(txid_current(),pg_backend_pid(),v_kind,v_id,v_operation,0,false,true)
      ON CONFLICT DO NOTHING;
  ELSE
    INSERT INTO eatwhat_sync.catalog_business_write_pending(transaction_id,backend_id,kind,numeric_id,
      operation,revision_before,expect_outbox,imported)
      VALUES(txid_current(),pg_backend_pid(),v_kind,v_id,v_operation,v_ctx.revision_before,v_ctx.expect_outbox,false)
      ON CONFLICT DO NOTHING;
  END IF;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE OR REPLACE FUNCTION eatwhat_sync.apply_catalog_projection(
  p_kind text,p_gid uuid,p_numeric bigint,p_origin text,p_author uuid,p_parent uuid,p_operation text,p_payload jsonb
) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE node eatwhat_identity.node_state%ROWTYPE; entity text; local_parity integer;
  mapped_gid uuid; mapped_id bigint; mapped_origin text; v_author_id bigint; v_parent_id bigint;
  v_owner_id bigint; v_hub_id bigint; picture_keys text[]; restaurant_gid uuid; owner_gid uuid;
BEGIN
  IF p_kind NOT IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')
      OR p_operation NOT IN ('CREATE','UPDATE','DELETE','HUB_ASSIGNMENT')
      OR p_gid IS NULL OR p_numeric IS NULL OR p_numeric<1 OR p_numeric>9007199254740991
      OR p_origin IS NULL OR length(p_origin) NOT BETWEEN 1 AND 64
      OR jsonb_typeof(p_payload)<>'object' THEN
    RAISE EXCEPTION 'Invalid catalog projection identity' USING ERRCODE='23514';
  END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RAISE EXCEPTION 'Catalog projection requires an explicit site' USING ERRCODE='55000'; END IF;
  entity:=CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' ELSE 'food_tags' END;
  local_parity:=CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END;
  SELECT global_id,origin_site INTO mapped_gid,mapped_origin FROM eatwhat_identity.entity_identity
    WHERE entity_type=entity AND numeric_id=p_numeric;
  IF p_numeric<=1000000000 AND NOT FOUND THEN
    RAISE EXCEPTION 'Unregistered legacy catalog ID is not importable' USING ERRCODE='23514';
  END IF;
  IF FOUND AND (mapped_gid<>p_gid OR mapped_origin IS DISTINCT FROM p_origin) THEN
    RAISE EXCEPTION 'Catalog numeric identity is already reserved' USING ERRCODE='23505';
  END IF;
  SELECT numeric_id INTO mapped_id FROM eatwhat_identity.entity_identity WHERE global_id=p_gid;
  IF FOUND AND (mapped_id<>p_numeric OR NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity
       WHERE global_id=p_gid AND entity_type=entity AND numeric_id=p_numeric AND origin_site=p_origin)) THEN
    RAISE EXCEPTION 'Catalog GID is already reserved' USING ERRCODE='23505';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=entity AND numeric_id=p_numeric) THEN
    IF p_origin=node.site_id OR NOT eatwhat_cluster.valid_origin_number(p_numeric,p_origin) THEN
      RAISE EXCEPTION 'Catalog source ID is outside its allocation domain' USING ERRCODE='23514';
    END IF;
  END IF;

  -- The function is callable only for the immutable payload currently selected by the ledger.
  -- This makes the import context unusable as a general explicit-ID insertion escape hatch.
  IF p_operation='DELETE' THEN
    IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=p_kind AND o.object_gid=p_gid AND o.numeric_id=p_numeric AND o.origin_site=p_origin
          AND o.author_gid IS NOT DISTINCT FROM p_author AND o.parent_gid IS NOT DISTINCT FROM p_parent
          AND o.tombstone AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision r
            CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h
            WHERE r.operation_id=(h->>'operationId')::uuid AND r.kind=p_kind AND r.object_gid=p_gid
              AND r.tombstone AND r.payload=p_payload
              AND (h->>'tombstone')::boolean)) THEN
      RAISE EXCEPTION 'Catalog delete is not the active ledger state' USING ERRCODE='23514';
    END IF;
  ELSE
    IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=p_kind AND o.object_gid=p_gid AND o.numeric_id=p_numeric AND o.origin_site=p_origin
          AND o.author_gid IS NOT DISTINCT FROM p_author AND o.parent_gid IS NOT DISTINCT FROM p_parent
          AND NOT o.tombstone AND NOT o.rejected AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision r
            CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h
            WHERE r.operation_id=(h->>'operationId')::uuid AND r.kind=p_kind AND r.object_gid=p_gid
              AND NOT r.tombstone AND r.payload=p_payload
              AND NOT (h->>'tombstone')::boolean)) THEN
      RAISE EXCEPTION 'Catalog payload is not an active ledger head' USING ERRCODE='23514';
    END IF;
  END IF;

  IF p_operation='DELETE' THEN
    INSERT INTO eatwhat_consent.import_context(transaction_id,backend_id,entity_type,numeric_id,global_id,origin_site,subject_id)
      VALUES(txid_current(),pg_backend_pid(),entity,p_numeric,p_gid,p_origin,COALESCE(p_author,p_gid));
    IF p_kind='HUB' THEN
      INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
          actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
        SELECT txid_current(),pg_backend_pid(),'RESTAURANT',r.id,COALESCE((SELECT min(id) FROM public.users WHERE role='admin'),1),
          NULL,p_numeric,'HUB_ASSIGNMENT',COALESCE(ro.revision,0),false
        FROM public.restaurant r LEFT JOIN eatwhat_sync.catalog_object ro
          ON ro.kind='RESTAURANT' AND ro.numeric_id=r.id AND ro.managed
        WHERE r.hub_id=p_numeric
        ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
    ELSIF p_kind='RESTAURANT' THEN
      INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
          actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
        SELECT txid_current(),pg_backend_pid(),'FOOD',f.id,COALESCE((SELECT min(id) FROM public.users WHERE role='admin'),1),
          f.account_id,p_numeric,'DELETE',COALESCE(fo.revision,0),false
        FROM public.foods f LEFT JOIN eatwhat_sync.catalog_object fo
          ON fo.kind='FOOD' AND fo.numeric_id=f.id AND fo.managed
        WHERE f.restaurant_id=p_numeric
        ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
    END IF;
    CASE p_kind
      WHEN 'HUB' THEN DELETE FROM public.hub WHERE id=p_numeric;
      WHEN 'RESTAURANT' THEN DELETE FROM public.restaurant WHERE id=p_numeric;
      WHEN 'FOOD' THEN DELETE FROM public.foods WHERE id=p_numeric;
      ELSE DELETE FROM public.food_tags WHERE id=p_numeric;
    END CASE;
    DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current()
      AND backend_id=pg_backend_pid() AND entity_type=entity AND numeric_id=p_numeric;
    RETURN 'TERMINAL';
  END IF;

  -- An active identity with no public row is a withdrawn projection that must be restored in place.
  -- Only a catalog tombstone/rejection suppresses the row permanently.
  IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind=p_kind AND object_gid=p_gid
      AND (tombstone OR rejected)) THEN RETURN 'TERMINAL'; END IF;

  IF p_kind IN ('RESTAURANT','FOOD','CUSTOM_TAG') THEN
    IF p_author IS NULL THEN RAISE EXCEPTION 'Catalog author is required' USING ERRCODE='23514'; END IF;
    SELECT numeric_id INTO v_author_id FROM eatwhat_identity.entity_identity
      WHERE entity_type='users' AND global_id=p_author;
    IF v_author_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=v_author_id) THEN RETURN 'WAIT_PARENT'; END IF;
  END IF;

  IF p_kind='RESTAURANT' THEN
    IF p_payload->>'hubGid' IS NOT NULL THEN
      SELECT numeric_id INTO v_hub_id FROM eatwhat_identity.entity_identity WHERE entity_type='hub'
        AND global_id=(p_payload->>'hubGid')::uuid;
      IF v_hub_id IS NULL THEN RETURN 'WAIT_PARENT'; END IF;
      IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind='HUB'
          AND object_gid=(p_payload->>'hubGid')::uuid AND (tombstone OR rejected)) THEN
        -- Hub deletion means unbound. Keep the restaurant and immutable hubGid evidence,
        -- while projecting the nullable FK to NULL, matching ON DELETE SET NULL semantics.
        v_hub_id:=NULL;
      ELSIF NOT EXISTS(SELECT 1 FROM public.hub WHERE id=v_hub_id) THEN RETURN 'WAIT_PARENT'; END IF;
    END IF;
    IF p_payload->>'poi' IS NOT NULL AND EXISTS(SELECT 1 FROM public.restaurant
       WHERE poi=p_payload->>'poi' AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='FOOD' THEN
    restaurant_gid:=(p_payload->>'restaurantGid')::uuid;
    SELECT numeric_id INTO v_parent_id FROM eatwhat_identity.entity_identity WHERE entity_type='restaurant'
      AND global_id=restaurant_gid;
    IF v_parent_id IS NULL THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT'
        AND object_gid=restaurant_gid AND (tombstone OR rejected)) THEN RETURN 'TERMINAL'; END IF;
    IF NOT EXISTS(SELECT 1 FROM public.restaurant WHERE id=v_parent_id) THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM public.foods WHERE restaurant_id=v_parent_id AND name=p_payload->>'name'
       AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='SYSTEM_TAG' THEN
    IF EXISTS(SELECT 1 FROM public.food_tags WHERE tag_type='system'
       AND normalized_name=p_payload->>'normalizedName' AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='CUSTOM_TAG' THEN
    owner_gid:=(p_payload->>'ownerGid')::uuid;
    IF owner_gid<>p_author THEN RAISE EXCEPTION 'Custom tag owner mismatch' USING ERRCODE='23514'; END IF;
    SELECT numeric_id INTO v_owner_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=owner_gid;
    IF v_owner_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=v_owner_id) THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM public.food_tags WHERE tag_type='custom' AND food_tags.owner_id=v_owner_id
       AND normalized_name=p_payload->>'normalizedName' AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  END IF;

  INSERT INTO eatwhat_consent.import_context(transaction_id,backend_id,entity_type,numeric_id,global_id,origin_site,subject_id)
    VALUES(txid_current(),pg_backend_pid(),entity,p_numeric,p_gid,p_origin,COALESCE(p_author,p_gid));
  IF p_kind='HUB' THEN
    IF EXISTS(SELECT 1 FROM public.hub WHERE id=p_numeric) THEN
      UPDATE public.hub SET name=p_payload->>'name',
        center=public.ST_SetSRID(public.ST_MakePoint((p_payload->'centerWgs'->>'lng')::float8,(p_payload->'centerWgs'->>'lat')::float8),4326),
        gcj_lng=(p_payload->'centerGcj'->>'lng')::float8,gcj_lat=(p_payload->'centerGcj'->>'lat')::float8,
        boundary=CASE WHEN p_payload->>'boundaryGeoJson' IS NULL THEN NULL ELSE
          public.ST_Multi(public.ST_SetSRID(public.ST_GeomFromGeoJSON(p_payload->>'boundaryGeoJson'),4326))::public.geometry(MultiPolygon,4326) END
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.hub(id,name,center,gcj_lng,gcj_lat,boundary) VALUES(p_numeric,p_payload->>'name',
        public.ST_SetSRID(public.ST_MakePoint((p_payload->'centerWgs'->>'lng')::float8,(p_payload->'centerWgs'->>'lat')::float8),4326),
        (p_payload->'centerGcj'->>'lng')::float8,(p_payload->'centerGcj'->>'lat')::float8,
        CASE WHEN p_payload->>'boundaryGeoJson' IS NULL THEN NULL ELSE
          public.ST_Multi(public.ST_SetSRID(public.ST_GeomFromGeoJSON(p_payload->>'boundaryGeoJson'),4326))::public.geometry(MultiPolygon,4326) END);
    END IF;
  ELSIF p_kind='RESTAURANT' THEN
    picture_keys:=ARRAY(SELECT jsonb_array_elements_text(p_payload->'pictureKeys'));
    IF EXISTS(SELECT 1 FROM public.restaurant WHERE id=p_numeric) THEN
      UPDATE public.restaurant SET account_id=v_author_id,name=p_payload->>'name',address=p_payload->>'address',
        city_id=(p_payload->>'cityId')::integer,
        location=public.ST_SetSRID(public.ST_MakePoint((p_payload->'locationWgs'->>'lng')::float8,(p_payload->'locationWgs'->>'lat')::float8),4326)::public.geography,
        gcj_lng=(p_payload->'locationGcj'->>'lng')::float8,gcj_lat=(p_payload->'locationGcj'->>'lat')::float8,
        hub_id=v_hub_id,poi=p_payload->>'poi',picture_url=picture_keys WHERE id=p_numeric;
    ELSE
      INSERT INTO public.restaurant(id,account_id,name,address,city_id,location,gcj_lng,gcj_lat,hub_id,poi,picture_url)
        VALUES(p_numeric,v_author_id,p_payload->>'name',p_payload->>'address',(p_payload->>'cityId')::integer,
          public.ST_SetSRID(public.ST_MakePoint((p_payload->'locationWgs'->>'lng')::float8,(p_payload->'locationWgs'->>'lat')::float8),4326)::public.geography,
          (p_payload->'locationGcj'->>'lng')::float8,(p_payload->'locationGcj'->>'lat')::float8,v_hub_id,p_payload->>'poi',picture_keys);
    END IF;
  ELSIF p_kind='FOOD' THEN
    picture_keys:=ARRAY(SELECT jsonb_array_elements_text(p_payload->'pictureKeys'));
    IF EXISTS(SELECT 1 FROM public.foods WHERE id=p_numeric) THEN
      UPDATE public.foods SET account_id=v_author_id,restaurant_id=v_parent_id,name=p_payload->>'name',
        description=p_payload->>'description',price=(p_payload->>'price')::numeric,category=p_payload->>'category',picture_url=picture_keys
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.foods(id,account_id,restaurant_id,name,description,price,category,picture_url)
        VALUES(p_numeric,v_author_id,v_parent_id,p_payload->>'name',p_payload->>'description',
          (p_payload->>'price')::numeric,p_payload->>'category',picture_keys);
    END IF;
  ELSE
    IF EXISTS(SELECT 1 FROM public.food_tags WHERE id=p_numeric) THEN
      UPDATE public.food_tags SET name=p_payload->>'name',normalized_name=p_payload->>'normalizedName',
        tag_type=p_payload->>'tagType',owner_id=CASE WHEN p_kind='CUSTOM_TAG' THEN v_owner_id ELSE NULL END
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.food_tags(id,name,normalized_name,tag_type,owner_id)
        VALUES(p_numeric,p_payload->>'name',p_payload->>'normalizedName',p_payload->>'tagType',
          CASE WHEN p_kind='CUSTOM_TAG' THEN v_owner_id ELSE NULL END);
    END IF;
  END IF;
  DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid()
    AND entity_type=entity AND numeric_id=p_numeric;
  RETURN 'APPLIED';
END $$;

CREATE OR REPLACE FUNCTION eatwhat_sync.catalog_business_write_check() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_head eatwhat_sync.catalog_revision%ROWTYPE; v_delete_head eatwhat_sync.catalog_revision%ROWTYPE;
  v_gid uuid; v_kind text; v_event boolean;
  v_withdrawal_ctx eatwhat_sync.catalog_withdrawal_write_context%ROWTYPE; v_present boolean;
BEGIN
  v_kind:=NEW.kind;
  SELECT global_id INTO v_gid FROM eatwhat_identity.entity_identity
    WHERE entity_type=eatwhat_sync.catalog_entity_type(v_kind) AND numeric_id=NEW.numeric_id;
  IF NEW.imported AND NOT NEW.withdrawal AND NEW.operation<>'DELETE' AND EXISTS(
      SELECT 1 FROM eatwhat_sync.catalog_decision_projection_context c
      WHERE c.transaction_id=NEW.transaction_id AND c.backend_id=NEW.backend_id AND c.kind=NEW.kind AND c.numeric_id=NEW.numeric_id) THEN
    SELECT r.* INTO v_head FROM eatwhat_sync.catalog_decision_projection_context c
      JOIN eatwhat_sync.catalog_revision r ON r.operation_id=c.winner_operation_id
      JOIN eatwhat_sync.catalog_object o ON o.kind=r.kind AND o.object_gid=r.object_gid
      WHERE c.transaction_id=NEW.transaction_id AND c.backend_id=NEW.backend_id AND c.kind=NEW.kind AND c.numeric_id=NEW.numeric_id
        AND eatwhat_sync.catalog_decision_proven(c.decision_id)
        AND o.numeric_id=NEW.numeric_id AND NOT o.tombstone AND NOT o.rejected
        AND o.causal_state->'heads' @> jsonb_build_array(jsonb_build_object('operationId',r.operation_id::text));
    IF NOT FOUND OR NOT eatwhat_sync.catalog_payload_matches_public(NEW.kind,NEW.numeric_id,v_head.payload)
      OR (NEW.kind IN ('RESTAURANT','FOOD','CUSTOM_TAG') AND NOT EXISTS(
        SELECT 1 FROM eatwhat_identity.entity_identity ui WHERE ui.entity_type='users' AND ui.global_id=v_head.author_gid
        AND ui.numeric_id=CASE NEW.kind WHEN 'RESTAURANT' THEN (SELECT account_id FROM public.restaurant WHERE id=NEW.numeric_id)
          WHEN 'FOOD' THEN (SELECT account_id FROM public.foods WHERE id=NEW.numeric_id)
          ELSE (SELECT owner_id FROM public.food_tags WHERE id=NEW.numeric_id) END)) THEN
      RAISE EXCEPTION 'Catalog decision projection does not match selected immutable head' USING ERRCODE='23514';
    END IF;
    DELETE FROM eatwhat_sync.catalog_decision_projection_context WHERE transaction_id=NEW.transaction_id
      AND backend_id=NEW.backend_id AND kind=NEW.kind AND numeric_id=NEW.numeric_id;
    DELETE FROM eatwhat_sync.catalog_business_write_pending WHERE transaction_id=NEW.transaction_id
      AND backend_id=NEW.backend_id AND kind=NEW.kind AND numeric_id=NEW.numeric_id AND operation=NEW.operation;
    RETURN NULL;
  END IF;
  IF NEW.withdrawal THEN
    SELECT * INTO v_withdrawal_ctx FROM eatwhat_sync.catalog_withdrawal_write_context c
      WHERE c.transaction_id=NEW.transaction_id AND c.backend_id=NEW.backend_id
        AND c.kind=NEW.kind AND c.numeric_id=NEW.numeric_id;
    IF NOT FOUND OR v_gid IS DISTINCT FROM v_withdrawal_ctx.object_gid
      OR NOT EXISTS(SELECT 1 FROM eatwhat_consent.subject s JOIN eatwhat_identity.node_state n ON n.singleton
        JOIN eatwhat_sync.catalog_object o ON o.kind=NEW.kind AND o.numeric_id=NEW.numeric_id
          AND o.object_gid=v_withdrawal_ctx.object_gid AND o.managed
        WHERE s.subject_id=v_withdrawal_ctx.subject_gid AND s.status='WITHDRAWN'
          AND n.mode='EXTERNAL' AND (
            (NEW.kind IN ('RESTAURANT','CUSTOM_TAG') AND o.author_gid=s.subject_id)
            OR (NEW.kind='FOOD' AND (o.author_gid=s.subject_id OR EXISTS(
              SELECT 1 FROM eatwhat_sync.catalog_object p WHERE p.kind='RESTAURANT'
                AND p.object_gid=o.parent_gid AND p.author_gid=s.subject_id))))) THEN
      RAISE EXCEPTION 'Catalog withdrawal context is invalid' USING ERRCODE='42501';
    END IF;
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1)',eatwhat_sync.catalog_entity_type(NEW.kind))
      INTO v_present USING NEW.numeric_id;
    IF v_present OR NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=NEW.kind AND o.numeric_id=NEW.numeric_id AND o.object_gid=v_gid
          AND jsonb_typeof(o.causal_state->'heads')='array'
          AND jsonb_array_length(o.causal_state->'heads')>0
          AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(o.causal_state->'heads') h
            WHERE NOT eatwhat_sync.catalog_head_archive_proven(o.kind,o.object_gid,o.authority_site,h))) THEN
      RAISE EXCEPTION 'Catalog withdrawal projection lacks verified archive evidence' USING ERRCODE='23514';
    END IF;
    DELETE FROM eatwhat_sync.catalog_withdrawal_write_context
      WHERE transaction_id=NEW.transaction_id AND backend_id=NEW.backend_id
        AND kind=NEW.kind AND numeric_id=NEW.numeric_id;
    DELETE FROM eatwhat_sync.catalog_business_write_pending WHERE transaction_id=NEW.transaction_id
      AND backend_id=NEW.backend_id AND kind=NEW.kind AND numeric_id=NEW.numeric_id AND operation=NEW.operation;
    RETURN NULL;
  END IF;
  IF NEW.imported THEN
    IF v_gid IS NULL OR NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=v_kind AND o.object_gid=v_gid AND o.numeric_id=NEW.numeric_id) THEN
      RAISE EXCEPTION 'Inbound catalog write has no persistent identity' USING ERRCODE='23514';
    END IF;
  END IF;
  SELECT r.* INTO v_head FROM eatwhat_sync.catalog_object o
    JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h ON true
    JOIN eatwhat_sync.catalog_revision r ON r.operation_id=(h->>'operationId')::uuid
    WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id AND o.managed
      AND r.revision>NEW.revision_before AND NOT (h->>'tombstone')::boolean
      AND NOT o.tombstone AND NOT o.rejected
    ORDER BY r.revision DESC LIMIT 1;
  IF NEW.operation='DELETE' THEN
    -- DELETE projections select a tombstone head, not the non-tombstone projection head
    -- selected above. Keep its immutable revision so an inbound bridge-only terminal state
    -- can be tied to the exact object and head carried by the authenticated inbox event.
    SELECT r.* INTO v_delete_head FROM eatwhat_sync.catalog_object o
      JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h ON true
      JOIN eatwhat_sync.catalog_revision r ON r.operation_id=(h->>'operationId')::uuid
      WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id AND o.managed AND o.tombstone
        AND r.revision>NEW.revision_before AND r.tombstone
        AND (h->>'tombstone')::boolean
      ORDER BY r.revision DESC LIMIT 1;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id AND o.managed AND o.tombstone
          AND o.revision>NEW.revision_before AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision r
            WHERE r.kind=o.kind AND r.object_gid=o.object_gid AND r.tombstone AND r.revision=o.revision))
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.hub WHERE id=NEW.numeric_id)
          AND v_kind='HUB')
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.restaurant WHERE id=NEW.numeric_id)
          AND v_kind='RESTAURANT')
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.foods WHERE id=NEW.numeric_id)
          AND v_kind='FOOD')
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.food_tags WHERE id=NEW.numeric_id)
          AND v_kind IN ('SYSTEM_TAG','CUSTOM_TAG'))
      AND NOT (v_kind='FOOD' AND NOT EXISTS(SELECT 1 FROM public.foods WHERE id=NEW.numeric_id)
        AND (EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object child
          JOIN eatwhat_sync.catalog_object parent ON parent.kind='RESTAURANT' AND parent.object_gid=child.parent_gid
          WHERE child.kind='FOOD' AND child.numeric_id=NEW.numeric_id AND (parent.tombstone OR parent.rejected))
          OR EXISTS(SELECT 1 FROM eatwhat_sync.catalog_business_write_context child_ctx
            JOIN eatwhat_identity.entity_identity parent_identity
              ON parent_identity.entity_type='restaurant' AND parent_identity.numeric_id=child_ctx.parent_numeric_id
            JOIN eatwhat_sync.catalog_object parent
              ON parent.kind='RESTAURANT' AND parent.object_gid=parent_identity.global_id
                AND parent.numeric_id=child_ctx.parent_numeric_id AND parent.managed
            WHERE child_ctx.transaction_id=NEW.transaction_id AND child_ctx.backend_id=NEW.backend_id
              AND child_ctx.kind='FOOD' AND child_ctx.numeric_id=NEW.numeric_id
              AND child_ctx.operation='DELETE' AND child_ctx.touched
              AND (parent.tombstone OR parent.rejected)))) THEN
      RAISE EXCEPTION 'Managed catalog delete lacks a newer tombstone' USING ERRCODE='23514';
    END IF;
    IF NEW.imported AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.inbox i
        CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(i.envelope->'payloadJson')='string'
          THEN (i.envelope->>'payloadJson')::jsonb ELSE i.envelope->'payloadJson' END AS payload) e
        WHERE i.aggregate_type='catalog.operation' AND i.status IN ('PROCESSING','APPLIED')
          AND v_delete_head.operation_id IS NOT NULL
          AND ((i.envelope->>'eventType'='catalog.action' AND i.event_id=v_delete_head.operation_id
                AND e.payload->>'kind'=v_kind AND e.payload->>'objectGid'=v_gid::text
                AND e.payload->>'numericId'=NEW.numeric_id::text
                AND e.payload->>'operationId'=v_delete_head.operation_id::text
                AND e.payload->>'contentHash'=v_delete_head.content_hash AND e.payload->>'operation'='DELETE')
            OR (i.envelope->>'eventType'='catalog.state' AND e.payload->>'kind'=v_kind
                AND e.payload->>'objectGid'=v_gid::text AND e.payload->>'numericId'=NEW.numeric_id::text
                AND EXISTS(SELECT 1 FROM jsonb_array_elements(e.payload->'heads') h
                  WHERE h->>'operationId'=v_delete_head.operation_id::text
                    AND h->>'numericId'=NEW.numeric_id::text
                    AND h->>'contentHash'=v_delete_head.content_hash
                    AND h->>'operation'='DELETE' AND h->>'tombstone'='true')))) THEN
      RAISE EXCEPTION 'Inbound catalog delete lacks its inbox action' USING ERRCODE='23514';
    END IF;
  ELSIF v_kind='RESTAURANT' AND NEW.operation='UPDATE'
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind='RESTAURANT'
        AND o.numeric_id=NEW.numeric_id AND o.revision>NEW.revision_before AND NOT o.tombstone AND NOT o.rejected)
      AND EXISTS(SELECT 1 FROM public.restaurant r WHERE r.id=NEW.numeric_id AND r.hub_id IS NULL)
      AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind='HUB' AND o.tombstone
        AND o.object_gid=(SELECT parent_gid FROM eatwhat_sync.catalog_object r
          WHERE r.kind='RESTAURANT' AND r.numeric_id=NEW.numeric_id)) THEN
    NULL;
  ELSE
    IF v_head.operation_id IS NULL OR NOT eatwhat_sync.catalog_payload_matches_public(v_kind,NEW.numeric_id,v_head.payload) THEN
      RAISE EXCEPTION 'Managed catalog public row does not match a new immutable revision' USING ERRCODE='23514';
    END IF;
    IF v_kind IN ('RESTAURANT','FOOD','CUSTOM_TAG') AND NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity ui
        WHERE ui.entity_type='users' AND ui.global_id=v_head.author_gid AND ui.numeric_id=CASE v_kind
          WHEN 'RESTAURANT' THEN (SELECT account_id FROM public.restaurant WHERE id=NEW.numeric_id)
          WHEN 'FOOD' THEN (SELECT account_id FROM public.foods WHERE id=NEW.numeric_id)
          ELSE (SELECT owner_id FROM public.food_tags WHERE id=NEW.numeric_id) END) THEN
      RAISE EXCEPTION 'Managed catalog author does not match public owner' USING ERRCODE='23514';
    END IF;
    IF v_kind='FOOD' AND NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity ri
        WHERE ri.entity_type='restaurant' AND ri.global_id=v_head.parent_gid
          AND ri.numeric_id=(SELECT restaurant_id FROM public.foods WHERE id=NEW.numeric_id)) THEN
      RAISE EXCEPTION 'Managed food parent does not match immutable revision' USING ERRCODE='23514';
    END IF;
    SELECT EXISTS(SELECT 1 FROM eatwhat_sync.outbox ob
      JOIN eatwhat_identity.node_state local_node ON local_node.singleton
      CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(ob.envelope->'payloadJson')='string'
        THEN (ob.envelope->>'payloadJson')::jsonb ELSE ob.envelope->'payloadJson' END AS payload) event_payload
      WHERE ob.aggregate_type='catalog.operation' AND ob.aggregate_id=v_head.operation_id
        AND ob.event_id=v_head.operation_id AND ob.origin_site=local_node.site_id
        AND ob.envelope->>'eventType'='catalog.action'
        AND (ob.envelope->>'payloadJson') IS NOT NULL
        AND event_payload.payload->>'operationId'=v_head.operation_id::text
        AND event_payload.payload->>'kind'=v_kind
        AND event_payload.payload->>'objectGid'=v_gid::text
        AND event_payload.payload->>'contentHash'=v_head.content_hash) INTO v_event;
    IF NOT NEW.imported AND NEW.expect_outbox AND NOT v_event THEN
      RAISE EXCEPTION 'Authorized catalog write lacks matching outbox action' USING ERRCODE='23514';
    END IF;
    -- Imported projections still require their exact inbox event. A local conflict resolution
    -- is accepted only when its exact action already exists in this site's outbox (v_event).
    IF NEW.imported AND NOT v_event AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.inbox i
        CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(i.envelope->'payloadJson')='string'
          THEN (i.envelope->>'payloadJson')::jsonb ELSE i.envelope->'payloadJson' END AS payload) e
        WHERE i.aggregate_type='catalog.operation' AND i.status IN ('PROCESSING','APPLIED')
          AND ((i.envelope->>'eventType'='catalog.action' AND e.payload->>'operationId'=v_head.operation_id::text)
            OR (i.envelope->>'eventType'='catalog.state' AND e.payload->>'kind'=v_kind
              AND e.payload->>'objectGid'=v_gid::text
              AND EXISTS(SELECT 1 FROM jsonb_array_elements(e.payload->'heads') h
                WHERE h->>'operationId'=v_head.operation_id::text
                  AND h->>'tombstone'='false')))) THEN
      RAISE EXCEPTION 'Inbound catalog projection lacks its inbox event' USING ERRCODE='23514';
    END IF;
  END IF;
  DELETE FROM eatwhat_sync.catalog_business_write_pending WHERE transaction_id=NEW.transaction_id
    AND backend_id=NEW.backend_id AND kind=NEW.kind AND numeric_id=NEW.numeric_id AND operation=NEW.operation;
  DELETE FROM eatwhat_sync.catalog_business_write_context WHERE transaction_id=NEW.transaction_id
    AND backend_id=NEW.backend_id AND kind=NEW.kind AND (numeric_id=NEW.numeric_id OR numeric_id=0);
  RETURN NULL;
END $$;

-- Erasure is an explicit exception for exact retained rows, never a general runtime mutation switch.
CREATE TABLE eatwhat_sync.scoped_erasure_context (
 transaction_id bigint NOT NULL, backend_id integer NOT NULL, table_name text NOT NULL, row_data jsonb NOT NULL
);
REVOKE ALL ON eatwhat_sync.scoped_erasure_context FROM PUBLIC;
CREATE FUNCTION eatwhat_sync.scoped_erasure_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' AND TG_LEVEL='ROW' AND TG_TABLE_SCHEMA='eatwhat_sync' AND EXISTS(
   SELECT 1 FROM eatwhat_sync.scoped_erasure_context c WHERE c.transaction_id=txid_current()
    AND c.backend_id=pg_backend_pid() AND c.table_name=TG_TABLE_NAME AND c.row_data=to_jsonb(OLD)) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Retained history requires scoped withdrawal erasure' USING ERRCODE='55000';
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.scoped_erasure_guard() FROM PUBLIC;
CREATE FUNCTION eatwhat_sync.erase_scoped_rows(p_table text,p_rows jsonb) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE removed bigint;
BEGIN
 IF p_table !~ '^[a-z][a-z0-9_]*$' OR jsonb_typeof(p_rows)<>'array' THEN
  RAISE EXCEPTION 'Invalid erasure scope' USING ERRCODE='23514'; END IF;
 INSERT INTO eatwhat_sync.scoped_erasure_context SELECT txid_current(),pg_backend_pid(),p_table,value FROM jsonb_array_elements(p_rows);
 EXECUTE format('DELETE FROM eatwhat_sync.%I r USING eatwhat_sync.scoped_erasure_context c WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid() AND c.table_name=$1 AND c.row_data=to_jsonb(r)',p_table) USING p_table;
 GET DIAGNOSTICS removed=ROW_COUNT;
 DELETE FROM eatwhat_sync.scoped_erasure_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid() AND table_name=p_table;
 RETURN removed;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.erase_scoped_rows(text,jsonb) FROM PUBLIC;
-- Preserve existing UPDATE/TRUNCATE rejection, replacing only DELETE with the exact-row guard.
DO $$ DECLARE r record; extra text; BEGIN
 FOR r IN SELECT t.tgname,c.relname,t.tgtype,p.proname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
   JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid
   WHERE n.nspname='eatwhat_sync' AND NOT t.tgisinternal AND (t.tgtype::int & 8)<>0
     AND p.proname IN ('catalog_immutable','reject_event_mutation')
 LOOP
   EXECUTE format('DROP TRIGGER %I ON eatwhat_sync.%I',r.tgname,r.relname);
   EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON eatwhat_sync.%I FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.scoped_erasure_guard()',r.tgname,r.relname);
   extra:=concat_ws(' OR ',CASE WHEN (r.tgtype::int & 16)<>0 THEN 'UPDATE' END,CASE WHEN (r.tgtype::int & 32)<>0 THEN 'TRUNCATE' END);
   IF extra<>'' THEN EXECUTE format('CREATE TRIGGER %I BEFORE %s ON eatwhat_sync.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.%I()',left(r.tgname,48)||'_other',extra,r.relname,r.proname); END IF;
 END LOOP;
END $$;

CREATE TABLE eatwhat_sync.catalog_erased_operation (
 operation_id uuid PRIMARY KEY, object_gid uuid NOT NULL, kind varchar(24) NOT NULL,
 content_hash char(64) NOT NULL, erased_epoch bigint NOT NULL
);
REVOKE ALL ON eatwhat_sync.catalog_erased_operation FROM PUBLIC;
CREATE FUNCTION eatwhat_sync.check_erased_catalog_identity() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_erased_operation e WHERE e.operation_id=NEW.operation_id
   AND (e.object_gid<>NEW.object_gid OR e.kind<>NEW.kind OR e.content_hash<>NEW.content_hash)) THEN
  RAISE EXCEPTION 'Erased catalog identity cannot be rewritten' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER erased_catalog_identity BEFORE INSERT ON eatwhat_sync.catalog_revision
 FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.check_erased_catalog_identity();
REVOKE ALL ON FUNCTION eatwhat_sync.check_erased_catalog_identity() FROM PUBLIC;
ALTER FUNCTION eatwhat_sync.prepare_catalog_withdrawal_cleanup(uuid,bigint) RENAME TO prepare_catalog_withdrawal_cleanup_v1;
REVOKE ALL ON FUNCTION eatwhat_sync.prepare_catalog_withdrawal_cleanup_v1(uuid,bigint) FROM PUBLIC;
CREATE FUNCTION eatwhat_sync.prepare_catalog_withdrawal_cleanup(p_subject uuid,p_user_id bigint) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE gids uuid[]; conflicts uuid[]; operations uuid[]; events uuid[]; epoch bigint; rows jsonb; name text; ready boolean;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('eatwhat.catalog.domain',0));
 ready:=eatwhat_sync.prepare_catalog_withdrawal_cleanup_v1(p_subject,p_user_id);
 IF NOT ready OR NOT (SELECT enabled FROM eatwhat_sync.catalog_multiwriter_config WHERE singleton) THEN RETURN ready; END IF;
 -- Complete public-DML proof checks while their original archive evidence still exists.
 SET CONSTRAINTS eatwhat_sync.catalog_business_write_deferred IMMEDIATE;
 SET CONSTRAINTS eatwhat_sync.catalog_business_write_deferred DEFERRED;
 SELECT s.epoch INTO epoch FROM eatwhat_consent.subject s JOIN eatwhat_identity.node_state n ON n.singleton
   WHERE s.subject_id=p_subject AND s.user_id=p_user_id AND s.status='WITHDRAWN' AND n.mode='EXTERNAL';
 IF epoch IS NULL THEN RAISE EXCEPTION 'Invalid catalog erasure authorization' USING ERRCODE='42501'; END IF;
 SELECT coalesce(array_agg(o.object_gid),'{}'::uuid[]) INTO gids FROM eatwhat_sync.catalog_object o
   WHERE o.author_gid=p_subject OR o.kind='FOOD' AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object p
     WHERE p.kind='RESTAURANT' AND p.object_gid=o.parent_gid AND p.author_gid=p_subject);
 SELECT coalesce(array_agg(r.operation_id),'{}'::uuid[]) INTO operations FROM eatwhat_sync.catalog_revision r WHERE r.object_gid=ANY(gids);
 SELECT coalesce(array_agg(DISTINCT id),'{}'::uuid[]) INTO conflicts FROM (
   SELECT conflict_id id FROM eatwhat_sync.catalog_head_conflict WHERE object_gid=ANY(gids)
   UNION SELECT c.conflict_id FROM eatwhat_sync.catalog_conflict c JOIN eatwhat_sync.catalog_candidate a ON a.kind=c.kind AND a.key_hash=c.key_hash WHERE a.object_gid=ANY(gids)) q;
 -- Raw event envelopes are erased by V69's shared personal retention cleanup after these archive proofs.
 FOREACH name IN ARRAY ARRAY['catalog_conflict_audit','catalog_resolution_receipt','catalog_head_conflict_audit','catalog_head_resolution_receipt','catalog_decision_authorization_review','catalog_decision_operation','catalog_decision_state'] LOOP
   EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(t)),''[]''::jsonb) FROM eatwhat_sync.%I t WHERE conflict_id=ANY($1)',name) INTO rows USING conflicts;
   PERFORM eatwhat_sync.erase_scoped_rows(name,rows);
 END LOOP;
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.catalog_decision_archive t WHERE t.payload->>'conflictId'=ANY(SELECT c::text FROM unnest(conflicts) c);
 PERFORM eatwhat_sync.erase_scoped_rows('catalog_decision_archive',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.catalog_authorization_review t WHERE t.object_gid=ANY(gids);
 PERFORM eatwhat_sync.erase_scoped_rows('catalog_authorization_review',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.catalog_actor_evidence t WHERE t.operation_id=ANY(operations);
 PERFORM eatwhat_sync.erase_scoped_rows('catalog_actor_evidence',rows);
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.catalog_assignment t WHERE t.operation_id=ANY(operations);
 PERFORM eatwhat_sync.erase_scoped_rows('catalog_assignment',rows);
 INSERT INTO eatwhat_sync.catalog_erased_operation SELECT r.operation_id,r.object_gid,r.kind,r.content_hash,epoch
   FROM eatwhat_sync.catalog_revision r WHERE r.object_gid=ANY(gids) ON CONFLICT DO NOTHING;
 SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') INTO rows FROM eatwhat_sync.catalog_revision t WHERE t.object_gid=ANY(gids);
 PERFORM eatwhat_sync.erase_scoped_rows('catalog_revision',rows);
 DELETE FROM eatwhat_sync.catalog_projection_state WHERE object_gid=ANY(gids);
 DELETE FROM eatwhat_sync.catalog_head_conflict WHERE object_gid=ANY(gids);
 DELETE FROM eatwhat_sync.catalog_candidate WHERE object_gid=ANY(gids);
 UPDATE eatwhat_sync.catalog_conflict SET status='OPEN',candidates='[]'::jsonb WHERE conflict_id=ANY(conflicts);
 DELETE FROM eatwhat_sync.catalog_conflict c WHERE c.conflict_id=ANY(conflicts) AND NOT EXISTS(
   SELECT 1 FROM eatwhat_sync.catalog_candidate a WHERE a.kind=c.kind AND a.key_hash=c.key_hash);
 DELETE FROM eatwhat_sync.catalog_natural_key k WHERE NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_candidate a WHERE a.kind=k.kind AND a.key_hash=k.key_hash)
   AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_conflict c WHERE c.kind=k.kind AND c.key_hash=k.key_hash);
 UPDATE eatwhat_sync.catalog_object SET causal_state=jsonb_build_object('seen',coalesce(causal_state->'seen','{}'::jsonb),'heads','[]'::jsonb),updated_at=clock_timestamp() WHERE object_gid=ANY(gids);
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.prepare_catalog_withdrawal_cleanup(uuid,bigint) FROM PUBLIC;
