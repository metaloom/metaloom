import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, Paper, Chip, Grid, IconButton, AvatarGroup, Avatar, Tooltip,
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  InputAdornment,
} from "@mui/material";
import { AddOutlined, CollectionsOutlined, ArrowForwardIos, DeleteOutlined, ArrowBack, SearchOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Collection, Asset } from "../../types";
import { mockCollectionService, mockAssetService } from "../../mock/services";
import { useSpace } from "../../context/SpaceContext";
import { useToast } from "../../context/ToastContext";
import { ASSETS } from "../../mock/data";
import { useTranslation } from "react-i18next";

const PALETTE = ["#57cbcc", "#2ea8ff", "#34d58a", "#f5a623", "#f0546e", "#a855f7", "#ec4899", "#00c9b1"];

function CollectionCard({ collection, onDelete, onClick }: { collection: Collection; onDelete: () => void; onClick: () => void }) {
  const assets = ASSETS.filter(a => collection.assetIds.includes(a.id));
  const { t } = useTranslation();

  return (
    <Paper
      elevation={0}
      onClick={onClick}
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        overflow: "hidden",
        cursor: "pointer",
        transition: "border-color 140ms ease, box-shadow 140ms ease",
        "&:hover": { borderColor: tokens.border.strong, boxShadow: `0 4px 20px rgba(0,0,0,0.35)` },
        "&:hover .delete-btn": { opacity: 1 },
        position: "relative",
      }}
    >
      {/* Delete button */}
      <Tooltip title={t("collections.tooltip.delete")}>
        <IconButton
          className="delete-btn"
          size="small"
          onClick={(e) => { e.stopPropagation(); onDelete(); }}
          sx={{
            position: "absolute", top: 6, right: 6, zIndex: 3,
            opacity: 0, transition: "opacity 140ms ease",
            bgcolor: "rgba(0,0,0,0.6)", color: tokens.accent.red,
            width: 24, height: 24,
            "&:hover": { bgcolor: "rgba(0,0,0,0.8)" },
          }}
        >
          <DeleteOutlined sx={{ fontSize: 13 }} />
        </IconButton>
      </Tooltip>

      {/* Color accent */}
      <Box sx={{ height: 3, bgcolor: collection.color }} />

      {/* Thumbnails grid */}
      <Box sx={{ display: "grid", gridTemplateColumns: "1fr 1fr", height: 80, gap: 0.5, p: 0.5, bgcolor: tokens.bg.overlay }}>
        {assets.slice(0, 4).map((a) => (
          <Box key={a.id} sx={{ borderRadius: tokens.radius.sm, overflow: "hidden", bgcolor: tokens.bg.base }}>
            <img src={a.thumbnailUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} loading="lazy" />
          </Box>
        ))}
        {Array.from({ length: Math.max(0, 4 - assets.length) }).map((_, i) => (
          <Box key={`empty_${i}`} sx={{ borderRadius: tokens.radius.sm, bgcolor: tokens.bg.base }} />
        ))}
      </Box>

      <Box sx={{ p: 1.5 }}>
        <Typography variant="subtitle2" fontWeight={700} sx={{ fontSize: "0.875rem", mb: 0.25 }}>
          {collection.name}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1.4, display: "block", mb: 1 }}>
          {collection.description}
        </Typography>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box sx={{ display: "flex", gap: 0.75 }}>
            <Chip label={`${collection.assetIds.length} ${t("collections.chip.assets")}`} size="small" sx={{ height: 18, fontSize: "0.65rem" }} />
            <Chip label={new Date(collection.updatedAt).toLocaleDateString()} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "transparent", color: tokens.text.tertiary }} />
          </Box>
        </Box>
      </Box>
    </Paper>
  );
}

// ── Collection Detail View ────────────────────────────────────────────────
function CollectionDetail({ collection, onBack }: { collection: Collection; onBack: () => void }) {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const assets = ASSETS.filter(a => collection.assetIds.includes(a.id));

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", alignItems: "center", gap: 1.5 }}>
        <Box sx={{ width: 4, height: 20, borderRadius: 2, bgcolor: collection.color, flexShrink: 0 }} />
        <Box sx={{ flex: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{collection.name}</Typography>
          {collection.description && <Typography variant="caption" color="text.secondary">{collection.description}</Typography>}
        </Box>
        <Chip label={`${collection.assetIds.length} ${t("collections.chip.assets")}`} size="small" sx={{ height: 18, fontSize: "0.65rem" }} />
        <Tooltip title={t("collections.tooltip.back")}>
          <IconButton size="small" onClick={onBack}><ArrowBack sx={{ fontSize: 16 }} /></IconButton>
        </Tooltip>
      </Box>
      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {assets.length === 0 ? (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
            <CollectionsOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">{t("collections.empty.noAssets")}</Typography>
          </Box>
        ) : (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2 }}>
            {assets.map(a => (
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
                  "&:hover": { borderColor: tokens.border.strong, boxShadow: `0 4px 16px rgba(0,0,0,0.3)` },
                  transition: "all 140ms ease",
                }}
              >
                <Box sx={{ position: "relative", paddingTop: "56.25%", bgcolor: tokens.bg.overlay }}>
                  <img src={a.thumbnailUrl} alt={a.name} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} loading="lazy" />
                  <Box sx={{ position: "absolute", top: 5, right: 5, width: 7, height: 7, borderRadius: "50%", bgcolor: a.status === "ready" ? tokens.accent.green : a.status === "failed" ? tokens.accent.red : tokens.accent.amber }} />
                </Box>
                <Box sx={{ px: 1.25, py: 1 }}>
                  <Typography variant="caption" fontWeight={600} noWrap display="block" sx={{ fontSize: "0.75rem", color: tokens.text.primary }}>{a.name}</Typography>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>{a.type}</Typography>
                </Box>
              </Paper>
            ))}
          </Box>
        )}
      </Box>
    </Box>
  );
}

export default function CollectionsView() {
  const { activeSpace } = useSpace();
  const { showToast } = useToast();
  const { t } = useTranslation();
  const [collections, setCollections] = useState<Collection[]>([]);
  const [selectedCollection, setSelectedCollection] = useState<Collection | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [newColor, setNewColor] = useState(PALETTE[0]);
  const [creating, setCreating] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Collection | null>(null);
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (!activeSpace) return;
    mockCollectionService.getBySpace(activeSpace.id).then(setCollections);
  }, [activeSpace]);

  const handleCreate = async () => {
    if (!activeSpace || !newName.trim()) return;
    setCreating(true);
    const col = await mockCollectionService.create(activeSpace.id, newName.trim(), newDesc.trim(), newColor);
    setCollections(prev => [...prev, col]);
    setNewName(""); setNewDesc(""); setNewColor(PALETTE[0]); setCreateOpen(false); setCreating(false);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    await mockCollectionService.delete(deleteTarget.id);
    setCollections(prev => prev.filter(c => c.id !== deleteTarget.id));
    if (selectedCollection?.id === deleteTarget.id) setSelectedCollection(null);
    setDeleteTarget(null);
    showToast(t("collections.toast.deleted"), "success");
  };

  if (selectedCollection) {
    return <CollectionDetail collection={selectedCollection} onBack={() => setSelectedCollection(null)} />;
  }

  const filtered = collections.filter(c => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return c.name.toLowerCase().includes(q) || c.description.toLowerCase().includes(q);
  });

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column", gap: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("collections.title")}</Typography>
            <Typography variant="caption" color="text.secondary">{activeSpace?.name} · {collections.length} {t("collections.count.collections")}</Typography>
          </Box>
          <Chip
            icon={<AddOutlined sx={{ fontSize: 14 }} />}
            label={t("collections.button.new")}
            size="small"
            onClick={() => setCreateOpen(true)}
            sx={{ cursor: "pointer", bgcolor: tokens.primary.subtle, border: `1px solid ${tokens.primary.main}`, color: tokens.primary.light }}
          />
        </Box>
        <Box sx={{ display: "flex", gap: 1, alignItems: "center" }}>
          <TextField
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={t("collections.search.placeholder")}
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
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>{filtered.length} {t("collections.count.results")}</Typography>
        </Box>
      </Box>

      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {filtered.length === 0 ? (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
            <CollectionsOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">{collections.length === 0 ? t("collections.empty.noCollections") : t("collections.empty.noSearch")}</Typography>
          </Box>
        ) : (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))", gap: 2 }}>
            {filtered.map(c => (
              <CollectionCard
                key={c.id}
                collection={c}
                onClick={() => setSelectedCollection(c)}
                onDelete={() => setDeleteTarget(c)}
              />
            ))}
          </Box>
        )}
      </Box>

      {/* Create collection dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 380 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("collections.dialog.newCollection")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField label={t("collections.label.name")} size="small" value={newName} onChange={e => setNewName(e.target.value)} autoFocus fullWidth />
          <TextField label={t("collections.label.description")} size="small" value={newDesc} onChange={e => setNewDesc(e.target.value)} multiline rows={2} fullWidth />
          <Box>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem", mb: 0.75, display: "block" }}>{t("collections.label.color")}</Typography>
            <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
              {PALETTE.map(c => (
                <Box
                  key={c}
                  onClick={() => setNewColor(c)}
                  sx={{
                    width: 22, height: 22, borderRadius: "50%", bgcolor: c, cursor: "pointer",
                    border: `2px solid ${newColor === c ? tokens.text.primary : "transparent"}`,
                    transition: "border-color 100ms ease",
                  }}
                />
              ))}
            </Box>
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setCreateOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{t("collections.button.cancel")}</Button>
          <Button onClick={handleCreate} size="small" variant="contained" disabled={!newName.trim() || creating}>
            {creating ? t("collections.button.creating") : t("collections.button.create")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirmation */}
      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 340 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("collections.dialog.deleteCollection")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">
            {t("collections.confirm.delete", { name: deleteTarget?.name })}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteTarget(null)} size="small" sx={{ color: tokens.text.secondary }}>{t("collections.button.cancel")}</Button>
          <Button onClick={handleDelete} size="small" variant="contained" sx={{ bgcolor: tokens.accent.red, "&:hover": { bgcolor: tokens.accent.red } }}>{t("collections.button.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

