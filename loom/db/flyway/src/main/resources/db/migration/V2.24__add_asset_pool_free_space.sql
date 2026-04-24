-- Add free_space and used_space columns to asset_pool table (bytes)
ALTER TABLE "asset_pool" ADD COLUMN "free_space" bigint;
ALTER TABLE "asset_pool" ADD COLUMN "used_space" bigint;
