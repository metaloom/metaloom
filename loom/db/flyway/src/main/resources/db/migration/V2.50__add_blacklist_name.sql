-- Add the blacklist name column.
--
-- The whole blacklist stack speaks "name": BlacklistCreateRequest.name,
-- BlacklistEndpointService.create reads it, BlacklistDao.createBlacklist takes it,
-- Blacklist.setName stores it on the POJO and BlacklistModelBuilder reads it back onto the
-- response. The table never had the column, so jOOQ quietly dropped the field on insert and
-- every name came back null. The DAO test that would have caught it was failing earlier, on
-- the one-entry-per-user-per-asset unique index, and never reached the assertion.
--
-- Nullable: existing rows have no name, and the column is descriptive rather than part of
-- the identity - that stays (asset_uuid, creator_uuid).

ALTER TABLE "blacklist" ADD COLUMN "name" varchar;

COMMENT ON COLUMN "blacklist"."name" IS 'Human readable label for the entry, e.g. the name of the claim';
