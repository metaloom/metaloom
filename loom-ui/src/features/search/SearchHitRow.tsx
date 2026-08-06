import React from "react";
import { useNavigate } from "react-router-dom";
import { Box, Chip, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import type { SearchHitResponse } from "../../api/search";
import { parseHighlight } from "./highlight";
import { formatTimecode, hitTarget } from "./searchHits";

/**
 * One search result.
 *
 * Highlight fragments are rendered through {@link parseHighlight} as text, never as HTML — see
 * that module for why.
 */
export default function SearchHitRow({ hit }: { hit: SearchHitResponse }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const target = hitTarget(hit);

  return (
    <Box
      data-testid="search-hit"
      data-hit-type={hit.type}
      onClick={target ? () => navigate(target) : undefined}
      sx={{
        px: 2, py: 1.5,
        borderBottom: `1px solid ${tokens.border.subtle}`,
        cursor: target ? "pointer" : "default",
        "&:hover": target ? { bgcolor: tokens.bg.elevated } : undefined,
      }}
    >
      <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, flexWrap: "wrap" }}>
        <Typography variant="body2" fontWeight={600} sx={{ color: tokens.text.primary }}>
          {hit.title}
        </Typography>
        <Chip
          size="small"
          label={t(`search.types.${hit.type}`)}
          sx={{ height: 18, fontSize: "0.62rem", textTransform: "uppercase", letterSpacing: "0.04em" }}
        />
        {hit.timeFromMs !== undefined && (
          <Chip
            size="small"
            variant="outlined"
            data-testid="search-hit-timecode"
            label={formatTimecode(hit.timeFromMs)}
            sx={{ height: 18, fontSize: "0.62rem" }}
          />
        )}
      </Box>

      {hit.subtitle && (
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, display: "block", mt: 0.25 }}>
          {hit.subtitle}
        </Typography>
      )}

      {hit.highlights?.map((fragment, fragmentIndex) => (
        <Typography
          key={fragmentIndex}
          variant="body2"
          data-testid="search-hit-snippet"
          sx={{ color: tokens.text.secondary, mt: 0.5, fontSize: "0.8rem", lineHeight: 1.6 }}
        >
          {parseHighlight(fragment).map((segment, segmentIndex) =>
            segment.match ? (
              <Box
                key={segmentIndex}
                component="mark"
                sx={{
                  bgcolor: tokens.primary.subtle, color: tokens.text.primary,
                  px: 0.25, borderRadius: tokens.radius.sm,
                }}
              >
                {segment.text}
              </Box>
            ) : (
              <React.Fragment key={segmentIndex}>{segment.text}</React.Fragment>
            ),
          )}
        </Typography>
      ))}
    </Box>
  );
}
