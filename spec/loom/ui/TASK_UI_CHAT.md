# TASK_UI_CHAT — Chat / Loom Agent (UI)

> Open UI work items for the chat feature, re-verified against `loom-ui/src` and `loom-ui/e2e`
> on 2026-08-01. The original U1–U8 backlog is **fully shipped** and collapsed to outcome
> records below; only the follow-ups remain.
> Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:** [CHAT.md](CHAT.md) (design) · [LOOM_UI.md](LOOM_UI.md) ·
> [../../features/chat/CHAT_TASKS.md](../../features/chat/CHAT_TASKS.md) (backend counterpart) ·
> [../../features/chat/CHAT_SESSIONS_CONCEPT.md](../../features/chat/CHAT_SESSIONS_CONCEPT.md)
>
> **Ordering:** Task 1 is a real user-visible defect and comes first; Tasks 2–4 are polish and
> hygiene and are independent of each other.
>
> **Test conventions:** "component test" here means a **mocked Playwright spec** under
> `loom-ui/e2e/*-mocked.spec.ts`; pure logic (SSE parser, message/reference mappers) is covered by
> node-env vitest next to the module. No RTL/jsdom exists in this repo.

## Shipped (outcome records)

| Capability | Landed in |
|---|---|
| Chat session CRUD + history | [api/chat.ts](../../../loom-ui/src/api/chat.ts) (`loadChat` normalizes persisted messages via `toChatMessage`) |
| Streaming assistant replies | [api/agent.ts](../../../loom-ui/src/api/agent.ts) `streamChatMessage` (fetch + ReadableStream + incremental SSE parser, `agent.test.ts`); state machine in [ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx) — `mockChatService` deleted |
| Markdown rendering | [MarkdownContent.tsx](../../../loom-ui/src/features/chat/MarkdownContent.tsx) (react-markdown + remark-gfm, raw HTML escaped) |
| Reasoning section (collapsed by default) | [ReasoningSection.tsx](../../../loom-ui/src/features/chat/ReasoningSection.tsx) |
| Tool action rows + entity chips | `tool_start`/`tool_end` events → `ActionRow` and `RefChip` in ChatWorkspace.tsx (`chatMessageMapper.test.ts`) |
| Stop / abort | Stop button while streaming + abort on session switch/unmount |
| Skills (CRUD, toggles, library) | [api/skills.ts](../../../loom-ui/src/api/skills.ts), [SkillsPanel.tsx](../../../loom-ui/src/features/chat/SkillsPanel.tsx), [SkillManagementView.tsx](../../../loom-ui/src/features/skills/SkillManagementView.tsx) at `/skills` |
| Session management (list, detail, publish, context refs, session files) | [api/chatSessions.ts](../../../loom-ui/src/api/chatSessions.ts), [ChatSessionsView.tsx](../../../loom-ui/src/features/chatSessions/ChatSessionsView.tsx), [ChatSessionDetail.tsx](../../../loom-ui/src/features/chatSessions/ChatSessionDetail.tsx) at `/chat/sessions[/:id]` |
| Agent memory browser | [api/memory.ts](../../../loom-ui/src/api/memory.ts) + [MemoryView.tsx](../../../loom-ui/src/features/memory/MemoryView.tsx) at `/memory` |
| Pipeline graph cards in chat | [PipelineGraphCard.tsx](../../../loom-ui/src/features/chat/PipelineGraphCard.tsx) + `pipelineGraphLayout.ts` (`pipelineGraphLayout.test.ts`) |
| Chat/skills e2e | `e2e/chat-mocked.spec.ts`, `chat-sessions-mocked.spec.ts`, `chat-split-mocked.spec.ts`, `chat-pipeline-graph-mocked.spec.ts`, `skills-mocked.spec.ts`, `skills-version-mocked.spec.ts` (+ `chat-backend.spec.ts`, `skills-backend.spec.ts` needing a live server) |

---

## Task 1: Teach `RefChip` the `memory` reference type

**Argumentation Summary:** The memory tools emit references with `type: "memory"` —
`GetMemoryTool`, `PutMemoryTool` and `ListMemoryTool` all call
`MCPToolResults.reference("memory", uuid, "<scope>:<memoryId>")`. `RefChip` in
[ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx) (~line 43) types
`RefType` as `"asset" | "collection" | "task" | "pipeline" | "annotation"` only, so a memory
reference renders with no icon, the neutral fallback colour, and a click that does nothing —
even though `/memory` ([MemoryView.tsx](../../../loom-ui/src/features/memory/MemoryView.tsx))
is exactly where it should land.

**Improvement Summary:** Add a `memory` case to `RefType`, `iconMap`, `colorMap` and
`handleClick`, navigating to the memory browser scoped to the referenced entry.

```
In loom-ui/src/features/chat/ChatWorkspace.tsx (RefChip):
- Extend RefType with "memory"; add an icon (e.g. PsychologyOutlined/BookmarksOutlined) and a
  token colour to iconMap/colorMap so it is visually distinct from the other five.
- In handleClick, navigate to /memory for a memory ref. The reference label is
  "<scopeKey>:<memoryId>" (see GetMemoryTool) — parse it and pass scope + id so MemoryView can
  preselect the entry; if MemoryView has no such param yet, add one (query string) rather than
  landing on an unfiltered list.
- Audit the reference types the agent can actually emit (rg 'MCPToolResults.reference(' over
  loom/) and add any other missing case in the same change instead of one at a time.
```

**References:** `loom/agent/memory/.../tool/GetMemoryTool.java` (and Put/List) ·
`loom/services/mcp/.../tool/MCPToolResults.java` ·
[MemoryView.tsx](../../../loom-ui/src/features/memory/MemoryView.tsx) · [CHAT.md](CHAT.md)

**Test Requirements:**
- vitest on the mapper: a `tool_end` payload with a `memory` reference maps to a chat reference
  of that type.
- `e2e/chat-mocked.spec.ts` extended: a mocked stream containing a memory reference renders a
  chip with an icon and navigates to `/memory` on click.

---

## Task 2: Resolve `suggestedFollowUps` — emit them or drop the affordance

**Argumentation Summary:** `ChatMessage.suggestedFollowUps` is still parsed
([api/chat.ts:64](../../../loom-ui/src/api/chat.ts)), typed
([types/index.ts:349](../../../loom-ui/src/types/index.ts)) and rendered in two places in
[ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx) (~134, ~223), but no
backend produces the field — `rg suggestedFollowUps loom/ --glob '*.java'` returns nothing since
the mock chat service was removed. The UI carries permanently dead branches, and readers assume a
feature that never appears.

**Improvement Summary:** Decide one way: either have the agent emit follow-ups (message field or
a dedicated SSE event) or delete the field and both render branches.

```
Preferred (cheap): delete.
- Remove suggestedFollowUps from src/types/index.ts, the parse in src/api/chat.ts and both
  render blocks in ChatWorkspace.tsx; drop any i18n keys left unused.
Alternative (if product wants it): have the chat loop emit follow-ups on message_end
  (see spec/features/chat/CHAT_TASKS.md before touching the event protocol), then keep the UI
  and cover it with a mocked stream.
Do not leave it half-wired — that is the current state.
```

**References:** [CHAT.md](CHAT.md) (event protocol) · [../../features/chat/CHAT_TASKS.md](../../features/chat/CHAT_TASKS.md) ·
[api/chat.ts](../../../loom-ui/src/api/chat.ts)

**Test Requirements:**
- If deleted: `npx tsc --noEmit` clean and the chat mocked specs still green.
- If implemented: vitest mapper test for the new field plus a mocked-stream e2e asserting the
  chips render and clicking one sends that text.

---

## Task 3: Add a markdown preview to the skill content editor

**Argumentation Summary:** [SkillManagementView.tsx](../../../loom-ui/src/features/skills/SkillManagementView.tsx)
edits skill content in a plain monospace textarea and does not import `MarkdownContent`, yet skill
bodies are markdown that the agent renders as markdown. Authors cannot see what they are writing
without saving and starting a chat.

**Improvement Summary:** Add a side-by-side (or toggled) preview pane rendering the draft through
the existing `MarkdownContent` component.

```
- In SkillManagementView.tsx, wrap the content editor in a two-pane layout: textarea left,
  <MarkdownContent> of the current draft right; collapse to a tab toggle on narrow viewports.
- Reuse features/chat/MarkdownContent.tsx as-is so escaping/GFM behaviour matches chat exactly.
- Preview the unsaved draft (component state), not the persisted value.
```

**References:** [MarkdownContent.tsx](../../../loom-ui/src/features/chat/MarkdownContent.tsx) ·
[api/skills.ts](../../../loom-ui/src/api/skills.ts) · [CHAT.md](CHAT.md) (skills)

**Test Requirements:**
- `e2e/skills-mocked.spec.ts` extended: typing `# Heading` into the editor renders an `h1` in the
  preview pane before saving.

---

## Task 4: Run the backend e2e variants and re-sync the stale chat rows in TASK_UI_AI_ML.md

**Argumentation Summary:** `e2e/chat-backend.spec.ts` and `e2e/skills-backend.spec.ts` exist but
need a live Loom server with demo data, so they are not part of the routine mocked run and can rot
undetected. Separately, the Chat rows of [TASK_UI_AI_ML.md](TASK_UI_AI_ML.md) (~lines 44–48) still
claim chat is "Missing / mockChatService only", which has been false since `api/chat.ts` and the
streaming endpoint shipped — an agent reading that file will re-implement finished work.

**Improvement Summary:** Fold the backend chat/skills specs into the documented backend-e2e run and
correct the AI/ML coverage matrix.

```
- Run chat-backend.spec.ts and skills-backend.spec.ts against a live server per LOOM_UI.md §2,
  fix whatever drifted, and list them in the backend-e2e set documented there.
- Update the Chat rows of spec/loom/ui/TASK_UI_AI_ML.md to point at api/chat.ts, api/agent.ts,
  api/chatSessions.ts and ChatWorkspace.tsx, and delete the mockChatService references.
```

**References:** [LOOM_UI.md](LOOM_UI.md) §2 (test setup) · [TASK_UI_AI_ML.md](TASK_UI_AI_ML.md) ·
[../../features/chat/CHAT_TASKS.md](../../features/chat/CHAT_TASKS.md)

**Test Requirements:**
- `e2e/chat-backend.spec.ts` and `e2e/skills-backend.spec.ts` green against a running Loom with
  demo data.

---

**Environment note (not a code task):** `react-markdown`, `remark-gfm` and their transitive
remark/rehype packages had to be installed from the public npm registry because the configured
Artifactory mirror timed out; they still need whitelisting there for reproducible installs.

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (collapsed the shipped U1–U8 work to outcome records; the open items are the memory RefChip, suggestedFollowUps, skill preview and backend-e2e/spec re-sync)_
