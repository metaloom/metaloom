import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, Paper, List, ListItemButton, ListItemText,
  IconButton, Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField, Tooltip,
  InputAdornment,
} from "@mui/material";
import { LibraryBooksOutlined, PhotoLibraryOutlined, VideocamOutlined, FolderOutlined, AddOutlined, DeleteOutlined, SearchOutlined, HelpOutlineOutlined, EditOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import AssetThumbnail from "../../components/AssetThumbnail";
import EmptyState from "../../components/EmptyState";
import { AssetResponse, assetBinaryUrl, listAssets } from "../../api/assets";
import { createLibrary, deleteLibrary, listLibraries, updateLibrary } from "../../api/libraries";
import type { PagingParams } from "../../api/paging";
import ListPaging from "../../components/ListPaging";
import { DEFAULT_SORT, ListFilterSelect, ListSortControl, type SortState } from "../../components/ListControls";
import { useCreatorOptions } from "../../hooks/useCreatorOptions";
import { pageFrom, usePagedList } from "../../hooks/usePagedList";
import { assetInLibrary, assetsInLibrary } from "./libraryAssets";
import { useSpace } from "../../context/SpaceContext";
import { useToast } from "../../context/ToastContext";
import { useAuth } from "../../context/AuthContext";
import { useTranslation } from "react-i18next";
import { AssetType } from "../../types";
import { PAGE_SIZE } from "../../hooks/pagedList";

/** Media class of an asset, used to pick the placeholder icon when there is no preview. */
function assetType(asset: AssetResponse): AssetType {
  const mime = asset.file?.mimeType ?? "";
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime.startsWith("audio/")) return "audio";
  if (mime.startsWith("application/") || mime.startsWith("text/")) return "document";
  return "unknown";
}

/** Only images can be rendered by an `<img>`; everything else keeps the type placeholder. */
function previewUrl(asset: AssetResponse): string {
  return assetType(asset) === "image" ? assetBinaryUrl(asset.uuid) : "";
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
  const [query, setQuery] = useState("");

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
    }).catch(() => {
      setLibraries([]);
      setSelectedLib(null);
    });
  }, [token]);

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

  const filteredAssets = useMemo(() => {
    return libraryAssets.filter(a => {
      const filename = a.file?.filename ?? "";
      const mimeType = a.file?.mimeType ?? "";
      const tags = (a.tags ?? []).map(tag => `${tag.collection}:${tag.name}`);
      if (!query.trim()) return true;
      const q = query.toLowerCase();
      return filename.toLowerCase().includes(q)
        || mimeType.toLowerCase().includes(q)
        || tags.some(tag => tag.toLowerCase().includes(q));
    });
  }, [libraryAssets, query]);

  const videoCount = filteredAssets.filter(a => (a.file?.mimeType ?? "").startsWith("video/")).length;
  const imageCount = filteredAssets.filter(a => (a.file?.mimeType ?? "").startsWith("image/")).length;
  const totalSize = filteredAssets.reduce((s, a) => s + (a.file?.size ?? 0), 0);

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
                </Box>
              </Box>
              <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
                <TextField
                  value={query}
                  onChange={e => setQuery(e.target.value)}
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

                {creators.length > 0 && (
                  <ListFilterSelect
                    value={creator}
                    onChange={setCreator}
                    options={creators}
                    allLabel={t("library.filter.allCreators")}
                    testId="library-filter-creator"
                    minWidth={150}
                  />
                )}

                <ListSortControl value={sortState} onChange={setSortState} testId="library-sort" />
              </Box>
            </Box>
            <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
              {libraryAssets.length === 0 ? (
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
              ) : filteredAssets.length === 0 ? (
                <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                  <LibraryBooksOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                  <Typography variant="body2" color="text.secondary">{t("library.empty.noSearch")}</Typography>
                </Box>
              ) : (
                <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2 }}>
                  {filteredAssets.map(a => (
                    <Paper
                      key={a.uuid}
                      elevation={0}
                      onClick={() => navigate(`/assets/${a.uuid}`)}
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
                          type={assetType(a)}
                          src={previewUrl(a)}
                          iconSize={28}
                          alt={a.file?.filename ?? ""}
                        />
                      </Box>
                      <Box sx={{ px: 1.25, py: 1 }}>
                        <Typography variant="caption" fontWeight={600} noWrap display="block" sx={{ fontSize: "0.75rem", color: tokens.text.primary }}>{a.file?.filename ?? "Untitled"}</Typography>
                        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>{a.file?.mimeType ?? "unknown"}</Typography>
                      </Box>
                    </Paper>
                  ))}
                </Box>
              )}

              {/* The library filter runs client-side over the loaded assets, so "load more" is
                  the only way to widen it. */}
              {!query.trim() && !assetPage.loading && (
                <ListPaging
                  loaded={assets.length}
                  total={assetPage.totalCount}
                  hasMore={assetPage.hasMore}
                  loadingMore={assetPage.loadingMore}
                  onLoadMore={assetPage.loadMore}
                  testId="library-assets-paging"
                />
              )}
            </Box>
          </>
        ) : (
          libraries.length === 0 ? (
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
