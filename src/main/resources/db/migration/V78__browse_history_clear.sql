SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE public.browse_history ADD COLUMN sync_clear_context jsonb NOT NULL DEFAULT '{}'
  CHECK (jsonb_typeof(sync_clear_context)='object');

-- Causal deletion frontier contains no targets or viewed times. Keep it across consent epochs:
-- forgetting it at re-grant would resurrect old observations still queued on another site.
CREATE TABLE eatwhat_sync.browse_clear (
  user_id bigint NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  target_type varchar(16) NOT NULL CHECK(target_type IN ('food','restaurant')),
  vector jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(vector)='object'),
  dirty boolean NOT NULL DEFAULT false,
  published_epoch bigint NOT NULL DEFAULT 0 CHECK(published_epoch>=0),
  PRIMARY KEY(user_id,target_type)
);

-- Preserve the installed V74.2/V76 cleanup and personal-event protocols.
DO $migration$
DECLARE definition text;
  before_text text := 'AND NOT(n.nspname=''eatwhat_control'' AND c.relname=''credential_version''';
  after_text text := 'AND NOT(n.nspname=''eatwhat_sync'' AND c.relname=''browse_clear'' AND a.attname=''user_id'' AND fk.confdeltype=''c'')
      AND NOT(n.nspname=''eatwhat_control'' AND c.relname=''credential_version''';
BEGIN
  definition := pg_get_functiondef('eatwhat_consent.purge_account(uuid,bigint)'::regprocedure);
  IF (length(definition)-length(replace(definition,before_text,'')))/length(before_text)<>1 THEN
    RAISE EXCEPTION 'P1 preflight: unsupported installed account cleanup definition';
  END IF;
  EXECUTE replace(definition,before_text,after_text);
END $migration$;

DO $migration$
DECLARE definition text;
  before_text text := 'ELSIF a IN (''relation.operation'',''browse.observation'') THEN';
  after_text text := 'ELSIF a=''browse.observation'' AND e->>''eventType''=''browse.cleared'' THEN
  RETURN QUERY SELECT (p->>''actor'')::uuid,(p->>''actorEpoch'')::bigint,false;
 ELSIF a IN (''relation.operation'',''browse.observation'') THEN';
BEGIN
  definition := pg_get_functiondef('eatwhat_sync.personal_event_subjects(jsonb)'::regprocedure);
  IF (length(definition)-length(replace(definition,before_text,'')))/length(before_text)<>1 THEN
    RAISE EXCEPTION 'P1 preflight: unsupported installed personal-event definition';
  END IF;
  EXECUTE replace(definition,before_text,after_text);
END $migration$;
