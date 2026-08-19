import React, { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  Box, Typography, Paper, List, ListItemButton, ListItemText,
  IconButton, Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField, Tooltip,
  InputAdornment,
} from "@mui/material";
import { LibraryBooksOutlined, PhotoLibraryOutlined, VideocamOutlined, FolderOutlined, AddOutlined, DeleteOutlined, SearchOutlined, HelpOutlineOutlined, EditOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import AssetThumbnail from "../../components/AssetThumbnail";
import EmptyState from "../../components/EmptyState";
import LoadFailure from "../../components/LoadFailure";
import { AssetResponse, assetBinaryUrl, listAssets } from "../../api/assets";
import { createLibrary, deleteLibrary, listLibraries, updateLibrary } from "../../api/libraries";
import { SearchApiError, searchAssets, type SearchHitResponse } from "../../api/search";
import type { PagingParams } from "../../api/paging";
import ListPaging from "../../components/ListPaging";
import { DEFAULT_SORT, ListFilterSelect, ListSortControl, type SortState } from "../../components/ListControls";
import { useCreatorOptions } from "../../hooks/useCreatorOptions";
import { pageFrom, usePagedList } from "../../hooks/usePagedList";
import { assetInLibrary, assetsInLibrary } from "./libraryAssets";
import { assetTypeFromMime } from "../assets/assetMapping";
import { clampOffset, hasNextPage } from "../search/searchHits";
import { useSpace } from "../../context/SpaceContext";
import { useSearch } from "../../context/SearchContext";
import { useToast } from "../../context/ToastContext";
import { useFailure } from "../../context/FailureContext";
import { useAuth } from "../../context/AuthContext";
import { useTranslation } from "react-i18next";
import { AssetType, SEARCH_PAGE_SIZE } from "../../types";
import { PAGE_SIZE } from "../../hooks/pagedList";

/**
 * What the grid renders.
 *
 * The two sources disagree about what an asset is: `/assets` returns the record, `/search/assets`
 * returns a ranked hit that carries only what it took to label it. Both are reduced to this before
 * they reach the grid, so the tiles do not have to know which mode they are in — and so the fields
 * a hit does not carry (tags, dimensions, library membership) are absent rather than invented.
 */
interface LibraryCard {
  id: string;
  name: string;
  mimeType: string;
  type: AssetType;
  /** Empty for anything an `<img>` cannot decode; the tile then keeps the type placeholder. */
  previewUrl: string;
  size: number;
}

/**
 * Only images get a preview here.
 *
 * `AssetThumbnail` can also seek a frame out of a video, but doing that in the library grid would
 * pull a whole video binary per tile for a panel that is mostly scrolled past — see
 * `library-thumbnails-mocked.spec.ts`, which pins that a video fetches no binary.
 */
function previewFor(type: AssetType, uuid: string): string {
  return type === "image" ? assetBinaryUrl(uuid) : "";
}

function cardFromAsset(asset: AssetResponse): LibraryCard {
  const mimeType = asset.file?.mimeType ?? "";
  const type = assetTypeFromMime(mimeType);
  return {
    id: asset.uuid,
    name: asset.file?.filename ?? "",
    mimeType,
    type,
    previewUrl: previewFor(type, asset.uuid),
    size: asset.file?.size ?? 0,
  };
}

function cardFromHit(hit: SearchHitResponse): LibraryCard {
  const mimeType = hit.mimeType ?? "";
  const type = assetTypeFromMime(mimeType);
  return {
    id: hit.uuid,
    name: hit.title,
    mimeType,
    type,
    previewUrl: previewFor(type, hit.uuid),
    size: hit.size ?? 0,
  };
}

function formatBytes(bytes: number): string {
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(1)} TB`;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(0)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

export default function LibraryView() {
  const { activeSpace } = useSpace();
  const { showToast } = useToast();
  const { reportFailure } = useFailure();
  const [loadError, setLoadError] = useState<string | null>(null);
  const { token } = useAuth();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [libraries, setLibraries] = useState<Array<{ id: string; name: string; description: string; meta: Record<string, unknown>; createdAt: string }>>([]);
  const [selectedLib, setSelectedLib] = useState<{ id: string; name: string; description: string; meta: Record<string, unknown>; createdAt: string } | null>(null);
  const [sortState, setSortState] = useState<SortState>(DEFAULT_SORT);
  const [creator, setCreator] = useState("");
  const creators = useCreatorOptions(token);
  // /assets caps at 25 rows per page. The per-library counts below are derived from whatever has
  // been loaded — there is no library-scoped count route — so paging is what makes them true.
  //
  // The sort and creator filter travel with the request for the same reason: the panel shows a
  // window onto the catalog, and reordering that window locally would order the window.
  const loadAssetPage = useMemo(
    () => (token
      ? (paging: PagingParams) => listAssets(token, {
        ...paging,
        sort: sortState.sort,
        dir: sortState.dir,
        filters: creator ? [{ key: "creator", value: creator }] : undefined,
      }).then(r => pageFrom(r, a => a))
      : null),
    [token, sortState.sort, sortState.dir, creator],
  );
  const assetPage = usePagedList<AssetResponse>(loadAssetPage, a => a.uuid);
  const assets = assetPage.items;
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [creating, setCreating] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [editName, setEditName] = useState("");
  const [editDesc, setEditDesc] = useState("");
  const [updating, setUpdating] = useState(false);

  // ── Search ──
  // The committed term lives in the URL, so a filtered library is shareable and the back button
  // re-runs it. `input` is only the uncommitted text in the field.
  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get("q") ?? "";
  const term = query.trim();
  const [input, setInput] = useState(query);
  /** The term this component last wrote into the URL, so its own write is not read back as a navigation. */
  const committedRef = useRef(query);

  const search = useSearch();
  // Pulled out because the context value is a fresh object on every provider render — depending on
  // `search` itself would re-fire the query effect. `markUnavailable` is a stable useCallback.
  const { markUnavailable } = search;
  /** Whether the grid is showing search results rather than the assets loaded for this library. */
  const searchMode = term.length > 0 && search.available;
  const [searchHits, setSearchHits] = useState<LibraryCard[] | null>(null);
  const [searchTotal, setSearchTotal] = useState(0);
  const [searching, setSearching] = useState(false);
  /** True when the provider answered 403 — a narrowed permission, not an empty index. */
  const [searchDenied, setSearchDenied] = useState(false);
  // Paging is stored with the scope it belongs to rather than reset by an effect: a term or library
  // change then reads as offset 0 on the same render, instead of firing one request at the old
  // offset and a second one after the reset lands.
  const libraryUuid = selectedLib?.id ?? "";
  const searchScope = `${term}|${libraryUuid}`;
  const [searchPage, setSearchPage] = useState({ scope: searchScope, offset: 0 });
  const searchOffset = searchPage.scope === searchScope ? searchPage.offset : 0;

  useEffect(() => {
    if (!token) return;
    listLibraries(token, { limit: PAGE_SIZE }).then(resp => {
      const libs = (resp.data ?? []).map(lib => ({
        id: lib.uuid,
        name: lib.name,
        description: typeof lib.meta?.description === "string" ? lib.meta.description : "",
        meta: lib.meta ?? {},
        createdAt: lib.status?.created ?? new Date().toISOString(),
      }));
      setLibraries(libs);
      setSelectedLib(prev => libs.find(l => l.id === prev?.id) ?? libs[0] ?? null);
      setLoadError(null);
    }).catch(e => {
      // This used to `setLibraries([])`, which rendered a failed load as "no libraries" - a
      // statement about the user's data rather than about the request, and the wrong one.
      setLoadError(reportFailure("loadLibraries", e).message);
    });
  }, [reportFailure, token]);

  // A navigation — the back button, or a link into a filtered library — puts a term in the URL that
  // this component did not type. Adopt it. Our own writes are skipped: they land while the user may
  // already have typed further, and echoing the committed value back would eat those keystrokes.
  useEffect(() => {
    if (query === committedRef.current) return;
    committedRef.current = query;
    setInput(query);
  }, [query]);

  // Commit the field into the URL once typing settles. Refining an existing term replaces the entry
  // rather than pushing one, so leaving the search takes one press of Back rather than one per
  // keystroke; entering or leaving search mode is a push, because those are the states worth
  // returning to.
  useEffect(() => {
    if (input === committedRef.current) return;
    const timer = setTimeout(() => {
      const next = input.trim();
      const refining = committedRef.current.trim() !== "" && next !== "";
      committedRef.current = next;
      setSearchParams(previous => {
        const params = new URLSearchParams(previous);
        if (next) params.set("q", next); else params.delete("q");
        return params;
      }, { replace: refining });
    }, 250);
    return () => clearTimeout(timer);
  }, [input, setSearchParams]);

  // Server-side search, scoped to the selected library. A local filter would only ever narrow the
  // pages already loaded, which for a library larger than one page silently disagrees with what the
  // global search field answers for the same term.
  useEffect(() => {
    if (!token || !searchMode) {
      setSearchHits(null);
      setSearchTotal(0);
      setSearching(false);
      setSearchDenied(false);
      return;
    }
    const controller = new AbortController();
    setSearching(true);
    searchAssets(
      token,
      {
        q: term,
        // Absent while no library is selected — the panel shows nothing then anyway, and an empty
        // `library=` would be dropped by the query builder rather than scoping anything.
        library: libraryUuid || undefined,
        limit: SEARCH_PAGE_SIZE,
        // Past LOOM_SEARCH_MAX_OFFSET the provider answers 400 rather than an empty page.
        offset: clampOffset(searchOffset),
      },
      { signal: controller.signal },
    )
      .then(response => {
        const page = (response.data ?? []).map(cardFromHit);
        // Offset 0 is a new query; anything else is the pager appending to what is on screen.
        setSearchHits(previous => (searchOffset === 0 || previous === null ? page : [...previous, ...page]));
        setSearchTotal(response._metainfo?.totalHits ?? page.length);
        setSearchDenied(false);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        if (e instanceof SearchApiError && e.status === 503) {
          // Search went down mid-session. Retract the box app-wide rather than answering 503 on
          // every further keystroke; the panel falls back to the assets already loaded.
          markUnavailable(e.body);
          setSearchHits(null);
        } else if (e instanceof SearchApiError && e.status === 403) {
          setSearchDenied(true);
          setSearchHits([]);
        } else {
          setSearchHits([]);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setSearching(false);
      });

    return () => controller.abort();
  }, [token, term, searchMode, libraryUuid, searchOffset, markUnavailable]);

  const handleCreate = async () => {
    if (!token || !newName.trim()) return;
    setCreating(true);
    try {
      const created = await createLibrary(token, {
        name: newName.trim(),
        meta: newDesc.trim() ? { description: newDesc.trim() } : undefined,
      });
      const lib = {
        id: created.uuid,
        name: created.name,
        description: typeof created.meta?.description === "string" ? created.meta.description : "",
        meta: created.meta ?? {},
        createdAt: created.status?.created ?? new Date().toISOString(),
      };
      setLibraries(prev => [...prev, lib]);
      setSelectedLib(lib);
      setNewName("");
      setNewDesc("");
      setCreateOpen(false);
    } finally {
      setCreating(false);
    }
  };

  const handleUpdate = async () => {
    if (!token || !selectedLib || !editName.trim()) return;
    setUpdating(true);
    try {
      // The server replaces the whole meta object, so send a client-side merge
      // that preserves any non-description keys.
      const desc = editDesc.trim();
      const meta = { ...selectedLib.meta };
      if (desc) meta.description = desc; else delete meta.description;
      const updated = await updateLibrary(token, selectedLib.id, {
        name: editName.trim(),
        meta,
      });
      const description = typeof updated.meta?.description === "string" ? updated.meta.description : "";
      setLibraries(prev => prev.map(lib => lib.id === selectedLib.id
        ? { ...lib, name: updated.name, description, meta: updated.meta ?? {} }
        : lib));
      setSelectedLib(prev => prev ? { ...prev, name: updated.name, description, meta: updated.meta ?? {} } : prev);
      setEditOpen(false);
    } finally {
      setUpdating(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget || !token) return;
    await deleteLibrary(token, deleteTarget.id);
    const updated = libraries.filter(l => l.id !== deleteTarget.id);
    setLibraries(updated);
    if (selectedLib?.id === deleteTarget.id) setSelectedLib(updated[0] ?? null);
    setDeleteTarget(null);
    showToast(t("library.toast.deleted"), "success");
  };

  const libraryAssets = useMemo(
    () => (selectedLib ? assetsInLibrary(assets, selectedLib.id) : []),
    [assets, selectedLib]
  );

  const countsByLibrary = useMemo(() => {
    const counts = new Map<string, number>();
    for (const lib of libraries) {
      counts.set(lib.id, assets.filter(a => assetInLibrary(a, lib.id)).length);
    }
    return counts;
  }, [assets, libraries]);

  // Search is unavailable but the user typed anyway: narrow the assets already loaded, and say so
  // below rather than pretending this covered the library.
  const locallyFiltered = useMemo(() => {
    if (!term) return libraryAssets;
    const q = term.toLowerCase();
    return libraryAssets.filter(a => {
      const filename = a.file?.filename ?? "";
      const mimeType = a.file?.mimeType ?? "";
      const tags = (a.tags ?? []).map(tag => `${tag.collection}:${tag.name}`);
      return filename.toLowerCase().includes(q)
        || mimeType.toLowerCase().includes(q)
        || tags.some(tag => tag.toLowerCase().includes(q));
    });
  }, [libraryAssets, term]);

  const cards = useMemo(
    () => (searchMode ? (searchHits ?? []) : locallyFiltered.map(cardFromAsset)),
    [searchMode, searchHits, locallyFiltered],
  );

  const videoCount = cards.filter(c => c.type === "video").length;
  const imageCount = cards.filter(c => c.type === "image").length;
  const totalSize = cards.reduce((s, c) => s + c.size, 0);

  return (
    <Box sx={{ display: "flex", height: "100%", overflow: "hidden", bgcolor: tokens.bg.base }}>
      {/* Library sidebar */}
      <Box sx={{ width: 230, flexShrink: 0, borderRight: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column" }}>
        <Box sx={{ px: 2, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box>
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("library.title")}</Typography>
              <Tooltip title={t("library.tooltip.info")} arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
            </Box>
            <Typography variant="caption" color="text.secondary">{activeSpace?.name ?? "All Spaces"}</Typography>
          </Box>
          <Tooltip title={t("library.tooltip.newLibrary")}>
            <IconButton size="small" onClick={() => setCreateOpen(true)} sx={{ bgcolor: tokens.primary.subtle, color: tokens.primary.main, "&:hover": { bgcolor: tokens.primary.glow } }}>
              <AddOutlined sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
        </Box>
        <List dense sx={{ p: 1, flex: 1, overflow: "auto" }}>
          {libraries.map(lib => (
            <ListItemButton
              key={lib.id}
              selected={selectedLib?.id === lib.id}
              onClick={() => setSelectedLib(lib)}
              sx={{ borderRadius: tokens.radius.md, mb: 0.5, pr: 0.5 }}
            >
              <Box sx={{ mr: 1.25, color: tokens.text.secondary, display: "flex" }}>
                <FolderOutlined sx={{ fontSize: 18 }} />
              </Box>
              <ListItemText
                primary={<Typography variant="body2" fontWeight={500} noWrap sx={{ fontSize: "0.82rem" }}>{lib.name}</Typography>}
                secondary={
                  <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }} data-testid="library-asset-count">
                    {/* There is no library-scoped count route, so this counts the assets loaded
                        so far. While the asset list is truncated, say "of the N loaded" rather
                        than presenting a page count as the library's size. */}
                    {assetPage.truncated
                      ? t("library.count.assetsPartial", { count: countsByLibrary.get(lib.id) ?? 0, loaded: assets.length })
                      : `${countsByLibrary.get(lib.id) ?? 0} ${t("library.count.assets")}`}
                  </Typography>
                }
              />
              <Tooltip title={t("library.tooltip.deleteLibrary")}>
                <IconButton
                  size="small"
                  onClick={(e) => { e.stopPropagation(); setDeleteTarget(lib); }}
                  sx={{ opacity: 0, ".MuiListItemButton-root:hover &": { opacity: 1 }, color: tokens.accent.red, ml: 0.5, width: 24, height: 24 }}
                >
                  <DeleteOutlined sx={{ fontSize: 14 }} />
                </IconButton>
              </Tooltip>
            </ListItemButton>
          ))}
        </List>
      </Box>

      {/* Library detail */}
      <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
        {selectedLib ? (
          <>
            <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column", gap: 1 }}>
              <Box>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                  <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{selectedLib.name}</Typography>
                  <Tooltip title="Edit library">
                    <IconButton
                      size="small"
                      onClick={() => {
                        setEditName(selectedLib.name);
                        setEditDesc(selectedLib.description);
                        setEditOpen(true);
                      }}
                      sx={{ width: 20, height: 20, color: tokens.text.tertiary }}>
                      <EditOutlined sx={{ fontSize: 14 }} />
                    </IconButton>
                  </Tooltip>
                </Box>
                <Typography variant="caption" color="text.secondary">{selectedLib.description}</Typography>
                <Box sx={{ display: "flex", gap: 1.5, mt: 1 }}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                    <VideocamOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                    <Typography variant="caption" color="text.secondary">{videoCount} {t("library.stats.videos")}</Typography>
                  </Box>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                    <PhotoLibraryOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                    <Typography variant="caption" color="text.secondary">{imageCount} {t("library.stats.images")}</Typography>
                  </Box>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                    <Typography variant="caption" color="text.secondary">{formatBytes(totalSize)} {t("library.stats.total")}</Typography>
                  </Box>
                  {searchMode && (
                    <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                      <Typography variant="caption" color="text.secondary" data-testid="library-search-hits">
                        {t("library.search.hits", { count: searchTotal })}
                      </Typography>
                    </Box>
                  )}
                </Box>
              </Box>
              <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
                <TextField
                  value={input}
                  onChange={e => setInput(e.target.value)}
                  placeholder={t("library.search.placeholder")}
                  size="small"
                  data-testid="library-search"
                  sx={{ maxWidth: 320 }}
                  InputProps={{
                    startAdornment: (
                      <InputAdornment position="start">
                        <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                      </InputAdornment>
                    ),
                  }}
                />

                {/* Both controls narrow and order the *listing*. `/search/assets` ranks by relevance
                    and takes no creator, so leaving them on screen while a search is active would
                    offer controls that quietly stop applying. */}
                {!searchMode && creators.length > 0 && (
                  <ListFilterSelect
                    value={creator}
                    onChange={setCreator}
                    options={creators}
                    allLabel={t("library.filter.allCreators")}
                    testId="library-filter-creator"
                    minWidth={150}
                  />
                )}

                {!searchMode && (
                  <ListSortControl value={sortState} onChange={setSortState} testId="library-sort" />
                )}
              </Box>

              {/* Searching without a search backend still filters, but only over what has been
                  fetched. Say so — a quietly partial result is the defect this screen was fixed for. */}
              {term && !search.available && !search.loading && (
                <Typography
                  variant="caption"
                  data-testid="library-search-degraded"
                  sx={{ color: tokens.accent.amber, fontSize: "0.7rem" }}
                >
                  {t("library.search.unavailable", { count: libraryAssets.length })}
                </Typography>
              )}
            </Box>
            <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
              {/* The empty state belongs to an empty library, never to a search that matched
                  nothing — otherwise a library full of assets offers to upload the first one
                  (LOOM_UI.md §7.5). While a term is active the inline hint below covers it. */}
              {!term && libraryAssets.length === 0 ? (
                // Library exists but holds nothing — send the user to the uploader.
                <EmptyState
                  icon={PhotoLibraryOutlined}
                  title={t("library.emptyState.assets.title")}
                  description={t("library.emptyState.assets.description")}
                  actionLabel={t("library.emptyState.assets.action")}
                  actionIcon={<AddOutlined sx={{ fontSize: 18 }} />}
                  onAction={() => navigate("/assets")}
                  testId="library-assets-empty-state"
                />
              ) : cards.length === 0 ? (
                <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }} data-testid="library-no-match">
                  <LibraryBooksOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                  <Typography variant="body2" color="text.secondary">
                    {searchDenied ? t("library.search.denied") : t("library.empty.noSearch")}
                  </Typography>
                </Box>
              ) : (
                <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2 }}>
                  {cards.map(a => (
                    <Paper
                      key={a.id}
                      elevation={0}
                      onClick={() => navigate(`/assets/${a.id}`)}
                      sx={{
                        cursor: "pointer",
                        bgcolor: tokens.bg.elevated,
                        border: `1px solid ${tokens.border.subtle}`,
                        borderRadius: tokens.radius.lg,
                        overflow: "hidden",
                        "&:hover": { borderColor: tokens.border.strong, boxShadow: "0 4px 20px rgba(0,0,0,0.35)" },
                        transition: "all 140ms ease",
                      }}
                    >
                      <Box sx={{ position: "relative", paddingTop: "56.25%", bgcolor: tokens.bg.overlay }}>
                        <AssetThumbnail
                          type={a.type}
                          src={a.previewUrl}
                          iconSize={28}
                          alt={a.name}
                        />
                      </Box>
                      <Box sx={{ px: 1.25, py: 1 }}>
                        <Typography variant="caption" fontWeight={600} noWrap display="block" sx={{ fontSize: "0.75rem", color: tokens.text.primary }}>{a.name || "Untitled"}</Typography>
                        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>{a.mimeType || "unknown"}</Typography>
                      </Box>
                    </Paper>
                  ))}
                </Box>
              )}

              {/* Two pagers, because the two modes page different things. Browsing pages the asset
                  listing; searching pages the hits, and stops at the deep-paging cap rather than
                  offering a button the provider would answer 400 to. `data.length` drives both —
                  `_metainfo.perPage` echoes the requested limit, not the effective one. */}
              {searchMode ? (
                !searching && (
                  <ListPaging
                    loaded={cards.length}
                    total={searchTotal}
                    hasMore={hasNextPage(searchOffset, cards.length - searchOffset, searchTotal)}
                    loadingMore={searching}
                    onLoadMore={() => setSearchPage({ scope: searchScope, offset: clampOffset(searchOffset + SEARCH_PAGE_SIZE) })}
                    testId="library-search-paging"
                  />
                )
              ) : (
                // The degraded local filter runs over the loaded assets, so "load more" is the only
                // way to widen it — which is why the footer stays put while a term is typed.
                !assetPage.loading && (
                  <ListPaging
                    loaded={assets.length}
                    total={assetPage.totalCount}
                    hasMore={assetPage.hasMore}
                    loadingMore={assetPage.loadingMore}
                    onLoadMore={assetPage.loadMore}
                    testId="library-assets-paging"
                  />
                )
              )}
            </Box>
          </>
        ) : (
          loadError ? (
            // Checked BEFORE the empty state, which is the whole point: "no libraries" and "the
            // libraries could not be loaded" are different statements, and this branch used to
            // make the second one look like the first.
            <LoadFailure message={loadError} testId="library-load-failure" />
          ) : libraries.length === 0 ? (
            // No libraries in this space yet — offer to create the first one.
            <EmptyState
              icon={LibraryBooksOutlined}
              title={t("library.emptyState.title")}
              description={t("library.emptyState.description")}
              actionLabel={t("library.emptyState.action")}
              actionIcon={<AddOutlined sx={{ fontSize: 18 }} />}
              onAction={() => setCreateOpen(true)}
              testId="library-empty-state"
            />
          ) : (
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: "100%", gap: 1 }}>
              <LibraryBooksOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
              <Typography variant="body2" color="text.secondary">{t("library.empty.selectLibrary")}</Typography>
            </Box>
          )
        )}
      </Box>

      {/* Create library dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 360 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("library.dialog.newLibrary")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField label={t("library.label.name")} size="small" value={newName} onChange={e => setNewName(e.target.value)} autoFocus fullWidth />
          <TextField label={t("library.label.description")} size="small" value={newDesc} onChange={e => setNewDesc(e.target.value)} multiline rows={2} fullWidth />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setCreateOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{t("library.button.cancel")}</Button>
          <Button onClick={handleCreate} size="small" variant="contained" disabled={!newName.trim() || creating}>
            {creating ? t("library.button.creating") : t("library.button.create")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Edit library dialog */}
      <Dialog open={editOpen} onClose={() => setEditOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 340 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>Edit Library</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField label={t("library.label.name")} size="small" value={editName} onChange={e => setEditName(e.target.value)} autoFocus fullWidth />
          <TextField label={t("library.label.description")} size="small" value={editDesc} onChange={e => setEditDesc(e.target.value)} multiline rows={2} fullWidth />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setEditOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{t("library.button.cancel")}</Button>
          <Button onClick={handleUpdate} size="small" variant="contained" disabled={!editName.trim() || updating}>
            {updating ? t("library.button.creating") : t("common.save")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirmation dialog */}
      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 340 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("library.dialog.deleteLibrary")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">
            {t("library.confirm.delete", { name: deleteTarget?.name })}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteTarget(null)} size="small" sx={{ color: tokens.text.secondary }}>{t("library.button.cancel")}</Button>
          <Button onClick={handleDelete} size="small" variant="contained" sx={{ bgcolor: tokens.accent.red, "&:hover": { bgcolor: tokens.accent.red } }}>{t("common.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
