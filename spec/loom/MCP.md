# MetaLoom // Loom MCP Server Specification

> The Model Context Protocol (MCP) server built into Loom: JSON-RPC 2.0 over
> HTTP+SSE and WebSocket, a Dagger-multibound tool registry, and the tools that
> expose assets, collections, pipelines and the agent memory bank to LLM clients.
>
> **Scope split:** this file owns the MCP *server, protocol and tool contract*.
> The chat agent that consumes the registry in-process is [ui/CHAT.md](ui/CHAT.md);
> the memory bank behind the four memory tools is
> [../features/chat/CHAT_MEMORY_PLAN.md](../features/chat/CHAT_MEMORY_PLAN.md);
> ports/options live in [SERVER.md](SERVER.md) and [CONFIGURATION.md](CONFIGURATION.md);
> permission names in [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md).

---

## 1. Overview

| Artifact         | Value                                                              |
|------------------|--------------------------------------------------------------------|
| MCP service      | `loom/services/mcp` (Maven module `loom-service-mcp`)               |
| Memory tools     | `loom/agent/memory` (contributes to the same tool set)              |
| Main class       | `io.metaloom.loom.mcp.MCPService`                                   |
| Default port     | `4041` (`ServerOptions.DEFAULT_MCP_PORT`, `LOOM_SERVER_MCP_PORT`)   |
| Protocol version | `2025-03-26` (`MCPConstants.MCP_PROTOCOL_VERSION`)                  |
| Server info      | `loom-mcp-server` / `1.0.0-SNAPSHOT` (hardcoded in the handler)     |
| Transports       | HTTP+SSE (`/mcp/sse` + `/mcp/message`) **and** WebSocket (`/mcp/ws`) — both implemented |

```mermaid
flowchart TD
  ext["External MCP client<br/>(LLM agent)"] -->|GET /mcp/sse<br/>POST /mcp/message| svc
  ext -->|WS /mcp/ws| svc
  chat["Chat agent (in-process)<br/>AgentLoop"] -->|dispatch(name, args, user, ctx)| reg
  svc["MCPService<br/>(own HTTP server, port 4041)"] --> auth["MCPAuthenticationHandler<br/>WebSocketAuthenticator"]
  svc --> rpc["MCPJsonRpcHandler<br/>(JSON-RPC 2.0, resolves MCPCallerContext)"]
  rpc --> reg["MCPToolRegistry<br/>(permission check + __loom strip)"]
  reg -->|EventBus mcp.tool.name| plain["Plain tools<br/>11 x loom/services/mcp"]
  reg -->|in-process execute(args, ctx)| ident["Identity-scoped tools<br/>2 x pipeline authoring<br/>4 x memory (loom/agent/memory)"]
  plain --> dao[("DaoCollection / DAOs")]
  plain --> auth2["PipelineAuthoringService<br/>(the one write path)"]
  ident --> auth2
  ident --> mem["MemoryService"]
  auth2 --> dao
```

**Key design decisions**

- **Transport decoupled from tools.** The JSON-RPC handler never calls a tool
  directly; plain tools are reached over the Vert.x EventBus (`mcp.tool.<name>`).
- **Dagger multibinding.** Tools are collected into `@MCPTools Set<MCPTool>` from
  *several* modules (`MCPToolModule`, `MemoryToolModule`), so a feature module can
  contribute tools — and contribute **none** when the feature is disabled.
- **Identity never travels on the EventBus.** Tools that need to know *who* asked
  declare `requiresIdentity()`; they get **no** EventBus address and are only
  reachable through `MCPToolRegistry.dispatch(name, args, user, ctx)`.
- **DAO-backed.** Tools inject `DaoCollection` (or `MemoryService`) — no REST
  round-trip.

---

## 2. Transports

### 2.1 HTTP + SSE (Streamable HTTP, MCP 2025-03-26)

| Endpoint | Method | Path           | Purpose                          |
|----------|--------|----------------|----------------------------------|
| SSE      | GET    | `/mcp/sse`     | Opens the persistent event stream |
| Message  | POST   | `/mcp/message` | Sends one JSON-RPC request        |

1. Client opens `GET /mcp/sse`; server replies `Content-Type: text/event-stream`
   (`Cache-Control: no-cache`, `Connection: keep-alive`) and immediately sends
   `event: endpoint` / `data: /mcp/message?sessionId=<uuid>`.
2. Client POSTs JSON-RPC to that URL. The HTTP body carries the response (200);
   when `sessionId` matches a live stream the same JSON is *also* pushed as an
   `event: message`.
3. Sessions live in a `ConcurrentHashMap<String, HttpServerResponse>`; the close
   handler removes them. No heartbeat/keepalive is sent.

**HTTP status codes** — `200` response, `202` for notifications (handler returned
`null`), `400` unparsable body (JSON-RPC `-32700` in the body), `401` failed auth,
`500` when the handler future itself fails. Tool *errors* are recovered into a
JSON-RPC error object with status `200`.

### 2.2 WebSocket

`GET /mcp/ws` upgrades via `rc.request().toWebSocket()`. Each text frame is one
JSON-RPC request; responses go back as text frames; notifications produce no frame.
Unparsable frames yield a `-32700` error frame. Auth (when enabled) runs on upgrade
through `WebSocketAuthenticator`, which closes with **4401** on failure.

### 2.3 Port

- `options().getServer().getMcpPort()` — `server.mcpPort` / `LOOM_SERVER_MCP_PORT`,
  default `4041`. Bind address from `server.bindAddress`.
- **Test mode:** `MCPService.start()` uses port `0` when `server.restPort == 0`
  (see [SERVER.md](SERVER.md) — `mcpPort: 0` alone does *not* trigger this).
- The MCP server is its own `HttpServer`, independent of the REST server.

---

## 3. JSON-RPC Protocol

Requests/responses are plain JSON-RPC 2.0. A request without `id` is a
notification: `MCPJsonRpcHandler.handle()` resolves to `null` and no response is
written.

| Method                      | Behaviour                                                     |
|-----------------------------|---------------------------------------------------------------|
| `initialize`                | Returns `protocolVersion`, `capabilities` (`tools.listChanged=true`, `resources.subscribe=false`, `resources.listChanged=false`), `serverInfo` |
| `notifications/initialized` | Logged, returns `null` (no response)                          |
| `ping`                      | Empty result object                                           |
| `tools/list`                | `{ "tools": [ descriptor.toJson(), … ] }`                     |
| `tools/call`                | `params.name` + `params.arguments` → registry dispatch        |
| `resources/list`            | `{ "resources": [] }` (stub)                                  |
| `resources/read`            | `-32601` "Resource reading not yet implemented"               |
| *anything else*             | `-32601` "Unknown method: …"                                  |

| Code   | Constant               | Meaning                                     |
|--------|------------------------|---------------------------------------------|
| -32700 | `ERR_PARSE_ERROR`      | Invalid JSON (HTTP body or WS frame)        |
| -32600 | `ERR_INVALID_REQUEST`  | Missing `method`                            |
| -32601 | `ERR_METHOD_NOT_FOUND` | Unknown method / `resources/read`           |
| -32602 | `ERR_INVALID_PARAMS`   | Missing `params` or `params.name`           |
| -32603 | `ERR_INTERNAL`         | Tool dispatch failure (incl. permission denial and unknown tool) |

---

## 4. Tool Model

### 4.1 `MCPTool`

```java
MCPToolDescriptor descriptor();
Future<JsonObject> execute(JsonObject arguments);
default Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) { return execute(arguments); }
```

Identity-scoped tools override the two-arg overload and make the one-arg overload
fail loudly (see `AbstractMemoryTool`) — reaching it would mean the caller identity
was lost.

### 4.2 `MCPToolDescriptor`

`record MCPToolDescriptor(String name, String description, JsonObject inputSchema,
List<String> requiredPermissions, boolean requiresIdentity)` — a 4-arg convenience
constructor defaults `requiresIdentity` to `false`.

- `toJson()` emits `name`, `description`, `inputSchema` and (when non-empty)
  `requiredPermissions`. **`requiresIdentity` is deliberately not serialized** — it
  is a server-side dispatch detail and the wire format must stay MCP-standard.
- `buildInputSchema(List<MCPToolParam>)` builds the JSON Schema.
  `MCPToolParam(name, type, description, required, enumValues)` — `enumValues`
  becomes a JSON Schema `enum` (used by the memory `scope` parameter); a 4-arg
  constructor leaves it null.

### 4.3 `MCPCallerContext` — server-resolved identity

`record MCPCallerContext(UUID userUuid, String userName, Set<UUID> groupUuids,
UUID spaceUuid, UUID chatUuid)`, plus `ANONYMOUS` and `isAuthenticated()`.

Every field is derived server-side. For **external** MCP clients the handler builds
it from the authenticated principal (`uuid`, `username`, groups via
`groupDao().loadGroupsForUser`) — `spaceUuid`/`chatUuid` stay `null`, so an external
client reaches user- and group-scoped data only. For the **chat agent** the loop
builds it from the chat (`AgentLoop.buildCallerContext`), adding space and chat.

Scope-like *arguments* may only act as filters over what this context resolves to.

### 4.4 `MCPToolRegistry`

1. Injected with `@MCPTools Set<MCPTool>` and `LoomAuthorizationProvider`;
   `register()`s each tool into a `ConcurrentHashMap`.
2. `register()` binds an EventBus consumer at `mcp.tool.<name>` **unless** the
   descriptor declares `requiresIdentity()` — those get no address at all.
3. `dispatch(name, args, user)` → `dispatch(name, args, user, ANONYMOUS)`.
   `dispatch(name, args, user, ctx)`:
   - unknown tool → failed future `"Unknown tool: …"`;
   - strips a caller-supplied `__loom` key (`CALLER_ENVELOPE_KEY`) from the
     arguments and logs a possible prompt-injection attempt;
   - `requiresIdentity` + unauthenticated caller → refuse;
   - if `user != null` **and** the tool declares permissions, all of them must
     match (`PermissionBasedAuthorization`), else `"Missing required permissions: …"`;
   - invokes in-process (identity tools) or over the EventBus (plain tools).
4. `unregister(name)` / `unregisterAll()` support runtime changes;
   `listDescriptors()` / `listToolNames()` / `getDescriptor(name)` for discovery.

### 4.5 Dagger wiring

| Class             | Role                                                                    |
|-------------------|-------------------------------------------------------------------------|
| `MCPModule`       | Provides `@Named("mcpRouter") Router`, `MCPAuthenticationHandler`, `WebSocketAuthenticator` |
| `MCPToolModule`   | `@ElementsIntoSet @MCPTools` — the 7 core tools                          |
| `MemoryToolModule`| `@ElementsIntoSet @MCPTools` — the 4 memory tools, **empty set** when `LOOM_AGENT_MEMORY_ENABLED=false` |
| `MCPTools`        | Qualifier for the tool set                                              |

All three are listed in `LoomCoreComponent`.

**Adding a tool:** implement `MCPTool` (`@Singleton`, inject `DaoCollection` or a
service), add it as a parameter of `MCPToolModule.mcpTools(...)` and to the returned
set. For a feature-gated tool, add a sibling `@ElementsIntoSet @MCPTools` module
that returns `Set.of()` when the feature is off, and register it in
`LoomCoreComponent`.

### 4.6 EventBus addresses

| Address               | Purpose                                                        |
|-----------------------|----------------------------------------------------------------|
| `mcp.tool.<toolName>` | Request/reply for plain tools (identity tools have none)        |
| `mcp.registry`        | Constant `EVENTBUS_TOOL_REGISTRY` — declared, not yet used      |

See [EVENTBUS.md](EVENTBUS.md) for the wider address map.

### 4.7 Result envelopes

Base MCP content format, built with `MCPToolResults.mcpTextResult(text)`:

```json
{ "content": [{ "type": "text", "text": "…" }] }
```

Two optional extras — external clients ignore them, the loom chat extracts them:

- **`references`** (`mcpResultWithReferences`) — the domain entities the result is
  about: `{"type":"asset","uuid":"…","label":"beach.mp4"}`. `type ∈ asset |
  collection | task | comment | pipeline | annotation | memory`; `label` is the
  filename / title / name. Rendered as entity chips ([ui/CHAT.md](ui/CHAT.md) §6).
- **`visuals`** (`mcpResult(text, references, visuals)` + `MCPToolResults.visual`)
  — renderable payloads drawn inline: `{"type":"pipeline-graph","uuid":"…",
  "label":"…","payload":{…}}`. Today only `pipeline-graph` exists.

Two rules keep visuals safe on any tool: **the text stays complete** (the model
never sees `visuals`, so dropping one never costs an answer) and **the payload is
bounded** (producer caps it — `GetPipelineTool.MAX_NODES`/`MAX_EDGES` — and
`VisualExtractor` caps count and encoded size again, [ui/CHAT.md](ui/CHAT.md) §6.1).

---

## 5. Registered Tools

Twenty-one tool implementations in total — the seventeen core tools always, the four
memory tools only when `LOOM_AGENT_MEMORY_ENABLED=true` (default `false`).

| Tool                | Class                  | Module   | Permissions       | Identity | References | Visual |
|---------------------|------------------------|----------|-------------------|----------|------------|--------|
| `search_assets`     | `SearchAssetsTool`     | mcp      | `READ_ASSET`      | no       | asset      | —      |
| `get_asset`         | `GetAssetTool`         | mcp      | `READ_ASSET`      | no       | asset      | —      |
| `search_transcript` | `SearchTranscriptTool` | mcp      | `READ_ASSET`      | no       | —          | —      |
| `list_collections`  | `ListCollectionsTool`  | mcp      | `READ_COLLECTION` | no       | collection | —      |
| `asset_statistics`  | `AssetStatisticsTool`  | mcp      | `READ_ASSET`      | no       | —          | —      |
| `list_pipelines`    | `ListPipelinesTool`    | mcp      | `READ_PIPELINE`   | no       | pipeline   | —      |
| `get_pipeline`      | `GetPipelineTool`      | mcp      | `READ_PIPELINE`   | no       | pipeline   | `pipeline-graph` |
| `list_node_descriptors` | `ListNodeDescriptorsTool` | mcp | `READ_PIPELINE`  | no       | —          | —      |
| `get_node_descriptor`   | `GetNodeDescriptorTool`   | mcp | `READ_PIPELINE`  | no       | —          | —      |
| `pipeline_authoring_guide` | `PipelineAuthoringGuideTool` | mcp | `READ_PIPELINE` | no  | —          | —      |
| `validate_pipeline` | `ValidatePipelineTool` | mcp      | `READ_PIPELINE` + `VALIDATE_MCP_PIPELINE` | no | — | —  |
| `create_pipeline`   | `CreatePipelineTool`   | mcp      | `CREATE_PIPELINE` + `CREATE_MCP_PIPELINE` | **yes** | pipeline | `pipeline-graph` |
| `update_pipeline`   | `UpdatePipelineTool`   | mcp      | `UPDATE_PIPELINE` + `UPDATE_MCP_PIPELINE` | **yes** | pipeline | `pipeline-graph` |
| `run_node_probe`    | `RunNodeProbeTool`     | mcp      | `READ_ASSET` + `EXECUTE_MCP_NODE` | **yes** | — | — |
| `run_node_graph`    | `RunNodeGraphTool`     | mcp      | `READ_ASSET` + `EXECUTE_MCP_NODE` | **yes** | — | `job-card` |
| `get_job`           | `GetJobTool`           | mcp      | `EXECUTE_MCP_NODE` | **yes** | — | `job-card` |
| `cancel_job`        | `CancelJobTool`        | mcp      | `EXECUTE_MCP_NODE` | **yes** | — | — |
| `list_memory`       | `ListMemoryTool`       | memory   | `READ_MEMORY`     | **yes**  | —          | —      |
| `get_memory`        | `GetMemoryTool`        | memory   | `READ_MEMORY`     | **yes**  | memory     | —      |
| `put_memory`        | `PutMemoryTool`        | memory   | `UPDATE_MEMORY`   | **yes**  | memory     | —      |
| `delete_memory`     | `DeleteMemoryTool`     | memory   | `DELETE_MEMORY`   | **yes**  | —          | —      |

The write surface is the two pipeline authoring tools plus the two memory writes;
everything else is read-oriented. The four execution tools are a third category: they write nothing
by default but they **spend worker and GPU time**, which is why `EXECUTE_MCP_NODE` is separate from
the authoring permissions — an operator can grant designing a pipeline without granting running one.
They are owned by [../chat/AGENTIC_NODE_EXECUTION.md](../chat/AGENTIC_NODE_EXECUTION.md); a rejection
from any of them is a tool result, never a failed future.

### 5.1 Asset & collection tools

| Tool | Parameters | Result | Known gaps |
|------|------------|--------|------------|
| `search_assets` | `query` (string), `mimeType` (string), `limit` (int, 25) | `Found N assets.` + JSON array (uuid, filename, mimeType, size, sha512) | `query`/`mimeType` are **accepted but ignored** — `assetDao.loadPage(null, limit, …)` returns an unfiltered page |
| `get_asset` | `assetId` (string, **required**) — UUID or SHA-512 via `AssetId.assetId()` | JSON object (uuid, filename, mimeType, size, sha512, initialOrigin, firstSeen, s3Bucket, s3ObjectPath) | Description promises media properties, geo and components; they are not returned. Missing asset → text result, not an error |
| `search_transcript` | `query` (string, **required**), `limit` (int, 10) | **Stub** text explaining that full-text search is unimplemented | Needs the search backend ([../features/search/SEARCH.md](../features/search/SEARCH.md)) |
| `list_collections` | `limit` (int, 25) | `Found N collections.` + JSON array (uuid, name) | No name filter, no space scoping |
| `asset_statistics` | `collection` (string) | JSON object: totalAssets, totalStorageBytes, totalStorageMB, images, videos, audio, documents, other | `collection` is **ignored**; loads up to 10 000 assets and aggregates in memory instead of using SQL aggregates |

### 5.2 Pipeline tools

`list_pipelines` — params `query`, `limit` (25). Emits uuid, name, description,
enabled, versionNumber, nodeCount taken from each pipeline's **latest version**
(resolved in one `loadByUuids`, not per row), plus one `pipeline` reference per row.
The node graph is deliberately omitted — one graph per row would swamp the context
window. `query` filters **in memory** over the loaded page, so it can only match
within the first `limit` pipelines.

`get_pipeline` — param `pipelineId` (**required**), a UUID *or* a pipeline name
(case-insensitive, falling back to a substring match over the first 200 pipelines).
Name resolution is not a convenience: the user asks for "the transcription
pipeline" and the model passes that phrasing straight through. Three renderings of
the same graph:

1. **Text** — header (name, uuid, description, version, enabled/dry-run/priority),
   node list `pn1 Media Source [filesystem-source, SOURCE]`, connection list
   `pn1.media -> pn2.video (branch PASS)`. This is all the model ever sees.
2. **Reference** — one `pipeline` chip.
3. **Visual** — `pipeline-graph` payload:

```json
{ "pipelineUuid": "…", "name": "…", "description": "…", "enabled": true, "versionNumber": 3,
  "nodes": [{ "id": "pn1", "kind": "filesystem-source", "label": "Media Source", "category": "SOURCE" }],
  "edges": [{ "source": "pn1", "sourcePort": "media", "target": "pn2", "targetPort": "video", "branch": "PASS" }],
  "truncated": false }
```

- Always the **latest version** — the one that would run today.
- `category` comes from `NodeDescriptorRegistry` (unknown kinds → `ANALYSIS`), so a
  node keeps the colour the pipeline editor gives it
  ([ui/PIPELINE_EDITOR.md](ui/PIPELINE_EDITOR.md)).
- Editor-only fields (`x`/`y`) and node options are stripped; the chat lays the
  graph out from the edges.
- Clipped at `MAX_NODES` (40) / `MAX_EDGES` (80); clipping sets `truncated`, which
  is stated in the text and shown on the card.

### 5.2a Pipeline authoring tools

Six tools that let an agent design a pipeline rather than only describe one:
*discover the node vocabulary → learn the format → check a draft → store it*.

| Tool | Parameters | Notes |
|------|------------|-------|
| `list_node_descriptors` | `category` (enum, from `NodeCategory`), `query`, `includePorts` (bool), `limit` (100) | One line per kind. **Projected, never dumped** — `NodeDescriptorEndpoint`'s full response is ~115 KB for 34 nodes. A clipped listing says so |
| `get_node_descriptor` | `kind` (**required**), `options` | Ports, port groups, `NodeParameter` options, node defaults, and whether an online worker offers the kind (`NodeAvailabilityService`) |
| `pipeline_authoring_guide` | — | Serves the `pipeline-authoring` built-in skill ([ui/CHAT.md §7](ui/CHAT.md)) |
| `validate_pipeline` | `definition` (**required**) | Dry run; stores nothing |
| `create_pipeline` | `name`, `definition` (**required**), `description`, `enabled`, `dryRun`, `priority` | Creates the pipeline + version 1 |
| `update_pipeline` | `pipelineId` (**required**, uuid *or* name), same optional fields | **Appends** a version; unset fields carry forward |

**`get_node_descriptor` resolves ports, it does not read them off the descriptor.**
`script`, `llm`, `vlm` and `filter` set `dynamicPorts` and derive their real ports from
the instance options, so the tool calls
`NodeDescriptorRegistry.resolvePorts(kind, options)` — the same call `PortGraphAnalyzer`
makes at save time. This is the **only** place in the system that serves resolved ports;
`NodeDescriptorEndpoint` serves the static descriptor only
([../features/pipeline/NODE_DATA_TYPES.md §3.4](../features/pipeline/NODE_DATA_TYPES.md)).

**One write path.** All three go through `PipelineAuthoringService`
(`loom/services/rest`), which `PipelineEndpointService.create`/`update` also call. The
seven-step create sequence and the `latest_version_uuid` repoint exist once, so a
pipeline the agent authored is indistinguishable from one drawn in the editor.

**A rejected definition is a result, not a failure.** `validate_pipeline` answers
`VALID` (possibly with warnings) or `INVALID: <message>` in a *succeeded* future, and
`create_pipeline` does the same when the definition is refused — a failed future
collapses into a `-32603` string the model cannot act on. Validation precedes the first
`store`, so a rejected create leaves **no row behind**.

**Warnings are never fatal**, and both come from a question save-time validation
deliberately does not fail on:

- kinds no online worker accepts (`PipelineEndpointService.unsupportedNodeKinds`) — a
  `503` at run time, a warning here, because the fleet will look different tomorrow;
- `AffinityValidator` warnings. Its fleet check is **skipped when unsupported kinds were
  already reported** — with nothing online, "no worker takes `sha512`" and "no worker
  takes `sha512` and `thumbnail` together" are the same news twice. The structural
  `GROUP_SPLIT` warnings still come through.

⚠️ `PipelineValidationService.validateDefinition` **skips port checking entirely when the
definition has no `edges` key** — the call sits inside `if (edges != null)`. A single-node
pipeline is legal, so this is deliberate, but a graph with edges is the checked path.

`PipelineGraphRenderer` owns uuid-or-name resolution, the graph projection, the text
rendering and the `MAX_NODES`/`MAX_EDGES` caps; `get_pipeline`, `create_pipeline` and
`update_pipeline` all use it, so a pipeline the agent just wrote is drawn exactly as one
it looked up.

### 5.3 Memory tools

Four identity-scoped tools over the agent memory bank; the bank itself (scopes,
frontmatter header, denylist, materialized `/memory` folder) is specified in
[../features/chat/CHAT_MEMORY_PLAN.md](../features/chat/CHAT_MEMORY_PLAN.md).

| Tool | Parameters | Notes |
|------|------------|-------|
| `list_memory` | `scope` (enum `user`\|`group`\|`space`\|`all`), `ref`, `prefix`, `limit` (50) | Ids, titles and write times only — not bodies |
| `get_memory` | `id` (**required**), `scope` (enum without `all`), `ref`, `includeHeader` (bool) | Shared-scope content is delimited and labelled with its author — data, not instructions |
| `put_memory` | `id` (**required**), `content` (**required**), `scope`, `ref`, `title` | The **only** way to change memory; the `/memory` folder is read-only. Provenance header added automatically |
| `delete_memory` | `id` (**required**), `scope`, `ref` | Permanent — no version history |

`scope` defaults to `user`; `ref` picks the group/space by name when several are
available; both are resolved against `MemoryService.scopes().resolve(ctx)`, so an
argument can only *narrow* what the caller already has. Because the tools require
identity, they are unreachable from an unauthenticated caller and have no EventBus
address. When `LOOM_AGENT_MEMORY_ENABLED=false` they are not registered at all and
never appear in `tools/list`.

---

## 6. Authentication and Permissions

Authentication is **off by default** and covers all three transports when enabled.

| Transport | Endpoint            | Accepted credentials                                            |
|-----------|---------------------|-----------------------------------------------------------------|
| SSE       | `GET /mcp/sse`      | `?token=<jwt>` **or** `Authorization: Bearer <jwt>`              |
| Message   | `POST /mcp/message` | `Authorization: Bearer <jwt>` **or** `X-API-Key: <token>`        |
| WebSocket | `GET /mcp/ws`       | `?token=<jwt>` (via `WebSocketAuthenticator`, close code 4401)   |

`MCPAuthenticationHandler` (in `loom/services/auth/auth-common`) tries the token as
a JWT first (`LoomAuthenticationHandler.authenticateToken`) and falls back to an API
key (`TokenDao.findByToken`, resolved to the token's `creator_uuid`). A token that
matches **neither** is rejected regardless of strict mode — strict/lenient only
governs requests with *no* credentials at all.

**CORS** — auth disabled: `Access-Control-Allow-Origin: *`. Auth enabled: the
`Origin` header is echoed (plus `Allow-Credentials: true`) when it matches
`allowedOrigins` (entries may contain `*` wildcards, e.g. `https://*.example.com`);
otherwise `*` is sent if the list contains `*`, else no CORS header is set.

**Permissions** — `MCPToolRegistry.dispatch()` checks
`descriptor.requiredPermissions()` with `PermissionBasedAuthorization` **only when a
`User` is present**. With auth disabled (or lenient mode and no credentials) `user`
is `null` and no permission check runs. `tools/list` exposes
`requiredPermissions` so clients can discover what a tool needs.

**`listDescriptorsFor(User)` — what may you use, not what exists.** `listDescriptors()`
answers the second question; an agent loop wants the first, and `AgentLoop.buildTools()`
therefore builds its tool definitions from `listDescriptorsFor(request.user())`.
Advertising a tool the caller will be refused on costs a turn — the model calls it, gets
a permission error back as a tool *result* rather than an aborted run, and often retries
— and, because the tool list is part of the prompt, a `create_pipeline` the user may not
use still reads as an invitation to author one. A `null` user returns everything, exactly
matching `dispatch`: a tool is advertised precisely when it would be permitted. If the
authorization lookup fails, only tools requiring **no** permission are listed (fail
closed).

⚠️ Not being *told* about a tool is not a control. `listDescriptorsFor` narrows the
prompt; `dispatch` is the gate. Both are tested, in
`MCPPipelineAuthoringTest.testUnprivilegedCallerIsNeitherToldNorAllowed`.

### 6.0a MCP-specific pipeline permissions

Four permissions gate pipeline work *through an agent*, separately from the
`*_PIPELINE` quad that gates the editor and the REST API:

| Permission | Gates |
|---|---|
| `CREATE_MCP_PIPELINE` | `create_pipeline` |
| `UPDATE_MCP_PIPELINE` | `update_pipeline` |
| `VALIDATE_MCP_PIPELINE` | `validate_pipeline` |
| `EXECUTE_MCP_NODE` | `run_node_probe`, `run_node_graph`, `get_job`, `cancel_job` |

Letting an agent write a pipeline is a different trust decision from letting a person
draw one, and an administrator has to be able to grant one without the other. The two
write tools declare the **base permission and the MCP one**, and `dispatch` requires
*all* declared permissions — so granting an MCP permission alone can never widen what a
user is able to do. `VALIDATE_MCP_PIPELINE` is separate because the dry run writes
nothing: an operator can hand the agent the design-and-check loop while withholding the
ability to store the result.

`EXECUTE_MCP_NODE` follows the same logic one step further: designing a pipeline and
*spending processing time* are different trust decisions, and it is the only permission
that lets a caller occupy the worker fleet. The probe and graph tools declare it
alongside `READ_ASSET`, so granting it alone can never widen what a user may read.

Added by `V2.76__mcp_pipeline_permissions.sql` and `V2.82__execute_mcp_node_permission.sql`;
UI group **Pipeline (assistant)** in `AdminArea.tsx`. See
[../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md).

### 6.1 Environment variables

| Option (config key)          | Environment variable            | Default | Description |
|------------------------------|---------------------------------|---------|-------------|
| `server.mcpPort`             | `LOOM_SERVER_MCP_PORT`          | `4041`  | MCP server port (`0` when `server.restPort` is `0`) |
| `server.bindAddress`         | `LOOM_SERVER_GRPC_BIND_ADDRESS` | `0.0.0.0` | Bind address shared with the other servers |
| `auth.mcpAuthEnabled`        | `LOOM_MCP_AUTH_ENABLED`         | `false` | Enable auth on all MCP endpoints |
| `auth.mcpAuthStrictMode`     | `LOOM_MCP_AUTH_STRICT_MODE`     | `false` | Reject requests without credentials (401 / WS 4401) |
| `auth.mcpAuthAllowedOrigins` | `LOOM_MCP_AUTH_ALLOWED_ORIGINS` | `*`     | Comma-separated CORS origins; validated non-blank when auth is enabled |
| `memory.enabled`             | `LOOM_AGENT_MEMORY_ENABLED`     | `false` | Off ⇒ the four memory tools are not registered and not advertised |

Full option reference: [CONFIGURATION.md](CONFIGURATION.md).

### 6.2 Security notes

- With auth disabled the port is unauthenticated and unauthorized — local
  development only. Even with auth on, `4041` should not face untrusted networks
  without firewall/VPN.
- Tool arguments are model-authored. Nothing in them participates in authorization;
  the `__loom` key is stripped and logged as a possible injection attempt.
- No rate limiting and no audit log of tool calls yet.

---

## 7. Lifecycle

`BootstrapInitializer.init()` starts MCP **after** the REST service, UI service and
the main HTTP server, and before the monitoring and gRPC services;
`deinit()` calls `mcpService.stop()` before `restService.stop()`.

`MCPService.start()`: resolve port → new `Router` with `BodyHandler` (**1 MB** body
limit, hardcoded) → register `/mcp/sse`, `/mcp/message`, `/mcp/ws` → create and
`listen()` the `HttpServer` (blocking `get()`).
`stop()`: `end()` every open SSE response, clear the session map, `server.close()`,
null the field. No graceful-shutdown timeout.

Tools register at construction time of `MCPToolRegistry` (Dagger singleton), i.e.
before the server accepts requests.

---

## 8. Integration

### 8.1 In-process (chat agent)

`AgentLoop` holds the `MCPToolRegistry` and:

- builds LLM tool definitions from `listDescriptors()`
  (`new ToolDefinition(name, description, inputSchema)`), then appends sandbox
  coding tools and `load_skill` — those are **not** MCP tools;
- dispatches with `dispatch(name, args, request.user(), callerContext)` where the
  context carries user, groups, space and chat;
- extracts `references`/`visuals` from the result and relays them on the SSE
  stream. See [ui/CHAT.md](ui/CHAT.md).

### 8.2 In-process (tests / other callers)

```java
MCPToolRegistry registry = mcpService.getToolRegistry();
JsonObject result = registry.dispatch("search_assets", args, null)
    .toCompletionStage().toCompletableFuture().get();
```

### 8.3 Remote clients

```
POST /mcp/message HTTP/1.1
Content-Type: application/json

{ "jsonrpc": "2.0", "method": "tools/list", "id": 1 }
```

Typical loop: `initialize` → `tools/list` → convert descriptors to the provider's
tool definitions → model emits calls → `tools/call` per call → feed results back.

---

## 9. Key Classes Reference

| Class                      | Package                                | Purpose |
|----------------------------|----------------------------------------|---------|
| `MCPService`               | `io.metaloom.loom.mcp`                 | HTTP server, SSE/WS endpoints, lifecycle |
| `MCPConstants`             | `io.metaloom.loom.mcp`                 | Paths, method names, error codes, EventBus prefixes |
| `MCPJsonRpcHandler`        | `io.metaloom.loom.mcp.handler`         | JSON-RPC dispatch; resolves `MCPCallerContext` for external clients |
| `MCPToolRegistry`          | `io.metaloom.loom.mcp.tool`            | Registration, permission check, EventBus/in-process dispatch |
| `MCPTool`                  | `io.metaloom.loom.mcp.tool`            | Tool interface (both `execute` overloads) |
| `MCPToolResults`           | `io.metaloom.loom.mcp.tool`            | `content` / `references` / `visuals` envelopes |
| `MCPToolDescriptor`        | `io.metaloom.loom.mcp.model`           | Descriptor record + `MCPToolParam` + schema builder |
| `MCPCallerContext`         | `io.metaloom.loom.mcp.model`           | Server-resolved caller identity |
| `JsonRpcRequest` / `JsonRpcResponse` | `io.metaloom.loom.mcp.model` | JSON-RPC 2.0 models |
| `MCPModule` / `MCPToolModule` / `MCPTools` | `io.metaloom.loom.mcp.dagger` | Infrastructure beans, core tool set, qualifier |
| `SearchAssetsTool`, `GetAssetTool`, `SearchTranscriptTool`, `ListCollectionsTool`, `AssetStatisticsTool`, `ListPipelinesTool`, `GetPipelineTool` | `io.metaloom.loom.mcp.tool.impl` | The 7 read tools |
| `ListNodeDescriptorsTool`, `GetNodeDescriptorTool`, `PipelineAuthoringGuideTool`, `ValidatePipelineTool`, `CreatePipelineTool`, `UpdatePipelineTool` | `io.metaloom.loom.mcp.tool.impl` | The 6 pipeline authoring tools |
| `PipelineGraphRenderer` | `io.metaloom.loom.mcp.tool.impl` | uuid-or-name resolution, graph projection, text rendering, `pipeline-graph` payload |
| `PipelineAuthoringService` | `io.metaloom.loom.rest.service.impl` | The single write path for definitions — REST and MCP both call it |
| `BuiltinSkills` | `io.metaloom.loom.common.skill` | Instruction packages that ship with Loom; source of the authoring guide |
| `AbstractMemoryTool`, `ListMemoryTool`, `GetMemoryTool`, `PutMemoryTool`, `DeleteMemoryTool` | `io.metaloom.loom.agent.memory.tool` | The 4 identity-scoped memory tools |
| `MemoryToolModule`         | `io.metaloom.loom.agent.memory.dagger` | Feature-gated contribution to `@MCPTools` |
| `MCPAuthenticationHandler` | `io.metaloom.loom.auth`                | MCP HTTP auth (JWT + API key) and CORS |
| `LoomAuthenticationHandler` / `LoomAuthorizationProvider` | `io.metaloom.loom.auth` | JWT auth / permission resolution (shared with REST) |
| `WebSocketAuthenticator`   | `io.metaloom.loom.rest.service.impl`   | WS token auth, close code 4401 |
| `TokenDao`                 | `io.metaloom.loom.db.model.token`      | API key lookup |
| `AgentLoop`                | `io.metaloom.loom.agent.chat.loop`     | In-process consumer of the registry |

---

## 10. Conventions and Gotchas

- **`requiresIdentity` is the security boundary.** Registering an EventBus address
  for such a tool would create an unauthenticated path to it. Never add one; never
  implement the one-arg `execute` for them with real behaviour.
- **Identity never comes from arguments.** `__loom` in the arguments is always a
  forgery attempt: stripped and logged. Scope-ish arguments (`scope`, `ref`,
  `collection`) may only filter what the context already resolves to.
- **`requiresIdentity` must not appear on the wire.** `toJson()` omits it on
  purpose — external MCP clients see a standard descriptor.
- **Permission checks are user-conditional.** No `User` ⇒ no check. Disabled auth
  therefore means full read access to every tool.
- **`MCPModule.mcpRouter` is unused.** `MCPService.start()` creates its own
  `Router`; the named bean is legacy wiring.
- **`restPort == 0` is what puts MCP on port 0**, not `mcpPort: 0`.
- **Body limit 1 MB, hardcoded** in `MCPService.start()`.
- **No SSE heartbeat** — idle streams can be dropped by intermediaries.
- **EventBus dispatch is node-local.** Despite the clustering rationale in the
  Javadoc, tool calls do not cross cluster nodes today ([../CLUSTERING.md](../concept/CLUSTERING.md)).
- **Tool descriptions are prompts.** They are handed verbatim to the model; word
  them as instructions to a model (`put_memory`'s "this is the ONLY way…" exists for
  that reason), and keep destructive semantics explicit. `update_pipeline`'s "the
  definition REPLACES the whole graph" is there because an agent that meant to add one
  node would otherwise delete the rest without noticing.
- **A rejected input is a result, not a tool failure.** A failed future becomes a
  `-32603` string; a model can act on `INVALID: node 'pn3' has no input port 'media'`
  and cannot act on that. Reserve failed futures for the tool being unable to answer.
- **Never dump a whole registry into a result.** `list_node_descriptors` projects to one
  line per kind for the same reason `list_pipelines` omits graphs — the full descriptor
  response is ~115 KB. When a listing is clipped, **say so**: a truncated list that reads
  as complete is how a model concludes a kind does not exist.
- **Advertise only what the caller may use.** Build tool lists from
  `listDescriptorsFor(user)`, never `listDescriptors()` (§6).
- **A write tool needs `requiresIdentity`.** Anything that stamps a creator must have
  one; the identity-free `execute` overload then fails loudly rather than writing a row
  with a null owner.
- **Text must stand alone.** `references`/`visuals` are optional decorations; a
  client that ignores them must still be able to answer from `content`.
- **Feature-gated tools return `Set.of()`** rather than registering a disabled stub —
  a disabled feature must not appear in `tools/list` at all.

---

## 11. Where do I find …?

| I need …                                    | Path |
|---------------------------------------------|------|
| HTTP server, SSE/WS routes, shutdown        | `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java` |
| JSON-RPC methods, initialize payload        | `…/mcp/handler/MCPJsonRpcHandler.java` |
| Paths, method names, error codes            | `…/mcp/MCPConstants.java` |
| Dispatch, permission check, `__loom` strip  | `…/mcp/tool/MCPToolRegistry.java` |
| Descriptor / schema / `enum` params         | `…/mcp/model/MCPToolDescriptor.java` |
| Caller identity record                      | `…/mcp/model/MCPCallerContext.java` |
| Result envelopes (`references`, `visuals`)  | `…/mcp/tool/MCPToolResults.java` |
| The 17 core tool implementations            | `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/impl/` |
| The shared pipeline write path              | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineAuthoringService.java` |
| The pipeline authoring guide (markdown)     | `loom/common/src/main/resources/skills/pipeline-authoring.md` |
| The 4 memory tools                          | `loom/agent/memory/src/main/java/io/metaloom/loom/agent/memory/tool/` |
| Tool set wiring                             | `…/mcp/dagger/MCPToolModule.java`, `…/agent/memory/dagger/MemoryToolModule.java`, `loom/core/…/dagger/LoomCoreComponent.java` |
| Auth handler (JWT, API key, CORS)           | `loom/services/auth/auth-common/src/main/java/io/metaloom/loom/auth/MCPAuthenticationHandler.java` |
| Port / auth options                         | `loom-shared/api/src/main/java/io/metaloom/loom/api/options/ServerOptions.java`, `AuthenticationOptions.java` |
| Startup / shutdown order                    | `loom/core/src/main/java/io/metaloom/loom/core/boot/BootstrapInitializer.java` |
| In-process consumer (chat)                  | `loom/agent/chat/src/main/java/io/metaloom/loom/agent/chat/loop/AgentLoop.java` |
| Tests                                       | `loom/services/mcp/src/test/java/…`, `loom/core/src/test/java/io/metaloom/loom/core/mcp/` |

---

## 12. Test Setup

Unit tests (module `loom-service-mcp`, no database):

| Test | Covers |
|------|--------|
| `MCPToolIdentityTest` | Identity tools get no EventBus address; plain tools do; context reaches the tool; anonymous callers refused; `__loom` stripped on both paths; unknown tool fails |
| `PipelineToolTest` | `get_pipeline` by uuid / name / partial name, graph + visual, unknown kind → `ANALYSIS`, truncation, descriptors, `list_pipelines` |
| `PipelineAuthoringToolTest` | `validate`/`create`/`update` against a **real** validator and descriptor registry: port errors name the port, a rejected create stores nothing, an update appends, `requiresIdentity` + the two-permission declaration, the identity-free `execute` fails loudly |
| `NodeDescriptorToolTest` | Listing projection, `category`/`query` filters, clipping reported not silent, resolved ports, availability, unknown kind |
| `MCPToolPermissionTest` | `listDescriptorsFor`: null user sees everything, a caller sees only what they hold, **all** declared permissions are required |

Integration tests (module `loom/core`, real PostgreSQL from the pooled test DB —
run `./setup-pool.sh` first):

| Test | Covers |
|------|--------|
| `MCPAuthDisabledTest` | Unauthenticated tool call succeeds when auth is off |
| `MCPAuthLenientTest` | Valid JWT, unprivileged JWT denied with structured error, missing credentials tolerated, API key path, `tools/list` exposes `requiredPermissions`, SSE `?token=`, CORS echo under wildcard |
| `MCPAuthStrictTest` | Message/SSE rejection without credentials, invalid token rejected, WS 4401 vs. valid-token round trip, CORS allow/deny |
| `MCPToolReferencesTest` | `references` on search/get asset, collections, pipelines; none for `asset_statistics`; `get_pipeline` visual present/absent |
| `MCPPipelineAuthoringTest` | Authoring end to end: descriptors + guide, validate against the real registry, create persists pipeline + version 1 + `latest_version_uuid`, a broken definition leaves no row, update appends, and an unprivileged caller is neither listed nor allowed |
| `MCPDirectToolCallTest` | Registry dispatch without HTTP, driven by an LLM tool-call loop |
| `MCPServerToolCallTest` | Full HTTP JSON-RPC flow: `initialize` + `tools/list` (no LLM needed), then a full LLM tool-call loop |

`MCPTestClient` and `MCPAuthTestSupport` provide the HTTP/WS/SSE client helpers,
JWT/API-key fixtures and canned JSON-RPC payloads.

The two LLM-driven tests call `LlmBackendAvailability.assumeRunning()` and are skipped
unless an OpenAI-compatible server serves `openai/gpt-oss-20b` at `http://127.0.0.1:8080/v1`.

---

## 13. Progress Assessment

### 13.1 Core protocol

- [x] JSON-RPC 2.0 request/response + notification handling (202 / no WS frame)
- [x] `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`
- [x] `resources/list` stub; `resources/read` returns method-not-found
- [x] Full error-code set (-32700 … -32603)
- [x] MCP content-format results
- [ ] Structured tool errors (failures collapse into `-32603` + a message string)
- [ ] Pagination metadata in tool results (total count / next cursor)

### 13.2 Transports

- [x] HTTP+SSE with session map, `endpoint` event and cleanup on close
- [x] WebSocket transport with full-duplex JSON-RPC
- [x] Auth on all three transports (JWT header/query, API key header, WS 4401)
- [x] Configurable CORS origins incl. wildcard subdomains
- [x] Port configurable (`LOOM_SERVER_MCP_PORT`), port-0 test mode
- [ ] SSE heartbeat/keepalive
- [ ] Configurable body limit (hardcoded 1 MB)
- [ ] Graceful shutdown timeout

### 13.3 Tool framework

- [x] Dagger multibinding across several modules (`MCPToolModule`, `MemoryToolModule`)
- [x] EventBus dispatch decoupled from transport
- [x] Identity-scoped dispatch (`requiresIdentity` + `MCPCallerContext`, no EventBus address)
- [x] `__loom` envelope stripping with injection warning
- [x] Permission declaration + check, surfaced in `tools/list`
- [x] `references` and `visuals` envelopes
- [x] `enum`-constrained tool parameters
- [x] Permission-filtered tool listing (`listDescriptorsFor`), fail-closed on lookup error
- [ ] Permission check skipped when no `User` is present (auth disabled ⇒ no authorization)
- [ ] Runtime (re)registration is possible but unused — no admin surface for it

### 13.4 Tools

- [x] `search_assets`, `get_asset`, `search_transcript` (stub), `list_collections`, `asset_statistics`
- [x] `list_pipelines`, `get_pipeline` (+ `pipeline-graph` visual)
- [x] `list_node_descriptors`, `get_node_descriptor` (resolved ports), `pipeline_authoring_guide`
- [x] `validate_pipeline` (dry run, warnings), `create_pipeline`, `update_pipeline`
- [x] `list_memory`, `get_memory`, `put_memory`, `delete_memory` (feature-gated)
- [ ] `search_assets` ignores `query` and `mimeType`
- [ ] `get_asset` returns none of the media/geo/component data its description promises
- [ ] `search_transcript` is a stub — needs the search backend
- [ ] `asset_statistics` ignores `collection` and aggregates 10 000 rows in memory
- [ ] `list_pipelines` filters `query` in memory over the loaded page
- [ ] No pipeline *operations* (run, cancel, status, events) — authoring only
- [ ] No `delete_pipeline`, and no restore of an earlier version
- [ ] No write tools for assets, tags, tasks, comments or annotations
- [ ] No tools for users/roles/groups, embeddings, GraphQL, processor status
- [ ] No visual types beyond `pipeline-graph` (asset previews, run timelines, charts)

### 13.5 Resources

- [x] `resources/list` empty stub, `resources/read` error
- [ ] No resource providers (assets/collections as MCP resources)
- [ ] No `resources/subscribe` / `unsubscribe`

### 13.6 Testing

- [x] Identity/dispatch unit tests (`MCPToolIdentityTest`)
- [x] Pipeline tool unit tests incl. truncation (`PipelineToolTest`)
- [x] SSE, WebSocket and message-endpoint auth tests (disabled / lenient / strict)
- [x] CORS allow + deny tests
- [x] Reference/visual envelope tests against a real database
- [x] HTTP `initialize` + `tools/list` without an LLM
- [x] Optional LLM tool-call loops (registry-direct and over HTTP)
- [x] Pipeline authoring against a real database, incl. permission denial (`MCPPipelineAuthoringTest`)
- [x] Permission-filtered listing (`MCPToolPermissionTest`)
- [ ] No tests for the memory tools at the MCP layer (covered only via `MemoryServiceTest` / `AgentLoopTest`)
- [ ] No tests for malformed JSON / unknown method / invalid tool arguments over the wire
- [ ] No concurrency or runtime register/unregister tests

### 13.7 Operations

- [x] Started/stopped by `BootstrapInitializer`; own port, own HTTP server
- [x] `getToolRegistry()` / `getServer()` exposed for in-process callers and tests
- [ ] No health endpoint for the MCP server
- [ ] No metrics (call count, latency, error rate) — see [../features/ops/METRICS.md](../features/ops/METRICS.md)
- [ ] No audit log of tool calls; no rate limiting
- [ ] EventBus dispatch is node-local (no clustered tool execution)

### 13.8 Documentation

- [x] This file; Javadoc on the public MCP classes; `MCPConstants` documents methods/codes
- [ ] No example curl/client script in the docs
- [ ] No client SDK (raw JSON-RPC only)

---

## 14. Relation to the REST API

Same DAOs, otherwise independent services — see [RESTAPI.md](RESTAPI.md).

| Concern        | REST API                         | MCP server |
|----------------|----------------------------------|------------|
| Port           | 6333                             | 4041 (`LOOM_SERVER_MCP_PORT`) |
| Protocol       | HTTP REST (`/api/v1`)            | JSON-RPC 2.0 (`/mcp/sse`, `/mcp/message`, `/mcp/ws`) |
| Auth           | JWT cookie + OAuth2 + API tokens | JWT (header/query), API key (header), strict/lenient, off by default |
| Authorization  | Endpoint permission checks       | `requiredPermissions` in `MCPToolRegistry.dispatch()` — only when a `User` exists |
| Data access    | `*EndpointService` → DAO         | Tool → `DaoCollection` / `MemoryService` |
| Body limit     | Unlimited (`-1`)                 | 1 MB |
| CORS           | All origins, all methods         | `LOOM_MCP_AUTH_ALLOWED_ORIGINS` |
| Write surface  | Full CRUD                        | Pipeline create/update and the memory bank; everything else read-only |
| WebSocket      | Processor + pipeline events ([WEBSOCKET.md](WEBSOCKET.md)) | MCP JSON-RPC frames |

Shared infrastructure: `LoomAuthenticationHandler`, `LoomAuthorizationProvider`,
`WebSocketAuthenticator`, `TokenDao`.

---
_Git HEAD revision: `a63b034b`_
_Last updated: 2026-08-06 (pipeline authoring tools, MCP pipeline permissions, permission-filtered tool listing)_