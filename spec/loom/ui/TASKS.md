# MetaLoom — Loom UI Task List

> Work items for the **Loom UI** (`loom-ui/`, React + TypeScript + Vite),
> weighted toward the **pipeline editor** and the **Cortex node / processor**
> surfaces that the recent pipeline refactor touched.
> Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:**
> [LOOM_UI.md](LOOM_UI.md) (UI spec) ·
> [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md) (editor detail) ·
> [../../features/pipeline/PIPELINE.md](../../features/pipeline/PIPELINE.md) (pipeline engine) ·
> [../../features/pipeline-nodes/NODES.md](../../features/pipeline-nodes/NODES.md) (node system) ·
> [../../features/pipeline/PIPELINE_TASKS.md](../../features/pipeline/PIPELINE_TASKS.md) (engine tasks).
>
> **Recent refactor this list responds to.** The pipeline mechanism was reworked
> around a new `loom/pipeline` engine (`PipelineRunEngine`, `NodeDispatcher`,
> `PipelineSegmenter`). Two concepts are new and have **no UI at all** yet:
>
> - **Node affinity** — every `PipelineGraphNode` carries an `affinity` group
>   string (default `"default"`). Connected nodes sharing a group are collapsed
>   into a `PipelineSegment` and dispatched to one worker so intermediate
>   artifacts (e.g. decoded frames) never round-trip through Loom.
> - **Node restriction** — a Cortex worker declares which node *kinds* it will
>   run. Today that is a single whitelist, held in the existing
>   `ProcessorRegistration.nodeKinds` field (from `--node-kinds` /
>   `CORTEX_NODE_KINDS`); these tasks **rename it to `nodeWhitelist`** and add a
>   complementary **`nodeBlacklist`**. `ProcessorRegistry.selectProcessorForKinds`
>   routes a segment only to a worker permitted to run **all** of its kinds. Today
>   the restriction is whitelist-only, **in-memory only**, and configurable only
>   at Cortex startup.
>
> **Ordering.** Tasks 1–7 are the pipeline/node focus. Task 1 (persist processor
> instances + their restrictions) is the prerequisite for dynamic UI-driven
> restriction (Tasks 2–4). Tasks 8+ are broader UI gaps.

---

## Task 1: Persist registered Cortex instances and their node-kind restrictions

**Argumentation Summary:** Registered Cortex/processor instances live **only in
`ProcessorRegistry`'s in-memory `ConcurrentHashMap`** and die with the Loom
process. Their node-kind whitelist (`ConnectedProcessor.nodeKinds`) is set once,
at WebSocket registration, from what the worker announces — there is no `ProcessorDao`,
no Flyway migration, and no way to remember or override a worker's restriction
across reconnects or restarts. This blocks every dynamic-restriction story: you
cannot set a worker's `nodeWhitelist` / `nodeBlacklist` from the UI if there is
nowhere durable to record the decision, and a restart would silently discard it.

**Improvement Summary:** Introduce a persisted `cortex_instance` (processor)
record keyed by the stable `nodeId`, tracking identity plus an
administrator-managed **`nodeWhitelist` and `nodeBlacklist`** (each a set of node
kinds), and reconcile the in-memory registry against it on registration. This is
greenfield — mirror the existing DAO/Flyway/DTO layering used by simpler
resources such as [blacklist](../../features/pipeline/PIPELINE.md) and `person`.

```
This is a backend-first task that unblocks the UI tasks below; do it in the
Loom server, then expose it (Task 2) and consume it (Task 4).

1. Flyway migration `V2.33__add_cortex_instance.sql` (next free version after
   V2.32) in loom/db/flyway/.../db/migration/:
   - Table `cortex_instance` (uuid PK, node_id UNIQUE, name, host, priority,
     last_seen, state, first_registered, meta/created/creator columns to match
     the AbstractJooqDao convention).
   - A representation of the `nodeWhitelist` and `nodeBlacklist`. Prefer a child
     table `cortex_instance_node_kind (instance_uuid, node_kind, list)` where
     list ∈ {WHITELIST, BLACKLIST}, so a kind can be queried and indexed, over an
     opaque JSONB blob.
   - `MANAGE_CORTEX_INSTANCE` / `READ_CORTEX_INSTANCE` permissions, following the
     `*_PIPELINE` permission pattern (see V2.19).

2. DAO layer:
   - `loom/db/api/.../model/cortex/CortexInstance` + `CortexInstanceDao`
     (interface), exposed on `DaoCollection`.
   - jOOQ impl in `loom/db/jooq/.../dao/cortex/` (regenerate jOOQ after the
     migration). Provide `loadByNodeId`, `findAll`, and upsert.
   - ⚠️ `loom/db/memory` has no pipeline DAOs; decide whether cortex-instance
     persistence needs a memory impl or is jOOQ-only, and state it explicitly.

3. Wire persistence into ProcessorRegistry.register(...): on REGISTER, upsert
   the cortex_instance row (identity + last_seen + state) and, when an
   admin-managed restriction exists in the DB, apply it to the in-memory
   ConnectedProcessor (nodeWhitelist / nodeBlacklist) rather than blindly
   trusting what the worker announced. The worker-announced set becomes the
   DEFAULT, the DB record is the OVERRIDE.

4. Document the new persistence + restriction model in
   ../../features/pipeline-nodes/NODES.md — add a "Node Restriction &
   Cortex-Instance Persistence" section covering: the rename of the announced
   nodeKinds field to nodeWhitelist, the new nodeBlacklist, the cortex_instance
   table, the DAO, and the startup-config vs DB-override precedence. Cross-link
   it from PIPELINE.md §12.
```

**References:**
- [../../features/pipeline-nodes/NODES.md](../../features/pipeline-nodes/NODES.md) (add the new section here)
- [../../features/pipeline/PIPELINE.md](../../features/pipeline/PIPELINE.md) §12 (processor protocol)
- `loom/services/rest/.../service/impl/ProcessorRegistry.java` (`ConnectedProcessor.nodeKinds`, `accepts`)
- `loom/db/flyway/.../db/migration/V2.32__*.sql` (latest migration; new one is `V2.33`)
- `loom/db/api/.../model/blacklist/BlacklistDao.java`, `loom/db/jooq/.../dao/` (DAO pattern to mirror)

**Test Requirements:**
- `CortexInstanceDaoTest` — CRUD via the generic DAO harness, plus `loadByNodeId`,
  upsert-on-reregister (no duplicate rows for the same `nodeId`), and
  `nodeWhitelist` / `nodeBlacklist` round-trip.
- Flyway migration applies cleanly on a fresh DB and is idempotent within the
  test container (`testdatabase-provider` pool `loom-dev`).
- `ProcessorRegistry` test: a worker reconnecting picks up its persisted DB
  restriction override (nodeWhitelist / nodeBlacklist), not just its announced set.

---

## Task 2: REST endpoints to read and manage Cortex-instance node restrictions

**Argumentation Summary:** `ProcessorEndpoint` exposes only `GET /api/v1/processors`
and `GET /api/v1/processors/:uuid`, both reading ephemeral in-memory state.
There is no write surface, so an operator cannot change a worker's `nodeWhitelist`
or `nodeBlacklist` without editing its `--node-kinds` flag and restarting it.
Once Task 1 gives us a durable record, the UI needs endpoints to mutate it.

**Improvement Summary:** Add authenticated CRUD-style routes for the persisted
restriction, and enrich the read model so the UI can show each worker's
`nodeWhitelist`, `nodeBlacklist`, and whether it is currently connected.

```
1. Extend loom/services/rest/.../endpoint/impl/ProcessorEndpoint.java:
   - GET  /api/v1/processors           -> ProcessorListResponse, now merged with
                                          persisted cortex_instance rows so
                                          offline-but-known workers still appear.
   - GET  /api/v1/processors/:nodeId    -> ProcessorResponse incl. nodeWhitelist
                                          / nodeBlacklist.
   - PUT  /api/v1/processors/:nodeId/restrictions
          body { nodeWhitelist: string[], nodeBlacklist: string[] } -> persists
          via CortexInstanceDao and re-applies to the live ConnectedProcessor if
          connected. Permission MANAGE_CORTEX_INSTANCE.
   - DELETE /api/v1/processors/:nodeId  -> forget a persisted (offline) instance.
   Add each new secured path to the individually-enumerated auth list (a new
   processor route is unauthenticated until listed — see PIPELINE.md §11).

2. DTOs in loom-shared/rest-model/.../processor/: extend ProcessorResponse with
   nodeWhitelist / nodeBlacklist / persisted flag; add
   ProcessorRestrictionUpdateRequest.

3. When restrictions change on a connected worker, decide and document whether a
   STATE_CHANGE / re-dispatch is needed for in-flight segments, or whether the
   change only affects future selection (selectProcessorForKinds).
```

**References:**
- `loom/services/rest/.../endpoint/impl/ProcessorEndpoint.java`
- `loom/services/rest/.../service/impl/ProcessorRegistry.java` (`selectProcessorForKinds`, `toResponse`)
- `loom-shared/rest-model/.../processor/ProcessorResponse.java`, `ProcessorListResponse.java`
- [../../features/pipeline/PIPELINE.md](../../features/pipeline/PIPELINE.md) §11 (auth-path enumeration), §12.1

**Test Requirements:**
- Endpoint tests: list merges live + persisted; `PUT /restrictions` persists and
  is reflected in a subsequent `selectProcessorForKinds`; permission enforced
  (401/403 without `MANAGE_CORTEX_INSTANCE`); `DELETE` only allowed for offline
  instances.

---

## Task 3: Rename the whitelist and add a `nodeBlacklist`

**Argumentation Summary:** Restriction today is a single whitelist under the
awkwardly-named `nodeKinds` field: `ConnectedProcessor.accepts(kind)` returns
true when `nodeKinds` is null/empty (unrestricted) or contains the kind. The
user-facing model is **whitelist *and* blacklist** — e.g. "this GPU box runs
everything *except* `whisper`". You cannot express that with a whitelist without
enumerating every other kind, which breaks the moment a new node type is added.
The field name should also say what it is.

**Improvement Summary:** Rename the existing `nodeKinds` field to `nodeWhitelist`
throughout, add a `nodeBlacklist` with blacklist-wins precedence, thread both
through registration/persistence, and keep the "empty whitelist = unrestricted"
backward-compatible default.

```
1. Rename nodeKinds -> nodeWhitelist everywhere it exists today
   (ProcessorRegistration, CortexOptions, ConnectedProcessor,
   announcedNodeKinds()). This is a mechanical rename; do it first so the
   blacklist reads naturally.

2. ProcessorRegistry.ConnectedProcessor: add Set<String> nodeBlacklist and
   change accepts(kind) to:
     - false if nodeBlacklist contains kind          (blacklist wins)
     - true  if nodeWhitelist is null/empty          (unrestricted)
     - nodeWhitelist.contains(kind) otherwise
   selectProcessorForKinds already uses allMatch(p::accepts), so a segment
   containing any blacklisted kind correctly excludes that worker.

3. Populate nodeBlacklist from the persisted DB record (Task 1) and/or a new
   ProcessorRegistration field; extend the cortex side (CortexOptions +
   --node-blacklist / CORTEX_NODE_BLACKLIST in CortexCLI, alongside the renamed
   --node-whitelist / CORTEX_NODE_WHITELIST) so the blacklist can be set at
   startup too. Keep --node-kinds / CORTEX_NODE_KINDS as deprecated aliases for
   one release so existing deployments do not break.

4. Update announcedNodeKinds() (rename accordingly) in LoomControlChannel and
   document the precedence (DB override > CLI/env; blacklist > whitelist) in
   NODES.md alongside Task 1's section.
```

**References:**
- `loom/services/rest/.../service/impl/ProcessorRegistry.java` (`accepts`, `selectProcessorForKinds`, `ConnectedProcessor.nodeKinds` → `nodeWhitelist`)
- `loom-shared/rest-model/.../processor/message/ProcessorRegistration.java` (`nodeKinds` → `nodeWhitelist`, add `nodeBlacklist`)
- `cortex/api/.../option/CortexOptions.java` (`nodeKinds` → `nodeWhitelist`), `cortex/core/.../cli/CortexCLI.java` (`--node-kinds` → `--node-whitelist`, add `--node-blacklist`), `EnvDefaultProvider.java`
- `cortex/core/.../impl/loom/LoomControlChannel.java` (`announcedNodeKinds`)

**Test Requirements:**
- Unit tests for `accepts` covering blacklist-wins, empty-whitelist-unrestricted,
  and whitelist∩blacklist conflict. Extend `ProcessorWhitelistTest`.
- Cortex CLI test that `--node-whitelist a,b` and `--node-blacklist c` populate
  `CortexOptions`, and that the deprecated `--node-kinds` alias still maps to
  `nodeWhitelist`.

---

## Task 4: Cortex / Processor management view — replace the mock and edit restrictions

**Argumentation Summary:** `loom-ui/src/features/cortex/CortexView.tsx` renders a
**hardcoded `WORKERS` array** — there is no `processors` API client, no live
data, and the LOOM_UI spec's claim that it hits `/api/v1/processors` + WS is
aspirational. With Tasks 1–3 landing real state and a restriction API, the UI
must become the place operators actually set a worker's `nodeWhitelist` /
`nodeBlacklist`, which is the "dynamically from within the loom-ui" requirement.

**Improvement Summary:** Build a `src/api/processors.ts` client, wire `CortexView`
to real data, and add a node-restriction editor per worker (multi-select of node
kinds sourced from `NodeRegistryContext.descriptors`) with a `nodeWhitelist` and
a `nodeBlacklist`.

```
1. src/api/processors.ts: listProcessors(token), getProcessor(token, nodeId),
   updateProcessorRestrictions(token, nodeId, { nodeWhitelist, nodeBlacklist }),
   forgetProcessor(token, nodeId) — mirroring src/api/pipelines.ts conventions
   (authHeaders, handleResponse).

2. CortexView.tsx:
   - Replace WORKERS with a useEffect([token]) load; keep the mock behind a
     mock-service fallback for offline dev (pattern used elsewhere).
   - Show each worker's capabilities AND its nodeWhitelist / nodeBlacklist. Mark
     persisted-but-offline instances distinctly from live ones.
   - Add a "Node restrictions" editor: two Autocomplete/multi-selects of node
     kinds (from useNodeRegistry().descriptors -> kind), one for nodeWhitelist and
     one for nodeBlacklist, saving via updateProcessorRestrictions. Optimistic
     update + ToastContext feedback, matching PipelineEditor's save UX.

3. i18n: add keys under cortex.* to en.json and de.json (no bare fallbacks).
```

**References:**
- `loom-ui/src/features/cortex/CortexView.tsx` (the mock `WORKERS` array)
- `loom-ui/src/api/pipelines.ts` (client conventions), `src/api/config.ts`
- `loom-ui/src/context/NodeRegistryContext.tsx` (`descriptors` for the kind list)
- [LOOM_UI.md](LOOM_UI.md) §3.1 (Cortex Workers row), §14 (still-mocked features)

**Test Requirements:**
- Playwright `cortex-backend.spec.ts`: list renders real processors; opening a
  worker shows its nodeWhitelist / nodeBlacklist; editing them and saving
  round-trips through `PUT /restrictions` and the change is reflected on reload.
- Update the LOOM_UI.md §14 mock table row for Cortex once real.

---

## Task 5: Pipeline editor — assign and visualise node affinity groups

**Argumentation Summary:** Affinity is the headline of the pipeline refactor —
`PipelineGraphNode.affinity` decides how the graph is cut into `PipelineSegment`s
and therefore whether a five-node video pipeline decodes once or five times. Yet
the pipeline editor has no concept of it: nodes cannot be assigned a group, the
definition JSON the UI writes carries no `affinity` field, and the segmentation
is invisible. Authors cannot influence the single most impactful performance
lever the engine exposes.

**Improvement Summary:** Add an `affinity` field to the node config UI and
persist it in the definition, and render segment membership on the canvas
(e.g. colored group outline / badge) so authors can see how their graph will be
dispatched.

```
1. NodeDetailSidebar (Config tab) in PipelineEditor.tsx: add an "Affinity group"
   input (free text with an autocomplete of groups already used in the graph;
   default placeholder "default"). handleParameterChange-style write to
   selected.definition.nodes[].affinity + setDirty(true).

2. Persist it: include affinity in the node objects written to the definition on
   save. Confirm the Loom-side definition schema and PipelineGraphParser
   (reads node.getString("affinity")) agree on the field name and location.

3. Canvas visualisation: group nodes sharing an affinity with a subtle tinted
   container/outline and a small group label on each node, so a segment is
   legible at a glance. Distinguish the implicit "default" group from
   author-assigned ones.

4. Keep it optional and backward compatible — a definition with no affinity
   fields must still load and default every node to "default".
```

**References:**
- `loom-ui/src/features/pipeline/PipelineEditor.tsx` (`NodeDetailSidebar`, `handleParameterChange`, `handleSave`)
- `loom/pipeline/.../graph/PipelineGraphNode.java` (`affinity`, `DEFAULT_AFFINITY`), `PipelineGraphParser.java:137`
- `loom/pipeline/.../graph/PipelineSegmenter.java`, `PipelineSegment.java`
- [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md) §13 (node detail sidebar), §7 (node rendering)

**Test Requirements:**
- Playwright: assign an affinity group to two connected nodes, save, reload, and
  assert the field persists in the definition and the grouping renders.
- A serde check that a UI-written definition with `affinity` fields parses via
  `PipelineGraphParser` into the expected segments.

---

## Task 6: Surface affinity validation warnings in the editor and on save

**Argumentation Summary:** `AffinityValidator` already computes the two ways
affinity goes wrong — `GROUP_SPLIT` (a group silently ran as several segments, so
the round trips the author tried to avoid still happen) and `UNPLACEABLE` (a
segment needs kinds no single connected worker may run, so it parks forever and
the run looks hung). But the validator has **only a test as a caller** — it is
wired into no production path, so authors never see these warnings.

**Improvement Summary:** Wire `AffinityValidator` into pipeline
validation/save on the server, feeding it a real fleet predicate from
`ProcessorRegistry`, and surface the returned warnings in the editor as
non-blocking advisories.

```
SERVER:
1. Invoke AffinityValidator.validate(graph, anyWorkerRunsAll) where
   anyWorkerRunsAll is backed by ProcessorRegistry.selectProcessorForKinds(...)
   != null. Call it from PipelineValidationService (or the run path) and return
   AffinityWarning[] as a non-blocking "warnings" array alongside the existing
   blocking validation errors. Do NOT reject the save on a warning — a pipeline
   can be authored before its workers connect.

UI:
2. PipelineEditor.tsx: render warnings distinctly from errors (amber, in the
   JSON/validation panel and inline on the affected nodes). GROUP_SPLIT ->
   highlight the group; UNPLACEABLE -> highlight the segment and name the
   missing node kinds.
3. The UNPLACEABLE message is fleet-dependent, so refresh it when the processor
   list changes (ties into Task 4 / Task 10).
```

**References:**
- `loom/pipeline/.../graph/AffinityValidator.java` (unwired; `GROUP_SPLIT`, `UNPLACEABLE`), `AffinityWarning.java`
- `loom/services/rest/.../validation/PipelineValidationService.java`
- `loom/services/rest/.../service/impl/ProcessorRegistry.java` (`selectProcessorForKinds`)
- `loom-ui/src/features/pipeline/PipelineEditor.tsx` (`validatePipeline`, validation display)
- [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md) §6 (validation)

**Test Requirements:**
- Server test: a graph whose group spans kinds no worker accepts yields an
  `UNPLACEABLE` warning; a group cut by a cycle yields `GROUP_SPLIT`; a placeable
  graph yields none. (Extend `AffinityValidatorTest` for the predicate wiring.)
- UI test: warnings render as advisories and do not block save.

---

## Task 7: Node palette placeability — show which kinds no connected worker will run

**Argumentation Summary:** With per-worker node restriction, a user can drop a
`whisper` node into a pipeline when no connected worker is permitted to run
`whisper`. Nothing tells them; the segment will simply never be dispatched. The
node palette and command palette list every descriptor kind regardless of
whether the current fleet can execute it.

**Improvement Summary:** Cross-reference the descriptor palette against the live
processor fleet and visually flag node kinds that no connected worker currently
accepts.

```
1. From the processors API (Task 4), derive the set of runnable node kinds:
   union over online workers of (accepts(kind)) across all descriptor kinds.
2. In the Add Node bar and command palette (PipelineEditor.tsx), render
   currently-unplaceable kinds greyed with a tooltip ("No connected worker runs
   this kind"). Do not hide them — a worker may connect later.
3. Keep this purely advisory and reactive to the processor list; never block
   adding the node.
```

**References:**
- `loom-ui/src/features/pipeline/PipelineEditor.tsx` (Add Node bar, `CommandPaletteContent`)
- `loom-ui/src/context/NodeRegistryContext.tsx` (`descriptors`)
- `loom-ui/src/api/processors.ts` (Task 4)
- [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md) §9 (palette)

**Test Requirements:**
- UI test with a mocked processor list: kinds accepted by no online worker render
  disabled/greyed; kinds accepted by at least one render normally; the state
  updates when the processor list changes.

---

## Task 8: Pipeline create / delete / clone UI

**Argumentation Summary:** The editor only *edits* existing pipelines. `POST` and
`DELETE /api/v1/pipelines` exist and `createPipeline`/`deletePipeline` are already
in `src/api/pipelines.ts`, but no UI calls them, so operators must use the raw
API to bootstrap or remove a pipeline — a glaring gap for a visual authoring tool.

**Improvement Summary:** Add create, delete, and clone affordances to the
pipeline list, reusing the existing API client and validation.

```
1. Pipeline list (PipelineEditor.tsx): "New pipeline" button -> dialog (name,
   description) -> createPipeline -> select the new pipeline with an empty/source
   -only canvas. "Delete" (with confirm, mirroring the node-delete dialog) ->
   deletePipeline -> drop from pipelines[] and reselect.
2. "Clone": createPipeline seeded with the selected pipeline's current
   definition (deep-copied nodes/edges/affinity). No backend clone endpoint
   exists, so do it client-side via create-with-definition.
3. Handle the dirty-flag interaction: warn on delete/switch when unsaved.
```

**References:**
- `loom-ui/src/api/pipelines.ts` (`createPipeline`, `deletePipeline` — already present, unused)
- `loom-ui/src/features/pipeline/PipelineEditor.tsx` (pipeline list, delete-confirm dialog)
- [LOOM_UI.md](LOOM_UI.md) §12.3 / §13 (create/delete listed as UI-missing)

**Test Requirements:**
- Playwright: create a pipeline, verify it appears and is selectable; clone it and
  verify the definition matches; delete it and verify removal.

---

## Task 9: Connect the pipeline-events WebSocket for live run status in the editor

**Argumentation Summary:** `GET /api/v1/pipelines/events/ws` exists and
`src/api/pipelineEvents.ts` is present, but the editor never subscribes. Run
history is fetched only on selection change, so a run appears static and node
`isActive` pulsing is never driven by real events. The refactor's whole tracking
pipeline (Cortex → Loom broadcaster → UI) has no consumer here.

**Improvement Summary:** Subscribe to the pipeline-events WebSocket while a
pipeline is selected and drive live node activity + run status from it, with
reconnection.

```
1. In PipelineEditor, open the events WS (optional ?pipeline=<name> filter) on
   selection; close on unmount/switch. Add token as ?token=<jwt> query param
   (WS auth per WEBSOCKET.md).
2. Map NODE_STARTED/NODE_COMPLETED/NODE_FAILED to node isActive/last-result
   styling on the canvas; map PIPELINE_STARTED/COMPLETED and RUN_COMPLETED to a
   live run banner and to refreshing the run history list.
3. Implement reconnect with backoff (LOOM_UI notes Cortex WS lacks auto-reconnect
   — apply the same fix here).
```

**References:**
- `loom-ui/src/api/pipelineEvents.ts`, `loom-ui/src/features/pipeline/PipelineEditor.tsx`
- [../../features/pipeline/PIPELINE.md](../../features/pipeline/PIPELINE.md) §12.2 (events WS, `PipelineEventMessage`)
- [../WEBSOCKET.md](../WEBSOCKET.md) (WS auth), [LOOM_UI.md](LOOM_UI.md) §12.8 (WS not connected)

**Test Requirements:**
- UI test with a mock WS server: emitted node/pipeline events update canvas
  activity and the run banner; a dropped socket reconnects.

---

## Task 10: Live processor updates via WebSocket in the Cortex view

**Argumentation Summary:** Even after Task 4 gives `CortexView` real data, it will
be a point-in-time REST snapshot. Worker heartbeats, state changes
(ONLINE/OFFLINE/paused), and system-status metrics arrive continuously over the
processor protocol, so a static list goes stale immediately.

**Improvement Summary:** Reflect live processor state in `CortexView` — ideally
via a read-only UI-facing broadcast of processor state changes, or by polling
`GET /api/v1/processors` on an interval until such a channel exists.

```
1. Preferred: a UI-facing processor-events stream (server broadcast of
   REGISTER/STATE_CHANGE/STATUS_UPDATE, analogous to PipelineEventBroadcaster).
   If out of scope, poll listProcessors on a short interval and diff.
2. Update worker cards live: state transitions, last-seen staleness, CPU/GPU/IO
   rings from SystemStatusInfo. Show "offline (persisted)" for known-but-absent
   instances (Task 1).
3. Auto-reconnect / resume polling on focus.
```

**References:**
- `loom-ui/src/features/cortex/CortexView.tsx`, `src/api/processors.ts` (Task 4)
- `loom/services/rest/.../service/impl/ProcessorRegistry.java` (`updateState`, `updateStatus`, `heartbeat`)
- [../../features/pipeline/PIPELINE.md](../../features/pipeline/PIPELINE.md) §12.1 (processor messages)

**Test Requirements:**
- UI test: a processor going OFFLINE is reflected without manual reload; a new
  registration appears; metrics update.

---

## Task 12: React error boundaries and global 401 handling

**Argumentation Summary:** There is no React error boundary anywhere, so a render
throw in any feature blanks the whole app. Token expiry is unhandled globally —
a 401 is left to each call site — so an expired session degrades into scattered
failures instead of a clean redirect to login.

**Improvement Summary:** Add error boundaries around the routed outlet (and the
pipeline canvas specifically), and a single 401 interceptor that logs out and
redirects.

```
1. An ErrorBoundary component wrapping <Outlet/> in AppShell, plus a tighter one
   around PipelineCanvas (React Flow throws are common). Friendly fallback +
   "reload" action; report via ToastContext.
2. In src/api/config.ts (handleResponse), on 401 clear auth and redirect to "/"
   (AuthGate) instead of returning a rejected promise every caller must handle.
```

**References:**
- `loom-ui/src/layout/AppShell.tsx`, `src/api/config.ts` (`handleResponse`), `src/context/AuthContext.tsx`
- [LOOM_UI.md](LOOM_UI.md) §10.2 (token expiry pitfall), §12.7/§15 (error boundaries missing)

**Test Requirements:**
- UI test: a forced child throw shows the fallback, not a blank page; a mocked
  401 redirects to login and clears the token.

---

## Task 13: Front-end unit / component test harness (Vitest + RTL)

**Argumentation Summary:** All UI testing is Playwright E2E; there are **no unit
or component tests**. Pure logic that is easy to get wrong and central to the
refactor — `validatePipeline` (cycles, ids), `toRFNodes`/`toRFEdges`, affinity
grouping in the UI, the processor-restriction diff — has no fast test coverage,
so regressions only surface in slow, backend-dependent E2E runs.

**Improvement Summary:** Introduce Vitest + React Testing Library and cover the
pure functions and small components first.

```
1. Add vitest + @testing-library/react + jsdom; wire an "npm run test:unit"
   script separate from test:e2e.
2. First targets (pure, no backend): validatePipeline (all four error types),
   toRFNodes/toRFEdges round-trip, affinity-group derivation (Task 5), the
   version diff (Task 11), and the processors client mapping (Task 4).
3. Establish the pattern (file colocated as *.test.ts / *.test.tsx) and document
   it in LOOM_UI.md §2 so new features add unit tests by default.
```

**References:**
- `loom-ui/package.json`, `loom-ui/src/features/pipeline/PipelineEditor.tsx` (`validatePipeline`, `toRFNodes`/`toRFEdges`)
- [LOOM_UI.md](LOOM_UI.md) §12.6 (no unit/component tests)

**Test Requirements:**
- CI-runnable `test:unit` passing; at least `validatePipeline` and the RF
  converters covered; harness documented for reuse.
