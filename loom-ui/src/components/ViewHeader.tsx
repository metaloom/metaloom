import React from "react";
import { Box, Typography } from "@mui/material";

import { tokens } from "../theme";

export interface ViewHeaderProps {
  /** The view's glyph. Rendered in the brand colour at a fixed size; pass the outlined variant. */
  icon: React.ReactNode;
  title: React.ReactNode;
  /** One line under the title — a count, the active space, what the view is for. */
  subtitle?: React.ReactNode;
  /** Sits beside the title, at caption weight. A count belongs here, not in the title. */
  meta?: React.ReactNode;
  /** Buttons and chips, pushed to the right-hand end of the row. */
  actions?: React.ReactNode;
  /** A filter bar or anything else that belongs inside the header band, below the title row. */
  children?: React.ReactNode;
  testId?: string;
}

/**
 * The band at the top of a view: glyph, name, and whatever that view does.
 *
 * <p>Every screen had one of these and no two were the same. Collections had an icon, a 1rem
 * bold title and a count beside it; tags had the title with no icon; assets had a title and a
 * subtitle and no icon; the workflow view had a hardcoded English string at a different size;
 * the admin panels each did their own. The result was that moving between views felt like
 * moving between applications, and there was nowhere to make a change to "the header" because
 * there was no header — there were twenty.</p>
 *
 * <p>This is deliberately not configurable beyond the four slots. A header that can be told its
 * font size is a header that will drift again.</p>
 */
export function ViewHeader({ icon, title, subtitle, meta, actions, children, testId = "view-header" }: ViewHeaderProps) {
  return (
    <Box
      data-testid={testId}
      sx={{
        px: 2.5, py: 1.5,
        borderBottom: `1px solid ${tokens.border.subtle}`,
        bgcolor: tokens.bg.surface,
        display: "flex", flexDirection: "column", gap: 1,
        flexShrink: 0,
      }}
    >
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, minHeight: 28 }}>
        <Box data-testid="view-header-icon"
          sx={{ display: "flex", alignItems: "center", color: tokens.primary.main, "& > *": { fontSize: 20 } }}>
          {icon}
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Box sx={{ display: "flex", alignItems: "baseline", gap: 1 }}>
            <Typography variant="h6" fontWeight={700} data-testid="view-header-title"
              sx={{ fontSize: "1rem", lineHeight: 1.3 }} noWrap>
              {title}
            </Typography>
            {meta != null && (
              <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: "nowrap" }}>{meta}</Typography>
            )}
          </Box>
          {subtitle != null && (
            <Typography variant="caption" color="text.secondary" display="block" sx={{ lineHeight: 1.4 }}>
              {subtitle}
            </Typography>
          )}
        </Box>
        {actions != null && (
          <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: 1, flexShrink: 0 }}>{actions}</Box>
        )}
      </Box>
      {children}
    </Box>
  );
}

export default ViewHeader;
