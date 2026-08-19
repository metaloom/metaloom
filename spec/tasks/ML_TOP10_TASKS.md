# MetaLoom — Top 10 Tasks

> A cross-file ranking of the open work in this directory, derived on 2026-08-18 from the 22 task
> files under [spec/tasks/](.) and their own ordering/blocking notes.
>
> **This is an index, not a task file.** It carries **no new task text**: every entry points at the
> task that owns the work, and the four mandatory fields, the agent prompt and the test requirements
> live there. Ordering follows [TASKS.template.md](TASKS.template.md) ("order by severity, and say up
> front which tasks are blocking"), but the numbering below is a *rank*, not a stable id — cite the
> owning file's task number, never `ML_TOP10 #3`.
>
> **Ranking rule.** Highest first: (1) defects that make the system report a wrong answer confidently,
> (2) controls that look enforced and are not, (3) work that unblocks other tasks, (4) everything the
> product visibly lacks. Within a rank, cheap-and-blocking beats expensive-and-isolated.

---

## Dependency map

```
#1 failure reporting ──> WORKFLOW Task 13 (ingest reconciliation)
#2 ledger provenance ──┬─> WORKFLOW Task 9 (AI review record)
                       └─> ARCH Task 5 (batch REST writes — must NOT land first)
#3 control channel     ── independent (loom-ui pre-login socket check gates step 1)
#4 schema keys ────────┬─> PERSISTENCE Task 6 (VectorConfigDao) [DB Task 24]
                       ├─> every endpoint permission test        [DB Task 15]
                       └─> DB Task 22 must follow DB Task 16
#5 node trust boundary ── independent (WORKFLOW Task 14 shares the enforcement point)
#6 fingerprint FP1+FP2 ──> FP3 (video4j) ──> FP4 (cortex) ──> LUCENE Task 5
#7 search SEARCH 2/3/25 ── independent · SEARCH 18 ──> SEARCH 19 · SEARCH 20 ──> image search
#8 face FACE 10+11 ─────> FACE 1 ; FACE 3 ──> FACE 4 ──> FACE 5 ; FACE 9 ──> FACE 7
#9 chat CTX3 ──> SEC2 ──> QW1/2/3/7 ──> RD1 ──┬─> RD3, RD4, EXE4
                                              └─ SEC1 ──> RD2 ; LP2 ──> ACT1/ACT2
#10 ui UI14 ✔ ──> UI16, UI17 unblocked ; UI11 ✔ ──> UI13 unblocked ; UI9 (ratchet) ──> UI21–31
```

## At a glance

| # | Theme | Owns it | Kind | Blocks |
|---|---|---|---|---|
| 1 | Node failures are reported as SUCCESS | [WORKFLOW_TASKS](WORKFLOW_TASKS.md) 17 · [NODE_TASKS](NODE_TASKS.md) 5 | 🔴 correctness | WORKFLOW 13 |
| 2 | The ledger cannot name its execution, and rows collide | [WORKFLOW_TASKS](WORKFLOW_TASKS.md) 18 · [NODE_DATA_TYPES](NODE_DATA_TYPES_TASKS.md) 2, 3 · [NODE_TASKS](NODE_TASKS.md) 3, 4 | 🔴 correctness | WORKFLOW 9, ARCH 5 |
| 3 | The control channel trusts any connected worker | [METALOOM_ARCHITECTURE_TASK](METALOOM_ARCHITECTURE_TASK.md) 6 | 🔴 security | — |
| 4 | Primary keys that silently discard data | [DATABASE_TASKS](DATABASE_TASKS.md) 15, 24, 16, 22 | 🔴 correctness | PERSISTENCE 6, permission tests |
| 5 | Two node-tier controls that control nothing | [NODE_TASKS](NODE_TASKS.md) 1, 2 | 🔴 security | WORKFLOW 14 shares the gate |
| 6 | The fingerprint only sees 45 % of a video | [NODE_FINGERPRINT_TASKS](NODE_FINGERPRINT_TASKS.md) 1–4 | 🔴 correctness | SEARCH_LUCENE 5 |
| 7 | Search: types that can never hit, and an ACL that isn't | [SEARCH_TASKS](SEARCH_TASKS.md) 2, 3, 25 | 🟠 correctness | — |
| 8 | Face options dropped, identities that outlive their embedder | [WORKFLOW_FACE_TASKS](WORKFLOW_FACE_TASKS.md) 10, 11, 1 | 🟠 correctness | FACE 3–5 |
| 9 | The agent's window, and the tools that lie to it | [CHAT_TASKS](CHAT_TASKS.md) CTX3, SEC2, RD1 | 🟠 defects | RD3/RD4/EXE4 |
| 10 | ~~Mutations that fail silently in the UI~~ **DONE 2026-08-18** | [LOOM_UI_TASKS](LOOM_UI_TASKS.md) 14, 11 | 🟠 trust | — (UI 16, 17, 13 unblocked) |

---

## 1. Stop reporting failures as success — DONE (2026-08-18)

> **Landed.** 15 chained call sites in 13 node classes converted to `.abort()` (the old count of
> 16-in-14 included `DominantColorNode`'s own explanatory *comment*, which a `grep` cannot tell from
> code), plus `CaptioningNode`'s `printStackTrace()` + bare `NodeResult.failed()` variant.
> `NodeContextImpl.next()` is fail-closed, `FailurePathGuardTest` (`cortex/api`) fails the build on
> the shape, and every converted node has a test asserting the terminal state and the surviving
> cause. WORKFLOW Task 13 is unblocked. Full write-up in
> [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 17.
>
> One thing the work surfaced that this item did *not* cover: 24 `persist(...)` catch blocks across
> 22 node classes swallow their own exception, record a `FAILED` ledger row and let the node return
> SUCCESS. The consequence is the same — a green node whose result was never durably stored — but the
> shape is different and the design is deliberate in at least some of them. Recorded as a new item in
> [NODE_TASKS.md](NODE_TASKS.md).

**Source:** [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 17 · the residual nodes in
[NODE_TASKS.md](NODE_TASKS.md) Task 5 · recorded again as an unowned defect in
[METALOOM_NOTES.md](METALOOM_NOTES.md).

`ctx.failure(cause).next()` returns `ResultState.SUCCESS` and drops the message —
`NodeContextImpl.next()` reads only `skipReason`; `failureCause` is read by `abort()` alone. Sixteen
call sites across fourteen production node classes do exactly that (`FacedetectNode`, `WhisperNode`,
`TikaNode`, `ThumbnailNode`, `FingerprintNode`, …). A run whose transcript, thumbnail or fingerprint
silently failed is indistinguishable from one that worked.

**Why first.** Everything below is measured against results this defect can falsify: a review queue
that never fills, a dedup proposal that never appears, undetected data loss on an ingest path. It
also **blocks** WORKFLOW Task 13, and `DominantColorNode:183` is already the converted worked
example, so the pattern to copy exists. Land the anti-regression guard (ArchUnit or a build grep) in
the same change, or a seventeenth site arrives with the next node.

---

## 2. Make a ledger row identify its execution — and stop rows overwriting each other

**Source:** [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 18 = [NODE_DATA_TYPES_TASKS.md](NODE_DATA_TYPES_TASKS.md)
Tasks 2 and 3 (the same method, the same request object — land them together) ·
[NODE_TASKS.md](NODE_TASKS.md) Tasks 3 and 4 (the collision half).

Three holes in one write path. `AbstractMediaNode.recordNodeResult` hardcodes
`ResultOrigin.COMPUTED`, so a cache replay is indistinguishable from real work even though the node
computed the real answer for its own skip decision. `NodeResultCreateRequest` carries no
`runUuid`/`taskUuid`, so although `V2.45` created the columns, a cortex row can never be joined to
its run — "which run produced these values, so I can invalidate them" has no answer. And `ScriptNode`
and `ImageGenNode` never override `nodeId()`, so two instances of either in one graph upsert onto the
same `(asset_uuid, node_kind, node_id)` row and the second silently overwrites the first.

**Why here.** Provenance is what makes #1's converted states useful after the fact, and it **blocks**
WORKFLOW Task 9 (a review keyed to a ledger row cannot say which execution it reviewed).
[METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Task 5 (batching the per-node REST
writes) touches the same call — land this first or that change is redone. Decide the
`cortex_instance` question (FK vs. denormalised name+version) explicitly and write it into
[../loom/DOMAIN.md](../loom/DOMAIN.md); `V2.66` already warns the two `node_id` columns mean
different things.

---

## 3. Harden the control channel

**Source:** [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Task 6.

`WebSocketAuthenticator#resolveStrict` returns **false** when neither `-Dloom.ws.strictAuth` nor
`LOOM_WS_STRICT_AUTH` is set, so a token-less worker connects with a warning. And an authenticated
worker is unconstrained: `ProcessorEndpoint#handleNodeTaskResult` / `#handleNodeTaskResultBatch` use
the node id only to prove registration, then settle whatever `runUuid` the body names — **any
registered worker can settle any item of any run**. `pipeline_node_task.leased_by` already records
the rightful owner. Registration is unthrottled (no `RateLimit` anywhere in `loom/`).

**Why here.** It is the trust boundary that #1 and #2 write across, so fix it while that path is
open. ⚠️ Step 1 is gated on confirming `loom-ui/src/api/pipelineEvents.ts` never opens the socket
before login — the login flow was reworked in `67000540`, so re-check rather than trusting the audit.
Answer for the second worker→server path too (every node also writes back over REST with the same
`LOOM_TOKEN`, which `LOOM_WS_STRICT_AUTH` does not touch), or record that it is deliberately only
user-authenticated.

---

## 4. Fix the primary keys that discard data

**Source:** [DATABASE_TASKS.md](DATABASE_TASKS.md) Tasks 15 and 24 (both 🔴 HIGH), then 16, then 22.

`user_permission` and `token_permission` have carried `PRIMARY KEY (user_uuid)` /
`(token_uuid)` since `V2.1`, so a subject can hold **exactly one direct grant, ever** — a second
insert is a key violation, not an added permission. The suite has hidden this by granting through
group+role throughout. `vector_config` has no primary key at all, which is why
`JooqVectorConfigRecord` is generated as a `TableRecordImpl` and **blocks**
[PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) Task 6.

**Why here.** Highest correctness payoff per line of SQL left in the schema, independent of #1–#3,
and it unblocks writing permission tests the honest way. Task 22 must land **after** Task 16 so the
new columns are indexed in one pass. ⚠️ Each is its own migration — do not batch them; each needs
`loom/db/jooq/generate.sh` **and** `./setup-pool.sh` with `loom/db/flyway` installed first, each
writes `V2.100`, and whoever lands second renumbers (sorting numerically, not lexically).

---

## 5. Make the two node-tier security controls real

**Source:** [NODE_TASKS.md](NODE_TASKS.md) Tasks 1 and 2.

`s3-sink` is a byte-carrying exit from MetaLoom and performs **no rights check** — any asset a
pipeline can reach can be uploaded to an external bucket.
[../workflows/WORKFLOW_RIGHTS_RELEASE.md](../workflows/WORKFLOW_RIGHTS_RELEASE.md) §2.6 names this
node as the exit that needs the gate and calls `ExportGateTest` its critical guard; that test does
not exist. Separately, `script` renders `allowNetwork` and `allowFilesystem` as checkboxes that
nothing reads, while `trusted` defaults to **true** — `allowAllAccess(true)`, full worker privileges
out of the box, with no memory bound.

**Why here.** Both are controls a user reasonably believes are enforced; the egress one also carries
legal exposure. WORKFLOW Task 14 (rights and release) needs the same enforcement point — decide it
once. For `script`, "delete the options and document the default" is an acceptable outcome; "leave
them" is not.

---

## 6. Fingerprint the whole timeline

**Source:** [NODE_FINGERPRINT_TASKS.md](NODE_FINGERPRINT_TASKS.md) Tasks 1 + 2 together, then 3 → 4.

The fingerprint never samples past ~45 % of a video, and its tuning is global mutable state with no
`producer_version` recorded. Tasks 1 and 2 both change the bytes a fingerprint produces, so they
**must land together** behind a single algorithm-id bump and one reindex — do not churn the corpus
twice. Then Task 3 (a time-windowed fingerprinter in the sibling **video4j** repo) blocks Task 4
(one `asset_fingerprint_comp` row per window), which is the producer
[SEARCH_LUCENE_TASKS.md](SEARCH_LUCENE_TASKS.md) Task 5 is 🔒 blocked on:
`FingerprintNode.persist(...)` hardcodes `windowIndex = 0` and leaves `time_from`/`time_to` NULL, so
no clip can ever match a longer video.

**Why here.** It costs a full reindex, and that cost only grows — the cheapest day to do it is the
earliest one. It is also the whole basis of dedup and similarity, two features that currently answer
confidently from half a file.

---

## 7. Close the search correctness gaps — and do not start Elasticsearch

**Source:** [SEARCH_TASKS.md](SEARCH_TASKS.md) Task 2, then 25, then the Task 3 decision.

`DETECTION` and `SEGMENT` are accepted by the API and by the request enum but no document source
emits them, so those types can never produce a hit (Task 2 — the remaining defect, do it first).
Task 25: a non-English corpus is only matched unstemmed. Task 3 is a decision, not a defect:
`search_document.library_uuids`/`space_uuids` are trigger-maintained and GIN-indexed,
`SearchRequest` has the fields and `PostgresSearchProvider.appendFilters` emits the predicate — and
**nothing calls either setter**. It reads like an enforced row-level ACL and is unreachable code.
Wire it only alongside a Loom-wide row-level ACL decision; otherwise retire it. Leaving it as it is
is the one unacceptable outcome.

⛔ **Do not start Elasticsearch.** [SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) §0 records the
assessment (Postgres covers today's cases) and §3 the four measurable triggers that would reverse it.
Its Task 11 spike blocks 12–15 and 23 and should not be run. Independent follow-ons if capacity
remains: Task 18 → 19 (list-route `?q=`), and Task 20 (the CLIP image node), which is the single
thing between here and text-to-image search.

---

## 8. Make the face loop honest about its model

**Source:** [WORKFLOW_FACE_TASKS.md](WORKFLOW_FACE_TASKS.md) Tasks 10 + 11 (same family, ~30 minutes
each), then Task 1, then 3 → 4 → 5.

`facedetect` advertises thirteen parameters, the editor renders all of them, `NodeOptionValidator`
accepts all of them — and `FacedetectNode.configure(...)` reads **two**. For `embeddingsEnabled` and
`embeddingModel` the "baked in at Dagger build time" justification is simply false; both are read per
item. Task 10 is therefore **blocking for Task 1's premise**: the model-pack change Task 1 gates on
cannot be triggered from the editor at all. Task 1 itself is the one way this feature produces a
*wrong* answer rather than a missing one — swap the pack and a cluster a human confirmed as "Anna"
keeps `status=CONFIRMED`, its `person_uuid` **and its reviewer's name** while its members are
recomputed from a different embedder's geometry. `DetectionDaoImpl.reviewOverrides()` (`V2.81`) is
the mechanism to copy; no DDL is needed.

**Open product question, gating Task 5:** is per-asset identity ("who is in this video") shippable on
its own, or must cross-asset identity ("who is this") land first? Decide before scheduling.

---

## 9. Fix the agent's window and the tools that lie to it

**Source:** [CHAT_TASKS.md](CHAT_TASKS.md) — its own recommended order: CTX3 → SEC2 → QW1/QW2/QW3/QW7
→ RD1 → RD4/RD3.

CTX3: `AgentLoop.executeToolCall` returns the **untruncated** tool result into the live history (only
the persisted `resultSummary` is capped), so one large result overflows the window mid-run and fails
the turn — on the first message of a new chat, with no history at all. Two shipped tools already
carry `// TODO(CTX3)` workarounds. RD1: `SearchAssetsTool` declares `query` and `mimeType` and reads
neither — it calls `assetDao.loadPage(null, limit, null, null, null)` — while `SearchTranscriptTool`
returns a hard-coded stub. The model reports the wrong assets confidently and cannot detect it. SEC2:
`chat.messages` is still client-writable, so a caller can author a transcript the loop replays
(the `chat.meta` half was closed on 2026-08-16).

**Why here.** CTX1/CTX2/CTX4 landed on 2026-08-16, so CTX3 is now the last way a single turn can
overflow. RD1 also defines the filter vocabulary RD3, RD4 and EXE4 all reuse — build it once. Do not
start the write tier (ACT1/ACT2) before LP2's confirmation primitive exists, and land SEC1 with RD2
rather than after it.

---

## 10. Give the UI a failure path — DONE (2026-08-18)

**Source:** [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) Task 14, then Task 11.
**Design:** [../features/ui/FAILURE_REPORTING.md](../features/ui/FAILURE_REPORTING.md)

> **Landed, and deliberately more general than the entry below asked for.** Rather than sprinkling
> `showToast` across two dozen catch blocks, the change built the mechanism those catch blocks
> should have been calling: `useFailure().reportFailure(action, error)`, which raises the toast
> *and* offers a **Report** button. The report carries the `X-Trace-Id` of the failing response,
> which is what finally joins the user's half of a failure ("I pressed Create and nothing happened")
> to the server's half (the stack trace) — neither of which was actionable alone.
>
> That required, in order: a trace id on every response (`TraceIdHandler`, sanitised inbound, CORS-
> exposed); one shared HTTP layer to capture it (`src/api/http.ts`, replacing **36** private
> `handleResponse` copies across 39 modules); a place for reports to go
> (`/api/v1/failure-reports`, three new permissions, `failure_report` + `failure_report_screenshot`,
> an admin inbox); a report form with an optional `getDisplayMedia` screenshot the user reviews and
> can enlarge before submitting; and the Task 11 pair — a route-level error boundary and a global
> 401 path that produces one message rather than one per widget.
>
> Task 14's steps 1-4 and 7 are done, step 5 is done for two views of twelve, step 6 for one of
> nine. Task 11 is complete. **Tasks 16, 17 and 13 are unblocked.**

A failed write is indistinguishable from a successful one across the feature tree.
`FaceDetectionManagement.tsx` clears the form and closes the dialog **outside** the try/catch, so a
rejected create looks accepted and the user believes a person exists that does not — eight
console-only sites in that one feature. `TagsView.tsx` has four mutations with no `try`/`catch` at
all. `AdminArea.tsx` swallows a delete; `LibraryView.tsx` turns a failed load into "no libraries".
`ToastProvider`/`useToast` already exist and a dozen views use them correctly — the mechanism is
present, just unapplied.

**Why here.** Task 14 **blocks** Tasks 16 and 17: the a11y sweep and the new admin specs both assert
on feedback that does not exist yet. Task 11 (error boundaries + a global 401 path) should land
before Task 13, because `React.lazy` without a boundary turns a chunk-load failure into a blank page.
⚠️ Never run the loom-ui runners through `npx` — call `./node_modules/.bin/{vitest,playwright}`
directly.

---

## Just below the line

Ranked, and each is a legitimate substitute if the entry above it is blocked on a decision:

11. [PIPELINE_TASKS.md](PIPELINE_TASKS.md) Tasks 15 and 16 — armed breakpoints are silently lost on
    run recovery, and a renamed-port edge inside an affinity segment resolves by port *name*, so
    **wrong** data reaches a node rather than no data. Both independent of everything.
12. [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Task 2 — a crashed worker's tasks
    sit `RUNNING` for a full lease interval; Task 11 already landed the machinery
    (`loadLeasedBy`, `reclaimWorker`), only the disconnect wiring is missing.
13. [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 8 — X5/X6/X7 in one change; degrades five of the six
    shipped review modes (profiles lost on reload, "the first 20 assets" instead of a queue, no
    resumable review record).
14. [SHARE_TASKS.md](SHARE_TASKS.md) Tasks 1 → 2 → 3 — the server collects customer feedback and the
    bell announces it, and there is nowhere in the product to read it; six typed client functions are
    already written and called by nothing.
15. [PIPELINE_TASKS.md](PIPELINE_TASKS.md) Task 13 — the last engine meter. Cheap, and
    [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Tasks 13, 14 and 15 were all
    deferred *pending measurement*; without it they would be tuned blind.
16. [NODE_DATA_TYPES_TASKS.md](NODE_DATA_TYPES_TASKS.md) Task 7 — result reuse is hard-coded to
    element 0, which is wrong under fan-out.
17. [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 6 — one migration comment and two DTO fields; an
    hour, and the `detection.type` string stops drifting a second time.
18. [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) Task 9 — the unreferenced-testid ratchet. Land it before any
    of the E2E batches (Tasks 21–31), because it is what stops the backlog growing behind them.

## Deliberately not ranked

* **Elasticsearch Phase 2** ([SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) Tasks 11–15, 23) —
  assessed and deferred by decision, with a written revisit trigger. Read §0 before proposing it.
* **The greenfield workflows** ([WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Tasks 10–14) — proposals whose
  own spec files carry their build order; the tasks there are entry points, not plans. Task 12
  depends on Task 4 and on the enforcement point #5 above also needs.
* **Spec re-baselines** ([WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) 16, [PIPELINE_TASKS.md](PIPELINE_TASKS.md)
  17, [NODE_SCHEMA_TASKS.md](NODE_SCHEMA_TASKS.md) 4, [DATABASE_TASKS.md](DATABASE_TASKS.md) 20–21) —
  do them *after* the code changes above, so each sweep records one state rather than two.
* **[WEBSITE_TASKS.md](WEBSITE_TASKS.md)**, **[LOOM_UI_UPLOAD_TASKS.md](LOOM_UI_UPLOAD_TASKS.md)** and
  the [IMAGEGEN_NODE.md](IMAGEGEN_NODE.md) open items — real work, none of it blocking and none of it
  producing a wrong answer today. The imagegen live-GPU smoke test is the exception worth scheduling
  opportunistically: it is the only unverified boundary in an otherwise shipped node.
* **[METALOOM_NOTES.md](METALOOM_NOTES.md)** — scratch backlog by design. Its two verified unowned
  defects are covered above (#1, and the dedup keeper-role rewrite under
  [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 3); the demo-seeding path having no test anywhere is
  still unowned and belongs in [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 15.

---

_Git HEAD revision: `d4e9134f`_
_Last updated: 2026-08-18 (cross-file ranking of spec/tasks/)_
