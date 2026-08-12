-- Remix permissions (spec/tasks/ASSET_REMIX_PLAN.md).
--
-- Gate the /api/v1/remixes, /api/v1/remixes/:uuid/assets and /api/v1/assets/:uuid/remixes routes.
-- Kept separate from READ_ASSET/UPDATE_ASSET: grouping assets into remixes is a curation act, and a
-- curator should be able to build and rename groups without necessarily being allowed to mutate the
-- underlying assets. Reading the members of a remix additionally requires READ_ASSET, so a remix
-- cannot be used as a side channel around asset visibility.
--
-- As with V2.62 (READ_DEDUP) and V2.96 (share): this migration only adds the enum values.
-- PostgreSQL cannot use a value added by ALTER TYPE ... ADD VALUE inside the same transaction that
-- added it, and Flyway wraps each migration in one transaction, so the seed grant lives in V2.102.
-- Nothing else may go in this file.

ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_REMIX';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'CREATE_REMIX';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'UPDATE_REMIX';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'DELETE_REMIX';
