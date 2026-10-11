-- ---------------------------------------------------------------------------
-- Person recognition: voice samples, reference prints and suggested persons.
--
-- Until now the only way a face or a voice became a person was a reviewer confirming a cluster
-- (V2.79), one asset at a time: the same person in two recordings was two unrelated clusters, and
-- nothing proposed the second attribution. This adds the three things the proposing half needs,
-- and nothing that would let a machine decide on its own.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. The hero voice sample
--
-- The voice counterpart of person.avatar_attachment_uuid (V2.90): which of the person's
-- PERSON_VOICE samples represents them. SET NULL for the same reason as the avatar - deleting the
-- sample must not delete the person - and the same cycle (person -> attachment -> person), broken
-- the same way: both columns are nullable, so the sample is written first and pointed at second.
-- ---------------------------------------------------------------------------
ALTER TABLE "person" ADD COLUMN "hero_voice_attachment_uuid" uuid;
ALTER TABLE "person"
    ADD CONSTRAINT "person_hero_voice_attachment_uuid_fkey" FOREIGN KEY ("hero_voice_attachment_uuid") REFERENCES "attachment" ("uuid") ON DELETE SET NULL;

CREATE INDEX "idx_person_hero_voice_attachment_uuid" ON "person" ("hero_voice_attachment_uuid");

-- ---------------------------------------------------------------------------
-- 2. A suggestion, kept apart from the verdict
--
-- A producing node that recognises a face or a voice as somebody already in the directory records
-- it here, never in person_uuid. person_uuid is the reviewer's verdict (V2.79) and the upsert key
-- preserves it across re-runs (ClusterDaoImpl.REVIEW_COLUMNS); a column a machine may write must
-- not be the one a filter, an export or a reviewer reads as "this is who it is". Voiceprints and
-- face vectors used to identify people are GDPR Art. 9 data, and the recognition study behind this
-- feature recommends human confirmation of every name.
--
-- These two columns are the producer's, not the reviewer's: a re-run overwrites them with what the
-- current references say, which is the point - a suggestion that improves as people confirm more
-- clusters and upload more samples.
--
-- SET NULL: deleting a person takes the suggestion with it, nothing else.
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ADD COLUMN "suggested_person_uuid" uuid;
ALTER TABLE "cluster" ADD COLUMN "suggested_score" real;
ALTER TABLE "cluster"
    ADD CONSTRAINT "cluster_suggested_person_uuid_fkey" FOREIGN KEY ("suggested_person_uuid") REFERENCES "person" ("uuid") ON DELETE SET NULL;

CREATE INDEX "idx_cluster_suggested_person_uuid" ON "cluster" ("suggested_person_uuid");

-- The reference read behind every suggestion: the confirmed centroids of one (type, model) space.
CREATE INDEX "idx_cluster_type_model_person" ON "cluster" ("type", "model") WHERE "person_uuid" IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Reference prints computed from a person's own samples
--
-- A confirmed cluster already carries a centroid, so confirmed material needs no copy. What does
-- not have one is a sample uploaded to the person: it is an attachment, not an asset, so no
-- pipeline ever embeds it. The node that owns the model embeds it on its next run and writes the
-- vector here.
--
-- One row per (sample, type, model), because a vector is only comparable inside the space that
-- produced it - the (type, model, dimensions) identity embedding (V2.75) and cluster (V2.79) already
-- carry. Switching a node to another model therefore enrols every sample again beside the old rows
-- instead of overwriting them, and the stored audio is what makes that possible at all.
--
-- type is the cluster type the print is compared with ('speaker', 'face'), so a match reads one
-- space from both tables with the same key.
--
-- vector is nullable on purpose: a sample the model could not use (no speech in it) is recorded with
-- an error instead of a vector, so it is not attempted again on every item of every run.
--
-- Both foreign keys cascade. The print is derived from the sample and means nothing without it, and
-- the sample already dies with its person (V2.90); person_uuid is carried for the match query, which
-- reads prints by space and groups them by person without a join.
-- ---------------------------------------------------------------------------
CREATE TABLE "person_print" (
  "uuid"              uuid NOT NULL DEFAULT uuidv7(),
  "person_uuid"       uuid NOT NULL,
  "attachment_uuid"   uuid NOT NULL,
  "type"              varchar NOT NULL,
  "model"             varchar NOT NULL,
  "dimensions"        int,
  "vector"            real[],
  "error"             varchar,
  "created"           timestamp NOT NULL DEFAULT now(),
  "edited"            timestamp NOT NULL DEFAULT now(),
  PRIMARY KEY ("uuid"),
  CONSTRAINT "person_print_person_uuid_fkey" FOREIGN KEY ("person_uuid") REFERENCES "person" ("uuid") ON DELETE CASCADE,
  CONSTRAINT "person_print_attachment_uuid_fkey" FOREIGN KEY ("attachment_uuid") REFERENCES "attachment" ("uuid") ON DELETE CASCADE,
  CONSTRAINT "person_print_sample_space_key" UNIQUE ("attachment_uuid", "type", "model"),
  -- Exactly one of the two outcomes: a usable vector of the declared length, or the reason there is none.
  CONSTRAINT "person_print_outcome_check" CHECK (
    ("vector" IS NOT NULL AND "error" IS NULL AND array_length("vector", 1) = "dimensions")
    OR ("vector" IS NULL AND "error" IS NOT NULL))
);

CREATE INDEX "idx_person_print_person_uuid" ON "person_print" ("person_uuid");
CREATE INDEX "idx_person_print_type_model" ON "person_print" ("type", "model");

-- ---------------------------------------------------------------------------
-- Documentation
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN "person"."hero_voice_attachment_uuid" IS 'The voice sample that represents this person. One of the person''s own PERSON_VOICE attachments.';
COMMENT ON COLUMN "cluster"."suggested_person_uuid" IS 'Person the producing node recognised this cluster as, from confirmed clusters and the person''s own samples. A proposal only: person_uuid remains the reviewer''s verdict.';
COMMENT ON COLUMN "cluster"."suggested_score" IS 'Cosine similarity of the cluster centroid to the suggested person''s reference, in the space of cluster.model.';
COMMENT ON TABLE "person_print" IS 'Reference vectors computed from a person''s own samples, one per (sample, cluster type, model). Written by the node owning the model; read by person matching.';
COMMENT ON COLUMN "person_print"."type" IS 'The cluster type this print is comparable with, e.g. speaker or face.';
COMMENT ON COLUMN "person_print"."error" IS 'Why the sample produced no vector (e.g. no speech). Recorded so the sample is not re-attempted on every run.';
