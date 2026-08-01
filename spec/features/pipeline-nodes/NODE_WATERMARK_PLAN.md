# Watermark Node — Design Record

> ## 🟢 Status: BUILT and shipped
>
> Kind `watermark`, module `cortex/nodes/watermark`, package `io.metaloom.cortex.node.watermark`.
> Bound in `NodeCollectionModule`, descriptor in `WatermarkDescriptorProvider`, 51 unit tests +
> 2 integration tests, website docs published. It is the **newest node in the tree** and therefore
> the worked example in [../../guidelines/NEW_NODE.md](../../guidelines/NEW_NODE.md).
>
> **This file is now a design record, not a plan.** The code is the source of truth
> (`cortex/nodes/watermark/`). Only §6 "Still open" describes work that does not exist.

The `watermark` node composites a configured overlay image onto the asset's own pixels. Images are
redrawn with `Graphics2D`; video is re-encoded through the `ffmpeg` overlay filter with its audio
stream-copied. **The source file is never modified.**

```
media : media/*  ──▶  watermark  ──▶  image : artifact/image   (image items)
                                 ──▶  video : artifact/video   (video items)
                                 ──▶  flag  : scalar/string    (DONE | FAILED)
```

Read alongside [NODES.md](NODES.md) (the node system, the capability matrix, the shared gotchas),
[../pipeline/NODE_DATA_TYPES.md](../pipeline/NODE_DATA_TYPES.md) (the port/content-type model, incl.
`artifact/video`) and [../../guidelines/NEW_NODE.md](../../guidelines/NEW_NODE.md) (the template
this node defines).

---

## 1. Already implemented

| Item | Where it lives |
|---|---|
| Node, lifecycle, cache key, artifact path, ledger | `cortex/nodes/watermark/core/…/WatermarkNode.java` |
| Kind binding (`@Binds @IntoMap @StringKey`) | `cortex/cli/…/dagger/NodeCollectionModule.java` (`WatermarkNodeModule`) |
| Placement arithmetic (pure, no ImageIO/ffmpeg) | `…/WatermarkGeometry.java` — `Placement(x, y, width, height)` |
| base64 decode + `Graphics2D` composite + atomic PNG write | `…/WatermarkImages.java` |
| The only subprocess call site (`isAvailable`/`probe`/`overlay`) | `…/FfmpegRunner.java` |
| `.part` naming + replacing atomic move | `…/AtomicFiles.java` |
| 11 options (10 own + inherited `timeoutMs`) + `validate()` | `…/WatermarkNodeOptions.java` (`KEY = "watermark"`) |
| Descriptor: 3 ports, 14 form parameters, `TRANSFORM`, icon `branding_watermark` | `loom-shared/node-model/…/spec/WatermarkDescriptorProvider.java` |
| `artifact/video` content type | `loom-shared/node-model/…/spec/ContentTypeRegistry.java` |
| 51 unit tests (7 classes + fixtures + AssertJ helpers) | `cortex/nodes/watermark/core/src/test/…` |
| 2 integration tests (in-process Loom + pooled Postgres) | `integration-test/…/integration/node/WatermarkNodeIntegrationTest.java` |
| Cross-tree registration guards | `NodeDescriptorServiceLoaderTest`, `NodePortConformanceTest`, `NodeRegistrarTest` |
| Customer-facing docs | `website/content/english/docs/nodes/watermark/index.adoc` |

**Persists**: `asset_node_result` ledger only. The marked bytes stay in
`metaPath/watermark_bin/<segment>/<sha512>-<optionsHash>.<ext>`.

---

## 2. The four decisions worth keeping

### 2.1 Relative X/Y address the inset box, not the frame

`x = mediaW * relX` would place the overlay's **left edge** at that fraction, so `relX = 1.0` pushes
the logo out of the picture and `0.5` centres its left edge rather than the logo. Instead:

```
targetW = scale > 0 ? round(mediaW * scale) : sourceW     // scale is relative to MEDIA width
targetH = round(targetW * sourceH / sourceW)              // aspect preserved
x       = round((mediaW - targetW) * relX)
y       = round((mediaH - targetH) * relY)
```

`0.0` flush left/top, `1.0` flush right/bottom, `0.5` centred, and the overlay can never leave the
frame. Defaults `relX = relY = 0.95` give bottom-right with a margin. **`scale` is relative to the
media**, because a logo sized in absolute pixels is right on exactly one resolution. A
wide-and-short frame can make the aspect-preserved height overflow: the node then **gives up the
requested width rather than the aspect ratio**.

### 2.2 `ffprobe`, not video4j

The video path needs the frame size. `VideoFile.width()/height()` would answer it but drags the
**OpenCV native runtime** into the module. `ffprobe` ships with the `ffmpeg` the node already
requires, so the module stays pure JDK plus one external binary and both media paths share the same
`WatermarkGeometry`. Resolving geometry in Java also keeps `scale2ref` (deprecated in ffmpeg 7) out
of the filter graph — it only ever sees integers.

### 2.3 Video is re-encoded; audio is not

```
ffmpeg -nostdin -hide_banner -loglevel error -y -i <in> -i <overlay.png> \
  -filter_complex "[1:v]scale=W:H,format=rgba,colorchannelmixer=aa=OP[wm];[0:v][wm]overlay=X:Y[v]" \
  -map "[v]" -map "0:a?" \
  -c:v libx264 -crf 23 -preset medium -c:a copy -movflags +faststart <out>
```

- `-map "0:a?"` — the trailing `?` makes audio **optional**; a silent clip does not fail the run.
- `colorchannelmixer=aa=` **scales** the existing alpha rather than replacing it, so a transparent
  pixel stays transparent at any opacity.
- `%.4f` with `Locale.ROOT` for opacity — a comma decimal separator splits the filter argument and
  ffmpeg rejects the whole graph, on a German-locale worker only.

### 2.4 Reproducibility: the options digest is in the *file name*

`producerVersion = "watermark/1:" + sha256(watermarkBase64)[0..12]` — a changed logo is visibly a
different producer in the ledger. And the artifact name carries the options digest, not just the
cache key: two `watermark` nodes in one graph (a logo bottom-right, a rating badge top-left) key on
the same media SHA-512 and would otherwise serve each other's output. The digest covers the
watermark **by content**, `relX`, `relY`, `scale`, `opacity` and the three video encode settings.
The cache-hit path also re-checks `Files.exists` — an artifact deleted between runs would otherwise
be handed downstream as a path that no longer resolves.

---

## 3. Conventions and Gotchas

🔴 **A `.part` temp file must keep the target's extension last.** ffmpeg picks its output muxer from
the file name, so `clip.mp4.part` fails with *"Unable to choose an output format"*.
`AtomicFiles.partFor` produces `clip.part.mp4`. Real bug; pinned by
`WatermarkImagesTest.testPartFileKeepsTheTargetsExtensionLast`.

🔴 **`ctx.failure(msg).abort()`, never `.next()`.** `NodeContextImpl.next()` ignores a recorded
failure cause and builds the result as `SUCCESS` ([NODES.md](NODES.md)). Here that would report an
*un-watermarked* item as done.

🔴 **The descriptor advertises `timeoutMs`, which is inherited from `AbstractNodeOptions`.** The
node reads `options().getTimeoutMs()` when building `FfmpegRunner`. If a future refactor moves that
field, the descriptor parameter silently becomes a no-op — there is no test binding the two.

⚠️ **A missing ffmpeg fails, it does not skip.** A skip reads as "this clip needed no watermarking",
which would quietly ship unmarked video. The message names the `ffmpegPath` option.

⚠️ **Subprocess output is drained on a separate daemon thread.** Draining after `waitFor` deadlocks
on a full pipe; draining on the calling thread makes `readLine` block until the child closes the
pipe, so the wall clock could never fire — the one failure mode the timeout exists to bound. Only
the trailing 20 lines are kept.

⚠️ **`ffprobe` reports *coded* dimensions.** A clip with rotation side-data or a non-square SAR
displays at different dimensions and the overlay lands against coded geometry. Known, unhandled.

⚠️ **The video overlay PNG is written next to the artifact**, not into a shared temp directory, so
two concurrent watermark nodes with different logos cannot clobber each other mid-encode.

⚠️ **`NodeResult` carries no origin.** `ctx.origin(LOCAL)` is set but not observable, so the tests
prove a cache hit by overwriting the artifact with a sentinel and checking it survives.

⚠️ **Concurrency 1 by default.** ffmpeg is already internally threaded.

---

## 4. Key Classes Reference

| Class | Package / module | Purpose |
|---|---|---|
| `WatermarkNode` | `io.metaloom.cortex.node.watermark` (`cortex/nodes/watermark/core`) | `KIND = "watermark"`; ports `IN_MEDIA`, `OUT_IMAGE`, `OUT_VIDEO`, `OUT_FLAG`; cache key, artifact path, ledger |
| `WatermarkNodeOptions` | same | `KEY = "watermark"`, `validate()`, extends `AbstractNodeOptions` |
| `WatermarkNodeModule` | same | Dagger `@Binds @IntoSet` + `@Binds @IntoMap @StringKey(KIND)` |
| `WatermarkGeometry` | same | **Pure** relative→absolute placement; `record Placement(x, y, width, height)` |
| `WatermarkImages` | same | base64 decode (bare or `data:` URI), `Graphics2D` composite, atomic PNG write |
| `FfmpegRunner` | same | The only class that starts a process: `isAvailable()`, `probe()`, `overlay()` |
| `AtomicFiles` | same | `.part` naming rule and the replacing atomic move |
| `WatermarkDescriptorProvider` | `io.metaloom.loom.nodes.spec` (`loom-shared/node-model`) | 3 ports, 14 parameters, `TRANSFORM`, icon `branding_watermark` |
| `ContentTypeRegistry` | same | `ARTIFACT_IMAGE`, `ARTIFACT_VIDEO`, `SCALAR_STRING` |

---

## 5. Options

All **per pipeline instance**; the node has no worker-level configuration beyond `metaPath`
(`CORTEX_META_PATH`, see [../../cortex/CONFIGURATION.md](../../cortex/CONFIGURATION.md)).

| Option | Type | Default | Notes |
|---|---|---|---|
| `watermarkBase64` | `CODE` | `""` | Bare base64 or a `data:image/png;base64,…` URI |
| `relX` / `relY` | `NUMBER` | `0.95` | Fraction of the **inset box**, not the frame (§2.1) |
| `scale` | `NUMBER` | `0.20` | Fraction of **media** width; `0` keeps the overlay's native size |
| `opacity` | `NUMBER` | `1.0` | Scales the overlay's existing alpha |
| `videoCodec` | `STRING` | `libx264` | Video path only |
| `videoCrf` | `INTEGER` | `23` | Video path only |
| `videoPreset` | `STRING` | `medium` | Video path only |
| `ffmpegPath` | `STRING` | `ffmpeg` | Named in the failure message when missing |
| `ffprobePath` | `STRING` | `ffprobe` | |
| `timeoutMs` | `INTEGER` | `600000` | Inherited from `AbstractNodeOptions`; wall clock per invocation |
| `enabled`, `processIncomplete`, `retryFailed` | `BOOLEAN` | `true`/`false`/`false` | Standard node parameters |

---

## 6. Progress Assessment

### Done

- [x] Module, node, options, Dagger module, kind binding in `NodeCollectionModule`
- [x] Image path — ImageIO/`Graphics2D`, no OpenCV, atomic PNG write
- [x] Video path — `ffmpeg` overlay filter, audio stream-copied, wall-clocked subprocess with
      bounded output capture and forcible termination
- [x] `artifact/video` content type in `ContentTypeRegistry`; satisfies `s3-sink`'s `artifact/*`
      input through the family wildcard
- [x] Descriptor + SPI registration, 14 form parameters, `watermarkBase64` as `CODE`
- [x] 51 unit tests + 2 integration tests + the three cross-tree registration guards
- [x] Customer-facing docs — `website/content/english/docs/nodes/watermark/index.adoc`

### Still open

- [ ] 🔴 **The artifact is durable only via `s3-sink`, which must share a worker with this node.**
      The general raw-byte-ingest gap is owned by
      [../rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md](../rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md);
      the affinity half is [NODES.md](NODES.md). Nothing to solve in this node.
- [ ] 🔴 **No UI icon rendering.** The descriptor sets `branding_watermark`, but `loom-ui` contains
      no consumer of `NodeDescriptor.icon` at all — `branding_watermark` appears nowhere under
      `loom-ui/src`. (An earlier revision of this file claimed a `PipelineEditor.tsx` `ICON_MAP`
      entry; there is no such map.) This is a UI-wide gap, not a watermark one.
- [ ] **No demo data.** `DemoDatabaseInitializer` has no `watermark` node in any demo pipeline
      (verified: zero matches), so the node is invisible in a fresh demo instance. Required by
      [../../guidelines/CODING.md](../../guidelines/CODING.md).
- [ ] **The watermark cannot come from upstream.** An optional `watermark : artifact/image` input
      port would let `imagegen` or `script` supply the overlay, following `imagegen`'s
      wired-port-beats-configured-option idiom. `WatermarkNode` declares only `IN_MEDIA`.
- [ ] **No `watermarkPath` option.** A large logo must be inlined into the pipeline JSON, which is
      stored in Postgres and rendered in the editor.
- [ ] **PNG output only** for images. PNG was chosen because it is alpha-safe and byte-deterministic,
      which the pixel assertions rely on; a JPEG + quality option is unimplemented.
- [ ] **No tiling, rotation or text watermarks.** One overlay, one position.
- [ ] **Rotation/SAR is not handled** — `ffprobe` coded dimensions, §3.

---

## 7. Test Setup

```bash
# The node and all its helpers — 51 tests. The video suite self-skips without ffmpeg.
./mvnw -pl cortex/nodes/watermark/core test

# Descriptor + content-type model, SPI discovery (asserts 26 providers / 41 kinds)
./mvnw -pl loom-shared/node-model test

# Kind registration: the worker must advertise 'watermark'
./mvnw -pl cortex/cli test -Dtest=NodeRegistrarTest

# Descriptor ports == the node's runtime port constants
./setup-pool.sh && ./mvnw -pl integration-test test -Dtest=NodePortConformanceTest

# End to end against a real in-process Loom + pooled DB (2 tests)
./mvnw -pl integration-test test -Dtest=WatermarkNodeIntegrationTest
```

| Test | What it guards against |
|---|---|
| `WatermarkGeometryTest` | Every placement defect: off-frame at `1.0`, left-edge-not-centre at `0.5`, lost aspect ratio, zero-pixel overlays |
| `WatermarkImagesTest` | Undecodable base64 reaching ImageIO; compositing off by a pixel; the `.part`/muxer trap |
| `WatermarkNodeTest` | Wrong corner; the source file being modified; a stale artifact shared between two differently-configured nodes; a cache hit serving a deleted file |
| `WatermarkNodeVideoTest` | The real ffmpeg contract — dimensions preserved, **audio copied**, temp files cleaned up, a missing binary failing rather than skipping |
| `WatermarkOptionsValidationTest` | A misconfiguration surfacing per-item instead of at pipeline start |
| `WatermarkNodePipelineTest` | The adapter, the events, and the artifact chaining into a downstream sink |
| `WatermarkNodeIntegrationTest` | The ledger row not reaching Postgres, or losing the `producerVersion` that identifies the mark |

---

## 8. Where do I find …?

| Need | Path |
|---|---|
| The node | `cortex/nodes/watermark/core/src/main/java/io/metaloom/cortex/node/watermark/WatermarkNode.java` |
| The placement arithmetic | `…/watermark/WatermarkGeometry.java` |
| base64 decode + compositing | `…/watermark/WatermarkImages.java` |
| Every subprocess call | `…/watermark/FfmpegRunner.java` |
| The `.part` naming rule | `…/watermark/AtomicFiles.java` |
| Options + validation | `…/watermark/WatermarkNodeOptions.java` |
| Synthetic image fixtures | `…/src/test/java/io/metaloom/cortex/node/watermark/WatermarkFixtures.java` |
| The descriptor and its 14 parameters | `loom-shared/node-model/…/spec/WatermarkDescriptorProvider.java` |
| The `artifact/video` content type | `loom-shared/node-model/…/spec/ContentTypeRegistry.java` |
| Kind binding | `cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/NodeCollectionModule.java` |
| Customer-facing docs | `website/content/english/docs/nodes/watermark/index.adoc` |
| How to build the next node like this one | [../../guidelines/NEW_NODE.md](../../guidelines/NEW_NODE.md) |
| Where the bytes should eventually go | [../rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md](../rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md) |

---

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (reduced to a design record — shipped work collapsed into one table, and the stale "UI icon mapped in PipelineEditor.tsx" claim corrected: loom-ui has no icon consumer at all.)_
