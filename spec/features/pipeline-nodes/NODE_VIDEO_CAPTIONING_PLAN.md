# Video Captioning — Status & Remaining Work

> ## 🟢 SHIPPED — as a `videoStrategy` option on the existing `captioning` node
>
> There is **no `video-captioning` node kind and no `cortex/nodes/video-captioning` module.**
> The three variants this plan explored were built, benchmarked (see
> [NODE_VIDEO_CAPTIONING_REPORT.md](NODE_VIDEO_CAPTIONING_REPORT.md)) and then **merged into
> the existing `captioning` node** as one option:
> `videoStrategy` ∈ `WHOLE` (default) | `SCENE` | `NATIVE`.
>
> Kind `captioning` is bound with `@Binds @IntoMap @StringKey("captioning")`; the node declares
> a `xor` input group of `image` | `video` and one `caption` output. Video results land in
> `asset_json_comp` with `schemaType="video-caption"` (images keep `schemaType="caption"`).
>
> **The `CAPTION` `segment_type` migration was never written** — Option B of this plan did not
> ship in its original form. Per-scene captions are carried as a `scenes` array **inside the
> JSON component**, not as `asset_segment_comp` rows. That is the main open item (§3).
>
> Measured latency/quality numbers are in the benchmark report, not here.
> Source of truth is the code under `cortex/`. Node reference: [NODES.md](NODES.md).

---

## 1. Already implemented

| Item | Where it lives |
|---|---|
| Node (image **and** video) | [`CaptioningNode`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/CaptioningNode.java) — `name()="captioning"`, `initialize()` calls `Video4j.init()`, `isProcessable` = `isVideo() \|\| isImage()` |
| Strategy enum | [`VideoCaptioningStrategy`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/VideoCaptioningStrategy.java) — `WHOLE` / `SCENE` / `NATIVE` |
| Strategy implementation | [`VideoCaptioner`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/VideoCaptioner.java) — `captionWhole` / `captionScene` (drives `OpticalFlowSceneDetector`) / `captionNative` |
| Frame sampling | [`FrameSampler`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/FrameSampler.java) — video4j `seekToFrame` + `ImageUtils.toBase64JPG` |
| Video model client | [`VideoVLMClient`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/VideoVLMClient.java) — OpenAI `POST /v1/chat/completions`, multi-image **and** `video_url` parts, bearer token, HTTP/1.1 |
| Image model client | [`SmolVLMClient`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/SmolVLMClient.java) — unchanged bespoke `POST /caption` |
| Result records | [`VideoCaptionOutput`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/VideoCaptionOutput.java) (`caption`, `scenes`, `modelLatencyMs`, `frameCount`) with nested `SceneCaption(seq, fromFrame, toFrame, caption)`; `CaptionResult`, `MultiModalCaption` |
| Typed ports | `IN_IMAGE` (`image`, `MEDIA_IMAGE`) **xor** `IN_VIDEO` (`video`, `MEDIA_VIDEO`) → `OUT_CAPTION` (`caption`, `TEXT_CAPTION`) |
| Options | [`CaptioningNodeOptions`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/CaptioningNodeOptions.java) — see §4 |
| Dagger bindings | [`CaptioningNodeModule`](../../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/CaptioningNodeModule.java) — `@IntoSet`, `@IntoMap @StringKey("captioning")`, `optionInfo()`, both client providers |
| UI descriptor | [`CaptioningDescriptorProvider`](../../../loom-shared/node-model/src/main/java/io/metaloom/loom/nodes/spec/CaptioningDescriptorProvider.java) — `xor("media_alt")` input group ⚠️ **exposes no video parameters, see §3** |
| Persistence — image | `asset_json_comp`, `nodeKind="captioning"`, `schemaType="caption"`, `variant=""` |
| Persistence — video | `asset_json_comp`, `schemaType="video-caption"`, `variant=""`, `data = {caption, variant:<strategy>, model, frameCount, scenes?[]}` + ledger row |
| Skip cache | `LocalResultCache<String>` (10 000) keyed by media path, shared by the image and video paths; hit ⇒ `ResultOrigin.LOCAL`, no re-persist |
| Metrics | `recordAiCall("smolvlm", …)` for images, `recordAiCall("video-vlm", …)` for video |
| Unit tests | [`CaptioningNodeTest`](../../../cortex/nodes/captioning/core/src/test/java/io/metaloom/cortex/node/captioning/CaptioningNodeTest.java) — image, image cache, `testCaptionsVideoWholeStrategy`, `…NativeStrategy`, `…SceneStrategy`, skips audio/document, disabled |
| Options tests | `CaptioningNodeOptionsValidationTest`, `SmolVLMClientTest`, AssertJ helpers |
| Comparison harness | [`VideoCaptioningComparisonIT`](../../../cortex/nodes/captioning/core/src/test/java/io/metaloom/cortex/node/captioning/VideoCaptioningComparisonIT.java) — env-gated, drives all three strategies against live endpoints |
| Integration test | [`CaptioningNodeIntegrationTest`](../../../integration-test/src/test/java/io/metaloom/loom/test/integration/node/CaptioningNodeIntegrationTest.java) — image path against a mock SmolVLM server, video path (`WHOLE`) driving the real frame sampler against the genai `MockLLMServer`; both read back via REST |
| Scene detector fixes | `AbstractSceneDetector` records boundaries with a `minSceneLength` debounce and closes the trailing scene; `OpticalFlowSceneDetector` uses a Canny-enhanced normalized mean frame difference; `SceneBoundaryIT` guards it (details in the benchmark report §6) |
| Customer docs | `website/content/english/docs/nodes/captioning/index.adoc` — documents all three strategies and the video options |
| Spec entries | [NODES.md](NODES.md) §2/§3/§5, [spec/CONTEXT.md](../../CONTEXT.md) |

### Shipped data flow

```mermaid
sequenceDiagram
    participant P as Pipeline
    participant N as CaptioningNode
    participant V as video4j (VideoFile)
    participant M as OpenAI-compatible VLM<br/>(vLLM / llama.cpp)
    participant L as LoomClient

    P->>N: process(ctx[video])
    N->>N: isProcessable → isVideo() || isImage()
    N->>N: LocalResultCache.get(path) — hit? re-emit, LOCAL, skip persist
    N->>V: Videos.open(path)
    alt WHOLE
        N->>V: FrameSampler — frameCount frames evenly across the clip
        N->>M: /v1/chat/completions, multi-image parts
    else SCENE
        N->>N: OpticalFlowSceneDetector.detect(video) → scenes
        loop per scene (≤ maxScenes)
            N->>M: /v1/chat/completions, frames of that scene
        end
    else NATIVE
        N->>M: /v1/chat/completions, video_url part (server samples)
    end
    M-->>N: caption text
    N->>N: ctx.output(OUT_CAPTION); cache.put(path, caption)
    N->>L: createAssetJsonComp(schemaType="video-caption", data{caption, variant, model, frameCount, scenes?})
    N->>L: recordNodeResult(SUCCESS, resultRef("asset_json_comp", uuid))
```

---

## 2. Model & serving choices (decided)

Decided by measurement — see [NODE_VIDEO_CAPTIONING_REPORT.md](NODE_VIDEO_CAPTIONING_REPORT.md)
for the runs behind these.

- **Default: Qwen2.5-VL-7B-Instruct-AWQ on vLLM**, `videoStrategy=WHOLE`. Apache-2.0, genuinely
  temporal (dynamic-FPS + absolute-time MRoPE), fixed frame budget so it works on any clip length.
- **Portable fallback: Qwen2.5-VL-7B GGUF Q4_K_M on llama.cpp** — same node, different
  `videoEndpointUrl`; ~2.8× slower and more repetitive in wording, but no Python and fits 12 GB.
- **`NATIVE` is opt-in for short clips only** — vLLM-only (llama.cpp returns HTTP 400 for
  `video_url`) and it overruns small context windows.
- **Upgrade path: Qwen3-VL** for native per-event timestamp tokens and a much larger context;
  **Tarsier2-7B** for max caption detail — both are Qwen2-VL-arch checkpoints, so both are a
  `videoModel` id swap, not code. `MiniCPM-V 2.6` is the low-VRAM alternative.
- **Avoid Llama-3.2-Vision** — its cross-attention architecture is unsupported in llama.cpp.

### Hardware sizing (reference)

The node targets an external OpenAI-compatible endpoint, so serving is a deployment concern.
On a heterogeneous 4090 (24 GB) + 3060 (12 GB) box: **vLLM cannot tensor-parallel across them**
(uniform per-GPU memory fraction caps at the smaller card) — pin vLLM to the 4090; llama.cpp
*can* use both via `--split-mode layer --tensor-split 24,12`. Video captioning is
**headroom-bound, not parameter-bound**: a 512×384 frame is ~250–350 visual tokens for
Qwen2.5-VL, so 16–32 frames is ~4k–8k tokens before the prompt. Rule of thumb: pick a quant that
leaves **≥10 GB free** after weights. Serve commands actually used are in the report §9.

---

## 3. Open work

### 3.1 Per-scene captions are not in `asset_segment_comp`

Option B of the original plan — persisting one `SegmentEntry` per captioned scene with
`segment_type=CAPTION` — **was never implemented.** Verified: `grep -rn CAPTION` over
`loom/db/flyway/src/main/resources/db/migration/` returns nothing, so the CHECK constraint on
`asset_segment_comp.segment_type` is still `SCENE, SILENCE, SHOT, CHAPTER`. `SCENE` captions are
instead carried as a `scenes` array inside the `video-caption` JSON component, using **frame
indices** (`fromFrame`/`toFrame`), not milliseconds.

To finish it:

1. New migration `loom/db/flyway/src/main/resources/db/migration/V2.xx__add_caption_segment_type.sql`
   adding `CAPTION` to the CHECK constraint.
2. Re-run `./setup-pool.sh` **and** `loom/db/jooq/generate.sh` — a Flyway change without both
   leaves the pooled test databases stale and the suite fails (see [.claude/CLAUDE.md](../../../.claude/CLAUDE.md)).
   Install `loom/db/flyway` first, or `setup-pool.sh` prints "Pool Created" while silently skipping
   the new migration.
3. `createAssetSegmentComps(segmentType="CAPTION", entries[title=caption, timeFrom, timeTo])` —
   whole-set replace on `(asset, node_kind, segment_type)`, mirroring `SceneDetectionNode`.
   Decide first whether to store frames or milliseconds: `SceneDetectionNode` stores **frame
   indices** and carries the source fps in `producerVersion` (e.g. `"fps=25.0"`); a caption timeline
   should be consistent with that choice.
4. Extend `CaptioningNodeIntegrationTest` with a `SCENE`-strategy case that reads the segments back
   via REST.

### 3.2 The descriptor exposes none of the video options

`CaptioningDescriptorProvider` declares only `enabled`, `processIncomplete` and `retryFailed`.
None of `videoStrategy`, `videoEndpointUrl`, `videoModel`, `videoApiKey`, `frameCount`,
`targetFrameSize`, `maxScenes`, `maxTokens`, `temperature`, `videoPrompt` is a `NodeParameter`,
so **the UI cannot configure video captioning at all** — it can only be set in the Cortex options
file. The website page already documents these options, which makes the gap a visible
inconsistency. Adding them is descriptor-only work; ports are unaffected, so
`NodePortConformanceTest` is not at risk.

### 3.3 Smaller items

- **`NATIVE` needs token capping.** It fails with `Input length (12461) exceeds model's maximum
  context length (8192)` on anything past a few seconds. Either expose `fps` / `max_frames`
  `mm_processor_kwargs` through the options, or document a required `--max-model-len`.
- **`compute()` swallows failures.** The video/image dispatch catches `Exception`, calls
  `e.printStackTrace()` and returns `NodeResult.failed()` without a ledger row — unlike
  `persistVideo`, which does record a FAILED ledger entry. Worth aligning with the rest of the tree.
- **`SCENE` degenerates to whole-video on single-shot content** (correctly — the detector yields
  one scene), so it is only worth enabling for edited/multi-shot material.
- **Audio fusion (not started).** Feed an upstream Whisper transcript into the caption prompt for
  audio-aware descriptions. With the typed-port model this is a new `text` input port on the node
  plus a prompt template change.
- **Node kind naming.** `captioning`'s display name in the descriptor is still *"Image Captioning"*
  even though it captions video too.

---

## 4. Options (the `captioning` block)

| Option | Default | Applies to | Meaning |
|---|---|---|---|
| `enabled` / `processIncomplete` / `retryFailed` | `true` / `false` / `false` | both | Standard `AbstractNodeOptions` flags |
| `smolVLMHost` | `localhost` | image | SmolVLM captioning service host |
| `smolVLMPort` | `8000` | image | SmolVLM captioning service port |
| `videoStrategy` | `WHOLE` | video | `WHOLE` \| `SCENE` \| `NATIVE` |
| `videoEndpointUrl` | `http://localhost:8000` | video | Base URL of the OpenAI-compatible VLM endpoint |
| `videoModel` | `qwen25vl-awq` | video | Model id served at that endpoint |
| `videoApiKey` | `""` | video | Optional bearer token |
| `frameCount` | `8` | video | Frames sampled for `WHOLE`; per scene for `SCENE` |
| `targetFrameSize` | `512` | both | Longest edge of the encoded frame in px |
| `maxScenes` | `32` | video (`SCENE`) | Upper bound on captioned scenes |
| `maxTokens` | `256` | video | Generation limit |
| `temperature` | `0.2` | video | Sampling temperature |
| `videoPrompt` | *"Describe what happens in this video in two or three sentences. Focus on actions, subjects and setting."* | video | Prompt sent with the frames |

The node itself reads **no environment variables**; the backends do (see the report §9 for the
`vllm serve` / `llama-server` invocations used).

---

## 5. Key Classes Reference

| Class | Package / module | Purpose |
|---|---|---|
| `CaptioningNode` | `io.metaloom.cortex.node.captioning` (`cortex/nodes/captioning/core`) | The merged image + video captioning node |
| `VideoCaptioner` | same | Executes the selected `VideoCaptioningStrategy` |
| `VideoCaptioningStrategy` | same | `WHOLE` / `SCENE` / `NATIVE` enum |
| `FrameSampler` | same | video4j frame sampling → base64 JPEG |
| `VideoVLMClient` | same | OpenAI-compatible `/v1/chat/completions`, multi-image + `video_url` |
| `SmolVLMClient` | same | Bespoke single-image `/caption` client (image path) |
| `VideoCaptionOutput` | same | `caption`, `scenes`, `modelLatencyMs`, `frameCount` |
| `CaptioningNodeOptions` | same | All options in §4 |
| `CaptioningNodeModule` | same | Dagger bindings incl. `@StringKey("captioning")` |
| `CaptioningDescriptorProvider` | `io.metaloom.loom.nodes.spec` (`loom-shared/node-model`) | UI descriptor, `xor` media input group |
| `OpticalFlowSceneDetector` | `io.metaloom.cortex.node.scene.impl` (`cortex/nodes/scene-detection/core`) | Scene segmentation used by the `SCENE` strategy |
| `AbstractMediaNode` | `io.metaloom.cortex.common.node` (`cortex/common`) | Lifecycle + `recordNodeResult` / `resultRef` |
| `JsonCompCreateRequest` | `io.metaloom.loom.rest.model.jsoncomp` (`loom-shared/rest-model`) | `asset_json_comp` payload |

---

## 6. Conventions and Gotchas

- **There is no `video-captioning` kind.** Anything referring to `video-captioning-whole/-scene/-native`
  or `cortex/nodes/video-captioning` predates the merge and is stale.
- **`image` and `video` are a `xor` port group.** Wire exactly one; wiring both is a validation error.
- **Image and video share one `LocalResultCache` and one cache key (the media path)**, but persist to
  *different* `schemaType`s (`caption` vs `video-caption`).
- **`variant` is `""` on both rows.** The strategy name is stored *inside* the JSON payload as a
  `variant` field — that is data, not the component's natural-key discriminator.
- **Scene bounds are frame indices, not milliseconds.** Both here and in `SceneDetectionNode`; the
  fps needed to convert is carried in `producerVersion`.
- **llama.cpp cannot serve `NATIVE`** — it does not decode video files and returns HTTP 400 for
  `video_url` (SmolVLM2 is the sole exception in that family, and its caption quality is far lower).
- **vLLM cannot tensor-parallel a 4090 + 3060.** Pin it to the larger card.
- **`Video4j.init()` runs in `initialize()`**, and video tests must be guarded with `assumeVideo4j()`.
- **A Flyway change means `./setup-pool.sh` *and* `loom/db/jooq/generate.sh`** — relevant the moment
  §3.1 is picked up.
- **The code is the source of truth.** Where this document and `cortex/` disagree, the code wins —
  fix this file in the same change ([SPEC_RULES.md](../../SPEC_RULES.md)).

---

## 7. Where do I find …?

| Concept | Path |
|---|---|
| Measured latency / quality numbers | [NODE_VIDEO_CAPTIONING_REPORT.md](NODE_VIDEO_CAPTIONING_REPORT.md) |
| Raw benchmark data | [video-captioning-results/](video-captioning-results/) |
| Node system reference | [NODES.md](NODES.md) |
| Typed port / content-type model | [../pipeline/NODE_DATA_TYPES.md](../pipeline/NODE_DATA_TYPES.md) |
| New-node checklist | [../../guidelines/NEW_NODE.md](../../guidelines/NEW_NODE.md) |
| Definition of done for a code change | [../../guidelines/CODING.md](../../guidelines/CODING.md) |
| Node implementation | `cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/` |
| Scene detector | `cortex/nodes/scene-detection/core/src/main/java/io/metaloom/cortex/node/scene/` |
| UI descriptor | `loom-shared/node-model/src/main/java/io/metaloom/loom/nodes/spec/CaptioningDescriptorProvider.java` |
| Unit tests + comparison harness | `cortex/nodes/captioning/core/src/test/java/io/metaloom/cortex/node/captioning/` |
| Integration test | `integration-test/src/test/java/io/metaloom/loom/test/integration/node/CaptioningNodeIntegrationTest.java` |
| Flyway migrations | `loom/db/flyway/src/main/resources/db/migration/` |
| Customer-facing node docs | `website/content/english/docs/nodes/captioning/index.adoc` |

---

## 8. Progress Assessment

**Design decisions — settled**

- [x] Granularity: ship whole-video first, scene second (original Option C)
- [x] Node placement: **merged into `captioning`** as a `videoStrategy` option, not a separate kind
- [x] Model tier: Qwen2.5-VL-7B-AWQ on vLLM as default, llama.cpp Q4 as portable fallback
- [x] Backend is pure config (`videoEndpointUrl` + `videoModel`), so model swaps are not code changes

**Implementation — shipped**

- [x] `VideoCaptioningStrategy` (`WHOLE` / `SCENE` / `NATIVE`) + `VideoCaptioner`
- [x] `VideoVLMClient` (multi-image **and** `video_url`), `FrameSampler`, `VideoCaptionOutput`
- [x] `video` input port on `CaptioningNode`, `xor` group with `image`
- [x] Video options on `CaptioningNodeOptions` (§4)
- [x] `asset_json_comp` persistence as `schemaType="video-caption"` + ledger row
- [x] Scene detector defects fixed (boundaries recorded, frame-difference cut signal, tail handling)
- [x] Website node page documenting all three strategies

**Tests**

- [x] `CaptioningNodeTest` covers all three video strategies + image + skips + disabled
- [x] `CaptioningNodeIntegrationTest` video path (`WHOLE`) against the mock OpenAI server, read back via REST
- [x] `VideoCaptioningComparisonIT` env-gated harness across all three strategies
- [x] `SceneBoundaryIT` guards multi-scene detection
- [ ] Integration coverage for the `SCENE` strategy's persisted `scenes` array

**Open**

- [ ] **`CAPTION` `segment_type` migration + `createAssetSegmentComps` persistence** (§3.1) — never written
- [ ] **Expose the video options as `NodeParameter`s in `CaptioningDescriptorProvider`** (§3.2) — the UI cannot configure video captioning today
- [ ] Cap `NATIVE` token usage via `fps` / `max_frames`, or document a required `--max-model-len` (§3.3)
- [ ] Record a ledger row when `compute()` fails instead of `printStackTrace()` + `NodeResult.failed()` (§3.3)
- [ ] Audio fusion — Whisper transcript as an extra text input to the caption prompt (§3.3)
- [ ] Rename the descriptor's display name away from "Image Captioning" (§3.3)

---

## 9. References

- Qwen2.5-VL — <https://qwenlm.github.io/blog/qwen2.5-vl/> · Qwen3-VL — <https://github.com/QwenLM/Qwen3-VL>
- Tarsier2 — <https://github.com/bytedance/tarsier> · <https://arxiv.org/abs/2501.07888>
- MiniCPM-V — <https://github.com/OpenBMB/MiniCPM-V>
- VideoLLaMA3 — <https://github.com/DAMO-NLP-SG/VideoLLaMA3>
- Grounded-VideoLLM — <https://github.com/WHB139426/Grounded-Video-LLM> · VTG-LLM — <https://github.com/gyxxyg/VTG-LLM>
- vLLM multimodal serving — <https://docs.vllm.ai/en/stable/examples/online_serving/openai_chat_completion_client_for_multimodal/>

---

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (verified shipped against code; reduced to a status + open-work document — the node is `captioning`'s `videoStrategy`, and the `CAPTION` segment-type migration was never written)_
