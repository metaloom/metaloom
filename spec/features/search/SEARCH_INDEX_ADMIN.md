# Search Index Administration — Technical Specification

> The operator surface over every index Loom maintains: the lexical `search_document` table, each
> embedding vector space, and the perceptual fingerprint index. Covers `GET /api/v1/search-indices`,
> the job routes beneath it, the two permissions that gate them, and the `/admin/indices` screen.
>
> Lexical design: [SEARCH.md](SEARCH.md). Vector design: [SEMANTIC_SEARCH.md](SEMANTIC_SEARCH.md).
> Fingerprint index: [../../concept/LUCENE_PLAN.md](../../concept/LUCENE_PLAN.md). UI shell:
> [../../loom/ui/LOOM_UI.md](../../loom/ui/LOOM_UI.md).

## 0. Status

🟢 **Built and green.** Backend, both clients, the UI screen and its tests all ship.

| Piece | Where |
|---|---|
| Registry + job model | `loom/services/rest/…/rest/search/{SearchIndexRegistry,SearchIndexJobRunner,IndexJobRegistry,IndexJob,IndexJobAction,IndexJobState,SearchIndexDescriptor,SearchIndexBackend,SearchIndexKind}.java` |
| REST | `SearchIndexEndpoint` + `SearchIndexEndpointService`; models in `loom-shared/rest-model/…/model/searchindex/` |
| Permissions | `READ_SEARCH_INDEX`, `MANAGE_SEARCH_INDEX` — `V2.85` adds them, `V2.86` grants them to the existing admin role |
| SPI additions | `VectorIndex.{drop,status,status(space),streamIndexedEmbeddingUuids}`, `SimilarityIndex.{drop,status,status(algorithm),streamIndexedAssetUuids,providerName}`, `SearchIndexer.rebuild()`, `IndexStatus.{sizeBytes,deletedCount}` |
| DAO | `EmbeddingDao.{listSpaces,streamAll(space),findDirty(space),filterExisting}`, `AssetComponentDao.{streamByAlgorithm,countByAlgorithm,filterExistingFingerprintAssets}` |
| Clients | `SearchIndexMethods` (Java), `loom_client/methods/search_index.py` (Python) |
| UI | `loom-ui/src/features/admin/SearchIndicesAdmin.tsx`, `src/api/searchIndices.ts`, `src/components/StatusChip.tsx` |
| Tests | 14 `SearchIndexEndpointTest` · 12 `EmbeddingDaoTest` · 25 Lucene · 18 vitest · 12 `search-indices-mocked.spec.ts` |

---

## 1. The problem this solves

Four index-like structures existed with no way to look at any of them. Between them the only
operator surface was three undocumented RPC routes — `POST /vector-index/{rebuild,sync}`,
`GET /vector-index/status`, `POST /similarity-index/rebuild` — which reported no size, no backlog,
no producing model, and ran the whole rebuild inside the HTTP request.

They also fail in the same three ways despite being built completely differently, which is the
argument for one screen rather than three:

| Failure | Looks like |
|---|---|
| **Behind** | The index holds fewer entries than the database |
| **Drifted** | It holds more — orphans left by deletes it never saw |
| **Broken** | It is configured but did not open |

## 2. The index model

There is **no registry table, and there must not be one.** A vector space exists because rows with
that `(type, model, dimensions)` triple exist; `embedding.type` and `.model` are free text precisely
so a new model needs no code change. Deriving the list from the data is what keeps that true. So
`SearchIndexRegistry.list()` recomputes on every request.

| id | kind | System of record | Actions |
|---|---|---|---|
| `lexical` | `LEXICAL` | `search_document` | `REINDEX` |
| `vector-<type>-<slug(model)>-<dims>` | `VECTOR` | `embedding` rows in that space | `REINDEX`, `DELTA_SYNC`, `DROP` |
| `fingerprint` | `FINGERPRINT` | `asset_fingerprint_comp` | `REINDEX`, `DELTA_SYNC`, `DROP` |

### 2.1 Why ids are slugs resolved by lookup

`SearchIndexId.of(space)` lowercases and collapses non-alphanumerics: `vector-face-inspireface-r18-512`.
Model names routinely contain a slash (`sentence-transformers/all-MiniLM-L6-v2`), and while Vert.x 5
does survive `%2F` in a path segment, reverse proxies frequently reject or pre-decode it.

The slug is therefore **not reversible**, and `SearchIndexRegistry.find(id)` matches against the live
list rather than parsing. That is a feature: an id can only ever name an index that exists, and a
collision is detectable at list time instead of silently routing an operation to the wrong index.

### 2.2 Why size is per backend, not per index

🔴 **A per-space byte figure does not exist.** One Lucene directory holds every vector space at once
and its segments interleave them. Splitting the bytes by document share would look authoritative and
be invented, so `SearchIndexListResponse` has a second array — `backends[]` — carrying `sizeBytes`,
`documentCount` and `deletedCount`, and the indices beneath a backend carry counts alone.

`deletedCount` is reported because Lucene deletes are **logical**: after a drop, the space is not
released until the next merge. Without that number an operator sees a drop that "did not work".

### 2.3 What "pending" means, per kind

| Kind | Source of the number | Why |
|---|---|---|
| `LEXICAL` | Hardcoded **0** | Triggers maintain `search_document` inside the writing transaction; it cannot lag. `PostgresSearchProvider.info()` sets `dirtyCount = 0` for the same reason |
| `VECTOR` | `embedding.dirty` count for that space | The existing outbox flag, `idx_embedding_dirty` |
| the semantic space | `dirty` **plus** `SearchEmbeddingService.pendingCount()` | The bigger backlog is upstream of the flag: documents whose text changed since they were embedded have no row to mark dirty. Reporting only the flag would show 0 while ten thousand freshly imported assets wait on the embedding host |
| `FINGERPRINT` | `max(0, records − indexed)` | `asset_fingerprint_comp` has no freshness column, so this is inferred rather than tracked |

⚠️ `SearchEndpointService.status` substitutes the semantic backlog into `/search/status`'s
`dirtyCount`. On this screen those two numbers belong to **different rows** and must not be
conflated.

### 2.4 The semantic space is synthesised when empty

If `LOOM_SEARCH_SEMANTIC_ENABLED=true` and no vector has been written yet, `listSpaces()` returns
nothing for it — and the screen would show no semantic index at exactly the moment an operator
enables semantic search and wants to watch the backlog drain. `SearchIndexRegistry.vectorSpaces()`
therefore adds the configured space with zero counts when it is absent.

---

## 3. Jobs

`POST /search-indices/:id/jobs` answers **202** with an `IndexJobResponse` to poll. A job collection
rather than `/reindexes` + `/drops` + `/syncs`: the paths stay plural per
[CODING.md](../../guidelines/CODING.md), and a client polls one route regardless of what it started.

```
IndexJob { uuid, indexId, action, state, processed, total?, removed,
           startedAt, finishedAt, error, cancelRequested }
state: PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED
```

| Rule | Why |
|---|---|
| 🔴 **Executor is `vertx.createSharedWorkerExecutor("loom-index-jobs", 1, 24, HOURS)`** | Not `executeBlocking`. A real reindex runs for minutes; on the shared worker pool it occupies a general-purpose worker and trips the blocked-thread checker (60 s default), logging a stack trace per second — a healthy rebuild would look like an incident. Pool size **1** also serialises jobs globally for free, which matters because each Lucene backend is single-writer anyway. `JooqModule.java:59` is the in-repo precedent |
| **One job per index, else 409** | Queueing would let an impatient operator stack five rebuilds each redoing the last one's work. The conflict names what is already running |
| **`total` is nullable** | The lexical rebuild is one SQL call to `search_document_rebuild()` with no intermediate progress. A fabricated total would make the client draw a determinate bar over an unpredictable operation. The UI renders indeterminate when it is null |
| **Cancel is cooperative** | Jobs hold a Lucene write lock and a database cursor; interrupting either is how a cancelled rebuild becomes a corrupt index. Cost: a job inside one long call finishes that call first |
| **In memory, bounded to 32 terminal jobs** | A job asks for work derivable from the database — a restart loses the record and nothing is inconsistent. Eviction skips running jobs, or their own progress would become unpollable |

### 3.1 Reindex is drop-then-fill, never `rebuild()`

🔴 **`VectorIndex.rebuild(Stream)` calls `writer.deleteAll()`.** One directory holds every space, so
reindexing the face vectors through it would silently empty the search-text vectors beside them.
`drop(VectorSpace)` exists for exactly this, and `LuceneVectorIndexTest#shouldDropOneSpaceAndLeave
ItsNeighboursIntact` is the guard.

The space filter needs three clauses:

```java
type == space.type() AND model == space.model() AND FieldExists(vec_<dimensions>)
```

The `FieldExistsQuery` is not optional — the dimension is carried structurally by the k-NN field name
rather than as a filter term, so type + model alone would also take a same-named model of a different
length, which is the one pair `VectorSpace` exists to keep apart.

### 3.2 Delta sync and the orphan sweep

`embedding` cascades away with its asset and leaves **no tombstone**, so an index that missed the
delete hook — disabled at the time, or the process died between the two writes — keeps answering with
vectors whose rows are gone. A rebuild cures it by starting empty; a delta sync has to find them.

`streamIndexedEmbeddingUuids()` walks the `embedding_uuid` **term dictionary**, in batches of 10k
diffed against `EmbeddingDao.filterExisting`. Two deliberate choices:

- **Unscoped.** The term dictionary is per field, not per space; scoping would force a filtered
  document walk with stored-field decompression over every doc.
- **liveDocs is not consulted.** The dictionary retains terms for deleted-but-unmerged documents, so
  filtering would be needed to avoid false orphans — except that deleting an already-deleted document
  is a no-op, so the sweep deletes blindly and the problem disappears.

Per kind: vector spaces drain `dirty` then sweep (and the semantic space additionally calls
`embedAllStale`, which costs inference calls and is therefore a button press, never a poll side
effect); fingerprints sweep only; the lexical index does not support the action at all.

---

## 4. Permissions

| Permission | Gates |
|---|---|
| `READ_SEARCH_INDEX` | `GET /search-indices`, `/:id`, `/:id/jobs`, `/:id/jobs/:jobUuid` |
| `MANAGE_SEARCH_INDEX` | `POST /:id/jobs`, `DELETE /:id/jobs/:jobUuid` |

This resolves [SEARCH_PLAN.md](../../concept/SEARCH_PLAN.md) open item 3. The routes were gated on
`UPDATE_ASSET`/`READ_ASSET`, which conflated two unrelated authorities — an editor who can retag a
photo should not be able to empty the face index. `SearchIndexEndpointTest` asserts both directions:
`READ_SEARCH_INDEX` alone cannot start a job, and `UPDATE_ASSET` no longer can either.

⚠️ **`V2.86` is required and is not redundant with `DatabaseInitializer`.** That grants every
`Permission.values()` only inside `if (role == null)` — on an existing installation a new enum value
reaches nobody, and the administrator of an upgraded instance would find the screen answering 403.
It must be a separate migration from `V2.85` because Postgres will not let a transaction use an enum
value it added.

---

## 5. Server-side rejection, not UI courtesy

Each descriptor advertises `supportedActions` so the screen can hide a button, but the check is also
in `SearchIndexEndpointService`:

- `DROP` on `lexical` → **400** naming what the index does accept. Emptying `search_document` would
  make search answer nothing until a rebuild, while triggers repopulated it piecemeal on the next
  write; a rebuild covers every case a drop was reached for.
- A job against an unavailable index → **503** naming the reason, never a reported successful rebuild
  of nothing. "The index is off" and "the index is empty" are opposite answers.
- Reading, by contrast, is **200 even when everything is broken** — this is the screen an operator
  opens when something is already wrong, and a 503 would hide the diagnosis behind the symptom.

---

## 6. UI

`/admin/indices` → `loom-ui/src/features/admin/SearchIndicesAdmin.tsx`. Its own file, not inside
`AdminArea.tsx` (already ~1.6k lines); `AdminArea` carries only the tab entry and the route.

| Aspect | Rule |
|---|---|
| Layout | One card per backend (provider, availability, on-disk size, deleted count) with its indices in a table beneath |
| Actions | Rendered from `supportedActions`, never hardcoded |
| Polling | 2 s while any index has an active job, 15 s otherwise. Self-rescheduling `setTimeout`, because the cadence depends on the response |
| Failure | A failed poll raises a banner and **keeps the last good snapshot** — a blank screen is a worse answer than a stale one to "is anything broken". A 403 is separate: it replaces the screen, since that state is stable |
| Progress | Determinate only when `total` is non-null; `data-progress` carries `"indeterminate"` otherwise so a spec can assert it |
| Errors | `useToast()`. ⚠️ `AdminArea.tsx` never imports it and swallows errors into `console.error`; new admin screens should not copy that |
| Test ids | `search-indices-admin`, `search-index-backend-<id>`, `search-index-backend-size-<id>`, `search-index-row-<id>` (+ `data-state`), `search-index-status-<id>`, `search-index-pending-<id>`, `search-index-action-<id>-<action>`, `search-index-job-progress-<id>` (+ `data-progress`), `search-index-drop-confirm[-text]`, `search-indices-{loading,error,empty,forbidden}` |

---

## 7. Backward compatibility

`/vector-index/{rebuild,sync,status}` and `/similarity-index/rebuild` stay registered. This is
supersession, not a rename: `rebuildSimilarityIndex` exists in both clients, and removing it would
force a Python method deletion and a parity-tripwire churn for no benefit.

**Fixed in passing:** `LoomOpenAPI.endpoints()` was missing `SearchEndpoint` and
`SimilarityIndexEndpoint` entirely — both were wired in `EndpointModule` but absent from
`openapi.json`, and `clients/python/tests/test_parity.py` carried five paths as "stale spec"
exceptions. All three endpoints (plus the new one) are now registered and those exceptions are gone.

---

## 8. Conventions and gotchas

| # | Rule |
|---|---|
| 1 | 🔴 **Never reach for `VectorIndex.rebuild(Stream)` for one space.** §3.1 |
| 2 | 🔴 **`vertx.executeBlocking` is wrong for these jobs.** §3 |
| 3 | ⚠️ **On-disk size does not shrink after a drop** until a merge. Label it as including deleted entries; do not call `forceMergeDeletes()` on a request path |
| 4 | ⚠️ **`PostgresSearchProvider.info()` sets `lastSyncedAt = Instant.now()`** — it is not a freshness signal. The registry does not surface it for the lexical index |
| 5 | **A new `loom_permission` value needs a jOOQ enum update too.** `JooqLoomPermission` is hand-maintained (`generate.sh` regenerates everything else); without it `setup-pool.sh` fails with `No enum constant JooqLoomPermission.READ_SEARCH_INDEX` |
| 6 | **Clean-rebuild `loom/core` after the endpoint constructor change, before `./setup-pool.sh`** — stale Dagger factories throw a confusing `NoSuchMethodError` |
| 7 | ℹ️ `SimilarityHit.sha512()` is always null. Both the write hook and the rebuild pass null because `asset_fingerprint_comp` does not carry the content hash. Consistent, and consistently empty in `SimilarAssetResponse.sha512` — a pre-existing dead field, not a regression of this change |

## 9. Test setup

```bash
./setup-pool.sh                                        # after V2.85/V2.86
mvn -o -pl loom/services/lucene test -Dtest='Lucene*IndexTest'     # 25
mvn -o -pl loom/db/jooq        test -Dtest=EmbeddingDaoTest        # 12
mvn -o -pl loom/core           test -Dtest=SearchIndexEndpointTest # 14
cd loom-ui && ./node_modules/.bin/vitest run src/api/searchIndices.test.ts        # 18
cd loom-ui && ./node_modules/.bin/playwright test e2e/search-indices-mocked.spec.ts  # 12
```

The default test configuration binds **no** vector index and disables similarity, which is the
interesting case rather than a limitation: it is what a fresh install looks like, and it is exactly
when an operator opens this screen. Most endpoint assertions are therefore about degrading honestly.

## 10. Demo

`loom/containers/demo/Containerfile` sets `LOOM_VECTOR_INDEX_PROVIDER=lucene` and
`LOOM_SIMILARITY_ENABLED=true`. Both are local directories under the writable `/loom`, need no
external service, and fall back to a no-op implementation if the directory cannot be opened, so
neither can fail boot. The index is not on a volume and is lost when the container is replaced —
harmless, since both are declared rebuildable caches and the screen's own Reindex button is the
repair. `LOOM_SEARCH_SEMANTIC_ENABLED` stays **false**: it needs an embeddings host the demo cannot
reach, and a row reading "disabled, and here is why" is the more honest demo.

`DemoDatabaseInitializer` needs no change — the vector spaces materialise from whatever embeddings
the demo pipelines produce.

---

_Git HEAD revision: `27894151`_
_Last updated: 2026-08-09 (initial — the search index admin surface)_
