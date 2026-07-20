# MetaLoom // Pipeline Editor Detailed Specification

> Exhaustive documentation of the Pipeline Editor implementation: React Flow canvas, node/edge rendering, event handling, persistence, API integration, CRUD operations, and validation. This file is cross-referenced from [LOOM_UI.md](LOOM_UI.md).

---

## 1. Overview

The Pipeline Editor (`/pipelines` route) is the most complex UI component in Loom. It provides a visual, node-based interface for constructing and executing media processing pipelines.

### 1.1 Component Location

```
src/features/pipeline/PipelineEditor.tsx  (single file, ~2400 lines)
```

### 1.2 Key Sub-Components (defined in same file)

| Component | Lines | Purpose |
|-----------|-------|---------|
| `PipelineEditor` | 1600-2400 | Main orchestration, state, API integration |
| `PipelineCanvas` | 800-1300 | React Flow wrapper, interactions, layout |
| `PipelineNodeComponent` | 100-300 | Custom node renderer with handles |
| `PipelineInspector` | 400-500 | Right panel: metadata + run history |
| `NodeDetailSidebar` | 400-800 | Collapsible node config (config/log/JSON tabs) |
| `RunHistory` | 300-400 | Run history list in inspector |
| `CommandPaletteContent` | 1300-1450 | N-key node search modal |
| `validatePipeline` | 1300-1450 | Validation logic (cycles, duplicates, etc.) |
| `toRFNodes` / `toRFEdges` | 300-400 | React Flow format converters |

---

## 2. Event Handling

### 2.1 Event Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            USER INTERACTIONS                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Node Click ──────────────────► onNodeClick ──────────────► handleNodeSelect │
│       │                                                              │       │
│       │                                                              ▼       │
│       │                                                      selectedNodeId  │
│       │                                                              │       │
│       │                                                              ▼       │
│       │                                                      nodeDetailOpen  │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                    NodeDetailSidebar (opens)                         │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Handle Drag ────────────────► onConnect ────────────────► isValidConnection │
│       │                                                              │       │
│       │                                                              ▼       │
│       │                                                      sourceType ===   │
│       │                                                      targetType ?     │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                    Edge created (addEdge)                            │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Edge Right-Click ───────────► onEdgeClick ──────────────► setEdgeMenu     │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │              Context Menu: PASS / REJECT / ANY                       │   │
│  │              Selection ──────────────────────────────► handleEdgeTypeChange │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  DEL Key ────────────────────► keydown handler ───────────► handleDeleteNodeRequest │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │              Delete Confirmation Dialog                              │   │
│  │              Confirm ────────────────────────────────► handleDeleteNodeConfirm │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  N Key ──────────────────────► keydown handler ───────────► setShowCommandPalette │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │              Command Palette Dialog (keyboard nav)                   │   │
│  │              Select ──────────────────────────────────► handleAddNode │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  A Key ──────────────────────► keydown handler ───────────► setAutoArrangeTrigger │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │              Topological Layout Effect ──────────────► setNodes       │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Parameter Change ───────────► onParameterChange ──────────► handleParameterChange │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │              selected.definition.nodes[].data[key] = value           │   │
│  │              setDirty(true)                                          │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Save Click ─────────────────► handleSave ────────────────► validatePipeline │
│       │                                                              │       │
│       │                                                              ▼       │
│       │                                                      errors? → notify │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │              POST /api/v1/pipelines/:uuid ────────────► setDirty(false) │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Run Click ───────────────────► handleRun ────────────────► POST /run       │
│       │                                                              │       │
│       ▼                                                              ▼       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │              notify(success/info, message)                           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Event Handler Reference

| Event | Handler | File Location | Side Effects |
|-------|---------|---------------|--------------|
| Node click | `onNodeClick` → `handleNodeSelect` | `PipelineCanvas` → `PipelineEditor` | `selectedNodeId`, `nodeDetailOpen` |
| Pane click | `onPaneClick` → `handleNodeSelect(null)` | `PipelineCanvas` → `PipelineEditor` | Clear selection |
| Connect handles | `onConnect` | `PipelineCanvas` | `setEdges(addEdge(...))` |
| Validate connection | `isValidConnection` | `PipelineCanvas` | Returns boolean, sets `connectionRejectedRef` |
| Connect start | `onConnectStartHandler` | `PipelineCanvas` | `connectingRef.current = true` |
| Connect end | `onConnectEndHandler` | `PipelineCanvas` | Shows error toast if rejected |
| Reconnect start | `onReconnectStart` | `PipelineCanvas` | `reconnectingEdgeRef.current = edge` |
| Reconnect | `onReconnect` | `PipelineCanvas` | `setEdges(reconnectEdge(...))` |
| Reconnect end | `onReconnectEnd` | `PipelineCanvas` | Deletes edge if dropped in empty space |
| Edge click | `onEdgeClick` | `PipelineCanvas` | `setEdgeMenu({edgeId, x, y})` |
| Edge type change | `handleEdgeTypeChange` | `PipelineEditor` | Updates edge style + `selected.definition.edges` |
| Node delete request | `handleDeleteNodeRequest` | `PipelineEditor` | `setDeleteConfirm({nodeId, label})` |
| Node delete confirm | `handleDeleteNodeConfirm` | `PipelineEditor` | Removes from definition, `setRemovalTrigger` |
| Display name change | `handleDisplayNameChange` | `PipelineEditor` | `setNodeDisplayNames` |
| Parameter change | `handleParameterChange` | `PipelineEditor` | Updates `selected.definition.nodes[].data`, `setDirty(true)` |
| Graph change | `handleGraphChange` | `PipelineCanvas` → `PipelineEditor` | `setGraphJson`, `setDirty(true)`, clears validation |
| Save | `handleSave` | `PipelineEditor` | Validates, `POST /pipelines/:uuid`, `setDirty(false)` |
| Run | `handleRun` | `PipelineEditor` | `POST /pipelines/:uuid/run`, notify |
| Auto-arrange | `setAutoArrangeTrigger` | `PipelineEditor` | Triggers layout effect |
| Keyboard (H/N/A/Del) | `keydown` handler | `PipelineEditor` | Various (help, palette, arrange, delete) |
| Log resize | `handleLogDividerMouseDown` | `PipelineEditor` | `setLogHeight` |

### 2.3 React Flow Event Props

```typescript
<ReactFlow
  nodes={nodes}
  edges={edges}
  onNodesChange={onNodesChange}        // Position changes (drag)
  onEdgesChange={onEdgesChange}        // Edge deletions
  onNodeClick={onNodeClick}            // Node selection
  onPaneClick={onPaneClick}            // Deselect
  onConnect={onConnect}                // New edge
  onConnectStart={onConnectStartHandler}
  onConnectEnd={onConnectEndHandler}
  onReconnectStart={onReconnectStart}
  onReconnect={onReconnect}
  onReconnectEnd={onReconnectEnd}
  onEdgeClick={onEdgeClick}
  isValidConnection={isValidConnection} // Connection validation
  nodeTypes={nodeTypes}                // Custom node component
  fitView                              // Initial fit
  fitViewOptions={{ padding: 0.3 }}
  snapToGrid
  snapGrid={[15, 15]}
  style={{ background: tokens.bg.base }}
/>
```

---

## 3. Persistence

### 3.1 State Persistence Model

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PIPELINE EDITOR STATE                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐       │
│  │  Server State   │     │  Local State    │     │  Derived State  │       │
│  │  (source of     │     │  (optimistic    │     │  (computed      │       │
│  │   truth)        │     │   edits)        │     │   on render)    │       │
│  ├─────────────────┤     ├─────────────────┤     ├─────────────────┤       │
│  │ pipelines[]     │     │ addedNodes[]    │     │ graphJson       │       │
│  │ selected        │     │ nodeDisplayNames│     │ validationErrors│       │
│  │ pipelineRuns[]  │     │ selectedNodeId  │     │                 │       │
│  │ descriptors[]   │     │ canvasTab       │     │                 │       │
│  │                 │     │ dirty           │     │                 │       │
│  │                 │     │ logOpen         │     │                 │       │
│  │                 │     │ logHeight       │     │                 │       │
│  │                 │     │ nodeDetailOpen  │     │                 │       │
│  │                 │     │ showHelp        │     │                 │       │
│  │                 │     │ showCommandPalette│   │                 │       │
│  │                 │     │ nodeFilter      │     │                 │       │
│  │                 │     │ autoArrangeTrigger│   │                 │       │
│  │                 │     │ removalTrigger  │     │                 │       │
│  └─────────────────┘     └─────────────────┘     └─────────────────┘       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Data Flow: Load → Edit → Save

#### Load (Mount)
```typescript
// PipelineEditor.tsx ~line 1650
useEffect(() => {
  if (!token) return;
  listPipelines(token).then(resp => {
    const ps: Pipeline[] = resp.data.map(mapToPipeline);
    setPipelines(ps);
    setSelected(ps[0] ?? null);
    setLoading(false);
  }).catch(() => setLoading(false));
}, [token]);

// Run history loads when selection changes
useEffect(() => {
  if (!token || !selected) { setPipelineRuns([]); return; }
  setRunsLoading(true);
  listPipelineRuns(token, selected.id)
    .then(setPipelineRuns)
    .finally(() => setRunsLoading(false));
}, [token, selected?.id]);
```

#### Edit (Local Mutation)
```typescript
// Adding a node (handleAddNode)
const newNode: RFNode = { id, type: "pipelineNode", position, data: {...} };
setAddedNodes(prev => [...prev, newNode]);

// Also mutate server-state mirror for NodeDetailSidebar
selected.definition.nodes.push({
  id, type: desc.kind, label: desc.name, description: desc.description,
  position: newNode.position, data: paramDefaults,
});
setSelected({ ...selected }); // Trigger re-render
setDirty(true);
```

#### Save (Persist to Server)
```typescript
// handleSave ~line 1750
const handleSave = useCallback(async () => {
  // 1. Validate
  const definition = graphJson ?? { nodes: selected.definition.nodes, edges: selected.definition.edges };
  const errors = validatePipeline(nodesForValidation, edgesForValidation, descriptors);
  if (errors.length) return notify("error", errors[0].message);

  // 2. Build request
  const req: PipelineUpdateRequest = {
    name: selected.name,
    description: selected.description,
    definition,
    enabled: selected.enabled,
    priority: selected.priority,
    dryRun: selected.dryRun,
  };

  // 3. POST to API
  await updatePipeline(token, selected.id, req);
  setDirty(false);
  notify("success", "Pipeline saved");
}, [token, selected, graphJson, descriptors, notify]);
```

### 3.3 Dirty Flag & Unsaved Changes

| State | Trigger | Cleared By |
|-------|---------|------------|
| `dirty: true` | Node add, parameter change, edge type change, node delete, node drag (via `handleGraphChange`) | `handleSave` success |
| `dirty: false` | Initial load, successful save | Any edit |

**Warning:** No navigation guard — leaving `/pipelines` with `dirty=true` loses changes silently.

### 3.4 Run History Persistence

Run history is **server-authoritative**, fetched on selection change:

```typescript
// PipelineEditor.tsx ~line 1670
useEffect(() => {
  if (!token || !selected) { setPipelineRuns([]); return; }
  setRunsLoading(true);
  listPipelineRuns(token, selected.id)
    .then(setPipelineRuns)
    .catch(() => setPipelineRuns([]))
    .finally(() => setRunsLoading(false));
}, [token, selected?.id]);
```

The `PipelineRunRecord` type:
```typescript
interface PipelineRunRecord {
  uuid: string;
  pipelineUuid: string;
  started: string;
  finished?: string;
  status: string;           // "success" | "failed" | "running" | "idle"
  mediaCount: number;
  successCount: number;
  failureCount: number;
  dryRun: boolean;
  errorMessage?: string;
}
```

---

## 4. API Integration

### 4.1 API Client (`src/api/pipelines.ts`)

```typescript
// Base configuration
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "/api/v1";

function authHeaders(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

// List all pipelines
export async function listPipelines(token: string): Promise<PipelineListResponse> {
  const res = await fetch(`${API_BASE_URL}/pipelines`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<PipelineListResponse>(res);
}

// Load single pipeline (not used directly — list returns full data)
export async function loadPipeline(token: string, uuid: string): Promise<PipelineResponse> { ... }

// Create pipeline (not used in UI)
export async function createPipeline(token: string, request: PipelineCreateRequest): Promise<PipelineResponse> { ... }

// Update pipeline (used by handleSave)
export async function updatePipeline(token: string, uuid: string, request: PipelineUpdateRequest): Promise<PipelineResponse> { ... }

// Delete pipeline (not used in UI)
export async function deletePipeline(token: string, uuid: string): Promise<void> { ... }

// Run pipeline (used by handleRun)
export async function runPipeline(token: string, uuid: string, request: PipelineRunRequest = {}): Promise<PipelineRunResponse> { ... }

// Run history
export async function listPipelineRuns(token: string, uuid: string): Promise<PipelineRunRecord[]> { ... }
```

### 4.2 Request/Response Types

```typescript
// PipelineUpdateRequest (sent on save)
interface PipelineUpdateRequest {
  name?: string;
  description?: string;
  definition?: Record<string, unknown>;  // { nodes: [], edges: [] }
  enabled?: boolean;
  priority?: number;
  dryRun?: boolean;
}

// PipelineRunRequest (sent on run)
interface PipelineRunRequest {
  mediaUuids?: string[];      // Specific assets
  pathGlobs?: string[];       // Path patterns
  dryRun?: boolean;           // Override pipeline setting
}

// PipelineRunResponse (from run)
interface PipelineRunResponse {
  workOrderId: string;
  processorNodeId?: string;
  dispatched: boolean;
  message?: string;
}
```

### 4.3 Node Descriptors API (`src/api/nodeDescriptors.ts`)

```typescript
export async function fetchNodeDescriptors(): Promise<NodeDescriptorsResponse> {
  const res = await fetch(`${API_BASE_URL}/pipeline/node-descriptors`, {
    method: "GET",
    // No auth header — public endpoint
  });
  return handleResponse<NodeDescriptorsResponse>(res);
}

interface NodeDescriptorsResponse {
  nodeDescriptors: NodeDescriptor[];
  contentTypes: ContentType[];
}
```

Loaded once at startup via `NodeRegistryContext`:

```typescript
// NodeRegistryContext.tsx
useEffect(() => {
  load();  // fetchNodeDescriptors()
}, [load]);
```

---

## 5. CRUD Handling

### 5.1 CRUD Matrix

| Operation | UI Implementation | API Endpoint | Optimistic? |
|-----------|-------------------|--------------|-------------|
| **Create** | ❌ Not implemented | `POST /api/v1/pipelines` | N/A |
| **Read (List)** | ✅ Pipeline list sidebar | `GET /api/v1/pipelines` | No |
| **Read (Single)** | ✅ Select in list | Via list response | No |
| **Update** | ✅ Canvas edit → Save button | `POST /api/v1/pipelines/:uuid` | Yes |
| **Delete** | ❌ Not implemented | `DELETE /api/v1/pipelines/:uuid` | N/A |
| **Run** | ✅ Run button | `POST /api/v1/pipelines/:uuid/run` | No |

### 5.2 Update Flow (The Only Implemented Write)

```
User edits canvas
       │
       ▼
Local state mutated (addedNodes, selected.definition, nodeDisplayNames)
       │
       ▼
dirty = true
       │
       ▼
User clicks "Save"
       │
       ▼
validatePipeline(nodes, edges, descriptors)
       │
       ├─ Errors? ──► notify(error) ──► STOP
       │
       ▼
POST /api/v1/pipelines/:uuid (PipelineUpdateRequest)
       │
       ├─ Success? ──► dirty = false, notify(success)
       │
       ▼
       Failure? ──► notify(error), dirty remains true
```

### 5.3 Node CRUD (Within Pipeline)

| Action | Local Mutation | Server Sync |
|--------|----------------|-------------|
| **Add Node** | `addedNodes.push()`, `selected.definition.nodes.push()` | On save |
| **Delete Node** | `selected.definition.nodes.filter()`, `addedNodes.filter()`, `removalTrigger` | On save |
| **Move Node** | React Flow `onNodesChange` → `nodes[].position` | On save (via `graphJson`) |
| **Change Parameter** | `selected.definition.nodes[].data[key] = value` | On save |
| **Change Edge Type** | `selected.definition.edges[].edgeType = type` | On save |

### 5.4 Run Operation

```typescript
// handleRun ~line 1800
const handleRun = useCallback(async () => {
  if (!token || !selected || running) return;
  setRunning(true);
  try {
    const resp = await runPipeline(token, selected.id, { dryRun: selected.dryRun });
    notify(
      resp.dispatched ? "success" : "info",
      resp.dispatched
        ? "Pipeline run dispatched"
        : resp.message || "No processor available",
    );
  } catch (err) {
    notify("error", err.message || "Run failed");
  } finally {
    setRunning(false);
  }
}, [token, selected, running, notify]);
```

**Response handling:**
- `dispatched: true` → Work order sent to processor, run will appear in history
- `dispatched: false` → No processor available (503 equivalent), shows message

---

## 6. Validation

### 6.1 Validation Function

**Location:** `PipelineEditor.tsx` lines ~1300-1450

```typescript
function validatePipeline(
  nodes: { id: string; type: string; label: string }[],
  edges: { source: string; target: string }[],
  descriptors: NodeDescriptor[]
): ValidationError[] {
  const errors: ValidationError[] = [];
  const descKinds = new Set(descriptors.map(d => d.kind));

  // 1. Duplicate ID check
  const idSet = new Set<string>();
  for (const n of nodes) {
    if (idSet.has(n.id)) {
      errors.push({ type: "duplicateId", message: `Duplicate node ID: "${n.id}"` });
    } else {
      idSet.add(n.id);
    }
  }

  // 2. Invalid ID format check
  const NODE_ID_REGEX = /^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$/;
  for (const n of nodes) {
    if (!NODE_ID_REGEX.test(n.id)) {
      errors.push({ type: "invalidId", message: `Invalid node ID: "${n.id}"` });
    }
  }

  // 3. Unknown node type check
  for (const n of nodes) {
    if (!descKinds.has(n.type)) {
      errors.push({ type: "unknownType", message: `Unknown node type: "${n.type}"` });
    }
  }

  // 4. Cycle detection (Kahn's algorithm)
  const adj = new Map<string, string[]>();
  const inDeg = new Map<string, number>();
  for (const n of nodes) { adj.set(n.id, []); inDeg.set(n.id, 0); }
  for (const e of edges) {
    if (adj.has(e.source) && inDeg.has(e.target)) {
      adj.get(e.source)!.push(e.target);
      inDeg.set(e.target, (inDeg.get(e.target) ?? 0) + 1);
    }
  }
  const queue: string[] = [];
  for (const [id, deg] of inDeg) { if (deg === 0) queue.push(id); }
  let visited = 0;
  while (queue.length > 0) {
    const id = queue.shift()!;
    visited++;
    for (const t of adj.get(id) ?? []) {
      const d = (inDeg.get(t) ?? 0) - 1;
      inDeg.set(t, d);
      if (d === 0) queue.push(t);
    }
  }
  if (visited < nodes.length) {
    errors.push({ type: "cycle", message: "Cycle detected in pipeline graph" });
  }

  return errors;
}
```

### 6.2 Validation Error Types

| Type | Message | Severity |
|------|---------|----------|
| `duplicateId` | `Duplicate node ID: "xyz" — node IDs must be unique` | Blocking |
| `invalidId` | `Invalid node ID: "xyz" — IDs must match ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$` | Blocking |
| `unknownType` | `Unknown node type: "xyz" — not found in descriptor registry` | Blocking |
| `cycle` | `Cycle detected in pipeline graph — nodes form a circular dependency` | Blocking |

### 6.3 Validation Triggers

| Trigger | Behavior |
|---------|----------|
| **Save click** | Runs validation; blocks save on errors; shows first error in toast |
| **Graph change** | Clears stale validation errors (`setValidationErrors([])`) |
| **Auto-arrange** | No validation (layout only) |
| **Node add/delete** | No immediate validation (deferred to save) |

### 6.4 Validation Display

Errors shown in **JSON tab** (bottom panel):

```tsx
{validationErrors.length > 0 && (
  <Box sx={{ mt: 1, px: 1.5, py: 0.5, bgcolor: `${tokens.accent.red}11`, border: `1px solid ${tokens.accent.red}44`, borderRadius: tokens.radius.sm }}>
    {validationErrors.map((err, i) => (
      <Box key={i} sx={{ display: "flex", alignItems: "flex-start", gap: 0.5, py: 0.25 }}>
        <ErrorOutline sx={{ fontSize: 12, color: tokens.accent.red, mt: 1 }} />
        <Typography variant="caption" sx={{ fontSize: "0.65rem", color: tokens.accent.red }}>{err.message}</Typography>
      </Box>
    ))}
  </Box>
)}
```

---

## 7. Node & Edge Rendering Details

### 7.1 Node Component (`PipelineNodeComponent`)

```typescript
function PipelineNodeComponent({ data, selected, id }: NodeProps) {
  // Visual config from category
  const category = data.category ?? "ANALYSIS";
  const cfg = categoryConfig[category] ?? categoryConfig.ANALYSIS;
  
  // Handles from descriptor
  const inputs = data.inputs ?? [{ name: "Input", dataType: "media" }];
  const outputs = data.outputs ?? [{ name: "Output", dataType: "media" }];
  
  // States
  const isSource = category === "SOURCE";
  const isActive = data.isActive;  // From pipeline events
  
  return (
    <Box sx={...}>  // Styled container with category colors
      {/* Active indicator (green pulsing dot) */}
      {isActive && <Box sx={...} />}
      
      {/* Delete button (on hover) */}
      {hovered && onDelete && <IconButton onClick={() => onDelete(id)} />}
      
      {/* Header: icon + label + description */}
      <Box sx={{ display: "flex", gap: 1 }}>
        <Box sx={{ bgcolor: cfg.bg, color: cfg.color }}>{data.nodeIcon ?? cfg.icon}</Box>
        <Typography>{data.label}</Typography>
        <Typography variant="caption">{data.description}</Typography>
      </Box>
      
      {/* Input handles (left) */}
      {inputs.map((inp, idx) => (
        <Handle type="target" position={Position.Left} id={`in_${idx}`} style={{ background: DATA_TYPE_COLOR[inp.dataType] }} />
      ))}
      
      {/* Output handles (right) */}
      {outputs.map((out, idx) => (
        <Handle type="source" position={Position.Right} id={`out_${idx}`} style={{ background: DATA_TYPE_COLOR[out.dataType] }} />
      ))}
    </Box>
  );
}
```

### 7.2 Category Configuration

```typescript
const categoryConfig: Record<NodeCategory, { color: string; icon: React.ReactNode; bg: string }> = {
  SOURCE:    { color: tokens.accent.blue,  icon: <CloudUploadOutlined />,  bg: `${tokens.accent.blue}18` },
  FILTER:    { color: tokens.accent.amber, icon: <FilterAltOutlined />,    bg: `${tokens.accent.amber}18` },
  ANALYSIS:  { color: tokens.primary.main, icon: <MemoryOutlined />,       bg: tokens.primary.subtle },
  TRANSFORM: { color: "#e040fb",           icon: <TransformOutlined />,     bg: "#e040fb18" },
  OUTPUT:    { color: tokens.accent.teal,  icon: <CloudDownloadOutlined />, bg: `${tokens.accent.teal}18` },
};
```

### 7.3 Data Type Colors (Handle Colors)

```typescript
type ConnectorDataType = "media" | "data" | "control" | "text" | "hash";

const DATA_TYPE_COLOR: Record<ConnectorDataType, string> = {
  text: "#42a5f5",      // Blue
  media: "#66bb6a",     // Green
  data: "#ffa726",      // Orange
  hash: "#ab47bc",      // Purple
  control: "#78909c",   // Gray
};
```

### 7.4 Edge Styling

```typescript
const EDGE_TYPE_STYLE: Record<string, { stroke: string; strokeDasharray?: string; label: string; labelBg: string; labelColor: string }> = {
  PASS:   { stroke: tokens.accent.green,  label: "PASS",   labelBg: `${tokens.accent.green}22`,   labelColor: tokens.accent.green },
  REJECT: { stroke: tokens.accent.red,    strokeDasharray: "6 3", label: "REJECT", labelBg: `${tokens.accent.red}22`,     labelColor: tokens.accent.red },
  ANY:    { stroke: tokens.border.strong, label: "ANY",    labelBg: tokens.bg.overlay,             labelColor: tokens.text.tertiary },
};
```

### 7.5 Connection Validation Logic

```typescript
const isValidConnection = useCallback((conn: Connection) => {
  if (!conn.source || !conn.target || !conn.sourceHandle || !conn.targetHandle) return true;
  
  const sourceNode = nodes.find(n => n.id === conn.source);
  const targetNode = nodes.find(n => n.id === conn.target);
  if (!sourceNode || !targetNode) return true;

  const sourceOutputs = sourceNode.data.outputs as ConnectorDef[];
  const targetInputs = targetNode.data.inputs as ConnectorDef[];
  if (!sourceOutputs || !targetInputs) return true;

  const outIdx = parseInt(conn.sourceHandle.replace("out_", ""), 10);
  const inIdx = parseInt(conn.targetHandle.replace("in_", ""), 10);
  const sourceType = sourceOutputs[outIdx]?.dataType;
  const targetType = targetInputs[inIdx]?.dataType;
  if (!sourceType || !targetType) return true;

  const valid = sourceType === targetType;  // Exact match required
  if (!valid) connectionRejectedRef.current = true;
  return valid;
}, [nodes]);
```

**Rule:** Connections only allowed between **identical data types** (media↔media, data↔data, hash↔hash, etc.)

---

## 8. Auto-Arrange Algorithm

### 8.1 Topological Layout (Kahn's Algorithm)

```typescript
useEffect(() => {
  if (!autoArrangeTrigger || nodes.length === 0) return;
  
  const NODE_W = 200;
  const NODE_H = 80;
  const GAP_X = 80;
  const GAP_Y = 40;

  // Build adjacency & in-degree
  const adj = new Map<string, string[]>();
  const inDeg = new Map<string, number>();
  for (const n of nodes) { adj.set(n.id, []); inDeg.set(n.id, 0); }
  for (const e of edges) {
    adj.get(e.source)?.push(e.target);
    inDeg.set(e.target, (inDeg.get(e.target) ?? 0) + 1);
  }

  // Topological sort → assign columns
  const queue: string[] = [];
  for (const [id, deg] of inDeg) { if (deg === 0) queue.push(id); }
  const col = new Map<string, number>();
  while (queue.length > 0) {
    const id = queue.shift()!;
    const c = col.get(id) ?? 0;
    for (const t of adj.get(id) ?? []) {
      col.set(t, Math.max(col.get(t) ?? 0, c + 1));
      inDeg.set(t, (inDeg.get(t) ?? 0) - 1);
      if (inDeg.get(t) === 0) queue.push(t);
    }
  }
  // Unvisited (disconnected) → column 0
  for (const n of nodes) { if (!col.has(n.id)) col.set(n.id, 0); }

  // Group by column, assign positions
  const columns = new Map<number, string[]>();
  for (const [id, c] of col) {
    if (!columns.has(c)) columns.set(c, []);
    columns.get(c)!.push(id);
  }

  const posMap = new Map<string, { x: number; y: number }>();
  for (const [c, ids] of columns) {
    ids.forEach((id, row) => {
      posMap.set(id, { x: c * (NODE_W + GAP_X), y: row * (NODE_H + GAP_Y) });
    });
  }

  // Apply positions
  setNodes(nds => nds.map(n => {
    const p = posMap.get(n.id);
    return p ? { ...n, position: p } : n;
  }));

  // Fit view
  setTimeout(() => fitView({ padding: 0.3 }), 50);
}, [autoArrangeTrigger]);
```

### 8.2 Layout Properties

| Property | Value |
|----------|-------|
| Node width | 200px |
| Node height | 80px |
| Column gap (X) | 80px |
| Row gap (Y) | 40px |
| Fit view padding | 0.3 |
| Animation | None (instant) |

---

## 9. Command Palette & Add Node Bar

### 9.1 Add Node Bar (Always Visible)

- **Location:** Above log panel
- **UI:** TextField with search + Popper menu
- **Filter:** Real-time filtering by name/kind/category
- **Selection:** Mouse click or Enter key

### 9.2 Command Palette (N Key)

- **Trigger:** `N` key (global, when pipeline selected)
- **UI:** Full-screen Dialog with List
- **Navigation:** ↑/↓ arrows, Enter to select, Escape to close
- **Auto-focus:** Input focused on open
- **Scroll:** Selected item scrolled into view

### 9.3 Shared Filtering Logic

```typescript
const filtered = descriptors.filter(d =>
  !filter ||
  d.name.toLowerCase().includes(filter.toLowerCase()) ||
  d.kind.toLowerCase().includes(filter.toLowerCase()) ||
  d.category.toLowerCase().includes(filter.toLowerCase())
);
```

---

## 10. Keyboard Shortcuts

| Key | Action | Handler |
|-----|--------|---------|
| `H` | Toggle help overlay | `setShowHelp(v => !v)` |
| `N` | Open command palette | `setShowCommandPalette(true)` |
| `A` | Auto-arrange nodes | `setAutoArrangeTrigger(v => v + 1)` |
| `Delete` | Delete selected node | `handleDeleteNodeRequest` |
| `Escape` | Close dialogs/palette | Various close handlers |

---

## 11. JSON View Details

### 11.1 Two Panels

| Panel | Source | Editable | Syntax Highlight |
|-------|--------|----------|------------------|
| **Loaded Definition** | `selected.definition` (server) | No | Yes |
| **Current Canvas State** | `graphJson` (React Flow) | Via canvas | Yes |

### 11.2 Syntax Highlighting

```typescript
function syntaxHighlightJson(json: string): string {
  return json.replace(
    /("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false)\b|-?\d+(?:\.\d*)?(?:[eE][+-]?\d+)?|\bnull\b)/g,
    (match) => {
      let cls = "json-number";
      if (match.startsWith('"')) cls = match.endsWith(":") ? "json-key" : "json-string";
      else if (/true|false/.test(match)) cls = "json-boolean";
      else if (match === "null") cls = "json-null";
      const escaped = match.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
      return `<span class="${cls}">${escaped}</span>`;
    },
  );
}
```

### 11.3 CSS Classes (via `sx`)

```typescript
sx={{
  "& .json-key": { color: tokens.primary.main, fontWeight: 600 },
  "& .json-string": { color: tokens.accent.green },
  "& .json-number": { color: tokens.accent.amber },
  "& .json-boolean": { color: tokens.accent.blue },
  "& .json-null": { color: tokens.text.tertiary },
}}
```

### 11.4 Validity Indicator

```tsx
<Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
  <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: graphJson ? tokens.accent.green : tokens.accent.red }} />
  <Typography variant="caption" sx={{ color: graphJson ? tokens.accent.green : tokens.accent.red }}>
    {graphJson ? "Valid JSON" : "Invalid JSON"}
  </Typography>
</Box>
```

---

## 12. Log Panel & Run History

### 12.1 Log Panel

- **Draggable height:** 80-400px via divider handle
- **Collapsible:** Show/hide button in toolbar
- **Content:** Run history (when pipeline selected) or placeholder

### 12.2 Run History Display

```tsx
// RunHistory component
{runs.map(r => (
  <Paper key={r.uuid} elevation={0} sx={{ bgcolor: tokens.bg.overlay, border: `1px solid ${tokens.border.subtle}` }}>
    <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
      {/* Status icon: success=green✓, failed=red✗, running=amber⟳ */}
      <Typography variant="caption" fontWeight={600} sx={{ color: statusColor }}>
        {r.status}
      </Typography>
      {r.dryRun && <Chip label="dry" size="small" />}
      <Typography variant="caption" sx={{ color: tokens.text.tertiary, ml: "auto" }}>
        {new Date(r.started).toLocaleString()}
      </Typography>
    </Box>
    <Box sx={{ display: "flex", gap: 1 }}>
      <Typography variant="caption">{r.successCount} / {r.mediaCount} assets</Typography>
      {r.failureCount > 0 && <Typography variant="caption" color="error">{r.failureCount} errors</Typography>}
    </Box>
    {r.errorMessage && <Typography variant="caption" color="error" fontFamily="monospace">{r.errorMessage}</Typography>}
  </Paper>
))}
```

---

## 13. Node Detail Sidebar

### 13.1 Three Tabs

| Tab | Content | Editable |
|-----|---------|----------|
| **Config** | Display name (max 15 chars), description, dynamic parameters | Yes |
| **Log** | Simulated processing log with timestamps | No (mock) |
| **JSON** | Full node state as formatted JSON | No |

### 13.2 Dynamic Parameter Editors

Parameters sourced from `NodeDescriptor.parameters`:

```typescript
// NodeDescriptor.parameters: NodeParameter[]
interface NodeParameter {
  key: string;
  type: "STRING" | "INTEGER" | "BOOLEAN" | "FLOAT" | "ENUM" | "STRING_LIST";
  defaultValue?: unknown;
  label: string;
  description: string;
  allowedValues?: string[];  // For ENUM
}
```

**Editor mapping:**

| Parameter Type | UI Component |
|----------------|--------------|
| `ENUM` | `<TextField select>` with `<MenuItem>` options |
| `BOOLEAN` | `<Switch>` |
| `STRING_LIST` | `<TextField>` comma-separated → array |
| `INTEGER` | `<TextField type="number">` → `parseInt` |
| `FLOAT` | `<TextField type="number">` → `parseFloat` |
| `STRING` | `<TextField>` |

### 13.3 Parameter Change Flow

```typescript
// NodeDetailSidebar calls onParameterChange
onParameterChange?.(nodeId, param.key, value)

// PipelineEditor.handleParameterChange
const handleParameterChange = useCallback((nodeId, key, value) => {
  if (!selected) return;
  const node = selected.definition.nodes.find(n => n.id === nodeId);
  if (node) {
    (node.data as Record<string, unknown>)[key] = value;
    setSelected({ ...selected });  // Trigger re-render
    setDirty(true);
  }
}, [selected]);
```

---

## 14. Pipeline Inspector (Right Panel)

### 14.1 Content

```tsx
<PipelineInspector pipeline={selected} runs={pipelineRuns} runsLoading={runsLoading} />
```

**Displays:**
- Pipeline name + description
- Chips: Enabled/Disabled, Priority, Dry Run
- Latest run status banner (color-coded)
- Scrollable `RunHistory` list

### 14.2 Empty State

When no pipeline selected:
```tsx
<Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: "100%" }}>
  <AccountTreeOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
  <Typography variant="body2" color="text.secondary">Select a pipeline to inspect</Typography>
</Box>
```

---

## 15. Data Type Definitions

### 15.1 Pipeline (UI Type)

```typescript
// src/types/index.ts
interface Pipeline {
  id: string;
  spaceId: string;
  name: string;
  description: string;
  enabled: boolean;
  priority: number;
  dryRun: boolean;
  definition: {
    nodes: PipelineNode[];
    edges: PipelineEdge[];
  };
  runs: PipelineRun[];
  createdAt: string;
  updatedAt: string;
}

interface PipelineNode {
  id: string;
  type: string;           // NodeDescriptor.kind
  label: string;
  description: string;
  position: { x: number; y: number };
  data: Record<string, unknown>;  // Dynamic parameters
}

type EdgeKind = "PASS" | "REJECT" | "ANY";

interface PipelineEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
  animated?: boolean;
  edgeType?: EdgeKind;
}
```

### 15.2 Node Descriptor (API Type)

```typescript
// src/types/nodeDescriptors.ts
interface NodeDescriptor {
  kind: string;                    // Unique identifier
  name: string;                    // Display name
  description: string;
  icon: string;                    // Material icon name
  category: NodeCategory;          // SOURCE | FILTER | ANALYSIS | TRANSFORM | OUTPUT
  inputs: NodeInput[];             // [{ name, contentType, required? }]
  outputs: NodeOutput[];           // [{ name, contentType }]
  parameters: NodeParameter[];     // Configurable parameters
  defaultConcurrency: number;
  defaultMode: NodeMode;           // SEQUENTIAL | PARALLEL
  defaultBlocking: boolean;
  events: string[];                // Event types emitted
}
```

### 15.3 React Flow Node Data

```typescript
// Extended data passed to PipelineNodeComponent
interface PipelineNodeData {
  label: string;
  description: string;
  category: NodeCategory;
  nodeIcon?: React.ReactNode;
  inputs: ConnectorDef[];          // [{ name, dataType }]
  outputs: ConnectorDef[];
  onDelete?: (nodeId: string) => void;
  isActive?: boolean;
  displayName?: string;
  // ... dynamic parameters from descriptor
}
```

---

## 16. Known Issues & Limitations

| Issue | Impact | Workaround |
|-------|--------|------------|
| No pipeline creation UI | Users cannot create new pipelines | Use API directly |
| No pipeline deletion UI | Users cannot delete pipelines | Use API directly |
| No undo/redo | Accidental changes irreversible | Manual re-edit |
| Dirty flag not persisted | Navigate away = lose changes | Save before navigating |
| No collaborative editing | Multi-user conflicts | Single editor at a time |
| Validation only on save | Errors discovered late | Run validation on change (not implemented) |
| Mock video playback | Asset detail not real | N/A (different feature) |
| WebSocket pipeline events not connected | No live run updates | Refresh run history manually |
| Command palette doesn't show categories | Hard to browse | Use Add Node bar with category chips |

---

## 17. Extension Points

### 17.1 Adding New Node Categories

1. Add to `NodeCategory` type in `src/types/nodeDescriptors.ts`
2. Add entry to `categoryConfig` in `PipelineEditor.tsx`
3. Add icon mapping in `ICON_MAP` if needed
4. Backend must return descriptor with new category

### 17.2 Adding New Parameter Types

1. Add to `ParameterType` in `src/types/nodeDescriptors.ts`
2. Add case in `NodeDetailSidebar` parameter editor switch
3. Handle serialization in `handleParameterChange`

### 17.3 Adding New Edge Types

1. Add to `EdgeKind` type in `src/types/index.ts`
2. Add entry to `EDGE_TYPE_STYLE`
3. Update edge context menu options

### 17.4 Custom Node Components

Register in `nodeTypes` object:
```typescript
const nodeTypes = { 
  pipelineNode: PipelineNodeComponent,
  customNode: CustomNodeComponent,  // Add here
};
```

---

## 18. Testing Reference

### 18.1 E2E Test Coverage (`e2e/pipeline-backend.spec.ts`)

| Test | Description |
|------|-------------|
| Node descriptors loaded | Verifies `/api/v1/pipeline/node-descriptors` returns 20+ descriptors |
| Add-node menu categories | Checks category chips filter correctly |
| Add source node | Verifies "Filesystem Source" appears on canvas |
| Add multiple nodes | Adds 4 nodes of different categories |
| Connector handles | Validates source/analysis nodes have correct handles |

### 18.2 Test Selectors (data-testid)

| Element | Selector |
|---------|----------|
| Pipeline canvas | `pipeline-canvas` |
| Add node button | `pipeline-add-node-button` |
| Add node menu | `pipeline-add-node-menu` |
| Node item (by kind) | `pipeline-node-item-{kind}` |
| Category chip | `pipeline-category-chip-{category}` |

---

## 19. Related Documentation

| Document | Link |
|----------|------|
| Loom UI Overview | [LOOM_UI.md](LOOM_UI.md) |
| REST API Specification | [../RESTAPI.md](../RESTAPI.md#34-pipeline-run-endpoint) |
| WebSocket Protocol | [../WEBSOCKET.md](../WEBSOCKET.md) |
| MCP Specification | [../MCP.md](../MCP.md) |