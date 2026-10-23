SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
ALTER TABLE eatwhat_cluster.control_process ADD COLUMN deployment_protocol integer NOT NULL DEFAULT 0 CHECK(deployment_protocol BETWEEN 0 AND 1);

-- Retained authority decision; there is deliberately no expiry that could
-- permit a second release while an old remote command is still unresolved.
CREATE TABLE eatwhat_cluster.deployment_fence (
    job_id uuid PRIMARY KEY,
    cluster_id uuid NOT NULL,
    state_id uuid NOT NULL,
    holder_id uuid NOT NULL,
    generation bigint NOT NULL CHECK(generation>0),
    epoch bigint NOT NULL CHECK(epoch>0),
    manifest_hash varchar(64) NOT NULL CHECK(manifest_hash ~ '^[0-9a-f]{64}$'),
    callback_token_hash varchar(64) CHECK(callback_token_hash IS NULL OR callback_token_hash ~ '^[0-9a-f]{64}$'),
    targets jsonb NOT NULL CHECK(jsonb_typeof(targets)='array' AND jsonb_array_length(targets) BETWEEN 1 AND 16),
    state varchar(24) NOT NULL CHECK(state IN ('ACTIVE','SUCCEEDED','FAILED','PARTIAL_FAILURE')),
    dispatch_authorized boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    CHECK((state='ACTIVE')=(finished_at IS NULL))
);
CREATE UNIQUE INDEX deployment_fence_active ON eatwhat_cluster.deployment_fence(cluster_id) WHERE state='ACTIVE';
REVOKE ALL ON eatwhat_cluster.deployment_fence FROM PUBLIC;

-- Older controller binaries must not bypass the retained decision merely
-- because they do not yet know about this table.
CREATE FUNCTION eatwhat_cluster.guard_deployment_membership() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM 1 FROM eatwhat_cluster.state WHERE singleton FOR UPDATE;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE state='ACTIVE') THEN
        IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    IF TG_OP='UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    -- Coordinator startup inserts the same immutable binding with ON CONFLICT
    -- DO NOTHING. It must remain possible to start and reconcile an old job.
    IF TG_TABLE_NAME='state' AND TG_OP='INSERT' THEN
        IF EXISTS(SELECT 1 FROM eatwhat_cluster.state s WHERE s.singleton=NEW.singleton
            AND s.cluster_id=NEW.cluster_id AND s.coordinator_site=NEW.coordinator_site
            AND s.coordinator_instance=NEW.coordinator_instance) THEN RETURN NEW; END IF;
    END IF;
    RAISE EXCEPTION 'DEPLOYMENT_ACTIVE' USING ERRCODE='P0001';
END $$;
CREATE TRIGGER deployment_membership_state BEFORE INSERT OR UPDATE OR DELETE ON eatwhat_cluster.state
    FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_deployment_membership();
CREATE TRIGGER deployment_membership_member BEFORE INSERT OR UPDATE OR DELETE ON eatwhat_cluster.member
    FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_deployment_membership();
CREATE TRIGGER deployment_membership_change BEFORE INSERT OR UPDATE OR DELETE ON eatwhat_cluster.change
    FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_deployment_membership();
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_deployment_membership() FROM PUBLIC;

CREATE FUNCTION eatwhat_cluster.guard_control_state_takeover() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE old_state uuid; new_state uuid; new_protocol integer;
BEGIN
    IF NEW.cluster_id IS DISTINCT FROM OLD.cluster_id THEN
        RAISE EXCEPTION 'CLUSTER_BINDING_MISMATCH' USING ERRCODE='P0001';
    END IF;
    IF NEW.holder_id=OLD.holder_id THEN RETURN NEW; END IF;
    SELECT state_id INTO old_state FROM eatwhat_cluster.control_process
        WHERE holder_id=OLD.holder_id AND cluster_id=OLD.cluster_id;
    SELECT state_id,deployment_protocol INTO new_state,new_protocol FROM eatwhat_cluster.control_process
        WHERE holder_id=NEW.holder_id AND cluster_id=NEW.cluster_id;
    IF old_state IS NULL OR new_state IS NULL OR old_state<>new_state THEN
        RAISE EXCEPTION 'CONTROL_SHARED_STATE_REQUIRED' USING ERRCODE='P0001';
    END IF;
    IF EXISTS(SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE cluster_id=OLD.cluster_id AND state='ACTIVE') AND coalesce(new_protocol,0)<1 THEN
        RAISE EXCEPTION 'DEPLOYMENT_PROTOCOL_REQUIRED' USING ERRCODE='P0001';
    END IF;
    IF OLD.expires_at>clock_timestamp() AND EXISTS(
        SELECT 1 FROM eatwhat_cluster.deployment_fence WHERE cluster_id=OLD.cluster_id AND state='ACTIVE') THEN
        RAISE EXCEPTION 'DEPLOYMENT_ACTIVE' USING ERRCODE='P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER control_state_takeover BEFORE UPDATE ON eatwhat_cluster.control_lease
    FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_control_state_takeover();
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_control_state_takeover() FROM PUBLIC;
