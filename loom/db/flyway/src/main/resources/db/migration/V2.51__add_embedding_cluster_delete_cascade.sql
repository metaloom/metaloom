-- embedding_cluster.cluster_uuid had no ON DELETE CASCADE.
--
-- V2.12 created both embedding_cluster foreign keys without a delete action. V2.43
-- dropped the embedding table with CASCADE and recreated only the embedding_uuid FK
-- (with ON DELETE CASCADE), leaving the cluster_uuid FK as NO ACTION. As a result a
-- cluster that still had any embedding linked through embedding_cluster could not be
-- deleted at all - the delete failed with a foreign-key violation - and the join rows
-- would otherwise strand a cluster that was meant to be gone.
--
-- An embedding_cluster row only means "this embedding belongs to that cluster"; when the
-- cluster is deleted the membership has no meaning, so it goes with it. This mirrors the
-- collection_cluster FKs, which already cascade from both sides (V2.12).

ALTER TABLE "embedding_cluster" DROP CONSTRAINT IF EXISTS "embedding_cluster_cluster_uuid_fkey";
ALTER TABLE "embedding_cluster"
    ADD CONSTRAINT "embedding_cluster_cluster_uuid_fkey"
    FOREIGN KEY ("cluster_uuid") REFERENCES "cluster" ("uuid") ON DELETE CASCADE;
