# MetaLoom // Cortex — Examples

This directory contains two small, **customer-facing** examples that show how to extend MetaLoom
Cortex with your own processing logic and run it against a Loom backend.

They are deliberately lightweight: your code compiles against the slim Cortex node API and persists
its results through the Loom **REST client** — there is no dependency on the Loom database, jOOQ, or
Postgres.

| Module | What it shows |
|---|---|
| [`cortex-custom-node`](./cortex-custom-node) | How to **write a custom node** whose result is persisted agnostically into the `asset_json_comp` table via a thin Loom REST call. |
| [`cortex-custom`](./cortex-custom) | How to **assemble a custom Cortex daemon** that *includes* that node and connects to a Loom backend. |
| [`cortex-python`](./cortex-python) | How to implement a minimal **Cortex worker in Python** that speaks Loom's wire protocol directly — registers, receives `NODE_TASK`s, runs a node, and reports results. |

## How the two fit together

```
cortex-custom-node            cortex-custom (daemon)
──────────────────            ──────────────────────
HelloWorldNode        ◀── included by ──   NodeCollectionModule / PipelineNodeFactoryModule
  │                                          │
  │ computes a result                        │ assembles a Cortex instance
  ▼                                          ▼
POST /api/v1/assets/:uuid/json-comps  ──▶  Loom backend  ──▶  asset_json_comp
```

1. **Author a node** (`cortex-custom-node`). A node extends `AbstractMediaNode`, computes something
   from a media file, and — when running online — posts an opaque JSON payload to Loom. Loom stores
   it in the generic `asset_json_comp` sink keyed by `(asset, node_kind, schema_type, variant)`.
2. **Assemble an instance** (`cortex-custom`). The daemon registers your node module alongside the
   built-in Cortex nodes, connects to a Loom backend, and runs in the foreground until stopped.

## The persistence path

Node results are stored **agnostically** — Loom does not need a dedicated table per node kind. The
node calls the slim, customer-facing endpoint:

```
POST   /api/v1/assets/:uuid/json-comps        # upsert a generic JSON component
GET    /api/v1/assets/:uuid/json-comps        # list them
GET    /api/v1/assets/:uuid/json-comps/:uuid  # load one
DELETE /api/v1/assets/:uuid/json-comps/:uuid  # delete one
```

The request body carries `nodeKind`, `schemaType`, an optional `variant`, and an opaque `data`
object. Re-posting the same `(nodeKind, schemaType, variant)` for an asset upserts the single row.

## Building

```bash
# Build both examples (from the repo root)
mvn -pl examples/cortex-custom-node,examples/cortex-custom -am install

# Run the tests
mvn -pl examples/cortex-custom-node,examples/cortex-custom test
```

See each module's own README for details on authoring a node and running the daemon.

## License

Apache License, Version 2.0.
