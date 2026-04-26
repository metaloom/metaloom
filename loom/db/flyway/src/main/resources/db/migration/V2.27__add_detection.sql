CREATE TABLE "detection" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "type" varchar NOT NULL,
  "frame_number" int NOT NULL DEFAULT 0,
  "bbox_x" real NOT NULL DEFAULT 0,
  "bbox_y" real NOT NULL DEFAULT 0,
  "bbox_width" real NOT NULL DEFAULT 0,
  "bbox_height" real NOT NULL DEFAULT 0,
  "confidence" real NOT NULL DEFAULT 0,
  "meta" jsonb,

  "asset_uuid" uuid NOT NULL,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);

CREATE INDEX ON "detection" ("asset_uuid");
CREATE INDEX ON "detection" ("type");
ALTER TABLE "detection" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid");
ALTER TABLE "detection" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "detection" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

COMMENT ON TABLE "detection" IS 'Stores object and face detections within assets';
COMMENT ON COLUMN "detection"."type" IS 'Type of detection (e.g. facedetection, objectdetection)';
COMMENT ON COLUMN "detection"."frame_number" IS 'Frame index within the media (0 for images)';
COMMENT ON COLUMN "detection"."bbox_x" IS 'Bounding box X coordinate (normalized 0-1)';
COMMENT ON COLUMN "detection"."bbox_y" IS 'Bounding box Y coordinate (normalized 0-1)';
COMMENT ON COLUMN "detection"."bbox_width" IS 'Bounding box width (normalized 0-1)';
COMMENT ON COLUMN "detection"."bbox_height" IS 'Bounding box height (normalized 0-1)';
COMMENT ON COLUMN "detection"."confidence" IS 'Detection confidence score (0.0 - 1.0)';
COMMENT ON COLUMN "detection"."meta" IS 'Custom meta properties (e.g. gender, age, label, face angle)';
COMMENT ON COLUMN "detection"."asset_uuid" IS 'UUID of the parent asset';

-- Add detection permissions
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'CREATE_DETECTION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_DETECTION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'DELETE_DETECTION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'UPDATE_DETECTION';
