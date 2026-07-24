-- Make audit columns nullable on tables written by machines rather than users.
--
-- creator_uuid/editor_uuid were NOT NULL on every result table, but those rows are
-- written by Cortex workers. cortex_instance already recognised this in V2.33 and made
-- its audit columns nullable; the result tables have the same property and did not get
-- the same treatment, which would have forced the sync path to invent a synthetic user
-- for every row.
--
-- detection and embedding were recreated with nullable audit columns in V2.43, and every
-- asset_*_comp table in V2.38-V2.42, so this migration reduces to attachment.

ALTER TABLE "attachment" ALTER COLUMN "creator_uuid" DROP NOT NULL;
ALTER TABLE "attachment" ALTER COLUMN "editor_uuid" DROP NOT NULL;

COMMENT ON COLUMN "attachment"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user (see cortex_instance)';
COMMENT ON COLUMN "attachment"."editor_uuid" IS 'NULL when written by a Cortex worker rather than a user (see cortex_instance)';
