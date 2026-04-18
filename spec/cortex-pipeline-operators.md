# Cortex Pipeline — Operators Analysis

## Context

The rx-pipeline PoC demonstrates a reactive pipeline where the `LLMNode` manually combines two upstream `Single` signals (face detection + image description) via `Single.zip` before processing. This raises the question: does the cortex pipeline need dedicated operator nodes (zip, split, merge, buffer) in the UI graph, or can these be handled implicitly?

## Key Insight: The Executor Already Handles Multi-Input

In the cortex pipeline, a node's process signature is:

```java
NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults);
```

The `DAGPipelineExecutor` already:
1. Waits for **all** dependency futures via `CompletableFuture.allOf(...)` before scheduling a node
2. Collects results from all upstream nodes into the `upstreamResults` map
3. Passes the combined map to the downstream node

This means the zip/combine behavior from the PoC's `Single.zip(faceSignal, descriptionSignal)` is **already built into the executor**. A node with two parents automatically receives both results, keyed by node ID, and can read whichever upstream outputs it needs. The user does not need to think about how to combine inputs — the executor does it automatically.

## Operator Analysis

### Split (Fan-Out)

**No dedicated operator needed.** A split is simply one node connected to multiple downstream nodes via `connectTo()`. In the UI, the user draws additional edges from a node's output to other nodes' inputs.

Already supported:
```java
hashNode.connectTo(thumbnailNode);
hashNode.connectTo(embeddingNode);
```

### Buffer

**No dedicated operator needed.** Buffering is an internal concern of the node or the executor. Per-node concurrency is already controlled via `concurrency()` and the semaphore in the executor. The `bufferSize()` from the rx-pipeline PoC maps to the executor's per-node semaphore and the `flatMap` concurrency parameter in the reactive `apply()` method.

If a node needs to batch items (e.g., accumulate N embeddings before a bulk write), that is node-specific logic inside `process()` or a custom `apply()` override — not a graph-level concern.

### Merge / Zip (Fan-In)

**No dedicated operator needed.** This is the most interesting case. In the rx-pipeline PoC, the `LLMNode` explicitly zips two `Single` streams. In the cortex pipeline, this happens automatically:

- A node declares its dependencies by being connected from multiple parents
- The executor waits for all parents to complete
- The node receives `upstreamResults` containing all parent outputs
- The node reads whatever it needs: `upstreamResults.get("facedetection").getOutput("faces")`, `upstreamResults.get("visual-llm").getOutput("description")`

The merge/combine logic is **inherently node-specific** — the node must know *what* to do with the merged data (e.g., pair a face identity with a scene description). A generic "merge operator" would not add value because it cannot know the domain semantics. The node itself is the merge operator.

### Conditional Branching (Filter Fan-Out)

**Already modeled.** The `FilterBranch` enum (PASS / REJECT / ANY) and `connectTo(downstream, FilterBranch)` allow a filter node to route media down different paths. This is a specialized split and is already first-class in the pipeline API.

## Filter Nodes: Generic vs. Domain-Specific

Filter nodes are the one case where dedicated node classes *do* exist as operators — but they are still regular `PipelineNode` implementations, not a special graph-level operator concept. The distinction is where they live:

### Generic Filters (pipeline-core)

Filters that operate on media-level properties and have no knowledge of upstream node output schemas. These live in `pipeline-core` because they are universally applicable:

- `MimeTypeFilterNode` — filters by MIME type
- `DateFilterNode` — filters by file creation/modification time
- `SizeFilterNode` — filters by file size
- `DuplicateFilterNode` — filters already-processed media
- `BlacklistFilterNode` — filters by upstream text output against a term list (generic because it reads arbitrary `String` outputs via `UpstreamOutputKey`)

### Domain-Specific Filters (node module)

Filters that inspect the *typed output* of a specific upstream node must understand that node's output schema. These **belong in the same Maven module** as the node they depend on, because:

1. They need compile-time access to the node's output key constants and model types
2. They are only meaningful when that node is present in the pipeline
3. They share configuration/option types with the parent node

Examples:
- A **face quality filter** (min confidence, min face size) belongs in the `facedetect` module — it reads `FacedetectNode.OUTPUT_FACE_COUNT` and face bounding boxes
- A **transcription language filter** belongs in the `whisper` module — it reads the detected language from the whisper output
- An **embedding similarity threshold filter** belongs in the `embedding` module — it compares vector distances

From the UI and executor perspective, domain-specific filters are indistinguishable from generic ones — they are just `PipelineNode` instances with `isPartitioning() = true`. The only difference is the Maven module they ship in and the `NodeDescriptor` they advertise (their `inputs` will declare a dependency on a specific content type like `data/face_embedding` rather than `media/*`).

## UI Implications

Since no dedicated operator nodes are needed, the pipeline UI can enforce correctness through **constraints and visual feedback** rather than requiring the user to insert operator nodes:

| Scenario | UI Behavior |
|---|---|
| Node expects 2+ inputs but only 1 is connected | Node shows a warning (e.g., red border, missing-input indicator) |
| Node has 0 inputs and is not the source | Validation error — node is unreachable |
| Fan-out (split) | User draws additional edge from output — no extra node needed |
| Fan-in (multi-input) | User connects multiple parents to one node — executor handles the rest |
| Buffer/concurrency tuning | Exposed as a node property (slider or number field), not a graph element |

## Summary

All common reactive operators map to existing pipeline mechanics without needing dedicated graph nodes:

| Operator | Realization | Dedicated Node? |
|---|---|---|
| **Split** | Multiple edges from one node's output | No — just draw a second edge |
| **Buffer** | Internal to node or executor (`concurrency()`, custom `apply()`) | No — node property |
| **Merge / Zip** | Executor waits for all dependencies; node reads `upstreamResults` map | No — inherently node-specific |
| **Filter branch** | `connectTo(downstream, FilterBranch)` with PASS/REJECT/ANY | No — already a connection property |

The executor's dependency-driven scheduling means the user only needs to think about **what nodes to use** and **how to connect them**. The combining of matching inputs from the same asset is handled automatically by the execution engine.
