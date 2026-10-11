-- A person owns their voice samples the way they own their pictures (V2.89/V2.90): a recording uploaded to
-- the person, or a stretch of speech cut out of an asset into the person's own keeping. It needs a type, not a
-- table, for the reason V2.89 gave - attachment is already the sink for binaries that are not assets.
--
-- Enum additions live in their own migration on purpose: ALTER TYPE ... ADD VALUE cannot run inside a
-- transaction block on older Postgres, and a value added in one transaction is not usable in it.
-- Nothing else may go in this file.
ALTER TYPE "attachment_type" ADD VALUE IF NOT EXISTS 'PERSON_VOICE';
