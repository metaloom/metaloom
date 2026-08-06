import React, { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, ClickAwayListener, IconButton, InputAdornment, ListItemButton,
  Paper, Popper, TextField, Tooltip, Typography,
} from "@mui/material";
import { SearchOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../theme";
import { searchSuggestions, type SearchSuggestionResponse } from "../api/search";
import { useAuth } from "../context/AuthContext";
import { useSearch } from "../context/SearchContext";

/**
 * How long to wait after a keystroke before asking for suggestions.
 *
 * Each request runs a trigram scan, so firing one per character turns a typed word into eight
 * index scans that all but the last are thrown away.
 */
const SUGGEST_DEBOUNCE_MS = 250;

/** A one-character trigram prefix matches essentially the whole index. */
const MIN_SUGGEST_CHARS = 2;

const SUGGEST_LIMIT = 8;

/**
 * The global search entry point, in the sidebar above the navigation.
 *
 * Renders nothing at all when the deployment cannot serve searches — a box that errors on every
 * keystroke is worse than no box, and the explanation belongs on the /search route rather than
 * in a 220px rail.
 */
export default function GlobalSearchField({ collapsed }: { collapsed: boolean }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { token } = useAuth();
  const { available } = useSearch();

  const [value, setValue] = useState("");
  const [items, setItems] = useState<SearchSuggestionResponse[]>([]);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Monotonic request id. At 250ms a fast typist can have two calls in flight, and the older one
  // can land last — without this the dropdown paints suggestions for a prefix already replaced.
  const requestSeq = useRef(0);

  const closeDropdown = useCallback(() => {
    setOpen(false);
    setActiveIndex(-1);
  }, []);

  const runSearch = useCallback(
    (term: string) => {
      const trimmed = term.trim();
      if (!trimmed) return;
      closeDropdown();
      navigate(`/search?q=${encodeURIComponent(trimmed)}`);
    },
    [navigate, closeDropdown],
  );

  useEffect(() => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    const term = value.trim();
    if (!token || term.length < MIN_SUGGEST_CHARS) {
      setItems([]);
      closeDropdown();
      return;
    }
    debounceTimer.current = setTimeout(() => {
      const seq = ++requestSeq.current;
      searchSuggestions(token, term, { limit: SUGGEST_LIMIT })
        .then((list) => {
          if (seq !== requestSeq.current) return;
          setItems(list);
          setOpen(list.length > 0);
          setActiveIndex(-1);
        })
        .catch(() => {
          // Deliberately silent, mirroring the provider: a typeahead that pops an error toast on
          // a half-typed word is noise. It also cannot signal that search is down — the Noop
          // provider answers this route with an empty list, not a 503.
          if (seq !== requestSeq.current) return;
          setItems([]);
          closeDropdown();
        });
    }, SUGGEST_DEBOUNCE_MS);
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
    };
  }, [value, token, closeDropdown]);

  if (!available) return null;

  // The 56px rail has no room for an input; the icon keeps search reachable, exactly as the
  // nav sub-groups collapse to their icons.
  if (collapsed) {
    return (
      <Box sx={{ px: 0.75, pt: 1, pb: 0.5, display: "flex", justifyContent: "center" }}>
        <Tooltip title={t("search.global.tooltip")} placement="right">
          <IconButton
            size="small"
            data-testid="global-search-button"
            onClick={() => navigate("/search")}
            sx={{ border: `1px solid ${tokens.border.subtle}`, width: 28, height: 28 }}
          >
            <SearchOutlined sx={{ fontSize: 15, color: tokens.text.secondary }} />
          </IconButton>
        </Tooltip>
      </Box>
    );
  }

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "Enter") {
      event.preventDefault();
      // A highlighted suggestion searches for its text rather than jumping to the entity, so
      // Enter means the same thing whether or not the dropdown is open.
      runSearch(activeIndex >= 0 && items[activeIndex] ? items[activeIndex].text : value);
      return;
    }
    if (event.key === "Escape") {
      closeDropdown();
      return;
    }
    if (!open || items.length === 0) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((index) => (index + 1) % items.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((index) => (index <= 0 ? items.length - 1 : index - 1));
    }
  };

  return (
    <ClickAwayListener onClickAway={closeDropdown}>
      <Box ref={anchorRef} sx={{ px: 1.5, pb: 1 }}>
        <TextField
          fullWidth
          size="small"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => { if (items.length > 0) setOpen(true); }}
          placeholder={t("search.global.placeholder")}
          // A distinct placeholder matters: several e2e specs locate in-page filters with
          // getByPlaceholder(/search/i), and this field is mounted on every route.
          inputProps={{ "data-testid": "global-search-input", "aria-label": t("search.global.tooltip") }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
          sx={{ "& .MuiInputBase-input": { fontSize: "0.8rem" } }}
        />

        {/* The sidebar root is overflow:hidden, so an inline dropdown would be clipped at 220px.
            Popper portals out of it. */}
        <Popper
          open={open && items.length > 0}
          anchorEl={anchorRef.current}
          placement="bottom-start"
          style={{ zIndex: 1300, width: anchorRef.current?.clientWidth }}
        >
          <Paper
            data-testid="global-search-suggestions"
            sx={{
              mt: 0.5, py: 0.5, borderRadius: tokens.radius.md,
              border: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.elevated,
              maxHeight: 320, overflow: "auto",
            }}
          >
            {items.map((item, index) => (
              <ListItemButton
                key={`${item.type}-${item.uuid}`}
                data-testid={`global-search-suggestion-${index}`}
                selected={index === activeIndex}
                onClick={() => runSearch(item.text)}
                sx={{ px: 1.5, py: 0.5, gap: 1, alignItems: "baseline" }}
              >
                <Typography variant="body2" noWrap sx={{ fontSize: "0.8rem", flex: 1, minWidth: 0 }}>
                  {item.text}
                </Typography>
                <Typography variant="caption" sx={{ fontSize: "0.65rem", color: tokens.text.tertiary }}>
                  {t(`search.types.${item.type}`)}
                </Typography>
              </ListItemButton>
            ))}
          </Paper>
        </Popper>
      </Box>
    </ClickAwayListener>
  );
}
