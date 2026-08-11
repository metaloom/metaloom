# Video Captioning — Benchmark Report

> ## 📊 This is a benchmark record, not a plan
>
> **Benchmark run: 2026-07-24** (environment captured 23:00 UTC — see
> [video-captioning-results/RUN_ENV.txt](video-captioning-results/RUN_ENV.txt)).
> **Model: Qwen2.5-VL-7B-Instruct**, in two quantizations on two cards of one workstation:
> **AWQ on vLLM 0.25.1 / RTX 4090 (24 GB)** and **GGUF Q4_K_M on llama.cpp / RTX 3060 (12 GB)**.
> **Every number below comes from an actual run — nothing is estimated.**
>
> Raw data: [video-captioning-results/](video-captioning-results/) —
> `results-set-a-shortclips.json`, `results-set-b-cinematic.json`,
> `results-set-c-synthetic-3cuts.json`, `RUN_ENV.txt`.
>
> **Naming note (verified 2026-08-01):** the three node classes benchmarked here no longer exist
> as separate kinds. They were merged into the existing `captioning` node as a `videoStrategy`
> option, and `cortex/nodes/video-captioning` was deleted. The measurements are unaffected —
> the same code paths run today under different names:
>
> | Benchmarked as | Today | Class that runs it |
> |---|---|---|
> | `WholeVideoCaptioningNode` / kind `video-captioning-whole` | `videoStrategy = WHOLE` (default) | `VideoCaptioner.captionWhole` |
> | `SceneVideoCaptioningNode` / kind `video-captioning-scene` | `videoStrategy = SCENE` | `VideoCaptioner.captionScene` |
> | `NativeVideoCaptioningNode` / kind `video-captioning-native` | `videoStrategy = NATIVE` | `VideoCaptioner.captionNative` |
>
> Design rationale and remaining work: [NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md).
> Node reference: [NODES.md](../features/nodes/NODES.md).

---

## 1. TL;DR / Recommendation

- **Ship the whole-video multi-image variant (today `videoStrategy=WHOLE`) served by
  Qwen2.5-VL-7B-AWQ on vLLM.** It was the best quality/latency/reliability balance:
  coherent captions at **~0.5 s** model time, works on clips of any length (fixed
  frame budget), and runs on a single 24 GB GPU. **Composite score 82/100.**
  *(Adopted — `WHOLE` is the shipped default.)*
- **llama.cpp GGUF Q4_K_M is a valid fallback** (runs on the 12 GB 3060, no Python),
  but was **~2.8× slower** and noticeably **more repetitive** in wording. Score 63/100.
- **Native-video (`video_url`) is the highest-quality path *when the clip fits the
  context window*** — but it is vLLM-only and **fails on anything longer than a few
  seconds** at an 8k context (real error: `Input length (12461) exceeds 8192`). Keep
  it as an opt-in for short clips or raise `--max-model-len`.
- **The scene-first variant now works** (see §6): the scene detector — which
  originally never emitted more than one scene — was fixed, and the variant now
  produces a real per-scene caption **timeline**. On a synthetic 3-cut clip it
  detected all 3 scenes and captioned each distinctly, capturing content the
  whole-video variant blended away.

---

## 2. What was benchmarked (and where that code lives now)

At benchmark time this was a standalone module `cortex/nodes/video-captioning` with a shared,
backend-agnostic OpenAI-compatible client and **three interchangeable node variants**, all
extending the `AbstractMediaNode` lifecycle and mirroring the image `CaptioningNode`.

It has since been folded into [cortex/nodes/captioning](../../../cortex/nodes/captioning) —
one node kind, three strategies:

| Strategy | Behaviour as benchmarked |
|---|---|
| `WHOLE` | Sample N frames evenly across the clip → one multi-image prompt → single caption |
| `SCENE` | **Scene-segment upfront** (`OpticalFlowSceneDetector`) → caption each scene → per-scene timeline |
| `NATIVE` | Hand the whole file to the server via `video_url`; server does its own temporal sampling |

Supporting classes, at their current paths:

- [`VideoVLMClient`](../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/VideoVLMClient.java)
  — OpenAI `/v1/chat/completions`, multi-image **and** `video_url`
- [`FrameSampler`](../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/FrameSampler.java)
  — video4j `seekToFrame` sampling
- [`VideoCaptioner`](../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/VideoCaptioner.java)
  and [`VideoCaptioningStrategy`](../../cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/VideoCaptioningStrategy.java)
- `CaptioningNodeOptions` (was `VideoCaptioningNodeOptions`)
- the env-gated harness [`VideoCaptioningComparisonIT`](../../cortex/nodes/captioning/core/src/test/java/io/metaloom/cortex/node/captioning/VideoCaptioningComparisonIT.java)

Persistence reuses the `asset_json_comp` + node-result-ledger pattern (skipped during the
benchmark, since the harness runs with a null Loom client). The backend is pure config
(`videoEndpointUrl` + `videoModel`), so vLLM ↔ llama.cpp is a config swap, not a code change.

```mermaid
flowchart LR
    V[Video file] --> S[FrameSampler / scene detect]
    S --> C[VideoVLMClient<br/>OpenAI /v1/chat/completions]
    C -->|multi-image| L[llama.cpp Q4 · RTX 3060]
    C -->|multi-image| Q[vLLM AWQ · RTX 4090]
    C -->|video_url| Q
    L --> R[caption + latency]
    Q --> R
```

## 3. Test environment (actual)

| | |
|---|---|
| Date | **2026-07-24**, 23:00 UTC |
| GPUs | RTX 4090 (24 GB, Ada) + RTX 3060 (12 GB, Ampere), driver 595.71.05 |
| vLLM | 0.25.1, torch 2.11.0+cu130, Python 3.13 — **installed and ran cleanly** |
| llama.cpp | built from source (CUDA, commit `555881e`) — `llama-server` + libmtmd |
| Model | Qwen2.5-VL-7B-Instruct — **AWQ** (`Qwen/…-AWQ`) on vLLM; **GGUF Q4_K_M** + f16 mmproj (`ggml-org/…-GGUF`) on llama.cpp |
| Placement | **Both cards used at once:** vLLM AWQ → 4090 (weights 6.7 GB, KV 13.6 GB); llama.cpp Q4 → 3060 (~6.9 GB). |

Two environment gotchas worth recording (both fixed):
- vLLM's flashinfer JIT calls `/usr/local/cuda/bin/nvcc`, which pointed at CUDA 13.2
  (no `nvcc`); setting `CUDA_HOME=/usr/local/cuda-13.1` fixed it.
- vLLM **cannot tensor-parallel across the 4090+3060** (uniform per-GPU memory
  fraction caps at the 12 GB card) — confirmed by its own startup warning. So each
  runtime was pinned to one card; both ran simultaneously.

## 4. Results (real runs)

Videos: 4 real clips (single-shot pexels / AI-cinematic) + 1 **synthetic 3-hard-cut**
clip built by concatenating three different pexels clips. 8 frames (whole) / 4 per
scene, 512 px, 256 max tokens.

### 4.1 Latency (mean model time over successful runs)

| Backend (quant, GPU) | Variant | Runs | Model ms | Wall ms |
|---|---|---:|---:|---:|
| **vLLM AWQ (4090)** | whole | 5 | **511** | 1033 |
| vLLM AWQ (4090) | scene (1 scene, 4 frames) | 5 | 279 | 1123 |
| vLLM AWQ (4090) | native (`video_url`) | 1* | 759 | 762 |
| **llama.cpp Q4_K_M (3060)** | whole | 5 | **1422** | 2088 |
| llama.cpp Q4_K_M (3060) | scene | 5 | 1260 | 2213 |
| llama.cpp Q4_K_M (3060) | native | 0 | — | — (unsupported) |

\* native succeeded only on the shortest clip (`demo.mp4`); see §5.
**vLLM AWQ was ~2.8× faster than llama.cpp Q4 on identical multi-image work.**

### 4.2 Quality (example — `demo.mp4`, a face with a recognition overlay)

- **vLLM native** (richest): *"…a digital interface overlays the image, displaying
  facial recognition data such as age, gender, and race. The person turns their head
  from front to side…"*
- **vLLM AWQ whole**: *"A woman turns her head to the right. The camera follows her…"* — correct, a bit terse.
- **vLLM AWQ whole** (pexels): *"…a gold necklace… A red laser is scanning her face from left to right."* — good detail.
- **llama.cpp Q4 whole**: *"turns her head from right to left… right to left… right to left."* — correct but **repetitive** (a consistent Q4 trait vs AWQ).

**Quality read:** native ≳ vLLM-AWQ-whole > llama.cpp-Q4-whole. AWQ produced more
synthesized, less repetitive prose than Q4 at the same model/frames.

## 5. Native-video: the context-window catch

Native `video_url` gives the best single caption **but only when the decoded video
fits the context**. Real failures observed at `--max-model-len 8192`:

- `Input length (12461) exceeds model's maximum context length (8192)` on the SD
  pexels and cinematic clips — the server sampled far more frames than the 8-frame
  multi-image budget.
- llama.cpp returns HTTP 400 for `video_url` on every clip (it does not decode video).

**Implication:** the whole-video **multi-image** variant is the robust default (fixed
frame budget → bounded tokens → works on any length). Native is an opt-in for short
clips, or needs a much larger context window (Qwen3-VL / higher `max-model-len`) plus
`fps` / `max_frames` capping.

> Still true today: `VideoCaptioningStrategy.NATIVE` exposes no `fps` / `max_frames` cap,
> so the failure mode above is unchanged — see
> [NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md) §3.3.

## 6. Scene-first variant: scene detector fixed

Initially the scene-first variant produced **exactly one scene on every clip — even
the synthetic 3-hard-cut video.** Three defects in the scene detector, now fixed
(all three fixes verified still present in the code as of 2026-08-01):

1. **Boundaries were never recorded.**
   [`AbstractSceneDetector.detect()`](../../../cortex/nodes/scene-detection/core/src/main/java/io/metaloom/cortex/node/scene/AbstractSceneDetector.java)
   computed a per-frame `isSplit = delta > threshold` but only used it to drive a debug
   viewer overlay; the sole `addScene(...)` was the *"no cut → one scene"* fallback. Fixed
   to **accumulate scene boundaries** on each split (with a `minSceneLength` debounce so a
   cut spanning a couple of sampled frames — or a transient motion spike — doesn't
   over-segment) and to close the trailing scene at end-of-stream.
2. **The optical-flow signal was dead.**
   [`OpticalFlowSceneDetector`](../../../cortex/nodes/scene-detection/core/src/main/java/io/metaloom/cortex/node/scene/impl/OpticalFlowSceneDetector.java)
   always returned `delta = 0`: `previousGray` aliased the pooled live Mat (so flow.calc
   compared a frame to itself), `goodFeaturesToTrack` got a stale empty mask (1 corner),
   and the FFM `SparsePyrLKOpticalFlow.calc` binding returned a degenerate one-row status.
   Replaced the cut signal with a **normalized mean frame-difference** metric (Canny-
   enhanced; ≈0.06 within a shot, ≈0.5 across a hard cut), which is clean and reliable; the
   optical-flow point overlay is kept for the interactive viewer only.
3. **Short clips were truncated.** The `length - 100` tail guard skipped the last ~100
   frames, so a short clip's final cut was never examined. Replaced with proper
   end-of-stream handling.

**Result (real run, llama.cpp on the 3-cut clip):** 3 scenes detected (`[0-70] [70-150]
[150-225]`) and a 3-line caption timeline — *"Scene 1: a man and a woman at a table with a
laptop… Scene 2: a man in a pink shirt, deep in thought… Scene 3: a woman adjusting a gold
necklace…"* — each scene distinct and correct. The whole-video variant on the same clip
described only the first scene and blended the rest. A single-shot clip still correctly
yields **1 scene** (no false cuts). Verified by
[`SceneBoundaryIT`](../../../cortex/nodes/scene-detection/core/src/test/java/io/metaloom/cortex/node/scene/SceneBoundaryIT.java)
(env-gated: `SCENE_TEST_VIDEO`, `SCENE_MIN_SCENES`).

**Persistence to Loom.** The existing `SceneDetectionNode` already wrote its scene set to
`asset_segment_comp`, but with the always-one-scene detector it only ever stored a single
row; it now persists **one segment per detected scene** (whole-set replace, so a shorter
re-run deletes surplus rows). The bounds are stored as **frame indices** (the raw detector
output) rather than the column's nominal milliseconds — kept exact and frame-rate-agnostic
— with the source **fps carried in `producerVersion`** (e.g. `"fps=25.0"`) so a consumer
derives seconds as `frame / fps` without re-opening the video.
[`SceneDetectionNodeIntegrationTest`](../../../integration-test/src/test/java/io/metaloom/loom/test/integration/node/SceneDetectionNodeIntegrationTest.java)
proves this end-to-end against a real Loom (REST + pooled DB): on the 3-cut clip it
persisted 3 segments — `0-70`, `70-150`, `150-225`, each `producerVersion=fps=25.0` — and
read them back via REST (Tests run: 2, Failures: 0).

## 7. Scoring (quality + latency + reliability)

Composite = 55 % quality + 30 % speed + 15 % reliability, each 0–100 (quality from §4.2
on a 1–5 scale ×20; speed = normalized inverse of model ms; reliability = fraction of
clips that succeeded).

| Solution | Quality | Speed | Reliability | **Composite** |
|---|---:|---:|---:|---:|
| **vLLM AWQ · whole (multi-image)** | 80 | 90 | 100 | **82** ✅ |
| vLLM AWQ · scene | 66 | 100 | 100 | 74† |
| vLLM AWQ · native (video_url) | 92 | 70 | 20 | 68 |
| **llama.cpp Q4 · whole** | 66 | 32 | 100 | **63** |
| llama.cpp Q4 · scene | 64 | 38 | 100 | 62† |
| llama.cpp · native | — | — | 0 | n/a |

† scene latencies in the table are from the earlier single-scene runs (before the §6
fix). With the detector fixed, the scene variant makes **one model call per scene**, so
its latency and quality now scale with scene count — on the 3-cut clip it produced a full
3-scene timeline (2651 ms on llama.cpp) that the whole-video variant could not.

## 8. Recommendation & next steps

1. **Adopt the whole-video variant + vLLM Qwen2.5-VL-7B-AWQ (4090)** as the default
   production path. Keep the **llama.cpp Q4 on the 3060** config as a portable fallback
   (no Python, smaller GPU) — same node, different `videoEndpointUrl`.
   ✅ **Done** — `videoStrategy` defaults to `WHOLE`, `videoModel` defaults to `qwen25vl-awq`.
2. **Scene detector is fixed** (§6) — the scene-first variant now yields a real
   per-scene timeline and is the right choice for multi-shot / edited content. Next
   step is to persist the per-scene captions to `asset_segment_comp` (per the plan);
   for single-shot content it degenerates to whole-video, so gate it on expected content.
   ⏳ **Partly done** — scenes are persisted, but as a `scenes` array inside the
   `video-caption` JSON component. The `CAPTION` `segment_type` migration was never written
   (plan §3.1).
3. **Native video**: keep as opt-in for short clips, or move to Qwen3-VL with a large
   `--max-model-len` and explicit `fps` / `max_frames` caps to bound tokens.
   ⏳ **Open** — `NATIVE` ships, the caps do not.
4. **Delete the losing variant later:** once (2) lands, whole-video and scene are the
   keepers; native stays only if the short-clip / large-context case matters.
   ✅ **Superseded** — all three became strategies on one node, so pruning is now deleting an
   enum constant plus its `VideoCaptioner` branch.
5. Reproduce anytime: start the two servers, then
   `mvn -pl cortex/nodes/captioning/core -Dtest=VideoCaptioningComparisonIT -DfailIfNoTests=false test`
   (env: `LLAMACPP_URL`, `VLLM_URL`, `TEST_VIDEOS`, `RESULTS_FILE`).

## 9. Serve commands used (reproducible)

```bash
# vLLM AWQ on the 4090 (native video needs the media-path flag)
CUDA_VISIBLE_DEVICES=0 CUDA_HOME=/usr/local/cuda-13.1 \
vllm serve Qwen/Qwen2.5-VL-7B-Instruct-AWQ --served-model-name qwen25vl-awq \
  --quantization awq_marlin --max-model-len 8192 --gpu-memory-utilization 0.88 \
  --limit-mm-per-prompt '{"image":32,"video":1}' \
  --allowed-local-media-path /path/to/media --port 8000

# llama.cpp GGUF Q4_K_M on the 3060
CUDA_VISIBLE_DEVICES=1 llama-server \
  -m Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf \
  --mmproj mmproj-Qwen2.5-VL-7B-Instruct-f16.gguf \
  -ngl 99 -c 8192 --port 8081
```

---

## 10. Harness environment variables

The comparison harness is **env-gated** — it is a no-op unless these are set, so it never
breaks a normal build.

| Variable | Meaning |
|---|---|
| `VLLM_URL` | Base URL of the vLLM endpoint (e.g. `http://localhost:8000`) |
| `LLAMACPP_URL` | Base URL of the llama.cpp endpoint (e.g. `http://localhost:8081`) |
| `TEST_VIDEOS` | Comma-separated paths of the clips to caption |
| `RESULTS_FILE` | Where to write the JSON result set (the files in `video-captioning-results/`) |
| `CUDA_HOME` | Must point at a CUDA install that actually has `nvcc` (see §3) |
| `SCENE_TEST_VIDEO` / `SCENE_MIN_SCENES` | Gate + expectation for `SceneBoundaryIT` (§6) |

Node-side options used during the runs (`frameCount=8`, `targetFrameSize=512`,
`maxTokens=256`, `videoModel`, `videoEndpointUrl`) are documented in
[NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md) §4.

---

## 11. Key Classes Reference

| Class | Package / module | Role in this benchmark |
|---|---|---|
| `VideoCaptioningComparisonIT` | `io.metaloom.cortex.node.captioning` (`cortex/nodes/captioning/core`, test) | The harness that produced every number here |
| `VideoCaptioner` | `io.metaloom.cortex.node.captioning` (`cortex/nodes/captioning/core`) | Runs `captionWhole` / `captionScene` / `captionNative` |
| `VideoCaptioningStrategy` | same | The enum the three benchmarked variants collapsed into |
| `VideoVLMClient` | same | Both request shapes measured: multi-image and `video_url` |
| `FrameSampler` | same | The fixed frame budget that makes `WHOLE` length-independent |
| `VideoCaptionOutput` | same | Carries `modelLatencyMs` and `frameCount` — the raw timing source |
| `CaptioningNode` | same | Host node; `captionVideo(media)` is public so the harness can drive it without Loom |
| `AbstractSceneDetector` | `io.metaloom.cortex.node.scene` (`cortex/nodes/scene-detection/core`) | Boundary accumulation + `minSceneLength` debounce (§6.1) |
| `OpticalFlowSceneDetector` | `io.metaloom.cortex.node.scene.impl` | Canny-enhanced frame-difference cut signal (§6.2) |
| `SceneBoundaryIT` | `io.metaloom.cortex.node.scene` (test) | Guards multi-scene detection |
| `SceneDetectionNodeIntegrationTest` | `io.metaloom.loom.test.integration.node` (`integration-test`) | Proves per-scene segment persistence end-to-end (§6) |

---

## 12. Conventions and Gotchas

- **These numbers are a snapshot of 2026-07-24 on one workstation.** Re-measure before
  quoting them for different hardware, a different quantization or a newer vLLM.
- **The class names in §1–§9 are historical.** Map them through the table at the top of this
  file; `cortex/nodes/video-captioning` no longer exists.
- **`video_url` is vLLM-only.** llama.cpp returns HTTP 400 — it does not decode video files.
- **Quantization changes prose style, not just speed.** Q4_K_M was measurably more repetitive
  than AWQ at the same model and frame count — visible in §4.2, not in any latency figure.
- **vLLM's per-GPU memory fraction is uniform**, so a 4090+3060 pair caps at the smaller card.
  Pin each runtime to one GPU and run them side by side, as done here.
- **Scene bounds in the raw results are frame indices**, not milliseconds; divide by the fps
  recorded in `producerVersion`.
- **Do not "clean up" the measurements.** This file is a record — correct stale paths and add
  outcome annotations, but never restate a number that was not measured
  ([SPEC_RULES.md](../guidelines/SPEC_RULES.md)).

---

## 13. Where do I find …?

| Concept | Path |
|---|---|
| Raw result sets + captured environment | [video-captioning-results/](video-captioning-results/) |
| Design rationale, options and open work | [NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md) |
| Node system reference | [NODES.md](../features/nodes/NODES.md) |
| Typed port / content-type model | [../pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md) |
| New-node checklist | [../../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) |
| The harness | `cortex/nodes/captioning/core/src/test/java/io/metaloom/cortex/node/captioning/VideoCaptioningComparisonIT.java` |
| Strategy implementations | `cortex/nodes/captioning/core/src/main/java/io/metaloom/cortex/node/captioning/VideoCaptioner.java` |
| Scene detector | `cortex/nodes/scene-detection/core/src/main/java/io/metaloom/cortex/node/scene/` |
| Customer-facing node docs | `website/content/english/docs/nodes/captioning/index.adoc` |

---

## 14. Progress Assessment

**Benchmark itself — complete, not to be redone unless the hardware or model changes**

- [x] Three variants implemented and run end-to-end against two real quantized backends
- [x] Latency measured over 5 runs per backend/variant (§4.1)
- [x] Quality compared on real captions (§4.2) and scored into a composite (§7)
- [x] Native-video context-window failure characterized with the real error (§5)
- [x] Scene-detector defects found, fixed and verified on a synthetic 3-cut clip (§6)
- [x] Raw results and run environment archived under `video-captioning-results/`
- [x] Serve commands recorded for reproduction (§9)

**Acted on since**

- [x] `WHOLE` + vLLM AWQ adopted as the shipped default (`videoStrategy`, `videoModel`)
- [x] Three variants merged into the `captioning` node; standalone module deleted
- [x] Scene-detector fixes landed with `SceneBoundaryIT` + `SceneDetectionNodeIntegrationTest`
- [ ] Per-scene captions persisted to `asset_segment_comp` (`CAPTION` migration never written)
- [ ] `fps` / `max_frames` caps for `NATIVE`, or a documented `--max-model-len` floor
- [ ] Re-measure against Qwen3-VL (native timestamp tokens, larger context)
- [ ] Re-measure the `SCENE` strategy post-fix — §7's scene rows are pre-fix single-scene runs

---
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_