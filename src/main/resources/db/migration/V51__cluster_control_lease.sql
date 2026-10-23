SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- One logical authority site may run multiple controller processes. The database
-- serializes takeover with fenced membership and GC transactions.
CREATE TABLE eatwhat_cluster.control_lease (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    cluster_id uuid NOT NULL,
    holder_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    expires_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON eatwhat_cluster.control_lease FROM PUBLIC;
