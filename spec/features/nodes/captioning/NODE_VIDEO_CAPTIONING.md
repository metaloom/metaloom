# Captioning Node (`captioning`) — Still Images and the Three Video Strategies

> **Status**: 🟢 **Built and shipping.** Kind `captioning`, module
> [cortex/nodes/captioning/](../../../../cortex/nodes/captioning/), package
> `io.metaloom.cortex.node.captioning`. 25 unit tests + 4 env-gated tests (3 `SmolVLMClientTest`,
> 1 comparison harness) + 2 integration tests. Inference is external: a bespoke **SmolVLM** service for stills, an
> **OpenAI-compatible VLM endpoint** (Qwen2.5-VL on vLLM or llama.cpp) for video. Contract in the
> generated `node-descriptors.json`, kept honest by `NodeSpecGoldenTest`.
>
> **There is no `video-captioning` node kind and no `cortex/nodes/video-captioning` module.** The
> three video variants that were built and benchmarked separately were merged into this node as one
> option — `videoStrategy` ∈ `WHOLE` (default) | `SCENE` | `NATIVE`. Anything still referring to
> `video-captioning-whole/-scene/-native` predates that merge.
>
> **Scope**: the `captioning` node end to end — from the media file's bytes to the `asset_json_comp`
> row and the `asset_node_result` ledger entry, for both the image and the video branch.
> **Audience**: AI coding agents and humans working on
> [cortex/nodes/captioning/](../../../../cortex/nodes/captioning/).

**Out of scope, and where it lives instead:**

| Not here | There |
|---|---|
| The node system, lifecycle, registration, caching layers | [../NODES.md](../NODES.md) |
| Port content types and cardinality across all nodes | [../../pipeline/NODE_DATA_TYPES.md](../../pipeline/NODE_DATA_TYPES.md) |
| Rules for adding a node at all | [../../../guidelines/NEW_NODE.md](../../../guidelines/NEW_NODE.md) |
| **Measured latency and caption-quality numbers, the serve commands, the model comparison** | [../../../concept/NODE_VIDEO_CAPTIONING_REPORT.md](../../../concept/NODE_VIDEO_CAPTIONING_REPORT.md) |
| The raw benchmark JSON and the captured run environment | [../video-captioning-results/](../video-captioning-results/) |
| Video decoding, video4j, the OpenCV native line, the other video nodes | [../../../cortex/SERVICE_VIDEO.md](../../../cortex/SERVICE_VIDEO.md) |
| The scene detector the `SCENE` strategy drives | `cortex/nodes/scene-detection/`, [../NODES.md](../NODES.md) §3 |
| The other VLM node — a *different* module, `cortex/nodes/vlm/`, prompt-driven and multi-port | [../NODES.md](../NODES.md) §3 |
| The AI-call metric labels `smolvlm` / `video-vlm` | [../../ops/METRICS.md](../../ops/METRICS.md) |
| The customer-facing page and its two screenshots | [../../../website/WEBSITE.md](../../../website/WEBSITE.md) |

---

## 0. Executive Summary

| Question | Short answer |
|---|---|
| **What does it do?** | Produces one sentence describing what a still image or a video shows |
| **One node, two backends** | Images → bespoke SmolVLM `POST /caption`. Video → OpenAI-compatible `POST /v1/chat/completions` (§1) |
| **How is a video turned into a caption?** | The `videoStrategy` option: `WHOLE` (default) · `SCENE` · `NATIVE` (§2) |
| **Which ports?** | `image` **xor** `video` in, one `caption` out. The XOR group is `media_alt` (§3.1) |
| **Where does the result go?** | `asset_json_comp` — `schemaType=caption` for images, `schemaType=video-caption` for video — plus one `asset_node_result` ledger row (§4) |
| **Are per-scene captions segments?** | 🟡 No. They are a `scenes` array **inside** the JSON component, keyed by **frame index**. The `CAPTION` `segment_type` was never migrated (§9) |
| **Can the UI configure video captioning?** | 🟡 No. Every video option is `@ParamDoc(hidden = true)`, so the descriptor advertises only the three common flags. Cortex options file only (§6) |
| **Does it need a GPU?** | Not on the worker. Both models run in the external service; the worker only decodes frames |

```
image | video  (XOR group "media_alt")  ──▶  captioning  ──▶  caption : text/caption  ONE
```

`text/caption` is a leaf of the `text/*` family, so the `caption` port feeds any `text/*` input —
`translate`, `tts`, `sentiment`, `guard`, `tag`, `script`, and the `prompt` port of `imagegen` /
`videogen`. See [../../pipeline/NODE_DATA_TYPES.md](../../pipeline/NODE_DATA_TYPES.md).

---

## 1. Two backends behind one node

The node is one kind with two entirely separate inference paths. They share the lifecycle, the skip
cache and the output port, and nothing else.

| | Image branch | Video branch |
|---|---|---|
| Client | `SmolVLMClient` | `VideoVLMClient` |
| Protocol | bespoke `POST /caption`, `{prompt?, image_data\|image_url}` | OpenAI `POST /v1/chat/completions` |
| Endpoint option | `smolVLMHost` + `smolVLMPort` (`localhost:8000`) | `videoEndpointUrl` (`http://localhost:8000`) |
| Model selection | whatever the service loaded | `videoModel` (`qwen25vl-awq`) sent in the request body |
| Auth | none | optional `Authorization: Bearer <videoApiKey>` |
| Payload | one JPEG, base64, longest edge `targetFrameSize` | N frames as `image_url` data-URI parts, or one `video_url` part |
| Metric label | `smolvlm` | `video-vlm` |
| `schemaType` written | `caption` | `video-caption` |

Both clients force **HTTP/1.1** (`HttpClient.Version.HTTP_1_1`). The SmolVLM client says why in a
comment — FastAPI rejects the JDK client's default HTTP/2 upgrade attempt. The same reflex is why
`VideoVLMClient` pins it too, and it is the tree-wide idiom for sidecar clients.

`VideoVLMClient` normalises its base URL: trailing slashes are stripped, and `/v1/chat/completions`
is appended unless the configured URL already ends in `/chat/completions`. So both
`http://host:8000` and a full endpoint URL work.

---

## 2. The three video strategies

`videoStrategy` selects one. All three drive the *same* `VideoVLMClient` against the *same*
endpoint — swapping model or backend is an option change, never a code change.

| Strategy | How the clip reaches the model | Produces | Backend support |
|---|---|---|---|
| `WHOLE` (default) | `frameCount` frames sampled evenly across the whole clip, sent as one multi-image prompt | one caption for the clip, `scenes` empty | every OpenAI-compatible backend |
| `SCENE` | `OpticalFlowSceneDetector` cuts the clip first, then each scene is captioned independently | a labelled per-scene timeline **and** a structured `scenes` array | every OpenAI-compatible backend |
| `NATIVE` | the file's `file://` URI is sent as a `video_url` part; the **server** does the sampling | one caption for the clip | 🟡 vLLM only — llama.cpp returns HTTP 400 |

Behaviour worth knowing before choosing one:

* **`SCENE` degenerates to whole-video on single-shot content** — correctly, because the detector
  yields one scene. It earns its cost only on edited, multi-shot material.
* **`SCENE`'s per-scene frame budget is not `frameCount`.** `VideoCaptioner.captionScene` computes
  `perScene = max(2, min(frameCount, 4))`, so it is clamped to 2–4 frames per scene however large
  `frameCount` is. The website page and the node's own option docs both overstate this (§9).
* **`SCENE` caps at `maxScenes` (32)** and simply stops after that many — the caption then describes
  only the first 32 scenes, with no flag saying so.
* **When the detector returns nothing, `SCENE` falls back to one scene spanning the clip**
  (`new Scene(0, Math.max(0, video.length() - 1))`), so it never produces an empty caption.
* **`NATIVE` overruns small context windows.** A real failure from the benchmark run:
  `Input length (12461) exceeds model's maximum context length (8192)`. It is an opt-in for short
  clips, or needs a raised `--max-model-len` on the server (§9).
* **The overall caption for `SCENE` is a rendered string**, one line per scene:
  `Scene <n> [frames <from>-<to>]: <caption>`. That string is what the `caption` port emits; the
  machine-readable form is `VideoCaptionOutput.scenes()`.

---

## 3. The decisions worth keeping

### 3.1 `image` and `video` are an XOR group, not two independent inputs

Both ports are `InputPort.one(...)`, both carry `group = "media_alt"`, and the node declares
`@PortGroupDoc(id = "media_alt", mode = PortGroupMode.XOR, label = "Media")`. Wire exactly one;
wiring both is a graph validation error. The generated descriptor carries the group, so the editor
draws the alternative rather than two required inputs.

`isProcessable` is `isVideo() || isImage()` — the *file* decides which branch runs, the wiring only
decides what may reach the node.

### 3.2 One skip cache, one key, two schema types

`LocalResultCache<String>` (10 000 entries, in-heap, worker-lifetime) is shared by both branches and
keyed by **the media's absolute path**. A hit re-emits the cached caption, records
`recordAiCacheHit(...)`, returns `ResultOrigin.LOCAL` and **skips re-persisting** — the durable copy
is already in Loom.

The consequence to keep in mind: one cache, but two different `schemaType`s downstream. The cache
holds only the caption string, so it cannot distinguish an image caption from a video caption — it
does not need to, because one path is a file and a file is either an image or a video, never both.

### 3.3 The strategy is stored inside the payload, not as the component's `variant`

`variant` is `""` on both rows. The strategy name is written **into** the JSON data as
`"variant": "whole" | "scene" | "native"`. That is deliberate: `variant` is the component's
natural-key discriminator, and the strategy is a property of how the caption was produced, not a
second component to keep alongside the first. Re-running with a different strategy therefore
**replaces** the `video-caption` component rather than adding a sibling.

### 3.4 Scene bounds are frame indices, not milliseconds

`SceneCaption(seq, fromFrame, toFrame, caption)` carries frame numbers, matching `SceneDetectionNode`,
which stores frame indices and carries the source fps in `producerVersion` (e.g. `"fps=25.0"`).
A caption timeline that used milliseconds would be the only time base in the video tree that does.
🟡 The `video-caption` component itself carries **no fps**, so converting its `scenes` to wall-clock
time requires reading the fps from elsewhere.

### 3.5 Frames are sampled in Java, at bucket centres

`FrameSampler` uses video4j `seekToFrame` + `frameToImage`, downscales with imgscalr when the longest
edge exceeds `targetFrameSize`, and places sample *i* of *n* at `(i + 0.5) / n` of the range — the
centre of each of *n* equal buckets, so the often-black first and last frames are avoided. Frames
that decode to `null` are dropped rather than failing the run, so a short or damaged clip yields
fewer frames than `frameCount` rather than an error.

`Video4j.init()` runs in `initialize()`. It is only needed by the video branch, but it is idempotent
and cheap, so it is unconditional.

### 3.6 🟡 `compute()` swallows the cause

The image/video dispatch in `CaptioningNode.compute` catches `Exception`, calls
`e.printStackTrace()` and returns `NodeResult.failed()` — **no ledger row, no message, no logger**.
`persistImage` / `persistVideo` do the right thing (a `FAILED` `asset_node_result` row carrying
`e.getMessage()`), and `AbstractMediaNode.process` would have logged and aborted with the cause had
compute not caught it first. This is the node's sharpest open defect (§9).

Two dead branches live in the same method: the `isAudio()` skip and the trailing
`NodeResult.failed()` are unreachable, because `isProcessable` has already sent everything that is
neither image nor video down the `skipped("unprocessable")` path.

---

## 4. Persistence

| What | Where |
|---|---|
| An image caption | `asset_json_comp`, `nodeKind="captioning"`, `schemaType="caption"`, `variant=""`, `data = {caption}` |
| A video caption | `asset_json_comp`, `nodeKind="captioning"`, `schemaType="video-caption"`, `variant=""`, `data = {caption, variant, model, frameCount, scenes?}` |
| The record that the node ran | one `asset_node_result` row, `result_ref = asset_json_comp:<uuid>` |

The `scenes` array is written only when it is non-empty, i.e. only by the `SCENE` strategy:

```json
{
  "caption": "Scene 1 [frames 0-74]: ...\nScene 2 [frames 75-180]: ...",
  "variant": "scene",
  "model": "qwen25vl-awq",
  "frameCount": 6,
  "scenes": [
    { "seq": 0, "fromFrame": 0, "toFrame": 74, "caption": "..." },
    { "seq": 1, "fromFrame": 75, "toFrame": 180, "caption": "..." }
  ]
}
```

**No migration, and none was needed** — `asset_json_comp` is schemaless by design and
`schemaType` is free text ([../NODES.md](../NODES.md) §2). Persistence is **best effort**: with no
`LoomClient` (offline / unit tests) or with an asset Loom does not know, both persist methods return
without touching the node's result state.

### 4.1 🟡 Per-scene captions are not `asset_segment_comp` rows

The original design's Option B — one `SegmentEntry` per captioned scene with
`segment_type = CAPTION` — was never implemented. Verified against the tree: no migration under
`loom/db/flyway/src/main/resources/db/migration/` mentions `CAPTION`, so the CHECK constraint added
by `V2.42__add_asset_segment_comp.sql` still admits only `SCENE`, `SILENCE`, `SHOT`, `CHAPTER`.
Scene captions live in the JSON component instead. §9 carries what finishing it would take.

---

## 5. Data flow

```mermaid
sequenceDiagram
    participant P as Pipeline
    participant N as CaptioningNode
    participant V as video4j (VideoFile)
    participant S as SmolVLM service<br/>POST /caption
    participant M as OpenAI-compatible VLM<br/>vLLM / llama.cpp
    participant L as LoomClient

    P->>N: process(ctx[media])
    N->>N: enabled? exists? isVideo() || isImage()
    N->>N: LocalResultCache.get(absolutePath)
    alt cache hit
        N-->>P: caption, ResultOrigin.LOCAL, no persist
    else image
        N->>S: captionByImage(scaled JPEG, targetFrameSize)
        S-->>N: caption
        N->>L: createAssetJsonComp(schemaType="caption")
    else video
        alt WHOLE
            N->>V: FrameSampler.sampleEvenly(frameCount, targetFrameSize)
            N->>M: /v1/chat/completions with N image_url parts
        else SCENE
            N->>V: OpticalFlowSceneDetector.detect(video)
            loop per scene, up to maxScenes
                N->>V: FrameSampler.sampleRange(from, to, 2..4)
                N->>M: /v1/chat/completions with that scene's frames
            end
        else NATIVE
            N->>M: /v1/chat/completions with one video_url part
        end
        M-->>N: caption text
        N->>L: createAssetJsonComp(schemaType="video-caption", data{caption, variant, model, frameCount, scenes?})
    end
    N->>N: ctx.output(OUT_CAPTION); cache.put(path, caption)
    N->>L: recordNodeResult(SUCCESS, resultRef("asset_json_comp", uuid))
```

---

## 6. Options

All are `captioning.*` node options ([../NODES.md](../NODES.md) §6 for how they are set).

| Option | Type | Default | Applies to | Notes |
|---|---|---|---|---|
| `enabled` / `processIncomplete` / `retryFailed` | `BOOLEAN` | `true` / `false` / `false` | both | Standard `AbstractNodeOptions` flags — **the only three the descriptor advertises** |
| `smolVLMHost` | `STRING` | `localhost` | image | SmolVLM captioning service host |
| `smolVLMPort` | `INTEGER` | `8000` | image | SmolVLM captioning service port |
| `videoStrategy` | `ENUM` | `WHOLE` | video | `WHOLE` \| `SCENE` \| `NATIVE` — §2 |
| `videoEndpointUrl` | `STRING` | `http://localhost:8000` | video | Base URL of the OpenAI-compatible VLM endpoint |
| `videoModel` | `STRING` | `qwen25vl-awq` | video | Model id served at that endpoint |
| `videoApiKey` | `STRING` | `""` | video | Optional bearer token; omitted from the request when blank |
| `frameCount` | `INTEGER` | `8` | video | Frames for `WHOLE`. For `SCENE` it is clamped to 2–4 per scene (§2) |
| `targetFrameSize` | `INTEGER` | `512` | **both** | Longest edge in px before encoding — the image branch uses it too |
| `maxScenes` | `INTEGER` | `32` | video (`SCENE`) | Upper bound on captioned scenes |
| `maxTokens` | `INTEGER` | `256` | video | Generation limit |
| `temperature` | `NUMBER` | `0.2` | video | Sampling temperature |
| `videoPrompt` | `STRING` | *"Describe what happens in this video in two or three sentences. Focus on actions, subjects and setting."* | video | The single prompt sent with the frames |

**The node reads no environment variables.** The backends do — the `vllm serve` and `llama-server`
invocations actually used are in
[the benchmark report](../../../concept/NODE_VIDEO_CAPTIONING_REPORT.md) §9.

### 6.1 🟡 None of the video options is in the descriptor

Every field above except the three common flags carries `@ParamDoc(hidden = true)`, so
`node-descriptors.json` lists exactly `enabled`, `processIncomplete`, `retryFailed` for this kind.
The comment on the options class states the reason plainly: the hidden markers were added so the
generated contract stayed byte-identical to the hand-written provider it replaced, and surfacing them
is a contract change that belongs in its own reviewed commit. Until then **video captioning can only
be configured in the Cortex options file** — while the website page documents all of it, which makes
the gap visible to customers. Unhiding them is descriptor-only work: ports are untouched, so only the
`NodeSpecGoldenTest` golden file has to be regenerated.

### 6.2 `validate()`

`CaptioningNodeOptions.validate()` extends `validateCommon()` with: non-blank `smolVLMHost`, positive
`smolVLMPort`, non-null `videoStrategy`, non-blank `videoEndpointUrl` and `videoModel`, positive
`frameCount`, `targetFrameSize` and `maxTokens`. 🟡 `maxScenes` and `temperature` are **not**
validated — a zero `maxScenes` silently captions nothing, and a negative temperature is only rejected
by the server.

### 6.3 Comparison-harness environment variables

Read by `VideoCaptioningComparisonIT` only, never by the node:

| Variable | Default | Meaning |
|---|---|---|
| `LLAMACPP_URL` / `LLAMACPP_MODEL` | `http://127.0.0.1:8081` / `qwen` | The llama.cpp endpoint under test |
| `VLLM_URL` / `VLLM_MODEL` | `http://127.0.0.1:8000` / `qwen25vl-awq` | The vLLM endpoint under test |
| `TEST_VIDEOS` | two clips under the video4j workspace | Comma-separated absolute paths |
| `RESULTS_FILE` | `target/video-caption-results.json` | Where the run's JSON is written |
| `FRAME_COUNT` / `FRAME_SIZE` / `MAX_TOKENS` | `8` / `512` / `256` | Sampling and generation budget for the run |

---

## 7. Models and serving

The node targets an external OpenAI-compatible endpoint, so serving is a deployment concern and the
model is an option. The choices below were made **by measurement** — every number behind them is in
[NODE_VIDEO_CAPTIONING_REPORT.md](../../../concept/NODE_VIDEO_CAPTIONING_REPORT.md), with the raw
JSON in [../video-captioning-results/](../video-captioning-results/).

| Role | Model / serving | Why |
|---|---|---|
| **Default** | Qwen2.5-VL-7B-Instruct-AWQ on vLLM, `videoStrategy=WHOLE` | Apache-2.0, genuinely temporal (dynamic-FPS + absolute-time MRoPE), fixed frame budget so clip length does not matter. The shipped `videoModel` default `qwen25vl-awq` names this build |
| **Portable fallback** | Qwen2.5-VL-7B GGUF Q4_K_M on llama.cpp | Same node, different `videoEndpointUrl`. ~2.8× slower and more repetitive, but no Python and it fits 12 GB |
| **Opt-in** | `NATIVE`, vLLM only, short clips | Highest quality when the clip fits the context window |
| **Upgrade path** | Qwen3-VL (native timestamp tokens, larger context), Tarsier2-7B (max detail), MiniCPM-V 2.6 (low VRAM) | All Qwen2-VL-architecture checkpoints — a `videoModel` id swap, not code |
| **Avoid** | Llama-3.2-Vision | Its cross-attention architecture is unsupported in llama.cpp |

Sizing rule of thumb, from the report: video captioning is **headroom-bound, not parameter-bound** —
a 512×384 frame is ~250–350 visual tokens for Qwen2.5-VL, so 16–32 frames is ~4k–8k tokens before the
prompt. Pick a quant that leaves ≥10 GB free after weights. On a mixed 4090 + 3060 box, vLLM cannot
tensor-parallel across the two cards (the uniform per-GPU memory fraction caps at the smaller one) —
pin vLLM to the 4090; llama.cpp *can* use both with `--split-mode layer --tensor-split 24,12`.

---

## 8. Key Classes Reference

| Class | Package (`io.metaloom.cortex.node.captioning`) | Purpose |
|---|---|---|
| `CaptioningNode` | — | Kind `captioning`; ports, both branches, the skip cache, both persistence paths |
| `CaptioningNodeOptions` | — | Every option in §6 plus `validate()`; all video fields `@ParamDoc(hidden = true)` |
| `CaptioningNodeModule` | — | Dagger `@Binds @IntoSet` + `@Binds @IntoMap @StringKey("captioning")`, `optionInfo()`, both client providers |
| `VideoCaptioner` | — | Runs the selected strategy: `captionWhole` / `captionScene` / `captionNative` |
| `VideoCaptioningStrategy` | — | `WHOLE` / `SCENE` / `NATIVE` |
| `FrameSampler` | — | video4j `seekToFrame` sampling + imgscalr downscale; `sampleEvenly`, `sampleRange` |
| `VideoVLMClient` | — | OpenAI `/v1/chat/completions`; `captionFrames` (multi-image) and `captionVideoUrl`. **The seam the tests replace** |
| `SmolVLMClient` | — | Bespoke `POST /caption` image client. Also replaced by subclassing in tests |
| `VideoCaptionOutput` | — | `caption`, `scenes`, `modelLatencyMs`, `frameCount`; nested `SceneCaption(seq, fromFrame, toFrame, caption)` |
| `CaptionResult` | — | One model round-trip: `text`, `latencyMs`, `inputCount` |
| `MultiModalCaption` | — | 🟡 Empty class, no references — dead |
| `OpticalFlowSceneDetector` | `io.metaloom.cortex.node.scene.impl` (`cortex/nodes/scene-detection/core`) | **reused** — the segmentation the `SCENE` strategy drives |
| `AbstractMediaNode` | `io.metaloom.cortex.common.node` (`cortex/common`) | Lifecycle, `fetchAsset`, `recordNodeResult`, `resultRef`, the injected `CortexMetrics` |
| `LocalResultCache` | `io.metaloom.cortex.common.cache` | **reused** — the in-heap skip cache |
| `JsonCompCreateRequest` | `io.metaloom.loom.rest.model.jsoncomp` (`loom-shared/rest-model`) | The `asset_json_comp` payload |

---

## 9. Progress Assessment

### Done

- [x] One node kind, two backends, three video strategies; `videoStrategy` selects between them
- [x] `image` **xor** `video` input group (`media_alt`) → one `caption` (`text/caption`) output
- [x] Dagger `@Binds @IntoMap @StringKey("captioning")`, registered in `cortex/cli` `NodeCollectionModule`
- [x] Java-side frame sampling (`FrameSampler`) with bucket-centre placement and downscaling
- [x] `VideoVLMClient` speaking both multi-image and `video_url`, bearer auth, forced HTTP/1.1
- [x] `asset_json_comp` persistence — `caption` for images, `video-caption` for video — plus the ledger row
- [x] Shared `LocalResultCache`, `ResultOrigin.LOCAL` on a hit, no re-persist
- [x] `recordAiCall` / `recordAiCacheHit` under the `smolvlm` and `video-vlm` labels
- [x] Scene detector defects fixed (boundaries recorded with a `minSceneLength` debounce, Canny-enhanced frame-difference cut signal, trailing scene closed) — guarded by `SceneBoundaryIT`
- [x] 25 unit tests, `CaptioningNodeIntegrationTest` (image + video, read back through REST), env-gated `VideoCaptioningComparisonIT`
- [x] Benchmark report + raw result JSONs, and the model decisions that came out of them (§7)
- [x] Customer docs page `website/content/english/docs/nodes/captioning/` with `nodeviz`, `config.png` and a real `debug.png` from the video branch
- [x] Docs fixture recipe `VisionRecipes.captioning()` — the video branch against a live endpoint

### Open

- [ ] 🟡 **`compute()` swallows the cause** (§3.6). It catches `Exception`, prints the stack trace and
      returns `NodeResult.failed()` with no ledger row and no message — while `persistVideo` records a
      `FAILED` row properly. Align it with the rest of the tree: record the failure, and prefer
      `ctx.failure(msg).abort()` (`.next()` reports `SUCCESS` and drops the message).
- [ ] 🟡 **No video option is configurable from the UI** (§6.1). Drop `hidden = true` from the video
      fields in `CaptioningNodeOptions`, add the enum/number/string metadata, and regenerate with
      `mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true`.
- [ ] 🟡 **`CAPTION` `segment_type` + `createAssetSegmentComps`** (§4.1) — never written. Needs a
      Flyway migration extending the `asset_segment_comp` CHECK constraint, then `./setup-pool.sh`
      **and** `loom/db/jooq/generate.sh` (install `loom/db/flyway` first, or `setup-pool.sh` prints
      "Pool Created" while silently skipping the new migration), a whole-set replace on
      `(asset, node_kind, segment_type)` mirroring `SceneDetectionNode`, and a decision on frames vs
      milliseconds — `SceneDetectionNode` stores frames and carries fps in `producerVersion`.
- [ ] **No integration coverage for the `SCENE` strategy's persisted `scenes` array.** The video IT
      exercises `WHOLE` only.
- [ ] **`NATIVE` has no token cap.** Either expose `fps` / `max_frames` as `mm_processor_kwargs`
      through the options, or document a required `--max-model-len` on the server.
- [ ] **`maxScenes` and `temperature` are unvalidated** (§6.2).
- [ ] **`frameCount` is documented wrong for `SCENE`** — the website options table and the field's own
      javadoc say "per scene", but the code clamps to 2–4. Fix the code or the docs, not neither.
- [ ] **The descriptor still calls the node "Image Captioning"** and describes it as
      "Generate a textual caption for an image", although it captions video too.
- [ ] **`MultiModalCaption` is an empty, unreferenced class.** Delete it.
- [ ] **No `CaptioningNodePipelineTest`** — one of the kinds [../NODES.md](../NODES.md) §10 lists as
      missing pipeline-level coverage.
- [ ] **Audio fusion (not started).** Feeding an upstream Whisper transcript into the caption prompt
      would need a new `text/*` input port plus a prompt template — a contract change, not a tweak.

### Deliberately not built

- [ ] **No per-scene `variant` rows.** One `video-caption` component per asset; the strategy is a
      field in the payload, not a component discriminator (§3.3).
- [ ] **No prompt-per-run configurability beyond `videoPrompt`.** Multi-prompt, multi-output vision
      work is the separate `vlm` node's job (`cortex/nodes/vlm/`), which already has dynamic ports.
- [ ] **No explicit `ctx.preview(...)` calls.** The node emits a single `ONE` text port, which the
      debug card renders on its own.

---

## 10. Test Setup

```bash
# 25 unit tests - no endpoint needed, both clients are stubbed by subclassing.
# (The 3 SmolVLMClientTest cases in the same module self-skip without a live SmolVLM.)
./mvnw -o -pl cortex/nodes/captioning/core test

# The generated contract equals the annotated node
./mvnw -o -pl integration-test test -Dtest=NodeSpecGoldenTest

# End to end against an in-process Loom + pooled Postgres (image + video, read back via REST)
./setup-pool.sh
./mvnw -o -pl integration-test test -Dtest=CaptioningNodeIntegrationTest

# The scene detector the SCENE strategy depends on
./mvnw -o -pl cortex/nodes/scene-detection/core test -Dtest=SceneBoundaryIT

# The comparison harness - self-skips unless a real endpoint answers
VLLM_URL=http://127.0.0.1:8000 VLLM_MODEL=qwen25vl-awq \
  ./mvnw -o -pl cortex/nodes/captioning/core test -Dtest=VideoCaptioningComparisonIT
```

| Test | What it guards against |
|---|---|
| `CaptioningNodeTest` (8) | An image caption not reaching the port; the second run re-invoking the model instead of the cache; any of the three video strategies failing to produce a caption; `SCENE` producing an empty or unlabelled timeline; audio or a document being processed instead of skipped; a disabled node running |
| `CaptioningNodeOptionsValidationTest` (17) | Every option constraint in §6.2 surfacing per item instead of at pipeline start |
| `SmolVLMClientTest` (3) | The image wire format against a real SmolVLM service — `assumeRunning()` skips it otherwise |
| `CaptioningNodeIntegrationTest` (2) | The `caption` component not reaching Postgres; the `video-caption` component missing, or losing its `variant` — the video case runs the **real** video4j sampler against the genai `MockLLMServer`, which already speaks `/v1/chat/completions` |
| `VideoCaptioningComparisonIT` (1) | Not a regression test — the harness that produced the benchmark report. Drives all three strategies against every reachable endpoint and writes the result JSON |
| `SceneBoundaryIT` | The `SCENE` strategy's segmentation collapsing to a single scene again |

`CaptioningNodeTest` constructs the node with a **null Loom client**, so persistence is a no-op and
the unit tests never need a backend — the write-back is covered by the integration test instead.
Video tests call `assumeVideo4j()` / initialise `Video4j` statically; see
[../../../cortex/SERVICE_VIDEO.md](../../../cortex/SERVICE_VIDEO.md) for the native requirements.

---

## 11. Conventions and Gotchas

- **There is no `video-captioning` kind.** Any reference to `video-captioning-whole/-scene/-native`
  or `cortex/nodes/video-captioning` predates the merge into this node and is stale. The benchmark
  report carries the old-name → new-name mapping in its header.
- **The `vlm` node is a different node.** `cortex/nodes/vlm/` is prompt-driven with dynamic
  `result_<id>` output ports and an olmOCR preset; it shares nothing with this module but the idea of
  a vision model.
- **Wire `image` or `video`, never both.** They are an XOR group.
- **One cache key, two schema types** — the path-keyed skip cache is shared, the persistence is not.
- **`variant` on the component is always `""`.** The strategy lives inside the JSON data.
- **Scene bounds are frame indices**, and the component carries no fps to convert them with.
- **`frameCount` does not mean "frames per scene".** `SCENE` clamps to 2–4 (§2).
- **llama.cpp cannot serve `NATIVE`** — it does not decode video files and answers `video_url` with
  HTTP 400. SmolVLM2 is the family's sole exception, at far lower caption quality.
- **vLLM cannot tensor-parallel a 4090 + 3060.** Pin it to the larger card; llama.cpp can split.
- **Both HTTP clients force HTTP/1.1.** FastAPI rejects the JDK client's HTTP/2 upgrade.
- **`Video4j.init()` runs in `initialize()`**, unconditionally, including for image-only pipelines.
- **A Flyway change means `./setup-pool.sh` *and* `loom/db/jooq/generate.sh`** — relevant the moment
  §4.1 is picked up.
- **The code is the source of truth.** Where this document and `cortex/` disagree, the code wins —
  fix this file in the same change ([SPEC_RULES.md](../../../guidelines/SPEC_RULES.md),
  [CODING.md](../../../guidelines/CODING.md)).

---

## 12. Where do I find …?

| Need | Path |
|---|---|
| The node | [cortex/nodes/captioning/core/…/CaptioningNode.java](../../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/CaptioningNode.java) |
| The strategies | `…/captioning/VideoCaptioner.java` · `VideoCaptioningStrategy.java` |
| The options + `validate()` | `…/captioning/CaptioningNodeOptions.java` |
| The two clients and their wire shapes | `…/captioning/VideoVLMClient.java` · `SmolVLMClient.java` |
| Frame sampling | `…/captioning/FrameSampler.java` |
| Dagger bindings | `…/captioning/CaptioningNodeModule.java`, registered in `cortex/cli/…/dagger/NodeCollectionModule.java` |
| The generated contract | `loom-shared/node-model/src/main/resources/node-descriptors.json` (kind `captioning`) |
| Unit tests + the comparison harness | `cortex/nodes/captioning/core/src/test/java/io/metaloom/cortex/node/captioning/` |
| The integration test | `integration-test/src/test/java/io/metaloom/loom/test/integration/node/CaptioningNodeIntegrationTest.java` |
| The docs fixture recipe | `integration-test/…/node/docs/VisionRecipes.java` (`captioning()`) |
| The scene detector | `cortex/nodes/scene-detection/core/src/main/java/io/metaloom/cortex/node/scene/` |
| Flyway migrations (for §4.1) | `loom/db/flyway/src/main/resources/db/migration/` |
| The customer page | [website/content/english/docs/nodes/captioning/index.adoc](../../../../website/content/english/docs/nodes/captioning/index.adoc) |
| Measured latency / quality, and the serve commands | [../../../concept/NODE_VIDEO_CAPTIONING_REPORT.md](../../../concept/NODE_VIDEO_CAPTIONING_REPORT.md) |
| Raw benchmark data + captured run environment | [../video-captioning-results/](../video-captioning-results/) |
| The node system as a whole | [../NODES.md](../NODES.md) |
| The port / content-type model | [../../pipeline/NODE_DATA_TYPES.md](../../pipeline/NODE_DATA_TYPES.md) |
| Rules for building the next node | [../../../guidelines/NEW_NODE.md](../../../guidelines/NEW_NODE.md) |
| The video subsystem and its native requirements | [../../../cortex/SERVICE_VIDEO.md](../../../cortex/SERVICE_VIDEO.md) |

---

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11_
