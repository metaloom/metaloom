# MetaLoom — Task Queue

Tasks captured for later work, following [../TASKS.template.md](../TASKS.template.md).

---

## Task: Cache expensive intermediate artifacts inside node implementations

**Argumentation Summary:** Under Variant C a node receives its upstream dependencies'
*outputs* — a map of values — and nothing else. There is no place for a node to hand
an expensive intermediate artifact to the node that runs after it. For a video
pipeline that means every node needing decoded frames decodes them again: a five-node
graph opens and decodes the same file five times. Affinity groups put those nodes on
one worker and in one process, which is the precondition for sharing, but sharing
itself does not happen because nothing in the API expresses it.

**Improvement Summary:** Let a node implementation cache expensive artifacts —
decoded frames, extracted audio, parsed documents — so a later node in the same
segment reuses them instead of recomputing. The cache belongs to the node
implementation rather than to the pipeline engine: the engine has no idea what is
expensive, how large it is, or when it stops being valid, and guessing on a node's
behalf is how caches leak.

```
Design a caching mechanism a node implementation can use to reuse expensive
intermediate artifacts within one segment execution on one worker.

Context:
- `SegmentTaskRunner` (cortex/node-runtime) already runs several nodes over one
  media item in one process, in dependency order. That is the only place where
  sharing is possible at all - nodes in different segments run on different
  workers and possibly at different times.
- `PipelineNode.process(LoomMedia, Map<String, NodeResult>)` gives a node its
  upstream outputs. `NodeResult` outputs are serialised back to Loom, so they are
  the wrong home for a 200 MB frame buffer.
- The media handle (`LoomMedia`) is already resolved once per segment. It is a
  lightweight file reference, not a decoded artifact.

Questions the design must answer:
- Who owns the cache - the node instance, the segment execution, or the worker?
- What is its lifecycle? An artifact valid for one segment execution is not
  necessarily valid for the next item, and one that outlives the segment needs an
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
- [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) section 14 —
  how segments run and what workers receive
- [../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md](../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md)
  section 9 — recorded as answered question 2: the node implementation caches these
- `cortex/node-runtime/src/main/java/io/metaloom/cortex/runtime/SegmentTaskRunner.java`
- `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/node/PipelineNode.java`

**Test Requirements:**
- A segment of two nodes that both need the same expensive artifact produces it once
- The cache does not leak between media items - item B must not see item A's artifact
- A failed or retried node does not serve a stale artifact to the nodes after it
- Memory does not grow without bound across a long run
