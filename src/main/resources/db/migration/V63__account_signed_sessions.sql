SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE TABLE eatwhat_account_multiwriter.signing_key(
 key_id char(64) PRIMARY KEY,source_site varchar(64) NOT NULL,source_instance uuid NOT NULL,
 public_key bytea NOT NULL,private_key bytea,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE eatwhat_account_multiwriter.session_fact(
 event_id uuid PRIMARY KEY,subject_id uuid NOT NULL,token_hash char(64) NOT NULL,
 fact jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX account_session_fact_subject ON eatwhat_account_multiwriter.session_fact(subject_id,created_at);
CREATE TABLE eatwhat_account_multiwriter.session_revocation(token_hash char(64) PRIMARY KEY,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
REVOKE ALL ON eatwhat_account_multiwriter.signing_key,eatwhat_account_multiwriter.session_fact,eatwhat_account_multiwriter.session_revocation FROM PUBLIC;

ALTER TABLE eatwhat_account_multiwriter.signing_key ADD COLUMN retired boolean NOT NULL DEFAULT false;
ALTER TABLE eatwhat_account_multiwriter.signing_key ADD COLUMN revoked boolean NOT NULL DEFAULT false;
-- Owner-only maintenance: normal rotation retires signing use but preserves old verification.
-- Emergency rotation additionally revokes sessions signed by the local site's retained keys.
CREATE FUNCTION eatwhat_account_multiwriter.rotate_session_key(p_revoke boolean DEFAULT false)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,eatwhat_account_multiwriter AS $$
DECLARE changed integer;
BEGIN
 UPDATE eatwhat_account_multiwriter.signing_key SET retired=true,revoked=revoked OR p_revoke
 WHERE source_site=(SELECT site_id FROM eatwhat_identity.node_state WHERE singleton)
   AND source_instance=(SELECT instance_id FROM eatwhat_identity.node_state WHERE singleton) AND private_key IS NOT NULL;
 GET DIAGNOSTICS changed=ROW_COUNT; RETURN changed;
END $$;
CREATE FUNCTION eatwhat_account_multiwriter.revoke_received_session_key(p_key text,p_site text,p_instance uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,eatwhat_account_multiwriter AS $$
BEGIN
 UPDATE eatwhat_account_multiwriter.signing_key SET revoked=true WHERE key_id=p_key AND source_site=p_site AND source_instance=p_instance;
 RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.rotate_session_key(boolean),eatwhat_account_multiwriter.revoke_received_session_key(text,text,uuid) FROM PUBLIC;

CREATE TABLE eatwhat_account_multiwriter.transfer_page(
 source_site varchar(64) NOT NULL,source_instance uuid NOT NULL,transfer_id char(64) NOT NULL,
 page_index integer NOT NULL CHECK(page_index>=0),page_count integer NOT NULL CHECK(page_count BETWEEN 1 AND 512),
 subject_id uuid NOT NULL,payload bytea NOT NULL CHECK(octet_length(payload)<=131072),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(source_site,source_instance,transfer_id,page_index));
REVOKE ALL ON eatwhat_account_multiwriter.transfer_page FROM PUBLIC;

-- Deleting a withdrawn projection also erases its private account operation values.
-- The consent graph remains as the anti-resurrection tombstone; role/ban history supports auditing.
CREATE FUNCTION eatwhat_account_multiwriter.erase_withdrawn_projection(p_subject uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF (SELECT mode FROM eatwhat_identity.node_state WHERE singleton)<>'EXTERNAL'
   OR NOT EXISTS(SELECT 1 FROM eatwhat_consent.subject s WHERE s.subject_id=p_subject AND s.status='WITHDRAWN'
      AND NOT EXISTS(SELECT 1 FROM public.users u WHERE u.id=s.user_id)) THEN
   RAISE EXCEPTION 'Only a cleared withdrawn projection may be erased' USING ERRCODE='42501'; END IF;
 DELETE FROM eatwhat_account_multiwriter.delivery WHERE event_id IN(SELECT event_id FROM eatwhat_account_multiwriter.operation WHERE subject_id=p_subject AND field NOT IN ('consent','role','banned'));
 DELETE FROM eatwhat_account_multiwriter.operation WHERE subject_id=p_subject AND field NOT IN ('consent','role','banned');
 DELETE FROM eatwhat_account_multiwriter.alias_claim WHERE subject_id=p_subject;
 DELETE FROM eatwhat_account_multiwriter.conflict WHERE subject_id=p_subject;
 DELETE FROM eatwhat_account_multiwriter.authorization_review WHERE subject_id=p_subject;
 DELETE FROM eatwhat_account_multiwriter.review_decision WHERE subject_id=p_subject;
 DELETE FROM eatwhat_account_multiwriter.transfer_page WHERE subject_id=p_subject;
 DELETE FROM eatwhat_account_multiwriter.session_fact WHERE subject_id=p_subject;
 DELETE FROM eatwhat_account_multiwriter.session WHERE subject_id=p_subject;
 IF to_regprocedure('eatwhat_sync.erase_withdrawn_business_payload(uuid)') IS NOT NULL THEN
   EXECUTE 'SELECT eatwhat_sync.erase_withdrawn_business_payload($1)' USING p_subject;
 END IF;
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION eatwhat_account_multiwriter.erase_withdrawn_projection(uuid) FROM PUBLIC;
