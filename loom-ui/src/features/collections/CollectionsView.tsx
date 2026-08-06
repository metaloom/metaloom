import React, { useEffect, useMemo, useState } from "react";
import {
  Box, Typography, Paper, Chip, IconButton, Tooltip,
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  InputAdornment,
} from "@mui/material";
import { AddOutlined, CollectionsOutlined, DeleteOutlined, EditOutlined, SearchOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import EmptyState from "../../components/EmptyState";
import { useToast } from "../../context/ToastContext";
import { useAuth } from "../../context/AuthContext";
import {
  listCollections, createCollection, updateCollection, deleteCollection, CollectionResponse,
} from "../../api/collections";
import type { PagingParams } from "../../api/paging";
import ListPaging from "../../components/ListPaging";
import { pageFrom, usePagedList } from "../../hooks/usePagedList";
import { useTranslation } from "react-i18next";

interface CollectionItem {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
}

function mapResponseToItem(r: CollectionResponse): CollectionItem {
  return {
    id: r.uuid,
    name: r.name,
    createdAt: r.status?.created ?? new Date().toISOString(),
    updatedAt: r.status?.edited ?? new Date().toISOString(),
  };
}

// ── Collection Card ───────────────────────────────────────────────────────
function CollectionCard({ item, onEdit, onDelete }: { item: CollectionItem; onEdit: () => void; onDelete: () => void }) {
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
        "&:hover .collection-actions": { opacity: 1 },
        position: "relative",
      }}
    >
      {/* Action buttons */}
      <Box
        className="collection-actions"
        sx={{
          position: "absolute", top: 6, right: 6, zIndex: 3,
          display: "flex", gap: 0.5, opacity: 0, transition: "opacity 140ms ease",
        }}
      >
        <Tooltip title="Edit collection">
          <IconButton
            size="small"
            onClick={onEdit}
            sx={{ bgcolor: "rgba(0,0,0,0.6)", color: tokens.primary.light, width: 24, height: 24, "&:hover": { bgcolor: "rgba(0,0,0,0.8)" } }}
          >
            <EditOutlined sx={{ fontSize: 13 }} />
          </IconButton>
        </Tooltip>
        <Tooltip title="Delete collection">
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
      <Box sx={{ height: 3, bgcolor: tokens.accent.teal }} />

      {/* Header */}
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, p: 2, pb: 1.5 }}>
        <Box sx={{
          width: 36, height: 36, borderRadius: tokens.radius.md,
          bgcolor: "rgba(0,201,177,0.12)",
          display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0,
        }}>
          <CollectionsOutlined sx={{ fontSize: 20, color: tokens.accent.teal }} />
        </Box>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="subtitle2" fontWeight={700} noWrap sx={{ fontSize: "0.875rem" }}>
            {item.name}
          </Typography>
        </Box>
      </Box>

      {/* Stats */}
      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.75, px: 2, pb: 2 }}>
        <Chip label={new Date(item.updatedAt).toLocaleDateString()} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "transparent", color: tokens.text.tertiary }} />
      </Box>
    </Paper>
  );
}

// ── Main View ─────────────────────────────────────────────────────────────
export default function CollectionsView() {
  const { showToast } = useToast();
  const { token } = useAuth();
  const { t } = useTranslation();
  const [query, setQuery] = useState("");

  // /collections caps at 25 rows per page — page it rather than showing the first page as if it
  // were the collection.
  const loadPage = useMemo(
    () => (token ? (paging: PagingParams) => listCollections(token, paging).then(r => pageFrom(r, mapResponseToItem)) : null),
    [token],
  );
  const page = usePagedList<CollectionItem>(loadPage, c => c.id);
  const collections = page.items;
  const setCollections = page.setItems;

  // Create dialog
  const [createOpen, setCreateOpen] = useState(false);
  const [formName, setFormName] = useState("");
  const [saving, setSaving] = useState(false);

  // Edit dialog
  const [editItem, setEditItem] = useState<CollectionItem | null>(null);

  // Delete dialog
  const [deleteTarget, setDeleteTarget] = useState<CollectionItem | null>(null);

  useEffect(() => {
    if (page.error) showToast(t("collections.toast.loadFailed"), "error");
  }, [page.error]);

  const resetForm = () => { setFormName(""); };

  const handleCreate = async () => {
    if (!formName.trim() || !token) return;
    setSaving(true);
    try {
      const resp = await createCollection(token, { name: formName.trim() });
      setCollections(prev => [...prev, mapResponseToItem(resp)]);
      resetForm();
      setCreateOpen(false);
      showToast(t("collections.toast.created"), "success");
    } catch {
      showToast(t("collections.toast.createFailed"), "error");
    } finally {
      setSaving(false);
    }
  };

  const openEdit = (item: CollectionItem) => {
    setEditItem(item);
    setFormName(item.name);
  };

  const handleEdit = async () => {
    if (!editItem || !formName.trim() || !token) return;
    setSaving(true);
    try {
      const resp = await updateCollection(token, editItem.id, { name: formName.trim() });
      const updated = mapResponseToItem(resp);
      setCollections(prev => prev.map(c => c.id === updated.id ? updated : c));
      resetForm();
      setEditItem(null);
      showToast(t("collections.toast.updated"), "success");
    } catch {
      showToast(t("collections.toast.updateFailed"), "error");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget || !token) return;
    try {
      await deleteCollection(token, deleteTarget.id);
      setCollections(prev => prev.filter(c => c.id !== deleteTarget.id));
      setDeleteTarget(null);
      showToast(t("collections.toast.deleted"), "success");
    } catch {
      showToast(t("collections.toast.deleteFailed"), "error");
    }
  };

  const filtered = collections.filter(c => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return c.name.toLowerCase().includes(q);
  });

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column", gap: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <CollectionsOutlined sx={{ fontSize: 20, color: tokens.primary.main }} />
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("collections.title")}</Typography>
            <Typography variant="caption" color="text.secondary" data-testid="collections-count">{page.totalCount} {t("collections.count.collections")}</Typography>
          </Box>
          <Chip
            icon={<AddOutlined sx={{ fontSize: 14 }} />}
            label={t("collections.button.new")}
            size="small"
            onClick={() => { resetForm(); setCreateOpen(true); }}
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
        </Box>
      </Box>

      {/* Grid */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {collections.length === 0 && !page.loading ? (
          // No collections in this space yet — offer to create the first one.
          <EmptyState
            icon={CollectionsOutlined}
            title={t("collections.empty.title")}
            description={t("collections.empty.description")}
            actionLabel={t("collections.empty.action")}
            actionIcon={<AddOutlined sx={{ fontSize: 18 }} />}
            onAction={() => { resetForm(); setCreateOpen(true); }}
            testId="collections-empty-state"
          />
        ) : filtered.length === 0 ? (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
            <CollectionsOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">
              {t("collections.empty.noSearch")}
            </Typography>
          </Box>
        ) : (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))", gap: 2 }}>
            {filtered.map(c => (
              <CollectionCard
                key={c.id}
                item={c}
                onEdit={() => openEdit(c)}
                onDelete={() => setDeleteTarget(c)}
              />
            ))}
          </Box>
        )}

        {/* Only meaningful while browsing: the search box filters what is loaded, so paging in
            more rows is exactly how you widen it. */}
        {!query.trim() && !page.loading && (
          <ListPaging
            loaded={collections.length}
            total={page.totalCount}
            hasMore={page.hasMore}
            loadingMore={page.loadingMore}
            onLoadMore={page.loadMore}
            testId="collections-paging"
          />
        )}
      </Box>

      {/* Create dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 380 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("collections.dialog.newCollection")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField label={t("collections.label.name")} size="small" value={formName} onChange={e => setFormName(e.target.value)} autoFocus fullWidth />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setCreateOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{t("collections.button.cancel")}</Button>
          <Button onClick={handleCreate} size="small" variant="contained" disabled={!formName.trim() || saving}>
            {saving ? t("collections.button.creating") : t("collections.button.create")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Edit dialog */}
      <Dialog open={!!editItem} onClose={() => { setEditItem(null); resetForm(); }} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 380 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("collections.dialog.editCollection")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField label={t("collections.label.name")} size="small" value={formName} onChange={e => setFormName(e.target.value)} autoFocus fullWidth />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => { setEditItem(null); resetForm(); }} size="small" sx={{ color: tokens.text.secondary }}>{t("collections.button.cancel")}</Button>
          <Button onClick={handleEdit} size="small" variant="contained" disabled={!formName.trim() || saving}>
            {t("collections.button.save")}
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

