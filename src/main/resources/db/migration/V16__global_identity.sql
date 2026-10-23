SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Numeric API identities remain unchanged. Existing rows are NOT backfilled here.
CREATE SCHEMA eatwhat_identity;
REVOKE ALL ON SCHEMA eatwhat_identity FROM PUBLIC;

CREATE TABLE eatwhat_identity.node_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    site_id varchar(64) NOT NULL,
    mode varchar(16) NOT NULL CHECK (mode IN ('CAMPUS', 'EXTERNAL')),
    instance_id uuid NOT NULL,
    database_name text NOT NULL,
    database_oid bigint NOT NULL,
    cluster_identifier text NOT NULL,
    activated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE eatwhat_identity.entity_identity (
    entity_type varchar(32) NOT NULL CHECK (entity_type IN ('users', 'contacts', 'verifications', 'activity_foods', 'activity_dinners', 'activity_likes', 'notifications', 'follow', 'privacy', 'hub', 'restaurant', 'foods', 'browse_history', 'food_tags', 'food_taggings', 'food_favorites', 'likes')),
    numeric_id bigint NOT NULL,
    global_id uuid NOT NULL UNIQUE,
    origin_site varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_type, numeric_id)
);

-- Functions run as their migration owner; callers cannot rewrite the identity registry.
-- Trigger table names are verified and every metadata relation is schema-qualified.
CREATE FUNCTION eatwhat_identity.guard_business_identity()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, eatwhat_identity
AS $$
DECLARE
    node_mode text;
    parity integer;
    allocated_id bigint;
BEGIN
    IF TG_TABLE_SCHEMA <> 'public' OR (TG_TABLE_NAME NOT IN ('users', 'contacts', 'verifications', 'activity_foods', 'activity_dinners', 'activity_likes', 'notifications', 'follow', 'privacy', 'hub', 'restaurant', 'foods', 'browse_history', 'food_tags', 'food_taggings', 'food_favorites', 'likes')
            AND NOT (TG_TABLE_NAME = 'user_info' AND TG_OP = 'UPDATE')) THEN
        RAISE EXCEPTION 'Unsupported identity relation' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id THEN
            RAISE EXCEPTION 'Business numeric identity is immutable' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT mode INTO node_mode FROM eatwhat_identity.node_state WHERE singleton;
    IF FOUND THEN
        parity := CASE WHEN node_mode = 'CAMPUS' THEN 1 ELSE 0 END;
        IF NEW.id IS NULL OR NEW.id <= 1000000000 OR NEW.id > 9007199254740991
                OR mod(NEW.id, 2) <> parity THEN
            RAISE EXCEPTION 'Numeric identity is outside this site allocation domain' USING ERRCODE = '23514';
        END IF;
        BEGIN
            allocated_id := currval(pg_get_serial_sequence(format('%I.%I', TG_TABLE_SCHEMA, TG_TABLE_NAME), 'id')::regclass);
        EXCEPTION WHEN object_not_in_prerequisite_state THEN
            RAISE EXCEPTION 'Explicit numeric identity requires allocation in this connection' USING ERRCODE = '23514';
        END;
        IF NEW.id <> allocated_id THEN
            RAISE EXCEPTION 'Explicit numeric identity bypassed the local allocator' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION eatwhat_identity.record_business_identity()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, eatwhat_identity
AS $$
BEGIN
    IF TG_TABLE_SCHEMA <> 'public' OR TG_TABLE_NAME NOT IN ('users', 'contacts', 'verifications', 'activity_foods', 'activity_dinners', 'activity_likes', 'notifications', 'follow', 'privacy', 'hub', 'restaurant', 'foods', 'browse_history', 'food_tags', 'food_taggings', 'food_favorites', 'likes') THEN
        RAISE EXCEPTION 'Unsupported identity relation' USING ERRCODE = '23514';
    END IF;
    -- AFTER INSERT excludes INSERT ... ON CONFLICT DO NOTHING / DO UPDATE losers.
    -- Deliberately no ON CONFLICT clause: a deleted identity may never be reassigned.
    INSERT INTO eatwhat_identity.entity_identity(entity_type, numeric_id, global_id, origin_site)
    VALUES (TG_TABLE_NAME, NEW.id, gen_random_uuid(),
            (SELECT site_id FROM eatwhat_identity.node_state WHERE singleton));
    RETURN NEW;
END;
$$;

REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_identity FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA eatwhat_identity FROM PUBLIC;

-- user_info shares users.id and must never be reassigned to another account.
CREATE TRIGGER guard_global_identity BEFORE UPDATE OF id ON public.user_info
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.users
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.users
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.contacts
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.contacts
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.verifications
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.verifications
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.activity_foods
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.activity_foods
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.activity_dinners
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.activity_dinners
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.activity_likes
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.activity_likes
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.notifications
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.notifications
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.follow
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.follow
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.privacy
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.privacy
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.hub
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.hub
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.restaurant
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.restaurant
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.foods
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.foods
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.browse_history
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.browse_history
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.food_tags
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.food_tags
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.food_taggings
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.food_taggings
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.food_favorites
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.food_favorites
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.likes
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.likes
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();
