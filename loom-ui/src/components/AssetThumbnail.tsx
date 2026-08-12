import React, { useState } from "react";
import { Box } from "@mui/material";
import MediaPlaceholder from "./MediaPlaceholder";
import { AssetType } from "../types";

interface Props {
  type: AssetType;
  /** Preview URL; omit (or pass an empty string) when the asset has no renderable preview. */
  src?: string;
  /** Placeholder icon size in px, used when there is no preview or it fails to load. */
  iconSize?: number;
  alt?: string;
  /** How the image fills its box. Cards crop ("cover"); detail views usually want "contain". */
  fit?: "cover" | "contain";
}

/**
 * An asset preview that degrades to the type placeholder.
 *
 * A missing preview is the normal case — audio, PDFs and anything without a stored binary have
 * none — so a failed load is not an error worth surfacing: it falls back to
 * {@link MediaPlaceholder} silently.
 *
 * Video is previewed by the browser rather than by the server. There is no thumbnail service and
 * no poster-frame endpoint (LOOM_UI.md §7.2); a `<video>` with `preload="metadata"` and a `#t=`
 * fragment decodes one frame out of the same binary the `<img>` would have loaded, which the
 * range support on `/assets/:uuid/binary/data` serves without shipping the whole file. It is
 * muted and non-interactive: this is a tile, not a player.
 */
export default function AssetThumbnail({ type, src, iconSize = 40, alt = "", fit = "cover" }: Props) {
  const [failed, setFailed] = useState(false);

  if (!src || failed) {
    return <MediaPlaceholder type={type} iconSize={iconSize} />;
  }
  const sx = { position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: fit, display: "block" } as const;

  if (type === "video") {
    return (
      <Box
        component="video"
        // Seek off frame 0, which is often a fade-in or a black leader frame.
        src={`${src}#t=${VIDEO_POSTER_SECONDS}`}
        muted
        playsInline
        preload="metadata"
        aria-label={alt || undefined}
        onError={() => setFailed(true)}
        sx={{ ...sx, pointerEvents: "none", bgcolor: "#000" }}
      />
    );
  }
  return (
    <Box
      component="img"
      src={src}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
      sx={sx}
    />
  );
}

/** Where in a clip the tile's frame is taken from. */
const VIDEO_POSTER_SECONDS = 1;
