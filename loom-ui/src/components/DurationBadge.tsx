import React from "react";
import { Box } from "@mui/material";

import { tokens } from "../theme";

/** h:mm:ss, always — a badge on a tile is read at a glance and a changing shape is harder to scan. */
export function clockHMS(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds));
  const h = Math.floor(whole / 3600);
  const m = Math.floor((whole % 3600) / 60);
  const s = whole % 60;
  return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

/**
 * How long a video is, in the corner of its thumbnail.
 *
 * Bottom right, which is where every video player and every video site puts it — the top-left
 * corner of these tiles already carries the type glyph and the selection checkbox, and a second
 * badge there made the two compete.
 *
 * Renders nothing without a duration rather than a placeholder: not every asset has been probed,
 * and "0:00:00" would be a claim rather than a gap.
 */
export function DurationBadge({ seconds }: { seconds?: number }) {
  if (!seconds || !Number.isFinite(seconds) || seconds <= 0) return null;
  return (
    <Box
      data-testid="asset-duration-badge"
      data-seconds={Math.round(seconds)}
      sx={{
        position: "absolute", bottom: 6, right: 6, zIndex: 2,
        px: 0.6, py: 0.1,
        bgcolor: "rgba(0,0,0,0.75)", color: "#fff",
        borderRadius: tokens.radius.sm,
        fontSize: "0.66rem", fontWeight: 600, fontVariantNumeric: "tabular-nums",
        lineHeight: 1.5, letterSpacing: "0.01em",
        pointerEvents: "none",
      }}
    >
      {clockHMS(seconds)}
    </Box>
  );
}

export default DurationBadge;
