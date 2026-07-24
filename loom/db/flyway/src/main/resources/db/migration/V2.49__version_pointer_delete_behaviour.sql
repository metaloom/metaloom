-- Make the "latest/active version" pointers survive a delete.
--
-- pipeline.latest_version_uuid references pipeline_version, and pipeline_version.pipeline_uuid
-- references pipeline ON DELETE CASCADE - a cycle. Deleting a pipeline cascades into its
-- versions, and removing those versions is then rejected by the pointer coming back the
-- other way:
--
--   ERROR: update or delete on table "pipeline_version" violates foreign key constraint
--          "pipeline_latest_version_uuid_fkey" on table "pipeline"
--
-- so DELETE /pipelines/:uuid always failed with a 500. ON DELETE SET NULL breaks the cycle:
-- the pointer clears when the version it names goes away, whatever order the cascade runs in.
--
-- skill.active_version_uuid has the identical shape and is fixed the same way before it
-- bites in the same place.

ALTER TABLE "pipeline" DROP CONSTRAINT IF EXISTS "pipeline_latest_version_uuid_fkey";
ALTER TABLE "pipeline"
    ADD CONSTRAINT "pipeline_latest_version_uuid_fkey"
    FOREIGN KEY ("latest_version_uuid") REFERENCES "pipeline_version" ("uuid") ON DELETE SET NULL;

ALTER TABLE "skill" DROP CONSTRAINT IF EXISTS "skill_active_version_uuid_fkey";
ALTER TABLE "skill"
    ADD CONSTRAINT "skill_active_version_uuid_fkey"
    FOREIGN KEY ("active_version_uuid") REFERENCES "skill_version" ("uuid") ON DELETE SET NULL;
