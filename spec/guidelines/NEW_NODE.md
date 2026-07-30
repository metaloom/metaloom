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
> | Generative, backed by a GPU **sidecar**, bytes stay local | `cortex/nodes/image-generation` / `cortex/nodes/video-generation` | sidecar HTTP client + ledger-only persistence + `*_bin` cache |
> | Analytical, writes a **typed component** to Loom | `cortex/nodes/whisper` | `createAssetTranscript` + `asset_transcript_comp` is the reference persistence path |
> | Pure compute, **no model / no sidecar** | `cortex/nodes/dominant-color` | k-means arithmetic, no external runtime |
> | A **sink** that consumes upstream artifacts | `cortex/nodes/s3-sink` | reads files an upstream node wrote to local disk |
>
> `video-generation` (the LTX-2 videogen node) was created by copying `image-generation` verbatim and
> renaming — it is the canonical worked example of this guide.

---

## 1. Anatomy of a node

A node module `cortex/nodes/<name>` (a `core/` submodule if it may later grow a native sibling, like
`image-generation` and `video-generation`; a flat module otherwise, like `depthmap`) contains, in
package `io.metaloom.cortex.node.<pkg>`:

| File | Responsibility |
|---|---|
| `XNode.java` | extends `AbstractMediaNode<XNodeOptions>`; implements `name()`, `isProcessable()`, `compute()` |
| `XNodeOptions.java` | extends `AbstractNodeOptions<XNodeOptions>`; a `public static final String KEY`; getters/setters; `validate()` |
| `XClient.java` | *(sidecar nodes only)* pure `java.net.http` client; **force HTTP/1.1** — FastAPI rejects HTTP/2 |
| `XMode.java` | *(optional)* an enum when the node has modes (e.g. `GENERATE`/`ANIMATE`) |
| `XNodeModule.java` | Dagger `@Module extends AbstractNodeModule` — the four bindings below |

### The node lifecycle (`AbstractMediaNode`)

`compute(ctx, asset)` runs after the base class has checked enabled → exists → `isProcessable` →
fetched the `AssetResponse`. Inside it:

- **Read inputs by port, never by node id.** Declare `public static final InputPort<…>` /
  `OutputPort<…>` constants. A wired input port overrides the equivalent configured option
  (`ctx.optionalInput(IN_PROMPT).orElseGet(o::getPrompt)`).
- **Emit outputs by port**: `ctx.output(OUT_X, value)` (ONE) / `ctx.outputElement(OUT_X, value)`
  (MANY).
- **Keep an in-heap skip cache** (`LocalResultCache<V>`, keyed by `media.absolutePath()`): on a hit,
  re-emit the cached outputs and return `ctx.origin(LOCAL).next()` — **skip both recompute and
  re-persist** (the durable copy already lives in Loom). A cache hit returns SUCCESS with
  `ResultOrigin.LOCAL`, **not** SKIPPED.
- **Return** `ctx.origin(COMPUTED).next()` on success, `ctx.failure(msg).next()` on failure.

### Persistence (choose one)

Guard everything with `asset != null && client() != null` (a clean no-op offline).

- **Typed component** (analytical nodes): POST the payload to its per-asset REST sub-resource (which
  **upserts**), then `recordNodeResult(asset, ctx, SUCCESS, null, version, resultRef("<table>", uuid))`.
- **Ledger only** (generative / side-effect nodes whose bytes stay on the worker): write the bytes to
  `metaPath/<name>_bin/<segment>/<sha512>.<ext>` (via `HashUtils.segmentPath`) and record
  `recordNodeResult(asset, ctx, SUCCESS, null, null, null)` — **no `result_ref`**. Uploading produced
  bytes into Loom's binary subsystem needs a byte-ingest endpoint that does not exist yet; wiring the
  output port into `s3-sink` is the current way to keep them. See the persistence table in
  [NODES.md §2](../features/pipeline-nodes/NODES.md).

### Dagger bindings (`XNodeModule`)

Four members, exactly as in every sibling module:

```java
@Binds @IntoSet          FilesystemNode<?, ?> bindNode(XNode node);              // legacy CLI set
@Binds @IntoMap @StringKey("<kind>") FilesystemNode<?, ?> kindX(XNode node);     // executable pipeline kind (== name())
@IntoSet @Provides static CortexNodeOptionDeserializerInfo optionInfo();         // options deserialization
@Provides static XNodeOptions options(CortexOptions o);                          // nodeOptions(o, KEY, new XNodeOptions())
// plus @Provides for the XClient when the node has a sidecar client
```

⚠️ A node implementing `PipelineConfigurable` (per-instance config, like `script`/`s3-sink`) **must
not be `@Singleton`** — `configure()` mutates the instance and the runner builds one per task.

---

## 2. Registration touch-points (all four, or the node is invisible)

A new node is dispatchable only when every one of these is edited. Miss one and it silently does not
run, or the build fails.

| # | File | Edit |
|---|---|---|
| 1 | `cortex/nodes/pom.xml` | add `<module>your-node</module>` |
| 2 | `cortex/processor/pom.xml` | add a `<dependency>` on `cortex-<your-node>-node` — this is the aggregation module the CLI/server pull in transitively |
| 3 | `cortex/cli/.../dagger/NodeCollectionModule.java` | import `XNodeModule` and add `XNodeModule.class` to `@Module(includes = {…})` |
| 4 | `loom-shared/node-model/.../spec/XDescriptorProvider.java` **+** the `META-INF/services/io.metaloom.loom.nodes.spec.NodeDescriptorProvider` file | the descriptor (ports, parameters, category, icon) and its ServiceLoader registration |

**And update the guard test:** `NodeDescriptorServiceLoaderTest` asserts an exact provider count and
kind count. Adding a descriptor bumps both (`26→27` providers, `41→42` kinds at the time of writing) —
update both `assertEquals` literals, and the "**N providers declare M kinds**" line in
[NODES.md §8](../features/pipeline-nodes/NODES.md). This test failing is the intended tripwire, not a
regression.

> 🔴 **The descriptor's port ids, content types and cardinalities must equal the node's
> `InputPort`/`OutputPort` constants.** Nothing enforces this yet, so a typo produces a node that
> validates its own graph wrongly. Copy the ids across by hand and double-check them. Content types come
> from `ContentTypeRegistry` (`TEXT_ANY`, `MEDIA_IMAGE`, `ARTIFACT_VIDEO`, `SCALAR_STRING`, …).

---

## 3. Tests (from [CODING.md](CODING.md))

Mirror the sibling's test set. For a sidecar node the four classes are (see
`cortex/nodes/video-generation/core/src/test`):

- `XNodeTest` — mock the client; assert the happy path writes the artifact under `<name>_bin`, emits
  the output ports, both modes hit the right endpoint, non-processable media self-skips, and a second
  run is served from the cache (client hit **once**).
- `XNodePersistenceTest` — mock `LoomHttpClient`; assert exactly one `asset_node_result` row with the
  right `nodeKind`/`state`/`origin` (and `resultRef == null` for ledger-only), and a FAILED row when
  the sidecar throws.
- `XOptionsValidationTest` — the `validate()` contract: defaults valid, each invalid field reported.
  Use the generated `assertj` helpers.
- `XNodePipelineTest extends AbstractNodeChainTest` — spy the node, stub `compute`, assert adapter
  integration: completion/tracking events, output chaining into a `CapturingNode`, disabled + dry-run
  skip.

Plus the two `assertj` helpers (`XNodeAssertions extends NodeAssertions`, `XOptionsAssert extends
AbstractCortexNodeOptionsAssert`).

**Run them** with `mvn -pl cortex/nodes/<name>/... test -o` (install deps once with `-am -DskipTests`
first — some unrelated `-am` modules have flaky testcontainer tests). Then compile `cortex/cli`
(`mvn -pl cortex/cli -am compile -o`) to prove the **Dagger graph still resolves** with the node wired
in.

---

## 4. Customer-facing + spec + demo (the rest of done)

- **Website docs** — `website/content/english/docs/nodes/<kind>/index.adoc`, plus three edits to
  `website/content/english/docs/nodes/_index.adoc`: the category table row, the "Requirements at a
  Glance" row, and a "Processing Capabilities" paragraph. Keep it customer-facing: no spec references,
  no internal class names, SVG/diagrams not ASCII art (per [CODING.md](CODING.md)). Reuse the
  `ml-nodeviz` port-diagram block from a sibling page. If the node pulls a new model, add its licence
  to `docs/legal/model-licenses/`.
- **Spec** — add the node to [NODES.md](../features/pipeline-nodes/NODES.md): the node-list table
  (§3), the persistence table (§2), and the descriptor counts (§8).
- **Demo data** — `DemoDatabaseInitializer` seeds demo pipelines. Add the node to a demo pipeline
  graph **when it fits an existing ingest/publish story**; the generative sidecar nodes
  (`imagegen`, `tts`, `depthmap`, `videogen`) are currently left out of demo pipelines because they
  need a running GPU sidecar the demo container has no access to — follow that precedent and note it
  rather than seeding a pipeline that cannot run.
- **Sidecar** — GPU/model runtimes live in `metaloom/sidecars/<name>-sidecar/` (FastAPI, one free
  port after the last one; `videogen` → LTX-2 → `9220`). The Java node is a **pure HTTP client** of
  it. See the sidecars README for the port table and the heavy-GPU template.

---

## 5. Conventions and gotchas

- **Clean-rebuild after a constructor change.** Adding a constructor arg to a node (e.g. a new
  injected client) needs `cortex/core` rebuilt, or `setup-pool`/tests fail with `NoSuchMethodError`
  against the stale Dagger factory.
- **Force HTTP/1.1** in every sidecar client (`HttpClient.newBuilder().version(HTTP_1_1)`).
- **Never add a `"nodeId:outputKey"` option.** Upstream data arrives through a declared input port and
  an edge the pipeline author draws. This whole option family is being deleted (see
  [NODES.md §5](../features/pipeline-nodes/NODES.md)).
- **Cardinality is behaviour, not decoration.** A `ONE` input fed by a `MANY` output runs the node
  once per element (per-face, per-paragraph); a `MANY` input gathers the branch and runs once.
- **The `KEY` constant, the `@StringKey`, the `name()` return, and the descriptor `kind` must all be
  the same string.**

---

## 6. Where do I find …?

| Concept | Path |
|---|---|
| Node base class + `recordNodeResult`/`resultRef` | `cortex/common/.../node/AbstractMediaNode.java` |
| Options base + `validateCommon()` | `cortex/api/.../option/node/AbstractNodeOptions.java` |
| Port types + content-type vocabulary | `cortex/api` (`InputPort`/`OutputPort`) · `loom-shared/node-model/.../ContentTypeRegistry.java` |
| Descriptor model (`NodeDescriptor`, `PortSpec`, `NodeParameter`) | `loom-shared/node-model/.../spec/` |
| Dagger aggregation | `cortex/cli/.../dagger/NodeCollectionModule.java` · `cortex/processor/pom.xml` |
| Descriptor count guard test | `loom-shared/node-model/.../NodeDescriptorServiceLoaderTest.java` |
| Test scaffolding (`StubLoomMedia`, `AbstractNodeChainTest`, `CapturingNode`) | `cortex/pipeline` test-jar |
| Worked example (this guide, applied) | `cortex/nodes/video-generation` |
