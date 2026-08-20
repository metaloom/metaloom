# MetaLoom — Top 10 Tasks

> A cross-file ranking of the open work in this directory, derived on 2026-08-18 from the 22 task
> files under [spec/tasks/](.) and their own ordering/blocking notes. Re-ranked 2026-08-20: the
> original #1 (failure reporting — WORKFLOW Task 17 / NODE Task 5) and #10 (the UI failure path —
> LOOM_UI Tasks 14 + 11) landed on 2026-08-18 and were removed; their write-ups live in the owning
> files. Ranks 9 and 10 were promoted from just below the line.
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
#1 ledger provenance ──┬─> WORKFLOW Task 9 (AI review record)
                       └─> ARCH Task 5 (batch REST writes — must NOT land first)
#2 control channel     ── independent (loom-ui pre-login socket check gates step 1)
#3 schema keys ────────┬─> PERSISTENCE Task 6 (VectorConfigDao) [DB Task 24]
                       ├─> every endpoint permission test        [DB Task 15]
                       └─> DB Task 22 must follow DB Task 16
#4 node trust boundary ── independent (WORKFLOW Task 14 shares the enforcement point)
#5 fingerprint FP1+FP2 ──> FP3 (video4j) ──> FP4 (cortex) ──> LUCENE Task 5
#6 search SEARCH 2/3/25 ── independent · SEARCH 18 ──> SEARCH 19 · SEARCH 20 ──> image search
#7 face FACE 10+11 ─────> FACE 1 ; FACE 3 ──> FACE 4 ──> FACE 5 ; FACE 9 ──> FACE 7
#8 chat CTX3 ──> SEC2 ──> QW1/2/3/7 ──> RD1 ──┬─> RD3, RD4, EXE4
                                              └─ SEC1 ──> RD2 ; LP2 ──> ACT1/ACT2
#9 pipeline PIPELINE 15 · 16 ── independent of everything
#10 worker reclaim ARCH 2 ── independent (ARCH Task 11 landed the machinery; only wiring is left)
```

## At a glance

| # | Theme | Owns it | Kind | Blocks |
|---|---|---|---|---|
| 1 | The ledger cannot name its execution, and rows collide | [WORKFLOW_TASKS](WORKFLOW_TASKS.md) 18 · [NODE_DATA_TYPES](NODE_DATA_TYPES_TASKS.md) 2, 3 · [NODE_TASKS](NODE_TASKS.md) 3, 4 | 🔴 correctness | WORKFLOW 9, ARCH 5 |
| 2 | The control channel trusts any connected worker | [METALOOM_ARCHITECTURE_TASK](METALOOM_ARCHITECTURE_TASK.md) 6 | 🔴 security | — |
| 3 | Primary keys that silently discard data | [DATABASE_TASKS](DATABASE_TASKS.md) 15, 24, 16, 22 | 🔴 correctness | PERSISTENCE 6, permission tests |
| 4 | Two node-tier controls that control nothing | [NODE_TASKS](NODE_TASKS.md) 1, 2 | 🔴 security | WORKFLOW 14 shares the gate |
| 5 | The fingerprint only sees 45 % of a video | [NODE_FINGERPRINT_TASKS](NODE_FINGERPRINT_TASKS.md) 1–4 | 🔴 correctness | SEARCH_LUCENE 5 |
| 6 | Search: types that can never hit, and an ACL that isn't | [SEARCH_TASKS](SEARCH_TASKS.md) 2, 3, 25 | 🟠 correctness | — |
| 7 | Face options dropped, identities that outlive their embedder | [WORKFLOW_FACE_TASKS](WORKFLOW_FACE_TASKS.md) 10, 11, 1 | 🟠 correctness | FACE 3–5 |
| 8 | The agent's window, and the tools that lie to it | [CHAT_TASKS](CHAT_TASKS.md) CTX3, SEC2, RD1 | 🟠 defects | RD3/RD4/EXE4 |
| 9 | Breakpoints lost on recovery; wrong data on segment edges | [PIPELINE_TASKS](PIPELINE_TASKS.md) 15, 16 | 🟠 correctness | — |
| 10 | A crashed worker's tasks wait out the full lease | [METALOOM_ARCHITECTURE_TASK](METALOOM_ARCHITECTURE_TASK.md) 2 | 🟠 reliability | — |

---



---

## 2. Harden the control channel

**Source:** [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Task 6.

`WebSocketAuthenticator#resolveStrict` returns **false** when neither `-Dloom.ws.strictAuth` nor
`LOOM_WS_STRICT_AUTH` is set, so a token-less worker connects with a warning. And an authenticated
worker is unconstrained: `ProcessorEndpoint#handleNodeTaskResult` / `#handleNodeTaskResultBatch` use
the node id only to prove registration, then settle whatever `runUuid` the body names — **any
registered worker can settle any item of any run**. `pipeline_node_task.leased_by` already records
the rightful owner. Registration is unthrottled (no `RateLimit` anywhere in `loom/`).

**Why here.** It is the trust boundary that #1's ledger writes cross (and that the landed failure
reporting reports over), so fix it while that path is open. ⚠️ Step 1 is gated on confirming `loom-ui/src/api/pipelineEvents.ts` never opens the socket
before login — the login flow was reworked in `67000540`, so re-check rather than trusting the audit.
Answer for the second worker→server path too (every node also writes back over REST with the same
`LOOM_TOKEN`, which `LOOM_WS_STRICT_AUTH` does not touch), or record that it is deliberately only
user-authenticated.

---

## 3. Fix the primary keys that discard data

**Source:** [DATABASE_TASKS.md](DATABASE_TASKS.md) Tasks 15 and 24 (both 🔴 HIGH), then 16, then 22.

`user_permission` and `token_permission` have carried `PRIMARY KEY (user_uuid)` /
`(token_uuid)` since `V2.1`, so a subject can hold **exactly one direct grant, ever** — a second
insert is a key violation, not an added permission. The suite has hidden this by granting through
group+role throughout. `vector_config` has no primary key at all, which is why
`JooqVectorConfigRecord` is generated as a `TableRecordImpl` and **blocks**
[PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) Task 6.

**Why here.** Highest correctness payoff per line of SQL left in the schema, independent of #1 and #2,
and it unblocks writing permission tests the honest way. Task 22 must land **after** Task 16 so the
new columns are indexed in one pass. ⚠️ Each is its own migration — do not batch them; each needs
`loom/db/jooq/generate.sh` **and** `./setup-pool.sh` with `loom/db/flyway` installed first, each
writes `V2.100`, and whoever lands second renumbers (sorting numerically, not lexically).

---

## 4. Make the two node-tier security controls real

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

## 5. Fingerprint the whole timeline

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

## 6. Close the search correctness gaps — and do not start Elasticsearch

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

## 7. Make the face loop honest about its model

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

## 8. Fix the agent's window and the tools that lie to it

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

## 9. Keep the debugger honest — breakpoints across a restart, bindings across a segment

**Source:** [PIPELINE_TASKS.md](PIPELINE_TASKS.md) Tasks 15 and 16 — independent of each other and
of everything above. Promoted from just below the line on 2026-08-20.

Task 15: armed breakpoints are run state that lives **only in the engine**. `PipelineRunRecovery`
rebuilds items, tasks and the graph after a Loom restart and never mentions breakpoints, so a
recovered `RUNNING`/`PAUSED` run comes back with none armed and the items that were being held
stream straight through — nothing logs it. The fix is contained: persist the armed set in
`pipeline_run.meta` (JSONB since `V2.29`; `ADHOC`'s `meta.definition` is the precedent, no
migration) and re-arm on recovery. Task 16: `SegmentNode` carries no `InputBinding`, so a
segment-internal edge resolves by port *name* — a pair the editor let you wire `image_out` →
`media_in` works when dispatched separately and receives **nothing** (or a same-named port from
another dependency) once affinity puts both in one segment. **Wrong** data reaches a node rather
than no data.

**Why here.** Both are rank-1-class defects — a control the user armed silently disarmed, and
silently wrong data — just narrower in blast radius than #1–#5. Both are cheap, fully specified in
the owning file, and block nothing and are blocked by nothing.

---

## 10. Reclaim a vanished worker's leases at disconnect

**Source:** [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Task 2. Promoted from
just below the line on 2026-08-20.

The graceful drain path is complete; the ungraceful ones are not. `ProcessorRegistry#evict` does
`updateState(OFFLINE)` + `unregister` and nothing else, so a crashed worker's tasks sit `RUNNING`
until `LeaseReaper` sweeps a full lease interval later — even though the registry knows the node id
at that moment and `PipelineNodeTaskDao#countLeasedBy` already keys on it. Task 11 landed the
machinery (`loadLeasedBy`, `reclaimWorker`, driven from `ProcessorPresenceReaper` on
silence-eviction); only the socket-close wiring is missing. Decide the accounting first —
`onNodeTaskLost` for a vanished worker vs. the `onNodeTaskReturned` attempt refund for a clean
close — and keep both paths idempotent against a concurrent reaper sweep. Interrupted **source**
enumerations still have no reclaim path at all; the owning task names the two acceptable shapes.

**Why here.** Mostly-landed machinery with a small wiring gap, and the user-visible symptom is a
ten-minute stall after every worker crash. Independent of everything above.

---

## Just below the line

Ranked, and each is a legitimate substitute if the entry above it is blocked on a decision:

11. [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 8 — X5/X6/X7 in one change; degrades five of the six
    shipped review modes (profiles lost on reload, "the first 20 assets" instead of a queue, no
    resumable review record).
12. [SHARE_TASKS.md](SHARE_TASKS.md) Tasks 1 → 2 → 3 — the server collects customer feedback and the
    bell announces it, and there is nowhere in the product to read it; six typed client functions are
    already written and called by nothing.
13. [PIPELINE_TASKS.md](PIPELINE_TASKS.md) Task 13 — the last engine meter. Cheap, and
    [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Tasks 13, 14 and 15 were all
    deferred *pending measurement*; without it they would be tuned blind.
14. [NODE_DATA_TYPES_TASKS.md](NODE_DATA_TYPES_TASKS.md) Task 7 — result reuse is hard-coded to
    element 0, which is wrong under fan-out.
15. [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 6 — one migration comment and two DTO fields; an
    hour, and the `detection.type` string stops drifting a second time.
16. [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) Task 9 — the unreferenced-testid ratchet. Land it before any
    of the E2E batches (Tasks 21–31), because it is what stops the backlog growing behind them.
17. [NODE_TASKS.md](NODE_TASKS.md) Task 21 — the sibling defect the failure-reporting sweep
    surfaced: 24 `persist(...)` catch blocks record a `FAILED` ledger row and let the node return
    SUCCESS, so a green node's result was never durably stored.
18. [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) Tasks 16, 17 and 13, plus Task 14's remaining steps 5–6 —
    all unblocked by the landed UI failure path; the a11y sweep and the admin component specs can
    now assert on feedback that exists.

## Deliberately not ranked

* **Elasticsearch Phase 2** ([SEARCH_ELASTICSEARCH.md](SEARCH_ELASTICSEARCH.md) Tasks 11–15, 23) —
  assessed and deferred by decision, with a written revisit trigger. Read §0 before proposing it.
* **The greenfield workflows** ([WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Tasks 10–14) — proposals whose
  own spec files carry their build order; the tasks there are entry points, not plans. Task 12
  depends on Task 4 and on the enforcement point #4 above also needs.
* **Spec re-baselines** ([WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) 16, [PIPELINE_TASKS.md](PIPELINE_TASKS.md)
  17, [NODE_SCHEMA_TASKS.md](NODE_SCHEMA_TASKS.md) 4, [DATABASE_TASKS.md](DATABASE_TASKS.md) 20–21) —
  do them *after* the code changes above, so each sweep records one state rather than two.
* **[WEBSITE_TASKS.md](WEBSITE_TASKS.md)**, **[LOOM_UI_UPLOAD_TASKS.md](LOOM_UI_UPLOAD_TASKS.md)** and
  the [IMAGEGEN_NODE.md](IMAGEGEN_NODE.md) open items — real work, none of it blocking and none of it
  producing a wrong answer today. The imagegen live-GPU smoke test is the exception worth scheduling
  opportunistically: it is the only unverified boundary in an otherwise shipped node.
* **[METALOOM_NOTES.md](METALOOM_NOTES.md)** — scratch backlog by design. Its two verified unowned
  defects are covered (the failure-reporting defect landed 2026-08-18 as
  [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 17; the dedup keeper-role rewrite lives under
  [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 3); the demo-seeding path having no test anywhere is
  still unowned and belongs in [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 15.

---

_Git HEAD revision: `daefc256`_
_Last updated: 2026-08-20 (removed the two DONE entries — failure reporting and the UI failure
path, both landed 2026-08-18 — re-ranked 1–8, promoted PIPELINE 15+16 and ARCH 2 to ranks 9–10).
Earlier: 2026-08-18 (cross-file ranking of spec/tasks/)_
