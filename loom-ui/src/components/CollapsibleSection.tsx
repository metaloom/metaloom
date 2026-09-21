import React from "react";
import { Box, Collapse, Typography } from "@mui/material";
import { ExpandMoreOutlined } from "@mui/icons-material";

import { tokens } from "../theme";

export interface CollapsibleSectionProps {
  /** Stable key for the persisted open/closed state. Never a translated label. */
  id: string;
  title: React.ReactNode;
  icon?: React.ReactNode;
  /** A count, a language tag — whatever is worth reading while the section is shut. */
  meta?: React.ReactNode;
  /** Controls to the right of the heading. Clicks there never toggle the section. */
  actions?: React.ReactNode;
  expanded: boolean;
  onToggle: (id: string) => void;
  children: React.ReactNode;
}

/**
 * A titled, foldable band in a stack of them.
 *
 * <p>The asset viewer's left column used to be a fixed pile of sections with a single scrolling
 * one in the middle: metadata scrolled, and the transcript, the locations and the description
 * below it were simply off the bottom of an `overflow: hidden` pane with no way to reach them.
 * Folding is the half that makes a long column navigable, and one scroller around the whole stack
 * is the other half — the two only work together, which is why this component does not scroll
 * itself.</p>
 *
 * <p>The heading is a `button` so the keyboard reaches it, and `aria-expanded` says which way it
 * is. `data-section-id` / `data-expanded` exist so a test can assert the fold survived a reload
 * without reading localStorage.</p>
 */
export function CollapsibleSection({ id, title, icon, meta, actions, expanded, onToggle, children }: CollapsibleSectionProps) {
  return (
    <Box data-testid="asset-section" data-section-id={id} data-expanded={expanded ? "true" : "false"}
      sx={{ borderTop: `1px solid ${tokens.border.subtle}` }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, pr: 1 }}>
        <Box
          component="button"
          type="button"
          aria-expanded={expanded}
          data-testid="asset-section-toggle"
          onClick={() => onToggle(id)}
          sx={{
            flex: 1, minWidth: 0, display: "flex", alignItems: "center", gap: 0.75,
            px: 2, py: 1, border: "none", background: "none", cursor: "pointer",
            textAlign: "left", color: "inherit", font: "inherit",
            "&:hover": { bgcolor: tokens.bg.hover },
          }}
        >
          <ExpandMoreOutlined
            sx={{
              fontSize: 16, color: tokens.text.tertiary, flexShrink: 0,
              transform: expanded ? "rotate(0deg)" : "rotate(-90deg)",
              transition: "transform 150ms ease",
            }}
          />
          {icon}
          <Typography variant="caption" fontWeight={600}
            sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem" }}>
            {title}
          </Typography>
          {meta != null && (
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", opacity: 0.8 }}>
              {meta}
            </Typography>
          )}
        </Box>
        {actions}
      </Box>
      {/* `unmountOnExit`: a folded transcript of 1400 lines should not be in the document at all —
          that column is the reason this screen needed folding in the first place. */}
      <Collapse in={expanded} unmountOnExit>
        <Box sx={{ px: 2, pb: 2 }}>{children}</Box>
      </Collapse>
    </Box>
  );
}

export default CollapsibleSection;
