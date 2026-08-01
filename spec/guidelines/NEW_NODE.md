# Adding a New Cortex Node

This is the **definition of done for a new Cortex processing node**. It is a rules file, not
background: a node is not finished until every touch-point below is covered. It complements
[CODING.md](CODING.md) (the general definition of done) and the node system spec
[../features/pipeline-nodes/NODES.md](../features/pipeline-nodes/NODES.md) (the source of truth for
how nodes work). When this guide and the code disagree, the code wins — fix this guide in the same
change.

> **Work from a sibling.** Do not write a node from a blank file. Pick the closest existing node and
> copy its shape. The recommended templates:
>
> | Your node is… | Copy | Why |
> |---|---|---|
> | Analytical, writes a **typed component** to Loom | `cortex/nodes/whisper` | `createAssetTranscript` + `asset_transcript_comp` is the reference persistence path |
> | Produces **new bytes** from the asset (transform/generative) | `cortex/nodes/watermark` (newest) · `cortex/nodes/image-generation` | `<name>_bin` artifact cache + ledger-only persistence; watermark also shows the ffmpeg/subprocess shape |
> | Pure compute, **no model / no sidecar** | `cortex/nodes/dominant-color` | k-means arithmetic, no external runtime |
> | A **sink** that consumes upstream artifacts | `cortex/nodes/s3-sink` | reads files an upstream node wrote to local disk; also a `PipelineConfigurable` |
> | A minimal out-of-tree example | `examples/cortex-custom-node` | the smallest thing that compiles and registers |

---

## 1. Anatomy of a node

A node module is `cortex/nodes/<name>/core` (all but two nodes use the `core/` submodule layout —
copy it; flat modules exist only for `filesystem-source` and `s3-source`), in package
`io.metaloom.cortex.node.<pkg>`:

| File | Responsibility |
|---|---|
| `XNode.java` | extends `AbstractMediaNode<XNodeOptions>`; implements `name()`, `isProcessable()`, `compute()`; declares the port constants |
| `XNodeOptions.java` | extends `AbstractNodeOptions<XNodeOptions>`; a `public static final String KEY`; getters/setters; `validate()` |
| `XNodeModule.java` | Dagger `@Module extends AbstractNodeModule` — the bindings in §1.3 |
| `XClient.java` | *(sidecar nodes only)* pure `java.net.http` client; **force HTTP/1.1** — FastAPI rejects HTTP/2 |
| `XMode.java` | *(optional)* an enum when the node has modes (e.g. `GENERATE`/`ANIMATE`) |

### 1.1 The node lifecycle (`AbstractMediaNode`)

`process()` in the base class checks enabled → file exists → `isProcessable()` → fetches the
`AssetResponse` (null when offline), then calls `compute(ctx, asset)`. Inside `compute`:

- **Declare ports as `public static final` constants** — the conformance test (§2) reflects over them:
  ```java
  public static final InputPort<LoomMedia> IN_MEDIA  = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);
  public static final OutputPort<String>   OUT_IMAGE = OutputPort.one("image", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);
  ```
- **Read inputs by port, never by node id.** `ctx.input(PORT)` (ONE) · `ctx.optionalInput(PORT)`
  (ONE, may be absent) · `ctx.inputs(PORT)` → `List<Element<T>>` (MANY, seq-ordered and origin-tagged).
  `ctx.isWired(PORT)` tells which alternative of an XOR group fed the node; `ctx.isDemanded(PORT)` is
  a hint that lets you skip genuinely expensive work nobody consumes. A wired input port overrides the
  equivalent configured option: `ctx.optionalInput(IN_PROMPT).orElseGet(o::getPrompt)`.
  **`NodeOutputKey` and `ctx.upstreamOutput(nodeId, key)` are deleted** — do not reintroduce either.
- **Emit outputs by port**: `ctx.output(OUT_X, value)` (ONE) / `ctx.outputElement(OUT_X, value)` (MANY).
- **Keep an in-heap skip cache** (`LocalResultCache<V>`, keyed by `media.absolutePath()` — plus a hash
  of the options that change the result, if the node can appear twice in one graph). On a hit, re-emit
  the cached outputs and return `ctx.origin(LOCAL).next()` — skip both recompute *and* re-persist.
  A cache hit is SUCCESS with `ResultOrigin.LOCAL`, **not** SKIPPED. If the cached value is a file
  path, `Files.exists` it first.
- **Return values:**

  | Outcome | Return | Resulting `ResultState` |
  |---|---|---|
  | success | `ctx.origin(COMPUTED).next()` | SUCCESS |
  | nothing to do | `ctx.skipped(reason).next()` | SKIPPED (outputs survive) |
  | failure | `ctx.failure(msg).abort()` | FAILED |

  🔴 **`ctx.failure(msg).next()` reports SUCCESS.** `NodeContextImpl.next()` looks only at
  `skipReason` and ignores `failureCause`. Several older nodes (`whisper` among them) still do this
  and are wrong — do not copy them. Failure is always `.abort()`.

### 1.2 Persistence (choose one)

Guard everything with `asset != null && client() != null` (a clean no-op offline). Both paths end in
the ledger call `recordNodeResult(asset, ctx, state, reason, producerVersion, resultRef)` on
`AbstractMediaNode`, which POSTs to `/api/v1/assets/:uuid/node-results`. It is best-effort (a ledger
failure never fails the node), a no-op offline, and the row **upserts** on
`(asset_uuid, node_kind, node_id)`.

- **Typed component** (analytical nodes): POST the payload to its per-asset REST sub-resource, then
  `recordNodeResult(asset, ctx, SUCCESS, null, version, resultRef("<table>", uuid))`. `WhisperNode`
  is the reference (`createAssetTranscript` → `asset_transcript_comp`), *except* for its failure
  return and its private copy of `recordNodeResult` — use the base-class method.
- **Ledger only** (nodes whose bytes stay on the worker): write to
  `metaPath/<name>_bin/<segment>/<sha512>.<ext>` (via `HashUtils.segmentPath`) and call
  `recordNodeResult(asset, ctx, SUCCESS, null, producerVersion(), null)` — **no `result_ref`**.
  Loom has no byte-ingest endpoint for produced media yet; wiring the artifact output port into
  `s3-sink` is the current way to keep the bytes. See the persistence table in
  [NODES.md §2](../features/pipeline-nodes/NODES.md).

Also record a FAILED ledger row on the failure path. `producerVersion` should change whenever the
meaning of the output changes — `watermark` uses `"watermark/1:<digest of the logo>"`.

### 1.3 Dagger bindings (`XNodeModule`)

Exactly as in every sibling module — **all of it lives in the node's own module**:

```java
@Module
public abstract class XNodeModule extends AbstractNodeModule {

  @Binds @IntoSet                      abstract FilesystemNode<?, ?> bindNode(XNode node);   // legacy CLI set
  @Binds @IntoMap @StringKey("<kind>") abstract FilesystemNode<?, ?> kindX(XNode node);      // executable pipeline kind

  @IntoSet @Provides public static CortexNodeOptionDeserializerInfo optionInfo() {
    return new CortexNodeOptionDeserializerInfo(XNodeOptions.class, XNodeOptions.KEY);
  }

  @Provides public static XNodeOptions options(CortexOptions options) {
    return nodeOptions(options, XNodeOptions.KEY, new XNodeOptions());
  }
  // plus an @Provides for the XClient when the node has a sidecar client
}
```

- **The `@IntoMap @StringKey` binding is what makes the node schedulable.** The executable-kind
  registry is built from that map alone, and **`PipelineNodeFactoryModule` needs no edit** — it
  provides an empty `RegistryNodeFactory` that `NodeRegistrar` fills from the multibinding at
  bootstrap. Do not add anything there.
- ⚠️ A node implementing `PipelineConfigurable` (per-instance config, like `script`/`s3-sink`) **must
  not be `@Singleton`** — `RegistryNodeRegistrar.adapt(...)` calls `configure(JsonObject)` on the
  instance and the runner builds one per task. Its options arrive flattened onto the top level of the
  node definition, alongside `id`/`type`/`mode`/`blocking`/`concurrency`/`syncToLoom`/`timeoutMs`.
  Such a node should also override `nodeId()` so its ledger rows do not collide
  (`asset_node_result` is `UNIQUE (asset_uuid, node_kind, node_id)`).

---

## 2. Registration touch-points (all five, or the node is invisible)

A new node is dispatchable only when every one of these is edited. Miss one and it silently does not
run, or the build fails.

| # | File | Edit |
|---|---|---|
| 1 | `cortex/nodes/pom.xml` | add `<module>your-node</module>` |
| 2 | `cortex/processor/pom.xml` | add a `<dependency>` on `cortex-<your-node>-node` — this is the aggregation module the CLI/server pull in transitively |
| 3 | `cortex/cli/.../dagger/NodeCollectionModule.java` | import `XNodeModule` and add `XNodeModule.class` to `@Module(includes = {…})` |
| 4 | `loom-shared/node-model/.../spec/XDescriptorProvider.java` **+** the `META-INF/services/io.metaloom.loom.nodes.spec.NodeDescriptorProvider` file | the descriptor (ports, parameters, category, icon) and its ServiceLoader registration |
| 5 | `integration-test/.../node/NodePortConformanceTest.java` | add a `NODE_KINDS.put("<node class FQN>", "<kind>")` line — kinds are listed explicitly, never scanned |

**And update the two guard tests:**

- `NodeDescriptorServiceLoaderTest` asserts an exact provider count and kind count — currently
  **26 providers / 41 kinds**. Adding a descriptor bumps both `assertEquals` literals; also add the
  kind to its `testKindsFromEachFormerModule` list and update the "N providers declare M kinds" line
  in [NODES.md §8](../features/pipeline-nodes/NODES.md). This test failing is the intended tripwire,
  not a regression.
- `NodePortConformanceTest` (touch-point 5) compares the node's `InputPort`/`OutputPort` constants
  against its descriptor's `PortSpec`s — **id, content type and cardinality must match on both
  sides**, in both directions. Nodes whose ports are derived per instance (`script`, `llm`, `vlm`)
  are exempted via `DYNAMIC_KINDS`; everything else must match exactly.

Descriptor shape (see `WatermarkDescriptorProvider` for a full one):
`setKind` / `setName` / `setDescription` / `setIcon` (Material icon name) / `setCategory`
(`SOURCE`, `FILTER`, `ANALYSIS`, `TRANSFORM`, …) / `setInputPorts` + `setOutputPorts`
(`PortSpec.one|many|optionalOne|optionalMany(id, contentType).describedAs(label, desc)`) /
`setParameters` (`NodeParameter`, starting with the common `enabled` / `processIncomplete` /
`retryFailed`) / `setDefaultConcurrency` / `setDefaultMode` / `setEvents`.
Content types come from `ContentTypeRegistry` (`MEDIA_*`, `TEXT_*`, `DETECTION_*`, `HASH_*`,
`SCALAR_*`, `ARTIFACT_*`, `STRUCT_*`, `CONTROL_*`).

---

## 3. Tests (from [CODING.md](CODING.md))

Mirror the sibling's test set (see `cortex/nodes/watermark/core/src/test`):

- `XNodeTest` — the happy path writes the artifact / emits the ports, non-processable media
  self-skips, a failure returns FAILED (not SUCCESS), and a second run is served from the cache
  (a mocked client is hit **once**).
- `XNodePersistenceTest` — mock `LoomHttpClient`; assert exactly one `asset_node_result` row with the
  right `nodeKind`/`state`/`origin` (and `resultRef == null` for ledger-only), and a FAILED row when
  the work throws.
- `XOptionsValidationTest` — the `validate()` contract: defaults valid, each invalid field reported.
  Use the generated `assertj` helpers.
- `XNodePipelineTest extends AbstractNodeChainTest` — spy the node, stub `compute`, assert adapter
  integration: completion/tracking events, output chaining into a `CapturingNode`, disabled + dry-run
  skip.

Plus the two `assertj` helpers under `.../<pkg>/assertj/`: `XNodeAssertions extends NodeAssertions`
(`cortex/core-media` test-jar) and `XOptionsAssert extends AbstractCortexNodeOptionsAssert`
(`cortex/api` test-jar).

**Run them** with `mvn -pl cortex/nodes/<name>/core test -o` (install deps once with `-am -DskipTests`
first — some unrelated `-am` modules have flaky testcontainer tests). Then compile `cortex/cli`
(`mvn -pl cortex/cli -am compile -o`) to prove the **Dagger graph still resolves** with the node wired
in, and run `NodePortConformanceTest` + `NodeDescriptorServiceLoaderTest`.

---

## 4. Customer-facing + spec + demo (the rest of done)

- **Website docs** — `website/content/english/docs/nodes/<kind>/index.adoc`, plus three edits to
  `website/content/english/docs/nodes/_index.adoc`: the category-section table row, the "Requirements
  at a Glance" row, and a "Processing Capabilities" paragraph. Keep it customer-facing: no spec
  references, no internal class names, SVG/diagrams not ASCII art (per [CODING.md](CODING.md)). Reuse
  the `ml-nodeviz` port-diagram block from a sibling page — it renders the ports from the same ids the
  descriptor declares. If the node pulls a new model, add its licence to
  `website/content/english/docs/legal/model-licenses/`.
- **Spec** — add the node to [NODES.md](../features/pipeline-nodes/NODES.md): the node-list table
  (§3), the persistence table (§2), the Dagger wiring/descriptor counts (§8) and the capability
  matrix (§12).
- **Demo data** — `DemoDatabaseInitializer` seeds demo pipelines. Add the node to a demo pipeline
  graph **when it fits an existing ingest/publish story**; the GPU sidecar nodes (`imagegen`, `tts`,
  `depthmap`, `videogen`) are deliberately left out because the demo container has no sidecar —
  follow that precedent and note it rather than seeding a pipeline that cannot run.
- **Sidecar** — GPU/model runtimes live in `sidecars/<name>-sidecar/` (FastAPI, one free port after
  the last one; `videogen` → LTX-2 → `9220`). The Java node is a **pure HTTP client** of it. See the
  sidecars README for the port table and the heavy-GPU template.

---

## 5. Conventions and gotchas

- **Clean-rebuild after a constructor change.** Adding a constructor arg to a node (e.g. a new
  injected client) needs `cortex/core` rebuilt, or `setup-pool`/tests fail with `NoSuchMethodError`
  against the stale Dagger factory.
- **Force HTTP/1.1** in every sidecar client (`HttpClient.newBuilder().version(HTTP_1_1)`).
- **Never add a `"nodeId:outputKey"` option.** Upstream data arrives through a declared input port and
  an edge the pipeline author draws (see [NODES.md §5](../features/pipeline-nodes/NODES.md)).
- **Cardinality is behaviour, not decoration.** A `ONE` input fed by a `MANY` output runs the node
  once per element (per-face, per-paragraph); a `MANY` input gathers the branch and runs once.
- **Two outputs are how you express a branch.** `watermark` writes `image` *or* `video` per item;
  the unwritten port simply delivers nothing downstream — no filter node needed.
- **The `KEY` constant, the `@StringKey`, the `name()` return, and the descriptor `kind` must all be
  the same string.**
- **Fail, don't skip, when the worker cannot do the job it was given** (missing `ffmpeg`, unreachable
  sidecar). A skip reads as "this item did not need processing".

---

## 6. Where do I find …?

| Concept | Path |
|---|---|
| Node base class + `recordNodeResult`/`resultRef`/`nodeId` | `cortex/common/.../node/AbstractMediaNode.java` |
| `next()` / `abort()` / `skipped()` semantics | `cortex/api/.../node/context/impl/NodeContextImpl.java` |
| Options base + `validateCommon()` | `cortex/api/.../option/node/AbstractNodeOptions.java` |
| Per-instance config contract | `cortex/common/.../node/PipelineConfigurable.java` |
| Port types + content-type vocabulary | `cortex/api/.../node/{InputPort,OutputPort}.java` · `loom-shared/node-model/.../ContentTypeRegistry.java` |
| Descriptor model (`NodeDescriptor`, `PortSpec`, `NodeParameter`, `NodeCategory`) | `loom-shared/node-model/.../spec/` |
| Kind registry wiring (no edit needed) | `cortex/cli/.../dagger/PipelineNodeFactoryModule.java` · aggregation in `NodeCollectionModule.java` |
| Descriptor count guard test | `loom-shared/node-model/.../NodeDescriptorServiceLoaderTest.java` |
| Port ↔ descriptor conformance test | `integration-test/.../node/NodePortConformanceTest.java` |
| Test scaffolding (`StubLoomMedia`, `AbstractNodeChainTest`, `CapturingNode`) | `cortex/pipeline-core` test-jar (`io.metaloom.cortex.pipeline.test`) |
| Ledger endpoint + its tests | `loom/services/rest/.../AssetEndpoint.java` · `loom/core/.../endpoint/test/NodeResultEndpointTest.java` |
| Worked examples (this guide, applied) | `cortex/nodes/watermark` · `cortex/nodes/dominant-color` |

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (re-verified against the watermark node: failure must use .abort(), added the NodePortConformanceTest touch-point, corrected the guard-test counts and the test-scaffolding module)_
