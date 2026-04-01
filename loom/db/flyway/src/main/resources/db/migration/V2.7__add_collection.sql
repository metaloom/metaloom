
CREATE TABLE "collection" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar UNIQUE NOT NULL,
  "meta" jsonb,
  "description" varchar,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  "parent_collection_uuid" uuid,
  PRIMARY KEY ("uuid")
);
ALTER TABLE "collection" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "collection" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "collection" ADD FOREIGN KEY ("parent_collection_uuid") REFERENCES "collection" ("uuid");
ALTER TABLE "collection" ADD FOREIGN KEY ("parent_collection_uuid") REFERENCES "collection" ("uuid");
COMMENT ON TABLE "collection" IS 'Collections are used to group assets together.
          A collection may be a folder which groups together assets for a project.';
COMMENT ON COLUMN "collection"."meta" IS 'Custom meta properties';

CREATE TABLE "tag_collection" (
  "tag_uuid" uuid NOT NULL,
  "collection_uuid" uuid NOT NULL,
  PRIMARY KEY ("tag_uuid", "collection_uuid")
);
ALTER TABLE "tag_collection" ADD FOREIGN KEY ("tag_uuid") REFERENCES "tag" ("uuid");
ALTER TABLE "tag_collection" ADD FOREIGN KEY ("collection_uuid") REFERENCES "collection" ("uuid");
COMMENT ON TABLE "tag_collection" IS 'Store tag <-> collection reference';

CREATE TABLE "collection_cluster" (
  "collection_uuid" uuid NOT NULL,
  "cluster_uuid" uuid NOT NULL,
  PRIMARY KEY ("collection_uuid", "cluster_uuid")
);
CREATE INDEX ON "collection_cluster" ("collection_uuid");
CREATE INDEX ON "collection_cluster" ("cluster_uuid");