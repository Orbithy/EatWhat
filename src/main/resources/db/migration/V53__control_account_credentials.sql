SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE SCHEMA eatwhat_control;
CREATE TABLE eatwhat_control.credential_version (
    user_id bigint PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    version bigint NOT NULL CHECK(version>0)
);
CREATE TABLE eatwhat_control.credential_change (
    request_id uuid PRIMARY KEY,
    user_id bigint NOT NULL,
    username_changed boolean NOT NULL,
    password_changed boolean NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION eatwhat_control.advance_credential_version() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF (OLD.role='admin' OR NEW.role='admin' OR EXISTS(SELECT 1 FROM eatwhat_control.credential_version WHERE user_id=NEW.id))
    AND (NEW.nick_name,NEW.password_hash,NEW.role,NEW.banned) IS DISTINCT FROM (OLD.nick_name,OLD.password_hash,OLD.role,OLD.banned) THEN
   INSERT INTO eatwhat_control.credential_version(user_id,version) VALUES(NEW.id,1)
   ON CONFLICT(user_id) DO UPDATE SET version=eatwhat_control.credential_version.version+1;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER control_credential_version AFTER UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION eatwhat_control.advance_credential_version();
REVOKE ALL ON SCHEMA eatwhat_control FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_control FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_control.advance_credential_version() FROM PUBLIC;
