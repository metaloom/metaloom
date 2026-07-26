import React from "react";
import { Box, Button, Typography } from "@mui/material";
import { tokens } from "../theme";

interface Props {
  /** Icon component rendered large inside the tinted halo. */
  icon: React.ComponentType<{ sx?: object }>;
  /** Headline — a short question or statement ("Looking to start a project?"). */
  title: string;
  /** Supporting sentence explaining what the feature is for. */
  description?: string;
  /** Label of the primary call-to-action. Omit to render the state without a button. */
  actionLabel?: string;
  /** Icon rendered before the call-to-action label. */
  actionIcon?: React.ReactNode;
  onAction?: () => void;
  actionDisabled?: boolean;
  /** `data-testid` applied to the wrapper so E2E tests can assert the state. */
  testId?: string;
  /**
   * Reduced variant for empty states inside panels/sidebars rather than a full
   * feature page — smaller icon, no vertical centering.
   */
  compact?: boolean;
}

/**
 * Shared empty state for feature pages. Renders a large haloed icon, a headline,
 * an explanatory sentence and an optional call-to-action that creates the first
 * element. Used by every feature page that can legitimately have nothing in it
 * (assets, library, collections, tags, tasks, skills, pools, …) so the "nothing
 * here yet" moment reads the same everywhere.
 */
export default function EmptyState({
  icon: Icon,
  title,
  description,
  actionLabel,
  actionIcon,
  onAction,
  actionDisabled,
  testId,
  compact,
}: Props) {
  const haloSize = compact ? 72 : 112;
  const iconSize = compact ? 34 : 54;

  return (
    <Box
      data-testid={testId}
      sx={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        textAlign: "center",
        gap: 1,
        px: 3,
        py: compact ? 4 : 8,
        ...(compact ? {} : { minHeight: 320, height: "100%" }),
      }}
    >
      <Box
        sx={{
          width: haloSize,
          height: haloSize,
          borderRadius: "50%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          bgcolor: tokens.primary.subtle,
          border: `1px solid ${tokens.border.subtle}`,
          mb: 1,
        }}
      >
        <Icon sx={{ fontSize: iconSize, color: tokens.primary.main, opacity: 0.85 }} />
      </Box>

      <Typography
        variant={compact ? "subtitle1" : "h6"}
        fontWeight={700}
        sx={{ color: tokens.text.primary, fontSize: compact ? "0.95rem" : "1.25rem" }}
      >
        {title}
      </Typography>

      {description && (
        <Typography
          variant="body2"
          sx={{ color: tokens.text.secondary, maxWidth: 440, lineHeight: 1.6 }}
        >
          {description}
        </Typography>
      )}

      {actionLabel && onAction && (
        <Button
          variant="contained"
          size="medium"
          onClick={onAction}
          disabled={actionDisabled}
          startIcon={actionIcon}
          data-testid={testId ? `${testId}-action` : undefined}
          sx={{ mt: 2, borderRadius: tokens.radius.full, px: 2.5, textTransform: "none", fontWeight: 600 }}
        >
          {actionLabel}
        </Button>
      )}
    </Box>
  );
}
