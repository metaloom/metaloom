# MetaLoom — Task Queue

Captured, not-yet-scheduled work. Every entry follows [../TASKS.template.md](../TASKS.template.md).
Completed entries are collapsed to a one-line outcome record; only open work keeps full detail.

> Note: [../CONTEXT.md](../CONTEXT.md) and
> [../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md](../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3
> still link this file as `spec/tasks/TASKS.md`. The real path is `spec/plans/TASKS.md`.

## Progress Assessment

- [ ] Cache expensive intermediate artifacts inside node implementations — **open**, verified
      unimplemented at `2e5981cb` (see below)
- [x] `imagegen` node + Ideogram sidecar ([imagegen-node.md](imagegen-node.md)) — shipped

## Completed

| Task | Outcome |
|---|---|
| `imagegen` node + Ideogram 4.0 Python sidecar ([imagegen-node.md](imagegen-node.md)) | Shipped. Node in `cortex/nodes/image-generation/core/…/imagegen/` (`ImageGenNode`, `ImageGenClient`, `ImageGenNodeOptions`, `ImageGenMode` for `GENERATE`/`REMIX`, `ImageGenNodeModule`), wired via `cortex/cli/…/dagger/NodeCollectionModule.java`; unit/pipeline/persistence/options tests plus `integration-test/…/node/ImageGenNodeIntegrationTest.java`; sidecar in `sidecars/ideogram-sidecar/`; documented in [../features/pipeline-nodes/NODES.md](../features/pipeline-nodes/NODES.md) and `website/content/english/docs/nodes/imagegen/`. The plan file's own status header (“proposed”) is stale. Its “Loom binary upload” follow-up is now tracked in [../features/rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md](../features/rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md) — not here. |

---

## Task: Cache expensive intermediate artifacts inside node implementations

**Argumentation Summary:** A node receives its upstream dependencies' *outputs* and nothing
else. There is no place to hand an expensive intermediate artifact to the node that runs next,
so every node needing decoded frames decodes them again: a five-node graph opens the same file
five times. Affinity segments put those nodes in one process — the precondition for sharing —
but sharing does not happen because nothing in the API expresses it.

**Improvement Summary:** Let a node implementation cache expensive artifacts (decoded frames,
extracted audio, parsed documents) so a later node in the same segment reuses them. The cache
belongs to the node implementation, not the engine: the engine cannot know what is expensive,
how large it is, or when it stops being valid, and guessing on a node's behalf is how caches leak.

**Verified state at `2e5981cb` — still open.** What exists is *result* caching only, not artifact
caching:
- `cortex/common/…/common/cache/LocalResultCache.java` — bounded per-node LRU of already-computed
  **results**, keyed by path/SHA-512; used by ~20 nodes to skip recomputation on a second pass.
- `cortex/pipeline-api/…/api/cache/NodeCacheProvider.java` with `HeapNodeCache`,
  `SidecarFileNodeCache`, `XAttrNodeCache`, `LayeredNodeCache`, `NoOpNodeCache` in
  `pipeline-common` — also `NodeResult`-shaped, and `PipelineNode.cacheProvider()` is never called
  by any executor, so it is currently dead wiring worth resolving alongside this task.
- `SegmentTaskRunner`'s own javadoc states the gap outright: segment dispatch measured 1.01× vs.
  per-node dispatch, and “genuine decode-once would need a shared per-segment context … which this
  API does not have”.

```
Design a caching mechanism a node implementation can use to reuse expensive
intermediate artifacts within one segment execution on one worker.

Context:
- `SegmentTaskRunner` (cortex/node-runtime) runs several nodes over one media
  item in one process, in dependency order. That is the only place where sharing
  is possible at all - nodes in different segments run on different workers and
  possibly at different times.
- `PipelineNode.process(LoomMedia, NodeInputs)` gives a node its upstream port
  payloads. Those outputs are serialised back to Loom, so they are the wrong home
  for a 200 MB frame buffer.
- The media handle (`LoomMedia`) is resolved once per segment, but it is a
  lightweight file reference, not a decoded artifact.
- `NodeCacheProvider` / `LocalResultCache` cache results, not artifacts. Decide
  whether artifact caching extends one of them or is a separate concept, and
  either use or delete the unused `PipelineNode.cacheProvider()` hook.

Questions the design must answer:
- Who owns the cache - the node instance, the segment execution, or the worker?
- What is its lifecycle? An artifact valid for one segment execution is not
  necessarily valid for the next item; one that outlives the segment needs an
  eviction policy and a memory ceiling.
- What happens when a node in the segment fails, or the segment is retried after
  a lease expiry? A cache surviving into a retry may be exactly what is wanted,
  or may be the reason the retry fails the same way.
- Does anything need to change in `PipelineNode`, or can this live entirely
  inside node implementations that opt in?

Explicit non-goal: do not add a general-purpose cross-run or cross-worker cache.
The scope is one segment, one item, one process.
```

**References:**
- [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) §14 — how segments run
  and what a worker receives
- [../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md](../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3 —
  recorded as an answered question: intermediate results live in the node implementation's own cache
- `cortex/node-runtime/src/main/java/io/metaloom/cortex/runtime/SegmentTaskRunner.java`
- `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/node/PipelineNode.java`
- `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/cache/NodeCacheProvider.java`
- `cortex/common/src/main/java/io/metaloom/cortex/common/cache/LocalResultCache.java`

**Test Requirements:**
- A segment of two nodes needing the same expensive artifact produces it once
  (extend `cortex/node-runtime/src/test/java/io/metaloom/cortex/runtime/SegmentTaskRunnerTest.java`)
- The cache does not leak between media items — item B must not see item A's artifact
- A failed or retried node does not serve a stale artifact to the nodes after it
- Memory does not grow without bound across a long run

---

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (verified the queue against the code: the caching task is still open, imagegen collapsed to an outcome record)_
