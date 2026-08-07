# Workflow: AI Output Review — Approve, Edit or Reject Machine-Written Text

> **Status**: 🔵 **Proposal.** The producers are all built and write to the database; the review screen
> exists as `LLMMode` and is **100% mock** — a hardcoded string keyed on three demo asset ids. Nothing
> records whether a machine-written value was ever checked by a person.
> **Complexity**: **simple.** No new table is strictly required; the smallest useful version is a
> status column and a text field on values that already exist.
> **Scope**: the human check on every text a model wrote — captions, descriptions, transcripts,
> translations, OCR text, document summaries.
> **Audience**: AI coding agents working on `loom/db/flyway`, `loom/services/rest` and
> `loom-ui/src/features/workflow/`.

Family index and shared anatomy: [WORKFLOWS.md](WORKFLOWS.md). Status legend: 🟢 built · 🟡 partly
built · 🔵 plan · 🔴 defect · ⚪ stub.

**Out of scope, and where it lives instead:**

| Not here | There |
|---|---|
| The producers: `captioning`, `vlm`, `llm`, `whisper`, `translate`, `ocr`, `tika`, `sentiment` | [../features/nodes/NODES.md](../features/nodes/NODES.md) and the per-node plans in [../concept/](../concept/) |
| Chatting with the agent about an asset | [../loom/ui/CHAT.md](../loom/ui/CHAT.md) — deliberately not a workflow ([WORKFLOWS.md](WORKFLOWS.md) §0.1) |
| Safety verdicts (`guard`) | [WORKFLOW_SAFETY_TRIAGE.md](WORKFLOW_SAFETY_TRIAGE.md) — a different decision with a different consequence |
| Marking exported files as AI-generated (C2PA, IPTC) | [../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md), [WORKFLOW_RIGHTS_RELEASE.md](WORKFLOW_RIGHTS_RELEASE.md) |

---

## 1. Why this is worth building

MetaLoom writes a lot of prose that no human has read. Seven node kinds produce free text today, and
every one of them is fallible in a way that a person spots in under two seconds:

| Producer | Writes | Typical failure a reviewer catches instantly |
|---|---|---|
| `captioning` / `vlm` | Image and video captions | Confident hallucination — objects that are not there |
| `whisper` | Transcripts (`asset_transcript_comp`) | 🔴 Empty transcript reported as success. Every video in the local test corpus is silent, and whisper cheerfully returns nothing |
| `translate` | Translated text | Names and product terms mangled |
| `ocr` | Text in images | Confusable glyphs, wrong reading order |
| `tika` | Document body text | 🔴 The parser currently returns `null` |
| `llm` | Arbitrary derived text | Prompt drift after a model or template change |
| `sentiment` | A class + score | Irony, negation |

**No consumer distinguishes checked from unchecked text.** Search ranks a hallucinated caption exactly
like a curated one; an export carries both; the agent cites both.

---

## 2. Design

### 2.1 The smallest thing that works

Reuse the shared `review_status` enum proposed in
[WORKFLOW_OBJECT_DETECT.md](WORKFLOW_OBJECT_DETECT.md) §2.1 — decided once, used by three workflows.

The values live in different tables (`asset_transcript_comp`, `asset_json_comp`, and the generic comp
tables reworked by `V2.38`-`V2.42`). Rather than adding four near-identical column sets, add **one**
review table keyed by the ledger row that already identifies every machine-written value:

```sql
-- asset_node_result (V2.45) already records: asset, node_kind, node_id, producer_version, result_ref
CREATE TABLE "node_result_review" (
    "uuid"            uuid NOT NULL DEFAULT uuid_generate_v4(),
    "result_uuid"     uuid NOT NULL REFERENCES "asset_node_result" ("uuid") ON DELETE CASCADE,
    "status"          review_status NOT NULL DEFAULT 'PENDING',
    "corrected_text"  text,
    "note"            varchar,
    "reviewed_at"     timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "reviewer_uuid"   uuid NOT NULL REFERENCES "user" ("uuid"),
    CONSTRAINT "node_result_review_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "node_result_review_key" UNIQUE ("result_uuid", "reviewer_uuid")
);
CREATE INDEX "idx_node_result_review_status" ON "node_result_review" ("status");
```

| Choice | Why |
|---|---|
| Hang the review off `asset_node_result`, not off each comp table | One table instead of seven, and the ledger already carries `producer_version` — so a review is automatically scoped to *the answer that was reviewed*, and a re-run under a new version is correctly unreviewed again |
| `corrected_text` beside the original, never overwriting it | A correction is a second opinion, not an erasure. The original is the signal that tells you the model is drifting |
| `UNIQUE (result_uuid, reviewer_uuid)` | Two reviewers may disagree; that is information, not a conflict |
| `reviewer_uuid` `NOT NULL` | Unlike the producer tables, a review has no machine author by definition |

🔴 Prerequisite: the ledger is not fully populated. `asset_node_result.result_ref` is null for several
producers (`FacedetectNode` writes one; others pass `null`), and `origin` is hard-coded `COMPUTED`. A
review keyed to a ledger row needs the row to point at the value. That is a small, independently
useful fix — see [../tasks/METALOOM_NOTES.md](../tasks/METALOOM_NOTES.md) "Complete the node
provenance record".

### 2.2 Consequences of a decision

| Decision | Effect |
|---|---|
| `CONFIRMED` | The text is trusted: boosted in ranking, eligible for export, quotable by the agent |
| `CONFIRMED` + `corrected_text` | The correction supersedes the original for every consumer; the original stays for drift analysis |
| `REJECTED` | Excluded from `search_document`, from export, and from the agent's context. **Not deleted** — a rejected caption is the evidence that this model/prompt combination is unreliable |

⚠️ Excluding rejected text from search means the `search_document` trigger set (`V2.57`-`V2.59`) has to
learn about review state. That is the largest single piece of work in this proposal and the reason the
"simple" label applies to the review loop, not to full consumer integration. Ship the loop first;
integrate consumers behind it.

### 2.3 The screen

`LLMMode` (`WorkflowView.tsx:572-621`) already has the right shape — media on top, a model-output card
with a model chip, approve/reject buttons, an `r` "re-run prompt" binding. What it lacks is data and a
write path:

```ts
// WorkflowView.tsx:583 — the current "model output"
const mockResult = asset.id === "a1" ? "Corporate presentation scene with modern furniture..." : ...
```

| # | Change | Notes |
|---|---|---|
| 1 | Fetch real text: `GET /assets/:uuid/node-results` filtered to text-producing kinds | The route exists |
| 2 | Show the real `node_kind` + `producer_version` in the chip instead of the hardcoded `gpt-4o` | The version is what makes a stale review recognisable |
| 3 | `Y`/`N` POST a review, batched, with rollback | Same batching argument as [WORKFLOW_OBJECT_DETECT.md](WORKFLOW_OBJECT_DETECT.md) §2.2 |
| 4 | An **edit** action — the third answer, and the most valuable one | A `TextField` seeded with the original; `Escape` cancels |
| 5 | Implement or remove the `r` "re-run prompt" binding | It is currently a `case` with an empty body (`:899`) — a bound key that does nothing is worse than an unbound one |
| 6 | Queue on `status=PENDING`, ordered by producer confidence where one exists | Shared defect X6 |

---

## 3. Progress Assessment

- [x] Seven text-producing node kinds, all writing typed components plus a ledger row
- [x] `LLMMode` layout, `llm-default` key profile (`Y`/`N`/`r`)
- [x] `asset_node_result` ledger with `node_kind`, `node_id`, `producer_version` (`V2.45`)
- [ ] 🔴 Complete the ledger: populate `result_ref`, a real `origin`, `run_uuid`/`task_uuid`
- [ ] 🔵 `review_status` enum (shared) + `node_result_review` table + DAO + contract and cascade tests
- [ ] 🔵 `POST /assets/:uuid/node-results/:uuid/review` + a bulk variant + a cross-asset PENDING queue
- [ ] 🔵 `REVIEW` permissions, or reuse `UPDATE_ASSET` — decide explicitly
- [ ] 🔵 UI: real text, real model chip, edit action, batched writes, PENDING queue (§2.3)
- [ ] 🔵 Remove or implement the dead `rerun_llm` binding
- [ ] 🔵 Consumers: exclude `REJECTED` text from `search_document`, from export, from agent context
- [ ] Mocked Playwright e2e; demo data with one PENDING and one CONFIRMED result
- [ ] Customer docs

---

## 4. Test Setup

| Test | Covers |
|---|---|
| `NodeResultReviewDaoTest` 🔵 | Round-trip, `listByStatus`, two reviewers on one result, cascade when the ledger row or the asset is deleted |
| `NodeResultReviewEndpointTest` 🔵 | 200 + 403 without permission, bulk partial failure, invalid status 400, unknown uuid 404, a re-run under a new `producer_version` leaves the old review in place and the new value `PENDING` |
| `SearchDocumentReviewTest` 🔵 | A `REJECTED` result is not indexed; confirming re-indexes it |
| `workflow-llm-mocked.spec.ts` 🔵 | Real text rendered, `Y` posts, edit posts `correctedText`, failed post reverts |

```bash
mvn -pl loom/db/jooq test -Dtest=NodeResultReviewDaoTest
mvn -pl loom/core test -Dtest=NodeResultReviewEndpointTest
cd loom-ui && ./node_modules/.bin/playwright test e2e/workflow-llm-mocked.spec.ts
```

🔴 `./setup-pool.sh` before DAO/endpoint tests and after the Flyway change; install `loom/db/flyway`
first. ⚠️ `npx` stalls — use `./node_modules/.bin/`.

---

## 5. Configuration

| Variable | Effect |
|---|---|
| `LOOM_AI_ENABLED` / `_PROVIDER_TYPE` / `_URL` / `_MODEL_ID` | The producer side. Off ⇒ no queue |
| `LOOM_SEARCH_ENABLED` | Gates the consumer half (§2.2) |
| `CORTEX_NODE_WHITELIST` / `_BLACKLIST` | Must permit the text-producing kinds |

No new variable. Review state is data, not configuration.

---

## 6. Key Classes Reference

| Class / file | Package or path | Purpose |
|---|---|---|
| `LLMMode` | `loom-ui/src/features/workflow/WorkflowView.tsx:572` | 🔴 The mock screen; `mockResult` at `:583` |
| `AssetEndpoint` `/node-results` | `io.metaloom.loom.rest.endpoint.impl` | Where ledger rows are written and read |
| `AbstractMediaNode.recordNodeResult` / `resultRef` | `io.metaloom.cortex.common.node` | The ledger convention; `WhisperNode` is the reference producer |
| `CaptioningNode`, `VlmNode`, `LlmNode`, `TranslateNode`, `WhisperNode`, `OcrNode`, `TikaNode` | `io.metaloom.cortex.node.*` | The producers |
| `PostgresSearchProvider` | `io.metaloom.loom.db.jooq.search` | The consumer that must learn about review state |
| `DedupGroupEndpointService` | `io.metaloom.loom.rest.service.impl` | The review-endpoint pattern |

---

## 7. Conventions and Gotchas

| Area | Gotcha |
|---|---|
| **Never overwrite the original** | 🔴 A correction is a second value. The original is the drift signal |
| **A review is scoped to a `producer_version`** | 🟢 Hanging the review off the ledger row gets this for free: re-running under a new version yields an unreviewed value, as it should |
| **`result_ref` is often null** | 🔴 Several producers pass `null`; the review key depends on it |
| **`ctx.failure(...).next()` returns SUCCESS** | 🔴 A producer reporting success on failure creates an empty "result" for a human to review |
| **Whisper succeeds with an empty transcript** | 🔴 Every video in the local test corpus is silent; a success with no text is the normal case there, not an anomaly |
| **Tika's parser returns null** | 🔴 Known open defect — document text has no producer today |
| **`origin` is hard-coded `COMPUTED`** | ⚠️ So the ledger cannot yet distinguish computed from imported from human-corrected |
| **Batch the writes** | ⚠️ Keyboard review outruns per-item round trips |

---

## 8. Where do I find …?

| Need | Look here |
|---|---|
| The mock to replace | `loom-ui/src/features/workflow/WorkflowView.tsx:572` |
| The ledger | `loom/db/flyway/.../V2.45__add_asset_node_result.sql`; `AbstractMediaNode.recordNodeResult` |
| The reference producer | `cortex/nodes/whisper/.../WhisperNode.java` |
| The shared review enum proposal | [WORKFLOW_OBJECT_DETECT.md](WORKFLOW_OBJECT_DETECT.md) §2.1 |
| Search indexing | `V2.57`-`V2.59`; [../features/search/SEARCH.md](../features/search/SEARCH.md) |
| Provenance gaps | [../tasks/METALOOM_NOTES.md](../tasks/METALOOM_NOTES.md) |
| Open tasks | [../tasks/WORKFLOW_TASKS.md](../tasks/WORKFLOW_TASKS.md) W9 |

---

_Git HEAD revision: `21e8a8cd`_
_Last updated: 2026-08-07 (new file — proposal; verified LLMMode is fully mocked)_
