# MetaLoom // Loom MCP Server Specification

> This document describes the Model Context Protocol (MCP) server built into
> Loom. It is intended to be consumed by AI coding agents and developers who
> need to understand, extend, or integrate with the MCP feature.
>
> The progress checklist at the end tracks areas that still need improvement.

---

## 1. Overview

The MCP server exposes Loom's asset library, collections, and search
capabilities to AI assistants (LLM agents) via the Model Context Protocol
(JSON-RPC 2.0 over HTTP+SSE or WebSocket). An AI agent can discover
available tools, invoke them, and receive structured results that it can
use to answer user questions about the content stored in Loom.

### 1.1 Module Location

| Artifact            | Path                                    |
|---------------------|-----------------------------------------|
| MCP Service         | `loom/services/mcp`                     |
| Main class          | `io.metaloom.loom.mcp.MCPService`       |
| Default port        | `4041` (`ServerOptions.DEFAULT_MCP_PORT`, `LOOM_SERVER_MCP_PORT`) |
| Protocol version    | `2025-03-26` (`MCP_PROTOCOL_VERSION`)  |

### 1.2 Architecture

```
                     AI Agent / LLM Client
                    /                     \
              HTTP + SSE              WebSocket
            (POST /mcp/message)     (/mcp/ws)
                    \                     /
                     \                   /
                      MCPJsonRpcHandler
                      (JSON-RPC 2.0)
                             |
                      MCPToolRegistry
                      (EventBus dispatch)
                             |
                    EventBus: mcp.tool.<name>
                             |
                   ┌─────────┴─────────┐
                   │                   │
             SearchAssetsTool    GetAssetTool  ... (7 tools)
             (DAO-backed)        (DAO-backed)
```

**Key design decisions:**

- **Transport decoupled from tools** — The JSON-RPC handler never calls tool
  implementations directly. Instead, it dispatches via the Vert.x EventBus
  (`mcp.tool.<name>`). This allows tools to be registered/unregistered at
  runtime and, in a clustered deployment, to run on different nodes.
- **Dagger multibinding** — Tools are collected into a `Set<MCPTool>` via
  `@MCPTools` qualifier and `MCPToolModule`. Adding a new tool requires only
  adding it to the module's `mcpTools` method.
- **DAO-backed** — Each tool injects `DaoCollection` and queries the database
  directly. No REST API round-trip is needed; tools operate at the DAO layer.

---

## 2. Transports

### 2.1 HTTP + SSE (Streamable HTTP)

This is the MCP 2025-03-26 standard transport.

| Endpoint                | Method | Path              | Purpose                                  |
|-------------------------|--------|-------------------|------------------------------------------|
| SSE stream              | GET    | `/mcp/sse`        | Opens a persistent SSE connection        |
| JSON-RPC message        | POST   | `/mcp/message`    | Sends a JSON-RPC request                 |

**Flow:**

1. Client opens a GET connection to `/mcp/sse`.
2. Server responds with `Content-Type: text/event-stream` and sends an
   `endpoint` event containing the URL to POST messages to:
   ```
   event: endpoint
   data: /mcp/message?sessionId=<uuid>
   ```
3. Client sends JSON-RPC requests via POST to `/mcp/message?sessionId=<uuid>`.
4. The HTTP response body contains the JSON-RPC response (status 200).
5. The same response is also pushed to the SSE stream as a `message` event,
   so other listeners on the SSE connection receive it.

**SSE session lifecycle:**

- Sessions are stored in a `ConcurrentHashMap<String, HttpServerResponse>`.
- When the SSE connection closes, the session is removed.
- The `sessionId` is a random UUID generated per connection.

### 2.2 WebSocket

| Endpoint | Path     | Purpose                                  |
|----------|----------|------------------------------------------|
| WS       | `/mcp/ws` | Full-duplex JSON-RPC over a single connection |

**Flow:**

1. Client initiates a WebSocket upgrade at `/mcp/ws`.
2. Each text frame is parsed as a JSON-RPC request.
3. The response is sent back as a text frame on the same WebSocket.
4. Notifications (requests without an `id`) do not produce a response frame.

The WebSocket transport is useful for tools that may produce incremental
results or for clients that prefer a single bidirectional connection.

### 2.3 Port Configuration

- Configurable via `options().getServer().getMcpPort()` — set through the
  `server.mcpPort` config key or the `LOOM_SERVER_MCP_PORT` environment
  variable. Default: `4041` (`ServerOptions.DEFAULT_MCP_PORT`).
- When the REST server port is set to `0` (test mode), the MCP server also
  uses port `0` (OS-assigned random port). This is determined by checking
  `options().getServer().getRestPort() == 0`.
- The bind address is taken from `options().getServer().getBindAddress()`.
- The MCP server runs on a **separate HTTP server** from the REST API
  (which listens on the REST port, typically `6333`).

---

## 3. JSON-RPC Protocol

### 3.1 Request Format

All requests follow JSON-RPC 2.0:

```json
{
  "jsonrpc": "2.0",
  "method": "<method-name>",
  "id": <number-or-string>,
  "params": { ... }
}
```

Requests without an `id` field are treated as notifications (no response
is sent, though the handler returns an empty response for pipeline
consistency).

### 3.2 Response Format

**Success:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": { ... }
}
```

**Error:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32601,
    "message": "Unknown method: foo"
  }
}
```

### 3.3 Supported Methods

| Method                  | Description                                      |
|-------------------------|--------------------------------------------------|
| `initialize`            | Handshake — returns protocol version, capabilities, and server info |
| `notifications/initialized` | Client notification after initialization (no response) |
| `ping`                  | Health check — returns empty result              |
| `tools/list`            | Lists all registered tools with their descriptors |
| `tools/call`            | Invokes a tool by name with arguments            |
| `resources/list`        | Returns empty resource list (stubbed)            |
| `resources/read`        | Returns error — not yet implemented              |

### 3.4 Error Codes

| Code   | Constant              | Meaning                          |
|--------|-----------------------|----------------------------------|
| -32700 | `ERR_PARSE_ERROR`     | Invalid JSON                     |
| -32600 | `ERR_INVALID_REQUEST` | Missing method or malformed request |
| -32601 | `ERR_METHOD_NOT_FOUND`| Unknown JSON-RPC method          |
| -32602 | `ERR_INVALID_PARAMS`  | Missing required parameters      |
| -32603 | `ERR_INTERNAL`        | Internal server error / tool failure |

### 3.5 Initialize Response

The `initialize` method returns:

```json
{
  "protocolVersion": "2025-03-26",
  "capabilities": {
    "tools": { "listChanged": true },
    "resources": { "subscribe": false, "listChanged": false }
  },
  "serverInfo": {
    "name": "loom-mcp-server",
    "version": "1.0.0-SNAPSHOT"
  }
}
```

---

## 4. Tool Registry and Dispatch

### 4.1 MCPTool Interface

Every tool implements `io.metaloom.loom.mcp.tool.MCPTool`:

```java
public interface MCPTool {
    MCPToolDescriptor descriptor();
    Future<JsonObject> execute(JsonObject arguments);
}
```

- `descriptor()` returns the tool name, description, and JSON Schema for
  parameters (used in `tools/list` responses).
- `execute()` receives the arguments from the MCP client and returns a
  `Future<JsonObject>` containing the MCP content-format result.

### 4.2 MCPToolDescriptor

A `record` with four fields:

| Field                 | Type       | Description                                    |
|-----------------------|------------|------------------------------------------------|
| `name`                | `String`   | Unique tool name (e.g. `"search_assets"`)      |
| `description`         | `String`   | Human-readable description                     |
| `inputSchema`         | `JsonObject` | JSON Schema describing accepted parameters   |
| `requiredPermissions` | `List<String>` | Permissions required to invoke this tool (e.g., `["READ_ASSET"]`) |

The `buildInputSchema(List<MCPToolParam>)` helper constructs the schema from
a list of `MCPToolParam(name, type, description, required)` records.

The `toJson()` method includes `requiredPermissions` in the output when present,
allowing clients to discover permission requirements.

### 4.3 MCPToolRegistry

The registry:

1. Receives all `MCPTool` instances via Dagger injection (`@MCPTools Set<MCPTool>`).
2. For each tool, stores it in a `ConcurrentHashMap` and registers an EventBus
   consumer at address `mcp.tool.<name>`.
3. `dispatch(toolName, arguments)` sends a request on the EventBus to the
   tool's consumer and returns the reply as a `Future<JsonObject>`.
4. Supports runtime registration/unregistration of tools.

### 4.4 Dagger Wiring

| Class           | Role                                                |
|-----------------|-----------------------------------------------------|
| `MCPModule`     | Provides the `Router` bean (named `"mcpRouter"`)    |
| `MCPToolModule` | Provides the `Set<MCPTool>` via `@ElementsIntoSet`  |
| `MCPTools`      | Qualifier annotation for the tool set               |
| `MCPService`    | Starts/stops the HTTP server, wires routes          |
| `MCPJsonRpcHandler` | Parses JSON-RPC, dispatches to tool registry     |

To add a new tool:

1. Create a class implementing `MCPTool` in `io.metaloom.loom.mcp.tool.impl`.
2. Annotate it with `@Singleton` and inject `DaoCollection` (or other deps).
3. Add the new tool class as a parameter to `MCPToolModule.mcpTools(...)`.
4. The Dagger-generated component will inject it automatically.

### 4.5 EventBus Addresses

| Address                  | Purpose                                |
|--------------------------|----------------------------------------|
| `mcp.tool.<toolName>`    | Request/reply for tool execution       |
| `mcp.registry`           | Tool registration notifications (future use) |

---

## 5. Registered Tools

### 5.0 Reference envelopes

Besides the standard MCP `content` items, tool results may carry an additional
`references` array which lists the loom domain entities the result is about:

```json
{ "content": [{ "type": "text", "text": "…" }],
  "references": [{ "type": "asset", "uuid": "…", "label": "beach.mp4" }] }
```

`type ∈ asset | collection | task | comment | pipeline | annotation`; `label` is
the filename / title / name. External MCP clients simply ignore the extra field;
the loom chat agent ([ui/CHAT.md](ui/CHAT.md) §6) extracts it to render entity
chips for tool results. Built via `MCPToolResults.mcpResultWithReferences(...)`.
Currently populated by `search_assets`, `get_asset`, `search_transcript`,
`list_collections`, `list_pipelines` and `get_pipeline`; `asset_statistics`
carries no references.

### 5.0.1 Visual envelopes

A result may additionally carry a `visuals` array — renderable payloads the chat
draws **inline** instead of only describing in text:

```json
{ "content": [{ "type": "text", "text": "Pipeline: Media Transcription…" }],
  "references": [{ "type": "pipeline", "uuid": "…", "label": "Media Transcription" }],
  "visuals": [{ "type": "pipeline-graph", "uuid": "…", "label": "Media Transcription",
                "payload": { "nodes": [ … ], "edges": [ … ] } }] }
```

`type` discriminates the payload shape; today only `pipeline-graph` exists
(produced by `get_pipeline`, §5.7). Built via
`MCPToolResults.mcpResult(text, references, visuals)` + `MCPToolResults.visual(...)`.

Two rules make this safe to attach to any tool:

- **The text stays complete.** The model never sees `visuals` — the agent loop feeds
  it the `content` text only. A visual may therefore be dropped (payload too large,
  unknown type, non-loom client) without costing an answer.
- **The payload is bounded.** It is relayed on `tool_end` and persisted onto the chat
  transcript, so the producing tool caps it (`GetPipelineTool.MAX_NODES` /
  `MAX_EDGES`) and the consuming `VisualExtractor` caps count and encoded size again
  ([ui/CHAT.md](ui/CHAT.md) §6.1).

### 5.1 search_assets

| Field        | Value |
|--------------|-------|
| Name         | `search_assets` |
| Description  | Search for assets by filename, MIME type, tags, or metadata. Returns a paginated list of matching assets with key metadata fields. |
| Class        | `SearchAssetsTool` |
| Required Permissions | `READ_ASSET` |

**Parameters:**

| Parameter  | Type    | Required | Description                                    |
|------------|---------|----------|------------------------------------------------|
| `query`    | string  | No       | Free-text search query (filename, origin, metadata) |
| `mimeType` | string  | No       | Filter by MIME type (e.g. `image/jpeg`, `video/*`) |
| `limit`    | integer | No       | Maximum results (default: 25)                  |

**Result:** Text content with a JSON array of asset summaries (uuid,
filename, mimeType, size, sha512).

**Current limitation:** The `query` and `mimeType` parameters are accepted
but not yet wired to DAO-level filtering. The tool currently loads a page
of assets without applying filters.

### 5.2 get_asset

| Field        | Value |
|--------------|-------|
| Name         | `get_asset` |
| Description  | Load complete metadata for a single asset, including file info, hashes, media properties, geo location, and components. |
| Class        | `GetAssetTool` |
| Required Permissions | `READ_ASSET` |

**Parameters:**

| Parameter  | Type   | Required | Description                        |
|------------|--------|----------|------------------------------------|
| `assetId`  | string | Yes      | Asset UUID or SHA-512 hash         |

**Result:** Text content with full asset metadata (uuid, filename, mimeType,
size, sha512, initialOrigin, firstSeen, s3Bucket, s3ObjectPath).

### 5.3 search_transcript

| Field        | Value |
|--------------|-------|
| Name         | `search_transcript` |
| Description  | Search across transcripts and extracted text from all assets. |
| Class        | `SearchTranscriptTool` |
| Required Permissions | `READ_ASSET` |

**Parameters:**

| Parameter  | Type    | Required | Description                                    |
|------------|---------|----------|------------------------------------------------|
| `query`    | string  | Yes      | Text to search for in transcripts/documents    |
| `limit`    | integer | No       | Maximum results (default: 10)                  |

**Result:** Text content with matching text snippets and asset references.

**Current limitation:** Returns a stub response. Full-text search requires
Elasticsearch/Lucene integration that is not yet implemented.

### 5.4 list_collections

| Field        | Value |
|--------------|-------|
| Name         | `list_collections` |
| Description  | List available asset collections. Collections group assets for spaces or topics. |
| Class        | `ListCollectionsTool` |
| Required Permissions | `READ_COLLECTION` |

**Parameters:**

| Parameter  | Type    | Required | Description                                       |
|------------|---------|----------|---------------------------------------------------|
| `limit`    | integer | No       | Maximum collections to return (default: 25)       |

**Result:** Text content with a JSON array of collections (uuid, name).

### 5.5 asset_statistics

| Field        | Value |
|--------------|-------|
| Name         | `asset_statistics` |
| Description  | Get aggregate statistics about the asset library: total count, storage used, MIME type distribution. |
| Class        | `AssetStatisticsTool` |
| Required Permissions | `READ_ASSET` |

**Parameters:**

| Parameter    | Type   | Required | Description                                       |
|--------------|--------|----------|---------------------------------------------------|
| `collection` | string | No       | Optional collection UUID to scope statistics       |

**Result:** Text content with a JSON object containing totalAssets,
totalStorageBytes, totalStorageMB, images, videos, audio, documents, other.

**Current limitation:** The `collection` parameter is accepted but not yet
used for scoping. Statistics are computed by loading a page of up to 10,000
assets and aggregating in memory. This should use SQL aggregate queries.

### 5.6 list_pipelines

| Field        | Value |
|--------------|-------|
| Name         | `list_pipelines` |
| Description  | List the media processing pipelines with name, description, uuid and enabled state. |
| Class        | `ListPipelinesTool` |
| Required Permissions | `READ_PIPELINE` |

**Parameters:**

| Parameter  | Type    | Required | Description                                            |
|------------|---------|----------|--------------------------------------------------------|
| `query`    | string  | No       | Case-insensitive filter on pipeline name or description |
| `limit`    | integer | No       | Maximum number of pipelines (default: 25)              |

**Result:** Text content with a JSON array (uuid, name, description, enabled,
versionNumber, nodeCount) plus a `pipeline` reference per row. Metadata comes from
each pipeline's **latest version**, resolved in one `loadByUuids` query rather than
per row. The node graph is deliberately **not** included — one graph per row would
swamp the context window; `get_pipeline` loads the one the user asked about.

**Current limitation:** `query` filters in memory over the loaded page rather than
at DAO level, so it can only match within the first `limit` pipelines.

### 5.7 get_pipeline

| Field        | Value |
|--------------|-------|
| Name         | `get_pipeline` |
| Description  | Load one pipeline including its node graph (nodes and port-to-port connections). |
| Class        | `GetPipelineTool` |
| Required Permissions | `READ_PIPELINE` |

**Parameters:**

| Parameter    | Type   | Required | Description                                    |
|--------------|--------|----------|------------------------------------------------|
| `pipelineId` | string | Yes      | Pipeline UUID **or** pipeline name (case-insensitive, falls back to a substring match) |

Name resolution is not a convenience: a user asks for "the transcription pipeline",
and the model passes that phrasing straight through.

**Result:** three renderings of the same graph —

1. **Text** — header (name, uuid, description, version, enabled/dry-run/priority), a
   node list `pn1 Media Source [filesystem-source, SOURCE]`, and a connection list
   `pn1.media -> pn2.video (branch PASS)`. This is all the model ever sees.
2. **Reference** — one `pipeline` entity chip.
3. **Visual** — a `pipeline-graph` payload (§5.0.1) which the chat renders as a
   compact diagram ([ui/CHAT.md](ui/CHAT.md) §6.1):

```json
{ "pipelineUuid": "…", "name": "…", "description": "…", "enabled": true, "versionNumber": 3,
  "nodes": [{ "id": "pn1", "kind": "filesystem-source", "label": "Media Source", "category": "SOURCE" }],
  "edges": [{ "source": "pn1", "sourcePort": "media", "target": "pn2", "targetPort": "video", "branch": "PASS" }],
  "truncated": false }
```

- The graph is that of the **latest version** — the one that would run today, which is
  what "the current pipeline" means to whoever is asking.
- `category` is resolved through the `NodeDescriptorRegistry` (unknown kinds fall back
  to `ANALYSIS`), so a node keeps the colour the pipeline editor gives it.
- Editor-only fields (`x`/`y`) and node options are stripped; the chat lays the graph
  out itself from the edges (a canvas layout does not fit a chat bubble).
- Clipped at `MAX_NODES` (40) / `MAX_EDGES` (80); clipping sets `truncated`, which is
  stated in the text and shown on the card.

---

## 6. Tool Result Format

All tools return results in the MCP content format:

```json
{
  "content": [
    {
      "type": "text",
      "text": "<human-readable or JSON string>"
    }
  ]
}
```

The `MCPToolResults.mcpTextResult(String)` helper wraps a text string in this
format; `mcpResultWithReferences(...)` and `mcpResult(...)` add the `references` and
`visuals` envelopes of §5.0 / §5.0.1 on top of it.

---

## 7. Authentication

### 7.1 Current State

**The MCP server implements authentication across all transports.**

Authentication is disabled by default and can be enabled via the
`LOOM_MCP_AUTH_ENABLED` environment variable (or `mcp.auth.enabled` in
configuration). When enabled, the following authentication mechanisms are
supported:

| Transport | Endpoint | Authentication Methods |
|-----------|----------|------------------------|
| SSE | `GET /mcp/sse` | `?token=<jwt>` query parameter OR `Authorization: Bearer <jwt>` header |
| Message | `POST /mcp/message` | `Authorization: Bearer <jwt>` header OR `X-API-Key` header |
| WebSocket | `GET /mcp/ws` | `?token=<jwt>` query parameter (via `WebSocketAuthenticator`) |

### 7.2 Configuration

| Option | Environment Variable | Default | Description |
|--------|---------------------|---------|-------------|
| `mcp.auth.enabled` | `LOOM_MCP_AUTH_ENABLED` | `false` | Enable authentication on all MCP endpoints |
| `mcp.auth.strictMode` | `LOOM_MCP_AUTH_STRICT_MODE` | `false` | Require valid credentials on every request (reject unauthenticated) |
| `mcp.auth.allowedOrigins` | `LOOM_MCP_AUTH_ALLOWED_ORIGINS` | `*` | Comma-separated list of allowed origins for SSE CORS |

When `strictMode` is `false` (lenient mode), requests without valid credentials
are accepted but logged with a warning. When `strictMode` is `true`, requests
without valid credentials are rejected with HTTP 401 (or WS close code 4401).

### 7.3 Implementation Details

The authentication infrastructure reuses existing components:

1. **`MCPAuthenticationHandler`** — New handler in `loom/services/auth-common` that:
   - Extracts credentials from HTTP requests (query params, headers)
   - Validates JWT tokens via `LoomAuthenticationHandler.authenticateToken()`
   - Validates API keys via `TokenDao.findByToken()`
   - Applies CORS headers based on `allowedOrigins` configuration

2. **`WebSocketAuthenticator`** — Reused from REST WebSocket endpoints
   (`loom/services/rest`) to validate `?token=` query parameter on WS upgrade

3. **`LoomAuthorizationProvider`** — Reused for permission checking via
   Vert.x `PermissionBasedAuthorization`

4. **`MCPToolDescriptor.requiredPermissions`** — Each tool declares required
   permissions (e.g., `READ_ASSET`, `READ_COLLECTION`) that are checked
   before dispatch in `MCPToolRegistry.dispatch()`

### 7.4 Security Implications

- When authentication is **disabled** (default), the MCP server behaves as
  before — no auth checks, CORS allows all origins. Suitable for local
  development only.
- When authentication is **enabled**, all endpoints require valid credentials.
  The MCP port (4041) should still not be exposed to untrusted networks
  without additional network-level security (firewall, VPN, etc.).
- Tools access DAOs directly but now go through permission checks in
  `MCPToolRegistry.dispatch()` before execution.

---

## 8. Lifecycle

### 8.1 Startup

The `BootstrapInitializer` starts the MCP server after the REST service and
HTTP server are up:

```java
// BootstrapInitializer.init()
restService.start();
uiService.start();
httpServer.listen();
mcpService.start();  // starts MCP HTTP server on port 4041
```

The `MCPService.start()` method:
1. Resolves the port (4041, or 0 if REST port is 0 for tests).
2. Creates a Vert.x `Router` with a `BodyHandler` (1 MB body limit).
3. Registers the SSE endpoint (`GET /mcp/sse`).
4. Registers the message endpoint (`POST /mcp/message`).
5. Registers the WebSocket endpoint (`GET /mcp/ws`).
6. Creates and starts the HTTP server.

### 8.2 Shutdown

`MCPService.stop()`:
1. Closes all open SSE sessions (calls `response.end()` on each).
2. Clears the `sseSessions` map.
3. Closes the HTTP server.

`BootstrapInitializer.deinit()` calls `mcpService.stop()` followed by
`restService.stop()`.

### 8.3 Tool Registration

Tools are registered at startup via Dagger injection:
1. `MCPToolModule` provides a `Set<MCPTool>` annotated with `@MCPTools`.
2. `MCPToolRegistry` receives the injected set and calls `register()` on
   each tool.
3. Each `register()` call stores the tool in the map and creates an EventBus
   consumer at `mcp.tool.<name>`.

---

## 9. Integration with AI Agents

### 9.1 Direct Tool Registry Access (In-Process)

In-process callers (e.g. an LLM integration test) can bypass HTTP entirely
and call tools directly via `MCPToolRegistry`:

```java
MCPToolRegistry registry = mcpService.getToolRegistry();
JsonObject result = registry.dispatch("search_assets", arguments)
    .toCompletionStage().toCompletableFuture().get();
```

This is used by `MCPDirectToolCallTest` to test tools against a real
PostgreSQL database without HTTP overhead.

### 9.2 HTTP JSON-RPC (Remote Clients)

Remote AI agents interact via HTTP POST to `/mcp/message`:

```
POST /mcp/message HTTP/1.1
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "tools/list",
  "id": 1
}
```

The `MCPServerToolCallTest` demonstrates the full flow:
1. `initialize` handshake
2. `tools/list` to discover available tools
3. Convert tool descriptors to LLM `ToolDefinition` objects
4. Ask the LLM a question with the tool definitions
5. For each tool call the LLM produces, send `tools/call` to the MCP server
6. Feed the tool result back into the conversation
7. Repeat until the LLM produces a final answer

### 9.3 LLM Integration (genai-utils)

The MCP tool descriptors are designed to be convertible to LLM tool
definitions:

```java
List<ToolDefinition> llmTools = toolRegistry.listDescriptors().stream()
    .map(d -> new ToolDefinition(d.name(), d.description(), d.inputSchema()))
    .toList();
```

This allows any LLM provider (e.g. Ollama) to use the MCP tools via
function calling. The LLM generates tool calls, which are dispatched through
the MCP registry or HTTP server, and the results are fed back as tool
result messages.

---

## 10. Key Classes Reference

| Class                  | Package                                    | Purpose                                  |
|------------------------|--------------------------------------------|------------------------------------------|
| `MCPService`           | `io.metaloom.loom.mcp`                     | HTTP server, SSE/WS endpoints, lifecycle |
| `MCPConstants`         | `io.metaloom.loom.mcp`                     | Constants (paths, method names, error codes) |
| `MCPJsonRpcHandler`    | `io.metaloom.loom.mcp.handler`             | JSON-RPC request dispatch                |
| `MCPToolRegistry`      | `io.metaloom.loom.mcp.tool`                | Tool registration and EventBus dispatch  |
| `MCPTool`              | `io.metaloom.loom.mcp.tool`                | Tool interface                           |
| `MCPToolDescriptor`    | `io.metaloom.loom.mcp.model`               | Tool descriptor record + schema builder  |
| `MCPToolModule`        | `io.metaloom.loom.mcp.dagger`              | Dagger module providing tool set         |
| `MCPModule`            | `io.metaloom.loom.mcp.dagger`              | Dagger module providing MCP router       |
| `MCPTools`             | `io.metaloom.loom.mcp.dagger`              | Dagger qualifier for tool set            |
| `JsonRpcRequest`       | `io.metaloom.loom.mcp.model`               | JSON-RPC 2.0 request model               |
| `JsonRpcResponse`      | `io.metaloom.loom.mcp.model`               | JSON-RPC 2.0 response model              |
| `SearchAssetsTool`     | `io.metaloom.loom.mcp.tool.impl`           | Search assets tool                       |
| `GetAssetTool`         | `io.metaloom.loom.mcp.tool.impl`           | Get single asset tool                    |
| `SearchTranscriptTool` | `io.metaloom.loom.mcp.tool.impl`           | Search transcripts tool                  |
| `ListCollectionsTool`  | `io.metaloom.loom.mcp.tool.impl`           | List collections tool                    |
| `AssetStatisticsTool`  | `io.metaloom.loom.mcp.tool.impl`           | Asset statistics tool                    |
| `ListPipelinesTool`    | `io.metaloom.loom.mcp.tool.impl`           | List pipelines tool                      |
| `GetPipelineTool`      | `io.metaloom.loom.mcp.tool.impl`           | Single pipeline + graph visual tool      |
| `MCPToolResults`       | `io.metaloom.loom.mcp.tool`                | Result envelopes (content / references / visuals) |
| `MCPAuthenticationHandler` | `io.metaloom.loom.auth`                | MCP HTTP authentication (JWT + API key)  |
| `WebSocketAuthenticator` | `io.metaloom.loom.rest.service.impl`     | WebSocket authentication (token query)   |
| `LoomAuthenticationHandler` | `io.metaloom.loom.auth`                | JWT authentication (shared with REST)    |
| `LoomAuthorizationProvider` | `io.metaloom.loom.auth`                | Permission checking (shared with REST)   |
| `TokenDao`             | `io.metaloom.loom.db.model.token`          | API token validation                     |

---

## 11. Progress Assessment

The following checkboxes track the implementation status and areas that need
improvement. AI agents can use this list to identify work items.

### 11.1 Core Protocol

- [x] JSON-RPC 2.0 request/response handling
- [x] `initialize` method with protocol version and capabilities
- [x] `notifications/initialized` notification handling
- [x] `ping` health check method
- [x] `tools/list` method returning all registered tool descriptors
- [x] `tools/call` method with EventBus dispatch
- [x] `resources/list` stub (returns empty list)
- [x] Error codes for parse, invalid request, method not found, invalid params, internal
- [x] MCP content format result (`{ "content": [{ "type": "text", "text": "..." }] }`)

### 11.2 Transports

- [x] HTTP + SSE (Streamable HTTP) transport with session management
- [x] WebSocket transport (`/mcp/ws`) with full-duplex JSON-RPC
- [x] SSE session tracking with automatic cleanup on disconnect
- [x] SSE `endpoint` event sent on connection to tell client where to POST
- [x] POST message endpoint with session ID query parameter
- [x] Body handler with 1 MB body limit on message endpoint
- [x] CORS `Access-Control-Allow-Origin: *` header on SSE endpoint (configurable via `LOOM_MCP_AUTH_ALLOWED_ORIGINS`)
- [ ] SSE heartbeat/keepalive (connections may time out on long idle)
- [x] WebSocket authentication (token via `?token=` query param, reuses `WebSocketAuthenticator`)
- [x] SSE authentication (token via `?token=` query param OR `Authorization: Bearer` header)
- [x] Message endpoint authentication (requires `Authorization: Bearer` header OR `X-API-Key` header)
- [ ] Configurable body limit (hardcoded to 1 MB, not configurable)
- [x] MCP server port configurable via `LoomOptions` (`LOOM_SERVER_MCP_PORT`, default 4041)

### 11.3 Tools

- [x] `search_assets` tool — lists assets with basic metadata
- [x] `get_asset` tool — loads single asset by UUID or SHA-512
- [x] `search_transcript` tool — stub (returns placeholder, no full-text search)
- [x] `list_collections` tool — lists collections with UUID and name
- [x] `asset_statistics` tool — aggregates counts by MIME type and total storage
- [x] `list_pipelines` tool — lists pipelines with the metadata of their latest version
- [x] `get_pipeline` tool — loads one pipeline's node graph as text + `pipeline-graph` visual
- [x] Visual envelopes (`visuals`) for results the chat renders inline
- [x] Tool descriptor with JSON Schema for parameters
- [x] Dagger multibinding for tool registration
- [x] EventBus-based dispatch (decoupled from transport)
- [ ] `search_assets` does not apply `query` or `mimeType` filters (loads page without filtering)
- [ ] `search_transcript` is a stub — no Elasticsearch/Lucene integration
- [ ] `asset_statistics` does not use the `collection` parameter for scoping
- [ ] `asset_statistics` loads up to 10,000 assets in memory instead of using SQL aggregates
- [ ] No tool for creating/updating/deleting assets (read-only tools only)
- [ ] `list_pipelines` filters `query` in memory over the loaded page, not at DAO level
- [ ] No tool for pipeline *operations* (run, cancel, run status, events) — the pipeline tools are read-only
- [ ] No visual payload for anything but pipelines (asset previews, run timelines, statistics charts)
- [ ] No tool for tag operations (create, list, assign to assets)
- [ ] No tool for user/role/group management
- [ ] No tool for embedding operations
- [ ] No tool for task/comment/annotation operations
- [ ] No tool for GraphQL queries
- [ ] No tool for processor status or registration
- [ ] Tool results do not include pagination metadata (total count, next page cursor)
- [ ] Tool error handling returns generic messages without structured error codes

### 11.4 Authentication and Security

- [x] MCP server runs on a separate port from the REST API (4041 vs 6333)
- [x] Authentication on all MCP endpoints (SSE, message, WebSocket) via `LOOM_MCP_AUTH_ENABLED`
- [x] JWT token via `Authorization: Bearer` header (SSE, message)
- [x] JWT token via `?token=` query parameter (SSE, WebSocket)
- [x] API key via `X-API-Key` header (message endpoint)
- [x] Permission checks on tool execution via `MCPToolDescriptor.requiredPermissions`
- [x] Strict/lenient mode via `LOOM_MCP_AUTH_STRICT_MODE`
- [x] Configurable CORS allowed origins via `LOOM_MCP_AUTH_ALLOWED_ORIGINS`
- [x] Reuses `LoomAuthenticationHandler` and `WebSocketAuthenticator` from REST API
- [ ] No rate limiting on MCP endpoints
- [ ] No audit logging of tool calls

### 11.5 Resources (MCP Resources)

- [x] `resources/list` returns an empty list (stubbed)
- [x] `resources/read` returns method-not-found error
- [ ] No MCP resource providers implemented (could expose assets, collections, etc. as resources)
- [ ] No `resources/subscribe` or `resources/unsubscribe` support

### 11.6 Testing

- [x] `MCPDirectToolCallTest` — tests tool dispatch via registry (no HTTP)
- [x] `MCPServerToolCallTest` — tests full HTTP JSON-RPC flow
- [x] Tests use real PostgreSQL database with test fixtures
- [x] Tests integrate with Ollama LLM for tool-call loop validation
- [x] `initialize` and `tools/list` verified over HTTP
- [ ] No tests for WebSocket transport (`/mcp/ws`)
- [ ] No tests for SSE transport (`/mcp/sse`)
- [ ] No tests for error handling (invalid JSON, missing method, etc.)
- [ ] No tests for tool call with invalid arguments
- [ ] No tests for concurrent tool calls
- [ ] No tests for tool registration/unregistration at runtime
- [ ] Tests require Ollama running at `http://127.0.0.1:11434` with `gpt-oss:20b`

### 11.7 Configuration and Integration

- [x] MCP service started by `BootstrapInitializer` after REST and HTTP server
- [x] MCP service stopped by `BootstrapInitializer.deinit()`
- [x] Dagger DI wiring with `MCPModule`, `MCPToolModule`, `MCPTools` qualifier
- [x] Separate `loom-service-mcp` Maven module with minimal dependencies
- [x] `MCPService.getToolRegistry()` exposes registry for in-process callers
- [x] `MCPService.getServer()` exposes HTTP server for test port discovery
- [x] MCP port configurable via `LoomOptions` (`LOOM_SERVER_MCP_PORT`, default 4041)
- [ ] No health check endpoint for MCP server
- [ ] No metrics/observability for MCP server (tool call count, latency, etc.)
- [ ] No graceful shutdown timeout (server.close() is immediate)
- [ ] MCP server does not participate in Vert.x cluster (EventBus dispatch is local only)

### 11.8 Documentation

- [x] This document (MCP.md)
- [x] Javadoc on all public classes and methods
- [x] `MCPConstants` documents all method names and error codes
- [ ] No OpenAPI/JSON schema for MCP endpoints (MCP uses JSON-RPC, not REST)
- [ ] No client SDK for MCP (clients must use raw JSON-RPC)
- [ ] No example client script or curl commands in documentation
- [ ] No diagram of the tool dispatch flow in Javadoc

---

## 12. Connection Points to REST API

The MCP server and the REST API share the same underlying data layer (DAOs)
but are otherwise independent services. Key connection points:

| Concern             | REST API                          | MCP Server                          |
|---------------------|-----------------------------------|-------------------------------------|
| Port                | 6333 (configurable)               | 4041 (configurable via `LOOM_SERVER_MCP_PORT`) |
| Authentication      | JWT cookie + OAuth2 + API tokens  | JWT (header/query), API key (header), strict/lenient mode |
| Protocol            | HTTP REST                         | JSON-RPC 2.0                        |
| Data access         | via `*EndpointService` -> DAO     | via `DaoCollection` -> DAO (direct) |
| Path prefix         | `/api/v1`                         | `/mcp/sse`, `/mcp/message`, `/mcp/ws` |
| Body limit          | Unlimited (`-1`)                  | 1 MB                                |
| CORS                | All origins, all methods          | Configurable via `LOOM_MCP_AUTH_ALLOWED_ORIGINS` |
| Tool operations     | Full CRUD on all resources        | Read-only (search, get, list, stats) with permission checks |
| Pipeline operations | `POST /pipelines/:uuid/run`       | Read-only: `list_pipelines`, `get_pipeline` (no run/cancel) |
| WebSocket           | Processor + pipeline events       | MCP JSON-RPC over WS                |

The MCP tools access DAOs directly but now go through permission checks in
`MCPToolRegistry.dispatch()` before execution. Authentication infrastructure
(`LoomAuthenticationHandler`, `WebSocketAuthenticator`, `LoomAuthorizationProvider`,
`TokenDao`) is shared with the REST API.
