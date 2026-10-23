SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE FUNCTION eatwhat_cluster.guard_local_membership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.singleton,NEW.cluster_id,NEW.site_id,NEW.instance_id,NEW.created_at) IS DISTINCT FROM
       (OLD.singleton,OLD.cluster_id,OLD.site_id,OLD.instance_id,OLD.created_at)
       OR NEW.epoch<OLD.epoch OR NEW.epoch>OLD.epoch+1
       OR (OLD.state='RETIRED' AND NEW.state<>'RETIRED')
       OR (NEW.epoch=OLD.epoch AND NEW.members IS DISTINCT FROM OLD.members)
       OR (NEW.epoch>OLD.epoch AND (OLD.pending_change IS NULL OR NEW.pending_change IS DISTINCT FROM OLD.pending_change))
       OR (OLD.pending_change IS NOT NULL AND NEW.pending_change IS NOT NULL AND OLD.pending_change<>NEW.pending_change)
       OR (OLD.pending_change IS NOT NULL AND NEW.pending_change IS NULL AND NOT EXISTS(
           SELECT 1 FROM eatwhat_cluster.local_phase WHERE change_id=OLD.pending_change AND phase='APPLIED')) THEN
       RAISE EXCEPTION 'invalid local membership transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER local_membership_guard BEFORE UPDATE ON eatwhat_cluster.local_state FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_local_membership();
CREATE FUNCTION eatwhat_cluster.guard_manifest() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.file_id,NEW.revision) IS DISTINCT FROM (OLD.file_id,OLD.revision)
       OR NEW.epoch<OLD.epoch OR NEW.generation<OLD.generation
       OR (NEW.epoch=OLD.epoch AND NEW.generation=OLD.generation AND NEW.required_members IS DISTINCT FROM OLD.required_members) THEN
        RAISE EXCEPTION 'replica manifest cannot change or rewind at the same generation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER replica_manifest_guard BEFORE UPDATE ON eatwhat_cluster.replica_manifest FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_manifest();
CREATE FUNCTION eatwhat_cluster.guard_account_cursor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.account_last_user<OLD.account_last_user THEN RAISE EXCEPTION 'account replay cannot rewind' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER account_cursor_guard BEFORE UPDATE ON eatwhat_cluster.replay_cursor FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_account_cursor();
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_local_membership(),eatwhat_cluster.guard_manifest(),eatwhat_cluster.guard_account_cursor() FROM PUBLIC;
