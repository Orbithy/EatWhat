SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE TABLE eatwhat_cluster.session_security_version(user_id bigint PRIMARY KEY,version bigint NOT NULL CHECK(version>0));
ALTER TABLE eatwhat_cluster.login_session ADD COLUMN security_version bigint NOT NULL DEFAULT 0 CHECK(security_version>=0);
CREATE FUNCTION eatwhat_cluster.advance_session_security() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF (NEW.password_hash,NEW.banned,NEW.role) IS DISTINCT FROM (OLD.password_hash,OLD.banned,OLD.role)
    AND EXISTS(SELECT 1 FROM eatwhat_identity.allocation WHERE singleton AND slot=0) THEN
   INSERT INTO eatwhat_cluster.session_security_version(user_id,version) VALUES(NEW.id,1)
   ON CONFLICT(user_id) DO UPDATE SET version=eatwhat_cluster.session_security_version.version+1;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER cluster_security_version AFTER UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.advance_session_security();
REVOKE ALL ON eatwhat_cluster.session_security_version FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_cluster.advance_session_security() FROM PUBLIC;
