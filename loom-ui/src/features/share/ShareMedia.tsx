import React, { useEffect, useRef, useState } from "react";
import { Box, Typography } from "@mui/material";
import InsertDriveFileOutlined from "@mui/icons-material/InsertDriveFileOutlined";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { sharedBinaryUrl, type SharedAssetResponse } from "../../api/shares";
import { mediaKindOf } from "./shareExpiry";

export interface ShareMediaHandle {
  /** Current playback position in seconds, or undefined for media that does not have one. */
  currentTime: () => number | undefined;
  /** Move the playhead. */
  seekTo: (seconds: number) => void;
}

/**
 * The media itself.
 *
 * **This is the first real player in the application.** The internal asset detail screen has never
 * had one — its `videoRef` is unattached and its "playback" is a `setInterval` that advances a
 * number — so there was nothing to copy. A plain `<video controls>` is enough: the browser's own
 * controls are better than anything hand-rolled here, they are keyboard accessible, and seeking
 * works because the share binary route honours `Range` and answers 206.
 *
 * The `src` carries no token. Media elements cannot set a header, so they authenticate with the
 * `loom_share_session` cookie the session route sets — which is also why that cookie exists.
 */
const ShareMedia = React.forwardRef<ShareMediaHandle, {
  slug: string;
  asset: SharedAssetResponse;
  onTimeUpdate?: (seconds: number) => void;
  onDurationChange?: (seconds: number) => void;
}>(function ShareMedia({ slug, asset, onTimeUpdate, onDurationChange }, ref) {
  const { t } = useTranslation();
  const mediaRef = useRef<HTMLVideoElement | HTMLAudioElement | null>(null);
  const [failed, setFailed] = useState(false);
  const kind = mediaKindOf(asset.mimeType);
  const src = sharedBinaryUrl(slug, asset.uuid);

  React.useImperativeHandle(ref, () => ({
    currentTime: () => mediaRef.current?.currentTime,
    seekTo: (seconds: number) => {
      if (mediaRef.current) {
        mediaRef.current.currentTime = seconds;
      }
    },
  }));

  // A new asset means a new source; without this the element keeps the previous one's position and
  // a click through a collection lands mid-clip.
  useEffect(() => {
    setFailed(false);
    if (mediaRef.current) {
      mediaRef.current.load();
    }
  }, [asset.uuid]);

  const frame = {
    width: "100%",
    maxHeight: "72vh",
    borderRadius: tokens.radius.md,
    background: "#000",
    display: "block",
  } as const;

  if (failed || kind === "other") {
    return (
      <Box
        data-testid="share-media-unavailable"
        sx={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          gap: 1,
          minHeight: 240,
          borderRadius: tokens.radius.md,
          bgcolor: tokens.bg.surface,
          border: `1px solid ${tokens.border.subtle}`,
          color: tokens.text.tertiary,
        }}
      >
        <InsertDriveFileOutlined sx={{ fontSize: 48 }} />
        <Typography variant="body2">{t("share.media.noPreview")}</Typography>
      </Box>
    );
  }

  if (kind === "video") {
    return (
      <video
        ref={mediaRef as React.RefObject<HTMLVideoElement>}
        data-testid="share-media-video"
        src={src}
        controls
        playsInline
        preload="metadata"
        style={frame}
        onError={() => setFailed(true)}
        onTimeUpdate={(e) => onTimeUpdate?.((e.target as HTMLVideoElement).currentTime)}
        onLoadedMetadata={(e) => onDurationChange?.((e.target as HTMLVideoElement).duration)}
      />
    );
  }

  if (kind === "audio") {
    return (
      <Box sx={{ p: 3, borderRadius: tokens.radius.md, bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` }}>
        <audio
          ref={mediaRef as React.RefObject<HTMLAudioElement>}
          data-testid="share-media-audio"
          src={src}
          controls
          preload="metadata"
          style={{ width: "100%" }}
          onError={() => setFailed(true)}
          onTimeUpdate={(e) => onTimeUpdate?.((e.target as HTMLAudioElement).currentTime)}
          onLoadedMetadata={(e) => onDurationChange?.((e.target as HTMLAudioElement).duration)}
        />
      </Box>
    );
  }

  if (kind === "pdf") {
    // An <object> rather than an <iframe>: it degrades to its own children when the browser has no
    // PDF viewer, which is the difference between a fallback and a blank rectangle.
    return (
      <object data={src} type="application/pdf" data-testid="share-media-pdf" style={{ ...frame, height: "72vh" }}>
        <Typography variant="body2" sx={{ color: tokens.text.secondary, p: 2 }}>
          {t("share.media.noPreview")}
        </Typography>
      </object>
    );
  }

  return (
    <Box
      component="img"
      data-testid="share-media-image"
      src={src}
      alt={asset.title || asset.filename}
      onError={() => setFailed(true)}
      sx={{ ...frame, objectFit: "contain", maxHeight: "72vh", mx: "auto" }}
    />
  );
});

export default ShareMedia;
