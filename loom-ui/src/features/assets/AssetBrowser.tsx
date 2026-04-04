import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, TextField, InputAdornment, Chip, IconButton,
  ToggleButtonGroup, ToggleButton, Paper, Skeleton, Tooltip,
  FormControl, Select, MenuItem, SelectChangeEvent,
} from "@mui/material";
import {
  SearchOutlined, GridViewOutlined, FormatListBulletedOutlined,
  PlayCircleOutline, ImageOutlined, AudiotrackOutlined, InsertDriveFileOutlined,
  FilterListOutlined, Circle, PhotoSizeSelectSmallOutlined,
  PhotoSizeSelectActualOutlined, PhotoSizeSelectLargeOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Asset, AssetType, AssetStatus } from "../../types";
import { mockAssetService, mockLibraryService } from "../../mock/services";
import { useProject } from "../../context/ProjectContext";
import { LIBRARIES } from "../../mock/data";

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

function AssetCard({ asset, cardSize = "medium" }: { asset: Asset; cardSize?: CardSize }) {
  const navigate = useNavigate();
  const sc = statusColor[asset.status];

  return (
    <Paper
      elevation={0}
      onClick={() => navigate(`/assets/${asset.id}`)}
      sx={{
        cursor: "pointer",
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        overflow: "hidden",
        transition: "border-color 140ms ease, box-shadow 140ms ease",
        "&:hover": {
          borderColor: tokens.border.strong,
          boxShadow: `0 4px 20px rgba(0,0,0,0.35)`,
        },
      }}
    >
      {/* Thumbnail */}
      <Box sx={{ position: "relative", paddingTop: "56.25%", bgcolor: tokens.bg.overlay }}>
        <img
          src={asset.thumbnailUrl}
          alt={asset.name}
          style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }}
          loading="lazy"
        />
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
function AssetRow({ asset }: { asset: Asset }) {
  const navigate = useNavigate();
  const sc = statusColor[asset.status];
  const lib = LIBRARIES.find(l => l.id === asset.libraryId);

  return (
    <Box
      onClick={() => navigate(`/assets/${asset.id}`)}
      sx={{
        display: "flex", alignItems: "center", gap: 1.5,
        px: 1.5, py: 1,
        borderRadius: tokens.radius.md,
        cursor: "pointer",
        "&:hover": { bgcolor: tokens.bg.hover },
      }}
    >
      <Box sx={{ width: 48, height: 32, borderRadius: tokens.radius.sm, overflow: "hidden", flexShrink: 0, bgcolor: tokens.bg.overlay }}>
        <img src={asset.thumbnailUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} loading="lazy" />
      </Box>
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Typography variant="body2" fontWeight={500} noWrap sx={{ fontSize: "0.82rem", color: tokens.text.primary }}>
          {asset.name}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {lib?.name ?? asset.libraryId}
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
    </Box>
  );
}

// ── Main Asset Browser ────────────────────────────────────────────────────
interface Props {
  embedded?: boolean;
}

export default function AssetBrowser({ embedded = false }: Props) {
  const { activeProject } = useProject();
  const [assets, setAssets] = useState<Asset[]>([]);
  const [filtered, setFiltered] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [viewMode, setViewMode] = useState<"grid" | "list">("grid");
  const [cardSize, setCardSize] = useState<CardSize>("medium");
  const [statusFilter, setStatusFilter] = useState<AssetStatus | "all">("all");
  const [typeFilter, setTypeFilter] = useState<AssetType | "all">("all");
  const [libraryFilter, setLibraryFilter] = useState<string>("all");

  useEffect(() => {
    if (!activeProject) return;
    setLoading(true);
    mockAssetService.getByProject(activeProject.id).then((a) => {
      setAssets(a);
      setFiltered(a);
      setLoading(false);
    });
  }, [activeProject]);

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

  const libs = LIBRARIES.filter(l => l.projectId === activeProject?.id);

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
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Assets</Typography>
              <Typography variant="caption" color="text.secondary">{activeProject?.name}</Typography>
            </Box>
          </Box>
        )}

        <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
          <TextField
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search assets, tags…"
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
              <MenuItem value="all">All Status</MenuItem>
              <MenuItem value="ready">Ready</MenuItem>
              <MenuItem value="processing">Processing</MenuItem>
              <MenuItem value="failed">Failed</MenuItem>
              <MenuItem value="archived">Archived</MenuItem>
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 80 }}>
            <Select
              value={typeFilter}
              onChange={(e: SelectChangeEvent) => setTypeFilter(e.target.value as AssetType | "all")}
              displayEmpty
              sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
            >
              <MenuItem value="all">All Types</MenuItem>
              <MenuItem value="video">Video</MenuItem>
              <MenuItem value="image">Image</MenuItem>
              <MenuItem value="audio">Audio</MenuItem>
              <MenuItem value="document">Document</MenuItem>
            </Select>
          </FormControl>

          {libs.length > 0 && (
            <FormControl size="small" sx={{ minWidth: 100 }}>
              <Select
                value={libraryFilter}
                onChange={(e: SelectChangeEvent) => setLibraryFilter(e.target.value)}
                displayEmpty
                sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
              >
                <MenuItem value="all">All Libraries</MenuItem>
                {libs.map(l => <MenuItem key={l.id} value={l.id}>{l.name}</MenuItem>)}
              </Select>
            </FormControl>
          )}

          <ToggleButtonGroup value={viewMode} exclusive onChange={(_, v) => v && setViewMode(v)} size="small">
            <ToggleButton value="grid" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important` }}>
              <GridViewOutlined sx={{ fontSize: 16 }} />
            </ToggleButton>
            <ToggleButton value="list" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important` }}>
              <FormatListBulletedOutlined sx={{ fontSize: 16 }} />
            </ToggleButton>
          </ToggleButtonGroup>

          {viewMode === "grid" && (
            <ToggleButtonGroup value={cardSize} exclusive onChange={(_, v) => v && setCardSize(v as CardSize)} size="small">
              <ToggleButton value="small" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important`, px: 0.75 }}>
                <Tooltip title="Small — thumbnail only"><PhotoSizeSelectSmallOutlined sx={{ fontSize: 14 }} /></Tooltip>
              </ToggleButton>
              <ToggleButton value="medium" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important`, px: 0.75 }}>
                <Tooltip title="Medium"><PhotoSizeSelectActualOutlined sx={{ fontSize: 14 }} /></Tooltip>
              </ToggleButton>
              <ToggleButton value="large" sx={{ border: `1px solid ${tokens.border.default}`, borderRadius: `${tokens.radius.sm} !important`, px: 0.75 }}>
                <Tooltip title="Large — tags, size, duration"><PhotoSizeSelectLargeOutlined sx={{ fontSize: 14 }} /></Tooltip>
              </ToggleButton>
            </ToggleButtonGroup>
          )}
        </Box>

        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
            {filtered.length} assets
          </Typography>
          {(statusFilter !== "all" || typeFilter !== "all" || libraryFilter !== "all" || query) && (
            <Chip
              label="Clear filters"
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
        ) : filtered.length === 0 ? (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
            <SearchOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">No assets match your filters</Typography>
          </Box>
        ) : viewMode === "grid" ? (
          <Box sx={{ display: "grid", gridTemplateColumns: `repeat(auto-fill, minmax(${cardSize === "small" ? "120px" : cardSize === "large" ? "260px" : "190px"}, 1fr))`, gap: cardSize === "small" ? 1 : 2 }}>
            {filtered.map(a => <AssetCard key={a.id} asset={a} cardSize={cardSize} />)}
          </Box>
        ) : (
          <Paper elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.lg, overflow: "hidden" }}>
            {filtered.map((a, i) => (
              <React.Fragment key={a.id}>
                <AssetRow asset={a} />
                {i < filtered.length - 1 && <Box sx={{ height: 1, bgcolor: tokens.border.subtle, mx: 1.5 }} />}
              </React.Fragment>
            ))}
          </Paper>
        )}
      </Box>
    </Box>
  );
}
