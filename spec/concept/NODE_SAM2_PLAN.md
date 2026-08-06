# SAM 2 Node — Plan and Outcome

🟢 **BUILT** end to end, including `sidecars/sam2` (`:9130`). Kind `sam2`, 43rd advertised node kind.

> This is the **decision record**: why the node looks the way it does and what was rejected. The
> reference spec for working on it — ports, options, the sidecar protocol, tests, progress — is
> [../features/nodes/sam2/NODE_SAM2.md](../features/nodes/sam2/NODE_SAM2.md).

## Why

Metaloom could say **where** a thing is and never **which pixels** are it. Every geometry the schema
holds is an axis-aligned rectangle — `detection.bbox_*` (whose V2.43 migration comment calls it "the
single geometry convention") and `tag_asset`'s area columns, and nothing else. `STRUCT_SEGMENTS`
sounds relevant and is not: it is labelled "Timeframes" and is time-coded.

Segmentation had been deferred once already —
[NODE_DEPTHMAP_PLAN.md](NODE_DEPTHMAP_PLAN.md) line 315: *"~~Segmentation masks / stereo /
multi-view~~ — out of scope"*. This is that deferral resolved.

## Shape

| Decision | What | Why |
|---|---|---|
| Scope | All three SAM 2 capabilities: `AUTOMATIC` (segment everything), `PROMPTED` (one mask per upstream box), `TRACK` (propagate through a clip) | They are different jobs, not quality levels; leaving any out would have meant a second node later |
| Mode selection | An explicit `Sam2Mode` **option**, not `ctx.isWired` | `NodeContext.create(media)` builds empty inputs, so `isWired` is false for every docs fixture and unit test — a wiring-derived mode would document a different node from the one that runs. And wiring cannot tell `AUTOMATIC`-on-video from `TRACK` |
| Persistence | **Ledger only.** Masks under `metaPath/sam2_bin/…`, one `asset_node_result` row, `result_ref == null` | `detection` has no column for polygonal geometry; putting an RLE or polygon in `meta` would be a write path with no read path, which is the defect [NEW_NODE.md](../guidelines/NEW_NODE.md) §1.4 exists to prevent. No migration, no schema risk |
| Runtime | FastAPI sidecar `sidecars/sam2` on **9130**, Java node a pure `java.net.http` client | 9100 tts, 9110 sentiment, 9120 depth — 9130 continues the 91xx analysis band. Same shape as `depthmap` |
| Content type | New `struct/masks` ("Segmentation Masks") | `struct/segments` is time-coded and already wired to time-range consumers |
| Video frames | Sampled in **Java** (`Sam2FrameSampler`, video4j) and POSTed as base64 JPEGs | The tree already owns this policy three times over; a server-side path would silently require the sidecar to be co-located; the video bytes are unbounded |

## Ports

```
image | video  (XOR media_alt)        →  masks       artifact/image  MANY
detections     detection/* MANY opt      segments    struct/masks    ONE
                                         overlay     artifact/image  ONE
                                         mask_count  scalar/integer  ONE
                                         flag        scalar/string   ONE
```

One MANY output only, deliberately: `ObjectDetectNode` documents that two MANY outputs of different
lengths zip incorrectly when both are wired downstream. Per-mask labels live inside the `segments`
elements instead.

## Traps this node had to navigate

- **`NodePreviews` downsamples only the first element of a MANY port**
  (`cortex/node-runtime/.../NodePreviews.java:70`). A segment-everything run would therefore
  illustrate itself with one cut-out. Mitigated by the `overlay` port — ONE `artifact/image`, tinted
  composite, auto-previewed — plus explicit `ctx.preview(OUT_MASKS, i, …)` per mask. This is why
  `emitOverlay` defaults to **true**.
- **Three coordinate spaces**: the source image, the `maxDim` image actually posted, and the mask.
  Upstream boxes are measured in the first, the sidecar answers in the second. The manifest names both
  pairs (`width`/`height` and `imageWidth`/`imageHeight`) for the reason `DepthmapNode.buildMeta`
  gives: without them the projection is a guess.
- **XYWH vs XYXY.** Upstream detectors emit `{"bbox":{"x","y","w","h"}}`; SAM 2 takes XYXY. Converted
  once, in `Sam2Box.fromXywh`.
- **TRACK annotation-frame mismatch.** The upstream detector samples at *its* chop rate, so a box
  stamped frame 137 need not be one of this node's samples. `SampledFrames.nearestIndex` snaps to the
  nearest sampled frame rather than dropping the box; forcing the two chop rates to agree would be a
  `nodeId:outputKey`-shaped coupling, which [NODES.md](../features/nodes/NODES.md) §6.4 forbids.
- **N artifacts, not one.** `manifest.json` is written **last** and its presence is the commit marker
  the skip cache stats — one call regardless of mask count, and absent from a directory a killed
  worker left half-written. The option digest (including the prompt boxes) is in the *directory name*,
  not only the cache key, so two `sam2` instances in one graph neither serve nor overwrite each other.
- **`ctx.failure(msg).abort()`, never `.next()`.** `TRACK` asked for a still fails rather than skips —
  the worker was given a job it cannot do. `Sam2NodeTest.testTrackOnAnImageFails` is the test that
  pins it; `depthmap` still uses `.next()` there and is wrong.

## Sidecar

`transformers`, **not** the PyPI `sam2` distribution — that is a third-party upload, and Meta's own
route builds a CUDA extension and pins hydra. `setup.sh` asserts
`Sam2Model / Sam2VideoModel / Sam2Processor / Sam2VideoProcessor` import rather than pinning a
`transformers` minor. One GPU lock plus `--workers 1`: `points_per_side=32` is 1024 forward passes and
the video predictor holds a per-request memory bank.

Default `facebook/sam2.1-hiera-small`. SAM 2 code and every 2.1 checkpoint are Apache-2.0 — unlike the
Depth Anything family, there is no non-commercial member to avoid.

## Known limitation

The `transformers` `"mask-generation"` pipeline does not expose SAM 2's own thresholds. `AUTOMATIC`
applies `predIouThresh` as a post-filter on the score the pipeline returns, and
**`stabilityScoreThresh` has no analogue in that path and is currently ignored**. The node's contract
is unaffected — masks are still filtered by `minMaskArea` and capped by `maxMasks` — and the option is
kept because it becomes live the moment that path grows one.

## Not done

- **Demo data.** Not seeded, following the explicit `imagegen`/`tts`/`depthmap`/`videogen` precedent:
  the demo container has no sidecar, and a demo pipeline that cannot run is worse than an absent one.
- ~~**Docs screenshots.**~~ Done: `SidecarRecipes.sam2()` ran `AUTOMATIC` against the real sidecar and
  the page now carries `config.png`, `debug.png` and `debug-detail.png`. It also surfaced a live
  limitation — `NodeResultStrip` caps the card at three rows, so this node's `masks` and `overlay`
  ports collapse into a `+n more` chip that cannot be clicked, and the debug card shows no picture of
  a segmentation. Recorded in [../features/nodes/sam2/NODE_SAM2.md](../features/nodes/sam2/NODE_SAM2.md) §10.
- **A queryable mask.** Masks are worker-local files. Making them searchable needs either a byte-ingest
  endpoint for produced media or a real geometry column — both are their own change.
