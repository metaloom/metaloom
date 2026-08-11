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
> **Removed as implemented** — this file does not carry task text for them: the SPI and both
> implementations, the Dagger binding and boot guard, `SimilarityOptions`, the comp write/delete
> hooks, both REST routes, the Java and Python clients, the administration surface
> (`drop` / `status` / `streamIndexedAssetUuids` / `providerName` and the `fingerprint` index row with
> its `REINDEX` / `DELTA_SYNC` / `DROP` jobs), the customer-facing website pages, and the boot-time
> auto-rebuild, which was deliberately dropped rather than deferred. All recorded in
> [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §11.
>
> **Ordering / blocking:** Task 1 is the only correctness defect and gates Task 6 (a UI panel must not
> surface a field that is always null). Task 2 blocks any Kubernetes deployment of the feature.
> Task 3 is what makes Tasks 1 and 6 demonstrable without hand-seeding data. Tasks 4 and 5 are
> independent.

## Progress Assessment

- [ ] **Defect:** Task 1 — `sha512` is null on every similarity hit
- [ ] **Operability:** Task 2 (Helm), Task 4 (shutdown)
- [ ] **Demonstrability:** Task 3 (demo data), Task 6 (UI)
- [ ] **Feature gap:** Task 5 (multi-sector indexing)

---

## Task 1: Populate `sha512` on similarity hits, or remove the field

**Argumentation Summary:** `SimilarityHit.sha512`, `SimilarAssetResponse.sha512` and the Lucene
`sha512sum` stored field all exist, and **every production call site passes `null`**:
`FingerprintCompEndpointService.reindex(...)` calls `index(assetUuid, null, algorithm, hex)`, the
legacy rebuild in `SimilarityEndpointService.rebuild(...)` builds
`new HexFingerprint(assetUuid, null, ...)`, and `SearchIndexJobRunner.reindexFingerprints(...)` does
the same. The cause is structural: `asset_fingerprint_comp` does not carry the content hash, so no
path can supply one without joining `asset`. The result is a declared, documented, permanently empty
field in a public REST response — a consumer that trusts it silently gets nulls, and the Lucene
stored field costs disk for nothing.

**Improvement Summary:** Decide one way: either join the asset hash into all three write paths and
assert it end to end, or delete the field from the SPI records, the Lucene document, the REST DTO and
both clients. Populating it is the better outcome — the dedup consumer identifies duplicates by
content hash — but a clean removal is preferable to the current half-wiring.

```
Preferred route — populate it:
1. Add a hash-bearing lookup to loom/db/api/src/main/java/io/metaloom/loom/db/model/asset/
   AssetComponentDao.java: a projection that returns (asset_uuid, algorithm, fingerprint,
   sha512sum) by joining asset on asset_fingerprint_comp.asset_uuid, as
   `Stream<HexFingerprint> streamHexFingerprintsByAlgorithm(String algorithm)`. Implement it in
   loom/db/jooq/.../AssetComponentDaoImpl.java next to streamByAlgorithm; keep it streaming, the
   reindex job walks the whole table.
2. Point SearchIndexJobRunner.reindexFingerprints (loom/services/rest/.../rest/search/) at the new
   method and drop the null-sha512 comment there.
3. Point SimilarityEndpointService.rebuild (loom/services/rest/.../service/impl/) at the same
   method.
4. In FingerprintCompEndpointService.reindex(comp), load the owning asset's sha512sum once via the
   asset DAO and pass it to similarityIndex.index(...). Keep the method best-effort: a failed
   lookup must log and skip the index write, never fail the comp write.
5. Leave LuceneSimilarityIndex untouched — it already stores and returns the field when it is
   non-null.

Fallback route — remove it, if the join is judged too costly on the hot write path:
1. Drop sha512 from SimilarityHit, IndexedFingerprint and HexFingerprint
   (loom-shared/api/.../api/search/), from HASH_FIELD in LuceneSimilarityIndex, and from
   SimilarAssetResponse (loom-shared/rest-model/.../model/similarity/).
2. Update SimilarityMethods, LoomHttpClientImpl, clients/python/loom_client/models/ and the
   generated OpenAPI (regenerate from inside loom/doc), then run the Python parity test.
3. Update spec/loom/SEARCH_LUCENE.md §5 and §7 to drop the "always null" note.
```

**References:** [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §3, §5, §7 ·
[../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md) §7 (finding 7,
which records the null as pre-existing) · [../loom/PYTHON_CLIENT.md](../loom/PYTHON_CLIENT.md)

**Test Requirements:** Extend `SimilarAssetsEndpointTest` (`loom/core/.../endpoint/test/`) with a
test asserting that a hit carries the seeded asset's `sha512sum` — or, on the removal route, that the
DTO no longer exposes the field. Extend `LuceneSimilarityIndexTest` with a round-trip assertion for a
non-null hash. On the removal route also run `clients/python/tests/test_parity.py`. Run
`./setup-pool.sh`, then `mvn test -pl loom/services/lucene -Dtest=LuceneSimilarityIndexTest` and
`mvn test -pl loom/core -Dtest=SimilarAssetsEndpointTest`.

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

## Task 3: Seed near-duplicate fingerprints in the demo data

**Argumentation Summary:** `DemoDatabaseInitializer` seeds pipelines that *mention* fingerprinting
(`pn4`, "Fingerprint") but writes no `asset_fingerprint_comp` rows. With the index switched on, the
`fingerprint` row on `/admin/indices` therefore reports zero documents and
`GET /assets/:uuid/similar-assets` returns an empty list for every demo asset — the feature looks
broken out of the box, and there is nothing for the dedup workflow, the admin screen or a future UI
panel to demonstrate against.

**Improvement Summary:** Seed a small set of `asset_fingerprint_comp` rows including at least one
near-identical pair, so the demo database exercises the whole path: write hook, k-NN query,
self-exclusion, and a non-empty index status.

```
1. In loom/core/src/main/java/io/metaloom/loom/core/boot/DemoDatabaseInitializer.java, add a
   seedFingerprintComps() step that writes asset_fingerprint_comp rows for a handful of demo video
   assets via AssetComponentDao.upsertFingerprintComp, with nodeKind "fingerprint",
   algorithm SimilarityOptions.DEFAULT_ALGORITHM and sectorIndex 0.
2. Generate the hex the same way SimilarAssetsEndpointTest.hexFingerprint(int) does — 2 bytes
   version, 1 pad, 2 bytes vector size (256), 1 pad, then 32 bytes of bit data — and share that
   helper rather than duplicating the layout. Seed at least one pair whose bit data differs in a
   single byte (a near-duplicate, above the 0.10 score floor) plus one clearly dissimilar
   fingerprint, so a query returns a ranked, non-trivial result.
3. Seeding must not depend on LOOM_SIMILARITY_ENABLED: the comp rows are the system of record and a
   later reindex job populates the index when an operator switches similarity on.
4. Keep the fixture aligned with the dedup demo described in
   spec/features/nodes/dedup/NODE_DEDUP.md so both features demo off the same assets.
```

**References:** [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §4 ·
[../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) ·
[../guidelines/CODING.md](../guidelines/CODING.md) (demo data is part of the definition of done)

**Test Requirements:** Extend `DemoPipelineDefinitionTest`
(`loom/core/src/test/java/io/metaloom/loom/core/boot/`) — or add a sibling demo-data test — asserting
that the demo database contains at least two `asset_fingerprint_comp` rows for the default algorithm
and that a similarity query over the seeded pair returns the partner asset. Run `./setup-pool.sh`,
then `mvn test -pl loom/core -Dtest=DemoPipelineDefinitionTest,SimilarAssetsEndpointTest`.

---

## Task 4: Close the index on server shutdown

**Argumentation Summary:** `LuceneSimilarityIndex` implements `AutoCloseable` and has a working
`close()`, but nothing calls it: `SimilarityModule` provides the singleton and no shutdown path
touches it. Today JVM exit releases the `write.lock` and the last `commit()` has usually run, so the
practical cost is bounded — but an unclosed `IndexWriter` at an abrupt stop leaves the lock and any
uncommitted segments behind, and in tests a server that is torn down without closing the index can
hold the directory long enough to collide with the next fixture. `LuceneVectorIndex` has the same
shape, so the fix should cover both rather than one.

**Improvement Summary:** Wire the Lucene indices into the existing server shutdown sequence, closing
them once, idempotently, after the HTTP server has stopped accepting requests.

```
1. Find the shutdown path used for other closeable singletons — BootstrapInitializer closes the
   c3p0 data source via an AutoCloseable check; use the same hook rather than inventing a second
   lifecycle.
2. On shutdown, if the bound SimilarityIndex (and VectorIndex) instance is an AutoCloseable, call
   commit() then close() inside a try/catch that logs and swallows: a failing close must not stop
   the shutdown sequence.
3. Make LuceneSimilarityIndex.close() idempotent and safe to call after close (guard on the
   `available` flag), and make every write method a no-op once closed rather than throwing, so a
   late in-flight request cannot produce an AlreadyClosedException stack trace.
4. Do not add close() to the SimilarityIndex SPI: the Noop implementation has nothing to close and
   an external backend would not either. The instanceof check is the correct seam.
```

**References:** [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §3.1, §4 ·
[../concept/CLUSTERING.md](../concept/CLUSTERING.md) §3 (the `write.lock` rule) ·
`loom/core/src/main/java/io/metaloom/loom/core/boot/BootstrapInitializer.java`

**Test Requirements:** Add `shouldBeSafeToCloseTwiceAndIgnoreLateWrites` to
`LuceneSimilarityIndexTest`, and a test asserting that a second `LuceneSimilarityIndex` can open the
same directory after the first has been closed — the regression that the missing hook risks. Run
`mvn test -pl loom/services/lucene -Dtest=LuceneSimilarityIndexTest`, then `./setup-pool.sh` and
`mvn test -pl loom/core -Dtest=SimilarAssetsEndpointTest` to confirm the server path still tears down
cleanly.

---

## Task 5: Index per-sector fingerprints so a clip can match a longer video

**Argumentation Summary:** Only `sector_index == 0` — the whole-asset fingerprint — is indexed:
`FingerprintCompEndpointService.createFingerprintComp` guards the hook with
`if (stored.getSectorIndex() == 0)` and `SimilarityEndpointService.loadFingerprint` selects sector 0.
`asset_fingerprint_comp` already models sectors with `sector_index`, `time_from` and `time_to`, and
`MultiSectorFingerprint` produces them, so the schema and the producer are ahead of the index. The
consequence is that a 30-second excerpt of a 40-minute video is not a near-duplicate of it at
whole-asset level, which is exactly the case an operator expects a perceptual index to catch.

**Improvement Summary:** Extend the index to hold one document per sector, keyed by
`(asset_uuid, sector_index)`, and return the matching time range on each hit, while keeping the
existing whole-asset query as the default.

```
1. Design the key change first: the Lucene document key becomes asset_uuid + sector_index, so
   `remove(assetUuid)` must delete every sector of an asset (a term query on ASSET_FIELD, not a
   single-document delete) and `index(...)` must upsert on the compound term. Add SECTOR_FIELD and
   store time_from / time_to alongside it.
2. Extend the SPI in loom-shared/api/.../api/search/: add the sector index and time range to
   IndexedFingerprint, HexFingerprint and SimilarityHit. Keep the existing method signatures
   working for whole-asset callers, defaulting sector to 0.
3. Drop the sector-0 guard in FingerprintCompEndpointService and index every sector row.
4. In SimilarityEndpointService, add a query mode: default stays "whole asset vs whole asset"
   (sector 0 both sides); a new `sectors=true` query parameter queries all of the asset's sectors
   and collapses the hits per matched asset, keeping the best-scoring sector and reporting its time
   range on SimilarAssetResponse.
5. Self-exclusion changes: with several documents per asset the query must drop every sector of the
   query asset, not just one hit, and the limit + 1 over-fetch is no longer sufficient — over-fetch
   by the asset's sector count instead.
6. Update SearchIndexJobRunner.reindexFingerprints and sweepFingerprintOrphans: counts become
   per-sector, and streamIndexedAssetUuids must still yield distinct asset uuids.
7. Note the disk cost in spec/loom/SEARCH_LUCENE.md §7 — the index grows by the mean sector count
   per asset — and add the new query parameter to spec/loom/RESTAPI.md.
```

**References:** [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) §3, §4, §7 ·
[../loom/DOMAIN.md](../loom/DOMAIN.md) (`asset_fingerprint_comp`) · migration
`V2.41__add_asset_fingerprint_comp.sql` ·
[../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md)

**Test Requirements:** New `LuceneSimilarityIndexTest` cases: a multi-sector asset is removed in
full by `remove(assetUuid)`; a sector query finds a clip inside a longer asset; per-algorithm counts
still hold with several sectors per asset. New `SimilarAssetsEndpointTest` cases: the default query
is unchanged by the presence of sector rows, and `sectors=true` returns one collapsed hit per asset
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
4. Do not display SimilarAssetResponse.sha512 until Task 1 is resolved; it is always null.
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
_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (split out of `spec/concept/LUCENE_PLAN.md`)_
