-- Remove webhook support altogether.
--
-- Drops the webhook table, the loom_events enum (only ever used by
-- webhook.triggers) and the four *_WEBHOOK values from the loom_permission
-- enum. Postgres cannot drop a single enum value, so loom_permission is
-- rebuilt from its current values minus the webhook ones.

DROP TABLE IF EXISTS "webhook";

DROP TYPE IF EXISTS "loom_events";

-- Drop any granted webhook permissions before the enum is rebuilt
DELETE FROM "role_permission" WHERE "permission"::text IN ('CREATE_WEBHOOK', 'READ_WEBHOOK', 'DELETE_WEBHOOK', 'UPDATE_WEBHOOK');
DELETE FROM "user_permission" WHERE "permission"::text IN ('CREATE_WEBHOOK', 'READ_WEBHOOK', 'DELETE_WEBHOOK', 'UPDATE_WEBHOOK');
DELETE FROM "token_permission" WHERE "permission"::text IN ('CREATE_WEBHOOK', 'READ_WEBHOOK', 'DELETE_WEBHOOK', 'UPDATE_WEBHOOK');

DO $$
DECLARE
  values_list text;
BEGIN
  SELECT string_agg(quote_literal(e.enumlabel), ', ' ORDER BY e.enumsortorder)
    INTO values_list
    FROM pg_enum e
    JOIN pg_type t ON t.oid = e.enumtypid
   WHERE t.typname = 'loom_permission'
     AND e.enumlabel NOT IN ('CREATE_WEBHOOK', 'READ_WEBHOOK', 'DELETE_WEBHOOK', 'UPDATE_WEBHOOK');

  EXECUTE 'ALTER TYPE "loom_permission" RENAME TO "loom_permission_old"';
  EXECUTE 'CREATE TYPE "loom_permission" AS ENUM (' || values_list || ')';
  EXECUTE 'ALTER TABLE "role_permission" ALTER COLUMN "permission" TYPE "loom_permission" USING "permission"::text::"loom_permission"';
  EXECUTE 'ALTER TABLE "user_permission" ALTER COLUMN "permission" TYPE "loom_permission" USING "permission"::text::"loom_permission"';
  EXECUTE 'ALTER TABLE "token_permission" ALTER COLUMN "permission" TYPE "loom_permission" USING "permission"::text::"loom_permission"';
  EXECUTE 'DROP TYPE "loom_permission_old"';
END $$;
