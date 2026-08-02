import React, { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, TextField, InputAdornment, Chip, IconButton,
  ToggleButtonGroup, ToggleButton, Paper, Skeleton, Tooltip,
  FormControl, Select, MenuItem, SelectChangeEvent,
  Button, Checkbox, InputLabel,
  Dialog, DialogTitle, DialogContent, DialogActions,
} from "@mui/material";
import {
  SearchOutlined, GridViewOutlined, FormatListBulletedOutlined,
  PlayCircleOutline, ImageOutlined, AudiotrackOutlined, InsertDriveFileOutlined,
  FilterListOutlined, Circle, PhotoSizeSelectSmallOutlined,
  PhotoSizeSelectActualOutlined, PhotoSizeSelectLargeOutlined,
  CloudUploadOutlined, DeleteOutlined, LocalOfferOutlined, CloseOutlined,
  PermMediaOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import AssetThumbnail from "../../components/AssetThumbnail";
import EmptyState from "../../components/EmptyState";
import { Asset, AssetType, AssetStatus } from "../../types";
import { useAuth } from "../../context/AuthContext";
import {
  listAssets, AssetResponse, deleteAsset, bulkUpdateAssets, assetBinaryUrl,
} from "../../api/assets";
import { listLibraries, LibraryResponse } from "../../api/libraries";
import { useSpace } from "../../context/SpaceContext";
import { useToast } from "../../context/ToastContext";
import { useUploads } from "../uploads/UploadContext";
import { enqueue } from "../uploads/uploadQueue";
import { useTranslation } from "react-i18next";

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

/** Map a Loom REST AssetResponse to the local Asset type used by the UI. */
function toAsset(r: AssetResponse): Asset {
  const mime = r.file?.mimeType ?? "";
  let type: AssetType = "unknown";
  if (mime.startsWith("image/")) type = "image";
  else if (mime.startsWith("video/")) type = "video";
  else if (mime.startsWith("audio/")) type = "audio";
  else if (mime.startsWith("application/") || mime.startsWith("text/")) type = "document";

  const video = r.videoComponents?.[0];
  const image = r.imageComponents?.[0];

  return {
    id: r.uuid,
    spaceId: "",
    libraryId: "",
    name: r.file?.filename ?? r.uuid,
    type,
    status: "ready" as AssetStatus,
    tags: (r.tags ?? []).map(t => t.name),
    description: "",
    duration: video?.duration,
    width: video?.width ?? image?.width,
    height: video?.height ?? image?.height,
    fileSize: r.file?.size ?? 0,
    mimeType: mime,
    sha512: r.hashes?.sha512,
    // Only images can be shown by an <img>; everything else falls back to the type placeholder.
    thumbnailUrl: type === "image" ? assetBinaryUrl(r.uuid) : "",
    url: "",
    ownerId: r.status?.creator?.uuid ?? "",
    collectionIds: (r.collections ?? []).map(c => c.uuid),
    createdAt: r.status?.created ?? "",
    updatedAt: r.status?.edited ?? "",
    metadata: {},
  };
}

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
  const [assets, setAssets] = useState<Asset[]>([]);
  const [filtered, setFiltered] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [viewMode, setViewMode] = useState<"grid" | "list">("grid");
  const [cardSize, setCardSize] = useState<CardSize>("medium");
  const [statusFilter, setStatusFilter] = useState<AssetStatus | "all">("all");
  const [typeFilter, setTypeFilter] = useState<AssetType | "all">("all");
  const [libraryFilter, setLibraryFilter] = useState<string>("all");

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

  const reload = useCallback(() => {
    if (!token) return;
    setLoading(true);
    listAssets(token).then((resp) => {
      const mapped = (resp.data ?? []).map(toAsset);
      setAssets(mapped);
      setLoading(false);
    }).catch(() => {
      setLoading(false);
    });
  }, [token]);

  useEffect(() => {
    reload();
  }, [reload]);

  useEffect(() => {
    if (!token) return;
    listLibraries(token).then(resp => setLibraries(resp.data ?? [])).catch(() => { /* libraries optional */ });
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
      setAssets(prev => prev.filter(a => a.id !== deleteTarget.id));
      setDeleteTarget(null);
      showToast(t("assets.toast.deleted"), "success");
    } catch {
      showToast(t("assets.toast.deleteFailed"), "error");
    }
  };

  const handleBulkDelete = async () => {
    if (!token || selected.size === 0) return;
    setBulkBusy(true);
    const ids = Array.from(selected);
    const results = await Promise.allSettled(ids.map(id => deleteAsset(token, id)));
    const okIds = ids.filter((_, i) => results[i].status === "fulfilled");
    setAssets(prev => prev.filter(a => !okIds.includes(a.id)));
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
    let res = assets;
    if (statusFilter !== "all") res = res.filter(a => a.status === statusFilter);
    if (typeFilter !== "all") res = res.filter(a => a.type === typeFilter);
    if (libraryFilter !== "all") res = res.filter(a => a.libraryId === libraryFilter);
    if (query.trim()) {
      const q = query.toLowerCase();
      res = res.filter(a =>
        a.name.toLowerCase().includes(q) ||
        a.tags.some(t => t.includes(q)) ||
        a.description.toLowerCase().includes(q)
      );
    }
    setFiltered(res);
  }, [assets, query, statusFilter, typeFilter, libraryFilter]);

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
            sx={{ flex: 1, minWidth: 180 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                </InputAdornment>
              ),
            }}
          />

          <FormControl size="small" sx={{ minWidth: 90 }}>
            <Select
              value={statusFilter}
              onChange={(e: SelectChangeEvent) => setStatusFilter(e.target.value as AssetStatus | "all")}
              displayEmpty
              sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
            >
              <MenuItem value="all">{t("assets.filter.allStatus")}</MenuItem>
              <MenuItem value="ready">{t("assets.filter.ready")}</MenuItem>
              <MenuItem value="processing">{t("assets.filter.processing")}</MenuItem>
              <MenuItem value="failed">{t("assets.filter.failed")}</MenuItem>
              <MenuItem value="archived">{t("assets.filter.archived")}</MenuItem>
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 80 }}>
            <Select
              value={typeFilter}
              onChange={(e: SelectChangeEvent) => setTypeFilter(e.target.value as AssetType | "all")}
              displayEmpty
              sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
            >
              <MenuItem value="all">{t("assets.filter.allTypes")}</MenuItem>
              <MenuItem value="video">{t("assets.filter.video")}</MenuItem>
              <MenuItem value="image">{t("assets.filter.image")}</MenuItem>
              <MenuItem value="audio">{t("assets.filter.audio")}</MenuItem>
              <MenuItem value="document">{t("assets.filter.document")}</MenuItem>
            </Select>
          </FormControl>

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
            <IconButton size="small" onClick={exitSelection}><CloseOutlined sx={{ fontSize: 16 }} /></IconButton>
          </Box>
        )}

        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
            {filtered.length} {t("assets.count")}
          </Typography>
          {(statusFilter !== "all" || typeFilter !== "all" || libraryFilter !== "all" || query) && (
            <Chip
              label={t("assets.filter.clear")}
              size="small"
              onDelete={() => { setStatusFilter("all"); setTypeFilter("all"); setLibraryFilter("all"); setQuery(""); }}
              sx={{ height: 18, fontSize: "0.65rem" }}
            />
          )}
        </Box>
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: embedded ? 1.5 : 2.5 }}>
        {loading ? (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2 }}>
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} variant="rounded" height={160} sx={{ borderRadius: tokens.radius.lg, bgcolor: tokens.bg.elevated }} />
            ))}
          </Box>
        ) : assets.length === 0 ? (
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
        ) : filtered.length === 0 ? (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
            <SearchOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">{t("assets.empty.noMatch")}</Typography>
          </Box>
        ) : viewMode === "grid" ? (
          <Box sx={{ display: "grid", gridTemplateColumns: `repeat(auto-fill, minmax(${cardSize === "small" ? "120px" : cardSize === "large" ? "260px" : "190px"}, 1fr))`, gap: cardSize === "small" ? 1 : 2 }}>
            {filtered.map(a => (
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
            {filtered.map((a, i) => (
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
      </Box>

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
