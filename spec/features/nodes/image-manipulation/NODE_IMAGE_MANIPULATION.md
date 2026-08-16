# Image Manipulation Node (`image-manipulation`) — EXIF Autorotate, Crop, Reframe, Resize

> **Status**: 🟢 **Built and shipping.** Kind `image-manipulation`, module
> [cortex/nodes/image-manipulation/](../../../../cortex/nodes/image-manipulation/), package
> `io.metaloom.cortex.node.imagemanip`. 118 unit tests + 3 integration tests; contract in the
> generated `node-descriptors.json`, kept honest by `NodeSpecGoldenTest`.
> **Scope**: the `image-manipulation` node — applying an ordered chain of geometric operations to an
> image and writing the result as a new artifact. Everything from the source file's bytes to the
> `imagemanip_bin` cache and the `asset_node_result` ledger row.
> **Audience**: AI coding agents and humans working on
> [cortex/nodes/image-manipulation/](../../../../cortex/nodes/image-manipulation/).

**Out of scope, and where it lives instead:**

| Not here | There |
|---|---|
| The node system, lifecycle, registration, caching layers | [../NODES.md](../NODES.md) |
| Port content types and cardinality across all nodes | [../NODE_DATA_TYPES.md](../NODE_DATA_TYPES.md) §4 |
| Rules for adding a node at all | [../../../guidelines/NEW_NODE.md](../../../guidelines/NEW_NODE.md) |
| Where the EXIF `Orientation` value is **recorded** (not applied) | [../metadata/METADATA_OVERVIEW.md](../metadata/METADATA_OVERVIEW.md) |
| Where the subject boxes come from | `facedetect`, [../NODES.md](../NODES.md) §3.1 |
| Keeping the produced bytes off the worker | `s3-sink`, [../NODES.md](../NODES.md) §2.1 |
| The **video** equivalent (not built) | [../../../tasks/METALOOM_NOTES.md](../../../tasks/METALOOM_NOTES.md) §7 below |

---

## 0. Executive Summary

| Question | Short answer |
|---|---|
| **What does it do?** | Autorotate by EXIF, crop (fixed or subject-aware), force a target aspect ratio (crop or pad), and bound the long edge — in one decode→transform→encode pass |
| **What does VVS mean?** | *Vertical Video Syndrome.* It is not a separate operation: it is `ASPECT` with `aspectMode=PAD, padFill=BLUR` (§3.4) |
| **Does it change the source?** | Never. It writes a new artifact and leaves the archive byte-identical |
| **Where do the bytes go?** | `metaPath/imagemanip_bin/<segment>/<sha512>-<digest>.<ext>` — ledger-only, wire `image` into `s3-sink` to keep them |
| **Where do subject boxes come from?** | The `detections` input port (`detection/*`, MANY), usually from `facedetect`. Never from the `detection` DB table (§4) |
| **Images only?** | Yes. Video is a deliberate non-goal (§7) |

```
image      : media/image   ──▶  image-manipulation  ──▶  image    : artifact/image
detections : detection/*  ┈┈▶  (optional, MANY)     ──▶  geometry : struct/json
                                                    ──▶  flag     : scalar/string
```

---

## 1. Why the node exists

The `metadata` node **extracts** EXIF `Orientation` and stores it on `asset_image_comp.orientation`,
but **nothing in the tree applies it**, and `ImageIO.read` ignores EXIF by design — so `facedetect`,
`dominant-color`, `thumbnail`, `vlm` and `captioning` all measure a phone photo sideways. There is
also no crop, aspect or resize helper on the `BufferedImage` path anywhere: the only crop code was
`FacedescriptionNode.cropFace`, inlined for ArcFace. This is the node that owns pixel geometry.

`loom/services/image` looks like it should own this and does not — it is a documented dead stub
([../../../cortex/SERVICE_IMAGE.md](../../../cortex/SERVICE_IMAGE.md)). Cortex nodes are where pixel
work lives.

---

## 2. The chain

`operations` is an **ordered** comma-separated list, default `AUTOROTATE,ASPECT,RESIZE`. Each
operation appears at most once; `validate()` rejects duplicates, unknown names, and any list where
`AUTOROTATE` is not first. All geometry is expressed **relative to the frame**, never in absolute
pixels — a value in pixels is right at exactly one resolution.

| Op | What it does |
|---|---|
| `AUTOROTATE` | Read EXIF `Orientation` (1–8) from the file and apply the inverse transform so pixels are upright |
| `CROP` | A fixed rectangle in relative 0–1 coordinates (`cropX/Y/Width/Height`), clamped to the frame |
| `SUBJECT_CROP` | Union of the input detection boxes, padded, expanded to `targetAspect`, clamped |
| `ASPECT` | Force `targetAspect`. `aspectMode = CROP` cuts the long axis, `PAD` grows the short one; `PAD` fill is `COLOR` or `BLUR` |
| `RESIZE` | Bound the long edge by `maxLongEdge`, aspect preserved; `allowUpscale = false` by default |

All operations run in a single pass in [`ImageManipulationNode.Frame`](../../../../cortex/nodes/image-manipulation/core/src/main/java/io/metaloom/cortex/node/imagemanip/ImageManipulationNode.java),
which carries the pixels **and** the subject boxes together — see §3.1.

---

## 3. The five decisions worth keeping

### 3.1 🔴 The detection boxes are transformed with the pixels

The sharpest correctness property, and the reason `AUTOROTATE` and `SUBJECT_CROP` are one node and
not two. `FacedetectNode` decodes with `ImageIO.read`, which ignores EXIF, so its boxes are in
**stored** pixel space — exactly the space autorotation redefines. A `SUBJECT_CROP` after an
`AUTOROTATE` that turned the frame a quarter would otherwise crop a region that is plausible and
wrong, with no error anywhere.

So `Frame` moves the boxes through the same transform as the pixels — the quarter-turn case in
`ManipulationGeometry.transform`, and then through every later crop (rebase onto the cut window),
pad (shift onto the enlarged canvas) and resize (scale). All eight EXIF orientations for pixels and
boxes come from **one** table so they can never drift apart. Pinned by
`ImageManipulationNodeTest.testDetectionBoxesAreRotatedWithThePixels` and
`ManipulationGeometryTest.testEveryOrientationKeepsABoxInsideTheTransformedFrame`.

### 3.2 Geometry is pure arithmetic

`ManipulationGeometry` touches no `ImageIO`, no `Graphics2D`, no file — `Rect` in, `Rect` out.
Five operations compose and the composition is where the bugs are, so every one is pinned by a fast
pure test (`ManipulationGeometryTest`, 33 cases) rather than by rendering and comparing pixels. The
`WatermarkGeometry` precedent.

### 3.3 EXIF is read from the file, not from Loom

`AssetResponse` could supply `ImageInfo.orientation`, but that requires the `metadata` node to have
run *and* the worker to be online — the node's behaviour would silently depend on pipeline ordering
and connectivity. `ExifOrientation` reads the tag with **metadata-extractor** (the dependency
`MetadataExtractor` already uses, and for the same reason Tika is avoided there: its
`ImageMetadataExtractor` flattens EXIF/IPTC/XMP). Offline-safe, order-independent. A missing,
unreadable or corrupt tag is `Orientation.NORMAL`, never a failure — PNG has no EXIF and is an
ordinary input.

### 3.4 VVS is a fill strategy, not an operation

The **Vertical Video Syndrome** fix is `ASPECT` with `aspectMode=PAD, padFill=BLUR`: a portrait frame
becomes a landscape one whose margins are a blurred, scaled copy of the picture instead of black bars.
Modelling it as a fill strategy of `ASPECT` keeps the operation set orthogonal — the same machinery
letterboxes a landscape image into a portrait target. The backdrop is scaled to **cover** the canvas
and overscanned by `blurZoom` (default 1.15) so its own edges never appear inside the frame; the
blur is computed on a downscaled buffer and enlarged, which is visually identical and milliseconds
instead of seconds.

> **The VVS preset** — the recipe an author reaches for by that name:
>
> ```
> operations   = AUTOROTATE,ASPECT
> targetAspect = 16:9
> aspectMode   = PAD
> padFill      = BLUR
> ```

### 3.5 The digest covers the boxes, and it is in the file name

The cache key and the artifact file name both carry `sha256(options | surviving detections)[0..12]`.
Two `image-manipulation` nodes in one graph — a 16:9 hero crop and a 1:1 thumbnail — key on the same
media SHA-512 and would otherwise serve each other's output. And the detections are a **second input
that changes the pixels**: without them in the digest, a re-run against better face boxes is served
the first run's crop from the local cache. Only the boxes that survive filtering are digested — a
dropped one cannot change the result and must not invalidate the cache. The cache-hit path also
`Files.exists`-checks the artifact, so one deleted between runs is rebuilt rather than handed on as a
dead path. Pinned by `ImageManipulationNodeTest.testBetterDetectionsAreNotServedTheFirstRunsCrop`.

---

## 4. Conventions and Gotchas

🔴 **Consume the detection *port*, never the `detection` table.** The port elements carry
`"coordinates": "ABSOLUTE_PIXELS"`, but `V2.43__rework_detection_embedding.sql` documents
`detection.bbox_x` as *"normalized 0-1. This is the single geometry convention"* — and nothing
validates either claim. Reading boxes back over REST would crop from the wrong coordinate space by a
factor of the image width. `SubjectBoxes` drops any element whose marker is not `ABSOLUTE_PIXELS`.

🔴 **`ctx.failure(msg).abort()`, never `.next()`.** `NodeContextImpl.next()` ignores a recorded
failure cause and builds the result as `SUCCESS`. The decode is **inside** the `compute` try for the
same reason: an undecodable file must leave a FAILED ledger row from this node, not escape to
`AbstractMediaNode.process()`, which reports the failure but records nothing.

🔴 **JPEG cannot hold alpha.** `ImageIO.write(argbRaster, "jpeg", …)` does not reject the alpha
channel — it writes four components into a three-component format and the result reads back inverted
or magenta. `ManipulationImages.write` flattens onto `backgroundColor` first whenever the format
cannot carry alpha.

⚠️ **Decode through `MediaArtifacts.decodedImage(ctx)`, never `ImageIO.read`** — it caches the decode
per segment across nodes, and its contract is that a node wanting something derived publishes it
under its own key rather than writing into the shared raster. Every operation here allocates its own
destination; `crop` copies rather than returning a `getSubimage` view onto the shared image.

⚠️ **Name clash: `io.metaloom.utils.fs.FileUtils.autoRotate` is unrelated** — it rotates *filenames*
for collision-free moves (used by `dedup`) and has nothing to do with EXIF.

⚠️ **`imageWidth`/`imageHeight` are absent from `facedetect`'s video-path elements.** This node is
image-only so it will not normally see them, but do not write code that trusts those two fields.

⚠️ **`SUBJECT_CROP` frames faces only.** `facedetect` is the only detector in the tree; `yolo4j` is
not wired in. The port is `detection/*`, so a future person/object detector needs no change here.
Its `subjectFallback` (`CENTRE` | `SKIP` | `FAIL`) decides what happens on an image with no subject —
`CENTRE` by default, because a landscape with no faces is the ordinary case, not an error.

---

## 5. Options

All **per pipeline instance**. The node adds **no** worker-level configuration and **no** environment
variables — it reads `metaPath` (`CORTEX_META_PATH`) from `CortexOptions` and nothing else, and
`CortexEnvOptions` needs no edit.

| Option | Type | Default | Notes |
|---|---|---|---|
| `operations` | `STRING` | `AUTOROTATE,ASPECT,RESIZE` | Ordered, comma-separated. `AUTOROTATE` first if present |
| `cropX` / `cropY` | `NUMBER` | `0.0` | Relative top-left of the `CROP` window |
| `cropWidth` / `cropHeight` | `NUMBER` | `1.0` | Relative size; the defaults are the identity crop |
| `subjectTypes` | `STRING` | `face` | Comma-separated detection `type` values to accept; `*` accepts all |
| `minConfidence` | `NUMBER` | `0.5` | Detections below this are ignored |
| `subjectPadding` | `NUMBER` | `0.35` | Grow the subject *union* by this fraction of its own size |
| `subjectFallback` | `ENUM` | `CENTRE` | `CENTRE` \| `SKIP` \| `FAIL` when no detection survives |
| `targetAspect` | `STRING` | `""` | `W:H`, e.g. `16:9`. Required when `operations` include `ASPECT` |
| `aspectMode` | `ENUM` | `CROP` | `CROP` (lose pixels) \| `PAD` (lose none) |
| `padFill` | `ENUM` | `BLUR` | `COLOR` \| `BLUR`. `BLUR` is the VVS fix (§3.4) |
| `padColor` | `STRING` | `#000000` | `padFill = COLOR` only |
| `blurRadius` | `INTEGER` | `24` | Box-blur radius of the backdrop |
| `blurZoom` | `NUMBER` | `1.15` | Backdrop overscan so its edges stay out of frame |
| `maxLongEdge` | `INTEGER` | `0` | `RESIZE` bound; `0` disables |
| `allowUpscale` | `BOOLEAN` | `false` | Enlarge a frame smaller than the bound |
| `outputFormat` | `ENUM` | `JPEG` | `PNG` (lossless, keeps alpha) \| `JPEG` (small, photos) |
| `jpegQuality` | `NUMBER` | `0.90` | `outputFormat = JPEG` only |
| `backgroundColor` | `STRING` | `#FFFFFF` | Alpha-flatten target when encoding a format with no alpha |
| `enabled`, `processIncomplete`, `retryFailed`, `timeoutMs` | | `true`/`false`/`false`/`60000` | Standard, from `AbstractNodeOptions` |

---

## 6. Key Classes Reference

| Class | Package (`io.metaloom.cortex.node.imagemanip`) | Purpose |
|---|---|---|
| `ImageManipulationNode` | — | Kind `image-manipulation`; ports, chain execution (`Frame`), digest-keyed cache, artifact path, ledger |
| `ImageManipulationNodeOptions` | — | `KEY = "image-manipulation"`, `operationChain()`, `validate()` |
| `ImageManipulationNodeModule` | — | Dagger `@Binds @IntoSet` + `@Binds @IntoMap @StringKey("image-manipulation")` |
| `ManipulationGeometry` | — | **Pure** `Rect` arithmetic: orientation (pixels & boxes), crop, union, aspect, resize, parsing |
| `Orientation` | — | The eight EXIF orientations, including which four **mirror** |
| `ExifOrientation` | — | metadata-extractor read of `TAG_ORIENTATION`; never throws |
| `SubjectBoxes` | — | Parse/filter the detection elements; the caching digest material |
| `ManipulationImages` | — | `Graphics2D` ops, box blur, alpha flatten, atomic PNG/JPEG write |
| `Op` / `AspectMode` / `PadFill` / `SubjectFallback` / `OutputFormat` | — | The option enums |
| `MediaArtifacts` | `io.metaloom.cortex.common.artifact` | **reused** — the shared per-segment decode cache |

---

## 7. Progress Assessment

### Done

- [x] Module, node, options, Dagger module, kind binding in `NodeCollectionModule`
- [x] Pure geometry for all 8 orientations, pixels **and** boxes; crop, subject crop, aspect, resize
- [x] EXIF read (metadata-extractor, never throws), blur-pad VVS fix, alpha flatten, atomic write
- [x] Digest-keyed cache covering options **and** surviving detections
- [x] Descriptor in the generated resource; kind advertised; 118 unit + 3 integration tests
- [x] All registration touch-points (`NodeSpecCatalog`, both poms, `NodeCollectionModule`,
      `NodeDescriptorServiceLoaderTest` 38→39 kinds)

### Follow-ups this node creates

- [ ] 🔴 **Durable derived bytes.** `POST /api/v1/attachments` and
      `LoomClient.uploadAttachment(...)` already exist; no cortex node calls them. The blocker is
      narrow: `io.metaloom.loom.api.attachment.AttachmentType` has only `ASSET_THUMBNAIL` /
      `EMBEDDING_ATTACHMENT`, while the Postgres `attachment_type` enum already carries
      `CONTACT_SHEET`, `POSTER_FRAME`, `WAVEFORM`, `PROXY`, `EXTRACTED_AUDIO`. A `DERIVED_IMAGE`
      value needs a Flyway migration + jOOQ regen + the Java enum, then this node becomes the first
      caller. Until then the artifact is durable only via `s3-sink` sharing a worker. The
      *"no byte-ingest endpoint yet"* comments on `watermark`/`thumbnail`/`depthmap`/`imagegen` are
      stale and should be corrected in the same change.
- [ ] **Consolidate the image helpers.** `VlmImages`, `DepthImages` and `WatermarkImages` are three
      near-duplicate `read`/`downscale`/`toOpaque` helpers, and `VlmImages.rotate` handles only 4 of
      the 8 EXIF orientations. Promote `AtomicFiles` + the writer into `cortex/common`;
      `ManipulationGeometry` makes the missing four cases available to all of them.
- [ ] **A `focalPoint` output.** `SUBJECT_CROP` computes a subject centroid; emitting it would let
      `loom-ui` crop responsively without re-running the node, answering the *"Focalpoint node?"*
      backlog line.

### Deliberately not built

- [ ] **No video path.** Video needs an ffmpeg re-encode and rotation/SAR handling; a
      `video-manipulation` sibling should reuse `ManipulationGeometry` and `watermark`'s
      `FfmpegRunner`. Tracked in [../../../tasks/METALOOM_NOTES.md](../../../tasks/METALOOM_NOTES.md).
- [ ] **No person/object detection** (`yolo4j` is not wired in). `SUBJECT_CROP` consumes
      `detection/*`, so a future detector needs no change here.
- [ ] **No per-face fan-out.** `IN_DETECTIONS` is `MANY`, so the node gathers the boxes and produces
      **one** composed crop per image. `ONE` would instead emit one crop per face — a different
      feature (per-person thumbnails), needing the face index in the artifact name.
- [ ] **No WebP/AVIF** (not in the JDK's ImageIO) and **no colour/exposure/sharpening** — geometry
      only.

---

## 8. Test Setup

```bash
# 118 unit tests: geometry, EXIF, pixels, subject boxes, options, node, persistence, pipeline
./mvnw -o -pl cortex/nodes/image-manipulation/core test

# Descriptor + content-type model, SPI discovery (asserts 39 advertised kinds)
./mvnw -o -pl loom-shared/node-model test

# The generated contract equals the annotated node, and the kind is advertised
./mvnw -o -pl integration-test test -Dtest=NodeSpecGoldenTest

# End to end against an in-process Loom + pooled Postgres (needs ./setup-pool.sh first)
./setup-pool.sh
./mvnw -o -pl integration-test test -Dtest=ImageManipulationNodeIntegrationTest
```

| Test | What it guards against |
|---|---|
| `ManipulationGeometryTest` | Every composition defect: the four **mirrored** orientations treated as rotations; axes not swapped; boxes not carried with the pixels; a crop leaving the frame; `expandToAspect` cutting subjects; upscaling when disallowed |
| `ExifOrientationTest` | A missing/unreadable/corrupt tag failing instead of no-op'ing; the spliced EXIF-JPEG fixture staying decodable |
| `ManipulationImagesTest` | JPEG-with-alpha producing inverted colour; a mirror mistaken for a half-turn; the blur backdrop leaving a transparent sliver; a non-atomic write; a `crop` view corrupting the shared source raster |
| `SubjectBoxesTest` | A box in the wrong coordinate space silently misread; one malformed element failing the item; the digest not tracking the boxes |
| `ImageManipulationOptionsValidationTest` | A mistyped op, a duplicate, `AUTOROTATE` not first, a malformed aspect, a degenerate crop — surfacing per item instead of at pipeline start |
| `ImageManipulationNodeTest` | The source file modified; the chain out of order; a re-run not served from cache; a cache hit serving a deleted file; **better detections served the first run's crop**; **a box not rotated with the pixels** |
| `ImageManipulationNodePersistenceTest` | The ledger row missing, carrying a `resultRef`, or a re-persist on a cache hit; a FAILED row on the failure path |
| `ImageManipulationNodePipelineTest` | Adapter integration, events, artifact chaining into a downstream sink, disabled + dry-run skip |
| `ImageManipulationNodeIntegrationTest` | The ledger row not reaching Postgres, or losing the `producerVersion`; the VVS blur-pad end to end; subject crop following upstream detections |

---

## 9. Where do I find …?

| Need | Path |
|---|---|
| The node | [cortex/nodes/image-manipulation/core/…/ImageManipulationNode.java](../../../../cortex/nodes/image-manipulation/core/src/main/java/io/metaloom/cortex/node/imagemanip/ImageManipulationNode.java) |
| The pure geometry | `…/imagemanip/ManipulationGeometry.java` |
| The pixel operations | `…/imagemanip/ManipulationImages.java` |
| The EXIF read | `…/imagemanip/ExifOrientation.java` · `Orientation.java` |
| The options + `validate()` | `…/imagemanip/ImageManipulationNodeOptions.java` |
| The tests | `cortex/nodes/image-manipulation/core/src/test/…` |
| The node system as a whole | [../NODES.md](../NODES.md) |
| The port/content-type model | [../NODE_DATA_TYPES.md](../NODE_DATA_TYPES.md) |
| Rules for building the next node | [../../../guidelines/NEW_NODE.md](../../../guidelines/NEW_NODE.md) |

---

_Git HEAD revision: `d9bbc2dc`_
_Last updated: 2026-08-03 (new node built: EXIF autorotate, crop, subject crop, aspect/VVS, resize; images only, ordered op chain, detections via input port)_
