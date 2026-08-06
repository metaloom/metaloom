-- Make "embedding" a system of record that a pluggable ANN index can be derived from.
--
-- V2.43 left the column comment on embedding.vector saying "OPEN DECISION: pgvector in Postgres or
-- an external index fed via vector_config". This migration answers the half of it that face
-- similarity needs: the vector column stays a plain real[] and stays authoritative, and the
-- approximate-nearest-neighbour structure lives OUTSIDE Postgres behind the VectorIndex SPI - the
-- same stance LuceneSimilarityIndex already takes for perceptual fingerprints, and for the same
-- reason: pgvector is not in the stock postgres image, so an unconditional CREATE EXTENSION would
-- break jOOQ codegen, setup-pool.sh, every developer's local database and the Helm bundled one.
--
-- Text -> media semantic search is a separate question with a separate answer (pgvector, see
-- spec/features/search/SEMANTIC_SEARCH.md section 2); a face vector cannot consume a text query,
-- so the two do not have to agree.
--
-- Four things are added here.
--
-- 1. An exporter contract - dirty/synced_at/index_version - mirroring what search_document already
--    has. Without it nothing can incrementally feed an index, and a write that fails halfway leaves
--    the index quietly short of rows with no way to notice.
--
-- 2. A dimensions CHECK. The column has always claimed to guard against comparing vectors from
--    different models, but nothing enforced it, so a 512-d InspireFace vector and a 128-d dlib one
--    could sit in the same column distinguishable only by free text. The table is empty today,
--    which makes this the cheapest moment this constraint will ever be.
--
-- 3. "model" joins the identity key. The V2.43 key (asset, node_kind, type, frame_number,
--    subject_index) has no model discriminator, so re-running a node under a NEW embedding model
--    would UPSERT over the old model's row - two models could never coexist, and a model upgrade
--    would be a destructive one-way operation with no way to A/B or roll back. That is the same
--    collision the transcript-chunk case runs into. model becomes NOT NULL DEFAULT '' to join it.
--
-- 4. "normalized", recording whether the vector was unit-normalized at write time. With normalized
--    vectors cosine and inner product rank identically, so the choice of operator/similarity
--    function stops mattering - and the assumption becomes auditable instead of implicit.

-- ---------------------------------------------------------------------------
-- Exporter contract
-- ---------------------------------------------------------------------------
ALTER TABLE "embedding" ADD COLUMN "synced_at"     timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE "embedding" ADD COLUMN "index_version" int     NOT NULL DEFAULT 1;
ALTER TABLE "embedding" ADD COLUMN "dirty"         boolean NOT NULL DEFAULT true;
ALTER TABLE "embedding" ADD COLUMN "normalized"    boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN "embedding"."synced_at" IS 'When this row was last drained into the vector index. Paired with dirty to make the export incremental.';
COMMENT ON COLUMN "embedding"."index_version" IS 'Bumped when the index layout changes, so a stale index can be recognised and rebuilt rather than silently queried.';
COMMENT ON COLUMN "embedding"."dirty" IS 'True while the row has not been written to the vector index. New rows start dirty; EmbeddingSyncService clears it.';
COMMENT ON COLUMN "embedding"."normalized" IS 'True when the vector was unit-normalized at write time. Normalized vectors rank identically under cosine and inner product.';

-- ---------------------------------------------------------------------------
-- Enforce what "dimensions" has always claimed
-- ---------------------------------------------------------------------------
ALTER TABLE "embedding" ADD CONSTRAINT "embedding_dimensions_check"
    CHECK (array_length("vector", 1) = "dimensions");

COMMENT ON CONSTRAINT "embedding_dimensions_check" ON "embedding" IS 'dimensions must equal the actual vector length - a mismatch means the row cannot be compared against anything and would poison an index segment.';

-- ---------------------------------------------------------------------------
-- "model" joins the identity key so two models can coexist for one asset
-- ---------------------------------------------------------------------------
UPDATE "embedding" SET "model" = '' WHERE "model" IS NULL;
ALTER TABLE "embedding" ALTER COLUMN "model" SET DEFAULT '';
ALTER TABLE "embedding" ALTER COLUMN "model" SET NOT NULL;

ALTER TABLE "embedding" DROP CONSTRAINT "embedding_unique_key";
ALTER TABLE "embedding" ADD CONSTRAINT "embedding_unique_key"
    UNIQUE ("asset_uuid", "node_kind", "type", "model", "frame_number", "subject_index");

COMMENT ON CONSTRAINT "embedding_unique_key" ON "embedding" IS 'Identity of an embedding. model is part of it so upgrading the embedding model adds rows next to the old ones instead of overwriting them.';
COMMENT ON COLUMN "embedding"."model" IS 'Readable model identifier, e.g. inspireface-r18. Part of the identity key: rows from different models coexist.';

-- ---------------------------------------------------------------------------
-- Indexes the exporter and the type/model segmentation need
-- ---------------------------------------------------------------------------
CREATE INDEX "idx_embedding_type_model" ON "embedding" ("type", "model");
CREATE INDEX "idx_embedding_dirty"      ON "embedding" ("synced_at") WHERE "dirty";

-- ---------------------------------------------------------------------------
-- Close the V2.43 open decision on the vector column itself
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN "embedding"."vector" IS 'Embedding vector as a plain PostgreSQL array, and the system of record for it. Approximate nearest-neighbour search lives outside Postgres behind the VectorIndex SPI (Lucene HNSW today); that index is a derived, rebuildable cache of this column and never authoritative. Superseded the V2.43 open decision - see spec/features/search/SEMANTIC_SEARCH.md.';
