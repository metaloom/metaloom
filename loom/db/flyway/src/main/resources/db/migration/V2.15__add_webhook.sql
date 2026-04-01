
CREATE TABLE "webhook" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "url" varchar NOT NULL,
  "status" varchar,
  "active" boolean NOT NULL DEFAULT true,
  "triggers" loom_events,
  "secretToken" varchar,
  "meta" jsonb,
  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);

ALTER TABLE "webhook" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "webhook" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

COMMENT ON TABLE "webhook" IS 'Table which stores the registered webhooks';
COMMENT ON COLUMN "webhook"."triggers" IS 'List of triggers which can invoke the webhook';
COMMENT ON COLUMN "webhook"."secretToken" IS 'Secret token which webhook services can use to authenticate the request.';
COMMENT ON COLUMN "webhook"."meta" IS 'Custom meta properties to the element';
