CREATE TABLE "library" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar NOT NULL,
  "meta" jsonb,
  "description" varchar,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
COMMENT ON COLUMN "library"."meta" IS 'Custom meta properties to the element';
ALTER TABLE "library" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "library" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

CREATE TABLE "library_asset" (
  "library_uuid" uuid NOT NULL,
  "asset_uuid" uuid NOT NULL,
  PRIMARY KEY ("library_uuid", "asset_uuid")
);
ALTER TABLE "library_asset" ADD FOREIGN KEY ("library_uuid") REFERENCES "library" ("uuid");
ALTER TABLE "library_asset" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid");

CREATE TABLE "library_collection" (
  "library_uuid" uuid NOT NULL,
  "collection_uuid" uuid NOT NULL,
  PRIMARY KEY ("library_uuid", "collection_uuid")
);
ALTER TABLE "library_collection" ADD FOREIGN KEY ("library_uuid") REFERENCES "library" ("uuid");
ALTER TABLE "library_collection" ADD FOREIGN KEY ("collection_uuid") REFERENCES "collection" ("uuid");

