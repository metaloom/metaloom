# Chat Sessions — Publishing, Discovery & Context Composition (Concept)

> Status: **concept** (Phase 3). Phases 1–2 (per-chat coding sandbox + `/api/v1/session/:uuid/*`
> filesystem proxy) are implemented in `loom/agent`. This document specifies how a **chat session** is
> made a first-class, publishable, discoverable entity, and how one session can **compose its context**
> from other published sessions with fine-grained control — chat history, skills and/or filesystem.

## 1. What a "chat session" is

A **chat session** is the durable record behind one chat: its transcript, the skills (and skill
**versions**) that were active, and the coding sandbox filesystem (`/session`) the agent produced.
Today a chat is just a row in `chat`; this phase elevates it to a named, describable, shareable unit.

Naming: the entity is **chat session** everywhere — table `chat_session`, REST under
`/api/v1/chat/sessions`, UI route `/chat/sessions`.

## 2. Auto-generated name & description (on first use)

When a chat session is **first utilized** (its first completed exchange / first coding action), the
agent generates a short **name** and a one-paragraph **description** for it, the same way the loop
already auto-titles a chat after the first exchange
([AgentLoop.generateTitle](../../../loom/agent/chat/src/main/java/io/metaloom/loom/agent/chat/loop/AgentLoop.java)).
`generateTitle` is extended to also emit a `description`; both are persisted onto the `chat_session`
row and are afterwards **editable** by the user in the UI. This gives every session a meaningful
name/description without manual effort, which is what makes the library browsable.

## 3. Data model

New Flyway migration (`V2.38__add_chat_session.sql`; latest is V2.37 — re-run `./setup-pool.sh` after
adding it, then `loom/db/jooq/generate.sh`).

```sql
CREATE TABLE chat_session (
    uuid            UUID PRIMARY KEY,
    chat_uuid       UUID REFERENCES chat(uuid) ON DELETE CASCADE,  -- the owning chat (1:1)
    name            VARCHAR NOT NULL,        -- AI-generated, user-editable
    description     VARCHAR,                 -- AI-generated, user-editable
    tags            TEXT[]  NOT NULL DEFAULT '{}',
    published       BOOLEAN NOT NULL DEFAULT FALSE,
    -- filesystem snapshot (see §6); NULL until first snapshot
    pool_uuid       UUID REFERENCES asset_pool(uuid),
    blob_path       VARCHAR,                 -- tarball of /session within the pool
    fs_size         BIGINT,
    fs_sha256       VARCHAR,
    created         TIMESTAMP NOT NULL,
    creator_uuid    UUID REFERENCES "user"(uuid),
    edited          TIMESTAMP,
    editor_uuid     UUID REFERENCES "user"(uuid)
);
CREATE INDEX idx_chat_session_published ON chat_session(published);
CREATE INDEX idx_chat_session_creator   ON chat_session(creator_uuid);

-- Active skill versions pinned by the session (see §4). Uses skill_version (V2.37).
CREATE TABLE chat_session_skill (
    session_uuid       UUID REFERENCES chat_session(uuid) ON DELETE CASCADE,
    skill_uuid         UUID REFERENCES skill(uuid) ON DELETE CASCADE,
    skill_version      INT NOT NULL,          -- pinned version from skill_version
    PRIMARY KEY (session_uuid, skill_uuid)
);

-- Context composition: which OTHER (published) sessions feed this session, and which parts (see §5).
CREATE TABLE chat_session_context_ref (
    session_uuid          UUID REFERENCES chat_session(uuid) ON DELETE CASCADE,
    source_session_uuid   UUID REFERENCES chat_session(uuid) ON DELETE CASCADE,
    include_chat_history  BOOLEAN NOT NULL DEFAULT FALSE,
    include_skills        BOOLEAN NOT NULL DEFAULT FALSE,
    include_filesystem    BOOLEAN NOT NULL DEFAULT FALSE,
    ordinal               INT NOT NULL DEFAULT 0,   -- apply order (filesystem/skills last-wins)
    PRIMARY KEY (session_uuid, source_session_uuid)
);
```

Permissions follow the flat-enum + service-layer-ownership pattern used by chat/skill: new
`CREATE/READ/UPDATE/DELETE_CHAT_SESSION`; per-object visibility (own rows + published rows) is enforced
in the service. DAO `ChatSessionDao` (`db/api` + `db/jooq`) mirroring `SkillDao`/`ChatDao`, exposed via
`DaoCollection.chatSessionDao()`.

## 4. Active skill version reference

A chat already tracks `meta.activeSkillUuids`; a chat session additionally **pins the version** of each
active skill (`chat_session_skill.skill_version`, from the `skill_version` table added in V2.37). This
makes a shared session reproducible: consumers see and reuse the exact skill version that produced it,
not whatever the author's skill has since become. The session detail page surfaces "Skill X @ v3".

## 5. Context composition (replaces copy-on-install)

There is **no install/fork**. Instead, a chat session's context is **editable** and can **reference**
other published sessions, with fine-grained control over what each contributes:

- For each referenced source session, three independent toggles: **chat history**, **skills**,
  **filesystem**. Any combination is valid (e.g. "pull only the filesystem from A, only the skills
  from B").
- References are **live and reversible** — the user can select/unselect sources and flip toggles at
  any time from the session's context editor; nothing is copied irreversibly.

At run time the `AgentLoop` composes the effective context from the owning session plus its enabled
references (`chat_session_context_ref`, in `ordinal` order):

- **filesystem** → the referenced session's `/session` tarball is restored into the runner before the
  first tool call (namespaced under `/session/refs/<name>/`, or merged last-wins per `ordinal`).
- **skills** → the referenced session's pinned skill versions are added to the active-skill set for
  the run (subject to the caller's read access).
- **chat history** → a condensed transcript of the referenced session is injected as prior context
  (bounded/summarised to respect the context window).

Because references are per-part and editable, a user assembles a working context from several prior
sessions (their own or others') without ever "installing" or duplicating a whole session.

### Endpoints

Under `API_V1_PATH + "/chat/sessions"` (new endpoint in `loom-agent-chat`, ownership-gated like
`SessionFsEndpoint`):

| Route | Meaning |
|---|---|
| `GET /chat/sessions?scope=mine\|published&tag=&q=` | List with **REST filtering** — own sessions, the published library, tag/name search. One endpoint, filtered (no separate `/library`, no `/install`). |
| `GET /chat/sessions/:uuid` | Full detail (name, description, tags, ages, source chat, pinned skill versions, filesystem summary, context refs) |
| `POST /chat/sessions` | Capture the current chat as a session (snapshot `/session`, insert row; name/description default to the AI-generated ones) |
| `PATCH /chat/sessions/:uuid` | Edit name / description / tags |
| `POST /chat/sessions/:uuid/publish` \| `/unpublish` | Toggle `published` (owner only) |
| `GET /chat/sessions/:uuid/context` | The current context refs (source sessions + per-part flags) |
| `PUT /chat/sessions/:uuid/context` | Replace the context refs — add/remove source published sessions and set the chat-history / skills / filesystem toggles |
| `GET /chat/sessions/:uuid/files?path=` etc. | (Phase-2 `/api/v1/session/:uuid/*` proxy is reused for browsing the filesystem) |

## 6. Filesystem persistence

Reuse the existing storage abstraction (`asset_pool` + `AssetBinary` served via Vert.x `FileSystem`;
S3 is stubbed, so local FS today). The runner mounts a persisted `/session` volume; `runnerd` gains
backend-only `POST /snapshot` (tar of `/session`) and `POST /restore` (safe extraction, path-traversal
+ zip-bomb guards). Snapshots are taken on publish (and optionally on reap so an idle session is not
lost). The `SandboxReaper` still evicts only the *runner*; a session's stored tarball is independent
and outlives it.

## 7. loom-ui

New feature area `loom-ui/src/features/chatSessions/` and routes in
[AppShell.tsx](../../../loom-ui/src/layout/AppShell.tsx):

- **`/chat/sessions`** — **list view** (`ChatSessionsView.tsx`). Card/table list showing **name**,
  **description**, a **human-readable relative age** ("edited 3 days ago" — the same date-formatting
  approach already used in `TasksView`/`CollectionsView`/`AssetPoolsView`), tags, and a
  published badge. A **scope switch / tabs** — "My sessions" vs "Library (published)" — backed by the
  `?scope=` REST filter, plus a search box (`?q=`) and tag filter. Client in
  `loom-ui/src/api/chatSessions.ts` mirroring [skills.ts](../../../loom-ui/src/api/skills.ts).
- **`/chat/sessions/:uuid`** — **detail page** (`ChatSessionDetail.tsx`) showing **all** info: name +
  description (inline-editable), tags, created/edited relative ages, the source chat link, **pinned
  skill versions** ("Skill X @ v3"), a **filesystem view** (file tree via the Phase-2
  `/api/v1/session/:uuid/files` + `download`/`preview` proxy), the current **context references**, and
  a **Publish / Unpublish** button.
- **Context editor** — a panel (in the chat workspace and on the detail page, modelled on
  [SkillsPanel](../../../loom-ui/src/features/chat/SkillsPanel.tsx)) to **select / unselect published
  sessions** from the library and, per selected source, three checkboxes: **chat history**, **skills**,
  **filesystem**. Saving `PUT`s the context refs. This is the primary way a user reuses another
  session's work — by referencing parts of it, not installing it.
- **Publish** — the detail page and the session's kebab menu expose publish/unpublish; published
  sessions appear in every user's "Library" tab (server-filtered).

## 8. Security

- Cross-session context is **untrusted content authored by other users**: filesystems are restored as
  files (never as instructions), chat-history injections are clearly delimited as third-party context,
  and referenced skills are still subject to the caller's read access. The agent only acts on any of it
  when the user asks.
- Tarball restore enforces path-traversal, absolute-path, max-size and entry-count guards
  (as `runnerd._safe_path` already does for the workspace).
- List/detail/publish/context routes re-check ownership or published-state in the service layer (RBAC
  is not per-object). Referencing a source that gets unpublished/deleted degrades gracefully (the ref
  is ignored, surfaced in the editor).

## 9. Phasing

1. `chat_session` table + DAO; AI name/description on first use; capture + detail + browse (private).
2. Publish + filtered list (My / Library) + REST filtering; loom-ui list & detail pages.
3. Context composition — `chat_session_context_ref`, the per-part (history/skills/filesystem) toggles,
   the context editor, and run-time context assembly in `AgentLoop`.

Each phase is independently useful and reuses existing Loom machinery (asset-pool storage, skill
versioning, the Phase-1 sandbox orchestrator, and the Phase-2 filesystem proxy).
