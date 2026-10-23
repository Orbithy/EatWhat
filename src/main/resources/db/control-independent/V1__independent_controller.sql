-- Dedicated controller databases only. Applied with a separate Flyway history.
-- Forward-only; retaining authority and credential history is intentional.
CREATE SCHEMA IF NOT EXISTS eatwhat_controller;
REVOKE ALL ON SCHEMA eatwhat_controller FROM PUBLIC;
CREATE TABLE eatwhat_controller.installation (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    installation_id uuid NOT NULL UNIQUE,
    controller_id uuid NOT NULL UNIQUE,
    cluster_id uuid NOT NULL,
    source_site varchar(64) NOT NULL,
    source_instance uuid NOT NULL,
    source_database_fingerprint char(32) NOT NULL,
    local_database_fingerprint char(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_controller.account (
    id bigint PRIMARY KEY CHECK(id>0),
    username varchar(32) NOT NULL UNIQUE,
    password_hash text NOT NULL,
    credential_version bigint NOT NULL DEFAULT 1 CHECK(credential_version>0),
    disabled boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_controller.credential_change (
    request_id uuid PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES eatwhat_controller.account(id),
    credential_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_controller.handoff_outbox (
    request_id uuid PRIMARY KEY,
    actor_id bigint NOT NULL REFERENCES eatwhat_controller.account(id),
    credential_version bigint NOT NULL,
    source_holder uuid NOT NULL,
    target_holder uuid NOT NULL CHECK(target_holder<>source_holder),
    expected_generation bigint NOT NULL CHECK(expected_generation>0),
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','DISPATCHING','FORWARDED','REJECTED','CANCELLED')),
    reason varchar(64) NOT NULL DEFAULT 'WAITING_FOR_AUTHORITY_FORWARD',
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- Local sequencing is deliberately excluded from authority-state mirroring.
-- It prevents delayed same-generation snapshots from restoring old credentials.
CREATE SEQUENCE eatwhat_controller.snapshot_revision;
CREATE TABLE eatwhat_controller.snapshot_cursor (
    source_controller uuid PRIMARY KEY,
    generation bigint NOT NULL CHECK(generation>0),
    revision bigint NOT NULL CHECK(revision>0),
    installed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_controller FROM PUBLIC;
CREATE FUNCTION eatwhat_controller.physical_fingerprint() RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
    SELECT md5(system_identifier::text||':'||(SELECT oid::text FROM pg_database WHERE datname=current_database())) FROM pg_control_system()
$$;
REVOKE ALL ON FUNCTION eatwhat_controller.physical_fingerprint() FROM PUBLIC;
