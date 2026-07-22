# TASK_UI_CHAT — Chat / Loom Agent (UI)

UI implementation tasks for the chat feature, per the design in [CHAT.md](CHAT.md).
**All planned tasks (U1–U8) are implemented** — this file now records the shipped
state and the remaining follow-ups. Backend counterpart:
[CHAT_TASKS.md](../../features/chat/CHAT_TASKS.md). Task format for new entries:
[../../TASKS.template.md](../../TASKS.template.md).

## Implementation Status (2026-07-22)

Verification: 102 vitest tests green (incl. SSE parser chunk-boundary cases, message/reference
mappers, skills api), 57 mocked Playwright tests green (incl. the 6 new chat/skills tests),
`tsc --noEmit` and `npm run build` clean. The backend e2e variants are written but require a
running Loom server (see [LOOM_UI.md](LOOM_UI.md) §2). Note: `react-markdown`/`remark-gfm`
had to be installed from the public npm registry — the configured Artifactory mirror timed out.

## Coverage Matrix

| Capability | Backend surface | UI Status | Where |
|---|---|---|---|
| Chat session CRUD | `/api/v1/chats` | Implemented | [api/chat.ts](../../../loom-ui/src/api/chat.ts) + sessions rail; `loadChat` normalizes persisted messages via `toChatMessage`. |
| Assistant replies | `POST /chats/:uuid/stream` | Implemented | [api/agent.ts](../../../loom-ui/src/api/agent.ts) `streamChatMessage` (fetch + ReadableStream + incremental SSE parser); `mockChatService` removed. |
| Markdown rendering | — | Implemented | [MarkdownContent.tsx](../../../loom-ui/src/features/chat/MarkdownContent.tsx) (react-markdown + remark-gfm, raw HTML escaped). |
| Streaming (text/reasoning/tool events) | SSE event protocol | Implemented | State machine in [ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx) `sendMessage`; in-flight bubble renders accumulated deltas, `message_end` swaps in the authoritative message. |
| Reasoning section (hidden by default) | `reasoning_delta` events | Implemented | [ReasoningSection.tsx](../../../loom-ui/src/features/chat/ReasoningSection.tsx) — live indicator, collapsed content, Show/Hide toggle. |
| Entity chips | `references[]` on `tool_end`/messages | Implemented | `tool_end.references` → `toChatReference` → existing `RefChip`, live during the run. |
| Tool action rows | `tool_start`/`tool_end` events | Implemented | Existing `ActionRow` fed by real events (running → done/error + summary). |
| Stop / abort | fetch abort + `DELETE /chats/:uuid/stream` | Implemented | Stop button (`chat-stop-button`) while streaming; abort on session switch/unmount. |
| Skills (CRUD, toggles, library) | `/api/v1/skills` | Implemented | [api/skills.ts](../../../loom-ui/src/api/skills.ts), [SkillsPanel.tsx](../../../loom-ui/src/features/chat/SkillsPanel.tsx), [SkillManagementView.tsx](../../../loom-ui/src/features/skills/SkillManagementView.tsx) at `/skills` (+ sidebar entry). |
| Chat E2E tests | — | Implemented | `e2e/chat-mocked.spec.ts`, `e2e/skills-mocked.spec.ts` (green); `e2e/chat-backend.spec.ts`, `e2e/skills-backend.spec.ts` (need a live server). |

## Open Follow-ups

- **Run the backend e2e variants** (`chat-backend.spec.ts`, `skills-backend.spec.ts`)
  against a live Loom server with demo data and fold them into the routine
  backend-e2e run documented in [LOOM_UI.md](LOOM_UI.md) §2.3.
- **Suggested follow-ups** — `ChatMessage.suggestedFollowUps` still renders but no
  backend source produces them since the mock removal; either emit them from the
  agent (extra event or message field) or drop the UI affordance.
- **Skill markdown editor preview** — the content editor is a plain monospace
  textarea; a side-by-side `MarkdownContent` preview would help authors.
- **Artifactory whitelist** — `react-markdown` + `remark-gfm` (and transitive
  remark/rehype packages) need to be whitelisted on the configured npm mirror for
  reproducible installs.
- **LOOM_UI.md refresh** — §3.1 still lists chat as "`POST /api/v1/graphql` (not yet
  registered)" and [TASK_UI_AI_ML.md](TASK_UI_AI_ML.md)'s coverage matrix predates
  `api/chat.ts` and the streaming endpoint; both should be re-synced.
