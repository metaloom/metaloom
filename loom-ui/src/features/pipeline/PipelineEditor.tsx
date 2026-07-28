import React, { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import ReactFlow, {
  Background, Controls, MiniMap, Handle, Position,
  NodeProps, ReactFlowProvider, useNodesState, useEdgesState,
  MarkerType, Node as RFNode, Edge as RFEdge,
  Connection, addEdge, reconnectEdge, useReactFlow,
  BackgroundVariant,
} from "reactflow";
import "reactflow/dist/style.css";
import {
  Box, Typography, Chip, Paper, Divider, IconButton, Tooltip,
  List, ListItemButton, ListItemText, ListItemIcon, Switch, Stack, Avatar, Collapse, TextField,
  Autocomplete, InputAdornment, Popper, ClickAwayListener, MenuItem,
  Dialog, DialogTitle, DialogContent, DialogContentText, DialogActions, Button,
  Snackbar, Alert, Popover, CircularProgress, Drawer,
} from "@mui/material";
import {
  PlayArrowOutlined, AccountTreeOutlined, CheckCircleOutline,
  ErrorOutline, CloudUploadOutlined, FilterAltOutlined,
  SettingsOutlined, CloudDownloadOutlined, MemoryOutlined,
  CircleOutlined, AccessTimeOutlined, BarChartOutlined, StopCircleOutlined,
  TerminalOutlined, ExpandLessOutlined, ExpandMoreOutlined,
  ChevronRightOutlined, ChevronLeftOutlined,
  AddOutlined, CenterFocusStrongOutlined, VideocamOutlined,
  MovieFilterOutlined, FolderOpenOutlined, AutoAwesomeOutlined,
  LocalOfferOutlined, ImageOutlined, SubtitlesOutlined,
  DataObjectOutlined, BugReportOutlined,
  Fingerprint, Mic, Psychology, Tune, HighQuality, Grain,
  TextFields, GridView, LinearScale, Straighten, ContentCopy,
  DateRange, Block, VerifiedOutlined, PlaylistRemoveOutlined,
  ImageSearchOutlined, FaceRetouchingNatural, Face, Description,
  LayersOutlined, SchemaOutlined, PaletteOutlined,
  TransformOutlined, CloseOutlined, SearchOutlined,
  SaveOutlined, HistoryOutlined, RestoreOutlined,
  CompareArrowsOutlined, DeleteOutline,
} from "@mui/icons-material";
import { Tabs, Tab } from "@mui/material";
import { tokens } from "../../theme";
import { Pipeline, PipelineNode, EdgeKind, pipelineNodeOptions } from "../../types";
import {
  listPipelines, PipelineResponse,
  updatePipeline, runPipeline, cancelPipelineRun, listPipelineRuns, type PipelineUpdateRequest,
  createPipeline, deletePipeline, type PipelineCreateRequest,
  type PipelineRunRecord,
  listPipelineRunItems, type PipelineRunItemRecord,
  listPipelineVersions, restorePipelineVersion,
} from "../../api/pipelines";
import { subscribePipelineEvents, type PipelineEventMessage } from "../../api/pipelineEvents";
import { useAuth } from "../../context/AuthContext";
import { useSpace } from "../../context/SpaceContext";
import { useNodeRegistry } from "../../context/NodeRegistryContext";
import type { NodeDescriptor, NodeCategory } from "../../types/nodeDescriptors";
import { PipelineVersionDiff } from "./PipelineVersionDiff";

// Default affinity group name. Mirrors PipelineGraphNode.DEFAULT_AFFINITY on the
// Loom side: a null/blank affinity collapses to "default" (one group per node).
const DEFAULT_AFFINITY = "default";

// Derive a stable colour for a non-default affinity group from its name, so nodes
// sharing a group visibly share a colour on the canvas. Returns null for the
// implicit "default" group (rendered neutrally).
function affinityColor(affinity: string | undefined): string | null {
  if (!affinity || affinity === DEFAULT_AFFINITY) return null;
  let hash = 0;
  for (let i = 0; i < affinity.length; i++) {
    hash = (hash * 31 + affinity.charCodeAt(i)) | 0;
  }
  const hue = Math.abs(hash) % 360;
  return `hsl(${hue}, 65%, 55%)`;
}

// ── Category-based node styling ───────────────────────────────────────────
const categoryConfig: Record<NodeCategory, { color: string; icon: React.ReactNode; bg: string }> = {
  SOURCE:    { color: tokens.accent.blue,  icon: <CloudUploadOutlined sx={{ fontSize: 14 }} />,  bg: `${tokens.accent.blue}18` },
  FILTER:    { color: tokens.accent.amber, icon: <FilterAltOutlined sx={{ fontSize: 14 }} />,    bg: `${tokens.accent.amber}18` },
  ANALYSIS:  { color: tokens.primary.main, icon: <MemoryOutlined sx={{ fontSize: 14 }} />,       bg: tokens.primary.subtle },
  TRANSFORM: { color: "#e040fb",           icon: <TransformOutlined sx={{ fontSize: 14 }} />,     bg: "#e040fb18" },
  OUTPUT:    { color: tokens.accent.teal,  icon: <CloudDownloadOutlined sx={{ fontSize: 14 }} />, bg: `${tokens.accent.teal}18` },
};

// Map material icon names from the API to MUI components
const ICON_MAP: Record<string, React.ReactNode> = {
  folder_open:              <FolderOpenOutlined sx={{ fontSize: 14 }} />,
  cloud_download:           <CloudDownloadOutlined sx={{ fontSize: 14 }} />,
  cloud_upload:             <CloudUploadOutlined sx={{ fontSize: 14 }} />,
  fingerprint:              <Fingerprint sx={{ fontSize: 14 }} />,
  movie_filter:             <MovieFilterOutlined sx={{ fontSize: 14 }} />,
  description:              <Description sx={{ fontSize: 14 }} />,
  mic:                      <Mic sx={{ fontSize: 14 }} />,
  text_fields:              <TextFields sx={{ fontSize: 14 }} />,
  psychology:               <Psychology sx={{ fontSize: 14 }} />,
  label:                    <LocalOfferOutlined sx={{ fontSize: 14 }} />,
  image_search:             <ImageSearchOutlined sx={{ fontSize: 14 }} />,
  grid_view:                <GridView sx={{ fontSize: 14 }} />,
  tune:                     <Tune sx={{ fontSize: 14 }} />,
  high_quality:             <HighQuality sx={{ fontSize: 14 }} />,
  grain:                    <Grain sx={{ fontSize: 14 }} />,
  linear_scale:             <LinearScale sx={{ fontSize: 14 }} />,
  straighten:               <Straighten sx={{ fontSize: 14 }} />,
  content_copy:             <ContentCopy sx={{ fontSize: 14 }} />,
  filter_alt:               <FilterAltOutlined sx={{ fontSize: 14 }} />,
  date_range:               <DateRange sx={{ fontSize: 14 }} />,
  block:                    <Block sx={{ fontSize: 14 }} />,
  verified:                 <VerifiedOutlined sx={{ fontSize: 14 }} />,
  playlist_remove:          <PlaylistRemoveOutlined sx={{ fontSize: 14 }} />,
  face:                     <Face sx={{ fontSize: 14 }} />,
  face_retouching_natural:  <FaceRetouchingNatural sx={{ fontSize: 14 }} />,
  file_copy:                <ContentCopy sx={{ fontSize: 14 }} />,
  layers:                   <LayersOutlined sx={{ fontSize: 14 }} />,
  schema:                   <SchemaOutlined sx={{ fontSize: 14 }} />,
  palette:                  <PaletteOutlined sx={{ fontSize: 14 }} />,
};

/** Resolve the icon for a descriptor, falling back to its category default. */
function resolveNodeIcon(desc: NodeDescriptor): React.ReactNode {
  return ICON_MAP[desc.icon] ?? categoryConfig[desc.category]?.icon ?? <MemoryOutlined sx={{ fontSize: 14 }} />;
}

/** Get the visual config (color, icon, bg) for a descriptor. */
function nodeVisualConfig(desc: NodeDescriptor) {
  const cat = categoryConfig[desc.category] ?? categoryConfig.ANALYSIS;
  return { ...cat, icon: resolveNodeIcon(desc) };
}

// Connector data types – derived from content-type strings
type ConnectorDataType = "media" | "data" | "control" | "text" | "hash";

interface ConnectorDef {
  name: string;
  dataType: ConnectorDataType;
}

/** Map an API content-type string to a UI connector data type. */
function toConnectorDataType(contentType: string): ConnectorDataType {
  if (contentType.startsWith("media/")) return "media";
  if (contentType === "data/hash" || contentType === "data/fingerprint") return "hash";
  if (contentType === "data/text" || contentType === "data/transcript" || contentType === "data/caption") return "text";
  if (contentType.startsWith("control/")) return "control";
  return "data";
}

/** Convert a NodeDescriptor to ConnectorDef arrays. */
function descriptorConnectors(desc: NodeDescriptor): { inputs: ConnectorDef[]; outputs: ConnectorDef[] } {
  return {
    inputs: desc.inputs.map((i) => ({ name: i.name, dataType: toConnectorDataType(i.contentType) })),
    outputs: desc.outputs.map((o) => ({ name: o.name, dataType: toConnectorDataType(o.contentType) })),
  };
}

/**
 * Content type each script output value type carries downstream. Mirrors `ScriptValueType`
 * in the cortex script node — keep the two in step when adding a value type.
 */
const SCRIPT_VALUE_CONTENT_TYPE: Record<string, string> = {
  STRING: "data/string", TEXT: "data/text", INTEGER: "data/integer", NUMBER: "data/number",
  BOOLEAN: "data/boolean", JSON: "data/text", TEXT_LIST: "data/text", TIMEFRAMES: "data/scene",
  IMAGE: "data/thumbnail", IMAGE_LIST: "data/thumbnail", PATH: "data/path",
};

/**
 * Connectors for one node, preferring any the node declares for itself.
 *
 * Most kinds have a fixed output set that the descriptor states. A `script` node's outputs are
 * per instance — they come from its `outputs` option — so the descriptor cannot know them and
 * the handles have to be derived from the node's own configuration instead.
 */
function nodeConnectors(desc: NodeDescriptor | undefined, options: Record<string, unknown>): { inputs: ConnectorDef[]; outputs: ConnectorDef[] } {
  const base = desc
    ? descriptorConnectors(desc)
    : { inputs: [{ name: "Input", dataType: "media" as ConnectorDataType }], outputs: [{ name: "Output", dataType: "media" as ConnectorDataType }] };

  const declared = options.outputs;
  if (!Array.isArray(declared) || declared.length === 0) return base;

  const outputs = declared
    .filter((o): o is { key: string; type?: string } => !!o && typeof o === "object" && typeof (o as { key?: unknown }).key === "string")
    .map(o => ({ name: o.key, dataType: toConnectorDataType(SCRIPT_VALUE_CONTENT_TYPE[o.type ?? "STRING"] ?? "data/string") }));

  return outputs.length > 0 ? { inputs: base.inputs, outputs } : base;
}

// Color map for connector data types
const DATA_TYPE_COLOR: Record<ConnectorDataType, string> = {
  text: "#42a5f5",
  media: "#66bb6a",
  data: "#ffa726",
  hash: "#ab47bc",
  control: "#78909c",
};

// ── Custom Pipeline Node Component ────────────────────────────────────────
function PipelineNodeComponent({ data, selected, id }: NodeProps) {
  const category = (data.category as NodeCategory) ?? "ANALYSIS";
  const cfg = categoryConfig[category] ?? categoryConfig.ANALYSIS;
  const nodeIcon = data.nodeIcon as React.ReactNode | undefined;
  const isSource = category === "SOURCE";
  const isActive = data.isActive as boolean | undefined;
  const lastResult = data.lastResult as "completed" | "failed" | undefined;
  const inputs = isSource ? [] : ((data.inputs as ConnectorDef[] | undefined) ?? [{ name: "Input", dataType: "media" as ConnectorDataType }]);
  const outputs = (data.outputs as ConnectorDef[] | undefined) ?? [{ name: "Output", dataType: "media" as ConnectorDataType }];
  const [hovered, setHovered] = useState(false);
  const onDelete = data.onDelete as ((nodeId: string) => void) | undefined;

  // Affinity group: nodes sharing a non-default group are outlined + badged in a
  // group-derived colour so the resulting pipeline segment is legible at a glance.
  const affinity = (data.affinity as string | undefined) || DEFAULT_AFFINITY;
  const groupColor = affinityColor(affinity);

  // When the node is not currently processing, tint its side borders to reflect
  // the last result received over the pipeline-events socket.
  const resultColor = lastResult === "completed" ? tokens.accent.green : lastResult === "failed" ? tokens.accent.red : null;
  const sideBorderColor = selected ? cfg.color : (!isActive && resultColor) ? resultColor : tokens.border.default;

  return (
    <Box
      data-testid={`pipeline-node-${id}`}
      data-active={isActive ? "true" : "false"}
      data-result={lastResult ?? "none"}
      data-affinity={affinity}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      sx={{
        minWidth: 180,
        bgcolor: tokens.bg.elevated,
        borderLeft: `1.5px solid ${sideBorderColor}`,
        borderRight: `1.5px solid ${sideBorderColor}`,
        borderTop: `3px solid ${cfg.color}`,
        borderBottom: `3px solid ${cfg.color}`,
        borderRadius: tokens.radius.md,
        overflow: "visible",
        boxShadow: selected
          ? `0 0 14px ${cfg.color}44`
          : isActive
          ? `0 0 18px ${cfg.color}55, 0 0 6px ${cfg.color}33`
          : `0 2px 8px rgba(0,0,0,0.4)`,
        transition: "border-color 120ms ease, box-shadow 120ms ease",
        position: "relative",
        // Group outline (offset ring) for non-default affinity groups.
        ...(groupColor && {
          outline: `2px dashed ${groupColor}`,
          outlineOffset: 3,
        }),
        ...(isActive && {
          animation: "pulse-active 2s ease-in-out infinite",
          "@keyframes pulse-active": {
            "0%, 100%": { boxShadow: `0 0 8px ${cfg.color}33` },
            "50%": { boxShadow: `0 0 22px ${cfg.color}66, 0 0 8px ${cfg.color}44` },
          },
        }),
      }}
    >
      {/* Affinity group badge */}
      {groupColor && (
        <Box
          data-testid={`pipeline-node-affinity-badge-${id}`}
          sx={{
            position: "absolute",
            top: -9,
            left: 8,
            px: 0.75,
            height: 15,
            display: "flex",
            alignItems: "center",
            gap: 0.4,
            borderRadius: 999,
            bgcolor: groupColor,
            color: "#fff",
            fontSize: "0.56rem",
            fontWeight: 700,
            letterSpacing: "0.02em",
            lineHeight: 1,
            zIndex: 9,
            boxShadow: "0 1px 3px rgba(0,0,0,0.4)",
            maxWidth: 120,
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
          }}
        >
          {affinity}
        </Box>
      )}
      {/* Active indicator dot */}
      {isActive && !hovered && (
        <Box
          sx={{
            position: "absolute",
            top: -4,
            right: -4,
            width: 10,
            height: 10,
            borderRadius: "50%",
            bgcolor: tokens.accent.green,
            border: `2px solid ${tokens.bg.elevated}`,
            zIndex: 10,
            animation: "blink 1.4s ease-in-out infinite",
            "@keyframes blink": {
              "0%, 100%": { opacity: 1 },
              "50%": { opacity: 0.3 },
            },
          }}
        />
      )}

      {/* Delete button */}
      {hovered && onDelete && (
        <Tooltip title="Delete node">
          <IconButton
            size="small"
            onClick={(e) => { e.stopPropagation(); onDelete(id); }}
            sx={{
              position: "absolute",
              top: -5,
              right: -5,
              width: 14,
              height: 14,
              bgcolor: tokens.accent.red,
              color: "#fff",
              zIndex: 10,
              "&:hover": { bgcolor: "#d32f2f" },
              boxShadow: "0 1px 4px rgba(0,0,0,0.4)",
            }}
          >
            <CloseOutlined sx={{ fontSize: 8 }} />
          </IconButton>
        </Tooltip>
      )}

      {/* Header stripe */}
      <Box sx={{ px: 1.5, py: 1.25, display: "flex", alignItems: "center", gap: 1 }}>
        <Box sx={{ width: 26, height: 26, borderRadius: tokens.radius.sm, bgcolor: cfg.bg, display: "flex", alignItems: "center", justifyContent: "center", color: cfg.color, flexShrink: 0 }}>
          {nodeIcon ?? cfg.icon}
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

      {/* Input handles */}
      {inputs.map((inp, idx) => {
        const topPct = inputs.length === 1 ? 50 : 30 + (idx * 40) / Math.max(1, inputs.length - 1);
        const dtColor = DATA_TYPE_COLOR[inp.dataType] ?? tokens.text.tertiary;
        return (
          <React.Fragment key={`in_${idx}`}>
            <Handle
              type="target"
              position={Position.Left}
              id={`in_${idx}`}
              style={{ background: dtColor, border: `2px solid ${tokens.bg.elevated}`, width: 10, height: 10, top: `${topPct}%` }}
            />
            {hovered && (
              <Box
                sx={{
                  position: "absolute",
                  left: -8,
                  top: `${topPct}%`,
                  transform: "translate(-100%, -50%)",
                  pointerEvents: "none",
                  px: 0.5,
                  py: 0.15,
                  borderRadius: "3px",
                  bgcolor: `${tokens.bg.base}ee`,
                  border: `1px solid ${dtColor}44`,
                  display: "flex",
                  alignItems: "center",
                  whiteSpace: "nowrap",
                }}
              >
                <Typography sx={{ fontSize: "0.52rem", color: dtColor, fontWeight: 600, lineHeight: 1 }}>
                  {inp.dataType}
                </Typography>
              </Box>
            )}
          </React.Fragment>
        );
      })}

      {/* Output handles */}
      {outputs.map((out, idx) => {
        const topPct = outputs.length === 1 ? 50 : 30 + (idx * 40) / Math.max(1, outputs.length - 1);
        const dtColor = DATA_TYPE_COLOR[out.dataType] ?? tokens.text.tertiary;
        return (
          <React.Fragment key={`out_${idx}`}>
            <Handle
              type="source"
              position={Position.Right}
              id={`out_${idx}`}
              style={{ background: dtColor, border: `2px solid ${tokens.bg.elevated}`, width: 10, height: 10, top: `${topPct}%` }}
            />
            {hovered && (
              <Box
                sx={{
                  position: "absolute",
                  right: -8,
                  top: `${topPct}%`,
                  transform: "translate(100%, -50%)",
                  pointerEvents: "none",
                  px: 0.5,
                  py: 0.15,
                  borderRadius: "3px",
                  bgcolor: `${tokens.bg.base}ee`,
                  border: `1px solid ${dtColor}44`,
                  display: "flex",
                  alignItems: "center",
                  whiteSpace: "nowrap",
                }}
              >
                <Typography sx={{ fontSize: "0.52rem", color: dtColor, fontWeight: 600, lineHeight: 1 }}>
                  {out.dataType}
                </Typography>
              </Box>
            )}
          </React.Fragment>
        );
      })}

      {/* Bottom of node */}
    </Box>
  );
}

const nodeTypes = { pipelineNode: PipelineNodeComponent };

// ── Convert pipeline nodes to React Flow format ───────────────────────────

function toRFNodes(pnodes: PipelineNode[], selectedId: string | null, descriptors: NodeDescriptor[], onDelete?: (nodeId: string) => void, activeNodeIds?: Set<string>, nodeResults?: Record<string, "completed" | "failed">): RFNode[] {
  // Build a lookup map once
  const descMap = new Map(descriptors.map(d => [d.kind, d]));

  return pnodes.map(n => {
    const desc = descMap.get(n.type);
    const connectors = nodeConnectors(desc, pipelineNodeOptions(n));
    const category: NodeCategory = desc?.category ?? "ANALYSIS";
    return {
      id: n.id,
      type: "pipelineNode",
      position: n.position,
      selected: n.id === selectedId,
      data: {
        label: n.label,
        description: n.description,
        category,
        // Preserve the descriptor kind (definition `type`) in node data so
        // getGraphJson can emit the real node type on save instead of the
        // category. Without this the canvas serialization corrupts node types.
        kind: n.type,
        nodeIcon: desc ? resolveNodeIcon(desc) : undefined,
        inputs: connectors.inputs,
        outputs: connectors.outputs,
        onDelete,
        isActive: activeNodeIds?.has(n.id) ?? false,
        lastResult: nodeResults?.[n.id],
        // Affinity is a top-level field on the definition node; surface it in the
        // React Flow node data so the renderer and getGraphJson can see it.
        affinity: n.affinity ?? DEFAULT_AFFINITY,
        ...pipelineNodeOptions(n),
      },
    };
  });
}

// Edge type styling for PASS / REJECT / ANY
const EDGE_TYPE_STYLE: Record<string, { stroke: string; strokeDasharray?: string; label: string; labelBg: string; labelColor: string }> = {
  PASS:   { stroke: tokens.accent.green,  label: "PASS",   labelBg: `${tokens.accent.green}22`,   labelColor: tokens.accent.green },
  REJECT: { stroke: tokens.accent.red,    strokeDasharray: "6 3", label: "REJECT", labelBg: `${tokens.accent.red}22`,     labelColor: tokens.accent.red },
  ANY:    { stroke: tokens.border.strong, label: "ANY",    labelBg: tokens.bg.overlay,             labelColor: tokens.text.tertiary },
};

function toRFEdges(edges: Pipeline["definition"]["edges"]): RFEdge[] {
  return edges.map(e => {
    const et = e.edgeType ?? "ANY";
    const style = EDGE_TYPE_STYLE[et] ?? EDGE_TYPE_STYLE.ANY;
    return {
      id: e.id,
      source: e.source,
      target: e.target,
      label: e.label ?? style.label,
      labelStyle: { fill: style.labelColor, fontWeight: 600, fontSize: 9 },
      labelBgStyle: { fill: style.labelBg, stroke: style.stroke, strokeDasharray: undefined },
      labelBgPadding: [4, 2] as [number, number],
      labelBgBorderRadius: 4,
      animated: e.animated ?? false,
      style: { stroke: style.stroke, strokeWidth: 1.5, strokeDasharray: style.strokeDasharray },
      data: { edgeType: et },
    };
  });
}

// ── Run History Panel ─────────────────────────────────────────────────────
function RunHistory({ runs, loading, onCancel, onSelect }: { runs: PipelineRunRecord[]; loading?: boolean; onCancel?: (runUuid: string) => void; onSelect?: (run: PipelineRunRecord) => void }) {
  const { t } = useTranslation();
  if (loading) {
    return (
      <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75, p: 1.5 }}>
        <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 0.5 }}>
          {t("pipeline.runHistory.title")}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {t("pipeline.runHistory.loading")}
        </Typography>
      </Box>
    );
  }
  if (runs.length === 0) {
    return (
      <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75, p: 1.5 }}>
        <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 0.5 }}>
          {t("pipeline.runHistory.title")}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {t("pipeline.runHistory.noRuns")}
        </Typography>
      </Box>
    );
  }
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75, p: 1.5 }}>
      <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 0.5 }}>
        {t("pipeline.runHistory.title")}
      </Typography>
      {runs.map(r => {
        const status = r.status.toLowerCase();
        const isSuccess = status === "success" || status === "completed";
        const isFailed = status === "failed" || status === "error";
        const isRunning = status === "running" || status === "active";
        const isCancelled = status === "cancelled" || status === "canceled";
        const dateStr = r.started ? new Date(r.started).toLocaleString() : "";
        return (
          <Paper
            key={r.uuid}
            elevation={0}
            data-testid={`pipeline-run-row-${r.uuid}`}
            onClick={onSelect ? () => onSelect(r) : undefined}
            sx={{
              bgcolor: tokens.bg.overlay,
              border: `1px solid ${tokens.border.subtle}`,
              borderRadius: tokens.radius.md,
              p: 1.25,
              cursor: onSelect ? "pointer" : "default",
              transition: "border-color 0.15s, background-color 0.15s",
              ...(onSelect ? { "&:hover": { borderColor: tokens.border.default, bgcolor: tokens.bg.surface } } : {}),
            }}
          >
            <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
              {isSuccess ? <CheckCircleOutline sx={{ fontSize: 14, color: tokens.accent.green }} /> :
                isFailed ? <ErrorOutline sx={{ fontSize: 14, color: tokens.accent.red }} /> :
                  isRunning ? <CircleOutlined sx={{ fontSize: 14, color: tokens.accent.amber }} /> :
                    isCancelled ? <Block sx={{ fontSize: 14, color: tokens.text.tertiary }} /> :
                      <CircleOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
              <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.75rem", color: isSuccess ? tokens.accent.green : isFailed ? tokens.accent.red : isRunning ? tokens.accent.amber : tokens.text.tertiary }}>
                {r.status}
              </Typography>
              {r.dryRun && (
                <Chip label="dry" size="small" sx={{ height: 14, fontSize: "0.55rem", bgcolor: `${tokens.accent.amber}22`, color: tokens.accent.amber }} />
              )}
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>
                {dateStr}
              </Typography>
              {isRunning && onCancel && (
                <Tooltip title={t("pipeline.runHistory.cancel") || "Cancel run"}>
                  <IconButton
                    data-testid={`pipeline-run-cancel-${r.uuid}`}
                    aria-label={t("pipeline.runHistory.cancel") || "Cancel run"}
                    size="small"
                    onClick={(e) => { e.stopPropagation(); onCancel(r.uuid); }}
                    sx={{ width: 20, height: 20, color: tokens.text.tertiary, "&:hover": { color: tokens.accent.red } }}
                  >
                    <StopCircleOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                </Tooltip>
              )}
            </Box>
            <Box sx={{ display: "flex", gap: 1 }}>
              <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.7rem" }}>
                {r.successCount} / {r.mediaCount} {t("pipeline.runHistory.assets")}
              </Typography>
              {r.failureCount > 0 && (
                <Typography variant="caption" sx={{ color: tokens.accent.red, fontSize: "0.7rem" }}>
                  {r.failureCount} {r.failureCount > 1 ? t("pipeline.runHistory.errors") : t("pipeline.runHistory.error")}
                </Typography>
              )}
            </Box>
            {r.errorMessage && (
              <Typography variant="caption" sx={{ color: tokens.accent.red, fontSize: "0.65rem", display: "block", mt: 0.25, fontFamily: "monospace" }}>
                {r.errorMessage}
              </Typography>
            )}
          </Paper>
        );
      })}
    </Box>
  );
}

// ── Run Detail Drawer ─────────────────────────────────────────────────────

/** Map a run-item state to an accent colour. Unknown states fall back to tertiary. */
function runItemStateColor(state: string): string {
  switch (state.toUpperCase()) {
    case "SUCCESS": return tokens.accent.green;
    case "FAILED": return tokens.accent.red;
    case "RUNNING": return tokens.accent.amber;
    case "PENDING": return tokens.accent.blue;
    case "SKIPPED": return tokens.text.tertiary;
    default: return tokens.text.tertiary;
  }
}

function RunDetailDrawer({ run, items, loading, onClose }: {
  run: PipelineRunRecord | null;
  items: PipelineRunItemRecord[];
  loading?: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const open = !!run;
  const accent = run ? runItemStateColor(run.status) : tokens.text.tertiary;
  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      PaperProps={{ sx: { width: 440, bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.default}`, backgroundImage: "none" } }}
      data-testid="pipeline-run-detail-drawer"
    >
      <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
        {/* Header */}
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, p: 2, borderBottom: `1px solid ${tokens.border.subtle}` }}>
          <Box sx={{ width: 4, height: 28, borderRadius: 2, bgcolor: accent }} />
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography variant="subtitle1" fontWeight={700} noWrap>
              {t("pipeline.runDetail.title")}
            </Typography>
            {run && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontFamily: "monospace" }} noWrap>
                {run.uuid}
              </Typography>
            )}
          </Box>
          {run && (
            <Chip
              label={run.status}
              size="small"
              sx={{ bgcolor: `${accent}22`, color: accent, border: `1px solid ${accent}44`, fontWeight: 700 }}
            />
          )}
          <IconButton size="small" onClick={onClose} aria-label="close" data-testid="pipeline-run-detail-close">
            <CloseOutlined sx={{ fontSize: 18 }} />
          </IconButton>
        </Box>

        {/* Body */}
        <Box sx={{ flex: 1, overflow: "auto", p: 2, display: "flex", flexDirection: "column", gap: 1 }}>
          <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem" }}>
            {t("pipeline.runDetail.items")}
          </Typography>
          {loading ? (
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }} data-testid="pipeline-run-items-loading">
              {t("pipeline.runDetail.loading")}
            </Typography>
          ) : items.length === 0 ? (
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }} data-testid="pipeline-run-items-empty">
              {t("pipeline.runDetail.noItems")}
            </Typography>
          ) : (
            items.map(item => {
              const c = runItemStateColor(item.state);
              return (
                <Paper
                  key={item.uuid}
                  elevation={0}
                  data-testid="pipeline-run-item"
                  data-state={item.state}
                  sx={{ bgcolor: tokens.bg.overlay, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, p: 1.25 }}
                >
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
                    <Chip
                      label={item.state}
                      size="small"
                      sx={{ height: 18, fontSize: "0.6rem", bgcolor: `${c}22`, color: c, border: `1px solid ${c}44`, fontWeight: 700 }}
                    />
                    <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>
                      #{item.itemSeq}
                    </Typography>
                  </Box>
                  <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.72rem", display: "block", wordBreak: "break-all" }}>
                    {item.mediaPath}
                  </Typography>
                  {item.errorMessage && (
                    <Typography
                      variant="caption"
                      data-testid="pipeline-run-item-error"
                      sx={{ color: tokens.accent.red, fontSize: "0.65rem", display: "block", mt: 0.5, fontFamily: "monospace", wordBreak: "break-all" }}
                    >
                      {item.errorMessage}
                    </Typography>
                  )}
                </Paper>
              );
            })
          )}
        </Box>
      </Box>
    </Drawer>
  );
}

// ── Node Detail Panel ─────────────────────────────────────────────────────
function NodeDetailPanel({ nodeId, pipeline }: { nodeId: string | null; pipeline: Pipeline | null }) {
  const { getDescriptor } = useNodeRegistry();
  if (!nodeId || !pipeline) return null;
  const node = pipeline.definition.nodes.find(n => n.id === nodeId);
  if (!node) return null;
  const desc = getDescriptor(node.type);
  const cfg = desc ? nodeVisualConfig(desc) : categoryConfig.ANALYSIS;

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
        {Object.entries(pipelineNodeOptions(node)).map(([k, v]) => (
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

// ── REST → domain mapping ─────────────────────────────────────────────────

/**
 * Map the flattened REST pipeline model onto the local domain type. Used both
 * for the initial listing and whenever a version restore hands us a fresh
 * server-rendered pipeline.
 */
function toPipeline(p: PipelineResponse): Pipeline {
  return {
    id: p.uuid,
    versionUuid: p.versionUuid,
    versionNumber: p.versionNumber,
    spaceId: "",
    name: p.name,
    description: p.description ?? "",
    enabled: p.enabled,
    priority: p.priority,
    dryRun: p.dryRun,
    definition: {
      nodes: ((p.definition as any)?.nodes ?? []) as Pipeline["definition"]["nodes"],
      edges: ((p.definition as any)?.edges ?? []) as Pipeline["definition"]["edges"],
    },
    runs: [],
    createdAt: p.status?.created ?? "",
    updatedAt: p.status?.edited ?? "",
  };
}

// ── Version badge + version history dropdown ──────────────────────────────

/**
 * Minimal `v<n>` badge overlaid on the top-left of the node editor. Clicking it
 * opens the version history, from which any previous version can be restored.
 */
function PipelineVersionBadge({
  pipeline, versions, loading, restoring, onOpen, onRestore, onCompare,
}: {
  pipeline: Pipeline;
  versions: PipelineResponse[];
  loading: boolean;
  restoring: number | null;
  onOpen: () => void;
  onRestore: (versionNumber: number) => void;
  onCompare: (versionNumber: number) => void;
}) {
  const { t } = useTranslation();
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);

  const handleClick = (e: React.MouseEvent<HTMLElement>) => {
    setAnchorEl(e.currentTarget);
    onOpen();
  };

  const current = pipeline.versionNumber;
  const open = Boolean(anchorEl);

  return (
    <>
      <Tooltip title={t("pipeline.version.badgeTooltip")}>
        <Chip
          data-testid="pipeline-version-badge"
          icon={<HistoryOutlined sx={{ fontSize: 12 }} />}
          label={current !== undefined ? `v${current}` : "—"}
          size="small"
          onClick={handleClick}
          sx={{
            height: 20,
            fontSize: "0.68rem",
            fontWeight: 700,
            fontFamily: "monospace",
            bgcolor: tokens.bg.overlay,
            border: `1px solid ${tokens.border.default}`,
            color: tokens.text.secondary,
            cursor: "pointer",
            backdropFilter: "blur(4px)",
            "& .MuiChip-icon": { color: tokens.text.tertiary, ml: 0.5 },
            "&:hover": { bgcolor: tokens.bg.hover, borderColor: tokens.primary.main, color: tokens.primary.light },
          }}
        />
      </Tooltip>

      <Popover
        open={open}
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
        transformOrigin={{ vertical: "top", horizontal: "left" }}
        PaperProps={{
          "data-testid": "pipeline-version-list",
          sx: {
            mt: 0.5,
            width: 300,
            maxHeight: 360,
            bgcolor: tokens.bg.panel,
            border: `1px solid ${tokens.border.default}`,
            borderRadius: tokens.radius.lg,
            overflow: "auto",
          },
        }}
      >
        <Box sx={{ px: 1.5, py: 1, borderBottom: `1px solid ${tokens.border.subtle}`, position: "sticky", top: 0, bgcolor: tokens.bg.panel, zIndex: 1 }}>
          <Typography variant="caption" fontWeight={700} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem" }}>
            {t("pipeline.version.title")}
          </Typography>
        </Box>

        {loading && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, p: 1.5 }}>
            <CircularProgress size={12} />
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
              {t("pipeline.version.loading")}
            </Typography>
          </Box>
        )}

        {!loading && versions.length === 0 && (
          <Typography data-testid="pipeline-version-empty" variant="caption" sx={{ display: "block", color: tokens.text.tertiary, fontSize: "0.7rem", p: 1.5 }}>
            {t("pipeline.version.empty")}
          </Typography>
        )}

        {!loading && versions.map(v => {
          const isCurrent = v.versionNumber === current;
          const isRestoring = restoring === v.versionNumber;
          const author = v.status?.creator?.name;
          const created = v.status?.created ? new Date(v.status.created).toLocaleString() : "";
          return (
            <Box
              key={v.versionUuid ?? v.versionNumber}
              data-testid={`pipeline-version-item-${v.versionNumber}`}
              sx={{
                px: 1.5, py: 1,
                borderBottom: `1px solid ${tokens.border.subtle}`,
                display: "flex", alignItems: "center", gap: 1,
                bgcolor: isCurrent ? tokens.primary.subtle : "transparent",
              }}
            >
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                  <Typography variant="caption" fontWeight={700} sx={{ fontFamily: "monospace", fontSize: "0.72rem", color: isCurrent ? tokens.primary.light : tokens.text.primary }}>
                    v{v.versionNumber}
                  </Typography>
                  {isCurrent && (
                    <Chip
                      label={t("pipeline.version.current")}
                      size="small"
                      sx={{ height: 14, fontSize: "0.55rem", bgcolor: `${tokens.primary.main}22`, color: tokens.primary.light }}
                    />
                  )}
                </Box>
                <Typography variant="caption" sx={{ display: "block", color: tokens.text.tertiary, fontSize: "0.65rem", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {[created, author].filter(Boolean).join(" · ")}
                </Typography>
              </Box>
              {!isCurrent && (
                <>
                  <Tooltip title={t("pipeline.version.compareTooltip", { version: v.versionNumber })}>
                    <span>
                      <IconButton
                        data-testid={`pipeline-version-compare-${v.versionNumber}`}
                        aria-label={t("pipeline.version.compareTooltip", { version: v.versionNumber })}
                        size="small"
                        onClick={() => { setAnchorEl(null); onCompare(v.versionNumber); }}
                        sx={{ color: tokens.text.tertiary, "&:hover": { color: tokens.primary.light } }}
                      >
                        <CompareArrowsOutlined sx={{ fontSize: 15 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                  <Tooltip title={t("pipeline.version.restoreTooltip", { version: v.versionNumber })}>
                    <span>
                      <IconButton
                        data-testid={`pipeline-version-restore-${v.versionNumber}`}
                        aria-label={t("pipeline.version.restoreTooltip", { version: v.versionNumber })}
                        size="small"
                        disabled={restoring !== null}
                        onClick={() => { setAnchorEl(null); onRestore(v.versionNumber); }}
                        sx={{ color: tokens.text.tertiary, "&:hover": { color: tokens.primary.light } }}
                      >
                        {isRestoring ? <CircularProgress size={13} /> : <RestoreOutlined sx={{ fontSize: 15 }} />}
                      </IconButton>
                    </span>
                  </Tooltip>
                </>
              )}
            </Box>
          );
        })}
      </Popover>
    </>
  );
}

// ── Pipeline Inspector (right stats panel) ────────────────────────────────
function PipelineInspector({ pipeline, runs, runsLoading, onCancelRun, onSelectRun }: { pipeline: Pipeline | null; runs: PipelineRunRecord[]; runsLoading: boolean; onCancelRun?: (runUuid: string) => void; onSelectRun?: (run: PipelineRunRecord) => void }) {
  const { t } = useTranslation();
  if (!pipeline) {
    return (
      <Box sx={{ p: 2, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: "100%", gap: 1 }}>
        <AccountTreeOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
        <Typography variant="body2" color="text.secondary">{t("pipeline.inspector.empty")}</Typography>
      </Box>
    );
  }

  const latestRun = runs[0];
  const runStatusColor: Record<string, string> = { success: tokens.accent.green, failed: tokens.accent.red, running: tokens.accent.amber, cancelled: tokens.text.tertiary, idle: tokens.text.tertiary, paused: tokens.text.tertiary };
  // Normalise like RunHistory already does, so the banner is robust to the server's
  // upper-case status vocabulary (RUNNING / CANCELLED / …) as well as mocked lower-case.
  const latestStatus = latestRun ? latestRun.status.toLowerCase() : "";
  const latestIsRunning = latestStatus === "running" || latestStatus === "active";

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", overflow: "hidden" }}>
      {/* Header */}
      <Box sx={{ px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <Typography variant="subtitle2" fontWeight={700} sx={{ fontSize: "0.875rem", mb: 0.25 }}>{pipeline.name}</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1.4, display: "block" }}>{pipeline.description}</Typography>
        <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", mt: 0.75 }}>
          {pipeline.versionNumber !== undefined && (
            <Chip data-testid="pipeline-inspector-version" label={`v${pipeline.versionNumber}`} size="small" sx={{ height: 16, fontSize: "0.62rem", fontFamily: "monospace", bgcolor: tokens.primary.subtle, color: tokens.primary.light }} />
          )}
          <Chip label={pipeline.enabled ? t("pipeline.inspector.enabled") : t("pipeline.inspector.disabled")} size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: pipeline.enabled ? `${tokens.accent.green}22` : tokens.bg.overlay, color: pipeline.enabled ? tokens.accent.green : tokens.text.tertiary }} />
          <Chip label={`${t("pipeline.inspector.priority")} ${pipeline.priority}`} size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: tokens.bg.overlay }} />
          {pipeline.dryRun && <Chip label={t("pipeline.inspector.dryRun")} size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: `${tokens.accent.amber}22`, color: tokens.accent.amber }} />}
        </Box>
      </Box>

      {/* Latest run status */}
      {latestRun && (
        <Box data-testid="pipeline-run-banner" data-status={latestRun.status} sx={{ px: 2, py: 1, bgcolor: `${runStatusColor[latestStatus] ?? runStatusColor.idle}0a`, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          {latestStatus === "success" || latestStatus === "completed" ? <CheckCircleOutline sx={{ fontSize: 14, color: tokens.accent.green }} /> :
            latestStatus === "failed" || latestStatus === "error" ? <ErrorOutline sx={{ fontSize: 14, color: tokens.accent.red }} /> :
              latestIsRunning ? <CircleOutlined sx={{ fontSize: 14, color: tokens.accent.amber, animation: "spin 1s linear infinite", "@keyframes spin": { from: { transform: "rotate(0deg)" }, to: { transform: "rotate(360deg)" } } }} /> :
                latestStatus === "cancelled" || latestStatus === "canceled" ? <Block sx={{ fontSize: 14, color: tokens.text.tertiary }} /> :
                  <CircleOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
          <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.75rem", color: runStatusColor[latestStatus] ?? tokens.text.tertiary }}>
            {latestIsRunning ? t("pipeline.inspector.runningNow") : `${t("pipeline.inspector.lastRun")} ${latestRun.status}`}
          </Typography>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>
            · {latestRun.successCount} / {latestRun.mediaCount} {t("pipeline.runHistory.assets")}
          </Typography>
          {latestIsRunning && onCancelRun && (
            <Tooltip title={t("pipeline.runHistory.cancel") || "Cancel run"}>
              <IconButton
                data-testid="pipeline-run-banner-cancel"
                aria-label={t("pipeline.runHistory.cancel") || "Cancel run"}
                size="small"
                onClick={() => onCancelRun(latestRun.uuid)}
                sx={{ width: 20, height: 20, color: tokens.text.tertiary, "&:hover": { color: tokens.accent.red } }}
              >
                <StopCircleOutlined sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
          )}
        </Box>
      )}

      {/* Scrollable run history */}
      <Box sx={{ flex: 1, overflow: "auto" }}>
        <RunHistory runs={runs} loading={runsLoading} onCancel={onCancelRun} onSelect={onSelectRun} />
      </Box>
    </Box>
  );
}

// ── Node Detail Sidebar (second collapsible right panel) ──────────────────
function NodeDetailSidebar({
  nodeId, pipeline, open, onClose, onDisplayNameChange, onParameterChange, onAffinityChange,
}: {
  nodeId: string | null;
  pipeline: Pipeline | null;
  open: boolean;
  onClose: () => void;
  onDisplayNameChange?: (nodeId: string, name: string) => void;
  onParameterChange?: (nodeId: string, key: string, value: unknown) => void;
  onAffinityChange?: (nodeId: string, value: string) => void;
}) {
  const { getDescriptor } = useNodeRegistry();
  const node = (nodeId && pipeline) ? pipeline.definition.nodes.find(n => n.id === nodeId) ?? null : null;
  const desc = node ? getDescriptor(node.type) : undefined;
  const cfg = desc ? nodeVisualConfig(desc) : (node ? categoryConfig.ANALYSIS : null);
  const [displayName, setDisplayName] = useState("");
  const [affinity, setAffinity] = useState(DEFAULT_AFFINITY);
  const [detailTab, setDetailTab] = useState(0);
  // Per-parameter "the JSON you typed does not parse" flags, for JSON-typed parameters.
  const [jsonParamError, setJsonParamError] = useState<Record<string, boolean>>({});
  const { t } = useTranslation();

  // Distinct affinity groups already used in the graph, for the autocomplete.
  const affinityOptions = pipeline
    ? [...new Set(pipeline.definition.nodes.map(n => n.affinity || DEFAULT_AFFINITY))]
    : [DEFAULT_AFFINITY];

  // Sync display name + affinity when node changes
  useEffect(() => {
    setDisplayName((node as any)?.displayName ?? "");
    setAffinity(node?.affinity || DEFAULT_AFFINITY);
    setDetailTab(0);
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
        <Typography variant="caption" fontWeight={700} sx={{ fontSize: "0.78rem", flex: 1, whiteSpace: "nowrap" }}>{t("pipeline.nodeDetail.title")}</Typography>
        <Tooltip title={t("pipeline.nodeDetail.collapse")}>
          <IconButton size="small" onClick={onClose} sx={{ width: 20, height: 20 }}>
            <ChevronLeftOutlined sx={{ fontSize: 14 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto" }}>
        {node && cfg ? (
          <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
            {/* Tabs */}
            <Tabs value={detailTab} onChange={(_, v) => setDetailTab(v)} sx={{ minHeight: 32, borderBottom: `1px solid ${tokens.border.subtle}`, px: 1 }}>
              <Tab label={t("pipeline.nodeDetail.tab.config")} sx={{ fontSize: "0.7rem", minHeight: 32, py: 0.5, minWidth: 60 }} />
              <Tab label={t("pipeline.nodeDetail.tab.log")} sx={{ fontSize: "0.7rem", minHeight: 32, py: 0.5, minWidth: 60 }} />
              <Tab label={t("pipeline.nodeDetail.tab.json")} sx={{ fontSize: "0.7rem", minHeight: 32, py: 0.5, minWidth: 60 }} />
            </Tabs>

            {detailTab === 0 && (
              <Box sx={{ p: 1.5, display: "flex", flexDirection: "column", gap: 2, overflow: "auto" }}>
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
                  label={t("pipeline.nodeDetail.displayName")}
                  value={displayName}
                  onChange={e => {
                    const v = e.target.value.slice(0, 15);
                    setDisplayName(v);
                    if (onDisplayNameChange && nodeId) onDisplayNameChange(nodeId, v);
                  }}
                  size="small"
                  fullWidth
                  placeholder={t("pipeline.nodeDetail.maxChars")}
                  inputProps={{ maxLength: 15 }}
                  helperText={`${displayName.length}/15`}
                  sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem" }, "& .MuiFormHelperText-root": { fontSize: "0.62rem", textAlign: "right" } }}
                />
                <TextField
                  label={t("pipeline.nodeDetail.description")}
                  value={node.description}
                  multiline
                  minRows={2}
                  size="small"
                  fullWidth
                  InputProps={{ readOnly: true }}
                  sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem" } }}
                />

                {/* Affinity group — nodes sharing a group are dispatched together as
                    one segment. Free text with an autocomplete of groups already used. */}
                <Autocomplete
                  freeSolo
                  size="small"
                  options={affinityOptions}
                  value={affinity}
                  onChange={(_, val) => {
                    const v = (typeof val === "string" ? val : "").trim() || DEFAULT_AFFINITY;
                    setAffinity(v);
                    if (onAffinityChange && nodeId) onAffinityChange(nodeId, v);
                  }}
                  onInputChange={(_, val) => {
                    const v = val.trim() || DEFAULT_AFFINITY;
                    setAffinity(v);
                    if (onAffinityChange && nodeId) onAffinityChange(nodeId, v);
                  }}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label={t("pipeline.nodeDetail.affinity")}
                      placeholder={DEFAULT_AFFINITY}
                      size="small"
                      fullWidth
                      helperText={t("pipeline.nodeDetail.affinityHelp")}
                      inputProps={{ ...params.inputProps, "data-testid": "pipeline-node-affinity-input" }}
                      sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem" }, "& .MuiFormHelperText-root": { fontSize: "0.62rem" } }}
                    />
                  )}
                />

                {/* Dynamic parameter editor (from NodeDescriptor.parameters) */}
                <Box>
                  <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.66rem", mb: 1, display: "block" }}>
                    {desc && desc.parameters.length > 0 ? t("pipeline.nodeDetail.parameters") : t("pipeline.nodeDetail.configuration")}
                  </Typography>
                  {desc && desc.parameters.length > 0 ? (
                    <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
                      {desc.parameters.map(param => {
                        const currentValue = pipelineNodeOptions(node)[param.key] ?? param.defaultValue ?? "";
                        const label = param.label || param.key;
                        const descText = param.description || "";
                        // `values` is what the backend emits; `allowedValues` is the legacy alias.
                        const enumValues = param.values ?? param.allowedValues;
                        const isEnum = param.type === "ENUM" && enumValues;
                        const isBool = param.type === "BOOLEAN";
                        const isInt = param.type === "INTEGER";
                        const isFloat = param.type === "NUMBER" || param.type === "FLOAT";
                        const isStringList = param.type === "ENUM_SET" || param.type === "STRING_LIST";
                        const isCode = param.type === "CODE";
                        const isJson = param.type === "JSON";
                        const fieldValue = isStringList
                          ? Array.isArray(currentValue) ? (currentValue as unknown[]).join(", ") : String(currentValue)
                          : isJson
                            ? typeof currentValue === "string" ? currentValue : JSON.stringify(currentValue ?? null, null, 2)
                            : currentValue;

                        return (
                          <Box key={param.key}>
                            <Tooltip title={descText || t("pipeline.nodeDetail.parameterDescription")} placement="left" arrow>
                              <Typography variant="caption" sx={{ fontSize: "0.66rem", color: tokens.text.tertiary, display: "block", mb: 0.25, cursor: "help" }}>
                                {label}
                              </Typography>
                            </Tooltip>
                            {isEnum ? (
                              <TextField
                                select
                                size="small"
                                fullWidth
                                value={String(currentValue ?? "")}
                                onChange={e => onParameterChange?.(nodeId!, param.key, e.target.value)}
                                sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem" } }}
                              >
                                {(enumValues ?? []).map(opt => (
                                  <MenuItem key={opt} value={opt} sx={{ fontSize: "0.78rem" }}>{opt}</MenuItem>
                                ))}
                              </TextField>
                            ) : isBool ? (
                              <Switch
                                size="small"
                                checked={!!currentValue}
                                onChange={e => onParameterChange?.(nodeId!, param.key, e.target.checked)}
                                sx={{ "& .MuiSwitch-switchBase": { color: tokens.primary.main } }}
                              />
                            ) : isStringList ? (
                              <TextField
                                size="small"
                                fullWidth
                                value={fieldValue as string}
                                placeholder="comma, separated, values"
                                onChange={e => {
                                  const parts = e.target.value.split(",").map(s => s.trim()).filter(s => s.length > 0);
                                  onParameterChange?.(nodeId!, param.key, parts);
                                }}
                                sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem", fontFamily: "monospace" } }}
                              />
                            ) : isCode || isJson ? (
                              // Script bodies and structured bags need room and a monospace face.
                              // JSON is stored parsed, so it is only committed when it parses —
                              // otherwise every keystroke mid-edit would write a broken value.
                              <TextField
                                size="small"
                                fullWidth
                                multiline
                                minRows={param.rows ?? (isCode ? 12 : 4)}
                                maxRows={isCode ? 30 : 12}
                                spellCheck={false}
                                value={String(fieldValue ?? "")}
                                placeholder={isCode ? `// ${param.language ?? "javascript"}` : "{ }"}
                                onChange={e => {
                                  const raw = e.target.value;
                                  if (!isJson) {
                                    onParameterChange?.(nodeId!, param.key, raw);
                                    return;
                                  }
                                  try {
                                    onParameterChange?.(nodeId!, param.key, JSON.parse(raw));
                                    setJsonParamError(prev => ({ ...prev, [param.key]: false }));
                                  } catch {
                                    // Keep the text the user is typing, flag it, and let save-time
                                    // validation refuse rather than silently persisting a string.
                                    onParameterChange?.(nodeId!, param.key, raw);
                                    setJsonParamError(prev => ({ ...prev, [param.key]: true }));
                                  }
                                }}
                                error={isJson && !!jsonParamError[param.key]}
                                helperText={isJson && jsonParamError[param.key] ? t("pipeline.nodeDetail.invalidJson") : undefined}
                                sx={{ "& .MuiInputBase-root": { fontSize: "0.72rem", fontFamily: "monospace", lineHeight: 1.45 } }}
                              />
                            ) : (
                              <TextField
                                size="small"
                                fullWidth
                                type={isInt || isFloat ? "number" : "text"}
                                value={String(fieldValue ?? "")}
                                onChange={e => {
                                  let val: unknown = e.target.value;
                                  if (isInt) val = e.target.value === "" ? "" : parseInt(e.target.value, 10);
                                  else if (isFloat) val = e.target.value === "" ? "" : parseFloat(e.target.value);
                                  onParameterChange?.(nodeId!, param.key, val);
                                }}
                                sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem", fontFamily: isInt || isFloat ? "monospace" : "inherit" } }}
                              />
                            )}
                          </Box>
                        );
                      })}
                    </Box>
                  ) : (
                    <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
                      {Object.entries(pipelineNodeOptions(node)).map(([k, v]) => (
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
                      {Object.keys(pipelineNodeOptions(node)).length === 0 && (
                        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem", fontStyle: "italic" }}>
                          {t("pipeline.nodeDetail.noParameters")}
                        </Typography>
                      )}
                    </Box>
                  )}
                </Box>

                {/* Node ID */}
                <Box sx={{ p: 1, bgcolor: tokens.bg.overlay, borderRadius: tokens.radius.sm }}>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.66rem", display: "block" }}>{t("pipeline.nodeDetail.nodeId")}</Typography>
                  <Typography variant="caption" sx={{ fontFamily: "monospace", fontSize: "0.72rem", color: tokens.text.secondary }}>{node.id}</Typography>
                </Box>
              </Box>
            )}

            {detailTab === 1 && (
              <Box sx={{ p: 1.5, flex: 1, overflow: "auto" }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mb: 1 }}>
                  <BugReportOutlined sx={{ fontSize: 14, color: tokens.primary.main }} />
                  <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.72rem" }}>{t("pipeline.nodeDetail.processingLog")}</Typography>
                </Box>
                <Box sx={{ bgcolor: tokens.bg.base, borderRadius: tokens.radius.sm, p: 1, border: `1px solid ${tokens.border.subtle}` }}>
                  {[
                    { ts: "14:32:01.243", level: "info", msg: `[${node.label}] Node initialized` },
                    { ts: "14:32:01.501", level: "info", msg: `[${node.label}] Processing asset batch (3 items)` },
                    { ts: "14:32:02.118", level: "info", msg: `[${node.label}] Asset a1 — completed in 617ms` },
                    { ts: "14:32:02.834", level: "info", msg: `[${node.label}] Asset a3 — completed in 716ms` },
                    { ts: "14:32:03.290", level: "warn", msg: `[${node.label}] Asset a4 — slow processing (>1s)` },
                    { ts: "14:32:04.501", level: "info", msg: `[${node.label}] Asset a4 — completed in 1667ms` },
                    { ts: "14:32:04.502", level: "info", msg: `[${node.label}] Batch complete. 3/3 succeeded.` },
                  ].map((entry, i) => (
                    <Typography key={i} sx={{
                      fontFamily: "'JetBrains Mono', 'Fira Code', monospace", fontSize: "0.68rem", lineHeight: 1.7, display: "block",
                      color: entry.level === "warn" ? tokens.accent.amber : entry.level === "error" ? tokens.accent.red : tokens.text.secondary,
                    }}>
                      <Box component="span" sx={{ color: tokens.text.tertiary, mr: 1 }}>{entry.ts}</Box>
                      <Box component="span" sx={{ color: entry.level === "warn" ? tokens.accent.amber : entry.level === "error" ? tokens.accent.red : tokens.primary.main, mr: 1, fontWeight: 600 }}>
                        {entry.level.toUpperCase()}
                      </Box>
                      {entry.msg}
                    </Typography>
                  ))}
                </Box>
              </Box>
            )}

            {detailTab === 2 && (
              <Box sx={{ p: 1.5, flex: 1, overflow: "auto" }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mb: 1 }}>
                  <DataObjectOutlined sx={{ fontSize: 14, color: tokens.primary.main }} />
                  <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.72rem" }}>{t("pipeline.nodeDetail.nodeState")}</Typography>
                </Box>
                <Box sx={{ bgcolor: tokens.bg.base, borderRadius: tokens.radius.sm, p: 1.5, border: `1px solid ${tokens.border.subtle}` }}>
                  <Typography component="pre" sx={{
                    fontFamily: "'JetBrains Mono', 'Fira Code', monospace", fontSize: "0.68rem", lineHeight: 1.6,
                    color: tokens.text.secondary, whiteSpace: "pre-wrap", wordBreak: "break-word", m: 0,
                  }}>
                    {JSON.stringify({ id: node.id, type: node.type, label: node.label, description: node.description, config: pipelineNodeOptions(node), status: "idle", lastRun: null, metrics: { processedCount: 3, avgLatencyMs: 1000, errorRate: 0 } }, null, 2)}
                  </Typography>
                </Box>
              </Box>
            )}
          </Box>
        ) : (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: "100%", gap: 1, pt: 4 }}>
            <SettingsOutlined sx={{ fontSize: 28, color: tokens.text.tertiary }} />
            <Typography variant="caption" color="text.secondary" sx={{ textAlign: "center" }}>{t("pipeline.nodeDetail.emptyHint")}</Typography>
          </Box>
        )}
      </Box>
    </Box>
  );
}

// ── Canvas ────────────────────────────────────────────────────────────────
function PipelineCanvas({
  pipeline, onNodeSelect, externalNodes, nodeDisplayNames, nodeAffinities, nodeParameters, descriptors,
  onDeleteNode, activeNodeIds, nodeResults, onGraphChange, removalTrigger, autoArrangeTrigger,
  onEdgeTypeChange, reloadKey,
}: {
  pipeline: Pipeline | null;
  onNodeSelect: (id: string | null) => void;
  externalNodes?: RFNode[];
  nodeDisplayNames?: Record<string, string>;
  nodeAffinities?: Record<string, string>;
  nodeParameters?: Record<string, Record<string, unknown>>;
  descriptors: NodeDescriptor[];
  onDeleteNode?: (nodeId: string, label: string) => void;
  activeNodeIds?: Set<string>;
  nodeResults?: Record<string, "completed" | "failed">;
  onGraphChange?: (json: any) => void;
  removalTrigger?: { nodeId: string; key: number } | null;
  autoArrangeTrigger?: number;
  onEdgeTypeChange?: (edgeId: string, edgeType: EdgeKind) => void;
  /** Bumped by the parent to force a full graph reload of the same pipeline (e.g. after a version restore). */
  reloadKey?: number;
}) {
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const reconnectingEdgeRef = useRef<RFEdge | null>(null);
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const connectionRejectedRef = useRef(false);
  const connectingRef = useRef(false);
  const [edgeMenu, setEdgeMenu] = useState<{ edgeId: string; x: number; y: number } | null>(null);
  const { t } = useTranslation();
  const { fitView } = useReactFlow();

  // Callback for node X-button delete
  const handleNodeDelete = useCallback((nodeId: string) => {
    const node = nodes.find(n => n.id === nodeId);
    if (onDeleteNode) onDeleteNode(nodeId, (node?.data?.label as string) ?? nodeId);
  }, [nodes, onDeleteNode]);

  // Only reset the graph when the pipeline itself changes, or when the parent
  // explicitly asks for a reload (version restore).
  useEffect(() => {
    if (!pipeline) { setNodes([]); setEdges([]); return; }
    setNodes(toRFNodes(pipeline.definition.nodes, null, descriptors, handleNodeDelete, activeNodeIds, nodeResults));
    setEdges(toRFEdges(pipeline.definition.edges));
    setSelectedId(null);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pipeline?.id, reloadKey]);

  // Handle node removal from parent
  useEffect(() => {
    if (removalTrigger) {
      setNodes(nds => nds.filter(n => n.id !== removalTrigger.nodeId));
      setEdges(eds => eds.filter(e => e.source !== removalTrigger.nodeId && e.target !== removalTrigger.nodeId));
    }
  }, [removalTrigger, setNodes, setEdges]);

  // Auto-arrange nodes in a left-to-right DAG layout
  useEffect(() => {
    if (!autoArrangeTrigger || nodes.length === 0) return;
    const NODE_W = 200;
    const NODE_H = 80;
    const GAP_X = 80;
    const GAP_Y = 40;

    // Build adjacency: node → set of target node ids
    const adj = new Map<string, string[]>();
    const inDeg = new Map<string, number>();
    for (const n of nodes) { adj.set(n.id, []); inDeg.set(n.id, 0); }
    for (const e of edges) {
      adj.get(e.source)?.push(e.target);
      inDeg.set(e.target, (inDeg.get(e.target) ?? 0) + 1);
    }

    // Topological sort (Kahn) to assign columns
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
    // Assign unvisited nodes (disconnected) to column 0
    for (const n of nodes) { if (!col.has(n.id)) col.set(n.id, 0); }

    // Group by column
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

    setNodes(nds => nds.map(n => {
      const p = posMap.get(n.id);
      return p ? { ...n, position: p } : n;
    }));

    // Fit view after layout settles
    setTimeout(() => fitView({ padding: 0.3 }), 50);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoArrangeTrigger]);

  // Keep onDelete callback and live run state (isActive / lastResult) up to date
  // without resetting node positions.
  useEffect(() => {
    setNodes(nds => nds.map(n => ({
      ...n,
      data: { ...n.data, onDelete: handleNodeDelete, isActive: activeNodeIds?.has(n.id) ?? false, lastResult: nodeResults?.[n.id] },
    })));
  }, [handleNodeDelete, activeNodeIds, nodeResults, setNodes]);

  // Append externally-added nodes
  useEffect(() => {
    if (externalNodes && externalNodes.length > 0) {
      setNodes(prev => {
        const existingIds = new Set(prev.map(n => n.id));
        const newOnes = externalNodes.filter(n => !existingIds.has(n.id)).map(n => ({
          ...n,
          data: { ...n.data, onDelete: handleNodeDelete, isActive: activeNodeIds?.has(n.id) ?? false, lastResult: nodeResults?.[n.id] },
        }));
        return newOnes.length > 0 ? [...prev, ...newOnes] : prev;
      });
    }
  }, [externalNodes, setNodes, handleNodeDelete, activeNodeIds, nodeResults]);

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

  // Apply affinity-group changes made in the NodeDetailSidebar into the live
  // canvas node data, without resetting positions (same channel as display names).
  useEffect(() => {
    if (!nodeAffinities) return;
    setNodes(nds => nds.map(n => {
      const aff = nodeAffinities[n.id];
      if (aff !== undefined && n.data.affinity !== aff) {
        return { ...n, data: { ...n.data, affinity: aff } };
      }
      return n;
    }));
  }, [nodeAffinities, setNodes]);

  // Apply parameter edits made in the NodeDetailSidebar into the live canvas node data.
  // Without this channel the edits live only on `selected.definition`, while getGraphJson
  // serialises from the canvas — so saving would silently discard every parameter change.
  // Output connectors are recomputed here too, so a `script` node's handles follow its
  // declared outputs as they are edited.
  useEffect(() => {
    if (!nodeParameters) return;
    setNodes(nds => nds.map(n => {
      const params = nodeParameters[n.id];
      if (!params) return n;
      const changed = Object.entries(params).some(([k, v]) => n.data[k] !== v);
      if (!changed) return n;
      const data = { ...n.data, ...params };
      const desc = descriptors.find(d => d.kind === (n.data.kind as string));
      const connectors = nodeConnectors(desc, data as Record<string, unknown>);
      return { ...n, data: { ...data, inputs: connectors.inputs, outputs: connectors.outputs } };
    }));
  }, [nodeParameters, descriptors, setNodes]);

  const onNodeClick = useCallback((_: React.MouseEvent, node: RFNode) => {
    setSelectedId(node.id);
    onNodeSelect(node.id);
  }, [onNodeSelect]);

  const onPaneClick = useCallback(() => {
    setSelectedId(null);
    onNodeSelect(null);
  }, [onNodeSelect]);

  // DEL key handler
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Delete" && selectedId && onDeleteNode) {
        const node = nodes.find(n => n.id === selectedId);
        onDeleteNode(selectedId, (node?.data?.label as string) ?? selectedId);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [selectedId, nodes, onDeleteNode]);

  // Connection type validation: only allow same data-type connections
  const isValidConnection = useCallback((conn: Connection) => {
    if (!conn.source || !conn.target || !conn.sourceHandle || !conn.targetHandle) return true;
    const sourceNode = nodes.find(n => n.id === conn.source);
    const targetNode = nodes.find(n => n.id === conn.target);
    if (!sourceNode || !targetNode) return true;

    const sourceOutputs = sourceNode.data.outputs as ConnectorDef[] | undefined;
    const targetInputs = targetNode.data.inputs as ConnectorDef[] | undefined;
    if (!sourceOutputs || !targetInputs) return true;

    const outIdx = parseInt(conn.sourceHandle.replace("out_", ""), 10);
    const inIdx = parseInt(conn.targetHandle.replace("in_", ""), 10);
    const sourceType = sourceOutputs[outIdx]?.dataType;
    const targetType = targetInputs[inIdx]?.dataType;
    if (!sourceType || !targetType) return true;

    const valid = sourceType === targetType;
    if (!valid) connectionRejectedRef.current = true;
    return valid;
  }, [nodes]);

  // New connection: snap source→target (only if valid)
  const onConnect = useCallback((conn: Connection) => {
    if (!conn.source || !conn.target) return;
    if (!isValidConnection(conn)) return;
    connectionRejectedRef.current = false;
    const newEdge: RFEdge = {
      id: `e_${conn.source}_${conn.target}_${Date.now()}`,
      source: conn.source,
      target: conn.target,
      sourceHandle: conn.sourceHandle ?? undefined,
      targetHandle: conn.targetHandle ?? undefined,
      style: { stroke: tokens.border.strong, strokeWidth: 1.5 },
    };
    setEdges(eds => addEdge(newEdge, eds));
  }, [setEdges, isValidConnection]);

  // Connection start/end for error feedback
  const onConnectStartHandler = useCallback(() => {
    connectionRejectedRef.current = false;
    connectingRef.current = true;
  }, []);

  const onConnectEndHandler = useCallback((event: MouseEvent | TouchEvent) => {
    // Check if we were connecting and it was rejected
    if (connectingRef.current && connectionRejectedRef.current) {
      setConnectionError(t("pipeline.editor.connectionTypeError"));
      setTimeout(() => setConnectionError(null), 3000);
    }
    connectionRejectedRef.current = false;
    connectingRef.current = false;
  }, [t]);

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

  // Edge click: open context menu for edge type labeling (PASS / REJECT / ANY)
  const onEdgeClick = useCallback((event: React.MouseEvent, edge: RFEdge) => {
    event.stopPropagation();
    setEdgeMenu({ edgeId: edge.id, x: event.clientX, y: event.clientY });
  }, []);

  // Apply edge type change locally and notify parent
  const handleEdgeTypeChange = useCallback((edgeId: string, edgeType: EdgeKind) => {
    const style = EDGE_TYPE_STYLE[edgeType] ?? EDGE_TYPE_STYLE.ANY;
    setEdges(eds => eds.map(e => {
      if (e.id !== edgeId) return e;
      return {
        ...e,
        label: edgeType,
        labelStyle: { fill: style.labelColor, fontWeight: 600, fontSize: 9 },
        labelBgStyle: { fill: style.labelBg, stroke: style.stroke },
        style: { ...e.style, stroke: style.stroke, strokeDasharray: style.strokeDasharray },
        data: { ...e.data, edgeType },
      };
    }));
    onEdgeTypeChange?.(edgeId, edgeType);
    setEdgeMenu(null);
  }, [setEdges, onEdgeTypeChange]);

  // Expose nodes/edges for JSON view
  const getGraphJson = useCallback(() => {
    // Keys handled explicitly above / not part of the persisted config. `affinity`
    // is lifted to the node top level (the shape Loom's PipelineGraphParser reads),
    // so it must be excluded from the nested `config` bag too.
    const RESERVED = ["label", "description", "category", "kind", "nodeIcon", "inputs", "outputs", "onDelete", "isActive", "lastResult", "displayName", "affinity"];
    const nodeData = nodes.map(n => {
      const affinity = (n.data as any).affinity as string | undefined;
      const configEntries = Object.entries(n.data).filter(([k]) => !RESERVED.includes(k));
      return {
        id: n.id,
        // Prefer the preserved descriptor kind; fall back to category only for
        // legacy nodes that predate kind preservation.
        type: (n.data as any).kind ?? (n.data as any).category ?? "unknown",
        label: n.data.label,
        description: n.data.description,
        position: n.position,
        // Only emit non-default affinity — keeps the JSON clean and backward compatible.
        ...(affinity && affinity !== DEFAULT_AFFINITY ? { affinity } : {}),
        // `options` is the key Loom's PipelineGraphParser reads. This used to be emitted as
        // `config`, which no parser ever read — so node parameters edited here were silently
        // dropped at the Loom boundary and never reached a worker. The parser still accepts
        // `config` as a legacy alias so definitions saved before this fix keep loading.
        ...(configEntries.length > 0 ? { options: Object.fromEntries(configEntries) } : {}),
      };
    });
    const edgeData = edges.map(e => ({
      id: e.id,
      source: e.source,
      target: e.target,
      ...(e.sourceHandle ? { sourceHandle: e.sourceHandle } : {}),
      ...(e.targetHandle ? { targetHandle: e.targetHandle } : {}),
      ...(e.data?.edgeType && e.data.edgeType !== "ANY" ? { edgeType: e.data.edgeType } : {}),
    }));
    return { nodes: nodeData, edges: edgeData };
  }, [nodes, edges]);

  // Notify parent of graph changes for JSON tab
  useEffect(() => {
    if (onGraphChange) onGraphChange(getGraphJson());
  }, [nodes, edges, getGraphJson, onGraphChange]);

  if (!pipeline) {
    return (
      <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", bgcolor: tokens.bg.base }}>
        <Typography variant="body2" color="text.secondary">Select a pipeline to view its graph</Typography>
      </Box>
    );
  }

  return (
    <Box sx={{
      flex: 1, height: "100%", position: "relative",
      "& .react-flow__connection-path": {
        stroke: tokens.border.strong,
        strokeWidth: 1.5,
      },
      "& .react-flow__connection.valid .react-flow__connection-path": {
        stroke: tokens.accent.green,
      },
      "& .react-flow__connection:not(.valid) .react-flow__connection-path": {
        stroke: tokens.accent.red,
        strokeDasharray: "5 3",
      },
      "& .react-flow__handle.connectingto": {
        boxShadow: `0 0 0 3px ${tokens.accent.red}66`,
      },
      "& .react-flow__handle.valid": {
        boxShadow: `0 0 0 3px ${tokens.accent.green}66`,
      },
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
        onConnectStart={onConnectStartHandler}
        onConnectEnd={onConnectEndHandler}
        onReconnectStart={onReconnectStart}
        onReconnect={onReconnect}
        onReconnectEnd={onReconnectEnd}
        onEdgeClick={onEdgeClick}
        isValidConnection={isValidConnection}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.3 }}
        snapToGrid
        snapGrid={[15, 15]}
        style={{ background: tokens.bg.base }}
      >
        <Background variant={BackgroundVariant.Dots} color={tokens.border.subtle} gap={20} size={1} />
        <Controls />
        <MiniMap
          style={{ background: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` }}
          nodeColor={() => tokens.border.strong}
        />
      </ReactFlow>
      {/* Edge context menu for PASS / REJECT / ANY labeling */}
      {edgeMenu && (
        <ClickAwayListener onClickAway={() => setEdgeMenu(null)}>
          <Paper
            style={{ position: "fixed", top: edgeMenu.y, left: edgeMenu.x, zIndex: 1300 }}
            sx={{
              bgcolor: tokens.bg.panel,
              border: `1px solid ${tokens.border.default}`,
              borderRadius: tokens.radius.md,
              minWidth: 140,
              boxShadow: "0 4px 16px rgba(0,0,0,0.5)",
              py: 0.5,
            }}
          >
            <Typography variant="caption" sx={{ px: 1.5, py: 0.5, fontSize: "0.62rem", fontWeight: 700, color: tokens.text.tertiary, textTransform: "uppercase", letterSpacing: "0.06em" }}>
              {t("pipeline.editor.edgeLabel")}
            </Typography>
            {(["PASS", "REJECT", "ANY"] as EdgeKind[]).map(kind => {
              const s = EDGE_TYPE_STYLE[kind];
              return (
                <ListItemButton key={kind} onClick={() => handleEdgeTypeChange(edgeMenu.edgeId, kind)} sx={{ py: 0.5, px: 1.5, gap: 1 }}>
                  <Box sx={{ width: 16, height: 3, borderRadius: 1, bgcolor: s.stroke, borderStyle: kind === "REJECT" ? "none" : "solid", backgroundImage: kind === "REJECT" ? `repeating-linear-gradient(90deg, ${s.stroke} 0 4px, transparent 4px 7px)` : "none" }} />
                  <Typography variant="caption" sx={{ fontSize: "0.72rem", fontWeight: 600, color: s.labelColor }}>{kind}</Typography>
                </ListItemButton>
              );
            })}
          </Paper>
        </ClickAwayListener>
      )}
      {/* Connection error toast */}
      {connectionError && (
        <Box
          sx={{
            position: "absolute",
            top: 12,
            left: "50%",
            transform: "translateX(-50%)",
            zIndex: 100,
            animation: "fadeIn 150ms ease",
            "@keyframes fadeIn": { from: { opacity: 0, transform: "translateX(-50%) translateY(-6px)" }, to: { opacity: 1, transform: "translateX(-50%) translateY(0)" } },
          }}
        >
          <Chip
            icon={<ErrorOutline sx={{ fontSize: 14 }} />}
            label={connectionError}
            size="small"
            sx={{
              bgcolor: `${tokens.accent.red}22`,
              border: `1px solid ${tokens.accent.red}`,
              color: tokens.accent.red,
              fontWeight: 600,
              fontSize: "0.72rem",
            }}
          />
        </Box>
      )}
    </Box>
  );
}

// ── JSON syntax highlighting ──────────────────────────────────────────────
function syntaxHighlightJson(json: string): string {
  return json.replace(
    /("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false)\b|-?\d+(?:\.\d*)?(?:[eE][+-]?\d+)?|\bnull\b)/g,
    (match) => {
      let cls = "json-number";
      if (match.startsWith('"')) {
        cls = match.endsWith(":") ? "json-key" : "json-string";
      } else if (/true|false/.test(match)) {
        cls = "json-boolean";
      } else if (match === "null") {
        cls = "json-null";
      }
      // Escape HTML entities to prevent injection
      const escaped = match.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
      return `<span class="${cls}">${escaped}</span>`;
    },
  );
}

// ── Node Command Palette ──────────────────────────────────────────────────
function CommandPaletteContent({
  descriptors, onAdd, onClose,
}: {
  descriptors: NodeDescriptor[];
  onAdd: (desc: NodeDescriptor) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [filter, setFilter] = useState("");
  const [selectedIdx, setSelectedIdx] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  const filtered = descriptors.filter(d =>
    !filter ||
    d.name.toLowerCase().includes(filter.toLowerCase()) ||
    d.kind.toLowerCase().includes(filter.toLowerCase()) ||
    d.category.toLowerCase().includes(filter.toLowerCase())
  );

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    setSelectedIdx(0);
  }, [filter]);

  // Scroll selected item into view
  useEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const item = list.children[selectedIdx] as HTMLElement | undefined;
    item?.scrollIntoView({ block: "nearest" });
  }, [selectedIdx]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setSelectedIdx(i => Math.min(i + 1, filtered.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setSelectedIdx(i => Math.max(i - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (filtered[selectedIdx]) onAdd(filtered[selectedIdx]);
    } else if (e.key === "Escape") {
      onClose();
    }
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", maxHeight: 480 }}>
      <Box sx={{ p: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <TextField
          inputRef={inputRef}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={t("pipeline.commandPalette.placeholder")}
          size="small"
          fullWidth
          autoFocus
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
          sx={{
            "& .MuiInputBase-root": { fontSize: "0.82rem" },
            "& .MuiOutlinedInput-notchedOutline": { borderColor: tokens.border.default },
          }}
        />
      </Box>
      <List ref={listRef} dense sx={{ overflow: "auto", flex: 1, py: 0.5 }}>
        {filtered.length === 0 && (
          <Box sx={{ p: 2, textAlign: "center" }}>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>{t("pipeline.commandPalette.empty")}</Typography>
          </Box>
        )}
        {filtered.map((d, idx) => {
          const cfg = nodeVisualConfig(d);
          return (
            <ListItemButton
              key={d.kind}
              selected={idx === selectedIdx}
              onClick={() => onAdd(d)}
              sx={{
                py: 0.75,
                px: 1.5,
                gap: 1.25,
                "&.Mui-selected": { bgcolor: `${tokens.primary.main}18` },
              }}
            >
              <Box sx={{ width: 24, height: 24, borderRadius: tokens.radius.sm, bgcolor: cfg.bg, display: "flex", alignItems: "center", justifyContent: "center", color: cfg.color, flexShrink: 0 }}>
                {cfg.icon}
              </Box>
              <Box sx={{ minWidth: 0, flex: 1 }}>
                <Typography variant="body2" sx={{ fontSize: "0.8rem", fontWeight: 600, lineHeight: 1.2 }}>{d.name}</Typography>
                <Typography variant="caption" sx={{ fontSize: "0.65rem", color: tokens.text.tertiary, display: "block" }}>{d.category} · {d.description.slice(0, 50)}</Typography>
              </Box>
            </ListItemButton>
          );
        })}
      </List>
    </Box>
  );
}

// ── Pipeline validation (cycle detection, duplicate IDs, etc.) ──────────
const NODE_ID_REGEX = /^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$/;

interface ValidationError {
  type: "duplicateId" | "cycle" | "invalidId" | "noSource" | "multipleSource" | "unknownType";
  message: string;
}

function validatePipeline(nodes: { id: string; type: string; label: string }[], edges: { source: string; target: string }[], descriptors: NodeDescriptor[]): ValidationError[] {
  const errors: ValidationError[] = [];
  const descKinds = new Set(descriptors.map(d => d.kind));

  // Duplicate ID check
  const idSet = new Set<string>();
  for (const n of nodes) {
    if (idSet.has(n.id)) {
      errors.push({ type: "duplicateId", message: `Duplicate node ID: "${n.id}" — node IDs must be unique` });
    } else {
      idSet.add(n.id);
    }
  }

  // Invalid ID format check
  for (const n of nodes) {
    if (!NODE_ID_REGEX.test(n.id)) {
      errors.push({ type: "invalidId", message: `Invalid node ID: "${n.id}" — IDs must match ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$` });
    }
  }

  // Unknown node type check
  for (const n of nodes) {
    if (!descKinds.has(n.type)) {
      errors.push({ type: "unknownType", message: `Unknown node type: "${n.type}" — not found in descriptor registry` });
    }
  }

  // Cycle detection (Kahn's algorithm)
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
    errors.push({ type: "cycle", message: "Cycle detected in pipeline graph — nodes form a circular dependency" });
  }

  return errors;
}

// ── Main Pipeline Editor ──────────────────────────────────────────────────
export default function PipelineEditor() {
  const { activeSpace } = useSpace();
  const { t } = useTranslation();
  const { token } = useAuth();
  const { descriptors, loading: registryLoading, error: registryError } = useNodeRegistry();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [selected, setSelected] = useState<Pipeline | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [logOpen, setLogOpen] = useState(true);
  const [logHeight, setLogHeight] = useState(160);
  const [nodeDetailOpen, setNodeDetailOpen] = useState(false);
  const [addedNodes, setAddedNodes] = useState<RFNode[]>([]);
  const [nodeDisplayNames, setNodeDisplayNames] = useState<Record<string, string>>({});
  const [nodeAffinities, setNodeAffinities] = useState<Record<string, string>>({});
  const [nodeParameters, setNodeParameters] = useState<Record<string, Record<string, unknown>>>({});
  const [addNodeOpen, setAddNodeOpen] = useState(false);
  const [addNodeIdx, setAddNodeIdx] = useState(0);
  const addNodeBarRef = useRef<HTMLDivElement>(null);
  const addNodeInputRef = useRef<HTMLInputElement>(null);
  const [canvasTab, setCanvasTab] = useState<0 | 1>(0); // 0 = Visual, 1 = JSON
  const [graphJson, setGraphJson] = useState<any>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ nodeId: string; label: string } | null>(null);
  // Pipeline create / clone / delete + unsaved-switch guard state.
  const [createDialog, setCreateDialog] = useState<{ mode: "new" | "clone"; name: string; description: string } | null>(null);
  const [creating, setCreating] = useState(false);
  const [deletePipelineConfirm, setDeletePipelineConfirm] = useState(false);
  const [deletingPipeline, setDeletingPipeline] = useState(false);
  /** Pipeline the user tried to switch to while unsaved; drives the discard-confirm dialog. */
  const [pendingSwitch, setPendingSwitch] = useState<Pipeline | null>(null);
  // Live run state, driven by the pipeline-events WebSocket (see the subscription
  // effect below). `activeNodeIds` pulses nodes currently processing; `nodeResults`
  // tints a node green/red once it completes/fails.
  const [activeNodeIds, setActiveNodeIds] = useState<Set<string>>(() => new Set());
  const [nodeResults, setNodeResults] = useState<Record<string, "completed" | "failed">>({});
  const [removalTrigger, setRemovalTrigger] = useState<{ nodeId: string; key: number } | null>(null);
  const removalKeyRef = useRef(0);
  const [showHelp, setShowHelp] = useState(false);
  const [showCommandPalette, setShowCommandPalette] = useState(false);
  const [nodeFilter, setNodeFilter] = useState("");
  const [autoArrangeTrigger, setAutoArrangeTrigger] = useState(0);
  const isDraggingLog = useRef(false);
  const logRef = useRef<HTMLDivElement>(null);

  // Validation errors (cycle / duplicate-id / unknown type)
  const [validationErrors, setValidationErrors] = useState<ValidationError[]>([]);
  // Live run history from the server
  const [pipelineRuns, setPipelineRuns] = useState<PipelineRunRecord[]>([]);
  const [runsLoading, setRunsLoading] = useState(false);
  // Run detail drill-down (drawer): the selected run and its items.
  const [selectedRun, setSelectedRun] = useState<PipelineRunRecord | null>(null);
  const [runItems, setRunItems] = useState<PipelineRunItemRecord[]>([]);
  const [runItemsLoading, setRunItemsLoading] = useState(false);

  // Version history
  const [versions, setVersions] = useState<PipelineResponse[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [restoringVersion, setRestoringVersion] = useState<number | null>(null);
  const [restoreConfirm, setRestoreConfirm] = useState<number | null>(null);
  /** Version number being compared against current, or null when the diff is closed. */
  const [diffTarget, setDiffTarget] = useState<number | null>(null);
  /** Bumped to force PipelineCanvas to rebuild the graph for the same pipeline id. */
  const [canvasReloadKey, setCanvasReloadKey] = useState(0);

  // Save / Run state
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [running, setRunning] = useState(false);
  const [snack, setSnack] = useState<{ open: boolean; severity: "success" | "error" | "info"; message: string }>({
    open: false, severity: "info", message: "",
  });
  const notify = useCallback((severity: "success" | "error" | "info", message: string) => {
    setSnack({ open: true, severity, message });
  }, []);

  useEffect(() => {
    if (!token) return;
    listPipelines(token).then(resp => {
      const ps: Pipeline[] = (resp.data ?? []).map(toPipeline);
      setPipelines(ps);
      setSelected(ps[0] ?? null);
      setLoading(false);
    }).catch(() => setLoading(false));
  }, [token]);

  // Load run history for the selected pipeline. Extracted into a callback so live
  // pipeline events (PIPELINE_STARTED / PIPELINE_COMPLETED) and a manual run
  // dispatch can refresh the list without a reselect.
  const loadRuns = useCallback(() => {
    if (!token || !selected) { setPipelineRuns([]); return; }
    setRunsLoading(true);
    listPipelineRuns(token, selected.id)
      .then(runs => setPipelineRuns(runs))
      .catch(() => setPipelineRuns([]))
      .finally(() => setRunsLoading(false));
  }, [token, selected?.id]);

  useEffect(() => { loadRuns(); }, [loadRuns]);

  // Open the run-detail drawer for a run and fetch its items.
  const openRunDetail = useCallback((run: PipelineRunRecord) => {
    setSelectedRun(run);
    if (!token || !selected) { setRunItems([]); return; }
    setRunItemsLoading(true);
    listPipelineRunItems(token, selected.id, run.uuid)
      .then(items => setRunItems(items))
      .catch(() => setRunItems([]))
      .finally(() => setRunItemsLoading(false));
  }, [token, selected?.id]);

  const closeRunDetail = useCallback(() => {
    setSelectedRun(null);
    setRunItems([]);
  }, []);

  // Reset live run state and subscribe to the pipeline-events WebSocket while a
  // pipeline is selected. The socket is shared (see api/pipelineEvents.ts) and
  // multiplexed, so we filter incoming frames by pipeline name here. Returns the
  // unsubscribe fn, which tears the subscription down on unmount / selection switch.
  useEffect(() => {
    setActiveNodeIds(new Set());
    setNodeResults({});
    if (!selected) return;
    const selectedName = selected.name;
    const handleEvent = (event: PipelineEventMessage) => {
      if (event.pipelineName !== selectedName) return;
      switch (event.type) {
        case "NODE_STARTED":
          if (event.nodeId) setActiveNodeIds(prev => { const next = new Set(prev); next.add(event.nodeId!); return next; });
          break;
        case "NODE_COMPLETED":
          if (event.nodeId) {
            setActiveNodeIds(prev => { const next = new Set(prev); next.delete(event.nodeId!); return next; });
            setNodeResults(prev => ({ ...prev, [event.nodeId!]: "completed" }));
          }
          break;
        case "NODE_FAILED":
          if (event.nodeId) {
            setActiveNodeIds(prev => { const next = new Set(prev); next.delete(event.nodeId!); return next; });
            setNodeResults(prev => ({ ...prev, [event.nodeId!]: "failed" }));
          }
          break;
        case "NODE_SKIPPED":
        case "NODE_BUFFERED":
        case "NODE_STATS":
          if (event.nodeId) setActiveNodeIds(prev => { const next = new Set(prev); next.delete(event.nodeId!); return next; });
          break;
        case "PIPELINE_STARTED":
          setNodeResults({});
          loadRuns();
          break;
        case "PIPELINE_COMPLETED":
          setActiveNodeIds(new Set());
          loadRuns();
          break;
      }
    };
    return subscribePipelineEvents(handleEvent, token);
  }, [token, selected?.id, selected?.name, loadRuns]);

  // Load version history for the selected pipeline. Re-runs whenever the
  // pipeline's own version changes (save / restore) so the list stays fresh.
  const loadVersions = useCallback(() => {
    if (!token || !selected) { setVersions([]); return; }
    setVersionsLoading(true);
    listPipelineVersions(token, selected.id)
      .then(vs => setVersions([...vs].sort((a, b) => b.versionNumber - a.versionNumber)))
      .catch(() => setVersions([]))
      .finally(() => setVersionsLoading(false));
  }, [token, selected?.id]);

  useEffect(() => {
    loadVersions();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, selected?.id, selected?.versionUuid]);

  /**
   * Restore a previous version. The server copies the requested version forward
   * into a brand-new version and returns the resulting pipeline, which we adopt
   * wholesale — clearing any local canvas edits and forcing the node editor to
   * rebuild from the restored definition.
   */
  const handleRestoreVersion = useCallback(async (versionNumber: number) => {
    if (!token || !selected || restoringVersion !== null) return;
    setRestoringVersion(versionNumber);
    try {
      const resp = await restorePipelineVersion(token, selected.id, versionNumber);
      const restored = toPipeline(resp);
      setSelected(restored);
      setPipelines(prev => prev.map(p => (p.id === restored.id ? restored : p)));
      // Drop local editing state — it belongs to the version we just replaced.
      setAddedNodes([]);
      setNodeDisplayNames({});
      setNodeAffinities({});
      setGraphJson(null);
      setSelectedNodeId(null);
      setNodeDetailOpen(false);
      setValidationErrors([]);
      setDirty(false);
      setCanvasReloadKey(k => k + 1);
      notify("success", t("pipeline.version.restoreOk", { from: versionNumber, to: restored.versionNumber }));
    } catch (err) {
      notify("error", (err as Error).message || t("pipeline.version.restoreFailed"));
    } finally {
      setRestoringVersion(null);
      setRestoreConfirm(null);
    }
  }, [token, selected, restoringVersion, notify, t]);

  const handleNodeSelect = useCallback((id: string | null) => {
    setSelectedNodeId(id);
    if (id !== null) setNodeDetailOpen(true);
  }, []);

  const handleDisplayNameChange = useCallback((nodeId: string, name: string) => {
    setNodeDisplayNames(prev => ({ ...prev, [nodeId]: name }));
  }, []);

  // Persist parameter changes from the NodeDetailSidebar into the pipeline definition
  const handleParameterChange = useCallback((nodeId: string, key: string, value: unknown) => {
    if (!selected) return;
    const node = selected.definition.nodes.find(n => n.id === nodeId);
    if (node) {
      // Normalise onto `options` — the only shape Loom reads — carrying over any legacy
      // `config`/`data` bag the stored definition still uses.
      node.options = { ...pipelineNodeOptions(node), [key]: value };
      delete node.config;
      delete node.data;
      setSelected({ ...selected });
      // Mirror onto the canvas: getGraphJson serialises from there, not from the definition.
      setNodeParameters(prev => ({ ...prev, [nodeId]: { ...(prev[nodeId] ?? {}), [key]: value } }));
      setDirty(true);
    }
  }, [selected]);

  // Persist affinity-group changes from the NodeDetailSidebar. Writes the
  // top-level `affinity` field on the definition node (the shape Loom reads) and
  // pushes it to the live canvas via the nodeAffinities channel.
  const handleAffinityChange = useCallback((nodeId: string, value: string) => {
    if (!selected) return;
    const node = selected.definition.nodes.find(n => n.id === nodeId);
    if (node) {
      node.affinity = value;
      setSelected({ ...selected });
      setNodeAffinities(prev => ({ ...prev, [nodeId]: value }));
      setDirty(true);
    }
  }, [selected]);

  // Persist edge type changes (PASS / REJECT / ANY) into the pipeline definition
  const handleEdgeTypeChange = useCallback((edgeId: string, edgeType: EdgeKind) => {
    if (!selected) return;
    const edge = selected.definition.edges.find(e => e.id === edgeId);
    if (edge) {
      edge.edgeType = edgeType;
      edge.label = edgeType;
      setSelected({ ...selected });
      setDirty(true);
    }
  }, [selected]);

  const handleAddNode = useCallback((desc: NodeDescriptor) => {
    if (!selected) return;
    const id = `pn-${Date.now()}`;
    const paramDefaults: Record<string, unknown> = {};
    for (const p of desc.parameters) {
      if (p.defaultValue !== undefined && p.defaultValue !== null) paramDefaults[p.key] = p.defaultValue;
    }
    const connectors = nodeConnectors(desc, paramDefaults);
    const newNode: RFNode = {
      id,
      type: "pipelineNode",
      position: { x: 300 + Math.random() * 100, y: 100 + Math.random() * 100 },
      data: {
        label: desc.name,
        description: desc.description,
        category: desc.category,
        kind: desc.kind,
        nodeIcon: resolveNodeIcon(desc),
        inputs: connectors.inputs,
        outputs: connectors.outputs,
        ...paramDefaults,
      },
    };
    // Also add to pipeline definition so NodeDetailSidebar can find it
    selected.definition.nodes.push({
      id, type: desc.kind, label: desc.name, description: desc.description,
      position: newNode.position, options: paramDefaults,
    });
    setAddedNodes(prev => [...prev, newNode]);
    setAddNodeOpen(false);
    setNodeFilter("");
  }, [selected]);

  // Delete node handlers
  const handleDeleteNodeRequest = useCallback((nodeId: string, label: string) => {
    setDeleteConfirm({ nodeId, label });
  }, []);

  const handleDeleteNodeConfirm = useCallback(() => {
    if (!deleteConfirm || !selected) return;
    const { nodeId } = deleteConfirm;
    // Remove from pipeline definition
    selected.definition.nodes = selected.definition.nodes.filter(n => n.id !== nodeId);
    selected.definition.edges = selected.definition.edges.filter(e => e.source !== nodeId && e.target !== nodeId);
    // Remove from added nodes
    setAddedNodes(prev => prev.filter(n => n.id !== nodeId));
    // Trigger canvas removal
    removalKeyRef.current++;
    setRemovalTrigger({ nodeId, key: removalKeyRef.current });
    // Force re-render by setting a cloned pipeline
    setSelected({ ...selected });
    if (selectedNodeId === nodeId) {
      setSelectedNodeId(null);
      setNodeDetailOpen(false);
    }
    setDeleteConfirm(null);
  }, [deleteConfirm, selected, selectedNodeId]);

  const handleGraphChange = useCallback((json: any) => {
    setGraphJson(json);
    setDirty(true);
    setValidationErrors([]); // clear stale validation on graph change
  }, []);

  const handleSave = useCallback(async () => {
    if (!token || !selected || saving) return;
    // Run validation before saving
    const definition = graphJson ?? {
      nodes: selected.definition.nodes,
      edges: selected.definition.edges,
    };
    const nodesForValidation = (definition.nodes ?? []).map((n: any) => ({ id: n.id, type: n.type ?? n.category, label: n.label }));
    const edgesForValidation = (definition.edges ?? []).map((e: any) => ({ source: e.source, target: e.target }));
    const errors = validatePipeline(nodesForValidation, edgesForValidation, descriptors);
    setValidationErrors(errors);
    if (errors.length > 0) {
      notify("error", errors[0].message);
      return;
    }
    setSaving(true);
    try {
      const req: PipelineUpdateRequest = {
        name: selected.name,
        description: selected.description,
        definition,
        enabled: selected.enabled,
        priority: selected.priority,
        dryRun: selected.dryRun,
      };
      const resp = await updatePipeline(token, selected.id, req);
      // A save creates a new version server-side — adopt the new version
      // metadata so the badge and the history list stay in sync. The canvas is
      // deliberately left untouched (it already shows what we just saved).
      const versioned: Pipeline = { ...selected, versionUuid: resp.versionUuid, versionNumber: resp.versionNumber };
      setSelected(versioned);
      setPipelines(prev => prev.map(p => (p.id === versioned.id ? versioned : p)));
      setDirty(false);
      notify("success", t("pipeline.editor.saveOk"));
    } catch (err) {
      notify("error", (err as Error).message || "Save failed");
    } finally {
      setSaving(false);
    }
  }, [token, selected, saving, graphJson, descriptors, notify, t]);

  // Select a pipeline in the sidebar, resetting the per-node inspector state.
  const applySelect = useCallback((p: Pipeline) => {
    setSelected(p);
    setSelectedNodeId(null);
    setNodeDetailOpen(false);
    setNodeAffinities({});
    setDirty(false);
  }, []);

  // Clicking a pipeline in the list. When there are unsaved edits, defer the
  // switch behind a discard-confirm instead of silently dropping them.
  const handleSelectPipeline = useCallback((p: Pipeline) => {
    if (p.id === selected?.id) return;
    if (dirty) {
      setPendingSwitch(p);
      return;
    }
    applySelect(p);
  }, [selected, dirty, applySelect]);

  const confirmSwitch = useCallback(() => {
    if (pendingSwitch) applySelect(pendingSwitch);
    setPendingSwitch(null);
  }, [pendingSwitch, applySelect]);

  // Create a brand-new pipeline or clone the selected one. Clone seeds the new
  // pipeline with a deep copy of the current definition (validated first, as a
  // save would be); "new" starts from an empty source-only canvas.
  const handleCreateConfirm = useCallback(async () => {
    if (!token || !createDialog || creating) return;
    const name = createDialog.name.trim();
    if (!name) {
      notify("error", t("pipeline.editor.nameRequired"));
      return;
    }
    let definition: { nodes: unknown[]; edges: unknown[] };
    const req: PipelineCreateRequest = { name, description: createDialog.description.trim() };
    if (createDialog.mode === "clone" && selected) {
      // Clone the persisted definition — it preserves node `type` (the descriptor
      // kind). The live canvas serialization (graphJson) rewrites `type` to the
      // node category, so it is unsuitable as a clone source.
      definition = JSON.parse(JSON.stringify({
        nodes: selected.definition.nodes ?? [],
        edges: selected.definition.edges ?? [],
      }));
      const nodesForValidation = (definition.nodes as any[]).map(n => ({ id: n.id, type: n.type ?? n.category, label: n.label }));
      const edgesForValidation = (definition.edges as any[]).map(e => ({ source: e.source, target: e.target }));
      const errors = validatePipeline(nodesForValidation, edgesForValidation, descriptors);
      if (errors.length > 0) {
        notify("error", errors[0].message);
        return;
      }
      req.enabled = selected.enabled;
      req.priority = selected.priority;
      req.dryRun = selected.dryRun;
    } else {
      definition = { nodes: [], edges: [] };
      req.enabled = true;
      req.priority = 0;
      req.dryRun = false;
    }
    req.definition = definition as Record<string, unknown>;
    setCreating(true);
    try {
      const resp = await createPipeline(token, req);
      const np = toPipeline(resp);
      setPipelines(prev => [...prev, np]);
      applySelect(np);
      setCanvasReloadKey(k => k + 1);
      setCreateDialog(null);
      notify("success", createDialog.mode === "clone" ? t("pipeline.editor.cloneOk") : t("pipeline.editor.createOk"));
    } catch (err) {
      notify("error", (err as Error).message || "Create failed");
    } finally {
      setCreating(false);
    }
  }, [token, createDialog, creating, selected, descriptors, notify, t, applySelect]);

  const handleDeletePipeline = useCallback(async () => {
    if (!token || !selected || deletingPipeline) return;
    setDeletingPipeline(true);
    try {
      await deletePipeline(token, selected.id);
      const rest = pipelines.filter(p => p.id !== selected.id);
      setPipelines(rest);
      const next = rest[0] ?? null;
      if (next) applySelect(next); else { setSelected(null); setDirty(false); }
      setCanvasReloadKey(k => k + 1);
      setDeletePipelineConfirm(false);
      notify("success", t("pipeline.editor.deleteOk"));
    } catch (err) {
      notify("error", (err as Error).message || "Delete failed");
    } finally {
      setDeletingPipeline(false);
    }
  }, [token, selected, deletingPipeline, pipelines, notify, t, applySelect]);

  const handleRun = useCallback(async () => {
    if (!token || !selected || running) return;
    setRunning(true);
    try {
      const resp = await runPipeline(token, selected.id, { dryRun: selected.dryRun });
      notify(
        resp.dispatched ? "success" : "info",
        resp.dispatched
          ? (t("pipeline.editor.runDispatched") || "Pipeline run dispatched")
          : (resp.message || t("pipeline.editor.runNoProcessor") || "No processor available"),
      );
      // Refresh the run list so the freshly-dispatched run shows immediately;
      // subsequent PIPELINE_* events keep it live.
      if (resp.dispatched) loadRuns();
    } catch (err) {
      notify("error", (err as Error).message || "Run failed");
    } finally {
      setRunning(false);
    }
  }, [token, selected, running, notify, t, loadRuns]);

  const handleCancelRun = useCallback(async (runUuid: string) => {
    if (!token || !selected) return;
    try {
      await cancelPipelineRun(token, selected.id, runUuid);
      notify("success", t("pipeline.editor.runCancelled") || "Pipeline run cancelled");
      // Refresh so the run flips to cancelled immediately.
      loadRuns();
    } catch (err) {
      notify("error", (err as Error).message || "Cancel failed");
    }
  }, [token, selected, notify, t, loadRuns]);

  // Global keyboard shortcuts (H = help, N = command palette)
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable) return;
      if (e.key === "h" || e.key === "H") {
        setShowHelp(v => !v);
      } else if (e.key === "n" || e.key === "N") {
        e.preventDefault();
        if (selected) setShowCommandPalette(true);
      } else if (e.key === "a" || e.key === "A") {
        if (selected) setAutoArrangeTrigger(v => v + 1);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
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
        <Box sx={{ px: 2, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", flex: 1 }}>{t("pipeline.editor.title")}</Typography>
          <Tooltip title={t("pipeline.editor.newPipeline")}>
            <IconButton
              size="small"
              data-testid="pipeline-create-button"
              onClick={() => setCreateDialog({ mode: "new", name: "", description: "" })}
              sx={{ color: tokens.text.secondary, "&:hover": { color: tokens.primary.light } }}
            >
              <AddOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
        </Box>
        <List dense sx={{ p: 1, flex: 1, overflow: "auto" }}>
          {pipelines.map(p => {
            const latestRun = p.runs[0];
            const sc = latestRun ? runStatusColor[latestRun.status] : tokens.text.tertiary;
            return (
              <ListItemButton
                key={p.id}
                data-testid={`pipeline-list-item-${p.id}`}
                selected={selected?.id === p.id}
                onClick={() => handleSelectPipeline(p)}
                sx={{ borderRadius: tokens.radius.md, mb: 0.5 }}
              >
                <ListItemText
                  primary={<Typography variant="body2" fontWeight={500} noWrap sx={{ fontSize: "0.82rem" }}>{p.name}</Typography>}
                  secondary={
                    <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, mt: 0.25 }}>
                      <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: sc }} />
                      <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>
                        {latestRun ? latestRun.status : t("pipeline.editor.noRuns")} · P{p.priority}
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
                <Tooltip title={t("pipeline.editor.showLog")}>
                  <Chip
                    icon={<TerminalOutlined sx={{ fontSize: 13 }} />}
                    label={t("pipeline.editor.log")}
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
              <Tooltip title={dirty ? (t("pipeline.editor.saveTooltip") || "Save pipeline") : (t("pipeline.editor.savedTooltip") || "No unsaved changes")}>
                <span>
                  <Chip
                    icon={<SaveOutlined sx={{ fontSize: 14 }} />}
                    label={saving
                      ? (t("pipeline.editor.saving") || "Saving…")
                      : dirty
                        ? (t("pipeline.editor.save") || "Save")
                        : (t("pipeline.editor.saved") || "Saved")}
                    size="small"
                    onClick={dirty && !saving ? handleSave : undefined}
                    sx={{
                      bgcolor: dirty ? `${tokens.accent.amber}22` : tokens.bg.overlay,
                      border: `1px solid ${dirty ? tokens.accent.amber : tokens.border.default}`,
                      color: dirty ? tokens.accent.amber : tokens.text.secondary,
                      cursor: dirty && !saving ? "pointer" : "default",
                      fontWeight: 600,
                      opacity: saving ? 0.6 : 1,
                    }}
                  />
                </span>
              </Tooltip>
              <Tooltip title={selected.dryRun ? t("pipeline.editor.dryRunTooltip") : t("pipeline.editor.runTooltip")}>
                <span>
                  <Chip
                    icon={<PlayArrowOutlined sx={{ fontSize: 14 }} />}
                    label={running
                      ? (t("pipeline.editor.running") || "Running…")
                      : selected.dryRun
                        ? t("pipeline.editor.dryRun")
                        : t("pipeline.editor.run")}
                    size="small"
                    onClick={running ? undefined : handleRun}
                    sx={{
                      bgcolor: selected.dryRun ? `${tokens.accent.amber}22` : tokens.primary.subtle,
                      border: `1px solid ${selected.dryRun ? tokens.accent.amber : tokens.primary.main}`,
                      color: selected.dryRun ? tokens.accent.amber : tokens.primary.light,
                      cursor: running ? "default" : "pointer",
                      fontWeight: 600,
                      opacity: running ? 0.6 : 1,
                    }}
                  />
                </span>
              </Tooltip>
              <Tooltip title={t("pipeline.editor.clonePipelineTitle")}>
                <Chip
                  icon={<ContentCopy sx={{ fontSize: 13 }} />}
                  label={t("pipeline.editor.clone")}
                  size="small"
                  data-testid="pipeline-clone-button"
                  onClick={() => setCreateDialog({ mode: "clone", name: `${selected.name} (copy)`, description: selected.description })}
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
              <Tooltip title={t("pipeline.editor.deletePipelineTitle")}>
                <Chip
                  icon={<DeleteOutline sx={{ fontSize: 14 }} />}
                  label={t("common.delete")}
                  size="small"
                  data-testid="pipeline-delete-button"
                  onClick={() => setDeletePipelineConfirm(true)}
                  sx={{
                    bgcolor: `${tokens.accent.red}18`,
                    border: `1px solid ${tokens.accent.red}55`,
                    color: tokens.accent.red,
                    cursor: "pointer",
                    fontWeight: 500,
                    "&:hover": { bgcolor: `${tokens.accent.red}28`, borderColor: tokens.accent.red },
                  }}
                />
              </Tooltip>
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                <Typography variant="caption" sx={{ fontSize: "0.72rem", color: tokens.text.tertiary }}>{t("pipeline.editor.enabled")}</Typography>
                <Switch size="small" checked={selected.enabled} />
              </Box>
            </Box>
          )}
          {/* Canvas + log panel */}
          <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
            {/* Visual / JSON tabs */}
            {selected && (
              <Box sx={{ borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, flexShrink: 0 }}>
                <Tabs value={canvasTab} onChange={(_, v) => setCanvasTab(v)} sx={{ minHeight: 32, px: 1 }}>
                  <Tab label={t("pipeline.editor.tabVisual")} sx={{ fontSize: "0.72rem", minHeight: 32, py: 0.5, minWidth: 60 }} />
                  <Tab label={t("pipeline.editor.tabJson")} icon={<DataObjectOutlined sx={{ fontSize: 13 }} />} iconPosition="start" sx={{ fontSize: "0.72rem", minHeight: 32, py: 0.5, minWidth: 60 }} />
                </Tabs>
              </Box>
            )}

            {/* Visual tab */}
            {canvasTab === 0 && (
              <Box data-testid="pipeline-canvas" sx={{ flex: 1, overflow: "hidden", position: "relative" }}>
                {/* Minimal version badge overlaid on the editor — click for history */}
                {selected && (
                  <Box sx={{ position: "absolute", top: 8, left: 8, zIndex: 5 }}>
                    <PipelineVersionBadge
                      pipeline={selected}
                      versions={versions}
                      loading={versionsLoading}
                      restoring={restoringVersion}
                      onOpen={loadVersions}
                      onRestore={n => setRestoreConfirm(n)}
                      onCompare={n => setDiffTarget(n)}
                    />
                  </Box>
                )}
                <PipelineCanvas
                  pipeline={selected}
                  onNodeSelect={handleNodeSelect}
                  externalNodes={addedNodes}
                  nodeDisplayNames={nodeDisplayNames}
                  nodeAffinities={nodeAffinities}
                  nodeParameters={nodeParameters}
                  descriptors={descriptors}
                  onDeleteNode={handleDeleteNodeRequest}
                  activeNodeIds={activeNodeIds}
                  nodeResults={nodeResults}
                  onGraphChange={handleGraphChange}
                  removalTrigger={removalTrigger}
                  autoArrangeTrigger={autoArrangeTrigger}
                  onEdgeTypeChange={handleEdgeTypeChange}
                  reloadKey={canvasReloadKey}
                />
              </Box>
            )}

            {/* JSON tab */}
            {canvasTab === 1 && (
              <Box sx={{ flex: 1, overflow: "auto", p: 2, bgcolor: tokens.bg.base, display: "flex", flexDirection: "column", gap: 2 }}>
                {/* Loaded definition from server */}
                <Box>
                  <Typography variant="caption" sx={{ fontSize: "0.68rem", fontWeight: 700, color: tokens.text.tertiary, textTransform: "uppercase", letterSpacing: "0.06em", mb: 0.5, display: "block" }}>
                    {t("pipeline.editor.jsonLoaded")}
                  </Typography>
                  <Box sx={{
                    bgcolor: tokens.bg.surface,
                    border: `1px solid ${tokens.border.subtle}`,
                    borderRadius: tokens.radius.md,
                    p: 2,
                    overflow: "auto",
                    maxHeight: "40%",
                  }}>
                    <Typography
                      component="pre"
                      sx={{
                        fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
                        fontSize: "0.76rem",
                        lineHeight: 1.7,
                        color: tokens.text.secondary,
                        whiteSpace: "pre-wrap",
                        wordBreak: "break-word",
                        m: 0,
                        "& .json-key": { color: tokens.primary.main, fontWeight: 600 },
                        "& .json-string": { color: tokens.accent.green },
                        "& .json-number": { color: tokens.accent.amber },
                        "& .json-boolean": { color: tokens.accent.blue },
                        "& .json-null": { color: tokens.text.tertiary },
                      }}
                      dangerouslySetInnerHTML={{
                        __html: selected?.definition
                          ? syntaxHighlightJson(JSON.stringify(selected.definition, null, 2))
                          : "<span style='color: " + tokens.text.tertiary + "'>No loaded definition</span>",
                      }}
                    />
                  </Box>
                </Box>
                {/* Current canvas state */}
                <Box sx={{ flex: 1, display: "flex", flexDirection: "column" }}>
                  <Typography variant="caption" sx={{ fontSize: "0.68rem", fontWeight: 700, color: tokens.text.tertiary, textTransform: "uppercase", letterSpacing: "0.06em", mb: 0.5, display: "block" }}>
                    {t("pipeline.editor.jsonCurrent")}
                  </Typography>
                  <Box sx={{
                    bgcolor: tokens.bg.surface,
                    border: `1px solid ${tokens.border.subtle}`,
                    borderRadius: tokens.radius.md,
                    p: 2,
                    overflow: "auto",
                    flex: 1,
                  }}>
                    <Typography
                      component="pre"
                      sx={{
                        fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
                        fontSize: "0.76rem",
                        lineHeight: 1.7,
                        color: tokens.text.secondary,
                        whiteSpace: "pre-wrap",
                        wordBreak: "break-word",
                        m: 0,
                        "& .json-key": { color: tokens.primary.main, fontWeight: 600 },
                        "& .json-string": { color: tokens.accent.green },
                        "& .json-number": { color: tokens.accent.amber },
                        "& .json-boolean": { color: tokens.accent.blue },
                        "& .json-null": { color: tokens.text.tertiary },
                      }}
                      dangerouslySetInnerHTML={{
                        __html: graphJson
                          ? syntaxHighlightJson(JSON.stringify(graphJson, null, 2))
                          : "<span style='color: " + tokens.text.tertiary + "'>No pipeline data</span>",
                      }}
                    />
                  </Box>
                </Box>
                {/* JSON validity bar */}
                <Box sx={{
                  mt: 1,
                  px: 1.5,
                  py: 0.5,
                  bgcolor: tokens.bg.surface,
                  border: `1px solid ${tokens.border.subtle}`,
                  borderRadius: tokens.radius.sm,
                  display: "flex",
                  alignItems: "center",
                  gap: 0.75,
                  flexShrink: 0,
                }}>
                  <Box sx={{
                    width: 8,
                    height: 8,
                    borderRadius: "50%",
                    bgcolor: graphJson ? tokens.accent.green : tokens.accent.red,
                  }} />
                  <Typography variant="caption" sx={{
                    fontSize: "0.68rem",
                    color: graphJson ? tokens.accent.green : tokens.accent.red,
                    fontWeight: 600,
                  }}>
                    {graphJson ? t("pipeline.editor.jsonValid") : t("pipeline.editor.jsonInvalid")}
                  </Typography>
                </Box>
                {/* Validation errors */}
                {validationErrors.length > 0 && (
                  <Box sx={{ mt: 1, px: 1.5, py: 0.5, bgcolor: `${tokens.accent.red}11`, border: `1px solid ${tokens.accent.red}44`, borderRadius: tokens.radius.sm, flexShrink: 0 }}>
                    {validationErrors.map((err, i) => (
                      <Box key={i} sx={{ display: "flex", alignItems: "flex-start", gap: 0.5, py: 0.25 }}>
                        <ErrorOutline sx={{ fontSize: 12, color: tokens.accent.red, mt: 1, flexShrink: 0 }} />
                        <Typography variant="caption" sx={{ fontSize: "0.65rem", color: tokens.accent.red, lineHeight: 1.3 }}>{err.message}</Typography>
                      </Box>
                    ))}
                  </Box>
                )}
              </Box>
            )}

            {/* Add node bar — above the log */}
            {selected && (
              <ClickAwayListener onClickAway={() => setAddNodeOpen(false)}>
                <Box ref={addNodeBarRef} sx={{ px: 2, py: 1, borderTop: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, flexShrink: 0, position: "relative" }}>
                  <TextField
                    inputRef={addNodeInputRef}
                    size="small"
                    placeholder={t("pipeline.editor.addNode")}
                    value={nodeFilter}
                    onChange={(e) => { setNodeFilter(e.target.value); setAddNodeIdx(0); }}
                    onFocus={() => setAddNodeOpen(true)}
                    onKeyDown={(e) => {
                      const filtered = descriptors.filter(d =>
                        !nodeFilter ||
                        d.name.toLowerCase().includes(nodeFilter.toLowerCase()) ||
                        d.kind.toLowerCase().includes(nodeFilter.toLowerCase()) ||
                        d.category.toLowerCase().includes(nodeFilter.toLowerCase())
                      );
                      if (e.key === "ArrowDown") {
                        e.preventDefault();
                        setAddNodeIdx(i => Math.min(i + 1, filtered.length - 1));
                      } else if (e.key === "ArrowUp") {
                        e.preventDefault();
                        setAddNodeIdx(i => Math.max(i - 1, 0));
                      } else if (e.key === "Enter") {
                        e.preventDefault();
                        if (filtered[addNodeIdx]) handleAddNode(filtered[addNodeIdx]);
                      } else if (e.key === "Escape") {
                        setAddNodeOpen(false);
                        addNodeInputRef.current?.blur();
                      }
                    }}
                    InputProps={{
                      startAdornment: (
                        <InputAdornment position="start">
                          <AddOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                        </InputAdornment>
                      ),
                    }}
                    sx={{
                      width: "30%", minWidth: 180, maxWidth: 280,
                      "& .MuiInputBase-root": { fontSize: "0.78rem", height: 34 },
                      "& .MuiOutlinedInput-notchedOutline": { borderColor: tokens.border.default },
                    }}
                  />
                  <Popper
                    open={addNodeOpen}
                    anchorEl={addNodeBarRef.current}
                    placement="top-start"
                    style={{ zIndex: 1300, width: 280 }}
                    modifiers={[{ name: "offset", options: { offset: [16, 4] } }, { name: "preventOverflow", enabled: false }]}
                  >
                    <Paper
                      elevation={8}
                      sx={{
                        bgcolor: tokens.bg.panel,
                        border: `1px solid ${tokens.border.default}`,
                        borderRadius: tokens.radius.md,
                        maxHeight: 320,
                        overflow: "auto",
                      }}
                    >
                      <List dense sx={{ py: 0.5 }}>
                        {descriptors
                          .filter(d =>
                            !nodeFilter ||
                            d.name.toLowerCase().includes(nodeFilter.toLowerCase()) ||
                            d.kind.toLowerCase().includes(nodeFilter.toLowerCase()) ||
                            d.category.toLowerCase().includes(nodeFilter.toLowerCase())
                          )
                          .map((d, idx) => {
                            const cfg = nodeVisualConfig(d);
                            return (
                              <ListItemButton
                                key={d.kind}
                                selected={idx === addNodeIdx}
                                onClick={() => handleAddNode(d)}
                                sx={{
                                  py: 0.6,
                                  px: 1.5,
                                  gap: 1,
                                  "&.Mui-selected": { bgcolor: `${tokens.primary.main}18` },
                                }}
                              >
                                <Box sx={{ width: 22, height: 22, borderRadius: tokens.radius.sm, bgcolor: cfg.bg, display: "flex", alignItems: "center", justifyContent: "center", color: cfg.color, flexShrink: 0 }}>
                                  {cfg.icon}
                                </Box>
                                <Box sx={{ minWidth: 0, flex: 1 }}>
                                  <Typography variant="body2" sx={{ fontSize: "0.78rem", fontWeight: 600, lineHeight: 1.2 }}>{d.name}</Typography>
                                  <Typography variant="caption" sx={{ fontSize: "0.62rem", color: tokens.text.tertiary, display: "block" }}>{d.category} · {d.description.slice(0, 45)}</Typography>
                                </Box>
                              </ListItemButton>
                            );
                          })}
                        {descriptors.filter(d =>
                          !nodeFilter ||
                          d.name.toLowerCase().includes(nodeFilter.toLowerCase()) ||
                          d.kind.toLowerCase().includes(nodeFilter.toLowerCase()) ||
                          d.category.toLowerCase().includes(nodeFilter.toLowerCase())
                        ).length === 0 && (
                          <Box sx={{ p: 1.5, textAlign: "center" }}>
                            <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>{t("pipeline.commandPalette.empty")}</Typography>
                          </Box>
                        )}
                      </List>
                    </Paper>
                  </Popper>
                </Box>
              </ClickAwayListener>
            )}

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
                <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.75rem", flex: 1 }}>{t("pipeline.editor.systemLog")}</Typography>
                {selected && (
                  <Typography data-testid="pipeline-log-status" variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>
                    {selected.name} · {pipelineRuns[0]?.status ?? t("pipeline.editor.noRuns")}
                  </Typography>
                )}
                <IconButton size="small" onClick={() => setLogOpen(v => !v)} sx={{ width: 20, height: 20 }}>
                  {logOpen ? <ExpandMoreOutlined sx={{ fontSize: 14 }} /> : <ExpandLessOutlined sx={{ fontSize: 14 }} />}
                </IconButton>
              </Box>
              <Box sx={{ flex: 1, overflow: "auto", p: 1.5 }}>
                {selected ? (
                  pipelineRuns.length > 0 ? (
                    pipelineRuns.map(r => {
                      const status = r.status.toLowerCase();
                      const isErr = status === "failed" || status === "error";
                      const isWarn = status === "running" || status === "active";
                      const isOk = status === "success" || status === "completed";
                      const color = isErr ? tokens.accent.red : isWarn ? tokens.accent.amber : isOk ? tokens.accent.green : tokens.text.secondary;
                      return (
                        <Typography
                          key={r.uuid}
                          sx={{
                            fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
                            fontSize: "0.72rem",
                            lineHeight: 1.6,
                            color,
                            display: "block",
                            whiteSpace: "pre-wrap",
                            wordBreak: "break-word",
                          }}
                        >
                          <Box component="span" sx={{ color: tokens.text.tertiary, mr: 1.5 }}>
                            {r.started ? new Date(r.started).toLocaleTimeString() : ""}
                          </Box>
                          {r.status} — {r.successCount}/{r.mediaCount} {t("pipeline.runHistory.assets")}{r.failureCount > 0 ? `, ${r.failureCount} ${t("pipeline.runHistory.errors")}` : ""}{r.errorMessage ? ` — ${r.errorMessage}` : ""}
                        </Typography>
                      );
                    })
                  ) : (
                    <Typography sx={{ fontFamily: "monospace", fontSize: "0.72rem", color: tokens.text.tertiary }}>
                      {t("pipeline.runHistory.noRuns")}
                    </Typography>
                  )
                ) : (
                  <Typography sx={{ fontFamily: "monospace", fontSize: "0.72rem", color: tokens.text.tertiary }}>
                    {t("pipeline.editor.selectPipelineLogs")}
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
        onParameterChange={handleParameterChange}
        onAffinityChange={handleAffinityChange}
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
              <Typography variant="caption" sx={{ fontSize: "0.7rem", color: tokens.primary.light, fontWeight: 600 }}>{t("pipeline.nodeDetail.title")}</Typography>
            </Box>
          </Tooltip>
        )}
        <Box sx={{ flex: 1, overflow: "hidden" }}>
          <PipelineInspector pipeline={selected} runs={pipelineRuns} runsLoading={runsLoading} onCancelRun={handleCancelRun} onSelectRun={openRunDetail} />
        </Box>
      </Box>

      {/* Run detail drill-down drawer */}
      <RunDetailDrawer run={selectedRun} items={runItems} loading={runItemsLoading} onClose={closeRunDetail} />

      {/* Version diff — compare a previous version with the current one */}
      {selected && (
        <PipelineVersionDiff
          open={diffTarget !== null}
          onClose={() => setDiffTarget(null)}
          uuid={selected.id}
          token={token}
          baseVersion={diffTarget}
          currentVersion={selected.versionNumber}
        />
      )}

      {/* Restore version confirmation dialog */}
      <Dialog
        open={restoreConfirm !== null}
        onClose={() => restoringVersion === null && setRestoreConfirm(null)}
        PaperProps={{
          sx: {
            bgcolor: tokens.bg.panel,
            border: `1px solid ${tokens.border.default}`,
            borderRadius: tokens.radius.lg,
            minWidth: 360,
          },
        }}
      >
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700 }}>
          {t("pipeline.version.restoreTitle")}
        </DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ fontSize: "0.85rem", color: tokens.text.secondary }}>
            {t("pipeline.version.restoreMessage", { version: restoreConfirm })}
          </DialogContentText>
          {dirty && (
            <DialogContentText sx={{ fontSize: "0.8rem", color: tokens.accent.amber, mt: 1 }}>
              {t("pipeline.version.restoreDirtyWarning")}
            </DialogContentText>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setRestoreConfirm(null)} disabled={restoringVersion !== null} sx={{ color: tokens.text.secondary, textTransform: "none" }}>
            {t("common.cancel")}
          </Button>
          <Button
            data-testid="pipeline-version-restore-confirm"
            onClick={() => restoreConfirm !== null && handleRestoreVersion(restoreConfirm)}
            disabled={restoringVersion !== null}
            startIcon={restoringVersion !== null ? <CircularProgress size={13} /> : <RestoreOutlined sx={{ fontSize: 15 }} />}
            sx={{ color: tokens.primary.light, textTransform: "none", fontWeight: 600 }}
          >
            {t("pipeline.version.restore")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete node confirmation dialog */}
      <Dialog
        open={Boolean(deleteConfirm)}
        onClose={() => setDeleteConfirm(null)}
        PaperProps={{
          sx: {
            bgcolor: tokens.bg.panel,
            border: `1px solid ${tokens.border.default}`,
            borderRadius: tokens.radius.lg,
            minWidth: 360,
          },
        }}
      >
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700 }}>
          {t("pipeline.editor.deleteNodeTitle")}
        </DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ fontSize: "0.85rem", color: tokens.text.secondary }}>
            {t("pipeline.editor.deleteNodeMessage", { name: deleteConfirm?.label ?? "" })}
          </DialogContentText>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteConfirm(null)} sx={{ color: tokens.text.secondary }}>
            {t("common.cancel")}
          </Button>
          <Button
            onClick={handleDeleteNodeConfirm}
            variant="contained"
            color="error"
            sx={{ fontWeight: 600 }}
          >
            {t("common.delete")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Create / clone pipeline dialog */}
      <Dialog
        open={Boolean(createDialog)}
        onClose={() => !creating && setCreateDialog(null)}
        PaperProps={{
          sx: {
            bgcolor: tokens.bg.panel,
            border: `1px solid ${tokens.border.default}`,
            borderRadius: tokens.radius.lg,
            minWidth: 380,
          },
        }}
      >
        <DialogTitle data-testid="pipeline-create-dialog" sx={{ fontSize: "1rem", fontWeight: 700 }}>
          {createDialog?.mode === "clone" ? t("pipeline.editor.clonePipelineTitle") : t("pipeline.editor.newPipelineTitle")}
        </DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: 1 }}>
          <TextField
            autoFocus
            fullWidth
            size="small"
            label={t("pipeline.editor.nameLabel")}
            data-testid="pipeline-create-name"
            value={createDialog?.name ?? ""}
            onChange={e => setCreateDialog(d => (d ? { ...d, name: e.target.value } : d))}
            onKeyDown={e => { if (e.key === "Enter" && createDialog?.name.trim()) { e.preventDefault(); handleCreateConfirm(); } }}
            sx={{ mt: 1 }}
          />
          <TextField
            fullWidth
            size="small"
            label={t("pipeline.editor.descriptionLabel")}
            value={createDialog?.description ?? ""}
            onChange={e => setCreateDialog(d => (d ? { ...d, description: e.target.value } : d))}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setCreateDialog(null)} disabled={creating} sx={{ color: tokens.text.secondary, textTransform: "none" }}>
            {t("common.cancel")}
          </Button>
          <Button
            data-testid="pipeline-create-confirm"
            onClick={handleCreateConfirm}
            variant="contained"
            disabled={creating || !createDialog?.name.trim()}
            startIcon={creating ? <CircularProgress size={13} /> : undefined}
            sx={{ fontWeight: 600, textTransform: "none" }}
          >
            {createDialog?.mode === "clone" ? t("pipeline.editor.clone") : t("pipeline.editor.create")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete pipeline confirmation dialog */}
      <Dialog
        open={deletePipelineConfirm}
        onClose={() => !deletingPipeline && setDeletePipelineConfirm(false)}
        PaperProps={{
          sx: {
            bgcolor: tokens.bg.panel,
            border: `1px solid ${tokens.border.default}`,
            borderRadius: tokens.radius.lg,
            minWidth: 360,
          },
        }}
      >
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700 }}>
          {t("pipeline.editor.deletePipelineTitle")}
        </DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ fontSize: "0.85rem", color: tokens.text.secondary }}>
            {t("pipeline.editor.deletePipelineMessage", { name: selected?.name ?? "" })}
          </DialogContentText>
          {dirty && (
            <DialogContentText sx={{ fontSize: "0.8rem", color: tokens.accent.amber, mt: 1 }}>
              {t("pipeline.editor.switchDirtyMessage")}
            </DialogContentText>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeletePipelineConfirm(false)} disabled={deletingPipeline} sx={{ color: tokens.text.secondary }}>
            {t("common.cancel")}
          </Button>
          <Button
            data-testid="pipeline-delete-confirm"
            onClick={handleDeletePipeline}
            variant="contained"
            color="error"
            disabled={deletingPipeline}
            startIcon={deletingPipeline ? <CircularProgress size={13} /> : undefined}
            sx={{ fontWeight: 600 }}
          >
            {t("common.delete")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Unsaved-changes guard when switching pipelines */}
      <Dialog
        open={Boolean(pendingSwitch)}
        onClose={() => setPendingSwitch(null)}
        PaperProps={{
          sx: {
            bgcolor: tokens.bg.panel,
            border: `1px solid ${tokens.border.default}`,
            borderRadius: tokens.radius.lg,
            minWidth: 360,
          },
        }}
      >
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700 }}>
          {t("pipeline.editor.switchDirtyTitle")}
        </DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ fontSize: "0.85rem", color: tokens.text.secondary }}>
            {t("pipeline.editor.switchDirtyMessage")}
          </DialogContentText>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setPendingSwitch(null)} sx={{ color: tokens.text.secondary }}>
            {t("common.cancel")}
          </Button>
          <Button
            data-testid="pipeline-switch-confirm"
            onClick={confirmSwitch}
            variant="contained"
            color="error"
            sx={{ fontWeight: 600 }}
          >
            {t("pipeline.editor.discard")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Help overlay */}
      {showHelp && (
        <Box
          onClick={() => setShowHelp(false)}
          onKeyDown={(e) => { if (e.key === "Escape") setShowHelp(false); }}
          tabIndex={-1}
          sx={{
            position: "fixed",
            inset: 0,
            zIndex: 1300,
            bgcolor: "rgba(0,0,0,0.6)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            animation: "fadeInOverlay 150ms ease",
            "@keyframes fadeInOverlay": { from: { opacity: 0 }, to: { opacity: 1 } },
          }}
        >
          <Paper
            onClick={(e) => e.stopPropagation()}
            elevation={8}
            sx={{
              p: 3,
              minWidth: 320,
              maxWidth: 420,
              bgcolor: tokens.bg.panel,
              border: `1px solid ${tokens.border.default}`,
              borderRadius: tokens.radius.lg,
            }}
          >
            <Typography variant="h6" fontWeight={700} sx={{ mb: 2, fontSize: "1rem" }}>
              {t("pipeline.help.title")}
            </Typography>
            {[
              { key: "H", desc: t("pipeline.help.hKey") },
              { key: "N", desc: t("pipeline.help.nKey") },
              { key: "A", desc: t("pipeline.help.aKey") },
              { key: "Del", desc: t("pipeline.help.delKey") },
            ].map((shortcut) => (
              <Box key={shortcut.key} sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1.25 }}>
                <Chip
                  label={shortcut.key}
                  size="small"
                  sx={{
                    fontFamily: "monospace",
                    fontWeight: 700,
                    fontSize: "0.76rem",
                    minWidth: 44,
                    bgcolor: tokens.bg.overlay,
                    border: `1px solid ${tokens.border.default}`,
                    borderRadius: tokens.radius.sm,
                  }}
                />
                <Typography variant="body2" sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>
                  {shortcut.desc}
                </Typography>
              </Box>
            ))}
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, display: "block", mt: 2, fontSize: "0.7rem" }}>
              {t("pipeline.help.close")}
            </Typography>
          </Paper>
        </Box>
      )}

      {/* Node command palette (N key) */}
      <Dialog
        open={showCommandPalette}
        onClose={() => setShowCommandPalette(false)}
        PaperProps={{
          sx: {
            bgcolor: tokens.bg.panel,
            border: `1px solid ${tokens.border.default}`,
            borderRadius: tokens.radius.lg,
            width: 400,
            maxHeight: 480,
            overflow: "hidden",
          },
        }}
      >
        <CommandPaletteContent
          descriptors={descriptors}
          onAdd={(desc) => { handleAddNode(desc); setShowCommandPalette(false); }}
          onClose={() => setShowCommandPalette(false)}
        />
      </Dialog>

      {/* Global feedback for Save/Run actions */}
      <Snackbar
        open={snack.open}
        autoHideDuration={4000}
        onClose={() => setSnack(s => ({ ...s, open: false }))}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
      >
        <Alert
          onClose={() => setSnack(s => ({ ...s, open: false }))}
          severity={snack.severity}
          sx={{ width: "100%" }}
        >
          {snack.message}
        </Alert>
      </Snackbar>
    </Box>
  );
}
