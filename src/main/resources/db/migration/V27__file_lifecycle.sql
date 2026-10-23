CREATE SCHEMA IF NOT EXISTS eatwhat_file;
REVOKE ALL ON SCHEMA eatwhat_file FROM PUBLIC;

CREATE TABLE eatwhat_file.upload_intent (
    intent_id uuid PRIMARY KEY,
    owner_id bigint NOT NULL REFERENCES public.users(id),
    owner_gid uuid NOT NULL,
    biz varchar(24) NOT NULL CHECK (biz IN ('avatar','activity','foods','restaurants')),
    logical_key text NOT NULL UNIQUE,
    staging_key text NOT NULL UNIQUE,
    declared_size bigint NOT NULL CHECK (declared_size > 0),
    declared_type varchar(255) NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CLAIMED','COMPLETE','REJECTED')),
    fencing_token bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX upload_intent_recovery ON eatwhat_file.upload_intent(status, lease_until, expires_at);

CREATE TABLE eatwhat_file.file_object (
    file_id uuid PRIMARY KEY,
    owner_gid uuid NOT NULL,
    biz varchar(24) NOT NULL,
    logical_key text NOT NULL UNIQUE,
    canonical_revision integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE eatwhat_file.file_revision (
    file_id uuid NOT NULL REFERENCES eatwhat_file.file_object(file_id),
    revision integer NOT NULL,
    final_key text NOT NULL UNIQUE,
    sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    content_type varchar(255) NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('CANDIDATE','VERIFIED','REJECTED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    verified_at timestamptz,
    PRIMARY KEY(file_id, revision)
);

CREATE TABLE eatwhat_file.file_replica (
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    site varchar(16) NOT NULL,
    object_key text NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('AVAILABLE_LOCAL','PENDING_SYNC','R2_AVAILABLE','FAILED')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(file_id, revision, site),
    FOREIGN KEY(file_id, revision) REFERENCES eatwhat_file.file_revision(file_id, revision)
);

CREATE TABLE eatwhat_file.file_key_alias (
    logical_key text PRIMARY KEY,
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    owner_gid uuid NOT NULL,
    biz varchar(24) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(file_id, revision) REFERENCES eatwhat_file.file_revision(file_id, revision)
);

CREATE TABLE eatwhat_file.file_reference (
    logical_key text NOT NULL REFERENCES eatwhat_file.file_key_alias(logical_key),
    ref_kind varchar(32) NOT NULL,
    ref_id text NOT NULL,
    visibility varchar(16) NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','PUBLIC')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(logical_key, ref_kind, ref_id)
);

CREATE TABLE eatwhat_file.file_orphan_candidate (
    candidate_id uuid PRIMARY KEY,
    intent_id uuid NOT NULL REFERENCES eatwhat_file.upload_intent(intent_id),
    fencing_token bigint NOT NULL,
    object_key text NOT NULL UNIQUE,
    state varchar(24) NOT NULL CHECK (state IN ('WRITING','WRITTEN','CANONICAL','ORPHANED','CLEANED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE eatwhat_file.file_transfer_job (
    job_id uuid PRIMARY KEY,
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    target_site varchar(16) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'READY',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(file_id, revision, target_site),
    FOREIGN KEY(file_id, revision) REFERENCES eatwhat_file.file_revision(file_id, revision)
);

CREATE FUNCTION eatwhat_file.guard_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'file lifecycle facts are retained' USING ERRCODE='23514'; END IF;
    IF TG_TABLE_NAME='file_revision' THEN
        IF (NEW.file_id,NEW.revision,NEW.final_key,NEW.sha256,NEW.size_bytes,NEW.content_type,NEW.state,NEW.verified_at,NEW.created_at)
           IS DISTINCT FROM (OLD.file_id,OLD.revision,OLD.final_key,OLD.sha256,OLD.size_bytes,OLD.content_type,OLD.state,OLD.verified_at,OLD.created_at) THEN
            RAISE EXCEPTION 'file revisions are immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='file_key_alias' THEN
        IF NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'file aliases are immutable' USING ERRCODE='23514'; END IF;
    ELSIF TG_TABLE_NAME='file_object' THEN
        IF (NEW.file_id,NEW.owner_gid,NEW.biz,NEW.logical_key,NEW.canonical_revision,NEW.created_at)
           IS DISTINCT FROM (OLD.file_id,OLD.owner_gid,OLD.biz,OLD.logical_key,OLD.canonical_revision,OLD.created_at) THEN
            RAISE EXCEPTION 'file objects are immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='file_reference' THEN
        RAISE EXCEPTION 'file references are append-only' USING ERRCODE='23514';
    ELSIF TG_TABLE_NAME='upload_intent' THEN
        IF (NEW.intent_id,NEW.owner_id,NEW.owner_gid,NEW.biz,NEW.logical_key,NEW.staging_key,NEW.declared_size,NEW.declared_type,NEW.expires_at,NEW.created_at)
           IS DISTINCT FROM (OLD.intent_id,OLD.owner_id,OLD.owner_gid,OLD.biz,OLD.logical_key,OLD.staging_key,OLD.declared_size,OLD.declared_type,OLD.expires_at,OLD.created_at) THEN
            RAISE EXCEPTION 'upload intent identity is immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='file_orphan_candidate' THEN
        IF (NEW.candidate_id,NEW.intent_id,NEW.fencing_token,NEW.object_key,NEW.created_at)
           IS DISTINCT FROM (OLD.candidate_id,OLD.intent_id,OLD.fencing_token,OLD.object_key,OLD.created_at) THEN
            RAISE EXCEPTION 'orphan candidate identity is immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='file_replica' THEN
        IF (NEW.file_id,NEW.revision,NEW.site,NEW.object_key) IS DISTINCT FROM (OLD.file_id,OLD.revision,OLD.site,OLD.object_key) THEN
            RAISE EXCEPTION 'file replica identity is immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='file_transfer_job' THEN
        IF (NEW.job_id,NEW.file_id,NEW.revision,NEW.target_site,NEW.created_at) IS DISTINCT FROM (OLD.job_id,OLD.file_id,OLD.revision,OLD.target_site,OLD.created_at) THEN
            RAISE EXCEPTION 'file transfer job identity is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_immutable() FROM PUBLIC;
CREATE TRIGGER revision_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_revision FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_immutable();
CREATE TRIGGER alias_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_key_alias FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_immutable();
CREATE TRIGGER file_object_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_object FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_immutable();
CREATE TRIGGER reference_append_only BEFORE UPDATE OR DELETE ON eatwhat_file.file_reference FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_immutable();
CREATE TRIGGER intent_identity_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.upload_intent FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_immutable();
CREATE TRIGGER orphan_identity_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_orphan_candidate FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_immutable();
CREATE TRIGGER replica_identity_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_replica FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_immutable();
CREATE TRIGGER transfer_identity_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_transfer_job FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_immutable();
