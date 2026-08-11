# SAM 2 Node (`sam2`) — Per-Pixel Segmentation, Prompted Cut-Outs and Video Tracking

> **Status**: 🟢 **Built and shipping.** Kind `sam2`, module
> [cortex/nodes/sam2/](../../../../cortex/nodes/sam2/), package `io.metaloom.cortex.node.sam2`.
> 63 unit tests + 1 integration test; inference in the FastAPI sidecar
> [sidecars/sam2/](../../../../sidecars/sam2/) on port 9130. Contract in the generated
> `node-descriptors.json`, kept honest by `NodeSpecGoldenTest`.
> **Scope**: the `sam2` node and the sidecar it talks to — everything from the source file's bytes to
> the `sam2_bin` mask directory and the `asset_node_result` ledger row.
> **Audience**: AI coding agents and humans working on
> [cortex/nodes/sam2/](../../../../cortex/nodes/sam2/).

**Out of scope, and where it lives instead:**

| Not here | There |
|---|---|
| The node system, lifecycle, registration, caching layers | [../NODES.md](../NODES.md) |
| Port content types and cardinality across all nodes | [../../pipeline/NODE_DATA_TYPES.md](../../pipeline/NODE_DATA_TYPES.md) §4 |
| Rules for adding a node at all | [../../../guidelines/NEW_NODE.md](../../../guidelines/NEW_NODE.md) |
| Where the prompt boxes come from | `objectdetect`, `facedetect` — [../NODES.md](../NODES.md) §3.1 |
| Keeping the produced masks off the worker | `s3-sink`, [../NODES.md](../NODES.md) §2.1 |
| The customer-facing page and its two screenshots | [../../../website/WEBSITE.md](../../../website/WEBSITE.md) § Node pages |
| The other depth/geometry consumer of the same portrait | `depthmap`, `scene-layout` |

---

## 0. Executive Summary

| Question | Short answer |
|---|---|
| **What does it do?** | Turns "where is it" (a rectangle) into "which pixels are it" (a mask), in three modes |
| **What are the three modes?** | `AUTOMATIC` segment everything · `PROMPTED` one mask per upstream box · `TRACK` propagate through a clip (§2) |
| **How is the mode chosen?** | An explicit `mode` **option**, never from which ports happen to be wired (§3.1) |
| **Where do the masks go?** | `metaPath/sam2_bin/<segment>/<sha512>-<digest>/` as binary PNGs — ledger only, wire `masks` into `s3-sink` to keep them |
| **Does it need a GPU?** | Strongly recommended. `pointsPerSide=32` is 1024 forward passes for one still |
| **Does it write to the schema?** | No. One `asset_node_result` row, `result_ref == null` — there is no polygonal geometry column (§4) |
| **Which picture is the honest one?** | The `overlay` port. The `masks` port is `MANY` and the runtime previews only its first element (§3.5) |

```
image | video (XOR media_alt)      ──▶  sam2  ──▶  masks      : artifact/image  MANY
detections : detection/* MANY  ┈┈▶  (optional)  ──▶  segments   : struct/masks
                                                ──▶  overlay    : artifact/image
                                                ──▶  mask_count : scalar/integer
                                                ──▶  flag       : scalar/string
```

---

## 1. Why the node exists

Every geometry the Loom schema holds is an **axis-aligned rectangle** — `detection.bbox_*`, whose
V2.43 migration comment calls it "the single geometry convention", and `tag_asset`'s area columns.
Nothing else. `STRUCT_SEGMENTS` sounds relevant and is not: it is labelled "Timeframes" and is
time-coded, not spatial.

Segmentation had been deferred once already, in the depthmap plan
("*Segmentation masks / stereo / multi-view — out of scope*"). This node is that deferral resolved,
and it is the first producer of per-pixel shape in the tree.

---

## 2. The three modes

`mode` selects one. They are genuinely different jobs, not quality levels — which is why all three
shipped together rather than as a node and a later sibling.

| Mode | Media | Prompts | What it produces |
|---|---|---|---|
| `AUTOMATIC` (default) | image, video | none | Every distinct region SAM 2 finds, sorted **largest first**, unlabelled |
| `PROMPTED` | image, video | required | One mask per upstream box, carrying that detector's label |
| `TRACK` | **video only** | optional | One mask per object per sampled frame, one identity across the clip |

Behaviour that follows from the media shape rather than the mode:

* **`AUTOMATIC`/`PROMPTED` on a video segment exactly one frame** — the one named by `trackFrame`.
  Segment-everything over 64 frames is 64k forward passes and tens of thousands of mask files for one
  asset; the node would look like it hung. `TRACK` is the mode that spans the clip, and it exists so
  this one does not have to.
* **`TRACK` on a still fails, it does not skip.** The worker was given a job it cannot do, and a skip
  reads as "this item did not need processing". Pinned by `Sam2NodeTest.testTrackOnAnImageFails`.
* **`PROMPTED` with no boxes skips.** A photo where the detector found nothing is a normal outcome.
* **`TRACK` with nothing wired prompts the whole first frame** and lets propagation carry it — SAM 2's
  own demo shape, and what "everything here" means to a box-prompted predictor.

---

## 3. The decisions worth keeping

### 3.1 🔴 The mode is an option, not inferred from wiring

`NodeContext.create(media)` builds **empty** inputs, so `ctx.isWired` is false for every docs fixture
and every unit test — a wiring-derived mode would document and test a different node from the one
that runs in a real graph. Wiring also cannot separate `AUTOMATIC`-on-video from `TRACK`: both are
"video in, no detections". Media *shape* is still read from `ctx.media()`; the mode says what to do,
the file says whether there are frames to do it over.

### 3.2 🔴 Three coordinate spaces, all named

The sharpest correctness property in the node. Three spaces are in play:

1. the **source** image or the video's native frame — where upstream boxes were measured;
2. the **posted** image, the source downscaled to `maxDim` — what the sidecar actually sees;
3. the **mask**, which is whatever size the sidecar says it is.

So the manifest reports **both pairs**: `width`/`height` describe the masks, `imageWidth`/
`imageHeight` describe the source. Without both, projecting a mask back onto the original is a guess —
the reason `DepthmapNode.buildMeta` gives.

Two traps inside that:

* **(2) and (3) are not always the same.** The sidecar clamps `max_dim` to its own `SAM2_MAX_DIM`, so a
  node asking for more gets masks smaller than the image it sent. `writeImageResult` therefore reads
  the mask size from the **response**, falling back to the posted size only when the response says
  nothing. `Sam2NodeTest.testMaskDimensionsComeFromTheSidecarNotThePostedImage`.
* **A box measured against a differently-sized frame must be re-based before it is scaled** — a
  detector run with its own `videoScaleSize`, say. `UpstreamBox.toPrompt` multiplies by
  `nativeWidth / imageWidth` first.

### 3.3 XYWH in, XYXY out

Upstream detectors emit `{"bbox":{"x","y","w","h"}}`; SAM 2 takes `x1,y1,x2,y2`. Converted **once**,
in `Sam2Box.fromXywh`. `readDetections` also honours the element's own `coordinates` convention:
`NORMALIZED` boxes are multiplied back up by `imageWidth`/`imageHeight` — the guard copied from
`SceneLayoutNode.readElement`, because the same producers feed both nodes.

### 3.4 TRACK snaps a prompt to the nearest sampled frame

The upstream detector samples at *its* chop rate, so a box stamped frame 137 need not be one of the
frames this node sampled. `SampledFrames.nearestIndex` snaps to the nearest sampled frame rather than
dropping the box, because dropping it silently loses an object — and forcing the two chop rates to
agree would be a `nodeId:outputKey`-shaped coupling, which [../NODES.md](../NODES.md) §6.4 forbids.

`frameNumbers` carries **source** frame numbers, not `0..N-1`: a mask on sampled entry 3 means
nothing, a mask on source frame 75 can be seeked to.

### 3.5 🔴 The overlay exists because MANY-port previews show one element

`NodePreviews` downsamples only the **first** element of a `MANY` port, so a segment-everything run
would illustrate itself with a single cut-out. Mitigated twice over: the `overlay` port is `ONE`
`artifact/image` — the tinted composite, auto-previewed — and the node additionally attaches an
explicit `ctx.preview(OUT_MASKS, i, …)` per mask. This is why `emitOverlay` defaults to **true**.

`ctx.preview(OUT_SEGMENTS, …)` also renders the masks as a markdown table (label, area, score, box,
frame), because the default rendering of an element is the path it is, which says nothing about what
was segmented.

### 3.6 One MANY output, deliberately

`ObjectDetectNode` documents that two `MANY` outputs of different lengths zip incorrectly when both
are wired downstream. Only `masks` is `MANY`; per-mask labels live inside the `segments` elements
instead. `Sam2NodePipelineTest.testTheMasksPortChainsIndependentlyOfTheOverlay` pins the shape.

### 3.7 The manifest is written last, and the digest is in the directory name

`manifest.json` is the **commit marker**: its presence is what the skip cache stats, so it must not
exist until every file it names does. One stat call regardless of mask count, and a directory a
killed worker left half-written has none — so the node recomputes rather than handing out a manifest
naming files that are gone.

The option digest — including the **prompt boxes** — is in the *directory name*, not only in the cache
key. Two `sam2` instances in one graph (an automatic pass and a `person`-only prompted pass) must
neither serve nor overwrite each other's masks. That is `image-manipulation`'s lesson.

A **debug run bypasses the cache entirely**: the cache holds the ports and nothing else, so a hit
would re-emit paths with no overlay and no crops. The same file showing its masks the first time it is
examined and not the second is precisely what makes a debugging view untrustworthy.

### 3.8 🔴 `ctx.failure(msg).abort()`, never `.next()`

`NodeContextImpl.next()` reads `skipReason` but **not** `failureCause`, so a failure returned through
it reports `SUCCESS` with the message dropped. This node aborts. `depthmap` still uses `.next()` there
and is wrong — see [../NODES.md](../NODES.md) and the nineteen-node list in
[../../../website/WEBSITE.md](../../../website/WEBSITE.md).

### 3.9 A new `struct/masks` content type, not `struct/segments`

The `segments` output carries a **new** content type, `struct/masks` ("Segmentation Masks"), added to
`ContentTypeRegistry` for this node. `struct/segments` was the obvious candidate and was rejected: it
is labelled **"Timeframes"**, it means *time-coded* segments of a media item, and it is already wired
to time-range consumers — `scene-detection`'s `scenes` port and the `script` node's `TIMEFRAMES` value
type. Reusing it would have let a spatial mask list be plugged into a port expecting a timeline, and
the port model has no way to catch that afterwards. The registry constant carries the same warning:

```java
/** Spatial per-object segmentation masks. Deliberately not {@link #STRUCT_SEGMENTS}, which is time-coded. */
public static final String STRUCT_MASKS = "struct/masks";
```

This node is the first and so far only producer of `struct/masks`
(`NodeDescriptorServiceLoaderTest` records it as such).

### 3.10 Design decisions and rejected alternatives

The condensed decision record — what shipped, what was considered instead, and why the alternative
lost. Each row points at the section that carries the detail.

| Decision | What shipped | Rejected alternative | Why the alternative lost |
|---|---|---|---|
| Scope (§2) | All three SAM 2 capabilities in one node: `AUTOMATIC`, `PROMPTED`, `TRACK` | Ship one mode, add the others as sibling nodes later | They are different jobs, not quality levels; leaving any out would have meant a second node covering the same model and the same sidecar |
| Mode selection (§3.1) | An explicit `mode` option | Derive the mode from `ctx.isWired` | `NodeContext.create(media)` builds empty inputs, so `isWired` is false for every docs fixture and unit test — the documented node would not be the node that runs. Wiring also cannot separate `AUTOMATIC`-on-video from `TRACK` |
| Content type (§3.9) | A new `struct/masks` | Reuse `struct/segments` | `struct/segments` is "Timeframes", time-coded, and already wired to time-range consumers |
| Persistence (§4) | Ledger only: mask files on the worker, one `asset_node_result` row, `result_ref == null` | An RLE or polygon in `detection.meta`, or a migration adding a geometry column | `detection` has no polygonal geometry column, and a payload in `meta` would be a write path with no read path — the defect [../../../guidelines/NEW_NODE.md](../../../guidelines/NEW_NODE.md) §1.4 exists to prevent. Ledger-only meant no migration and no schema risk |
| Runtime (§6) | FastAPI sidecar on **9130**, Java side a pure `java.net.http` client | An in-JVM inference path | Same shape as `depthmap`; 9100 TTS, 9110 sentiment, 9120 depth, so 9130 continues the 91xx analysis band |
| Sidecar dependency (§6) | `transformers` | The PyPI `sam2` distribution, or Meta's own repository | The PyPI package is a third-party upload; Meta's route builds a CUDA extension and pins hydra. `setup.sh` asserts the four classes import instead of pinning a `transformers` minor, because which release first shipped the video classes moves |
| Video decoding (§6) | Frames sampled in **Java** (`Sam2FrameSampler`, video4j) and POSTed as base64 JPEGs | Post the video file and let the sidecar decode | The tree already owns this policy three times over; a server-side path would silently require the sidecar to be co-located with the media, and the video bytes are unbounded |
| Port shape (§3.6) | Exactly one `MANY` output (`masks`); per-mask labels live inside `segments` | A second `MANY` port for labels or scores | `ObjectDetectNode` documents that two `MANY` outputs of different lengths zip incorrectly when both are wired downstream |
| Preview (§3.5) | An `overlay` port, `ONE` `artifact/image`, on by default | Rely on the `masks` port previewing itself | `NodePreviews` downsamples only the first element of a `MANY` port, so a segment-everything run would illustrate itself with one cut-out. This is why `emitOverlay` defaults to **true** |
| Prompt frame alignment (§3.4) | `SampledFrames.nearestIndex` snaps an upstream box to the nearest sampled frame | Require the detector and this node to use the same `videoChopRate` | Dropping an unmatched box silently loses an object, and forcing the two rates to agree is a `nodeId:outputKey`-shaped coupling that [../NODES.md](../NODES.md) §6.4 forbids |
| Cache identity (§3.7) | The option digest — **including the prompt boxes** — is in the artifact *directory name*, not only in the cache key | Digest in the cache key alone | Two `sam2` instances in one graph (an automatic pass and a `person`-only prompted pass) must neither serve nor overwrite each other's masks. `image-manipulation`'s lesson |
| Commit marker (§3.7) | `manifest.json` written **last**; its presence is what the skip cache stats | Stat the mask files, or a per-file check | One stat call regardless of mask count, and a directory a killed worker left half-written has no manifest, so the node recomputes instead of handing out a manifest naming files that are gone |
| Failure signalling (§3.8) | `ctx.failure(msg).abort()` | `ctx.failure(msg).next()`, as `depthmap` does | `next()` reads `skipReason` but not `failureCause`, so the run reports `SUCCESS` with the message dropped |
| Demo data (§10) | None seeded | A demo pipeline containing `sam2` | Explicit `imagegen` / `tts` / `depthmap` / `videogen` precedent: the demo container has no sidecar, and a demo pipeline that cannot run is worse than an absent one |

---

## 4. Persistence: ledger only

| What | Where |
|---|---|
| The mask PNGs, the overlay, `manifest.json` | `metaPath/sam2_bin/<segment>/<sha512>-<digest>/` on the worker |
| The record that this node ran | one `asset_node_result` row, `result_ref == null` |
| Which checkpoint produced it | `producerVersion = sam2/1:<checkpoint>` |

No migration, no schema risk, and deliberately so: `detection` has no column for polygonal geometry,
and putting an RLE or a polygon in `meta` would be a **write path with no read path** — the defect
[../../../guidelines/NEW_NODE.md](../../../guidelines/NEW_NODE.md) §1.4 exists to prevent. The
checkpoint is in the producer version because which checkpoint produced a mask materially changes its
edges.

> 🔴 **The masks are worker-local, and there are N of them, not one.** Any node consuming them must run
> on the same worker — pin them into one **affinity group**. Wiring `masks` into `s3-sink` is the only
> supported way to get the bytes off the worker.

---

## 5. The flag port

`flag` is the node's own verdict and it is checked independently of the result state — including by
the docs fixture generator, which refuses to publish a screenshot of a run whose flag says `FAILED`.

| Value | Meaning |
|---|---|
| `SUCCESS` | Masks produced and nothing was cut short |
| `NONE` | Nothing segmented. **Not a failure** — a blank wall is a valid answer |
| `CAPPED` | Masks produced, but `maxMasks` or `maxFrames` stopped the work early |
| `FAILED` | The sidecar call or the write failed; the node aborts with the cause |

`CAPPED` is its own value rather than `SUCCESS` because the difference between "this is what is in the
file" and "this is the first N of what is in the file" is exactly what a consumer of a truncated
result needs to know. `objectdetect`'s precedent.

---

## 6. The sidecar protocol

FastAPI, `sidecars/sam2`, port **9130** — 9100 is TTS, 9110 sentiment, 9120 depth, so 9130 continues
the 91xx analysis band. The Java side is a pure `java.net.http` client that **forces HTTP/1.1**,
because FastAPI rejects the JDK client's default HTTP/2 upgrade attempt (`DepthmapClient`'s lesson).

| Endpoint | Called for | Request | Response |
|---|---|---|---|
| `POST /v1/segment` | `AUTOMATIC`, `PROMPTED` | `image_b64`, `mode`, `max_dim`, `points_per_side`, `pred_iou_thresh`, `stability_score_thresh`, `min_mask_area`, `max_masks`, `multimask`, `boxes?`, `model?` | `{model, mode, width, height, masks[{index, png_b64, area, score?, bbox, label?, promptIndex?}], truncated{masks}}` |
| `POST /v1/track` | `TRACK` | `frames_b64`, `frame_numbers`, `max_dim`, `max_masks`, `prompts[{obj_id, frame_index, box}]`, `model?` | `{model, mode, width, height, frameCount, objects[], frames[{index, frameNumber, masks[{objId, png_b64, area, bbox}]}], truncated{}}` |
| `GET /health` | probes, the docs fixture requirement | — | `{status, device, models{default}, maxDim, maxFrames, loaded[]}` |

* **Every coordinate crossing this boundary is in the posted image's space**, never the source
  resolution. The sidecar never learns the source size and must not have to guess it.
* **Masks come back as base64 8-bit grayscale PNGs** whose pixels are 0 or 255 — 8 bits rather than 1
  because a 1-bit PNG is a decoding hazard in Java's ImageIO, and deflate makes the size difference
  negligible for a two-value image. `Sam2NodeTest.testProducedMaskIsBinaryGrayscale` pins it.
* **Video frames are sampled in Java** (`Sam2FrameSampler`, video4j) and POSTed as base64 JPEGs. The
  tree already owns this policy three times over; a server-side path would silently require the
  sidecar to be co-located, and the video bytes are unbounded.
* **`transformers`, not the PyPI `sam2` distribution** — that is a third-party upload, and Meta's own
  route builds a CUDA extension and pins hydra. `setup.sh` asserts `Sam2Model` / `Sam2VideoModel` /
  `Sam2Processor` / `Sam2VideoProcessor` import rather than pinning a `transformers` minor, because
  which release first shipped the video classes moves.
* **One GPU lock plus `--workers 1`.** `pointsPerSide=32` is 1024 forward passes for a single image,
  and the video predictor holds a **per-request memory bank** that grows with objects x frames. Two
  concurrent requests do not go faster; they run the card out of memory. The node declares
  `defaultConcurrency = 1` to say so; the lock is what guarantees it when something else calls the
  server.

### 6.1 Known limitation

The `transformers` `"mask-generation"` pipeline does not expose SAM 2's own thresholds. `AUTOMATIC`
applies `predIouThresh` as a post-filter on the score the pipeline does return, and
**`stabilityScoreThresh` has no analogue in that path and is currently ignored**. The node's contract
is unaffected — masks are still filtered by `minMaskArea` and capped by `maxMasks` — and the option is
kept because it becomes live the moment that path grows one.

---

## 7. Options

All are `sam2.*` node options ([../NODES.md](../NODES.md) §7 for how they are set).

| Option | Type | Default | Notes |
|---|---|---|---|
| `sam2Host` | `STRING` | `localhost` | Sidecar host |
| `sam2Port` | `INTEGER` | `9130` | Sidecar port |
| `mode` | `ENUM` | `AUTOMATIC` | `AUTOMATIC` \| `PROMPTED` \| `TRACK` — §2 |
| `model` | `STRING` | `null` | Checkpoint override; `null` uses the sidecar's default |
| `maxDim` | `INTEGER` | `1024` | Longest side posted; **also the size of every produced mask** |
| `pointsPerSide` | `INTEGER` | `32` | `AUTOMATIC` only. 32 = 1024 forward passes per image |
| `predIouThresh` | `NUMBER` | `0.8` | `AUTOMATIC` only |
| `stabilityScoreThresh` | `NUMBER` | `0.95` | `AUTOMATIC` only — **currently ignored**, §6.1 |
| `minMaskArea` | `INTEGER` | `256` | Measured on the downscaled image. `0` is legal |
| `maxMasks` | `INTEGER` | `64` | Hitting it reports `CAPPED` |
| `multimask` | `BOOLEAN` | `false` | `PROMPTED` only. ⚠️ Three candidates per box are produced **before** the cap applies, so turning it on can truncate a permissive box set on its own. Documented rather than validated — both settings are individually legal |
| `videoChopRate` | `INTEGER` | `25` | Sample every Nth frame — roughly one per second of typical footage |
| `maxFrames` | `INTEGER` | `64` | Hitting it reports `CAPPED` |
| `trackFrame` | `INTEGER` | `0` | Which sampled frame the prompts go on; also the frame `AUTOMATIC`-on-video segments |
| `emitOverlay` | `BOOLEAN` | `true` | §3.5 — do not default this off |
| `timeoutMs` | `INTEGER` | `300000` | **Overridden from `AbstractNodeOptions`' default** and advertised via `@ParamOverride`: segment-everything runs for minutes, so `depthmap`'s 120 s would time out a healthy request |
| `enabled`, `processIncomplete`, `retryFailed` | | `true`/`false`/`false` | Standard, from `AbstractNodeOptions` |

### Sidecar environment variables

| Variable | Default | Meaning |
|---|---|---|
| `SAM2_HOST` / `SAM2_PORT` | `0.0.0.0` / `9130` | Listener |
| `SAM2_MODEL` | `facebook/sam2.1-hiera-small` | Default checkpoint; a per-request `model` overrides it |
| `SAM2_MAX_DIM` | `1024` | Server-side cap on the longest side — a request may ask for less, never more |
| `SAM2_MAX_FRAMES` | `128` | Server-side cap on frames per `/v1/track` request |
| `DEVICE` | `cuda` if available | torch device |

---

## 8. Models and licensing

Default **`facebook/sam2.1-hiera-small`** (46.0 M params). SAM 2 code and every 2.1 checkpoint are
**Apache-2.0** — unlike the Depth Anything V2 family, there is no non-commercial member to avoid, so
nothing here needs a `#restricted` entry on the model-licences page.

| Model id | Params | Rough inference VRAM |
|---|---|---|
| `facebook/sam2.1-hiera-tiny` | 38.9 M | ~2.5 GB |
| `facebook/sam2.1-hiera-small` | 46.0 M | ~3 GB (**default**) |
| `facebook/sam2.1-hiera-base-plus` | 80.8 M | ~4.5 GB |
| `facebook/sam2.1-hiera-large` | 224.4 M | ~9 GB |

⚠️ **The VRAM column is estimated from parameter counts, not measured.** For `/v1/track` the weights
are not the dominant term anyway: the memory bank grows with tracked objects × frames, so `maxFrames`
and `maxMasks` are the real VRAM knobs.

---

## 9. Key Classes Reference

| Class | Package (`io.metaloom.cortex.node.sam2`) | Purpose |
|---|---|---|
| `Sam2Node` | — | Kind `sam2`; ports, the three modes, digest-keyed cache, artifact directory, ledger |
| `Sam2NodeOptions` | — | `KEY = "sam2"`, the sixteen options, `validate()` |
| `Sam2NodeModule` | — | Dagger `@Binds @IntoSet` + `@Binds @IntoMap @StringKey("sam2")` |
| `Sam2Mode` | — | `AUTOMATIC` / `PROMPTED` / `TRACK` |
| `Sam2Client` | — | The sidecar HTTP client. **The seam the tests replace** — non-final class and methods |
| `Sam2Box` | — | XYXY prompt box; `fromXywh` is the single conversion point |
| `Sam2TrackPrompt` | — | `objId` + `frameIndex` + box, for `/v1/track` |
| `Sam2Images` | — | Read, downscale, base64 PNG encode/decode, atomic PNG write, the tinted overlay composite |
| `Sam2FrameSampler` | `…sam2.video` | video4j frame sampling at `videoChopRate`, bounded by `maxFrames` |
| `SampledFrames` | `…sam2.video` | The sampling result: JPEGs, **source** frame numbers, native + sampled sizes, `nearestIndex`, `scaleToSampled` |
| `LocalResultCache` | `io.metaloom.cortex.common.cache` | **reused** — the in-heap manifest skip cache |

---

## 10. Progress Assessment

### Done

- [x] Module, node, options, Dagger module, kind binding in `NodeCollectionModule`
- [x] All three modes, on stills and on video; XOR `media_alt` input group
- [x] Sidecar `sidecars/sam2` (`setup.sh` / `run.sh` / `server.py`) on 9130, `/v1/segment`, `/v1/track`, `/health`
- [x] New `struct/masks` content type in `ContentTypeRegistry` and in the staged descriptor snapshot
- [x] Both dimension pairs in the manifest; XYWH→XYXY conversion; `NORMALIZED` box handling; frame snapping
- [x] Digest-keyed cache covering the options **and** the prompt boxes, digest in the directory name
- [x] Manifest-last commit marker; cache falls through when it is gone, and in a debug run
- [x] `abort()` on failure, not `next()`; `FAILED` on the flag port before aborting
- [x] Overlay port + per-mask previews + the `segments` markdown table
- [x] 63 unit tests + `Sam2NodeIntegrationTest`; descriptor pinned by `NodeSpecGoldenTest`
- [x] Customer docs page `website/content/english/docs/nodes/sam2/` with `nodeviz`, `config.png`,
      `debug.png` and `debug-detail.png` — real AUTOMATIC run against the sidecar on a GPU

### Follow-ups this node creates

- [ ] 🔴 **A queryable mask.** Masks are worker-local files: there is no way to ask "which assets have a
      person mask covering more than 30% of the frame". Needs either a byte-ingest endpoint for
      produced media or a real geometry column — both are their own change. Shares the blocker
      recorded on `image-manipulation` (`AttachmentType` has no `DERIVED_IMAGE` value).
- [ ] **`stabilityScoreThresh` is dead until the sidecar grows a path for it** (§6.1). Either wire the
      real SAM 2 automatic-mask-generator instead of the `"mask-generation"` pipeline, or mark the
      option unsupported in the descriptor.
- [ ] **No demo data**, following the explicit `imagegen`/`tts`/`depthmap`/`videogen` precedent: the
      demo container has no sidecar, and a demo pipeline that cannot run is worse than an absent one.
- [ ] **The debug card cannot reach the overlay.** `NodeResultStrip` caps at three rows and this node
      emits five ports, so `masks` and `overlay` collapse into a `+n more` chip that is not clickable.
      The Results tab lists all five but renders no thumbnails and opens nothing. Either make the chip
      expand or render previews in the Results tab; until then the page documents the limitation.

### Deliberately not built

- [ ] **No `PROMPTED` docs fixture.** Photographing it needs the YOLO natives and an ONNX model *on top
      of* the sidecar — two requirements for one picture, and an aborted fixture teaches nobody
      anything. `SidecarRecipes.sam2()` runs `AUTOMATIC`.
- [ ] **No point or scribble prompts.** SAM 2 takes them; no producer in the tree emits them.
- [ ] **No per-box fan-out.** `IN_DETECTIONS` is `MANY`, so the whole detector branch is gathered and
      every box goes through **one** call — one image encode rather than one per object.
- [ ] **No nested fan-out.** This node emits a `MANY` port, so the graph analyzer rejects it outright if
      its media input is ever fed per-element. Nothing can do that today — no node emits `media/image`
      as a sequence, and `artifact/image` cannot reach a `media/image` port because content-type
      families never cross — but the error would read like a bug in this node rather than in the graph.

---

## 11. Test Setup

```bash
# The sidecar, once. PYTHON=python3.13 matters: torch has no 3.14 wheels
cd sidecars/sam2 && PYTHON=python3.13 ./setup.sh
CUDA_VISIBLE_DEVICES=1 ./run.sh          # :9130; pin the card the rest of the box is not using

# 63 unit tests - no sidecar needed, Sam2Client is stubbed
./mvnw -o -pl cortex/nodes/sam2/core test

# The generated contract equals the annotated node, and the kind is advertised
./mvnw -o -pl integration-test test -Dtest=NodeSpecGoldenTest

# End to end against an in-process Loom + pooled Postgres
./setup-pool.sh
./mvnw -o -pl integration-test test -Dtest=Sam2NodeIntegrationTest

# Regenerate the docs fixture and both screenshots (needs the sidecar up, on a GPU)
mvn -o -pl integration-test test -Dtest=DocsFixtureGenerator \
    -Dloom.regenerateDocsFixtures=true -Dloom.docsFixtureKinds=sam2
cd loom-ui && node scripts/capture-node-config-screenshots.mjs sam2 \
           && node scripts/capture-node-screenshots.mjs sam2
```

| Test | What it guards against |
|---|---|
| `Sam2NodeTest` (26) | The mask space read from the posted image instead of the response; a mask that is not binary grayscale; the manifest missing a dimension pair or naming a file it did not write; an empty result reported as a failure; a truncated result reported as `SUCCESS`; a sidecar failure reported as `SUCCESS`; a failure poisoning the cache; a cache hit serving a deleted directory or short-circuiting a debug run; two option sets or two box sets sharing a cache key; a still reaching `/v1/track` |
| `Sam2VideoTest` (8) | Sampled indices leaking out in place of source frame numbers; a prompt not snapped to the nearest sampled frame; boxes not rescaled into the sampled space; `TRACK` with nothing wired producing no prompt; a capped frame walk not flagged; `AUTOMATIC`-on-video segmenting the wrong frame |
| `Sam2OptionsValidationTest` (17) | Every out-of-range option surfacing per item instead of at pipeline start; the 300 s timeout default being lost; `minMaskArea = 0` and a tight `maxMasks` with `multimask` wrongly rejected |
| `Sam2NodePersistenceTest` (5) | The ledger row missing or losing the checkpoint; no `FAILED` row when the sidecar throws or `TRACK` is asked for a still; a re-persist on a cache hit; an offline run failing instead of succeeding silently |
| `Sam2NodePipelineTest` (7) | Adapter integration, completion and tracking events, the `masks` port chaining independently of the `overlay`, the manifest reaching a downstream consumer, disabled + dry-run skip |
| `Sam2NodeIntegrationTest` | The ledger row not reaching Postgres, or losing its `producerVersion` |

---

## 12. Where do I find …?

| Need | Path |
|---|---|
| The node | [cortex/nodes/sam2/core/…/Sam2Node.java](../../../../cortex/nodes/sam2/core/src/main/java/io/metaloom/cortex/node/sam2/Sam2Node.java) |
| The options + `validate()` | `…/sam2/Sam2NodeOptions.java` |
| The sidecar client and the wire shapes | `…/sam2/Sam2Client.java` |
| Image helpers and the overlay composite | `…/sam2/Sam2Images.java` |
| Frame sampling and the coordinate helpers | `…/sam2/video/Sam2FrameSampler.java` · `SampledFrames.java` |
| The tests | `cortex/nodes/sam2/core/src/test/…` |
| The sidecar | [sidecars/sam2/](../../../../sidecars/sam2/) — `server.py`, `setup.sh`, `run.sh`, `README.md` |
| The docs fixture recipe | `integration-test/…/node/docs/SidecarRecipes.java` (`sam2()`) |
| The customer page | [website/content/english/docs/nodes/sam2/index.adoc](../../../../website/content/english/docs/nodes/sam2/index.adoc) |
| Why this node looks the way it does | §3 of this file — the decisions worth keeping, and §3.10 for the rejected alternatives |
| The `struct/masks` content type | [ContentTypeRegistry.java](../../../../loom-shared/node-model/src/main/java/io/metaloom/loom/nodes/spec/ContentTypeRegistry.java) — `STRUCT_MASKS` |
| The node system as a whole | [../NODES.md](../NODES.md) |
| The port/content-type model | [../../pipeline/NODE_DATA_TYPES.md](../../pipeline/NODE_DATA_TYPES.md) |
| Rules for building the next node | [../../../guidelines/NEW_NODE.md](../../../guidelines/NEW_NODE.md) |

---

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11_
