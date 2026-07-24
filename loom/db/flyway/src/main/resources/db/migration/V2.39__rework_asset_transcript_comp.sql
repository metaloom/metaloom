-- Rework asset_transcript_comp for per-track transcripts and full-text search.
--
-- Transcription is the one result path that already works end to end (WhisperNode ->
-- createAssetTranscript -> the UI transcript panel), which makes it the template for
-- every other node. It is also the case where multiplicity is unavoidable: a video with
-- two audio tracks has two transcripts, and the same track may be transcribed into more
-- than one language.
--
-- transcript_json is retained: the UI consumes transcriptJson.sections[] with word level
-- timings, so segment rows are not needed yet.
--
-- DESTRUCTIVE rewrite - see V2.38 for the shared component contract.

DROP TABLE IF EXISTS "asset_transcript_comp" CASCADE;

CREATE TABLE "asset_transcript_comp" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "confidence"       real,

    "stream_index"     int NOT NULL DEFAULT 0,
    "lang"             varchar NOT NULL DEFAULT '',
    "audio_comp_uuid"  uuid,
    "model"            varchar,
    "transcript_text"  text,
    "duration"         bigint,
    "word_count"       int,
    "transcript_json"  jsonb,
    "text_search"      tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce("transcript_text", ''))) STORED,

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "asset_transcript_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_transcript_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    -- SET NULL, not CASCADE: re-running the probe node replaces the audio comp row with
    -- a fresh uuid, and that must not delete the transcript. The transcript's own link to
    -- the asset is the cascading one.
    CONSTRAINT "asset_transcript_comp_audio_comp_uuid_fkey" FOREIGN KEY ("audio_comp_uuid") REFERENCES "asset_audio_comp" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_transcript_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_transcript_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_transcript_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_transcript_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_transcript_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "stream_index", "lang")
);

CREATE INDEX "idx_asset_transcript_comp_asset_uuid" ON "asset_transcript_comp" ("asset_uuid");
CREATE INDEX "idx_asset_transcript_comp_lang" ON "asset_transcript_comp" ("lang");
CREATE INDEX "idx_asset_transcript_comp_audio_comp_uuid" ON "asset_transcript_comp" ("audio_comp_uuid");
CREATE INDEX "idx_asset_transcript_comp_text_search" ON "asset_transcript_comp" USING GIN ("text_search");

COMMENT ON TABLE "asset_transcript_comp" IS 'Transcript of one audio track of an asset. One row per (producer, track, language).';
COMMENT ON COLUMN "asset_transcript_comp"."stream_index" IS 'Which audio track was transcribed. Part of the unique key rather than relying on audio_comp_uuid, because an audio-only asset may be transcribed before any audio component row exists.';
COMMENT ON COLUMN "asset_transcript_comp"."lang" IS 'BCP-47 language tag of the transcript; empty string means undetermined';
COMMENT ON COLUMN "asset_transcript_comp"."audio_comp_uuid" IS 'The audio component this transcript was produced from, when one is known';
COMMENT ON COLUMN "asset_transcript_comp"."model" IS 'Readable mirror of producer_version, e.g. whisper-large-v3';
COMMENT ON COLUMN "asset_transcript_comp"."transcript_text" IS 'Full concatenated transcript';
COMMENT ON COLUMN "asset_transcript_comp"."duration" IS 'Duration in milliseconds';
COMMENT ON COLUMN "asset_transcript_comp"."transcript_json" IS 'Full model output including sections, words and timings. Consumed by the UI transcript panel.';
COMMENT ON COLUMN "asset_transcript_comp"."text_search" IS 'Generated full-text index column. Uses the immutable, language-neutral simple configuration. Never write it - it is maintained by PostgreSQL.';
COMMENT ON COLUMN "asset_transcript_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';
