-- ---------------------------------------------------------------------------
-- Retire the asset-backed person gallery, replaced by V2.90.
--
-- person_image has had no writer since V2.26 created it: no DAO, no endpoint, no UI. The only code
-- that ever touched it was a cascade test pinning it so that "the table cannot grow a writer and an
-- orphan problem at the same time". It is dropped rather than left in place because a table every
-- schema reader has to rule out is a cost that never stops being paid.
--
-- primary_image_uuid goes with it. It pointed at an asset, which is the wrong thing for the
-- population this feature actually produces: for a person discovered in a video, the "primary image"
-- resolved to the entire video file. Its replacement is person.avatar_attachment_uuid (V2.90), which
-- points at a picture of the person.
--
-- Neither carried data worth migrating - person_image was never written, and primary_image_uuid
-- could only be set by hand through the REST API, which nothing in the product did.
-- ---------------------------------------------------------------------------
DROP TABLE "person_image";

ALTER TABLE "person" DROP COLUMN "primary_image_uuid";
