CREATE TYPE "attachment_type" AS ENUM (
  'ASSET_THUMBNAIL',
  'EMBEDDING_ATTACHMENT'
);

CREATE TABLE "attachment_binary" (
  "sha512sum" varchar NOT NULL,
  "size" bigint NOT NULL,

   PRIMARY KEY ("sha512sum")
);
CREATE UNIQUE INDEX ON "attachment_binary" ("sha512sum");

CREATE TABLE "attachment" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "binary_sha512sum" varchar NOT NULL,

  "embedding_uuid" uuid,
  "asset_uuid" uuid,

  "mime_type" varchar NOT NULL,
  "type" attachment_type NOT NULL,
  "filename" varchar NOT NULL,
  "meta" jsonb,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
CREATE UNIQUE INDEX ON "attachment" ("uuid");
ALTER TABLE "attachment" ADD FOREIGN KEY ("binary_sha512sum") REFERENCES "attachment_binary" ("sha512sum");
ALTER TABLE "attachment" ADD FOREIGN KEY ("embedding_uuid") REFERENCES "embedding" ("uuid");
ALTER TABLE "attachment" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid");

ALTER TABLE "attachment" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "attachment" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");