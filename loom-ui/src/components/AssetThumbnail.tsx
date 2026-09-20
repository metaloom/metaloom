import React, { useState } from "react";
import { Box } from "@mui/material";
import MediaPlaceholder from "./MediaPlaceholder";
import { AssetType } from "../types";
import { assetPosterUrl } from "../api/assets";
import { useMediaToken } from "../hooks/useMediaToken";

interface Props {
  type: AssetType;
  /** Preview URL for an image; omit (or pass an empty string) when the asset has no renderable preview. */
  src?: string;
  /**
   * The asset a video tile should ask the server for a poster frame of.
   *
   * Required for video. Without it a video tile falls back to the placeholder rather than
   * downloading the original, which is what it used to do.
   */
  assetUuid?: string;
  /** Placeholder icon size in px, used when there is no preview or it fails to load. */
  iconSize?: number;
  alt?: string;
  /** How the image fills its box. Cards crop ("cover"); detail views usually want "contain". */
  fit?: "cover" | "contain";
  /** Width to request the poster at, so a grid tile does not pull a 1080p frame. */
  posterWidth?: number;
}

/**
 * An asset preview that degrades to the type placeholder.
 *
 * A missing preview is the normal case — audio, PDFs and anything without a stored binary have
 * none — so a failed load is not an error worth surfacing: it falls back to
 * {@link MediaPlaceholder} silently.
 *
 * **Video is a server-rendered poster frame.** It used to be a muted `<video src={binary}#t=1>`,
 * which asked the browser to decode a frame out of the original file. That is wrong twice over: it
 * opens a connection to a multi-gigabyte asset to draw a 180px tile, and no browser decodes
 * Matroska at all — so the tile that was supposed to save the download did the download *and*
 * showed the placeholder anyway. `GET /assets/:uuid/poster` returns a few KB of JPEG instead, and
 * carries a short-lived media token because an `<img>` cannot send an Authorization header.
 */
export default function AssetThumbnail({
  type, src, assetUuid, iconSize = 40, alt = "", fit = "cover", posterWidth = 480,
}: Props) {
  const [failed, setFailed] = useState(false);
  // Only minted for video: an image tile serves straight from the binary route.
  const mediaToken = useMediaToken(type === "video" ? assetUuid : null);

  const posterSrc = type === "video" && assetUuid && mediaToken
    ? assetPosterUrl(assetUuid, mediaToken, undefined, posterWidth)
    : null;
  const effectiveSrc = type === "video" ? posterSrc : src;

  if (!effectiveSrc || failed) {
    return <MediaPlaceholder type={type} iconSize={iconSize} />;
  }

  const sx = { position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: fit, display: "block" } as const;
  return (
    <Box
      component="img"
      src={effectiveSrc}
      alt={alt}
      loading="lazy"
      data-testid={type === "video" ? "asset-poster" : undefined}
      onError={() => setFailed(true)}
      sx={type === "video" ? { ...sx, bgcolor: "#000" } : sx}
    />
  );
}
