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
 * A missing preview is the normal case — most assets have no stored binary, and formats a
 * browser cannot decode (video, audio, PDF) never get one — so a failed load is not an error
 * worth surfacing: it falls back to {@link MediaPlaceholder} silently.
 */
export default function AssetThumbnail({ type, src, iconSize = 40, alt = "", fit = "cover" }: Props) {
  const [failed, setFailed] = useState(false);

  if (!src || failed) {
    return <MediaPlaceholder type={type} iconSize={iconSize} />;
  }
  return (
    <Box
      component="img"
      src={src}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
      sx={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: fit, display: "block" }}
    />
  );
}
