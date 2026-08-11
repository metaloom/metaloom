# MetaLoom Glossary

Compact term reference for the whole platform. Each table covers one component area; the
**Component** column names the subsystem that *owns* the term (the place where it is defined,
persisted or executed), which is not always the only place it appears.

**Scope.** Definitions only — one line each. Nothing here is authoritative over the code:
when they disagree, the code wins and this file gets fixed in the same change
([guidelines/SPEC_RULES.md](guidelines/SPEC_RULES.md)).

| Looking for… | Go to |
|---|---|
| Module layout, frameworks | [METALOOM.md](METALOOM.md) |
| Loom internals, DI, lifecycle | [loom/LOOM.md](loom/LOOM.md) |
| Cortex internals, control channel | [cortex/CORTEX.md](cortex/CORTEX.md) |
| Entities, tables, relations | [loom/DOMAIN.md](loom/DOMAIN.md) |
| Ports, payloads, fan-out | [features/pipeline/NODE_DATA_TYPES.md](features/pipeline/NODE_DATA_TYPES.md) |

**Component values used below:** `Platform` (whole product), `Loom` (backend), `Cortex`
(worker), `Loom+Cortex` (shared wire/model vocabulary), `loom-ui`, `CLI`, `Sidecar`.

---

## 1. Platform & Components

| Term | Component | Description |
|---|---|---|
| **MetaLoom** | Platform | The DAM product: ingests media, derives metadata (hashes, faces, transcripts, thumbnails, text, quality, embeddings) and stores/indexes/exposes it. |
| **Loom** | Loom | The central backend server. Sole owner of the PostgreSQL database, auth, storage and the pipeline DAG; exposes REST/gRPC/GraphQL/MCP/WebSocket. |
| **Cortex** | Cortex | The stateless worker process that executes the source/node/segment tasks Loom dispatches. No database, no DAG of its own. |
| **loom-ui** | loom-ui | React/Vite/MUI web front end, served by Loom's `UIService`; includes the pipeline editor. |
| **loom-app** | Platform | Electron wrapper packaging the built `loom-ui` as a desktop app. |
| **CLI** | CLI | Top-level `cli/` module — PicoCLI + REST client, built as a GraalVM native image. **Cortex has no CLI** despite its `cortex/cli` module name. |
| **Sidecar** | Sidecar | A Python GPU/model HTTP service (depth, tts, sentiment, ideogram, ltx2, mage-flow) that some Cortex nodes call out to. Not a Maven module. |
| **Session Runner** | Loom | Per-chat container image (`loom/agent/session-runner`) hosting the agent's coding sandbox filesystem. |
| **Online / Offline mode** | Cortex | Online = `LOOM_HOST`/`LOOM_PORT` resolvable, so the worker registers and receives tasks. Offline = no Loom client at all; the worker idles. |

---

## 2. Identity & Access (RBAC)

| Term | Component | Description |
|---|---|---|
| **User** | Loom | An account (login, SSO, password hash, enabled/deleted flags). Creator/editor of nearly every entity. |
| **Group** | Loom | A collection of users that carries roles. The only path from a user to a role. |
| **Role** | Loom | A named bundle of permissions, attached to groups via `role_group`. |
| **Permission** | Loom | A CRUD-style grant value from the `loom_permission` PG enum (`CREATE_ASSET`, `READ_ROLE`, `MANAGE_CORTEX_INSTANCE`, …), bound to a role, user or token. |
| **ACL** | Loom | The effective chain User → Group → Role → Permission, checked per endpoint via `lrc.requirePerm(…)`. There is **no direct user→role binding**, and the stored `resource` column is not enforced. |
| **Token** | Loom | An API key with its own permission set — scoped machine access, independent of a user's own grants. |
| **JWT / `__Host-loom_token`** | Loom | The signed session credential; issued on login as an `HttpOnly; Secure; SameSite=STRICT` cookie, passed to WebSockets as `?token=`. |

---

## 3. Content Organization

| Term | Component | Description |
|---|---|---|
| **Space** | Loom | The outermost workspace grouping libraries and collections. DB table is `project`; API and permissions call it `SPACE_*`. Also scopes SPACE-level agent memory. |
| **Library** | Loom | A container of assets and collections, and the scanner root that asset locations belong to. Its `pool_uuid` decides where uploaded bytes land. |
| **Collection** | Loom | A hierarchical (self-parenting) folder grouping assets and clusters. M:N with both. |
| **Tag** | Loom | A named label with rating and colour, unique per `(name, collection)`. Placement on an asset may be time- or area-scoped. |
| **Tag collection (namespace)** | Loom | `tag.collection` — a **plain varchar namespace** (`people`, `places`) in the tag uniqueness key. Unrelated to the Collection entity despite the shared word. |
| **Containment axis** | Loom | Space / Library / Collection — M:N at every level, so one asset is reachable by several paths. Distinct from the storage axis (§4). |

---

## 4. Assets & Storage

| Term | Component | Description |
|---|---|---|
| **Asset** | Loom | The **bytes**: `uuid` PK plus hashes (`sha512sum` is the unique content identity), size, mime type, filename. Anything derived by *interpretation* lives in a component table, never on `asset`. |
| **Asset Location** | Loom | A physical placement of an asset's binary — a path within a pool or an S3 object key. **0..n per asset**, natural key `(library_uuid, path)`. Exposed over REST as a "binary". |
| **Asset Pool** | Loom | A storage backend: a filesystem directory **XOR** an S3 bucket (CHECK-enforced), with free/used space tracked. Libraries, attachments and chat sessions point at one. |
| **Binary** | Loom | The REST-facing name for an asset location / the stored bytes (`/binaries`). "The" binary of an asset is `AssetBinaryDao.loadPrimaryByAssetUuid`, never a `fetchOne`. |
| **Attachment** | Loom | A derived auxiliary binary (thumbnail, contact sheet, poster frame, waveform, proxy, extracted audio) with node provenance; content-addressed by sha512 in a pool. |
| **Blacklist** | Loom | A block entry on an asset (copyright, virus scan) with a review count and label. |
| **Annotation** | Loom | A time- or area-scoped marker on an asset: FEEDBACK, TAG or CHAPTER. |
| **Asset Remix** | Loom | A derivation link between two assets. Schema-only — no DAO, no code references. |

---

## 5. Derived Data (Components & Results)

| Term | Component | Description |
|---|---|---|
| **Asset Component** | Loom | One of nine `asset_*_comp` tables (geo, doc, image, video, audio, transcript, json, fingerprint, segment) holding a node's typed output for an asset. All share one contract: provenance + execution refs + `UNIQUE (asset_uuid, node_kind, <discriminators>)`. |
| **Discriminator** | Loom | The tail of a component's unique key (`stream_index`, `page_number`, `lang`, `seq`, …) that lets one node write several rows per asset while a retry still replaces in place. |
| **Producer Version** | Loom | The node version stamped on every component/ledger row. Deliberately **not** part of the unique key — it is the invalidation sweep (`WHERE node_kind = ? AND producer_version <> ?`). |
| **Asset Node Result** | Loom | The per-**asset** processing ledger: did node X at version V process asset A, and what happened (SUCCESS/SKIPPED/FAILED). Catalog state; outlives every run. |
| **Detection** | Loom | An object or face instance in a frame — type, label, frame number, and a bounding box **normalized 0–1** (the single geometry convention). |
| **Embedding** | Loom | A vector extracted from an asset (`dlib_facemark`, `inspireface`, …). Geometry is not duplicated here; it lives on the linked detection. |
| **Cluster** | Loom | A group of embeddings judged similar, with a unique name and a `type` (e.g. `person`). |
| **Face Cluster** | Loom | A Cluster of `type = person` over face embeddings — the "these frames show the same face" grouping; naming it links it to a Person via tags/collections. |
| **Person** | Loom | A named identity (firstname/lastname/alias) with an image gallery and a primary image. |
| **Dedup Group** | Loom | One candidate duplicate set awaiting human review: algorithm, score, status PENDING/CONFIRMED/REJECTED, and a denormalized keep-asset. The apply node acts only on CONFIRMED. |
| **Dedup Group Member** | Loom | Membership in a dedup group with role KEEP or DUP plus discovery-time size/zero-chunk snapshots. |
| **Search Document** | Loom | The materialized, weighted, ACL-projected search row per indexed entity. **Trigger-maintained only** — never hand-written; rebuild via `search_document_rebuild()`. |

---

## 6. Pipeline & Execution

| Term | Component | Description |
|---|---|---|
| **Pipeline** | Loom | The identity of a processing graph plus a pointer to its latest version. Name, definition and flags live on the version. |
| **Pipeline Version** | Loom | An immutable snapshot of a pipeline definition (graph JSONB, enabled, priority, dry-run), keyed `(pipeline_uuid, version_number)`. |
| **Definition** | Loom | The JSON graph: `version`, `nodes[]` and `edges[]`. Parsed by `PipelineGraphParser` into an executable `PipelineGraph`. |
| **Node** | Loom+Cortex | One processing step. In a definition it is a placed instance with an id, a **kind** and options; in Cortex it is the class that executes it. |
| **Node Kind** | Loom+Cortex | The string type of a node (`sha512`, `thumbnail`, `whisper`, `filter`, …). Registered in Cortex by a one-line `@Binds @IntoMap @StringKey` multibinding. |
| **Node Descriptor** | Loom | The design-time declaration of a kind — ports, groups, parameters, category, icon — served to the editor at `/api/v1/pipeline/node-descriptors`. |
| **Descriptor vs runnable kind** | Loom+Cortex | Not the same set. A kind can be placeable in the palette with no worker binding, or runnable with no descriptor. An unknown kind falls back to a **stub that reports success** — a green run can mean nothing ran. |
| **Source Node** | Cortex | A node with no inputs that enumerates media (`filesystem-source`, `s3-source`, `gdrive-source`, `asset-source`, …) and emits a `media` output port. |
| **Filter Node** | Cortex | The single `filter` kind. Its **buckets are ports**: an item leaves through one bucket port, alongside the always-present `other`, `passed` and `bucket` ports. |
| **Pipeline Run** | Loom | One execution of a pipeline version: status PENDING/RUNNING/PAUSED/SUCCESS/FAILED/PARTIAL/CANCELLED plus success/failure/skip counts. |
| **Run Item** | Loom | One media item discovered by the run's source node (media path + nullable sha512 = the pre-hash identity). **The item is the origin** for fan-out. |
| **Node Task** | Loom+Cortex | One node execution for one run item and element — leased, retried, dead-lettered. Idempotency key `(item_uuid, node_id, element_seq)`. |
| **Source Task / Segment Task** | Loom+Cortex | The two other dispatch units: enumerate a source, or run a chain of nodes as one worker-side unit. |
| **Segment** | Loom | A chain of nodes the segmenter packs into one `SEGMENT_TASK` so a worker runs them without a round trip. Broken at routing (selective/PASS/REJECT) edges; `SINGLE`-mode nodes only. |
| **Affinity** | Loom | The constraint that forces certain nodes onto the same worker (shared local files/state); validated by `AffinityValidator`, used by the segmenter. |
| **Cortex Instance / Processor** | Loom | A registered worker row keyed by its stable `node_id`, with host, priority, state and a node-kind whitelist/blacklist. |
| **Control Channel** | Cortex | The Cortex-side WebSocket to `/api/v1/processors/ws`: REGISTER, heartbeat, status, task dispatch and drain. Loom never dials out to Cortex. |
| **Drain** | Cortex | Graceful shutdown: announce TERMINATING, stop accepting, wait out running tasks, then hand back whatever remains as `TASK_RETURNED`. |
| **Node Whitelist / Blacklist** | Cortex | What a worker advertises it will accept. Defaults to the registered kinds, so a worker cannot claim work it cannot run; the blacklist always narrows. |
| **Variant C** | Platform | The current split: Loom owns the graph and dispatches one task at a time, Cortex is a dumb executor. Any text describing a Cortex-side DAG executor is legacy. |

---

## 7. Node Data Model (Ports & Payloads)

| Term | Component | Description |
|---|---|---|
| **Port** | Loom+Cortex | A named connector on a node (`PortSpec`: id, content type, cardinality, required, group). A node binds by **its own port**, never by an upstream node id. |
| **Content Type** | Loom+Cortex | The `family/subtype` id describing what a port carries — 8 families, 39 ids (`media/image`, `detection/face`, `hash/sha512`, `artifact/image`, `struct/json`, `control/filter`, …). |
| **Family / Wildcard** | Loom+Cortex | The part before the slash, and its `family/*` root. Assignability never crosses families, and sibling subtypes are never assignable. |
| **Cardinality** | Loom+Cortex | `ONE` or `MANY` on a port. A `MANY` input is what makes a node a gather; a `MANY` output is what makes downstream nodes fan out. |
| **Port Group** | Loom+Cortex | An alternative/exclusivity constraint over ports: `XOR` on inputs (exactly one wired), `EXCLUSIVE` on outputs (at most one wired). |
| **Dynamic Ports** | Loom+Cortex | Ports that only exist once the node is configured (`script`, `llm`, `vlm`, `filter`), resolved by a `NodePortResolver` from the node's options. |
| **Selective Port** | Loom | An output port declared as "written for some items only". A consumer wired solely to ports that did not fire is SKIPPED — **the port is the branch**, and selectivity is inherited down the branch. |
| **Edge** | Loom | A wire from `(source, sourcePort)` to `(target, targetPort)`, optionally carrying a `branch` (ANY/PASS/REJECT). Both port ids are mandatory — there is no positional fallback. |
| **Port Payload** | Loom+Cortex | A port's value on the wire: content type + cardinality + a list of elements. Never a bare object. |
| **Data Element** | Loom+Cortex | One value inside a payload, carrying its origin tag. |
| **Origin** | Loom+Cortex | `{itemId, seq, total}` on every element. The run item is the origin, which is why fan-out needs no lineage columns. |
| **Fan-out** | Loom | One upstream `MANY` output turning a `ONE`-input consumer into `PER_ELEMENT` mode — N tasks, one per element. |
| **Gather** | Loom | The implicit opposite: a `MANY` input waits for the whole upstream branch and runs **once** with all elements, seq-ordered. Neither word appears in the definition JSON — both fall out of the two cardinalities. |
| **Execution Mode** | Loom | `SINGLE` or `PER_ELEMENT` per node, computed at parse time in topological order. Nested fan-out and cross-driver zips are rejected. |
| **Media Ref** | Loom+Cortex | The resolvable media handle carried on every task (`mediaType` + derived `contentType()`); media is both ambient on the task **and** a declared port. |
| **Node Result** | Cortex | The unified result a node returns (`NodeResult` + `ResultState`), mapped to wire payloads and to the `asset_node_result` ledger. |
| **Value Coercion** | Cortex | Normalizing a value to its declared content type at both boundaries (`scalar/integer` always widens to `Long`), so a JSON round trip cannot reintroduce a `ClassCastException`. |

---

## 8. AI Agent

| Term | Component | Description |
|---|---|---|
| **Agent** | Loom | The in-Loom LLM chat loop (`loom/agent/chat`) with tools, skills, memory and a coding sandbox. Cortex has no agent. |
| **Chat** | Loom | One conversation: a JSONB message array, optionally scoped to a Space (which scopes SPACE-level memory). |
| **Chat Session** | Loom | The durable, publishable record behind a chat — AI-generated name/description, tags, pinned skill versions and a `/session` filesystem snapshot stored in an asset pool. |
| **Skill** | Loom | A user-owned agent capability, unique per `(creator, name)`. Publishable; an installed copy keeps a pointer to its origin skill. |
| **Skill Version** | Loom | An immutable snapshot of a skill body, keyed `(skill_uuid, version_number)` — what a chat session pins for reproducibility. |
| **Memory Entry** | Loom | A scoped markdown note in the agent memory bank, addressed by a path-like `memory_id` and scoped USER / GROUP / SPACE. |
| **Memory Deny Rule** | Loom | An admin-curated regex matched against every memory write; a hit rejects the write with the rule's message. |
| **Sandbox** | Loom | The coding sandbox backing a chat (podman or kubernetes), orchestrated by `loom/agent/sandbox` and reaped when idle. |
| **MCP** | Loom | Loom's Model Context Protocol server (JSON-RPC over HTTP+SSE/WS) exposing Loom tools to external AI clients. |

---

## 9. Collaboration

| Term | Component | Description |
|---|---|---|
| **Task** | Loom | A workflow item (title, status PENDING/REJECTED/ACCEPTED/REVIEW, priority, due date) attachable to assets and annotations. Unrelated to a pipeline **Node Task**. |
| **Comment** | Loom | A threaded comment on a task, asset or annotation. |
| **Reaction** | Loom | A social reaction/rating (e.g. thumbsup) on an asset, task, comment or annotation. |
| **Share / Share Link** | Loom | A capability URL that lets somebody with no Loom account view one asset or one collection. Optionally password-protected and time-limited. |
| **Slug** | Loom | The public half of a share link - 128 random bits in base64url, appearing in the URL in place of a uuid. |
| **Share Session** | Loom | The opaque token a share visitor holds after satisfying a link's password. Not a JWT, and not an account: it grants nothing by itself. |
| **Share Visitor** | Loom | Whoever opens a share link. Identified only by the name given on the first visit, which is stored on the share row - one link is one identity. |

---

## 10. Terms That Collide

Same word, different meaning — check the component column before assuming.

| Word | Meaning A | Meaning B |
|---|---|---|
| **Collection** | The Collection entity (folder of assets/clusters) | `tag.collection`, a varchar namespace in the tag uniqueness key |
| **Task** | Collaboration Task (`task` table, human workflow) | Node/Source/Segment Task (pipeline unit of work) |
| **Node** | A node instance in a pipeline definition | The Cortex class implementing a node kind |
| **Node ID** | `node.id` inside a pipeline definition (graph-local) | `CORTEX_NODE_ID`, the stable identity of a worker process |
| **Space** | The API and permission name | `project`, the actual DB table |
| **Binary** | The REST resource `/binaries` (an asset location) | The raw bytes stored in a pool |
| **CLI** | The top-level `cli/` native client | `cortex/cli`, a module that contains no command line |
| **Result** | `NodeResult` (what a node returns) | `asset_node_result` (the permanent per-asset ledger row) |
| **Origin** | `Origin` on a data element (`itemId`/`seq`/`total`) | `asset_node_result.origin` (COMPUTED/LOCAL/REMOTE) |

---

## Progress Assessment

- [x] Platform/component terms (Loom, Cortex, UI, CLI, sidecars, session runner, online/offline)
- [x] RBAC terms (user, group, role, permission, ACL, token, JWT)
- [x] Organization terms (space, library, collection, tag, both containment axes)
- [x] Asset & storage terms (asset, asset location, asset pool, binary, attachment)
- [x] Derived-data terms (asset component, node result ledger, detection, embedding, cluster,
      face cluster, person, dedup group, search document)
- [x] Pipeline terms (pipeline, version, run, run item, node task, segment, affinity, processor)
- [x] Port-model terms (port, content type, cardinality, port group, selective port, payload,
      element, origin, fan-out, gather, execution mode)
- [x] Agent terms (chat, chat session, skill, skill version, memory entry, sandbox, MCP)
- [x] Collision table for words that mean two things
- [ ] No terms yet for the plugin system, `services/image` / `services/video`, or the
      Helm/deployment vocabulary — those specs do not exist either
- [ ] Not cross-checked against the loom-ui TypeScript vocabulary (editor-only terms such as
      handle, palette, node inspector are absent)
- [ ] Term definitions are transcribed from the sibling specs, not re-derived from the code;
      a term that drifts in code drifts here one hop later

---

_Git HEAD revision: `d9bbc2dc`_
_Last updated: 2026-08-03 (initial glossary, derived from METALOOM.md, loom/LOOM.md, loom/DOMAIN.md, cortex/CORTEX.md and features/pipeline/NODE_DATA_TYPES.md)_
