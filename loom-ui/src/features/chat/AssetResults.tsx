import { useState } from "react";
import { Box, Chip, Paper, Tooltip, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";

import { assetPosterUrl } from "../../api/assets";
import MediaPlaceholder from "../../components/MediaPlaceholder";
import { useMediaToken } from "../../hooks/useMediaToken";
import { tokens } from "../../theme";
import { AssetResultItem, AssetResultsPayload, AssetType } from "../../types";
import { assetTypeFromMime } from "../assets/assetMapping";
import { formatTimecode } from "../share/shareExpiry";

/**
 * The `asset-results` visual (CHAT.md §6.3): the result set of a search the agent ran, drawn twice
 * — as a compact strip under the answer, and as the contents of the workspace panel beside it.
 *
 * <p>Two renderings of one payload rather than two sources, and that is the point of the feature.
 * The panel used to list the newest assets in the catalogue no matter what the conversation was
 * about, so the two halves of the screen were about different things. Feeding both from the same
 * visual is what keeps the browser showing the assets that are actually being discussed.</p>
 */

/** Seconds from the milliseconds a transcript hit reports. */
function startSecondsOf(item: AssetResultItem): number | undefined {
  return item.timeFromMs === undefined || item.timeFromMs === null ? undefined : item.timeFromMs / 1000;
}

/**
 * A result tile's picture.
 *
 * <p>Served from the poster route for both video *and* image, which is not what the asset grid
 * does: the grid points an `<img>` at the stored binary, and that route wants an `Authorization`
 * header an `<img>` cannot send. The poster route takes the `?mt=` media token instead, is a few
 * kilobytes rather than the whole original, and answers for a still image too — ffmpeg fails the
 * seek and the server retries at zero.</p>
 */
function ResultThumb({ item, width }: { item: AssetResultItem; width: number }) {
  const type: AssetType = assetTypeFromMime(item.mimeType);
  const renderable = type === "video" || type === "image";
  const mediaToken = useMediaToken(renderable ? item.uuid : null);
  const [failed, setFailed] = useState(false);

  if (!renderable || !mediaToken || failed) {
    return <MediaPlaceholder type={type} iconSize={Math.round(width / 5)} />;
  }
  return (
    <Box
      component="img"
      src={assetPosterUrl(item.uuid, mediaToken, undefined, width * 2)}
      alt={item.title ?? item.uuid}
      loading="lazy"
      data-testid="chat-asset-result-thumb"
      onError={() => setFailed(true)}
      sx={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover", display: "block", bgcolor: "#000" }}
    />
  );
}

/** "harbour · 10 of about 412" — what was searched for, and how much of it is on screen. */
function ResultsHeading({ payload, shown }: { payload: AssetResultsPayload; shown: number }) {
  const { t } = useTranslation();
  const total = payload.total ?? shown;
  const count = total > shown
    ? t(payload.totalExact === false ? "chat.results.ofAbout" : "chat.results.of", { shown, total })
    : t("chat.results.count", { count: shown });
  return (
    <Box sx={{ display: "flex", alignItems: "baseline", gap: 0.75, minWidth: 0 }}>
      <Typography variant="caption" fontWeight={600} noWrap sx={{ fontSize: "0.76rem", color: tokens.text.primary }}>
        {payload.query || t("chat.results.untitled")}
      </Typography>
      <Typography variant="caption" noWrap sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }} data-testid="chat-asset-results-count">
        {count}
      </Typography>
    </Box>
  );
}

/**
 * The strip drawn under the answer.
 *
 * <p>Deliberately small and horizontal: it is a receipt for "these are the files I looked at", not
 * a browser. Clicking a tile opens the asset in the workspace panel, where there is room for it.</p>
 */
export function AssetResultsStrip({ payload, onSelect }: {
  payload: AssetResultsPayload;
  onSelect?: (uuid: string, startSeconds?: number) => void;
}) {
  const items = payload.items ?? [];
  if (items.length === 0) return null;

  return (
    <Paper
      elevation={0}
      data-testid="chat-asset-results"
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.md,
        overflow: "hidden",
        width: "100%",
        maxWidth: "100%",
      }}
    >
      <Box sx={{ px: 1.5, py: 0.9, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <ResultsHeading payload={payload} shown={items.length} />
      </Box>
      {/* Horizontal rather than wrapped: a wrapped grid of 24 tiles is taller than the answer above it. */}
      <Box sx={{ display: "flex", gap: 1, p: 1.25, overflowX: "auto", overflowY: "hidden" }}>
        {items.map(item => {
          const start = startSecondsOf(item);
          return (
            <Tooltip key={item.uuid} title={item.snippet ? `${item.title ?? ""} — ${item.snippet}` : item.title ?? item.uuid}>
              <Box
                role="button"
                data-testid="chat-asset-result"
                data-asset-uuid={item.uuid}
                onClick={() => onSelect?.(item.uuid, start)}
                sx={{
                  width: 104, flexShrink: 0, cursor: "pointer", borderRadius: tokens.radius.sm, overflow: "hidden",
                  border: `1px solid ${tokens.border.subtle}`,
                  "&:hover": { borderColor: tokens.primary.main },
                  transition: "border-color 120ms ease",
                }}
              >
                <Box sx={{ position: "relative", width: "100%", aspectRatio: "16 / 9", bgcolor: tokens.bg.overlay }}>
                  <ResultThumb item={item} width={104} />
                  {start !== undefined && (
                    <Chip
                      label={formatTimecode(start)}
                      size="small"
                      sx={{
                        position: "absolute", right: 3, bottom: 3, height: 15, fontSize: "0.58rem",
                        bgcolor: "rgba(0,0,0,0.72)", color: "#fff",
                      }}
                    />
                  )}
                </Box>
                <Typography variant="caption" noWrap display="block"
                  sx={{ px: 0.6, py: 0.4, fontSize: "0.66rem", color: tokens.text.secondary }}>
                  {item.title ?? item.uuid}
                </Typography>
              </Box>
            </Tooltip>
          );
        })}
      </Box>
    </Paper>
  );
}

/**
 * The same result set as the workspace panel's list — the sidebar half of "in sync with the
 * conversation".
 */
export function AssetResultsPanel({ payload, selectedUuid, onSelect }: {
  payload: AssetResultsPayload;
  selectedUuid?: string | null;
  onSelect?: (uuid: string, startSeconds?: number) => void;
}) {
  const { t } = useTranslation();
  const items = payload.items ?? [];

  return (
    <Box data-testid="chat-results-panel" sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <Box sx={{ px: 2, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <ResultsHeading payload={payload} shown={items.length} />
        {payload.criteria && (
          <Typography variant="caption" noWrap display="block" sx={{ fontSize: "0.66rem", color: tokens.text.tertiary }}>
            {payload.criteria}
          </Typography>
        )}
      </Box>
      <Box sx={{ flex: 1, overflow: "auto", p: 1 }}>
        {items.length === 0 ? (
          <Typography variant="caption" sx={{ px: 1, py: 1.5, display: "block", color: tokens.text.tertiary, fontSize: "0.72rem" }}>
            {t("chat.results.empty")}
          </Typography>
        ) : items.map(item => {
          const start = startSecondsOf(item);
          const selected = selectedUuid === item.uuid;
          return (
            <Box
              key={item.uuid}
              role="button"
              data-testid="chat-result-row"
              data-asset-uuid={item.uuid}
              onClick={() => onSelect?.(item.uuid, start)}
              sx={{
                display: "flex", alignItems: "center", gap: 1.25, px: 1, py: 0.75, mb: 0.25,
                borderRadius: tokens.radius.md, cursor: "pointer",
                bgcolor: selected ? tokens.primary.subtle : "transparent",
                border: `1px solid ${selected ? tokens.primary.main : "transparent"}`,
                "&:hover": { bgcolor: selected ? tokens.primary.subtle : tokens.bg.hover },
              }}
            >
              <Box sx={{ position: "relative", width: 64, height: 40, flexShrink: 0, borderRadius: tokens.radius.sm, overflow: "hidden", bgcolor: tokens.bg.overlay }}>
                <ResultThumb item={item} width={64} />
              </Box>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="caption" fontWeight={500} noWrap display="block" sx={{ fontSize: "0.76rem", color: tokens.text.primary }}>
                  {item.title ?? item.uuid}
                </Typography>
                <Typography variant="caption" noWrap display="block" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>
                  {item.snippet ?? item.mimeType ?? ""}
                </Typography>
              </Box>
              {start !== undefined && (
                <Chip label={formatTimecode(start)} size="small"
                  sx={{ height: 17, fontSize: "0.6rem", bgcolor: tokens.bg.overlay, color: tokens.text.secondary }} />
              )}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}
