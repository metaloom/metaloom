-- Rename asset_fingerprint_comp.sector_index to window_index and correct the column comments.
--
-- Two unrelated things were called "sector" and the schema said they were the same thing. The
-- V2.41 comment on this column read "Which sector of a multi-sector fingerprint; 0 for whole-asset
-- fingerprints", which describes the *internal* sampling trick of MultiSectorVideoFingerprinter:
-- it seeks to a handful of points in the video, stacks those frames into one 16x16 binary image
-- and emits ONE 256-bit vector. Those sectors are folded into a single value and never become
-- rows.
--
-- What this column actually numbers is a timeline window - "this hash covers 00:30-00:40" - which
-- is why it sits next to time_from / time_to. The conflation was not harmless: a reader of the
-- schema concludes the windows already exist, and a task specification was written on exactly that
-- assumption (spec/tasks/SEARCH_LUCENE_TASKS.md Task 5, since corrected). No row with
-- sector_index > 0 has ever been written - FingerprintNode hardcodes index 0 and is the only
-- writer - so the windowed producer is still ahead of us, not behind.
--
-- Renaming rather than only fixing the comment: the name is what a reader sees first, and the
-- windowed producer (spec/tasks/NODE_FINGERPRINT_TASKS.md Task 4) is about to make this column
-- load-bearing. Doing it now, while every row still holds the default 0, is the cheapest this
-- change will ever be.
--
-- ALTER TABLE ... RENAME COLUMN carries the unique constraint
-- asset_fingerprint_comp_unique_key (asset_uuid, node_kind, algorithm, sector_index) over to the
-- new name by itself; the constraint name is unchanged and no index is rebuilt.
--
-- Not backwards compatible for out-of-tree readers of the column. Acceptable here: the only
-- readers are in this repository and in cortex, and they move with this change.

ALTER TABLE "asset_fingerprint_comp" RENAME COLUMN "sector_index" TO "window_index";

COMMENT ON TABLE "asset_fingerprint_comp" IS 'Perceptual fingerprints of an asset, one row per timeline window. Indexed by (algorithm, fingerprint) so dedup is an index scan rather than a table walk.';
COMMENT ON COLUMN "asset_fingerprint_comp"."window_index" IS 'Timeline window index within the asset: 0 is the whole-asset fingerprint, 1..n are windows with time_from/time_to set. Unrelated to the internal sectors of the multi-sector fingerprint algorithm, which are folded into a single vector.';
COMMENT ON COLUMN "asset_fingerprint_comp"."time_from" IS 'Start of the window this row covers, in milliseconds. NULL on the whole-asset row.';
COMMENT ON COLUMN "asset_fingerprint_comp"."time_to" IS 'End of the window this row covers, in milliseconds. NULL on the whole-asset row.';
