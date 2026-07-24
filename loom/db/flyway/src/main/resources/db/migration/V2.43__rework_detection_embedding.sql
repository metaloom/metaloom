-- Rework detection and embedding.
--
-- A face detection and the embedding computed from that same region are the canonical
-- pair, and there was no foreign key between them. Both carried an independent copy of
-- the geometry in different units and conventions - detection in normalized real
-- bounding boxes, embedding in absolute int areaStartX/areaStartY - so correlating them
-- required floating point matching plus the asset's pixel dimensions.
--
-- Neither table had provenance or an idempotency key, so re-running the face detection
-- node on a video appended a complete second set of detections for every frame.
--
-- DESTRUCTIVE rewrite - see V2.38 for the shared component contract.
-- embedding_cluster and attachment.embedding_uuid reference embedding, so their foreign
-- keys are dropped by the CASCADE and recreated at the end of this migration.

DROP TABLE IF EXISTS "embedding" CASCADE;
DROP TABLE IF EXISTS "detection" CASCADE;

-- ---------------------------------------------------------------------------
-- Detection
-- ---------------------------------------------------------------------------
CREATE TABLE "detection" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,

    "type"             varchar NOT NULL,
    "label"            varchar,
    "frame_number"     int NOT NULL DEFAULT 0,
    "detection_index"  int NOT NULL DEFAULT 0,
    "time_from"        bigint,

    "bbox_x"           real NOT NULL DEFAULT 0,
    "bbox_y"           real NOT NULL DEFAULT 0,
    "bbox_width"       real NOT NULL DEFAULT 0,
    "bbox_height"      real NOT NULL DEFAULT 0,
    "confidence"       real NOT NULL DEFAULT 0,

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "detection_pkey" PRIMARY KEY ("uuid"),
    -- V2.27 omitted the cascade, which made deleting an asset with detections impossible.
    CONSTRAINT "detection_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "detection_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "detection_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "detection_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "detection_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "detection_unique_key" UNIQUE ("asset_uuid", "node_kind", "frame_number", "detection_index")
);

CREATE INDEX "idx_detection_asset_uuid" ON "detection" ("asset_uuid");
CREATE INDEX "idx_detection_type" ON "detection" ("type");
CREATE INDEX "idx_detection_label" ON "detection" ("label");

COMMENT ON TABLE "detection" IS 'Object and face detections within assets. One row per detected instance, keyed by (asset, producer, frame, ordinal) so a re-run replaces rather than duplicates.';
COMMENT ON COLUMN "detection"."type" IS 'Kind of detection, e.g. facedetection, objectdetection';
COMMENT ON COLUMN "detection"."label" IS 'Detected class for object detection, e.g. dog. Promoted out of meta so it can be indexed.';
COMMENT ON COLUMN "detection"."frame_number" IS 'Frame index within the media; 0 for images';
COMMENT ON COLUMN "detection"."detection_index" IS 'Ordinal of this detection within the frame, starting at 0';
COMMENT ON COLUMN "detection"."time_from" IS 'Millisecond offset of the frame, for video';
COMMENT ON COLUMN "detection"."bbox_x" IS 'Bounding box X, normalized 0-1. This is the single geometry convention; embedding no longer carries a second one.';
COMMENT ON COLUMN "detection"."confidence" IS 'Detection confidence, 0.0 - 1.0';
COMMENT ON COLUMN "detection"."meta" IS 'Custom meta properties (e.g. gender, age, face angle)';
COMMENT ON COLUMN "detection"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';

-- ---------------------------------------------------------------------------
-- Embedding
-- ---------------------------------------------------------------------------
CREATE TABLE "embedding" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "confidence"       real,

    "type"             varchar NOT NULL,
    "model"            varchar,
    "dimensions"       int NOT NULL,
    "vector"           real[] NOT NULL,

    "detection_uuid"   uuid,
    "frame_number"     int NOT NULL DEFAULT 0,
    "subject_index"    int NOT NULL DEFAULT 0,
    "time_from"        bigint,
    "time_to"          bigint,

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "embedding_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "embedding_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "embedding_detection_uuid_fkey" FOREIGN KEY ("detection_uuid") REFERENCES "detection" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "embedding_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "embedding_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "embedding_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "embedding_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "embedding_unique_key" UNIQUE ("asset_uuid", "node_kind", "type", "frame_number", "subject_index"),
    CONSTRAINT "embedding_range_check" CHECK ("time_to" IS NULL OR "time_from" IS NULL OR "time_to" >= "time_from")
);

CREATE INDEX "idx_embedding_asset_uuid" ON "embedding" ("asset_uuid");
CREATE INDEX "idx_embedding_detection_uuid" ON "embedding" ("detection_uuid");

COMMENT ON TABLE "embedding" IS 'Embedding vectors extracted from an asset. The geometry lives on the linked detection - this table no longer carries a second, absolute-pixel copy of it.';
COMMENT ON COLUMN "embedding"."type" IS 'Type of the embedding, e.g. dlib_facemark, inspireface';
COMMENT ON COLUMN "embedding"."model" IS 'Readable mirror of producer_version';
COMMENT ON COLUMN "embedding"."dimensions" IS 'Length of the vector. Guards against comparing vectors from different models.';
COMMENT ON COLUMN "embedding"."vector" IS 'Embedding vector as a plain PostgreSQL array. OPEN DECISION: similarity search is either pgvector in Postgres or an external index fed via vector_config. Until that is decided this column is a staging buffer with no ANN index - see spec/features/DB_SCHEMA_FEEDBACK.md section 4.2.';
COMMENT ON COLUMN "embedding"."detection_uuid" IS 'The detection this vector was computed from, when there is one. Whole-image and audio-window embeddings leave it NULL.';
COMMENT ON COLUMN "embedding"."subject_index" IS 'Ordinal of the subject within the frame, used when there is no detection row to key on';
COMMENT ON COLUMN "embedding"."time_from" IS 'Start of the window this embedding covers, in milliseconds (audio/video)';
COMMENT ON COLUMN "embedding"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';

-- ---------------------------------------------------------------------------
-- Recreate the foreign keys the CASCADE dropped
-- ---------------------------------------------------------------------------
ALTER TABLE "embedding_cluster"
    ADD CONSTRAINT "embedding_cluster_embedding_uuid_fkey"
    FOREIGN KEY ("embedding_uuid") REFERENCES "embedding" ("uuid") ON DELETE CASCADE;

ALTER TABLE "attachment"
    ADD CONSTRAINT "attachment_embedding_uuid_fkey"
    FOREIGN KEY ("embedding_uuid") REFERENCES "embedding" ("uuid") ON DELETE CASCADE;
