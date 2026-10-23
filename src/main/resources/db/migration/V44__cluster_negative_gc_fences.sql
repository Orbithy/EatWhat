SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
ALTER TABLE eatwhat_cluster.gc_fence DROP CONSTRAINT gc_fence_file_id_revision_fkey;
ALTER TABLE eatwhat_cluster.gc_fence ADD COLUMN absent boolean NOT NULL DEFAULT false;
CREATE FUNCTION eatwhat_cluster.guard_late_revision() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.file_id::text||':'||NEW.revision::text,0));
    IF EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence WHERE file_id=NEW.file_id AND revision=NEW.revision) THEN
        RAISE EXCEPTION 'revision has a retained cluster deletion fence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cluster_late_revision BEFORE INSERT ON eatwhat_file.file_revision FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_late_revision();
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_late_revision() FROM PUBLIC;
