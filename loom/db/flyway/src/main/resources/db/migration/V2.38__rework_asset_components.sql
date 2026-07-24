-- Rework the typed asset component tables onto the shared component contract.
--
-- The V2.18 tables could not receive Cortex node output safely: no idempotency key
-- (a retried node appended a duplicate row), no provenance (a model upgrade could not
-- be detected let alone invalidated), and no multiplicity discriminator (a video with
-- two audio tracks or a PDF with 40 OCR'd pages was not representable).
--
-- This is a DESTRUCTIVE rewrite. Nothing writes these tables except the V2.18 backfill,
-- so no data is preserved.
--
-- Shared component contract (every asset_*_comp table):
--   uuid / asset_uuid                       identity
--   node_kind / node_id / producer_version  who produced it, and with what
--   run_uuid / task_uuid                    which pipeline execution produced it
--   confidence                              extraction confidence, where meaningful
--   meta                                    free-form
--   created/creator_uuid/edited/editor_uuid audit; creator/editor are NULLABLE because
--                                           these rows are written by workers, not users
--   UNIQUE (asset_uuid, node_kind, <typed discriminators>)
--
-- producer_version is deliberately NOT part of the unique key: a re-run replaces the
-- row in place and rewrites the version, and
--   WHERE node_kind = 'whisper' AND producer_version <> 'large-v3'
-- finds everything that needs recomputing.

DROP TABLE IF EXISTS "asset_geo_comp" CASCADE;
DROP TABLE IF EXISTS "asset_doc_comp" CASCADE;
DROP TABLE IF EXISTS "asset_image_comp" CASCADE;
DROP TABLE IF EXISTS "asset_video_comp" CASCADE;
DROP TABLE IF EXISTS "asset_audio_comp" CASCADE;

-- ---------------------------------------------------------------------------
-- Geo component
-- ---------------------------------------------------------------------------
CREATE TABLE "asset_geo_comp" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "confidence"       real,

    "method"           varchar NOT NULL DEFAULT '',
    "time_from"        bigint NOT NULL DEFAULT 0,
    "geo_lon"          decimal(9,6),
    "geo_lat"          decimal(8,6),
    "geo_alias"        varchar,
    "accuracy_m"       real,

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "asset_geo_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_geo_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_geo_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_geo_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_geo_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_geo_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_geo_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "method", "time_from")
);

CREATE INDEX "idx_asset_geo_comp_asset_uuid" ON "asset_geo_comp" ("asset_uuid");
CREATE INDEX "idx_asset_geo_comp_position" ON "asset_geo_comp" ("geo_lon", "geo_lat");

COMMENT ON TABLE "asset_geo_comp" IS 'Geo location extracted from an asset. Multiple rows per asset: one per producer, method and time offset (a drone video carries a whole GPS track).';
COMMENT ON COLUMN "asset_geo_comp"."node_kind" IS 'Producing node kind, e.g. tika, llm, manual';
COMMENT ON COLUMN "asset_geo_comp"."producer_version" IS 'Model/algorithm version. Not part of the unique key: a re-run replaces the row.';
COMMENT ON COLUMN "asset_geo_comp"."method" IS 'How the position was derived: exif, xmp, gps-track, llm, manual';
COMMENT ON COLUMN "asset_geo_comp"."time_from" IS 'Millisecond offset into the media; 0 for stills';
COMMENT ON COLUMN "asset_geo_comp"."accuracy_m" IS 'Reported accuracy in meters, when known';
COMMENT ON COLUMN "asset_geo_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';

-- ---------------------------------------------------------------------------
-- Document component
-- ---------------------------------------------------------------------------
CREATE TABLE "asset_doc_comp" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "confidence"       real,

    "page_number"      int NOT NULL DEFAULT 0,
    "page_count"       int,
    "text_lang"        varchar,
    "doc_plain_text"   text,
    "doc_word_count"   int,
    "text_search"      tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce("doc_plain_text", ''))) STORED,

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "asset_doc_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_doc_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_doc_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_doc_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_doc_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_doc_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_doc_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "page_number")
);

CREATE INDEX "idx_asset_doc_comp_asset_uuid" ON "asset_doc_comp" ("asset_uuid");
CREATE INDEX "idx_asset_doc_comp_text_search" ON "asset_doc_comp" USING GIN ("text_search");

COMMENT ON TABLE "asset_doc_comp" IS 'Extracted text of a document or an image region. One row per producer per page: Tika writes the whole document as page 0, OCR writes one row per page.';
COMMENT ON COLUMN "asset_doc_comp"."page_number" IS 'Page this text was extracted from; 0 means the whole document';
COMMENT ON COLUMN "asset_doc_comp"."text_lang" IS 'Detected or configured language of the extracted text';
COMMENT ON COLUMN "asset_doc_comp"."text_search" IS 'Generated full-text index column. Uses the immutable, language-neutral simple configuration. Never write it - it is maintained by PostgreSQL.';
COMMENT ON COLUMN "asset_doc_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';

-- ---------------------------------------------------------------------------
-- Image component
-- ---------------------------------------------------------------------------
CREATE TABLE "asset_image_comp" (
    "uuid"                 uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"           uuid NOT NULL,

    "node_kind"            varchar NOT NULL,
    "node_id"              varchar,
    "producer_version"     varchar NOT NULL DEFAULT '',
    "run_uuid"             uuid,
    "task_uuid"            uuid,
    "confidence"           real,

    "stream_index"         int NOT NULL DEFAULT 0,
    "media_width"          int,
    "media_height"         int,
    "image_dominant_color" varchar,
    "image_encoding"       varchar,
    "orientation"          int,
    "bit_depth"            int,
    "blurriness"           real,

    "meta"                 jsonb,
    "created"              timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"         uuid,
    "edited"               timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"          uuid,

    CONSTRAINT "asset_image_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_image_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_image_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_image_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_image_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_image_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_image_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "stream_index")
);

CREATE INDEX "idx_asset_image_comp_asset_uuid" ON "asset_image_comp" ("asset_uuid");

COMMENT ON TABLE "asset_image_comp" IS 'Image properties extracted from an asset. Never gated on the asset mime type: an MP3 with embedded cover art legitimately owns an image component.';
COMMENT ON COLUMN "asset_image_comp"."stream_index" IS 'Which image stream: multi-frame TIFF/GIF, embedded cover art. 0 for a plain image.';
COMMENT ON COLUMN "asset_image_comp"."image_encoding" IS 'Encoding of the image stream (restored; V2.18 dropped it)';
COMMENT ON COLUMN "asset_image_comp"."blurriness" IS 'Laplacian blurriness measure produced by the quality node';
COMMENT ON COLUMN "asset_image_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';

-- ---------------------------------------------------------------------------
-- Video component
-- ---------------------------------------------------------------------------
CREATE TABLE "asset_video_comp" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "confidence"       real,

    "stream_index"     int NOT NULL DEFAULT 0,
    "media_width"      int,
    "media_height"     int,
    "media_duration"   bigint,
    "video_bitrate"    int,
    "video_encoding"   varchar,
    "fps"              real,
    "frame_count"      bigint,
    "rotation"         int,
    "blurriness"       real,

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "asset_video_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_video_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_video_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_video_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_video_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_video_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_video_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "stream_index")
);

CREATE INDEX "idx_asset_video_comp_asset_uuid" ON "asset_video_comp" ("asset_uuid");

COMMENT ON TABLE "asset_video_comp" IS 'Video stream properties. Two producers of the same dimension (e.g. tika probing and the quality node measuring) yield two partially filled rows - the read side coalesces them by producer precedence.';
COMMENT ON COLUMN "asset_video_comp"."stream_index" IS 'Which video stream within the container. 0 for single-stream media.';
COMMENT ON COLUMN "asset_video_comp"."media_duration" IS 'Duration in milliseconds';
COMMENT ON COLUMN "asset_video_comp"."fps" IS 'Frames per second, produced by the quality node';
COMMENT ON COLUMN "asset_video_comp"."frame_count" IS 'Total frame count, produced by the quality node';
COMMENT ON COLUMN "asset_video_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';

-- ---------------------------------------------------------------------------
-- Audio component
-- ---------------------------------------------------------------------------
CREATE TABLE "asset_audio_comp" (
    "uuid"                uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"          uuid NOT NULL,

    "node_kind"           varchar NOT NULL,
    "node_id"             varchar,
    "producer_version"    varchar NOT NULL DEFAULT '',
    "run_uuid"            uuid,
    "task_uuid"           uuid,
    "confidence"          real,

    "stream_index"        int NOT NULL DEFAULT 0,
    "lang"                varchar,
    "track_title"         varchar,
    "is_default"          boolean,
    "audio_bpm"           int,
    "audio_sampling_rate" int,
    "audio_channels"      int,
    "audio_bitrate"       int,
    "audio_encoding"      varchar,
    "media_duration"      bigint,

    "meta"                jsonb,
    "created"             timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"        uuid,
    "edited"              timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"         uuid,

    CONSTRAINT "asset_audio_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_audio_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_audio_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_audio_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_audio_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_audio_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_audio_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "stream_index")
);

CREATE INDEX "idx_asset_audio_comp_asset_uuid" ON "asset_audio_comp" ("asset_uuid");

COMMENT ON TABLE "asset_audio_comp" IS 'Audio track properties. One row per track: a video with a German and an English track has two, discriminated by stream_index.';
COMMENT ON COLUMN "asset_audio_comp"."stream_index" IS 'Which audio track within the container';
COMMENT ON COLUMN "asset_audio_comp"."lang" IS 'Track language as declared by the container';
COMMENT ON COLUMN "asset_audio_comp"."track_title" IS 'Track title as declared by the container';
COMMENT ON COLUMN "asset_audio_comp"."is_default" IS 'Whether the container marks this as the default track';
COMMENT ON COLUMN "asset_audio_comp"."audio_encoding" IS 'Audio encoding used (e.g. mp3, flac)';
COMMENT ON COLUMN "asset_audio_comp"."media_duration" IS 'Duration in milliseconds';
COMMENT ON COLUMN "asset_audio_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';
