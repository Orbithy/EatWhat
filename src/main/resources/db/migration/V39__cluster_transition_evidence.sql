SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE eatwhat_cluster.local_change (
    change_id uuid PRIMARY KEY,
    plan jsonb NOT NULL CHECK(jsonb_typeof(plan)='object'),
    plan_hash char(64) NOT NULL CHECK(plan_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_cluster.local_phase (
    change_id uuid NOT NULL REFERENCES eatwhat_cluster.local_change(change_id),
    phase varchar(16) NOT NULL CHECK(phase IN ('STAGED','FENCED','PREPARED','APPLIED','RELEASED')),
    proof_hash char(64) NOT NULL CHECK(proof_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(change_id,phase)
);
CREATE TABLE eatwhat_cluster.replay_cursor (
    change_id uuid NOT NULL REFERENCES eatwhat_cluster.local_change(change_id),
    source_site varchar(64) NOT NULL,
    source_instance uuid NOT NULL,
    cutoff bigint NOT NULL CHECK(cutoff>=0),
    last_order bigint NOT NULL DEFAULT 0 CHECK(last_order>=0 AND last_order<=cutoff),
    account_complete boolean NOT NULL DEFAULT false,
    complete boolean NOT NULL DEFAULT false,
    PRIMARY KEY(change_id,source_site)
);
CREATE FUNCTION eatwhat_cluster.guard_transition_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') OR TG_TABLE_NAME IN ('local_change','local_phase') THEN
        RAISE EXCEPTION 'cluster transition evidence is retained' USING ERRCODE='23514';
    END IF;
    IF (NEW.change_id,NEW.source_site,NEW.source_instance,NEW.cutoff) IS DISTINCT FROM
       (OLD.change_id,OLD.source_site,OLD.source_instance,OLD.cutoff)
       OR NEW.last_order<OLD.last_order OR (OLD.account_complete AND NOT NEW.account_complete)
       OR (OLD.complete AND NOT NEW.complete) THEN
        RAISE EXCEPTION 'cluster replay progress cannot be rewound' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['local_change','local_phase','replay_cursor'] LOOP
        EXECUTE format('CREATE TRIGGER cluster_transition_guard BEFORE UPDATE OR DELETE ON eatwhat_cluster.%I FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_transition_evidence()',t);
        EXECUTE format('CREATE TRIGGER cluster_transition_no_truncate BEFORE TRUNCATE ON eatwhat_cluster.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation()',t);
    END LOOP;
END $$;
REVOKE ALL ON eatwhat_cluster.local_change,eatwhat_cluster.local_phase,eatwhat_cluster.replay_cursor FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_transition_evidence() FROM PUBLIC;
