# MetaLoom // Cortex — Custom Node Example

This example shows how to write a **custom Cortex node** and persist its result **agnostically**
into Loom's generic `asset_json_comp` table — without pulling in any heavy database libraries.

The node ([`HelloWorldNode`](./src/main/java/io/metaloom/cortex/node/hello/HelloWorldNode.java))
reads a media file, computes its size and a naive word count, emits those on two declared **output
ports**, and — when running online — posts the result to Loom over REST.

**Your node appears in the pipeline editor automatically.** Annotate it, drop the jar on a worker's
classpath, and it is placeable — no change to Loom, and no Loom rebuild. See
[Reaching the pipeline editor](#reaching-the-pipeline-editor) below.

## The node contract

A node extends `AbstractMediaNode<O>` and implements two methods:

```java
public class HelloWorldNode extends AbstractMediaNode<HelloWorldNodeOptions> {

    @Override
    public String name() {
        return "hello-world"; // the node kind
    }

    @Override
    protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
        return true; // narrow this to images/video/etc. if you like
    }

    @Override
    protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
        // ... compute, publish outputs, persist ...
    }
}
```

`AbstractMediaNode` handles the lifecycle for you: the *enabled* check, the *file exists* check, the
*is-processable* check, and (in online mode) fetching the `AssetResponse` from Loom before
`compute(...)` runs. In **offline mode** (no `LoomClient` configured) `asset` is `null` and the node
simply skips the remote persistence — which is exactly what the unit tests exercise.


## Reaching the pipeline editor

A node used to be *runnable but unauthorable*: Loom would dispatch tasks to it, because the worker
said it could run `hello-world`, but the editor could not place it and the graph parser rejected it
as an unknown type. The contract lived in Loom's own jar, so a third-party node had nowhere to put
one.

The worker now **announces** its contracts. Three pieces make that work:

### 1. Annotate the node

```java
@NodeSpec(nodeId = "hello-world", name = "Hello World", icon = "description",
    category = NodeCategory.ANALYSIS,
    description = "Example custom node: reports a file's size and estimated word count.")
public class HelloWorldNode extends AbstractMediaNode<HelloWorldNodeOptions> {

    @PortDoc(label = "Hash", description = "An SHA-256 hash produced upstream.", required = false)
    public static final InputPort<String> IN_HASH =
        InputPort.one("hash", ContentTypeRegistry.HASH_SHA256, String.class);
```

Note what is **not** in the annotation: no port list, no content types, no cardinalities. Those are
read off the `InputPort`/`OutputPort` constants the node already executes against, so the contract
cannot drift from the code. The annotation carries only what reflection cannot know — display names,
descriptions, an icon, a category.

### 2. Annotate the options

```java
@ParamDoc(label = "Compute File Size", description = "Emit the file_size output port")
private boolean computeFileSize = true;
```

The parameter key is the field name, the type is the field type, and the default is whatever a
default-constructed options instance holds. `enabled`, `processIncomplete` and `retryFailed` come
from `AbstractNodeOptions` and are declared once there, for every node.

### 3. Register a `NodeSpecSource`

Cortex knows its own built-in nodes by name and finds yours through `ServiceLoader`:

```java
public class HelloWorldNodeSpecSource implements NodeSpecSource {
    public Collection<Class<?>> nodeClasses() {
        return List.of(HelloWorldNode.class);
    }
}
```

…listed in
`src/main/resources/META-INF/services/io.metaloom.cortex.api.node.spec.NodeSpecSource`.

Return **class literals**, never instances: a class literal does not run the class's static
initializer, so listing a node here costs nothing even if it loads native libraries.

### What happens at runtime

```
worker starts
  └─ REGISTER            ─▶ Loom: "I can run hello-world"      (this is what dispatch reads)
  ◀─ REGISTERED
  └─ NODE_REGISTRATION   ─▶ Loom: "and here is what it looks like"
  ◀─ NODE_REGISTRATION_ACK  per-node: accepted, or rejected with a reason
```

The contract is then **durable**. Stop the worker and the node stays in the palette, greyed out and
labelled offline — a pipeline that uses it still opens, still validates and still saves. It simply
cannot run, which a run request answers with a 503 naming the missing worker. That split is
deliberate: deleting contracts when a worker disconnects would turn a 30-second restart into "your
saved pipeline no longer validates".

### Things worth knowing

| | |
|---|---|
| **Built-in wins** | Announcing a contract for a node id Loom already ships is rejected with reason `BUILTIN` and the announcement is ignored. The rejection is reported in the ack — check your worker log if an edit seems to have no effect. |
| **Icons are a fixed set** | `icon` is a key into a compile-time map in the editor. An unknown name falls back to the category icon, so your node still renders — it just cannot introduce a new icon. |
| **Content types are free** | A port may name a content type nobody has ever declared (`struct/nsfw`). Assignability is structural, so it connects correctly and Loom synthesizes a label for the editor. |
| **Lowest version wins** | When several workers offer one node on different versions, Loom serves the **lowest** — the contract every worker in the fleet can honour. A port that only exists on newer workers appears once the last old one is gone. |
| **Turning it off** | `CORTEX_NODE_SPEC_ANNOUNCE=false` restores the previous behaviour exactly: the worker still registers and still runs everything it could before, but nothing Loom does not itself ship stays authorable. |


## Declaring ports

A node's data contract is a set of **typed ports** declared as public static constants. The port id
belongs to the node; the *edge* decides which upstream node fills an input:

```java
public static final InputPort<String> IN_HASH =
    InputPort.one("hash", ContentTypeRegistry.HASH_SHA256, String.class);

public static final OutputPort<Long> OUT_FILE_SIZE =
    OutputPort.one("file_size", ContentTypeRegistry.SCALAR_INTEGER, Long.class);
public static final OutputPort<Long> OUT_WORD_COUNT =
    OutputPort.one("word_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);
```

Read and write them through the context — never by string key:

```java
String hash = ctx.input(IN_HASH);        // null when nothing is wired
ctx.output(OUT_FILE_SIZE, media.size());
```

Use `OutputPort.many(...)` / `ctx.outputElement(...)` when a node fans out (one element per
detection, per paragraph, …), and read the matching side with `ctx.inputs(PORT)`.

## Persisting the result into `asset_json_comp`

Instead of a dedicated component table, the node writes an **opaque JSON payload** to the generic
sink via a single thin REST call:

```java
if (!isOfflineMode() && asset != null) {
    JsonObject data = new JsonObject()
        .put(OUT_FILE_SIZE.id(), fileSize)
        .put(OUT_WORD_COUNT.id(), wordCount);
    JsonCompCreateRequest request = new JsonCompCreateRequest()
        .setNodeKind(name())         // "hello-world"
        .setSchemaType(SCHEMA_TYPE)  // shape label for the payload
        .setData(data);
    client().createAssetJsonComp(asset.getUuid(), request).sync();
}
```

Loom upserts a row in `asset_json_comp` keyed by `(asset, node_kind, schema_type, variant)`, so
re-running the node rewrites its single row rather than accumulating duplicates. This is the
**lightweight, customer-facing** persistence path: your node only depends on the Cortex node API and
the Loom REST client — never on the Loom database.

> **Promotion policy:** start in `asset_json_comp`. If you later need to *query* on a field inside
> the payload, render it as a first-class object in the UI, or reference it with a foreign key,
> graduate that node kind to a typed component table.

## Registering the node

Nodes are contributed to a Cortex instance through a Dagger module
([`HelloWorldNodeModule`](./src/main/java/io/metaloom/cortex/node/hello/HelloWorldNodeModule.java)):

```java
@Module
public abstract class HelloWorldNodeModule extends AbstractNodeModule {

    @Binds
    @IntoSet
    abstract FilesystemNode<?, ?> bindHelloWorldNode(HelloWorldNode node);

    @Provides
    @IntoSet
    static CortexNodeOptionDeserializerInfo optionInfo() {
        return new CortexNodeOptionDeserializerInfo(HelloWorldNodeOptions.class, HelloWorldNodeOptions.KEY);
    }

    @Provides
    static HelloWorldNodeOptions options(CortexOptions options) {
        return nodeOptions(options, HelloWorldNodeOptions.KEY, new HelloWorldNodeOptions());
    }
}
```

The [`cortex-custom`](../cortex-custom) daemon example depends on this module and includes it in its
node set — see that module's README.

## Testing a node

Nodes are easy to unit-test in **offline mode** — pass a `null` client and drive `process(...)`
directly, asserting on `result.getState()` and `result.get(PORT)`. Seed input ports with
`NodeInputs.builder().input(PORT, value).build()`. See
[`HelloWorldNodeTest`](./src/test/java/io/metaloom/cortex/node/hello/HelloWorldNodeTest.java).

```bash
mvn -pl examples/cortex-custom-node test
```

## License

Apache License, Version 2.0.
