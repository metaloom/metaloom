import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Chip, Paper, Tooltip, Typography } from "@mui/material";
import { AccountTreeOutlined, OpenInNewOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { ChatVisual, PipelineGraphPayload } from "../../types";
import { layoutPipelineGraph } from "./pipelineGraphLayout";

/**
 * Compact, inline rendering of a `pipeline-graph` visual returned by the `get_pipeline` MCP tool
 * (CHAT.md §6.1). It answers "what does this pipeline do?" at a glance inside the conversation; the
 * full editor is one click away.
 */

/** Node colours by category — the same mapping the pipeline editor uses, so a node keeps its colour. */
const CATEGORY_COLORS: Record<string, string> = {
  SOURCE: tokens.accent.blue,
  FILTER: tokens.accent.amber,
  ANALYSIS: tokens.primary.main,
  TRANSFORM: "#e040fb",
  OUTPUT: tokens.accent.teal,
};

const BRANCH_COLORS: Record<string, string> = {
  PASS: tokens.accent.green,
  REJECT: tokens.accent.red,
};

function categoryColor(category?: string): string {
  return CATEGORY_COLORS[category ?? ""] ?? tokens.primary.main;
}

export default function PipelineGraphCard({ visual }: { visual: ChatVisual }) {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const payload = visual.payload as PipelineGraphPayload;
  const layout = useMemo(() => layoutPipelineGraph(payload ?? { nodes: [], edges: [] }), [payload]);

  // A graph without nodes is not worth a card — the assistant's text already carries the answer.
  if (layout.nodes.length === 0) return null;

  const name = payload.name || visual.label;
  const disabled = payload.enabled === false;

  return (
    <Paper
      elevation={0}
      data-testid="chat-pipeline-graph"
      data-pipeline-uuid={payload.pipelineUuid ?? visual.id}
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.md,
        overflow: "hidden",
        maxWidth: "100%",
      }}
    >
      {/* Header */}
      <Box
        sx={{
          px: 1.5, py: 1, display: "flex", alignItems: "center", gap: 1,
          borderBottom: `1px solid ${tokens.border.subtle}`,
        }}
      >
        <AccountTreeOutlined sx={{ fontSize: 15, color: tokens.accent.teal }} />
        <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.78rem", color: tokens.text.primary }} noWrap>
          {name}
        </Typography>
        {payload.versionNumber !== undefined && (
          <Chip
            label={t("chat.pipeline.version", { version: payload.versionNumber })}
            size="small"
            sx={{ height: 18, fontSize: "0.62rem", bgcolor: tokens.bg.overlay, color: tokens.text.secondary }}
          />
        )}
        {disabled && (
          <Chip
            label={t("chat.pipeline.disabled")}
            size="small"
            sx={{ height: 18, fontSize: "0.62rem", bgcolor: `${tokens.accent.amber}1f`, color: tokens.accent.amber }}
          />
        )}
        <Box sx={{ flex: 1 }} />
        <Chip
          icon={<OpenInNewOutlined sx={{ fontSize: 12 }} />}
          label={t("chat.pipeline.open")}
          size="small"
          data-testid="chat-pipeline-graph-open"
          onClick={() => navigate("/pipelines")}
          sx={{
            height: 20, fontSize: "0.65rem", cursor: "pointer",
            bgcolor: tokens.primary.subtle, border: `1px solid ${tokens.primary.glow}`, color: tokens.primary.light,
            "&:hover": { bgcolor: tokens.primary.glow },
          }}
        />
      </Box>

      {/* Graph — scrolls horizontally rather than shrinking the node labels into illegibility */}
      <Box sx={{ overflowX: "auto", overflowY: "hidden", p: 1 }}>
        <Box sx={{ position: "relative", width: layout.width, height: layout.height, minWidth: layout.width }}>
          <svg
            data-testid="chat-pipeline-graph-edges"
            width={layout.width}
            height={layout.height}
            style={{ position: "absolute", inset: 0, pointerEvents: "none", overflow: "visible" }}
            aria-hidden="true"
          >
            {layout.edges.map((edge, i) => {
              const color = BRANCH_COLORS[edge.branch ?? ""] ?? tokens.border.strong;
              return (
                <g key={`${edge.source}-${edge.sourcePort}-${edge.target}-${edge.targetPort}-${i}`}>
                  <path d={edge.path} fill="none" stroke={color} strokeWidth={1.5} opacity={0.9} />
                  {edge.branch && (
                    <text
                      x={edge.labelX}
                      y={edge.labelY - 3}
                      textAnchor="middle"
                      fontSize={8}
                      fill={color}
                    >
                      {edge.branch}
                    </text>
                  )}
                </g>
              );
            })}
          </svg>

          {layout.nodes.map(node => {
            const color = categoryColor(node.category);
            return (
              <Tooltip
                key={node.id}
                title={node.kind ? `${node.label} · ${node.kind}` : node.label}
                placement="top"
              >
                <Box
                  data-testid="chat-pipeline-graph-node"
                  sx={{
                    position: "absolute",
                    left: node.x, top: node.y, width: node.width, height: node.height,
                    display: "flex", alignItems: "center", gap: 0.75,
                    px: 1,
                    borderRadius: tokens.radius.sm,
                    bgcolor: `${color}14`,
                    border: `1px solid ${color}55`,
                    overflow: "hidden",
                  }}
                >
                  <Box sx={{ width: 4, height: "62%", borderRadius: 2, bgcolor: color, flexShrink: 0 }} />
                  <Box sx={{ minWidth: 0 }}>
                    <Typography
                      variant="caption"
                      noWrap
                      display="block"
                      sx={{ fontSize: "0.68rem", fontWeight: 600, color: tokens.text.primary, lineHeight: 1.2 }}
                    >
                      {node.label}
                    </Typography>
                    {node.kind && (
                      <Typography
                        variant="caption"
                        noWrap
                        display="block"
                        sx={{ fontSize: "0.6rem", color: tokens.text.tertiary, lineHeight: 1.2 }}
                      >
                        {node.kind}
                      </Typography>
                    )}
                  </Box>
                </Box>
              </Tooltip>
            );
          })}
        </Box>
      </Box>

      {payload.truncated && (
        <Typography variant="caption" sx={{ px: 1.5, pb: 0.75, display: "block", color: tokens.text.tertiary, fontSize: "0.65rem" }}>
          {t("chat.pipeline.truncated")}
        </Typography>
      )}
    </Paper>
  );
}
