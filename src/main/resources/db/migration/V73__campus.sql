SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';

-- 校区（产品文档 4.1）：「在哪吃」，决定推荐、附近和专题的范围；一个校区包含若干商圈。
-- 校区由迁移按固定 id 写入，每个站点都一样，所以商圈的同步数据可以直接引用 campus.id。
-- 新增校区也要写迁移，不提供在线新建的接口。
CREATE TABLE public.campus (
  id          bigint PRIMARY KEY,
  name        varchar(32) NOT NULL UNIQUE,
  gcj_lng     double precision NOT NULL CHECK (gcj_lng BETWEEN 72.004 AND 137.8347),
  gcj_lat     double precision NOT NULL CHECK (gcj_lat BETWEEN 0.8293 AND 55.8271),
  is_default  boolean NOT NULL DEFAULT false,
  open        boolean NOT NULL DEFAULT true,
  sort        integer NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now()
);
-- 默认校区：没有定位时选它；没有归属校区的商圈和不属于任何商圈的店也算在它下面。
CREATE UNIQUE INDEX campus_single_default ON public.campus (is_default) WHERE is_default;
-- 中心点是校区大致位置（GCJ-02），只用于选最近的校区和判断是否离校区太远，上线前请运营核对。
INSERT INTO public.campus (id, name, gcj_lng, gcj_lat, is_default, open, sort)
VALUES (1, '软件园', 117.1386, 36.6665, true, true, 0);

ALTER TABLE public.hub ADD COLUMN campus_id bigint REFERENCES public.campus (id);
CREATE INDEX idx_hub_campus_id ON public.hub (campus_id);

-- Hub catalog payloads carry campusId (omitted when NULL), so the assignment replicates with the hub.
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
      UPDATE public.hub SET name=p_payload->>'name',campus_id=(p_payload->>'campusId')::bigint,
        center=public.ST_SetSRID(public.ST_MakePoint((p_payload->'centerWgs'->>'lng')::float8,(p_payload->'centerWgs'->>'lat')::float8),4326),
        gcj_lng=(p_payload->'centerGcj'->>'lng')::float8,gcj_lat=(p_payload->'centerGcj'->>'lat')::float8,
        boundary=CASE WHEN p_payload->>'boundaryGeoJson' IS NULL THEN NULL ELSE
          public.ST_Multi(public.ST_SetSRID(public.ST_GeomFromGeoJSON(p_payload->>'boundaryGeoJson'),4326))::public.geometry(MultiPolygon,4326) END
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.hub(id,name,center,gcj_lng,gcj_lat,boundary,campus_id) VALUES(p_numeric,p_payload->>'name',
        public.ST_SetSRID(public.ST_MakePoint((p_payload->'centerWgs'->>'lng')::float8,(p_payload->'centerWgs'->>'lat')::float8),4326),
        (p_payload->'centerGcj'->>'lng')::float8,(p_payload->'centerGcj'->>'lat')::float8,
        CASE WHEN p_payload->>'boundaryGeoJson' IS NULL THEN NULL ELSE
          public.ST_Multi(public.ST_SetSRID(public.ST_GeomFromGeoJSON(p_payload->>'boundaryGeoJson'),4326))::public.geometry(MultiPolygon,4326) END,
        (p_payload->>'campusId')::bigint);
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
        description=p_payload->>'description',price=(p_payload->>'price')::numeric,category=p_payload->>'category',picture_url=picture_keys,
        meal_times=ARRAY(SELECT jsonb_array_elements_text(p_payload->'mealTimes')),
        stage_color=p_payload->>'stageColor',
        stage_alternates=CASE WHEN p_payload ? 'stageAlternates' THEN ARRAY(SELECT jsonb_array_elements_text(p_payload->'stageAlternates')) END,
        cover_center_x=(p_payload->'coverCrop'->>'centerX')::numeric,cover_center_y=(p_payload->'coverCrop'->>'centerY')::numeric,
        cover_diameter=(p_payload->'coverCrop'->>'diameter')::numeric,
        remove_background=COALESCE((p_payload->>'removeBackground')::boolean,false)
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.foods(id,account_id,restaurant_id,name,description,price,category,picture_url,
          meal_times,stage_color,stage_alternates,cover_center_x,cover_center_y,cover_diameter,remove_background)
        VALUES(p_numeric,v_author_id,v_parent_id,p_payload->>'name',p_payload->>'description',
          (p_payload->>'price')::numeric,p_payload->>'category',picture_keys,
          ARRAY(SELECT jsonb_array_elements_text(p_payload->'mealTimes')),p_payload->>'stageColor',
          CASE WHEN p_payload ? 'stageAlternates' THEN ARRAY(SELECT jsonb_array_elements_text(p_payload->'stageAlternates')) END,
          (p_payload->'coverCrop'->>'centerX')::numeric,(p_payload->'coverCrop'->>'centerY')::numeric,
          (p_payload->'coverCrop'->>'diameter')::numeric,COALESCE((p_payload->>'removeBackground')::boolean,false));
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

CREATE OR REPLACE FUNCTION eatwhat_sync.catalog_payload_matches_public(p_kind text,p_id bigint,p_payload jsonb) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_user_gid uuid; v_parent_gid uuid; v_hub_gid uuid; v_owner_gid uuid; v_expected jsonb;
BEGIN
  IF p_kind='HUB' THEN
    -- Keep the exact float8 JSON representation used by CatalogStore's JDBC Double reader.
    -- Casting float8 to numeric here rounds differently from BigDecimal.valueOf(double),
    -- so valid high-precision coordinates could fail the immutable-payload check.
    SELECT jsonb_build_object('name',h.name,'centerWgs',jsonb_build_object('lng',public.ST_X(h.center),'lat',public.ST_Y(h.center)),
      'centerGcj',jsonb_build_object('lng',h.gcj_lng,'lat',h.gcj_lat),
      'boundaryGeoJson',CASE WHEN h.boundary IS NULL THEN NULL ELSE public.ST_AsGeoJSON(h.boundary) END)
      -- campusId appears only when set, so pre-V73 hub heads keep matching.
      || CASE WHEN h.campus_id IS NOT NULL THEN jsonb_build_object('campusId',h.campus_id) ELSE '{}'::jsonb END
      INTO v_expected FROM public.hub h WHERE h.id=p_id;
  ELSIF p_kind='RESTAURANT' THEN
    SELECT ii.global_id INTO v_user_gid FROM public.restaurant r LEFT JOIN eatwhat_identity.entity_identity ii
      ON ii.entity_type='users' AND ii.numeric_id=r.account_id WHERE r.id=p_id;
    SELECT hi.global_id INTO v_hub_gid FROM public.restaurant r LEFT JOIN eatwhat_identity.entity_identity hi
      ON hi.entity_type='hub' AND hi.numeric_id=r.hub_id WHERE r.id=p_id;
    SELECT jsonb_build_object('name',r.name,'address',r.address,'cityId',r.city_id,
      'locationWgs',jsonb_build_object('lng',public.ST_X(r.location::public.geometry),'lat',public.ST_Y(r.location::public.geometry)),
      'locationGcj',jsonb_build_object('lng',r.gcj_lng,'lat',r.gcj_lat),'hubGid',v_hub_gid,
      'poi',r.poi,'pictureKeys',COALESCE(to_jsonb(r.picture_url),'[]'::jsonb))
      INTO v_expected FROM public.restaurant r WHERE r.id=p_id;
    -- A deleted hub's foreign key is set NULL while the head retains its historical hub GID.
    IF v_expected IS NOT NULL AND p_payload->>'hubGid' IS NOT NULL AND v_hub_gid IS NULL
        AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object ho WHERE ho.kind='HUB'
          AND ho.object_gid=(p_payload->>'hubGid')::uuid AND (ho.tombstone OR ho.rejected)) THEN
      v_expected:=jsonb_set(v_expected,'{hubGid}',p_payload->'hubGid');
    END IF;
    IF v_expected IS NOT NULL THEN v_expected:=v_expected||jsonb_build_object('authorGid',v_user_gid); END IF;
  ELSIF p_kind='FOOD' THEN
    SELECT ri.global_id INTO v_parent_gid FROM public.foods f LEFT JOIN eatwhat_identity.entity_identity ri
      ON ri.entity_type='restaurant' AND ri.numeric_id=f.restaurant_id WHERE f.id=p_id;
    SELECT ui.global_id INTO v_user_gid FROM public.foods f LEFT JOIN eatwhat_identity.entity_identity ui
      ON ui.entity_type='users' AND ui.numeric_id=f.account_id WHERE f.id=p_id;
    SELECT jsonb_build_object('restaurantGid',v_parent_gid,'name',f.name,'description',f.description,'price',f.price,
      'category',f.category,'pictureKeys',COALESCE(to_jsonb(f.picture_url),'[]'::jsonb),'authorGid',v_user_gid)
      -- Display fields appear only when they differ from their defaults, so pre-V72 heads keep matching.
      || CASE WHEN cardinality(f.meal_times)>0 THEN jsonb_build_object('mealTimes',to_jsonb(f.meal_times)) ELSE '{}'::jsonb END
      || CASE WHEN f.stage_color IS NOT NULL THEN jsonb_build_object('stageColor',f.stage_color) ELSE '{}'::jsonb END
      || CASE WHEN f.stage_alternates IS NOT NULL THEN jsonb_build_object('stageAlternates',to_jsonb(f.stage_alternates)) ELSE '{}'::jsonb END
      || CASE WHEN f.cover_center_x IS NOT NULL THEN jsonb_build_object('coverCrop',jsonb_build_object(
           'centerX',f.cover_center_x,'centerY',f.cover_center_y,'diameter',f.cover_diameter)) ELSE '{}'::jsonb END
      || CASE WHEN f.remove_background THEN jsonb_build_object('removeBackground',true) ELSE '{}'::jsonb END
      INTO v_expected FROM public.foods f WHERE f.id=p_id;
  ELSIF p_kind IN ('SYSTEM_TAG','CUSTOM_TAG') THEN
    SELECT ui.global_id INTO v_owner_gid FROM public.food_tags t LEFT JOIN eatwhat_identity.entity_identity ui
      ON ui.entity_type='users' AND ui.numeric_id=t.owner_id WHERE t.id=p_id;
    SELECT jsonb_build_object('name',t.name,'normalizedName',t.normalized_name,
      'tagType',t.tag_type,'ownerGid',v_owner_gid) INTO v_expected FROM public.food_tags t WHERE t.id=p_id;
  END IF;
  IF v_expected IS NULL THEN RETURN false; END IF;
  -- Catalog payloads omit locally derived identity fields not present in the canonical domain.
  IF p_kind='HUB' THEN
    IF v_expected IS NULL OR (v_expected-'boundaryGeoJson')<>(p_payload-'boundaryGeoJson') THEN RETURN false; END IF;
    IF p_payload->>'boundaryGeoJson' IS NULL THEN RETURN v_expected->'boundaryGeoJson'=p_payload->'boundaryGeoJson'; END IF;
    RETURN (v_expected->>'boundaryGeoJson')::jsonb=(p_payload->>'boundaryGeoJson')::jsonb;
  END IF;
  IF p_kind='RESTAURANT' THEN RETURN (v_expected-'authorGid')=p_payload; END IF;
  IF p_kind='FOOD' THEN RETURN (v_expected-'authorGid')=p_payload; END IF;
  IF p_kind='SYSTEM_TAG' THEN RETURN (v_expected-'ownerGid')=p_payload; END IF;
  RETURN v_expected=p_payload;
END $$;
