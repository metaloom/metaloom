# MetaLoom // Cortex — Custom Node Example

This example shows how to write a **custom Cortex node** and persist its result **agnostically**
into Loom's generic `asset_json_comp` table — without pulling in any heavy database libraries.

The node ([`HelloWorldNode`](./src/main/java/io/metaloom/cortex/node/hello/HelloWorldNode.java))
reads a media file, computes its size and a naive word count, emits those on two declared **output
ports**, and — when running online — posts the result to Loom over REST.

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
