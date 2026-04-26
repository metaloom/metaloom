CREATE TABLE "person" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "alias" varchar,
  "firstname" varchar,
  "lastname" varchar,
  "meta" jsonb,
  "primary_image_uuid" uuid,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
COMMENT ON COLUMN "person"."alias" IS 'Display alias for the person';
COMMENT ON COLUMN "person"."firstname" IS 'First name';
COMMENT ON COLUMN "person"."lastname" IS 'Last name';
COMMENT ON COLUMN "person"."meta" IS 'Custom meta properties';
COMMENT ON COLUMN "person"."primary_image_uuid" IS 'UUID of the primary gallery image';
ALTER TABLE "person" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "person" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

CREATE TABLE "person_image" (
  "person_uuid" uuid NOT NULL,
  "asset_uuid" uuid NOT NULL,
  PRIMARY KEY ("person_uuid", "asset_uuid")
);
ALTER TABLE "person_image" ADD FOREIGN KEY ("person_uuid") REFERENCES "person" ("uuid") ON DELETE CASCADE;
ALTER TABLE "person_image" ADD FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;
COMMENT ON TABLE "person_image" IS 'Gallery of pictures associated with a person';

ALTER TABLE "person" ADD FOREIGN KEY ("primary_image_uuid") REFERENCES "asset" ("uuid") ON DELETE SET NULL;
