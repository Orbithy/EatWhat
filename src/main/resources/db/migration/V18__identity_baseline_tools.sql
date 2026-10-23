SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Operator-only metadata. Runtime receives no privileges on these tables.
CREATE TABLE eatwhat_identity.baseline (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    baseline_id uuid NOT NULL UNIQUE,
    manifest_version integer NOT NULL CHECK (manifest_version = 1),
    baseline_role varchar(16) NOT NULL CHECK (baseline_role IN ('AUTHORITATIVE', 'REPLICA')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE eatwhat_identity.baseline_checkpoint (
    entity_type varchar(32) PRIMARY KEY CHECK (entity_type IN (
        'users','contacts','verifications','activity_foods','activity_dinners','activity_likes',
        'notifications','follow','privacy','hub','restaurant','foods','browse_history',
        'food_tags','food_taggings','food_favorites','likes'
    )),
    last_numeric_id bigint NOT NULL DEFAULT 0 CHECK (last_numeric_id >= 0),
    high_watermark bigint NOT NULL CHECK (high_watermark BETWEEN 0 AND 1000000000),
    imported_rows bigint NOT NULL DEFAULT 0 CHECK (imported_rows >= 0),
    completed boolean NOT NULL DEFAULT false,
    CHECK (last_numeric_id <= high_watermark)
);

-- Do not inherit deployment default grants intended for public business tables.
REVOKE ALL ON eatwhat_identity.baseline, eatwhat_identity.baseline_checkpoint FROM PUBLIC;
DO $$
DECLARE grant_row record;
BEGIN
    FOR grant_row IN
        SELECT DISTINCT grantee FROM information_schema.table_privileges
        WHERE table_schema = 'eatwhat_identity'
          AND table_name IN ('baseline', 'baseline_checkpoint')
          AND grantee NOT IN (current_user, 'PUBLIC')
    LOOP
        EXECUTE format('REVOKE ALL ON eatwhat_identity.baseline, eatwhat_identity.baseline_checkpoint FROM %I', grant_row.grantee);
    END LOOP;
END $$;

-- An explicit baseline binds a frozen preparation database across process restarts and batches.
-- There is deliberately no unfreeze operation in this phase.
CREATE FUNCTION eatwhat_identity.guard_baseline_business_write() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, eatwhat_identity
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM eatwhat_identity.baseline) THEN
        RAISE EXCEPTION 'Business writes are frozen for identity baseline preparation' USING ERRCODE = '55000';
    END IF;
    RETURN NULL;
END $$;

REVOKE ALL ON FUNCTION eatwhat_identity.guard_baseline_business_write() FROM PUBLIC;

-- Installed as an additional statement trigger by the first operator baseline binding.
-- The unconditional exception must not depend on a writer's pre-existing MVCC snapshot.
-- Trigger DDL and the baseline metadata commit or roll back in the same transaction.
CREATE FUNCTION eatwhat_identity.reject_frozen_business_write() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, eatwhat_identity
AS $$
BEGIN
    RAISE EXCEPTION 'Business writes are frozen for identity baseline preparation' USING ERRCODE = '55000';
END $$;

REVOKE ALL ON FUNCTION eatwhat_identity.reject_frozen_business_write() FROM PUBLIC;

DO $$
DECLARE business_table text;
BEGIN
    FOREACH business_table IN ARRAY ARRAY[
        'users','contacts','verifications','activity_foods','activity_dinners','activity_likes',
        'notifications','follow','privacy','hub','restaurant','foods','browse_history',
        'food_tags','food_taggings','food_favorites','likes','user_info','provinces','cities'
    ] LOOP
        EXECUTE format('CREATE TRIGGER identity_baseline_write_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_identity.guard_baseline_business_write()', business_table);
    END LOOP;
END $$;
