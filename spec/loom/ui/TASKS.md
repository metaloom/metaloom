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
