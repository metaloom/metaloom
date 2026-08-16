# Fingerprint Similarity Index (Lucene) — Task List

> Open work items for the perceptual fingerprint similarity index, derived from a code audit on
> 2026-08-11 against `loom/services/lucene`, `loom/services/rest`, `loom/core`, `helm/loom` and
> `loom-ui/src`. Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) (technical spec) ·
> [../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md) (operating
> the index) · [../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) and
> [../workflows/WORKFLOW_DEDUP.md](../workflows/WORKFLOW_DEDUP.md) (the consumer) ·
> [../concept/CLUSTERING.md](../concept/CLUSTERING.md) §3 (the `write.lock` rule)
>
> **Scope boundary.** This file owns *indexing and querying* fingerprints. How a fingerprint is
> **computed and persisted** — `FingerprintNode` and the video4j fingerprinter behind it — is
> [NODE_FINGERPRINT_TASKS.md](NODE_FINGERPRINT_TASKS.md), which also explains the `sector_index`
> name collision that this file's Task 5 originally got wrong. The column is called `window_index`
> since V2.105.
>
> **Removed as implemented** — this file does not carry task text for them: the SPI and both
> implementations, the Dagger binding and boot guard, `SimilarityOptions`, the comp write/delete
> hooks, both REST routes, the Java and Python clients, the administration surface
> (`drop` / `status` / `streamIndexedAssetUuids` / `providerName` and the `fingerprint` index row with
> its `REINDEX` / `DELTA_SYNC` / `DROP` jobs), the customer-facing website pages, the shutdown hook
> that commits and closes both Lucene indices (Task 4, done 2026-08-12 — see
> [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §4.3), and the boot-time auto-rebuild, which
> was deliberately dropped rather than deferred. All recorded in
> [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §11.
>
> **Ordering / blocking:** Task 1 was the only correctness defect and gated Task 6; it is now done, so
> the UI panel may display the hash. Task 2 blocks any Kubernetes deployment of the feature.
> Task 3 is done, so Task 6 has a demo corpus to build against without hand-seeding data. Task 4 was
> independent and is done. **Task 5 is blocked** on
> [NODE_FINGERPRINT_TASKS.md](NODE_FINGERPRINT_TASKS.md)
> Tasks 3 and 4 — there are no windowed fingerprints to index until the producer writes them.

## Progress Assessment

- [x] **Defect:** Task 1 — `sha512` is null on every similarity hit (done 2026-08-12)
- [ ] **Operability:** Task 2 (Helm) · Task 4 — shutdown close (done 2026-08-12)
- [ ] **Demonstrability:** Task 3 — demo data (done 2026-08-12) · Task 6 (UI)
- [ ] **Feature gap:** Task 5 (windowed indexing) — blocked on the producer, see
      [NODE_FINGERPRINT_TASKS.md](NODE_FINGERPRINT_TASKS.md)

---

---

## Task 2: Template `LOOM_SIMILARITY_*` in the Helm chart

**Argumentation Summary:** `helm/loom/templates/deployment.yaml` templates no `LOOM_SIMILARITY_*`
variable and `helm/loom/values.yaml` offers no `similarity` block, so the feature **cannot be enabled
from the chart at all** — an operator has to patch the deployment by hand. The index also needs
durable storage: without a volume, `LOOM_SIMILARITY_INDEX_PATH` lands in the container filesystem and
every restart silently starts from an empty index, which the routes will happily serve as "no
duplicates" once the write hook has repopulated only the newest assets. The chart already has the
pattern for both, in the `persistence` map and the `LOOM_AGENT_MEMORY_*` block.

**Improvement Summary:** Add a `similarity` values block plus its five env vars, and a per-replica
index volume, so an operator can turn the fingerprint index on the way they turn on agent memory.

```
1. helm/loom/values.yaml: add a `similarity` block with `enabled` (default false), `indexPath`,
   `algorithm`, `scoreThreshold` and `topK`, defaulting to the SimilarityOptions defaults
   (see spec/loom/SEARCH_LUCENE.md §6). Document each with a `# --` comment, matching the style of
   the existing blocks.
2. helm/loom/templates/deployment.yaml: emit the five env vars under an
   `{{- if .Values.similarity.enabled }}` guard, next to the LOOM_AGENT_MEMORY_* block.
3. Add a `persistence.similarityIndex` entry (ReadWriteOnce) and mount it at the configured
   indexPath, following the existing persistence.config / persistence.keystore templates in
   pvc.yaml and deployment.yaml.
4. Document the one-directory-per-process rule next to the value: a Lucene IndexWriter holds an
   exclusive write.lock, so a ReadWriteMany volume shared by replicas silently disables similarity
   on all but the first pod. replicaCount is already documented as 1 in values.yaml; state that
   raising it requires one volume per replica.
5. Update helm/loom/README.md with the new values.
```

**References:** [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §6, §7 ·
[../concept/CLUSTERING.md](../concept/CLUSTERING.md) §3 (B-8, C-7) ·
[../loom/CONFIGURATION.md](../loom/CONFIGURATION.md) §4.11

**Test Requirements:** The chart has no test harness, so verify by rendering:
`helm template loom helm/loom --set similarity.enabled=true` must emit all five env vars and the
volume mount, and `helm template loom helm/loom` (defaults) must emit none of them. Run
`helm lint helm/loom` for both value sets.

---

## Task 5: Index windowed fingerprints so a clip can match a longer video — 🔒 BLOCKED on the producer

**Blocked by:** [NODE_FINGERPRINT_TASKS.md](NODE_FINGERPRINT_TASKS.md) Tasks 3 and 4. Do not start
this task before they land — there would be nothing to index. See the correction note below.

**Argumentation Summary:** Only `window_index == 0` — the whole-asset fingerprint — is indexed:
`FingerprintCompEndpointService.createFingerprintComp` guards the hook with
`if (stored.getWindowIndex() == 0)` and `SimilarityEndpointService.loadFingerprint` selects window 0.
A 30-second excerpt of a 40-minute video is therefore not a near-duplicate of it at whole-asset
level, which is the case an operator most expects a perceptual index to catch.

> **Correction (2026-08-12).** An earlier version of this task claimed `asset_fingerprint_comp`
> models sectors "and `MultiSectorFingerprint` produces them, so the schema and the producer are
> ahead of the index." **That is false, and it inverted the dependency.** The `sectorCount` inside
> `MultiSectorVideoFingerprinterImpl` is an internal sampling trick — several seek points stacked
> into **one** 256-bit vector — and it never emits per-window values. `window_index` /
> `time_from` / `time_to` on the table model something different: timeline windows. The only writer
> is `FingerprintNode.persist(...)`, which hardcodes `setWindowIndex(0)` and leaves the times NULL,
> so **no row with `window_index > 0` has ever existed**. Executing this task as originally written
> would have produced a per-window index containing exactly one window per asset — the index that
> already exists, with more code. The column itself was renamed from `sector_index` in V2.105 so the
> schema stops asserting the collision (NODE_FINGERPRINT_TASKS.md Task 5). The producer work now
> lives in
> [NODE_FINGERPRINT_TASKS.md](NODE_FINGERPRINT_TASKS.md); this task is the indexing half only.

**Improvement Summary:** Once the node writes window rows, extend the index to hold one document per
window, keyed by `(asset_uuid, window_index)`, and return the matching time range on each hit, while
keeping the existing whole-asset query as the default.

```
Precondition: asset_fingerprint_comp contains rows with window_index > 0, written under their own
algorithm identifier ("metaloom-window-v1") by NODE_FINGERPRINT_TASKS.md Task 4. Verify that before
writing any code here.

1. Design the key change first: the Lucene document key becomes asset_uuid + window_index, so
   `remove(assetUuid)` must delete every window of an asset (a term query on ASSET_FIELD, not a
   single-document delete) and `index(...)` must upsert on the compound term. Add WINDOW_FIELD and
   store time_from / time_to alongside it.
2. Extend the SPI in loom-shared/api/.../api/search/: add the window index and time range to
   IndexedFingerprint, HexFingerprint and SimilarityHit. Keep the existing method signatures
   working for whole-asset callers, defaulting the window to 0.
3. Drop the window-0 guard in FingerprintCompEndpointService and index every row. The two vector
   populations are already kept apart by the algorithm identifier, which every query filters on -
   do not rely on the window number for that separation.
4. In SimilarityEndpointService, add a query mode: default stays "whole asset vs whole asset"
   (window 0, whole-asset algorithm, both sides); a new `windows=true` query parameter queries all
   of the asset's windows under the window algorithm and collapses the hits per matched asset,
   keeping the best-scoring window and reporting its time range on SimilarAssetResponse. A hit's
   value to the user is largely that time range - it says where in the long video the clip came
   from - so surface it, do not just score with it.
5. Self-exclusion changes: with several documents per asset the query must drop every window of the
   query asset, not just one hit, and the limit + 1 over-fetch is no longer sufficient — over-fetch
   by the asset's window count instead.
6. Update SearchIndexJobRunner.reindexFingerprints and sweepFingerprintOrphans: counts become
   per-window, and streamIndexedAssetUuids must still yield distinct asset uuids.
7. Note the disk cost in spec/loom/SEARCH_LUCENE.md §7 and be concrete about it — with the
   recommended 10 s window / 2 s stride the index holds roughly (duration / 2 s) documents per
   video instead of one, so a 40-minute video contributes ~1200. This is the dominant operational
   consequence of the feature and an operator must not discover it from disk usage. Add the new
   query parameter to spec/loom/RESTAPI.md.
```

**References:** [NODE_FINGERPRINT_TASKS.md](NODE_FINGERPRINT_TASKS.md) (the blocking producer work,
and the "sector" name collision) · [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §3, §4, §7 ·
[../loom/DOMAIN.md](../loom/DOMAIN.md) (`asset_fingerprint_comp`) · migration
`V2.41__add_asset_fingerprint_comp.sql` (+ `V2.105__rename_fingerprint_window_index.sql`) ·
[../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md)

**Test Requirements:** New `LuceneSimilarityIndexTest` cases: a multi-window asset is removed in
full by `remove(assetUuid)`; a window query finds a clip inside a longer asset; per-algorithm counts
still hold with several windows per asset. New `SimilarAssetsEndpointTest` cases: the default query
is unchanged by the presence of window rows, and `windows=true` returns one collapsed hit per asset
with its time range. Run `./setup-pool.sh`, then
`mvn test -pl loom/services/lucene -Dtest=LuceneSimilarityIndexTest` and
`mvn test -pl loom/core -Dtest=SimilarAssetsEndpointTest,SearchIndexEndpointTest`.

---

## Task 6: Surface similar assets in the UI

**Argumentation Summary:** `GET /assets/:uuid/similar-assets` has no consumer in `loom-ui` —
`grep similar-assets loom-ui/src` finds nothing, and there is no `src/api/similarity.ts`. The only
client is the cortex dedup node, so a user browsing an asset cannot see near-duplicates and an
operator has no way to sanity-check the index beyond the count on `/admin/indices`.
[../workflows/WORKFLOW_COLLECTION_CURATION.md](../workflows/WORKFLOW_COLLECTION_CURATION.md) lists
"more like this" as a curation need that this route already answers.

**Improvement Summary:** Add a "Similar assets" panel on the asset detail view, backed by a small API
module, that degrades honestly when the index is off.

```
1. Add loom-ui/src/api/similarity.ts with listSimilarAssets(assetUuid, {algorithm, limit,
   threshold}), following the shape of src/api/dedup.ts.
2. Add a SimilarAssetsPanel component under src/features/assetDetail/ and mount it in
   AssetDetail.tsx next to the other panels. Render thumbnail, filename and score per hit, and link
   each to its asset detail route.
3. Handle the three states distinctly - this is the whole point of the backend answering 503 rather
   than an empty list: 200 with hits renders the list; 200 with no hits renders "no near-duplicates
   found"; 503 renders "the duplicate index is switched off" and must never look like an empty
   result. A 503 must not surface as an error toast on the whole asset view.
4. SimilarAssetResponse.sha512 now carries the matched asset's content hash (Task 1); show it where
   it helps a user tell two visually identical files apart, but never as the primary label.
5. Add en/de strings to src/i18n/locales/{en,de}.json under a new similarAssets key.
```

**References:** [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §5 ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) ·
[../workflows/WORKFLOW_COLLECTION_CURATION.md](../workflows/WORKFLOW_COLLECTION_CURATION.md) ·
[LOOM_UI_TASKS.md](LOOM_UI_TASKS.md)

**Test Requirements:** A mocked Playwright spec `loom-ui/e2e/asset-similar-mocked.spec.ts` covering
all three states (hits, empty, 503), following the `dedup-mocked` / `asset-search-mocked` pattern.
Run it with `./node_modules/.bin/playwright test e2e/asset-similar-mocked.spec.ts` from `loom-ui`
(never via `npx`, which stalls), and `./node_modules/.bin/vitest run` for any pure-logic helper
added alongside.

---
_Git HEAD revision: `67000540`_
_Last updated: 2026-08-16 (the column is `window_index` since V2.105; Task 5 renamed its field,
query parameter and Lucene key accordingly. Earlier: Task 5 corrected and scoped to indexing only —
its false premise that a producer of per-window fingerprints already exists is recorded inline; the
producer work moved to
tasks/NODE_FINGERPRINT_TASKS.md. Earlier: Task 3 done, the demo database seeds a near-duplicate
fingerprint pair)_
