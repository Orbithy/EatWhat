-- Public policy configuration is replicated independently of personal consent.
CREATE TABLE eatwhat_sync.consent_policy (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
    version varchar(64),
    url varchar(2048),
    event_id uuid,
    CHECK ((revision=0 AND version IS NULL AND url IS NULL AND event_id IS NULL)
        OR (revision>0 AND version IS NOT NULL AND version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' AND url IS NOT NULL AND event_id IS NOT NULL))
);
INSERT INTO eatwhat_sync.consent_policy(singleton) VALUES (true);

CREATE TABLE eatwhat_sync.consent_policy_history (
    revision bigint PRIMARY KEY CHECK (revision BETWEEN 1 AND 9007199254740991),
    event_id uuid NOT NULL UNIQUE,
    version varchar(64) NOT NULL,
    url varchar(2048) NOT NULL,
    source_site varchar(64) NOT NULL,
    source_instance uuid NOT NULL,
    local_admin_id bigint,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON eatwhat_sync.consent_policy,eatwhat_sync.consent_policy_history FROM PUBLIC;
