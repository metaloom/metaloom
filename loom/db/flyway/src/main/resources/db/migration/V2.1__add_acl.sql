CREATE TYPE "loom_permission" AS ENUM (
  /* Annotation */
  'CREATE_ANNOTATION','READ_ANNOTATION','DELETE_ANNOTATION','UPDATE_ANNOTATION',

  /* Asset */
  'CREATE_ASSET','READ_ASSET','DELETE_ASSET','UPDATE_ASSET',

  /* Asset Location */
  'CREATE_ASSET_LOCATION','READ_ASSET_LOCATION','DELETE_ASSET_LOCATION','UPDATE_ASSET_LOCATION',

  /* Attachment */
  'CREATE_ATTACHMENT','READ_ATTACHMENT','DELETE_ATTACHMENT','UPDATE_ATTACHMENT',

  /* User */
  'CREATE_USER','READ_USER','DELETE_USER','UPDATE_USER',

  /* Role */
  'CREATE_ROLE','READ_ROLE','DELETE_ROLE','UPDATE_ROLE',

  /* Group */
  'CREATE_GROUP','READ_GROUP','DELETE_GROUP','UPDATE_GROUP',

  /* Project */
  'CREATE_PROJECT','READ_PROJECT','DELETE_PROJECT','UPDATE_PROJECT',

  /* Cluster */
  'CREATE_CLUSTER','READ_CLUSTER','DELETE_CLUSTER','UPDATE_CLUSTER',

  /* Collection */
  'CREATE_COLLECTION','READ_COLLECTION','DELETE_COLLECTION','UPDATE_COLLECTION',

  /* Comment */
  'CREATE_COMMENT','READ_COMMENT','DELETE_COMMENT','UPDATE_COMMENT',

  /* Embedding */
  'CREATE_EMBEDDING','READ_EMBEDDING','DELETE_EMBEDDING','UPDATE_EMBEDDING',

  /* Reaction */
  'CREATE_REACTION','READ_REACTION','DELETE_REACTION','UPDATE_REACTION',

  /* Task */
  'CREATE_TASK','READ_TASK','DELETE_TASK','UPDATE_TASK',

  /* Tag */
  'CREATE_TAG','READ_TAG','DELETE_TAG','UPDATE_TAG',

  /* Tag + Asset */
  'TAG_ASSET','UNTAG_ASSET',

  /* Token */
  'CREATE_TOKEN','READ_TOKEN','DELETE_TOKEN','UPDATE_TOKEN',

  /* WebHook */
  'CREATE_WEBHOOK','READ_WEBHOOK','DELETE_WEBHOOK','UPDATE_WEBHOOK',

  /* Library */
  'CREATE_LIBRARY','READ_LIBRARY','DELETE_LIBRARY','UPDATE_LIBRARY'
);

CREATE TABLE "user" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "username" varchar UNIQUE NOT NULL,
  "firstname" varchar,
  "lastname" varchar,
  "email" varchar,
  "enabled" boolean NOT NULL DEFAULT true,
  "deleted" boolean NOT NULL DEFAULT false,
  "sso" boolean NOT NULL DEFAULT false,
  "password_hash" varchar,
  "meta" jsonb,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
CREATE UNIQUE INDEX ON "user" ("username");
ALTER TABLE "user" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "user" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON COLUMN "user"."enabled" IS 'Flag to enable or disable the user.';
COMMENT ON COLUMN "user"."sso" IS 'Flag that indicates that the user was created via SSO mappings';
COMMENT ON COLUMN "user"."meta" IS 'Custom meta properties to the element';

CREATE TABLE "token" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar,

  "description" varchar,
  "token" varchar NOT NULL,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  "meta" jsonb,
  PRIMARY KEY ("uuid")
);
CREATE UNIQUE INDEX ON "token" ("token");
CREATE UNIQUE INDEX ON "token" ("creator_uuid", "name");
ALTER TABLE "token" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid") ON DELETE CASCADE;
CREATE INDEX ON "token" ("creator_uuid");
COMMENT ON COLUMN "token"."meta" IS 'Custom meta properties to the element';

CREATE TABLE "role" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar UNIQUE NOT NULL,
  "meta" jsonb,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
CREATE UNIQUE INDEX ON "role" ("name");
ALTER TABLE "role" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "role" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON COLUMN "role"."meta" IS 'Custom meta properties to the element';

CREATE TABLE "role_permission" (
  "role_uuid" uuid,
  "resource" varchar NOT NULL,
  "permission" loom_permission NOT NULL,
  PRIMARY KEY ("role_uuid", "permission")
);
ALTER TABLE "role_permission" ADD FOREIGN KEY ("role_uuid") REFERENCES "role" ("uuid") ON DELETE CASCADE;
CREATE UNIQUE INDEX ON "role_permission" ("role_uuid", "resource", "permission");
COMMENT ON COLUMN "role_permission"."permission" IS 'Permission granted / granted to the resource';

CREATE TABLE "user_permission" (
  "user_uuid" uuid,
  "resource" varchar NOT NULL,
  "permission" loom_permission NOT NULL,
  PRIMARY KEY ("user_uuid")
);
CREATE UNIQUE INDEX ON "user_permission" ("user_uuid", "resource", "permission");
ALTER TABLE "user_permission" ADD FOREIGN KEY ("user_uuid") REFERENCES "user" ("uuid") ON DELETE CASCADE;
COMMENT ON COLUMN "user_permission"."permission" IS 'Permission granted / granted to the resource';

CREATE TABLE "token_permission" (
  "token_uuid" uuid,
  "resource" varchar NOT NULL,
  "permission" loom_permission NOT NULL,
  PRIMARY KEY ("token_uuid")
);
CREATE UNIQUE INDEX ON "token_permission" ("token_uuid", "resource", "permission");
ALTER TABLE "token_permission" ADD FOREIGN KEY ("token_uuid") REFERENCES "token" ("uuid");
COMMENT ON COLUMN "token_permission"."permission" IS 'Permission granted / granted to the resource';

CREATE TABLE "group" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar UNIQUE NOT NULL,
  "meta" jsonb,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
ALTER TABLE "group" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "group" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
COMMENT ON COLUMN "group"."meta" IS 'Custom meta properties to the element';
CREATE UNIQUE INDEX ON "group" ("name");

CREATE TABLE "role_group" (
  "group_uuid" uuid NOT NULL,
  "role_uuid" uuid NOT NULL,
  PRIMARY KEY ("group_uuid", "role_uuid")
);
ALTER TABLE "role_group" ADD FOREIGN KEY ("group_uuid") REFERENCES "group" ("uuid") ON DELETE CASCADE;
ALTER TABLE "role_group" ADD FOREIGN KEY ("role_uuid") REFERENCES "role" ("uuid") ON DELETE CASCADE;


CREATE TABLE "user_group" (
  "user_uuid" uuid NOT NULL,
  "group_uuid" uuid NOT NULL,
  PRIMARY KEY ("user_uuid", "group_uuid")
);
ALTER TABLE "user_group" ADD FOREIGN KEY ("user_uuid") REFERENCES "user" ("uuid") ON DELETE CASCADE;
ALTER TABLE "user_group" ADD FOREIGN KEY ("group_uuid") REFERENCES "group" ("uuid") ON DELETE CASCADE;

