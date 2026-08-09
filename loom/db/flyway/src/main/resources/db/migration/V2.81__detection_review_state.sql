-- Review state on "detection": a human confirm/reject decision against a machine proposal.
--
-- The review UI could already confirm or reject an object detection and the answer had nowhere to
-- go - WorkflowView's handlers wrote React state and the decision was lost on reload. The same gap
-- blocked per-detection review in the face workflow and the "which faces are unconsented?" question
-- the release gate asks (spec/workflows/WORKFLOW_OBJECT_DETECT.md §2,
-- spec/workflows/WORKFLOW_RIGHTS_RELEASE.md §2.2).
--
-- This mirrors the review model V2.61 established for dedup_group and V2.79 applied to cluster: a
-- node proposes with status PENDING, a human decides, and the decision outlives the next run of the
-- node that proposed it.

-- ---------------------------------------------------------------------------
-- 1. One vocabulary, not three.
--
-- V2.79 created "cluster_status" with exactly the three values this table needs, and
-- WORKFLOW_SAFETY_TRIAGE will need them again for its verdict. Renaming is a pure catalog
-- operation - same labels, same OIDs, no table rewrite and no data migration - so the alternative
-- (a second, structurally identical type) would buy nothing but a duplicate jOOQ enum.
--
-- Unlike ALTER TYPE ... ADD VALUE, RENAME is transactional, so it is safe in a Flyway file.
--
-- "dedup_status" is deliberately left alone: it is shipped, dedup_group is not part of this change,
-- and folding it in would rewrite a table this migration has no reason to touch.
-- ---------------------------------------------------------------------------
ALTER TYPE "cluster_status" RENAME TO "review_status";

-- ---------------------------------------------------------------------------
-- 2. The review columns.
--
-- reviewed_at/reviewer_uuid are NOT edited/editor_uuid. Those are the machine-nullable audit block
-- (V2.43, V2.47) and the producing node's own upsert writes them on every re-run; conflating the
-- two would let a re-run silently claim that a human reviewed the row.
--
-- corrected_label is a third column rather than an overwrite of "label". "label" is what the model
-- actually said, and that is the training signal - a reviewer correcting "dog" to "wolf" must not
-- destroy the evidence that the model answered "dog".
--
-- Every row that exists at this point is machine-written and unreviewed, so the PENDING default is
-- already correct and needs no back-fill. (V2.79 needed one only because "cluster" also held
-- human-authored rows.)
-- ---------------------------------------------------------------------------
ALTER TABLE "detection" ADD COLUMN "status" "review_status" NOT NULL DEFAULT 'PENDING';
ALTER TABLE "detection" ADD COLUMN "reviewed_at" timestamp WITHOUT TIME ZONE;
ALTER TABLE "detection" ADD COLUMN "reviewer_uuid" uuid;
ALTER TABLE "detection" ADD COLUMN "corrected_label" varchar;

-- No ON DELETE, matching detection_creator_uuid_fkey (V2.43): users are not deleted casually, and
-- losing the reviewer's identity would be worse than blocking the delete.
ALTER TABLE "detection"
    ADD CONSTRAINT "detection_reviewer_uuid_fkey" FOREIGN KEY ("reviewer_uuid") REFERENCES "user" ("uuid");

-- ---------------------------------------------------------------------------
-- 3. Two indexes, because there are two queues.
--
-- The per-asset queue ("what is left to review on this asset?") predicates on asset_uuid; the
-- cross-asset queue behind GET /detections?status=PENDING&type= does not, and cannot use an index
-- led by asset_uuid at all. Same pair V2.79 created for "cluster".
-- ---------------------------------------------------------------------------
CREATE INDEX "idx_detection_review" ON "detection" ("asset_uuid", "type", "status");
CREATE INDEX "idx_detection_status_type" ON "detection" ("status", "type");

-- ---------------------------------------------------------------------------
-- 4. What a review means for the next run. THE RULE:
--
--   A node upsert must not clear a non-PENDING status.
--
-- detection_unique_key is (asset_uuid, node_kind, frame_number, detection_index), so a re-run
-- overwrites row #3 with whatever the node now calls #3. Left alone, that silently replaces a
-- reviewed answer with an unreviewed one - the same class of bug V2.43's upsert key was introduced
-- to fix, reappearing one level up.
--
-- Implemented in DetectionDaoImpl.upsertDetection as: status, reviewed_at, reviewer_uuid and
-- corrected_label are preserved on conflict, UNLESS the incoming producer_version differs from the
-- stored one, in which case all four reset to PENDING.
--
-- The version gate is the point. detection_index is an ordinal, not an identity, and it is not
-- stable across model versions: after an upgrade, #3 may not be the object the reviewer looked at,
-- so carrying the verdict forward would attribute a human decision to a different box. Within one
-- producer_version the ordinal is stable and the verdict stands. This is the "version the row"
-- option from WORKFLOW_OBJECT_DETECT.md §2.4 without its extra column - a stale review is retired
-- rather than kept against a superseded row - and it needs no IoU helper.
--
-- Pinned by DetectionUpsertReviewTest.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN "detection"."status" IS 'PENDING (awaiting review), CONFIRMED (a human agreed with the detection) or REJECTED (a false positive). Reset to PENDING by an upsert whose producer_version differs from the stored one.';
COMMENT ON COLUMN "detection"."reviewed_at" IS 'When a human decided. NULL while PENDING. Distinct from "edited", which the producing node touches on every re-run.';
COMMENT ON COLUMN "detection"."reviewer_uuid" IS 'The user who decided. Distinct from editor_uuid, which is machine-written provenance (V2.47).';
COMMENT ON COLUMN "detection"."corrected_label" IS 'The label a reviewer supplied when the detection was right but its class was wrong. "label" keeps what the model said, which is the training signal.';
