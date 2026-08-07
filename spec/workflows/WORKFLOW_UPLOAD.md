# Workflow: Upload — From a Dropped File to a Running Pipeline

> **Status**: 🟢 **Built end to end.** A file dropped in the browser is stored, deduplicated by
> content, recorded per library, and — if a pipeline has opted in for its mime type — automatically
> processed. 🟡 The gaps are in *discoverability and control*, not in the mechanism.
> **Scope**: what happens to a file after the bytes arrive, and how the automatic pipeline run is
> selected. This is the one workflow with no human review step: the human's decision *is* the upload.
> **Audience**: AI coding agents working on `AssetUploadEndpointService`, `AssetPipelineTrigger`,
> `PipelineMatcher` and `loom-ui/src/features/uploads/`.

Family index and shared anatomy: [WORKFLOWS.md](WORKFLOWS.md). Status legend: 🟢 built · 🟡 partly
built · 🔵 plan · 🔴 defect · ⚪ stub.

**Out of scope, and where it lives instead:**

| Not here | There |
|---|---|
| The upload **screen**: queue, drag & drop, progress, cancel, retry, XHR | [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) |
| Byte-carrying routes, storage layout, pools, Range downloads, reclaim | [../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) |
| The engine that runs the graph once it is dispatched | [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) |
| Ingesting an existing corpus rather than one file | [WORKFLOW_INGEST_MIGRATION.md](WORKFLOW_INGEST_MIGRATION.md) |
| Pulling from S3 / Google Drive / OneDrive instead | [../concept/NODE_S3SOURCE_PLAN.md](../concept/NODE_S3SOURCE_PLAN.md), [../concept/NODE_CLOUDSOURCE_PLAN.md](../concept/NODE_CLOUDSOURCE_PLAN.md) |
| Authoring the pipeline that gets triggered | [../loom/ui/PIPELINE_EDITOR.md](../loom/ui/PIPELINE_EDITOR.md) |

---

## 0. Executive Summary

| Question | Short answer |
|---|---|
| **Does an upload start a pipeline?** | 🟢 **Yes.** `asset.created` on the Vert.x EventBus → `AssetPipelineTrigger` → `PipelineMatcher` → `runForAsset`. |
| **How does a pipeline opt in?** | ⚠️ Through **JSON in `pipeline_version.meta`**: `{"trigger": {"auto": true, "mimeTypes": ["image/*"]}}`. There is no trigger column, no validation and no editor field. |
| **Which pipeline wins when several match?** | The enabled version with the highest `priority`. |
| **What happens when the same file is uploaded twice?** | 🟢 The asset resolves by SHA-512; a second upload returns **200** (not 201), updates the per-library binary row, reclaims the superseded blob, and **deliberately does not re-run the pipeline**. |
| **What if no worker can run the graph?** | The run is rejected with **503** naming the kinds — but on the auto-trigger path that 503 is only **logged**. The uploader sees a successful upload and no processing. |
| **What if a pipeline is added after the assets?** | 🔴 **Nothing happens.** There is no backfill: the trigger fires on creation only. |

---

## 1. The path, as built

```mermaid
sequenceDiagram
    participant B as browser
    participant E as AssetUploadEndpointService
    participant S as BinaryStorage (pool)
    participant D as Postgres
    participant EB as Vert.x EventBus
    participant T as AssetPipelineTrigger
    participant P as PipelineEndpointService
    participant W as Cortex worker

    B->>E: POST /api/v1/assets/upload (multipart)<br/>libraryUuid, [poolUuid], [origin]
    E->>E: resolve pool from library; checkCapacity
    E->>E: computeSHA512(temp file)
    E->>S: store(temp, sha512, mime) -> locator
    Note over E,S: bytes first, so a storage failure never leaves<br/>an asset row pointing at nothing
    E->>D: loadBySHA512
    alt new content
        E->>D: createAsset (201)
    else known content
        Note over E,D: reuse the existing asset (200)
    end
    E->>D: asset_binary per (asset, library): create or update<br/>update -> BinaryReclaimer.reclaim(old locator)
    alt created only
        E->>EB: publish loom.asset.created {assetUuid, mimeType}
    end
    E-->>B: AssetResponse, 201 or 200

    EB->>T: onAssetCreated (executeBlocking)
    T->>D: latest version of every pipeline (one batch)
    T->>T: PipelineMatcher.selectForMimeType
    T->>P: runForAsset(pipelineUuid, assetUuid, asset.creatorUuid)
    P->>P: unsupportedNodeKinds precheck -> 503 if any kind has no online worker
    P->>W: dispatch SOURCE_TASK / NODE_TASK / SEGMENT_TASK
```

### 1.1 Ordering rules the code encodes deliberately

Each of these is a comment in `AssetUploadEndpointService` and each is load-bearing:

| Rule | Why |
|---|---|
| **Bytes before rows.** `storage.store(...)` runs before any DB write | A storage failure never leaves a dangling asset pointing at a missing file |
| **An asset *is* its content.** `loadBySHA512` before create | `asset.sha512sum` is `UNIQUE`; creating unconditionally made every re-upload — the same file twice, or an import into a second library — fail with a unique violation surfacing as a **500** |
| **One binary row per (asset, library).** Re-uploading into the same library updates rather than duplicates | The `(library_uuid, path)` unique index would reject a duplicate anyway |
| **Reclaim on replace.** `BinaryReclaimer.reclaim(previousPool, previousLocator)` | Storage is reference-counted; an orphaned blob is never freed otherwise |
| **Publish after the binary row exists** | The consumer must be able to resolve the locator, or the worker cannot find the file |
| **Publish only when `created`** | "The asset was processed when it first arrived, and re-running on every duplicate upload would be pure cost" |
| **An unknown pool is a 404**, not a fallback to local disk | A silent fallback would scatter bytes across the wrong storage |

### 1.2 The trigger contract

`PipelineMatcher` (`loom/services/rest/.../service/impl/PipelineMatcher.java`):

```json
"meta": { "trigger": { "auto": true, "mimeTypes": ["image/*", "video/mp4"] } }
```

A version matches when **all** hold:

1. `pipeline_version.enabled` is true,
2. `meta.trigger.auto` is `true`,
3. one `mimeTypes` pattern matches — a bare `*`, a `type/`-prefixed wildcard, or an exact `type/subtype`.

Ties break on `pipeline_version.priority`, highest wins. Only the **latest** version of each pipeline
is considered (`latestVersions()` batches the lookup, so there is no N+1).

---

## 2. Gaps

None of these break the mechanism; all of them make it hard to operate.

| # | Gap | Consequence |
|---|---|---|
| U1 | 🟡 **The trigger is untyped JSON in `meta`.** No column, no schema validation, no `PipelineModelValidator` rule | A typo (`mimetypes`, `autorun`) silently matches nothing. There is no error, no warning and no way to ask "which pipeline handles a JPEG?" |
| U2 | 🔴 **The trigger is invisible in the editor.** [../loom/ui/PIPELINE_EDITOR.md](../loom/ui/PIPELINE_EDITOR.md) has no trigger field | An operator cannot enable auto-processing from the UI at all |
| U3 | 🔴 **A 503 from the auto-trigger is only logged.** `AssetPipelineTrigger` logs `dispatched=false` and the message | The uploader sees a green checkmark and no processing. This is the workflow's worst failure mode |
| U4 | 🔴 **No backfill.** The trigger fires on `asset.created` only | Adding a pipeline never processes assets already in the system. There is no "run this pipeline over library X" from the UI, only a manual per-asset run |
| U5 | 🟡 **Matching is mime-type only.** Not library, pool, collection, size or origin | Every JPEG in the instance gets the same treatment. A library that wants a cheaper graph cannot ask for one |
| U6 | 🟡 **The run user is `asset.getCreatorUuid()`** | Correct for an interactive upload; ambiguous for a service-token or worker-created asset |
| U7 | 🟡 **A duplicate upload never reprocesses** | Deliberate and right for identical bytes — but if the *pipeline* changed since, the asset keeps the old results with no way to notice. Related: the ledger records `producer_version`, so the staleness is detectable; nothing acts on it |

U3 and U4 are the two an operator will hit first.

### 2.1 Suggested shape for U1/U2

Promote the trigger to a validated model without a schema change, then optionally to columns:

1. A `PipelineTrigger` record in `loom-shared/pipeline-model` with `auto`, `mimeTypes`, and room for
   `libraryUuids`, and a `PipelineModelValidator` rule that rejects unknown keys inside
   `meta.trigger`. ⚠️ Structural validation is already duplicated in `PipelineModelValidator` and
   `PipelineValidationService`; add it in **one** place and delegate — do not create a third copy.
2. A trigger panel in the editor writing that model.
3. `GET /api/v1/pipelines/triggers?mimeType=image/jpeg` — "which pipeline handles this?" answered
   without reading JSON by hand.

### 2.2 Suggested shape for U3

The upload response already distinguishes 201 from 200. Extend it with the trigger outcome —
`{triggered: true, runUuid}` / `{triggered: false, reason: "no matching pipeline" | "unsupported kinds: [whisper]"}`
— and surface it in the upload queue row. The information exists at
`AssetPipelineTrigger.handle`; it is thrown away.

⚠️ The publish is fire-and-forget on the EventBus, so the response cannot *wait* for the trigger.
Either make the match synchronous (it is one batched DAO read plus in-memory filtering) and publish
only the dispatch, or push the outcome over the existing pipeline-events WebSocket, which the UI is
already connected to.

---

## 3. Progress Assessment

### Built
- [x] `POST /api/v1/assets/upload` (multipart) with `libraryUuid`, optional `poolUuid` and `origin`
- [x] Pool resolution from the library, capacity check, unknown pool ⇒ 404
- [x] SHA-512 computed before storage; bytes stored before any DB row
- [x] Content-addressed asset resolution (201 create / 200 reuse) — no more unique-violation 500s
- [x] `asset_binary` per (asset, library), update-in-place, `BinaryReclaimer` on replace
- [x] `loom.asset.created` published only for genuinely new content
- [x] `AssetPipelineTrigger` + `PipelineMatcher`: enabled, auto, mime pattern, highest priority
- [x] `unsupportedNodeKinds` precheck rejecting unschedulable graphs with a 503 naming the kinds
- [x] Upload screen: background queue, multi-file drag & drop, progress, cancel, retry ([../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md))

### Open
- [ ] 🔴 **U3** — surface the trigger outcome to the uploader instead of logging it (§2.2)
- [ ] 🔴 **U4** — a backfill path: run a pipeline over a library / collection / query
- [ ] 🔴 **U2** — a trigger panel in the pipeline editor
- [ ] 🟡 **U1** — a validated `PipelineTrigger` model and a "which pipeline handles X?" route (§2.1)
- [ ] 🟡 **U5** — match on library / pool / collection as well as mime type
- [ ] 🟡 **U7** — detect and act on results whose `producer_version` predates the current pipeline
- [ ] Customer docs: "what happens after I upload"
- [ ] Demo data: at least one pipeline carrying a valid `meta.trigger`

---

## 4. Test Setup

| Test | Covers | Command |
|---|---|---|
| `PipelineMatcherTest` 🟢 | `isAutoMatch` and `matches`: bare `*`, `type/` wildcard, exact, disabled version, missing/blank trigger, priority tie-break | `mvn -pl loom/core test -Dtest=PipelineMatcherTest` |
| `AssetUploadEndpointTest` 🟢 | 201 vs 200 on duplicate content, unknown pool ⇒ 404, capacity rejection, binary row per library, permission cases | `mvn -pl loom/core test -Dtest=AssetUploadEndpointTest` |
| `assetUpload.test.ts`, upload Playwright specs 🟢 | Queue lifecycle, progress, cancel, retry ([../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §8) | `cd loom-ui && ./node_modules/.bin/vitest run src/api/assetUpload.test.ts` |
| `AssetPipelineTriggerTest` 🔵 **to write** | Event → match → `runForAsset` with the asset's creator; **no** publish for a duplicate upload; a 503 from dispatch does not throw | — |
| Backfill tests 🔵 | Once U4 exists | — |

🔴 `./setup-pool.sh` before endpoint tests and after any Flyway change. Grant test permissions via
group+role. ⚠️ `npx` stalls — use `./node_modules/.bin/`.

---

## 5. Configuration

| Variable | Default | Effect |
|---|---|---|
| `LOOM_STORAGE_UPLOAD_DIR` | — | Where uploaded bytes land for a filesystem-backed pool |
| `LOOM_SERVER_REST_PORT` | `8092` | The upload endpoint's port |
| `CORTEX_NODE_WHITELIST` / `_BLACKLIST` | — | 🔴 Decides whether the triggered graph is schedulable at all. A kind excluded here turns every matching upload into a silent 503 (U3) |
| `LOOM_WS_STRICT_AUTH` | lenient | Affects the pipeline-events WebSocket the UI would use to report the outcome (§2.2) |

Per-pipeline, in `pipeline_version`:

| Field | Type | Meaning |
|---|---|---|
| `meta.trigger.auto` | boolean | Opt in to auto-processing |
| `meta.trigger.mimeTypes` | string[] | Patterns: `*`, `image/*`, `video/mp4` |
| `enabled` | boolean | A disabled version never matches |
| `priority` | int | Highest wins among matches |

---

## 6. Key Classes Reference

| Class | Package | Purpose |
|---|---|---|
| `AssetUploadEndpointService` | `io.metaloom.loom.rest.service.impl` | The whole upload path; the ordering rules of §1.1 are its comments |
| `AssetEventPublisher` | same | `loom.asset.created`, fields `assetUuid` / `mimeType` |
| `AssetPipelineTrigger` | same | EventBus consumer → match → `runForAsset`, on a worker thread |
| `PipelineMatcher` | same | `selectForMimeType`, `isAutoMatch`, `matches`. Static and package-external so it is unit-testable without a DB |
| `PipelineEndpointService.runForAsset` / `dispatchRun` | same | Shared dispatch core for both the REST run endpoint and the auto-trigger |
| `PipelineEndpointService.unsupportedNodeKinds` | same | The 503 precheck against `ProcessorRegistry` |
| `BinaryReclaimer` | same | Reference-counted reclaim of a superseded locator |
| `BinaryStorage` / `storageResolver` | `io.metaloom.loom.rest.*` | Pool abstraction (filesystem or S3) |
| `PipelineRunEngine` | `io.metaloom.loom.pipeline.engine` | Owns run state once dispatched |
| `uploads` feature | `loom-ui/src/features/uploads/` | The screen |

---

## 7. Conventions and Gotchas

| Area | Gotcha |
|---|---|
| **Bytes before rows** | 🟢 Storage first, DB second. Never reverse it |
| **An asset is its content** | 🟢 `loadBySHA512` before create. Uploading the same file twice is a 200, not an error and not a second asset |
| **Publish only on create** | ⚠️ A duplicate upload deliberately does **not** re-run a pipeline. If a re-run is wanted, it needs an explicit path, not a change here |
| **The trigger is JSON in `meta`** | 🔴 No column, no validation, no editor field. A typo matches nothing, silently (U1, U2) |
| **Only the latest version is considered** | ⚠️ An older enabled version with a trigger is invisible to the matcher |
| **Unschedulable ⇒ 503, but only logged** | 🔴 The uploader is never told (U3). Check the Loom log for `dispatched=false` when "nothing happened" |
| **No backfill** | 🔴 A pipeline added later never sees existing assets (U4) |
| **`asset_binary` is per library** | ⚠️ One asset can hold a binary row per library; `libraryUuid` decides which one a replace targets, and omitting it with several locations is a 400, not a guess |
| **Loom has no shutdown hook** | 🔴 SIGTERM skips `deinit()`. An upload in flight is not drained — only Cortex drains |
| **`POST` creates and updates** | ⚠️ Everywhere in this API |

---

## 8. Where do I find …?

| Need | Look here |
|---|---|
| The upload service | `loom/services/rest/.../service/impl/AssetUploadEndpointService.java` |
| The routes | `loom/services/rest/.../endpoint/impl/AssetEndpoint.java:145` (`/upload`), `:622` (`/:uuid/binary/data`) |
| The trigger | `.../service/impl/AssetPipelineTrigger.java`, `.../PipelineMatcher.java` |
| The dispatch core and the 503 precheck | `.../service/impl/PipelineEndpointService.java:246` |
| The upload screen | `loom-ui/src/features/uploads/`, spec [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) |
| Storage layout, pools, reclaim | [../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) |
| The engine that takes over after dispatch | [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) |
| Event systems overview | [../METALOOM_CONTEXT.md](../METALOOM_CONTEXT.md) §4.3, [../loom/EVENTBUS.md](../loom/EVENTBUS.md) |
| Open tasks | [../tasks/WORKFLOW_TASKS.md](../tasks/WORKFLOW_TASKS.md) W7 |

---

_Git HEAD revision: `21e8a8cd`_
_Last updated: 2026-08-07 (new file — verified against AssetUploadEndpointService, AssetPipelineTrigger, PipelineMatcher)_
