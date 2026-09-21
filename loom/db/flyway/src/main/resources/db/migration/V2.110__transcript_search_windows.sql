-- Index a transcript as a series of timecoded windows rather than as one document.
--
-- WHAT WAS WRONG
--   V2.58 gave each transcript exactly one search_document row: the whole 43-minute episode as a
--   single body, with time_from hardcoded to 0. The comment above that INSERT says the row exists
--   "so a hit can deep-link to a timestamp in the player" — but there was only ever one timestamp
--   and it was zero. Searching for a line of dialogue told you which episode it was in and then
--   dropped you at 0:00 of a three-quarter-hour file. There was no way at all to ask "where in
--   this episode does somebody say that", and semantic search over a 60,000-character body is
--   worse than useless: one embedding cannot represent forty minutes of unrelated conversation.
--
-- WHAT THIS DOES
--   One document per window of the transcript, each carrying its own time_from. A window is an
--   authored section where somebody has edited one, and otherwise a minute of whisper segments
--   glued together. A minute is the unit because it is roughly a scene's worth of speech: short
--   enough that a single embedding means something, long enough that a sentence is not split from
--   the sentence that gives it its sense.
--
-- WHY A DERIVED UUID
--   search_document is keyed by (entity_type, entity_uuid) and a window is not a row anywhere —
--   the segments live inside transcript_json. uuid_generate_v5(transcript_uuid, index) is stable
--   across rebuilds and across servers, so the incremental refresh and search_document_rebuild()
--   produce byte-identical keys, which is the invariant V2.58 was built around. uuid-ossp is
--   still installed (V2.104 says so explicitly); v5 is a hash, not a generator, so it does not
--   want uuidv7.
--
-- The embeddings follow on their own: SearchEmbeddingService embeds whatever is dirty, and every
-- row written here is dirty.

-- ---------------------------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------------------------

/**
 * h:mm:ss for a millisecond offset. Goes in the subtitle so a result row says where it is.
 */
CREATE OR REPLACE FUNCTION "search_format_timecode"(p_ms bigint)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT to_char(make_interval(secs => greatest(coalesce(p_ms, 0), 0) / 1000.0), 'HH24:MI:SS')
$$;

/** How long one window is, in milliseconds, when the windows have to be derived from segments. */
CREATE OR REPLACE FUNCTION "search_transcript_window_ms"()
RETURNS bigint
LANGUAGE sql
IMMUTABLE
AS $$ SELECT 60000::bigint $$;

/**
 * The windows of one transcript: (index, start in ms, text).
 *
 * Three sources, in order of authority:
 *   1. transcript_json.sections[] — chapters somebody edited. startTime is in *seconds* there;
 *      it is the UI's own shape (TranscriptSection) and the UI counts in seconds.
 *   2. transcript_json.segments[] — what WhisperNode writes. from/to are in *milliseconds*.
 *      Bucketed into fixed windows.
 *   3. transcript_text — a transcript from a producer that stored neither. One window at zero,
 *      which is exactly what every transcript got before this migration.
 *
 * The two unit systems are not a mistake to be cleaned up here: sections are authored through the
 * REST model and segments come from the ASR node, and both shapes are already on disk.
 */
CREATE OR REPLACE FUNCTION "search_transcript_windows"(p_transcript_uuid uuid)
RETURNS TABLE("window_index" int, "time_from" bigint, "body" text)
LANGUAGE sql
STABLE
AS $$
    WITH tc AS (
        SELECT "uuid", "transcript_json", "transcript_text"
          FROM "asset_transcript_comp"
         WHERE "uuid" = p_transcript_uuid
    ),
    authored AS (
        SELECT (row_number() OVER (ORDER BY coalesce((s->>'startTime')::double precision, 0)))::int - 1 AS idx,
               (coalesce((s->>'startTime')::double precision, 0) * 1000)::bigint AS from_ms,
               btrim(concat_ws(' ',
                   nullif(s->>'title', ''),
                   (SELECT string_agg(w->>'word', ' ' ORDER BY ord)
                      FROM jsonb_array_elements(CASE jsonb_typeof(s->'words') WHEN 'array' THEN s->'words' ELSE '[]'::jsonb END)
                           WITH ORDINALITY AS wl(w, ord)))) AS txt
          FROM tc, jsonb_array_elements(
                   CASE jsonb_typeof(tc."transcript_json"->'sections') WHEN 'array' THEN tc."transcript_json"->'sections' ELSE '[]'::jsonb END) AS s
    ),
    segmented AS (
        SELECT (coalesce((g->>'from')::bigint, 0) / "search_transcript_window_ms"())::int AS idx,
               min(coalesce((g->>'from')::bigint, 0)) AS from_ms,
               btrim(string_agg(g->>'text', ' ' ORDER BY coalesce((g->>'from')::bigint, 0))) AS txt
          FROM tc, jsonb_array_elements(
                   CASE jsonb_typeof(tc."transcript_json"->'segments') WHEN 'array' THEN tc."transcript_json"->'segments' ELSE '[]'::jsonb END) AS g
         GROUP BY 1
    ),
    -- Which source won, resolved once so the three branches below cannot all fire.
    chosen AS (
        SELECT EXISTS (SELECT 1 FROM authored WHERE txt <> '')  AS has_authored,
               EXISTS (SELECT 1 FROM segmented WHERE txt <> '') AS has_segments
    )
    SELECT a.idx, a.from_ms, a.txt FROM authored a, chosen c WHERE c.has_authored AND a.txt <> ''
    UNION ALL
    SELECT g.idx, g.from_ms, g.txt FROM segmented g, chosen c WHERE NOT c.has_authored AND c.has_segments AND g.txt <> ''
    UNION ALL
    SELECT 0, 0::bigint, left(t."transcript_text", "search_body_cap"())
      FROM tc t, chosen c
     WHERE NOT c.has_authored AND NOT c.has_segments AND coalesce(t."transcript_text", '') <> ''
$$;

COMMENT ON FUNCTION "search_transcript_windows"(uuid) IS
    'Timecoded windows of one transcript: authored sections if any, else whisper segments bucketed per minute, else the whole text at offset 0.';

-- ---------------------------------------------------------------------------------------------
-- The refresh function, with the transcript half replaced
-- ---------------------------------------------------------------------------------------------
--
-- Only the transcript block below V2.58's asset block changes. The whole function is restated
-- because CREATE OR REPLACE replaces the body wholesale — this is the same reason every later
-- migration that touches one of these functions restates it.

CREATE OR REPLACE FUNCTION "search_document_refresh_asset"(p_asset_uuid uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_asset       "asset"%ROWTYPE;
    v_subtitle    text;
    v_body        text;
    v_keywords    text;
    v_truncated   boolean := false;
    v_tags        text[];
    v_libraries   uuid[];
    v_spaces      uuid[];
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

    v_keywords := concat_ws(' ',
        v_asset."mime_type",
        v_asset."initial_origin",
        "search_tokenize_path"(v_asset."initial_origin"),
        "search_tokenize_path"(v_asset."filename"),
        (SELECT string_agg(DISTINCT "search_tokenize_path"(al."path"), ' ')
           FROM "asset_location" al WHERE al."asset_uuid" = p_asset_uuid),
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

    -- One document per timecoded window, so a hit lands on the moment rather than on the file.
    -- The delete comes first and is keyed on the set of uuids the windows *currently* produce:
    -- re-transcribing with a different model changes how many windows there are, and the ones
    -- that no longer exist have to go rather than linger as results pointing at nothing.
    DELETE FROM "search_document"
     WHERE "entity_type" = 'transcript'
       AND "asset_uuid"  = p_asset_uuid
       AND "entity_uuid" NOT IN (
            SELECT uuid_generate_v5(tc."uuid", w."window_index"::text)
              FROM "asset_transcript_comp" tc
              CROSS JOIN LATERAL "search_transcript_windows"(tc."uuid") w
             WHERE tc."asset_uuid" = p_asset_uuid);

    INSERT INTO "search_document" (
        "entity_type", "entity_uuid", "asset_uuid", "title", "subtitle", "body", "keywords",
        "body_truncated", "lang", "mime_type", "size", "time_from", "sort_date",
        "library_uuids", "space_uuids", "collection_uuids", "tag_names", "dirty", "synced_at")
    SELECT
        'transcript',
        uuid_generate_v5(tc."uuid", w."window_index"::text),
        p_asset_uuid,
        coalesce(v_asset."filename", ''),
        -- The timecode is in the subtitle because it is weight B: a result row has to say where
        -- in the file it is, and the reader is looking for that before they read the line itself.
        concat_ws(' · ', "search_format_timecode"(w."time_from"), nullif(concat_ws(' ', tc."lang", tc."model"), '')),
        left(w."body", "search_body_cap"()),
        '',
        length(w."body") > "search_body_cap"(),
        coalesce(tc."lang", ''), v_asset."mime_type", v_asset."size", w."time_from", v_asset."first_seen",
        coalesce(v_libraries, '{}'), coalesce(v_spaces, '{}'), coalesce(v_collections, '{}'),
        coalesce(v_tags, '{}'), true, now()
      FROM "asset_transcript_comp" tc
      CROSS JOIN LATERAL "search_transcript_windows"(tc."uuid") w
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

-- ---------------------------------------------------------------------------------------------
-- Rewrite the transcript documents that already exist
-- ---------------------------------------------------------------------------------------------
--
-- Every existing transcript document is the old whole-episode shape with time_from = 0. Dropping
-- them first rather than relying on the upsert: the derived uuid of window 0 is not the uuid of
-- the old row (which was the transcript's own uuid), so the old rows would otherwise survive as
-- duplicates of their own first minute.

DELETE FROM "search_document" WHERE "entity_type" = 'transcript';

DO $$
DECLARE
    v_asset uuid;
BEGIN
    FOR v_asset IN SELECT DISTINCT "asset_uuid" FROM "asset_transcript_comp" LOOP
        PERFORM "search_document_refresh_asset"(v_asset);
    END LOOP;
END $$;

-- Reaching a window by its offset is the other half of the deep link: the asset detail view asks
-- for "the transcript documents of this asset, in time order" when it renders a result list.
CREATE INDEX IF NOT EXISTS "idx_search_document_asset_time"
    ON "search_document" ("asset_uuid", "time_from")
 WHERE "asset_uuid" IS NOT NULL;
