# Search — Task List

> Open work items for lexical, semantic and Elasticsearch-backed search, derived from a code audit on
> 2026-08-11 against `loom-shared/api`, `loom/db/jooq`, `loom/services/{rest,mcp,graphql,elasticsearch}`,
> `loom/core`, `cortex/nodes` and `loom-ui/src`. Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../features/search/SEARCH.md](../features/search/SEARCH.md) (lexical technical spec, and
> the authority on what is built) · [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md)
> (vectors, embedding, hybrid ranking) · [../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md)
> (the `/search-indices` admin surface) · [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) (the
> *other* index — perceptual fingerprint k-NN, tracked in [SEARCH_LUCENE_TASKS.md](SEARCH_LUCENE_TASKS.md))
>
> This file replaces the former `spec/concept/SEARCH_PLAN.md`. Everything that file recorded as built is
> now described in [SEARCH.md](../features/search/SEARCH.md) §0 and §12; everything it recorded as
> outstanding is a task below. The old plan IDs (`P0-5`, `P1-19`, `P2-4`, `P3-12`, …) are carried in the
> task headings so existing cross-references still resolve.
>
> **Removed as implemented** — this file carries no task text for them: the `io.metaloom.loom.api.search`
> SPI, `SearchOptions` (25 env vars) and its validation, migrations `V2.57`–`V2.59` (`READ_SEARCH`,
> `search_document`, `search_document_deleted`, 12 functions, 17 triggers, backfill), the jOOQ codegen
> exclusion, `PostgresSearchProvider` with ranking / facets / highlighting / suggest, `NoopSearchProvider`,
> the boot-safe `SearchModule` binding, the four `/api/v1/search/*` routes with the `READ_SEARCH` gate and
> per-type narrowing, the REST models and both clients, the whole loom-ui consumer (`api/search.ts`,
> `SearchContext`, `GlobalSearchField`, `/search` view, server-side asset browsing in both the asset
> browser and the library panel), the demo corpus the
> backend e2e asserts against, the customer-facing website docs, the reindex admin surface (which
> superseded `/search/reindexes`), and the entire text half of semantic + hybrid search
> (`TextEmbedder`, `OpenAiTextEmbedder`, `RankFusion`, `SearchEmbeddingService`, `SearchEmbeddingDrainer`,
> dynamic capabilities, `sidecars/llamacpp-embeddings`).
>
> **Ordering / blocking:**
> * **Task 2 is the remaining correctness defect** — two API-accepted entity types that can never
>   produce a hit. Do it first. (Task 1, the MCP tools that ignored their query argument, is ✅ done.)
> * **Task 11 (the Elasticsearch client spike) gates Tasks 12–16 and Task 23.** Do not write Elasticsearch
>   code before it resolves.
> * Task 12 gates 13–15; Task 14 gates 15 and 17.
> * Task 18 gates Task 19.
> * **Task 20 (the CLIP image node) is the single thing between here and text-to-image search** — the
>   ranker, the fusion and the UI mode toggle would all consume its output unchanged.
> * Tasks 3–10, 16, 21, 22 and 24 are independent of everything else.

## Progress Assessment

- [ ] **Defects:** ~~Task 1 (MCP `search_assets` ignores `query`)~~ ✅ done, Task 2 (`DETECTION` / `SEGMENT` produce no documents)
- [ ] **Dead or half-wired code:** Task 3 (row-level ACL clause), Task 9 (orphaned loom-ui trees)
- [ ] **Consumers not yet on the SPI:** ~~Task 4 (`LibraryView`)~~ ✅ done, Task 7 (GraphQL)
- [ ] **Test and regression guards:** Task 5 (codegen guard), Task 6 (document-source coverage), Task 24 (retrieval quality)
- [ ] **Ergonomics:** Task 8 (transcript timecode deep link), Task 10 (`searchParams()`), Task 16 (`POST /search/results`), Task 17 (`/search/facets`)
- [ ] **Phase 2 — Elasticsearch:** Tasks 11–15, gated on the Task 11 spike; the outbox they drain already exists and is maintained
- [ ] **List-route narrowing (a different feature from `/search/*`):** Tasks 18, 19
- [ ] **Phase 3 — the image half:** Tasks 20–23

---

## Task 2: Emit `DETECTION` and `SEGMENT` search documents (was P1-26)

**Argumentation Summary:** `SearchEntityType.DETECTION` and `SearchEntityType.SEGMENT` are accepted by
`/api/v1/search/results?types=`, are mapped to `READ_DETECTION` / `READ_ASSET` in the narrowing table, and
are offered by the loom-ui type chips — but `search_document_rebuild()` loops only asset, tag, person,
collection, library, cluster and annotation, and no refresh function writes a row for either. Detection
labels and `asset_segment_comp.title` fold into the owning asset's `keywords` (weight D), so the text is
*searchable* but can never surface as a hit of its own. The result is a filter that is guaranteed to
return an empty page, which is indistinguishable to a user from a broken index.

**Improvement Summary:** Add one refresh function per type plus their triggers, following the existing
per-entity pattern exactly, so `search_document_rebuild()` picks them up unchanged.

```
1. New Flyway migration in loom/db/flyway/src/main/resources/db/migration/. Check the highest existing
   version FIRST, sorting numerically (`ls … | sort -t. -k2 -n | tail`) — lexical sort puts V2.9 after
   V2.99. Another branch may take the next number.
2. Add search_document_refresh_detection(p_uuid uuid) and search_document_refresh_segment(p_uuid uuid)
   modelled on search_document_refresh_annotation in V2.59:
     - detection: title = detection.label, subtitle = the owning asset's filename, body = any
       description/attribute text, keywords = the label tokenized, asset_uuid = detection.asset_uuid,
       time_from = the detection's start time where one exists.
     - segment: title = asset_segment_comp.title, subtitle = the owning asset's filename,
       time_from = the segment start, asset_uuid = the owning asset.
   Populate library_uuids / space_uuids / collection_uuids from the owning asset exactly as the asset
   refresh does, or ACL narrowing (Task 3) will silently exclude them later.
3. Extend the existing detection / asset_segment_comp triggers so they refresh BOTH the owning asset's
   document (as today, via search_tg_refresh_by_asset_uuid) AND the entity's own document. Do not
   replace the asset-side refresh: the keyword folding is what makes a detection label surface the asset
   in a plain search.
4. Add both types to the loop inside search_document_rebuild() so rebuild-equals-incremental keeps
   holding by construction — that identity is the whole drift defence (SEARCH.md §4.2).
5. Run ./setup-pool.sh after the migration lands. Codegen is NOT needed: no new table, no new column.
6. Remove the "detection and segment are accepted but never hit" row from SEARCH.md §5.1 and the
   corresponding red note in §2, and drop the guaranteed-empty caveat from the loom-ui type chips if one
   is rendered there.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §2 (the enum with no documents), §4.1
(keyword folding), §4.2 (triggers), §6 (permission mapping) · `V2.59__add_search_triggers.sql`
**Test Requirements:** New methods in `SearchDocumentSourceTest` (`loom/db/jooq/src/test/…/search/`) —
a detection label surfaces as a `DETECTION` hit *and* still surfaces its asset; a segment title surfaces
as a `SEGMENT` hit carrying `timeFrom`. The existing rebuild-equals-incremental case in
`SearchDocumentLifecycleTest` must stay green unchanged. Keep each class at 15 methods or fewer — the
test-DB pool provisions in tens. Add a `types=detection` case to `SearchEndpointTest`.
`./setup-pool.sh && mvn -o -pl loom/db/jooq test -Dtest='Search*'`

---

## Task 3: Populate the row-level ACL narrowing, or delete the dead clause

**Argumentation Summary:** `search_document.library_uuids` / `space_uuids` / `collection_uuids` are
maintained by triggers and GIN-indexed, `SearchRequest.allowedLibraryUuids` / `allowedSpaceUuids` exist
(`SearchRequest.java:75,78`), and `PostgresSearchProvider.appendFilters` already emits the narrowing
predicate — but **nothing in the tree calls either setter**. The clause is unreachable code that reads
like an enforced control. Anyone auditing search authorization sees ACL columns, an ACL request field and
an ACL SQL predicate, and can reasonably conclude row-level filtering is on. It is not: enforcement is the
global `READ_SEARCH` gate plus per-type narrowing, which is exact parity with the rest of the API
(`AbstractCRUDEndpointService.list()` does one global `checkPerm` and no user-scoped filtering).

**Improvement Summary:** Either populate the two sets in `SearchEndpointService` from the requesting
user's library/space membership, or delete the fields and the clause. Populating is the better outcome
only if Loom adopts row-level ACL generally — search must not become the one endpoint with a different
authorization model. Until that decision, at minimum make the dead code visibly dead.

```
Decision required before code. Two acceptable outcomes:

A) Wire it (only alongside a Loom-wide row-level ACL decision):
   1. In loom/services/rest/.../service/impl/SearchEndpointService.java, resolve the caller's readable
      library and space UUIDs from the request-scoped LoomRoutingContext and set them on SearchRequest.
   2. Note the reindex fan-out this creates (SEARCH.md §6.1): library_asset / collection_asset changes
      are already triggered; a project_library change means EVERY asset in that library and needs a
      batched job, not a trigger; group/role/space MEMBERSHIP changes need no reindex at all, because
      membership is a query-time set. That last point is what keeps the fan-out bounded.
   3. No reindex and no migration are needed — the columns are already populated.

B) Retire it:
   1. Remove allowedLibraryUuids / allowedSpaceUuids from SearchRequest and the corresponding branch
      from PostgresSearchProvider.appendFilters.
   2. Keep the explicit ?library= / ?space= / ?collection= filters — those ARE read and are a user
      filter, not an ACL.
   3. Record in SEARCH.md §6.1 that the columns remain for a future ACL and that switching it on is a
      `WHERE library_uuids && :allowed` clause with no reshaping and no reindex.

Do NOT leave it as it is. Whichever route is taken, update SEARCH.md §4.3 and §6.1 so the spec stops
describing a half-wired control.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §4.3 ("what is populated but unread"), §6.1
(row-level ACL, absent by design) · [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md)
**Test Requirements:** For (A): an endpoint test where two users with disjoint library membership issue
the same query and each sees only their own assets, plus a case proving a group-membership change takes
effect without a reindex. For (B): compilation plus the existing `Search*` suites staying green.

---

## Task 5: Add `SearchDocumentCodegenTest` (was P1-6b)

**Argumentation Summary:** `search_document` carries three generated, stored columns — `text_search`,
`text_search_en`, `trgm_text` — that jOOQ cannot bind (there is no `tsvector` binding) and that fail an
`INSERT` if they ever reach one. They are kept out of the generated code by the `<excludes>` pattern
`.*\.text_search.*|.*\.trgm_text` in `loom/db/jooq/pom.xml`. The obvious narrower pattern
`.*\.text_search` misses both `text_search_en` and `trgm_text`. That exclusion was verified **by
inspection only**, so a future `generate.sh` run — or an innocent-looking edit to the pattern — can
silently reintroduce `Object`-typed columns that reach an insert at runtime, in a module whose codegen
output is committed and rarely read.

**Improvement Summary:** Four lines of reflection asserting `JooqSearchDocument` never regains the three
fields.

```
1. New test class loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/search/SearchDocumentCodegenTest.java
   — a plain unit test, NO database, so it does not consume a pooled test DB.
2. Reflect over JooqSearchDocument.class.getFields() and assert that no field name (case-insensitive)
   equals TEXT_SEARCH, TEXT_SEARCH_EN or TRGM_TEXT.
3. Assert the positive side too, so the test fails loudly if codegen stops emitting the table at all:
   the expected 22 non-generated fields are present (or at least a known subset such as ENTITY_TYPE,
   ENTITY_UUID, TITLE, BODY, KEYWORDS, DIRTY, ES_SYNCED_AT).
4. Reference the pom exclusion by line in the class javadoc so the fix is one click away from the
   failure.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §8 (the exclusion), §10 ("jOOQ codegen") ·
`loom/db/jooq/pom.xml:250` · [../loom/DOMAIN.md](../loom/DOMAIN.md)
**Test Requirements:** The new class itself. `mvn -o -pl loom/db/jooq test -Dtest=SearchDocumentCodegenTest`
— it must pass without `./setup-pool.sh`, which is the point of keeping it DB-free.

---

## Task 6: Cover the untested document sources (was SEARCH.md §8.2)

**Argumentation Summary:** `SearchDocumentSourceTest` covers the asset, transcript and tag document
families in detail — filename, `initial_origin`, transcript, OCR, Tika, caption, video-caption scenes,
LLM answer, face description, ingested `metadata` (including the array fields), and the deliberate
exclusions (camera settings, `quality`). It covers **none** of `annotation`, `person`, `collection`,
`library` or `cluster`, all of which have their own refresh functions and triggers, and it does not
assert that `detection.label` and `asset_segment_comp.title` fold into the owning asset's `keywords`.
Those five entity types are queryable via `?types=` today, so a regression in any of their refresh
functions produces silently empty results with nothing red.

**Improvement Summary:** One new test class (the existing one is already at the 15-method ceiling) that
inserts each entity and asserts its document is findable by its own text.

```
1. New class loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/search/SearchEntityDocumentTest.java —
   a NEW class rather than growth of SearchDocumentSourceTest, which is already at 15 methods; the
   test-DB pool provisions in increments of ten (max 60) and a larger class outruns it.
2. One method per entity: annotation body, person name, collection name, library name, cluster label.
   Each: insert the entity, query for a term unique to it, assert the hit has the expected entity_type
   and entity_uuid.
3. Two methods for the folding: a detection label makes its owning asset findable, and an
   asset_segment_comp title makes its owning asset findable. Keep these even after Task 2 lands — Task 2
   adds a SECOND hit, it does not remove the folding.
4. Assert relative to your own fixtures. The pooled test DB is NOT empty — it carries fixtures of its
   own, so "the result set is empty" is never a valid assertion; use the hitsAsset(result, mine) style
   the existing search tests use.
5. Update SEARCH.md §8.1 with the new class and §8.2 by removing the gap.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §4.1, §4.2, §8.1, §8.2, §10 ("the pooled test
DB is not empty") · `V2.59__add_search_triggers.sql`
**Test Requirements:** The new class, seven or more methods, all green.
`./setup-pool.sh && mvn -o -pl loom/db/jooq test -Dtest='Search*'`

---

## Task 8: Deep-link a transcript hit to its timecode

**Argumentation Summary:** A `TRANSCRIPT` hit carries `timeFromMs` and the transcript document exists
precisely so a hit can point at a moment rather than a file — but `AssetDetail` has no seek parameter, so
`SearchHitRow` renders the offset as a badge and links to the asset. The user lands on a 40-minute video
at 00:00 holding a timestamp they must scrub to by hand, which negates the reason transcripts get their
own document at all.

**Improvement Summary:** Accept a seek parameter on the asset detail route and have transcript hits link
to it.

```
1. loom-ui/src/features/assetDetail/AssetDetail.tsx — read a `t` search parameter (seconds, or ms;
   pick one and document it) via useSearchParams and seek the media element to it once metadata has
   loaded. Ignore it for non-temporal assets rather than erroring.
2. loom-ui/src/features/search/SearchHitRow.tsx — for hits whose entityType is TRANSCRIPT (and, after
   Task 2, SEGMENT and DETECTION where a timeFrom exists), build the link as
   /assets/<assetUuid>?t=<seconds> instead of the bare asset link. Keep the badge.
3. Guard the seek behind readiness: setting currentTime before loadedmetadata is a no-op in every
   browser. Use a loadedmetadata listener, not a timeout.
4. Remove the corresponding open item from SEARCH.md §12.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §4.1 (why transcripts get a second document),
§12 · [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md)
**Test Requirements:** A mocked Playwright case asserting the href of a transcript hit carries the
timecode, plus a vitest case over the link-building helper. `./node_modules/.bin/playwright test
e2e/search-mocked.spec.ts`.

---

## Task 9: Delete the orphaned `loom-ui` trees (was P0-5)

**Argumentation Summary:** `loom-ui/src/Dashboard/`, `loom-ui/src/User/` and `loom-ui/src/Content/` are
all still present and unreachable from `main.tsx` — `AppShell.tsx` is the only live route table. They
reference only each other. Both stale copies of `MainSearchBar` live in this tree, so anyone grepping for
the search box finds dead code first and the live `GlobalSearchField` second. This blocked nothing in the
end: the search UI landed alongside them rather than replacing them.

**Improvement Summary:** Verify reachability per file, then delete all three directories.

```
1. Verify BEFORE deleting, per file, not per directory: for each file under the three trees, grep the
   rest of loom-ui/src for an import of it. AppShell.tsx is the only live route table; main.tsx is the
   entry point. A single live import means that file moves instead of dying.
2. Delete the three directories.
3. Run the type check and the full vitest + Playwright suites — a dead-tree deletion that breaks a test
   means something was not dead.
4. Remove the P0-5 line from SEARCH.md §12 Phase 0.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §12 (Phase 0) ·
[LOOM_UI_TASKS.md](LOOM_UI_TASKS.md)
**Test Requirements:** `./node_modules/.bin/tsc --noEmit`, `./node_modules/.bin/vitest run`, and the
mocked Playwright suites, all green after the deletion. Use `./node_modules/.bin/…` directly — `npx`
hangs in this repo.

---

## Task 10: Add `LoomRoutingContext.searchParams()` (was P1-9b)

**Argumentation Summary:** Every other parameter family is reached through an accessor on
`LoomRoutingContext` (`pagingParams()`, and friends), but services call `SearchParameters.create(lrc)`
directly. Purely a symmetry gap — nothing is broken — but it is the kind of inconsistency that makes the
next person add a third convention.

**Improvement Summary:** One accessor delegating to `SearchParameters.create(this)`.

```
1. Add searchParams() to LoomRoutingContext (and its implementation) returning SearchParameters,
   delegating to SearchParameters.create(this), mirroring pagingParams().
2. WATCH THE MODULE BOUNDARY: SearchParameters lives in loom/services/rest while
   SearchQueryParameterKey lives in loom-shared/rest-model, both in package
   io.metaloom.loom.rest.parameter. If LoomRoutingContext cannot see loom/services/rest, this accessor
   cannot exist there — in that case close the task by recording the constraint in SEARCH.md §9 rather
   than inverting a module dependency for cosmetics.
3. Do NOT make SearchParameters extend AbstractQueryParameters. That base types mapParameter against
   QueryParameterKey and its typed default-value fallback throws ClassCastException (an Integer default
   read through a String accessor) on the first request that omits a parameter. The separation is
   deliberate.
4. Point SearchEndpointService at the accessor.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §5 (parameters), §9 (the module split)
**Test Requirements:** The existing `SearchEndpointTest` (16 cases) must stay green unchanged — this is
a refactor with no behaviour change. `mvn -o -pl loom/core test -Dtest=SearchEndpointTest`.

---

## Task 11: SPIKE — verify the Elasticsearch client API (was P2-1) — BLOCKS Tasks 12–16, 23

**Argumentation Summary:** `loom/services/elasticsearch` is `pom.xml` + `README.md` with **no `src/`**.
Its pom depends on the internal `io.metaloom.elasticsearch:elasticsearch-client` `1.2.0-SNAPSHOT`, whose
API has never been verified against what Phase 2 needs. Every subsequent Elasticsearch task assumes bulk
indexing, `search_after`, aliases, index templates and — for Phase 3 — `knn`. If that client cannot do
them, the mapping, the indexer and the provider are all built on a wrong assumption and get rewritten.

**Improvement Summary:** Prove or disprove the five capabilities against a real Elasticsearch container,
then pick the client. Write no production Elasticsearch code until this closes.

```
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
5. Write the outcome into spec/features/search/SEARCH.md §3 (the comparison table) and open the
   follow-up tasks with the chosen client named. Do NOT leave the answer only in a commit message.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §0, §3 · `loom/services/elasticsearch/README.md`
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
       though nothing populates the ACL request fields yet (Task 3) — adding them later is a reindex
     - a declared but unpopulated dense_vector so Task 23 needs no reindex
3. LoomSearchMapping + ensureSchema() in io.metaloom.loom.elasticsearch — idempotent, safe on every
   boot, and it must NOT fail server boot if Elasticsearch is unreachable (SearchModule's contract).
4. Add the LOOM_SEARCH_ES_* options to SearchOptions with validation, and regenerate
   loom/doc/src/main/generated/loom-config.yaml by running
   `mvn -o exec:java -Dexec.mainClass=io.metaloom.loom.doc.ExampleGenerator` FROM INSIDE loom/doc. That
   run also rewrites the OpenAPI files, which churn on random example UUIDs — revert that noise rather
   than committing it.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §3, §4, §5, §7 · Task 11 (the client decision)
**Test Requirements:** A Testcontainers test asserting `ensureSchema()` is idempotent (twice in a row is
clean), that the mapping applies, and that an unreachable Elasticsearch leaves the server booting.

---

## Task 13: `ElasticsearchSearchIndexer` and the outbox drain (was P2-3, P2-4)

**Argumentation Summary:** `search_document.dirty` / `synced_at` / `es_synced_at` and the
`search_document_deleted` tombstone table are already written and maintained by triggers, and **nothing
in Java reads them** — no code outside the generated jOOQ classes touches them. They are a live, correct,
unconsumed feed. That is the load-bearing property of the whole design: Phase 2 starts from a populated
outbox rather than a backfill project.

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
advertise `DEEP_PAGING`, which Postgres never does, and the `nextCursor` field is already in the response
envelope and always null under Postgres precisely so this swap needs no API change.

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
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §2, §2.1, §5 · Task 12
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
[WEBSITE_DOC_TASKS.md](WEBSITE_DOC_TASKS.md)
**Test Requirements:** The parity test itself, green against both providers; a `helm template` render of
the new block; and a compose-profile smoke run.

---

## Task 16: `POST /api/v1/search/results` (was P2-10)

**Argumentation Summary:** Every search parameter travels in the query string today. A long query, or a
filter set with many tag and library UUIDs, will hit URL-length limits in proxies and browsers long
before it hits any application limit — and the failure is a 414 from infrastructure, not an error the
application can explain.

**Improvement Summary:** A body-encoded twin of `/search/results` sharing one service method.

```
1. Add a POST route to SearchEndpoint whose body carries the same fields SearchQueryParameterKey
   defines, and bind it to the SAME SearchEndpointService method the GET route uses. Two entry points,
   one implementation — otherwise the permission gate and the narrowing drift.
2. Reuse SearchParameters' validation semantics rather than re-implementing them: blank q is 400, offset
   past LOOM_SEARCH_MAX_OFFSET is 400 naming the cap, unknown mode is 400 SEARCH_UNSUPPORTED.
3. Keep the response model identical (SearchResultResponse) so no client needs a second parser.
4. Add the route to the OpenAPI generation and regenerate from inside loom/doc; add the method to
   SearchMethods, LoomHttpClientImpl and the Python client. The Python parity test guards the client
   method count and WILL fail if only one side is updated.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §5 · [../loom/RESTAPI.md](../loom/RESTAPI.md) ·
`clients/python/tests/test_parity.py`
**Test Requirements:** `SearchEndpointTest` cases mirroring the GET permission and validation cases for
POST, plus the Python parity test staying green.

---

## Task 17: `/api/v1/search/facets` and the facet UI (was P2-13)

**Argumentation Summary:** `PostgresSearchProvider` already computes facets for `mime_type`,
`entity_type` and `lang`, exposed as `?facets=` on `/results`. There is no way to ask for the facet
counts *without* running and paying for a full result page, which is what a filter sidebar needs on
first render.

**Improvement Summary:** A dedicated facets route plus the UI that consumes it.

```
1. Add GET /api/v1/search/facets to SearchEndpoint, same gate and same narrowing, returning
   SearchFacetResponse buckets without the hit list.
2. Facets are computed against the FILTERED query — selecting an entity_type facet collapses that facet.
   The UI must therefore give a visible way to undo a selection, or the user reaches a state they cannot
   leave (SEARCH.md §5.1).
3. loom-ui: render the buckets as a filter surface in SearchView alongside the existing facet chips, and
   keep selection in the URL so a faceted search stays shareable.
4. Extend api/search.ts and both API clients.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §5, §5.1
**Test Requirements:** `SearchEndpointTest` cases for the route and its permission gate; a mocked
Playwright case asserting a facet selection narrows the results and can be undone.

---

## Task 18: `?q=` substring narrowing on the CRUD list routes (was P2-11)

**Argumentation Summary:** This is a **different feature from `/search/*`** and must not be confused with
it: no ranking, no cross-entity documents, just substring narrowing of one list. It is needed because the
external lhs-filter `Operation` enum has only `EQUALS/NOT_EQUALS/AFTER/BEFORE/RANGE/GREATER/LESSER` — no
`LIKE` or `CONTAINS` — so `?filter=` can never carry a query term. Today a UI list view that wants to
filter must load the page and filter in the browser.

**Improvement Summary:** An optional `q` parameter on the list routes, implemented as `ILIKE` or trigram
in the DAO `WHERE`, leaving keyset paging untouched.

```
1. Add `q` narrowing to the DAO loadPage implementations, as an ILIKE or pg_trgm predicate on the
   entity's natural name column.
2. KEYSET PAGING MUST BE UNTOUCHED. This is narrowing, not ranking — there is no relevance ordering, so
   the existing seek() paging keeps working exactly as it does now. Ranking is what forced /search/* onto
   capped offsets; do not import that trade-off here.
3. Do NOT add `q` to QueryParameterKey. addListRoute iterates its values and would document `q` on the
   roughly forty routes that ignore it. Add it per-route, or through a dedicated key type.
4. Add DAO tests per entity, asserting the narrowing and that paging still walks the narrowed set
   correctly.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §7.2, §10 ("filter operators",
"`QueryParameterKey`") · [../loom/RESTAPI.md](../loom/RESTAPI.md)
**Test Requirements:** DAO tests per narrowed entity plus endpoint tests for at least two routes.
`./setup-pool.sh` is not needed — no schema change.

---

## Task 19: Migrate the qualifying loom-ui list views to `?q=` (was P2-12)

**Argumentation Summary:** Several list views filter client-side over a loaded page, which is wrong for
any list that can outgrow one page. Migrating every view would be churn for its own sake, so a criterion
is needed rather than a sweep.

**Improvement Summary:** Migrate a view when its list can plausibly exceed 500 rows, and leave the rest
alone.

```
Criterion — migrate a view IF AND ONLY IF its list can plausibly exceed 500 rows.

Migrate: TagsView (keep the client-side tree grouping — that is a render concern, not a filter),
CollectionsView, AssetPoolsView, the six AdminArea boxes, and the detection views.

Do NOT migrate: CortexView, PipelineEditor. Their lists are bounded by deployment size and the migration
would only add a round trip.

Per view: send ?q= to the list endpoint, keep the term in the URL via useSearchParams, and debounce the
input. Do not reach for /search/* here — these are list narrowings, not ranked searches.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §7.2 · Task 18 (blocks this) ·
[LOOM_UI_TASKS.md](LOOM_UI_TASKS.md)
**Test Requirements:** One mocked Playwright case per migrated view asserting the request carries `q` and
that the rendered set narrows. Run with `./node_modules/.bin/playwright test`.

---

## Task 20: `cortex/nodes/embedding` — CLIP/SigLIP whole-image vectors (was P3-12)

**Argumentation Summary:** Text-to-text semantic and hybrid search are shipped and work. Text-to-image
search does not exist, and **this node is the only thing missing**: `TextEmbedder`, `RankFusion`, the
`VectorIndex` SPI, the fused query path in `PostgresSearchProvider` and the loom-ui mode toggle are all
in place and would consume image vectors unchanged. `cortex/nodes/` has 35 node modules and no `embedding`
among them.

**Improvement Summary:** A Cortex node producing whole-image CLIP/SigLIP embeddings into the existing
`embedding` table and vector index.

```
1. New module cortex/nodes/embedding following the structure of an existing node module. Use
   cortex/nodes/captioning or the fingerprint node as the shape reference.
2. Persist through the established node-result path: a typed component plus an asset_node_result ledger
   entry. WhisperNode is the reference implementation for Loom write-back.
3. Write embeddings as ordinary `embedding` rows through the VectorIndex SPI. embedding.type is FREE
   TEXT by design — do NOT reintroduce an EmbeddingType enum. Key the vector space by
   (type, model, dimensions) so the model can be changed without a schema change.
4. The query side needs the SAME model for text: a CLIP text encoder, not the llama.cpp text embedder
   used for the lexical corpus. Text and image vectors must share one space or the similarity is
   meaningless. Decide and document where the text side of the CLIP pair is served
   (sidecars/, or the existing embedding host with a second model).
5. Install the node module BEFORE regenerating node-descriptors.json, or the harvest reads a stale jar.
   Also install cortex/processor before the CLI build, or Dagger emits "<error>" in place of the new
   node module.
6. The node emits ONE vector per image; if it ever emits per-region vectors, give it a ONE summary port
   as well, or its debug card understates the result.
```

**References:** [SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) §4 (the design), §0.4 (why the
text path took the route it did) · [../features/nodes/NODES.md](../features/nodes/NODES.md) ·
[NODE_TASKS.md](NODE_TASKS.md)
**Test Requirements:** A node unit test with a deterministic fake embedder, plus a per-node E2E in the
`integration-test` module following the existing per-node IT pattern. Then an end-to-end assertion that a
text query retrieves an image whose caption never contained the term — that is the only test that proves
text-to-image actually works.

---

## Task 21: `vector_config` profiles (was P3-13)

**Argumentation Summary:** Fusion weights and the RRF constant are environment variables, so A/B testing
two rankings is a redeploy. `SearchRequest` already carries a `profile` concept and
`SearchQueryParameterKey` already defines `profile` — and it reaches nothing.

**Improvement Summary:** A `vector_config` table, a DAO, a read route and a seeded `default` profile, so
`?profile=` selects a named ranking configuration at query time.

```
1. Migration adding vector_config (uuid, name unique, model, dimensions, fusion weights, rrf k,
   created/edited audit columns), plus a seeded `default` row carrying today's env-var values so
   behaviour is unchanged on upgrade.
   Check the highest migration version numerically before claiming one.
2. VectorConfigDao + jOOQ regeneration (loom/db/jooq/generate.sh), then ./setup-pool.sh. A new DAO
   changes the DaoCollection constructor — clean-rebuild loom/core BEFORE setup-pool.sh or it fails with
   NoSuchMethodError.
3. GET /api/v1/vector-configs (plural, per the coding guidelines) with its own permission, plus the
   endpoint and permission tests the guidelines require.
4. Make ?profile= resolve a config and drive RankFusion, falling back to the env-var values when the
   parameter is absent so nothing changes for existing callers.
```

**References:** [SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) §9 (the env vars this
replaces) · [../guidelines/CODING.md](../guidelines/CODING.md) · [DATABASE_TASKS.md](DATABASE_TASKS.md)
**Test Requirements:** DAO tests including delete-cascade, endpoint tests including permission cases, and
a query test proving two profiles rank the same corpus differently. `./setup-pool.sh` after the
migration.

---

## Task 22: Face-similarity query route and `SearchRequest.clusterUuid` (was P3-14)

**Argumentation Summary:** `SearchRequest.clusterUuid` exists (`SearchRequest.java:41`) and **nothing
sets it**. The SPI was deliberately shaped to carry both a text query and a "find things like this
cluster" query, and only the first half was built. Face embeddings are persisted and indexed, so the data
side is ready.

**Improvement Summary:** A query route that takes a cluster (or a face) and returns visually similar
assets through the existing vector index.

```
1. Add the route under the search namespace, plural leaf, gated on the appropriate READ_* permission for
   faces/clusters as well as READ_SEARCH.
2. Resolve clusterUuid to its centroid or representative embedding and query the VectorIndex for the
   matching space.
3. Return the standard SearchResultResponse so no client needs a second model.
4. NEVER call VectorIndex.rebuild() from this path or anywhere near it — one Lucene directory holds every
   space, and rebuild wipes all of them. Per-space work uses drop(space).
5. Gate face results on pose where the pipeline provides it: embeddings go orthogonal at profile angles
   while the detection score stays high, so a score-only filter returns confident nonsense.
```

**References:** [SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) ·
[../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) ·
[WORKFLOW_FACE_TASKS.md](WORKFLOW_FACE_TASKS.md) · [../concept/CLUSTERING.md](../concept/CLUSTERING.md)
**Test Requirements:** An endpoint test with seeded face embeddings asserting the route returns the
cluster's own members ahead of unrelated assets, plus its permission cases.

---

## Task 23: Elasticsearch `dense_vector` and native `knn` / `rrf` (was P3-15)

**Argumentation Summary:** Java-side RRF fusion over a separate vector index is the right answer while
Postgres is the only backend. Once Elasticsearch exists, it can do k-NN and reciprocal-rank fusion
natively in one query, which removes a round trip and the Java fusion step from the hot path.

**Improvement Summary:** Populate the `dense_vector` declared in Task 12's mapping and use native `knn`
plus `rrf` in `ElasticsearchSearchProvider`.

```
1. Requires Tasks 11-14 AND the vectors from Task 20 (or the existing text embeddings).
2. Populate the dense_vector field in the Task 13 drain. Because Task 12 declared the field in the
   original mapping, this needs NO reindex — that was the point of declaring it unpopulated.
3. Implement SEMANTIC and HYBRID in ElasticsearchSearchProvider using native knn and rrf.
4. Keep the results commensurable with the Postgres path: the parity test from Task 15 must cover
   semantic mode too, or the two backends quietly rank differently in the one mode users notice most.
```

**References:** [SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) §5.2 ·
[SEARCH.md](../features/search/SEARCH.md) §10 ("two search paths, one provider") · Tasks 11-14, 20
**Test Requirements:** Extend the Task 15 parity test with semantic and hybrid queries across both
backends.

---

## Task 24: Verify semantic retrieval quality against a real model

**Argumentation Summary:** Every semantic test uses a deterministic fake embedder. That proves the
plumbing — fusion arithmetic, capability gating, the drain — and proves nothing about whether the
retrieval is any good. Nobody has measured recall against a real model on a real corpus, and the feature
is off by default partly for that reason. Related: a demo container shows no mode toggle, because no demo
vectors exist, so nobody encounters the feature by accident either.

**Improvement Summary:** A measured baseline against `sidecars/llamacpp-embeddings`, plus demo vectors so
the feature is visible in a demo deployment.

```
1. Stand up sidecars/llamacpp-embeddings (see its README) and embed the demo corpus.
2. Build a small labelled query set against the demo corpus — 20-30 queries with known-relevant assets,
   including several a LEXICAL search demonstrably misses (paraphrase, synonym, description-not-keyword).
   That last group is the entire justification for the feature.
3. Measure recall@10 and MRR for LEXICAL, SEMANTIC and HYBRID. Record the numbers, the model and the
   date in SEMANTIC_SEARCH.md. A number with no model and no date is not a baseline.
4. Use the result to decide whether LOOM_SEARCH_SEMANTIC_ENABLED should stay off by default, and say so
   explicitly either way.
5. Seed demo vectors in DemoDatabaseInitializer (or generate them at demo boot) so a demo container
   shows the mode toggle. The toggle is capability-gated, so it appears on its own once an embedding
   host and a vector index both answer.
```

**References:** [SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) §9, §10, §12 ·
`sidecars/llamacpp-embeddings/README.md` · `loom/core/.../boot/DemoDatabaseInitializer.java`
**Test Requirements:** The measurement harness need not run in CI — it needs a real model — but it must
be checked in and runnable, and its recorded numbers must name the model and the date. The demo-vector
seeding does belong in the demo boot path and must not slow or break a demo start when no embedding host
is present.

---

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (created from `spec/concept/SEARCH_PLAN.md`, which was retired. Shipped work
moved into SEARCH.md §0/§12; the remaining work re-verified against the tree and rewritten as 24 tasks.
Corrections found during the audit: the `integration-test` build failure the plan warned about
(`DedupNodeOptions.setDupFolder`) no longer exists; the migration high-water mark is `V2.99`, not
`V2.84`; the P1-24 spec sync into RBAC / PERMISSIONS / RESTAPI is done, leaving only MCP.md, which is
folded into Task 1; the search test count is 55 DB-side plus 16 endpoint plus 13 fusion, not 49)_
