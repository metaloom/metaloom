# MetaStorage — Feedback & Should-It-Stay Analysis

> ## ✅ Actioned (2026-07-24)
>
> The core recommendation of this review — **drop the elaborate storage system,
> keep hashes in xattr, use a lightweight cache for the rest** — has been
> implemented:
>
> - **Hashes stay in xattr.** SHA-512 persistence was already independent of
>   MetaStorage (`LoomMediaImpl` → `XAttrUtils`, xattr `loom_sha512`); it is
>   untouched.
> - **`FingerprintNode` now uses a lightweight in-memory LRU cache** as its skip
>   cache instead of `FingerprintMetaStorage`.
> - **The entire MetaStorage subsystem was deleted** — interface, impl, four
>   backends (incl. the buggy HEAP one), keys, `MetaDataStream`, both
>   `LoomStorageModule`s, and all eight `*MetaStorage` decorators. See the status
>   banner in [METASTORAGE.md](METASTORAGE.md).
>
> This resolves findings §2.1, §2.2, §3.1–§3.8 (by removal) and §3.10. See the
> updated action list in [§5](#5-prioritised-action-list). What did **not** ship:
> a durable local cache for offline/CLI mode (§4.2 item 2) and the shutdown-flush
> fix (§5 #1) — both remain open and are the recommended follow-ups.
>
> The review text below is retained as the rationale that led to the change.

---

> Review of the MetaStorage subsystem described in
> [METASTORAGE.md](METASTORAGE.md), verified against the code on 2026-07-24.
> The first half is a defect/design review. The second half
> ([§4](#4-is-this-storage-system-still-needed)) answers the harder question the
> task actually asks: **now that Loom is the system of record, is a local typed
> storage system with four pluggable backends still warranted — and what would
> it mean to move node results into JVM heap and sync them to Loom
> periodically?**
>
> Severity tags: **HIGH** (correctness or an active liability), **MED**
> (maintainability / drift risk), **LOW** (cosmetic / cleanup).

---

## 1. Executive summary

MetaStorage is a **well-designed answer to a problem Cortex no longer has.** It
was the local result store for a standalone tool with no back-end. Since then:

- **Loom became the system of record.** Results now travel to Loom as typed
  asset components plus a `node_result` ledger (see
  [../pipeline-nodes/NODES.md](../pipeline-nodes/NODES.md) §2). That is the
  durable copy.
- **The pipeline grew its own cache** (`NodeCacheProvider`) for the only job
  MetaStorage still meaningfully does in the DAG — *skip-if-already-computed* —
  and the node base class (`AbstractMediaNode`) explicitly points nodes at *that*
  cache, not MetaStorage.

The result is **three overlapping persistence systems** and a MetaStorage layer
that is now almost entirely dormant: **one** production node (`FingerprintNode`)
still uses it; the other seven `*MetaStorage` classes are test-only or dead
([METASTORAGE.md](METASTORAGE.md) §8). The subsystem carries four backends, a
streaming abstraction, and a Dagger multibinding to serve a single xattr
skip-check.

My recommendation, argued in §4: **do not keep it as-is.** Collapse it to a
single small, optional *local cache* interface used only where Cortex runs
detached from Loom (offline CLI), and let Loom + the pipeline cache own
everything else. A full move to "heap + periodic sync" is attractive for the
*hot path* but must not be the *durability* story — the periodic-sync window is a
data-loss window, and Cortex already loses buffered results on `SIGTERM` today.

---

## 2. The structural problem: three stores, no owner

### 2.1 Overlap and ambiguity — HIGH

Three systems can each persist a node result, with no documented rule for which
owns what:

| System | Skip-if-done? | Durable? | Delivers to Loom? |
|---|---|---|---|
| MetaStorage | yes (per node, manual) | on local FS/xattr | no |
| `NodeCacheProvider` | yes (executor, automatic) | heap/xattr/sidecar | no |
| Loom sync | no | yes (Postgres) | yes |

A new contributor writing a node has to choose between MetaStorage and
`NodeCacheProvider` for the *same* skip-cache purpose, and the two even share
backends (both have an "xattr" and a "sidecar/FS" implementation with **different
serialisation formats and different key schemes**). This is the same "two systems
persist node results, unify or document" concern already flagged in
[NODES.md](../pipeline-nodes/NODES.md) §10 — this document is the follow-through.

**The abstraction is not paying for itself.** Four backends, a `LoomMetaType`
extension point, and a decorator per domain exist to serve one live caller.

### 2.2 The base class already voted against it — HIGH

`AbstractMediaNode`'s Javadoc states the "already processed?" check is the
*pipeline cache's* job "not … the node itself," and tells nodes to persist via
`ctx.output(...)`. So the canonical node lifecycle **bypasses MetaStorage by
design**. `FingerprintNode` using it is now the exception, not the pattern — and
even it double-writes (xattr *and* `ctx.output`). Either the guidance is wrong or
`FingerprintNode` is; today they contradict each other.

---

## 3. Concrete defects

### 3.1 HEAP handler ignores media identity — HIGH (latent)

`AbstractCachingLoomTypeHandler` (the HEAP backend) keys its Caffeine cache on
`metaKey.fullKey()` **only** — the `LoomMedia` argument is never consulted:

```java
public <T> void put(LoomMedia media, LoomMetaKey<T> metaKey, T value) {
    String fullKey = metaKey.fullKey();      // media is ignored
    attrCache.put(fullKey, value);
}
```

Every media item therefore collides on a single entry per key: writing a value
for file A and reading it for file B returns A's value. This is only "safe"
today because **no production key uses `HEAP`** — but the moment someone picks
`HEAP` for a real key (an obvious choice for a cheap scalar) they get silent
cross-file corruption. Either fix the key to include the media hash or delete the
backend (§3.3).

### 3.2 `append`/`getAll` are a leaky abstraction — MED

The `MetaStorage` interface advertises `append`/`getAll`, but **only AVRO
implements them**; XATTR, FS, and HEAP throw `RuntimeException` at runtime. A
caller cannot rely on the interface — it must know the backend behind the key.
Either lift list semantics into the type system (a `ListMetaKey`) or drop the
methods from the common interface and keep them on an Avro-specific sub-type.

### 3.3 HEAP backend is dead and 30-day TTL is arbitrary — MED

No production key uses HEAP; it exists only in `MetaStorageTest`. Its
`expireAfterWrite(30 days)` is unexplained and unrelated to the pipeline heap
cache's own 60-min TTL. Given §3.1, the cleanest action is to **remove the HEAP
core type and handler** entirely, or reduce the four-backend set to what is
actually used (XATTR + AVRO, plus FS if the thumbnail path is revived).

### 3.4 Silent error handling in the AVRO handler — MED

`AvroLoomMetaTypeHandlerImpl` swallows every `IOException` with
`e.printStackTrace()` and returns `null` (reads) or nothing (writes) — a failed
write looks identical to a successful one, and a corrupt file reads as "no
value," triggering silent recompute. It also contains a stray debug
`System.out.println("Checking path: …")` in `toMetaPath`, on the hot path of
every get/put. Replace with proper logging and let failures surface (they are
real: a full disk should not read as "not computed yet").

### 3.5 Two different SHA-512 keys — MED

`MetaStorageImpl` defines `metaKey("sha512sum", 1, XATTR, …)` for its
`setSHA512`/`getSHA512` convenience, while `HashMetaStorage` defines
`metaKey("sha512", 1, XATTR, …)`. These are **different physical xattrs**
(`…_sha512sum_v1` vs `…_sha512_v1`). Whichever writes, the other cannot read.
Neither the `sha512sum` convenience nor its key has a production caller, so this
is currently harmless — but it is a trap. Delete the unused convenience pair, or
make both refer to one key constant.

### 3.6 FS backend only accepts `String` — MED

`FSLoomMetaTypeHandlerImpl.writeLocalStorage` throws for any non-`String`,
non-stream value. So every FS-backed domain value (`whisper-result`,
`scene-detection-result`) is hand-serialised to JSON by its `*MetaStorage` and
stored as an opaque string. That is fine, but it means "FS" and "AVRO" are two
inconsistent binary strategies for the same job (structured records on disk),
chosen per node with no rationale recorded.

### 3.7 XATTR portability and durability caveats — MED

Xattrs are **Linux-and-filesystem-dependent**, are stripped by many copy/move
tools, `rsync` without `-X`, most archive formats, and any upload; they have
per-value and per-file size limits; and they require write access to the media
(often mounted read-only in a worker). As a *cache* this is acceptable (a miss
just recomputes). As anything load-bearing it is fragile. The doc should state
plainly that xattr storage is best-effort cache only — which reinforces that Loom
must be the durable copy.

### 3.8 Streaming path (`MetaDataStream`) is entirely dead — LOW

The only production `MetaDataStream` key is `ThumbnailMetaStorage.thumbnail_bin`,
and `ThumbnailMetaStorage` has **no callers at all**
([METASTORAGE.md](METASTORAGE.md) §8). `MetaDataStreamFSImpl` and the FS
handler's stream branch exist to serve nothing. Remove, or revive with the
thumbnail node.

### 3.9 No versioning migration despite a version field — MED

`LoomMetaKey.version()` folds into `fullKey()`, so bumping a key version silently
orphans old values (they are neither read nor cleaned up). There is no eviction,
no migration, and no `remove`. Combined with the sidecar/xattr caches never being
swept, local storage only grows. This is the local-storage mirror of the
"no node versioning / can't invalidate old cached results" item in
[NODES.md](../pipeline-nodes/NODES.md) §10.

### 3.10 Wiring lives in `cortex/cli` — LOW

`LoomStorageModule` (the handler set) sits in `cortex/cli` and is duplicated in
`examples/cortex-custom`. The front-door binding is in `cortex/core`. A subsystem
this central should be wired in one core module, not in the CLI, so alternate
entry points can't accidentally ship a `MetaStorage` with a missing backend
(which fails only at runtime, as a `MetaStorageException`).

---

## 4. Is this storage system still needed?

Short answer: **not in its current form.** The elaborate four-backend, typed,
pluggable design solves the "Cortex is the only place results live" problem,
which no longer exists. What Cortex genuinely still needs is much smaller.

### 4.1 What a local store is actually for, post-Loom

Enumerate the real remaining jobs and ask which need *local* persistence:

| Job | Needs local store? | Why / who does it now |
|---|---|---|
| **Deliver results to the user/system of record** | **No** | Loom (REST → Postgres). Authoritative. |
| **Skip already-computed work within a run** | Not durably | In-memory dedup within the executor is enough for one run. |
| **Skip work across runs / restarts** | Optional optimisation | Loom is queried first (`AbstractMediaNode.fetchAsset`, `FingerprintNode` checks `asset.getFingerprint()`); a local cache only saves a round trip. |
| **Run detached from Loom (offline CLI batch)** | **Yes** | This is the one case with no alternative — [ARCHITECTURE §12](../../cortex/METALOOM_ARCHITECTURE.md) lists offline CLI as a supported mode. |
| **Hold large intermediate blobs (thumbnails, detections) before upload** | Maybe | Currently dead (§3.8); if revived, a spill-to-disk buffer, not a typed store. |

Only **offline CLI** strictly requires durable local storage. Everything else is
either Loom's job or an optimisation that a small cache covers.

### 4.2 Recommendation: collapse, don't extend

1. **Make Loom + the pipeline cache the default path.** Nodes deliver via
   `ctx.output(...)`; skip-if-done uses `NodeCacheProvider` (or a Loom lookup).
   This is already what `AbstractMediaNode` documents — finish the job by
   migrating `FingerprintNode` off `FingerprintMetaStorage`.
2. **Keep one small optional local cache** for offline/CLI mode only, behind a
   single interface, single backend (sidecar files keyed by SHA-512 — portable,
   unlike xattr). Delete HEAP (§3.1/3.3), the `sha512sum` convenience (§3.5), the
   dead streaming path (§3.8), and the `append`/`getAll` interface methods (§3.2).
3. **Unify with `NodeCacheProvider`.** Two xattr caches and two sidecar caches
   with different formats is the actual maintenance liability. One cache
   abstraction, one on-disk format.

That removes ~4 backends, a Dagger multibinding, 8 decorator classes, and a whole
category of "which store do I use?" confusion, in exchange for one cache used in
one clearly-scoped situation.

### 4.3 The "results live in JVM heap, synced to Loom periodically" idea

The task asks specifically what it would mean to drop local storage and instead
keep results in heap and flush to Loom on a timer. Assessed honestly:

**This already half-exists.** The pipeline path buffers results in memory and
bulk-syncs them (`DefaultLoomBulkSyncCollector`, batch size 100, auto-flush;
[PIPELINE.md](../pipeline/PIPELINE.md) §4.11) and there is a `HeapNodeCache`
(Caffeine) for skip-checks. So "heap + periodic sync" is close to the *intended
hot path* — MetaStorage is the *older, colder* path beside it. Formalising
heap-first is a reasonable direction. But note the sharp edges:

**What you gain**

- **Speed.** No xattr syscalls or sidecar file I/O per result; RAM only.
- **Portability.** No Linux/xattr dependency; nothing left on the media tree.
- **One mental model.** Compute → hold in heap → push to Loom. No third store.
- **Type fidelity.** Heap keeps real objects; no stringify/parse round-trips
  (the persistent caches lose types today — [PIPELINE.md](../pipeline/PIPELINE.md)
  §4.10).

**What you lose or must handle**

- **Durability across process death — the big one.** Everything in the sync
  window is lost on crash/`SIGTERM`. Cortex *already* has this bug: it has **no
  shutdown hook and does not flush the buffer**, silently losing up to a batch of
  computed results ([ARCHITECTURE §8](../../cortex/METALOOM_ARCHITECTURE.md)).
  Making heap the *only* store widens that window from "the last batch" to
  "everything since the last timer flush." A periodic timer is strictly worse
  than the current auto-flush-at-N for loss, unless paired with a flush-on-idle
  and a shutdown-hook flush. **Prerequisite, not optional.**
- **Re-do cost on restart.** With no local record, a restarted worker recomputes
  everything not yet in Loom. For cheap nodes (hashes) that is fine; for
  expensive ones (whisper, facedetect, LLM) re-running a half-finished large file
  is costly. Mitigation: query Loom first (already done for some nodes) so
  "durable in Loom" substitutes for "durable locally."
- **Memory pressure from large values.** Scalars in heap are trivial. But
  face-detection lists, transcripts, and especially **thumbnail image bytes** are
  exactly the "complex data" MetaStorage was built to hold off-heap (Avro/FS).
  Holding thousands of those in heap between flushes, under the container's
  `-Xmx512m` default ([ARCHITECTURE §8](../../cortex/METALOOM_ARCHITECTURE.md)),
  is an OOM waiting to happen. **Big binary results must stream to Loom
  immediately (or spill to a temp file), never accumulate in heap.**
- **Back-pressure.** If Loom is down or slow, a heap buffer with only a timer
  grows unbounded. The current collector re-adds a failed batch to the buffer for
  retry — good — but that buffer needs a cap + shed/block policy, or a slow Loom
  becomes a Cortex OOM.
- **Offline CLI.** Heap-only + Loom-sync has *no answer* for the no-Loom mode.
  That mode is the one place a durable local store is irreducible (§4.1).

**Verdict on the heap idea.** Adopt heap-first *for the hot path and for
scalars/small structured results* — it is faster, simpler, and mostly already
built. But:

- It is a **latency/throughput** change, **not** a durability strategy. Loom is
  the durability boundary; treat the sync as "get it to Loom promptly," and make
  the window small (flush on batch, on idle, and on shutdown) rather than on a
  fixed long timer.
- **Never buffer large binaries in heap** — stream them to Loom or spill.
- **Keep exactly one small durable local cache for offline/CLI**, because heap +
  Loom cannot serve the no-back-end case.

So the honest conclusion: the *complicated* local storage system is **not**
needed — but a *simple* local cache still is, for one narrow reason. The move to
"heap + sync" is the right direction for online workers, provided the durability
window is closed (starting with the missing shutdown-flush that already loses
data today).

---

## 5. Prioritised action list

| # | Action | Severity | Ref | Status |
|---|---|---|---|---|
| 1 | Add a shutdown hook that flushes the sync buffer (fixes existing data loss) — prerequisite for any heap-first move | HIGH | §4.3, [ARCHITECTURE §8](../../cortex/METALOOM_ARCHITECTURE.md) | **open** |
| 2 | Migrate `FingerprintNode` off `FingerprintMetaStorage`; retire the unused `*MetaStorage` classes | HIGH | §2.2, [METASTORAGE.md](METASTORAGE.md) §8 | ✅ done — moved to in-memory LRU cache |
| 3 | Fix or delete the HEAP backend (media-collision bug) | HIGH | §3.1, §3.3 | ✅ done — deleted |
| 4 | Unify MetaStorage's local caching with `NodeCacheProvider`; one on-disk format | HIGH | §2.1 | ✅ done — MetaStorage removed; `NodeCacheProvider` is the sole cache |
| 5 | Keep one portable (sidecar, SHA-512-keyed) local cache for offline/CLI only | MED | §4.2 | **open** — not yet needed; add when offline/CLI durability is required |
| 6 | Remove leaky `append`/`getAll` from the common interface | MED | §3.2 | ✅ done — interface removed |
| 7 | Fix Avro silent error handling + stray `System.out.println` | MED | §3.4 | ✅ done — handler removed |
| 8 | Delete unused `sha512sum` convenience + dead `MetaDataStream`/thumbnail path | LOW | §3.5, §3.8 | ✅ done — removed |
| 9 | Move handler wiring into a core module | LOW | §3.10 | ✅ moot — wiring removed |

_Last updated: 2026-07-24_
</content>
