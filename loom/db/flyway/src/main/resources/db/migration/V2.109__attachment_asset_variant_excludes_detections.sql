-- Let an asset keep more than one face crop.
--
-- V2.79 added attachment_detection_variant_key and its comment states the reason plainly:
-- "V2.44's attachment_asset_variant_key is one row per (asset, type, node_kind, variant), which
-- cannot serve here: an asset has many face crops, one per detection." It added the detection-keyed
-- index but never dropped or narrowed the asset-keyed one, so both applied and the older index still
-- rejected every crop after the first: facedetect logged
--   Could not store the face crop for detection <uuid>: Request failed {Conflict}
--   ERROR: duplicate key value violates unique constraint "attachment_asset_variant_key"
-- and an asset with ten faces persisted exactly one, leaving the review UI full of blank tiles.
--
-- The asset-level key still earns its place for attachments that hang off the asset itself (one
-- thumbnail per node_kind+variant, the idempotency V2.44 wanted), so narrow it rather than drop it:
-- rows that name a detection are keyed by attachment_detection_variant_key instead.
DROP INDEX IF EXISTS "attachment_asset_variant_key";

CREATE UNIQUE INDEX "attachment_asset_variant_key"
    ON "attachment" ("asset_uuid", "type", "node_kind", "variant")
    WHERE "asset_uuid" IS NOT NULL AND "node_kind" IS NOT NULL AND "detection_uuid" IS NULL;
