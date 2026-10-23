SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Additive foundation. Empty until the separately gated cluster service is explicitly initialized.
-- Forward-only: membership, allocation and GC decision identities are retained permanently.
CREATE SCHEMA eatwhat_cluster;
REVOKE ALL ON SCHEMA eatwhat_cluster FROM PUBLIC;
CREATE TABLE eatwhat_cluster.state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    cluster_id uuid NOT NULL UNIQUE,
    coordinator_site varchar(64) NOT NULL,
    coordinator_instance uuid NOT NULL,
    epoch bigint NOT NULL DEFAULT 0 CHECK(epoch>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_cluster.member (
    site_id varchar(64) PRIMARY KEY,
    instance_id uuid NOT NULL UNIQUE,
    profile_id varchar(64) NOT NULL UNIQUE,
    mode varchar(16) NOT NULL CHECK(mode IN ('CAMPUS','EXTERNAL')),
    allocation_slot integer NOT NULL UNIQUE CHECK(allocation_slot BETWEEN 0 AND 1023),
    state varchar(16) NOT NULL CHECK(state IN ('REGISTERED','JOINING','ACTIVE','DRAINING','RETIRED')),
    database_fingerprint char(32) UNIQUE,
    joined_epoch bigint,
    retired_epoch bigint,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK(database_fingerprint IS NULL OR database_fingerprint ~ '^[0-9a-f]{32}$'),
    CHECK(state NOT IN ('ACTIVE','DRAINING') OR joined_epoch IS NOT NULL)
);
CREATE TABLE eatwhat_cluster.change (
    change_id uuid PRIMARY KEY,
    request_hash char(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    base_epoch bigint NOT NULL CHECK(base_epoch>=0),
    next_epoch bigint NOT NULL CHECK(next_epoch=base_epoch+1),
    kind varchar(16) NOT NULL CHECK(kind IN ('JOIN','RETIRE')),
    site_id varchar(64) NOT NULL REFERENCES eatwhat_cluster.member(site_id),
    state varchar(16) NOT NULL CHECK(state IN ('PREPARING','COMMITTED','INSTALLED','APPLIED')),
    participants jsonb NOT NULL CHECK(jsonb_typeof(participants)='array'),
    actor varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX one_cluster_change ON eatwhat_cluster.change((true)) WHERE state<>'APPLIED';
CREATE TABLE eatwhat_cluster.change_vote (
    change_id uuid NOT NULL REFERENCES eatwhat_cluster.change(change_id),
    site_id varchar(64) NOT NULL,
    instance_id uuid NOT NULL,
    phase varchar(16) NOT NULL CHECK(phase IN ('PREPARED','APPLIED','RELEASED')),
    proof_hash char(64) NOT NULL CHECK(proof_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(change_id,site_id,phase)
);
CREATE TABLE eatwhat_cluster.audit (
    id bigserial PRIMARY KEY,
    request_id uuid NOT NULL,
    actor varchar(128) NOT NULL,
    action varchar(32) NOT NULL,
    site_id varchar(64),
    epoch bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_cluster.command (
    request_id uuid PRIMARY KEY,
    request_hash char(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    result_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_cluster.local_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    cluster_id uuid NOT NULL,
    epoch bigint NOT NULL CHECK(epoch>=0),
    site_id varchar(64) NOT NULL,
    instance_id uuid NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('JOINING','ACTIVE','DRAINING','RETIRED')),
    members jsonb NOT NULL CHECK(jsonb_typeof(members)='array'),
    pending_change uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_cluster.gc_operation (
    operation_id uuid PRIMARY KEY,
    file_id uuid NOT NULL,
    revision integer NOT NULL CHECK(revision>0),
    epoch bigint NOT NULL CHECK(epoch>=1),
    sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK(size_bytes>0),
    participants jsonb NOT NULL CHECK(jsonb_typeof(participants)='array'),
    state varchar(16) NOT NULL CHECK(state IN ('PREPARING','COMMITTED','DELETED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(file_id,revision)
);
CREATE TABLE eatwhat_cluster.gc_vote (
    operation_id uuid NOT NULL REFERENCES eatwhat_cluster.gc_operation(operation_id),
    site_id varchar(64) NOT NULL,
    instance_id uuid NOT NULL,
    phase varchar(16) NOT NULL CHECK(phase IN ('PREPARED','DELETED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(operation_id,site_id,phase)
);
CREATE TABLE eatwhat_cluster.gc_fence (
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    operation_id uuid NOT NULL UNIQUE,
    epoch bigint NOT NULL,
    sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL,
    participants jsonb NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('PREPARED','COMMITTED','DELETED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(file_id,revision),
    FOREIGN KEY(file_id,revision) REFERENCES eatwhat_file.file_revision(file_id,revision)
);
CREATE TABLE eatwhat_identity.allocation (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    cluster_id uuid NOT NULL,
    site_id varchar(64) NOT NULL,
    instance_id uuid NOT NULL,
    slot integer NOT NULL CHECK(slot BETWEEN 0 AND 1023),
    minimum_id bigint NOT NULL CHECK(minimum_id>1000000000),
    maximum_id bigint NOT NULL CHECK(maximum_id<=9007199254740991 AND maximum_id>=minimum_id),
    increment_by integer NOT NULL CHECK(increment_by IN (2,1024)),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION eatwhat_cluster.guard_retained() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'cluster history is retained' USING ERRCODE='23514'; END IF;
    IF TG_TABLE_NAME IN ('audit','allocation','change_vote','gc_vote','command') THEN
        RAISE EXCEPTION 'cluster evidence is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='member' THEN
        IF (NEW.site_id,NEW.instance_id,NEW.profile_id,NEW.mode,NEW.allocation_slot,NEW.created_at)
           IS DISTINCT FROM (OLD.site_id,OLD.instance_id,OLD.profile_id,OLD.mode,OLD.allocation_slot,OLD.created_at)
           OR (OLD.database_fingerprint IS NOT NULL AND NEW.database_fingerprint IS DISTINCT FROM OLD.database_fingerprint)
           OR (OLD.joined_epoch IS NOT NULL AND NEW.joined_epoch IS DISTINCT FROM OLD.joined_epoch)
           OR (OLD.retired_epoch IS NOT NULL AND NEW.retired_epoch IS DISTINCT FROM OLD.retired_epoch)
           OR (NEW.state<>OLD.state AND NOT ((OLD.state='REGISTERED' AND NEW.state='JOINING')
             OR (OLD.state='REGISTERED' AND NEW.state='RETIRED')
             OR (OLD.state='JOINING' AND NEW.state='ACTIVE') OR (OLD.state='ACTIVE' AND NEW.state='DRAINING')
             OR (OLD.state='DRAINING' AND NEW.state='RETIRED'))) THEN
            RAISE EXCEPTION 'invalid member transition' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME IN ('gc_operation','gc_fence') THEN
        IF (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state')
           OR (NEW.state<>OLD.state AND NOT ((OLD.state IN ('PREPARING','PREPARED') AND NEW.state='COMMITTED')
             OR (OLD.state='COMMITTED' AND NEW.state='DELETED'))) THEN
            RAISE EXCEPTION 'GC identity and fences are immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='change' THEN
        IF (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state')
           OR (NEW.state<>OLD.state AND NOT ((OLD.state='PREPARING' AND NEW.state='COMMITTED')
             OR (OLD.state='COMMITTED' AND NEW.state='INSTALLED') OR (OLD.state='INSTALLED' AND NEW.state='APPLIED'))) THEN
            RAISE EXCEPTION 'invalid membership decision' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='state' THEN
        IF (to_jsonb(NEW)-'epoch') IS DISTINCT FROM (to_jsonb(OLD)-'epoch') OR NEW.epoch<>OLD.epoch+1 THEN
            RAISE EXCEPTION 'invalid cluster epoch' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['state','member','change','change_vote','audit','command','local_state','gc_operation','gc_vote','gc_fence'] LOOP
        EXECUTE format('CREATE TRIGGER cluster_retained BEFORE UPDATE OR DELETE ON eatwhat_cluster.%I FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_retained()',t);
        EXECUTE format('CREATE TRIGGER cluster_no_truncate BEFORE TRUNCATE ON eatwhat_cluster.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation()',t);
    END LOOP;
END $$;
CREATE TRIGGER allocation_retained BEFORE UPDATE OR DELETE ON eatwhat_identity.allocation FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_retained();
CREATE TRIGGER allocation_no_truncate BEFORE TRUNCATE ON eatwhat_identity.allocation FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_cluster FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA eatwhat_cluster FROM PUBLIC;

-- All V35 reference acquisition paths now also honor multi-party fences, even with workers disabled.
CREATE OR REPLACE FUNCTION eatwhat_file.assert_keys_referenceable(keys text[]) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE t record; BEGIN
    FOR t IN SELECT DISTINCT a.file_id,a.revision FROM eatwhat_file.file_key_alias a
             WHERE a.logical_key=ANY(keys) ORDER BY a.file_id,a.revision LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended(t.file_id::text||':'||t.revision::text,0));
        IF EXISTS(SELECT 1 FROM eatwhat_file.file_gc_coordination c WHERE c.file_id=t.file_id
                    AND c.revision=t.revision AND c.state<>'REQUESTED')
            OR EXISTS(SELECT 1 FROM eatwhat_file.file_replica r WHERE r.file_id=t.file_id
                    AND r.revision=t.revision AND r.state IN ('DELETING','DELETED'))
            OR EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence c WHERE c.file_id=t.file_id AND c.revision=t.revision) THEN
            RAISE EXCEPTION 'file revision is frozen for physical deletion' USING ERRCODE='23514';
        END IF;
    END LOOP;
END $$;
