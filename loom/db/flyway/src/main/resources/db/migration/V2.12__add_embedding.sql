CREATE TABLE "embedding" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "meta" jsonb,
  "source" varchar,

  "fromTime" int,
  "toTime" int,
  "areaHeight" int,
  "areaWidth" int,
  "areaStartX" int,
  "areaStartY" int,

  "vector" real[] NOT NULL,
  "type" varchar NOT NULL,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  "asset_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "embedding" ("asset_uuid");
ALTER TABLE "embedding" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "embedding" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "embedding" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
COMMENT ON TABLE "embedding" IS 'Embedding information which was extracted from an asset.';
COMMENT ON COLUMN "embedding"."meta" IS 'Custom meta properties to the embedding.';
COMMENT ON COLUMN "embedding"."source" IS 'Additional source information (e.g. face number by dlib)';
COMMENT ON COLUMN "embedding"."areaHeight" IS 'Area info where the face has been detected.';
COMMENT ON COLUMN "embedding"."areaWidth" IS 'Area info where the face has been detected.';
COMMENT ON COLUMN "embedding"."areaStartX" IS 'Area info where the face has been detected.';
COMMENT ON COLUMN "embedding"."areaStartY" IS 'Area info where the face has been detected.';
COMMENT ON COLUMN "embedding"."vector" IS 'Actual embedding vector data';
COMMENT ON COLUMN "embedding"."type" IS 'Type of the embedding (e.g. dlib_facemark)';

CREATE TABLE "cluster" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar NOT NULL,
  "meta" jsonb,
  "type" varchar NOT NULL,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
CREATE UNIQUE INDEX ON "cluster" ("name");
ALTER TABLE "cluster" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "cluster" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON COLUMN "cluster"."name" IS 'Name of the cluster. (e.g. name of a person)';
COMMENT ON COLUMN "cluster"."meta" IS 'Custom meta properties to the embedding.';
COMMENT ON COLUMN "cluster"."type" IS 'Type of the cluster (e.g. person)';
COMMENT ON TABLE "cluster" IS 'Generic cluster that aggregates multiple embeddings. 
A cluster could for example represent a person which can have multiple face embeddings.
Alternatively media fingerprint embeddings can be used to group media together by visual similarity.';


CREATE TABLE "tag_cluster" (
  "tag_uuid" uuid NOT NULL,
  "cluster_uuid" uuid NOT NULL,
  PRIMARY KEY ("tag_uuid", "cluster_uuid")
);
ALTER TABLE "tag_cluster" ADD FOREIGN KEY ("tag_uuid") REFERENCES "tag" ("uuid");
ALTER TABLE "tag_cluster" ADD FOREIGN KEY ("cluster_uuid") REFERENCES "cluster" ("uuid");
COMMENT ON TABLE "tag_cluster" IS 'Store tag <-> cluster reference';

CREATE TABLE "embedding_cluster" (
  "embedding_uuid" uuid NOT NULL,
  "cluster_uuid" uuid NOT NULL,
  PRIMARY KEY ("embedding_uuid", "cluster_uuid")
);
ALTER TABLE "embedding_cluster" ADD FOREIGN KEY ("embedding_uuid") REFERENCES "embedding" ("uuid");
ALTER TABLE "embedding_cluster" ADD FOREIGN KEY ("cluster_uuid") REFERENCES "cluster" ("uuid");
COMMENT ON TABLE "embedding_cluster" IS 'List embeddings for clusters';

ALTER TABLE "collection_cluster" ADD FOREIGN KEY ("collection_uuid") REFERENCES "collection" ("uuid") ON DELETE CASCADE;
ALTER TABLE "collection_cluster" ADD FOREIGN KEY ("cluster_uuid") REFERENCES "cluster" ("uuid") ON DELETE CASCADE;
