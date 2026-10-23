SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- An accepted installation may allocate IDs before its membership JOIN.
-- Keep the reservation even if its server registration is later removed.
CREATE TABLE eatwhat_cluster.installation_allocation (
    server_id uuid PRIMARY KEY,
    cluster_id uuid NOT NULL,
    site_id varchar(64) NOT NULL UNIQUE CHECK(site_id ~ '^[a-z][a-z0-9-]{0,62}$'),
    instance_id uuid NOT NULL UNIQUE,
    mode varchar(16) NOT NULL,
    allocation_slot integer NOT NULL UNIQUE CHECK(allocation_slot BETWEEN 0 AND 1023),
    first_job_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK((mode='CAMPUS' AND allocation_slot=0) OR (mode='EXTERNAL' AND allocation_slot BETWEEN 1 AND 1023))
);
REVOKE ALL ON eatwhat_cluster.installation_allocation FROM PUBLIC;

CREATE FUNCTION eatwhat_cluster.guard_installation_allocation() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM 1 FROM eatwhat_cluster.state WHERE singleton FOR UPDATE;
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'INSTALLATION_ALLOCATION_RETAINED' USING ERRCODE='P0001';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_cluster.state WHERE singleton AND cluster_id=NEW.cluster_id) THEN
        RAISE EXCEPTION 'CLUSTER_BINDING_MISMATCH' USING ERRCODE='P0001';
    END IF;
    IF EXISTS(SELECT 1 FROM eatwhat_cluster.member m WHERE
        (m.site_id=NEW.site_id OR m.instance_id=NEW.instance_id OR m.allocation_slot=NEW.allocation_slot)
        AND (m.site_id<>NEW.site_id OR m.instance_id<>NEW.instance_id OR m.mode<>NEW.mode OR m.allocation_slot<>NEW.allocation_slot)) THEN
        RAISE EXCEPTION 'MEMBER_IDENTITY_OR_SLOT_RETAINED' USING ERRCODE='P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER installation_allocation_retained BEFORE INSERT OR UPDATE OR DELETE ON eatwhat_cluster.installation_allocation
    FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_installation_allocation();

CREATE FUNCTION eatwhat_cluster.guard_member_installation_allocation() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM 1 FROM eatwhat_cluster.state WHERE singleton FOR UPDATE;
    IF EXISTS(SELECT 1 FROM eatwhat_cluster.installation_allocation a WHERE
        (a.site_id=NEW.site_id OR a.instance_id=NEW.instance_id OR a.allocation_slot=NEW.allocation_slot)
        AND (a.site_id<>NEW.site_id OR a.instance_id<>NEW.instance_id OR a.mode<>NEW.mode OR a.allocation_slot<>NEW.allocation_slot)) THEN
        RAISE EXCEPTION 'INSTALLATION_ALLOCATION_RETAINED' USING ERRCODE='P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER member_installation_allocation BEFORE INSERT OR UPDATE ON eatwhat_cluster.member
    FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_member_installation_allocation();
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_installation_allocation(),eatwhat_cluster.guard_member_installation_allocation() FROM PUBLIC;
