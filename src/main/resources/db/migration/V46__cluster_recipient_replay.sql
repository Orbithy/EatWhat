SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE TABLE eatwhat_cluster.recipient_replay (
 site_id varchar(64) NOT NULL, instance_id uuid NOT NULL, generation bigint NOT NULL CHECK(generation>=0),
 PRIMARY KEY(site_id,instance_id)
);
REVOKE ALL ON eatwhat_cluster.recipient_replay FROM PUBLIC;
