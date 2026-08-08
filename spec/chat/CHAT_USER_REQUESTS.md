# MetaLoom // Agentic Chat — User Requests and Capability Assessment

> A catalogue of what people will actually type into the Loom chat, and for each one: the
> interaction it implies inside MetaLoom, and what is missing today.
>
> **Purpose.** This file exists to *find the open spots*. It is deliberately written from the user's
> side rather than the architecture's, so that capabilities nobody has specified yet surface as
> concrete missing pieces instead of staying invisible.
>
> **Context:** [AGENTIC_CHAT_PLAN.md](AGENTIC_CHAT_PLAN.md) (the vision and the gap map) ·
> [AGENTIC_CHAT_CONTEXT_DATA.md](AGENTIC_CHAT_CONTEXT_DATA.md) (how metadata reaches the model) ·
> [LOOM_UI_CHAT.md](LOOM_UI_CHAT.md) (the built loop) · [MCP.md](../loom/MCP.md) (the tool surface) ·
> [NODES.md](../features/nodes/NODES.md) (what each node computes and where it lands).

## 0. How to read this

| Verdict | Meaning |
|---|---|
| `NOW` | Answerable with the tools that exist at this revision |
| `P1` | Needs only the retrieval + comprehension work — [AGENTIC_CHAT_PLAN.md §9](AGENTIC_CHAT_PLAN.md) phase 1 |
| `P2` | Needs P1 plus a UI surface (asset visuals, working set) |
| `P3` | Needs a resolver: place names or a label taxonomy |
| `P4` | Needs catalog write tools |
| `P5` | Needs ad-hoc node execution, the async job model, or byte ingest for produced media |
| `P6` | Needs vision in the loop, or semantic/vector search |
| `NEW` | Needs a capability that does not exist anywhere in MetaLoom yet — a node, a table or a subsystem. **These are the open spots.** |

Every `NEW` item is collected in §14.

---

## 1. Progress Assessment

- [x] Requests catalogued across twelve categories (§2–§13)
- [x] The four seed prompts worked end to end (§2.1, §4.1, §4.2, §9.1)
- [x] Missing capabilities extracted and ranked by how many requests need them (§14, §15)
- [ ] No request in this file is served end to end today beyond the trivial ones in §3
- [ ] `NEW` items N1–N14 have no owning spec yet — several deserve one (§14)

---

## 2. Catalog awareness and housekeeping

The questions a user asks in the first thirty seconds. They look trivial and mostly are not.

### 2.1 Worked example — *"What has last been ingested?"*

What it takes:

1. Resolve "ingested" — `asset.created` (row creation) or `asset.first_seen` (first observation on
   a filesystem)? They differ for a re-scan. The agent must pick one and *say which*.
2. Sort descending, limit ~10, project filename, mime, size, time, thumbnail.
3. Render as a grid with thumbnails, not a JSON blob.

What is missing:

- `search_assets` accepts `query` and `mimeType` and **ignores both**; it calls
  `assetDao.loadPage(null, limit, …)`. There is no sort parameter at all ([MCP.md §5.1](../loom/MCP.md)).
- `SearchSortMode.NEWEST` exists in the SPI and no MCP tool exposes it.
- The chat cannot render a thumbnail — the only visual type is `pipeline-graph`.

Verdict: **`P1`** for the answer, **`P2`** for an answer that looks like a DAM.

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 1 | "What has last been ingested?" | sort by created desc + thumbnail grid | `P1`/`P2` |
| 2 | "How many assets do we have, and how much storage?" | `asset_statistics` — exists, but loads 10 000 rows into memory and ignores its `collection` parameter | `NOW` (badly) / `P1` (properly) |
| 3 | "What arrived this week that nothing has processed yet?" | anti-join `asset` against `asset_node_result` | `P1` + `NEW N1` (a coverage query) |
| 4 | "Which assets failed processing, and why?" | `asset_node_result` where `state='FAILED'`, group by `node_kind`, read `reason` | `P1` + `NEW N1` |
| 5 | "Is anything stuck right now?" | live `pipeline_run` + `pipeline_node_task` state | `P1` (a run-status tool) |
| 6 | "What changed in the catalog since yesterday?" | a diff over `edited` across assets and comps | `NEW N2` — no change feed exists |
| 7 | "Which of my uploads are still missing transcripts?" | coverage query scoped by creator | `P1` + `NEW N1` |
| 8 | "How much of the library has been face-indexed?" | `asset_node_result` coverage by `node_kind` and `producer_version` | `P1` + `NEW N1` |

**Open spot N1 — a coverage/gap query surface.** `asset_node_result` was built precisely to answer
"has node X at version V processed asset A" and nothing queries it. This is the single cheapest new
tool in this document and it makes the agent genuinely useful to an operator on day one.

---

## 3. What works today

For honesty, the complete list of requests the shipped agent handles end to end:

| Request | How |
|---|---|
| "What pipelines exist?" | `list_pipelines` |
| "Show me the transcription pipeline" | `get_pipeline` — text, a chip and an inline graph |
| "What node kinds can I use? What options does `whisper` take?" | `list_node_descriptors`, `get_node_descriptor` (resolved ports) |
| "Build me a pipeline that hashes and thumbnails everything" | `pipeline_authoring_guide` → `validate_pipeline` → `create_pipeline` |
| "Roughly how many assets are there?" | `asset_statistics` |
| "Remember that our client prefers 16:9 crops" | `put_memory` (when `LOOM_AGENT_MEMORY_ENABLED=true`) |
| "Write me a script that renames these files" | sandbox coding tools (when enabled) |

Everything else in this document is aspiration.

---

## 4. Retrieval — place, time and content

### 4.1 Worked example — *"Where was this asset filmed?"*

What it takes: read `asset_geo_comp` for the asset (possibly several rows — a drone video carries a
whole GPS track, keyed by `method` and `time_from`), pick or summarize, and translate coordinates
into a place a human recognizes.

What is missing:

- **No tool reads any component table.** `get_asset` returns asset columns only.
- `asset_geo_comp.geo_alias` is a column with **no producer** — nothing reverse-geocodes.
- For video, the answer is a *track*, not a point: "starts near X, ends near Y" needs a summarizer,
  not a row dump.

Verdict: **`P1`** to answer "48.1845, 16.3122"; **`P3`** to answer "Schönbrunn Palace gardens,
Vienna".

### 4.2 Worked example — *"Find me assets from Vienna Schönbrunn that show animals"*

The hardest of the seed prompts, because it needs three things Loom cannot do:

1. **Place name → geometry.** "Vienna Schönbrunn" must become a bounding box or a centre + radius.
   No gazetteer, no geocoder, and `geo_alias` is empty. `NEW N3`.
2. **A geo filter in search.** `asset_geo_comp` has a `(geo_lon, geo_lat)` index, but
   `search_document` carries no geo columns and `SearchRequest` has no bbox/radius parameter, so
   geo and text cannot be combined in one query. `NEW N4`.
3. **"animals" → labels.** `objectdetect` writes `detection.label` (indexed, and folded into
   `search_document.keywords`), so a search for "dog" works. "animals" matches nothing — there is no
   hypernym expansion. `NEW N5`.

A partial answer is available sooner: caption and VLM text often literally says "a deer in a park",
and that text *is* indexed (`search_extract_json_text` covers `caption`, `video-caption`, `vlm`,
`ocr`, `tika`, `metadata`, `face-description`). So lexical search over descriptions gets surprisingly
far — which is an argument for landing P1 before the resolvers.

Verdict: **`P3`** for a correct answer; **`P1`** for a decent one; **`P6`** for "assets that *look
like* a zoo" without any of the above.

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 9 | "Photos from our Japan trip in 2023" | geo cluster + date range | `P3` + `NEW N4` |
| 10 | "Everything shot within 500 m of this asset" | radius query around another asset's coordinates | `NEW N4` |
| 11 | "Group last month's photos by the city they were taken in" | reverse geocode + group-by | `NEW N3` |
| 12 | "Videos where somebody says 'quarterly results'" | transcript phrase search | `P1` — `asset_transcript_comp` is indexed; `search_transcript` is a stub |
| 13 | "Assets mentioning our old brand name anywhere — OCR, transcript, filename or metadata" | cross-corpus lexical search | `P1` — `search_document` already unifies exactly these |
| 14 | "Find the shot where the camera pans across the harbour" | scene + caption + motion | `P6` + `NEW N6` (no motion/camera-movement descriptor anywhere) |
| 15 | "Pictures that feel like this one" | perceptual or semantic similarity | `PARTIAL` — `/assets/:uuid/similar-assets` (Lucene fingerprints) exists and no tool exposes it; true semantic is `P6` |
| 16 | "Everything blurry or underexposed" | `quality` json comp + `asset_image_comp.blurriness` | `P1` |
| 17 | "Portrait-orientation images over 4000 px wide" | `asset_image_comp` dimensions | `P1` |
| 18 | "Assets with no metadata at all" | absence query across comps | `P1` + `NEW N1` |
| 19 | "Find the two-minute stretch of this interview about pricing" | transcript segment search returning a **timecode**, not an asset | `NEW N7` — `SearchEntityType.TRANSCRIPT` and `SEGMENT` exist as enum values with no documents of their own |

**Open spot N7 — sub-asset retrieval.** Every retrieval path returns whole assets. For video and
long documents the useful answer is a *range*: "asset X, 04:12–06:30". `asset_segment_comp` and
`asset_transcript_comp` carry the timing; nothing surfaces it as a hit. This changes the shape of
the result type, so it is worth deciding early.

---

## 5. People and identity

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 20 | "Show me all the photos of Alice" | `person` → `cluster` → `embedding` → `detection` → `asset` | `P1` + `NEW N8` — the traversal has no tool, and [WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) reports there is **no clustering code** yet |
| 21 | "Who is in this picture?" | detections + cluster labels + confidence | `P1` (+ N8) |
| 22 | "Find the group shots where Alice and Bob both appear" | intersect two identities over one asset | `NEW N8` |
| 23 | "Is this the same person as in that other photo?" | face k-NN — `VectorIndex` is **built** | `P1` — the index exists, nothing exposes it as a tool |
| 24 | "Blur every face that is not a staff member" | detections + identity + a redaction node | `P5` + `NEW N9` — no redaction/blur-region node exists (`sam2` produces masks but ledger-only) |
| 25 | "Which assets contain people we have no consent record for?" | identity + a rights/consent model | `NEW N10` — consent has no schema. [WORKFLOW_RIGHTS_RELEASE.md](../workflows/WORKFLOW_RIGHTS_RELEASE.md) is the concept |

---

## 6. Understanding one asset

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 26 | "Tell me everything you know about this asset" | the **dossier** — every comp, rendered and capped | `P1`, and the central design question of [AGENTIC_CHAT_CONTEXT_DATA.md](AGENTIC_CHAT_CONTEXT_DATA.md) |
| 27 | "Summarize this hour-long interview" | transcript → summarize, likely map-reduce over segments | `P1` + `NEW N11` (no sub-agent / chunked summarization in the loop) |
| 28 | "What is the layout of this scene — what is in front of what?" | `scene-layout` json comp (12 spatial predicates) + `depthmap` | `P1` — computed and completely unread |
| 29 | "Why did this asset get tagged 'outdoor'?" | provenance: which node, which version, what confidence | `P1` — `V2.71` per-placement provenance makes this answerable |
| 30 | "Is anything wrong with this file?" | `consistency` + `quality` + failed ledger rows | `P1` |
| 31 | "Read me the text in this screenshot" | `ocr` json comp | `P1` |
| 32 | "What is the mood of this clip?" | `sentiment` (audio/text) — computed, unread | `P1` |
| 33 | "Does this video actually match its description?" | compare metadata against caption/VLM output | `P1`, better with `P6` vision |

Note how many of these are `P1`: **the expensive computation is already done and simply cannot be
read back.** That is the strongest argument for phasing retrieval and comprehension first.

---

## 7. Cross-asset analysis

Where an assistant beats a search box.

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 34 | "What are the recurring themes in last quarter's uploads?" | aggregate over captions/tags/labels, then cluster | `P1` + `NEW N11` |
| 35 | "Which photographer's work is technically strongest?" | join `quality` against creator/EXIF artist, aggregate | `P1` + `NEW N12` (no aggregate-over-comp tool) |
| 36 | "Show me the odd ones out in this collection" | outlier detection over embeddings or quality | `P6` |
| 37 | "How has our colour palette changed over three years?" | `dominant-color` comps over time, charted | `P1` + `NEW N12` + a chart visual type |
| 38 | "Which tags do we apply inconsistently?" | tag co-occurrence analysis | `NEW N12` |
| 39 | "Do we have duplicates wasting space, and how much?" | `dedup_group` + sizes — backend is built, the review UI is a mock | `P1` |
| 40 | "Which parts of the catalog would benefit most from re-processing?" | ledger coverage × `producer_version` drift × asset value | `NEW N1` |

**Open spot N12 — aggregation.** Every one of these is a `GROUP BY` the agent must not do by
pulling rows. A bounded `aggregate_assets(groupBy, metric, filter)` tool with a whitelisted set of
dimensions is a small, high-value addition — and the natural companion of a chart visual type.

---

## 8. Curation and action

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 41 | "Tag these as 'approved'" | `PUT /assets/:uuid/tags` with agent provenance | `P4` |
| 42 | "Make a collection called 'Best of Summer' from these" | collection create + membership | `P4` |
| 43 | "Assign the unreviewed ones to Maria" | `task` + `task_assignee` (`V2.69`) | `P4` |
| 44 | "Reject anything with a face under 80 % confidence" | filter + bulk write + **confirmation** | `P4` + the confirmation primitive |
| 45 | "Undo what you just did" | withdraw a machine write set by `node_id` prefix | `NEW N13` — no undo/withdrawal surface for agent writes |
| 46 | "Watch for new drone footage and tag it automatically" | a standing rule, not a chat turn | `NEW N14` — the agent has no way to leave a **persistent trigger** behind. `pipeline_version.meta.trigger` is the closest thing ([WORKFLOW_UPLOAD.md](../workflows/WORKFLOW_UPLOAD.md)) |
| 47 | "Rate these 1–5 as I look through them" | ratings are stored as reactions; nothing can filter on them ([WORKFLOW_MANUAL_SORT.md](../workflows/WORKFLOW_MANUAL_SORT.md) §5) | `P4` + the `FilterBy.RATING` gap |
| 48 | "Move everything from 2019 to cold storage" | `move` node — does not exist; cross-device moves silently copy ([WORKFLOW_TRASH.md](../workflows/WORKFLOW_TRASH.md)) | `P5` |

**Open spot N14 — standing rules.** "From now on, whenever X, do Y" is one of the most natural
things to say to an assistant, and MetaLoom has no representation for it that a chat can create.
This is arguably a bigger product idea than the chat itself: the agent as a **rule author** rather
than a rule executor. It composes cleanly with the curated-operations model in
[AGENTIC_CHAT_PLAN.md §6.3](AGENTIC_CHAT_PLAN.md).

---

## 9. Production — making something new

### 9.1 Worked example — *"Make a collage from some assets that feature birds"*

Five steps, four blockers:

| Step | Mechanism | State |
|---|---|---|
| 1. Find bird assets | label/caption/VLM retrieval | `P1`/`P3` (§4.2) |
| 2. Inspect or generate descriptions | read `vlm`/`caption` comps; run `vlm` on assets that lack one | read is `P1`; **run on demand is `P5`** — there is no ad-hoc node execution ([AGENTIC_CHAT_PLAN.md §6](AGENTIC_CHAT_PLAN.md)) |
| 3. Choose a selection | model judgement, ideally looking at thumbnails | `P6` — the loop is text-only |
| 4. Compose the collage | **no node composes several images into one.** `image-manipulation` is single-image ops; `imagegen` generates from a prompt, it does not lay out inputs | `NEW N15` — a `composite` / `contact-sheet` node |
| 5. Show and keep the result | produced bytes stay in the worker's `imagegen_bin`; Loom has no byte-ingest endpoint for produced media ([NODES.md §2.1](../features/nodes/NODES.md)); the chat has no image visual | `P5` + `P2` |

Verdict: **the furthest-away seed prompt**, and the one that exercises the most missing
architecture. It is a good acceptance test for the whole plan: when this works, tiers 1–4 all work.

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 49 | "Make a contact sheet of this shoot" | same as above, without the retrieval difficulty | `P5` + `NEW N15` |
| 50 | "Give me a 15-second highlight cut of this match" | scene detection + selection + **video assembly** | `NEW N16` — no editing/concat node |
| 51 | "Generate three thumbnail options for this video" | frame selection + crop + score | `P5` |
| 52 | "Write alt text for every image missing it" | VLM over a filtered set, write back | `P5` — the flagship batch job, and a good first curated operation |
| 53 | "Translate these subtitles into German" | `translate` node over transcripts | `P5` |
| 54 | "Read this article aloud in German" | `tts` node — exists; bytes cannot come back | `P5` |
| 55 | "Turn this still into a five-second clip" | `videogen` node — exists; same byte problem | `P5` |
| 56 | "Watermark everything before it leaves" | `watermark` node — exists; ledger-only | `P5` |
| 57 | "Upscale and denoise these scans" | no such node | `NEW N17` |
| 58 | "Make a square crop that keeps the subject centred" | `image-manipulation` subject crop — **built**, and unreachable from chat | `P5` |

Note the pattern: **most production capability already exists as nodes.** What is missing is the
ability to *invoke* them and to *get the bytes back*. Two changes unlock ten requests.

---

## 10. Delivery and rights

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 59 | "Export the approved ones to the client's S3 bucket" | `s3-sink` — exists, needs invocation + credentials scoping | `P5` |
| 60 | "Prepare a press pack: web-res JPEGs plus a caption sheet" | multi-node graph + a document artifact | `P5` + `NEW N18` (no document/report generation) |
| 61 | "Is this cleared for commercial use?" | licence/rights model | `NEW N10` — `dc.rights` is captured by the `metadata` node and there is no rights *model* |
| 62 | "Strip GPS before publishing" | metadata write-back — [ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md) is a concept, nothing built | `NEW N19` |
| 63 | "Mark anything the AI generated" | IPTC `DigitalSourceType` / C2PA provenance marking | `NEW N19` |
| 64 | "Send Maria a link to these five" | share links | `NEW N20` — no sharing/link model |

---

## 11. Pipeline and system self-service

The one category where the agent is already strong.

| # | Request | Verdict |
|---|---|---|
| 65 | "Build me a pipeline that transcribes and translates every new video" | `NOW` |
| 66 | "Why is this pipeline failing?" | `P1` — needs run/task/error read tools |
| 67 | "What would this pipeline cost to run over the whole library?" | `NEW N21` — no cost/throughput model |
| 68 | "Add a quality check before the sink" | `NOW` (`update_pipeline`) |
| 69 | "Which node kinds does my fleet actually support right now?" | `PARTIAL` — `get_node_descriptor` reports availability per kind; no fleet overview tool |
| 70 | "Re-run face detection on everything the old model touched" | `asset_node_result` invalidation query + a bulk re-run | `P5` + `NEW N1` — the index for this exists (`idx_asset_node_result_producer`) and nothing uses it |

---

## 12. Conversational and meta requests

Easy to forget, and they shape how the feature feels.

| # | Request | Interaction implied | Verdict |
|---|---|---|---|
| 71 | "What can you actually do?" | honest capability introspection from the advertised tool set | `P1` — cheap and high value |
| 72 | "Why did you pick those five?" | tool-call provenance already in the transcript; needs to be explainable in words | `P1` |
| 73 | "Show me your work as a pipeline I can save" | turn an ad-hoc job into a stored pipeline — an elegant bridge between §6 Options B and C | `P5` + a good idea |
| 74 | "Do that again next month" | see `NEW N14` | `NEW N14` |
| 75 | "Remember I always want 300 dpi TIFFs for print" | `put_memory` | `NOW` (when memory is enabled) |
| 76 | "Stop — that is wrong" | abort is built; **partial-work rollback is not** | `NEW N13` |

---

## 13. Outside the box

Requests that do not fit the DAM frame and are exactly where an agentic system earns its keep.

| # | Request | Why it is interesting | Verdict |
|---|---|---|---|
| 77 | "Find where our storyboard diverged from the finished film" | cross-modal alignment: document text vs. scene captions | `P6` + `NEW N11` |
| 78 | "Which of our stock photos does the competitor also use?" | fingerprint search against an external corpus | `NEW N22` — no external-corpus notion |
| 79 | "Build a training set: 500 diverse, well-lit, rights-cleared faces" | multi-constraint sampling with diversity — not a query, an **optimization** | `NEW N23`, and a genuinely valuable product feature |
| 80 | "Audit this collection for bias in who is depicted" | aggregate over detections/identity with a reporting frame | `NEW N12` + `NEW N10` |
| 81 | "What is missing from our archive?" | reason about *absence* — the hardest question here, and the one an LLM is uniquely good at | `NEW N12` |
| 82 | "Reconstruct the shooting day from timestamps and GPS" | temporal + spatial clustering into a narrative | `P3` + `NEW N4` |
| 83 | "Draft the press release from these assets" | comprehension → generation, output is text not media | `P1` — genuinely near, and a good early demo |
| 84 | "Watch the storage trend and warn me before we run out" | monitoring + a standing rule | `NEW N14` + `NEW N21` |
| 85 | "Explain this asset to a five-year-old / to a lawyer" | audience-adapted rendering of the dossier | `P1` |
| 86 | "Here is a photo — do we already have it?" | user-supplied media as **input to the conversation** | `NEW N24` — the chat has no upload/attach path |
| 87 | "Take these ten clips and tell me which one to lead with, and why" | judgement over media, with reasons | `P6` — the request that most needs vision |
| 88 | "Clean up the mess in this folder however you think best" | open-ended delegation: plan, propose, confirm, execute, report | Everything at once. The **north-star acceptance test** |

**Open spot N24 — media into the conversation.** Every design above assumes assets are already in
the catalog. "Here is a picture, find me more like it" is an obvious thing to say and there is no
path for it: no attachment on the stream request, no transient asset, no way to hand bytes to a
node. Cheap version: upload it as a normal asset into a scratch pool and proceed.

---

## 14. The open spots

Every `NEW` item, with a suggested home.

| # | Missing capability | Requests | Suggested owner |
|---|---|---|---|
| N1 | **Coverage / ledger query surface** over `asset_node_result` | 3,4,7,8,18,40,70 | A tool + an endpoint; note it in [NODES.md](../features/nodes/NODES.md) |
| N2 | **Catalog change feed** ("what changed since…") | 6 | New — likely a query over `edited` columns before it is a table |
| N3 | **Place-name resolution** (gazetteer or geocoder, offline-capable) | 9,11,82 and the seed prompt | New spec; also fills `asset_geo_comp.geo_alias` |
| N4 | **Geo filtering in search** (bbox / radius, combinable with text) | 9,10,11,82 | [SEARCH.md](../features/search/SEARCH.md) extension |
| N5 | **Label taxonomy / hypernym expansion** | seed prompt, 34 | Static map beside the detector's label set |
| N6 | **Camera-motion / shot-type descriptors** | 14 | A node — nothing computes this |
| N7 | **Sub-asset retrieval** (timecoded hits) | 19,27,50 | [SEARCH.md](../features/search/SEARCH.md) — `TRANSCRIPT`/`SEGMENT` entity types exist unused |
| N8 | **Identity traversal tools** + the missing clustering stage | 20,21,22 | [WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) |
| N9 | **Redaction node** (blur/mask regions, driven by detections) | 24,62 | New node; `sam2` masks are the input |
| N10 | **Rights / consent model** | 25,61,80 | [WORKFLOW_RIGHTS_RELEASE.md](../workflows/WORKFLOW_RIGHTS_RELEASE.md) |
| N11 | **Map-reduce / sub-agent summarization** in the loop | 27,34,77 | [AGENTIC_CHAT_PLAN.md §5.1](AGENTIC_CHAT_PLAN.md) |
| N12 | **Bounded aggregation tool** (`GROUP BY` without row dumps) | 35,37,38,40,80,81 | New MCP tool + a chart visual type |
| N13 | **Undo / withdrawal of agent writes** | 45,76 | Copy the `V2.71` per-placement provenance pattern |
| N14 | **Standing rules the agent can author** | 46,74,84 | Big idea — deserves its own concept file |
| N15 | **Composite / contact-sheet node** | seed prompt, 49 | New node ([NEW_NODE.md](../guidelines/NEW_NODE.md)) |
| N16 | **Video assembly / editing node** | 50 | New node |
| N17 | **Upscale / restore node** | 57 | New node |
| N18 | **Document / report generation** | 60,83 | Could ride the sandbox instead of a node |
| N19 | **Metadata write-back and AI marking** | 62,63 | [ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md) — concept exists |
| N20 | **Share links** | 64 | New |
| N21 | **Cost / throughput estimation** | 67,84 | Needs per-node timing history — `pipeline_node_task` has durations |
| N22 | **External corpus comparison** | 78 | New |
| N23 | **Constraint-satisfying sampling** (diverse training sets) | 79 | New — high commercial value |
| N24 | **User-supplied media in the conversation** | 86 | Chat + upload integration |

---

## 15. What blocks the most

Ranked by how many of the 88 requests each missing capability gates. This is the prioritization
signal this document exists to produce.

| Rank | Missing capability | Requests gated | Where |
|---|---|---|---|
| 1 | **Read access to node results** (the dossier + comp query) | ~45 | [AGENTIC_CHAT_CONTEXT_DATA.md](AGENTIC_CHAT_CONTEXT_DATA.md) |
| 2 | **Real retrieval** (`SearchProvider`-backed tools: sort, filters, facets) | ~35 | [AGENTIC_CHAT_PLAN.md §4.1](AGENTIC_CHAT_PLAN.md) |
| 3 | **Ad-hoc node execution** + async jobs | ~20 | [AGENTIC_CHAT_PLAN.md §6, §7](AGENTIC_CHAT_PLAN.md) |
| 4 | **Asset visuals in chat** (thumbnails, grids, images) | ~20 (quality of answer, not possibility) | [AGENTIC_CHAT_PLAN.md §5.2](AGENTIC_CHAT_PLAN.md) |
| 5 | **Catalog write tools** with provenance and confirmation | ~10 | [AGENTIC_CHAT_PLAN.md §4.3](AGENTIC_CHAT_PLAN.md) |
| 6 | **Produced-byte ingest** | ~10 | [NODES.md §2.1](../features/nodes/NODES.md) |
| 7 | **Vision in the loop** | ~8 (and the ceiling on everything else) | [AGENTIC_CHAT_PLAN.md §5.3](AGENTIC_CHAT_PLAN.md) |
| 8 | **Aggregation** | ~7 | N12 |
| 9 | **Geo + taxonomy resolvers** | ~6 | N3, N4, N5 |
| 10 | **Semantic search** | ~6 | [SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) |

The top two are **reading data Loom already computed**. No new nodes, no new models, no GPU — the
computation is done and the results are unreachable. That is the finding this document exists for.

---

## 16. Test setup

This is a requirements document, so its "tests" are acceptance prompts. Recommended: a
`chat-acceptance.spec.ts` fixture list driven against the demo corpus, one entry per request that
has left the `NEW` state, asserting the tool call sequence rather than the prose.

```bash
./setup-pool.sh
./start-demo.sh                                            # demo corpus with comps populated
cd loom-ui && ./node_modules/.bin/playwright test e2e/chat-backend.spec.ts
```

Fixture requirements the demo data does not yet meet:

- Assets with **GPS** in two recognizably different places (`DemoDatabaseInitializer`).
- Assets with `objectdetect` labels spanning at least two hypernym groups.
- One video with a real transcript. The `/opt/metaloom/loom-testdata` corpus is silent, so every
  `whisper` run reports success with an empty transcript — verify before writing an assertion
  against transcript content.
- One asset with a deliberately hostile OCR/caption payload, to prove dossier delimiting works.

---

## 17. Conventions and Gotchas

- **Do not confuse "computed" with "reachable".** Most of this document is gated on reading data
  that already exists. Check the comp tables before proposing a new node.
- **`search_document` already unifies OCR, transcripts, captions, VLM output, tags and Dublin Core
  metadata.** Reach for it before designing a new corpus.
- **Detection labels are literal.** No taxonomy, no synonyms, no hypernyms.
- **`asset_geo_comp` can hold a whole track**, not one point — the answer to "where" is sometimes a
  path.
- **Ratings are stored as reactions and no filter can read them** —
  [WORKFLOW_MANUAL_SORT.md](../workflows/WORKFLOW_MANUAL_SORT.md) §5. "Find my 5-star shots" is
  therefore harder than it sounds.
- **Nodes that produce bytes are ledger-only** — a green run does not mean the user can see the
  result.
- **The demo corpus has no audio**, so any transcript-shaped acceptance test needs new fixtures.
- **A request that needs a `NEW` capability is not a chat bug.** Route it to the owning spec rather
  than piling workarounds into the agent.

---

## 18. Where do I find …?

| I want … | Look at |
|---|---|
| The vision and the gap map | [AGENTIC_CHAT_PLAN.md](AGENTIC_CHAT_PLAN.md) |
| How metadata should reach the model | [AGENTIC_CHAT_CONTEXT_DATA.md](AGENTIC_CHAT_CONTEXT_DATA.md) |
| The built loop and UI contract | [LOOM_UI_CHAT.md](LOOM_UI_CHAT.md) |
| The current tool inventory and its known gaps | [MCP.md §5](../loom/MCP.md) |
| What each node computes and where it lands | [NODES.md §2–§3](../features/nodes/NODES.md) |
| The search stack | [SEARCH.md](../features/search/SEARCH.md), [SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) |
| Human-in-the-loop review families | [WORKFLOWS.md](../workflows/WORKFLOWS.md) |
| Adding a node | [NEW_NODE.md](../guidelines/NEW_NODE.md) |
| Demo fixtures | `loom/core/.../boot/DemoDatabaseInitializer.java` |

---

_Git HEAD revision: `43ada5a8`_
_Last updated: 2026-08-08 (new file — 88 requests catalogued, 24 open spots extracted)_
