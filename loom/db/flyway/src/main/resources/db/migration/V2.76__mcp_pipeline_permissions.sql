-- Pipeline authoring through the MCP server (the chat agent and any external MCP client).
--
-- Separate from the CREATE/UPDATE_PIPELINE quad added in V2.19: letting an agent write a pipeline
-- is a different trust decision from letting a person draw one in the editor, so an administrator
-- has to be able to grant one without the other. The MCP authoring tools require BOTH the base
-- permission and the matching one below, which means granting one of these on its own can never
-- widen what a user is able to do.
--
-- VALIDATE_MCP_PIPELINE gates the dry run. It writes nothing, so it exists as its own value to let
-- an operator hand the agent the design-and-check loop while withholding the ability to store the
-- result.
--
-- Enum additions live in their own migration on purpose: ALTER TYPE ... ADD VALUE cannot run inside
-- a transaction block on older Postgres, and a value added in one transaction is not usable in it.
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'CREATE_MCP_PIPELINE';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'UPDATE_MCP_PIPELINE';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'VALIDATE_MCP_PIPELINE';
