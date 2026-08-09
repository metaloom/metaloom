import React from "react";
import { Chip } from "@mui/material";
import { tokens } from "../theme";

/**
 * Visual state for a status chip: which token palette to paint it with.
 *
 * Four tones rather than a free colour because the distinction they encode is the one
 * an operator reads first — working / degraded / broken / not applicable — and letting
 * each screen pick its own greens would make that reading unreliable across screens.
 */
export type Tone = "green" | "amber" | "red" | "neutral";

export function toneStyles(tone: Tone) {
  switch (tone) {
    case "green":
      return { bgcolor: "rgba(52,213,138,0.15)", color: tokens.accent.green };
    case "amber":
      return { bgcolor: "rgba(245,166,35,0.15)", color: tokens.accent.amber };
    case "red":
      return { bgcolor: "rgba(240,84,110,0.15)", color: tokens.accent.red };
    case "neutral":
    default:
      return { bgcolor: tokens.bg.overlay, color: tokens.text.tertiary };
  }
}

export default function StatusChip({
  label,
  tone,
  testId,
  title,
}: {
  label: string;
  tone: Tone;
  testId?: string;
  title?: string;
}) {
  return (
    <Chip
      label={label}
      size="small"
      title={title}
      data-testid={testId}
      sx={{
        ...toneStyles(tone),
        fontWeight: 600,
        fontSize: "0.72rem",
        height: 22,
      }}
    />
  );
}
