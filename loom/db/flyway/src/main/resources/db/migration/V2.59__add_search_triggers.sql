-- Triggers that keep search_document in step with the source tables, plus the initial backfill.
--
-- Every trigger does the same minimal thing: work out which entity was affected and call that
-- entity's refresh function from V2.58. No trigger builds a document itself, which is why the
-- incremental path and search_document_rebuild() cannot drift apart.
--
-- Asset deletion needs no trigger at all: search_document.asset_uuid cascades, which removes the
-- asset document and every derived transcript/annotation document in one step.

-- ---------------------------------------------------------------------------------------------
-- Tombstones for an external index
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION "search_tg_tombstone"()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO "search_document_deleted" ("entity_type", "entity_uuid")
    VALUES (OLD."entity_type", OLD."entity_uuid")
    ON CONFLICT ("entity_type", "entity_uuid") DO UPDATE SET "deleted_at" = now();
    RETURN OLD;
END; $$;

CREATE TRIGGER "tg_search_document_tombstone"
    BEFORE DELETE ON "search_document"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_tombstone"();

-- A freshly (re)created document is no longer deleted.
CREATE OR REPLACE FUNCTION "search_tg_untombstone"()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM "search_document_deleted"
     WHERE "entity_type" = NEW."entity_type" AND "entity_uuid" = NEW."entity_uuid";
    RETURN NEW;
END; $$;

CREATE TRIGGER "tg_search_document_untombstone"
    AFTER INSERT ON "search_document"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_untombstone"();

-- ---------------------------------------------------------------------------------------------
-- Asset-scoped sources. All of these carry an asset_uuid, so one generic trigger function serves
-- them: refresh the affected asset's whole document family.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION "search_tg_refresh_by_asset_uuid"()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_old uuid;
    v_new uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        v_old := (to_jsonb(OLD) ->> 'asset_uuid')::uuid;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        v_new := (to_jsonb(NEW) ->> 'asset_uuid')::uuid;
    END IF;

    -- On DELETE the parent asset may itself be gone (cascade), in which case the refresh function
    -- sees no asset row and removes the documents - which is what we want anyway.
    IF v_old IS NOT NULL THEN
        PERFORM "search_document_refresh_asset"(v_old);
    END IF;
    IF v_new IS NOT NULL AND v_new IS DISTINCT FROM v_old THEN
        PERFORM "search_document_refresh_asset"(v_new);
    END IF;

    RETURN NULL;
END; $$;

-- The asset table itself keys on "uuid" rather than "asset_uuid", so it needs its own function.
-- DELETE is handled by the search_document.asset_uuid cascade, not here.
CREATE OR REPLACE FUNCTION "search_tg_refresh_asset"()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM "search_document_refresh_asset"(NEW."uuid");
    RETURN NULL;
END; $$;

CREATE TRIGGER "tg_search_asset"
    AFTER INSERT OR UPDATE ON "asset"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_asset"();

CREATE TRIGGER "tg_search_asset_location"
    AFTER INSERT OR UPDATE OR DELETE ON "asset_location"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_by_asset_uuid"();

CREATE TRIGGER "tg_search_asset_json_comp"
    AFTER INSERT OR UPDATE OR DELETE ON "asset_json_comp"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_by_asset_uuid"();

CREATE TRIGGER "tg_search_asset_transcript_comp"
    AFTER INSERT OR UPDATE OR DELETE ON "asset_transcript_comp"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_by_asset_uuid"();

CREATE TRIGGER "tg_search_asset_segment_comp"
    AFTER INSERT OR UPDATE OR DELETE ON "asset_segment_comp"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_by_asset_uuid"();

CREATE TRIGGER "tg_search_detection"
    AFTER INSERT OR UPDATE OR DELETE ON "detection"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_by_asset_uuid"();

CREATE TRIGGER "tg_search_tag_asset"
    AFTER INSERT OR UPDATE OR DELETE ON "tag_asset"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_by_asset_uuid"();

CREATE TRIGGER "tg_search_library_asset"
    AFTER INSERT OR UPDATE OR DELETE ON "library_asset"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_by_asset_uuid"();

CREATE TRIGGER "tg_search_collection_asset"
    AFTER INSERT OR UPDATE OR DELETE ON "collection_asset"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_by_asset_uuid"();

-- ---------------------------------------------------------------------------------------------
-- Standalone entities
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION "search_tg_refresh_entity"()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_uuid uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_uuid := OLD."uuid";
    ELSE
        v_uuid := NEW."uuid";
    END IF;

    CASE TG_ARGV[0]
        WHEN 'tag'        THEN PERFORM "search_document_refresh_tag"(v_uuid);
        WHEN 'person'     THEN PERFORM "search_document_refresh_person"(v_uuid);
        WHEN 'collection' THEN PERFORM "search_document_refresh_collection"(v_uuid);
        WHEN 'library'    THEN PERFORM "search_document_refresh_library"(v_uuid);
        WHEN 'cluster'    THEN PERFORM "search_document_refresh_cluster"(v_uuid);
        WHEN 'annotation' THEN PERFORM "search_document_refresh_annotation"(v_uuid);
    END CASE;

    RETURN NULL;
END; $$;

CREATE TRIGGER "tg_search_tag"        AFTER INSERT OR UPDATE OR DELETE ON "tag"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_entity"('tag');
CREATE TRIGGER "tg_search_person"     AFTER INSERT OR UPDATE OR DELETE ON "person"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_entity"('person');
CREATE TRIGGER "tg_search_collection" AFTER INSERT OR UPDATE OR DELETE ON "collection"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_entity"('collection');
CREATE TRIGGER "tg_search_library"    AFTER INSERT OR UPDATE OR DELETE ON "library"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_entity"('library');
CREATE TRIGGER "tg_search_cluster"    AFTER INSERT OR UPDATE OR DELETE ON "cluster"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_entity"('cluster');
CREATE TRIGGER "tg_search_annotation" AFTER INSERT OR UPDATE OR DELETE ON "annotation"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_entity"('annotation');

-- A tag rename changes the keywords and tag_names of every asset carrying it. Bounded fan-out,
-- unlike project_library which would need a batched job.
CREATE OR REPLACE FUNCTION "search_tg_tag_fanout"()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_asset uuid;
BEGIN
    IF NEW."name" IS DISTINCT FROM OLD."name" OR NEW."collection" IS DISTINCT FROM OLD."collection" THEN
        FOR v_asset IN SELECT "asset_uuid" FROM "tag_asset" WHERE "tag_uuid" = NEW."uuid" LOOP
            PERFORM "search_document_refresh_asset"(v_asset);
        END LOOP;
    END IF;
    RETURN NULL;
END; $$;

CREATE TRIGGER "tg_search_tag_fanout"
    AFTER UPDATE ON "tag"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_tag_fanout"();

-- ---------------------------------------------------------------------------------------------
-- Backfill
-- ---------------------------------------------------------------------------------------------

SELECT "search_document_rebuild"();
