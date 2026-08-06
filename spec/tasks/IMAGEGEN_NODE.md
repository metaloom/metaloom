# Plan: `imagegen` node + image-generation sidecar

> Status: **implemented** — the node ships and the sidecar is built. This file is now a
> thin status page for the remaining open items.
>
> ⚠️ **Superseded**: the authoritative design/spec for this node is
> [../features/pipeline-nodes/NODE_IMAGEGEN_PLAN.md](../concept/NODE_IMAGEGEN_PLAN.md)
> (architecture, ports, options table, gotchas, key-classes reference). Do not add design
> detail here — put it there. Node catalog: [../features/pipeline-nodes/NODES.md](../features/nodes/NODES.md).

## Already implemented

| Item | Where it lives |
|---|---|
| Maven module | `cortex/nodes/image-generation/` (parent + `core`), registered in `cortex/nodes/pom.xml`, `cortex/processor/pom.xml`, `integration-test/pom.xml` |
| Node | `cortex/nodes/image-generation/core/.../imagegen/ImageGenNode.java` — `name()="imagegen"`, `isProcessable = media.isImage()`, `LocalResultCache`, writes `metaPath/imagegen_bin/<seg>/<sha512>.png`, ledger-only `recordNodeResult` |
| Modes | `ImageGenMode` (`GENERATE` \| `REMIX`), selected via `ImageGenNodeOptions.mode` |
| Options | `ImageGenNodeOptions` (`KEY="imagegen"`): `mode`, `prompt`, `host`, `port` (9200), `generateEndpoint`, `remixEndpoint`, `width`/`height`, `strength`, `seed`, `steps`, `timeoutMs` (120 s) + `validate()` |
| Sidecar client | `ImageGenClient` — `java.net.http.HttpClient` → `/generate`, `/remix`, returns PNG `byte[]` |
| Typed ports | `IN_PROMPT` (`text/*`, optional — a wired prompt wins over the option), `IN_MEDIA` (`media/image`), `OUT_IMAGE` (`artifact/image`), `OUT_FLAG` |
| Dagger wiring | `ImageGenNodeModule` (`@Binds @IntoSet`, `@Binds @IntoMap @StringKey("imagegen")`, `optionInfo`, `options`, `imageGenClient`); included in `cortex/cli/.../dagger/NodeCollectionModule.java` |
| Pipeline-editor descriptor | `website/static/pipeline-editor/node-descriptors.json` — kind `imagegen`, category `TRANSFORM`, both input ports |
| Unit tests | `.../imagegen/{ImageGenNodeTest,ImageGenNodePipelineTest,ImageGenNodePersistenceTest,ImageGenOptionsValidationTest}` + `assertj/ImageGenNodeAssertions` |
| Integration test | `integration-test/.../node/ImageGenNodeIntegrationTest.java` (stubbed client, asserts PNG + `imagegen` ledger row via REST); port conformance in `NodePortConformanceTest` |
| Sidecar | `sidecars/ideogram-sidecar/` — FastAPI `/health` `/generate` `/remix`, default **SDXL-Turbo**, Ideogram-4 opt-in via `IMAGEGEN_MODEL`+`HF_TOKEN`, `gen_ideogram.py` |
| Second sidecar (same contract) | `sidecars/mage-flow-sidecar/` — Mage-Flow 4B, **MIT weights**; drop-in by changing only the node's `port` |
| Docs | `spec/features/pipeline-nodes/NODES.md` (§2/§3/§5/§12), `website/content/english/docs/nodes/imagegen/index.adoc`, `docs/legal/model-licenses/` |
| Sink path for the bytes | `S3SinkNode` uploads `imagegen_path` to S3 and registers it as its own Loom asset |

## Open work

- [ ] **Live GPU smoke test.** Everything below the sidecar boundary is covered by stubbed
      clients; no end-to-end run against real weights has been recorded.
      ```bash
      cd sidecars/ideogram-sidecar && CUDA_VISIBLE_DEVICES=1 ./venv/bin/uvicorn server:app --port 9200
      # point ImageGenNodeOptions host/port at it, run GENERATE on a real image asset
      # expect: PNG under metaPath/imagegen_bin/<seg>/<sha512>.png + asset_node_result row node_kind="imagegen"
      ```
      Repeat against `sidecars/mage-flow-sidecar` (its own port) to confirm the contract is
      genuinely model-agnostic.
- [ ] **Loom byte-ingest for generated media.** The PNG stays in the local `imagegen_bin`
      cache because Loom has no raw byte-upload endpoint (only `AttachmentMethods.uploadAttachment`).
      `S3SinkNode` is the current workaround, not a fix. Affects `thumbnail`, `depthmap`,
      `tts`, `script` and `imagegen` alike — solve once, at the Loom REST layer.
- [ ] **Commercial-safe default model.** The `ideogram-sidecar` default (SDXL-Turbo) and its
      opt-in Ideogram-4 weights are both non-commercial. Decide whether MIT-licensed Mage-Flow
      becomes the documented default for shipping deployments, and record the decision in
      `NODE_IMAGEGEN_PLAN.md` §7 and `docs/legal/model-licenses/`.
- [ ] **Spec coverage for `sidecars/mage-flow-sidecar`.** It is absent from the whole `spec/`
      tree; `NODE_IMAGEGEN_PLAN.md` §7 still names only the ideogram sidecar.
- [ ] **Prompt templating.** The `IN_PROMPT` port already lets an upstream caption or LLM answer
      drive generation, so caption chaining is done; placeholder substitution inside
      `options.prompt` (e.g. `${caption}`) is still not implemented.
- [ ] **Sidecar prompting features** (nice-to-have): structured-JSON / magic-prompt expansion,
      bounding-box layout, colour-palette steering.

## Gotchas (carried forward)

- `ImageUtils` has no PNG writer (JPG only) — use `ImageIO.write(img, "png", …)`.
- Ledger-only persistence: pass `resultRef=null`/`producerVersion=null`; the base class no-ops
  when `asset == null || client() == null`, so offline runs stay clean.
- Ideogram-4 nf4 on a 12 GB GPU only works at `guidance_scale=1.0`; any CFG or per-step
  CPU↔GPU swapping collapses it to a gray *"Image blocked by safety filter"* card — a
  quantization artifact, **not** a moderation filter.
- After the `NodeCollectionModule` edit, clean-rebuild `loom/core` before `./setup-pool.sh`
  or tests (known `NoSuchMethodError` pitfall).

---
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_