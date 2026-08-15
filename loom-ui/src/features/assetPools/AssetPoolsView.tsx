import React, { useEffect, useMemo, useState } from "react";
import {
  Box, Typography, Paper, Chip, IconButton, Dialog, DialogTitle, DialogContent,
  DialogActions, Button, TextField, Tooltip, InputAdornment, MenuItem, Select,
  FormControl, InputLabel,
} from "@mui/material";
import {
  AddOutlined, DeleteOutlined, SearchOutlined, EditOutlined,
  FolderOutlined, CloudOutlined, StorageOutlined, HelpOutlineOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import EmptyState from "../../components/EmptyState";
import { AssetPool, AssetPoolType } from "../../types";
import { useToast } from "../../context/ToastContext";
import { useAuth } from "../../context/AuthContext";
import { listPools, createPool, updatePool, deletePool, PoolResponse } from "../../api/pools";
import type { PagingParams } from "../../api/paging";
import ListPaging from "../../components/ListPaging";
import { DEFAULT_SORT, ListFilterSelect, ListSortControl, type SortState } from "../../components/ListControls";
import { useCreatorOptions } from "../../hooks/useCreatorOptions";
import { pageFrom, usePagedList } from "../../hooks/usePagedList";
import { useTranslation } from "react-i18next";

function formatBytes(bytes: number): string {
  if (bytes >= 1e15) return `${(bytes / 1e15).toFixed(1)} PB`;
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(1)} TB`;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(0)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

function formatCount(n: number): string {
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)}M`;
  if (n >= 1e3) return `${(n / 1e3).toFixed(1)}K`;
  return String(n);
}

function PoolIcon({ type }: { type: AssetPoolType }) {
  return type === "s3"
    ? <CloudOutlined sx={{ fontSize: 20, color: tokens.accent.blue }} />
    : <FolderOutlined sx={{ fontSize: 20, color: tokens.accent.green }} />;
}

// ── Free Space Donut Chart ────────────────────────────────────────────────
function FreeSpaceDonut({ freeSpace, usedSpace, totalSize, size = 52 }: { freeSpace: number; usedSpace?: number; totalSize: number; size?: number }) {
  const used = usedSpace != null ? usedSpace : totalSize - freeSpace;
  const pct = totalSize > 0 ? Math.min(used / totalSize, 1) : 0;
  const r = (size - 6) / 2;
  const circ = 2 * Math.PI * r;
  const usedColor = pct > 0.9 ? tokens.accent.red : pct > 0.7 ? tokens.accent.amber : tokens.accent.green;

  return (
    <Tooltip title={`${formatBytes(freeSpace)} free / ${formatBytes(totalSize)} total (${Math.round((1 - pct) * 100)}% free)`}>
      <Box sx={{ position: "relative", width: size, height: size, flexShrink: 0 }}>
        <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={tokens.border.subtle} strokeWidth={5} />
          <circle
            cx={size / 2} cy={size / 2} r={r} fill="none"
            stroke={usedColor} strokeWidth={5}
            strokeDasharray={`${pct * circ} ${circ}`}
            strokeLinecap="round"
            transform={`rotate(-90 ${size / 2} ${size / 2})`}
          />
        </svg>
        <Box sx={{
          position: "absolute", inset: 0,
          display: "flex", alignItems: "center", justifyContent: "center",
        }}>
          <Typography sx={{ fontSize: "0.6rem", fontWeight: 700, color: tokens.text.secondary, lineHeight: 1 }}>
            {Math.round(pct * 100)}%
          </Typography>
        </Box>
      </Box>
    </Tooltip>
  );
}

// ── Pool Detail Card ──────────────────────────────────────────────────────
function PoolCard({ pool, onEdit, onDelete }: { pool: AssetPool; onEdit: () => void; onDelete: () => void }) {
  const hasFreeSpace = pool.freeSpace != null && pool.freeSpace >= 0;
  const totalForChart = hasFreeSpace && pool.totalSize > 0 ? pool.totalSize : (hasFreeSpace ? pool.freeSpace! : 0);

  return (
    <Paper
      elevation={0}
      data-testid="pool-card"
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        overflow: "hidden",
        transition: "border-color 140ms ease, box-shadow 140ms ease",
        "&:hover": { borderColor: tokens.border.strong, boxShadow: "0 4px 20px rgba(0,0,0,0.35)" },
        "&:hover .pool-actions": { opacity: 1 },
        position: "relative",
      }}
    >
      {/* Action buttons */}
      <Box
        className="pool-actions"
        sx={{
          position: "absolute", top: 6, right: 6, zIndex: 3,
          display: "flex", gap: 0.5, opacity: 0, transition: "opacity 140ms ease",
        }}
      >
        <Tooltip title="Edit pool">
          <IconButton
            size="small"
            onClick={onEdit}
            sx={{ bgcolor: "rgba(0,0,0,0.6)", color: tokens.primary.light, width: 24, height: 24, "&:hover": { bgcolor: "rgba(0,0,0,0.8)" } }}
          >
            <EditOutlined sx={{ fontSize: 13 }} />
          </IconButton>
        </Tooltip>
        <Tooltip title="Delete pool">
          <IconButton
            size="small"
            onClick={onDelete}
            sx={{ bgcolor: "rgba(0,0,0,0.6)", color: tokens.accent.red, width: 24, height: 24, "&:hover": { bgcolor: "rgba(0,0,0,0.8)" } }}
          >
            <DeleteOutlined sx={{ fontSize: 13 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {/* Color accent */}
      <Box sx={{ height: 3, bgcolor: pool.type === "s3" ? tokens.accent.blue : tokens.accent.green }} />

      {/* Header */}
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, p: 2, pb: 1.5 }}>
        <Box sx={{
          width: 36, height: 36, borderRadius: tokens.radius.md,
          bgcolor: pool.type === "s3" ? "rgba(46,168,255,0.12)" : "rgba(52,213,138,0.12)",
          display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0,
        }}>
          <PoolIcon type={pool.type} />
        </Box>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="subtitle2" fontWeight={700} noWrap sx={{ fontSize: "0.875rem" }}>
            {pool.name}
          </Typography>
          <Chip
            label={pool.type === "s3" ? "S3 Bucket" : "Filesystem"}
            size="small"
            sx={{
              height: 18, fontSize: "0.65rem", mt: 0.25,
              bgcolor: pool.type === "s3" ? "rgba(46,168,255,0.12)" : "rgba(52,213,138,0.12)",
              color: pool.type === "s3" ? tokens.accent.blue : tokens.accent.green,
            }}
          />
        </Box>
      </Box>

      {/* Location details */}
      <Box sx={{ px: 2, pb: 1.5 }}>
        {pool.type === "filesystem" && pool.fsPath && (
          <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.72rem", fontFamily: "monospace", wordBreak: "break-all" }}>
            {pool.fsPath}
          </Typography>
        )}
        {pool.type === "s3" && (
          <Box>
            <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.72rem", fontFamily: "monospace" }}>
              {pool.s3Bucket}
            </Typography>
            {pool.s3Region && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", display: "block" }}>
                {pool.s3Region} · {pool.s3Endpoint}
              </Typography>
            )}
          </Box>
        )}
      </Box>

      {/* Stats + Free Space Chart */}
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 2, pb: 2 }}>
        {hasFreeSpace && totalForChart > 0 && (
          <FreeSpaceDonut freeSpace={pool.freeSpace!} usedSpace={pool.usedSpace} totalSize={totalForChart} />
        )}
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.75, flex: 1 }}>
          <Chip label={`${formatCount(pool.assetCount)} assets`} size="small" sx={{ height: 18, fontSize: "0.65rem" }} />
          {hasFreeSpace && (
            <Chip label={`${formatBytes(pool.freeSpace!)} free`} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "rgba(52,213,138,0.12)", color: tokens.accent.green }} />
          )}
          {pool.usedSpace != null && pool.usedSpace > 0 && (
            <Chip label={`${formatBytes(pool.usedSpace)} used`} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "rgba(46,168,255,0.12)", color: tokens.accent.blue }} />
          )}
          <Chip label={formatBytes(pool.totalSize)} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "transparent", color: tokens.text.tertiary }} />
          <Chip label={new Date(pool.updatedAt).toLocaleDateString()} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "transparent", color: tokens.text.tertiary }} />
        </Box>
      </Box>
    </Paper>
  );
}

// ── Main View ─────────────────────────────────────────────────────────────

function mapResponseToPool(r: PoolResponse): AssetPool {
  return {
    id: r.uuid,
    name: r.name,
    type: r.s3Bucket ? "s3" : "filesystem",
    fsPath: r.fsPath,
    s3Bucket: r.s3Bucket,
    s3Region: r.s3Region,
    s3Endpoint: r.s3Endpoint,
    freeSpace: r.freeSpace,
    usedSpace: r.usedSpace,
    assetCount: 0,
    totalSize: 0,
    createdAt: r.status?.created ?? new Date().toISOString(),
    updatedAt: r.status?.edited ?? new Date().toISOString(),
  };
}

export default function AssetPoolsView() {
  const { showToast } = useToast();
  const { token } = useAuth();
  const { t } = useTranslation();
  const [sortState, setSortState] = useState<SortState>(DEFAULT_SORT);
  const [creator, setCreator] = useState("");
  const creators = useCreatorOptions(token);
  // /pools caps at 25 rows per page — page it rather than passing off page one as the whole set.
  // Sort and creator are query parameters, so changing either restarts the listing (LOOM_UI.md §7.5.2).
  const loadPage = useMemo(
    () => (token
      ? (paging: PagingParams) => listPools(token, {
        ...paging,
        sort: sortState.sort,
        dir: sortState.dir,
        filters: creator ? [{ key: "creator", value: creator }] : undefined,
      }).then(r => pageFrom(r, mapResponseToPool))
      : null),
    [token, sortState.sort, sortState.dir, creator],
  );
  const page = usePagedList<AssetPool>(loadPage, p => p.id);
  const pools = page.items;
  const setPools = page.setItems;
  const [query, setQuery] = useState("");

  // Create dialog
  const [createOpen, setCreateOpen] = useState(false);
  const [formName, setFormName] = useState("");
  const [formType, setFormType] = useState<AssetPoolType>("filesystem");
  const [formFsPath, setFormFsPath] = useState("");
  const [formS3Bucket, setFormS3Bucket] = useState("");
  const [formS3Region, setFormS3Region] = useState("");
  const [formS3Endpoint, setFormS3Endpoint] = useState("");
  const [saving, setSaving] = useState(false);

  // Edit dialog
  const [editPool, setEditPool] = useState<AssetPool | null>(null);

  // Delete dialog
  const [deleteTarget, setDeleteTarget] = useState<AssetPool | null>(null);

  useEffect(() => {
    if (page.error) showToast(t("assetPools.toast.loadFailed"), "error");
  }, [page.error]);

  const resetForm = () => {
    setFormName(""); setFormType("filesystem"); setFormFsPath("");
    setFormS3Bucket(""); setFormS3Region(""); setFormS3Endpoint("");
  };

  const handleCreate = async () => {
    if (!formName.trim() || !token) return;
    setSaving(true);
    try {
      const resp = await createPool(token, {
        name: formName.trim(),
        ...(formType === "filesystem" ? { fsPath: formFsPath.trim() } : {}),
        ...(formType === "s3" ? { s3Bucket: formS3Bucket.trim(), s3Region: formS3Region.trim(), s3Endpoint: formS3Endpoint.trim() } : {}),
      });
      setPools(prev => [...prev, mapResponseToPool(resp)]);
      resetForm();
      setCreateOpen(false);
      showToast(t("assetPools.toast.created"), "success");
    } catch {
      showToast(t("assetPools.toast.createFailed"), "error");
    } finally {
      setSaving(false);
    }
  };

  const openEdit = (pool: AssetPool) => {
    setEditPool(pool);
    setFormName(pool.name);
    setFormType(pool.type);
    setFormFsPath(pool.fsPath ?? "");
    setFormS3Bucket(pool.s3Bucket ?? "");
    setFormS3Region(pool.s3Region ?? "");
    setFormS3Endpoint(pool.s3Endpoint ?? "");
  };

  const handleEdit = async () => {
    if (!editPool || !formName.trim() || !token) return;
    setSaving(true);
    try {
      const resp = await updatePool(token, editPool.id, {
        name: formName.trim(),
        fsPath: formType === "filesystem" ? formFsPath.trim() : undefined,
        s3Bucket: formType === "s3" ? formS3Bucket.trim() : undefined,
        s3Region: formType === "s3" ? formS3Region.trim() : undefined,
        s3Endpoint: formType === "s3" ? formS3Endpoint.trim() : undefined,
      });
      const updated = mapResponseToPool(resp);
      setPools(prev => prev.map(p => p.id === updated.id ? updated : p));
      resetForm();
      setEditPool(null);
      showToast(t("assetPools.toast.updated"), "success");
    } catch {
      showToast(t("assetPools.toast.updateFailed"), "error");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget || !token) return;
    try {
      await deletePool(token, deleteTarget.id);
      setPools(prev => prev.filter(p => p.id !== deleteTarget.id));
      setDeleteTarget(null);
      showToast(t("assetPools.toast.deleted"), "success");
    } catch {
      showToast(t("assetPools.toast.deleteFailed"), "error");
    }
  };

  const filtered = pools.filter(p => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return (
      p.name.toLowerCase().includes(q) ||
      p.type.toLowerCase().includes(q) ||
      (p.fsPath?.toLowerCase().includes(q) ?? false) ||
      (p.s3Bucket?.toLowerCase().includes(q) ?? false) ||
      (p.s3Region?.toLowerCase().includes(q) ?? false)
    );
  });

  const fsCount = pools.filter(p => p.type === "filesystem").length;
  const s3Count = pools.filter(p => p.type === "s3").length;
  const totalAssets = pools.reduce((s, p) => s + p.assetCount, 0);
  const totalSize = pools.reduce((s, p) => s + p.totalSize, 0);

  // Shared form fields for create and edit
  const renderFormFields = () => (
    <>
      <TextField label={t("assetPools.form.name")} size="small" value={formName} onChange={e => setFormName(e.target.value)} autoFocus fullWidth />
      {/* The label needs an explicit id wired to the select: without it MUI emits no aria-labelledby,
          so the control has no accessible name - unreadable to a screen reader and unaddressable by label. */}
      <FormControl size="small" fullWidth>
        <InputLabel id="asset-pool-type-label">{t("assetPools.form.type")}</InputLabel>
        <Select labelId="asset-pool-type-label" id="asset-pool-type" value={formType} label={t("assetPools.form.type")} onChange={e => setFormType(e.target.value as AssetPoolType)}>
          <MenuItem value="filesystem">{t("assetPools.form.filesystem")}</MenuItem>
          <MenuItem value="s3">{t("assetPools.form.s3")}</MenuItem>
        </Select>
      </FormControl>
      {formType === "filesystem" && (
        <TextField label={t("assetPools.form.fsPath")} size="small" value={formFsPath} onChange={e => setFormFsPath(e.target.value)} placeholder="/mnt/media/pool" fullWidth />
      )}
      {formType === "s3" && (
        <>
          <TextField label={t("assetPools.form.s3Bucket")} size="small" value={formS3Bucket} onChange={e => setFormS3Bucket(e.target.value)} placeholder="my-bucket" fullWidth />
          <TextField label={t("assetPools.form.s3Region")} size="small" value={formS3Region} onChange={e => setFormS3Region(e.target.value)} placeholder="eu-central-1" fullWidth />
          <TextField label={t("assetPools.form.s3Endpoint")} size="small" value={formS3Endpoint} onChange={e => setFormS3Endpoint(e.target.value)} placeholder="https://s3.eu-central-1.amazonaws.com" fullWidth />
        </>
      )}
    </>
  );

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column", gap: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <StorageOutlined sx={{ fontSize: 20, color: tokens.primary.main }} />
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("assetPools.title")}</Typography>
              <Tooltip title={t("assetPools.tooltip.info")} arrow>
              <HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} />
            </Tooltip>
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <Chip label={`${fsCount} ${t("assetPools.chip.filesystem")}`} size="small" icon={<FolderOutlined sx={{ fontSize: 12 }} />} sx={{ height: 20, fontSize: "0.65rem" }} />
            <Chip label={`${s3Count} ${t("assetPools.chip.s3")}`} size="small" icon={<CloudOutlined sx={{ fontSize: 12 }} />} sx={{ height: 20, fontSize: "0.65rem" }} />
            <Chip label={`${formatCount(totalAssets)} ${t("assetPools.chip.assets")}`} size="small" sx={{ height: 20, fontSize: "0.65rem" }} />
            <Chip label={formatBytes(totalSize)} size="small" sx={{ height: 20, fontSize: "0.65rem" }} />
            <Chip
              icon={<AddOutlined sx={{ fontSize: 14 }} />}
              label={t("assetPools.button.new")}
              size="small"
              onClick={() => { resetForm(); setCreateOpen(true); }}
              sx={{ cursor: "pointer", bgcolor: tokens.primary.subtle, border: `1px solid ${tokens.primary.main}`, color: tokens.primary.light }}
            />
          </Box>
        </Box>
        <Box sx={{ display: "flex", gap: 1, alignItems: "center" }}>
          <TextField
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={t("assetPools.search.placeholder")}
            size="small"
            data-testid="asset-pools-search"
            sx={{ flex: 1, maxWidth: 360 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                </InputAdornment>
              ),
            }}
          />

          {creators.length > 0 && (
            <ListFilterSelect
              value={creator}
              onChange={setCreator}
              options={creators}
              allLabel={t("assetPools.filter.allCreators")}
              testId="asset-pools-filter-creator"
              minWidth={160}
            />
          )}

          <ListSortControl value={sortState} onChange={setSortState} testId="asset-pools-sort" />
        </Box>
      </Box>

      {/* Pool grid */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {pools.length === 0 && !page.loading ? (
          // No pools configured yet — offer to create the first one.
          <EmptyState
            icon={StorageOutlined}
            title={t("assetPools.emptyState.title")}
            description={t("assetPools.emptyState.description")}
            actionLabel={t("assetPools.emptyState.action")}
            actionIcon={<AddOutlined sx={{ fontSize: 18 }} />}
            onAction={() => { resetForm(); setCreateOpen(true); }}
            testId="asset-pools-empty-state"
          />
        ) : filtered.length === 0 ? (
          <Box sx={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100%", opacity: 0.5 }}>
            <Typography variant="body2">{t("assetPools.empty")}</Typography>
          </Box>
        ) : (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 2 }}>
            {filtered.map(pool => (
              <PoolCard
                key={pool.id}
                pool={pool}
                onEdit={() => openEdit(pool)}
                onDelete={() => setDeleteTarget(pool)}
              />
            ))}
          </Box>
        )}

        {/* The chip counts above are computed from the loaded pools, so paging in the rest is
            what makes them true. */}
        {!query.trim() && !page.loading && (
          <ListPaging
            loaded={pools.length}
            total={page.totalCount}
            hasMore={page.hasMore}
            loadingMore={page.loadingMore}
            onLoadMore={page.loadMore}
            testId="asset-pools-paging"
          />
        )}
      </Box>

      {/* Create dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 400 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("assetPools.dialog.create")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          {renderFormFields()}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setCreateOpen(false)} size="small">{t("assetPools.button.cancel")}</Button>
          <Button onClick={handleCreate} variant="contained" size="small" disabled={!formName.trim() || saving}>{t("assetPools.button.create")}</Button>
        </DialogActions>
      </Dialog>

      {/* Edit dialog */}
      <Dialog open={!!editPool} onClose={() => { setEditPool(null); resetForm(); }} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 400 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("assetPools.dialog.edit")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          {renderFormFields()}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => { setEditPool(null); resetForm(); }} size="small">{t("assetPools.button.cancel")}</Button>
          <Button onClick={handleEdit} variant="contained" size="small" disabled={!formName.trim() || saving}>{t("assetPools.button.save")}</Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirmation */}
      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 340 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("assetPools.dialog.delete")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            {t("assetPools.confirm.delete", { name: deleteTarget?.name })}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteTarget(null)} size="small">{t("assetPools.button.cancel")}</Button>
          <Button onClick={handleDelete} color="error" variant="contained" size="small">{t("assetPools.button.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
