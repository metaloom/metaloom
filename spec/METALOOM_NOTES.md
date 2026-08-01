# NOTES — scratch backlog

Scratch pad for raw ideas and open questions that do **not yet have a spec file of their own**; once
an item grows teeth it moves into a real spec under `spec/features/…` or a task in
[plans/TASKS.md](plans/TASKS.md) and is deleted here. This file tracks no progress — the linked specs do.

---

## Tasks

- Remove PicoCLIModule from cortex. Cortex has no longer a CLI! Cortex is just a container anymore!
- Create Market analysis of metaloom. Are there comperable offerings?
- USP: Describe the USP of metaloom. Also point out missing aspects. Currently metaloom only focuses on extraction. What could be the real usecase for agencies. Where lies the big benefit. How can all this extraction information be utilized? Is metaloom a search and ingestion pipeline for assets? Is there any more?
- Metadata ingestion: Tika? Dublin Core - https://de.wikipedia.org/wiki/Dublin_Core, exif
- Metadata writeback: (Dublin Core - https://de.wikipedia.org/wiki/Dublin_Core) - Check how many there are. Investigate (Images have exif data)
- How can nodes register themself onto loom? How can custom nodes automatically be picked up and how can the UI allow those nodes to be used for pipelines
- How about a AI aware agentic supported sync program which automagically syncs assets which are relevant for a user to the client of the user (e.g https://www.lucidlink.com/) - how could this be implemented for loom-app?
- Add gdrive/onedrive support for asset sources
- Add dedicated file upload area in the UI
- Add way of viewing notifications in the UI (How are notifications tracked in loom?)
- Add semantic ingestion node. This allows constructed semantic data to be ingested into loom
- Add translate node. This node should be able to translate input text into a specified language
- Add language filter node. This node is able to determine the language and act as a filter
- Add support for dynamic outputs (e.g. language filter node) should have dynamic amount of outputs (min 1). The UI in the pipeline editor should have a + sign to add new filter options
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
  videogen; the `llm`, `vlm` and `captioning` nodes still call an *external* Ollama / vLLM endpoint.
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
  not up, matching the `OllamaAvailability` pattern in `loom/core`.

Still genuinely environmental: `xattr` unsupported on some test filesystems, and the SmolVLM
endpoint (`SmolVLMClientTest`).

### ⛔ facedetect is broken by an OpenCV ABI split (open)

Not test noise — a production defect, surfaced once video decoding started working again.

`inspireface4j` ships `libjinspireface.so` linked against Debian's **OpenCV 4.10**
(`libopencv_core.so.410`, `libopencv_imgproc.so.410`), while `video4j` → `opencv-ffm 5.0.0` loads
the locally built **OpenCV 5.1**. Both end up in one JVM, so the `cv::Mat*` that
`InspirefaceLib.detect()` hands across the boundary is read with the wrong struct layout:

```
SIGSEGV … C  [libopencv_core.so.410+0x18f200]  cv::Mat::Mat(cv::Mat const&)+0x60
             io.metaloom.inspireface4j.InspirefaceLib.detect(…)
```

This crashes the forked JVM (exit 134) and takes `FacedetectNodeTest`, `InspirefaceTest` and
`VideoFaceScannerTest` with it — `FacedetectNodeTest` is the node's own test, so the production
face-detection path is affected too, not just the tests. It was previously masked: the tests died
earlier on `UnsatisfiedLinkError: libavcodec.so.61`.

It could not be fixed here. Rebuilding `jinspirelib` against OpenCV 5 configures correctly
(`cmake -DOpenCV_DIR=…/opencv/build -DCMAKE_POLICY_VERSION_MINIMUM=3.5`, finds 5.1.0) but cannot
compile: `inspireface4j/inspireface-linux-x86-ubuntu18-1.2.1` is a **broken symlink** to
`/home/jotschi/build/…`, so the InspireFace SDK headers are absent on this machine.

Two ways out, both a deliberate call:

1. Restore the InspireFace 1.2.1 SDK and rebuild `libjinspireface.so` against OpenCV 5.1 — the
   whole rest of the stack has moved to OpenCV 5.
2. Pin `video4j`/`opencv-ffm` back to 4.10 so both halves agree again.

---

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (dropped notes already implemented in code, kept the five still-open ideas)_
