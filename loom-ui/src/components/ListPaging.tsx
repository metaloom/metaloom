import React from "react";
import { Box, Button, CircularProgress, Typography } from "@mui/material";
import { ExpandMoreOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../theme";

interface Props {
  /** Rows currently held in memory. */
  loaded: number;
  /** Rows the server says exist in total. */
  total: number;
  hasMore: boolean;
  loadingMore?: boolean;
  onLoadMore: () => void;
  /** `data-testid` prefix — the footer gets `<testId>`, the button `<testId>-button`. */
  testId?: string;
}

/**
 * Footer for a paged list: how much of the collection is on screen, and a way to fetch the rest.
 *
 * Every Loom list route caps at 25 rows by default, so a list that renders whatever the first
 * `fetch` returned is showing a page while looking like a collection. This component is the
 * visible half of the fix — it is deliberately a **button**, not scroll-triggered loading, so
 * "there is more" is stated rather than discovered.
 *
 * Renders nothing when everything is already loaded.
 */
export default function ListPaging({ loaded, total, hasMore, loadingMore, onLoadMore, testId }: Props) {
  const { t } = useTranslation();
  if (!hasMore && loaded >= total) return null;

  return (
    <Box
      data-testid={testId}
      sx={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        gap: 1.5,
        py: 2,
        flexWrap: "wrap",
      }}
    >
      <Typography variant="caption" sx={{ color: tokens.text.tertiary }} data-testid={testId ? `${testId}-count` : undefined}>
        {t("common.paging.showing", { shown: loaded, total })}
      </Typography>
      {hasMore && (
        <Button
          size="small"
          variant="outlined"
          onClick={onLoadMore}
          disabled={loadingMore}
          startIcon={loadingMore ? <CircularProgress size={14} /> : <ExpandMoreOutlined sx={{ fontSize: 16 }} />}
          data-testid={testId ? `${testId}-button` : undefined}
          sx={{ textTransform: "none", borderRadius: tokens.radius.full, px: 2 }}
        >
          {t("common.paging.loadMore")}
        </Button>
      )}
    </Box>
  );
}
