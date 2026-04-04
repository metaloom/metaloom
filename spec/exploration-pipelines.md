# MetaLoom // Exploration — Custom Processing Pipelines

**Status:** Exploration / RFC
**Date:** April 2026

---

## 1. Problem Statement

Today, Cortex executes a **hardcoded sequential chain** of all registered actions on every media file:

```
hash → consistency → tika → fingerprint → thumbnail → facedetect → whisper → ocr → captioning → llm → scene-detection → dedup → loom
```

**Limitations of the current design:**

| Problem | Detail |
|---------|--------|
| **No parallelism** | Actions run sequentially even when independent (e.g. `sha256` and `md5` have no dependency on each other). |
| **No per-folder configuration** | Every file in every scan path gets the same action chain. A photo archive doesn't need `whisper`; a podcast library doesn't need `facedetect`. |
| **No filtering** | Beyond a hardcoded `FilterHelper.isVideo()` check in `FilesystemProcessorImpl`, there's no way to route files based on MIME type, path glob, or file size. |
| **No user-facing configuration** | Action selection is baked into `ActionCollectionModule` (Dagger DI) and optionally narrowed by a CLI `--actions` flag. No UI, no stored pipeline definitions. |
| **No dynamic ordering** | The `ActionOrder` enum (`UNDEFINED`, `LAST`) is essentially unused. Order is determined by the Dagger module include list. |
| **No pipeline reuse / sharing** | Multi-user or multi-library scenarios cannot have different processing strategies. |

### Goal

Allow users to **define, store, and assign custom processing pipelines** that control:
1. **Which** actions to run
2. In **what order** (with dependency awareness)
3. With **what parallelism** (independent branches run concurrently)
4. On **which assets** (filters: path glob, MIME type, file size, tags, etc.)
5. Scoped to **specific libraries / folders / collections**

---

## 2. Current Architecture Summary

### 2.1 Cortex Side (Worker)

```
FilesystemProcessorImpl
  └── for each file:
        └── for each FilesystemAction in Set<FilesystemAction>:
              └── action.process(ActionContext)
                    ├── isProcessable(ctx)?  → skip if false
                    ├── isProcessed(ctx)?    → skip if already done
                    └── compute(ctx, asset)  → write xattr + push to Loom
```

- **Action registration:** `ActionCollectionModule` includes per-action Dagger modules, each contributing a `FilesystemAction` via `@IntoSet`.
- **Action enable/disable:** `AbstractActionOptions.enabled` (YAML config) and CLI `--actions` whitelist.
- **Inter-action data flow:** Via `LoomMedia` xattr store — actions write results, downstream actions read them through typed `MediaType<T>` wrappers (e.g. `ctx.media(HASH)`, `ctx.media(CONSISTENCY)`).
- **Configuration:** `~/.config/metaloom/cortex.yml` deserialized into `CortexOptions` (global) + per-action `*ActionOptions`.

### 2.2 Loom Side (Server)

- **No pipeline concept.** Loom stores asset metadata, manages libraries/collections/projects, and serves REST/GraphQL/gRPC APIs.
- **Library** = top-level organizational unit (maps to a storage mount / S3 bucket).
- **Collection** = hierarchical grouping within a library.
- **Webhook system** exists (`loom_events` enum) for event-driven notifications.
- **Vert.x EventBus** used internally for service-to-service messaging.
- **Task table** exists but is workflow/collaboration-focused (PENDING/REVIEW/ACCEPTED/REJECTED), not a processing-job queue.

### 2.3 Implicit Action Dependency Graph

Actions have **implicit data dependencies** expressed through `ctx.media(TYPE)` reads:

```
sha512 ──────────────┐
sha256               │
md5                  ├──► loom (needs hash)
chunk_hash           │
                     │
consistency ─────────┤──► fingerprint (needs consistency + hash)
                     ├──► thumbnail  (needs consistency)
                     │
tika ────────────────┤
                     │
facedetect           │
whisper              │
captioning           │
llm                  │
scene-detection      │
                     │
fingerprint ─────────┤──► fingerprint-dedup
sha512 ──────────────┤──► hash-dedup
                     │
[all above] ─────────┴──► loom (final flush)
```

Key dependency chains:

| Action | Depends On |
|--------|-----------|
| `fingerprint` | `sha512` (for identity), `consistency` (skip if incomplete) |
| `thumbnail` | `consistency` (skip if incomplete) |
| `hash-dedup` | `sha512` |
| `fingerprint-dedup` | `fingerprint` |
| `loom` (final sync) | all preceding actions (collects all xattr data) |
| `tika`, `whisper`, `captioning`, `facedetect`, `llm`, `scene-detection` | `sha512` (for Loom lookup in online mode), otherwise independent of each other |

---

## 3. Pipeline Definition Approaches

### 3.1 Option A: Visual Node Editor (ComfyUI / Blender-style)

A graph-based UI where actions are nodes and edges represent data flow and execution order.

```
┌─────────┐     ┌──────────┐     ┌────────────┐
│  hash   │────►│  tika    │────►│            │
│ (sha512)│     └──────────┘     │   loom     │
│         │                      │  (sync)    │
│         │────►┌──────────────┐ │            │
└─────────┘     │ consistency  │►│            │
                └──────┬───────┘ └────────────┘
                       │
              ┌────────┼────────┐
              ▼        ▼        ▼
         ┌────────┐ ┌──────┐ ┌────────┐
         │fingerp.│ │thumb │ │whisper │
         └────────┘ └──────┘ └────────┘
```

**Pros:**
- Intuitive for non-technical users — visual understanding of data flow
- Explicit parallelism: unconnected branches execute concurrently
- Flexible: supports arbitrary graphs, conditional branches, loops
- Precedent: ComfyUI, Blender, Unreal Blueprints, Node-RED — proven UX pattern
- Enables "marketplace" sharing of pipeline templates

**Cons:**
- **Significant UI development effort** — requires a canvas-based node editor (e.g. React Flow, rete.js, litegraph.js)
- Risk of over-engineering for a system that currently has ~15 actions
- Users must understand action I/O contracts to connect nodes correctly
- Harder to version-control (JSON graph serialization is not diff-friendly)
- Mobile/CLI users cannot use it

**Serialization:** Pipeline graphs serialize naturally to JSON:
```json
{
  "nodes": [
    { "id": "n1", "action": "sha512" },
    { "id": "n2", "action": "tika" },
    { "id": "n3", "action": "consistency" },
    { "id": "n4", "action": "fingerprint", "options": { "processIncomplete": false } },
    { "id": "n5", "action": "loom" }
  ],
  "edges": [
    { "from": "n1", "to": "n2" },
    { "from": "n1", "to": "n3" },
    { "from": "n3", "to": "n4" },
    { "from": "n1", "to": "n5", "type": "barrier" },
    { "from": "n2", "to": "n5" },
    { "from": "n4", "to": "n5" }
  ]
}
```

### 3.2 Option B: Pipeline DSL (Configuration File)

A YAML/TOML-based pipeline definition with explicit stages, parallelism groups, and filters.

```yaml
pipeline:
  name: "video-full-analysis"
  description: "Full processing for video libraries"

  filters:
    mime_type: ["video/*"]
    min_size: 1048576        # 1 MB
    path_glob: "/media/videos/**"

  stages:
    - name: "hashing"
      parallel: true
      actions: [sha512, sha256, md5, chunk_hash]

    - name: "validation"
      actions: [consistency]

    - name: "analysis"
      parallel: true
      actions:
        - tika
        - fingerprint:
            processIncomplete: false
        - thumbnail:
            cols: 4
            rows: 4
        - facedetect:
            detectionType: INSPIREFACE
        - whisper
        - scene-detection

    - name: "ai"
      parallel: true
      actions: [captioning, llm]

    - name: "dedup"
      parallel: true
      actions: [hash-dedup, fingerprint-dedup]

    - name: "sync"
      actions: [loom]
```

**Pros:**
- Low implementation cost — reuse existing YAML/Jackson infrastructure in `CortexOptionsLoader`
- Version-control friendly (YAML diffs well)
- CLI-first: works without any UI
- Explicit stage ordering with intra-stage parallelism is simple to reason about
- Easy to validate: ensure each stage's dependencies are satisfied by prior stages
- Could ship as part of `cortex.yml` or as standalone `.pipeline.yml` files

**Cons:**
- Less expressive than a full DAG — no conditional branching, no loops
- Parallelism is coarse-grained (stage-level, not edge-level)
- Less intuitive for non-technical users
- No visual feedback on execution progress within parallel groups

### 3.3 Option C: Directed Acyclic Graph (DAG) Definition (Programmatic / Config Hybrid)

Define pipelines as explicit DAGs (like Airflow, Prefect, or Gradle task graphs) where each action declares its dependencies.

```yaml
pipeline:
  name: "video-full-analysis"
  filters:
    mime_type: ["video/*"]
  
  actions:
    sha512: {}
    sha256: {}
    md5: {}
    chunk_hash: {}
    consistency:
      after: [sha512]
    tika:
      after: [sha512]
    fingerprint:
      after: [consistency, sha512]
      options:
        processIncomplete: false
    thumbnail:
      after: [consistency]
    facedetect:
      after: [sha512]
    whisper:
      after: [sha512]
    captioning:
      after: [sha512]
    llm:
      after: [sha512]
    scene-detection:
      after: [sha512]
    hash-dedup:
      after: [sha512]
    fingerprint-dedup:
      after: [fingerprint]
    loom:
      after: [sha512, tika, fingerprint, thumbnail, facedetect, whisper, captioning, llm, scene-detection, hash-dedup, fingerprint-dedup]
```

The runtime builds a DAG, performs topological sort, and executes with maximum parallelism (any action whose dependencies are satisfied starts immediately).

**Pros:**
- Maximum parallelism — runtime automatically schedules ready actions
- Explicit dependency declarations make the graph self-documenting
- Can be visualized in UI as a Gantt chart or DAG diagram
- Natural fit for the existing implicit dependency structure
- Config-based, version-control friendly
- Could auto-derive default `after` from action metadata (annotated dependencies)

**Cons:**
- Users must understand dependencies to author pipelines correctly
- Cycle detection and dependency validation needed at parse time
- More complex executor implementation (thread pool + dependency tracking)
- Debugging parallel execution failures is harder than sequential

### 3.4 Option D: Preset-based Templates with Overrides

Provide built-in pipeline presets for common scenarios; users pick a preset and optionally customize.

```yaml
pipeline:
  preset: "video-full"           # built-in: video-full, image-standard, audio-podcast, document-archive
  overrides:
    whisper:
      enabled: false
    llm:
      prompts:
        classify:
          model: "gemma2:27b"
          prompt: "Classify: ${name}"
  filters:
    path_glob: "/media/videos/**"
```

Built-in presets:
- **`video-full`**: hash → consistency → tika → fingerprint → thumbnail → facedetect → whisper → scene-detection → captioning → llm → dedup → loom
- **`image-standard`**: hash → tika → facedetect → captioning → llm → dedup → loom
- **`audio-podcast`**: hash → tika → whisper → llm → dedup → loom
- **`document-archive`**: hash → tika → ocr → llm → dedup → loom
- **`hash-only`**: sha512 → sha256 → md5 → chunk_hash → loom

**Pros:**
- Lowest barrier to entry — users don't need to understand the action graph
- Sensible defaults for 90% of use cases
- Override mechanism allows fine-tuning without full pipeline authoring
- Presets can evolve with new actions without user intervention
- Easy to implement: resolve preset → merge overrides → execute

**Cons:**
- Limited flexibility for advanced users
- Preset explosion: many media types × processing strategies = many presets
- Custom action combinations still require full pipeline definition (fallback to B or C)
- Doesn't inherently introduce parallelism (unless presets are internally DAG-aware)

---

## 4. Pipeline Storage & Management

### 4.1 Option S1: Loom-Managed (Server-Side)

Pipelines stored as first-class entities in the Loom database, managed via REST API, edited in Loom Studio UI.

**Schema sketch:**

```sql
CREATE TABLE "pipeline" (
  "uuid"        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
  "name"        varchar NOT NULL UNIQUE,
  "description" varchar,
  "definition"  jsonb NOT NULL,          -- serialized pipeline graph/stages
  "filters"     jsonb,                   -- path, mime, size filters
  "enabled"     boolean DEFAULT true,
  "meta"        jsonb,

  "created"     timestamp NOT NULL DEFAULT now(),
  "creator_uuid" uuid NOT NULL REFERENCES "user"("uuid"),
  "edited"      timestamp NOT NULL DEFAULT now(),
  "editor_uuid" uuid NOT NULL REFERENCES "user"("uuid")
);

-- Assign pipelines to libraries (folder-specific)
CREATE TABLE "library_pipeline" (
  "library_uuid"  uuid NOT NULL REFERENCES "library"("uuid") ON DELETE CASCADE,
  "pipeline_uuid" uuid NOT NULL REFERENCES "pipeline"("uuid") ON DELETE CASCADE,
  "priority"      int DEFAULT 0,         -- when multiple pipelines match, higher wins
  PRIMARY KEY ("library_uuid", "pipeline_uuid")
);

-- Optionally assign pipelines to collections
CREATE TABLE "collection_pipeline" (
  "collection_uuid" uuid NOT NULL REFERENCES "collection"("uuid") ON DELETE CASCADE,
  "pipeline_uuid"   uuid NOT NULL REFERENCES "pipeline"("uuid") ON DELETE CASCADE,
  "priority"        int DEFAULT 0,
  PRIMARY KEY ("collection_uuid", "pipeline_uuid")
);
```

**REST API:**
```
GET    /api/v1/pipelines                 — list all pipelines
POST   /api/v1/pipelines                 — create pipeline
GET    /api/v1/pipelines/:uuid           — get pipeline
PUT    /api/v1/pipelines/:uuid           — update pipeline
DELETE /api/v1/pipelines/:uuid           — delete pipeline
GET    /api/v1/libraries/:uuid/pipelines — list pipelines for library
POST   /api/v1/libraries/:uuid/pipelines — assign pipeline to library
```

**Cortex workflow (online mode):**
1. Cortex scans a folder belonging to library X
2. Fetches assigned pipelines: `GET /api/v1/libraries/:libraryUuid/pipelines`
3. For each file, evaluates pipeline filters → selects matching pipeline
4. Executes the pipeline's action graph instead of the hardcoded chain

**Pros:**
- Single source of truth — pipelines visible to all Cortex workers and UI users
- Enables UI-based pipeline editor in Loom Studio
- Permission-controlled (reuse existing RBAC for pipeline CRUD)
- Pipeline versioning / audit trail via standard `created`/`edited` columns
- Multi-tenant: different users/teams can have different pipelines
- Pipeline execution status could be tracked per-asset in Loom

**Cons:**
- Requires Loom to be running (breaks offline mode unless pipelines are cached)
- Adds coupling between Cortex and Loom — Cortex currently has no dependency on Loom for *configuration*
- More DB migrations, new endpoints, new CRUD layer
- Pipeline changes propagate to all Cortex workers immediately (could be disruptive)

### 4.2 Option S2: Cortex-Managed (Worker-Side Config Files)

Pipelines defined as YAML files alongside the Cortex configuration, on the worker filesystem.

```
~/.config/metaloom/
  cortex.yml                    # global options
  pipelines/
    video-full.pipeline.yml
    image-standard.pipeline.yml
    audio-podcast.pipeline.yml
```

In `cortex.yml`:
```yaml
pipeline_assignments:
  - library: "uuid-of-video-library"
    pipeline: "video-full"
    filters:
      path_glob: "/media/videos/**"
  - library: "uuid-of-photo-library"
    pipeline: "image-standard"
  - default: "hash-only"          # fallback for unmatched files
```

**Pros:**
- Works in offline mode — no Loom dependency for configuration
- Simple to implement — extends existing YAML config loader
- Version-controllable (git-managed config directory)
- No Loom schema changes needed
- Worker-local customization: different Cortex instances can have different pipelines

**Cons:**
- Not centrally managed — each Cortex worker needs its own config
- No UI management (manual YAML editing)
- Pipeline sync across workers is the operator's responsibility
- Cannot benefit from Loom's RBAC for pipeline access control
- No pipeline execution tracking in Loom

### 4.3 Option S3: Hybrid — Loom as Registry, Cortex as Local Cache

Combine S1 and S2: Loom is the primary pipeline registry, but Cortex caches pipeline definitions locally for offline resilience.

**Workflow:**
1. Pipelines are created and edited in Loom (via REST API / UI)
2. Cortex in **online mode**: fetches pipelines from Loom on startup and periodically refreshes
3. Cortex caches fetched pipelines to `~/.config/metaloom/pipelines/.cache/`
4. Cortex in **offline mode**: uses cached pipelines or local-only `.pipeline.yml` overrides
5. Local `.pipeline.yml` files can override or extend Loom-managed pipelines

**Pros:**
- Best of both worlds: central management + offline resilience
- UI editing in Loom Studio, CLI editing via local files
- Graceful degradation: if Loom is unreachable, cached pipelines still work
- Cortex can have local overrides for development/testing

**Cons:**
- Cache invalidation complexity (stale pipelines, conflict resolution)
- Two sources of truth when local overrides exist
- More implementation effort than either S1 or S2 alone

---

## 5. Pipeline Filters

Filters determine which assets a pipeline processes. They should be evaluated **before** entering the action chain.

### Proposed Filter Types

| Filter | Type | Example | Notes |
|--------|------|---------|-------|
| `mime_type` | glob list | `["video/*", "image/jpeg"]` | Matched against Tika-detected or extension-based MIME |
| `path_glob` | glob list | `["/media/videos/**", "*.mp4"]` | Matched against `asset_location.path` |
| `path_regex` | regex | `"^/archive/20[0-9]{2}/"` | For complex path matching |
| `min_size` | bytes | `1048576` (1 MB) | Skip tiny files |
| `max_size` | bytes | `10737418240` (10 GB) | Skip huge files for expensive actions |
| `filename_pattern` | glob | `"IMG_*.jpg"` | Match original filename |
| `tag` | tag list | `["unprocessed", "needs-review"]` | Only process tagged assets (online mode) |
| `exclude_tag` | tag list | `["processed", "blacklisted"]` | Skip already-categorized assets |
| `created_after` | timestamp | `"2025-01-01T00:00:00Z"` | Only process recent assets |
| `library_uuid` | uuid | `"..."` | Scope to specific library |
| `collection_uuid` | uuid | `"..."` | Scope to specific collection |

### Filter Evaluation Order

```
file discovered → mime_type check → path filter → size filter → tag filter → pipeline selected → execute actions
```

Multiple pipelines may match a single asset. Resolution:
- **Priority-based:** highest `priority` value wins
- **First-match:** first matching pipeline in ordered list wins (simpler)
- **All-match:** run all matching pipelines (risk of redundant work, but enables additive processing)

**Recommendation:** Priority-based with a `default` fallback pipeline.

---

## 6. Parallelism Execution Model

### 6.1 Stage-Based Parallelism (Matches Option B)

```
Stage 1 (parallel): [sha512, sha256, md5, chunk_hash]  ← all run concurrently
     ↓ barrier
Stage 2 (sequential): [consistency]
     ↓ barrier
Stage 3 (parallel): [tika, fingerprint, thumbnail, whisper, facedetect, captioning]
     ↓ barrier
Stage 4 (sequential): [loom]
```

- Implementation: `ExecutorService` with `CountDownLatch` or `CompletableFuture.allOf()` per stage
- Simple mental model: stages run top-to-bottom, actions within a stage run in parallel

### 6.2 DAG-Based Parallelism (Matches Option C)

```
sha512 ─────┬──► tika ──────────────┐
sha256      │                       │
md5         ├──► consistency ──┬──► fingerprint ──► fingerprint-dedup ──┐
chunk_hash  │                  ├──► thumbnail                          ├──► loom
            │                  │                                       │
            ├──► facedetect ───┤                                       │
            ├──► whisper ──────┤                                       │
            ├──► captioning ───┤                                       │
            ├──► llm ──────────┤                                       │
            └──► scene-det. ───┘                                       │
            └──► hash-dedup ───────────────────────────────────────────┘
```

- Implementation: `CompletableFuture` composition or a custom DAG executor with a thread pool
- Each action starts as soon as all upstream dependencies complete
- Maximum concurrency: bounded by thread pool size and I/O limits

### 6.3 Resource-Aware Scheduling

Some actions are CPU-bound (hashing), some are I/O-bound (Loom sync), some require GPU (facedetect, YOLO). A resource-aware scheduler could:

- Tag actions with resource requirements: `cpu`, `gpu`, `network`, `disk`
- Limit concurrent GPU actions to 1 (or GPU count)
- Allow many I/O-bound actions in parallel
- This is an advanced optimization — not required for v1

---

## 7. Impact on Existing Architecture

### 7.1 Changes to Cortex

| Component | Change |
|-----------|--------|
| `FilesystemProcessorImpl` | Replace flat loop with pipeline executor (stage-based or DAG-based) |
| `ActionCollectionModule` | Remain as action *registry*; pipeline definition controls which subset to use |
| `CortexAction` | Add optional `dependencies()` method returning required upstream action names |
| `CortexOptions` | Add `pipeline_assignments` section referencing pipeline definitions |
| `ActionContext` | Potentially needs barrier/synchronization support for parallel actions writing to shared `LoomMedia` |
| **New: `PipelineExecutor`** | Orchestrates action execution per pipeline definition |
| **New: `PipelineResolver`** | Evaluates filters and selects pipeline for a given media file |
| **New: `PipelineLoader`** | Loads pipeline definitions from YAML / Loom API |

### 7.2 Changes to Loom (if server-managed)

| Component | Change |
|-----------|--------|
| **New DB table:** `pipeline` | Stores pipeline definitions |
| **New DB table:** `library_pipeline` | Associates pipelines with libraries |
| **New REST endpoints** | CRUD for pipelines, assignment to libraries |
| **New Loom Studio UI** | Pipeline list, editor (visual or form-based) |
| `LoomClient` (Cortex side) | New methods: `listPipelines()`, `getPipeline(uuid)`, `getPipelinesForLibrary(uuid)` |

### 7.3 Thread Safety Considerations

`LoomMedia` (the xattr-backed media wrapper) would be accessed by multiple action threads concurrently. Current implementation likely not thread-safe.

Options:
- **Copy-on-read:** Each parallel action gets a snapshot; merge results after stage/group completes
- **Concurrent map backing:** Replace xattr cache with `ConcurrentHashMap`
- **Write-once semantics:** Each action only writes its own keys (already the case), so concurrent reads are safe if the backing store supports it

**Recommendation:** Since actions already write to distinct xattr keys, a `ConcurrentHashMap`-backed media store should suffice.

---

## 8. Recommendation

### Phase 1: YAML DSL with Stage-Based Parallelism (Option B + Option D presets + Storage S2)

**Why this first:**
- Delivers the core value (configurable pipelines, folder-specific, filters, parallelism) with minimal effort
- Reuses existing YAML infrastructure
- Works offline
- Preset templates (Option D) lower the barrier for common cases
- Implementation scope: ~2-3 new classes in Cortex, no Loom changes

**Deliverables:**
1. `Pipeline` model class + `PipelineDefinition` (stages, filters, action configs)
2. `PipelineLoader` — reads `.pipeline.yml` files from config directory
3. `PipelineResolver` — matches files to pipelines based on filters
4. `StagedPipelineExecutor` — replaces the flat loop in `FilesystemProcessorImpl`
5. Built-in presets: `video-full`, `image-standard`, `audio-podcast`, `document-archive`, `hash-only`
6. `cortex.yml` extension: `pipeline_assignments` section

### Phase 2: Loom Pipeline Registry (Storage S3 - Hybrid)

**Why second:**
- Once pipelines prove useful, central management becomes important for multi-worker deployments
- Requires Loom DB migration + REST endpoints + LoomClient extension
- Enables UI management in Loom Studio

**Deliverables:**
1. `pipeline` + `library_pipeline` DB tables + Flyway migration
2. Pipeline CRUD REST endpoints
3. `LoomClient.listPipelines()` / `getPipelinesForLibrary()`
4. Cortex: fetch-and-cache pipeline definitions from Loom on startup
5. Pipeline list/detail view in Loom Studio

### Phase 3: Visual Node Editor UI (Option A)

**Why last:**
- Highest implementation cost, lowest incremental value over the DSL
- Only justified once there's a large action library or user demand for complex conditional graphs
- Phase 1+2 JSON serialization format can directly back the node editor

**Deliverables:**
1. React-based node editor in Loom Studio (e.g. using React Flow / xyflow)
2. Visual pipeline builder with action palette, drag-and-drop, edge connections
3. Pipeline validation (cycle detection, dependency satisfaction)
4. Export to pipeline JSON (compatible with Phase 1 executor)

---

## 9. Open Questions

1. **Pipeline versioning:** Should pipeline edits create new versions (immutable history) or update in-place? Versioning enables rollback but adds complexity.

2. **Execution tracking:** Should Loom track which pipeline processed each asset and the result per action? This would require a new `asset_pipeline_run` table. Useful for debugging and re-processing.

3. **Re-processing triggers:** When a pipeline definition changes, should Cortex re-process assets that were processed by the old version? Could use a `pipeline_version` hash compared against a stored `last_processed_pipeline_hash` on the asset_location.

4. **Conditional actions:** Should pipelines support conditions beyond filters? E.g. "run `whisper` only if `tika` detected audio duration > 30s". This moves toward a full workflow engine (complex).

5. **Error handling per-pipeline:** Current behavior: log error, continue. Should pipelines support retry policies, dead-letter handling, or alerting?

6. **Multi-pipeline merging:** If an asset matches multiple pipelines (e.g. library-level + collection-level), should they merge (union of actions) or should the highest-priority pipeline win exclusively?

7. **Action parameter scoping:** The same action (e.g. `llm`) could have different parameters in different pipelines. The current `*ActionOptions` are global singletons via Dagger. Need per-pipeline-invocation option overrides in the executor.

---

## 10. Appendix: Comparison Matrix

| Criterion | A: Node Editor | B: YAML DSL | C: DAG Config | D: Presets |
|-----------|:-:|:-:|:-:|:-:|
| Implementation effort | High | Low | Medium | Very Low |
| Parallelism expressiveness | Full DAG | Stage-level | Full DAG | None (unless DAG-backed) |
| User accessibility | High (visual) | Medium (dev-friendly) | Medium | Very High |
| Offline support | Requires export | Native | Native | Native |
| Version-control friendly | Poor (JSON blob) | Excellent | Good | Excellent |
| Extensibility | Excellent | Good | Good | Limited |

| Criterion | S1: Loom-Managed | S2: Cortex Config | S3: Hybrid |
|-----------|:-:|:-:|:-:|
| Central management | Yes | No | Yes |
| Offline support | No | Yes | Yes (cached) |
| Multi-worker consistency | Automatic | Manual sync | Automatic + cache |
| Implementation effort | Medium | Low | High |
| UI management | Natural fit | Requires separate tool | Natural fit |
| RBAC integration | Yes | No | Yes |
