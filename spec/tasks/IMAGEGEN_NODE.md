# Plan: `imagegen` node + image-generation sidecar

> Status: **implemented** — the node ships and the sidecar is built. This file is now a
> thin status page for the remaining open items.
>
> ⚠️ **Superseded**: the authoritative design/spec for this node is
> [NODE_IMAGEGEN.md](../features/nodes/image-generation/NODE_IMAGEGEN.md)
> (architecture, ports, options table, gotchas, key-classes reference). Do not add design
> detail here — put it there. Node catalog: [../features/pipeline-nodes/NODES.md](../features/nodes/NODES.md).

## Already implemented

| Item | Where it lives |
|---|---|
| Maven module | `cortex/nodes/image-generation/` (parent + `core`), registered in `cortex/nodes/pom.xml`, `cortex/processor/pom.xml`, `integration-test/pom.xml` |
| Node | `cortex/nodes/image-generation/core/.../imagegen/ImageGenNode.java` — `name()="imagegen"`, `isProcessable = media.isImage()`, `LocalResultCache`, writes `metaPath/imagegen_bin/<seg>/<sha512>-<digest>.png` (options digest since 2026-08-20; the node is now `PipelineConfigurable` with a `nodeId()` override), ledger-only `recordNodeResult` |
| Modes | `ImageGenMode` (`GENERATE` \| `REMIX` \| `EDIT` \| `MASK`), selected via `ImageGenNodeOptions.mode` |
| Options | `ImageGenNodeOptions` (`KEY="imagegen"`): `mode`, `prompt`, `maskPrompt`, `negativePrompt`, `trueCfgScale`, `host`, `port` (9200), `generateEndpoint`, `remixEndpoint`, `editEndpoint`, `maskEndpoint`, `width`/`height`, `outputResolution`, `composite`, `strength`, `seed`, `steps`, `timeoutMs` (120 s) + `validate()` |
| Sidecar client | `ImageGenClient` — `java.net.http.HttpClient` → `/generate`, `/remix`, `/edit`, `/mask`, returns `ImageGenResult` (PNG bytes + `X-Model-Id`) |
| Typed ports | `IN_PROMPT` (`text/*`, optional — a wired prompt wins over the option), `IN_MEDIA` (`media/image`), `IN_REFERENCES` (`artifact/image`, MANY), `IN_MASK` (`artifact/image`), `OUT_IMAGE` (`artifact/image`), `OUT_FLAG` |
| Dagger wiring | `ImageGenNodeModule` (`@Binds @IntoSet`, `@Binds @IntoMap @StringKey("imagegen")`, `optionInfo`, `options`, `imageGenClient`); included in `cortex/cli/.../dagger/NodeCollectionModule.java` |
| Pipeline-editor descriptor | `website/static/pipeline-editor/node-descriptors.json` and `loom-shared/node-model/.../node-descriptors.json` — kind `imagegen`, category `TRANSFORM`, all four input ports. Both copies must be regenerated |
| Unit tests | `.../imagegen/{ImageGenNodeTest,ImageGenNodePipelineTest,ImageGenNodePersistenceTest,ImageGenOptionsValidationTest}` + `assertj/ImageGenNodeAssertions` |
| Integration test | `integration-test/.../node/ImageGenNodeIntegrationTest.java` (stubbed client, asserts PNG + `imagegen` ledger row via REST); port conformance in `NodePortConformanceTest` |
| Sidecar | `sidecars/ideogram-sidecar/` — FastAPI `/health` `/generate` `/remix`, default **SDXL-Turbo**, Ideogram-4 opt-in via `IMAGEGEN_MODEL`+`HF_TOKEN`, `gen_ideogram.py` |
| Second sidecar (same contract) | `sidecars/mage-flow-sidecar/` — Mage-Flow 4B, **MIT weights**; drop-in by changing only the node's `port` |
| Third sidecar (superset contract) | `sidecars/qwen-image-sidecar/` — Qwen-Image-2.1 on `:9230`. Adds `/edit` and `/mask` on top of the shared contract; **non-commercial weights**. Spec: [../sidecars/QWEN_IMAGE_SIDECAR.md](../sidecars/QWEN_IMAGE_SIDECAR.md) |
| `EDIT` / `MASK` modes | `ImageGenMode.EDIT` / `.MASK`, the `references` (MANY) and `mask` (ONE) `artifact/image` input ports, and the `maskPrompt` / `negativePrompt` / `trueCfgScale` / `outputResolution` / `composite` options — all in the digest material |
| Ledger provenance | `ImageGenResult` carries the sidecar's `X-Model-Id`, recorded as `producerVersion`. Non-null only against the qwen backend |
| MCP twin | `GenerateImageTool` (`loom/services/mcp`) — the same capability from the chat window, calling the sidecar from inside Loom and ingesting the result as a real asset via `ProducedAssetIngestor` |
| Docs | `spec/features/pipeline-nodes/NODES.md` (§2/§3/§5/§12), `website/content/english/docs/nodes/imagegen/index.adoc`, `docs/legal/model-licenses/` |
| Sink path for the bytes | `S3SinkNode` uploads `imagegen_path` to S3 and registers it as its own Loom asset |

## Open work

- [ ] **Live GPU smoke test.** Everything below the sidecar boundary is covered by stubbed
      clients; no end-to-end run against real weights has been recorded, for any of the three
      backends.
      ```bash
      cd sidecars/ideogram-sidecar && CUDA_VISIBLE_DEVICES=1 ./venv/bin/uvicorn server:app --port 9200
      # point ImageGenNodeOptions host/port at it, run GENERATE on a real image asset
      # expect: PNG under metaPath/imagegen_bin/<seg>/<sha512>-<digest>.png + asset_node_result row node_kind="imagegen"
      ```
      Repeat against `sidecars/mage-flow-sidecar` (its own port) to confirm the contract is
      genuinely model-agnostic.
- [ ] **Loom byte-ingest for generated media.** The PNG stays in the local `imagegen_bin`
      cache because Loom has no raw byte-upload endpoint (only `AttachmentMethods.uploadAttachment`).
      `S3SinkNode` is the current workaround, not a fix. Affects `thumbnail`, `depthmap`,
      `tts`, `script` and `imagegen` alike — solve once, at the Loom REST layer.
- [x] ~~**Commercial-safe default model.**~~ Decided 2026-09-23: **Mage-Flow (MIT) is the documented
      default for shipping deployments.** Recorded in NODE_IMAGEGEN.md §6 and in
      `website/content/english/docs/legal/model-licenses/`, which now also carries Qwen-Image-2.1's
      research licence and states the trade-off out loud — the only backend that can combine images
      or mask a region is the only one you may not use commercially.
- [x] ~~**Spec coverage for `sidecars/mage-flow-sidecar`.**~~ `spec/sidecars/MAGE_FLOW_SIDECAR.md`
      exists and §6 of NODE_IMAGEGEN.md now compares all three backends.
- [ ] **Prompt templating.** The `IN_PROMPT` port already lets an upstream caption or LLM answer
      drive generation, so caption chaining is done; placeholder substitution inside
      `options.prompt` (e.g. `${caption}`) is still not implemented.
- [x] ~~**Sidecar prompting features**~~ — partly: `negativePrompt` + `trueCfgScale` landed with the
      qwen backend. Structured-JSON / magic-prompt expansion and colour-palette steering are still
      open; Qwen's own `PE-T2I` / `PE-I2I` prompt-expansion checkpoints would cover the first of
      those at roughly 18 GB more resident VRAM.
- [x] ~~`EDIT` and `MASK` have never run against live weights~~ - run 2026-09-23 against an H200 on
      infom1. Text-to-image, instruction editing and three-image composition all work well. Examples
      and measurements: `metaloom/scratchpad/qwen-image-2.1-examples/`.
- [ ] 🔴 **`MASK` returns the wrong region.** "The woman's hair" gave a clean mask of the whole
      person; "the black leather jacket" came back inverted. The plumbing is right and the semantics
      are not - a generative model is being asked to segment. Not blocking, because a plain `EDIT`
      instruction handles the case it was built for. Either retire the mode or feed it from `sam2`.
- [ ] **Measured VRAM is 46 GB at 1K and 66 GB at 2K**, not the 33 GB of weights - torch never
      returns the activation memory. Docs corrected; worth knowing before scheduling it on a card.
- [ ] **No capability check before the call.** `EDIT`/`MASK` against ports 9200 or 9210 fail with the
      sidecar's raw 404 rather than a readable "this backend cannot do that". `/health` advertises a
      `capabilities` list and nothing reads it.
- [ ] **Docs fixture and screenshots still show `REMIX`.** The customer page describes the two new
      modes but pictures neither; regenerating needs a live qwen sidecar.

## Gotchas (carried forward)

- `ImageUtils` has no PNG writer (JPG only) — use `ImageIO.write(img, "png", …)`.
- Ledger-only persistence: pass `resultRef=null`; the base class no-ops when
  `asset == null || client() == null`, so offline runs stay clean. `producerVersion` is no longer
  always null — it carries the sidecar's `X-Model-Id` when one is sent.
- Ideogram-4 nf4 on a 12 GB GPU only works at `guidance_scale=1.0`; any CFG or per-step
  CPU↔GPU swapping collapses it to a gray *"Image blocked by safety filter"* card — a
  quantization artifact, **not** a moderation filter.
- After the `NodeCollectionModule` edit, clean-rebuild `loom/core` before `./setup-pool.sh`
  or tests (known `NoSuchMethodError` pitfall).

---
_Git HEAD revision: `6653bbe8`_
_Last updated: 2026-09-23 (EDIT/MASK modes, the qwen-image sidecar, the MCP `generate_image` twin, `producerVersion`; the commercial-default question is decided). Earlier: 2026-08-20 (NODE_TASKS.md Task 4 landed: options digest in path+cache key, PipelineConfigurable, nodeId() override). Earlier: 2026-08-06 (reference sweep — no content changes)_