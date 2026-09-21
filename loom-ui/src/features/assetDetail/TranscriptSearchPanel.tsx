import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Box, Chip, CircularProgress, IconButton, InputBase, Tooltip, Typography,
} from "@mui/material";
import { CloseOutlined, SearchOutlined } from "@mui/icons-material";

import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useSearch } from "../../context/SearchContext";
import { SearchApiError, searchResults, type SearchHitResponse } from "../../api/search";
import { parseHighlight } from "../search/highlight";
import { formatTimecode } from "../search/searchHits";
import type { SearchMode } from "../../types";

/** How long to wait after the last keystroke before asking the server. */
const DEBOUNCE_MS = 300;

/**
 * Search what is said in this one video, and jump to the moment.
 *
 * <h3>Why this is not the "find in transcript" box below it</h3>
 *
 * <p>{@link TranscriptPanel} scans the sections already in the browser for a literal substring.
 * That is the right tool for "I can see the word, take me to it" and it stays. It cannot answer
 * the other question — "where does somebody talk about the iris device" — because the words the
 * reader would type are not the words that were said, and because it only ever sees the
 * transcripts that happen to be loaded.</p>
 *
 * <p>This asks the server instead, scoped to this asset with {@code ?asset=}. Since the index
 * windows a transcript per minute rather than storing one document per episode, each hit comes
 * back with the offset of the minute it was said in, which is what makes a result clickable.
 * With an embedding host configured the same box answers semantically; without one the mode chip
 * is not rendered at all rather than offering a setting that earns a 400.</p>
 */
export function TranscriptSearchPanel({ assetUuid, onSeek }: {
  assetUuid: string;
  /** Seconds into the asset. */
  onSeek: (seconds: number) => void;
}) {
  const { t: tAD } = useTranslation("translation", { keyPrefix: "assetDetail" });
  const { token } = useAuth();
  const { available, has } = useSearch();

  const [query, setQuery] = useState("");
  const [mode, setMode] = useState<SearchMode>("LEXICAL");
  const [hits, setHits] = useState<SearchHitResponse[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);

  const semantic = has("SEMANTIC");
  const modes = useMemo<SearchMode[]>(() => (semantic ? ["LEXICAL", "SEMANTIC", "HYBRID"] : []), [semantic]);

  // A mode the provider stopped advertising has to fall back, or every search is a 400 until the
  // chip is clicked again. The capability is recomputed per call server-side: an embedding host
  // that dies retracts it while this component is mounted.
  useEffect(() => {
    if (!semantic && mode !== "LEXICAL") setMode("LEXICAL");
  }, [semantic, mode]);

  const reset = useCallback(() => {
    abort.current?.abort();
    abort.current = null;
    setHits(null);
    setError(null);
    setBusy(false);
  }, []);

  useEffect(() => {
    const term = query.trim();
    if (!token || term === "") {
      reset();
      return;
    }
    const timer = window.setTimeout(() => {
      abort.current?.abort();
      const controller = new AbortController();
      abort.current = controller;
      setBusy(true);
      setError(null);
      searchResults(token, {
        q: term,
        types: ["transcript"],
        asset: assetUuid,
        mode,
        highlight: true,
        limit: 25,
      }, { signal: controller.signal })
        .then(result => {
          if (controller.signal.aborted) return;
          setHits(result.data ?? []);
        })
        .catch(err => {
          if (controller.signal.aborted || (err as Error)?.name === "AbortError") return;
          // 503 is "this deployment has no search", which is a different sentence from "nothing
          // matched" and has to read as one. Anything else keeps the server's own message.
          setHits(null);
          setError(err instanceof SearchApiError && err.status === 503
            ? tAD("transcript.search.unavailable")
            : (err as Error)?.message ?? tAD("transcript.search.failed"));
        })
        .finally(() => {
          if (!controller.signal.aborted) setBusy(false);
        });
    }, DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [token, query, mode, assetUuid, reset, tAD]);

  useEffect(() => () => abort.current?.abort(), []);

  // Nothing to offer, and saying so with a dead search box would be worse than saying nothing.
  if (!available) return null;

  return (
    <Box data-testid="transcript-search-panel" sx={{ mb: 1.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, flexWrap: "wrap" }}>
        <Box sx={{
          display: "flex", alignItems: "center", gap: 0.5, flex: 1, minWidth: 180,
          px: 1, py: 0.25, borderRadius: tokens.radius.md,
          bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`,
        }}>
          <SearchOutlined sx={{ fontSize: 15, color: tokens.text.tertiary }} />
          <InputBase
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={tAD("transcript.search.placeholder")}
            inputProps={{ "data-testid": "transcript-search-input", "aria-label": tAD("transcript.search.placeholder") }}
            sx={{ flex: 1, fontSize: "0.78rem" }}
          />
          {busy && <CircularProgress size={13} />}
          {query !== "" && !busy && (
            <Tooltip title={tAD("transcript.search.clear")}>
              <IconButton size="small" data-testid="transcript-search-clear" onClick={() => setQuery("")} sx={{ p: 0.25 }}>
                <CloseOutlined sx={{ fontSize: 14 }} />
              </IconButton>
            </Tooltip>
          )}
        </Box>
        {/* Only rendered where the provider says it can serve them. A control whose only possible
            outcome is a 400 is worse than no control. */}
        {modes.map(candidate => (
          <Chip
            key={candidate}
            size="small"
            label={tAD(`transcript.search.mode.${candidate}`)}
            data-testid={`transcript-search-mode-${candidate}`}
            variant={mode === candidate ? "filled" : "outlined"}
            color={mode === candidate ? "primary" : "default"}
            onClick={() => setMode(candidate)}
            sx={{ height: 20, fontSize: "0.65rem" }}
          />
        ))}
      </Box>

      {error && (
        <Typography variant="caption" data-testid="transcript-search-error"
          sx={{ display: "block", mt: 0.75, color: tokens.accent.amber, fontSize: "0.7rem" }}>
          {error}
        </Typography>
      )}

      {hits != null && !error && (
        <Box data-testid="transcript-search-results" data-count={hits.length} sx={{ mt: 0.75 }}>
          {hits.length === 0 ? (
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
              {tAD("transcript.search.noMatch")}
            </Typography>
          ) : (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 0.25, maxHeight: 220, overflow: "auto" }}>
              {hits.map(hit => (
                <Box
                  key={`${hit.uuid}`}
                  data-testid="transcript-search-hit"
                  data-time-ms={hit.timeFromMs ?? ""}
                  onClick={() => hit.timeFromMs != null && onSeek(hit.timeFromMs / 1000)}
                  sx={{
                    display: "flex", gap: 1, alignItems: "baseline",
                    px: 0.75, py: 0.5, borderRadius: tokens.radius.sm,
                    cursor: hit.timeFromMs != null ? "pointer" : "default",
                    "&:hover": { bgcolor: tokens.bg.hover },
                  }}
                >
                  <Typography variant="caption" sx={{
                    fontFamily: "monospace", fontSize: "0.68rem", color: tokens.primary.light, flexShrink: 0,
                  }}>
                    {formatTimecode(hit.timeFromMs ?? 0)}
                  </Typography>
                  <Typography variant="caption" sx={{ fontSize: "0.72rem", color: tokens.text.secondary, lineHeight: 1.5 }}>
                    {/* A semantic hit carries no snippet: nothing in the text matched the typed
                        words, the vector ranker found it by meaning. Fall back to the window's
                        own opening rather than rendering an empty row. */}
                    {(hit.highlights?.length ? hit.highlights : [hit.subtitle ?? ""]).map((fragment, i) =>
                      parseHighlight(fragment).map((segment, j) => (segment.match ? (
                        <Box key={`${i}-${j}`} component="mark"
                          sx={{ bgcolor: tokens.primary.subtle, color: tokens.text.primary, px: 0.25, borderRadius: tokens.radius.sm }}>
                          {segment.text}
                        </Box>
                      ) : (
                        <React.Fragment key={`${i}-${j}`}>{segment.text}</React.Fragment>
                      ))))}
                  </Typography>
                </Box>
              ))}
            </Box>
          )}
        </Box>
      )}
    </Box>
  );
}

export default TranscriptSearchPanel;
