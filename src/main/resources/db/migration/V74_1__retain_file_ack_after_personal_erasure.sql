SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- Receipts contain only immutable event identities/hashes, not personal envelopes.
-- Their source may move from outbox to the existing minimal erasure ledger.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM eatwhat_file.file_sync_ack a LEFT JOIN eatwhat_sync.outbox o USING(event_id)
   WHERE o.event_id IS NULL OR o.envelope_hash<>a.envelope_hash) THEN
  RAISE EXCEPTION 'Existing file acknowledgement source mismatch' USING ERRCODE='23514';
 END IF;
END $$;

CREATE FUNCTION eatwhat_file.guard_ack_source() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE source_hash char(64);
BEGIN
 SELECT envelope_hash INTO source_hash FROM eatwhat_sync.outbox WHERE event_id=NEW.event_id FOR KEY SHARE;
 IF NOT FOUND THEN
  SELECT envelope_hash INTO source_hash FROM eatwhat_sync.purged_personal_event WHERE event_id=NEW.event_id;
 END IF;
 IF source_hash IS NULL OR source_hash<>NEW.envelope_hash THEN
  RAISE EXCEPTION 'File acknowledgement requires matching retained source evidence' USING ERRCODE='23503';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_ack_source() FROM PUBLIC;
CREATE TRIGGER file_ack_source BEFORE INSERT ON eatwhat_file.file_sync_ack
 FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_ack_source();

CREATE FUNCTION eatwhat_file.guard_erased_ack_source() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM eatwhat_file.file_sync_ack a WHERE a.event_id=OLD.event_id
   AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.purged_personal_event p
     WHERE p.event_id=a.event_id AND p.envelope_hash=a.envelope_hash)) THEN
  RAISE EXCEPTION 'Acknowledged source erasure requires its minimal immutable receipt' USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_erased_ack_source() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER file_ack_erasure_evidence AFTER DELETE ON eatwhat_sync.outbox
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_erased_ack_source();

ALTER TABLE eatwhat_file.file_sync_ack DROP CONSTRAINT file_sync_ack_event_id_fkey;
