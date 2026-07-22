import React, { useEffect, useState } from "react";
import { Box, Collapse, Typography } from "@mui/material";
import { ExpandLess, ExpandMore, PsychologyOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { useTranslation } from "react-i18next";
import MarkdownContent from "./MarkdownContent";

/**
 * Collapsible reasoning ("thinking") section of an assistant message.
 *
 * The reasoning content is hidden by default: while the model streams reasoning
 * deltas only an animated "thinking…" indicator (with elapsed seconds) is shown;
 * the chunk text becomes visible only after the user explicitly expands the
 * section. After completion a subtle "Show reasoning" toggle remains — and only
 * when there is reasoning to show.
 */
export default function ReasoningSection({ reasoning, streaming }: { reasoning?: string; streaming: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [startedAt] = useState(() => Date.now());
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (!streaming) return;
    const timer = setInterval(() => setElapsed(Math.floor((Date.now() - startedAt) / 1000)), 500);
    return () => clearInterval(timer);
  }, [streaming, startedAt]);

  const hasReasoning = !!reasoning && reasoning.length > 0;
  if (!streaming && !hasReasoning) return null;

  return (
    <Box sx={{ mb: 0.75 }}>
      <Box
        data-testid={streaming ? "chat-reasoning-indicator" : "chat-reasoning-toggle"}
        role="button"
        onClick={() => hasReasoning && setOpen(o => !o)}
        sx={{
          display: "inline-flex",
          alignItems: "center",
          gap: 0.75,
          px: 1,
          py: 0.4,
          borderRadius: tokens.radius.md,
          cursor: hasReasoning ? "pointer" : "default",
          color: tokens.text.tertiary,
          bgcolor: streaming ? tokens.bg.elevated : "transparent",
          border: `1px dashed ${tokens.border.subtle}`,
          "&:hover": hasReasoning ? { color: tokens.text.secondary, borderColor: tokens.border.default } : {},
          ...(streaming
            ? {
                animation: "reasoningShimmer 1.6s ease-in-out infinite",
                "@keyframes reasoningShimmer": { "0%,100%": { opacity: 0.55 }, "50%": { opacity: 1 } },
              }
            : {}),
        }}
      >
        <PsychologyOutlined sx={{ fontSize: 14 }} />
        <Typography variant="caption" sx={{ fontSize: "0.72rem", fontStyle: streaming ? "italic" : "normal" }}>
          {streaming
            ? t("chat.reasoning.thinking", { seconds: elapsed })
            : open
              ? t("chat.reasoning.hide")
              : t("chat.reasoning.show")}
        </Typography>
        {hasReasoning && (open ? <ExpandLess sx={{ fontSize: 14 }} /> : <ExpandMore sx={{ fontSize: 14 }} />)}
      </Box>
      <Collapse in={open && hasReasoning}>
        <Box
          data-testid="chat-reasoning-content"
          sx={{
            mt: 0.5,
            px: 1.5,
            py: 1,
            borderLeft: `2px solid ${tokens.border.default}`,
            bgcolor: tokens.bg.elevated,
            borderRadius: `0 ${tokens.radius.md} ${tokens.radius.md} 0`,
          }}
        >
          <MarkdownContent content={reasoning ?? ""} muted />
        </Box>
      </Collapse>
    </Box>
  );
}
