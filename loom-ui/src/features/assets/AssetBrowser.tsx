import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, TextField, InputAdornment, Chip, IconButton,
  ToggleButtonGroup, ToggleButton, Paper, Skeleton, Tooltip,
  FormControl, Select, MenuItem, SelectChangeEvent,
  Button, Checkbox, InputLabel,
  Dialog, DialogTitle, DialogContent, DialogActions,
  Menu,
} from "@mui/material";
import {
  SearchOutlined, GridViewOutlined, FormatListBulletedOutlined,
  PlayCircleOutline, ImageOutlined, AudiotrackOutlined, InsertDriveFileOutlined,
  FilterListOutlined, Circle, PhotoSizeSelectSmallOutlined,
  PhotoSizeSelectActualOutlined, PhotoSizeSelectLargeOutlined,
  CloudUploadOutlined, DeleteOutlined, LocalOfferOutlined, CloseOutlined,
  PermMediaOutlined, MoreVertOutlined, LayersOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import AssetThumbnail from "../../components/AssetThumbnail";
import EmptyState from "../../components/EmptyState";
import ListPaging from "../../components/ListPaging";
import { Asset, AssetType, AssetStatus } from "../../types";
import { useAuth } from "../../context/AuthContext";
import {
  listAssets, AssetResponse, deleteAsset, bulkUpdateAssets,
} from "../../api/assets";
import { hitToCard, mimeFilterFor, toAsset } from "./assetMapping";
import { SearchApiError, searchAssets } from "../../api/search";
import type { PagingParams } from "../../api/paging";
import { useSearch } from "../../context/SearchContext";
import { listCollections } from "../../api/collections";
import {
  DEFAULT_SORT, ListFilterSelect, ListSortControl, type FilterOption, type SortState,
} from "../../components/ListControls";
import { useCreatorOptions } from "../../hooks/useCreatorOptions";
import { pageFrom, usePagedList } from "../../hooks/usePagedList";
import { PAGE_SIZE } from "../../hooks/pagedList";
import { listLibraries, LibraryResponse } from "../../api/libraries";
import { useSpace } from "../../context/SpaceContext";
import { useToast } from "../../context/ToastContext";
import { useUploads } from "../uploads/UploadContext";
import { enqueue } from "../uploads/uploadQueue";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import RemixCard from "../remix/RemixCard";
import RemixDialog from "../remix/RemixDialog";
import { combineIntoRemix, listRemixes, searchRemixes, type RemixResponse } from "../../api/remixes";

function formatBytes(bytes: number): string {
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(1)} TB`;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(0)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

function formatDuration(seconds?: number): string {
  if (!seconds) return "";
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  if (m >= 60) return `${Math.floor(m / 60)}h ${m % 60}m`;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

const typeIcon: Record<AssetType, React.ReactNode> = {
  video: <PlayCircleOutline sx={{ fontSize: 14 }} />,
  image: <ImageOutlined sx={{ fontSize: 14 }} />,
  audio: <AudiotrackOutlined sx={{ fontSize: 14 }} />,
  document: <InsertDriveFileOutlined sx={{ fontSize: 14 }} />,
  unknown: <InsertDriveFileOutlined sx={{ fontSize: 14 }} />,
};

const statusColor: Record<AssetStatus, string> = {
  ready: tokens.accent.green,
  processing: tokens.accent.amber,
  failed: tokens.accent.red,
  archived: tokens.text.tertiary,
};

// ── Asset Card (grid mode) ────────────────────────────────────────────────
type CardSize = "small" | "medium" | "large";

interface CardProps {
  asset: Asset;
  cardSize?: CardSize;
  selectionMode?: boolean;
  selected?: boolean;
  onToggleSelect?: (id: string) => void;
  onDelete?: (asset: Asset) => void;
}

function AssetCard({ asset, cardSize = "medium", selectionMode = false, selected = false, onToggleSelect, onDelete }: CardProps) {
  const navigate = useNavigate();
  const sc = statusColor[asset.status];

  const handleClick = () => {
    if (selectionMode) {
      onToggleSelect?.(asset.id);
    } else {
      navigate(`/assets/${asset.id}`);
    }
  };

  return (
    <Paper
      elevation={0}
      onClick={handleClick}
      data-testid="asset-card"
      data-asset-name={asset.name}
      sx={{
        cursor: "pointer",
        position: "relative",
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${selected ? tokens.primary.main : tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        overflow: "hidden",
        transition: "border-color 140ms ease, box-shadow 140ms ease",
        "&:hover": {
          borderColor: selected ? tokens.primary.main : tokens.border.strong,
          boxShadow: `0 4px 20px rgba(0,0,0,0.35)`,
        },
        "&:hover .asset-actions": { opacity: 1 },
      }}
    >
      {/* Selection checkbox */}
      {selectionMode && (
        <Checkbox
          checked={selected}
          onClick={(e) => { e.stopPropagation(); onToggleSelect?.(asset.id); }}
          size="small"
          sx={{ position: "absolute", top: 2, left: 2, zIndex: 3, color: "#fff", "&.Mui-checked": { color: tokens.primary.main }, bgcolor: "rgba(0,0,0,0.4)", borderRadius: 1, p: 0.25 }}
        />
      )}
      {/* Hover delete action */}
      {!selectionMode && onDelete && (
        <Box className="asset-actions" sx={{ position: "absolute", top: 6, right: 6, zIndex: 3, opacity: 0, transition: "opacity 140ms ease" }}>
          <Tooltip title="Delete asset">
            <IconButton
              size="small"
              onClick={(e) => { e.stopPropagation(); onDelete(asset); }}
              sx={{ bgcolor: "rgba(0,0,0,0.6)", color: tokens.accent.red, width: 24, height: 24, "&:hover": { bgcolor: "rgba(0,0,0,0.8)" } }}
            >
              <DeleteOutlined sx={{ fontSize: 13 }} />
            </IconButton>
          </Tooltip>
        </Box>
      )}
      {/* Thumbnail */}
      <Box sx={{ position: "relative", paddingTop: "56.25%", bgcolor: tokens.bg.overlay }}>
        <AssetThumbnail type={asset.type} src={asset.thumbnailUrl} iconSize={40} alt={asset.name} />
        <Box sx={{ position: "absolute", top: 6, left: 6, display: "flex", alignItems: "center", gap: 0.5, bgcolor: "rgba(0,0,0,0.6)", px: 0.75, py: 0.25, borderRadius: tokens.radius.sm }}>
          <Box sx={{ color: "#fff", display: "flex" }}>{typeIcon[asset.type]}</Box>
          {asset.duration && <Typography variant="caption" sx={{ color: "#fff", fontSize: "0.7rem", fontWeight: 600 }}>{formatDuration(asset.duration)}</Typography>}
        </Box>
        <Box sx={{ position: "absolute", top: 6, right: 6, width: 8, height: 8, borderRadius: "50%", bgcolor: sc, boxShadow: `0 0 6px ${sc}` }} />
      </Box>

      {/* Info — hidden in small mode */}
      {cardSize !== "small" && (
        <Box sx={{ px: 1.5, py: 1.25 }}>
          <Typography variant="body2" fontWeight={600} noWrap sx={{ fontSize: "0.8rem", color: tokens.text.primary, mb: 0.5 }}>
            {asset.name}
          </Typography>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
              {formatBytes(asset.fileSize)}
            </Typography>
            {cardSize === "medium" && asset.tags.slice(0, 2).map(tag => (
              <Chip key={tag} label={tag} size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: tokens.bg.overlay, color: tokens.text.secondary }} />
            ))}
          </Box>
          {/* Large mode extras */}
          {cardSize === "large" && (
            <>
              <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap", mt: 0.75 }}>
                {asset.tags.map(tag => (
                  <Chip key={tag} label={tag} size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: tokens.bg.overlay, color: tokens.text.secondary }} />
                ))}
              </Box>
              <Box sx={{ display: "flex", gap: 1, mt: 0.75, alignItems: "center" }}>
                {asset.duration && (
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
                    {formatDuration(asset.duration)}
                  </Typography>
                )}
                <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
                  {formatBytes(asset.fileSize)}
                </Typography>
              </Box>
            </>
          )}
        </Box>
      )}
    </Paper>
  );
}

// ── Asset Row (list mode) ─────────────────────────────────────────────────
interface RowProps {
  asset: Asset;
  selectionMode?: boolean;
  selected?: boolean;
  onToggleSelect?: (id: string) => void;
  onDelete?: (asset: Asset) => void;
}

function AssetRow({ asset, selectionMode = false, selected = false, onToggleSelect, onDelete }: RowProps) {
  const navigate = useNavigate();
  const sc = statusColor[asset.status];

  const handleClick = () => {
    if (selectionMode) {
      onToggleSelect?.(asset.id);
    } else {
      navigate(`/assets/${asset.id}`);
    }
  };

  return (
    <Box
      onClick={handleClick}
      sx={{
        display: "flex", alignItems: "center", gap: 1.5,
        px: 1.5, py: 1,
        borderRadius: tokens.radius.md,
        cursor: "pointer",
        bgcolor: selected ? tokens.primary.subtle : "transparent",
        "&:hover": { bgcolor: selected ? tokens.primary.subtle : tokens.bg.hover },
        "&:hover .asset-row-delete": { opacity: 1 },
      }}
    >
      {selectionMode && (
        <Checkbox
          checked={selected}
          onClick={(e) => { e.stopPropagation(); onToggleSelect?.(asset.id); }}
          size="small"
          sx={{ p: 0.25, color: tokens.text.tertiary, "&.Mui-checked": { color: tokens.primary.main } }}
        />
      )}
      <Box sx={{ position: "relative", width: 48, height: 32, borderRadius: tokens.radius.sm, overflow: "hidden", flexShrink: 0, bgcolor: tokens.bg.overlay }}>
        <AssetThumbnail type={asset.type} src={asset.thumbnailUrl} iconSize={18} alt={asset.name} />
      </Box>
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Typography variant="body2" fontWeight={500} noWrap sx={{ fontSize: "0.82rem", color: tokens.text.primary }}>
          {asset.name}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {asset.mimeType}
        </Typography>
      </Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, color: tokens.text.secondary }}>
        {typeIcon[asset.type]}
        <Typography variant="caption" sx={{ fontSize: "0.7rem", minWidth: 28 }}>{asset.type}</Typography>
      </Box>
      {asset.duration && (
        <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.7rem", minWidth: 40, textAlign: "right" }}>
          {formatDuration(asset.duration)}
        </Typography>
      )}
      <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem", minWidth: 60, textAlign: "right" }}>
        {formatBytes(asset.fileSize)}
      </Typography>
      <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: sc, flexShrink: 0 }} />
      {!selectionMode && onDelete && (
        <IconButton
          className="asset-row-delete"
          size="small"
          onClick={(e) => { e.stopPropagation(); onDelete(asset); }}
          sx={{ opacity: 0, transition: "opacity 140ms ease", color: tokens.accent.red, width: 24, height: 24 }}
        >
          <DeleteOutlined sx={{ fontSize: 14 }} />
        </IconButton>
      )}
    </Box>
  );
}

// ── Main Asset Browser ────────────────────────────────────────────────────
interface Props {
  embedded?: boolean;
}

export default function AssetBrowser({ embedded = false }: Props) {
  const { activeSpace } = useSpace();
  const { token } = useAuth();
  const { t } = useTranslation();
  const { showToast } = useToast();
  const [filtered, setFiltered] = useState<Asset[]>([]);
  const [query, setQuery] = useState("");
  const [viewMode, setViewMode] = useState<"grid" | "list">("grid");
  const [cardSize, setCardSize] = useState<CardSize>("medium");
  // "remix" is a filter over a different kind of thing than the mime-based options, which is why it
  // is not an AssetType: picking it hides the asset grid entirely and leaves only the remix band.
  const [typeFilter, setTypeFilter] = useState<AssetType | "all" | "remix">("all");
  const remixOnly = typeFilter === "remix";
  const [sortState, setSortState] = useState<SortState>(DEFAULT_SORT);
  const [creator, setCreator] = useState("");
  const [collection, setCollection] = useState("");
  const creators = useCreatorOptions(token);
  const [collectionOptions, setCollectionOptions] = useState<FilterOption[]>([]);

  // ── Browse mode ──
  // The server caps /assets at 25 rows, so the collection arrives a page at a time.
  //
  // Sort, creator and collection are query parameters rather than a pass over `assets`: the rows
  // in memory are one page of the catalog, so narrowing or reordering them locally would answer a
  // question about the page instead of about the collection. `typeFilter` stays local because the
  // list route has no mime filter to delegate it to — which is why it is the one control that goes
  // wrong on a partly loaded catalog, and why the footer keeps saying how much is loaded.
  const loadAssetPage = useMemo(
    () => (token
      ? (paging: PagingParams) => listAssets(token, {
        ...paging,
        sort: sortState.sort,
        dir: sortState.dir,
        filters: [
          ...(creator ? [{ key: "creator", value: creator }] : []),
          ...(collection ? [{ key: "collection", value: collection }] : []),
        ],
      }).then(r => pageFrom(r, toAsset))
      : null),
    [token, sortState.sort, sortState.dir, creator, collection],
  );
  const page = usePagedList<Asset>(loadAssetPage, a => a.id);
  const assets = page.items;

  // ── Search mode ──
  // A non-empty query goes to /search/assets so the boolean/phrase syntax and the whole catalog
  // are reachable — a local filter would only ever see the pages already loaded.
  const search = useSearch();
  // Pulled out because the context value is a fresh object on every provider render — depending on
  // `search` itself would re-fire the query effect. `markUnavailable` is a stable useCallback.
  const { markUnavailable } = search;
  const [searchHits, setSearchHits] = useState<Asset[] | null>(null);
  const [searchTotal, setSearchTotal] = useState(0);
  const [searching, setSearching] = useState(false);
  /** True when the provider answered 403 — a narrowed permission, not an empty index. */
  const [searchDenied, setSearchDenied] = useState(false);

  const term = query.trim();
  /** Whether the grid is showing search results rather than the browsed collection. */
  const searchMode = term.length > 0 && search.available;

  // Libraries (for the upload dialog target)
  const [libraries, setLibraries] = useState<LibraryResponse[]>([]);

  // Upload dialog. The transfer itself is owned by the shared upload queue, so this only collects
  // the files and the target — see features/uploads/uploadQueue.ts.
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadFiles, setUploadFiles] = useState<File[]>([]);
  const [uploadLibrary, setUploadLibrary] = useState("");
  const [uploadOrigin, setUploadOrigin] = useState("upload");
  const fileInputRef = useRef<HTMLInputElement>(null);
  const uploads = useUploads();

  // Delete confirmation
  const [deleteTarget, setDeleteTarget] = useState<Asset | null>(null);

  // Multi-select + bulk
  const [selectionMode, setSelectionMode] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkTagOpen, setBulkTagOpen] = useState(false);
  const [bulkTag, setBulkTag] = useState("");
  const [bulkBusy, setBulkBusy] = useState(false);
  /** Anchor for the bulk "..." menu. The bar holds Tag and Delete inline; anything rarer goes here. */
  const [bulkMenuAnchor, setBulkMenuAnchor] = useState<HTMLElement | null>(null);

  // ── Remixes ──
  // Fetched separately from the assets rather than merged into one feed: a union query over two
  // tables would need a shared sort key and a compound cursor, and the band reads better pinned
  // above the grid anyway. Member assets stay in the main feed - hiding them would mean an
  // anti-join on the hottest query in the product and assets that silently vanish once grouped.
  const [remixes, setRemixes] = useState<RemixResponse[]>([]);
  const [remixName, setRemixName] = useState("");
  const [remixDialogOpen, setRemixDialogOpen] = useState(false);
  const [searchParams, setSearchParams] = useSearchParams();
  /** The opened remix lives in the URL so it is deep-linkable, shareable and drivable from a test. */
  const openRemixUuid = searchParams.get("remix");

  const reload = page.reload;

  // Debounced server-side search. Every keystroke aborts the request in flight, so a fast typist
  // costs one query rather than one per character.
  useEffect(() => {
    if (!token || !searchMode) {
      setSearchHits(null);
      setSearching(false);
      setSearchDenied(false);
      return;
    }
    const controller = new AbortController();
    setSearching(true);
    const timer = setTimeout(() => {
      searchAssets(
        token,
        // "remix" narrows a different axis than mime, so it contributes no mime prefix here; the
        // asset search it guards is not rendered under that filter anyway.
        {
          q: term,
          limit: PAGE_SIZE,
          mime: remixOnly ? undefined : mimeFilterFor(typeFilter),
          // Forwarded because /search/assets has its own `collection` parameter. The creator
          // filter has no counterpart there, which is why that control hides while searching
          // rather than silently ceasing to apply.
          collection: collection || undefined,
        },
        { signal: controller.signal },
      )
        .then(resp => {
          setSearchHits((resp.data ?? []).map(hitToCard));
          setSearchTotal(resp._metainfo?.totalHits ?? resp.data?.length ?? 0);
          setSearchDenied(false);
        })
        .catch((e: unknown) => {
          if (controller.signal.aborted) return;
          if (e instanceof SearchApiError && e.status === 503) {
            // Search went down mid-session. Retract the box app-wide rather than answering 503 on
            // every further keystroke; the grid falls back to filtering the pages already loaded.
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
    }, 250);

    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [token, term, searchMode, typeFilter, collection, markUnavailable]);

  useEffect(() => {
    if (!token) return;
    listLibraries(token, { limit: PAGE_SIZE }).then(resp => setLibraries(resp.data ?? [])).catch(() => { /* libraries optional */ });
  }, [token]);

  // Options for the collection filter. Sorted by name so the picker reads alphabetically whatever
  // order the catalog is in, and failing quietly: a user without READ_COLLECTION keeps the rest of
  // the toolbar and simply does not see this control.
  useEffect(() => {
    if (!token) {
      setCollectionOptions([]);
      return;
    }
    let cancelled = false;
    listCollections(token, { limit: 200, sort: "name", dir: "asc" })
      .then(resp => {
        if (cancelled) return;
        setCollectionOptions((resp.data ?? []).map(c => ({ value: c.uuid, label: c.name })));
      })
      .catch(() => { if (!cancelled) setCollectionOptions([]); });
    return () => { cancelled = true; };
  }, [token]);

  const toggleSelect = useCallback((id: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  const exitSelection = () => { setSelectionMode(false); setSelected(new Set()); };

  const openUploadDialog = () => {
    setUploadFiles([]);
    setUploadOrigin("upload");
    setUploadLibrary(libraries[0]?.uuid ?? "");
    setUploadOpen(true);
  };

  /**
   * Hand the files to the background queue and close. Progress and the completion toast belong to
   * the queue, so this screen no longer waits on the transfer and the user can navigate away.
   */
  const handleUpload = () => {
    if (!uploadFiles.length || !uploadLibrary) return;
    enqueue(uploadFiles, {
      libraryUuid: uploadLibrary,
      libraryName: libraries.find(l => l.uuid === uploadLibrary)?.name,
      origin: uploadOrigin.trim() || undefined,
    });
    setUploadOpen(false);
    setUploadFiles([]);
    showToast(t("assets.toast.uploadQueued", { count: uploadFiles.length }), "info");
  };

  // Assets that finished uploading in the background are not in this list yet. Reloading when the
  // completed count grows keeps the grid current without polling.
  const settledCount = uploads.doneCount + uploads.duplicateCount;
  const lastSettledRef = useRef(settledCount);
  useEffect(() => {
    if (settledCount > lastSettledRef.current) {
      reload();
    }
    lastSettledRef.current = settledCount;
  }, [settledCount, reload]);

  const handleDelete = async () => {
    if (!token || !deleteTarget) return;
    try {
      await deleteAsset(token, deleteTarget.id);
      page.setItems(prev => prev.filter(a => a.id !== deleteTarget.id));
      setSearchHits(prev => prev?.filter(a => a.id !== deleteTarget.id) ?? null);
      setDeleteTarget(null);
      showToast(t("assets.toast.deleted"), "success");
    } catch {
      showToast(t("assets.toast.deleteFailed"), "error");
    }
  };

  /**
   * Fill the remix band.
   *
   * With a query, the band shows the remixes that match it; without one, it shows the first page of
   * all of them. Both go through here so the band means the same thing in both modes: "the remixes
   * relevant to what you are looking at".
   *
   * A search hit is not a RemixResponse - it carries a uuid and a title and no member count - so it
   * is mapped onto the card shape with the count omitted rather than faked.
   */
  const reloadRemixes = useCallback(async () => {
    if (!token) return;
    try {
      if (term) {
        const response = await searchRemixes(token, term, { limit: PAGE_SIZE });
        // memberCount is deliberately omitted: a search hit does not carry one, and the card shows
        // no count rather than a made-up one.
        setRemixes((response.data ?? []).map(hit => ({
          uuid: hit.uuid,
          name: hit.title,
          description: hit.subtitle,
        })));
      } else {
        const response = await listRemixes(token);
        setRemixes(response.data ?? []);
      }
    } catch {
      // A caller without READ_REMIX gets a 403 from either route. That is a narrowed permission
      // rather than an error worth a toast: the band simply does not appear.
      setRemixes([]);
    }
  }, [token, term]);

  useEffect(() => { reloadRemixes(); }, [reloadRemixes]);

  const openRemix = (remix: RemixResponse) => {
    const next = new URLSearchParams(searchParams);
    next.set("remix", remix.uuid);
    setSearchParams(next, { replace: false });
  };

  const closeRemix = () => {
    const next = new URLSearchParams(searchParams);
    next.delete("remix");
    setSearchParams(next, { replace: false });
  };

  /**
   * Combine the current selection into a new remix.
   *
   * One request, not a create followed by an add: the members ride along with the create, so a
   * failure cannot leave a named but empty remix behind.
   */
  const handleCombineIntoRemix = async () => {
    if (!token || selected.size === 0 || !remixName.trim()) return;
    setBulkBusy(true);
    try {
      const remix = await combineIntoRemix(token, remixName.trim(), Array.from(selected));
      showToast(t("remix.toast.created", { count: remix.memberCount }), "success");
      setRemixDialogOpen(false);
      setRemixName("");
      exitSelection();
      await reloadRemixes();
    } catch {
      showToast(t("remix.error.create"), "error");
    } finally {
      setBulkBusy(false);
    }
  };

  const handleBulkDelete = async () => {
    if (!token || selected.size === 0) return;
    setBulkBusy(true);
    const ids = Array.from(selected);
    const results = await Promise.allSettled(ids.map(id => deleteAsset(token, id)));
    const okIds = ids.filter((_, i) => results[i].status === "fulfilled");
    page.setItems(prev => prev.filter(a => !okIds.includes(a.id)));
    setSearchHits(prev => prev?.filter(a => !okIds.includes(a.id)) ?? null);
    const failed = ids.length - okIds.length;
    showToast(
      failed === 0 ? t("assets.toast.bulkDeleted", { count: okIds.length })
        : t("assets.toast.bulkDeletedPartial", { ok: okIds.length, failed }),
      failed === 0 ? "success" : "error"
    );
    setBulkBusy(false);
    exitSelection();
  };

  const handleBulkTag = async () => {
    if (!token || selected.size === 0 || !bulkTag.trim()) return;
    setBulkBusy(true);
    // Bulk update is keyed by sha512; resolve it from the loaded asset responses.
    const selectedAssets = assets.filter(a => selected.has(a.id));
    const entries = selectedAssets
      .filter(a => a.sha512)
      .map(a => ({
        hashes: { sha512: a.sha512! },
        update: { meta: { tags: [...a.tags, bulkTag.trim()] } },
      }));
    try {
      if (entries.length > 0) {
        await bulkUpdateAssets(token, { assets: entries });
      }
      showToast(t("assets.toast.bulkTagged", { count: entries.length }), "success");
      setBulkTagOpen(false);
      setBulkTag("");
      exitSelection();
      reload();
    } catch {
      showToast(t("assets.toast.bulkTagFailed"), "error");
    } finally {
      setBulkBusy(false);
    }
  };

  useEffect(() => {
    // Search mode: the server already applied both the term and `?mime=`, so the hits are the
    // result — filtering them again locally would only re-narrow a narrowed set.
    if (searchMode) {
      setFiltered(searchHits ?? []);
      return;
    }

    let res = assets;
    if (typeFilter !== "all" && !remixOnly) res = res.filter(a => a.type === typeFilter);
    // Search is unavailable but the user typed anyway: fall back to filtering the pages already
    // loaded, and say so below rather than pretending this covered the catalog.
    if (term) {
      const q = term.toLowerCase();
      res = res.filter(a =>
        a.name.toLowerCase().includes(q) ||
        a.tags.some(tag => tag.toLowerCase().includes(q)) ||
        a.description.toLowerCase().includes(q)
      );
    }
    setFiltered(res);
  }, [assets, term, typeFilter, searchMode, searchHits]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Toolbar */}
      <Box
        sx={{
          px: embedded ? 1.5 : 2.5,
          py: 1.5,
          borderBottom: `1px solid ${tokens.border.subtle}`,
          bgcolor: tokens.bg.surface,
          display: "flex",
          flexDirection: "column",
          gap: 1.25,
        }}
      >
        {!embedded && (
          <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <Box>
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("assets.title")}</Typography>
              <Typography variant="caption" color="text.secondary">{activeSpace?.name}</Typography>
            </Box>
          </Box>
        )}

        <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
          <TextField
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={t("assets.search.placeholder")}
            size="small"
            data-testid="assets-search"
            sx={{ flex: 1, minWidth: 180 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                </InputAdornment>
              ),
            }}
          />

          <FormControl size="small" sx={{ minWidth: 80 }}>
            <Select
              value={typeFilter}
              onChange={(e: SelectChangeEvent) => setTypeFilter(e.target.value as AssetType | "all" | "remix")}
              displayEmpty
              sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
            >
              <MenuItem value="all">{t("assets.filter.allTypes")}</MenuItem>
              <MenuItem value="video">{t("assets.filter.video")}</MenuItem>
              <MenuItem value="image">{t("assets.filter.image")}</MenuItem>
              <MenuItem value="audio">{t("assets.filter.audio")}</MenuItem>
              <MenuItem value="document">{t("assets.filter.document")}</MenuItem>
              <MenuItem value="remix" data-testid="assets-filter-remix">{t("assets.filter.remix")}</MenuItem>
            </Select>
          </FormControl>

          {collectionOptions.length > 0 && (
            <ListFilterSelect
              value={collection}
              onChange={setCollection}
              options={collectionOptions}
              allLabel={t("assets.filter.allCollections")}
              testId="assets-filter-collection"
              minWidth={150}
            />
          )}

          {/* Creator and sort apply to the browsed catalog. /search/assets ranks by relevance and
              takes no creator, so offering either while a search term is active would show
              controls that quietly do nothing. */}
          {!searchMode && creators.length > 0 && (
            <ListFilterSelect
              value={creator}
              onChange={setCreator}
              options={creators}
              allLabel={t("assets.filter.allCreators")}
              testId="assets-filter-creator"
              minWidth={150}
            />
          )}

          {!searchMode && (
            <ListSortControl value={sortState} onChange={setSortState} testId="assets-sort" />
          )}

          <ToggleButtonGroup value={viewMode} exclusive onChange={(_, v) => v && setViewMode(v)} size="small">
            <ToggleButton value="grid" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important` }}>
              <GridViewOutlined sx={{ fontSize: 16 }} />
            </ToggleButton>
            <ToggleButton value="list" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important` }}>
              <FormatListBulletedOutlined sx={{ fontSize: 16 }} />
            </ToggleButton>
          </ToggleButtonGroup>

          <ToggleButtonGroup
            value={cardSize}
            exclusive
            onChange={(_, v) => v && setCardSize(v as CardSize)}
            size="small"
            sx={{ visibility: viewMode === "grid" ? "visible" : "hidden" }}
          >
            <ToggleButton value="small" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important`, px: 0.75 }}>
              <Tooltip title={t("assets.tooltip.small")}><PhotoSizeSelectSmallOutlined sx={{ fontSize: 14 }} /></Tooltip>
            </ToggleButton>
            <ToggleButton value="medium" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important`, px: 0.75 }}>
              <Tooltip title={t("assets.tooltip.medium")}><PhotoSizeSelectActualOutlined sx={{ fontSize: 14 }} /></Tooltip>
            </ToggleButton>
            <ToggleButton value="large" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important`, px: 0.75 }}>
              <Tooltip title={t("assets.tooltip.large")}><PhotoSizeSelectLargeOutlined sx={{ fontSize: 14 }} /></Tooltip>
            </ToggleButton>
          </ToggleButtonGroup>

          <Box sx={{ flexGrow: 1 }} />

          <Button
            size="small"
            variant="text"
            onClick={() => { if (selectionMode) exitSelection(); else setSelectionMode(true); }}
            sx={{ color: selectionMode ? tokens.primary.main : tokens.text.secondary, fontSize: "0.78rem" }}
          >
            {selectionMode ? t("assets.button.cancelSelect") : t("assets.button.select")}
          </Button>

          <Button
            size="small"
            variant="contained"
            startIcon={<CloudUploadOutlined sx={{ fontSize: 16 }} />}
            onClick={openUploadDialog}
            sx={{ fontSize: "0.78rem" }}
          >
            {t("assets.button.upload")}
          </Button>
        </Box>

        {/* Bulk action bar (visible in selection mode) */}
        {selectionMode && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, bgcolor: tokens.primary.subtle, borderRadius: tokens.radius.md, px: 1.25, py: 0.75 }}>
            <Typography variant="caption" sx={{ fontSize: "0.75rem", color: tokens.primary.light, fontWeight: 600 }}>
              {t("assets.select.count", { count: selected.size })}
            </Typography>
            <Box sx={{ flexGrow: 1 }} />
            <Button size="small" startIcon={<LocalOfferOutlined sx={{ fontSize: 15 }} />} disabled={selected.size === 0 || bulkBusy}
              onClick={() => { setBulkTag(""); setBulkTagOpen(true); }} sx={{ fontSize: "0.75rem", color: tokens.text.secondary }}>
              {t("assets.button.bulkTag")}
            </Button>
            <Button size="small" startIcon={<DeleteOutlined sx={{ fontSize: 15 }} />} disabled={selected.size === 0 || bulkBusy}
              onClick={handleBulkDelete} sx={{ fontSize: "0.75rem", color: tokens.accent.red }}>
              {t("assets.button.bulkDelete")}
            </Button>
            <Tooltip title={t("assets.button.moreActions")}>
              <span>
                <IconButton
                  size="small"
                  disabled={selected.size === 0 || bulkBusy}
                  onClick={e => setBulkMenuAnchor(e.currentTarget)}
                  data-testid="bulk-actions-menu-button"
                >
                  <MoreVertOutlined sx={{ fontSize: 16 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Menu anchorEl={bulkMenuAnchor} open={Boolean(bulkMenuAnchor)} onClose={() => setBulkMenuAnchor(null)}>
              <MenuItem
                data-testid="bulk-combine-remix"
                onClick={() => { setBulkMenuAnchor(null); setRemixName(""); setRemixDialogOpen(true); }}
              >
                <LayersOutlined sx={{ fontSize: 16, mr: 1 }} />
                {t("remix.action.combine")}
              </MenuItem>
            </Menu>
            <IconButton size="small" onClick={exitSelection}><CloseOutlined sx={{ fontSize: 16 }} /></IconButton>
          </Box>
        )}

        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }} data-testid="assets-count">
            {/* In search mode the server's hit total; otherwise the size of the collection, which
                is not the same as the number of rows fetched so far. */}
            {remixOnly
              ? t("assets.filter.remixCount", { count: remixes.length })
              : searchMode
                ? t("assets.search.hits", { count: searchTotal })
                : `${typeFilter === "all" ? page.totalCount : filtered.length} ${t("assets.count")}`}
          </Typography>
          {(typeFilter !== "all" || query || creator || collection) && (
            <Chip
              label={t("assets.filter.clear")}
              size="small"
              data-testid="assets-filter-clear"
              // Clears the filters, not the sort: an ordering is how the user reads the list
              // rather than a narrowing of it, and resetting it here would undo a choice they did
              // not ask to undo.
              onDelete={() => { setTypeFilter("all"); setQuery(""); setCreator(""); setCollection(""); }}
              sx={{ height: 18, fontSize: "0.65rem" }}
            />
          )}
        </Box>

        {/* Searching without a search backend still filters, but only over what has been fetched.
            Say so — a quietly partial result is the defect this screen was fixed for. */}
        {term && !search.available && !search.loading && (
          <Typography
            variant="caption"
            data-testid="assets-search-degraded"
            sx={{ color: tokens.accent.amber, fontSize: "0.7rem" }}
          >
            {t("assets.search.unavailable", { count: assets.length })}
          </Typography>
        )}
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: embedded ? 1.5 : 2.5 }}>
        {page.loading || (searchMode && searching && searchHits === null) ? (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2 }}>
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} variant="rounded" height={160} sx={{ borderRadius: tokens.radius.lg, bgcolor: tokens.bg.elevated }} />
            ))}
          </Box>
        ) : assets.length === 0 && remixes.length === 0 && !searchMode ? (
          // Nothing at all yet — invite the user to upload their first asset.
          <EmptyState
            icon={PermMediaOutlined}
            title={t("assets.empty.title")}
            description={t("assets.empty.description")}
            actionLabel={t("assets.empty.action")}
            actionIcon={<CloudUploadOutlined sx={{ fontSize: 18 }} />}
            onAction={openUploadDialog}
            testId="assets-empty-state"
            compact={embedded}
          />
        ) : (remixOnly ? remixes.length === 0 : filtered.length === 0 && remixes.length === 0) ? (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }} data-testid="assets-no-match">
            <SearchOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">
              {searchDenied ? t("assets.search.denied") : t("assets.empty.noMatch")}
            </Typography>
          </Box>
        ) : viewMode === "grid" ? (
          <Box sx={{ display: "grid", gridTemplateColumns: `repeat(auto-fill, minmax(${cardSize === "small" ? "120px" : cardSize === "large" ? "260px" : "190px"}, 1fr))`, gap: cardSize === "small" ? 1 : 2 }}>
            {/* Remixes lead the grid. Not a separate view: a remix is something you browse
                alongside your assets, and pinning it here keeps one screen instead of two. In
                search mode these are the remixes matching the query, not all of them. */}
            {remixes.map(r => (
              <RemixCard key={r.uuid} remix={r} cardSize={cardSize} onOpen={openRemix} />
            ))}
            {!remixOnly && filtered.map(a => (
              <AssetCard
                key={a.id}
                asset={a}
                cardSize={cardSize}
                selectionMode={selectionMode}
                selected={selected.has(a.id)}
                onToggleSelect={toggleSelect}
                onDelete={setDeleteTarget}
              />
            ))}
          </Box>
        ) : (
          <Paper elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.lg, overflow: "hidden" }}>
            {(remixOnly ? [] : filtered).map((a, i) => (
              <React.Fragment key={a.id}>
                <AssetRow
                  asset={a}
                  selectionMode={selectionMode}
                  selected={selected.has(a.id)}
                  onToggleSelect={toggleSelect}
                  onDelete={setDeleteTarget}
                />
                {i < filtered.length - 1 && <Box sx={{ height: 1, bgcolor: tokens.border.subtle, mx: 1.5 }} />}
              </React.Fragment>
            ))}
          </Paper>
        )}

        {/* Browse mode pages the collection; in search mode the server already returned the top
            hits and deep paging is the /search screen's job, not the grid's. */}
        {!searchMode && !remixOnly && !page.loading && (
          <ListPaging
            loaded={assets.length}
            total={page.totalCount}
            hasMore={page.hasMore}
            loadingMore={page.loadingMore}
            onLoadMore={page.loadMore}
            testId="assets-paging"
          />
        )}
      </Box>

      {/* Name the remix about to be made from the selection. */}
      <Dialog
        open={remixDialogOpen}
        onClose={() => setRemixDialogOpen(false)}
        data-testid="remix-create-dialog"
        PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 420 } }}
      >
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("remix.dialog.createTitle")}</DialogTitle>
        <DialogContent sx={{ pt: "8px !important" }}>
          <Typography variant="body2" sx={{ color: tokens.text.secondary, mb: 1.5 }}>
            {t("remix.dialog.createHint", { count: selected.size })}
          </Typography>
          <TextField
            autoFocus
            fullWidth
            size="small"
            label={t("remix.dialog.nameLabel")}
            value={remixName}
            onChange={e => setRemixName(e.target.value)}
            onKeyDown={e => { if (e.key === "Enter") handleCombineIntoRemix(); }}
            inputProps={{ "data-testid": "remix-create-name" }}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setRemixDialogOpen(false)} size="small">{t("common.cancel")}</Button>
          <Button
            onClick={handleCombineIntoRemix}
            size="small"
            variant="contained"
            disabled={!remixName.trim() || bulkBusy}
            data-testid="remix-create-submit"
          >
            {t("remix.dialog.createSubmit")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* The opened remix. Its open state lives in ?remix=<uuid>, so a deep link lands here. */}
      <RemixDialog remixUuid={openRemixUuid} onClose={closeRemix} onChanged={reloadRemixes} />

      {/* Upload dialog */}
      <Dialog open={uploadOpen} onClose={() => setUploadOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 420 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("assets.dialog.upload")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            hidden
            onChange={e => setUploadFiles(Array.from(e.target.files ?? []))}
          />
          <Button variant="outlined" startIcon={<CloudUploadOutlined sx={{ fontSize: 16 }} />} onClick={() => fileInputRef.current?.click()} sx={{ justifyContent: "flex-start" }}>
            {uploadFiles.length === 1
              ? uploadFiles[0].name
              : uploadFiles.length > 1
                ? t("assets.upload.filesSelected", { count: uploadFiles.length })
                : t("assets.upload.pickFile")}
          </Button>
          {uploadFiles.length > 0 && (
            <Typography variant="caption" color="text.secondary">
              {formatBytes(uploadFiles.reduce((sum, f) => sum + f.size, 0))}
            </Typography>
          )}
          <FormControl size="small" fullWidth>
            <InputLabel>{t("assets.upload.library")}</InputLabel>
            <Select value={uploadLibrary} label={t("assets.upload.library")} onChange={(e: SelectChangeEvent) => setUploadLibrary(e.target.value)}>
              {libraries.map(l => <MenuItem key={l.uuid} value={l.uuid}>{l.name}</MenuItem>)}
            </Select>
          </FormControl>
          <TextField label={t("assets.upload.origin")} size="small" value={uploadOrigin} onChange={e => setUploadOrigin(e.target.value)} fullWidth />
          <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
            {t("assets.upload.backgroundHint")}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setUploadOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{t("assets.button.cancel")}</Button>
          <Button onClick={handleUpload} size="small" variant="contained" disabled={!uploadFiles.length || !uploadLibrary}>
            {t("assets.button.upload")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirmation */}
      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 340 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("assets.dialog.delete")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">{t("assets.confirm.delete", { name: deleteTarget?.name })}</Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteTarget(null)} size="small" sx={{ color: tokens.text.secondary }}>{t("assets.button.cancel")}</Button>
          <Button onClick={handleDelete} size="small" variant="contained" sx={{ bgcolor: tokens.accent.red, "&:hover": { bgcolor: tokens.accent.red } }}>{t("assets.button.delete")}</Button>
        </DialogActions>
      </Dialog>

      {/* Bulk tag dialog */}
      <Dialog open={bulkTagOpen} onClose={() => !bulkBusy && setBulkTagOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 360 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("assets.dialog.bulkTag", { count: selected.size })}</DialogTitle>
        <DialogContent sx={{ pt: "8px !important" }}>
          <TextField label={t("assets.label.tag")} size="small" value={bulkTag} onChange={e => setBulkTag(e.target.value)} autoFocus fullWidth />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setBulkTagOpen(false)} size="small" disabled={bulkBusy} sx={{ color: tokens.text.secondary }}>{t("assets.button.cancel")}</Button>
          <Button onClick={handleBulkTag} size="small" variant="contained" disabled={!bulkTag.trim() || bulkBusy}>{t("assets.button.apply")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
