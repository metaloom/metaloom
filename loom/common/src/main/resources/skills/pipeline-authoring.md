# Authoring a Loom pipeline

A pipeline is a directed graph of processing nodes that Loom runs over media items. You
author one as a JSON **definition**, and Loom stores it as version 1 of a new pipeline.

Do not write a definition from memory. The set of node kinds, their ports and their options
differ between deployments — a kind exists only if this Loom's descriptor registry knows it.
Always discover, then draft, then validate, then store.

## The workflow

1. `list_node_descriptors` — see which node kinds exist here. Filter by `category` or
   `query` rather than listing everything.
2. `get_node_descriptor` for **every** kind you intend to use. This is the only way to learn
   a node's real port ids, content types, cardinalities and options. Guessing a port id is
   the most common way to produce a definition that will not save.
3. Draft the definition.
4. `validate_pipeline` — checks the draft without storing anything. Fix what it reports and
   validate again. Repeat until it answers `VALID`.
5. `create_pipeline` (or `update_pipeline` to append a new version to an existing pipeline).

**Never call `create_pipeline` on a draft you have not validated.** Validation is free and
leaves no trace; a rejected create is a wasted round trip, and a definition that saves is a
definition that runs.

## The definition

```json
{
  "version": 1,
  "resultBatchSize": 1,
  "reuseResults": false,
  "nodes": [
    { "id": "pn1", "type": "filesystem-source", "name": "Media Source", "source": true },
    { "id": "pn2", "type": "sha512",  "name": "Checksum" },
    { "id": "pn3", "type": "facedetect", "name": "Faces", "syncToLoom": true,
      "options": { "minScore": 0.6 } },
    { "id": "pn4", "type": "facedescription", "name": "Describe faces" }
  ],
  "edges": [
    { "id": "pe1", "source": "pn1", "sourcePort": "media",      "target": "pn2", "targetPort": "media" },
    { "id": "pe2", "source": "pn1", "sourcePort": "media",      "target": "pn3", "targetPort": "image" },
    { "id": "pe3", "source": "pn3", "sourcePort": "detections", "target": "pn4", "targetPort": "detections" }
  ]
}
```

Top level: `version` (integer, currently `1`; omit it and Loom stamps it), `nodes` (required,
at least one), `edges` (optional — a single-node pipeline is legal), `resultBatchSize`
(default `1`) and `reuseResults` (default `false`).

Node fields:

| Field | Meaning |
|---|---|
| `id` | Graph-local id. Must match `^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$` and be unique. Not the node kind. |
| `type` | The node kind, exactly as `list_node_descriptors` reports it. |
| `name` | Human label shown in the editor. Optional. |
| `source` | `true` on the one source node. |
| `blocking` | Default **`true`** — a failure stops dependents. Set `false` only when downstream work is genuinely optional. |
| `syncToLoom` | Default `false`. `true` writes this node's outputs onto the asset in the catalog. |
| `affinity` | Group label; nodes sharing one may be dispatched to a worker together. A hint, never a requirement. |
| `options` | Per-instance node options. Keys must be parameters the descriptor declares. |

`x` and `y` are editor-only layout hints and are ignored by the server. Do not invent other
fields; unknown node option keys are rejected.

## Edges are port-to-port

**Every edge must carry `sourcePort` and `targetPort`.** There is no positional inference and
no fallback. A `nodes[].dependencies[]` array is rejected outright — do not emit one.

- `branch` is optional, one of `ANY`, `PASS`, `REJECT`. The key is `branch`, not `edgeType`.
  A `PASS`/`REJECT` edge may only leave a node whose category is `FILTER`.
- Two edges between the same pair of nodes are distinct as long as they use different ports.

## Rules the validator enforces

**Exactly one source.** One node with `"source": true`. Several is an error; with none, the
graph must have exactly one dependency-free root. A source's output port is `media`.

**Every node must be reachable from the source.** A disconnected node can never be
dispatched, so it is rejected rather than ignored.

**No cycles.**

**Content types must be assignable.** A type id is always `family/subtype`, and the families
are `media`, `text`, `detection`, `hash`, `scalar`, `artifact`, `struct`, `control`. An edge
is allowed when the two types are equal, or the consumer accepts the whole family
(`media/*`), or the producer is unspecific (a source emits `media/*` into a `media/image`
input — settled per item at run time). **Assignability never crosses families and sibling
subtypes are not assignable**: `media/image` does not satisfy `media/video`, and a
`hash/sha512` does not satisfy a `scalar/string`.

**Required inputs must be wired.** A port is required unless the descriptor says otherwise.
Source nodes are exempt.

**Port groups.** An `XOR` input group means *exactly one* of its members when the group is
required, *at most one* otherwise. Several nodes declare a `media_alt` group with `image` and
`video` members — wire one, never both.

**One edge into a `ONE` input.** An input port that takes several incoming edges must declare
cardinality `MANY`.

## Fan-out and gather

A node whose output declares `MANY` emits one element per item — `facedetect.detections` is
one element per detected face. Wiring a `MANY` output into a `ONE` input makes the consumer
run **once per element**. Wiring it into a `MANY` input makes the consumer run **once**, over
the whole sequence: that is the gather, and it is implicit. There is no merge node to place.

Two things are rejected:

- **Nested fan-out** — a node that runs per element and also declares a `MANY` output. Gather
  with a sequence input first.
- **Zipping unrelated sequences** — two `ONE` inputs of one node fed from different fan-out
  drivers. The elements have no correspondence.

## Reading validation errors

`validate_pipeline` answers `VALID` (possibly with warnings) or `INVALID: <message>`. It
reports the **first** problem, so fix and re-run rather than trying to guess the rest.
Messages name the node and the port, for example:

```
INVALID: node 'pn3' has no input port 'media'
INVALID: cannot connect pn1.media (media/*) to pn4.detections (detection/face): incompatible content types
INVALID: node 'pn4' requires input 'detections' (detection/face) but nothing is connected
INVALID: node 'pn2' input port 'media' takes one element but has 2 incoming edges; declare it as a sequence to accept several
```

When a port does not exist, call `get_node_descriptor` for that kind again and use the ids it
reports — do not try a synonym.

**Warnings are not errors.** "No online worker currently accepts these node kinds" means the
definition is sound but a run started right now would be refused because the worker that
handles that kind is offline. Save it anyway unless the user asked for something runnable
today; say so in your answer.

## Storing it

`create_pipeline` takes `name` and `definition`, plus optional `description`, `enabled`,
`dryRun` and `priority`. It creates the pipeline and its version 1 and returns the uuid.

`update_pipeline` takes `pipelineId` (a uuid or a pipeline name) and any subset of the same
fields. It **appends a new version**; it never edits an existing one, and any field you leave
out is carried forward from the current version. Use `get_pipeline` first when you intend to
modify an existing graph rather than replace it — you need the current nodes and edges to
change one of them.

Both need an authenticated caller. Running a pipeline is a separate action, and there is no
tool for it: tell the user the pipeline is ready and let them start the run.
