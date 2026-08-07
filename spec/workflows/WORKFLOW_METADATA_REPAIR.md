# Workflow: Metadata Repair — Find and Fix What the Files Got Wrong

> **Status**: 🔵 **Proposal.** The `metadata` node reads EXIF/GPS/IPTC/XMP into Dublin Core and lands
> it in `asset_json_comp` + `asset_geo_comp` + search. Nothing detects that a value is *wrong*, and
> nothing lets a human fix one in bulk.
> **Complexity**: **medium.** No new node is required for the read side; the write-back half depends
> on a concept that is not built.
> **Scope**: surfacing assets whose dates, locations, or rights fields are missing, contradictory or
> implausible, and correcting them in one keyboard-driven pass.
> **Audience**: AI coding agents working on `cortex/nodes/metadata`, `loom/services/rest` and
> `loom-ui/src/features/workflow/`.

Family index and shared anatomy: [WORKFLOWS.md](WORKFLOWS.md). Status legend: 🟢 built · 🟡 partly
built · 🔵 plan · 🔴 defect · ⚪ stub.

**Out of scope, and where it lives instead:**

| Not here | There |
|---|---|
| What the `metadata` node extracts, its precedence rules and its privacy policy | [../features/nodes/metadata/METADATA_OVERVIEW.md](../features/nodes/metadata/METADATA_OVERVIEW.md) |
| Writing metadata **back into files** (sidecars, embedded copies, C2PA) | [../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md) — concept, nothing built |
| Licence and rights as a *release gate* | [WORKFLOW_RIGHTS_RELEASE.md](WORKFLOW_RIGHTS_RELEASE.md) |
| Reviewing model-written text | [WORKFLOW_AI_REVIEW.md](WORKFLOW_AI_REVIEW.md) |
| The comp-table schema | [../loom/DOMAIN.md](../loom/DOMAIN.md), `V2.38`-`V2.42` |

---

## 1. The problem, concretely

A real corpus arrives with metadata that is present, plausible and wrong. The classes are known and
each is cheaply detectable:

| Class | Detection | Frequency in a real archive |
|---|---|---|
| **Missing capture date** | No EXIF `DateTimeOriginal`; only a filesystem mtime that reflects the last copy | Very common in re-encoded or exported media |
| **Impossible date** | `1970-01-01`, `2038`, a date after `first_seen`, a scan date on a photo of a 1950s print | Common |
| **Timezone-less timestamps** | EXIF dates carry no zone; two cameras in different zones interleave wrongly on a timeline | Universal |
| **Missing or implausible GPS** | `0,0`; a point in the ocean; a location contradicting a caption or an OCR'd sign | Common |
| **Contradictory sources** | EXIF says one date, XMP another, IPTC a third. The node applies precedence and the loser disappears silently | Common |
| **Missing rights** | No creator, no licence, no `copyright` — the fields that decide whether an asset may be used | Very common |
| **Character-set damage** | Mojibake in IPTC captions from legacy encodings | Occasional |

🔴 **Precedence is applied and then forgotten.** The node resolves EXIF vs IPTC vs XMP into one value.
When a human sees a wrong date, the losing candidates — often the correct ones — are no longer visible.

⚠️ Related known trap: Tika's `ImageMetadataExtractor` merges EXIF/IPTC/XMP and applies *its own*
precedence, which is why the metadata path uses `metadata-extractor` directly. Do not reintroduce
Tika on this path.

---

## 2. Design

### 2.1 Detection: rules, not a model

A `metadata-audit` capability that flags an asset as needing review. The cheapest implementation is
**not** a new node — it is a set of SQL-backed queries behind a REST route, because every input is
already in Postgres (`asset.first_seen`, `asset_json_comp`, `asset_geo_comp`, `asset_location`).

| Rule id | Condition |
|---|---|
| `NO_CAPTURE_DATE` | No Dublin Core `date` and no EXIF original date |
| `IMPLAUSIBLE_DATE` | Date before 1826 (the first photograph), after `now()`, or after `first_seen` |
| `NO_TIMEZONE` | A capture date with no zone offset |
| `NULL_ISLAND` | `geo_lat = 0 AND geo_lon = 0` |
| `SOURCE_CONFLICT` | Two metadata sources disagree by more than a tolerance |
| `NO_RIGHTS` | No creator and no licence |

Each rule yields `(assetUuid, ruleId, severity, observed, suggested?)`. Rules live in **one** place —
adding a rule must not require touching the endpoint, mirroring the `FilterStrategy` /
`TagStrategy` seam pattern this codebase already uses twice.

🔵 Do consider a `metadata-audit` **node** later: it can compare metadata against what the *pixels*
say (a `captioning` result mentioning snow against a July date; an OCR'd sign against a GPS point).
That is genuinely a node's job. Rules first.

### 2.2 Preserving the losing candidates

For a human to repair a value, they need to see what was discarded. `asset_json_comp` holds the
envelope; the node should record **all** candidates, not only the winner:

```json
{ "date": { "value": "2019-07-04T10:12:00", "source": "exif:DateTimeOriginal",
            "candidates": [ {"value": "2019-07-04T10:12:00", "source": "exif:DateTimeOriginal"},
                            {"value": "2021-03-02T09:00:00", "source": "xmp:CreateDate"} ] } }
```

⚠️ This changes the envelope contract, which is specified in
[../features/nodes/metadata/METADATA_OVERVIEW.md](../features/nodes/metadata/METADATA_OVERVIEW.md).
Update that spec in the same change — the code wins on conflict, and a silently diverged envelope is
worse than no candidates.

### 2.3 Repair, and where the correction lives

A correction must not overwrite what the file said. Three layers, in order:

| Layer | Holds | Authority |
|---|---|---|
| The file | What the camera or the exporter wrote | Evidence |
| `asset_json_comp` (machine) | The node's resolved value + candidates | Derived |
| 🔵 A human correction | The reviewer's value + who and why | Wins for every consumer |

The correction layer can reuse `node_result_review` from
[WORKFLOW_AI_REVIEW.md](WORKFLOW_AI_REVIEW.md) §2.1 (a `corrected_text` against a ledger row) if it
lands first — one mechanism for "a human overruled a machine value", used by two workflows. If it does
not, a small `asset_metadata_correction` table with `(asset_uuid, field, value, reviewer_uuid,
reason, created)` is the fallback. **Decide before building either.**

🔴 Writing the correction back *into the file* is [../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md)
and is not built. This workflow is complete without it: a correction in Loom is already authoritative
for search, export and the API. Do not block on the file half.

### 2.4 The screen

A `"metadata"` mode: the asset on the left; on the right a compact field list, each row showing the
value, its source, the flagged rule and the discarded candidates as one-click alternatives. Keys:
`1`-`9` accept the nth candidate, `e` edit freely, `n` skip, `→` next.

**Bulk is the point.** "Every asset in this folder is off by exactly seven hours" is the common case,
so the mode needs a *set* action: apply the same correction to the whole filtered queue, with a
preview and an undo. A one-at-a-time repair screen is not worth building.

---

## 3. Progress Assessment

- [x] `metadata` node: EXIF/GPS/IPTC/XMP → Dublin Core → `asset_json_comp` + `asset_geo_comp` + search
- [x] `asset_geo_comp`, `asset_json_comp` schema (`V2.40`, `V2.65` search integration)
- [ ] 🔵 Rule set + `GET /api/v1/assets/metadata-issues?rule=&severity=` behind a strategy seam (§2.1)
- [ ] 🔵 Preserve losing candidates in the envelope; update [../features/nodes/metadata/METADATA_OVERVIEW.md](../features/nodes/metadata/METADATA_OVERVIEW.md) in the same change (§2.2)
- [ ] 🔵 Decide the correction store: reuse `node_result_review` or add `asset_metadata_correction` (§2.3)
- [ ] 🔵 Correction endpoint, single and **bulk-over-a-query**, with preview and undo
- [ ] 🔵 `"metadata"` mode in `WorkflowView` with candidate keys and a set action (§2.4)
- [ ] 🔵 Consumers honour the correction layer: search, API responses, export
- [ ] 🔵 Stretch: a `metadata-audit` node cross-checking metadata against pixels and text
- [ ] Deferred: write corrections back into files ([../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md))
- [ ] Mocked Playwright e2e; demo data with a null-island and an impossible-date asset
- [ ] Customer docs

---

## 4. Test Setup

| Test | Covers |
|---|---|
| `MetadataRuleTest` 🔵 | Each rule against fixtures: boundary dates, `0,0`, missing rights, source conflict within and beyond tolerance |
| `MetadataIssueEndpointTest` 🔵 | Filtering by rule and severity, paging, 403 without `READ_ASSET` |
| `MetadataCorrectionEndpointTest` 🔵 | Single and bulk correction, the correction wins over the machine value, undo restores, 403 without permission |
| `MetadataEnvelopeCandidatesTest` 🔵 | Candidates preserved; the winner still matches the documented precedence |
| `workflow-metadata-mocked.spec.ts` 🔵 | Candidate keys apply the right value; the set action previews before writing |

⚠️ **Generate fixture bytes in test code.** `/opt/metaloom/loom-testdata` is outside git and its images
carry no EXIF, so a test that expects EXIF on a corpus file will fail for reasons unrelated to the
code. 🔴 `./setup-pool.sh` before DAO/endpoint tests and after any Flyway change.

```bash
mvn -pl cortex/nodes/metadata/core -am test
mvn -pl loom/core test -Dtest=MetadataIssueEndpointTest
```

---

## 5. Configuration

| Variable | Effect |
|---|---|
| `LOOM_SEARCH_ENABLED` | The issue queue is most useful as a search facet; off ⇒ the rule route still works, the facet does not |
| `CORTEX_NODE_WHITELIST` / `_BLACKLIST` | Must permit `metadata` |

Proposed rule thresholds (`SOURCE_CONFLICT` tolerance, the plausible date range) belong in node/service
options, not environment variables — they are policy per corpus.

---

## 6. Key Classes Reference

| Class / file | Package or path | Purpose |
|---|---|---|
| `MetadataNode` | `io.metaloom.cortex.node.metadata` | The extractor; owns the precedence rules |
| `asset_json_comp` / `asset_geo_comp` DAOs | `io.metaloom.loom.db.*` | Where the resolved values live |
| `PostgresSearchProvider` | `io.metaloom.loom.db.jooq.search` | Consumer that must honour corrections |
| `FilterStrategy` / `TagStrategy` | `io.metaloom.cortex.node.{filter,tag}` | The strategy-seam pattern the rule set should copy |
| `WorkflowView` | `loom-ui/src/features/workflow/WorkflowView.tsx` | Where the mode is added |

---

## 7. Conventions and Gotchas

| Area | Gotcha |
|---|---|
| **Never overwrite the file's value** | 🔴 Three layers: file (evidence), machine (derived), human (authoritative). Collapsing them destroys the audit trail |
| **Tika flattens image metadata** | 🔴 `ImageMetadataExtractor` merges EXIF/IPTC/XMP with its own precedence. This path uses `metadata-extractor` directly — do not "simplify" it back |
| **Test corpus has no EXIF** | ⚠️ `/opt/metaloom/loom-testdata` is unversioned and its images carry none. Generate fixture bytes in test code |
| **Precedence must stay documented** | ⚠️ [../features/nodes/metadata/METADATA_OVERVIEW.md](../features/nodes/metadata/METADATA_OVERVIEW.md) is the only place the rules are written down. Changing the envelope changes that spec |
| **Bulk needs preview and undo** | 🔴 A set correction applied to a mis-scoped query is the most damaging action in this whole spec family |
| **GPS is privacy-sensitive** | ⚠️ The metadata spec carries a privacy policy. A repair screen showing every location of every asset is a new exposure surface — check it against that policy |
| **`0,0` is a value, not a null** | ⚠️ Null Island is a real coordinate; treat it as a flag, never silently blank it |

---

## 8. Where do I find …?

| Need | Look here |
|---|---|
| What is extracted and how precedence works | [../features/nodes/metadata/METADATA_OVERVIEW.md](../features/nodes/metadata/METADATA_OVERVIEW.md) |
| The node | `cortex/nodes/metadata/` |
| The comp tables | `loom/db/flyway/.../V2.38`-`V2.42`, `V2.65`; [../loom/DOMAIN.md](../loom/DOMAIN.md) |
| Writing metadata back into files | [../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md) |
| The strategy seam to copy | `cortex/nodes/filter/core/.../FilterStrategy.java` |
| The correction-store candidate | [WORKFLOW_AI_REVIEW.md](WORKFLOW_AI_REVIEW.md) §2.1 |
| Open tasks | [../tasks/WORKFLOW_TASKS.md](../tasks/WORKFLOW_TASKS.md) W11 |

---

_Git HEAD revision: `21e8a8cd`_
_Last updated: 2026-08-07 (new file — proposal)_
