import React, { useCallback, useEffect, useState } from "react";
import ReactFlow, {
  Background, Controls, MiniMap, Handle, Position,
  NodeProps, ReactFlowProvider, useNodesState, useEdgesState,
  MarkerType, Node as RFNode, Edge as RFEdge,
} from "reactflow";
import "reactflow/dist/style.css";
import {
  Box, Typography, Chip, Paper, Divider, IconButton, Tooltip,
  List, ListItemButton, ListItemText, ListItemIcon, Switch, Stack, Avatar,
} from "@mui/material";
import {
  PlayArrowOutlined, AccountTreeOutlined, CheckCircleOutline,
  ErrorOutline, CloudUploadOutlined, FilterAltOutlined,
  SettingsOutlined, CloudDownloadOutlined, MemoryOutlined,
  CircleOutlined, AccessTimeOutlined, BarChartOutlined,
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

// ── Pipeline Inspector (right panel) ─────────────────────────────────────
function PipelineInspector({ pipeline, selectedNodeId }: { pipeline: Pipeline | null; selectedNodeId: string | null }) {
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

      {/* Node detail */}
      <NodeDetailPanel nodeId={selectedNodeId} pipeline={pipeline} />

      {/* Scrollable run history */}
      <Box sx={{ flex: 1, overflow: "auto" }}>
        <RunHistory runs={pipeline.runs} />
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

  useEffect(() => {
    mockPipelineService.getAll().then(ps => {
      setPipelines(ps);
      setSelected(ps[0] ?? null);
      setLoading(false);
    });
  }, []);

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
                onClick={() => { setSelected(p); setSelectedNodeId(null); }}
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
          <PipelineCanvas pipeline={selected} onNodeSelect={setSelectedNodeId} />
        </Box>
      </ReactFlowProvider>

      {/* Inspector panel */}
      <Box sx={{ width: 260, flexShrink: 0, borderLeft: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, overflow: "hidden" }}>
        <PipelineInspector pipeline={selected} selectedNodeId={selectedNodeId} />
      </Box>
    </Box>
  );
}
