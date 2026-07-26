# Plan: `imagegen` node + Ideogram 4.0 Python sidecar

> Status: **proposed** — exploration/design plan, not yet implemented.
> Node kind: `imagegen`. Backing model: Ideogram 4.0 (open-weight).

## Context

We want an **image-generation** capability in the Cortex pipeline, backed by the
open-weight **Ideogram 4.0** model (released 2026-06-03; 9.3B-param diffusion
transformer). Two deliverables:

1. A standalone **Python sidecar** that serves Ideogram 4.0 over HTTP (the PoC,
   lives outside the metaloom repo).
2. A new Cortex node **`imagegen`** that calls the sidecar to produce a derived
   image from an existing asset (image-to-image / remix), stores it to a local
   meta path, and records an `asset_node_result` ledger entry.

### Facts driving the design
- **Ideogram 4.0 runs in Python/PyTorch via HF Diffusers** — there is no C/C++
  native library, so a Java FFM wrapper (yolo4j / inspireface4j style) does
  **not** apply. Inference:
  `DiffusionPipeline.from_pretrained("ideogram-ai/ideogram-4-nf4", …)` or the
  shipped `run_inference.py` CLI. Checkpoints: `nf4` (24 GB CUDA) / `fp8`
  (broader HW). Weights are gated on Hugging Face.
- The model runs as a **Python sidecar**, not a Java wrapper. This mirrors the
  existing **CaptioningNode → SmolVLMClient → FastAPI** pattern exactly, so no
  `ideogram4j` project is needed.
- Node semantics: **image-to-image / remix** — `compute(ctx, asset)` takes the
  existing asset's image plus a prompt and generates a new image. This fits
  `AbstractMediaNode`'s asset-centric model.
- Output storage: **local meta path + ledger marker** (ThumbnailNode pattern).
  Pushing bytes into Loom's asset-binary subsystem is a follow-up, out of scope.

### ⚠️ License caveat
Ideogram 4.0 **code** is Apache-2.0 but the **weights** are under the *Ideogram 4
Non-Commercial Model Agreement*. This is fine for a PoC / research node; using it
in a commercial metaloom deployment would violate the weight license. The sidecar
is intentionally **pluggable** — any Diffusers-compatible image model can back the
same HTTP contract, so the node is not coupled to Ideogram specifically.

---

## Part A — `ideogram-sidecar` (Python PoC, outside metaloom)

A minimal FastAPI service wrapping Ideogram 4.0, sibling to the other workspace
projects. Name: `ideogram-sidecar` (`ideogram4-poc` is an acceptable alias — but
**not** `ideogram4j`; it is Python, not Java).

**Layout**
```
ideogram-sidecar/
  pyproject.toml    # diffusers, torch, transformers, fastapi, uvicorn, pillow
  server.py         # FastAPI app, loads the pipeline once at startup
  README.md         # HF login/gate steps, GPU reqs, docker run, curl examples
  Dockerfile        # CUDA base; optional, mirrors kokoro-fastapi packaging
```

**HTTP contract** (kept model-agnostic so the node isn't coupled to Ideogram):
- `GET  /health`   → `{"status":"ok","model":"ideogram-4-nf4"}`
- `POST /generate` — `{prompt, width, height, seed?, steps?}` → `image/png`
  (text-to-image; used for smoke tests).
- `POST /remix`    — `{image_b64, prompt, strength, seed?}` → `image/png`
  (image-to-image; the endpoint the node uses).

**Model loading** — load the pipeline once at startup:
`DiffusionPipeline.from_pretrained("ideogram-ai/ideogram-4-nf4", torch_dtype=torch.bfloat16, device_map="cuda")`.
Gate access requires a Hugging Face token (`HF_TOKEN` env). Structured-JSON /
magic-prompt expansion can be added later; plain-text prompts work.

**Prereqs to document**: ~24 GB CUDA GPU for `nf4` (or the `fp8` build for other
HW), `huggingface-cli login` + accepting the model gate.

**References to mirror**: `tts4j/kokoro4j/` (Dockerized FastAPI model server +
README skeleton) and the leftover `cortex/nodes/tts/server/server.py` scaffold
for the FastAPI shape.

---

## Part B — Cortex `imagegen` node (metaloom repo)

Mirror **CaptioningNode** (HTTP model-server client) for the call and
**ThumbnailNode** (produces an image file → local metaPath + ledger) for output.

### New module `cortex/nodes/image-generation/`
`pom.xml` (parent) + `core/pom.xml`, deps like `captioning/core/pom.xml` (slf4j,
cortex-api/common, video4j for `ImageUtils.load`). Add
`<module>image-generation</module>` to `cortex/nodes/pom.xml`.

`core/src/main/java/io/metaloom/cortex/node/imagegen/`:

- **`ImageGenNode.java`** — `extends AbstractMediaNode<ImageGenNodeOptions>`.
  - `name()` → `"imagegen"`.
  - `isProcessable(ctx)` → true when the asset is an image (reuse the media-type
    guard CaptioningNode / ThumbnailNode use).
  - `compute(ctx, asset)`:
    1. Load source image via `ImageUtils.load(...)` (as CaptioningNode does).
    2. Build the prompt from an options template (later: read an upstream caption
       via `ctx.upstreamOutput(...)`).
    3. `imageGenClient.remix(sourceImage, prompt, strength)` → PNG bytes.
    4. Write bytes to a hash-segmented dir under
       `cortexOptions.getMetaPath().resolve("imagegen_bin")` (copy ThumbnailNode's
       segmenting).
    5. `ctx.output(OUTPUT_IMAGE_PATH, path)` where
       `NodeOutputKey<String> OUTPUT_IMAGE_PATH = NodeOutputKey.of("imagegen_path", String.class)`.
    6. `recordNodeResult(asset, ctx, SUCCESS, reason, PRODUCER_VERSION, resultRef(...))`
       — best-effort ledger write with `nodeKind="imagegen"`.
    7. `return ctx.next()`.
- **`ImageGenNodeOptions.java`** — `extends AbstractNodeOptions`, `KEY="imagegen"`,
  fields `host`, `port`, `endpoint`, `promptTemplate`, `strength`, `width`,
  `height`, `timeoutMs`; `validate()` (mirror `WhisperOptions` /
  `CaptioningNodeOptions`).
- **`ImageGenClient.java`** — small `java.net.http.HttpClient` POST to the sidecar
  `/remix` (base64 image + prompt) returning `byte[]`. Direct analog of
  `SmolVLMClient`.
- **`ImageGenNodeModule.java`** — `extends AbstractNodeModule`: `@Binds @IntoSet`
  the node; `@Provides` options + `CortexNodeOptionDeserializerInfo(ImageGenNodeOptions.class, KEY)`;
  `@Provides` the `ImageGenClient`. (Copy `CaptioningNodeModule`.)

### Wiring (must-do or the node is invisible)
- `cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/NodeCollectionModule.java`
  — add `ImageGenNodeModule.class` to `includes`.
- `node_kind` is a free-form string on the Loom side (no enum), so no backend enum
  change is required.

### Tests (definition of done — `spec/guidelines/CODING.md`)
Mirror the whisper/thumbnail test set under `.../imagegen/`:
- `ImageGenNodeTest` — unit, mock `ImageGenClient`, assert an image is written and
  `ResultState.SUCCESS`.
- `ImageGenNodePipelineTest` — output-key propagation.
- `ImageGenNodePersistenceTest` — asserts the `asset_node_result` ledger row is
  recorded (pattern: `WhisperNodePersistenceTest`).
- `ImageGenOptionsValidationTest` — options `validate()`.
- Per-node E2E:
  `integration-test/src/test/java/io/metaloom/loom/test/integration/node/ImageGenNodeIntegrationTest.java`
  extends `AbstractNodeIntegrationTest` — pre-create an image asset, **mock the
  model client** (no live GPU), run `node.process(...)`, assert SUCCESS and that
  the output artifact exists.

### Docs & spec (definition of done)
- Add the node to the catalog in `spec/features/pipeline-nodes/NODES.md` and,
  if a fuller design doc is warranted, `spec/features/pipeline-nodes/NODE_IMAGE_GENERATION_PLAN.md`
  following `SPEC_RULES.md`. Surface the non-commercial license caveat there.
- Customer-facing docs: add a short page under `website/content/english/docs`
  (CODING.md requires customer-facing docs for new features).
- Demo data: if the node's enablement/config should be visible in the demo, add
  it in `DemoDatabaseInitializer` (`loom/core/.../boot/`).

---

## Critical files (reference / to modify)

| Purpose | Path |
|---|---|
| Node call pattern (HTTP model server) | `cortex/nodes/captioning/core/.../CaptioningNode.java`, `SmolVLMClient` |
| Produces an image + local metaPath + ledger | `cortex/nodes/thumbnail/core/.../ThumbnailNode.java` |
| Result write-back template (2-step) | `cortex/nodes/whisper/core/.../WhisperNode.java` (`recordNodeResult`, `resultRef`) |
| Base node class | `cortex/common/.../common/node/AbstractMediaNode.java` |
| Options base | `cortex/api/.../api/option/node/AbstractNodeOptions.java` |
| Result types | `cortex/api/.../api/node/{NodeResult,ResultState,NodeOutputKey}.java` |
| Module wiring | `cortex/cli/.../dagger/NodeCollectionModule.java`; `cortex/nodes/pom.xml` |
| IT base | `integration-test/.../node/AbstractNodeIntegrationTest.java`, `WhisperNodeIntegrationTest.java` |
| Sidecar template | `tts4j/kokoro4j/`; `cortex/nodes/tts/server/server.py` (FastAPI shape) |
| Node spec conventions | `spec/features/pipeline-nodes/NODES.md`; `spec/SPEC_RULES.md`; `spec/guidelines/CODING.md` |

## Out of scope / follow-ups
- **Loom binary upload**: pushing the generated PNG into Loom's asset-binary
  subsystem as a derivative asset (the client has no raw byte-upload today; only
  `AttachmentMethods.uploadAttachment`). Track separately.
- Structured-JSON / magic-prompt prompting, bounding-box layout, colour-palette
  steering — sidecar can add later.
- Caption-driven prompt sourcing (chain off the captioning node's output).

## Verification (end-to-end)
1. **Sidecar**: `huggingface-cli login`, accept the model gate, `uvicorn server:app`;
   `curl` `/remix` with an image → valid PNG (GPU box).
2. **Node unit/IT**: after any Flyway change run `./setup-pool.sh`; then
   `mvn -pl cortex/nodes/image-generation/core test` and the `imagegen` IT in
   `integration-test`. The IT mocks the model client — no GPU needed in CI.
3. **Live smoke** (optional, GPU): point `ImageGenNodeOptions.host/port` at a
   running sidecar, run the node against a real image asset, confirm a PNG lands
   under `metaPath/imagegen_bin/…` and an `asset_node_result` row with
   `node_kind="imagegen"` is created.
4. **Rebuild gotcha**: after endpoint/constructor changes, clean-rebuild
   `loom/core` before setup-pool/tests (known `NoSuchMethodError` pitfall).

---

_Basis: metaloom @ 5fbbeebc · drafted 2026-07-26._
