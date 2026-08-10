-- Record who attributed a face cluster to a person, and when.
--
-- "cluster" carried a verdict (V2.79 "status") and its subject ("person_uuid") but no author. The
-- confirm and reject paths passed the deciding user into ClusterDaoImpl, which had nowhere to put it
-- but "editor_uuid" - and editor_uuid/edited are the machine-nullable audit block (V2.43, V2.47) that
-- the producing node rewrites on every re-run. FacedetectNode re-proposing its clusters therefore
-- erased the only trace of which human said "this is Anna", leaving a biometric attribution with no
-- author (spec/workflows/WORKFLOW_FACE.md gotcha 12).
--
-- V2.81 solved exactly this for "detection" and its reasoning transfers unchanged: the review author
-- is a separate pair of columns precisely so a re-run cannot claim, or destroy, a human decision.
--
-- No back-fill. Every CONFIRMED row that exists at this point was set CONFIRMED by V2.79's own
-- UPDATE over the human-authored, NULL-asset_uuid rows - nobody reviewed them through the review
-- path, so inventing a reviewer for them would be the same lie this migration exists to prevent.
-- NULL reads as "not reviewed here", which is true.

-- ---------------------------------------------------------------------------
-- The review author.
--
-- No ON DELETE action, matching detection_reviewer_uuid_fkey (V2.81) and cluster_creator_uuid_fkey:
-- users are not deleted casually, and losing the reviewer's identity would be worse than blocking
-- the delete.
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ADD COLUMN "reviewed_at" timestamp WITHOUT TIME ZONE;
ALTER TABLE "cluster" ADD COLUMN "reviewer_uuid" uuid;

ALTER TABLE "cluster"
    ADD CONSTRAINT "cluster_reviewer_uuid_fkey" FOREIGN KEY ("reviewer_uuid") REFERENCES "user" ("uuid");

-- ---------------------------------------------------------------------------
-- What a re-run means for them. THE RULE, unchanged from V2.79 §6 and extended to cover the author:
--
--   A node upsert must not clear a review, nor claim one.
--
-- ClusterDaoImpl.upsertCluster preserves status, person_uuid, reviewed_at and reviewer_uuid on
-- conflict, so re-running the producer over an asset rewrites the geometry it owns and leaves the
-- verdict and its author alone. editor_uuid is deliberately NOT in that set: it is the producer's own
-- provenance and moves with every run, which is exactly why the reviewer needed columns of its own.
--
-- Pinned by ClusterDaoTest#testNodeReRunDoesNotClobberTheReviewer.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN "cluster"."reviewed_at" IS 'When a human decided. NULL while PENDING. Distinct from "edited", which the producing node touches on every re-run.';
COMMENT ON COLUMN "cluster"."reviewer_uuid" IS 'The user who decided. Distinct from editor_uuid, which is machine-written provenance (V2.47).';
