CREATE TABLE "tag" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar NOT NULL,
  "collection" varchar NOT NULL,

  "meta" jsonb,
  "rating" int,
  "color" char(6),

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
CREATE UNIQUE INDEX ON "tag" ("name", "collection");
ALTER TABLE "tag" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "tag" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON COLUMN "tag"."meta" IS 'Custom meta properties to the element';
COMMENT ON COLUMN "tag"."rating" IS 'Absolute or buffered/precomputed rating information';
COMMENT ON TABLE "tag" IS 'Tag on various elements. Tags are not user specifc';

CREATE TABLE "tag_user_meta" (
  "tag_uuid" uuid NOT NULL,
  "user_uuid" uuid NOT NULL,
  "rating" int NOT NULL,
  "meta" jsonb,
  PRIMARY KEY ("tag_uuid", "user_uuid")
);
ALTER TABLE "tag_user_meta" ADD FOREIGN KEY ("tag_uuid") REFERENCES "tag" ("uuid");
ALTER TABLE "tag_user_meta" ADD FOREIGN KEY ("user_uuid") REFERENCES "user" ("uuid");
COMMENT ON COLUMN "tag_user_meta"."rating" IS 'Rating of the tag by the user';
COMMENT ON COLUMN "tag_user_meta"."meta" IS 'Custom meta properties';

