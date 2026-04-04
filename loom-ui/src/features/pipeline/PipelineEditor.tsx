import React, { useCallback, useEffect, useRef, useState } from "react";
import ReactFlow, {
  Background, Controls, MiniMap, Handle, Position,
  NodeProps, ReactFlowProvider, useNodesState, useEdgesState,
  MarkerType, Node as RFNode, Edge as RFEdge,
} from "reactflow";
import "reactflow/dist/style.css";
import {
  Box, Typography, Chip, Paper, Divider, IconButton, Tooltip,
  List, ListItemButton, ListItemText, ListItemIcon, Switch, Stack, Avatar, Collapse, TextField,
} from "@mui/material";
import {
  PlayArrowOutlined, AccountTreeOutlined, CheckCircleOutline,
  ErrorOutline, CloudUploadOutlined, FilterAltOutlined,
  SettingsOutlined, CloudDownloadOutlined, MemoryOutlined,
  CircleOutlined, AccessTimeOutlined, BarChartOutlined,
  TerminalOutlined, ExpandLessOutlined, ExpandMoreOutlined,
  ChevronRightOutlined, ChevronLeftOutlined,
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
};

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
            {data.label as string}
          </Typography>
          <Typography sx={{ fontSize: "0.65rem", color: tokens.text.tertiary, lineHeight: 1.3 }}>
            {(data.description as string)?.slice(0, 40)}
          </Typography>
        </Box>
      </Box>
      <Handle type="target" position={Position.Left} style={{ background: cfg.color, border: `2px solid ${tokens.bg.elevated}`, width: 10, height: 10 }} />
      <Handle type="source" position={Position.Right} style={{ background: cfg.color, border: `2px solid ${tokens.bg.elevated}`, width: 10, height: 10 }} />
    </Box>
  );
}

const nodeTypes = { pipelineNode: PipelineNodeComponent };

// ── Convert pipeline nodes to React Flow format ───────────────────────────
function toRFNodes(pnodes: PipelineNode[], selectedId: string | null): RFNode[] {
  return pnodes.map(n => ({
    id: n.id,
    type: "pipelineNode",
    position: n.position,
    selected: n.id === selectedId,
    data: {
      label: n.label,
      description: n.description,
      nodeType: n.type,
      ...n.data,
    },
  }));
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
  nodeId, pipeline, open, onClose,
}: {
  nodeId: string | null;
  pipeline: Pipeline | null;
  open: boolean;
  onClose: () => void;
}) {
  const node = (nodeId && pipeline) ? pipeline.definition.nodes.find(n => n.id === nodeId) ?? null : null;
  const cfg = node ? (nodeTypeConfig[node.type] ?? nodeTypeConfig.process) : null;

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
function PipelineCanvas({ pipeline, onNodeSelect }: { pipeline: Pipeline | null; onNodeSelect: (id: string | null) => void }) {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  useEffect(() => {
    if (!pipeline) { setNodes([]); setEdges([]); return; }
    setNodes(toRFNodes(pipeline.definition.nodes, selectedId));
    setEdges(toRFEdges(pipeline.definition.edges));
  }, [pipeline, selectedId]);

  const onNodeClick = useCallback((_: React.MouseEvent, node: RFNode) => {
    setSelectedId(node.id);
    onNodeSelect(node.id);
  }, [onNodeSelect]);

  const onPaneClick = useCallback(() => {
    setSelectedId(null);
    onNodeSelect(null);
  }, [onNodeSelect]);

  if (!pipeline) {
    return (
      <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", bgcolor: tokens.bg.base }}>
        <Typography variant="body2" color="text.secondary">Select a pipeline to view its graph</Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ flex: 1, height: "100%" }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.3 }}
        style={{ background: tokens.bg.base }}
      >
        <Background color={tokens.border.subtle} gap={20} />
        <Controls style={{ background: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md }} />
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
              <PipelineCanvas pipeline={selected} onNodeSelect={handleNodeSelect} />
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
