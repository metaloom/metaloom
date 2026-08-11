# DOC_TASKS — Customer-Facing Website Documentation — Task List

> Work items for the customer documentation at `website/content/english/docs/`, derived from an
> audit of the site against the code on **2026-08-11**.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [WEBSITE.md](../website/WEBSITE.md) (site structure, build, the three gates,
> capture scripts) · [CODING.md](../guidelines/CODING.md) § Docs (the customer-docs rules) ·
> [WEBSITE_SEARCH.md](../website/WEBSITE_SEARCH.md) · [NODES.md](../features/nodes/NODES.md)
>
> **Ordering.** Tasks 1–5 are **corrections to published pages** — the site currently states things
> the code contradicts, or breaks the customer-docs rules. Do those first; they are cheap and each
> one is a reader being misled today. Tasks 6–11 are **missing pages**: three shipped APIs and two
> shipped features have no customer page at all. Tasks 12–16 are hygiene and gates that keep the
> docs from drifting again.
>
> **Blocking:** Task 11 (the Loom section index) must be done *after* tasks 6–10, since it links the
> pages they create. Task 15 (staged-artefact freshness) gates nothing else but should land before
> the next release build. Everything else is independent.
>
> Every task below is verifiable against the tree at the revision in the footer — the audit checked
> each claim in the code, not only in the specs. Items in
> [WEBSITE.md](../website/WEBSITE.md) § *Known gaps and defects* that are **not** repeated here were
> re-checked and found already fixed: the `guard` node has both pictures, the staged descriptor
> snapshot carries all 45 shipped kinds, `docs/nodes/_index.adoc` lists all 39 pages, and the Loom
> sink node no longer exists as a module (`cortex/nodes/loom/` has no sources).

---

## Task 1: Complete the Impressum — it is missing the address and the second contact channel

**Argumentation Summary:** `website/content/english/docs/legal/impressum/index.adoc` carries a
maintainer comment block (lines 7–21) stating that a geographic address and a second, direct means
of contact **must** be filled in before publication — § 5 Abs. 1 Z 2 and Z 3 ECG. The audit found
the placeholders were not filled in but **removed**: the *Medieninhaber* table at line 34 lists only
*Verantwortlich*, *E-Mail* and *Website*. An Austrian site disclosure with neither a postal address
nor a non-email channel is incomplete, and the page is published at `metaloom.io` today. This is the
one item on the site with legal rather than editorial consequence.

**Improvement Summary:** Add the operator's address and a second direct channel to the disclosure
table, then delete the now-satisfied bullets from the maintainer comment so the block stops claiming
work that is done.

```
1. Open website/content/english/docs/legal/impressum/index.adoc.
2. In the "Medieninhaber, Herausgeber und Diensteanbieter" table (starts line 34), add two rows
   between "Verantwortlich" and "E-Mail":
     | Anschrift  | <street and number> \n <postcode> <city>, Österreich
     | Telefon    | <number>
   If a telephone number is not to be published, replace that row with another channel that allows
   direct and immediate communication and is not email (§ 5 Abs. 1 Z 3 ECG) — a published contact
   form or a Discord/Matrix handle that the operator monitors. Do NOT leave the row out.
3. Update the maintainer comment block (lines 7-21): remove the three "To fill in" bullets that are
   now satisfied and KEEP the Firmenbuch/UID/Gewerbe paragraph and the "not legal advice" line —
   those are still live caveats.
4. Do not touch the #datenschutz section or the two DSGVO paragraphs.
5. Re-check the "privates Projekt / keine unternehmerische Tätigkeit" rows still hold; if MetaLoom
   Studio is offered commercially they must change in the same edit (see WEBSITE.md § Legal pages).
```

**References:** [WEBSITE.md](../website/WEBSITE.md) § *Legal pages* · § 5 ECG, § 25 MedienG ·
`website/content/english/docs/legal/impressum/index.adoc`
**Test Requirements:** No automated test covers legal content. `cd website && ./build.sh` must end
with `All done` (link check + localhost check pass), and the rendered `/docs/legal/impressum/` must
show both new rows. Confirm no `[…]` markers remain anywhere in the file:
`grep -n "\[…\]\|\[\.\.\.\]" website/content/english/docs/legal/impressum/index.adoc`.

---

## Task 3: Fix the LLM node page — prompts see the file name, nothing else

**Argumentation Summary:** `docs/nodes/llm/index.adoc` tells the reader (line 18, and again in the
nodeviz block on line 11) that the input is *"the asset, whose filename and stored fields the prompts
reason over"*. `LLMNode.compute` binds exactly one variable into every prompt —
`prompt.set("name", ctx.media().file().getName())` — and nothing else. There are no stored fields in
the prompt context, and no upstream node output is reachable from a prompt template. A customer who
writes a prompt referring to a tag, a caption or a transcript gets an answer the model invented, with
no error anywhere. This is the most expensive kind of documentation defect: it fails silently and
plausibly.

**Improvement Summary:** State precisely what a prompt can reference — the `{name}` binding — and say
that reaching other metadata means putting it in the prompt yourself or using a different node.

```
1. Open cortex/nodes/llm/core/src/main/java/io/metaloom/cortex/node/llm/LLMNode.java and confirm the
   binding set is still only "name" (compute(), around line 141). The code is authoritative here.
2. website/content/english/docs/nodes/llm/index.adoc line 18: replace the "media — the asset, whose
   filename and stored fields the prompts reason over" cell with a description naming the single
   binding, e.g. "`media` — the asset. Prompts can reference its file name as `{name}`; that is the
   only value bound into the prompt."
3. Same file line 11, inside the data-nodeviz JSON: update BOTH configs' input port description "d"
   fields, which repeat the same wrong claim. ⚠️ The JSON lives in a single-quoted HTML attribute —
   never write an apostrophe inside it (WEBSITE.md § Node diagrams). Keep exactly two ' per line.
4. Add a short paragraph under "== Configuration" stating that to give the model more context than
   the file name, put it in the prompt text itself; upstream node results are not interpolated.
5. Check the sibling pages for the same claim, since they were written from the same template:
   grep -n "stored fields" website/content/english/docs/nodes/*/index.adoc
   The translation playbook already documents the real behaviour — do not contradict it.
```

**References:** `cortex/nodes/llm/core/src/main/java/io/metaloom/cortex/node/llm/LLMNode.java:141` ·
[WEBSITE.md](../website/WEBSITE.md) § *Node diagrams* ·
`website/content/english/docs/playbooks/translation/index.adoc`
**Test Requirements:** `cd website && ./build.sh` — a broken `data-nodeviz` attribute does not fail
the build, so **visually confirm the diagram still renders** on `/docs/nodes/llm/` (a blank diagram
means the JSON was truncated by an apostrophe). `node check-links.mjs` for the anchors.

---

## Task 4: Fix `docs/cortex/metrics/` — three meters no code emits, and an incomplete label list

**Argumentation Summary:** The page documents `cortex_results_sent_total` (line 83),
`cortex_results_batches_sent_total` (line 84) and `cortex_source_ack_timeouts_total` (line 87) as
live meters. The audit confirms all three are declared and implemented
(`MicrometerCortexMetrics.recordResultsBatchSent` / `recordSourceAckTimeout`) but **called from no
production site** — the only callers are a test double in `LoomControlChannelTest`. The PromQL
example on line 90 therefore compares `rate(cortex_results_sent_total)` against
`rate(cortex_node_operations_total)`, and the numerator is permanently zero — a reader who follows
the page builds a dashboard that reads "nothing is being written back" on a healthy system.
Separately, the `provider` label list on line 98 reads `llm | smolvlm | whisper | tesseract`, while
the actual call sites emit at least `llm`, `translate`, `vlm`, `guard`, `tts`, `sentiment`,
`smolvlm`, `video-vlm`, `imagegen`, `tesseract` and `whisper`.

**Improvement Summary:** Stop presenting the three dead meters as live, and derive the `provider`
label list from the real call sites.

```
1. Confirm the meters are still dead:
   grep -rn "recordResultsBatchSent\|recordSourceAckTimeout" --include=*.java cortex/ \
     | grep -v "/test/\|common/metrics\|impl/monitoring"
   An empty result means nothing in production calls them. If that changed, this task becomes "verify
   the page" instead.
2. In website/content/english/docs/cortex/metrics/index.adoc, remove the three table rows (lines 83,
   84, 87). Do NOT invent a "not yet emitted" column — a customer-facing catalogue should list what
   the scrape endpoint actually returns.
3. Replace the PromQL example at line 90 with one built from meters that are emitted — e.g. comparing
   rate(cortex_node_operations_total{outcome="failure"}) against the total, which is the question the
   removed example was trying to answer.
4. Rebuild the `provider` label list on line 98 from the call sites, not from memory:
   grep -rn "recordAiCall(" --include=*.java cortex/ | grep -v "/test/\|common/metrics\|impl/monitoring"
   and resolve the METRICS_LABEL / METRICS_PROVIDER constants each one passes.
5. Do NOT edit spec/features/ops/METRICS.md §5.1 — the dead meters are deliberately recorded there as
   a code gap, and MetricsCatalogScrapeTest parses that file's §3/§5 tables at runtime.
6. Note in spec/features/ops/METRICS.md § Known gaps that the website page no longer advertises them,
   so the remaining work is purely "wire the meters".
```

**References:** [METRICS.md](../features/ops/METRICS.md) §5.1 (the dead-meter table) ·
`cortex/common/src/main/java/io/metaloom/cortex/common/metrics/CortexMetrics.java:64,73` ·
`cortex/llm-common/.../LlmInvoker.java:72`
**Test Requirements:** `mvn -pl loom/services/rest test -Dtest=MetricsCatalogScrapeTest` must stay
green (it reads the **spec** tables — this task must not change them). Then
`cd website && ./build.sh`.

---


## Task 7: Add a docs page for the gRPC API

**Argumentation Summary:** `loom/services/grpc` ships and runs its own HTTP/2 server on its own port,
started and stopped by the bootstrap sequence, with protobuf definitions in `loom-shared/proto/`. The
website documents it in exactly one place — the Maven coordinates of `loom-grpc-client` on
`docs/loom/maven-artifacts/` — and the Loom section index still calls it planned (Task 2). A customer
integrating from a non-JVM language has no way to learn the port, the services, the auth model or
where to get the `.proto` files.

**Improvement Summary:** A new `docs/loom/grpc-api/` page that sits beside the REST and GraphQL pages
and covers the endpoint, the services, authentication and how to obtain the protobuf definitions.

```
1. Read spec/loom/GRPC.md and cross-check loom/services/grpc/ and loom-shared/proto/ for the actual
   service and method names — the spec's Progress Assessment shows this area is still moving.
2. Create website/content/english/docs/loom/grpc-api/index.adoc, `title: gRPC API`, modelled on the
   structure of docs/loom/graphql-api/index.adoc (which is the closest sibling in tone).
3. Cover:
   - The separate server and port, and why it is separate from REST/UI/GraphQL/WebSocket (they scale
     and bind independently). Take the default port and env var from the configuration spec and
     confirm it against ServerOptions.
   - The service inventory: service -> RPCs -> what they do.
   - Authentication: how a JWT is attached to a gRPC call, linking link:../authentication/[].
   - Getting the .proto files: where they live in the source tree / which artifact carries them, and
     a `grpcurl`-style worked example against a running server.
   - A pointer to link:../maven-artifacts/[Maven Artifacts] for the Java client coordinates rather
     than repeating them.
4. Consider staging the .proto files into website/static/docs/examples/ the way openapi.json and
   schema.graphql are staged, so the page can offer a download card. If you do, add them to the
   staged-artefact table in spec/website/WEBSITE.md AND to Task 15's freshness check — a fourth
   silently-stale artefact is not an improvement.
5. Customer-docs rules apply: no class names, no spec references, escape localhost URLs in prose.
```

**References:** [GRPC.md](../loom/GRPC.md) · [SERVER.md](../loom/SERVER.md) ·
[CONFIGURATION.md](../loom/CONFIGURATION.md) · `loom/services/grpc/`, `loom-shared/proto/`
**Test Requirements:** `cd website && ./build.sh`. If `.proto` files are staged, verify the download
links resolve in `dist/` — `check-links.mjs` validates `href`/`src`, so a wrong path fails the build.

---

## Task 8: Add a docs page for the real-time events WebSocket

**Argumentation Summary:** Loom streams pipeline and processor lifecycle frames to browsers and the
CLI over `/api/v1/pipelines/events/ws`. This is how the UI's live task view and the CLI's run output
work, and it is the only way an integrator can follow a pipeline run without polling. The site
mentions WebSockets only in the Cortex worker context (the processor control channel) and in the
deployment port tables — the client-facing events socket is documented nowhere, so a customer
building an integration polls the REST API instead.

**Improvement Summary:** A new `docs/loom/events/` page documenting the UI events socket as a public
integration surface: URL, authentication, frame shapes, and what each frame means.

```
1. Read spec/loom/WEBSOCKET.md §4 (the UI events socket). §3 is the processor control channel —
   that is between Loom and a Cortex worker and is NOT customer integration surface; do not document
   it as one. §2 is the shared auth model.
2. Create website/content/english/docs/loom/events/index.adoc, `title: Real-Time Events`.
3. Cover:
   - The endpoint /api/v1/pipelines/events/ws and that it is server -> client only.
   - Authentication via ?token=<jwt> after the upgrade, and why (no Authorization header on a
     handshake). Link link:../authentication/[Authentication].
   - A frame reference table: frame type -> when Loom sends it -> the fields a client can rely on.
     Derive it from the code, not only from the spec — verify against the handler in loom/services.
   - A worked example: a small JavaScript or Python client that connects, authenticates and prints
     each frame. Escape any \ws://localhost or \http://localhost in prose.
   - A note on reconnection: what a client should do when the socket drops mid-run, and how to
     recover state from the REST task endpoints.
4. Cross-link from docs/pipeline/ (§ running a pipeline) and docs/loom/features/ so a reader
   following a run finds it.
5. The chat agent's stream is Server-Sent Events, NOT a WebSocket. Do not describe it on this page —
   if the reader needs it, link link:../chat/[Chat & AI Agent].
```

**References:** [WEBSOCKET.md](../loom/WEBSOCKET.md) §2, §4 ·
[PIPELINE.md](../features/pipeline/PIPELINE.md) · `loom/services/rest/` (the events handler)
**Test Requirements:** `cd website && ./build.sh`. Verify the new cross-links from
`docs/pipeline/index.adoc` resolve (`node check-links.mjs`), and that the code sample renders with
syntax highlighting.

---

## Task 9: Add a page on searching assets

**Argumentation Summary:** Search is one of the headline reasons to run a media asset manager, and
MetaLoom has three flavours of it — full-text over extracted metadata, semantic search over
embeddings, and the face/duplicate indices. The website documents only the **administration** of the
indices (`docs/loom/search-indices/`: reindexing, syncing, the job view). There is no page telling a
customer how to *search*: what the query syntax is, what fields are searchable, how semantic search
differs from full-text, or how to turn semantic search on (it is off by default —
`LOOM_SEARCH_SEMANTIC_ENABLED=false`, and needs an embedding endpoint and model). The single mention
of the capability is a bullet on `docs/loom/features/` reading "Full-text search over extracted
metadata".

**Improvement Summary:** A new `docs/loom/search/` page covering searching as a user-facing feature,
with the existing `search-indices/` page kept as the operator-facing counterpart and cross-linked.

```
1. Read spec/features/search/SEARCH.md and spec/features/search/SEMANTIC_SEARCH.md.
   spec/features/search/SEARCH_INDEX_ADMIN.md is what the existing search-indices page already
   covers — do not duplicate it.
2. Create website/content/english/docs/loom/search/index.adoc, `title: Searching Assets`, weight it
   so it lands next to Features in the Loom section.
3. Cover:
   - What is searchable: which extracted fields reach the index, and which node has to have run for a
     given field to be there (a transcript is only searchable after a transcription pipeline ran).
     This is the connection between the pipeline model and search, and no page makes it today.
   - Query syntax with worked examples, against REST and in the UI.
   - Semantic search: what it does differently, that it is OFF by default, and the three settings
     that turn it on (LOOM_SEARCH_SEMANTIC_ENABLED plus the embed URL and model) — link
     link:../configuration/[Configuration] for the full table.
   - Face and duplicate search as separate capabilities, linking link:../../nodes/facedetect/[] and
     link:../../nodes/dedup/[].
   - A closing "keeping the index current" section that links link:../search-indices/[Search Indices]
     rather than restating it.
4. Add a "Read more" line to the search bullet in docs/loom/features/index.adoc (line ~135) pointing
   at the new page, matching the format of the other bullets there.
5. Screenshots are optional; if you add one of the UI search screen, capture it with the existing
   loom-ui/scripts/capture-ui-screenshots.mjs conventions (dark theme, reducedMotion) and put it in
   THIS page's bundle, not in docs/ui/ — a bare image:: filename resolves inside the using page's
   bundle.
```

**References:** [SEARCH.md](../features/search/SEARCH.md) ·
[SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) ·
[SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md) ·
`website/content/english/docs/loom/search-indices/index.adoc`
**Test Requirements:** `cd website && ./build.sh`. The search index build step must report the new
page (`Search index OK — N pages`, N one higher). Confirm the new page is reachable from
`/docs/loom/` and from the Features page.

---

## Task 10: Add a page on permissions and roles

**Argumentation Summary:** MetaLoom has a real RBAC model — users, groups, roles and a per-entity
permission matrix — and it is the first thing anyone deploying for a team has to understand. The
website covers it in fragments: three bullets on `docs/loom/features/` (users, groups, roles), a
screenshot of the ACL matrix on `docs/ui/`, and the endpoint list in the REST reference. Nothing
explains the model itself — how a permission is resolved, what a role grants, how group membership
composes, or what the permission names mean. An administrator has to reverse-engineer it from the
Swagger UI.

**Improvement Summary:** A new `docs/loom/permissions/` page explaining the model once, so the
existing fragments can link to it instead of each half-explaining it.

```
1. Read spec/features/rbac/RBAC.md and spec/features/permissions/PERMISSIONS.md. The permission NAMES
   are the customer-visible vocabulary — take them from the code enum, since the specs can lag:
   the JooqLoomPermission enum and the permission constants it mirrors.
2. Create website/content/english/docs/loom/permissions/index.adoc, `title: Permissions and Roles`.
3. Cover:
   - The four objects (user, group, role, permission) and how they compose, in customer language.
   - How a permission is resolved for a request: the order of evaluation and what wins.
   - A reference table of every permission name -> what it allows -> the entity it applies to.
   - Worked setup: create a role, grant it, add a user to a group — using the REST API or the CLI,
     whichever a reader is most likely to automate.
   - A note on API keys and how their permissions relate to the issuing user's.
4. Illustrate the resolution order with inline SVG in a ++++ block (shared .ml-* classes,
   page-prefixed marker ids e.g. ml-perm-arrow) — ASCII art is forbidden by the customer-docs rules.
5. Retarget the "Read more" lines on docs/loom/features/index.adoc for Users (line ~58), Groups
   (~64) and Roles (~74) to include the new page, and link it from docs/ui/#administration.
```

**References:** [RBAC.md](../features/rbac/RBAC.md) ·
[PERMISSIONS.md](../features/permissions/PERMISSIONS.md) ·
`loom/db/jooq/.../JooqLoomPermission.java` · `website/content/english/docs/loom/features/index.adoc`
**Test Requirements:** `cd website && ./build.sh`, all three gates. Verify the permission table
against the code enum — a name that does not exist is worse than no table:
compare against `JooqLoomPermission`.

---

## Task 11: Rebuild the Loom section index so it lists every page in the section

**Argumentation Summary:** `docs/loom/_index.adoc` is the section landing page and its "Components"
list is how a reader navigates the largest docs section. It lists 14 entries while the section
contains 18 pages. **Database Integrity** — a shipped admin feature with its own page and three
screenshots — appears nowhere on it, so the only route to that page is the sidebar rail. Containers,
Helm Chart and Maven Artifacts are reachable via the Artifacts hub, which is defensible, but the
audit found no route at all to `database-integrity/`. Tasks 6–10 add five more pages that will have
the same problem unless the index is updated in the same pass.

**Improvement Summary:** Bring the Components list back in sync with the section, and add a check
step to the workflow so it stays that way.

```
1. Add a bullet for Database Integrity to the Components list in
   website/content/english/docs/loom/_index.adoc, in the same style as its neighbours:
   * *Database Integrity* — Checking the database for dangling references and inconsistent records.
     See link:database-integrity[Database Integrity].
2. Add bullets for the pages created by Tasks 6-10 (MCP Server, gRPC API, Real-Time Events,
   Searching Assets, Permissions and Roles). Do this task LAST, after those pages exist, or the link
   check fails the build.
3. Update line 7's opening sentence to name the four transports Loom actually exposes (REST,
   GraphQL, gRPC, MCP) — Task 2 removes "(planned)" from the same line.
4. Verify the list is complete afterwards, and record the one-liner in
   spec/website/WEBSITE.md § Where do I find …? so the next person can re-run it:
   diff <(ls -d website/content/english/docs/loom/*/ | xargs -n1 basename | sort) \
        <(grep -oE "link:[a-z-]+" website/content/english/docs/loom/_index.adoc | cut -d: -f2 | sort -u)
   Entries that are deliberately reached only through the Artifacts hub (containers, helm-chart,
   maven-artifacts) may stay out — note that in a comment so the next audit does not re-flag them.
5. Do the same completeness check for docs/cortex/_index.adoc (the audit found it current, including
   custom-nodes) and for the concept table on docs/_index.adoc — add the new pages to the "API and
   Client" and "Operation" rows there.
```

**References:** [WEBSITE.md](../website/WEBSITE.md) § *Page inventory* ·
`website/content/english/docs/loom/_index.adoc`
**Test Requirements:** `cd website && ./build.sh` — `check-links.mjs` fails on any `link:` target
that does not resolve, which is exactly the failure mode of adding a bullet before its page exists.
Confirm the rendered `/docs/loom/` lists every entry.

---

## Task 12: Clear the stale `guard` screenshot exemption

**Argumentation Summary:** `loom-ui/scripts/fixtures/nodes/status.json` still carries a `pending`
entry for `guard`, stating that its descriptor is not staged into
`website/static/pipeline-editor/node-descriptors.json` and that neither picture can therefore be
taken. Both premises are now false: `guard` **is** in the staged snapshot (45 kinds, verified), and
`website/content/english/docs/nodes/guard/` contains both `config.png` and `debug.png`. The entry now
does nothing but print a false countdown on every website build, which is precisely how a gate stops
being believed. The `pending` mechanism is only useful while its entries are true.

**Improvement Summary:** Delete the satisfied entry, leaving `status.json` an empty object, and
confirm the gate goes quiet.

```
1. Verify the premises before deleting anything:
   - ls website/content/english/docs/nodes/guard/   (config.png and debug.png must both be there)
   - the kind "guard" must appear in website/static/pipeline-editor/node-descriptors.json
2. Remove the "guard" entry from loom-ui/scripts/fixtures/nodes/status.json, leaving {}.
3. Run the gate on its own: cd website && node check-node-screenshots.mjs
   It must pass with no pending countdown printed.
4. Confirm the two pictures are real rather than placeholders — open them and check the config shot
   shows the guard settings panel and the debug shot shows a real run with its result strip. If the
   debug shot came from a stubbed backend, that is a fixture defect: WEBSITE.md § Where the debug
   payloads come from requires backend: "real" for every kind but the two cloud sources.
5. Update spec/website/WEBSITE.md § Known gaps: remove the "guard node page has neither picture"
   item and the "no blocked entry today" sentence's stale surrounding claim if needed.
```

**References:** [WEBSITE.md](../website/WEBSITE.md) § *The gate*, § *Where the debug payloads come
from* · `loom-ui/scripts/fixtures/nodes/status.json` · `website/check-node-screenshots.mjs`
**Test Requirements:** `cd website && node check-node-screenshots.mjs` passes with no pending output,
then a full `./build.sh`.

---

---

## Task 14: Teach the link gates about the detection player's asset URLs

**Argumentation Summary:** `check-links.mjs` validates an **explicitly enumerated** list of
`data-*-url` attributes (`openapi`, `graphql`, `schema` and the four `search-*` ones). The detection
player's `data-track-url` and `data-video-url` are not in that list, and neither are they in
`build.sh`'s `LINK_PATTERN`. The player is used on `/docs/pipeline/#detections-over-time` and
`/docs/nodes/facedetect/` — two of the most-visited pages — and its assets are **generated**, so a
regenerated track written under a new name breaks both players with a silent `fetch` failure that no
gate notices and no build reports. The gate's own design principle is that an enumerated list must be
kept complete; this is the entry that was missed.

**Improvement Summary:** Add both attributes to the checker and to the localhost pattern, so a
renamed or missing detection asset fails the build like any other broken link.

```
1. website/check-links.mjs line ~71: add data-track-url and data-video-url to attrPattern.
2. website/build.sh line ~35: add the same two attributes to LINK_PATTERN so a localhost URL in
   either one is caught too.
3. Update the comment block at the top of check-links.mjs (line ~5) that lists what is followed.
4. Run cd website && ./build.sh and confirm the checker now resolves the detection track and video —
   if either is currently missing or misnamed, that is the gate doing its job: FIX THE ASSET, do not
   remove the attribute from the list. The generator is
   integration-test/.../node/DetectionPlayerFixtureGenerator (-Dloom.regenerateDetectionTrack=true).
5. Update spec/website/WEBSITE.md § The detection player: the paragraph currently states these two
   attributes are NOT checked and that adding them "would make the intent real" — rewrite it to say
   they are checked, and drop the matching § Known gaps entry.
```

**References:** [WEBSITE.md](../website/WEBSITE.md) § *The detection player*, § *The two build-output
gates* · `website/check-links.mjs:71` · `website/build.sh:35`
**Test Requirements:** `cd website && ./build.sh` must end with `Link check OK — N pages`. Verify the
gate actually bites: temporarily rename the track asset, confirm the build fails, restore it.

---

## Task 15: Fail the build when a staged generated artefact is stale

**Argumentation Summary:** Three files under `website/static/` are generated and copied in by hand —
`docs/examples/openapi.{json,yaml}`, `docs/examples/schema.graphql` and
`pipeline-editor/node-descriptors.json`. Nothing automates the copy and no check catches staleness.
The consequences are not cosmetic: the Swagger UI on `/docs/loom/rest-api/` is the API reference a
customer integrates against, and `node-descriptors.json` drives both the public pipeline editor and
every node config screenshot — a stale snapshot silently documents ports and settings that no longer
exist. The `guard` node's missing pictures (Task 12) were a downstream symptom of exactly this copy
being late.

**Improvement Summary:** Add a freshness check to `build.sh` that compares each staged file against
its generator's output or source, and fails the build naming the command that fixes it.

```
1. The cheapest correct check for schema.graphql is a direct diff against its source:
     diff website/static/docs/examples/schema.graphql \
          loom/services/graphql/src/main/resources/loom.graphqls
   Wire that into build.sh as a hard failure. It costs nothing and closes one third of the gap.
2. For openapi.* and node-descriptors.json the source is a generator, so a diff means running Maven —
   too slow for every build. Instead:
   a. Have loom/doc's generator write a manifest (e.g. a content hash plus the git revision it was
      generated at) alongside each artefact, and stage it with them.
   b. In build.sh, fail if the staged manifest's revision is older than the last commit touching the
      generator's inputs (loom/services/rest/.../LoomOpenAPI.java and the node descriptor providers).
   c. Print the exact regeneration commands from WEBSITE.md § Staged generated artefacts in the
      failure message — including that the working directory MUST be loom/doc/.
3. If (2) is too invasive for one change, ship (1) plus a WARNING-level staleness report for the
   other two, and open a follow-up. A warning that names the fix still beats silence.
4. Any new staged artefact (e.g. the .proto files from Task 7) must be added to the same check in the
   same change.
5. Update spec/website/WEBSITE.md § Staged generated artefacts to describe the check, and remove the
   "nothing fails when they go stale" entry from § Known gaps.
```

**References:** [WEBSITE.md](../website/WEBSITE.md) § *Staged generated artefacts* ·
`website/build.sh` · `loom/doc/` (`ExampleGenerator`, `OpenAPIGenerator`, `NodeDescriptorGenerator`)
**Test Requirements:** `cd website && ./build.sh` passes on a current tree. Verify the check bites:
edit a byte of `website/static/docs/examples/schema.graphql`, confirm the build fails with the
regeneration command in the message, restore it. `mvn -pl loom/doc test` (`NodeDescriptorGeneratorTest`)
must stay green if the generator is touched.

---

## Task 16: Load Swagger UI and GraphiQL only on the pages that use them

**Argumentation Summary:** Both plugins are registered in `config.toml` under `[[params.plugins.js]]`
and `[[params.plugins.css]]`, so every page on the site — all ~90 of them, including every node page
and the marketing front door — downloads and parses roughly a megabyte of JavaScript that two pages
use. Both scripts already have to bail out when their mount div is absent, which is a workaround for
the same problem. The docs are also the part of the site most likely to be read on a slow connection
from a phone, and the reader has already paid for a 7 MB search model on the pages that use it.

**Improvement Summary:** Move the two plugin bundles from the global plugin lists to per-page assets,
using the same `page_css`-style mechanism the theme already has for page-scoped stylesheets.

```
1. Remove the swagger (3 js + 1 css) and graphiql (4 js + 1 css) entries from the
   [[params.plugins.js]] / [[params.plugins.css]] lists in website/config.toml. Leave nodeviz and
   detectionplayer global — they are small and used across many docs pages.
2. Add a front-matter-driven per-page hook in the theme, mirroring the existing page_css convention:
   a `page_js:` (list) key read in layouts/_default/baseof.html (or the docs single layout), emitting
   the scripts only for pages that declare it.
3. Declare the bundles on the two pages:
   - website/content/english/docs/loom/rest-api/index.adoc  -> the three swagger js + swagger css
   - website/content/english/docs/loom/graphql-api/index.adoc -> the four graphiql js + css
4. KEEP the bail-out guards in plugins/swagger/swagger.js and plugins/graphiql/graphiql.js. They are
   cheap and they are what stops a copy-pasted mount div from throwing site-wide.
5. Verify the two explorers still work: /docs/loom/rest-api/ must load the Swagger UI with
   docExpansion:'none', filter:true, deepLinking:true, persistAuthorization and validatorUrl: null
   (the site must never ship a reader's spec to validator.swagger.io), and /docs/loom/graphql-api/
   must build its schema offline from the staged SDL.
6. Confirm #swagger-ui keeps its own light surface from custom.less — the CSS is now loaded on one
   page only, so a rule that relied on it being global will break.
7. Update spec/website/WEBSITE.md § Swagger UI / GraphiQL wiring (it currently states both are loaded
   on every page and explains why the guards exist) and drop the § Known gaps entry.
```

**References:** [WEBSITE.md](../website/WEBSITE.md) § *Swagger UI / GraphiQL wiring*, § *Content
conventions* (`page_css`) · `website/config.toml` lines 51–89
**Test Requirements:** `cd website && ./build.sh`. Then serve `dist/` and check with
Playwright/Chromium under `loom-ui/`: `/docs/loom/rest-api/` renders operations, `/docs/loom/graphql-api/`
renders the explorer, and a third page (e.g. `/docs/nodes/facedetect/`) reports **no** swagger or
graphiql request in the network log and no console errors.

---

## Related task files

| Area | File |
|---|---|
| Website structure, build, gates, capture scripts | [WEBSITE.md](../website/WEBSITE.md) |
| Definition of done for a code change (incl. the docs rules) | [CODING.md](../guidelines/CODING.md) |
| Definition of done for a spec change | [SPEC_RULES.md](../guidelines/SPEC_RULES.md) |
| Node catalogue / adding a node | [NODES.md](../features/nodes/NODES.md) |
| Metrics catalogue (the dead-meter record behind Task 4) | [METRICS.md](../features/ops/METRICS.md) |
| Loom UI task record | [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) |

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (initial audit — 16 tasks: 5 corrections, 5 missing pages, 6 hygiene/gates)_
