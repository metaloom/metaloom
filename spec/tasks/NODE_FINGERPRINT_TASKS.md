# Fingerprint Node (perceptual video hash) — Task List

> Work items for the **producer** side of perceptual video fingerprinting — `FingerprintNode` in
> `cortex/nodes/fingerprint/core` and the `MultiSectorVideoFingerprinter` it drives in the sibling
> **video4j** repository — derived from a code audit on 2026-08-12.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../cortex/SERVICE_VIDEO.md](../cortex/SERVICE_VIDEO.md) (the video4j boundary) ·
> [../features/nodes/NODES.md](../features/nodes/NODES.md) (`fingerprint` row, §"Media components") ·
> [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) (the **consumer** — the k-NN index) ·
> [../loom/DOMAIN.md](../loom/DOMAIN.md) (`asset_fingerprint_comp`) · migration
> `V2.41__add_asset_fingerprint_comp.sql`
>
> **Scope boundary.** This file owns *how the fingerprint is computed and persisted*. How it is
> indexed and queried belongs to [SEARCH_LUCENE_TASKS.md](SEARCH_LUCENE_TASKS.md); how duplicates
> are grouped from it belongs to [../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md).
>
> **Two things are called "sector" and they are unrelated — read this before touching either.**
>
> | Name | What it is | Where |
> |---|---|---|
> | `sectorCount` in `MultiSectorVideoFingerprinterImpl` | Internal sampling trick: seek to a few points in the video, stack the frames from all of them into **one** 16×16 binary image, emit **one** 256-bit vector. Never leaves the fingerprinter. | video4j `fingerprint/v2/impl/` |
> | `asset_fingerprint_comp.window_index` (+ `time_from`/`time_to`) | A **timeline window**: one row per segment of the asset, "this hash covers 00:30–00:40". | `V2.41`; called `sector_index` until `V2.105` |
>
> The first buys robustness against re-encoding, rescaling and letterboxing of the *same span* of
> content. It does nothing for excerpts: a 30-second clip cut from a 40-minute video samples entirely
> different frames, so its whole-asset vector is unrelated to the source's. Only the second — real
> timeline windows — makes a clip match a longer video, and **nothing in the repository produces
> one**: `FingerprintNode.persist(...)` hardcodes `request.setWindowIndex(0)` and leaves
> `time_from`/`time_to` NULL, and it is the only writer. The schema is ahead of the producer; the
> producer is not ahead of the index.
>
> **Ordering / blocking:** Tasks 1 and 2 both change the bytes a fingerprint produces, so they must
> land together behind a single algorithm-id bump and one reindex — do not churn the corpus twice.
> Task 3 (video4j) blocks Task 4 (cortex), and Task 4 blocks
> [SEARCH_LUCENE_TASKS.md](SEARCH_LUCENE_TASKS.md) Task 5, which has nothing to index until it lands.
> Task 5 was independent hygiene and landed first, on 2026-08-16 — the column is `window_index`
> since `V2.105`, so Tasks 3-4 write `windowIndex` from the start.

## Progress Assessment

- [ ] **Defect:** Task 1 — the fingerprint never samples past ~45% of a video
- [ ] **Defect:** Task 2 — tuning is global mutable state and no `producer_version` is recorded
- [ ] **Feature gap:** Task 3 (windowed fingerprinter, video4j) · Task 4 (emit one comp row per window)
- [x] **Hygiene:** Task 5 — stop calling timeline windows "sectors" — done 2026-08-16 (`V2.105`)

---

## Task 1: Sample the whole timeline — the fingerprint ignores everything past ~45% of a video

**Argumentation Summary:** `MultiSectorVideoFingerprinterImpl` (video4j
`fingerprint/src/main/java/io/metaloom/video4j/fingerprint/v2/impl/`) declares `sectorCount = 4` and
`skipFactor = 1f / sectorCount - 0.1f` = `0.15`, then loops `for (int i = 1; i < sectorCount; i++)`
and seeks `video.seekToFrameRatio(i * skipFactor)`. That is **three** sectors, not four, at 15 %,
30 % and 45 % of the video, each reading `len / sectorCount` = 15 usable frames (`speedUp = 8`, so
roughly 120 decoded frames ≈ 4 s at 30 fps). **The second half of every video never contributes a
single bit.** Two videos that share an intro and diverge afterwards are reported as near-duplicates
at whatever score their first half earns, which is the failure mode the dedup review queue is least
able to catch — the operator sees a high score and a matching thumbnail. The `if (factor >= 1)`
guard inside the loop is dead code (the maximum factor is 0.45), which is itself evidence the
intended formula was a different one. Separately, `hash1(...)` duplicates the ~80-line stacking
pipeline that already exists as `AbstractVideoFingerprinter.computeImageStack(video, skipFactor,
handler)` — the v1 fingerprinter (`v1/impl/BinaryVideoFingerprinterImpl`) calls the shared method,
v2 re-implements it with a loop around it.

**Improvement Summary:** Spread the sector seek points across the full duration, use all
`sectorCount` of them, and fold the duplicated stacking loop back onto the base-class method.

```
Work happens in the video4j repository (../../video4j), not in metaloom.

1. In fingerprint/src/main/java/io/metaloom/video4j/fingerprint/v2/impl/
   MultiSectorVideoFingerprinterImpl.java, replace the seek formula. Use the sector midpoints so
   the samples are spread and neither endpoint is hit (leaders and end credits are the least
   discriminative part of a video):

       for (int i = 0; i < sectorCount; i++) {
           double factor = (i + 0.5d) / sectorCount;   // 0.125 / 0.375 / 0.625 / 0.875
           ...
       }

   Drop the `skipFactor` field and the now-unreachable `factor >= 1` guard with it.
2. Rewrite the body of the loop as a call to the inherited
   `computeImageStack(video, factor, handler)` and stack its per-sector results, deleting the
   duplicated pipeline in hash1(). Note that computeImageStack reads `len` frames while the sector
   loop reads `len / sectorCount` — pass the per-sector length rather than changing `len`'s
   meaning for the v1 fingerprinter, which shares the field.
3. Keep the output format identical: 16x16 binary, MultiSectorFingerprint.FINGERPRINT_VECTOR_SIZE
   = 256, FINGERPRINT_VERSION = 2. This task changes *which frames* are hashed, not the encoding,
   so MultiSectorFingerprintCodec and the Lucene vector width are untouched.
4. Every fingerprint already stored is now stale. Coordinate with Task 2: bump the algorithm
   identifier once, for both changes together (see Task 2 step 4), and document in
   spec/loom/SEARCH_LUCENE.md §4 that switching to the new identifier requires re-running the
   fingerprint node over the corpus — a Lucene REINDEX alone cannot help, because the source rows
   in asset_fingerprint_comp are the stale ones.
```

**References:** [../cortex/SERVICE_VIDEO.md](../cortex/SERVICE_VIDEO.md) §"Where do I find ...?" ·
[../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §4, §10 · video4j
`fingerprint/v2/impl/MultiSectorVideoFingerprinterImpl.java`,
`fingerprint/AbstractVideoFingerprinter.java`

**Test Requirements:** In video4j, extend `MultiSectorFingerprintTest` /
`MultiSectorVideoFingerprinterTest`: a video whose first half is identical to another and whose
second half differs must produce a **different** fingerprint (the regression this task fixes), and a
video re-encoded at a lower resolution must still produce a near-identical one (the property that
must survive). Assert the sector seek points cover both halves. Run
`mvn test -pl fingerprint -Dtest=MultiSectorVideoFingerprintTest,MultiSectorVideoFingerprinterTest`
in the video4j checkout. In metaloom, after `mvn install` of video4j, re-run
`mvn test -pl integration-test -Dtest=FingerprintNodeIntegrationTest` and
`mvn test -pl cortex/nodes/fingerprint/core`.

---

## Task 2: Make the fingerprinter's tuning immutable and stamp `producer_version`

**Argumentation Summary:** `MultiSectorVideoFingerprinterImpl` exposes `hashSize`, `len`,
`stackFactor` and `sectorCount` as **`public static` non-final fields**, and the constructor reads
them into the base class. Any code anywhere in the JVM that assigns one of them silently changes
every fingerprint produced from that point on, while `FingerprintNode.ALGORITHM` stays
`"metaloom-multisector-v1"`. Two workers with different settings therefore write mutually
incomparable vectors into the **same** k-NN index under the **same** algorithm filter, and nothing
downstream can tell — the index is a bag of 256-dim vectors and `LOOM_SIMILARITY_ALGORITHM` is the
only discriminator it has. `FingerprintNode.persist(...)` compounds this by never setting
`producerVersion` on the `FingerprintCompCreateRequest`, so the column falls back to its `''`
default and no stored row records what produced it. [../features/nodes/NODES.md](../features/nodes/NODES.md)
already flags node versioning as generally absent, and names `script` (`"<engine>:<sha256(script)[0..12]>"`),
`watermark` and `dominant-color` as the hand-rolled precedent to follow.

**Improvement Summary:** Turn the tuning knobs into final instance state supplied at construction,
and record the effective parameters in `producer_version` so a stored fingerprint is traceable to
the configuration that made it.

```
video4j (steps 1-2), metaloom (steps 3-5).

1. MultiSectorVideoFingerprinterImpl: make hashSize / len / stackFactor / sectorCount final
   instance fields set through a constructor, keeping the current values as the defaults of a
   no-arg constructor so existing callers compile unchanged. Remove the public static declarations
   rather than deprecating them - a static that no longer takes effect is worse than one that is
   gone.
2. Add a `String parameterDigest()` (or reuse an existing descriptor hook) returning a short stable
   digest of the effective parameters, e.g. sha256("16:60:1.30955:4")[0..12].
3. cortex/nodes/fingerprint/core/.../FingerprintNode.java: give FingerprintNodeOptions the fields
   it currently lacks - it is an empty class today and NODES.md lists that as an open item -
   exposing sectorCount / frameCount / stackFactor with the video4j defaults, validated in
   validate() as positive. Construct the hasher from them instead of `new
   MultiSectorVideoFingerprinterImpl()`.
4. Bump ALGORITHM to "metaloom-multisector-v2" in the same change as Task 1. The identifier is the
   only thing separating vector populations in one Lucene index, so a change in what the bits mean
   must change it. Update the LOOM_SIMILARITY_ALGORITHM default in SimilarityOptions and its row in
   spec/loom/SEARCH_LUCENE.md §6, plus the demo seed in DemoDatabaseInitializer.seedFingerprintComps
   which writes the algorithm string.
5. In persist(...), set request.setProducerVersion("multisector/2:" + hasher.parameterDigest()).
   Confirm it round-trips: FingerprintCompCreateRequest already declares producerVersion and V2.41
   already has the column, so no model or migration change is needed.
```

**References:** [../features/nodes/NODES.md](../features/nodes/NODES.md) §"Ops" (node versioning) ·
[../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §6 · [../loom/DOMAIN.md](../loom/DOMAIN.md)
(`producer_version` on the shared component contract, V2.38)

**Test Requirements:** `FingerprintNodeOptionsValidationTest` gains cases for the new fields
(rejecting zero/negative). A new `FingerprintNodeTest` case asserts the persisted request carries a
non-blank `producerVersion` and the v2 algorithm string — assert against the captured request, the
node's Loom write is already mockable there. `FingerprintNodeIntegrationTest` asserts the same on
the component read back through REST. Run `mvn test -pl cortex/nodes/fingerprint/core`, then
`./setup-pool.sh` and `mvn test -pl integration-test -Dtest=FingerprintNodeIntegrationTest` and
`mvn test -pl loom/core -Dtest=DemoFingerprintSeedTest`.

---

## Task 3: Add a time-windowed fingerprinter to video4j

**Argumentation Summary:** Matching a clip against the longer video it was cut from is impossible
with a whole-asset hash, and no amount of index work fixes that: the vector for a 30-second excerpt
is computed from that excerpt's own frames, at its own seek ratios, and shares nothing with the
40-minute source's vector. What is needed is a hash **per span of the timeline**, so the clip's spans
can be compared against the source's spans. `asset_fingerprint_comp` was designed for exactly this —
`window_index`, `time_from`, `time_to` — but no producer exists: `MultiSectorVideoFingerprinter.hash(VideoFile)`
returns a single `Fingerprint` for the whole file and has no notion of a range.

**Improvement Summary:** A `WindowedVideoFingerprinter` that walks the timeline and emits one
fingerprint per window with its millisecond range, reusing the existing stacking and encoding so the
vectors stay comparable to everything else in the index.

```
Work happens in the video4j repository, in fingerprint/src/main/java/io/metaloom/video4j/fingerprint/.

1. Settle the alignment question first - it determines everything downstream and it is the part
   this design most easily gets wrong. A fixed grid anchored at t=0 does NOT work on its own: a
   clip starting at 03:07 of the source has a grid offset by 7 s, so its windows straddle the
   source's boundaries and never match. Pick one and record the reasoning in
   spec/cortex/SERVICE_VIDEO.md:
     (a) Overlapping windows on the reference side - window W seconds, stride S < W. Any clip
         window lands within S/2 of some reference window. Costs W/S times the rows and the index
         documents; W=10 s, S=2 s is the usual starting point and the recommended default here,
         because it needs no other subsystem.
     (b) Content-anchored windows - start each window at a shot boundary. Alignment-free and
         cheaper in rows, but it makes the fingerprint depend on scene-detection output, which is
         a separate node with its own failure modes.
     (c) Short fixed windows plus sequence matching downstream - cheapest per window, but pushes
         the hard part into the query side, which SEARCH_LUCENE_TASKS.md Task 5 does not model.
   Recommendation: (a). It is self-contained, and (b) can be added later as a second mode without
   changing the persisted shape.
2. Add `WindowedVideoFingerprinter` with `List<WindowedFingerprint> hash(VideoFile video, double
   windowSeconds, double strideSeconds)`, where WindowedFingerprint carries the fingerprint plus
   timeFromMs / timeToMs. Derive frame positions from video.fps() and video.length(); guard fps()
   returning 0 or NaN on a malformed container by falling back to a whole-asset result rather than
   dividing by zero.
3. Reuse AbstractVideoFingerprinter's stacking for each window, and emit the same
   MultiSectorFingerprint encoding (256-dim, FINGERPRINT_VERSION 2). Comparable vectors are the
   whole point: a window vector and a whole-asset vector must live in one index format even though
   they must not share an algorithm identifier (Task 4 step 2).
4. Handle short media explicitly: a video shorter than one window yields exactly one window
   covering [0, duration]. State the behaviour in the javadoc - the caller uses the emitted count
   to size its writes.
5. Free every Mat on every path, including the exception path. Unclosed Mats are this codebase's
   recurring native-memory failure and a windowed fingerprinter allocates per window rather than
   per file, multiplying any leak by the window count.
```

**References:** [../cortex/SERVICE_VIDEO.md](../cortex/SERVICE_VIDEO.md) §"Native dependencies" ·
[../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §2 (query path), §7 · migration
`V2.41__add_asset_fingerprint_comp.sql` + `V2.105__rename_fingerprint_window_index.sql` (the
`window_index` / `time_from` / `time_to` columns this produces)

**Test Requirements:** In video4j, a new `WindowedVideoFingerprinterTest`: a 60 s video at W=10 s /
S=2 s emits the expected window count with contiguous, non-decreasing, non-overlapping-in-time
ranges; a video shorter than one window emits exactly one covering the whole duration; and the
decisive case — **a clip cut from the middle of a longer video shares at least one near-identical
window fingerprint with it**, which is the feature's entire premise and must be asserted directly.
Run `mvn test -pl fingerprint -Dtest=WindowedVideoFingerprinterTest` in the video4j checkout. Note
that `/opt/metaloom/loom-testdata` clips are short and silent; cut the clip in the test from an
existing fixture rather than adding a binary.

---

## Task 4: Emit one `asset_fingerprint_comp` row per window from `FingerprintNode`

**Argumentation Summary:** With Task 3 landed, the node is still the bottleneck:
`FingerprintNode.compute(...)` produces one hex string, `persist(...)` writes one row at
`windowIndex = 0` with NULL times, and `OUT_FINGERPRINT` is a `ONE`-cardinality port carrying that
single string. Until the node writes window rows, `window_index > 0` remains a column that has never
held a value in production, and [SEARCH_LUCENE_TASKS.md](SEARCH_LUCENE_TASKS.md) Task 5 would build a
per-window index containing exactly one window per asset.

**Improvement Summary:** Add an opt-in windowed mode that writes the whole-asset row plus one row
per window under a distinct algorithm identifier, leaving the default behaviour and the existing
dedup path untouched.

```
1. FingerprintNodeOptions (extended in Task 2): add `windowedEnabled` (default false),
   `windowSeconds` (default 10) and `strideSeconds` (default 2). Validate stride > 0 and
   stride <= window; a stride larger than the window would leave gaps in coverage, which is a
   configuration error, not a tuning choice.
2. Use a separate algorithm identifier for window rows - "metaloom-window-v1" - never the
   whole-asset one. This is the load-bearing decision of the task: LOOM_SIMILARITY_ALGORITHM is
   the only filter the k-NN index applies, so window vectors sharing the whole-asset identifier
   would be returned by the existing whole-asset query and scored against it as if comparable.
   Distinct identifiers keep the two populations disjoint in one index for free.
3. In compute(...), when windowedEnabled, run the windowed fingerprinter after the whole-asset one
   and pass the results to persist(...). Keep OUT_FINGERPRINT emitting the whole-asset hex
   unchanged - FingerprintDedupNode is bound to that port and must not change behaviour here.
   Do not add a MANY-cardinality window port in this task; nothing consumes it yet and an unbound
   MANY port is a preview/debug-card liability (see NODES.md).
4. persist(...): write the window-0 row as today, then one row per window with windowIndex = i + 1
   (1-based, so window 0 keeps its "whole asset" meaning), timeFrom / timeTo in milliseconds, and
   the window algorithm identifier. The unique key is (asset_uuid, node_kind, algorithm,
   window_index), so re-running the node upserts its own rows; a re-run with a *smaller* window
   count leaves the tail rows orphaned - delete rows above the new count, or state plainly in the
   javadoc that changing the window size requires clearing the algorithm's rows first.
5. Keep the whole thing best-effort exactly as persist() is today: a failed window write logs and
   records a FAILED node result, and must never fail the node or lose the whole-asset row that
   already succeeded. Consider one recordNodeResult for the batch rather than one per window - the
   ledger is per node execution, not per row.
6. The skip cache (`fingerprintCache`, keyed by absolute path) currently caches the whole-asset hex
   only. A cache hit must not skip the window writes when windowedEnabled is on and the windows
   were never written; either include the mode in the cache key or check Loom for window rows
   before returning early.
7. Update spec/features/nodes/NODES.md - the `fingerprint` persistence row (currently
   "asset_fingerprint_comp (window 0)") and the "Media components" open item that names
   `fingerprint` as hard-writing windowIndex = 0.
```

**References:** [../features/nodes/NODES.md](../features/nodes/NODES.md) (persistence table,
§"Media components") · [../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md)
(the `OUT_FINGERPRINT` consumer that must not change) ·
[SEARCH_LUCENE_TASKS.md](SEARCH_LUCENE_TASKS.md) Task 5 (the blocked consumer) ·
[../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §4

**Test Requirements:** `FingerprintNodeTest`: with `windowedEnabled = false` the node writes exactly
one request at `windowIndex = 0` (the no-regression case); with it on, the captured requests are one
window-0 row plus N window rows with 1-based window indices, non-null millisecond ranges and the
window algorithm string. A cache-hit case asserts window rows are still written.
`FingerprintNodeIntegrationTest` reads the components back through REST and asserts the count and
the time ranges. `FingerprintNodeOptionsValidationTest` covers stride > window and stride <= 0. Run
`mvn test -pl cortex/nodes/fingerprint/core`, then `./setup-pool.sh` and
`mvn test -pl integration-test -Dtest=FingerprintNodeIntegrationTest`.

---

## Task 5: Stop calling timeline windows "sectors" — DONE 2026-08-16

**Argumentation Summary:** The name collision documented at the top of this file was not merely
cosmetic — it was written into the schema and had already produced a wrong task specification. The
comment on `V2.41__add_asset_fingerprint_comp.sql` line 50 read *"Which sector of a multi-sector
fingerprint; 0 for whole-asset fingerprints"*, which states the two concepts are the same thing.
They are not: the multi-sector fingerprint's sectors are folded into one vector and never surface as
rows, while the column numbers timeline windows that carry `time_from` / `time_to`. Anyone reading
the schema concluded the windows already existed. The Task 5 entry in
[SEARCH_LUCENE_TASKS.md](SEARCH_LUCENE_TASKS.md) asserted exactly that, and would have produced a
per-window index holding one window per asset.

**Improvement Summary:** The column is `window_index`, the comments say what it means, and the specs
that repeated the conflation are corrected.

```
1. `V2.105__rename_fingerprint_window_index.sql` renames sector_index -> window_index and rewrites
   the table, window_index, time_from and time_to comments. COMMENT ON is DDL, so V2.41 is left
   untouched - it has run everywhere. ALTER TABLE ... RENAME COLUMN carries
   asset_fingerprint_comp_unique_key over by itself; no index was rebuilt and no row held anything
   but the default 0.
2. The rename decision was taken rather than deferred, and is recorded as closed in
   ../loom/DOMAIN.md §4 so the question is not reopened. It cost, in one change: jOOQ regeneration
   (loom/db/jooq/generate.sh - 5 files), the REST model (FingerprintCompModel /
   -CreateRequest / -Response), FingerprintCompEndpointService and SimilarityEndpointService, the
   Java client method javadoc, FingerprintNode in cortex, the demo seed, four test classes, the
   OpenAPI document regenerated from inside loom/doc (plus the committed website copies), and the
   generated Python client with its parity suite.
3. Specs corrected: ../loom/DOMAIN.md (component table + the closed-decision note),
   ../features/nodes/NODES.md (the `fingerprint` row and §"Media components", which had listed the
   column beside stream_index and frame_number as if all three were the same kind of
   discriminator), ../loom/PERSISTENCE.md, ../loom/SEARCH_LUCENE.md §3/§7,
   DATABASE_TASKS.md, SEARCH_LUCENE_TASKS.md Task 5, ../METALOOM_CONTEXT.md,
   loom/design/DB/dbdiagram.yaml and the customer-facing
   website/content/english/docs/nodes/fingerprint/index.adoc.
4. Done before Tasks 3-4 rather than after. The ordering note originally said the opposite, on the
   grounds that renaming a column while its first real producer is being written doubles the merge
   surface. The producer work had not started, so the cheaper order was to rename first and let
   Tasks 3-4 be written in the final vocabulary; their task text here has been restated to match.
```

**References:** [../loom/DOMAIN.md](../loom/DOMAIN.md) §4 (the recorded decision) ·
[../features/nodes/NODES.md](../features/nodes/NODES.md) §"Media components" ·
[SEARCH_LUCENE_TASKS.md](SEARCH_LUCENE_TASKS.md) Task 5 ·
[../guidelines/CODING.md](../guidelines/CODING.md) (a schema change must update the matching spec)

**Test Requirements:** Verified with `./setup-pool.sh` after the migration (the pool template shows
`window_index` and the new comment), then `mvn test -pl loom/db/jooq
-Dtest=AssetFingerprintSegmentCompDaoTest,AssetCascadeTest` (18), `mvn test -pl loom/core
-Dtest=SimilarAssetsEndpointTest,DemoFingerprintSeedTest,DedupGroupEndpointTest` (26), `mvn test -pl
loom/services/lucene,cortex/nodes/fingerprint/core` (47) and `clients/python/test.sh` (122) — all
green.

---
_Git HEAD revision: `67000540`_
_Last updated: 2026-08-16 (Task 5 done: `V2.105` renames `asset_fingerprint_comp.sector_index` to
`window_index`, and the rename decision is recorded as closed in loom/DOMAIN.md §4. Tasks 3-4
restated in the new vocabulary. Earlier: new file — the producer-side half of clip matching, split
out of tasks/SEARCH_LUCENE_TASKS.md Task 5)_
