import React from "react";
import {
  Box, Chip, Dialog, IconButton, Tab, Tabs, Tooltip, Typography,
} from "@mui/material";
import { CloseOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { contentTypeColor } from "./contentTypes";
import { detectionRegions, payloadToMarkdownTable, previewKind, stringifyValue } from "./resultRenderers";
import MarkdownContent from "../chat/MarkdownContent";
import { previewSrc } from "../../api/pipelines";
import type { NodePreviewMeta, PipelineNodeTaskRecord, PortPayload } from "../../api/pipelines";

/**
 * The enlarged view of one output port.
 *
 * Everything a node emitted is *some* JSON, so Raw is always available and always correct. The
 * other tabs are offered only when they can say more than Raw can: an image when the worker sent
 * one back, Markdown when the node described its own output or the payload tabulates cleanly,
 * a player when the value is playable media.
 *
 * Tabs are chosen by what is actually present rather than by content-type family alone, because
 * the family says what a port *declares* and the payload says what it *carried* — a `media/*`
 * port on an item that produced nothing has no player to offer.
 */

export interface NodeResultDetailProps {
  open: boolean;
  onClose: () => void;
  task: PipelineNodeTaskRecord | null;
  portId: string | null;
  payload: PortPayload | null;
  preview?: NodePreviewMeta;
  /** Label of the node that produced this, for the header. */
  nodeLabel?: string;
}

type ViewerId = "image" | "markdown" | "media" | "json" | "raw";

interface Viewer {
  id: ViewerId;
  label: string;
}

/** Extensions we are willing to hand to a `<video>` / `<audio>` element. */
const VIDEO_EXT = /\.(mp4|webm|ogv|mov|m4v)$/i;
const AUDIO_EXT = /\.(mp3|wav|ogg|oga|flac|m4a|aac)$/i;

function mediaKind(value: unknown): "video" | "audio" | null {
  if (typeof value !== "string") return null;
  if (VIDEO_EXT.test(value)) return "video";
  if (AUDIO_EXT.test(value)) return "audio";
  return null;
}

/**
 * Whether a table would say more than the value already does.
 *
 * A single scalar tabulates into one cell, which is strictly worse than reading it — so the tab
 * is offered only for a sequence, or for a record whose field names are themselves the point.
 */
function tabulates(payload: PortPayload | null): boolean {
  const elements = payload?.elements ?? [];
  if (elements.length === 0) return false;
  if (elements.length > 1) return true;
  const only = elements[0].value;
  return only !== null && typeof only === "object";
}

/**
 * Which viewers this port can offer, best first.
 *
 * The order is the offer order: whatever comes first is what opens, so the most informative
 * available rendering wins without the caller having to decide.
 */
export function viewersFor(
  payload: PortPayload | null,
  preview: NodePreviewMeta | undefined,
  t: (key: string) => string,
): Viewer[] {
  const viewers: Viewer[] = [];
  const elements = payload?.elements ?? [];
  const first = elements[0]?.value;

  if (preview?.url) {
    viewers.push({ id: "image", label: t("pipeline.resultDetail.tab.image") });
  }
  // A node's own description beats every default, so it opens first when it exists.
  if (preview?.markdown) {
    viewers.unshift({ id: "markdown", label: t("pipeline.resultDetail.tab.markdown") });
  } else if (tabulates(payload) && payloadToMarkdownTable(payload)) {
    viewers.push({ id: "markdown", label: t("pipeline.resultDetail.tab.table") });
  }
  if (mediaKind(first) && previewKind(payload?.contentType) === "media") {
    viewers.push({ id: "media", label: t("pipeline.resultDetail.tab.media") });
  }
  if (elements.length > 0) {
    viewers.push({ id: "json", label: t("pipeline.resultDetail.tab.json") });
  }
  // Always last, always present: whatever else failed to apply, the payload is still readable.
  viewers.push({ id: "raw", label: t("pipeline.resultDetail.tab.raw") });
  return viewers;
}

/** Colourised JSON, mirroring the editor's own JSON tab rather than introducing a second style. */
function syntaxHighlight(json: string): string {
  const escaped = json
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  return escaped.replace(
    /("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+-]?\d+)?)/g,
    (match) => {
      let cls = "json-number";
      if (/^"/.test(match)) cls = /:$/.test(match) ? "json-key" : "json-string";
      else if (/true|false/.test(match)) cls = "json-boolean";
      else if (/null/.test(match)) cls = "json-null";
      return `<span class="${cls}">${match}</span>`;
    },
  );
}

const JSON_STYLES = {
  fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
  fontSize: "0.75rem",
  lineHeight: 1.6,
  whiteSpace: "pre-wrap" as const,
  wordBreak: "break-word" as const,
  m: 0,
  color: tokens.text.secondary,
  "& .json-key": { color: tokens.accent.blue },
  "& .json-string": { color: tokens.accent.green },
  "& .json-number": { color: tokens.accent.amber },
  "& .json-boolean": { color: tokens.accent.teal },
  "& .json-null": { color: tokens.text.tertiary },
};

export default function NodeResultDetail({
  open, onClose, task, portId, payload, preview, nodeLabel,
}: NodeResultDetailProps) {
  const { t } = useTranslation();
  const viewers = React.useMemo(() => viewersFor(payload, preview, t), [payload, preview, t]);
  const [active, setActive] = React.useState(0);

  // A different port is a different set of viewers, so the selection cannot carry over.
  React.useEffect(() => { setActive(0); }, [portId, task?.uuid]);

  if (!task || !portId || !payload) return null;

  const viewer = viewers[Math.min(active, viewers.length - 1)];
  const elements = payload.elements ?? [];
  const origin = elements[0]?.origin;
  const color = contentTypeColor(payload.contentType, tokens.text.tertiary);

  // Boxes over the preview, for a port whose elements carry them. `detectionRegions` drops any
  // element that does not know the dimensions its pixels were measured against, so a port with
  // nothing to draw simply draws nothing.
  const regions = previewKind(payload.contentType) === "detection"
    ? detectionRegions(elements.map(element => element.value))
    : [];
  // The box outline reads against both the amber of a held node and whatever is in the picture;
  // the family colour would put a pink box on a face and call it a label.
  const boxColor = tokens.accent.green;

  // Per-element previews are keyed `portId#seq` in the same map as the port-level one — see
  // `NodeContext.preview(port, elementSeq, preview)`.
  const elementPreviews = elements
    .map((_, seq) => ({ seq, meta: task.previews?.[`${portId}#${seq}`] }))
    .filter((entry): entry is { seq: number; meta: NodePreviewMeta } => Boolean(entry.meta?.url));

  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullWidth
      maxWidth="lg"
      data-testid="pipeline-result-detail"
      PaperProps={{
        sx: {
          bgcolor: tokens.bg.panel,
          border: `1px solid ${tokens.border.default}`,
          borderRadius: tokens.radius.lg,
          backgroundImage: "none",
          height: "80vh",
        },
      }}
    >
      {/* Header: what you are looking at, and where it came from. */}
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: color, flexShrink: 0 }} />
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="subtitle2" fontWeight={700} noWrap>
            {nodeLabel ?? task.nodeId} · {portId}
          </Typography>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontFamily: "monospace" }} noWrap>
            {payload.contentType} · {payload.cardinality}
            {elements.length > 1 ? ` · ${elements.length} elements` : ""}
            {/* Only meaningful for a fanned-out node, and misleading noise otherwise. */}
            {origin && origin.total != null && origin.total > 1
              ? ` · element ${origin.seq} of ${origin.total}` : ""}
            {task.durationMs != null ? ` · ${task.durationMs}ms` : ""}
          </Typography>
        </Box>
        <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: 1 }}>
          <Chip
            label={task.state}
            size="small"
            sx={{ height: 18, fontSize: "0.6rem", fontWeight: 700, bgcolor: tokens.bg.overlay }}
          />
          <IconButton size="small" onClick={onClose} aria-label="close" data-testid="pipeline-result-detail-close">
            <CloseOutlined sx={{ fontSize: 18 }} />
          </IconButton>
        </Box>
      </Box>

      {viewers.length > 1 && (
        <Tabs
          value={Math.min(active, viewers.length - 1)}
          onChange={(_, v) => setActive(v)}
          sx={{ minHeight: 34, borderBottom: `1px solid ${tokens.border.subtle}`, px: 1 }}
        >
          {viewers.map(v => (
            <Tab key={v.id} label={v.label} data-testid={`result-tab-${v.id}`} sx={{ fontSize: "0.72rem", minHeight: 34, py: 0.5, minWidth: 64 }} />
          ))}
        </Tabs>
      )}

      <Box sx={{ flex: 1, overflow: "auto", p: 2 }} data-testid={`result-view-${viewer.id}`}>
        {viewer.id === "image" && preview?.url && (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 1 }}>
            {/* `inline-block` so the wrapper shrinks to the letterboxed image rather than to the
                column: the boxes are positioned in percentages of *the image*, and a wrapper wider
                than the picture would slide every one of them sideways. */}
            <Box sx={{ position: "relative", display: "inline-block", maxWidth: "100%" }}>
              <Box
                component="img"
                src={previewSrc(preview.url)}
                alt={portId}
                sx={{ display: "block", maxWidth: "100%", maxHeight: "60vh", objectFit: "contain", borderRadius: tokens.radius.md }}
              />
              {regions.map(region => (
                <Box
                  key={region.id}
                  data-testid={`result-detection-box-${region.id}`}
                  sx={{
                    position: "absolute",
                    left: `${region.x * 100}%`,
                    top: `${region.y * 100}%`,
                    width: `${region.width * 100}%`,
                    height: `${region.height * 100}%`,
                    border: `2px solid ${boxColor}`,
                    borderRadius: "2px",
                    boxShadow: "0 0 0 1px rgba(0,0,0,0.45)",
                    pointerEvents: "none",
                  }}
                >
                  {region.label && (
                    <Typography
                      variant="caption"
                      sx={{
                        position: "absolute", top: 0, left: 0, transform: "translateY(-100%)",
                        px: 0.5, fontSize: "0.6rem", fontWeight: 700, whiteSpace: "nowrap",
                        bgcolor: boxColor, color: tokens.text.inverse, borderRadius: "2px 2px 0 0",
                      }}
                    >
                      {region.label}
                    </Typography>
                  )}
                </Box>
              ))}
            </Box>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
              {/* Say it plainly: this is a reduced copy, not the artifact itself. */}
              {t("pipeline.resultDetail.previewNote")}
              {preview.width ? ` (${preview.width}×${preview.height})` : ""}
            </Typography>

            {elementPreviews.length > 0 && (
              <Box
                data-testid="result-element-previews"
                sx={{ display: "flex", flexWrap: "wrap", justifyContent: "center", gap: 1, mt: 1 }}
              >
                {/* One thumbnail per element, cut from the source at full resolution rather than
                    from the reduced copy above — at 512px on the longest edge a face in a 4K frame
                    would be some 30 pixels across. */}
                {elementPreviews.map(({ seq, meta }) => (
                  <Box key={seq} sx={{ textAlign: "center" }}>
                    <Box
                      component="img"
                      src={previewSrc(meta.url!)}
                      alt={`${portId} ${seq}`}
                      data-testid={`result-element-preview-${seq}`}
                      sx={{
                        display: "block", height: 72, borderRadius: tokens.radius.sm,
                        border: `1px solid ${tokens.border.default}`,
                      }}
                    />
                    <Typography variant="caption" sx={{ fontSize: "0.6rem", color: tokens.text.tertiary }}>
                      {seq}
                    </Typography>
                  </Box>
                ))}
              </Box>
            )}
          </Box>
        )}

        {viewer.id === "markdown" && (
          <MarkdownContent content={preview?.markdown || payloadToMarkdownTable(payload)} />
        )}

        {viewer.id === "media" && (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 1 }}>
            {/* The value is a path on the machine that holds it, which the browser cannot open.
                Saying so beats a permanently broken player. */}
            <Typography variant="body2" sx={{ color: tokens.text.secondary }}>
              {t("pipeline.resultDetail.mediaUnavailable")}
            </Typography>
            <Typography
              variant="caption"
              data-testid="result-media-path"
              sx={{ fontFamily: "monospace", color: tokens.text.tertiary, wordBreak: "break-all" }}
            >
              {stringifyValue(elements[0]?.value)}
            </Typography>
          </Box>
        )}

        {viewer.id === "json" && (
          <Typography
            component="pre"
            sx={JSON_STYLES}
            dangerouslySetInnerHTML={{
              __html: syntaxHighlight(JSON.stringify(
                elements.length === 1 ? elements[0].value : elements.map(e => e.value), null, 2)),
            }}
          />
        )}

        {viewer.id === "raw" && (
          <Typography component="pre" sx={JSON_STYLES}>
            {JSON.stringify(payload, null, 2)}
          </Typography>
        )}

        {preview?.skippedReason && viewer.id !== "raw" && (
          <Tooltip title={preview.skippedReason}>
            <Typography
              variant="caption"
              data-testid="result-preview-skipped"
              sx={{ display: "block", mt: 2, color: tokens.text.tertiary }}
            >
              {t("pipeline.resultDetail.previewSkipped")}: {preview.skippedReason}
            </Typography>
          </Tooltip>
        )}
      </Box>
    </Dialog>
  );
}
