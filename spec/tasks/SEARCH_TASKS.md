# Search — Task List

> Open work items for lexical and semantic search, derived from a code audit on
> 2026-08-11 against `loom-shared/api`, `loom/db/jooq`, `loom/services/{rest,mcp,graphql,elasticsearch}`,
> `loom/core`, `cortex/nodes` and `loom-ui/src`. Format follows [TASKS.template.md](TASKS.template.md).
>
> 🔴 **Elasticsearch (Phase 2) is no longer tracked here.** Tasks 11-15 and 23 moved to
> [SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) on 2026-08-16, keeping their numbers, together with
> the assessment that concluded Postgres covers today's cases and Phase 2 should **not** start. Read its
> §0 before proposing any Elasticsearch work. Task numbers 11-15 and 23 are **not reused** here.
>
> **Context:** [../features/search/SEARCH.md](../features/search/SEARCH.md) (lexical technical spec, and
> the authority on what is built) · [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md)
> (vectors, embedding, hybrid ranking) · [../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md)
> (the `/search-indices` admin surface) · [SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) (Phase 2,
> deferred) · [../loom/SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) (the
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
> browser and the library panel), the GraphQL `Query.search` field with the same `READ_SEARCH` gate and
> per-type narrowing (Task 7 — `SearchWiring`, the SDL types and enums, and the shared
> `SearchTypePermissions` map that keeps REST and GraphQL from drifting; the ~20 existing list fields
> deliberately did **not** gain filter arguments), the demo corpus the
> backend e2e asserts against, the customer-facing website docs, the reindex admin surface (which
> superseded `/search/reindexes`), and the entire text half of semantic + hybrid search
> (`TextEmbedder`, `OpenAiTextEmbedder`, `RankFusion`, `SearchEmbeddingService`, `SearchEmbeddingDrainer`,
> dynamic capabilities, `sidecars/llamacpp-embeddings`).
>
> **Ordering / blocking:**
> * **Task 2 is the remaining correctness defect** — two API-accepted entity types that can never
>   produce a hit. Do it first. (Task 1, the MCP tools that ignored their query argument, is ✅ done.)
> * **Task 25 is the remaining recall gap** — a non-English corpus is only matched unstemmed. Second.
> * Task 18 gates Task 19.
> * **Task 20 (the CLIP image node) is the single thing between here and text-to-image search** — the
>   ranker, the fusion and the UI mode toggle would all consume its output unchanged.
> * Tasks 3–10, 16, 17, 21, 22, 24 and 25 are independent of everything else. ⚠️ Earlier revisions
>   claimed the Elasticsearch spike gated Tasks 16 and 17; it does not — neither has any Elasticsearch
>   content. See [SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) §2.

## Progress Assessment

- [ ] **Defects:** ~~Task 1 (MCP `search_assets` ignores `query`)~~ ✅ done, Task 2 (`DETECTION` / `SEGMENT` produce no documents), Task 25 (English-only stemming)
- [ ] **Dead or half-wired code:** Task 3 (row-level ACL clause), Task 9 (orphaned loom-ui trees)
- [x] **Consumers not yet on the SPI:** ~~Task 4 (`LibraryView`)~~ ✅ done, ~~Task 7 (GraphQL)~~ ✅ done — every consumer is on the SPI
- [ ] **Test and regression guards:** Task 5 (codegen guard), Task 6 (document-source coverage), Task 24 (retrieval quality)
- [ ] **Ergonomics:** Task 8 (transcript timecode deep link), Task 10 (`searchParams()`), Task 16 (`POST /search/results`), Task 17 (`/search/facets`)
- [x] **Phase 2 — Elasticsearch:** assessed and **deferred**; Tasks 11–15 and 23 now live in [SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) with the reasoning and the revisit trigger
- [ ] **List-route narrowing (a different feature from `/search/*`):** Tasks 18, 19
- [ ] **Phase 3 — the image half:** Tasks 20–22 (Task 23 moved with Phase 2)

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

## Tasks 11-15: Elasticsearch Phase 2 — MOVED

🔴 **Moved to [SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) on 2026-08-16**, numbers unchanged:
Task 11 (client spike), Task 12 (mapping + `ensureSchema()`), Task 13 (indexer + outbox drain),
Task 14 (provider + health check), Task 15 (parity tests + compose/Helm). Its §0 records why the
whole phase is deferred and §3 the trigger that would reverse that. **These numbers are not reused
here.**

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

## Task 23: Elasticsearch `dense_vector` and native `knn` / `rrf` — MOVED

🔴 **Moved to [SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) on 2026-08-16**, number unchanged.
It optimises a path that already works on Postgres (`RankFusion` over the `VectorIndex` SPI), so it
adds no capability and is deferred with the rest of Phase 2. **This number is not reused here.**

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

## Task 25: Stem non-English documents — `LOOM_SEARCH_TS_CONFIG` only half-closes the loop

**Argumentation Summary:** `search_document.text_search_en` is a **generated, stored** column whose
config is hardcoded to `english` (`V2.58__add_search_document.sql:58` — a data-dependent
`to_tsvector(lang::regconfig, …)` is not `IMMUTABLE` and so cannot be a generated column). The *query*
side, however, **is** configurable: `PostgresSearchProvider.SCORE_EXPRESSION` and `appendMatch()` both
bind `options.getTsConfig()` as `?::regconfig`. The two halves therefore disagree the moment
`LOOM_SEARCH_TS_CONFIG` is set to anything but `english` — a German query is stemmed with German rules
against an English-stemmed index, which retrieves *less* than leaving the option alone. Today a German
transcript is reachable only through the unstemmed `simple` vector plus trigram similarity, so "Aufnahmen"
never matches a search for "Aufnahme". Whisper transcripts are a first-class corpus source and
`search_document.lang` is already populated per document, so the data needed to fix this is present and
unused. This is the largest remaining **recall** defect in lexical search, and the reason it is not an
argument for Elasticsearch is that the fix lives entirely inside the current architecture
([SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) §0.2).

**Improvement Summary:** Replace the generated `text_search_en` column with a trigger-maintained
`tsvector` computed from a `lang → regconfig` map, so each document is stemmed in its own language.

```
1. Decide the scope first and write it down: per-document stemming (use search_document.lang) or one
   configured language per deployment. Per-document is the better answer — the corpus IS mixed, because
   transcripts carry their own lang — and it costs the same migration.
2. New Flyway migration. Check the highest existing version numerically FIRST
   (`ls … | sort -t. -k2 -n | tail`) — a lexical sort puts V2.9 after V2.99, and another branch may be
   taking the next number.
     - Add search_lang_regconfig(p_lang varchar) RETURNS regconfig: an IMMUTABLE whitelist mapping
       'de'/'deu'/'ger' -> german, 'en' -> english, … and DEFAULT to 'simple' for anything unknown.
       Whitelist, never a cast: to_regconfig on arbitrary input is how you get a runtime error inside a
       trigger, and a NULL regconfig silently nulls the whole tsvector.
     - Drop the GENERATED clause from text_search_en and make it a plain tsvector column written by the
       refresh functions, using the same weights (title A, subtitle B, body C, keywords D) — copy them
       from V2.58 rather than re-deriving, or ranking shifts for every existing document.
     - Set it inside search_document_refresh_*(), NOT in a second trigger. Per-entity refresh recomputing
       the whole document family is what makes rebuild-equals-incremental hold by construction
       (SEARCH.md §4.2); a separate tsvector trigger breaks that identity.
     - End the migration with SELECT search_document_rebuild(); to restem the existing corpus.
3. Leave text_search ('simple') exactly as it is. It is what makes filenames, ids and codes survive, and
   the greatest() blend depends on both halves.
4. jOOQ: text_search_en stays excluded by the .*\.text_search.*|.*\.trgm_text pattern in
   loom/db/jooq/pom.xml — it is now writable-by-trigger rather than generated, but jOOQ still has no
   tsvector binding and it must never reach an INSERT. Do NOT narrow the pattern. If Task 5 has landed,
   its codegen guard already asserts this.
5. Provider: keep binding options.getTsConfig() only where a per-document config is NOT available (the
   query-side tsquery still needs one config). Where the map applies, the query must use the SAME
   function: websearch_to_tsquery(search_lang_regconfig(?), ?) or an explicit per-language branch.
   Query config and index config disagreeing is the entire bug being fixed here — do not reintroduce it
   on the other side.
6. Redocument LOOM_SEARCH_TS_CONFIG in SEARCH.md §7 and SearchOptions' @EnvironmentVariable description:
   after this task it is the fallback for documents with no usable lang, not "the stemmed query side".
7. ./setup-pool.sh after the migration. loom/db/jooq/generate.sh is NOT needed — no new table and no new
   column reaches the generated code.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) §4 (the generated columns and the immutability
constraint), §7 (`LOOM_SEARCH_TS_CONFIG`), §4.2 (per-entity refresh) ·
[SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) §0.2 · `V2.58__add_search_document.sql:58` ·
`PostgresSearchProvider.SCORE_EXPRESSION` / `appendMatch()`
**Test Requirements:** New methods in a `Search*` class in `loom/db/jooq/src/test/…/search/` (keep every
class at 15 methods or fewer — the test-DB pool provisions in tens): a German document is found by a
stemmed German query; an English document keeps being found by its existing stemmed query (regression);
a document with an unknown or empty `lang` still matches through the `simple` vector; and the
rebuild-equals-incremental case in `SearchDocumentLifecycleTest` stays green **unchanged** — that is the
assertion that proves the tsvector is written on the shared refresh path.
`./setup-pool.sh && mvn -o -pl loom/db/jooq test -Dtest='Search*'`

---

_Git HEAD revision: `5354b65d`_
_Last updated: 2026-08-16 (Task 7 ✅ done — the GraphQL `search` field shipped; its text moved into the
"removed as implemented" list and every SPI consumer is now on the provider. Two departures from the
task as written, both because the code disagreed: `SearchGraphQLTest` extends `AbstractGraphQLTest` in
`endpoint/graphql/` (the domain-test base that enforces `GraphQLSecurityTestcases`) rather than
`AbstractGraphQLEndpointTest`, which is the endpoint-mechanics base; and the `SearchEntityType` enum has
eleven members, not ten — `REMIX` was missing from the task's list. Earlier the same day: Elasticsearch Phase 2 — Tasks 11-15 and 23 — moved to
[SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) together with the assessment that deferred it; their
numbers are retired here rather than reused. Added Task 25, the English-only stemming gap that assessment
surfaced. Corrected the ordering notes: the Elasticsearch spike never gated Task 16 or Task 17, which the
header and the Progress Assessment previously contradicted each other about.)_
Earlier: 2026-08-11 (created from `spec/concept/SEARCH_PLAN.md`, which was retired. Shipped work
moved into SEARCH.md §0/§12; the remaining work re-verified against the tree and rewritten as 24 tasks.
Corrections found during the audit: the `integration-test` build failure the plan warned about
(`DedupNodeOptions.setDupFolder`) no longer exists; the migration high-water mark is `V2.99`, not
`V2.84`; the P1-24 spec sync into RBAC / PERMISSIONS / RESTAPI is done, leaving only MCP.md, which is
folded into Task 1; the search test count is 55 DB-side plus 16 endpoint plus 13 fusion, not 49))_
