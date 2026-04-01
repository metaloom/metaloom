CREATE TABLE "asset_location" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "asset_uuid" uuid NOT NULL,
  "library_uuid" uuid NOT NULL,
  "path" varchar NOT NULL, /* unclear */
  "filekey_inode" int,
  "filekey_stdev" int,
  "filekey_edate_nano" int,
  "filekey_edate" int,
  "meta" jsonb,
  "mime_type" varchar,
  "license" varchar, /* unclear */
  "state" varchar, /* unclear */
  "locked_by_uuid" uuid,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "asset_location" ("path");

ALTER TABLE "asset_location" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_location" ADD FOREIGN KEY ("library_uuid") REFERENCES "library" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_location" ADD FOREIGN KEY ("locked_by_uuid") REFERENCES "user" ("uuid");

ALTER TABLE "asset_location" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_location" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

COMMENT ON TABLE "asset_location" IS 'Assets keep track of media that has been found by the scanner. Multiple asset_locations may share the same asset thus the properties will be decoupled from asset.';
COMMENT ON COLUMN "asset_location"."asset_uuid" IS 'Reference to the media asset for the asset_location.';
COMMENT ON COLUMN "asset_location"."path" IS 'Currently known path to the asset_location in the filesystem';
COMMENT ON COLUMN "asset_location"."meta" IS 'Custom meta properties to the asset_location';
