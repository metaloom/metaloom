-- The search index: one materialized, weighted, ACL-projected document per searchable entity.
--
-- WHY A TABLE AND NOT A VIEW / UNION / MATERIALIZED VIEW
--   A 9-way UNION over the source tables cannot be indexed usefully: the richest text corpus lives
--   in asset_json_comp.data, whose only index is jsonb_path_ops (containment, not full text), so
--   every query would sequentially scan every OCR and Tika payload. A MATERIALIZED VIEW refreshes
--   all-or-nothing, which is unusable for a live catalog. A real table maintained by triggers gives
--   one GIN scan, one ranking expression, a cheap exact count, and cheap facets.
--
--   It is also the pre-assembled document an external index (Elasticsearch) will consume, together
--   with the dirty/synced_at columns that act as its outbox. That is what makes moving to an
--   external provider a binding change rather than a rewrite.
--
-- WHY TRIGGERS AND NOT DAO WRITE HOOKS
--   AbstractJooqDao.storeBatch uses ctx().batchInsert(records), which bypasses per-element hooks;
--   DemoDatabaseInitializer and Flyway backfills bypass the DAO layer entirely. Triggers are the
--   only layer that cannot be bypassed. The cost is a maintenance surface, mitigated by
--   search_document_rebuild() sharing the exact same per-entity refresh functions the triggers call,
--   so a full rebuild is byte-identical to the incremental path by construction.
--
-- WHAT IS NOT A SOURCE
--   asset_doc_comp has a tsvector GIN index but no producer - OCRNode writes asset_json_comp with
--   schema_type='ocr' and TikaNode with schema_type='tika'. The table is left in place (it is the
--   documented graduation path) but it is deliberately not read here. If those nodes ever graduate
--   to writing it, the only change needed is one more branch in search_document_refresh_asset.

CREATE EXTENSION IF NOT EXISTS "pg_trgm";

CREATE TABLE "search_document" (
    "entity_type"      varchar NOT NULL,
    "entity_uuid"      uuid    NOT NULL,
    -- Equals entity_uuid for entity_type='asset'; NULL for library/collection/person/tag/cluster.
    "asset_uuid"       uuid,

    "title"            text    NOT NULL DEFAULT '',   -- weight A
    "subtitle"         text    NOT NULL DEFAULT '',   -- weight B
    "body"             text    NOT NULL DEFAULT '',   -- weight C
    "keywords"         text    NOT NULL DEFAULT '',   -- weight D
    "body_truncated"   boolean NOT NULL DEFAULT false,

    "lang"             varchar NOT NULL DEFAULT '',
    "mime_type"        varchar,
    "size"             bigint,
    "time_from"        bigint,
    "sort_date"        timestamp WITHOUT TIME ZONE,

    -- Reserved ACL projection. Written from day 1, read by nothing until row-level ACL lands.
    -- Multi-valued because an asset has no library_uuid column - membership is many-to-many via
    -- library_asset, and space membership is a further hop through project_library.
    "library_uuids"    uuid[]  NOT NULL DEFAULT '{}',
    "space_uuids"      uuid[]  NOT NULL DEFAULT '{}',
    "collection_uuids" uuid[]  NOT NULL DEFAULT '{}',
    "tag_names"        text[]  NOT NULL DEFAULT '{}',

    -- Two generated vectors, both queried, higher rank wins.
    --   simple  - exact tokens survive: filenames, ids, codes, non-English words.
    --   english - stemming, so indexing "running" also matches a search for "run".
    -- A data-dependent config (to_tsvector(lang::regconfig, ...)) is NOT immutable and therefore
    -- cannot be a generated column; true multilingual support needs a trigger-maintained column.
    "text_search"      tsvector GENERATED ALWAYS AS (
           setweight(to_tsvector('simple',  coalesce("title",'')),    'A')
        || setweight(to_tsvector('simple',  coalesce("subtitle",'')), 'B')
        || setweight(to_tsvector('simple',  coalesce("body",'')),     'C')
        || setweight(to_tsvector('simple',  coalesce("keywords",'')), 'D')) STORED,
    "text_search_en"   tsvector GENERATED ALWAYS AS (
           setweight(to_tsvector('english', coalesce("title",'')),    'A')
        || setweight(to_tsvector('english', coalesce("subtitle",'')), 'B')
        || setweight(to_tsvector('english', coalesce("body",'')),     'C')
        || setweight(to_tsvector('english', coalesce("keywords",'')), 'D')) STORED,
    -- Trigram source for typo tolerance and typeahead. Bounded: a trigram index over a full
    -- transcript would be enormous and useless.
    "trgm_text"        text GENERATED ALWAYS AS (
        left(coalesce("title",'') || ' ' || coalesce("subtitle",''), 2048)) STORED,

    "index_version"    int     NOT NULL DEFAULT 1,
    "synced_at"        timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "dirty"            boolean NOT NULL DEFAULT true,
    "es_synced_at"     timestamp WITHOUT TIME ZONE,
    "error"            text,

    CONSTRAINT "search_document_pkey" PRIMARY KEY ("entity_type", "entity_uuid"),
    -- Doing real work: deleting an asset removes its own document AND every derived
    -- transcript document, with no delete trigger at all.
    CONSTRAINT "search_document_asset_uuid_fkey"
        FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE
);

CREATE INDEX "idx_search_document_text_search"    ON "search_document" USING GIN ("text_search");
CREATE INDEX "idx_search_document_text_search_en" ON "search_document" USING GIN ("text_search_en");
CREATE INDEX "idx_search_document_trgm"           ON "search_document" USING GIN ("trgm_text" gin_trgm_ops);
CREATE INDEX "idx_search_document_asset_uuid"     ON "search_document" ("asset_uuid");
CREATE INDEX "idx_search_document_entity_type"    ON "search_document" ("entity_type");
CREATE INDEX "idx_search_document_mime_type"      ON "search_document" ("mime_type");
CREATE INDEX "idx_search_document_sort_date"      ON "search_document" ("sort_date" DESC NULLS LAST);
CREATE INDEX "idx_search_document_libraries"      ON "search_document" USING GIN ("library_uuids");
CREATE INDEX "idx_search_document_spaces"         ON "search_document" USING GIN ("space_uuids");
CREATE INDEX "idx_search_document_collections"    ON "search_document" USING GIN ("collection_uuids");
CREATE INDEX "idx_search_document_tag_names"      ON "search_document" USING GIN ("tag_names");
CREATE INDEX "idx_search_document_dirty"          ON "search_document" ("synced_at") WHERE "dirty";

COMMENT ON TABLE "search_document" IS 'Materialized search index. One row per searchable entity, maintained by triggers on the source tables. Also the pre-assembled document and outbox for an external index.';
COMMENT ON COLUMN "search_document"."text_search" IS 'Generated full-text column (simple config, exact tokens). Never write it - PostgreSQL maintains it.';
COMMENT ON COLUMN "search_document"."text_search_en" IS 'Generated full-text column (english config, stemmed). Never write it - PostgreSQL maintains it.';
COMMENT ON COLUMN "search_document"."trgm_text" IS 'Generated, bounded trigram source over title+subtitle. Backs typo tolerance and typeahead.';
COMMENT ON COLUMN "search_document"."body_truncated" IS 'Body was cut at the configured cap. A tsvector is limited to 1MB and lexeme positions to 16383, so a book-sized Tika extraction must be truncated or the write fails.';
COMMENT ON COLUMN "search_document"."library_uuids" IS 'Reserved ACL projection - populated, but read by nothing until row-level ACL lands.';
COMMENT ON COLUMN "search_document"."dirty" IS 'Outbox flag for an external index. Always effectively unused by the Postgres provider, which queries this table directly.';

-- Tombstones. The asset_uuid cascade removes rows before an external indexer can observe the
-- delete, so the delete has to be recorded separately.
CREATE TABLE "search_document_deleted" (
    "entity_type" varchar   NOT NULL,
    "entity_uuid" uuid      NOT NULL,
    "deleted_at"  timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT "search_document_deleted_pkey" PRIMARY KEY ("entity_type", "entity_uuid")
);
CREATE INDEX "idx_search_document_deleted_at" ON "search_document_deleted" ("deleted_at");

COMMENT ON TABLE "search_document_deleted" IS 'Delete tombstones for external index sync. Drained and pruned by the indexer; unused by the Postgres provider.';


-- ---------------------------------------------------------------------------------------------
-- Text extraction from asset_json_comp.data
-- ---------------------------------------------------------------------------------------------

-- Concatenate every string leaf of a jsonb value. Used ONLY for llm and vlm payloads, whose shape
-- is prompt-defined and therefore unknowable. Applying it to every schema_type would index model
-- names, uuids and enum values as searchable text and wreck ranking.
CREATE OR REPLACE FUNCTION "search_jsonb_all_text"(p_data jsonb)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT coalesce(string_agg(s.val, ' '), '')
    FROM (
        SELECT v #>> '{}' AS val
        FROM jsonb_path_query(coalesce(p_data, '{}'::jsonb), '$.**') AS v
        WHERE jsonb_typeof(v) = 'string'
          AND length(v #>> '{}') > 2
    ) s
$$;

COMMENT ON FUNCTION "search_jsonb_all_text"(jsonb) IS 'Recursive string-leaf walker. Only for llm/vlm payloads - see the whitelist in search_extract_json_text.';

-- Whitelist-driven extraction, keyed on the schema_type each Cortex node writes.
CREATE OR REPLACE FUNCTION "search_extract_json_text"(p_schema_type varchar, p_data jsonb)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_text text;
BEGIN
    IF p_data IS NULL THEN
        RETURN '';
    END IF;

    CASE lower(coalesce(p_schema_type, ''))
        -- OCRNode
        WHEN 'ocr' THEN
            v_text := p_data ->> 'text';
        -- TikaNode
        WHEN 'tika' THEN
            v_text := p_data ->> 'content';
        -- CaptioningNode (image)
        WHEN 'caption' THEN
            v_text := p_data ->> 'caption';
        -- CaptioningNode (video): overall caption plus the per-scene caption timeline
        WHEN 'video-caption' THEN
            v_text := concat_ws(' ',
                p_data ->> 'caption',
                (SELECT string_agg(v #>> '{}', ' ')
                   FROM jsonb_path_query(p_data, '$.scenes[*].caption') AS v));
        -- FacedescriptionNode
        WHEN 'face-description' THEN
            v_text := (SELECT string_agg(v #>> '{}', ' ')
                         FROM jsonb_path_query(p_data, '$.faces[*].description') AS v);
        -- LLMNode: one answer per configured prompt, key varies by prompt
        WHEN 'llm' THEN
            v_text := coalesce(p_data ->> 'text', p_data ->> 'answer', p_data ->> 'summary',
                               p_data ->> 'description', "search_jsonb_all_text"(p_data));
        -- VlmNode
        WHEN 'vlm' THEN
            v_text := coalesce(p_data ->> 'text', "search_jsonb_all_text"(p_data));
        -- QualityNode is numeric; anything unknown is skipped rather than guessed at.
        ELSE
            v_text := NULL;
    END CASE;

    RETURN coalesce(v_text, '');
END;
$$;

COMMENT ON FUNCTION "search_extract_json_text"(varchar, jsonb) IS 'Extract human-readable text from an asset_json_comp payload. Whitelist-driven: unknown schema_types contribute nothing.';


-- ---------------------------------------------------------------------------------------------
-- Per-entity refresh functions.
--
-- Each one recomputes a whole entity's document(s) from scratch. The triggers call these and so
-- does search_document_rebuild(), which is why a full rebuild is byte-identical to the incremental
-- path - there is only one piece of code that knows how to build a document.
-- ---------------------------------------------------------------------------------------------

-- Cap on indexed body text. Mirrors LOOM_SEARCH_BODY_MAX_BYTES (default 512 KB); a tsvector is
-- limited to 1 MB, so an uncapped Tika extraction of a book fails the INSERT outright.
CREATE OR REPLACE FUNCTION "search_body_cap"()
RETURNS int
LANGUAGE sql
IMMUTABLE
AS $$ SELECT 524288 $$;

CREATE OR REPLACE FUNCTION "search_document_refresh_asset"(p_asset_uuid uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_asset      "asset"%ROWTYPE;
    v_subtitle   text;
    v_body       text;
    v_keywords   text;
    v_truncated  boolean := false;
    v_tags       text[];
    v_libraries  uuid[];
    v_spaces     uuid[];
    v_collections uuid[];
BEGIN
    SELECT * INTO v_asset FROM "asset" WHERE "uuid" = p_asset_uuid;
    IF NOT FOUND THEN
        DELETE FROM "search_document" WHERE "asset_uuid" = p_asset_uuid;
        RETURN;
    END IF;

    -- B: every known filesystem path of the asset
    SELECT coalesce(string_agg(DISTINCT al."path", E'\n'), '')
      INTO v_subtitle
      FROM "asset_location" al
     WHERE al."asset_uuid" = p_asset_uuid;

    -- C: the actual corpus - extracted json text plus transcripts
    SELECT coalesce(string_agg(x.t, E'\n' ORDER BY x.t), '')
      INTO v_body
      FROM (
            SELECT "search_extract_json_text"(jc."schema_type", jc."data") AS t
              FROM "asset_json_comp" jc
             WHERE jc."asset_uuid" = p_asset_uuid
            UNION ALL
            SELECT tc."transcript_text"
              FROM "asset_transcript_comp" tc
             WHERE tc."asset_uuid" = p_asset_uuid
           ) x
     WHERE x.t IS NOT NULL AND x.t <> '';

    IF length(v_body) > "search_body_cap"() THEN
        v_body := left(v_body, "search_body_cap"());
        v_truncated := true;
    END IF;

    SELECT array_agg(DISTINCT t."name" ORDER BY t."name")
      INTO v_tags
      FROM "tag_asset" ta JOIN "tag" t ON t."uuid" = ta."tag_uuid"
     WHERE ta."asset_uuid" = p_asset_uuid;

    -- D: short, high-signal terms
    v_keywords := concat_ws(' ',
        v_asset."mime_type",
        v_asset."initial_origin",
        (SELECT string_agg(DISTINCT d."label", ' ' ORDER BY d."label")
           FROM "detection" d WHERE d."asset_uuid" = p_asset_uuid AND d."label" IS NOT NULL),
        (SELECT string_agg(DISTINCT sc."title", ' ' ORDER BY sc."title")
           FROM "asset_segment_comp" sc WHERE sc."asset_uuid" = p_asset_uuid AND sc."title" IS NOT NULL),
        (SELECT string_agg(DISTINCT concat_ws(' ', t."name", t."collection"), ' ')
           FROM "tag_asset" ta JOIN "tag" t ON t."uuid" = ta."tag_uuid"
          WHERE ta."asset_uuid" = p_asset_uuid));

    SELECT array_agg(DISTINCT la."library_uuid") INTO v_libraries
      FROM "library_asset" la WHERE la."asset_uuid" = p_asset_uuid;

    SELECT array_agg(DISTINCT pl."project_uuid") INTO v_spaces
      FROM "library_asset" la JOIN "project_library" pl ON pl."library_uuid" = la."library_uuid"
     WHERE la."asset_uuid" = p_asset_uuid;

    SELECT array_agg(DISTINCT ca."collection_uuid") INTO v_collections
      FROM "collection_asset" ca WHERE ca."asset_uuid" = p_asset_uuid;

    INSERT INTO "search_document" (
        "entity_type", "entity_uuid", "asset_uuid", "title", "subtitle", "body", "keywords",
        "body_truncated", "mime_type", "size", "sort_date",
        "library_uuids", "space_uuids", "collection_uuids", "tag_names", "dirty", "synced_at")
    VALUES (
        'asset', p_asset_uuid, p_asset_uuid,
        coalesce(v_asset."filename", ''), v_subtitle, v_body, coalesce(v_keywords, ''),
        v_truncated, v_asset."mime_type", v_asset."size", v_asset."first_seen",
        coalesce(v_libraries, '{}'), coalesce(v_spaces, '{}'), coalesce(v_collections, '{}'),
        coalesce(v_tags, '{}'), true, now())
    ON CONFLICT ("entity_type", "entity_uuid") DO UPDATE SET
        "asset_uuid"       = EXCLUDED."asset_uuid",
        "title"            = EXCLUDED."title",
        "subtitle"         = EXCLUDED."subtitle",
        "body"             = EXCLUDED."body",
        "keywords"         = EXCLUDED."keywords",
        "body_truncated"   = EXCLUDED."body_truncated",
        "mime_type"        = EXCLUDED."mime_type",
        "size"             = EXCLUDED."size",
        "sort_date"        = EXCLUDED."sort_date",
        "library_uuids"    = EXCLUDED."library_uuids",
        "space_uuids"      = EXCLUDED."space_uuids",
        "collection_uuids" = EXCLUDED."collection_uuids",
        "tag_names"        = EXCLUDED."tag_names",
        "dirty"            = true,
        "synced_at"        = now();

    -- A transcript also gets its own document, so a hit can deep-link to a timestamp in the player
    -- rather than only surfacing the asset.
    DELETE FROM "search_document"
     WHERE "entity_type" = 'transcript'
       AND "asset_uuid"  = p_asset_uuid
       AND "entity_uuid" NOT IN (SELECT "uuid" FROM "asset_transcript_comp" WHERE "asset_uuid" = p_asset_uuid);

    INSERT INTO "search_document" (
        "entity_type", "entity_uuid", "asset_uuid", "title", "subtitle", "body", "keywords",
        "body_truncated", "lang", "mime_type", "size", "time_from", "sort_date",
        "library_uuids", "space_uuids", "collection_uuids", "tag_names", "dirty", "synced_at")
    SELECT
        'transcript', tc."uuid", p_asset_uuid,
        coalesce(v_asset."filename", ''),
        concat_ws(' ', tc."lang", tc."model"),
        left(coalesce(tc."transcript_text", ''), "search_body_cap"()),
        '',
        length(coalesce(tc."transcript_text", '')) > "search_body_cap"(),
        coalesce(tc."lang", ''), v_asset."mime_type", v_asset."size", 0, v_asset."first_seen",
        coalesce(v_libraries, '{}'), coalesce(v_spaces, '{}'), coalesce(v_collections, '{}'),
        coalesce(v_tags, '{}'), true, now()
      FROM "asset_transcript_comp" tc
     WHERE tc."asset_uuid" = p_asset_uuid
    ON CONFLICT ("entity_type", "entity_uuid") DO UPDATE SET
        "asset_uuid"       = EXCLUDED."asset_uuid",
        "title"            = EXCLUDED."title",
        "subtitle"         = EXCLUDED."subtitle",
        "body"             = EXCLUDED."body",
        "body_truncated"   = EXCLUDED."body_truncated",
        "lang"             = EXCLUDED."lang",
        "mime_type"        = EXCLUDED."mime_type",
        "size"             = EXCLUDED."size",
        "time_from"        = EXCLUDED."time_from",
        "sort_date"        = EXCLUDED."sort_date",
        "library_uuids"    = EXCLUDED."library_uuids",
        "space_uuids"      = EXCLUDED."space_uuids",
        "collection_uuids" = EXCLUDED."collection_uuids",
        "tag_names"        = EXCLUDED."tag_names",
        "dirty"            = true,
        "synced_at"        = now();
END;
$$;

-- Simple standalone entities: title/subtitle only, no asset linkage.
CREATE OR REPLACE FUNCTION "search_document_refresh_simple"(
    p_entity_type varchar, p_entity_uuid uuid, p_title text, p_subtitle text, p_keywords text, p_sort_date timestamp)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_title IS NULL AND p_subtitle IS NULL THEN
        DELETE FROM "search_document" WHERE "entity_type" = p_entity_type AND "entity_uuid" = p_entity_uuid;
        RETURN;
    END IF;
    INSERT INTO "search_document" (
        "entity_type", "entity_uuid", "title", "subtitle", "keywords", "sort_date", "dirty", "synced_at")
    VALUES (p_entity_type, p_entity_uuid, coalesce(p_title, ''), coalesce(p_subtitle, ''),
            coalesce(p_keywords, ''), p_sort_date, true, now())
    ON CONFLICT ("entity_type", "entity_uuid") DO UPDATE SET
        "title"     = EXCLUDED."title",
        "subtitle"  = EXCLUDED."subtitle",
        "keywords"  = EXCLUDED."keywords",
        "sort_date" = EXCLUDED."sort_date",
        "dirty"     = true,
        "synced_at" = now();
END;
$$;

CREATE OR REPLACE FUNCTION "search_document_refresh_tag"(p_uuid uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r "tag"%ROWTYPE;
BEGIN
    SELECT * INTO r FROM "tag" WHERE "uuid" = p_uuid;
    IF NOT FOUND THEN
        DELETE FROM "search_document" WHERE "entity_type" = 'tag' AND "entity_uuid" = p_uuid;
        RETURN;
    END IF;
    PERFORM "search_document_refresh_simple"('tag', p_uuid, r."name", r."collection", '', r."created");
END; $$;

CREATE OR REPLACE FUNCTION "search_document_refresh_person"(p_uuid uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r "person"%ROWTYPE;
BEGIN
    SELECT * INTO r FROM "person" WHERE "uuid" = p_uuid;
    IF NOT FOUND THEN
        DELETE FROM "search_document" WHERE "entity_type" = 'person' AND "entity_uuid" = p_uuid;
        RETURN;
    END IF;
    PERFORM "search_document_refresh_simple"('person', p_uuid,
        concat_ws(' ', r."firstname", r."lastname"), r."alias", '', r."created");
END; $$;

CREATE OR REPLACE FUNCTION "search_document_refresh_collection"(p_uuid uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r "collection"%ROWTYPE;
BEGIN
    SELECT * INTO r FROM "collection" WHERE "uuid" = p_uuid;
    IF NOT FOUND THEN
        DELETE FROM "search_document" WHERE "entity_type" = 'collection' AND "entity_uuid" = p_uuid;
        RETURN;
    END IF;
    PERFORM "search_document_refresh_simple"('collection', p_uuid, r."name", r."description", '', r."created");
END; $$;

CREATE OR REPLACE FUNCTION "search_document_refresh_library"(p_uuid uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r "library"%ROWTYPE;
BEGIN
    SELECT * INTO r FROM "library" WHERE "uuid" = p_uuid;
    IF NOT FOUND THEN
        DELETE FROM "search_document" WHERE "entity_type" = 'library' AND "entity_uuid" = p_uuid;
        RETURN;
    END IF;
    PERFORM "search_document_refresh_simple"('library', p_uuid, r."name", r."description", '', r."created");
END; $$;

CREATE OR REPLACE FUNCTION "search_document_refresh_cluster"(p_uuid uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r "cluster"%ROWTYPE;
BEGIN
    SELECT * INTO r FROM "cluster" WHERE "uuid" = p_uuid;
    IF NOT FOUND THEN
        DELETE FROM "search_document" WHERE "entity_type" = 'cluster' AND "entity_uuid" = p_uuid;
        RETURN;
    END IF;
    PERFORM "search_document_refresh_simple"('cluster', p_uuid, r."name", r."type", '', r."created");
END; $$;

CREATE OR REPLACE FUNCTION "search_document_refresh_annotation"(p_uuid uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r "annotation"%ROWTYPE;
BEGIN
    SELECT * INTO r FROM "annotation" WHERE "uuid" = p_uuid;
    IF NOT FOUND THEN
        DELETE FROM "search_document" WHERE "entity_type" = 'annotation' AND "entity_uuid" = p_uuid;
        RETURN;
    END IF;
    INSERT INTO "search_document" (
        "entity_type", "entity_uuid", "asset_uuid", "title", "subtitle", "keywords", "time_from",
        "sort_date", "dirty", "synced_at")
    VALUES ('annotation', p_uuid, r."asset_uuid", coalesce(r."title", ''), coalesce(r."description", ''),
            '', r."time_from", r."created", true, now())
    ON CONFLICT ("entity_type", "entity_uuid") DO UPDATE SET
        "asset_uuid" = EXCLUDED."asset_uuid",
        "title"      = EXCLUDED."title",
        "subtitle"   = EXCLUDED."subtitle",
        "time_from"  = EXCLUDED."time_from",
        "sort_date"  = EXCLUDED."sort_date",
        "dirty"      = true,
        "synced_at"  = now();
END; $$;

-- Full rebuild. Idempotent, and shares every refresh function with the trigger path, so its output
-- is byte-identical to what incremental maintenance produces. That property is asserted by
-- PostgresSearchProviderTest and is the main defence against trigger drift.
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

    SELECT count(*) INTO v_count FROM "search_document";
    RETURN v_count;
END;
$$;

COMMENT ON FUNCTION "search_document_rebuild"() IS 'Drop and rebuild the whole index from the source tables. Shares the per-entity refresh functions with the triggers, so a rebuild equals the incremental result.';
