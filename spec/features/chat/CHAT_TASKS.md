# CHAT_TASKS — Chat Agent & Skills (Backend)

Backend implementation tasks for the chat feature. **All planned tasks (B1–B9) are
implemented** — this file now records the outcome and the remaining follow-ups.
Design rationale and protocol definitions live in [CHAT.md](../../loom/ui/CHAT.md);
UI counterpart: [TASK_UI_CHAT.md](../../loom/ui/TASK_UI_CHAT.md). Task format for
new entries: [../../TASKS.template.md](../../TASKS.template.md).

## Implementation Status (2026-07-22)

| Task | Status | Notes |
|---|---|---|
| B1 migration + permissions | ✅ done | `V2.36__add_skill.sql`, `CREATE/READ/UPDATE/DELETE_SKILL`, jOOQ codegen regenerated |
| B2 Skill DAO stack | ✅ done | + `loadByName` helper; `SkillDaoTest` (9 tests) |
| B3 Skill REST + client | ✅ done | Owner-scoped service; `SkillEndpointTest` incl. cross-user isolation |
| B4 sharing (publish/library/install) | ✅ done | Copy + `origin_skill_uuid` provenance, name-collision suffix, derived `updateAvailable`; re-install yields a fresh suffixed copy |
| B5 genai-utils streaming | ✅ done (Ollama) | `generateStreamWithTools` + thinking-flag fix in `generateStream` |
| B6 MCP reference envelopes | ✅ done | `MCPToolResults` helper; 4 of 5 tools populate references; `MCPToolReferencesTest` |
| B7 `loom/services/ai` loop | ✅ done | `AgentLoop`/`AgentService`/`SkillPromptBuilder`/`ReferenceExtractor`/`load_skill`; `AiOptions` (`LOOM_AI_*`); `AgentLoopTest` (9 tests, fake streamer) |
| B8 SSE stream endpoint | ✅ done | `POST/DELETE /chats/:uuid/stream` in the ai module (avoids rest↔mcp dependency cycle); `ChatStreamEndpointTest` (sequence, 404, 400, 409+cancel) |
| B9 streaming swap-in + auto-title | ✅ done | `StreamingTurnStreamer` opt-in via `LOOM_AI_STREAMING=true` (default: turn-granular blocking); auto-title after first exchange |

Notable deviations from the original task text:
- The stream endpoint lives in `loom/services/ai` (not `loom-service-rest`) because
  the MCP module depends on the rest module — the endpoint is contributed to the
  REST endpoint set via `AiEndpointModule`.
- `ReferenceExtractor` only consumes the structured `references` field (all loom
  tools now provide it); the name→type fallback heuristic was dropped as fragile.
- Bug fixed along the way: `chat.messages` (jsonb → `JsonArray`) had no jOOQ
  converter, so loading any chat row failed with a Jackson `MappingException` —
  added `JsonArrayConverter` + forcedType `chat\.messages` and regenerated codegen.
- The `user_permission` table's single-permission-per-user PK (see
  [PERMISSIONS.md](../permissions/PERMISSIONS.md) §3.2) forced the endpoint tests
  to grant the second fixture user skill permissions via a group + role.

## Open Follow-ups

- **vLLM streaming with tools** — `generateStreamWithTools` is Ollama-only; the vLLM
  provider inherits the throwing default and must run with `LOOM_AI_STREAMING=false`
  (turn-granular `BlockingTurnStreamer`). Implement via openai-java streamed
  `delta.tool_calls` accumulation in `genai-utils/.../vllm/VLLMLLMProvider.java`.
- **Mid-turn abort for the streaming path** — `StreamingTurnStreamer.blockingForEach`
  cannot be disposed externally; an abort takes effect only after the current turn
  finishes. Wire the loop's cancel flag to a `Disposable`.
- **Transcript normalization** (CHAT.md §8 R4/R5) — `chat.messages` is a single jsonb
  array rewritten per exchange, and replay reconstructs tool results from ≤2 KB
  summaries. Revisit with a normalized `chat_message` table if fidelity or row
  growth hurts.
- **Group-scoped skill library** (CHAT.md §7.4) — optional `skill_group` join to
  scope library visibility to RBAC groups; layers on `published` without schema
  conflict.
- **Live-LLM smoke coverage** — `MCPServerToolCallTest`/`MCPDirectToolCallTest`
  require a local Ollama (`gpt-oss:20b`); consider a scheduled/optional CI job.
