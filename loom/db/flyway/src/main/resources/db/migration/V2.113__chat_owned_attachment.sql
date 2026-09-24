-- ---------------------------------------------------------------------------
-- Chat-owned attachment
--
-- A sixth nullable target column on attachment, alongside embedding_uuid (V2.43), asset_uuid
-- (V2.44), detection_uuid (V2.79), person_uuid (V2.90) and user_uuid (V2.93). Like those it carries
-- no CHECK pairing it against the others: the targets are not alternatives, and V2.44 already
-- argued that case.
--
-- The lifetime argument is V2.90's and V2.93's, applied to a conversation. A dropped file is not
-- derived from anything - it was handed to the agent mid-conversation - so no asset, detection or
-- person cascade can reach it. What can reach it is the chat: when the conversation is gone the
-- file has no remaining meaning, so CASCADE rather than SET NULL. A user who wants the file kept
-- promotes it into the library first, which copies the bytes into a real asset and leaves that
-- asset untouched by this cascade.
-- ---------------------------------------------------------------------------
ALTER TABLE "attachment" ADD COLUMN "chat_uuid" uuid;
ALTER TABLE "attachment"
    ADD CONSTRAINT "attachment_chat_uuid_fkey" FOREIGN KEY ("chat_uuid") REFERENCES "chat" ("uuid") ON DELETE CASCADE;

-- Read on every turn of every chat that has files: the agent's system prompt lists them. Unindexed
-- this would be a sequential scan of every thumbnail and face crop in the deployment, once per
-- message.
CREATE INDEX "idx_attachment_chat_uuid" ON "attachment" ("chat_uuid");

-- ---------------------------------------------------------------------------
-- No unique index, deliberately
--
-- This is where a chat diverges from an account. V2.93 made "at most one" a schema fact for
-- USER_AVATAR because nobody needs a gallery of account pictures. A conversation is the opposite
-- case: dropping five images and asking for them to be combined is the headline use, so the
-- cardinality here is PERSON_IMAGE's (V2.90), not USER_AVATAR's.
--
-- Nor is there an idempotency key on the content. Two byte-identical files dropped into one chat
-- are two things the user did, and the second must not silently replace the first - the same
-- argument listByPerson carries. Deduplication happens one level down: attachment_binary is keyed
-- by sha512sum, so the bytes are stored once regardless.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Documentation
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN "attachment"."chat_uuid" IS 'Chat that owns this dropped file. Conversational rather than catalogued: it is not an asset and it dies with the chat.';
