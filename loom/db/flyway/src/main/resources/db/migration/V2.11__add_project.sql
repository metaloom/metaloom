CREATE TABLE "project" (
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

CREATE TABLE "project_library" (
  "project_uuid" uuid NOT NULL,
  "library_uuid" uuid NOT NULL,
  PRIMARY KEY ("project_uuid", "library_uuid")
);

CREATE TABLE "project_collection" (
  "project_uuid" uuid NOT NULL,
  "collection_uuid" uuid NOT NULL,
  PRIMARY KEY ("project_uuid", "collection_uuid")
);

COMMENT ON COLUMN "project"."meta" IS 'Custom meta properties to the element';

ALTER TABLE "project" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "project" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "project_library" ADD FOREIGN KEY ("project_uuid") REFERENCES "project" ("uuid");
ALTER TABLE "project_library" ADD FOREIGN KEY ("library_uuid") REFERENCES "library" ("uuid");
ALTER TABLE "project_collection" ADD FOREIGN KEY ("project_uuid") REFERENCES "project" ("uuid");
ALTER TABLE "project_collection" ADD FOREIGN KEY ("collection_uuid") REFERENCES "collection" ("uuid");