import * as React from "react";
import { useEffect, useState, useCallback, useMemo } from "react";
import { Container, Grid, Typography, Chip, Box, FormControl, InputLabel, Select, MenuItem, CircularProgress } from "@mui/material";
import "./flow-style.css";
import "reactflow/dist/style.css";

import ReactFlow, {
  ReactFlowProvider,
  Controls,
  Node,
  Edge,
  Handle,
  Position,
  NodeProps,
} from "reactflow";

import {
  subscribePipelineEvents,
  PipelineEventMessage,
  PipelineEventType,
} from "../api/pipelineEvents";
import { listPipelines, PipelineResponse } from "../api/pipelines";
import { useAuth } from "../context/AuthContext";

// ── Per-node live state ──────────────────────────────────────────────────

interface NodeLiveState {
  status: "idle" | "processing" | "completed" | "failed" | "skipped" | "buffered";
  activeCount: number;
  pendingCount: number;
  processedCount: number;
  failedCount: number;
  lastDurationMs?: number;
}

const INITIAL_STATE: NodeLiveState = {
  status: "idle",
  activeCount: 0,
  pendingCount: 0,
  processedCount: 0,
  failedCount: 0,
};

// ── Colour mapping ───────────────────────────────────────────────────────

const STATUS_COLOURS: Record<NodeLiveState["status"], string> = {
  idle: "#e0e0e0",
  processing: "#42a5f5",
  completed: "#66bb6a",
  failed: "#ef5350",
  skipped: "#bdbdbd",
  buffered: "#ffa726",
};

// ── Custom ReactFlow node ────────────────────────────────────────────────

function PipelineNodeComponent({ data }: NodeProps) {
  const state: NodeLiveState = data.liveState ?? INITIAL_STATE;
  const borderColor = STATUS_COLOURS[state.status];
  return (
    <Box
      sx={{
        border: `3px solid ${borderColor}`,
        borderRadius: 2,
        background: "#fff",
        px: 2,
        py: 1,
        minWidth: 140,
        textAlign: "center",
      }}
    >
      <Handle type="target" position={Position.Top} />
      <Typography variant="subtitle2">{data.label}</Typography>
      <Box sx={{ display: "flex", gap: 0.5, justifyContent: "center", mt: 0.5, flexWrap: "wrap" }}>
        {state.activeCount > 0 && <Chip label={`active: ${state.activeCount}`} size="small" color="primary" />}
        {state.pendingCount > 0 && <Chip label={`queued: ${state.pendingCount}`} size="small" color="warning" />}
        {state.processedCount > 0 && (
          <Chip label={`done: ${state.processedCount}`} size="small" color="success" />
        )}
        {state.failedCount > 0 && (
          <Chip label={`fail: ${state.failedCount}`} size="small" color="error" />
        )}
      </Box>
      <Handle type="source" position={Position.Bottom} />
    </Box>
  );
}

// ── Helpers ──────────────────────────────────────────────────────────────

/** Convert a pipeline definition from the REST API into ReactFlow nodes/edges. */
function definitionToGraph(definition: Record<string, unknown> | undefined): { nodes: Node[]; edges: Edge[] } {
  if (!definition) return { nodes: [], edges: [] };
  const rawNodes = (definition.nodes as Array<Record<string, unknown>>) ?? [];
  const rawEdges = (definition.edges as Array<Record<string, unknown>>) ?? [];
  const nodes: Node[] = rawNodes.map((n) => ({
    id: String(n.id),
    type: "pipeline",
    data: { label: n.label ?? n.type ?? n.id },
    position: {
      x: (n.position as { x?: number })?.x ?? 0,
      y: (n.position as { y?: number })?.y ?? 0,
    },
  }));
  const edges: Edge[] = rawEdges.map((e) => ({
    id: String(e.id),
    source: String(e.source),
    target: String(e.target),
    animated: !!e.animated,
  }));
  return { nodes, edges };
}

function eventToStatus(type: PipelineEventType): NodeLiveState["status"] {
  switch (type) {
    case "NODE_STARTED":
      return "processing";
    case "NODE_COMPLETED":
      return "completed";
    case "NODE_FAILED":
      return "failed";
    case "NODE_SKIPPED":
      return "skipped";
    case "NODE_BUFFERED":
      return "buffered";
    default:
      return "idle";
  }
}

const nodeTypes = { pipeline: PipelineNodeComponent };

// ── Main component ───────────────────────────────────────────────────────

export default function PipelineArea() {
  const { token } = useAuth();
  const [pipelines, setPipelines] = useState<PipelineResponse[]>([]);
  const [selectedId, setSelectedId] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [nodeStates, setNodeStates] = useState<Record<string, NodeLiveState>>({});

  // Fetch pipelines from the REST API
  useEffect(() => {
    if (!token) return;
    listPipelines(token)
      .then((resp) => {
        const ps = resp.data ?? [];
        setPipelines(ps);
        if (ps.length > 0) setSelectedId(ps[0].uuid);
      })
      .catch((err) => console.error("Failed to load pipelines", err))
      .finally(() => setLoading(false));
  }, [token]);

  const selectedPipeline = pipelines.find((p) => p.uuid === selectedId);
  const { nodes: baseNodes, edges } = useMemo(
    () => definitionToGraph(selectedPipeline?.definition),
    [selectedPipeline]
  );

  const handleEvent = useCallback((event: PipelineEventMessage) => {
    if (!event.nodeId) return;

    setNodeStates((prev) => {
      const current = prev[event.nodeId!] ?? { ...INITIAL_STATE };
      const updated = { ...current };

      if (event.type === "NODE_STATS") {
        updated.activeCount = event.activeCount ?? current.activeCount;
        updated.pendingCount = event.pendingCount ?? current.pendingCount;
        updated.processedCount = event.processedCount ?? current.processedCount;
        updated.failedCount = event.failedCount ?? current.failedCount;
      } else {
        updated.status = eventToStatus(event.type);
        if (event.type === "NODE_COMPLETED") {
          updated.processedCount = current.processedCount + 1;
          updated.lastDurationMs = event.durationMs;
        } else if (event.type === "NODE_FAILED") {
          updated.failedCount = current.failedCount + 1;
        }
      }

      return { ...prev, [event.nodeId!]: updated };
    });
  }, []);

  useEffect(() => {
    const unsub = subscribePipelineEvents(handleEvent, token);
    return unsub;
  }, [handleEvent, token]);

  // Merge live state into ReactFlow node data
  const nodes = useMemo(
    () =>
      baseNodes.map((n) => ({
        ...n,
        data: { ...n.data, liveState: nodeStates[n.id] },
      })),
    [baseNodes, nodeStates]
  );

  if (loading) {
    return (
      <Container maxWidth="lg" sx={{ mt: 4, mb: 4, textAlign: "center" }}>
        <CircularProgress />
      </Container>
    );
  }

  return (
    <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 2 }}>
        <Typography variant="h5">Pipeline Monitor</Typography>
        {pipelines.length > 1 && (
          <FormControl size="small" sx={{ minWidth: 220 }}>
            <InputLabel>Pipeline</InputLabel>
            <Select
              value={selectedId}
              label="Pipeline"
              onChange={(e) => setSelectedId(e.target.value)}
            >
              {pipelines.map((p) => (
                <MenuItem key={p.uuid} value={p.uuid}>
                  {p.name}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        )}
      </Box>
      <Grid item xs={12}>
        <ReactFlowProvider>
          <div style={{ height: 600, width: "100%" }}>
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              fitView
              proOptions={{ hideAttribution: true }}
            >
              <Controls />
            </ReactFlow>
          </div>
        </ReactFlowProvider>
      </Grid>
    </Container>
  );
}
