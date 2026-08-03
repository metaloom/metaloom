# NOTES — scratch backlog

Scratch pad for raw ideas and open questions that do **not yet have a spec file of their own**; once
an item grows teeth it moves into a real spec under `spec/features/…` or a task in
[plans/TASKS.md](plans/TASKS.md) and is deleted here. This file tracks no progress — the linked specs do.

---

## Tasks

- Add a way to visualize the pipeline results in the UI. (e.g. render thumbnails for debugging. Make it possible to play,read,view results.)
- Add way to add trigger points to "halt" processing.
 It should be possible to step thru processing pipeline to debug the processor manually

- Video Manipulation Node: the video half of
  [concept/NODE_IMAGE_MANIPULATION_PLAN.md](../concept/NODE_IMAGE_MANIPULATION_PLAN.md) — autorotate
  by container rotation side-data, crop, aspect ratio fix, VVS (blurred pad for vertical video).
  Should reuse that node's `ManipulationGeometry` and `watermark`'s `FfmpegRunner`; would also close
  watermark's open "rotation/SAR is not handled" item. *(The image half is now specified.)*
- Focalpoint node? — partly answered: the image-manipulation node's `SUBJECT_CROP` frames upstream
  `detection/*` boxes, and an open item there proposes emitting the subject centroid as a
  `focalPoint` output instead of building a second node. Still open for saliency without detections.
- Chapter extraction from video?
- Static code analysis
- code review markdown file with rules
- How about a AI aware agentic supported sync program which automagically syncs assets which are relevant for a user to the client of the user (e.g https://www.lucidlink.com/) - how could this be implemented for loom-app?
- Add way of viewing notifications in the UI (How are notifications tracked in loom?)
- Add semantic ingestion node. This allows constructed semantic data to be ingested into loom
- Add tag node. The node should be able to automatically be able to tag an asset
- Would a merge node be useful to combine assets in a reactive pipeline? (e.g. zip them)


## Open ideas / questions

* **Rework the face workflow.** `FacedetectNode` + `FacedescriptionNode`, the `cluster` /
  `embedding_cluster` tables and the `loom-ui` `ClustersPanel` / `PersonsPanel` all exist, but the
  end-to-end loop *detect → cluster → confirm a cluster is a person* is not specified anywhere and
  has no confirmation endpoint. Review it, rework it, then write it down — today the pieces are
  scattered across [features/pipeline-nodes/NODES.md](features/pipeline-nodes/NODES.md),
  [loom/ui/LOOM_UI.md](loom/ui/LOOM_UI.md) and the migrations. Candidate: a new
  `spec/features/facedetection/` file.

* **Let the agentic loop author pipelines via MCP.** The MCP server exposes read-only pipeline tools
  only (`list_pipelines`, `get_pipeline`). Explore having the agent *design and verify* a pipeline —
  needs `create_pipeline` / `validate_pipeline` tools plus a dry-run/validation path.
  See [loom/MCP.md](loom/MCP.md), [features/pipeline/PIPELINE.md](features/pipeline/PIPELINE.md).

* **llama.cpp and VLM sidecars.** `sidecars/` covers tts, sentiment, depth, imagegen (×2) and
  videogen; the `llm`, `vlm` and `captioning` nodes still call an *external* OpenAI-compatible endpoint.
  Add in-repo sidecars for llama.cpp and a VLM (scratch attempt lives in untracked
  `loom-test-env/llamacpp/`). Two loose ends either way: sidecars are documented only in
  `sidecars/README.md` — nothing on the website — and neither Helm chart has a
  sidecar / `extraContainers` hook.

* **Binary delivery to the frontend.** How do asset bytes and derivatives reach a browser at scale —
  CDN in front of the pool, pre-encoded renditions, signed URLs, range/HLS?
  [features/rest/REST_BINARY_HANDLING.md](features/rest/REST_BINARY_HANDLING.md) stops at
  "stream it through the REST endpoint"; nothing beyond that is specified.

* **Complete the node provenance record.** The `asset_node_result` ledger already carries
  `node_kind` + `node_id` + `producer_version`, which identifies *which node kind* wrote a value but
  not *which worker build*. Still missing, so faulty data cannot be traced back to a worker:
  cortex instance name/version on the row (`cortex_instance` exists but is never joined),
  `run_uuid` / `task_uuid` (columns exist; `NodeResultCreateRequest` has no fields for them), and a
  real `origin` (hard-coded `COMPUTED`).

---

## Known test noise (not regressions)

Mostly cleared 2026-08-01 — what was written off as environmental noise was largely real:

- **`/extra/vid/*` media paths** — gone. The two scene detectors already resolved proper test
  media and then overwrote it with a dead path on the next line; the facedetect tests now use the
  shared test media. `VideoFaceScannerTest` was an interactive scratchpad (Swing viewer plus
  `System.in.read()`) that would have *hung* rather than failed once its video existed.
- **"OpenCV natives not loaded"** — not a test problem. The system moved to FFmpeg 8 while the
  OpenCV build and its `opencv-ffm` wrapper were still linked against FFmpeg 7
  (`libavcodec.so.61`), and the FFmpeg `-dev` packages had been removed, so rebuilding silently
  dropped FFmpeg support altogether. Reinstalling `lib{avcodec,avformat,avutil,swscale,avdevice}-dev`
  and rebuilding opencv `videoio` + `opencv-ffm` fixed thumbnail, fingerprint and
  scene-detection at once. Re-run `opencv-ffm/build.sh` after any system FFmpeg bump.
- **Scene detector tests OOM the module** — `FeatureSceneDetectorTest` / `OpticalFlowSceneDetectorTest`
  call the unbounded `detect()` over a whole video, and surefire reuses one forked JVM per module,
  so the retained native buffers pushed the later `SceneDetectionNodeTest` past the OOM killer
  (exit 137, ~22 GB RSS). They now run against the ~3 MB `video3` instead of the ~17 MB `video2`
  and assert their result. Only visible once the dead `/extra/vid` path stopped failing them
  instantly.
- **No local LLM endpoint** — the llm node tests now run against the llama.cpp server in
  [../loom-test-env/llamacpp](../loom-test-env/llamacpp) and *skip* rather than fail when it is
  not up, matching the `LlmBackendAvailability` pattern in `loom/core`.

- **No local SmolVLM endpoint** — `SmolVLMClientTest` now skips via `SmolVLMAvailability` instead of
  failing, and asserts a non-blank caption rather than printing one. Override with
  `-Dloom.test.smolvlm.host` / `-Dloom.test.smolvlm.port`.

Still genuinely environmental: `xattr` unsupported on some test filesystems.

### ✅ facedetect — resolved 2026-08-02, and it was hiding three separate bugs

The module crashed its forked JVM (exit 134) rather than failing tests: `inspireface4j` shipped a
`libjinspireface.so` linked against Debian's **OpenCV 4.10** while `video4j` → `opencv-ffm` loads
the locally built **OpenCV 5.1**, so the `cv::Mat*` crossing `InspirefaceLib.detect()` was read
with the wrong struct layout (`SIGSEGV in cv::Mat::Mat(cv::Mat const&)`). Fixed upstream by
`inspireface4j@7f13a09` "Bump to inspireface 1.2.3 and OpenCV5" — **remember to `mvn install`
inspireface4j**, the stale jar in `~/.m2` keeps the old native and the crash with it.

Removing the crash exposed two real defects underneath, both of which had made the video face
path return **zero faces no matter what it detected**:

- **`processFaces()` required `face.hasEmbedding()`.** Embeddings were attached by `processFace()`
  through a remote InsightFace HTTP service; that call sits commented out a few lines above and
  nothing replaced it, so no face could ever satisfy the filter. `FacedetectNode` reads only the
  box and the frame index, so the gate tested for data no consumer wants. Removed.
- **`BLUR_THRESHOLD` was 10 and unreachable under OpenCV 5.** Real faces in the test video measure
  2.71–4.24 (median 3.43) mean-absolute-Laplacian. Worse than a filter: `scanWindow()` stops
  scanning a window the moment one frame yields no faces, so an unreachable threshold truncated the
  scan after a single frame. Now 2.0 — **derived from one video and still wants calibration**; the
  sharpest-first sort plus the 10-face cap in `processFaces()` is what actually selects quality.

Two test expectations were wrong as well: `FacedetectNodeTest.testVideo` asserted `faceCount > 10`
against a scanner hard-capped at 10, and the `InspirefaceTest` session asked for attributes and
embeddings without enabling `ENABLE_FACE_ATTRIBUTE` / `ENABLE_FACE_RECOGNITION` (a session opened
without them still detects faces, but returns empty attributes).

`FacedescriptionNodeTest.testProcessImage` needs a local vision model
(`google/gemma-3-27b-it` on an OpenAI-compatible server at 8080) and now skips via
`VisionBackendAvailability` instead of failing on the
`null` that `processFace()` returns after three failed calls.

Module result: 45 tests, 0 failures, 1 skipped.

---

_Git HEAD revision: `4dc0390a`_
_Last updated: 2026-08-03 (notes re-pointed at the OpenAI-compatible provider and the renamed availability guards)_
