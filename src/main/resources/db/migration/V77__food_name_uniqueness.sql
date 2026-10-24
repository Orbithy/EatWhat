SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Shared by local writes, natural keys and inbound catalog projection. Preserve display spelling.
CREATE FUNCTION eatwhat_sync.food_name_key(p_name text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE SET search_path=pg_catalog
AS $$ SELECT translate(p_name, E' \t\n\r\f\013　', '') $$;

-- Never silently discard existing dishes or invalidate recorded conflict decisions.
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM public.foods WHERE catalog_visible
      GROUP BY restaurant_id,eatwhat_sync.food_name_key(name) HAVING count(*)>1) THEN
    RAISE EXCEPTION 'P1 preflight: resolve visible normalized food-name duplicates before V77';
  END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_conflict c
      JOIN eatwhat_sync.catalog_natural_key k USING(kind,key_hash)
      WHERE c.kind='FOOD' AND k.canonical_key->>'name'<>eatwhat_sync.food_name_key(k.canonical_key->>'name')) THEN
    RAISE EXCEPTION 'P1 preflight: food-name conflict history requires an explicit compatibility migration';
  END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_candidate c
      JOIN eatwhat_sync.catalog_natural_key k USING(kind,key_hash) WHERE c.kind='FOOD'
      GROUP BY c.object_gid,k.canonical_key->>'restaurantGid',eatwhat_sync.food_name_key(k.canonical_key->>'name')
      HAVING count(*)>1) THEN
    RAISE EXCEPTION 'P1 preflight: colliding historical food-name candidates require explicit reconciliation';
  END IF;
END $$;

CREATE TEMP TABLE p1_food_key_rewrite ON COMMIT DROP AS
SELECT key_hash old_hash,
       encode(sha256(convert_to('{"name":'||to_json(eatwhat_sync.food_name_key(canonical_key->>'name'))::text||
          ',"restaurantGid":'||to_json(canonical_key->>'restaurantGid')::text||'}','UTF8')),'hex') new_hash,
       jsonb_set(canonical_key,'{name}',to_jsonb(eatwhat_sync.food_name_key(canonical_key->>'name'))) new_key
FROM eatwhat_sync.catalog_natural_key WHERE kind='FOOD'
  AND canonical_key->>'name'<>eatwhat_sync.food_name_key(canonical_key->>'name');
INSERT INTO eatwhat_sync.catalog_natural_key(kind,key_hash,canonical_key)
SELECT DISTINCT 'FOOD',new_hash,new_key FROM p1_food_key_rewrite ON CONFLICT DO NOTHING;
UPDATE eatwhat_sync.catalog_candidate c SET key_hash=m.new_hash
FROM p1_food_key_rewrite m WHERE c.kind='FOOD' AND c.key_hash=m.old_hash;

CREATE UNIQUE INDEX foods_restaurant_normalized_name_visible_key
ON public.foods(restaurant_id,eatwhat_sync.food_name_key(name)) WHERE catalog_visible;

-- Amend the installed projection in place: V74.2 and V76 differ in unrelated activity support.
-- Preserve that installed behavior, its SECURITY DEFINER boundary, and all existing grants.
DO $migration$
DECLARE definition text;
  before_text text := 'restaurant_id=v_parent_id AND name=p_payload->>''name''';
  after_text text := 'restaurant_id=v_parent_id AND eatwhat_sync.food_name_key(name)=eatwhat_sync.food_name_key(p_payload->>''name'')';
BEGIN
  definition := pg_get_functiondef('eatwhat_sync.apply_catalog_projection(text,uuid,bigint,text,uuid,uuid,text,jsonb)'::regprocedure);
  IF (length(definition)-length(replace(definition,before_text,'')))/length(before_text)<>1 THEN
    RAISE EXCEPTION 'P1 preflight: unsupported installed food projection definition';
  END IF;
  EXECUTE replace(definition,before_text,after_text);
END $migration$;
