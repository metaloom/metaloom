-- Deduplication review permissions (spec/features/pipeline-nodes/NODE_DEDUP_PLAN.md §2.1).
--
-- Gate the /api/v1/dedup-groups and /api/v1/assets/:uuid/dedup-groups routes. Kept separate from
-- READ_ASSET: the review queue is its own workflow surface (a reviewer curates duplicate groups
-- without necessarily being able to mutate the underlying assets).
--
-- As with V2.57 (READ_SEARCH): this migration only adds the enum values. PostgreSQL cannot use a
-- value added by ALTER TYPE ... ADD VALUE inside the same transaction that added it, so any seed
-- grant must live in a later migration.

ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_DEDUP';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'CREATE_DEDUP';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'UPDATE_DEDUP';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'DELETE_DEDUP';
