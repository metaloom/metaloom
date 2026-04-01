-- Migration: Extract component data from the asset table into dedicated component tables.
-- Each component table supports multiple entries per asset, with a "source" field
-- to indicate the origin of the component (e.g. "exif", "audio track german", "thumbnail").

-- Geo Component
CREATE TABLE "asset_geo_comp" (
  "uuid" uuid DEFAULT uuid_generate_v4(),
  "asset_uuid" uuid NOT NULL,
  "source" varchar,
  "geo_lon" decimal(9,6),
  "geo_lat" decimal(8,6),
  "geo_alias" varchar,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "asset_geo_comp" ("asset_uuid");
CREATE INDEX ON "asset_geo_comp" ("geo_lon", "geo_lat");
ALTER TABLE "asset_geo_comp" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_geo_comp" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_geo_comp" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_geo_comp" IS 'Stores geo location information extracted from an asset';
COMMENT ON COLUMN "asset_geo_comp"."source" IS 'Name of the source for the geo info - e.g. exif data';

-- Doc Component
CREATE TABLE "asset_doc_comp" (
  "uuid" uuid DEFAULT uuid_generate_v4(),
  "asset_uuid" uuid NOT NULL,
  "source" varchar,
  "doc_plain_text" text,
  "doc_word_count" int,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "asset_doc_comp" ("asset_uuid");
ALTER TABLE "asset_doc_comp" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_doc_comp" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_doc_comp" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_doc_comp" IS 'Stores document/text extraction data from an asset';
COMMENT ON COLUMN "asset_doc_comp"."doc_plain_text" IS 'Extracted text of the document';

-- Image Component
CREATE TABLE "asset_image_comp" (
  "uuid" uuid DEFAULT uuid_generate_v4(),
  "asset_uuid" uuid NOT NULL,
  "source" varchar,
  "image_dominant_color" varchar,
  "media_width" int,
  "media_height" int,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "asset_image_comp" ("asset_uuid");
ALTER TABLE "asset_image_comp" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_image_comp" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_image_comp" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_image_comp" IS 'Stores image-specific properties extracted from an asset';

-- Video Component
CREATE TABLE "asset_video_comp" (
  "uuid" uuid DEFAULT uuid_generate_v4(),
  "asset_uuid" uuid NOT NULL,
  "source" varchar,
  "media_width" int,
  "media_height" int,
  "media_duration" bigint,
  "video_bitrate" int,
  "video_encoding" varchar,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "asset_video_comp" ("asset_uuid");
ALTER TABLE "asset_video_comp" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_video_comp" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_video_comp" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_video_comp" IS 'Stores video-specific properties extracted from an asset';

-- Audio Component
CREATE TABLE "asset_audio_comp" (
  "uuid" uuid DEFAULT uuid_generate_v4(),
  "asset_uuid" uuid NOT NULL,
  "source" varchar,
  "audio_bpm" int,
  "audio_sampling_rate" int,
  "audio_channels" int,
  "audio_bitrate" int,
  "audio_encoding" varchar,
  "media_duration" bigint,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "asset_audio_comp" ("asset_uuid");
ALTER TABLE "asset_audio_comp" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_audio_comp" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_audio_comp" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_audio_comp" IS 'Stores audio-specific properties extracted from an asset';
COMMENT ON COLUMN "asset_audio_comp"."audio_encoding" IS 'Store the audio encoding used (e.g. mp3, flac)';

-- Transcript Component
CREATE TABLE "asset_transcript_comp" (
  "uuid" uuid DEFAULT uuid_generate_v4(),
  "asset_uuid" uuid NOT NULL,
  "source" varchar,
  "lang" varchar,
  "transcript_text" text,
  "duration" int,
  "model" varchar,
  "transcript_json" jsonb,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "asset_transcript_comp" ("asset_uuid");
CREATE INDEX ON "asset_transcript_comp" ("lang");
ALTER TABLE "asset_transcript_comp" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_transcript_comp" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_transcript_comp" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_transcript_comp" IS 'Stores transcript data for audio/video assets';
COMMENT ON COLUMN "asset_transcript_comp"."transcript_text" IS 'Full concatenated transcript';
COMMENT ON COLUMN "asset_transcript_comp"."model" IS 'e.g. whisper-1';
COMMENT ON COLUMN "asset_transcript_comp"."source" IS 'pipeline/source of transcription';
COMMENT ON COLUMN "asset_transcript_comp"."transcript_json" IS 'Full Whisper output including segments, tokens, etc.';

-- Migrate existing data from asset table into component tables
INSERT INTO "asset_geo_comp" ("asset_uuid", "source", "geo_lon", "geo_lat", "geo_alias", "creator_uuid", "editor_uuid")
  SELECT "uuid", 'migrated', "geo_lon", "geo_lat", "geo_alias", "creator_uuid", "editor_uuid"
  FROM "asset"
  WHERE "geo_lon" IS NOT NULL OR "geo_lat" IS NOT NULL OR "geo_alias" IS NOT NULL;

INSERT INTO "asset_doc_comp" ("asset_uuid", "source", "doc_plain_text", "doc_word_count", "creator_uuid", "editor_uuid")
  SELECT "uuid", 'migrated', "doc_plain_text", "doc_word_count", "creator_uuid", "editor_uuid"
  FROM "asset"
  WHERE "doc_plain_text" IS NOT NULL OR "doc_word_count" IS NOT NULL;

INSERT INTO "asset_image_comp" ("asset_uuid", "source", "image_dominant_color", "media_width", "media_height", "creator_uuid", "editor_uuid")
  SELECT "uuid", 'migrated', "image_dominant_color", "media_width", "media_height", "creator_uuid", "editor_uuid"
  FROM "asset"
  WHERE "image_dominant_color" IS NOT NULL OR "image_encoding" IS NOT NULL;

INSERT INTO "asset_video_comp" ("asset_uuid", "source", "media_width", "media_height", "media_duration", "video_bitrate", "video_encoding", "creator_uuid", "editor_uuid")
  SELECT "uuid", 'migrated', "video_width", "video_height", "media_duration", "video_bitrate", "video_encoding", "creator_uuid", "editor_uuid"
  FROM "asset"
  WHERE "video_encoding" IS NOT NULL OR "video_bitrate" IS NOT NULL;

INSERT INTO "asset_audio_comp" ("asset_uuid", "source", "audio_bpm", "audio_sampling_rate", "audio_channels", "audio_bitrate", "audio_encoding", "media_duration", "creator_uuid", "editor_uuid")
  SELECT "uuid", 'migrated', "audio_bpm", "audio_sampling_rate", "audio_channels", "audio_bitrate", "audio_encoding", "media_duration", "creator_uuid", "editor_uuid"
  FROM "asset"
  WHERE "audio_encoding" IS NOT NULL OR "audio_bpm" IS NOT NULL OR "audio_channels" IS NOT NULL;

-- Drop old columns from asset table
ALTER TABLE "asset" DROP COLUMN IF EXISTS "geo_lon";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "geo_lat";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "geo_alias";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "media_width";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "media_height";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "media_duration";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "video_width";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "video_height";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "video_bitrate";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "video_encoding";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "image_dominant_color";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "image_encoding";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "audio_bpm";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "audio_sampling_rate";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "audio_channels";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "audio_bitrate";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "audio_encoding";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "doc_plain_text";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "doc_word_count";
