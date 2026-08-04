import React from "react";
import { Box, Typography, Tooltip } from "@mui/material";
import { tokens } from "../../theme";
import { contentTypeColor } from "./contentTypes";
import { summarizePayload } from "./resultRenderers";
import { previewSrc } from "../../api/pipelines";
import type { NodePreviewMeta, PipelineNodeTaskRecord, PortPayload } from "../../api/pipelines";

/**
 * The compact per-port result readout drawn on a node card in debug mode.
 *
 * One line per output port: a family-coloured dot matching the port's own handle, the port id,
 * and a one-line summary of what it carried. Deliberately capped — a fanned-out graph can put
 * a dozen ports on a node and the card has to stay a card.
 *
 * Clicking a row raises the port for the detail view; the card itself stays draggable because
 * the handler stops propagation only on the row.
 */

/** Ports shown before the strip collapses into a "+n more" chip. */
const MAX_ROWS = 3;

export interface NodeResultStripProps {
  /** Every execution of this node for the inspected item — more than one when the node fanned out. */
  tasks: PipelineNodeTaskRecord[];
  onSelectPort?: (task: PipelineNodeTaskRecord, portId: string, payload: PortPayload) => void;
}

/**
 * Merge the executions of one node into the ports to show.
 *
 * A fanned-out node has one task per element, each with the same port ids. Showing every task
 * would repeat the port list N times, so the ports come from the first task that wrote anything
 * and the element counts are summed across all of them — which is what someone reading "how many
 * faces did this find" actually wants.
 */
interface PortEntry {
  portId: string;
  payload: PortPayload;
  task: PipelineNodeTaskRecord;
  totalElements: number;
  preview?: NodePreviewMeta;
}

function collectPorts(tasks: PipelineNodeTaskRecord[]): PortEntry[] {
  const byPort = new Map<string, PortEntry>();
  for (const task of tasks) {
    for (const [portId, payload] of Object.entries(task.outputs ?? {})) {
      const existing = byPort.get(portId);
      if (existing) {
        existing.totalElements += payload.elements?.length ?? 0;
        // A fanned-out node has a preview per element; the first one that has bytes wins,
        // because the strip shows a single thumbnail per port.
        existing.preview ??= task.previews?.[portId];
      } else {
        byPort.set(portId, {
          portId, payload, task,
          totalElements: payload.elements?.length ?? 0,
          preview: task.previews?.[portId],
        });
      }
    }
  }
  return [...byPort.values()];
}

export default function NodeResultStrip({ tasks, onSelectPort }: NodeResultStripProps) {
  const ports = React.useMemo(() => collectPorts(tasks), [tasks]);
  if (ports.length === 0) return null;

  const shown = ports.slice(0, MAX_ROWS);
  const hidden = ports.length - shown.length;

  return (
    <Box
      data-testid="node-result-strip"
      sx={{
        px: 1.25, pb: 0.75,
        display: "flex", flexDirection: "column", gap: 0.25,
        borderTop: `1px dashed ${tokens.border.subtle}`,
        pt: 0.5, mt: 0.25,
      }}
    >
      {shown.map(({ portId, payload, task, totalElements, preview }) => {
        const summary = summarizePayload(payload);
        const color = contentTypeColor(payload.contentType, tokens.text.tertiary);
        // The strip sums elements across a fan-out, so the count shown can exceed what any
        // single task carried. Label it from the total, not from the sampled payload.
        const suffix = summary.many || totalElements > 1 ? ` ×${totalElements}` : "";
        return (
          <Tooltip
            key={portId}
            title={`${portId} · ${payload.contentType} · ${payload.cardinality}`}
            placement="right"
          >
            <Box
              data-testid={`node-result-port-${portId}`}
              data-content-type={payload.contentType}
              data-empty={summary.empty ? "true" : "false"}
              onClick={onSelectPort ? (e) => { e.stopPropagation(); onSelectPort(task, portId, payload); } : undefined}
              sx={{
                display: "flex", alignItems: "center", gap: 0.5,
                cursor: onSelectPort ? "pointer" : "default",
                borderRadius: tokens.radius.sm,
                px: 0.25,
                "&:hover": onSelectPort ? { bgcolor: tokens.bg.hover } : undefined,
              }}
            >
              {preview?.url ? (
                // The only way to see media a node *produced*: the port itself carries a path on
                // the worker that made it, which the browser cannot resolve.
                <Box
                  component="img"
                  data-testid={`node-result-thumb-${portId}`}
                  src={previewSrc(preview.url)}
                  alt=""
                  loading="lazy"
                  sx={{
                    width: 28, height: 20, objectFit: "cover", flexShrink: 0,
                    borderRadius: tokens.radius.sm, border: `1px solid ${color}66`,
                  }}
                />
              ) : (
                <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: color, flexShrink: 0 }} />
              )}
              <Typography sx={{ fontSize: "0.6rem", color: tokens.text.tertiary, flexShrink: 0 }}>
                {portId}
              </Typography>
              <Typography
                sx={{
                  fontSize: "0.6rem", fontFamily: "monospace",
                  color: summary.empty ? tokens.text.tertiary : tokens.text.secondary,
                  overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
                }}
              >
                {summary.label}{suffix}
              </Typography>
              {preview?.skippedReason && (
                // "Too large to preview" is a different statement from "this port emitted
                // nothing", and conflating them is what makes a debugging view untrustworthy.
                <Tooltip title={preview.skippedReason}>
                  <Box
                    component="span"
                    data-testid={`node-result-preview-skipped-${portId}`}
                    sx={{ fontSize: "0.55rem", color: tokens.text.tertiary, flexShrink: 0 }}
                  >
                    ⃠
                  </Box>
                </Tooltip>
              )}
            </Box>
          </Tooltip>
        );
      })}
      {hidden > 0 && (
        <Typography data-testid="node-result-more" sx={{ fontSize: "0.58rem", color: tokens.text.tertiary, pl: 1 }}>
          +{hidden} more
        </Typography>
      )}
    </Box>
  );
}
