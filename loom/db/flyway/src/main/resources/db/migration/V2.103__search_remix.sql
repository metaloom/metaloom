-- Index remixes in search_document, so a remix can be found by name and by what is in it
-- (spec/features/remix/REMIX.md, spec/features/search/SEARCH.md).
--
-- V2.100 shipped remixes without search support: they were reachable only through their own list
-- route. That is enough to browse them and wrong for finding one - a catalogue with a few hundred
-- remixes needs the same search box everything else uses.
--
-- WHAT GOES INTO THE DOCUMENT
--   title    = remix.name
--   subtitle = remix.description
--   keywords = the filenames of its members
--
-- The member filenames are the reason this is worth doing rather than just indexing the name. The
-- question people actually ask is "where is the group with the drone footage in it", and they ask it
-- with a filename, not with a group name they may not remember. Weight D keeps a filename match
-- below a name match, so searching "coastal" still ranks a remix called "Coastal drone" above one
-- that merely contains coastal-drone.mp4.
--
-- STALENESS
--   Three things change a remix's document: the remix row, its membership, and the filename of any
--   member. The first two get their own triggers. The third is a fan-out - bounded, because an asset
--   sits in few remixes - and follows the precedent of search_tg_tag_fanout in V2.59, which handles
--   the identical problem for a renamed tag. Without it a rename would silently leave every remix
--   holding that asset findable under a filename that no longer exists.

-- ---------------------------------------------------------------------------------------------
-- Refresh
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION "search_document_refresh_remix"(p_uuid uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    r          "remix"%ROWTYPE;
    v_keywords text;
BEGIN
    SELECT * INTO r FROM "remix" WHERE "uuid" = p_uuid;
    IF NOT FOUND THEN
        DELETE FROM "search_document" WHERE "entity_type" = 'remix' AND "entity_uuid" = p_uuid;
        RETURN;
    END IF;

    -- Bounded by the size of the remix, which is a human-curated handful. No LIMIT for the same
    -- reason search_document_refresh_asset does not bound its tag list.
    SELECT coalesce(string_agg(a."filename", ' '), '')
      INTO v_keywords
      FROM "remix_member" m
      JOIN "asset" a ON a."uuid" = m."asset_uuid"
     WHERE m."remix_uuid" = p_uuid;

    PERFORM "search_document_refresh_simple"('remix', p_uuid, r."name", r."description", v_keywords, r."created");
END; $$;

COMMENT ON FUNCTION "search_document_refresh_remix"(uuid) IS 'Rebuild the search document for one remix: name as title, description as subtitle, member filenames as keywords.';

-- ---------------------------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------------------------

-- Replaced wholesale to add the 'remix' branch. The other branches are unchanged from V2.59.
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
        WHEN 'remix'      THEN PERFORM "search_document_refresh_remix"(v_uuid);
    END CASE;

    RETURN NULL;
END; $$;

CREATE TRIGGER "tg_search_remix" AFTER INSERT OR UPDATE OR DELETE ON "remix"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_entity"('remix');

-- Membership changes the keywords. Keyed on remix_uuid rather than asset_uuid: the document being
-- rebuilt belongs to the remix, so search_tg_refresh_by_asset_uuid is the wrong helper here.
CREATE OR REPLACE FUNCTION "search_tg_refresh_remix_member"()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_old uuid;
    v_new uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        v_old := OLD."remix_uuid";
    END IF;
    IF TG_OP <> 'DELETE' THEN
        v_new := NEW."remix_uuid";
    END IF;

    -- On DELETE the parent remix may itself be gone (cascade), in which case the refresh function
    -- finds no row and deletes the document - which is what we want anyway.
    IF v_old IS NOT NULL THEN
        PERFORM "search_document_refresh_remix"(v_old);
    END IF;
    IF v_new IS NOT NULL AND v_new IS DISTINCT FROM v_old THEN
        PERFORM "search_document_refresh_remix"(v_new);
    END IF;

    RETURN NULL;
END; $$;

CREATE TRIGGER "tg_search_remix_member"
    AFTER INSERT OR UPDATE OR DELETE ON "remix_member"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_refresh_remix_member"();

-- A renamed asset changes the keywords of every remix holding it. Same shape and same reasoning as
-- search_tg_tag_fanout (V2.59): bounded fan-out, because an asset belongs to few remixes.
CREATE OR REPLACE FUNCTION "search_tg_remix_asset_fanout"()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_remix uuid;
BEGIN
    IF NEW."filename" IS DISTINCT FROM OLD."filename" THEN
        FOR v_remix IN SELECT "remix_uuid" FROM "remix_member" WHERE "asset_uuid" = NEW."uuid" LOOP
            PERFORM "search_document_refresh_remix"(v_remix);
        END LOOP;
    END IF;
    RETURN NULL;
END; $$;

CREATE TRIGGER "tg_search_remix_asset_fanout"
    AFTER UPDATE ON "asset"
    FOR EACH ROW EXECUTE FUNCTION "search_tg_remix_asset_fanout"();

-- ---------------------------------------------------------------------------------------------
-- Rebuild
-- ---------------------------------------------------------------------------------------------

-- Replaced wholesale to add the remix loop. A rebuild has to stay byte-identical to what the
-- triggers produce - that equivalence is asserted by the search tests and is the main defence
-- against trigger drift, so a new entity type that is triggered but not rebuilt would break it.
CREATE OR REPLACE FUNCTION "search_document_rebuild"()
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    v_uuid  uuid;
    v_count bigint := 0;
BEGIN
    DELETE FROM "search_document";

    FOR v_uuid IN SELECT "uuid" FROM "asset" LOOP
        PERFORM "search_document_refresh_asset"(v_uuid);
    END LOOP;
    FOR v_uuid IN SELECT "uuid" FROM "tag" LOOP
        PERFORM "search_document_refresh_tag"(v_uuid);
    END LOOP;
    FOR v_uuid IN SELECT "uuid" FROM "person" LOOP
        PERFORM "search_document_refresh_person"(v_uuid);
    END LOOP;
    FOR v_uuid IN SELECT "uuid" FROM "collection" LOOP
        PERFORM "search_document_refresh_collection"(v_uuid);
    END LOOP;
    FOR v_uuid IN SELECT "uuid" FROM "library" LOOP
        PERFORM "search_document_refresh_library"(v_uuid);
    END LOOP;
    FOR v_uuid IN SELECT "uuid" FROM "cluster" LOOP
        PERFORM "search_document_refresh_cluster"(v_uuid);
    END LOOP;
    FOR v_uuid IN SELECT "uuid" FROM "annotation" LOOP
        PERFORM "search_document_refresh_annotation"(v_uuid);
    END LOOP;
    FOR v_uuid IN SELECT "uuid" FROM "remix" LOOP
        PERFORM "search_document_refresh_remix"(v_uuid);
    END LOOP;

    SELECT count(*) INTO v_count FROM "search_document";
    RETURN v_count;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- Backfill
-- ---------------------------------------------------------------------------------------------

-- Only the remixes, rather than search_document_rebuild(): a full rebuild would drop and rewrite
-- every document on an installation that already has a populated index, for no gain.
DO $$
DECLARE v_uuid uuid;
BEGIN
    FOR v_uuid IN SELECT "uuid" FROM "remix" LOOP
        PERFORM "search_document_refresh_remix"(v_uuid);
    END LOOP;
END $$;
