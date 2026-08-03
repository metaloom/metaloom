-- ---------------------------------------------------------------------------------------------
-- Make ingested asset metadata searchable.
--
-- The `metadata` Cortex node writes one asset_json_comp per asset with schema_type = 'metadata',
-- carrying the canonical Dublin-Core-shaped envelope. search_extract_json_text is a whitelist:
-- a schema_type it does not name contributes nothing to search_document, silently. Without this
-- branch a photo's title, caption, keywords and creator are stored but unfindable.
--
-- Only the authored, human-readable fields are indexed. Camera settings, GPS coordinates and the
-- raw key/value block are deliberately left out: they are numbers and vendor tokens that would
-- dilute the tsvector without ever being typed into a search box.
-- ---------------------------------------------------------------------------------------------

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
        -- MetadataNode: the authored half of the Dublin Core envelope. dc.creator, dc.subject and
        -- dc.contributor are arrays by contract, so they are aggregated rather than cast.
        WHEN 'metadata' THEN
            v_text := concat_ws(' ',
                p_data #>> '{dc,title}',
                p_data #>> '{dc,description}',
                p_data #>> '{dc,publisher}',
                p_data #>> '{dc,coverage}',
                p_data #>> '{dc,rights}',
                (SELECT string_agg(v #>> '{}', ' ')
                   FROM jsonb_path_query(p_data, '$.dc.creator[*]') AS v),
                (SELECT string_agg(v #>> '{}', ' ')
                   FROM jsonb_path_query(p_data, '$.dc.contributor[*]') AS v),
                (SELECT string_agg(v #>> '{}', ' ')
                   FROM jsonb_path_query(p_data, '$.dc.subject[*]') AS v),
                p_data #>> '{rights,holder}',
                p_data #>> '{rights,credit}',
                p_data #>> '{geo,place,city}',
                p_data #>> '{geo,place,state}',
                p_data #>> '{geo,place,country}');
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
