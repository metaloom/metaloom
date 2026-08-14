import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  Alert, Box, Button, Chip, CircularProgress, InputAdornment, MenuItem,
  TextField, Typography,
} from "@mui/material";
import { SearchOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import HelpHint from "../../components/HelpHint";
import EmptyState from "../../components/EmptyState";
import { useAuth } from "../../context/AuthContext";
import { useSearch } from "../../context/SearchContext";
import { useToast } from "../../context/ToastContext";
import {
  SearchApiError, searchResults,
  type SearchResultResponse,
} from "../../api/search";
import {
  SEARCHABLE_ENTITY_TYPES, SEARCH_FACET_NAMES, SEARCH_MAX_OFFSET,
  SEARCH_MAX_QUERY_LENGTH, SEARCH_PAGE_SIZE,
  type SearchEntityType, type SearchSortMode,
} from "../../types";
import SearchHitRow from "./SearchHitRow";
import SearchUnavailable from "./SearchUnavailable";
import { clampOffset, hasNextPage, pageRange } from "./searchHits";

const SORT_MODES: SearchSortMode[] = ["RELEVANCE", "NEWEST", "OLDEST", "NAME", "SIZE"];

function parseCsv(raw: string | null): string[] {
  return raw ? raw.split(",").map((part) => part.trim()).filter(Boolean) : [];
}

/**
 * Cross-entity search.
 *
 * Everything that changes the server's answer lives in the URL, so a result page is shareable and
 * the back button re-runs the query it names. Only the uncommitted text in the field and the
 * response itself are component state.
 */
export default function SearchView() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();
  const { available, provider, reason, loading: statusLoading, has, markUnavailable } = useSearch();

  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get("q") ?? "";
  const offset = clampOffset(Number(searchParams.get("offset") ?? 0));
  const sort = (searchParams.get("sort") as SearchSortMode | null) ?? "RELEVANCE";
  const mime = searchParams.get("mime") ?? "";
  const lang = searchParams.get("lang") ?? "";
  const types = useMemo(() => parseCsv(searchParams.get("types")) as SearchEntityType[], [searchParams]);
  const tags = useMemo(() => parseCsv(searchParams.get("tag")), [searchParams]);

  const [input, setInput] = useState(query);
  const [result, setResult] = useState<SearchResultResponse | null>(null);
  const [error, setError] = useState<SearchApiError | null>(null);
  const [loading, setLoading] = useState(false);

  const canFacet = has("FACETS");
  // The server rejects a non-lexical mode with a 400 under the current provider. Never render a
  // control that can only produce that, and never send `mode` at all while it is hidden.
  const canChooseMode = has("SEMANTIC") || has("HYBRID");

  useEffect(() => setInput(query), [query]);

  /** Merge URL params, resetting paging — any change to the query resets the page. */
  const updateParams = useCallback(
    (changes: Record<string, string | undefined>, { keepOffset = false } = {}) => {
      setSearchParams((previous) => {
        const next = new URLSearchParams(previous);
        for (const [key, value] of Object.entries(changes)) {
          if (value === undefined || value === "") next.delete(key);
          else next.set(key, value);
        }
        if (!keepOffset) {
          next.delete("offset");
          next.delete("cursor");
        }
        return next;
      });
    },
    [setSearchParams],
  );

  const paramKey = searchParams.toString();

  useEffect(() => {
    // A blank term is a guaranteed 400, and "no query yet" is a legitimate resting state.
    if (!token || !query.trim()) {
      setResult(null);
      setError(null);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    searchResults(
      token,
      {
        q: query,
        types: types.length ? types : undefined,
        limit: SEARCH_PAGE_SIZE,
        offset,
        // RELEVANCE is the server default; omitting it keeps the wire minimal.
        sort: sort !== "RELEVANCE" ? sort : undefined,
        highlight: true,
        mime: mime || undefined,
        lang: lang || undefined,
        tag: tags.length ? tags : undefined,
        facets: canFacet ? [...SEARCH_FACET_NAMES] : undefined,
      },
      { signal: controller.signal },
    )
      .then((response) => {
        setResult(response);
        setLoading(false);
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setLoading(false);
        if (!(err instanceof SearchApiError)) {
          setError(new SearchApiError(0, String(err?.message ?? err)));
          return;
        }
        setError(err);
        if (err.status === 503) {
          // The provider went away mid-session. Retract the whole search UI, not just this page.
          showToast(err.body || t("search.error.unavailable"), "warning");
          markUnavailable(err.body);
        }
      });
    return () => controller.abort();
    // paramKey covers q/types/offset/sort/mime/lang/tag without re-firing on array identity.
  }, [token, paramKey, canFacet]);

  if (statusLoading) {
    return (
      <Box sx={{ display: "flex", justifyContent: "center", py: 8 }}>
        <CircularProgress size={24} />
      </Box>
    );
  }

  if (!available) {
    return <SearchUnavailable provider={provider} reason={reason} />;
  }

  const meta = result?._metainfo;
  const hits = result?.data ?? [];
  const range = pageRange(offset, hits.length);
  const nextBlockedByDepth = offset + SEARCH_PAGE_SIZE > SEARCH_MAX_OFFSET;
  const canGoNext = meta ? hasNextPage(offset, hits.length, meta.totalHits) : false;

  const toggleType = (type: SearchEntityType) => {
    const next = types.includes(type) ? types.filter((item) => item !== type) : [...types, type];
    updateParams({ types: next.join(",") });
  };

  /** A facet bucket writes the filter it represents — facets have no key of their own. */
  const applyFacet = (facetName: string, value: string) => {
    if (facetName === "mime_type") updateParams({ mime: value });
    else if (facetName === "lang") updateParams({ lang: value });
    else if (facetName === "entity_type") updateParams({ types: value });
  };

  const activeFilters: { key: string; label: string; clear: () => void }[] = [
    ...types.map((type) => ({
      key: `type-${type}`,
      label: t(`search.types.${type}`),
      clear: () => updateParams({ types: types.filter((item) => item !== type).join(",") }),
    })),
    ...(mime ? [{ key: "mime", label: mime, clear: () => updateParams({ mime: undefined }) }] : []),
    ...(lang ? [{ key: "lang", label: lang, clear: () => updateParams({ lang: undefined }) }] : []),
    ...tags.map((tag) => ({
      key: `tag-${tag}`,
      label: tag,
      clear: () => updateParams({ tag: tags.filter((item) => item !== tag).join(",") }),
    })),
  ];

  return (
    <Box data-testid="search-view" sx={{ flex: 1, overflow: "auto", px: 3, py: 2.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, mb: 2 }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1.1rem" }}>
          {t("search.title")}
        </Typography>
        <HelpHint topic="search" size={16} />
      </Box>

      <TextField
        fullWidth
        size="small"
        value={input}
        onChange={(event) => setInput(event.target.value)}
        onKeyDown={(event) => {
          if (event.key !== "Enter") return;
          event.preventDefault();
          updateParams({ q: input.trim() });
        }}
        placeholder={t("search.field.placeholder")}
        helperText={t("search.syntax.hint")}
        inputProps={{ maxLength: SEARCH_MAX_QUERY_LENGTH, "data-testid": "search-input" }}
        FormHelperTextProps={{ "data-testid": "search-syntax-hint" } as object}
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined sx={{ fontSize: 18, color: tokens.text.tertiary }} />
            </InputAdornment>
          ),
        }}
        sx={{ maxWidth: 640 }}
      />

      {/* Only drawn when a provider that can actually answer them is bound. */}
      {canChooseMode && (
        <Box sx={{ display: "flex", gap: 1, mt: 1.5 }} data-testid="search-mode-toggle">
          {(["LEXICAL", has("SEMANTIC") ? "SEMANTIC" : null, has("HYBRID") ? "HYBRID" : null]
            .filter(Boolean) as string[]).map((mode) => (
            <Chip
              key={mode}
              size="small"
              label={mode}
              variant={(searchParams.get("mode") ?? "LEXICAL") === mode ? "filled" : "outlined"}
              onClick={() => updateParams({ mode: mode === "LEXICAL" ? undefined : mode })}
            />
          ))}
        </Box>
      )}

      <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", mt: 2 }} data-testid="search-type-filter">
        {/* Only the types the indexer builds documents for; detection and segment can never hit. */}
        {SEARCHABLE_ENTITY_TYPES.map((type) => (
          <Chip
            key={type}
            size="small"
            data-testid={`search-type-${type}`}
            label={t(`search.types.${type}`)}
            variant={types.includes(type) ? "filled" : "outlined"}
            color={types.includes(type) ? "primary" : "default"}
            onClick={() => toggleType(type)}
            sx={{ fontSize: "0.7rem" }}
          />
        ))}

        <TextField
          select
          size="small"
          value={sort}
          onChange={(event) => updateParams({ sort: event.target.value })}
          inputProps={{ "data-testid": "search-sort" }}
          sx={{ ml: "auto", minWidth: 140, "& .MuiInputBase-input": { fontSize: "0.75rem", py: 0.5 } }}
        >
          {SORT_MODES.map((mode) => (
            <MenuItem key={mode} value={mode} sx={{ fontSize: "0.8rem" }}>
              {t(`search.sort.${mode}`)}
            </MenuItem>
          ))}
        </TextField>
      </Box>

      {/* Load-bearing: facet counts are computed against the filtered query, so an entity_type
          facet collapses once selected. Without a visible way back the user is stuck. */}
      {activeFilters.length > 0 && (
        <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", alignItems: "center", mt: 1.5 }}
             data-testid="search-active-filters">
          <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
            {t("search.filters.active")}
          </Typography>
          {activeFilters.map((filter) => (
            <Chip key={filter.key} size="small" label={filter.label} onDelete={filter.clear}
                  sx={{ fontSize: "0.7rem" }} />
          ))}
          <Button size="small" data-testid="search-filters-clear"
                  onClick={() => updateParams({ types: undefined, mime: undefined, lang: undefined, tag: undefined })}
                  sx={{ textTransform: "none", fontSize: "0.72rem" }}>
            {t("search.filters.clear")}
          </Button>
        </Box>
      )}

      {canFacet && result?.facets && Object.keys(result.facets).length > 0 && (
        <Box sx={{ mt: 2 }} data-testid="search-facets">
          {Object.entries(result.facets).map(([facetName, buckets]) => (
            buckets.length > 0 && (
              <Box key={facetName} sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", alignItems: "center", mb: 0.75 }}>
                <Typography variant="caption" sx={{ color: tokens.text.tertiary, minWidth: 84 }}>
                  {t(`search.facets.${facetName}`, facetName)}
                </Typography>
                {buckets.map((bucket) => (
                  <Chip
                    key={bucket.value}
                    size="small"
                    variant="outlined"
                    data-testid={`search-facet-${facetName}-${bucket.value}`}
                    label={`${bucket.value} (${bucket.count})`}
                    onClick={() => applyFacet(facetName, bucket.value)}
                    sx={{ fontSize: "0.68rem" }}
                  />
                ))}
              </Box>
            )
          ))}
        </Box>
      )}

      {/* The only signal that permission narrowing dropped entity types. */}
      {meta?.warnings && meta.warnings.length > 0 && (
        <Alert severity="info" data-testid="search-warnings" sx={{ mt: 2 }}>
          {meta.warnings.map((warning, index) => <div key={index}>{warning}</div>)}
        </Alert>
      )}

      {error && error.status !== 503 && (
        <Alert
          severity={error.status === 403 ? "warning" : "error"}
          data-testid="search-error"
          sx={{ mt: 2 }}
        >
          {/* The server message is the whole payload — the error code never reaches the wire. */}
          {error.status === 403 ? t("search.error.forbidden") : error.body || t("search.error.title")}
        </Alert>
      )}

      {meta && !loading && !error && (
        <Typography variant="caption" data-testid="search-summary"
                    sx={{ display: "block", color: tokens.text.tertiary, mt: 2 }}>
          {meta.totalExact
            ? t("search.summary.exact", { count: meta.totalHits, ms: meta.tookMs })
            : t("search.summary.approx", { count: meta.totalHits, ms: meta.tookMs })}
          {" · "}
          {t("search.summary.provider", { provider: meta.provider })}
        </Typography>
      )}

      {loading && (
        <Box sx={{ display: "flex", justifyContent: "center", py: 6 }}>
          <CircularProgress size={22} />
        </Box>
      )}

      {!loading && !query.trim() && (
        // Bound to "no query yet", never to a query that returned nothing.
        <EmptyState
          icon={SearchOutlined}
          title={t("search.empty.title")}
          description={t("search.empty.description")}
          testId="search-empty-state"
        />
      )}

      {!loading && !error && query.trim() && hits.length === 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
          <SearchOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
          <Typography variant="body2" color="text.secondary" data-testid="search-no-results">
            {t("search.noResults", { query })}
          </Typography>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, maxWidth: 420, textAlign: "center" }}>
            {t("search.syntax.long")}
          </Typography>
        </Box>
      )}

      {!loading && hits.length > 0 && (
        <Box sx={{ mt: 1.5, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}
             data-testid="search-results">
          {hits.map((hit) => <SearchHitRow key={`${hit.type}-${hit.uuid}`} hit={hit} />)}
        </Box>
      )}

      {!loading && hits.length > 0 && meta && (
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mt: 2 }} data-testid="search-pager">
          <Button
            size="small"
            disabled={offset === 0}
            data-testid="search-pager-prev"
            onClick={() => updateParams(
              { offset: String(Math.max(0, offset - SEARCH_PAGE_SIZE)) },
              { keepOffset: true },
            )}
            sx={{ textTransform: "none" }}
          >
            {t("search.pager.prev")}
          </Button>
          <Typography variant="caption" data-testid="search-pager-range" sx={{ color: tokens.text.tertiary }}>
            {t("search.pager.range", { from: range.from, to: range.to, total: meta.totalHits })}
          </Typography>
          <Button
            size="small"
            disabled={!canGoNext}
            data-testid="search-pager-next"
            onClick={() => {
              // Prefer the cursor when a provider ever returns one; Postgres never does.
              if (meta.nextCursor) updateParams({ cursor: meta.nextCursor }, { keepOffset: true });
              else updateParams({ offset: String(offset + SEARCH_PAGE_SIZE) }, { keepOffset: true });
            }}
            sx={{ textTransform: "none" }}
          >
            {t("search.pager.next")}
          </Button>
          {nextBlockedByDepth && (
            <Typography variant="caption" data-testid="search-pager-depth-limit" sx={{ color: tokens.text.tertiary }}>
              {t("search.pager.depthLimit", { max: SEARCH_MAX_OFFSET })}
            </Typography>
          )}
        </Box>
      )}
    </Box>
  );
}
