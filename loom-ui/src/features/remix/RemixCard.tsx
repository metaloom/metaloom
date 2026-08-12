import React from "react";
import { Box, Paper, Typography } from "@mui/material";
import LayersOutlined from "@mui/icons-material/LayersOutlined";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import type { RemixResponse } from "../../api/remixes";

export type RemixCardSize = "small" | "medium" | "large";

interface Props {
  remix: RemixResponse;
  cardSize?: RemixCardSize;
  onOpen: (remix: RemixResponse) => void;
}

/**
 * A remix rendered as a card in the asset grid.
 *
 * <p>Deliberately the same geometry as an asset card — a remix sits in the same grid and the
 * user should not have to learn a second layout — but visually a different kind of object: a
 * dashed accent border, a stacked-paper motif behind it, and a member count instead of a file
 * size. The point is that it reads as "a thing you open" at a glance rather than "a file".</p>
 */
export default function RemixCard({ remix, cardSize = "medium", onOpen }: Props) {
  const { t } = useTranslation();

  return (
    <Box sx={{ position: "relative" }}>
      {/* The stack behind the card. Purely decorative, and hidden from assistive tech: the
          member count says the same thing in words. */}
      <Box
        aria-hidden
        sx={{
          position: "absolute",
          inset: 0,
          transform: "translate(4px, 4px)",
          bgcolor: tokens.bg.overlay,
          border: `1px dashed ${tokens.border.subtle}`,
          borderRadius: tokens.radius.lg,
        }}
      />
      <Paper
        elevation={0}
        onClick={() => onOpen(remix)}
        data-testid="remix-card"
        data-remix-uuid={remix.uuid}
        role="button"
        tabIndex={0}
        onKeyDown={e => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onOpen(remix);
          }
        }}
        sx={{
          position: "relative",
          cursor: "pointer",
          bgcolor: tokens.bg.elevated,
          border: `1px dashed ${tokens.primary.main}`,
          borderRadius: tokens.radius.lg,
          overflow: "hidden",
          transition: "border-color 140ms ease, box-shadow 140ms ease",
          "&:hover": { boxShadow: `0 4px 20px rgba(0,0,0,0.35)` },
        }}
      >
        <Box sx={{ position: "relative", paddingTop: "56.25%", bgcolor: tokens.bg.overlay }}>
          <Box sx={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center" }}>
            <LayersOutlined sx={{ fontSize: cardSize === "small" ? 28 : 40, color: tokens.primary.light }} />
          </Box>
          <Box
            sx={{
              position: "absolute",
              top: 6,
              left: 6,
              display: "flex",
              alignItems: "center",
              gap: 0.5,
              bgcolor: "rgba(0,0,0,0.6)",
              px: 0.75,
              py: 0.25,
              borderRadius: tokens.radius.sm,
            }}
          >
            <Typography variant="caption" sx={{ color: "#fff", fontSize: "0.7rem", fontWeight: 600 }}>
              {t("remix.card.badge")}
            </Typography>
          </Box>
        </Box>

        {cardSize !== "small" && (
          <Box sx={{ px: 1.5, py: 1.25 }}>
            <Typography variant="body2" fontWeight={600} noWrap sx={{ fontSize: "0.8rem", color: tokens.text.primary, mb: 0.5 }}>
              {remix.name}
            </Typography>
            {remix.memberCount != null && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                {t("remix.card.members", { count: remix.memberCount })}
              </Typography>
            )}
          </Box>
        )}
      </Paper>
    </Box>
  );
}
