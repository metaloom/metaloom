import React, { useEffect, useState } from "react";
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
import { AssetPool, AssetPoolType } from "../../types";
import { mockAssetPoolService } from "../../mock/services";
import { useToast } from "../../context/ToastContext";

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

// ── Pool Detail Card ──────────────────────────────────────────────────────
function PoolCard({ pool, onEdit, onDelete }: { pool: AssetPool; onEdit: () => void; onDelete: () => void }) {
  return (
    <Paper
      elevation={0}
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

      {/* Stats */}
      <Box sx={{ display: "flex", gap: 0.75, px: 2, pb: 2 }}>
        <Chip label={`${formatCount(pool.assetCount)} assets`} size="small" sx={{ height: 18, fontSize: "0.65rem" }} />
        <Chip label={formatBytes(pool.totalSize)} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "transparent", color: tokens.text.tertiary }} />
        <Chip label={new Date(pool.updatedAt).toLocaleDateString()} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "transparent", color: tokens.text.tertiary }} />
      </Box>
    </Paper>
  );
}

// ── Main View ─────────────────────────────────────────────────────────────
export default function AssetPoolsView() {
  const { showToast } = useToast();
  const [pools, setPools] = useState<AssetPool[]>([]);
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
    mockAssetPoolService.getAll().then(setPools);
  }, []);

  const resetForm = () => {
    setFormName(""); setFormType("filesystem"); setFormFsPath("");
    setFormS3Bucket(""); setFormS3Region(""); setFormS3Endpoint("");
  };

  const handleCreate = async () => {
    if (!formName.trim()) return;
    setSaving(true);
    const pool = await mockAssetPoolService.create({
      name: formName.trim(),
      type: formType,
      ...(formType === "filesystem" ? { fsPath: formFsPath.trim() } : {}),
      ...(formType === "s3" ? { s3Bucket: formS3Bucket.trim(), s3Region: formS3Region.trim(), s3Endpoint: formS3Endpoint.trim() } : {}),
    });
    setPools(prev => [...prev, pool]);
    resetForm();
    setCreateOpen(false);
    setSaving(false);
    showToast("Asset pool created", "success");
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
    if (!editPool || !formName.trim()) return;
    setSaving(true);
    const updated = await mockAssetPoolService.update(editPool.id, {
      name: formName.trim(),
      type: formType,
      fsPath: formType === "filesystem" ? formFsPath.trim() : undefined,
      s3Bucket: formType === "s3" ? formS3Bucket.trim() : undefined,
      s3Region: formType === "s3" ? formS3Region.trim() : undefined,
      s3Endpoint: formType === "s3" ? formS3Endpoint.trim() : undefined,
    });
    if (updated) {
      setPools(prev => prev.map(p => p.id === updated.id ? updated : p));
    }
    resetForm();
    setEditPool(null);
    setSaving(false);
    showToast("Asset pool updated", "success");
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    await mockAssetPoolService.delete(deleteTarget.id);
    setPools(prev => prev.filter(p => p.id !== deleteTarget.id));
    setDeleteTarget(null);
    showToast("Asset pool deleted", "success");
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
      <TextField label="Name" size="small" value={formName} onChange={e => setFormName(e.target.value)} autoFocus fullWidth />
      <FormControl size="small" fullWidth>
        <InputLabel>Type</InputLabel>
        <Select value={formType} label="Type" onChange={e => setFormType(e.target.value as AssetPoolType)}>
          <MenuItem value="filesystem">Filesystem</MenuItem>
          <MenuItem value="s3">S3 Bucket</MenuItem>
        </Select>
      </FormControl>
      {formType === "filesystem" && (
        <TextField label="Filesystem Path" size="small" value={formFsPath} onChange={e => setFormFsPath(e.target.value)} placeholder="/mnt/media/pool" fullWidth />
      )}
      {formType === "s3" && (
        <>
          <TextField label="S3 Bucket" size="small" value={formS3Bucket} onChange={e => setFormS3Bucket(e.target.value)} placeholder="my-bucket" fullWidth />
          <TextField label="S3 Region" size="small" value={formS3Region} onChange={e => setFormS3Region(e.target.value)} placeholder="eu-central-1" fullWidth />
          <TextField label="S3 Endpoint" size="small" value={formS3Endpoint} onChange={e => setFormS3Endpoint(e.target.value)} placeholder="https://s3.eu-central-1.amazonaws.com" fullWidth />
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
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Asset Pools</Typography>
            <Tooltip title="Asset pools define storage locations for assets. They can be backed by a local filesystem folder or an S3-compatible bucket." arrow>
              <HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} />
            </Tooltip>
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <Chip label={`${fsCount} filesystem`} size="small" icon={<FolderOutlined sx={{ fontSize: 12 }} />} sx={{ height: 20, fontSize: "0.65rem" }} />
            <Chip label={`${s3Count} S3`} size="small" icon={<CloudOutlined sx={{ fontSize: 12 }} />} sx={{ height: 20, fontSize: "0.65rem" }} />
            <Chip label={`${formatCount(totalAssets)} assets`} size="small" sx={{ height: 20, fontSize: "0.65rem" }} />
            <Chip label={formatBytes(totalSize)} size="small" sx={{ height: 20, fontSize: "0.65rem" }} />
            <Chip
              icon={<AddOutlined sx={{ fontSize: 14 }} />}
              label="New Pool"
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
            placeholder="Search pools…"
            size="small"
            sx={{ flex: 1, maxWidth: 360 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                </InputAdornment>
              ),
            }}
          />
        </Box>
      </Box>

      {/* Pool grid */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {filtered.length === 0 ? (
          <Box sx={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100%", opacity: 0.5 }}>
            <Typography variant="body2">No asset pools found</Typography>
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
      </Box>

      {/* Create dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 400 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>New Asset Pool</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          {renderFormFields()}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setCreateOpen(false)} size="small">Cancel</Button>
          <Button onClick={handleCreate} variant="contained" size="small" disabled={!formName.trim() || saving}>Create</Button>
        </DialogActions>
      </Dialog>

      {/* Edit dialog */}
      <Dialog open={!!editPool} onClose={() => { setEditPool(null); resetForm(); }} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 400 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>Edit Asset Pool</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          {renderFormFields()}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => { setEditPool(null); resetForm(); }} size="small">Cancel</Button>
          <Button onClick={handleEdit} variant="contained" size="small" disabled={!formName.trim() || saving}>Save</Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirmation */}
      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 340 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>Delete Asset Pool</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            Are you sure you want to delete <strong>{deleteTarget?.name}</strong>? This will not delete the underlying storage, but the pool reference will be removed.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteTarget(null)} size="small">Cancel</Button>
          <Button onClick={handleDelete} color="error" variant="contained" size="small">Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
