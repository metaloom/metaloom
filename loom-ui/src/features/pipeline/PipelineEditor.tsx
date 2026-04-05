import React, { useCallback, useEffect, useRef, useState } from "react";
import ReactFlow, {
  Background, Controls, MiniMap, Handle, Position,
  NodeProps, ReactFlowProvider, useNodesState, useEdgesState,
  MarkerType, Node as RFNode, Edge as RFEdge,
  Connection, addEdge, reconnectEdge,
} from "reactflow";
import "reactflow/dist/style.css";
import {
  Box, Typography, Chip, Paper, Divider, IconButton, Tooltip,
  List, ListItemButton, ListItemText, ListItemIcon, Switch, Stack, Avatar, Collapse, TextField,
  Menu, MenuItem,
} from "@mui/material";
import {
  PlayArrowOutlined, AccountTreeOutlined, CheckCircleOutline,
  ErrorOutline, CloudUploadOutlined, FilterAltOutlined,
  SettingsOutlined, CloudDownloadOutlined, MemoryOutlined,
  CircleOutlined, AccessTimeOutlined, BarChartOutlined,
  TerminalOutlined, ExpandLessOutlined, ExpandMoreOutlined,
  ChevronRightOutlined, ChevronLeftOutlined,
  AddOutlined, CenterFocusStrongOutlined, VideocamOutlined,
  MovieFilterOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Pipeline, PipelineNode, PipelineRun } from "../../types";
import { mockPipelineService } from "../../mock/services";
import { useProject } from "../../context/ProjectContext";

// ── Custom Node Types ─────────────────────────────────────────────────────
const nodeTypeConfig: Record<string, { color: string; icon: React.ReactNode; bg: string }> = {
  source: { color: tokens.accent.blue, icon: <CloudUploadOutlined sx={{ fontSize: 14 }} />, bg: `${tokens.accent.blue}18` },
  filter: { color: tokens.accent.amber, icon: <FilterAltOutlined sx={{ fontSize: 14 }} />, bg: `${tokens.accent.amber}18` },
  process: { color: tokens.primary.main, icon: <MemoryOutlined sx={{ fontSize: 14 }} />, bg: tokens.primary.subtle },
  output: { color: tokens.accent.teal, icon: <CloudDownloadOutlined sx={{ fontSize: 14 }} />, bg: `${tokens.accent.teal}18` },
  yolo: { color: "#e040fb", icon: <CenterFocusStrongOutlined sx={{ fontSize: 14 }} />, bg: "#e040fb18" },
  scene_detection: { color: "#ff7043", icon: <MovieFilterOutlined sx={{ fontSize: 14 }} />, bg: "#ff704318" },
};

// Node templates available for adding to a pipeline
const NODE_TEMPLATES: { type: string; label: string; description: string; inputLabel?: string; outputLabel?: string; data: Record<string, unknown> }[] = [
  { type: "source", label: "S3 Source", description: "Watch an S3 bucket for new assets", inputLabel: "Trigger", outputLabel: "Asset", data: { bucket: "", prefix: "/" } },
  { type: "filter", label: "Format Filter", description: "Filter by MIME type", inputLabel: "Asset", outputLabel: "Filtered Asset", data: { types: ["video/*", "image/*"] } },
  { type: "process", label: "Hash", description: "SHA-256 + perceptual hash", inputLabel: "Asset", outputLabel: "Hash", data: { algorithms: ["sha256", "phash"] } },
  { type: "process", label: "Fingerprint", description: "Generate audio/video fingerprint", inputLabel: "Asset", outputLabel: "Fingerprint", data: { engine: "chromaprint" } },
  { type: "process", label: "Resize Proxy", description: "Generate proxy resolutions", inputLabel: "Asset", outputLabel: "Proxy", data: { resolutions: ["720p", "360p"] } },
  { type: "yolo", label: "YOLO Detection", description: "Run YOLOv8 object detection on frames", inputLabel: "Frames", outputLabel: "Detections", data: { model: "yolov8-dam", confidence: 0.72, classes: ["person", "car", "animal"] } },
  { type: "scene_detection", label: "Scene Detection", description: "Detect scene boundaries and transitions", inputLabel: "Asset", outputLabel: "Scenes", data: { model: "scenedetect-v3", threshold: 0.4, minSceneLength: 2.0 } },
  { type: "process", label: "Face Recognition", description: "InspireFace identity matching", inputLabel: "Frames", outputLabel: "Identities", data: { threshold: 0.85 } },
  { type: "process", label: "Sentiment Score", description: "NLP scene sentiment analysis", inputLabel: "Transcription Text", outputLabel: "Sentiment", data: { model: "genai-sentiment-v2" } },
  { type: "output", label: "S3 Delivery", description: "Store outputs in delivery bucket", inputLabel: "Asset", outputLabel: "Stored", data: { bucket: "" } },
  { type: "output", label: "Tag Writer", description: "Write tags back to asset metadata", inputLabel: "Tags", outputLabel: "Updated Asset", data: { overwrite: false } },
  { type: "output", label: "CDN Push", description: "Push to broadcast CDN", inputLabel: "Asset", outputLabel: "Published", data: { cdn: "" } },
];

function PipelineNodeComponent({ data, selected }: NodeProps) {
  const cfg = nodeTypeConfig[data.nodeType as string] ?? nodeTypeConfig.process;

  return (
    <Box
      sx={{
        minWidth: 160,
        bgcolor: tokens.bg.elevated,
        border: `1.5px solid ${selected ? cfg.color : tokens.border.default}`,
        borderRadius: tokens.radius.md,
        overflow: "hidden",
        boxShadow: selected ? `0 0 14px ${cfg.color}44` : `0 2px 8px rgba(0,0,0,0.4)`,
        transition: "border-color 120ms ease, box-shadow 120ms ease",
      }}
    >
      {/* Header stripe */}
      <Box sx={{ height: 3, bgcolor: cfg.color }} />
      <Box sx={{ px: 1.5, py: 1.25, display: "flex", alignItems: "center", gap: 1 }}>
        <Box sx={{ width: 26, height: 26, borderRadius: tokens.radius.sm, bgcolor: cfg.bg, display: "flex", alignItems: "center", justifyContent: "center", color: cfg.color, flexShrink: 0 }}>
          {cfg.icon}
        </Box>
        <Box>
          <Typography sx={{ fontSize: "0.78rem", fontWeight: 700, color: tokens.text.primary, lineHeight: 1.2 }}>
            {data.label as string}{data.displayName ? ` — ${data.displayName}` : ""}
          </Typography>
          <Typography sx={{ fontSize: "0.65rem", color: tokens.text.tertiary, lineHeight: 1.3 }}>
            {(data.description as string)?.slice(0, 40)}
          </Typography>
        </Box>
      </Box>
      <Tooltip title={data.inputLabel as string ?? "Input"} placement="left" arrow>
        <Handle type="target" position={Position.Left} style={{ background: cfg.color, border: `2px solid ${tokens.bg.elevated}`, width: 10, height: 10 }} />
      </Tooltip>
      <Tooltip title={data.outputLabel as string ?? "Output"} placement="right" arrow>
        <Handle type="source" position={Position.Right} style={{ background: cfg.color, border: `2px solid ${tokens.bg.elevated}`, width: 10, height: 10 }} />
      </Tooltip>
    </Box>
  );
}

const nodeTypes = { pipelineNode: PipelineNodeComponent };

// ── Convert pipeline nodes to React Flow format ───────────────────────────
// Default connector labels for node types loaded from pipeline definitions
const defaultConnectorLabels: Record<string, { input: string; output: string }> = {
  source: { input: "Trigger", output: "Asset" },
  filter: { input: "Asset", output: "Filtered Asset" },
  process: { input: "Asset", output: "Processed" },
  output: { input: "Asset", output: "Stored" },
  yolo: { input: "Frames", output: "Detections" },
  scene_detection: { input: "Asset", output: "Scenes" },
};

function toRFNodes(pnodes: PipelineNode[], selectedId: string | null): RFNode[] {
  return pnodes.map(n => {
    const labels = defaultConnectorLabels[n.type] ?? { input: "Input", output: "Output" };
    return {
      id: n.id,
      type: "pipelineNode",
      position: n.position,
      selected: n.id === selectedId,
      data: {
        label: n.label,
        description: n.description,
        nodeType: n.type,
        inputLabel: labels.input,
        outputLabel: labels.output,
        ...n.data,
      },
    };
  });
}

function toRFEdges(edges: Pipeline["definition"]["edges"]): RFEdge[] {
  return edges.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    label: e.label,
    animated: e.animated,
    style: { stroke: tokens.border.strong, strokeWidth: 1.5 },
    markerEnd: { type: MarkerType.ArrowClosed, color: tokens.border.strong, width: 16, height: 16 },
  }));
}

// ── Run History Panel ─────────────────────────────────────────────────────
function RunHistory({ runs }: { runs: PipelineRun[] }) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75, p: 1.5 }}>
      <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 0.5 }}>
        Run History
      </Typography>
      {runs.map(r => (
        <Paper key={r.id} elevation={0} sx={{ bgcolor: tokens.bg.overlay, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, p: 1.25 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
            {r.status === "success" ? <CheckCircleOutline sx={{ fontSize: 14, color: tokens.accent.green }} /> :
              r.status === "failed" ? <ErrorOutline sx={{ fontSize: 14, color: tokens.accent.red }} /> :
                <CircleOutlined sx={{ fontSize: 14, color: tokens.accent.amber }} />}
            <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.75rem", color: r.status === "success" ? tokens.accent.green : r.status === "failed" ? tokens.accent.red : tokens.accent.amber }}>
              {r.status}
            </Typography>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>
              {new Date(r.startedAt).toLocaleDateString()}
            </Typography>
          </Box>
          <Box sx={{ display: "flex", gap: 1 }}>
            <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.7rem" }}>
              {r.processedAssets} assets
            </Typography>
            {r.errors > 0 && (
              <Typography variant="caption" sx={{ color: tokens.accent.red, fontSize: "0.7rem" }}>
                {r.errors} error{r.errors > 1 ? "s" : ""}
              </Typography>
            )}
          </Box>
          {r.log.slice(-1).map((l, i) => (
            <Typography key={i} variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.65rem", display: "block", mt: 0.25, fontFamily: "monospace" }}>
              {l}
            </Typography>
          ))}
        </Paper>
      ))}
    </Box>
  );
}

// ── Node Detail Panel ─────────────────────────────────────────────────────
function NodeDetailPanel({ nodeId, pipeline }: { nodeId: string | null; pipeline: Pipeline | null }) {
  if (!nodeId || !pipeline) return null;
  const node = pipeline.definition.nodes.find(n => n.id === nodeId);
  if (!node) return null;
  const cfg = nodeTypeConfig[node.type] ?? nodeTypeConfig.process;

  return (
    <Box sx={{ p: 1.5, borderTop: `1px solid ${tokens.border.subtle}` }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
        <Box sx={{ width: 22, height: 22, borderRadius: tokens.radius.sm, bgcolor: cfg.bg, display: "flex", alignItems: "center", justifyContent: "center", color: cfg.color }}>
          {cfg.icon}
        </Box>
        <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.82rem" }}>{node.label}</Typography>
      </Box>
      <Typography variant="caption" sx={{ color: tokens.text.secondary, lineHeight: 1.5, display: "block", mb: 1 }}>{node.description}</Typography>
      <Box sx={{ display: "grid", gridTemplateColumns: "auto 1fr", gap: "2px 10px" }}>
        {Object.entries(node.data).map(([k, v]) => (
          <React.Fragment key={k}>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>{k}</Typography>
            <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.7rem", wordBreak: "break-word" }}>
              {Array.isArray(v) ? (v as unknown[]).join(", ") : String(v)}
            </Typography>
          </React.Fragment>
        ))}
      </Box>
    </Box>
  );
}

// ── Pipeline Inspector (right stats panel) ────────────────────────────────
function PipelineInspector({ pipeline }: { pipeline: Pipeline | null }) {
  if (!pipeline) {
    return (
      <Box sx={{ p: 2, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: "100%", gap: 1 }}>
        <AccountTreeOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
        <Typography variant="body2" color="text.secondary">Select a pipeline to inspect</Typography>
      </Box>
    );
  }

  const latestRun = pipeline.runs[0];
  const runStatusColor: Record<string, string> = { success: tokens.accent.green, failed: tokens.accent.red, running: tokens.accent.amber, idle: tokens.text.tertiary, paused: tokens.text.tertiary };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", overflow: "hidden" }}>
      {/* Header */}
      <Box sx={{ px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <Typography variant="subtitle2" fontWeight={700} sx={{ fontSize: "0.875rem", mb: 0.25 }}>{pipeline.name}</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1.4, display: "block" }}>{pipeline.description}</Typography>
        <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", mt: 0.75 }}>
          <Chip label={pipeline.enabled ? "enabled" : "disabled"} size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: pipeline.enabled ? `${tokens.accent.green}22` : tokens.bg.overlay, color: pipeline.enabled ? tokens.accent.green : tokens.text.tertiary }} />
          <Chip label={`priority ${pipeline.priority}`} size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: tokens.bg.overlay }} />
          {pipeline.dryRun && <Chip label="dry run" size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: `${tokens.accent.amber}22`, color: tokens.accent.amber }} />}
        </Box>
      </Box>

      {/* Latest run status */}
      {latestRun && (
        <Box sx={{ px: 2, py: 1, bgcolor: `${runStatusColor[latestRun.status]}0a`, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          {latestRun.status === "success" ? <CheckCircleOutline sx={{ fontSize: 14, color: tokens.accent.green }} /> :
            latestRun.status === "failed" ? <ErrorOutline sx={{ fontSize: 14, color: tokens.accent.red }} /> :
              latestRun.status === "running" ? <CircleOutlined sx={{ fontSize: 14, color: tokens.accent.amber, animation: "spin 1s linear infinite", "@keyframes spin": { from: { transform: "rotate(0deg)" }, to: { transform: "rotate(360deg)" } } }} /> :
                <CircleOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
          <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.75rem", color: runStatusColor[latestRun.status] }}>
            {latestRun.status === "running" ? "Running now" : `Last run: ${latestRun.status}`}
          </Typography>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>
            · {latestRun.processedAssets} assets
          </Typography>
        </Box>
      )}

      {/* Scrollable run history */}
      <Box sx={{ flex: 1, overflow: "auto" }}>
        <RunHistory runs={pipeline.runs} />
      </Box>
    </Box>
  );
}

// ── Node Detail Sidebar (second collapsible right panel) ──────────────────
function NodeDetailSidebar({
  nodeId, pipeline, open, onClose, onDisplayNameChange,
}: {
  nodeId: string | null;
  pipeline: Pipeline | null;
  open: boolean;
  onClose: () => void;
  onDisplayNameChange?: (nodeId: string, name: string) => void;
}) {
  const node = (nodeId && pipeline) ? pipeline.definition.nodes.find(n => n.id === nodeId) ?? null : null;
  const cfg = node ? (nodeTypeConfig[node.type] ?? nodeTypeConfig.process) : null;
  const [displayName, setDisplayName] = useState("");

  // Sync display name when node changes
  useEffect(() => {
    setDisplayName((node as any)?.displayName ?? "");
  }, [nodeId]);

  return (
    <Box
      sx={{
        width: open ? 280 : 0,
        flexShrink: 0,
        borderLeft: open ? `1px solid ${tokens.border.subtle}` : "none",
        borderRight: open ? `1px solid ${tokens.border.subtle}` : "none",
        bgcolor: tokens.bg.surface,
        overflow: "hidden",
        transition: "width 200ms ease",
        display: "flex",
        flexDirection: "column",
        position: "relative",
      }}
    >
      {/* Header */}
      <Box sx={{ px: 2, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1, flexShrink: 0 }}>
        <SettingsOutlined sx={{ fontSize: 14, color: tokens.primary.main }} />
        <Typography variant="caption" fontWeight={700} sx={{ fontSize: "0.78rem", flex: 1, whiteSpace: "nowrap" }}>Node Details</Typography>
        <Tooltip title="Collapse panel">
          <IconButton size="small" onClick={onClose} sx={{ width: 20, height: 20 }}>
            <ChevronLeftOutlined sx={{ fontSize: 14 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: 1.5 }}>
        {node && cfg ? (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
            {/* Node identity */}
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
              <Box sx={{ width: 28, height: 28, borderRadius: tokens.radius.sm, bgcolor: cfg.bg, display: "flex", alignItems: "center", justifyContent: "center", color: cfg.color, flexShrink: 0 }}>
                {cfg.icon}
              </Box>
              <Box>
                <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.85rem", lineHeight: 1.2 }}>{node.label}</Typography>
                <Chip label={node.type} size="small" sx={{ height: 14, fontSize: "0.6rem", bgcolor: `${cfg.color}22`, color: cfg.color, mt: 0.25 }} />
              </Box>
            </Box>

            {/* Description */}
            <TextField
              label="Display Name"
              value={displayName}
              onChange={e => {
                const v = e.target.value.slice(0, 15);
                setDisplayName(v);
                if (onDisplayNameChange && nodeId) onDisplayNameChange(nodeId, v);
              }}
              size="small"
              fullWidth
              placeholder="Max 15 characters"
              inputProps={{ maxLength: 15 }}
              helperText={`${displayName.length}/15`}
              sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem" }, "& .MuiFormHelperText-root": { fontSize: "0.62rem", textAlign: "right" } }}
            />
            <TextField
              label="Description"
              value={node.description}
              multiline
              minRows={2}
              size="small"
              fullWidth
              InputProps={{ readOnly: true }}
              sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem" } }}
            />

            {/* Data fields */}
            <Box>
              <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.66rem", mb: 1, display: "block" }}>
                Configuration
              </Typography>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
                {Object.entries(node.data).map(([k, v]) => (
                  <TextField
                    key={k}
                    label={k}
                    value={Array.isArray(v) ? (v as unknown[]).join(", ") : String(v)}
                    size="small"
                    fullWidth
                    InputProps={{ readOnly: true }}
                    sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem", fontFamily: "monospace" } }}
                  />
                ))}
              </Box>
            </Box>

            {/* Node ID */}
            <Box sx={{ p: 1, bgcolor: tokens.bg.overlay, borderRadius: tokens.radius.sm }}>
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.66rem", display: "block" }}>Node ID</Typography>
              <Typography variant="caption" sx={{ fontFamily: "monospace", fontSize: "0.72rem", color: tokens.text.secondary }}>{node.id}</Typography>
            </Box>
          </Box>
        ) : (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: "100%", gap: 1, pt: 4 }}>
            <SettingsOutlined sx={{ fontSize: 28, color: tokens.text.tertiary }} />
            <Typography variant="caption" color="text.secondary" sx={{ textAlign: "center" }}>Click a node in the canvas to view and edit its details</Typography>
          </Box>
        )}
      </Box>
    </Box>
  );
}

// ── Canvas ────────────────────────────────────────────────────────────────
function PipelineCanvas({ pipeline, onNodeSelect, externalNodes, nodeDisplayNames }: { pipeline: Pipeline | null; onNodeSelect: (id: string | null) => void; externalNodes?: RFNode[]; nodeDisplayNames?: Record<string, string> }) {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const reconnectingEdgeRef = useRef<RFEdge | null>(null);

  // Only reset graph when the pipeline itself changes
  useEffect(() => {
    if (!pipeline) { setNodes([]); setEdges([]); return; }
    setNodes(toRFNodes(pipeline.definition.nodes, null));
    setEdges(toRFEdges(pipeline.definition.edges));
    setSelectedId(null);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pipeline?.id]);

  // Append externally-added nodes
  useEffect(() => {
    if (externalNodes && externalNodes.length > 0) {
      setNodes(prev => {
        const existingIds = new Set(prev.map(n => n.id));
        const newOnes = externalNodes.filter(n => !existingIds.has(n.id));
        return newOnes.length > 0 ? [...prev, ...newOnes] : prev;
      });
    }
  }, [externalNodes, setNodes]);

  // Update selection state without resetting positions
  useEffect(() => {
    setNodes(nds => nds.map(n => ({ ...n, selected: n.id === selectedId })));
  }, [selectedId, setNodes]);

  // Apply display name changes
  useEffect(() => {
    if (!nodeDisplayNames) return;
    setNodes(nds => nds.map(n => {
      const dn = nodeDisplayNames[n.id];
      if (dn !== undefined && n.data.displayName !== dn) {
        return { ...n, data: { ...n.data, displayName: dn } };
      }
      return n;
    }));
  }, [nodeDisplayNames, setNodes]);

  const onNodeClick = useCallback((_: React.MouseEvent, node: RFNode) => {
    setSelectedId(node.id);
    onNodeSelect(node.id);
  }, [onNodeSelect]);

  const onPaneClick = useCallback(() => {
    setSelectedId(null);
    onNodeSelect(null);
  }, [onNodeSelect]);

  // New connection: snap source→target
  const onConnect = useCallback((conn: Connection) => {
    if (!conn.source || !conn.target) return;
    const newEdge: RFEdge = {
      id: `e_${conn.source}_${conn.target}_${Date.now()}`,
      source: conn.source,
      target: conn.target,
      sourceHandle: conn.sourceHandle ?? undefined,
      targetHandle: conn.targetHandle ?? undefined,
      style: { stroke: tokens.border.strong, strokeWidth: 1.5 },
      markerEnd: { type: MarkerType.ArrowClosed, color: tokens.border.strong, width: 16, height: 16 },
    };
    setEdges(eds => addEdge(newEdge, eds));
  }, [setEdges]);

  // Reconnect (drag existing edge to a new target)
  const onReconnectStart = useCallback((_: React.MouseEvent, edge: RFEdge) => {
    reconnectingEdgeRef.current = edge;
  }, []);

  const onReconnect = useCallback((oldEdge: RFEdge, newConn: Connection) => {
    reconnectingEdgeRef.current = null;
    setEdges(eds => reconnectEdge(oldEdge, newConn, eds));
  }, [setEdges]);

  const onReconnectEnd = useCallback((_: MouseEvent | TouchEvent, edge: RFEdge) => {
    // If the reconnect didn't complete (dropped in empty space) → delete the edge
    if (reconnectingEdgeRef.current) {
      setEdges(eds => eds.filter(e => e.id !== edge.id));
      reconnectingEdgeRef.current = null;
    }
  }, [setEdges]);

  if (!pipeline) {
    return (
      <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", bgcolor: tokens.bg.base }}>
        <Typography variant="body2" color="text.secondary">Select a pipeline to view its graph</Typography>
      </Box>
    );
  }

  return (
    <Box sx={{
      flex: 1, height: "100%",
      "& .react-flow__controls": {
        background: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.md,
        boxShadow: "0 2px 8px rgba(0,0,0,0.4)",
      },
      "& .react-flow__controls-button": {
        background: "transparent",
        border: "none",
        borderBottom: `1px solid ${tokens.border.subtle}`,
        fill: tokens.text.secondary,
        color: tokens.text.secondary,
        width: 28,
        height: 28,
        "&:hover": { background: tokens.bg.overlay, fill: tokens.text.primary },
        "&:last-child": { borderBottom: "none" },
      },
    }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        onConnect={onConnect}
        onReconnectStart={onReconnectStart}
        onReconnect={onReconnect}
        onReconnectEnd={onReconnectEnd}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.3 }}
        snapToGrid
        snapGrid={[15, 15]}
        connectionMode={"loose" as any}
        style={{ background: tokens.bg.base }}
      >
        <Background color={tokens.border.subtle} gap={20} />
        <Controls />
        <MiniMap
          style={{ background: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` }}
          nodeColor={() => tokens.border.strong}
        />
      </ReactFlow>
    </Box>
  );
}

// ── Main Pipeline Editor ──────────────────────────────────────────────────
export default function PipelineEditor() {
  const { activeProject } = useProject();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [selected, setSelected] = useState<Pipeline | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [logOpen, setLogOpen] = useState(true);
  const [logHeight, setLogHeight] = useState(160);
  const [nodeDetailOpen, setNodeDetailOpen] = useState(false);
  const [addNodeAnchor, setAddNodeAnchor] = useState<null | HTMLElement>(null);
  const [addedNodes, setAddedNodes] = useState<RFNode[]>([]);
  const [nodeDisplayNames, setNodeDisplayNames] = useState<Record<string, string>>({});
  const isDraggingLog = useRef(false);
  const logRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    mockPipelineService.getAll().then(ps => {
      setPipelines(ps);
      setSelected(ps[0] ?? null);
      setLoading(false);
    });
  }, []);

  const handleNodeSelect = useCallback((id: string | null) => {
    setSelectedNodeId(id);
    if (id !== null) setNodeDetailOpen(true);
  }, []);

  const handleDisplayNameChange = useCallback((nodeId: string, name: string) => {
    setNodeDisplayNames(prev => ({ ...prev, [nodeId]: name }));
  }, []);

  const handleAddNode = useCallback((template: typeof NODE_TEMPLATES[0]) => {
    if (!selected) return;
    const id = `pn_${Date.now()}`;
    const newNode: RFNode = {
      id,
      type: "pipelineNode",
      position: { x: 300 + Math.random() * 100, y: 100 + Math.random() * 100 },
      data: { label: template.label, description: template.description, nodeType: template.type, inputLabel: template.inputLabel ?? "Input", outputLabel: template.outputLabel ?? "Output", ...template.data },
    };
    // Also add to pipeline definition so NodeDetailSidebar can find it
    selected.definition.nodes.push({
      id, type: template.type, label: template.label, description: template.description,
      position: newNode.position, data: template.data,
    });
    setAddedNodes(prev => [...prev, newNode]);
    setAddNodeAnchor(null);
  }, [selected]);

  // Draggable log panel resize
  const handleLogDividerMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isDraggingLog.current = true;
    const startY = e.clientY;
    const startH = logHeight;
    const onMove = (ev: MouseEvent) => {
      if (!isDraggingLog.current) return;
      const delta = startY - ev.clientY;
      setLogHeight(Math.min(Math.max(startH + delta, 80), 400));
    };
    const onUp = () => { isDraggingLog.current = false; window.removeEventListener("mousemove", onMove); window.removeEventListener("mouseup", onUp); };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, [logHeight]);

  const runStatusColor: Record<string, string> = {
    success: tokens.accent.green,
    failed: tokens.accent.red,
    running: tokens.accent.amber,
    idle: tokens.text.tertiary,
    paused: tokens.text.tertiary,
  };

  return (
    <Box sx={{ display: "flex", height: "100%", overflow: "hidden", bgcolor: tokens.bg.base }}>
      {/* Pipeline list */}
      <Box sx={{ width: 220, flexShrink: 0, borderRight: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column" }}>
        <Box sx={{ px: 2, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}` }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Pipelines</Typography>
        </Box>
        <List dense sx={{ p: 1, flex: 1, overflow: "auto" }}>
          {pipelines.map(p => {
            const latestRun = p.runs[0];
            const sc = latestRun ? runStatusColor[latestRun.status] : tokens.text.tertiary;
            return (
              <ListItemButton
                key={p.id}
                selected={selected?.id === p.id}
                onClick={() => { setSelected(p); setSelectedNodeId(null); setNodeDetailOpen(false); }}
                sx={{ borderRadius: tokens.radius.md, mb: 0.5 }}
              >
                <ListItemText
                  primary={<Typography variant="body2" fontWeight={500} noWrap sx={{ fontSize: "0.82rem" }}>{p.name}</Typography>}
                  secondary={
                    <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, mt: 0.25 }}>
                      <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: sc }} />
                      <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>
                        {latestRun ? latestRun.status : "no runs"} · P{p.priority}
                      </Typography>
                    </Box>
                  }
                />
              </ListItemButton>
            );
          })}
        </List>
      </Box>

      {/* Canvas area */}
      <ReactFlowProvider>
        <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
          {/* Canvas toolbar */}
          {selected && (
            <Box sx={{ px: 2, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", alignItems: "center", gap: 1 }}>
              <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.875rem", flex: 1 }}>{selected.name}</Typography>
              {/* Add Node button */}
              <Tooltip title="Add a node to the pipeline">
                <Chip
                  icon={<AddOutlined sx={{ fontSize: 14 }} />}
                  label="Add Node"
                  size="small"
                  onClick={(e) => setAddNodeAnchor(e.currentTarget)}
                  sx={{
                    bgcolor: tokens.bg.overlay,
                    border: `1px solid ${tokens.border.default}`,
                    color: tokens.text.secondary,
                    cursor: "pointer",
                    fontWeight: 500,
                    "&:hover": { bgcolor: tokens.bg.hover, borderColor: tokens.primary.main, color: tokens.primary.light },
                  }}
                />
              </Tooltip>
              <Menu
                anchorEl={addNodeAnchor}
                open={Boolean(addNodeAnchor)}
                onClose={() => setAddNodeAnchor(null)}
                slotProps={{ paper: { sx: { maxHeight: 360, minWidth: 240, bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}` } } }}
              >
                {NODE_TEMPLATES.map((t, i) => {
                  const cfg = nodeTypeConfig[t.type] ?? nodeTypeConfig.process;
                  return (
                    <MenuItem key={i} onClick={() => handleAddNode(t)} sx={{ gap: 1.25, py: 0.75 }}>
                      <Box sx={{ width: 22, height: 22, borderRadius: tokens.radius.sm, bgcolor: cfg.bg, display: "flex", alignItems: "center", justifyContent: "center", color: cfg.color, flexShrink: 0 }}>
                        {cfg.icon}
                      </Box>
                      <Box>
                        <Typography variant="body2" sx={{ fontSize: "0.8rem", fontWeight: 600 }}>{t.label}</Typography>
                        <Typography variant="caption" sx={{ fontSize: "0.65rem", color: tokens.text.tertiary }}>{t.description.slice(0, 45)}</Typography>
                      </Box>
                    </MenuItem>
                  );
                })}
              </Menu>
              {/* Show Log button — only visible when log is collapsed */}
              {!logOpen && (
                <Tooltip title="Show log">
                  <Chip
                    icon={<TerminalOutlined sx={{ fontSize: 13 }} />}
                    label="Log"
                    size="small"
                    onClick={() => setLogOpen(true)}
                    sx={{
                      bgcolor: tokens.bg.overlay,
                      border: `1px solid ${tokens.border.default}`,
                      color: tokens.text.secondary,
                      cursor: "pointer",
                      fontWeight: 500,
                      "&:hover": { bgcolor: tokens.bg.hover, borderColor: tokens.primary.main, color: tokens.primary.light },
                    }}
                  />
                </Tooltip>
              )}
              <Tooltip title={selected.dryRun ? "Dry run mode — no writes" : "Run pipeline"}>
                <Chip
                  icon={<PlayArrowOutlined sx={{ fontSize: 14 }} />}
                  label={selected.dryRun ? "Dry Run" : "Run"}
                  size="small"
                  onClick={() => {}}
                  sx={{
                    bgcolor: selected.dryRun ? `${tokens.accent.amber}22` : tokens.primary.subtle,
                    border: `1px solid ${selected.dryRun ? tokens.accent.amber : tokens.primary.main}`,
                    color: selected.dryRun ? tokens.accent.amber : tokens.primary.light,
                    cursor: "pointer",
                    fontWeight: 600,
                  }}
                />
              </Tooltip>
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                <Typography variant="caption" sx={{ fontSize: "0.72rem", color: tokens.text.tertiary }}>Enabled</Typography>
                <Switch size="small" checked={selected.enabled} />
              </Box>
            </Box>
          )}
          {/* Canvas + log panel */}
          <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
            <Box sx={{ flex: 1, overflow: "hidden" }}>
              <PipelineCanvas pipeline={selected} onNodeSelect={handleNodeSelect} externalNodes={addedNodes} nodeDisplayNames={nodeDisplayNames} />
            </Box>

            {/* Log panel drag handle */}
            <Box
              onMouseDown={handleLogDividerMouseDown}
              sx={{
                height: 6, cursor: "row-resize", bgcolor: tokens.border.subtle,
                borderTop: `1px solid ${tokens.border.subtle}`,
                display: "flex", alignItems: "center", justifyContent: "center",
                "&:hover": { bgcolor: tokens.primary.subtle },
                flexShrink: 0,
              }}
            >
              <Box sx={{ width: 28, height: 2, borderRadius: 1, bgcolor: tokens.border.strong }} />
            </Box>

            {/* Log panel */}
            <Box
              ref={logRef}
              sx={{
                height: logOpen ? logHeight : 0,
                minHeight: logOpen ? 80 : 0,
                overflow: "hidden",
                transition: logOpen ? "none" : "height 200ms ease",
                display: "flex",
                flexDirection: "column",
                bgcolor: tokens.bg.base,
                borderTop: `1px solid ${tokens.border.subtle}`,
                flexShrink: 0,
              }}
            >
              <Box sx={{ px: 2, py: 0.75, display: "flex", alignItems: "center", gap: 1, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, flexShrink: 0 }}>
                <TerminalOutlined sx={{ fontSize: 14, color: tokens.primary.main }} />
                <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.75rem", flex: 1 }}>System Log</Typography>
                {selected && (
                  <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>
                    {selected.name} · {selected.runs[0]?.status ?? "no runs"}
                  </Typography>
                )}
                <IconButton size="small" onClick={() => setLogOpen(v => !v)} sx={{ width: 20, height: 20 }}>
                  {logOpen ? <ExpandMoreOutlined sx={{ fontSize: 14 }} /> : <ExpandLessOutlined sx={{ fontSize: 14 }} />}
                </IconButton>
              </Box>
              <Box sx={{ flex: 1, overflow: "auto", p: 1.5 }}>
                {selected ? (
                  selected.runs.flatMap(r => r.log.map((line, i) => (
                    <Typography
                      key={`${r.id}_${i}`}
                      sx={{
                        fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
                        fontSize: "0.72rem",
                        lineHeight: 1.6,
                        color: line.toLowerCase().includes("error") || line.toLowerCase().includes("fail")
                          ? tokens.accent.red
                          : line.toLowerCase().includes("warn")
                          ? tokens.accent.amber
                          : line.toLowerCase().includes("success") || line.toLowerCase().includes("complete")
                          ? tokens.accent.green
                          : tokens.text.secondary,
                        display: "block",
                        whiteSpace: "pre-wrap",
                        wordBreak: "break-word",
                      }}
                    >
                      <Box component="span" sx={{ color: tokens.text.tertiary, mr: 1.5 }}>
                        {new Date(r.startedAt).toLocaleTimeString()}
                      </Box>
                      {line}
                    </Typography>
                  )))
                ) : (
                  <Typography sx={{ fontFamily: "monospace", fontSize: "0.72rem", color: tokens.text.tertiary }}>
                    Select a pipeline to view logs.
                  </Typography>
                )}
              </Box>
            </Box>
          </Box>
        </Box>
      </ReactFlowProvider>

      {/* Node detail sidebar (collapsible) — left of stats panel */}
      <NodeDetailSidebar
        nodeId={selectedNodeId}
        pipeline={selected}
        open={nodeDetailOpen}
        onClose={() => setNodeDetailOpen(false)}
        onDisplayNameChange={handleDisplayNameChange}
      />

      {/* Stats inspector panel */}
      <Box sx={{ width: 240, flexShrink: 0, borderLeft: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, overflow: "hidden", display: "flex", flexDirection: "column" }}>
        {/* Expand node detail button — shown when panel is collapsed and a node is selected */}
        {!nodeDetailOpen && selectedNodeId && (
          <Tooltip title="Show node details" placement="left">
            <Box
              onClick={() => setNodeDetailOpen(true)}
              sx={{
                px: 1.5, py: 0.75, display: "flex", alignItems: "center", gap: 0.75,
                borderBottom: `1px solid ${tokens.border.subtle}`,
                cursor: "pointer", bgcolor: tokens.primary.subtle,
                "&:hover": { bgcolor: `${tokens.primary.main}22` },
              }}
            >
              <ChevronLeftOutlined sx={{ fontSize: 14, color: tokens.primary.main }} />
              <Typography variant="caption" sx={{ fontSize: "0.7rem", color: tokens.primary.light, fontWeight: 600 }}>Node Details</Typography>
            </Box>
          </Tooltip>
        )}
        <Box sx={{ flex: 1, overflow: "hidden" }}>
          <PipelineInspector pipeline={selected} />
        </Box>
      </Box>
    </Box>
  );
}
