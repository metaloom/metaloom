import * as React from "react";
import { useEffect, useState, useCallback, useMemo } from "react";
import { Container, Grid, Typography, Chip, Box } from "@mui/material";
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

const nodeTypes = { pipeline: PipelineNodeComponent };

// ── Static graph layout (matches existing demo graph) ────────────────────

const INITIAL_NODES: Node[] = [
  { id: "src", type: "pipeline", data: { label: "Source" }, position: { x: 350, y: 0 } },
  { id: "filter", type: "pipeline", data: { label: "Filter" }, position: { x: 350, y: 120 } },
  { id: "hash", type: "pipeline", data: { label: "Hash" }, position: { x: 100, y: 260 } },
  { id: "resize", type: "pipeline", data: { label: "Resize" }, position: { x: 350, y: 260 } },
  { id: "fp", type: "pipeline", data: { label: "Fingerprint" }, position: { x: 600, y: 260 } },
  { id: "s3", type: "pipeline", data: { label: "S3" }, position: { x: 350, y: 400 } },
];

const EDGES: Edge[] = [
  { id: "e1", source: "src", target: "filter", animated: true },
  { id: "e2", source: "filter", target: "hash", animated: true },
  { id: "e3", source: "filter", target: "resize", animated: true },
  { id: "e4", source: "filter", target: "fp", animated: true },
  { id: "e5", source: "resize", target: "s3", animated: true },
];

// ── Helpers ──────────────────────────────────────────────────────────────

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

// ── Main component ───────────────────────────────────────────────────────

export default function PipelineArea() {
  const [nodeStates, setNodeStates] = useState<Record<string, NodeLiveState>>({});

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
    const unsub = subscribePipelineEvents(handleEvent);
    return unsub;
  }, [handleEvent]);

  // Merge live state into ReactFlow node data
  const nodes = useMemo(
    () =>
      INITIAL_NODES.map((n) => ({
        ...n,
        data: { ...n.data, liveState: nodeStates[n.id] },
      })),
    [nodeStates]
  );

  return (
    <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
      <Typography variant="h5" gutterBottom>
        Pipeline Monitor
      </Typography>
      <Grid item xs={12}>
        <ReactFlowProvider>
          <div style={{ height: 600, width: "100%" }}>
            <ReactFlow
              nodes={nodes}
              edges={EDGES}
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
