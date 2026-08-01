# NOTES — scratch backlog

Scratch pad for raw ideas and open questions that do **not yet have a spec file of their own**; once
an item grows teeth it moves into a real spec under `spec/features/…` or a task in
[plans/TASKS.md](plans/TASKS.md) and is deleted here. This file tracks no progress — the linked specs do.

---

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
  and rebuilding opencv `videoio` + `opencv-ffm` fixed thumbnail, fingerprint, facedetect and
  scene-detection at once. Re-run `opencv-ffm/build.sh` after any system FFmpeg bump.
- **No local LLM endpoint** — the llm node tests now run against the llama.cpp server in
  [../loom-test-env/llamacpp](../loom-test-env/llamacpp) and *skip* rather than fail when it is
  not up, matching the `OllamaAvailability` pattern in `loom/core`.

Still genuinely environmental: `xattr` unsupported on some test filesystems, and the SmolVLM
endpoint (`SmolVLMClientTest`).

---

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (dropped notes already implemented in code, kept the five still-open ideas)_
