-- JSON Component (generic structured data from Cortex processing nodes)
CREATE TABLE "asset_json_comp" (
  "uuid" uuid DEFAULT uuid_generate_v4(),
  "asset_uuid" uuid NOT NULL,
  "source" varchar,
  "schema_type" varchar,
  "data" jsonb,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);
CREATE INDEX ON "asset_json_comp" ("asset_uuid");
CREATE INDEX ON "asset_json_comp" ("schema_type");
ALTER TABLE "asset_json_comp" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "asset_json_comp" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_json_comp" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON TABLE "asset_json_comp" IS 'Stores generic JSON component data produced by Cortex processing nodes';
COMMENT ON COLUMN "asset_json_comp"."source" IS 'Name of the source node that produced this data';
COMMENT ON COLUMN "asset_json_comp"."schema_type" IS 'Optional schema type label for the JSON data (e.g. yolo-detection, face-embedding)';
