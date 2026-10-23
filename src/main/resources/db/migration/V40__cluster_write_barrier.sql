SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- SELECT FOR SHARE needs UPDATE privilege. Keep legacy roles read-only on membership metadata;
-- this narrow owner function takes the lock without granting them membership mutation rights.
CREATE FUNCTION eatwhat_cluster.writes_allowed() RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE local_node record; BEGIN
    SELECT state,pending_change INTO local_node FROM eatwhat_cluster.local_state WHERE singleton FOR SHARE;
    IF NOT FOUND THEN RETURN NOT EXISTS(SELECT 1 FROM eatwhat_identity.allocation); END IF;
    RETURN local_node.state='ACTIVE' AND local_node.pending_change IS NULL;
END $$;
REVOKE ALL ON FUNCTION eatwhat_cluster.writes_allowed() FROM PUBLIC;
