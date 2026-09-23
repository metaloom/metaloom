import { useEffect, useMemo, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { Box, Chip, IconButton, Paper, Tooltip, Typography } from "@mui/material";
import { CloseOutlined, OpenInNewOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";

import { assetStreamUrl } from "../../api/assets";
import MediaPlaceholder from "../../components/MediaPlaceholder";
import { AssetVideoPlayer, AssetVideoPlayerHandle } from "../../components/AssetVideoPlayer";
import { useAuthedImage } from "../../hooks/useAuthedImage";
import { useMediaInfo } from "../../hooks/useMediaInfo";
import { useMediaToken } from "../../hooks/useMediaToken";
import { tokens } from "../../theme";
import { AssetType, AssetViewerPayload } from "../../types";
import { formatTimecode } from "../share/shareExpiry";

/**
 * One asset, embedded and playable, inside the conversation — the `asset-viewer` visual produced by
 * the `show_asset` tool (CHAT.md §6.2).
 *
 * <p>Everything else the agent returns about media is prose about files the user cannot see; a
 * reference chip is a name and an icon, which answers "which file" and never "is this the right
 * one". Deciding that means looking, so this is a real player rather than a thumbnail: the same
 * {@link AssetVideoPlayer} the asset detail view uses, over the same on-demand remux, because a
 * `<video src={binary}>` would hand the browser a Matroska original no browser decodes.</p>
 *
 * <p>Also used by the chat workspace panel to preview whichever asset is selected there, which is
 * why the payload comes in as data rather than being fetched here — the panel has the asset loaded
 * already, and a second round trip per selection would be for nothing.</p>
 */

/** Height of the picture. A card in a transcript is a preview, not a cinema; the detail view is one click away. */
const MEDIA_MAX_HEIGHT = 300;

/**
 * {@link MediaPlaceholder} paints itself `position: absolute; inset: 0`, so it needs a sized,
 * positioned box of its own — dropped straight into the flex row it collapses to nothing.
 */
function Placeholder({ type }: { type: AssetType }) {
  return (
    <Box sx={{ position: "relative", width: "100%", height: 140 }}>
      <MediaPlaceholder type={type} iconSize={32} />
    </Box>
  );
}

function placeholderTypeOf(kind: AssetViewerPayload["kind"]): AssetType {
  switch (kind) {
    case "video": return "video";
    case "audio": return "audio";
    case "image": return "image";
    case "document": return "document";
    default: return "unknown";
  }
}

export default function AssetViewerCard({ payload, onClose, testId = "chat-asset-viewer" }: {
  payload: AssetViewerPayload;
  /** Rendered as a dismiss button when given — the workspace panel uses it to go back to the list. */
  onClose?: () => void;
  testId?: string;
}) {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const playerRef = useRef<AssetVideoPlayerHandle>(null);
  const uuid = payload.assetUuid;
  const kind = payload.kind ?? "other";

  const isVideo = kind === "video";
  const isAudio = kind === "audio";
  const isImage = kind === "image";

  // Probed only for what needs it: the player cannot draw a timeline without a duration, and
  // nothing else on this card cares how long the file is.
  const mediaInfo = useMediaInfo(isVideo ? uuid : null);
  const imageUrl = useAuthedImage(isImage ? uuid : null);
  const mediaToken = useMediaToken(isAudio ? uuid : null);
  const audioUrl = isAudio && mediaToken ? assetStreamUrl(uuid, mediaToken, payload.startSeconds) : null;

  const start = Number.isFinite(payload.startSeconds) && (payload.startSeconds ?? 0) > 0 ? payload.startSeconds! : 0;

  /**
   * Open the player where the model said, once.
   *
   * Guarded by a ref rather than re-run on every render: the effect has to wait for the duration to
   * arrive, and without the guard every later probe or re-render would drag the viewer back to the
   * agent's timestamp after they had scrubbed away from it.
   */
  const seeked = useRef(false);
  useEffect(() => {
    if (!isVideo || start <= 0 || seeked.current) return;
    if (!(mediaInfo?.duration ?? 0)) return;
    seeked.current = true;
    playerRef.current?.seekTo(start);
  }, [isVideo, start, mediaInfo?.duration]);

  // A new asset in the same card slot is a new viewer; without this the seek guard of the previous
  // one would suppress the jump the new payload asked for.
  useEffect(() => { seeked.current = false; }, [uuid, start]);

  const openHref = useMemo(() => (start > 0 ? `/assets/${uuid}?t=${start}` : `/assets/${uuid}`), [uuid, start]);
  const filename = payload.filename || uuid;

  return (
    <Paper
      elevation={0}
      data-testid={testId}
      data-asset-uuid={uuid}
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.md,
        overflow: "hidden",
        width: "100%",
        maxWidth: "100%",
      }}
    >
      {/* Header */}
      <Box sx={{ px: 1.5, py: 1, display: "flex", alignItems: "center", gap: 1, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="caption" fontWeight={600} noWrap display="block"
            sx={{ fontSize: "0.78rem", color: tokens.text.primary }} data-testid="chat-asset-viewer-name">
            {filename}
          </Typography>
          {payload.mimeType && (
            <Typography variant="caption" noWrap display="block" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>
              {payload.mimeType}
            </Typography>
          )}
        </Box>
        {start > 0 && (
          <Chip
            label={formatTimecode(start)}
            size="small"
            data-testid="chat-asset-viewer-start"
            sx={{ height: 18, fontSize: "0.62rem", bgcolor: tokens.bg.overlay, color: tokens.text.secondary }}
          />
        )}
        <Tooltip title={t("chat.viewer.open")}>
          <IconButton size="small" data-testid="chat-asset-viewer-open" onClick={() => navigate(openHref)}
            sx={{ color: tokens.text.tertiary, "&:hover": { color: tokens.primary.light } }}>
            <OpenInNewOutlined sx={{ fontSize: 15 }} />
          </IconButton>
        </Tooltip>
        {onClose && (
          <IconButton size="small" data-testid="chat-asset-viewer-close" onClick={onClose} sx={{ color: tokens.text.tertiary }}>
            <CloseOutlined sx={{ fontSize: 15 }} />
          </IconButton>
        )}
      </Box>

      {/* Media */}
      <Box sx={{ bgcolor: "#000", display: "flex", alignItems: "center", justifyContent: "center", minHeight: isAudio ? 0 : 140 }}>
        {isVideo ? (
          // The remuxed stream and our own transport — see AssetVideoPlayer. The aspect ratio goes on
          // the player itself: against a parent whose height comes from an aspect ratio, a percentage
          // height degrades to auto and the picture collapses to the control bar.
          <AssetVideoPlayer
            ref={playerRef}
            assetUuid={uuid}
            duration={mediaInfo?.duration ?? 0}
            testId="chat-asset-video"
            sx={{ width: "100%", aspectRatio: "16 / 9", maxHeight: MEDIA_MAX_HEIGHT }}
          />
        ) : isImage ? (
          imageUrl ? (
            <Box component="img" src={imageUrl} alt={filename} data-testid="chat-asset-image"
              sx={{ display: "block", maxWidth: "100%", maxHeight: MEDIA_MAX_HEIGHT, objectFit: "contain" }} />
          ) : (
            // Null while loading and null on failure, and the two look the same on purpose: a
            // catalogue that indexes files by reference has assets whose bytes are simply not here.
            <Placeholder type="image" />
          )
        ) : isAudio ? (
          audioUrl ? (
            <Box component="audio" controls src={audioUrl} data-testid="chat-asset-audio" sx={{ width: "100%", px: 1.5, py: 1 }} />
          ) : (
            <Placeholder type="audio" />
          )
        ) : (
          <Box sx={{ width: "100%" }} data-testid="chat-asset-nopreview">
            <Placeholder type={placeholderTypeOf(kind)} />
          </Box>
        )}
      </Box>

      {/* Caption — why this asset is on screen, in the model's words */}
      {payload.caption && (
        <Typography variant="caption" data-testid="chat-asset-viewer-caption"
          sx={{ display: "block", px: 1.5, py: 0.75, fontSize: "0.72rem", color: tokens.text.secondary }}>
          {payload.caption}
        </Typography>
      )}
    </Paper>
  );
}
