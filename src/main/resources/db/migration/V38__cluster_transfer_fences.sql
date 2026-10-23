SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Existing admission paths must honor cluster fences even when runtime switches are disabled.
CREATE OR REPLACE FUNCTION eatwhat_file.guard_gc_transfer() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
  IF TG_TABLE_NAME='file_transfer_job' THEN
    IF NEW.status NOT IN ('READY','LEASED') THEN RETURN NEW; END IF;
  ELSE
    IF NEW.state IN ('DELETING','DELETED') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN IF NEW.state=OLD.state THEN RETURN NEW; END IF; END IF;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.file_id::text||':'||NEW.revision::text,0));
  IF EXISTS(SELECT 1 FROM eatwhat_file.file_gc_coordination c WHERE c.file_id=NEW.file_id
            AND c.revision=NEW.revision AND c.state<>'REQUESTED')
    OR EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence c WHERE c.file_id=NEW.file_id AND c.revision=NEW.revision) THEN
    RAISE EXCEPTION 'file transfer is fenced by coordinated deletion' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_file.file_replica r WHERE r.file_id=NEW.file_id
            AND r.revision=NEW.revision AND r.state IN ('DELETING','DELETED')) THEN
    -- An old job can be claimed solely to quarantine it without object I/O. No new admission/retry
    -- and no new replica are allowed after even a LOCAL-only deletion fence.
    IF TG_TABLE_NAME='file_transfer_job' THEN
      IF TG_OP='UPDATE' THEN
        IF NEW.status='LEASED' AND OLD.status IN ('READY','LEASED') THEN RETURN NEW; END IF;
      END IF;
    END IF;
    RAISE EXCEPTION 'file transfer is fenced by physical deletion' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION eatwhat_file.guard_gc_alias() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.file_id::text||':'||NEW.revision::text,0));
  IF EXISTS(SELECT 1 FROM eatwhat_file.file_gc_coordination c WHERE c.file_id=NEW.file_id AND c.revision=NEW.revision AND c.state<>'REQUESTED')
    OR EXISTS(SELECT 1 FROM eatwhat_file.file_replica r WHERE r.file_id=NEW.file_id AND r.revision=NEW.revision AND r.state IN ('DELETING','DELETED'))
    OR EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence c WHERE c.file_id=NEW.file_id AND c.revision=NEW.revision) THEN
    RAISE EXCEPTION 'cannot alias a revision frozen for deletion' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
