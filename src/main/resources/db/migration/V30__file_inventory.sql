-- V30: Report-only object-store inventory runs and findings (no GC/delete state)
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '300s';

CREATE TABLE eatwhat_file.file_gc_run (
    run_id uuid PRIMARY KEY,
    site varchar(16) NOT NULL CHECK (site IN ('LOCAL','PEER')),
    mode varchar(16) NOT NULL CHECK (mode IN ('INVENTORY','DRY_RUN','EXECUTE')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    listed_count bigint NOT NULL DEFAULT 0,
    finding_count bigint NOT NULL DEFAULT 0,
    error_count bigint NOT NULL DEFAULT 0,
    config_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    error text
);

CREATE INDEX file_gc_run_started_idx ON eatwhat_file.file_gc_run(site, started_at DESC);

CREATE FUNCTION eatwhat_file.guard_gc_run() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'file gc runs are retained' USING ERRCODE='23514';
    END IF;
    IF (NEW.run_id, NEW.site, NEW.mode, NEW.started_at, NEW.config_snapshot)
       IS DISTINCT FROM (OLD.run_id, OLD.site, OLD.mode, OLD.started_at, OLD.config_snapshot) THEN
        RAISE EXCEPTION 'file gc run identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_gc_run() FROM PUBLIC;
CREATE TRIGGER gc_run_guard BEFORE UPDATE OR DELETE ON eatwhat_file.file_gc_run FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_gc_run();

CREATE TABLE eatwhat_file.file_inventory_finding (
    finding_id bigserial PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES eatwhat_file.file_gc_run(run_id),
    site varchar(16) NOT NULL CHECK (site IN ('LOCAL','PEER')),
    object_key text NOT NULL,
    kind varchar(24) NOT NULL CHECK (kind IN ('MISSING_OBJECT','SIZE_MISMATCH','UNKNOWN_VERIFIED','STALE_STAGING','ORPHAN_CANDIDATE','LEGACY_KEY','STALE_MULTIPART')),
    object_last_modified timestamptz,
    detail text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX file_inventory_finding_run_idx ON eatwhat_file.file_inventory_finding(run_id);
CREATE INDEX file_inventory_finding_kind_idx ON eatwhat_file.file_inventory_finding(kind, created_at);

CREATE FUNCTION eatwhat_file.guard_finding_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'file inventory findings are append-only' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_finding_append_only() FROM PUBLIC;
CREATE TRIGGER finding_append_only BEFORE UPDATE OR DELETE ON eatwhat_file.file_inventory_finding FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_finding_append_only();

REVOKE ALL ON eatwhat_file.file_gc_run FROM PUBLIC;
REVOKE ALL ON eatwhat_file.file_inventory_finding FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_file.guard_gc_run() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_file.guard_finding_append_only() FROM PUBLIC;
