# Search — Elasticsearch Phase 2

> **Decision record plus the task list for the Elasticsearch/OpenSearch backend.** Split out of
> [SEARCH_TASKS.md](SEARCH_TASKS.md) on 2026-08-16 so the "should we?" answer and the "how?" tasks live
> in one place, and so the lexical/semantic task list is not read as an implicit commitment to build
> this. Task numbers are unchanged — **11, 12, 13, 14, 15 and 23** — because other files cite them.
>
> **Context:** [../features/search/SEARCH.md](../features/search/SEARCH.md) (the lexical spec, and the
> authority on what is built) · [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md)
> (vectors and hybrid ranking) · [../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md)
> (the `/search-indices` surface the drain must report into) · [SEARCH_TASKS.md](SEARCH_TASKS.md)
> (everything else: lexical defects, the semantic half, list-route narrowing)
>
> Format follows [TASKS.template.md](TASKS.template.md).

## 0. Verdict — do not start this yet

🔴 **Postgres covers today's cases. Do not begin Task 11.** Assessed 2026-08-16 against the tree at
`19e9d75e`. This section is the argument; §3 is the trigger that would reverse it.

The design already made deferring cheap and that is the point: `search_document` is simultaneously the
Postgres index, the pre-assembled Elasticsearch document and the outbox that would feed it
([SEARCH.md](../features/search/SEARCH.md) §1). Phase 2 changes a Dagger binding, not a pipeline. Nothing
is lost by waiting, and the outbox columns keep being maintained for free.

### 0.1 What the swap would *not* buy

| Assumed benefit | Why it does not apply here |
|---|---|
| **Semantic / hybrid ranking** | ✅ already shipped without Elasticsearch — `TextEmbedder` + `VectorIndex` + RRF in `PostgresSearchProvider.fusedSearch`. Task 23 moves a working path onto a different engine; it adds no capability. **The Elasticsearch case therefore has to be won on lexical scale alone.** |
| **Horizontal scale-out** — the strongest column in [SEARCH.md](../features/search/SEARCH.md) §3 | Not being cashed. `helm/loom/values.yaml:7` is `replicaCount: 1`, and the chart ships no multi-replica search story |
| **Row-level ACL** | Elasticsearch makes it *worse*. On Postgres it is `WHERE library_uuids && :allowed` evaluated in the same transaction as the data (Task 3 in [SEARCH_TASKS.md](SEARCH_TASKS.md)). On Elasticsearch it becomes an eventually-consistent permission projection, where a revoked grant stays effective until the next drain |
| **Faster relevance** | Unmeasured on both sides. "Elasticsearch ranks better" is not a claim this repo can currently support with a number — see Task 24 in [SEARCH_TASKS.md](SEARCH_TASKS.md), which has the same problem for the semantic path |

### 0.2 What Postgres genuinely cannot do — and what to do about each

Ranked by how much a user would notice. Three of the four have a Postgres-side fix.

1. 🔴 **Multilingual stemming — the only recall gap, and it does not need Elasticsearch.**
   `text_search_en` is a *generated* column hardcoded to `to_tsvector('english', …)`
   (`V2.58__add_search_document.sql:58` records why: a data-dependent config is not `IMMUTABLE`).
   `LOOM_SEARCH_TS_CONFIG` is bound on the **query** side only —
   `PostgresSearchProvider.SCORE_EXPRESSION` and `appendMatch()` pass `?::regconfig`. So
   `LOOM_SEARCH_TS_CONFIG=german` stems the *query* with German rules against an *English-stemmed
   index*, which is worse than leaving it alone. A German transcript today is reachable only through
   the unstemmed `simple` vector plus trigram similarity. Whisper transcripts are a first-class corpus
   source and `search_document.lang` is already populated, so this is a real deficiency for any
   non-English deployment — and the fix is a trigger-maintained `tsvector` plus a `lang → regconfig`
   map, a migration inside the existing architecture. Tracked as **Task 25** in
   [SEARCH_TASKS.md](SEARCH_TASKS.md).

2. ⚠️ **The 512 KB body cap**, forced by the Postgres `tsvector` ceiling (1 MB / 16383 lexeme
   positions). A multi-hour transcript is truncated by `search_body_cap()` and its tail is
   unsearchable; `body_truncated` records the loss honestly but does not undo it. **Task 2** in
   [SEARCH_TASKS.md](SEARCH_TASKS.md) (per-segment documents) shards long text into many small
   documents and largely dissolves this, while also fixing `DETECTION`/`SEGMENT` being
   accepted-but-unhittable. Elasticsearch has no equivalent per-document lexeme ceiling, but it is not
   the cheapest fix.

3. ⚠️ **Deep paging past `LOOM_SEARCH_MAX_OFFSET` (1000).** Only bites bulk-export and "select all
   matches" flows; nobody hand-pages to hit 1200. `SearchResult.nextCursor` is already in the envelope
   and always null under Postgres precisely so this can be fixed later without an API change
   ([SEARCH.md](../features/search/SEARCH.md) §5). **No Postgres-side fix** — this one is genuinely
   Elasticsearch-only.

4. ⚠️ **Facet and highlight cost at corpus scale.** `ts_headline` is O(document size) and unindexable
   (already restricted to the returned page); facets are `GROUP BY` over the filtered set. Both degrade
   as the corpus grows, neither has been measured, and this is the one that would eventually force the
   issue. **No Postgres-side fix beyond tuning** — Task 12's `index_options: offsets` mapping is the
   proper answer to the highlight half.

### 0.3 Spend the effort here instead

In this order, all in [SEARCH_TASKS.md](SEARCH_TASKS.md):

- **Task 25** — multilingual `tsvector`. The only outright recall defect above.
- **Task 2** — `DETECTION` / `SEGMENT` documents. Fixes a guaranteed-empty API filter *and* blunts the
  body cap.
- **Task 3** — row-level ACL. `appendFilters` already emits a narrowing predicate that nothing
  populates; it reads like an enforced control and is not one. Doing this on Postgres is strictly
  easier than doing it after a move to Elasticsearch (§0.1).

### 0.4 Keep the outbox

✅ **Do not remove `dirty` / `synced_at` / `es_synced_at` / `search_document_deleted`.** They cost
essentially nothing (the refresh functions write them as part of a per-entity upsert already measured
at ~0.13 ms per asset insert) and they are the entire reason Phase 2 stays a binding change. Treat any
proposal to drop them as a proposal to make this decision irreversible.

## 1. Progress Assessment

- [x] The outbox exists and is maintained by triggers: `dirty`, `synced_at`, `es_synced_at`, and the
      `search_document_deleted` tombstone table (`V2.58`, `V2.59`)
- [x] `LOOM_SEARCH_PROVIDER=elasticsearch` degrades honestly — `SearchModule` binds
      `NoopSearchProvider` with a "not implemented yet" reason rather than silently substituting
      Postgres
- [x] `SearchResult.nextCursor` reserved in the response envelope, always null under Postgres, with the
      "prefer `nextCursor`, fall back to `offset`" client contract already documented
- [x] Decision recorded: **not now** (§0), with the reversal trigger written down (§3)
- [ ] **Task 11** — the client spike. 🔴 **Blocks 12–15 and 23.** Not started, and per §0 should not be
- [ ] **Task 12** — mapping + `ensureSchema()` (gates 13–15)
- [ ] **Task 13** — `ElasticsearchSearchIndexer` + the outbox drain
- [ ] **Task 14** — `ElasticsearchSearchProvider` + health check (gates 15)
- [ ] **Task 15** — provider-parity tests + compose/Helm operability
- [ ] **Task 23** — `dense_vector` + native `knn`/`rrf` (also needs Task 20 in
      [SEARCH_TASKS.md](SEARCH_TASKS.md), or the existing text embeddings)
- [ ] No `LOOM_SEARCH_ES_*` options exist yet — they arrive with Task 12
- [ ] `loom/services/elasticsearch` is `pom.xml` + `README.md` with **no `src/`**
- [ ] ⚠️ Correction to older plans: **do not delete `loom/services/lucene`**. It serves fingerprint
      k-NN ([../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md)), not lexical search. Lucene's
      rejection in [SEARCH.md](../features/search/SEARCH.md) §3 is scoped to lexical search only
- [ ] `loom/services/qdrant` also has no `src/`, and is now unlikely to be needed

## 2. Ordering and blocking

```
Task 11 (spike)  ──┬──> Task 12 (mapping) ──┬──> Task 13 (indexer + drain) ──┐
                   │                        └──> Task 14 (provider) ──> Task 15 (parity + ops)
                   └──> Task 23 (knn/rrf) also needs 12, 13, 14 and vectors (SEARCH_TASKS Task 20)
```

🔴 **Write no production Elasticsearch code before Task 11 resolves.** Every task below assumes bulk
indexing, `search_after`, aliases, templates and `knn` from an internal `1.2.0-SNAPSHOT` client whose
API has never been verified.

⚠️ **Corrections to the ordering notes this file inherited from [SEARCH_TASKS.md](SEARCH_TASKS.md):**
its header said "Task 11 gates Tasks 12–16" while its Progress Assessment simultaneously listed Task 16
as independent. Task 16 (`POST /api/v1/search/results`) is a pure REST addition with no Elasticsearch
content — it is **not** gated and it stays in [SEARCH_TASKS.md](SEARCH_TASKS.md). Likewise "Task 14
gates 17": `/search/facets` is served by facets `PostgresSearchProvider` already computes, so Task 17
is **not** gated either.

## 3. When to revisit

Reverse §0 when **one** of these is true — not before, and not on the strength of "Elasticsearch is the
proper tool":

| Trigger | How to check |
|---|---|
| p95 on `GET /api/v1/search/results` **with `?facets=`** crosses your latency budget on the largest real corpus you have | Measure it. There is no recorded number today, which is itself the gap |
| Loom actually runs multi-replica against a shared Postgres under search load | `helm/loom/values.yaml` `replicaCount > 1` in a real deployment |
| A bulk/export flow genuinely needs to walk past result 1000 | The only gap in §0.2 with no Postgres-side fix |
| A customer requires per-language stemming for **many** languages in one deployment | Task 25 covers one config per deployment plus a `lang → regconfig` map; a genuinely per-document analyser story is Elasticsearch's |

Record the measurement and the date here when it is taken. A revisit driven by a number is a decision;
one driven by a feeling is a rewrite.

---

## Task 11: SPIKE — verify the Elasticsearch client API (was P2-1) — BLOCKS Tasks 12-15, 23

**Argumentation Summary:** `loom/services/elasticsearch` is `pom.xml` + `README.md` with **no `src/`**.
Its pom depends on the internal `io.metaloom.elasticsearch:elasticsearch-client` `1.2.0-SNAPSHOT`, whose
API has never been verified against what Phase 2 needs. Every subsequent Elasticsearch task assumes bulk
indexing, `search_after`, aliases, index templates and — for Phase 3 — `knn`. If that client cannot do
them, the mapping, the indexer and the provider are all built on a wrong assumption and get rewritten.

**Improvement Summary:** Prove or disprove the five capabilities against a real Elasticsearch container,
then pick the client. Write no production Elasticsearch code until this closes.

```
0. READ §0 OF THIS FILE FIRST. The standing decision is that this work is deferred; do not start the
   spike without a trigger from §3 or an explicit instruction. If a trigger fired, record which one and
   its measurement in §3 as step zero.
1. Stand up a current Elasticsearch (or OpenSearch) container and, in a throwaway module or test,
   exercise with io.metaloom.elasticsearch:elasticsearch-client 1.2.0-SNAPSHOT:
     a) bulk index of N documents, with per-document error reporting
     b) search_after paging
     c) alias create / swap
     d) index template / explicit mapping application
     e) a knn query against a dense_vector field
2. Record which of the five work, which do not, and at what version.
3. If any fail, choose a fallback in this preference order and record WHY:
     - co.elastic.clients:elasticsearch-java (official, heavier)
     - plain HTTP through the Vert.x WebClient (dependency-free, and covers OpenSearch identically —
       which matters because the spec promises OpenSearch parity)
4. Also resolve the second open question here: org.testcontainers:elasticsearch is managed at 1.17.6,
   which is old. Confirm it can pull a current image without conflicting with the Testcontainers version
   resolved elsewhere in the reactor, or bump it.
5. Write the outcome into spec/features/search/SEARCH.md §3 (the comparison table) AND into §1 of this
   file with the chosen client named. Do NOT leave the answer only in a commit message.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §0, §3 · `loom/services/elasticsearch/README.md`
· §0 and §3 of this file
**Test Requirements:** The spike's own throwaway harness, plus the recorded answer. Nothing merges to
`main` from the spike except the decision and, if it is the chosen route, a dependency change.

---

## Task 12: Elasticsearch mapping and `ensureSchema()` (was P2-2)

**Argumentation Summary:** An Elasticsearch index whose mapping is inferred rather than declared cannot
be changed without a reindex, and the fields search needs — analysed `title` sub-fields, `body` with
offsets, the ACL keyword arrays, and a `dense_vector` for Phase 3 — are exactly the ones dynamic mapping
gets wrong. Declaring all of them up front, including the ones not yet populated, is what lets Phase 3
land without reindexing the corpus.

**Improvement Summary:** A checked-in mapping JSON, a `LoomSearchMapping` class that applies it, and an
`ensureSchema()` that is safe to run on every boot.

```
1. loom/services/elasticsearch/src/main/resources/loom-search-mapping.json — one index per
   SearchEntityType behind a SINGLE read alias, so a cross-type query is one request.
2. Mirror search_document field for field (SEARCH.md §4), plus:
     - title with keyword and analysed sub-fields (sorting and matching both needed)
     - body with index_options: offsets — this is the proper fix for the ts_headline cost, and
       highlighting is why it must be in the mapping from the start
     - library_uuids / space_uuids / collection_uuids as keyword arrays FROM THE FIRST MAPPING, even
       though nothing populates the ACL request fields yet (SEARCH_TASKS.md Task 3) — adding them
       later is a reindex
     - a declared but unpopulated dense_vector so Task 23 needs no reindex
     - per-language analysers where the corpus warrants them. This is the one place Elasticsearch is
       structurally better than the Postgres path (§0.2 item 1); if SEARCH_TASKS.md Task 25 has landed
       by then, mirror its lang → analyser map rather than inventing a second one.
3. LoomSearchMapping + ensureSchema() in io.metaloom.loom.elasticsearch — idempotent, safe on every
   boot, and it must NOT fail server boot if Elasticsearch is unreachable (SearchModule's contract).
4. Add the LOOM_SEARCH_ES_* options to SearchOptions with validation, and regenerate
   loom/doc/src/main/generated/loom-config.yaml by running
   `mvn -o exec:java -Dexec.mainClass=io.metaloom.loom.doc.ExampleGenerator` FROM INSIDE loom/doc. That
   run also rewrites the OpenAPI files, which churn on random example UUIDs — revert that noise rather
   than committing it.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §3, §4, §5, §7 · Task 11 (the client decision)
· [SEARCH_TASKS.md](SEARCH_TASKS.md) Tasks 3 and 25
**Test Requirements:** A Testcontainers test asserting `ensureSchema()` is idempotent (twice in a row is
clean), that the mapping applies, and that an unreachable Elasticsearch leaves the server booting.

---

## Task 13: `ElasticsearchSearchIndexer` and the outbox drain (was P2-3, P2-4)

**Argumentation Summary:** `search_document.dirty` / `synced_at` / `es_synced_at` and the
`search_document_deleted` tombstone table are already written and maintained by triggers, and **nothing
in Java reads them** — no code outside the generated jOOQ classes touches them. They are a live, correct,
unconsumed feed. That is the load-bearing property of the whole design: Phase 2 starts from a populated
outbox rather than a backfill project (§0.4).

**Improvement Summary:** A bulk indexer plus a periodic drain that claims work with `FOR UPDATE SKIP
LOCKED`, so it is safe on every replica with no coordination.

```
1. ElasticsearchSearchIndexer implementing SearchIndexer: bulk index, retry with backoff, per-document
   error capture into search_document.error, dead-letter after N attempts. Do not let one poison
   document stall the drain.
2. ElasticsearchIndexSyncService — a Vert.x periodic verticle. The claim query is
     SELECT … FROM search_document WHERE dirty ORDER BY synced_at LIMIT :bulk FOR UPDATE SKIP LOCKED
   SKIP LOCKED is not an optimisation: it is what makes the drain correct when several replicas run it
   concurrently with no leader election.
3. Drain search_document_deleted in the same pass and issue the deletes. Tombstones are REQUIRED because
   the asset FK cascade removes search_document rows before any external indexer could observe the
   delete. Prune tombstones after 7 days.
4. Backfill is NOT a separate code path: search_document_rebuild() marks everything dirty and the normal
   drain handles it. Do not write a second bulk-load path.
5. Wire the drain's backlog into the /search-indices admin surface — the lexical index reports
   dirtyCount = 0 by construction today (SEARCH_INDEX_ADMIN.md); an Elasticsearch index must report the
   real backlog.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §4.3 (the unread outbox), §5 ·
[../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md) ·
`V2.58`, `V2.59`
**Test Requirements:** Testcontainers tests: a dirty row reaches Elasticsearch and is marked synced; a
delete leaves a tombstone that becomes a delete in the index; two concurrent drains never double-index
the same document; a poison document is dead-lettered without stalling the rest. Use `refresh=wait_for`
in assertions.

---

## Task 14: `ElasticsearchSearchProvider` and its health check (was P2-5, P2-6)

**Argumentation Summary:** `LOOM_SEARCH_PROVIDER=elasticsearch` currently resolves to
`NoopSearchProvider` with a "not implemented yet" reason — an honest degradation, but it means the option
exists and does nothing. The read side is also where the capability story pays off: Elasticsearch can
advertise `DEEP_PAGING`, which Postgres never does (§0.2 item 3), and the `nextCursor` field is already
in the response envelope and always null under Postgres precisely so this swap needs no API change.

**Improvement Summary:** The read-side provider plus a health component, wired through the existing
`SearchModule` fallback.

```
1. ElasticsearchSearchProvider implementing SearchProvider: query, highlight (using the offsets from the
   Task 12 mapping), facets, and search_after populating SearchResult.nextCursor.
2. Advertise DEEP_PAGING, FACETS and HIGHLIGHT. Keep capabilities() computed PER CALL, never cached — the
   UI renders its mode toggle from it and a backend that dies must retract its capabilities (SEARCH.md
   §2).
3. Bind it in loom/core/.../dagger/SearchModule.java under provider=elasticsearch, keeping the existing
   contract absolutely: any construction exception is logged and falls back to NoopSearchProvider.
   Search must never fail server boot.
4. ElasticsearchHealthCheck plus a `search` component on HealthEndpoint / HealthCheckResponse.
   WARNING: HealthEndpoint is registered WITHOUT secure(...). Do not leak the Elasticsearch URL,
   credentials or cluster name through it.
5. Clients must prefer nextCursor when present and fall back to offset — that contract is already
   documented (SEARCH.md §5); verify loom-ui honours it before this ships, or deep paging silently keeps
   using offsets.
6. Row-level ACL, if SEARCH_TASKS.md Task 3 has landed by then, is NOT the same control here that it is
   on Postgres: the Postgres predicate is evaluated in the same transaction as the data, this one reads
   an index that lags by one drain interval. Say so explicitly in SEARCH.md §6.1 rather than letting the
   two providers appear equivalent.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §2, §2.1, §5, §6.1 · Task 12 ·
[SEARCH_TASKS.md](SEARCH_TASKS.md) Task 3
**Test Requirements:** Testcontainers coverage of query, highlight, facets and `search_after` paging;
plus an endpoint test that `/search/status` reports `provider: elasticsearch` and that an unreachable
cluster still answers 200 with `available:false`.

---

## Task 15: Provider-parity tests and Phase 2 operability (was P2-8, P2-9)

**Argumentation Summary:** Two providers behind one SPI is only useful if they answer the same question
the same way. Without a parity test, "swap the binding" becomes "swap the binding and discover six months
later that phrase queries rank differently". Separately, Elasticsearch is a new service to operate: it
needs a compose entry that does not slow the default dev loop, and a Helm story that respects the chart's
"official images, no third-party subcharts, works offline" policy.

**Improvement Summary:** A fixture-corpus parity test across both backends, plus the compose and Helm
plumbing.

```
1. Provider-parity test: the same fixture corpus indexed in both backends returns the same TOP-5 SET
   (not the same order — rankers legitimately differ) for a fixed list of queries covering phrase,
   negation, stemming, typo tolerance and type filtering.
2. Use refresh=wait_for so the test is not racing the index.
3. Keep every test class at 15 methods or fewer — the test-DB pool provisions in tens (max 60).
4. Ops: an `elasticsearch` service in docker compose behind a PROFILE, so the default dev loop stays
   fast and nobody pays for a search backend they are not testing.
5. helm/loom/values.yaml — a `search:` block, plus an optional bundled single-node StatefulSet following
   the chart's existing policy: official images, no third-party subcharts, works offline.
6. Document the operational failure modes in the website docs, including the managed-Postgres one from
   Phase 1 that is still undocumented: pg_trgm is NOT a trusted extension and needs superuser or
   rds_superuser, with "ask your DBA to pre-create the extension" as the remedy.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §3, §8, §10 · `helm/loom/values.yaml` ·
[WEBSITE_TASKS.md](WEBSITE_TASKS.md)
**Test Requirements:** The parity test itself, green against both providers; a `helm template` render of
the new block; and a compose-profile smoke run.

---

## Task 23: Elasticsearch `dense_vector` and native `knn` / `rrf` (was P3-15)

**Argumentation Summary:** Java-side RRF fusion over a separate vector index is the right answer while
Postgres is the only backend. Once Elasticsearch exists, it can do k-NN and reciprocal-rank fusion
natively in one query, which removes a round trip and the Java fusion step from the hot path. ⚠️ This is
an optimisation of a **working** path, not a new capability — see §0.1.

**Improvement Summary:** Populate the `dense_vector` declared in Task 12's mapping and use native `knn`
plus `rrf` in `ElasticsearchSearchProvider`.

```
1. Requires Tasks 11-14 AND the vectors from SEARCH_TASKS.md Task 20 (or the existing text embeddings).
2. Populate the dense_vector field in the Task 13 drain. Because Task 12 declared the field in the
   original mapping, this needs NO reindex — that was the point of declaring it unpopulated.
3. Implement SEMANTIC and HYBRID in ElasticsearchSearchProvider using native knn and rrf.
4. Keep the results commensurable with the Postgres path: the parity test from Task 15 must cover
   semantic mode too, or the two backends quietly rank differently in the one mode users notice most.
```

**References:** [SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) §5.2 ·
[SEARCH.md](../features/search/SEARCH.md) §10 ("two search paths, one provider") · Tasks 11-14 ·
[SEARCH_TASKS.md](SEARCH_TASKS.md) Task 20
**Test Requirements:** Extend the Task 15 parity test with semantic and hybrid queries across both
backends.

---

## 4. Where do I find …?

| Need | Look here |
|---|---|
| What lexical search already does, and why Postgres was chosen | [../features/search/SEARCH.md](../features/search/SEARCH.md) §3, §4 |
| The outbox columns and the tombstone table | `loom/db/flyway/src/main/resources/db/migration/V2.58__add_search_document.sql`, `V2.59__add_search_triggers.sql` |
| The provider binding that must never fail boot | `loom/core/src/main/java/io/metaloom/loom/core/dagger/SearchModule.java` |
| The stub module | `loom/services/elasticsearch/` — `pom.xml` + `README.md`, no `src/` |
| Where a drain must report its backlog | [../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md) |
| Everything else still open on search | [SEARCH_TASKS.md](SEARCH_TASKS.md) |
| The *other* Lucene index (fingerprint k-NN — do not delete it) | [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) |

_Git HEAD revision: `19e9d75e`_
_Last updated: 2026-08-16 (created — Tasks 11-15 and 23 moved here verbatim from SEARCH_TASKS.md, plus
the §0 assessment that Postgres covers today's cases and Phase 2 should not start. Corrections recorded
while splitting: Task 16 and Task 17 are not gated by the Elasticsearch spike; the multilingual gap
LOOM_SEARCH_TS_CONFIG only half-closes became SEARCH_TASKS.md Task 25.)_
