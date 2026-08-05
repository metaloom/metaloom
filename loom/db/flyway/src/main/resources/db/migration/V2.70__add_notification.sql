-- Tell a user that something happened to them.
--
-- Until now the only way Loom told anyone anything was a toast in the browser, which lives
-- in React state: it survives neither a reload nor being logged out, and it cannot reach
-- somebody who was not looking at the time. A task assigned to a group at 02:00 reached
-- nobody. This is the durable inbox behind that.
--
-- ONE ROW PER RECIPIENT, materialised at dispatch time.
--
-- The rejected alternative was a single row addressed to a group plus a notification_read
-- side table. Four reasons this shape wins:
--
--   1. The hot query. "Unread for user X, newest first" and the bell's unread COUNT are a
--      single partial-index scan here. The shared-row model makes every list a join through
--      user_group (which groups apply to me) plus an anti-join against the read table - on
--      every session and every popover open.
--   2. Read state is per-user by definition, so the shared-row model needs the side table
--      anyway. It pays for a join AND a second table without saving the row.
--   3. DELETE /notifications/:uuid is per-user dismissal. On a shared row that either nukes
--      it for everyone or needs a third `dismissed` column on the side table.
--   4. Membership semantics - and this is the real argument, not the cheap one. Someone who
--      joins a group tomorrow must NOT retroactively receive yesterday's notifications, and
--      someone who leaves must keep what they were already told. "You were told" is a
--      historical fact. The shared-row model gets both backwards, because it recomputes the
--      audience at read time.
--
-- Note this is the OPPOSITE choice to task_assignee (V2.69), which deliberately does not
-- snapshot group membership. The two are consistent: ownership is a live fact that should
-- follow the roster, whereas notification is a record of something that already happened.

ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_NOTIFICATION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'UPDATE_NOTIFICATION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'DELETE_NOTIFICATION';
-- No CREATE_NOTIFICATION: notifications are dispatched server-side and there is no REST
-- create route, so the constant would be dead the day it was added.

CREATE TABLE "notification" (
  "uuid"              uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "recipient_uuid"    uuid NOT NULL,
  "type"              varchar NOT NULL,
  "read"              boolean NOT NULL DEFAULT false,
  "read_at"           timestamp WITHOUT TIME ZONE,
  "title"             varchar NOT NULL,
  "body"              varchar,

  -- What this notification is about. At most one is set; it drives the deep link.
  "task_uuid"         uuid,
  "comment_uuid"      uuid,
  "pipeline_run_uuid" uuid,
  "asset_uuid"        uuid,

  -- Why it reached this recipient, when it came via a group rather than by name. Purely
  -- explanatory - the audience was already resolved when the row was written.
  "via_group_uuid"    uuid,

  -- Named "meta" deliberately: the jOOQ forcedTypes include-expression in loom/db/jooq/pom.xml
  -- matches `.*\.meta.*`, so this column picks up JsonObjectConverter with no pom edit. A
  -- column named "payload" would silently generate a raw JSONB field instead.
  "meta"              jsonb,

  -- PIPELINE_RUN_FAILED has no acting user, so the audit columns are nullable, following
  -- node_descriptor (V2.66). creator_uuid is the ACTOR - whoever did the thing being
  -- reported - NOT the recipient. Those are never the same person: an actor is suppressed
  -- from their own notification.
  "created"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "creator_uuid"      uuid,
  "edited"            timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "editor_uuid"       uuid,

  CONSTRAINT "notification_pkey" PRIMARY KEY ("uuid"),

  -- A CHECK, not a Postgres ENUM. V2.55__remove_webhook.sql shows what removing a single
  -- enum value costs: rename the type, rebuild it from pg_enum, re-type every referencing
  -- column, drop the old type - inside a DO block. This list is the most churn-prone thing
  -- in the feature (every new trigger adds a value, and taste will remove some), and a CHECK
  -- is DROP CONSTRAINT + ADD CONSTRAINT. node_descriptor made the same call for source/status.
  CONSTRAINT "notification_type_check" CHECK ("type" IN (
    'TASK_ASSIGNED', 'TASK_UNASSIGNED', 'TASK_STATUS_CHANGED',
    'TASK_COMMENT', 'COMMENT_REPLY', 'PIPELINE_RUN_FAILED')),

  -- Recipient and subject CASCADE: a bell entry whose subject is gone deep-links to a 404,
  -- which is worse than no entry at all.
  CONSTRAINT "notification_recipient_fkey" FOREIGN KEY ("recipient_uuid")    REFERENCES "user"         ("uuid") ON DELETE CASCADE,
  CONSTRAINT "notification_task_fkey"      FOREIGN KEY ("task_uuid")         REFERENCES "task"         ("uuid") ON DELETE CASCADE,
  CONSTRAINT "notification_comment_fkey"   FOREIGN KEY ("comment_uuid")      REFERENCES "comment"      ("uuid") ON DELETE CASCADE,
  CONSTRAINT "notification_run_fkey"       FOREIGN KEY ("pipeline_run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE CASCADE,
  CONSTRAINT "notification_asset_fkey"     FOREIGN KEY ("asset_uuid")        REFERENCES "asset"        ("uuid") ON DELETE CASCADE,

  -- Actor and group SET NULL: losing the person who acted must not erase the fact that it
  -- happened. The UI renders an absent actor as "someone".
  CONSTRAINT "notification_group_fkey"   FOREIGN KEY ("via_group_uuid") REFERENCES "group" ("uuid") ON DELETE SET NULL,
  CONSTRAINT "notification_creator_fkey" FOREIGN KEY ("creator_uuid")   REFERENCES "user"  ("uuid") ON DELETE SET NULL,
  CONSTRAINT "notification_editor_fkey"  FOREIGN KEY ("editor_uuid")    REFERENCES "user"  ("uuid") ON DELETE SET NULL
);

-- The hot path: the bell's unread count and the unread-only list. PARTIAL, so the index
-- stays the size of the working set rather than the size of the archive.
CREATE INDEX "idx_notification_recipient_unread"
  ON "notification" ("recipient_uuid", "created" DESC) WHERE "read" = false;

-- The full drawer list, read and unread together.
CREATE INDEX "idx_notification_recipient_created"
  ON "notification" ("recipient_uuid", "created" DESC);

-- Postgres does NOT index the referencing side of a foreign key. Without these, every
-- task, comment, run and asset delete seq-scans the whole notification table looking for
-- cascade victims - and this is the one table in the schema designed to grow without bound.
CREATE INDEX "idx_notification_task"    ON "notification" ("task_uuid");
CREATE INDEX "idx_notification_comment" ON "notification" ("comment_uuid");
CREATE INDEX "idx_notification_run"     ON "notification" ("pipeline_run_uuid");
CREATE INDEX "idx_notification_asset"   ON "notification" ("asset_uuid");

COMMENT ON TABLE "notification" IS 'One durable inbox entry for one user. Group notifications are fanned out to one row per member at dispatch time - see the header of V2.70 for why the audience is resolved when the event happens rather than when the inbox is read';
COMMENT ON COLUMN "notification"."recipient_uuid" IS 'Always a concrete user, never a group';
COMMENT ON COLUMN "notification"."type" IS 'What happened. varchar + CHECK rather than an enum because this list churns and Postgres cannot drop an enum value';
COMMENT ON COLUMN "notification"."creator_uuid" IS 'The ACTOR whose action produced this, not the recipient. Null for machine-generated events such as a failed pipeline run';
COMMENT ON COLUMN "notification"."via_group_uuid" IS 'The group through which the recipient was reached, when it was not a direct mention. Explanatory only';
COMMENT ON COLUMN "notification"."title" IS 'Pre-rendered summary line. Rendered at dispatch so the inbox does not have to re-resolve deleted subjects at read time';
COMMENT ON COLUMN "notification"."read_at" IS 'When the recipient marked it read. Null while unread';
