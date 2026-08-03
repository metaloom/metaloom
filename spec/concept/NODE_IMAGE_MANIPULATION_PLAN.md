# Image Manipulation Node — Technical Specification

> ## 🔵 Status: PLANNED — nothing is built
>
> Kind `image-manipulation`, module `cortex/nodes/image-manipulation/core`, package
> `io.metaloom.cortex.node.imagemanip`. **No code exists yet**: no module, no descriptor, no
> binding, no tests. Every path in this file is a path to be *created* unless it is named as an
> existing class to reuse.
>
> This file is a plan, not a design record. Once the node ships, reduce it to a design record the
> way [NODE_WATERMARK_PLAN.md](NODE_WATERMARK_PLAN.md) was reduced, and move the §1 table from
> "will live" to "lives".

The `image-manipulation` node applies an ordered chain of geometric operations to an image and
writes the result as a new artifact: EXIF autorotation, a fixed crop, a subject-aware crop driven by
upstream detections, aspect-ratio normalisation (including the *vertical video syndrome* blurred-pad
fix) and a bounding resize — **in a single decode → transform → encode pass**. The source file is
never modified.

```
image      : media/image   ──▶  image-manipulation  ──▶  image    : artifact/image
detections : detection/*  ┈┈▶  (optional, MANY)     ──▶  geometry : struct/json
                                                    ──▶  flag     : scalar/string
```

**Images only.** There is deliberately no video path — see §9 "Deliberately not built". The backlog
item this file grew from covered video as well; that half stays in
[../tasks/METALOOM_NOTES.md](../tasks/METALOOM_NOTES.md).

Read alongside [../features/nodes/NODES.md](../features/nodes/NODES.md) (the node system, the
capability matrix, the shared gotchas), [../features/pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md)
(the port/content-type model), [../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) (the definition
of done for a new node) and [NODE_WATERMARK_PLAN.md](NODE_WATERMARK_PLAN.md) — the closest existing
sibling and the structural template for everything below.

---

## 0. Why this node

The gap is measurable, not speculative:

- The `metadata` node **extracts** EXIF `Orientation` (`MetadataExtractor`, `TAG_ORIENTATION`) and
  stores it on `asset_image_comp.orientation`. **Nothing in the repository ever applies it.** There
  is no `applyExifOrientation` anywhere, and `ImageIO.read` ignores EXIF by design — so `facedetect`,
  `dominant-color`, `thumbnail`, `vlm` and `captioning` all see phone photos sideways.
- There is no crop, aspect or resize helper on the `BufferedImage` path at all. The only crop code in
  the tree is `FacedescriptionNode.cropFace`, inlined for ArcFace alignment.
- `loom/services/image` looks like it should own this and does not: `ImageAsset.altText()` returns
  `null`, it has zero consumers repo-wide, and [../cortex/SERVICE_IMAGE.md](../cortex/SERVICE_IMAGE.md)
  recommends deleting the module. **Do not implement this there.** Cortex nodes are where pixel work
  lives.

---

## 1. To be implemented

| Item | Where it will live |
|---|---|
| Node, lifecycle, cache key, artifact path, ledger | `cortex/nodes/image-manipulation/core/…/ImageManipulationNode.java` |
| Ordered operation chain + the `Op` enum | `…/imagemanip/Op.java`, applied in `ImageManipulationNode.compute` |
| **Pure** geometry: orientation matrices, crop, subject union, aspect expansion, resize bounds | `…/imagemanip/ManipulationGeometry.java` — `record Rect(int x, int y, int w, int h)` |
| EXIF orientation read (metadata-extractor, not Tika) | `…/imagemanip/ExifOrientation.java` |
| Pixel operations (`Graphics2D`), blur fill, alpha flatten, PNG/JPEG encode | `…/imagemanip/ManipulationImages.java` |
| Detection-element parsing (`bbox`, `confidence`, `type`) | `…/imagemanip/SubjectBoxes.java` |
| Options + `validate()` | `…/imagemanip/ImageManipulationNodeOptions.java` (`KEY = "image-manipulation"`) |
| Dagger bindings | `…/imagemanip/ImageManipulationNodeModule.java` |
| Maven modules | `cortex/nodes/image-manipulation/pom.xml` (`cortex-image-manipulation`, packaging `pom`) + `…/core/pom.xml` (`cortex-image-manipulation-node`, jar, parent `cortex-nodes` via `../../pom.xml`) |
| Descriptor: 3 ports, ~20 form parameters, `TRANSFORM`, icon `crop` | `loom-shared/node-model/…/spec/ImageManipulationDescriptorProvider.java` |
| Unit tests (6 classes + fixtures + AssertJ helpers) | `cortex/nodes/image-manipulation/core/src/test/…` |
| Integration test | `integration-test/…/integration/node/ImageManipulationNodeIntegrationTest.java` |
| Customer-facing docs | `website/content/english/docs/nodes/image-manipulation/index.adoc` |

**Persists**: `asset_node_result` ledger only. The bytes stay in
`metaPath/imagemanip_bin/<segment>/<sha512>-<digest>.<ext>` — see §2.5.

**New content types needed**: none. `MEDIA_IMAGE`, `DETECTION_ANY`, `ARTIFACT_IMAGE`, `STRUCT_JSON`
and `SCALAR_STRING` all already exist in `ContentTypeRegistry`.

---

## 2. The five decisions worth arguing

### 2.1 🔴 The detection boxes must be rotated with the pixels

The sharpest correctness trap in the node, and the reason `AUTOROTATE` and `SUBJECT_CROP` cannot be
two independent nodes.

`FacedetectNode` decodes with `ImageIO.read`, which **ignores EXIF**. Its boxes are therefore in
*raw stored* pixel space. `AUTOROTATE` changes exactly that space. A `SUBJECT_CROP` running after an
`AUTOROTATE` that rotated 90° crops a region that is plausible, wrong, and produces no error —
faces land in the corner or outside the frame entirely.

So the orientation transform is applied to the boxes in the same pass as the pixels:

```
orientation 6 (rotate 90° CW), source W×H  →  output H×W
  box(x, y, w, h)  →  box(H - y - h, x, h, w)
```

`ManipulationGeometry` owns all eight cases for both pixels and boxes, from one table, so the two can
never drift apart. Every box the node consumes goes through it before any other op sees it — even
when `AUTOROTATE` is not in `operations`, in which case the transform is the identity.

### 2.2 Geometry is pure arithmetic in its own class

`ManipulationGeometry` touches no file, no `ImageIO`, no `Graphics2D`. It is `Rect`-in, `Rect`-out:
orientation transforms, relative→absolute crop, subject union, aspect expansion, resize bounds.

This is the `WatermarkGeometry` precedent, and it is why every watermark placement defect is pinned
by a fast pure test rather than by rendering and comparing pixels. Here it matters more: five ops
compose, and the composition is where the bugs are.

### 2.3 EXIF is read from the file, not from Loom

`AssetResponse` could supply `ImageInfo.orientation`, and the `metadata` node already writes it. It
is still the wrong source: it requires the `metadata` node to have run **and** the worker to be
online, so the node's behaviour would silently depend on pipeline ordering and connectivity.

Read the file with **metadata-extractor**, the same dependency `MetadataExtractor` uses — and for
the same reason it gives: Tika's `ImageMetadataExtractor` flattens EXIF, IPTC and XMP into one
namespace and applies its own precedence while doing so. Offline-safe, order-independent, one
dependency the tree already carries.

### 2.4 The cache key *and* the artifact filename carry a detections digest

`watermark` puts its options hash in the filename, not just the cache key, so two differently
configured instances in one graph cannot serve each other's output. This node needs the same, plus
one more term: the **detections are a second input that changes the pixels**. Without them in the
digest, a re-run against better face boxes serves the stale crop from the first run.

```
digest    = sha256(optionsMaterial + "|" + detectionsMaterial)[0..12]
cacheKey  = media.absolutePath() + "|" + digest
artifact  = metaPath/imagemanip_bin/<segment>/<sha512>-<digest>.<ext>
```

`detectionsMaterial` is the canonical serialisation of the boxes that survived filtering, in
`ctx.inputs(...)` order, and is empty when `SUBJECT_CROP` is not in `operations` — an unused input
must not invalidate the cache. On a hit, `Files.exists` the cached path before re-emitting it; an
artifact deleted between runs would otherwise be handed downstream as a path that no longer resolves.

### 2.5 Ledger-only for v1 — and the byte-ingest comment in the siblings is stale

Follow `watermark` / `thumbnail` / `depthmap` / `imagegen`: write to
`metaPath/imagemanip_bin/…` via `HashUtils.segmentPath`, atomic `.part` write, then
`recordNodeResult(asset, ctx, SUCCESS, null, producerVersion(), null)` — **no `result_ref`**.
Declaring the output as `ARTIFACT_IMAGE` makes the node `s3-sink`-compatible for free
(`ArtifactSelector` resolves relative paths against `metaPath`).

**Correct the record while doing so.** Every one of those four nodes carries a comment saying *"there
is no byte-ingest endpoint for produced media yet"*. That is no longer true:

- `POST /api/v1/attachments` exists — `AttachmentEndpoint`, multipart with one part `file` and form
  fields `assetUuid`, `embeddingUuid`, `type`, `poolUuid`. Its own OpenAPI description already lists
  `CONTACT_SHEET, POSTER_FRAME, WAVEFORM, PROXY, EXTRACTED_AUDIO` as types.
- `LoomClient.uploadAttachment(File file, String mimeType, UUID assetUuid, String type)` is
  implemented in `LoomHttpClientImpl`. **No cortex node calls it.**

The real blocker is narrower than the comments claim: `io.metaloom.loom.api.attachment.AttachmentType`
has only `ASSET_THUMBNAIL` and `EMBEDDING_ATTACHMENT`, while the Postgres `attachment_type` enum
(`V2.44__attachment_provenance.sql`) already carries the five above. A `DERIVED_IMAGE` /
`RENDITION` value needs a Flyway migration, a jOOQ regen and the Java enum. That is the concrete open
item (§9) — not "no endpoint exists".

---

## 3. Operations

`operations` is an **ordered** comma-separated list. Default `AUTOROTATE,ASPECT,RESIZE`. Each op
appears at most once; `validate()` rejects duplicates, unknown names, and any list where
`AUTOROTATE` is present but not first (§4).

All geometry is expressed **relative to the frame**, never in absolute pixels, for the reason
`NODE_WATERMARK_PLAN.md` §2.1 gives: a value in absolute pixels is correct at exactly one resolution.

### 3.1 `AUTOROTATE`

Read EXIF `Orientation` (1–8) and apply the inverse transform so the pixels are upright. Orientations
2, 4, 5 and 7 are **mirrored**, not merely rotated — a rotation-only implementation silently produces
a flipped image for those.

```
1 → identity          2 → flip-H
3 → rotate 180        4 → flip-V
5 → transpose         6 → rotate 90° CW
7 → transverse        8 → rotate 270° CW

axes swap for 5, 6, 7, 8  →  output dimensions are H×W
```

No EXIF, an unreadable tag, or `orientation == 1` is a no-op, not a failure. The boxes go through the
same transform (§2.1).

### 3.2 `CROP`

A fixed rectangle in relative 0–1 coordinates, clamped to the frame:

```
x = round(W * cropX)          w = round(W * cropWidth)
y = round(H * cropY)          h = round(H * cropHeight)
rect = clamp(Rect(x, y, w, h), W, H)      // never zero-area, never outside
```

Defaults `0,0,1,1` — the identity. A degenerate rectangle (zero width or height after clamping) is a
configuration error caught in `validate()`, not a per-item failure.

### 3.3 `SUBJECT_CROP`

Frame the subjects instead of the geometric centre. Consumes `IN_DETECTIONS`.

```
boxes  = detections filtered by subjectTypes and minConfidence, orientation-transformed (§2.1)
union  = bounding box of all boxes
padded = union grown by subjectPadding on every side (fraction of the union's own size)
rect   = clamp(expandToAspect(padded, targetAspect), W, H)
```

The union is grown, not each box — a group photo must stay one crop. `expandToAspect` grows the
short axis and only shrinks the long one if growth would leave the frame, so subjects are never cut
to satisfy the aspect.

**With no usable detections**, `subjectFallback` decides: `CENTRE` (default — behave as `ASPECT`),
`SKIP` (`ctx.skipped(...)`, outputs survive), or `FAIL`. `CENTRE` is the default because an image
with no faces is the common case, not an error.

⚠️ This covers **"crop to focalpoint" for faces only.** `facedetect` is the only detector in the
tree; `yolo4j` is not wired into this repository at all (grep-verified: zero hits for `yolo` in java
or pom files). `detection/object` and `detection/region` exist as content types and the `detection`
table's `type` column is a free string, so a person detector would slot in with no schema change —
but it does not exist. See §9 and the adjacent *"Focalpoint node?"* line in
[../tasks/METALOOM_NOTES.md](../tasks/METALOOM_NOTES.md).

### 3.4 `ASPECT` — and VVS

Force `targetAspect` (`"16:9"`, `"1:1"`, `"4:5"`; empty means keep the current ratio).

- `aspectMode = CROP` — centre-crop the long axis to the target. Loses pixels.
- `aspectMode = PAD` — letterbox/pillarbox to the target. Loses nothing.
  - `padFill = COLOR` — flat `padColor`, the classic black bars.
  - `padFill = BLUR` — the source scaled to **cover** the target, box-blurred by `blurRadius` and
    zoomed by `blurZoom`, drawn as the backdrop; the unmodified source is composited centred on top.

> ### VVS — the vertical video syndrome preset
>
> ```
> operations   = AUTOROTATE,ASPECT
> targetAspect = 16:9
> aspectMode   = PAD
> padFill      = BLUR
> ```
>
> A portrait phone photo becomes a landscape frame whose margins are a blurred enlargement of the
> image itself instead of black bars. This is the whole fix — it is **not** a separate operation.
> Modelling it as a fill strategy of `ASPECT` keeps the op set orthogonal: the same machinery
> letterboxes a landscape image into a portrait target without a second code path.
>
> `blurZoom` defaults to `1.15` so the backdrop's own edges never appear inside the frame.

### 3.5 `RESIZE`

Bound the result. Aspect always preserved.

```
scale = maxLongEdge > 0 ? min(1.0, maxLongEdge / max(W, H)) : 1.0
if (allowUpscale) scale = maxLongEdge / max(W, H)
```

`maxLongEdge = 0` disables it. `allowUpscale` defaults to `false`: enlarging invents detail and
inflates the artifact for no gain. Resampling is bilinear with `VALUE_RENDER_QUALITY`, matching
`WatermarkImages.composite`.

### 3.6 Why the order is the author's, and why it is checked

`operations` is ordered because the useful orders genuinely differ — cropping before resizing keeps
detail; a `CROP` that pre-frames a region before `SUBJECT_CROP` searches it is a real pipeline. Only
one ordering constraint is a hard error rather than a preference: **`AUTOROTATE` must come first**,
because every subsequent op reasons about a coordinate space that autorotation redefines (§2.1).

---

## 4. Conventions and Gotchas

🔴 **`ctx.failure(msg).abort()`, never `.next()`.** `NodeContextImpl.next()` ignores a recorded
failure cause and builds the result as `SUCCESS`. Here that reports an unprocessed image as done,
with no artifact, to a downstream sink. See [../features/nodes/NODES.md](../features/nodes/NODES.md).

🔴 **Consume the detection *port*, never the `detection` table.** The port elements carry
`"coordinates": "ABSOLUTE_PIXELS"`, but `V2.43__rework_detection_embedding.sql` documents
`detection.bbox_x` as *"normalized 0-1. This is the single geometry convention"* — and nothing
validates either claim. Reading boxes back over REST would crop from the wrong coordinate space by a
factor of the image width. The port is unambiguous; use it.

🔴 **`AUTOROTATE` must be first in `operations`** — enforced in `validate()`, not documented and
hoped for. §2.1 is the reason.

⚠️ **`imageWidth` / `imageHeight` are absent from `facedetect`'s video-path elements** (`VideoFile`
exposes no frame size). This node is image-only, so it will not normally see them — but do not write
code that trusts those two fields to be present.

⚠️ **JPEG cannot hold alpha.** Handing a `TYPE_INT_ARGB` raster to `ImageIO.write(…, "jpeg", …)`
produces inverted or pink output on several JDKs rather than an error. Flatten onto `backgroundColor`
before encoding whenever `outputFormat = JPEG`. `PAD` with `padFill = COLOR` and a transparent
`padColor` is the path that will actually hit this.

⚠️ **Decode through `MediaArtifacts.decodedImage(ctx)`, not `ImageIO.read`.** It caches the decode
per segment across nodes. Its javadoc is binding: *a node wanting something derived — resized,
colour-converted, cropped to a region — must publish that under its own key rather than mutating this
one.* Never write into the returned raster.

⚠️ **Name clash: `io.metaloom.utils.fs.FileUtils.autoRotate` is unrelated.** It rotates *filenames*
for collision-free moves (used by `dedup`) and has nothing to do with EXIF. Do not call it, do not
name anything after it.

⚠️ **A cache hit is `SUCCESS` with `ResultOrigin.LOCAL`, not `SKIPPED`** — re-emit the cached outputs
and return `ctx.origin(LOCAL).next()`.

⚠️ **`.part` naming:** reuse `AtomicFiles.partFor` / `AtomicFiles.move`. The extension-last rule
(`img.part.png`, not `img.png.part`) exists because ffmpeg picks its muxer from the name; ImageIO
does not care, but keeping one rule across the tree is cheaper than two.

---

## 5. Key Classes Reference

| Class | Package / module | Purpose |
|---|---|---|
| `ImageManipulationNode` | `io.metaloom.cortex.node.imagemanip` (`cortex/nodes/image-manipulation/core`) | **new** — kind `image-manipulation`; ports, chain execution, cache key, artifact path, ledger |
| `ImageManipulationNodeOptions` | same | **new** — `KEY = "image-manipulation"`, `validate()`, extends `AbstractNodeOptions` |
| `ImageManipulationNodeModule` | same | **new** — `@Binds @IntoSet` + `@Binds @IntoMap @StringKey("image-manipulation")` |
| `ManipulationGeometry` | same | **new** — pure `Rect` arithmetic; the eight orientation cases for pixels *and* boxes |
| `ManipulationImages` | same | **new** — `Graphics2D` ops, blur fill, alpha flatten, PNG/JPEG encode |
| `ExifOrientation` | same | **new** — metadata-extractor read of `TAG_ORIENTATION` |
| `SubjectBoxes` | same | **new** — parse and filter the detection elements |
| `Op` | same | **new** — `AUTOROTATE, CROP, SUBJECT_CROP, ASPECT, RESIZE` |
| `ImageManipulationDescriptorProvider` | `io.metaloom.loom.nodes.spec` (`loom-shared/node-model`) | **new** — 3 ports, form parameters, `TRANSFORM`, icon `crop` |
| `MediaArtifacts` | `io.metaloom.cortex.common.artifact` | **reuse** — `decodedImage(ctx)`, the shared per-segment decode cache |
| `AtomicFiles` | `io.metaloom.cortex.node.watermark` | **reuse** — `.part` naming + replacing move (§9: promote to `cortex/common`) |
| `WatermarkGeometry` | same | **reference** — the relative-placement precedent `ManipulationGeometry` mirrors |
| `FacedescriptionNode` | `io.metaloom.cortex.node.facedescription` | **reference** — `resolveBoxes(...)` shows reading a MANY detection port; `cropFace(...)` the clamped `getSubimage` |
| `MetadataExtractor` | `io.metaloom.cortex.node.metadata` | **reference** — the metadata-extractor-not-Tika EXIF read; `TAG_ORIENTATION` mapping |
| `ExifJpegFixture` | `io.metaloom.cortex.node.metadata.fixture` (test) | **reuse in tests** — `orientation(int)` builds a JPEG with a chosen EXIF orientation |
| `HashUtils` | `io.metaloom.utils.hash` (hash-utils) | **reuse** — `segmentPath(basePath, sha512)` |
| `LocalResultCache` | `io.metaloom.cortex.common.cache` | **reuse** — the in-heap skip cache |

---

## 6. Options

All **per pipeline instance**. The node adds **no worker-level configuration**: it reads
`metaPath` (`CORTEX_META_PATH`) from `CortexOptions` and nothing else.

| Option | Type | Default | Notes |
|---|---|---|---|
| `operations` | `STRING` | `AUTOROTATE,ASPECT,RESIZE` | Ordered, comma-separated. `AUTOROTATE` must be first if present |
| `cropX` / `cropY` | `NUMBER` | `0.0` | Relative top-left of the `CROP` rectangle |
| `cropWidth` / `cropHeight` | `NUMBER` | `1.0` | Relative size; the defaults are the identity crop |
| `subjectTypes` | `STRING` | `face` | Comma-separated `type` values to accept from the detection elements |
| `minConfidence` | `NUMBER` | `0.5` | Detections below this are ignored |
| `subjectPadding` | `NUMBER` | `0.35` | Grow the *union* by this fraction on every side |
| `subjectFallback` | `ENUM` | `CENTRE` | `CENTRE` \| `SKIP` \| `FAIL` when no detection survives |
| `targetAspect` | `STRING` | `""` | `W:H`, e.g. `16:9`. Empty keeps the current ratio |
| `aspectMode` | `ENUM` | `CROP` | `CROP` (lose pixels) \| `PAD` (lose nothing) |
| `padFill` | `ENUM` | `BLUR` | `COLOR` \| `BLUR`. `BLUR` is the VVS fix (§3.4) |
| `padColor` | `STRING` | `#000000` | `padFill = COLOR` only |
| `blurRadius` | `INTEGER` | `24` | Box-blur radius of the backdrop |
| `blurZoom` | `NUMBER` | `1.15` | Backdrop overscan so its edges stay out of frame |
| `maxLongEdge` | `INTEGER` | `0` | `RESIZE` bound; `0` disables |
| `allowUpscale` | `BOOLEAN` | `false` | Enlarging invents detail |
| `outputFormat` | `ENUM` | `JPEG` | `PNG` \| `JPEG`. PNG for graphics, JPEG for photos |
| `jpegQuality` | `NUMBER` | `0.90` | `outputFormat = JPEG` only |
| `backgroundColor` | `STRING` | `#FFFFFF` | Alpha flatten target when encoding JPEG (§4) |
| `enabled`, `processIncomplete`, `retryFailed`, `timeoutMs` | | `true` / `false` / `false` / inherited | Standard, from `AbstractNodeOptions` |

**Environment variables**: none. `CortexEnvOptions` carries no per-node keys at all — it applies
`LOOM_*` / `CORTEX_*` onto worker-level sections only (`applyLoom`, `applyWorker`, `applyS3`,
`applyGDrive`, `applyOneDrive`) — so it needs **no edit** for this node. Likewise `CortexOptions`
needs no per-node field; options are keyed in its `nodes` map by `KEY` and deserialized via the
`CortexNodeOptionDeserializerInfo` binding in the node's own Dagger module. See
[../cortex/CONFIGURATION.md](../cortex/CONFIGURATION.md).

---

## 7. Registration touch-points

[../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) §2 lists **five** touch-points. Three more are
live and missing from that guide — the implementing change must add them there too (the standing
rule: where a spec and the code disagree, the code wins, and you fix the spec in the same change).

| # | File | Edit |
|---|---|---|
| 1 | `cortex/nodes/pom.xml` | `<module>image-manipulation</module>` |
| 2 | `cortex/processor/pom.xml` | dependency on `cortex-image-manipulation-node` |
| 3 | `cortex/cli/…/dagger/NodeCollectionModule.java` | import + `ImageManipulationNodeModule.class` in `@Module(includes = {…})` |
| 4 | `loom-shared/node-model/…/spec/ImageManipulationDescriptorProvider.java` **+** `loom-shared/node-model/src/main/resources/META-INF/services/io.metaloom.loom.nodes.spec.NodeDescriptorProvider` | the descriptor and its ServiceLoader line |
| 5 | `integration-test/…/node/NodePortConformanceTest.java` | `map("io.metaloom.cortex.node.imagemanip.ImageManipulationNode", "image-manipulation")` |
| 6 | ⚠️ **not in NEW_NODE.md** — `cortex/api/…/node/spec/NodeSpecCatalog.java`, `BUILT_IN_NODE_CLASSES` | add the node class FQN. Missing here the node still *runs* but cannot be authored — the worker never announces its `@NodeSpec` |
| 7 | ⚠️ **not in NEW_NODE.md** — `integration-test/…/node/NodeSpecGoldenTest.java` | `GOLDEN.put(fqn, "image-manipulation")` — asserts the harvested `@NodeSpec` equals the hand-written provider field for field |
| 8 | ⚠️ **not in NEW_NODE.md** — `integration-test/pom.xml` | dependency on `cortex-image-manipulation-node`, or the integration test does not compile |

**Guard-test bumps** (failing is the intended tripwire, not a regression):

- `NodeDescriptorServiceLoaderTest` — `28` → **29** providers, `38` → **39** kinds, plus the kind in
  its `testKindsFromEachFormerModule` list.
- [../features/nodes/NODES.md](../features/nodes/NODES.md) §5.2 — currently in sync at *"28 providers
  declare 38 kinds"*; bump to 29/39. ⚠️ [../METALOOM_CONTEXT.md](../METALOOM_CONTEXT.md) §5 and §6
  still say **27/37** and are already stale against the test literals — correct them in the same
  change.
- `NodePortConformanceTest` compares id, content type **and** cardinality in both directions.

Because the node declares both `@NodeSpec`/`@PortDoc`/`@ParamDoc` **and** a hand-written
`ImageManipulationDescriptorProvider`, the two must agree exactly — that is what `NodeSpecGoldenTest`
enforces. The hand-written providers are being retired one at a time; until the sweep reaches this
node, both exist.

---

## 8. Test Setup

```bash
# Unit tests for the node and its helpers
./mvnw -pl cortex/nodes/image-manipulation/core test

# Descriptor + content-type model, SPI discovery (asserts 29 providers / 39 kinds after this change)
./mvnw -pl loom-shared/node-model test

# Kind registration: the worker must advertise 'image-manipulation'
./mvnw -pl cortex/cli test -Dtest=NodeRegistrarTest

# Prove the Dagger graph still resolves with the node wired in
./mvnw -pl cortex/cli -am compile -o

# Ports == descriptor, and the @NodeSpec harvest == the provider
./setup-pool.sh && ./mvnw -pl integration-test test -Dtest='NodePortConformanceTest,NodeSpecGoldenTest'

# End to end against an in-process Loom + pooled DB
./mvnw -pl integration-test test -Dtest=ImageManipulationNodeIntegrationTest
```

🔴 `./setup-pool.sh` is mandatory before any `integration-test` run and again after any Flyway change
— see the project `CLAUDE.md`. A node-constructor change also needs `cortex/core` clean-rebuilt, or
`setup-pool`/tests fail with `NoSuchMethodError` against the stale Dagger factory.

| Test | What it guards against |
|---|---|
| `ManipulationGeometryTest` | Every composition defect, pure and fast: the four **mirrored** EXIF cases silently treated as rotations; axes not swapped for 5–8; boxes not transformed with the pixels (§2.1); a crop leaving the frame; `expandToAspect` cutting subjects; upscaling when `allowUpscale = false` |
| `ExifOrientationTest` | Missing/unreadable EXIF failing instead of no-op'ing. Build inputs with `ExifJpegFixture.orientation(n)` for all of 1–8 |
| `ManipulationImagesTest` | JPEG-with-alpha producing inverted colour (§4); the blur backdrop's own edges visible inside the frame at `blurZoom = 1.0`; a non-atomic write leaving a truncated artifact |
| `ImageManipulationNodeTest` | The source file being modified; the chain running out of order; a second run not served from cache (mocked client hit **once**); a cache hit serving a deleted file; **a re-run with different detections serving the first run's crop** (§2.4) |
| `ImageManipulationNodePersistenceTest` | Mocked `LoomHttpClient`: exactly one `asset_node_result` row with the right `nodeKind`/`state`/`origin` and `resultRef == null`; a FAILED row when the work throws |
| `ImageManipulationOptionsValidationTest` | Misconfiguration surfacing per-item instead of at pipeline start: unknown op names, duplicates, `AUTOROTATE` not first, a malformed `targetAspect`, a degenerate crop rectangle |
| `ImageManipulationNodePipelineTest` | `extends AbstractNodeChainTest` — adapter integration, completion/tracking events, the artifact chaining into a `CapturingNode`, disabled + dry-run skip |
| `ImageManipulationNodeIntegrationTest` | The ledger row not reaching Postgres, or losing the `producerVersion` |

Plus the two AssertJ helpers under `…/imagemanip/assertj/`: `ImageManipulationNodeAssertions extends
NodeAssertions` (`cortex/core-media` test-jar) and `ImageManipulationOptionsAssert extends
AbstractCortexNodeOptionsAssert` (`cortex/api` test-jar). Test scaffolding (`StubLoomMedia`,
`AbstractNodeChainTest`, `CapturingNode`) comes from the `cortex-pipeline-core` test-jar.

Reusing `ExifJpegFixture` needs a test-jar dependency on `cortex-metadata-node`; if that coupling is
unwanted, copy the fixture rather than reimplement EXIF writing by hand.

---

## 9. Progress Assessment

### Open — everything

- [ ] Maven modules `cortex/nodes/image-manipulation` + `core`
- [ ] `Op`, `ManipulationGeometry` (pure, all 8 orientations, pixels **and** boxes)
- [ ] `ExifOrientation`, `SubjectBoxes`, `ManipulationImages` (blur fill, alpha flatten, PNG/JPEG)
- [ ] `ImageManipulationNode` — chain execution, digest-keyed cache, artifact path, ledger
- [ ] `ImageManipulationNodeOptions` + `validate()` (§6)
- [ ] `ImageManipulationNodeModule` Dagger bindings
- [ ] `ImageManipulationDescriptorProvider` + SPI line
- [ ] All **eight** registration touch-points (§7) + the three guard-test bumps
- [ ] Add the three missing touch-points to [../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) §2
- [ ] Correct the stale 27/37 counts in [../METALOOM_CONTEXT.md](../METALOOM_CONTEXT.md)
- [ ] Unit + pipeline + persistence + integration tests (§8)
- [ ] `../features/nodes/NODES.md` — node-list §3.1, persistence §2, cache-key §4, wiring §5.1/§5.2, options §6.2/§6.3
- [ ] `../features/pipeline/NODE_DATA_TYPES.md` §4 — the port rows
- [ ] `website/content/english/docs/nodes/image-manipulation/index.adoc` + the three edits to
      `nodes/_index.adoc` (category table row, requirements row, processing-capabilities paragraph)
- [ ] Demo data — this node needs no GPU and no sidecar, so unlike `imagegen`/`depthmap` it **should**
      be seeded into a `DemoDatabaseInitializer` ingest pipeline

### Follow-ups this node creates

- [ ] 🔴 **Durable derived bytes.** Add `DERIVED_IMAGE` to `AttachmentType` (Flyway migration + jOOQ
      regen + the Java enum) and have this node be the first to call
      `LoomClient.uploadAttachment(...)`. The endpoint and the client method already exist — §2.5.
      Until then the artifact is durable only via `s3-sink` sharing a worker with this node.
- [ ] **Correct the stale comment** in `watermark`, `thumbnail`, `depthmap` and `imagegen` that
      claims no byte-ingest endpoint exists.
- [ ] **Consolidate the image helpers.** `VlmImages`, `DepthImages` and `WatermarkImages` are three
      near-duplicate `read`/`downscale`/`toOpaque` implementations whose javadocs each admit the
      duplication, and `VlmImages.rotate` handles only 4 of the 8 EXIF orientations. Promote
      `AtomicFiles` and the writers into `cortex/common` as the first step; `ManipulationGeometry`
      makes the missing 4 cases available to all of them.
- [ ] **A `focalPoint` output.** `SUBJECT_CROP` computes a subject centroid; emitting it would let
      `loom-ui` crop responsively without re-running the node, and answers the *"Focalpoint node?"*
      backlog line without a second node.

### Deliberately not built

- [ ] **No video path.** Video needs an ffmpeg re-encode, container/rotation-side-data handling and a
      wholly different timeout budget. `watermark` already carries that shape and an open
      *"rotation/SAR is not handled"* item — a `video-manipulation` sibling should reuse
      `ManipulationGeometry` and `FfmpegRunner`. The backlog line stays in
      [../tasks/METALOOM_NOTES.md](../tasks/METALOOM_NOTES.md) for it.
- [ ] **No person/object detection.** `yolo4j` is not wired into this repo. `SUBJECT_CROP` consumes
      `detection/*`, so a future detector needs no change here.
- [ ] **No per-face fan-out.** `IN_DETECTIONS` is `MANY`, so the node gathers the boxes and produces
      **one** composed crop per image. Declaring it `ONE` would instead run the node once per face
      and emit one crop each — a different feature (per-person thumbnails), and one that would need
      the face index in the artifact filename.
- [ ] **No WebP/AVIF output.** Not in the JDK's ImageIO; adding an encoder dependency is out of scope.
- [ ] **No colour, exposure or sharpening operations.** This node is geometry only.

---

## 10. Where do I find …?

| Need | Path |
|---|---|
| The rules for building this node | [../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) |
| The closest built sibling to copy | `cortex/nodes/watermark/core/…/WatermarkNode.java` · [NODE_WATERMARK_PLAN.md](NODE_WATERMARK_PLAN.md) |
| Node base class, `recordNodeResult`, `resultRef` | `cortex/common/…/node/AbstractMediaNode.java` |
| `next()` / `abort()` / `skipped()` semantics | `cortex/api/…/node/context/impl/NodeContextImpl.java` |
| Options base + `validateCommon()` | `cortex/api/…/option/node/AbstractNodeOptions.java` |
| Port types + the content-type vocabulary | `cortex/api/…/node/{InputPort,OutputPort}.java` · `loom-shared/node-model/…/ContentTypeRegistry.java` |
| The shared decoded-image cache | `cortex/common/…/artifact/MediaArtifacts.java` |
| What a detection element looks like on the wire | `cortex/nodes/facedetect/…/FacedetectNode.java`, the `OUT_DETECTIONS` javadoc |
| Where EXIF orientation is extracted today | `cortex/nodes/metadata/…/MetadataExtractor.java` · `MetadataMapper.java` |
| Where it is stored | `asset_image_comp.orientation` · `AssetImageComp` · `ImageInfo` |
| The byte-ingest endpoint and client method | `loom/services/rest/…/endpoint/impl/AttachmentEndpoint.java` · `loom-client/common/…/method/AttachmentMethods.java` |
| Artifact → object storage | `cortex/nodes/s3-sink/…/ArtifactSelector.java` · [NODE_S3SINK_PLAN.md](NODE_S3SINK_PLAN.md) |
| The port/content-type model | [../features/pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md) |
| The node system as a whole | [../features/nodes/NODES.md](../features/nodes/NODES.md) |

---

_Git HEAD revision: `9a418194`_
_Last updated: 2026-08-03 (new file — plan for the image-manipulation node: EXIF autorotate, crop,
subject crop, aspect/VVS, resize; images only, ordered op chain, detections via input port)_
