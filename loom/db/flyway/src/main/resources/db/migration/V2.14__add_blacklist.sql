CREATE TABLE "blacklist" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "asset_uuid" uuid NOT NULL,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  "type" varchar,
  "review_count" int DEFAULT 1,
  "meta" jsonb,
  PRIMARY KEY ("uuid")
);
CREATE UNIQUE INDEX ON "blacklist" ("asset_uuid", "creator_uuid");
ALTER TABLE "blacklist" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
ALTER TABLE "blacklist" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "blacklist" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

COMMENT ON TABLE "blacklist" IS 'Stores information on blocked binaries.
A asset can be blocked due to copyright claim issues or because the virus scanner marked it.';
COMMENT ON COLUMN "blacklist"."asset_uuid" IS 'Blacklisted asset';
COMMENT ON COLUMN "blacklist"."created" IS 'Creation timestamp';
COMMENT ON COLUMN "blacklist"."creator_uuid" IS 'Creator of the blacklist entry';
COMMENT ON COLUMN "blacklist"."type" IS 'Type of the blacklist entry (e.g. copyright claim)';
COMMENT ON COLUMN "blacklist"."review_count" IS 'Amount of times this blacklist entry has been reviewed.';
COMMENT ON COLUMN "blacklist"."meta" IS 'Custom meta properties of the block entry. May contain additional information on the reason.';

