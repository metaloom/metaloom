import React from "react";
import { Box, Typography } from "@mui/material";
import { SearchOffOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";

interface Props {
  /** Which backend is bound — `none` when search is switched off entirely. */
  provider: string;
  /** The server's explanation, when it gave one. */
  reason?: string;
}

/**
 * Shown in place of the search UI when the deployment cannot serve queries.
 *
 * Names the provider and the reason rather than showing an empty result set: search being off is
 * a deployment fact the user can act on by asking an operator, and a blank page is not.
 */
export default function SearchUnavailable({ provider, reason }: Props) {
  const { t } = useTranslation();

  return (
    <Box
      data-testid="search-unavailable"
      sx={{
        display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center",
        textAlign: "center", gap: 1, px: 3, py: 8, minHeight: 320,
      }}
    >
      <Box
        sx={{
          width: 112, height: 112, borderRadius: "50%", mb: 1,
          display: "flex", alignItems: "center", justifyContent: "center",
          bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`,
        }}
      >
        <SearchOffOutlined sx={{ fontSize: 54, color: tokens.text.tertiary }} />
      </Box>

      <Typography variant="h6" fontWeight={700} sx={{ color: tokens.text.primary, fontSize: "1.25rem" }}>
        {t("search.unavailable.title")}
      </Typography>

      <Typography variant="body2" sx={{ color: tokens.text.secondary, maxWidth: 440, lineHeight: 1.6 }}>
        {t("search.unavailable.body")}
      </Typography>

      <Typography
        variant="body2"
        data-testid="search-unavailable-provider"
        sx={{ color: tokens.text.tertiary, maxWidth: 440, mt: 1, fontSize: "0.8rem" }}
      >
        {t("search.unavailable.provider", { provider })}
        {reason ? ` — ${reason}` : ""}
      </Typography>
    </Box>
  );
}
