# Search — Build Plan

> Status: **proposed** — no task below is started. Design contract and rationale live in
> [SEARCH.md](SEARCH.md); read it first. Phase 3 (semantic/hybrid) is detailed in
> [SEMANTIC_SEARCH.md](SEMANTIC_SEARCH.md).
>
> **Audience: AI coding agents.** This file is the execution order. Each task is small enough for one
> change, and the dependency column is real — running P1-7 before P1-6 produces code that does not
> compile.

## Strategy in one paragraph

Ship **Postgres full-text search** behind a provider SPI, then swap in **Elasticsearch/OpenSearch**
behind the same SPI without touching the endpoint, the models or the UI. The hinge is the
`search_document` table ([SEARCH.md](SEARCH.md) §5): it is the Postgres index, the pre-assembled
Elasticsearch document, *and* the outbox that feeds it. Lucene is rejected and its stub module is
deleted ([SEARCH.md](SEARCH.md) §2).

---

## 🔴 Build-order rules — read before touching anything

These are the things that fail confusingly rather than loudly.

| # | Rule |
|---|---|
| 1 | **`./setup-pool.sh` after every new Flyway migration.** It runs `io.metaloom.loom.test.PoolSetupRunner` in `loom/fixture` and rebuilds the testdatabase-provider template DBs. Skip it and every DAO and endpoint test fails against the *old* schema. |
| 2 | **Widen `loom/db/jooq/pom.xml:247` to `<excludes>.*\.text_search.*\|.*\.trgm_text</excludes>` BEFORE running `loom/db/jooq/generate.sh`.** The current pattern `.*\.text_search` catches `search_document.text_search` but not `text_search_en` or `trgm_text`, so a regen would emit `Object`-typed generated columns that can reach an `INSERT`. |
| 3 | **`generate.sh` re-runs every migration from scratch in a `postgres:latest` Testcontainer.** Any migration that is not runnable on a stock image breaks codegen for everyone. `pg_trgm` ships with the official image; `pgvector` does **not** — see [SEMANTIC_SEARCH.md](SEMANTIC_SEARCH.md). |
| 4 | **`ALTER TYPE loom_permission ADD VALUE` must be ALONE in its migration.** Flyway wraps each migration in one transaction, and a newly added enum value cannot be used in the same transaction that adds it. `V2.52`, `V2.37` and `V2.27` all follow this rule. A migration adding `READ_SEARCH` *and* inserting a `role_permission` row that uses it **will fail**. |
| 5 | **Clean-rebuild `loom/core` after endpoint constructor changes**, before `setup-pool.sh` or tests, or you get a confusing `NoSuchMethodError` from Dagger-generated factories. |
| 6 | **Highest existing migration is `V2.56__pipeline_run_paused_status.sql`.** Verify this before claiming `V2.57`; another branch may have taken it. |

---

## Phase 0 — Prerequisites (no search code)

Each of these is an independent, mergeable change that fixes something already broken. Doing them
first keeps the search PRs reviewable.

| ID | Task | Depends on |
|---|---|---|
| **P0-1** | `Page` gains a real `totalCount`: add `Page(long perPage, long totalCount, List<T> list)`, deprecate the 2-arg constructor (default `-1` = "not computed"), add `count(*) OVER ()` as an extra select field in `AbstractJooqDao.loadPage` and read it off the first record — one query, no second round trip. Then fix `ModelBuilder` line 29, which today does `metainfo.setTotalCount(page.size())` where `Page.size()` returns the *page* size ([SEARCH.md](SEARCH.md) §1.5). Correct `PagingInfo.totalCount`'s `@JsonPropertyDescription` too — it currently reads "Number of elements in the page". | — |
| **P0-2** | 🔴 Regression-sweep all ~20 list endpoints for the corrected `totalCount`. This changes a response field on every list route in the API — treat it as its own review, not a footnote to P0-1. | P0-1 |
| **P0-3** | `LoomRoutingContext.permissions(): ResourcePermissionSet` — request-cached, **non-throwing**. `checkPerm` is throw-only and `requirePerm` returns a `Future`; per-type narrowing ([SEARCH.md](SEARCH.md) §8.2) needs a boolean. The request-scoped Dagger subtree in `AbstractEndpoint.addRoute` makes the caching natural. | — |
| **P0-4** | New `LoomRestErrorCode.SEARCH_UNAVAILABLE` (inspect the enum's existing shape before adding). | — |
| **P0-5** | Delete the orphaned `loom-ui/src/Dashboard/`, `src/User/`, `src/Content/` trees. **Verify reachability from `main.tsx` for each file first** — `AppShell.tsx` is the only live route table, and the two `MainSearchBar` copies live in this dead tree ([SEARCH.md](SEARCH.md) §1.4). | — |

---

## Phase 1 — Postgres lexical search

### 1a. Contract and configuration

| ID | Task | Depends on |
|---|---|---|
| **P1-1** | `io.metaloom.loom.api.search` in `loom-shared/api`: `SearchProvider`, `SearchIndexer`, `SearchCapability`, `SearchRequest`, `SearchResult`, `SearchHit`, `SearchSuggestion`, `SearchDocument`, `SearchEntityType`, `SearchMode`, `SearchSortMode`, `FacetBucket`, `SearchProviderInfo`, `IndexStatus`. Full shapes in [SEARCH.md](SEARCH.md) §4. | — |
| **P1-2** | `io.metaloom.loom.api.options.SearchOptions` following `MemoryOptions` exactly; register in `LoomOptions` (field, getter, setter, `overrideWithEnv()`, `errors.nested("search", search)`); extend `LoomOptionsValidationTest`. Variables in [SEARCH.md](SEARCH.md) §9. | — |

### 1b. Database

| ID | Task | Depends on |
|---|---|---|
| **P1-3** | `V2.57__add_search_permission.sql` — `ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_SEARCH';` **and nothing else** (rule 4). Add `READ_SEARCH` to `io.metaloom.loom.db.model.perm.Permission` with the trailing `// doc: … ui: … test: …` comment the enum uses, and grant it to the admin role in the bootstrap initializer. Any seed `role_permission` rows go in a *later* migration. | — |
| **P1-4** | `V2.58__add_search_document.sql` — `CREATE EXTENSION IF NOT EXISTS "pg_trgm"`; the `search_document` table and all 12 indexes; `search_extract_json_text(varchar, jsonb)`; `search_jsonb_all_text(jsonb)`; `search_document_rebuild()`. DDL in [SEARCH.md](SEARCH.md) §5.2, extraction table in §6. Include the comment recording that `asset_doc_comp` is deliberately not a source (§6.1).<br>⚠️ `pg_trgm` is not a *trusted* extension, so it needs superuser or `rds_superuser`. Every environment here qualifies (`V1__db_setup.sql:6` already does an unconditional `CREATE EXTENSION "uuid-ossp"`), but document the managed-Postgres failure mode and the "ask your DBA to pre-create it" remedy. | P1-3 |
| **P1-5** | `V2.59__add_search_triggers.sql` — per-source trigger functions and `CREATE TRIGGER`s for `asset`, `asset_location`, `asset_json_comp`, `asset_transcript_comp`, `asset_segment_comp`, `detection`, `tag`/`tag_asset`, `annotation`, `person`, `collection`/`collection_asset`, `library`/`library_asset`, `project_library`; plus the `search_document_deleted` tombstone table and its `BEFORE DELETE` trigger (Phase 2 needs it, and adding it now avoids a second migration touching the same objects). Ends with `SELECT search_document_rebuild();` as the backfill.<br>🔴 Each trigger writes only its own source's contribution. 🔴 Truncate `body` to 512 KB and set `body_truncated` ([SEARCH.md](SEARCH.md) §6). | P1-4 |
| **P1-6** | Run `./setup-pool.sh`; widen the jOOQ `<excludes>` (rule 2); run `loom/db/jooq/generate.sh`; add `SearchDocumentCodegenTest` asserting `JooqSearchDocument` has **no** `TEXT_SEARCH`/`TEXT_SEARCH_EN`/`TRGM_TEXT` field. | P1-5 |

### 1c. Provider

| ID | Task | Depends on |
|---|---|---|
| **P1-7** | `io.metaloom.loom.db.jooq.search.PostgresSearchProvider` (it needs a `DSLContext`, and `AssetComponentDaoImpl` already establishes the address-by-name technique — no new dependency edge), plus `NoopSearchProvider` and a no-op `SearchIndexer`. 🔴 `websearch_to_tsquery` only; `ts_rank_cd(…, 32)` + trigram blend; highlighting as a *separate* query over the returned page only. Code sketch in [SEARCH.md](SEARCH.md) §10.1. | P1-1, P1-6 |
| **P1-8** | `PostgresSearchProviderTest` — the full list in [SEARCH.md](SEARCH.md) §10.2, including the **delete-cascade** test and the **rebuild-equals-incremental** test. One test method per source; no table-driven consolidation. | P1-7 |
| **P1-12** | `loom/core/.../dagger/SearchModule.java` binding `SearchProvider`/`SearchIndexer` from `SearchOptions`, wrapped so a provider that fails construction logs and falls back to `NoopSearchProvider`. 🔴 **Search must never fail server boot.** Register in `LoomCoreComponent`. | P1-7 |

### 1d. REST

| ID | Task | Depends on |
|---|---|---|
| **P1-9** | `SearchQueryParameterKey` (mirroring `QueryParameterKey`'s shape, **separate from it** — see [SEARCH.md](SEARCH.md) §7.3), `SearchParameters extends AbstractQueryParameters`, `LoomRoutingContext.searchParams()`, and an `addSearchRoute(...)` helper beside `addListRoute`. | P1-1 |
| **P1-10** | `io.metaloom.loom.rest.model.search.*` response models + `SearchExamples` + the new `ModelExamples` methods that `addRoute`'s example machinery requires. | P1-1 |
| **P1-11** | `SearchEndpoint` + `SearchEndpointService`; routes `/search/{results,assets,suggestions,status}`; register in `EndpointModule`. Global `READ_SEARCH` gate plus per-type narrowing; unavailable types dropped into `_metainfo.warnings`; capped offset. | P1-7, P1-9, P1-10, P0-3 |
| **P1-13** | ⚠️ `SearchMethods` client interface + `LoomHttpClientImpl` implementation + `ClientMethods` registration. **This is a hard dependency of the endpoint tests**, which drive everything through `LoomHttpClient` — not an optional convenience. | P1-10 |
| **P1-14** | `SearchEndpointTest` extending `AbstractEndpointTest` (not `AbstractCRUDEndpointTest`), including `testSearchNarrowsByTypePermission()`. 🔴 Grant permissions via **role → group → user**; `user_permission`'s PK is `user_uuid`, so a user can hold exactly one direct grant. | P1-11, P1-13 |
| **P1-15** | `/search/suggestions` — trigram typeahead over `trgm_text`. | P1-7, P1-11 |

### 1e. UI

| ID | Task | Depends on |
|---|---|---|
| **P1-16** | `loom-ui/src/api/search.ts` (`search`, `searchAssets`, `suggest`, `searchStatus` + types), following `src/api/tags.ts`'s `API_BASE_URL` / `authHeaders` / `handleResponse<T>` shape. Plus `src/api/search.test.ts`. | P1-10 |
| **P1-17** | `src/features/search/useSearch.ts` (250 ms debounce, `AbortController` cancellation of superseded requests, `q.length >= 2` gate); `src/features/search/SearchView.tsx` (results grouped by entity type, "show more" per group, transcript hits deep-linking to `/assets/:id?t=<ms>`, empty/error/provider-unavailable states); `src/layout/GlobalSearchBar.tsx`; a `/search` route in `AppShell.tsx`; Cmd/Ctrl-K overlay.<br>⚠️ There is no existing top bar in the live shell — this is a shell-layout change. | P1-16, P0-5 |
| **P1-18** | `AssetBrowser.tsx` → `searchAssets` with **server-side paging**. This also fixes a real scaling bug: `listAssets(token)` takes no parameters and loads the entire catalog. | P1-16, P0-1 |
| **P1-19** | `LibraryView.tsx` → server-side, scoped by library. | P1-18 |
| **P1-20** | Playwright `e2e/search-mocked.spec.ts` and `e2e/search-backend.spec.ts`. | P1-17, P1-21 |

### 1f. Other consumers and closeout

| ID | Task | Depends on |
|---|---|---|
| **P1-21** | `DemoDatabaseInitializer` search fixtures: a transcript with distinctive text, an `ocr` comp, a `tika` comp, searchable tag names, one annotation title. Pick **one** magic string (e.g. `"aurora"`) shared by the website docs page and the backend e2e test. | P1-5 |
| **P1-22** | Un-stub the MCP tools. `SearchAssetsTool`: inject `SearchProvider`, replace `loadPage(null, limit, null, null, null)` with a real `SearchRequest`, add `library`/`tag`/`offset` params, keep the `MCPToolResults.reference("asset", …)` output. `SearchTranscriptTool`: `types=[TRANSCRIPT]`, `highlight=true`, return snippet + `assetUuid` + `timeFromMs`. ⚠️ Its current description cites `asset_doc_comp.doc_plain_text`, a dead table — rewrite it. Neither needs new registration; `MCPToolModule` already lists both.<br>⚠️ `MCPTool.execute(JsonObject)` carries no user context, so per-type narrowing cannot apply there. Acceptable in Phase 1 (it matches today's global-gate model) but record it as a gap in [../rbac/RBAC.md](../rbac/RBAC.md). | P1-7 |
| **P1-23** | GraphQL: **one** new top-level `search(q, types, mode, limit, offset)` field plus `SearchResult`/`SearchHit` types and `SearchEntityType`/`SearchMode` enums. Do **not** add filter args to the ~20 existing list fields — one field is a far smaller surface, and `SearchHit.asset` needs a DataLoader for N+1 anyway, which is work worth doing once. Guard with `READ_SEARCH` via `GraphQLPermissionChecker`; test through `AbstractGraphQLEndpointTest`. | P1-7 |
| **P1-24** | Customer-facing docs page under `website/content/english/docs/` (🔴 per CODING.md: no spec-file references, no internal class names, SVG not ASCII art). Spec sync: [../rbac/RBAC.md](../rbac/RBAC.md), [../permissions/PERMISSIONS.md](../permissions/PERMISSIONS.md), [../../loom/RESTAPI.md](../../loom/RESTAPI.md), [../../loom/MCP.md](../../loom/MCP.md), [../db/DATABASE_TASKS.md](../db/DATABASE_TASKS.md). | P1-14 |
| **P1-25** | Delete `loom/services/lucene` from `loom/services/pom.xml` and remove the directory ([SEARCH.md](SEARCH.md) §2). | — |

**Phase 1 exit criteria:** a user can type in the global bar and find an asset by filename, by a word
in its transcript, by OCR'd text, by a caption, and by tag name; results are ranked, paged and
permission-gated; `GET /search/status` reports `provider: postgres`.

---

## Phase 2 — Elasticsearch / OpenSearch

| ID | Task | Depends on |
|---|---|---|
| **P2-1** | 🔴 **Spike first.** `loom/services/elasticsearch/pom.xml` depends on the internal `io.metaloom.elasticsearch:elasticsearch-client` (`1.2.0-SNAPSHOT`, managed in `bom/pom.xml`), whose API is unverified. Confirm it supports bulk indexing, `search_after`, aliases, index templates, and (for Phase 3) `knn`. Fallbacks in preference order: the official `co.elastic.clients:elasticsearch-java`, or plain HTTP via the Vert.x `WebClient` — the latter is dependency-free and covers OpenSearch identically. **Do not write ES code before this resolves.** | — |
| **P2-2** | `loom/services/elasticsearch/src/main/resources/elasticsearch/loom-search-mapping.json` + `LoomSearchMapping` + `ensureSchema()`. One index per entity type (`{prefix}-asset-v1`, …) behind a single read alias, so a mapping change reindexes one type at a time. Fields mirror `search_document` 1:1, plus: `title` with `.keyword`/`.exact`/`.suggest` sub-fields; `body` with `index_options: offsets` (fast unified highlighter — the proper fix for the `ts_headline` problem); `library_uuids`/`space_uuids`/`collection_uuids` as multi-valued `keyword` **from the first mapping**; and `embedding` as a **declared-but-unpopulated `dense_vector`**, so Phase 3 hybrid needs no reindex. | P2-1 |
| **P2-3** | `ElasticsearchSearchIndexer` — bulk, retry with backoff, per-document `error` capture, dead-letter after N attempts. | P2-2 |
| **P2-4** | `ElasticsearchIndexSyncService` (Vert.x periodic verticle). Drains with `SELECT … WHERE dirty ORDER BY synced_at LIMIT :bulk FOR UPDATE SKIP LOCKED` — 🔴 `SKIP LOCKED` is what makes it safe to run on every Loom replica with no coordination. Then `UPDATE … SET dirty = false, es_synced_at = now()`. Also drains `search_document_deleted`: 🔴 tombstones are **required**, because the `ON DELETE CASCADE` removes the row before anyone can read it. Prune tombstones after 7 days.<br>**Backfill is not a separate code path** — `SELECT search_document_rebuild()` marks everything dirty and the normal drain handles it with natural backpressure, which means the backfill is exercised by the same tests as steady state. | P2-3, P1-5 |
| **P2-5** | `ElasticsearchSearchProvider` — query, highlight, facets, `search_after` populating `nextCursor`. Advertises `DEEP_PAGING`, `FACETS`, `HIGHLIGHT`. | P2-2 |
| **P2-6** | `ElasticsearchHealthCheck`; add a `search` component to `HealthEndpoint`/`HealthCheckResponse`. ⚠️ `HealthEndpoint` is registered **without** `secure(...)`, i.e. public — do not leak the ES URL through it. | P2-5 |
| **P2-7** | `POST /api/v1/search/reindexes` (operator-forced drain / mark-all-dirty). ⚠️ **Open question:** which permission guards it — check what the existing maintenance routes use before adding `MANAGE_SEARCH`. | P2-4 |
| **P2-8** | Testcontainers ES tests: mapping applies; index/update/delete round-trip; a poison document does not stall the queue; `dirty` drain marks rows synced; a tombstone removes the ES doc. Plus a **provider-parity test** — the same fixture corpus in both backends returns the same *top-5 set* (not the same order) for a fixed query list. Use `refresh=wait_for`.<br>⚠️ `org.testcontainers:elasticsearch` is managed in `bom/pom.xml` at **1.17.6 — old**; verify it can pull a current ES image and does not conflict with the testcontainers version resolved elsewhere. | P2-5, P1-8 |
| **P2-9** | Ops: an `elasticsearch` service behind a **compose profile** in `test-database/docker-compose.yaml` and `podman-compose.yml` (so the default dev loop stays fast); a `search:` block in `helm/loom/values.yaml` mirroring the existing `ai:`/`postgresql:` conventions, plus an optional bundled single-node ES StatefulSet following the chart's stated "official images, no third-party subcharts, works offline" policy. | P2-6 |
| **P2-10** | `POST /api/v1/search/results` — body-encoded for long queries and many filters. | P1-11 |
| **P2-11** | `?q=` substring narrowing on list routes (`ILIKE`/trigram in the DAO `WHERE` clause, **keyset paging untouched**, no ranking) + DAO tests. See [SEARCH.md](SEARCH.md) §7.2 for why this is a different feature from `/search/*`. | P0-1 |
| **P2-12** | Migrate the remaining UI views to `?q=`. 🔴 **Criterion, so nobody churns views for free:** a view migrates when its list can plausibly exceed 500 rows in a real deployment. Migrate `TagsView` (keep the client-side tree grouping — that is a render concern), `CollectionsView`, `AssetPoolsView`, the six `AdminArea` boxes (users and groups are the ones that actually grow), and the detection views. **Do not migrate** `CortexView` or `PipelineEditor` — both are bounded by deployment size and the client filter is correct there. | P2-11 |
| **P2-13** | `/search/facets` + facet UI. | P2-5 |

---

## Phase 3 — Semantic / hybrid

Detailed in [SEMANTIC_SEARCH.md](SEMANTIC_SEARCH.md). Summary of the order:

| ID | Task | Depends on |
|---|---|---|
| **P3-1** | Spike: embedding model + inference host (ONNX in-process vs. a `sidecars/` service); fix the dimension | — |
| **P3-2** | Spike: pgvector availability — image change vs. a `pg_available_extensions` guard; confirm `generate.sh` and `setup-pool.sh` survive | — |
| **P3-3** | Guarded migration: `CREATE EXTENSION vector`, `embedding_vec_<dim>` + HNSW, `embedding` exporter columns + the missing `array_length` CHECK | P3-1, P3-2 |
| **P3-4** | `VectorConfigDao` + `GET /api/v1/vector-configs` + seed the `default` profile | P3-3 |
| **P3-5** | `cortex/nodes/embedding` — `EmbeddingNode` (CLIP/SigLIP whole-image) | P3-1 |
| **P3-6** | `embedding_vec` sync job (mirrors P2-4) | P3-3, P3-5 |
| **P3-7** | `VectorIndex` SPI + `PgVectorIndex`; `SearchMode.SEMANTIC` | P3-6 |
| **P3-8** | RRF fusion; `SearchMode.HYBRID` | P3-7 |
| **P3-9** | ES-side `dense_vector` population + native `rrf`/`knn` | P3-8, P2-5 |
| **P3-10** | `FacedetectNode` persists its existing InspireFace vectors; `cluster` gets `search_document` rows | P3-3 |
| **P3-11** | UI: mode toggle, "more like this", cluster filter | P3-8 |

---

## Definition of done (per [../../guidelines/CODING.md](../../guidelines/CODING.md))

| Requirement | Where it is satisfied |
|---|---|
| Plural REST paths | `search` is a namespace with no handler; all leaves are plural (P1-11) |
| Endpoint test | `SearchEndpointTest` (P1-14) |
| Fine-grained permission test cases | `testSearchRequiresPermission` + `testSearchNarrowsByTypePermission` (P1-14) |
| DAO implementation covered by tests | `PostgresSearchProviderTest` (P1-8) |
| **Delete-cascade test** | asset deletion removes exactly its document family and nothing else (P1-8) |
| Customer-facing website docs | P1-24 — no spec references, no internal class names, SVG not ASCII |
| Meaningful demo data | P1-21 |
| Spec kept in sync | P1-24 updates RBAC, PERMISSIONS, RESTAPI, MCP, DATABASE_TASKS |

## Open items — do not let an implementation invent answers

| # | Question | Blocks |
|---|---|---|
| 1 | Does `io.metaloom.elasticsearch:elasticsearch-client` 1.2.0-SNAPSHOT support bulk / `search_after` / aliases / templates / `knn`? | P2-1 (gates all of Phase 2) |
| 2 | Can `org.testcontainers:elasticsearch` 1.17.6 pull a current ES image without conflicting with the version resolved elsewhere? | P2-8 |
| 3 | Which permission guards `/search/reindexes`? Check the existing maintenance routes before adding `MANAGE_SEARCH`. | P2-7 |
| 4 | `SearchAssetHitResponse extends AssetResponse` vs. a side-car score map — depends on `AssetResponse` not being `final` and on the Jackson config. | P1-10 (minor) |
| 5 | Does pgvector allow a `PARTITION BY LIST (dimensions)` child to narrow `vector` → `vector(768)`? The design routes around this deliberately — do **not** promote the partitioning sketch to fact. | P3-3 |

## Where do I find …?

| Need | Look here |
|---|---|
| Why any of this is shaped the way it is | [SEARCH.md](SEARCH.md) |
| The `search_document` DDL | [SEARCH.md](SEARCH.md) §5.2 |
| The jsonb text-extraction whitelist | [SEARCH.md](SEARCH.md) §6 |
| Vector / hybrid design | [SEMANTIC_SEARCH.md](SEMANTIC_SEARCH.md) |
| Test details per task | [SEARCH.md](SEARCH.md) §10 |
| Definition of done for code | [../../guidelines/CODING.md](../../guidelines/CODING.md) |
| Definition of done for a spec change | [../../SPEC_RULES.md](../../SPEC_RULES.md) |

## Progress Assessment

- [ ] **Phase 0** — P0-1 … P0-5 (5 tasks)
- [ ] **Phase 1** — P1-1 … P1-25 (25 tasks); exit criteria above
- [ ] **Phase 2** — P2-1 … P2-13 (13 tasks); gated on the P2-1 spike
- [ ] **Phase 3** — P3-1 … P3-11 (11 tasks); see [SEMANTIC_SEARCH.md](SEMANTIC_SEARCH.md)
- [ ] Open items 1–5 resolved

---

_Git HEAD: `65e6c4649c639303932384942d4c68d8e9e8360d` (branch `master`)_
_Last updated: 2026-07-27_
