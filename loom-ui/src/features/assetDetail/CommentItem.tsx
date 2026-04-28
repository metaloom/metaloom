import React from "react";
import { Avatar, Box, Chip, Typography } from "@mui/material";
import { AccessTimeOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Comment } from "../../types";
import { formatDuration, userName } from "./helpers";

export function CommentItem({ comment, highlighted, onTimeClick, onHover }: { comment: Comment; highlighted: boolean; onTimeClick?: (t: number) => void; onHover?: (id: string | null) => void }) {
  return (
    <Box
      onMouseEnter={() => onHover?.(comment.id)}
      onMouseLeave={() => onHover?.(null)}
      sx={{
        display: "flex",
        gap: 1.5,
        p: 1.5,
        borderRadius: tokens.radius.md,
        bgcolor: highlighted ? tokens.primary.subtle : "transparent",
        border: highlighted ? `1px solid ${tokens.primary.glow}` : "1px solid transparent",
        transition: "all 160ms ease",
        cursor: "default",
      }}
    >
      <Avatar sx={{ width: 26, height: 26, fontSize: "0.65rem", bgcolor: tokens.bg.overlay, color: tokens.text.secondary, flexShrink: 0 }}>
        {userName(comment.authorId).split(" ").map(n => n[0]).join("")}
      </Avatar>
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
          <Typography variant="caption" fontWeight={600} color="text.primary" sx={{ fontSize: "0.78rem" }}>
            {userName(comment.authorId)}
          </Typography>
          {comment.timestampStart != null && (
            <Chip
              icon={<AccessTimeOutlined sx={{ fontSize: 10 }} />}
              label={formatDuration(comment.timestampStart)}
              size="small"
              onClick={() => onTimeClick?.(comment.timestampStart!)}
              sx={{ height: 16, fontSize: "0.65rem", bgcolor: tokens.primary.subtle, color: tokens.primary.light, cursor: "pointer" }}
            />
          )}
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>
            {new Date(comment.createdAt).toLocaleDateString()}
          </Typography>
        </Box>
        {comment.title && (
          <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.8rem", color: tokens.text.primary, mb: 0.25 }}>{comment.title}</Typography>
        )}
        <Typography variant="body2" sx={{ fontSize: "0.82rem", color: tokens.text.secondary, lineHeight: 1.55 }}>
          {comment.text}
        </Typography>
      </Box>
    </Box>
  );
}
