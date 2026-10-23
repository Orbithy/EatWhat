SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Published province/city numeric IDs are the shared dictionary identity.
-- Application startup validates every reference row before inserting this receipt.
CREATE TABLE reference_dictionary_manifest (
    dictionary_key varchar(64) PRIMARY KEY CHECK (dictionary_key = 'province-city'),
    version varchar(64) NOT NULL,
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    installed_at timestamptz NOT NULL DEFAULT now()
);

-- The runtime role may install the first validated receipt, but may not replace it.
-- A new dictionary release requires controlled migration-owner DDL and data review.
CREATE FUNCTION reject_reference_dictionary_manifest_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Reference dictionary manifest is immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER reference_dictionary_manifest_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON reference_dictionary_manifest
    FOR EACH STATEMENT EXECUTE FUNCTION reject_reference_dictionary_manifest_mutation();

-- Legacy databases have no receipt and retain their existing seed/manual-row behavior.
-- Once published, ordinary runtime DML cannot silently invalidate the registered hash.
CREATE FUNCTION protect_published_reference_dictionary() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.reference_dictionary_manifest WHERE dictionary_key = 'province-city') THEN
        RAISE EXCEPTION 'Published reference dictionary is read-only' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    ELSIF TG_OP = 'TRUNCATE' THEN
        RETURN NULL;
    ELSE
        RETURN NEW;
    END IF;
END;
$$;

CREATE TRIGGER provinces_published_read_only
    BEFORE INSERT OR UPDATE OR DELETE ON provinces
    FOR EACH ROW EXECUTE FUNCTION protect_published_reference_dictionary();
CREATE TRIGGER cities_published_read_only
    BEFORE INSERT OR UPDATE OR DELETE ON cities
    FOR EACH ROW EXECUTE FUNCTION protect_published_reference_dictionary();
CREATE TRIGGER provinces_published_no_truncate
    BEFORE TRUNCATE ON provinces
    FOR EACH STATEMENT EXECUTE FUNCTION protect_published_reference_dictionary();
CREATE TRIGGER cities_published_no_truncate
    BEFORE TRUNCATE ON cities
    FOR EACH STATEMENT EXECUTE FUNCTION protect_published_reference_dictionary();

-- Runtime roles deliberately lack sequence UPDATE privileges. Expose only this fixed,
-- no-argument operation rather than allowing arbitrary setval/sequence rewinds.
-- Version pc-1 reserves province IDs 1..31 and city IDs 1..451.
CREATE FUNCTION public.advance_published_reference_sequences() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog
AS $$
DECLARE
    sequence_state record;
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(1161909335, 1);
    SELECT last_value, is_called INTO sequence_state FROM public.provinces_id_seq;
    IF sequence_state.last_value < 31 OR (sequence_state.last_value = 31 AND NOT sequence_state.is_called) THEN
        PERFORM pg_catalog.setval('public.provinces_id_seq'::regclass, 31, true);
    END IF;
    SELECT last_value, is_called INTO sequence_state FROM public.cities_id_seq;
    IF sequence_state.last_value < 451 OR (sequence_state.last_value = 451 AND NOT sequence_state.is_called) THEN
        PERFORM pg_catalog.setval('public.cities_id_seq'::regclass, 451, true);
    END IF;
    -- setval is nontransactional: a failed commit may leave a safe forward-only gap.
    -- Values already at/past the fixed floor are not changed; no nextval is consumed.
END;
$$;
REVOKE ALL ON FUNCTION public.advance_published_reference_sequences() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.advance_published_reference_sequences() TO PUBLIC;
