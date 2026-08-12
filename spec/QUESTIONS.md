# Open Questions — answered from the code

Verified against the working tree on 2026-08-08 (HEAD `aefeca40`). Where an existing spec claim
disagrees with what is below, the code was read and the code wins.

---

* What happens when a cortex node dies mid pipeline run. Will the pipeline block or fail? How should we react to this?

---

## 1. How does a node display debug images? What channel do they travel back on? Do the thumbnails live on the Cortex side? Computed on demand? Cached?

**Short answer:** a run started with `debug: true` makes each worker encode a small JPEG/PNG of what
each output port carried, ships it inline in the node task result over the processor WebSocket, and
Loom stores it base64-encoded in `pipeline_node_task.previews` (JSONB). The UI fetches the bytes
per port from a dedicated REST route. Nothing is computed on demand, and nothing is recomputable
after the fact.

### 1.1 Why the channel exists at all

An `artifact/image` port does **not** carry the image — it carries an *absolute path on the worker
that produced it* (`ThumbnailNode`, `ImageManipulationNode` both do `ctx.output(OUT_IMAGE,
path.toString())`). Loom cannot reach that filesystem, so without previews the debugging view could
only name a file nobody can open. See the class doc on
`loom-shared/pipeline-model/.../pipeline/model/NodePreview.java`.

### 1.2 The path, end to end

| Step | Where |
|---|---|
| Operator ticks *debug* on the run | `PipelineRunRequest.debug` (`loom-shared/rest-model/.../pipeline/PipelineRunRequest.java:48`) |
| Engine puts the flag on every dispatched task | `PipelineRunEngine:140` / `:1723` → `NodeTask.capturePreviews` |
| Worker reads it | `NodeInputs.capturePreviews()` → `ctx.capturePreviews()` (`cortex/api/.../NodeContext.java:238`) |
| Runtime auto-generates one preview per image port | `cortex/node-runtime/.../runtime/NodePreviews.java` — opens the local file, downsamples, encodes, caps |
| A node can author its own | `ctx.preview(PORT, …)` / `ctx.preview(PORT, seq, …)` → `NodeContextImpl:231-241`; folded over the generated ones by `NodePreviews.merge` |
| Travels home | `NodeTaskResult.previews` (`loom-shared/pipeline-model/.../NodeTaskResult.java:37`) — the same processor WebSocket result frame as the outputs |
| Persisted | `DaoRunStateStore:234` / `:362` → `NodePreviews.encode` → `pipeline_node_task.previews` JSONB (migration `V2.67__pipeline_node_task_previews.sql`) |
| Served | `GET /api/v1/pipelines/:uuid/runs/:runUuid/items/:itemUuid/tasks/:taskUuid/previews/:portId` (`PipelineEndpoint:172` → `PipelineEndpointService.loadTaskPreview:576`), permission `READ_PIPELINE_RUN` |
| Displayed | `loom-ui/src/features/pipeline/NodeResultStrip.tsx` (the debug card, thumbnail per port) and `NodeResultDetail.tsx` (modal, incl. per-element previews) |

### 1.3 Do the thumbnails reside on the Cortex side?

Both, for different objects:

- The **real artifact** (the full-size thumbnail, depth map, generated image …) stays worker-local,
  under `CORTEX_META_PATH/<node>_bin/…`. It is never uploaded — see `REST_BINARY_HANDLING.md` §1.
- The **preview** is a separate, deliberately lossy copy that lives only in Postgres, inline on the
  task row. It is diagnostics, not catalogue state.

The one exception in the tree is **face crops**: `FacedetectNode.persistCrops` really does upload
them (`client().uploadFaceCrop(...)`), so they become durable attachments in a pool and are served
by `GET /api/v1/detections/:uuid/crop`. Those are *not* previews and survive the run.

### 1.4 On demand? Cached?

- **Not on demand.** The preview is built during node execution, on the machine holding the bytes,
  and only when the run asked for it — `capturePreviews` is a single boolean check otherwise, so a
  production run over 100 000 files pays nothing. A run started without `debug` has no previews and
  no way to obtain them later short of re-running.
- **Cached in the browser, not on the server.** `loadTaskPreview` sets
  `Cache-Control: private, max-age=3600` plus an ETag of `"<taskUuid>-<portId.hashCode()>"` and
  answers **304** on a matching `If-None-Match`. The bytes themselves are the cache — nothing
  recomputes them.
- **Retention:** run-scoped. `pipeline_node_task` is `ON DELETE CASCADE` from `pipeline_run`
  (`V2.31`), so previews die with the run. There is no TTL and no pruner — a debug run keeps its
  previews for as long as the run row exists.

### 1.5 Limits worth knowing

- `NodePreview.MAX_EDGE_PX = 512`, `DEFAULT_MAX_BYTES = 96 KiB` (overridable per worker via
  `ImagePreviews.maxBytes()`). Over the cap the preview is **dropped, never truncated**, and comes
  back as `skipped(reason)` so the UI can say "too large" instead of showing an empty port.
- A preview never fails a task: every encode error becomes a `skippedReason`.
- 🔴 `NodePreviews.build` previews only the **first** element of a `MANY` image port. A node emitting
  N images needs a `ONE` summary port (sam2's `overlay`) or its card understates the result.
- 🔴 `NodeResultStrip` caps the card at `MAX_ROWS = 3` ports **in emit order** and collapses the rest
  into a non-clickable `+n more`. Emit the port you want photographed early.

---

## 2. Does Loom ever touch asset files on the filesystem / S3? How is an asset played back in loom-ui, and who serves it?

**Yes — Loom reads and writes asset bytes itself**, through one seam:
`loom/services/fs/.../storage/BinaryStorage.java`, implemented by `FilesystemBinaryStorage` and
`S3BinaryStorage` (`loom/services/s3/`). Cortex is not in the byte path for uploads or playback.

### 2.1 Every place Loom touches bytes

| Caller | What it does |
|---|---|
| `AssetUploadEndpointService` | `POST /assets/upload`, `POST /assets/:uuid/binary/data` — hashes, capacity-checks, writes content-addressed |
| `AssetBinaryEndpointService.downloadByAssetUuid:204` | `GET /assets/:uuid/binary/data` — the playback/download route |
| `AttachmentEndpointService` | `POST /attachments`, `GET /attachments/:uuid/data` |
| `DetectionEndpointService:116` | `GET /detections/:uuid/crop` — the stored face crop |
| `BinaryReclaimer` | deletes bytes once the last `asset_location` row pointing at them is gone |
| `BinaryStorageResolver` | picks the backend per pool; also reports `freeSpace()` for capacity checks |

`BinaryStorage` is `store / exists / size / read(offset,length) / localPath / delete / freeSpace` —
i.e. Loom does real file and object I/O, it does not merely hand out paths.

### 2.2 Who serves playback

Loom does, from `GET /api/v1/assets/:uuid/binary/data` (permission `READ_ASSET_BINARY`):

- `Content-Type` from `asset_location.mime_type`, `Accept-Ranges: bytes` always advertised;
- `Range` honoured — **206** with `Content-Range`, **416** when unsatisfiable — which is exactly what
  a `<video>` element needs to seek;
- filesystem pools take the zero-copy path (`response.sendFile(path, offset, length)`); S3 pools are
  pumped through in 64 KiB chunks rather than buffered, so a multi-GB object does not land on the
  heap.

### 2.3 What the UI actually does with it

- `assetBinaryUrl(uuid)` (`loom-ui/src/api/assets.ts:246`) returns the server-absolute URL. It is
  used as an `<img src>`, which cannot carry an `Authorization` header — the request authenticates
  with the HttpOnly `__Host-loom_token` cookie, so it only resolves for a same-origin API base.
- **Images**: `AssetDetail.tsx` renders `<ZoomableImage src={asset.url}>`; grids use `AssetThumbnail`.
  The `url` is only populated for `type === "image"` (`features/assetDetail/helpers.ts:46-47`,
  `LibraryView.tsx:37`, `WorkflowView.tsx:92`).
- 🔴 **Video and audio are not played back at all.** `AssetDetail.tsx` renders a `MediaPlaceholder`
  with a fake play button and a *simulated* progress timer (`// Simulated video progress`, `:549`) —
  there is no `<video>`/`<audio>` element anywhere in the UI. The server side is ready (Range
  support is implemented and tested); only the client is missing. Same for thumbnails: a grid tile
  loads the full original, because no node uploads a rendition.

### 2.4 The important caveat

An asset **discovered by a pipeline scan has no bytes Loom can serve.** `DaoAssetSink.persist`
creates the `asset` row from the sha512 a hash node produced, with origin `"pipeline"`, and writes
**no** `asset_location` row and no pool. `GET /assets/:uuid/binary/data` therefore answers 404 ("No
binary found for asset") and the UI falls back to the placeholder. Only the upload paths (A/B in
`REST_BINARY_HANDLING.md` §3) and an explicit `POST /assets/:uuid/binary` registration produce a
servable asset.

---

## 3. Where are AssetPools configured? Are they the single source of truth for origins? How do source nodes reference them — is there a common id?

### 3.1 Where they are configured

`asset_pool` is a **database table**, not configuration: `V2.20__add_asset_pool.sql`, extended by
`V2.24` (free/used space) and `V2.63` (`library.pool_uuid`, `attachment_binary.pool_uuid`). A pool is
`name` plus **either** `fs_path` **or** `s3_bucket`/`s3_region`/`s3_endpoint` — a CHECK constraint
enforces exactly one, so the row *is* the filesystem-vs-S3 discriminator.

Managed at runtime through:

- REST `/api/v1/pools` (`AssetPoolEndpointService`);
- the UI at `loom-ui/src/features/assetPools/AssetPoolsView.tsx`, and as an optional override in the
  upload screen (`UploadView.tsx`, requires `READ_ASSET_POOL`);
- demo rows in `DemoDatabaseInitializer:305-320` (two fs pools, one S3 pool).

Credentials are **never** in the database: `LOOM_S3_ACCESS_KEY` / `_SECRET_KEY` / `_ENDPOINT` /
`_REGION` / `_PATH_STYLE` (`loom-shared/api/.../options/S3Options.java`), with
`LOOM_STORAGE_UPLOAD_DIR` as the `pool_uuid IS NULL` fallback. `BinaryStorageResolver` is the only
place that joins the two halves — and it caches one backend per pool uuid forever, so **editing a
pool's bucket or endpoint needs a Loom restart**.

### 3.2 Are they the source of truth for origins?

**No — and this is the crux.** A pool answers *"where do bytes Loom stores go?"*, not *"where does
content come from?"*. Concretely:

- `asset_location.pool_uuid` is only written by the upload path; the `path` column is then a key
  *within* the pool.
- A library points at the pool its uploads land in (`library.pool_uuid`, nullable = the legacy local
  upload dir).
- Content **discovered** by a source node has no pool at all — see §2.4. Its real origin is the
  source node's own options, which live in the pipeline definition, not in `asset_pool`.

So there are two independent, unlinked notions of "where the media is": the pool (Loom's storage) and
the source node's roots (Cortex's scan targets).

### 3.3 How do source nodes reference pools?

**They don't.** There is not a single reference to a pool uuid anywhere under `cortex/` — grep for
`poolUuid|pool_uuid|assetPool` across the whole worker tree returns nothing. Source nodes are
configured entirely by their own node options:

| Node | Options | Credentials |
|---|---|---|
| `filesystem-source` | `path`, `pathGlobs`, `emitStates`, `indexPath` (`FilesystemSourceNodeOptions`) | — |
| `s3-source` | `bucket`, `prefix`, `suffixes`, `emitStates`, `startAfter`, `useEvents` | `CORTEX_S3_*` on the worker |
| `gdrive-source` / `onedrive-source` | drive/folder ids | `CORTEX_GDRIVE_*` / `CORTEX_ONEDRIVE_*` |

A run can further narrow them — `PipelineRunRequest.path` / `pathGlobs` / `mediaUuids` are merged in
by `SourceOptionsResolver` (precedence `mediaUuids > pathGlobs > path`).

**There is no common id.** An S3 pool and an `s3-source` node can point at the same bucket, but they
are configured separately (pool row + `LOOM_S3_*` on Loom; node options + `CORTEX_S3_*` on the
worker) and nothing correlates them. If you want a source scan and Loom's storage to agree on a
location, that is currently an operator convention, not a modelled relationship.

---

## 4. Is there a dry-run / read-only mode for Cortex — nothing written, nothing moved?

**Not as a usable global mode.** There are two things called "dry run" and neither is it.

### 4.1 `CortexOptions.dryrun` — real, but nearly unreachable and nearly unwired

- Declared at `cortex/api/.../option/CortexOptions.java:43`, exposed on every node as
  `CortexNode.isDryrun()` (`AbstractCortexNode:39`).
- **Only two call sites honour it**, both the file-move path of dedup:
  `HashDedupNode:168` and `FingerprintDedupApplyNode:264` — they log `MOVING …` and skip
  `FileUtils.moveFile`. Nothing else in any of the 34 node kinds consults it.
- **Not settable via the environment.** `CortexEnvOptions` has no mapping for it. The only way in is
  `dryrun: true` in `cortex.yml` at `${user.home}/.config/metaloom/cortex.yml`, which
  `CortexOptionsLoader.load()` reads — and the container/Helm config mount is `/config`, which the
  loader never probes. So in a containerised deployment it cannot be turned on at all.

> ⚠️ Spec drift: `METALOOM_CONTEXT.md` §6 says `CortexOptionsLoader.load()` "has no caller". That is
> stale — `CortexClientModule.options(...)` calls it whenever no explicit `CortexOptions` is injected,
> which is exactly the `CortexMain` path. YAML *is* read; env still wins over it. The `/config` vs
> `~/.config/metaloom/` mismatch in that same gotcha is still true.

### 4.2 Pipeline `dryRun` — a no-op run, not a read-only run

`pipeline_version.dry_run` / `pipeline_run.dry_run` reaches the engine as `PipelineGraph.isDryRun()`.
It means **skip everything and dispatch nothing**: the source result is recorded as
`skipped("dry-run")` (`PipelineRunEngine:720`), `evaluateSkip` returns a skip for every node
(`:1314`), no segment is dispatched (`:1444`), and nothing is adopted from cached results (`:2103`).
Useful for validating that a graph schedules; useless for "run the analysis but do not persist".

### 4.3 What is not suppressible today

Nothing gates these:

- **Loom write-back.** Nodes call `client().createAssetNodeResult(...)`, `upsert…`, `uploadFaceCrop`
  directly (`AbstractMediaNode:156`, `FacedetectNode.persistCrops`). No switch turns those into
  no-ops.
- **Worker-local artifact writes.** `thumbnail`, `depthmap`, `sam2`, `imagegen`, `tts` all write into
  `CORTEX_META_PATH` unconditionally.
- **Sink nodes.** `s3-sink` has no dry-run option of its own.

### 4.4 What you can do today instead

- Run the worker **offline** (`LOOM_HOST` unset): it registers with nothing and idles — no Loom
  writes because there are no tasks.
- Use `CORTEX_NODE_BLACKLIST` (or `CORTEX_NODE_WHITELIST`) to make the destructive kinds unavailable
  on that worker; note that Loom prechecks every kind in the graph against `ProcessorRegistry` and
  rejects the run with **503** if one has no online worker, so this blocks the run rather than
  neutering the node.
- Author a pipeline without the sink/apply nodes.

### 4.5 If a real read-only mode is wanted

It needs to be a first-class flag rather than a per-node courtesy: `CORTEX_DRYRUN` in
`CortexEnvOptions`, plumbed the way `capturePreviews` already is (run request → `NodeTask` →
`NodeInputs` → `ctx`), so it is **per run** and not per worker, and enforced at the two seams that
actually mutate state — the `LoomClient` write methods and the filesystem/S3 write helpers — rather
than trusted to each of 34 node implementations to remember.

---

_Verified: 2026-08-08 · git HEAD `aefeca40`_
