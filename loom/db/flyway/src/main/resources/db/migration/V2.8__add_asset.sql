CREATE TABLE "asset" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "sha512sum" varchar NOT NULL,
  "size" bigint NOT NULL,

  "sha256sum" varchar,
  "md5sum" varchar,
  "chunk_hash" varchar,
  "zero_chunk_count" bigint,
  "mime_type" varchar NOT NULL,
  "filename" varchar NOT NULL,
  "initial_origin" varchar NOT NULL,
  "first_seen" timestamp NOT NULL DEFAULT (now()),
  "meta" jsonb,

  "geo_lon" decimal(9,6),
  "geo_lat" decimal(8,6),
  "geo_alias" varchar,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  "s3_bucket_name" varchar,
  "s3_object_path" varchar,

  "media_width" int,
  "media_height" int,
  "media_duration" int,

  "video_width" int,
  "video_height" int,
  "video_bitrate" int,
  "video_encoding" varchar,

  "image_dominant_color" varchar,
  "image_encoding" varchar,

  "audio_bpm" int,
  "audio_sampling_rate" int,
  "audio_channels" int,
  "audio_bitrate"  int,
  "audio_encoding" varchar,

  "doc_plain_text" varchar,
  "doc_word_count" int,
  PRIMARY KEY ("sha512sum")
);
CREATE UNIQUE INDEX ON "asset" ("uuid");
CREATE INDEX ON "asset" ("geo_lon", "geo_lat");
ALTER TABLE "asset" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

COMMENT ON TABLE "asset" IS 'This table stores information on the asset component of the asset';
COMMENT ON COLUMN "asset"."meta" IS 'Custom meta properties to the asset';
COMMENT ON COLUMN "asset"."initial_origin" IS 'Document the initial origin of the asset (e.g. first filepath encountered, first s3 path, url, hash)';
COMMENT ON COLUMN "asset"."media_width" IS 'Only set for images, video';
COMMENT ON COLUMN "asset"."media_height" IS 'Only set for images, video';
COMMENT ON COLUMN "asset"."media_duration" IS 'Duration of the video, audio';
COMMENT ON COLUMN "asset"."audio_encoding" IS 'Store the audio encoding used for the asset (e.g. mp3, flac)';
COMMENT ON COLUMN "asset"."doc_plain_text" IS 'Extracted text of the document';


CREATE TABLE "asset_remix" (
  "asset_a_uuid" uuid NOT NULL,
  "asset_b_uuid" uuid NOT NULL,
  "meta" jsonb,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL
);
CREATE INDEX ON "asset_remix" ("asset_a_uuid");
CREATE INDEX ON "asset_remix" ("asset_b_uuid");
ALTER TABLE "asset_remix" ADD FOREIGN KEY ("asset_a_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_remix" ADD FOREIGN KEY ("asset_b_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_remix" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_remix" IS 'Store information on remixes of binaries.';
COMMENT ON COLUMN "asset_remix"."meta" IS 'Custom meta properties to the element';


CREATE TABLE "collection_asset" (
  "collection_uuid" uuid NOT NULL,
  "asset_uuid" uuid NOT NULL,
  PRIMARY KEY ("collection_uuid", "asset_uuid")
);
ALTER TABLE "collection_asset" ADD FOREIGN KEY ("collection_uuid") REFERENCES "collection" ("uuid");
ALTER TABLE "collection_asset" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid");
CREATE INDEX ON "collection_asset" ("collection_uuid");
CREATE INDEX ON "collection_asset" ("asset_uuid");
COMMENT ON TABLE "collection_asset" IS 'Track asset_locations that belong to a collection.';
COMMENT ON TABLE "collection_asset" IS 'Track assets that belong to a collection.';

CREATE TABLE "tag_asset" (
  "tag_uuid" uuid NOT NULL,
  "asset_uuid" uuid NOT NULL,

  "time_from" int,
  "time_to" int,
  "areaStartX" int,
  "areaStartY" int,
  "areaWidth" int,
  "areaHeight" int,

  PRIMARY KEY ("tag_uuid", "asset_uuid")
);
ALTER TABLE "tag_asset" ADD FOREIGN KEY ("tag_uuid") REFERENCES "tag" ("uuid");
ALTER TABLE "tag_asset" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid");
COMMENT ON TABLE "tag_asset" IS 'Store tag <-> asset reference';

CREATE TABLE "asset_user_meta" (
  "asset_uuid" uuid NOT NULL,
  "user_uuid" uuid NOT NULL,
  "meta" jsonb,
  PRIMARY KEY ("asset_uuid", "user_uuid")
);
ALTER TABLE "asset_user_meta" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid");
ALTER TABLE "asset_user_meta" ADD FOREIGN KEY ("user_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_user_meta" IS 'Stores user specific metadata that can be added to asset';
COMMENT ON COLUMN "asset_user_meta"."meta" IS 'Custom meta properties';

CREATE TABLE "asset_task" (
  "asset_uuid" uuid NOT NULL,
  "task_uuid" uuid NOT NULL,
  PRIMARY KEY ("asset_uuid", "task_uuid")
);
ALTER TABLE "asset_task" ADD FOREIGN KEY ("task_uuid") REFERENCES "task" ("uuid");
ALTER TABLE "asset_task" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid");
