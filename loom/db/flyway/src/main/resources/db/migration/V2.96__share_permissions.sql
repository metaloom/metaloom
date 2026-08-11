-- Creating, reading, changing and revoking a share link.
--
-- A share link is a capability URL that lets somebody without a Loom account see one asset or one
-- collection. Handing that capability out is a trust decision of its own: a user who may read an
-- asset is not automatically a user who may publish it to the open internet, and an installation
-- that wants review-by-link for its editors but not for its interns needs the two separated. Hence
-- four verbs of its own rather than reusing READ_ASSET/READ_COLLECTION.
--
-- Note what these permissions do NOT cover. They govern the share row - the link, its expiry, its
-- password, its toggles - and nothing else. The visitor who opens the link holds no permission at
-- all and is never resolved to a user; their access is decided entirely by the share row itself,
-- server-side, in ShareAccessService. There is deliberately no VIEW_SHARE constant, because a
-- constant that no code path can ever check would only suggest an enforcement point that is not
-- there.
--
-- Creating a share additionally requires READ_ASSET or READ_COLLECTION on the target: you may not
-- publish what you cannot see. That check lives in ShareLinkEndpointService, not here.
--
-- Enum additions live in their own migration on purpose: ALTER TYPE ... ADD VALUE cannot run inside
-- a transaction block on older Postgres, and a value added in one transaction is not usable in it.
-- The table that uses them is V2.97 and the grant that names them is V2.98. Nothing else may go in
-- this file.
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'CREATE_SHARE';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'READ_SHARE';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'UPDATE_SHARE';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'DELETE_SHARE';
