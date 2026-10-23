SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE TABLE eatwhat_sync.profile_authorization (
 event_id uuid PRIMARY KEY,
 subject_gid uuid NOT NULL,
 actor_gid uuid NOT NULL,
 evidence jsonb NOT NULL,
 outcome text NOT NULL CHECK(outcome IN ('AUTHORIZED','HISTORICAL_VALID','CONCURRENT_REVOCATION_REVIEW','KNOWN_REVOKED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON eatwhat_sync.profile_authorization FROM PUBLIC;
ALTER TABLE eatwhat_sync.profile_authorization ADD COLUMN operation jsonb;
CREATE TABLE eatwhat_sync.profile_authorization_review (
 event_id uuid PRIMARY KEY,subject_gid uuid NOT NULL,actor_gid uuid NOT NULL,
 operation jsonb NOT NULL,reason text NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_sync.profile_authorization_review_decision (
 review_event_id uuid NOT NULL,decision_event_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(review_event_id,decision_event_id)
);
REVOKE ALL ON eatwhat_sync.profile_authorization_review,eatwhat_sync.profile_authorization_review_decision FROM PUBLIC;
