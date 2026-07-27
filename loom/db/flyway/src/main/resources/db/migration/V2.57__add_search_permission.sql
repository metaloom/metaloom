-- Search permission.
--
-- READ_SEARCH is the wholesale gate on the /api/v1/search/* routes. It is deliberately separate
-- from READ_ASSET because search is cross-entity by construction: the endpoint additionally narrows
-- the requested entity types against the existing READ_ASSET / READ_TAG / READ_ANNOTATION /
-- READ_PERSON / READ_COLLECTION / READ_LIBRARY / READ_DETECTION / READ_CLUSTER permissions and drops
-- the ones the caller may not see.
--
-- This migration adds the enum value and nothing that uses it. PostgreSQL cannot use a value added
-- by ALTER TYPE ... ADD VALUE inside the same transaction that added it, and Flyway wraps each
-- migration in one transaction - so any seed grant of READ_SEARCH must live in a later migration.

ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_SEARCH';
