SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Internal event foundation only. No business capture, peer, scheduler or transport is enabled.
CREATE SCHEMA eatwhat_sync;
REVOKE ALL ON SCHEMA eatwhat_sync FROM PUBLIC;

CREATE TABLE eatwhat_sync.outbox (
    event_id uuid PRIMARY KEY,
    local_order bigserial NOT NULL UNIQUE,
    origin_site varchar(64) NOT NULL,
    origin_instance_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    numeric_id bigint NOT NULL CHECK (numeric_id BETWEEN 1 AND 9007199254740991),
    envelope jsonb NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    envelope_hash char(64) NOT NULL CHECK (envelope_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE eatwhat_sync.delivery (
    event_id uuid NOT NULL REFERENCES eatwhat_sync.outbox(event_id),
    target_site varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','LEASED','ACKED','QUARANTINED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    acked_at timestamptz,
    receipt_status varchar(32),
    last_error varchar(512),
    PRIMARY KEY (event_id,target_site),
    CHECK ((status = 'LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'LEASED' AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((status = 'ACKED' AND acked_at IS NOT NULL AND receipt_status IS NOT NULL)
        OR (status <> 'ACKED' AND acked_at IS NULL AND receipt_status IS NULL))
);
CREATE INDEX delivery_ready ON eatwhat_sync.delivery(target_site,available_at,event_id) WHERE status='READY';
CREATE INDEX delivery_expired ON eatwhat_sync.delivery(target_site,lease_until,event_id) WHERE status='LEASED';

CREATE TABLE eatwhat_sync.inbox (
    event_id uuid PRIMARY KEY,
    envelope jsonb NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    envelope_hash char(64) NOT NULL CHECK (envelope_hash ~ '^[0-9a-f]{64}$'),
    origin_site varchar(64) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    numeric_id bigint NOT NULL CHECK (numeric_id BETWEEN 1 AND 9007199254740991),
    status varchar(32) NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED','PROCESSING','APPLIED','WAITING_DEPENDENCY','QUARANTINED','CONFLICT')),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    applied_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    last_error varchar(512),
    CHECK ((status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((status = 'APPLIED' AND applied_at IS NOT NULL) OR (status <> 'APPLIED' AND applied_at IS NULL))
);
CREATE INDEX inbox_ready ON eatwhat_sync.inbox(available_at,event_id) WHERE status='RECEIVED';
CREATE INDEX inbox_expired ON eatwhat_sync.inbox(lease_until,event_id) WHERE status='PROCESSING';

CREATE TABLE eatwhat_sync.entity_state (
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    numeric_id bigint NOT NULL CHECK (numeric_id BETWEEN 1 AND 9007199254740991),
    applied_vector jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(applied_vector) = 'object'),
    last_event_id uuid,
    conflicted boolean NOT NULL DEFAULT false,
    PRIMARY KEY (aggregate_type,aggregate_id),
    UNIQUE (aggregate_type,numeric_id)
);

CREATE TABLE eatwhat_sync.pending (
    event_id uuid PRIMARY KEY REFERENCES eatwhat_sync.inbox(event_id),
    dependencies jsonb NOT NULL CHECK (jsonb_typeof(dependencies) = 'array'),
    retry_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at timestamptz,
    reason varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX pending_retry ON eatwhat_sync.pending(retry_at,event_id);

CREATE TABLE eatwhat_sync.conflict (
    conflict_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL UNIQUE REFERENCES eatwhat_sync.inbox(event_id),
    current_vector jsonb NOT NULL CHECK (jsonb_typeof(current_vector) = 'object'),
    last_local_event_id uuid,
    current_envelope jsonb NOT NULL CHECK (jsonb_typeof(current_envelope) = 'object'),
    incoming_envelope jsonb NOT NULL CHECK (jsonb_typeof(incoming_envelope) = 'object'),
    reason varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE eatwhat_sync.command_dedup (
    site_id varchar(64) NOT NULL,
    principal_key varchar(128) NOT NULL,
    command_type varchar(128) NOT NULL,
    request_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED')),
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (site_id,principal_key,command_type,request_key),
    CHECK ((status = 'RUNNING' AND result_json IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND result_json IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE FUNCTION eatwhat_sync.reject_event_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    RAISE EXCEPTION 'Event history and deduplication records cannot be rewritten or removed' USING ERRCODE='55000';
END $$;

CREATE FUNCTION eatwhat_sync.guard_immutable_columns() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE column_name text;
BEGIN
    FOREACH column_name IN ARRAY TG_ARGV LOOP
        IF to_jsonb(NEW)->column_name IS DISTINCT FROM to_jsonb(OLD)->column_name THEN
            RAISE EXCEPTION 'Event identity and envelope columns are immutable' USING ERRCODE='55000';
        END IF;
    END LOOP;
    IF TG_TABLE_NAME='command_dedup' THEN
        IF OLD.status='COMPLETED' THEN
            RAISE EXCEPTION 'Completed command result is immutable' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER outbox_immutable BEFORE UPDATE ON eatwhat_sync.outbox
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
CREATE TRIGGER conflict_immutable BEFORE UPDATE ON eatwhat_sync.conflict
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
CREATE TRIGGER delivery_immutable_keys BEFORE UPDATE ON eatwhat_sync.delivery
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.guard_immutable_columns('event_id','target_site');
CREATE TRIGGER inbox_immutable_envelope BEFORE UPDATE ON eatwhat_sync.inbox
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.guard_immutable_columns(
    'event_id','envelope','envelope_hash','origin_site','aggregate_type','aggregate_id','numeric_id','received_at');
CREATE TRIGGER entity_state_immutable_identity BEFORE UPDATE ON eatwhat_sync.entity_state
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.guard_immutable_columns('aggregate_type','aggregate_id','numeric_id');
CREATE TRIGGER pending_immutable_identity BEFORE UPDATE ON eatwhat_sync.pending
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.guard_immutable_columns('event_id','created_at');
CREATE TRIGGER command_immutable_identity BEFORE UPDATE ON eatwhat_sync.command_dedup
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.guard_immutable_columns(
    'site_id','principal_key','command_type','request_key','request_hash','created_at');

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['outbox','delivery','inbox','entity_state','pending','conflict','command_dedup'] LOOP
        EXECUTE format('CREATE TRIGGER retained_event_history BEFORE DELETE OR TRUNCATE ON eatwhat_sync.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation()',table_name);
    END LOOP;
END $$;

REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_sync FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA eatwhat_sync FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA eatwhat_sync FROM PUBLIC;
