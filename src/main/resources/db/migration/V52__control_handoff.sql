SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE eatwhat_cluster.control_process (
    holder_id uuid PRIMARY KEY,
    cluster_id uuid NOT NULL,
    name varchar(64) NOT NULL,
    state_id uuid NOT NULL,
    lease_seconds integer NOT NULL CHECK (lease_seconds BETWEEN 5 AND 120),
    expires_at timestamptz NOT NULL,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_cluster.control_handoff (
    request_id uuid PRIMARY KEY,
    cluster_id uuid NOT NULL,
    source_holder uuid NOT NULL,
    target_holder uuid NOT NULL CHECK (target_holder <> source_holder),
    expected_generation bigint NOT NULL CHECK (expected_generation > 0),
    state varchar(16) NOT NULL CHECK (state IN ('WAITING','TRANSFERRING','COMPLETED','CANCELLED','FAILED')),
    reason varchar(64) NOT NULL DEFAULT 'WAITING_FOR_QUIESCENCE',
    actor varchar(128) NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz
);
CREATE INDEX control_process_live ON eatwhat_cluster.control_process(cluster_id,expires_at);
CREATE UNIQUE INDEX control_handoff_pending ON eatwhat_cluster.control_handoff(cluster_id)
    WHERE state IN ('WAITING','TRANSFERRING');
REVOKE ALL ON eatwhat_cluster.control_process,eatwhat_cluster.control_handoff FROM PUBLIC;
