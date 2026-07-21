import React from "react";
import { Box, Chip, Typography } from "@mui/material";
import { AccessTimeOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Annotation } from "../../types";
import { formatDuration, userName } from "./helpers";
import { AnnotationReactionBar } from "./AnnotationReactionBar";

export function AnnotationItem({ ann, highlighted, onTimeClick, onHover, token, currentUserUuid }: { ann: Annotation; highlighted: boolean; onTimeClick?: (t: number) => void; onHover?: (id: string | null) => void; token?: string | null; currentUserUuid?: string | null }) {
  return (
    <Box
      onMouseEnter={() => onHover?.(ann.id)}
      onMouseLeave={() => onHover?.(null)}
      sx={{
        display: "flex",
        gap: 1.25,
        p: 1.5,
        borderRadius: tokens.radius.md,
        bgcolor: highlighted ? `${ann.color}14` : "transparent",
        border: highlighted ? `1px solid ${ann.color}44` : `1px solid transparent`,
        transition: "all 160ms ease",
        cursor: "default",
      }}
    >
      <Box sx={{ width: 3, bgcolor: ann.color, borderRadius: 2, alignSelf: "stretch", flexShrink: 0 }} />
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.35 }}>
          <Typography variant="caption" fontWeight={700} sx={{ fontSize: "0.78rem", color: ann.color }}>{ann.title}</Typography>
          {ann.timestampStart != null && (
            <Chip
              icon={<AccessTimeOutlined sx={{ fontSize: 10 }} />}
              label={ann.timestampEnd != null ? `${formatDuration(ann.timestampStart)} – ${formatDuration(ann.timestampEnd)}` : formatDuration(ann.timestampStart)}
              size="small"
              onClick={() => onTimeClick?.(ann.timestampStart!)}
              sx={{ height: 16, fontSize: "0.65rem", bgcolor: `${ann.color}22`, color: ann.color, cursor: "pointer" }}
            />
          )}
        </Box>
        <Typography variant="body2" sx={{ fontSize: "0.8rem", color: tokens.text.secondary }}>
          {ann.description}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
          {userName(ann.authorId)}
        </Typography>
        <AnnotationReactionBar annotationUuid={ann.id} token={token} currentUserUuid={currentUserUuid} />
      </Box>
    </Box>
  );
}
