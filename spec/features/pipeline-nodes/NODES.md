# Cortex Node System Specification

> This document describes the Cortex node system for AI agents. It covers the
> node lifecycle, data persistence, configuration, execution model, and all
> implemented nodes. A progress assessment with a task checklist is at the end.
>
> **Source of truth**: the code under `cortex/`. This spec is a companion
> guide, not a replacement for reading the code.

---

## 1. General Node Principle

Cortex nodes are the atomic processing units of the Cortex pipeline. Each node
performs a single, well-defined operation on a media item (e.g. hash, face
detect, OCR, thumbnail). Nodes are composed into a directed acyclic graph
(DAG) by the pipeline system, which controls execution order, parallelism,
and data flow between nodes.

### Two-Level Node Hierarchy

There are two parallel hierarchies:

| Hierarchy | Base Interface | Base Class | Purpose |
|---|---|---|---|
| **Cortex-level** (legacy/CLI) | `CortexNode<I, T>` | `AbstractCortexNode` -> `AbstractFilesystemNode` -> `AbstractMediaNode` | Direct CLI invocation, standalone processing |
| **Pipeline-level** (DAG) | `PipelineNode` | `AbstractPipelineNode` | DAG execution, reactive streams, filter branching |

The pipeline-level hierarchy wraps Cortex-level nodes via
`CortexNodeAdapter`, which adapts a `FilesystemNode` into a `PipelineNode`.
This allows all existing Cortex nodes to participate in pipeline DAGs without
modification.

### Cortex-Level Class Hierarchy

```
CortexNode<I, T>                          (interface: name(), options(), initialize(), isDryrun())
  +-- AbstractCortexNode<I, T>            (holds LoomClient, CortexOptions, node options)
       +-- AbstractFilesystemNode<I, T>   (adds progress tracking, print/error, set(current, total))
            +-- AbstractMediaNode<T>      (the main base: process() -> isProcessable() -> compute())
```

### Key Interfaces

- `CortexNode<I, T extends CortexNodeOptions>` - top-level interface. `I` is
  the input type, `T` is the options type.
- `FilesystemNode<I, T>` extends `SourceNode<I, T>` - adds `process(NodeContext<I>)`
  and `process(LoomMedia, upstreamOutputs)`.
- `SourceNode<I, T>` - marker interface for nodes that yield media items
  (source nodes in the pipeline).
- `PipelineNode` - pipeline-level interface with DAG connectivity, concurrency,
  caching, and reactive `apply()`.

### Node Lifecycle (AbstractMediaNode)

The `AbstractMediaNode.process(NodeContext<LoomMedia>)` method implements a
fixed lifecycle:

1. **enabled check**: If `options().isEnabled()` is false, return
   `ctx.skipped("Disabled").next()`.
2. **exists check**: If the media file does not exist, return
   `ctx.failure("File not found").abort()`.
3. **processable check**: Call `isProcessable(ctx)` (abstract). If false,
   return `ctx.skipped("unprocessable").next()`.
4. **asset fetch**: If not in offline mode, fetch the `AssetResponse` from
   the Loom backend by SHA-512. This allows nodes to short-circuit if the
   result already exists remotely.
5. **compute**: Call `compute(ctx, asset)` (abstract). The node does its
   work and stores outputs via `ctx.output(key, value)`.
6. **result**: The `NodeResult` carries state (SUCCESS/SKIPPED/FAILED),
   outputs map, and origin (COMPUTED/LOCAL/REMOTE).

### NodeContext

`NodeContext<I>` is the per-invocation context:

- `input()` - the typed input
- `media()` - the `LoomMedia` file handle
- `duration()` - elapsed time since context creation
- `output(key, value)` / `output(NodeOutputKey, value)` - accumulate outputs
- `outputs()` - get all accumulated outputs as a map
- `upstreamOutputs()` - outputs from upstream dependency nodes (keyed by
  node id), available when running in a pipeline
- `upstreamOutput(nodeId, key)` - convenience accessor for a specific
  upstream output
- `skipped(reason)` / `failure(cause)` / `origin(ResultOrigin)` - state setters
- `next()` / `abort()` - produce `NodeResult` (continue or abort)
- `print(result, msg)` / `info(msg)` - progress logging

### NodeResult (Cortex-level)

- `ResultState`: `SUCCESS`, `SKIPPED`, `FAILED`
- `ResultOrigin`: `COMPUTED` (locally computed), `LOCAL` (from xattr/sidecar
  cache), `REMOTE` (fetched from Loom backend)
- `getOutputs()` - map of output key -> value
- `get(NodeOutputKey)` / `getOutput(String)` - typed accessors

### NodeOutputKey

Type-safe output key: `NodeOutputKey.of("sha512", String.class)`. Each node
declares its output keys as `public static final` constants. The string key
is also used for xattr/sidecar persistence.

> ⚠️ The `<T>` is **advisory only** — `NodeContextImpl` discards `valueType()` and every read is an
> unchecked cast. For the complete per-node input/output type reference, the connector
> (`contentType`) model, and the hop-by-hop account of where typing is lost, see
> [../pipeline/NODE_DATA_TYPES.md](../pipeline/NODE_DATA_TYPES.md).

---

## 2. Node Data Persistence

### Persistence model (typed component + node-result ledger)

> ⚠️ The former `MetaStorage` / `LoomMetaKey<T>` / per-node `*MetaStorage`
> system (xattr / AVRO / FS sidecars written onto the media file) has been
> **removed**. Node results are no longer stored on the file. They are
> persisted into the **Loom backend** as typed **asset components** plus a
> node-agnostic **processing ledger**, and cached in-heap for the worker's
> lifetime (see LocalResultCache below).

Every persisting node follows the same two-step shape inside
`compute(ctx, asset)`, guarded by `asset != null && client() != null` (a clean
no-op in offline mode):

1. **Write the typed payload** to a per-asset REST sub-resource. The endpoint
   **upserts** a row in the matching `asset_*_comp` (or `detection`) table on its
   natural key, so a re-run replaces its own row instead of duplicating it.
2. **Record the ledger** via `client().createAssetNodeResult(assetUuid, …)` — a
   row in the node-agnostic `asset_node_result` table (`nodeKind`,
   `producerVersion`, `state`, `origin`, `durationMs`, and an advisory
   `result_ref` `{table, uuids}` pointer to the payload). This is centralised in
   `AbstractMediaNode.recordNodeResult(...)` / `resultRef(...)` so nodes do not
   duplicate the boilerplate. It is **best-effort**: a ledger failure is logged
   and never fails the node.

**Per-node payload target** (all reached through the `LoomClient`):

| Node(s) | Payload target (REST → table) | Client method |
|---|---|---|
| Hash (`md5`/`sha256`/`sha512`/`chunk-hash`) | `POST assets/:uuid` update → `asset` row columns | `updateAsset` |
| Fingerprint | `assets/:uuid/fingerprints` → `asset_fingerprint_comp` | `createAssetFingerprintComp` |
| Consistency | `POST assets/:uuid` update → `asset` consistency block | `updateAsset` |
| Facedetect | `assets/:uuid/detections/bulk` → `detection` (upsert) | `bulkCreateAssetDetections` |
| Whisper | `assets/:uuid/transcripts` → `asset_transcript_comp` | `createAssetTranscript` |
| SceneDetection | `assets/:uuid/segments` → `asset_segment_comp` (whole-set replace) | `createAssetSegmentComps` |
| OCR, Tika, Quality, LLM, VLM, Captioning, Facedescription, Sentiment, SceneLayout | `assets/:uuid/json-comps` → `asset_json_comp` (distinct `schemaType`) | `createAssetJsonComp` |
| Thumbnail | ledger only (bytes stay in the local thumbnail cache) | `createAssetNodeResult` |
| S3Sink | uploads upstream artifacts to a bucket and **creates an asset per artifact** (`origin` = the `s3://` URI); `assets/:uuid/json-comps` → `asset_json_comp` (`schemaType=s3-artifact`, `variant` = node id) indexes them on the source asset | `createAsset`, `createAssetJsonComp` |
| TTS | ledger only (generated WAV stays in the local `tts_bin` cache) | `createAssetNodeResult` |
| ImageGen | ledger only (generated PNG stays in the local `imagegen_bin` cache) | `createAssetNodeResult` |
| Depthmap | ledger only (16-bit PNG stays in the local `depthmap_bin` cache); unlike the others it records `producerVersion` = the depth model | `createAssetNodeResult` |
| Script | `assets/:uuid/json-comps` → `asset_json_comp` (`variant` = node id) **+** `assets/:uuid/segments` → `asset_segment_comp` for `TIMEFRAMES` outputs; images stay in the local `script_bin` cache | `createAssetJsonComp`, `createAssetSegmentComps` |
| HashDedup | ledger only (side effect: moves duplicate files) | `createAssetNodeResult` |

The fingerprint (`asset_fingerprint_comp`) and segment (`asset_segment_comp`)
REST resources were added for this. The segment resource is a **whole-set
replace** (`replaceSegmentComps`), so a shorter re-run deletes the surplus rows.
The `detection` and `embedding` DAOs gained conflict-safe `upsertDetection` /
`upsertEmbedding` methods on their natural keys.

⚠️ **Media components are not yet split per node.** The component tables carry
per-component discriminators (`stream_index`, `sector_index`, `seq`,
`frame_number`), so multiple audio tracks / video streams / fingerprint sectors
*can* be stored — but the current nodes emit a single component each: Whisper
writes `streamIndex = 0`, Fingerprint writes `sectorIndex = 0` (whole asset).
Multi-track / multi-stream extraction is open work.

### Pipeline-Level Caching (NodeCacheProvider)

In addition to the per-node `LocalResultCache`, the pipeline system provides a
separate caching layer for `NodeResult` objects:

| Implementation | Description |
|---|---|
| `NoOpNodeCache` | No-op cache (default when caching disabled) |
| `HeapNodeCache` | In-memory Caffeine cache (max 10,000 entries, 60min TTL) |
| `XAttrNodeCache` | Extended attributes on the media file (`loom_cache_{nodeId}`) |
| `SidecarFileNodeCache` | Sidecar `.cache` files in a segmented directory structure |
| `LayeredNodeCache` | Multi-layer cache with back-fill (e.g. heap -> xattr -> sidecar) |

The pipeline executor checks the cache before invoking `process()`. On a
cache hit, the node is skipped and the cached result is used. On success,
the result is cached. Only `COMPLETED` results are cached.

### In-Heap Local Result Cache (`LocalResultCache`)

Separate from the pipeline `NodeCacheProvider`, every result-producing
`AbstractMediaNode` keeps its own **in-heap, worker-lifetime skip cache** via
`io.metaloom.cortex.common.cache.LocalResultCache<V>` — a bounded, thread-safe,
access-order LRU keyed by the media's absolute path.

- On a **hit**, the node re-emits the cached output(s), returns
  `ResultOrigin.LOCAL`, and **skips both re-computation and re-persistence** (the
  durable copy already lives in Loom).
- The cache is **non-durable by design** — it only avoids recomputing within a
  single worker's lifetime; it does not survive a restart. The durable copy is
  the Loom component/ledger written on the first pass.
- `FingerprintNode` keeps its own equivalent LRU (its hit path skips in
  `isProcessable()` rather than re-emitting). Side-effect nodes (`HashDedup`,
  `FingerprintDedup`, `LoomNode`) hold no cacheable result.

What each node caches: hash string / `SHA512` (hash nodes), zero-chunk count
(consistency), recognized text (OCR), Tika content, scene-detection output,
face count+flag snapshot (facedetect), per-face JSON (facedescription), caption,
transcript JSON (whisper), per-prompt outputs (LLM and VLM), metric snapshot (quality),
thumbnail path (thumbnail, itself backed by the durable `.thumb` file).

### Loom Backend Sync

Nodes persist results back to the Loom REST API. Two mechanisms coexist:

1. **Per-node persistence** (the primary path): each node writes its typed
   payload + records the `asset_node_result` ledger inside `compute()`, as
   described under *Persistence model* above. This is how transcripts, faces,
   fingerprints, scenes, OCR/Tika/LLM/caption/quality JSON, hashes and
   consistency reach Loom.
2. **Bulk hash sync** via **`LoomNode`**: a dedicated sink node that accumulates
   `AssetBulkUpdateEntry` objects (SHA-512 plus upstream MD5/SHA-256) and flushes
   them to `bulkUpdateAssets` in batches of 50. This predates per-node hash
   persistence and now overlaps with it — both write hash columns on the `asset`
   row.

> The pipeline-level `LoomBulkSyncCollector` / `syncToLoom()` mechanism still
> exists in `pipeline-common` but is not on the per-node persistence path above.

---

## 3. List of Nodes

> The **Output Keys** column below is a summary. For the authoritative per-key reference — Java type,
> declared connector `contentType`, and the descriptor-vs-runtime gaps — see
> [../pipeline/NODE_DATA_TYPES.md](../pipeline/NODE_DATA_TYPES.md) §5 and §6.

### Processing Nodes (AbstractMediaNode subclasses)

| Node | Module | `name()` | Output Keys | Processable Media | Description |
|---|---|---|---|---|---|
| `MD5Node` | hash | `md5` | `md5` (String) | Any file | Computes MD5 hash; checks Loom first for remote hit |
| `SHA256Node` | hash | `sha256` | `sha256` (String) | Any file | Computes SHA-256 hash |
| `SHA512Node` | hash | `sha512` | `sha512` (String) | Any file | Computes SHA-512 hash |
| `ChunkHashNode` | hash | `chunk-hash` | `chunk_hash` (String) | Any file | Computes chunk hash for dedup |
| `FingerprintNode` | fingerprint | `fingerprint` | `fingerprint` (String) | Video only | Multi-sector video fingerprint; checks in-heap cache, then Loom; persists sector-0 to `asset_fingerprint_comp` |
| `ConsistencyNode` | consistency | `consistency` | `zero_chunk_count` (Long), `is_complete` (Boolean) | Video, Audio | Counts zero chunks to detect incomplete files |
| `ThumbnailNode` | thumbnail | `thumbnail` | `thumbnail_flag` (String), `thumbnail_path` (String) | Video only | Generates contact-sheet thumbnails; checks upstream consistency |
| `FacedetectNode` | facedetect | `facedetect` | `face_count` (Integer), `facedetect_flag` (String), `detections` (String JSON) | Video, Image | Face detection via InspireFace; video uses frame scanning. `detections` carries the boxes plus an explicit `coordinates` marker and (image path only) the dimensions they were measured against, so a downstream node needs no round trip through Loom |
| `FacedescriptionNode` | facedetect | `facedescription` | `face_description` (String) | Video, Image | LLM-based face description; reads upstream `facedetect` output |
| `OCRNode` | ocr | `ocr` | `ocr_text` (String) | Image only | OCR via Tesseract; configurable language and tessdata path |
| `TikaNode` | tika | `tika` | `tika_flags` (String), `tika_content` (String) | Image, Audio, Video, Document | Apache Tika metadata extraction |
| `WhisperNode` | whisper | `whisper` | `whisper_result` (String JSON) | Video, Audio | Speech-to-text via whisper.cpp; persists transcript to Loom |
| `TtsNode` | tts | `tts` | `tts_flag` (String), `tts_path` (String) | Any (needs upstream text) | **Generative**: text-to-speech from an upstream node's text. DE via Orpheus/Kartoffel, EN via Kokoro, behind a FastAPI `/v1/tts` sidecar (`sidecars/tts`). Writes the WAV to the local `tts_bin` cache; ledger only |
| `SentimentNode` | sentiment | `sentiment` | `sentiment_label` (String), `sentiment_score` (Double), `sentiment_result` (String JSON) | Any (needs upstream text) | Polarity of upstream text (POSITIVE/NEUTRAL/NEGATIVE + signed `polarity`). DE via german-sentiment-bert, EN via twitter-roberta, behind a FastAPI `/v1/sentiment` sidecar (`sidecars/sentiment`). Persists to `asset_json_comp` (`variant` = source output key) |
| `DepthmapNode` | depthmap | `depthmap` | `depthmap_flag` (String), `depthmap_path` (String), `depthmap_meta` (String JSON) | Image only | Monocular depth estimation via a FastAPI `/v1/depth` sidecar (`sidecars/depth`); Depth-Anything-V2-Small (Apache-2.0) by default, ZoeDepth for metric mode. Writes a **16-bit PNG in NEARNESS units (65535 = nearest)** to the local `depthmap_bin` cache; ledger only |
| `SceneLayoutNode` | scene-layout | `scene-layout` | `scene_layout_result` (String JSON), `scene_layout_object_count` (Integer), `scene_layout_relation_count` (Integer) | Image only (needs upstream depth + boxes) | **No model, no sidecar** — pure geometry. Joins detector boxes to a depth map and derives FOREGROUND/MIDGROUND/BACKGROUND bands plus pairwise relations (`IN_FRONT_OF`, `BEHIND`, `OCCLUDES`, `CONTAINS`, `LEFT_OF`, `NEXT_TO`, …) with readable `phrases`. Persists to `asset_json_comp` (`schemaType="scene-layout"`). 🔴 Must share an affinity group with its `depthmap` node |
| `DominantColorNode` | dominant-color | `dominant-color` | `dominant_color_result` (String JSON), `dominant_color_hex` (String), `dominant_color_term` (String), `dominant_color_name_en` (String), `dominant_color_name_de` (String), `dominant_color_region_count` (Integer) | Image only | **No model, no sidecar** — pure arithmetic. Deterministic k-means in **CIELAB** over stride-sampled pixels; reports each colour as HEX/RGB/HSL/CIELAB+LCh plus a bilingual name (nearest of the 11 Berlin & Kay basic terms by CIEDE2000 + an LCh-derived modifier → `dark greyish blue` / `dunkles graustichiges Blau`). Measures the whole frame, an optional configured region, and every upstream `detections` box. Persists to `asset_json_comp` (`schemaType="dominant-color"`) |
| `LLMNode` | llm | `llm` | `llm_result_{promptId}` (String) | Any (uses filename) | LLM-based metadata extraction via Ollama; configurable prompts |
| `VlmNode` | vlm | `vlm` | `vlm_result_{promptId}` (String) | Image only | Vision-language model over an OpenAI-compatible endpoint; ships an olmOCR document-transcription preset |
| `QualityNode` | quality | `quality` | `blurriness`, `image_width/height`, `video_width/height/fps/frame_count`, `quality_flag` | Video, Image | Quality metrics (resolution, blurriness via Laplacian) |
| `SceneDetectionNode` | scene-detection | `scene-detection` | `scene_detection` (String) | Video only | Optical-flow scene detection |
| `CaptioningNode` | captioning | `captioning` | `caption_result` (String) | Image, Video | Image captions via SmolVLM; video captions via an OpenAI-compatible VLM (Qwen2.5-VL) with a whole/scene/native `videoStrategy` |
| `ImageGenNode` | image-generation | `imagegen` | `imagegen_flag` (String), `imagegen_path` (String) | Image | **Generative**: text-to-image (`GENERATE`) or image-to-image (`REMIX`) via a diffusers sidecar (`sidecars/ideogram-sidecar`). Writes the PNG to the local `imagegen_bin` cache; ledger only |
| `ScriptNode` | script | `script` | declared per node instance | Any | **Runs a user-supplied script.** GraalJS (`engine=js`) behind a pluggable `ScriptEngine` SPI. Outputs are *declared* as `{key, type}` config and filled at runtime, so one item can emit several multi-valued results (timeframes, text lists, images). Configured per pipeline-node instance via `PipelineConfigurable`. Trusted by default with an opt-in sandbox; always bounded by a wall clock + statement limit |
| `HashDedupNode` | dedup | `sha512-dedup` | (side effects: moves files) | Any (requires SHA-512) | Deduplicates files by SHA-512 hash; moves dups to target folder |
| `FingerprintDedupNode` | dedup | (fingerprint dedup) | (side effects) | Video only | Deduplicates by video fingerprint |
| `LoomNode` | loom | `loom` | (side effects: bulk update) | Any | Syncs hash results to Loom backend in batches of 50 |
| `S3SinkNode` | s3-sink | `s3-sink` | `s3_sink_flag`, `s3_sink_count`, `s3_sink_result` (String JSON) | Any (needs upstream file outputs) | **Sink**: uploads files produced upstream (`thumbnail_path`, `depthmap_path`, `imagegen_path`, `tts_path`, script images) to an S3 bucket and registers each as its own Loom asset. Per-instance config (`PipelineConfigurable`); 🔴 must share a worker with its producer |

### Pipeline-Only Nodes (AbstractPipelineNode subclasses)

| Node | Module | `kind` | Description |
|---|---|---|---|
| `FilesystemSourceNode` | `nodes/filesystem-source` | `filesystem-source` | Source node that enumerates media files from a directory tree or a set of path globs |
| `S3SourceNode` | `nodes/s3-source` | `s3-source` | Source node that enumerates objects from S3-compatible storage (differential listing + optional bucket notifications). Emits `s3://` references; bytes are materialized lazily per worker |
| `AssetSourceNode` | `pipeline-core` | (not registered) | Source node that emits a single configured media asset |
| `LoomFetchNode` | `pipeline-core` | `loom-fetch` (descriptor only) | Fetches user metadata (tags, annotations) from Loom backend |
| `CortexNodeAdapter` | `pipeline-core` | — | Wraps a `FilesystemNode` as a `PipelineNode` for DAG execution |
| `AbstractFilterNode` subclasses | `pipeline-core` | `filter-*` | Filter nodes (see below) |

### Filter Nodes (AbstractFilterNode subclasses)

Filter nodes partition the pipeline into PASS/REJECT branches. They emit a
`FILTER_PASSED` boolean output. Available filter implementations (in
`pipeline-core`):

- `MimeTypeFilterNode` - filters by MIME type patterns
- `DateFilterNode` - filters by date range
- `SizeFilterNode` - filters by file size
- `DuplicateFilterNode` - filters duplicates
- `BlacklistFilterNode` - filters blacklisted paths
- `QualityFilterNode` - filters by quality thresholds
- `ThresholdFilterNode` - generic threshold filter
- `AssetAttributeFilterNode` - filters by Loom asset attributes

---

## 4. Special Nodes: Filter and Source

### Filter Nodes

Filter nodes extend `AbstractFilterNode` (which extends `AbstractPipelineNode`).
They are **pipeline-only** constructs - they do not exist at the Cortex level.

**Design**:
- `AbstractFilterNode.process()` calls `evaluate(media, upstreamResults)`
  which returns `true` (pass) or `false` (reject).
- The result is stored as `FILTER_PASSED` (boolean) and `filter_reason`
  (String) in the `NodeResult` output map.
- `isPartitioning()` returns `true`.
- `partition(input)` splits the `Flowable<MediaContext>` into pass and
  reject branches using `share()` + `filter()`.
- Downstream nodes declare `conditionalDependencies()` mapping the filter
  node id to `FilterBranch.PASS` or `FilterBranch.REJECT`.
- The executor checks filter branch conditions before executing a node:
  if the filter output doesn't match the required branch, the node is
  skipped with `NodeResult.skipped(node.id, "Filter branch mismatch")`.

**FilterBranch enum**:
- `PASS` - execute only if filter passed
- `REJECT` - execute only if filter rejected
- `ANY` - execute regardless (default for non-filter dependencies)

### Source Nodes

Source nodes yield media items for the pipeline. There are two distinct notions
of "source", and confusing them is a common mistake:

| Concept | Interface | Meaning |
|---|---|---|
| **Marks itself as the entry point** | `PipelineNode.isSource()` | The DAG's root. Says nothing about where media comes from |
| **Produces the media stream** | `MediaSourceNode` (pipeline-api) | Owns its own selection and can enumerate it via `Flowable<LoomMedia> stream()` |
| **Cortex-level marker** | `SourceNode<I, T>` | Marker interface extending `CortexNode`, used by the legacy CLI hierarchy |

#### `MediaSourceNode` — self-describing selection

Historically the media stream was always supplied by the *caller* of
`PipelineExecutor.execute(pipeline, Flowable<LoomMedia>, runContext)`, so every
caller reimplemented its own discovery. `MediaSourceNode` moves that
responsibility onto the node:

```java
public interface MediaSourceNode extends PipelineNode {
    Flowable<LoomMedia> stream();
    default boolean isSource() { return true; }
}
```

This enables the source-driven entry point, where the pipeline decides what to
process:

```java
pipelineExecutor.execute(pipeline, runContext);   // no media argument
```

The default implementation on `PipelineExecutor` resolves `pipeline.sourceNode()`,
requires it to be a `MediaSourceNode`, and subscribes to its `stream()`. A
pipeline whose source is only a marker fails fast with an `IllegalStateException`
naming the offending node.

**Contract**: `stream()` must return a **cold** `Flowable` — no filesystem or
network work before subscription, and every subscription re-enumerates. This is
what lets a node instance registered once in a `PipelineManager` pick up files
added since the previous run.

**`FilesystemSourceNode`** (`nodes/filesystem-source`, kind `filesystem-source`):
- Enumerates either a root directory (walked recursively) or a set of path globs.
- Globs take precedence when both are configured.
- Falls back to `FilesystemSourceNodeOptions` defaults when the pipeline
  definition supplies no selection; a node with no selection from either source
  is rejected at construction.
- Glob/walk logic lives in `FilesystemMediaScanner` — the **single**
  implementation of path-based media discovery in Cortex, driven through
  `FilesystemSourceNode` when the worker runs a `SOURCE_TASK`.
- `process()` records `path` and `source=filesystem` for the item currently
  flowing through the DAG; the enumeration itself happens in `stream()`.

**`S3SourceNode`** (`nodes/s3-source`, kind `s3-source`):
- Enumerates a `bucket` + `prefix` from S3-compatible storage (AWS S3, MinIO, Ceph).
- **Differential**, like `filesystem-source`, but keyed on `(key, etag, size)` rather than
  `(st_dev, st_ino, mtime, size)` — S3 has no inode. A per-selection Avro index lives at
  `metaPath/s3-index/<sha256(endpoint/bucket/prefix)>.avro`. Including the endpoint in the key is
  what stops the same bucket name on two servers from sharing (and corrupting) one index.
- **`MOVED` is never produced.** A rename in S3 is a delete plus an add. It could be inferred by
  matching a removed key against a new key with the same `(etag, size)`, but ETags collide across
  genuinely identical objects — common in media archives with duplicate uploads — so the inference
  would invent renames. The option value is accepted for symmetry and never emitted.
- **Nothing is downloaded during enumeration.** The node emits `S3LoomMedia` handles carrying an
  `s3://bucket/key` reference; `size()` and `isVideo()`/`isImage()` are answered from the listing
  and the key's extension, so a filter node can reject an object before any transfer.
- Three scan paths, chosen per run by `S3DifferentialScanner`: **full list** (paginated
  `ListObjectsV2`, metadata-only, always correct), **resume** (`startAfter(lastSeenKey)`, opt-in,
  cannot see edits to older keys) and **events** (drain buffered bucket notifications and `HeadObject`
  only those keys — no listing at all). Both fast paths are gated on a full listing having happened
  within `reconcileIntervalMs` (default 6h). That single gate is what makes lost notifications and
  `startAfter`'s blind spot survivable.
- The kind is **only registered when the worker has S3 configuration**, so Loom never dispatches a
  source task the worker cannot serve.

#### Media references and lazy materialization

`MediaRef.path` used to be `media.absolutePath()`, and its Javadoc recorded the consequence:
*"shared storage is a prerequisite for distributing work across more than one Cortex instance."*
That is no longer true for object storage.

- `ProcessableMedia.reference()` (default `absolutePath()`) is the stable, location-independent
  identity. `SourceTaskRunner.toRef` uses it, so filesystem media is unchanged and S3 media travels
  as a URI.
- `NodeTaskRunner.MediaResolver` now takes the `MediaRef` rather than a `Path`. It had to: a
  `java.nio.file.Path` **cannot** hold a URI — `Paths.get("s3://b/k")` collapses to `s3:/b/k`.
- `MediaReferenceResolver` (cortex-common) resolves a reference back to a handle; the
  `S3MediaReferenceResolver` subclass (cortex-s3-common) handles `s3://` and delegates everything
  else. With no S3 configured the base class is provided and behaviour is byte-for-byte as before.
- `S3MediaMaterializer` downloads into
  `metaPath/s3_bin/<4-hex shard>/<sha256(bucket/key)>-<etag><ext>`, atomically (`.part` then
  `ATOMIC_MOVE`), with an mtime-ordered LRU sweep against `maxCacheBytes`.
  **The key's extension is preserved deliberately** — `LoomMediaImpl.isVideo()` delegates to
  `FilterHelper.isVideo(path())`, so an object cached without its suffix would be invisible to every
  media node. The etag in the file name means a changed object lands at a new path and a stale copy
  is never served; it is used strictly as an opaque change token, never as MD5 (multipart ETags are
  `<md5-of-md5s>-<partcount>`).
- Because materialization happens wherever the node task lands, **every worker touching S3 media
  needs the S3 settings** — not only the one running the source node.

#### S3 bucket notifications

`S3EventBuffer` (worker singleton) reconciles a continuous event stream with discrete runs:
transports fill it, a run drains it. This keeps `stream()` a cold, finite `Flowable` and leaves the
SOURCE_TASK contract untouched. Two transports feed it — `WebhookS3EventSource`, which registers a
route on the **existing** monitoring router (port 8093, same `register(router)` pattern as
`HealthEndpoint`) and is what MinIO's `notify_webhook` target speaks; and `SqsS3EventSource`, which
long-polls an SQS queue for AWS. Past `maxBufferedKeys` the buffer marks the bucket **degraded**
rather than dropping hints, which forces the next run onto a full listing.

> Events make a *run* cheap; they do not *start* a run. Loom's scheduler still owns that. A
> worker-initiated run trigger ("watch mode") is open work.

**`AssetSourceNode`** (pipeline-level):
- Emits exactly one configured `LoomMedia` per pipeline run.
- Uses `AtomicBoolean` to ensure single emission.
- Returns `NodeResult.skipped()` on subsequent invocations.
- Sets `setSource(true)` in the constructor.
- Implements `MediaSourceNode`; its `stream()` is the single configured asset.

**`FilesystemNode`** (Cortex-level):
- Extends `SourceNode` and adds `process(NodeContext<I>)`.
- `AbstractFilesystemNode` adds progress tracking (`set(current, total)`,
  `print(ctx, result, msg)`, `error(media, msg)`).
- `AbstractMediaNode` extends `AbstractFilesystemNode` and implements the
  full lifecycle (enabled -> exists -> processable -> compute).

The pipeline requires exactly one source node. `DefaultPipeline` validates
this and discovers all reachable nodes via BFS from the source.

---

## 5. Configuration Handling

### CortexOptions (top-level)

`CortexOptions` is the root configuration object:

| Field | Type | Description |
|---|---|---|
| `nodes` | `Map<String, CortexNodeOptions>` | Per-node options, keyed by node name |
| `loom` | `LoomClientOptions` | Loom backend connection settings |
| `dryrun` | `boolean` | Global dry-run mode (nodes log but don't mutate) |
| `metaPath` | `Path` | Base path for metadata storage files |
| `monitoringPort` | `int` | Monitoring port (default 8093) |

### Node Options (CortexNodeOptions / AbstractNodeOptions)

Every node has its own options class extending `AbstractNodeOptions<T>`:

**Common fields** (from `AbstractNodeOptions`):
- `enabled` (boolean, default true) - whether the node is active
- `processIncomplete` (boolean) - whether to process incomplete media
- `retryFailed` (boolean) - whether to retry previously failed items

**Per-node options** (examples):

| Node | Options Class | Key Fields |
|---|---|---|
| Hash | `HashNodeOptions` | `md5`, `sha256`, `sha512`, `chunkHash` (booleans) |
| Facedetect | `FacedetectNodeOptions` | `videoChopRate`, `videoScaleSize`, `faceClusterMinimum`, `faceClusterEPS`, `minFaceHeightFactor`, `inspirefacePackPath`, `capabilities` |
| Whisper | `WhisperOptions` | `modelPath`, `temperature`, `temperatureInc`, `language`, `useGpu`, `gpuDevice` |
| Quality | `QualityNodeOptions` | `checkBlurriness`, `checkResolution`, `checkVideoBitrate`, `checkAudioBitrate` |
| Thumbnail | `ThumbnailNodeOptions` | `tileSize`, `cols`, `rows` |
| OCR | `OCRNodeOptions` | `tessDataPath`, `language` |
| LLM | `LLMNodeOptions` | `ollamaUrl`, `prompts` (Map of prompt configs) |
| VLM | `VlmNodeOptions` | `endpointUrl`, `apiKey`, `prompts` (Map of `VlmNodePrompt`: `model`, `prompt`, `responseFormat`, `maxImageDim`, `maxTokens`, `temperature`, `retryOnRotation`) |
| Captioning | `CaptioningNodeOptions` | Image: `smolVLMHost`, `smolVLMPort`. Video: `videoStrategy` (`WHOLE`/`SCENE`/`NATIVE`), `videoEndpointUrl`, `videoModel`, `videoApiKey`, `frameCount`, `targetFrameSize`, `maxScenes`, `maxTokens`, `temperature`, `videoPrompt` |
| Image Generation | `ImageGenNodeOptions` | `mode` (`GENERATE`/`REMIX`), `prompt`, `host`, `port`, `generateEndpoint`, `remixEndpoint`, `width`, `height`, `strength`, `seed`, `steps`, `timeoutMs` (`KEY = "imagegen"`) |
| Sentiment | `SentimentNodeOptions` | `sentimentHost`, `sentimentPort` (9110), `language` (`auto`/`de`/`en`), `modelDe`, `modelEn`, `textSources` (ordered `nodeId:outputKey` list), `maxChars` |
| Depthmap | `DepthmapNodeOptions` | `depthHost`, `depthPort` (9120), `mode` (`RELATIVE`/`METRIC`), `model` (checkpoint override), `maxDim` (1024); `timeoutMs` is the inherited common option, defaulted to 120000 in the constructor (`KEY = "depthmap"`) |
| Scene Layout | `SceneLayoutNodeOptions` | `depthNodeId` (`depthmap`), `detectionSources` (`["facedetect"]`), `allowLoomFallback`, `coreInset` (0.25), `minCorePixels` (16), `depthZThreshold` (1.0), `occlusionMinOverlap` (0.05), `containmentRatio` (0.85), `nextToMaxGap` (0.5), `foregroundQuantile` (0.66), `backgroundQuantile` (0.33), `maxObjects` (40), `maxRelations` (200), `emitPhrases` (`KEY = "scene-layout"`) |
| Dominant Colour | `DominantColorNodeOptions` | `clusterCount` (5), `maxSamples` (40000), `maxIterations` (30), `convergenceEpsilon` (0.5), `seed` (42), `alphaThreshold` (128), `minRegionPixels` (64), `maxRegions` (32), `includeWholeImage` (true), `useDetections` (true), `detectionSources` (`["facedetect"]`), `regionX/Y/W/H` (0), `regionCoordinates` (`NORMALIZED`), `achromaticChroma` (12.0), `blackLightness` (20.0), `whiteLightness` (85.0), `emitPalette` (true) (`KEY = "dominant-color"`) |
| Script | `ScriptNodeOptions` | `engine`, `script`, `outputs` (declared `{key,type[,segmentType]}`), `params`, `requiredInputs`, `trusted`, `allowNetwork`, `allowFilesystem`, `timeoutMs`, `statementLimit`, `maxOutputBytes`, `maxLogLines` (`KEY = "script"`). ⚠️ Set per **pipeline node instance**, not per worker — see §5.1 |
| Dedup | `DedupNodeOptions` | `dupFolder` (Path) |
| Filesystem Source | `FilesystemSourceNodeOptions` | `path` (String), `pathGlobs` (List&lt;String&gt;) — defaults used when the pipeline definition supplies no selection |
| S3 Source | `S3SourceNodeOptions` | `bucket`, `prefix`, `suffixes`, `emitStates`, `startAfter`, `useEvents` (`KEY = "s3-source"`). ⚠️ **Connection settings are not here** — endpoint/region/credentials/cache live on `CortexOptions.getS3()` (`S3ClientOptions`, env `CORTEX_S3_*`), because they describe the worker and because a pipeline definition is stored in Postgres and rendered in the editor |
| S3 Sink | `S3SinkNodeOptions` | `bucket`, `keyTemplate`, `artifacts`, `autoDiscover`, `includeSource`, `createAssets`, `overwrite`, `deleteAfterUpload`, `maxArtifacts`, `maxArtifactBytes`, `failOnPartial` (`KEY = "s3-sink"`). ⚠️ Set per **pipeline node instance** — see §5.1. Connection settings stay on `CortexOptions.getS3()` |
| Scene | `SceneDetectionOptions` | (no custom fields) |
| Consistency | `ConsistencyNodeOptions` | (no custom fields) |
| Loom | `LoomNodeOptions` | (no custom fields) |

### 5.1 Per-instance configuration (`PipelineConfigurable`)

Every node above reads its options from `CortexOptions.getNodes().get(name())` — **per worker**.
That is right for options describing the worker's environment (a model path, a sidecar address) and
wrong for a node whose configuration *is* the work: two `script` nodes in one graph must run two
different scripts.

`io.metaloom.cortex.common.node.PipelineConfigurable` is the opt-in seam:

```java
public interface PipelineConfigurable {
    void configure(JsonObject nodeDef);
}
```

`RegistryNodeRegistrar.adapt(...)` calls it **only** for nodes that implement it, so no existing
node changes behaviour. `ScriptNode` and `S3SinkNode` are the implementors today.

⚠️ **An implementor must never be `@Singleton`.** `configure` mutates the node;
`NodeTaskRunner` builds one per task through the kind map's `Provider`, and marking the node a
singleton would let two concurrent script nodes overwrite each other's configuration.
`ScriptNodeTest.shouldGiveEachProviderCallItsOwnInstance` and
`PipelineConfigurableTest` pin this.

**Two defects had to be fixed before any of this could work** — both pre-existing, both affecting
every node's per-instance options, not just `script`:

1. `PipelineEditor.getGraphJson()` serialised node parameters under `config`, while
   `PipelineGraphParser` reads `options`. `"config"` was read by no Java parser, so **no node
   parameter set in the editor had ever reached a worker**. The editor now writes `options`; the
   parser accepts `config` as a legacy alias (`options` wins). Guarded by
   `PipelineNodeOptionsParsingTest`.
2. Parameter edits in the editor's node sidebar were written only to `selected.definition`, while
   the canvas serialisation (`getGraphJson`) reads React Flow node data — so a save discarded them.
   Edits now mirror onto the canvas through a `nodeParameters` channel, alongside the existing
   display-name and affinity channels.

### Dagger Wiring

Each node module extends `AbstractNodeModule` and provides:
1. `@Binds @IntoSet FilesystemNode<?, ?>` - registers the node in the
   Dagger multibinding set (used by the legacy CLI `FilesystemProcessorImpl`).
2. `@Binds @IntoMap @StringKey("<kind>") FilesystemNode<?, ?>` - registers the
   node as an **executable pipeline kind**. The `@StringKey` is the node's
   `name()` (the pipeline `type`). This map (`Map<String, Provider<FilesystemNode>>`)
   is the single source of truth for what the worker can run: `NodeRegistrar`
   turns it into the `RegistryNodeFactory` registry at bootstrap, and the worker
   announces `registeredTypes()` as its `nodeWhitelist` (§11). The `Provider`
   keeps the node uninstantiated until a task of its kind arrives. Stub / unwired
   nodes (`fingerprint-dedup`, `facedescription`) deliberately omit this binding.
3. `@Provides CortexNodeOptionDeserializerInfo` - registers the options
   class and its config key for deserialization.
4. `@Provides` method that extracts node-specific options from
   `CortexOptions` using `nodeOptions(cortexOptions, KEY, default)`.

The `CortexOptions.getNodes()` map is keyed by the node's `KEY` constant
(e.g. `"hash"`, `"facedetection"`, `"whisper"`).

### Pipeline-Level Node Configuration

Pipeline nodes carry configuration via:
- `id()` - unique identifier within the pipeline
- `mode()` - `NodeMode.SEQUENTIAL` or `NodeMode.PARALLEL`
- `blocking()` - whether downstream nodes wait for completion
- `concurrency()` - max concurrent executions (semaphore size)
- `syncToLoom()` - whether results are batched for Loom sync
- `cacheProvider()` - optional `NodeCacheProvider` for result caching
- `options()` - arbitrary key-value map (for pipeline-loaded nodes)

---

## 6. Pipeline Interaction

> The pipeline system is covered in detail in the pipeline specification.
> This section provides a brief overview for node developers.

### CortexNodeAdapter

The `CortexNodeAdapter` bridges Cortex-level nodes into the pipeline DAG:

- Wraps a `FilesystemNode<?, ?>` as a `PipelineNode`.
- Converts pipeline `NodeResult` maps to/from Cortex-level
  `NodeContext.upstreamOutputs()`.
- The adapter's `process(LoomMedia, Map<String, NodeResult>)` delegates to
  the wrapped node's `process(LoomMedia, upstreamOutputs)`.
- `isSource()` returns true if the wrapped node is a `SourceNode`.

### DAG Execution

The `ReactivePipelineExecutor` (in `pipeline-core`) executes pipelines:

1. **Topological sort**: `DefaultPipeline` sorts nodes via Kahn's algorithm
   to respect dependencies.
2. **Per-media DAG**: For each media item, builds `Single<NodeResult>` per
   node. Multi-parent dependencies use `Single.zip`.
3. **Reactive streams**: Uses RxJava 3 `Flowable` with backpressure.
   `maxConcurrentMedia` controls parallelism via `flatMap(fn, maxConcurrent)`.
4. **Per-node concurrency**: Each node has a `Semaphore(concurrency())`.
   The executor acquires/releases it around `process()`.
5. **Dependency resolution**: Before executing a node, checks:
   - Failed blocking dependencies -> skip
   - Filter branch conditions -> skip if mismatch
6. **Caching**: Checks `NodeCacheProvider` before executing. On success,
   caches the result.
7. **Loom sync**: If `syncToLoom() == true`, collects results for bulk sync.
8. **Event bus**: Publishes `NodeCompletionEvent` and `PipelineTrackingEvent`.

### Pipeline Construction

```java
sourceNode.connectTo(filterNode);
filterNode.connectTo(hashNode, FilterBranch.PASS);
filterNode.connectTo(tikaNode, FilterBranch.PASS);
hashNode.connectTo(fingerprintNode);

Pipeline pipeline = DefaultPipeline.builder("video-analysis")
    .source(sourceNode)
    .build();
```

### Pipeline Serialization

`PipelineSerializer` / `PipelineDeserializer` convert pipelines to/from JSON
for storage in the Loom backend. The JSON includes node ids, names, modes,
dependencies, concurrency, and options.

`LoomPipelineLoader` loads pipeline definitions from the Loom REST API and
registers them with the `PipelineManager`.

---

## 7. Execution Model

### CLI Execution (Legacy)

The CLI (`CortexCLIMain`) builds a `DaggerCortexComponent` with
`CortexOptions`, then invokes the selected actions. Nodes are injected via
Dagger and called directly (not through the pipeline DAG). The CLI iterates
over media files and calls `node.process(NodeContext)` for each.

### Pipeline Execution (Reactive)

The `ReactivePipelineExecutor` uses RxJava 3:

- **Backpressure**: `Flowable.flatMap(fn, maxConcurrentMedia)` - the source
  is paused when downstream can't keep up.
- **Scheduling**: `Schedulers.io()` for I/O-bound node work.
- **Per-node parallelism**: `Semaphore(concurrency())` per node.
- **Multi-parent gathering**: `Single.zip` of dependency singles.
- **Caching**: `Single.cache()` ensures each node's Single executes only
  once per media item even with multiple downstream subscribers.

### Virtual Threads

The codebase does **not** currently use virtual threads (`Thread.ofVirtual`,
`Executors.newVirtualThreadPerTaskExecutor`, or `StructuredTaskScope`).
The reactive executor uses RxJava 3 `Schedulers.io()` which is backed by a
standard thread pool. Virtual threads could be beneficial for I/O-bound
nodes (whisper, OCR, LLM, facedetect) but are not yet integrated.

### Node Concurrency Model

| Aspect | Mechanism |
|---|---|
| Media-level parallelism | `maxConcurrentMedia` in `ReactivePipelineExecutor` |
| Per-node parallelism | `Semaphore(concurrency())` on each `PipelineNode` |
| Node mode | `NodeMode.PARALLEL` vs `NodeMode.SEQUENTIAL` |
| Blocking | `isBlocking()` - if true, downstream nodes wait for completion |
| Filter partitioning | `AbstractFilterNode.partition()` splits into pass/reject branches |

### Dry-Run Mode

When `pipeline.isDryRun()` is true, the executor logs the node and media
but returns `NodeResult.skipped(node.id, "dry-run")` without calling
`process()`. This allows testing pipeline graphs without side effects.

---

## 8. Dagger DI Wiring

### Node Modules

Each node module extends `AbstractNodeModule` and uses Dagger `@Module`:

```java
@Module
public abstract class HashNodeModule extends AbstractNodeModule {
    @Binds @IntoSet
    abstract FilesystemNode<?, ?> bindSHA512Node(SHA512Node node);

    // Advertises "sha512" as an executable pipeline kind (lazy via Provider).
    @Binds @IntoMap @StringKey("sha512")
    abstract FilesystemNode<?, ?> kindSHA512(SHA512Node node);

    @IntoSet @Provides
    public static CortexNodeOptionDeserializerInfo optionInfo() {
        return new CortexNodeOptionDeserializerInfo(HashNodeOptions.class, HashNodeOptions.KEY);
    }

    @Provides
    public static HashNodeOptions options(CortexOptions options) {
        return nodeOptions(options, HashNodeOptions.KEY, new HashNodeOptions());
    }
}
```

The `@Binds @IntoSet` pattern collects all nodes into a `Set<FilesystemNode>`
multibinding (legacy CLI path). The `@Binds @IntoMap @StringKey` pattern collects
the executable pipeline kinds into `Map<String, Provider<FilesystemNode>>`, from
which `NodeRegistrar` populates the `RegistryNodeFactory` registry at bootstrap and
the worker derives its announced `nodeWhitelist`. The `@Provides` methods extract
per-node options from the shared `CortexOptions`.

**Assembly (`cortex/cli`)**: `PipelineNodeFactoryModule` binds the empty
`RegistryNodeFactory` as the `NodeFactory` and provides the `NodeRegistrar`
(`RegistryNodeRegistrar`), which registers the two source producers
(`filesystem-source`, `asset-source`) and every kind from the map. Adding a node
kind is therefore a one-line binding in the node's own module — no edit to the
assembly. `CortexBootstrapInitializer.init()` calls `registerAll()` before the
Loom control channel starts.

### NodeDescriptorRegistry

`NodeDescriptorProvider` (SPI via `ServiceLoader`) provides
`NodeDescriptor` objects for the UI. Each descriptor includes:
- `kind` - unique machine-readable id
- `name` - display name
- `category` - palette grouping
- `inputs` / `outputs` - connectors
- `parameters` - configurable form fields
- `defaultConcurrency`, `defaultMode`, `defaultBlocking`
- `events` - UI visualization events

---

## 9. Noteworthy Aspects

### Offline Mode

When `LoomClient` is null (no Loom backend configured), nodes run in
"offline mode". `AbstractMediaNode.fetchAsset()` returns null, and nodes
compute everything locally without checking for remote results.

### Upstream Output Access

Nodes can read outputs from upstream dependency nodes via:
- `ctx.upstreamOutput(nodeId, key)` - convenience accessor
- `ctx.upstreamOutputs()` - full map of upstream node id -> output map

Example: `FacedescriptionNode` reads `ctx.upstreamOutput("facedetect", "face_count")`
to skip face description when no faces were detected. `ThumbnailNode` reads
`ctx.upstreamOutput("consistency", "is_complete")` to skip incomplete videos.

> There is **no input type system** — no `NodeInputKey`, no declared binding. The lookup is keyed by
> pipeline **node id** (not kind), `<T>` is erased, and a rename makes it silently return `null`.
> Every `(nodeId, outputKey)` pair actually read at runtime, and which are hard-coded vs. configurable,
> is tabulated in [../pipeline/NODE_DATA_TYPES.md](../pipeline/NODE_DATA_TYPES.md) §6.2.

### ResultOrigin

Nodes declare whether a result was `COMPUTED` (locally computed), `REMOTE`
(fetched from Loom backend), or `LOCAL` (from local cache). This allows
downstream nodes and the pipeline to distinguish between freshly computed
and cached results.

### LoomMedia

`LoomMedia` extends `ProcessableMedia` and is a pure file handle:
- `isVideo()`, `isImage()`, `isAudio()`, `isDocument()` - media type checks
- `file()`, `path()`, `absolutePath()` - file access
- `exists()`, `size()`, `open()` - file operations
- `getSHA512()`, `setSHA512()`, `hasSHA512()` - content-based identity
- `listXAttr()` - extended attributes listing

`LoomMedia` carries only the SHA-512 identity (`getSHA512()`/`setSHA512()`) for
downstream nodes. The former `MetaStorage`-backed media decorators (`HashMedia`,
`FacedetectMedia`, …) that exposed per-domain typed accessors were removed
together with the `MetaStorage` system; domain results now live in Loom (§2) and
in the per-node `LocalResultCache`, not on the media handle.

### Pipeline Node IDs

Node IDs must match `^[a-z0-9]([a-z0-9\\-]{0,62}[a-z0-9])?$` (lowercase
alphanumeric with hyphens, 1-64 chars). This ensures stable identifiers for
UI mapping, WebSocket events, and serialization.

### Progress Tracking

`AbstractFilesystemNode` provides progress tracking via `set(current, total)`
and `print(ctx, result, msg)`. The print format is:
```
[current/total] [shortHash [nodeName]] result [duration] message
```

---

## 10. Progress Assessment

The node system is functional and well-structured, but several areas need
attention. The following checklist tracks aspects that need improvement,
fixes, or further development.

### Architecture and Design

- [x] **NodeResult classes unified**: there is now a single
      `io.metaloom.cortex.api.node.NodeResult` used by both the Cortex-level node
      API (produced by `NodeContext.next()/abort()`) and the pipeline-level
      `PipelineNode`. The former `io.metaloom.cortex.pipeline.api.NodeResult` was
      deleted. The unified type carries the superset: `state`, an optional
      `nodeId` (null outside a DAG, stamped by the adapter via
      `NodeResult.withNode(id, durationMs)`), `durationMs`, an optional `message`
      (skip reason / failure cause), the output map, and the typed
      `NodeOutputKey` accessors.

- [x] **Node/pipeline state enums unified**: the pipeline
      `io.metaloom.cortex.pipeline.api.NodeState` (whose `PENDING`/`RUNNING` were
      never used) was deleted; results use the terminal `ResultState`
      (SUCCESS/SKIPPED/FAILED) everywhere. The wire enum
      `io.metaloom.loom.pipeline.model.NodeState` is a separate contract and is
      untouched; `NodeResultMapper.toWireState` maps `SUCCESS→COMPLETED`,
      `SKIPPED→SKIPPED`, `FAILED→FAILED` explicitly.

- [x] **CortexNodeAdapter mapping simplified**: with one result type the adapter
      no longer converts between two shapes — it stamps the wrapped node's result
      with the adapter's pipeline id and elapsed time (`withNode`), preserving the
      node's own state, message and outputs. Covered by `CortexNodeAdapterTest`.

### Node Implementations

- [x] **`filesystem-source` implemented** (`nodes/filesystem-source`): the
      descriptor had been advertised by `cortex-source-api` with no runtime
      behind it, so the kind resolved to a success-reporting stub. It is now a
      real `MediaSourceNode` and is registered in `PipelineNodeFactoryModule`.
      Path discovery is consolidated in `FilesystemMediaScanner`; the copy that
      lived in `PipelineWorkOrderHandler` was removed.

- [ ] **`loom-fetch` has no runtime**: `LoomFetchNode` exists in `pipeline-core`
      but no producer is registered for the `loom-fetch` kind, so the descriptor
      advertised by `cortex-source-api` still stubs out as a success. It is also
      not a `MediaSourceNode`, so it cannot drive a run on its own.

- [ ] **`AssetSourceNode` is not registered as a kind**: it implements
      `MediaSourceNode` and is used programmatically and in tests, but pipeline
      JSON cannot select it.

- [x] **CaptioningNode video support**: Done. `CaptioningNode` now captions
      video as well as images. The image path still uses SmolVLM; the video path
      drives an OpenAI-compatible VLM (Qwen2.5-VL on vLLM / llama.cpp) via
      `VideoVLMClient`, with the `videoStrategy` option selecting one of three
      interchangeable strategies (formerly the separate `video-captioning-*`
      nodes, now merged in): `WHOLE` (sample N frames → one multi-image prompt →
      single caption), `SCENE` (optical-flow scene segmentation → per-scene
      caption timeline), `NATIVE` (hand the file to the server via `video_url`;
      vLLM-only). Images persist `schemaType=caption`; video persists
      `schemaType=video-caption` (carries `variant`, `model`, `frameCount` and an
      optional `scenes` array). Audio is skipped. The former
      `cortex/nodes/video-captioning` module was removed and its benchmark harness
      moved to the captioning module (`VideoCaptioningComparisonIT`). See
      [NODE_VIDEO_CAPTIONING_PLAN.md](NODE_VIDEO_CAPTIONING_PLAN.md) /
      [NODE_VIDEO_CAPTIONING_REPORT.md](NODE_VIDEO_CAPTIONING_REPORT.md).

- [ ] **FacedescriptionNode video support**: The `FacedescriptionNode` only
      processes images; video face description (per-frame extraction) is
      stubbed but not implemented.

- [ ] **HashDedupNode has dead code**: The `HashDedupNode` has commented-out
      code for storing updated paths and a `System.in.read()` blocking call
      in the error path that should be removed.

- [x] **LLMNode provider is now injected**: the `LLMProvider` is a constructor-
      injected field (Dagger provides `OllamaLLMProvider` by default) instead of a
      fresh `new OllamaLLMProvider()` per `compute()`. The backend protocol is
      selectable via `LLMNodeOptions.providerType()` (`OLLAMA` default, `VLLM` for
      an OpenAI-compatible endpoint), which is what lets the integration test drive
      the node against the `MockLLMServer`. The per-call `LargeLanguageModelImpl`
      (a cheap value object carrying model id + url) is still built per prompt.

- [x] **In-node persistence is now the standard**: every result-producing node
      writes its typed payload + `asset_node_result` ledger inside `compute()`
      (see §2). `WhisperNode`'s `createAssetTranscript()` is the reference, not a
      special case; the `syncToLoom()` / bulk-collector path was **not** adopted
      for this. `LoomNode` still bulk-updates hash columns and now overlaps the
      per-node hash writes.

- [ ] **LoomNode does not extend AbstractMediaNode**: `LoomNode` extends
      `AbstractFilesystemNode` directly, skipping the `isProcessable()` /
      `compute()` lifecycle. It also does not use `NodeOutputKey` for outputs.
      This is inconsistent with the rest of the node system.

- [x] **ThumbnailNode duplicate variable**: the dead second
      `resolveThumbnailPath(media)` call after the try block was removed; the
      node now records a ledger entry and caches the thumbnail path.

### Configuration

- [ ] **ConsistencyNodeOptions and LoomNodeOptions have no custom fields**:
      These options classes extend `AbstractNodeOptions` but add nothing.
      They should either have meaningful fields or be simplified.

- [ ] **Node options are not validated**: There is no validation framework
      for node options. Invalid configurations (e.g. negative concurrency,
      empty model paths) are not caught until runtime.

- [ ] **Config key consistency**: Some nodes use a `KEY` constant (e.g.
      `"hash"`, `"facedetection"`, `"whisper"`) while others do not. The
      key should be consistently defined in every options class.

### Persistence and Caching

- [ ] **XAttrNodeCache serialization is fragile**: The cache uses a
      line-based `key=value` format that breaks on values containing
      newlines or `=` signs. Should use JSON or a more robust format.

- [ ] **SidecarFileNodeCache.clear() is not implemented**: The `clear()`
      method logs a warning and does nothing. This prevents full cache
      invalidation.

- [x] **MetaStorage removed**: the old xattr/AVRO/FS `MetaStorage` system is
      gone (see §2). Node results now live in Loom (typed component + ledger);
      recompute avoidance is the in-heap `LocalResultCache`. Two caching layers
      still coexist — the per-node `LocalResultCache` and the pipeline
      `NodeCacheProvider` — which is worth documenting to avoid confusion.

- [ ] **No cache eviction strategy for SidecarFileNodeCache**: Sidecar
      cache files accumulate indefinitely with no cleanup mechanism.

### Pipeline Integration

- [ ] **CortexNodeAdapter does not propagate `syncToLoom()`**: The adapter
      constructor does not accept a `syncToLoom` flag; it defaults to false.
      Nodes that should sync to Loom need this set explicitly after
      adaptation.

- [ ] **CortexNodeAdapter does not set `cacheProvider()`**: The adapter
      does not propagate a cache provider from the wrapped Cortex node.
      Pipeline-level caching is not applied to adapted Cortex nodes unless
      set externally.

- [ ] **No reactive `apply()` override on CortexNodeAdapter**: The adapter
      relies on the default `AbstractPipelineNode.apply()` which wraps
      `process()` in a flatMap. Nodes with streaming characteristics (e.g.
      whisper processing long audio) cannot express streaming operators.

### Execution Model

- [ ] **No virtual thread support**: The executor uses RxJava `Schedulers.io()`
      with platform threads. For I/O-bound nodes (whisper, OCR, LLM, facedetect),
      virtual threads could improve throughput. Consider adding a
      `Thread.ofVirtual()`-based scheduler option.

- [ ] **`PipelineNode.timeoutMs()` is parsed but never enforced**: `RegistryNodeRegistrar.adapt()`
      reads `timeoutMs` off the node definition and stores it on `AbstractPipelineNode`, and
      **nothing reads it back**. The in-Cortex DAG executor that used to apply it no longer exists;
      only `NodeTaskRunner` remains, and it does not time out. A hung node (e.g. an LLM call) blocks
      its slot indefinitely. `ScriptNode` therefore enforces its own wall clock rather than relying
      on the field — any other node needing a timeout must do the same until the runner grows one.

- [ ] **No retry mechanism**: The `retryFailed` option in
      `AbstractNodeOptions` is declared but never checked by the executor.
      Failed nodes are not retried.

- [ ] **No backpressure propagation to nodes**: While the pipeline uses
      `Flowable.flatMap` with `maxConcurrentMedia`, individual nodes have
      no way to signal backpressure to the executor.

### Testing

- [ ] **No integration tests for the reactive executor**: The
      `ReactivePipelineExecutor` has unit tests but no integration tests
      that exercise real node graphs with actual media files.

- [ ] **Filter nodes are not tested with real partitioning**: The
      `AbstractFilterNode.partition()` method is tested in isolation but
      not with the full executor and real filter branches.

- [ ] **LoomBulkSyncCollector has no tests**: The bulk sync collector
      interface and its default implementation lack test coverage.

### Documentation

- [x] **Node descriptor registry is populated** (this claim was stale): 19 providers are
      registered in
      `loom-shared/node-model/src/main/resources/META-INF/services/io.metaloom.loom.nodes.spec.NodeDescriptorProvider`,
      and the pipeline editor renders its palette and edit forms from them.

- [ ] **Missing node documentation**: Individual nodes lack Javadoc on
      their options, outputs, and persistence keys. The
      `loom/doc/src/main/docs/cortex/nodes/index.adoc` file is the
      canonical doc but is not kept in sync with code changes.

- [ ] **No sequence diagram for pipeline execution**: The pipeline
      execution flow (source -> filter -> process -> sync) should be
      documented with a sequence diagram.

### Missing Features

- [x] **Produced bytes now have a durable home**: `s3-sink` (`cortex/nodes/s3-sink`) uploads the
      files `ThumbnailNode`, `DepthmapNode`, `TtsNode`, `ImageGenNode` and `ScriptNode` write into
      their worker-local `*_bin` caches, and **creates a Loom asset per uploaded file** with
      `origin` = the `s3://` URI, so a thumbnail is retrievable rather than stranded on one
      machine. The source asset gets an `asset_json_comp` (`schemaType=s3-artifact`,
      `variant` = node id) indexing what was published. See
      [NODE_S3SINK_PLAN.md](NODE_S3SINK_PLAN.md). Still open on the Loom side: the location is a
      string in `initial_origin` rather than a structured record, because
      `asset_location.pool_uuid` is a column nothing writes,
      `AssetBinaryEndpointService`'s three S3 branches are stubs, and `attachment`'s provenance
      columns (`V2.44`, added for exactly this) are invisible to REST — phases 2 and 3 of that plan.

- [ ] 🔴 **A sink must share a worker with its producer, and nothing enforces it**: `s3-sink` reads
      the files its upstream node wrote to local disk, exactly as `scene-layout` reads
      `depthmap_path`. `NodeTaskRunner`'s javadoc says affinity groups "will later" let Loom
      dispatch a whole subgraph; there is no affinity column in any migration and the editor's
      affinity channel is consumed by nothing. Today the only working configurations are a
      single-worker deployment or a `CORTEX_NODE_WHITELIST` that co-locates the pair. `s3-sink`
      fails loudly when an upstream output names a file that is not on this worker, which is the
      only mitigation available.

- [ ] **No node-level dry-run**: The pipeline has a global `dryRun` flag,
      but individual nodes cannot be dry-run independently.

- [ ] **No node health/status endpoint**: There is no way to query the
      health or last-run status of a node outside of the event bus.

- [ ] **No node-level metrics**: The executor tracks `nodeProcessedCounts`
      and `nodeFailedCounts` but these are not exposed via a metrics
      endpoint or JMX.

- [ ] **No conditional node chaining**: Nodes cannot be conditionally
      enabled/disabled at runtime based on upstream results (only filter
      branches provide conditional execution).

- [ ] **No node versioning**: Nodes have no version field. When a node's
      algorithm changes (e.g. a new hash function), there is no way to
      invalidate cached results from the previous version. `ScriptNode` is the one exception: it
      records `producerVersion = "<engine>:<sha256(script)[0..12]>"` in the ledger and includes the
      script hash in its cache key, so a changed script is visibly a different producer and never
      serves the previous script's cached result. That shape is the model for doing this generally.

- [ ] 🔴 **`ctx.failure(cause).next()` reports SUCCESS**: `NodeContextImpl.next()` only checks
      `skipReason`; the recorded failure cause is ignored and the result is built as
      `ResultState.SUCCESS`. Only `abort()` yields `FAILED`.

      **Eleven nodes end their catch blocks this way** — `TtsNode`, `SentimentNode`,
      `FacedetectNode`, `HashDedupNode`, `TikaNode`, `QualityNode` (twice), `ImageGenNode`,
      `WhisperNode`, `LoomNode`, `ThumbnailNode`, `FingerprintNode` — so a failed run is reported to
      the pipeline as a successful node with no outputs. The behaviour is known (there is a comment
      spelling it out in `SentimentNodeTest.testFailure...`) but has never been reconciled: the
      `asset_node_result` ledger records FAILED while the run's node result says SUCCESS, so
      `nodeFailedCounts`, blocking-dependency skipping and the UI's node status all see a success.

      `ScriptNode` and `S3SinkNode` use `ctx.failure(msg).abort()` instead, which is why their failure tests assert
      `FAILED` directly. Fixing the rest is either eleven one-word edits or a change to `next()` so
      it honours a recorded failure cause — the latter is smaller but silently changes what `next()`
      means for every existing caller, so it needs its own review rather than being folded into an
      unrelated change.

---

## 11. Node Restriction & Cortex-Instance Persistence

A worker (Cortex/processor) does not have to be able to run every kind of node.
A deployment can dedicate machines to particular work — GPU boxes to embeddings,
the one host with the media mount to filesystem sources — by restricting which
node **kinds** a worker will accept. This section covers how that restriction is
expressed, persisted, and reconciled.

### Whitelist and blacklist (the `nodeKinds` rename)

The single announced field `nodeKinds` has been split into two, because "the
kinds a worker will run" and "the kinds a worker refuses" are different
questions and a single list answered only the first:

| Field | Meaning |
|---|---|
| `nodeWhitelist` | Kinds this worker will run. Null/empty means **anything**, so a worker that predates whitelisting keeps receiving everything rather than dropping out of the pool. |
| `nodeBlacklist` | Kinds this worker refuses. Takes precedence over the whitelist: a kind in the blacklist is rejected even when the whitelist would admit it. Null/empty means **refuse nothing**. |

The rename touches the whole worker-restriction path and nothing else (in
particular it is unrelated to `SegmentTask.getNodeKinds()` /
`PipelineSegment.getNodeKinds()`, which mean "the kinds contained in a pipeline
segment"):

- `ProcessorRegistration` (rest-model) — announced `nodeWhitelist` / `nodeBlacklist`.
- `ConnectedProcessor` (`ProcessorRegistry`) — `accepts(kind)` returns false for a
  blacklisted kind, otherwise true when the whitelist is empty or contains the kind.
- `CortexOptions` / `CortexCLI` (worker config) — `--node-whitelist` /
  `--node-blacklist` flags (env `CORTEX_NODE_WHITELIST` / `CORTEX_NODE_BLACKLIST`);
  `LoomControlChannel.sendRegister()` announces both. The whitelist still defaults
  to the node factory's `registeredTypes()` so a worker cannot advertise work it
  cannot perform.

### The `cortex_instance` record

Previously a registered worker lived only in `ProcessorRegistry`'s in-memory map
and died with the Loom process; its restriction was whatever it announced, with
no way to remember or override it. Registration is now backed by a durable record
(migration `V2.33__add_cortex_instance.sql`):

- **`cortex_instance`** — one row per worker, keyed by the stable `node_id`
  (`UNIQUE`). Tracks identity (`name`, `host`, `priority`), presence (`last_seen`,
  `state`, `first_registered`), and the standard `meta`/audit columns. The
  `creator_uuid` / `editor_uuid` audit columns are **nullable** because a row is
  created by the machine that registers, with no user; the admin override path (UI)
  fills `editor_uuid` when it edits.
- **`cortex_instance_node_kind`** `(instance_uuid, node_kind, list)` — the
  whitelist/blacklist as a queryable/indexable child table (`list ∈ {WHITELIST,
  BLACKLIST}`), so a single kind can be looked up across all workers rather than
  buried in an opaque JSONB blob.
- Permissions `MANAGE_CORTEX_INSTANCE` / `READ_CORTEX_INSTANCE` (two-permission
  model: manage = write, read = view).

Persistence is jOOQ-only (`CortexInstanceDao` / `CortexInstanceDaoImpl`, exposed on
`DaoCollection`); there is deliberately **no in-memory DAO**, matching the pipeline
DAOs. The DAO provides `loadByNodeId`, `findAll`, and `upsertByNodeId` (insert or
update keyed by `node_id`, so re-registration never creates a duplicate row), and
round-trips both kind lists through the child table.

### Startup-config (DEFAULT) vs DB-override (OVERRIDE) precedence

On `REGISTER`, `ProcessorRegistry.register(...)` reconciles the announced
restriction against the persisted record:

1. **First registration** (no row for the `node_id`): the announced
   `nodeWhitelist` / `nodeBlacklist` are the **DEFAULT** and are seeded into a new
   `cortex_instance` row, and applied to the in-memory `ConnectedProcessor`.
2. **Reconnect / restart** (row exists): identity and presence are refreshed, but
   the persisted whitelist/blacklist are the **OVERRIDE** — they are applied to the
   `ConnectedProcessor` instead of blindly trusting what the worker re-announced,
   and they survive across reconnects. An administrator edits them via the record
   (Task 2/UI), and the change sticks even though the worker keeps announcing its
   own set.

Persistence failures never take a worker offline: if the reconcile step throws, the
registry falls back to the announced restriction and keeps serving.

---

## 12. Node Capability Matrix

Compact per-node status. Verified against the code and test tree.

**Columns**
- **Unit test runs node** — a unit test that actually invokes the node's
  `process`/`compute` (not just an `*OptionsValidationTest`).
- **Integration test** — exercised by an `integration-test` pipeline run
  (the `integration-test` pipeline drives `filesystem-source`, `sha512`, `md5`,
  `chunk-hash`, `thumbnail`, `vlm`, `loom` end-to-end; **all** node kinds are now
  registered as executable — see §8 — so this column reflects pipeline-run
  coverage, not what the worker can run).
- **Persists into Loom** — writes a typed payload and/or the
  `asset_node_result` ledger via the `LoomClient`.
- **Caches (what)** — in-heap `LocalResultCache` (worker-lifetime, non-durable)
  unless noted.
- **Media components** — whether the node emits per-component results
  (multiple audio tracks / video streams / fingerprint sectors / temporal
  segments). "No" = single whole-asset result.

| Node | Unit test runs node | Integration test | Persists into Loom | Caches (what) | Media components |
|---|---|---|---|---|---|
| `FilesystemSourceNode` | Yes | Yes | n/a (source; emits path) | No | No |
| `S3SourceNode` | Yes | Yes | n/a (source; emits `s3://` reference) | Persistent Avro object index (durable, per bucket+prefix) | No |
| `SHA512Node` | Yes | Yes | Yes - `asset` row + ledger | Yes - `SHA512` (100k) | No |
| `SHA256Node` | Yes | No | Yes - `asset` row + ledger | Yes - hash string (100k) | No |
| `MD5Node` | Yes | Yes | Yes - `asset` row + ledger | Yes - hash string (100k) | No |
| `ChunkHashNode` | Yes | Yes | Yes - `asset` row + ledger | Yes - hash string (100k) | No |
| `FingerprintNode` | Yes | No | Yes - `asset_fingerprint_comp` + ledger | Yes - fingerprint (own LRU 100k) | Partial - sector 0 only |
| `ConsistencyNode` | Yes | No | Yes - `asset` consistency block + ledger | Yes - zero-chunk count | No |
| `ThumbnailNode` | Yes | Yes | Partial - ledger only (bytes stay local) | Yes - thumb path (+ durable `.thumb`) | No |
| `FacedetectNode` | Yes | No | Yes - `detection` (bulk upsert) + ledger | Yes - count+flag+boxes snapshot | Partial - per-frame detections |
| `FacedescriptionNode` | Yes | No | Yes - `asset_json_comp` + ledger | Yes - per-face JSON | No (image only) |
| `OCRNode` | Yes | No | Yes - `asset_json_comp` + ledger | Yes - recognized text | No |
| `TikaNode` | Yes | No | Yes - `asset_json_comp` + ledger | Yes - Tika content | No |
| `WhisperNode` | Yes (+ persistence test) | No | Yes - `asset_transcript_comp` + ledger | Yes - transcript JSON | Partial - 1 track (`streamIndex 0`) |
| `TtsNode` | Yes (+ persistence test) | No | Partial - ledger only (WAV stays in local `tts_bin`) | Yes - audio path | No |
| `SentimentNode` | Yes (+ persistence test) | Yes | Yes - `asset_json_comp` (`variant` = source output key) + ledger | Yes - scored result JSON | No |
| `DepthmapNode` | Yes (+ persistence test) | Yes | Partial - ledger only (16-bit PNG stays in local `depthmap_bin`); records `producerVersion` | Yes - meta JSON (embeds the artifact path) | No |
| `SceneLayoutNode` | Yes (+ persistence + solver + sampler tests) | Yes | Yes - `asset_json_comp` (`schemaType="scene-layout"`) + ledger | Yes - layout result JSON | No |
| `DominantColorNode` | Yes (+ persistence + pipeline + colour-space / distance / namer / k-means / region tests) | Yes | Yes - `asset_json_comp` (`schemaType="dominant-color"`) + ledger | Yes - palette JSON, keyed by path **+ upstream payloads + options hash** | No |
| `LLMNode` | Yes | No | Yes - `asset_json_comp` per prompt + ledger | Yes - per-prompt outputs | No |
| `VlmNode` | Yes | Yes | Yes - `asset_json_comp` per prompt + ledger | Yes - per-prompt outputs | No |
| `QualityNode` | No (options only) | No | Yes - `asset_json_comp` + ledger | Yes - metric snapshot | Partial - image/video block |
| `SceneDetectionNode` | Yes | No | Yes - `asset_segment_comp` (replace) + ledger | Yes - scene output | Yes - scenes (`seq` set) |
| `CaptioningNode` | Yes | Yes | Yes - `asset_json_comp` + ledger | Yes - caption | Partial - video scene timeline (scene strategy) |
| `ImageGenNode` | Yes (+ persistence test) | No | Partial - ledger only (PNG stays in local `imagegen_bin`) | Yes - image path | No |
| `ScriptNode` | Yes (+ persistence + pipeline tests) | Yes | Yes - `asset_json_comp` (`variant` = node id) + `asset_segment_comp` + ledger | Yes - output bag, keyed by path **+ script hash** | Yes - `TIMEFRAMES` outputs become segment rows |
| `HashDedupNode` | No (empty stub) | No | Partial - ledger only (side effect) | No (moves files) | No |
| `FingerprintDedupNode` | No (empty stub) | No | No (node is a stub) | No | No |
| `LoomNode` | Yes | Yes | Yes - bulk `asset` hash update | No (in-heap batch buffer, not a result cache) | No |
| `S3SinkNode` | Yes (+ persistence tests) | Yes (real MinIO) | Yes - an `asset` per artifact + `asset_json_comp` index + ledger | No | Yes - one entry per uploaded artifact |

**Notable gaps**
- **No unit test that runs the node**: `QualityNode` (only options validation),
  `HashDedupNode` / `FingerprintDedupNode` (test classes are empty stubs).
- **Integration coverage**: beyond the 7 pipeline kinds exercised end-to-end, per-node
  end-to-end integration tests now live in `integration-test`
  (`io.metaloom.loom.test.integration.node.*NodeIntegrationTest`). Each boots a
  real in-process Loom (REST + pooled DB), runs the production node against a real
  file with a real `LoomHttpClient`, and asserts the typed payload reached its
  component table and is readable back via REST. Covered: hash (md5/sha256/
  sha512/chunk-hash), consistency, tika, quality, scene, thumbnail, fingerprint,
  facedetect, ocr, vlm, whisper, tts, sentiment, imagegen, depthmap, scene-layout, dominant-color, loom,
  s3-source. `s3-source` runs against a real MinIO container (`MinioContainer`) with no stubbing at
  all — real listings, real ETags, real downloads — and ends by driving a real `SHA512Node` over a
  materialized object to prove S3 media persists like any local file.
  `scene-layout` and `dominant-color` are stubbed nowhere at all — neither has a model, so their
  integration tests run the real computation against a real PNG on disk. `dominant-color`'s IT
  additionally asserts the German colour name reads back byte-for-byte through JSONB and REST, which
  is the only place UTF-8 survival across the whole persistence chain is pinned. The compute is stubbed for nodes needing a
  native model / external runtime (ocr → `OCRProvider`, whisper →
  `WhisperMediaProcessor`, facedetect → `InspireFacedetector`, tts → `TtsClient`,
  sentiment → `SentimentClient`)
  while the file,
  client, persistence and REST read-back remain real; the video4j nodes (quality,
  scene, thumbnail, fingerprint) run real OpenCV compute and self-skip
  (`assumeVideo4j()`) when the native runtime is absent. The LLM-family nodes
  (`llm`, `captioning`, `facedescription`) are covered by driving them against the
  `genai-utils` `MockLLMServer` (OpenAI-compatible) instead of a live Ollama /
  SmolVLM backend: `LLMNode` now takes an injectable `LLMProvider` + a
  `providerType` option (default Ollama; the IT injects `VLLMLLMProvider`),
  `CaptioningNode` takes an injectable `SmolVLMClient` (image path) plus an
  injectable `VideoVLMClient` (video path) — the video IT points that
  OpenAI-compatible client straight at the mock and asserts a `video-caption`
  component — and `FacedescriptionNode`'s
  `processFace` seam is routed to the mock. `VlmNode` follows the same pattern with
  an injectable `VlmChatClient`, and because that client already speaks the
  OpenAI-compatible protocol the mock exercises the node's real request/response
  path rather than a stub. Every node kind now has an end-to-end integration test.
- **Media components**: only `SceneDetectionNode` emits a genuine multi-row
  component set today; `FacedetectNode` rows are frame-indexed. Whisper and
  Fingerprint are hard-wired to a single index; true multi-track / multi-stream
  extraction is open.
- `FingerprintDedupNode` is still a `not implemented` stub; `HashDedupNode`
  retains dead code (`System.in.read()`).

---

_Git HEAD revision: `5ac79b6d`_
_Last updated: 2026-07-28 (added the `dominant-color` node — deterministic k-means in CIELAB over
stride-sampled pixels, reporting HEX/RGB/HSL/CIELAB+LCh plus a bilingual EN/DE name built from the 11
Berlin & Kay basic colour terms (nearest prototype by CIEDE2000) and an LCh-derived modifier. Measures
the whole frame, an optional configured region and every upstream `detections` box in one pass;
persists to `asset_json_comp` (`schemaType="dominant-color"`). No model, no sidecar, no OpenCV. Two
things worth knowing: the term codebook uses **several Lab prototypes per term** rather than one anchor
— a single anchor demonstrably names navy *purple* and pure green *yellow*, because lightness is then
counted twice — and pixel reduction is **stride sampling, not bilinear downscaling**, because
interpolation invents colours that are not in the image (a red/blue stripe pattern averages to purple)
and bleeds transparent pixels into their neighbours. Its `LocalResultCache` key covers the upstream
payloads and options, not just the media path, which is the bug `scene-layout` still has. Adds the
`data/color` content type. See [NODE_DOMINANT_COLOR_PLAN.md](NODE_DOMINANT_COLOR_PLAN.md).
Previously: added the `s3-source` node — differential ingest from S3-compatible object
storage via a persisted per-bucket Avro index, an opt-in `startAfter` resume path, and optional bucket
notifications (MinIO webhook on the monitoring port / AWS SQS) that let a run skip listing entirely,
backstopped by a periodic reconcile listing. That work also changed **media addressing** (§4):
`ProcessableMedia.reference()`, `NodeTaskRunner.MediaResolver.resolve(MediaRef)` and the new
`MediaReferenceResolver` mean media travels as a reference rather than a path, and S3 objects are
materialized lazily by whichever worker runs the node task — so `MediaRef`'s "shared storage is a
prerequisite" no longer holds for object storage. New modules: `cortex/s3-common` and
`cortex/nodes/s3-source`. Credentials are worker-level (`CortexOptions.getS3()`, `CORTEX_S3_*`) and
never enter a pipeline definition. Also fixed two latent defects found on the way: `bom/pom.xml`
declared `aws.sdk.version` 2.29.70, which has never existed on Maven Central (nothing consumed the
dependency, so nobody noticed), and `cortex/cli`'s shade config had no `ServicesResourceTransformer`,
which would have broken the AWS SDK's `ServiceLoader` lookup in the container image only. See
[NODE_S3SOURCE_PLAN.md](NODE_S3SOURCE_PLAN.md). Previously: cross-linked the new
[../pipeline/NODE_DATA_TYPES.md](../pipeline/NODE_DATA_TYPES.md) from §1 `NodeOutputKey`, §3 and §9
`Upstream Output Access` — it owns the per-node input/output type reference and the type-safety audit,
so this file keeps behaviour, configuration and persistence. Previously: added the `depthmap` and `scene-layout` nodes. `depthmap` runs monocular depth
estimation via the `sidecars/depth` FastAPI sidecar and writes a 16-bit **NEARNESS** PNG — 65535 = nearest
— to the local `depthmap_bin` cache, ledger-only but stamped with the model. `scene-layout` has no model
at all: it joins detector boxes to that map and derives depth bands plus pairwise spatial relations into
`asset_json_comp`. `FacedetectNode` gained a `detections` output so the boxes no longer have to travel
through Loom. 🔴 The two new nodes must share an affinity group — the map is worker-local. See
[NODE_DEPTHMAP_PLAN.md](NODE_DEPTHMAP_PLAN.md) and [NODE_SCENE_LAYOUT_PLAN.md](NODE_SCENE_LAYOUT_PLAN.md).
Previously: added the `script` node — runs a user-supplied script (GraalJS) as a pipeline
step with *declared* multi-valued outputs, persisting to `asset_json_comp` (`variant` = node id) plus
`asset_segment_comp` for timeframes; see [NODE_SCRIPT_PLAN.md](NODE_SCRIPT_PLAN.md). That work added §5.1
**per-instance node configuration** (`PipelineConfigurable`) and fixed the two defects that meant node
parameters set in the pipeline editor had never reached a worker. §10 gains two newly-recorded defects:
`PipelineNode.timeoutMs()` is parsed but enforced by nothing, and `ctx.failure(...).next()` reports
SUCCESS in eleven nodes. Previously: the `sentiment` node — EN/DE polarity of upstream text via the
`sidecars/sentiment` FastAPI sidecar, persisted to `asset_json_comp` with `variant` = source output
key; see [NODE_SENTIMENT_PLAN.md](NODE_SENTIMENT_PLAN.md))_
