import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, Paper, Chip, Avatar, Divider, List, ListItemButton, ListItemText,
  IconButton, Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField, Tooltip,
  InputAdornment,
} from "@mui/material";
import { LibraryBooksOutlined, PhotoLibraryOutlined, VideocamOutlined, FolderOutlined, AddOutlined, DeleteOutlined, SearchOutlined, HelpOutlineOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Library, Asset } from "../../types";
import { mockLibraryService, mockAssetService } from "../../mock/services";
import { useProject } from "../../context/ProjectContext";
import { useToast } from "../../context/ToastContext";

function formatBytes(bytes: number): string {
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(1)} TB`;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(0)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

export default function LibraryView() {
  const { activeProject } = useProject();
  const { showToast } = useToast();
  const navigate = useNavigate();
  const [libraries, setLibraries] = useState<Library[]>([]);
  const [selectedLib, setSelectedLib] = useState<Library | null>(null);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [creating, setCreating] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Library | null>(null);
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (!activeProject) return;
    mockLibraryService.getByProject(activeProject.id).then(libs => {
      setLibraries(libs);
      setSelectedLib(libs[0] ?? null);
    });
  }, [activeProject]);

  useEffect(() => {
    if (!selectedLib) return;
    mockAssetService.getByLibrary(selectedLib.id).then(setAssets);
  }, [selectedLib]);

  const handleCreate = async () => {
    if (!activeProject || !newName.trim()) return;
    setCreating(true);
    const lib = await mockLibraryService.create(activeProject.id, newName.trim(), newDesc.trim());
    setLibraries(prev => [...prev, lib]);
    setSelectedLib(lib);
    setNewName(""); setNewDesc(""); setCreateOpen(false); setCreating(false);
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    await mockLibraryService.delete(deleteTarget.id);
    const updated = libraries.filter(l => l.id !== deleteTarget.id);
    setLibraries(updated);
    if (selectedLib?.id === deleteTarget.id) setSelectedLib(updated[0] ?? null);
    setDeleteTarget(null);
    showToast("Library deleted", "success");
  };

  const videoCount = assets.filter(a => a.type === "video").length;
  const imageCount = assets.filter(a => a.type === "image").length;
  const totalSize = assets.reduce((s, a) => s + a.fileSize, 0);

  const filteredAssets = assets.filter(a => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return a.name.toLowerCase().includes(q) || a.type.toLowerCase().includes(q) || a.tags.some(t => t.toLowerCase().includes(q));
  });

  return (
    <Box sx={{ display: "flex", height: "100%", overflow: "hidden", bgcolor: tokens.bg.base }}>
      {/* Library sidebar */}
      <Box sx={{ width: 230, flexShrink: 0, borderRight: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column" }}>
        <Box sx={{ px: 2, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box>
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Libraries</Typography>
              <Tooltip title="Libraries are used to aggregate and organise assets into logical collections within a project." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
            </Box>
            <Typography variant="caption" color="text.secondary">{activeProject?.name}</Typography>
          </Box>
          <Tooltip title="New library">
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
                secondary={<Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>{lib.assetCount} assets</Typography>}
              />
              <Tooltip title="Delete library">
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
                <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{selectedLib.name}</Typography>
                <Typography variant="caption" color="text.secondary">{selectedLib.description}</Typography>
                <Box sx={{ display: "flex", gap: 1.5, mt: 1 }}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                    <VideocamOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                    <Typography variant="caption" color="text.secondary">{videoCount} videos</Typography>
                  </Box>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                    <PhotoLibraryOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                    <Typography variant="caption" color="text.secondary">{imageCount} images</Typography>
                  </Box>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                    <Typography variant="caption" color="text.secondary">{formatBytes(totalSize)} total</Typography>
                  </Box>
                </Box>
              </Box>
              <TextField
                value={query}
                onChange={e => setQuery(e.target.value)}
                placeholder="Search assets…"
                size="small"
                sx={{ maxWidth: 320 }}
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                    </InputAdornment>
                  ),
                }}
              />
            </Box>
            <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
              {filteredAssets.length === 0 ? (
                <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                  <LibraryBooksOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                  <Typography variant="body2" color="text.secondary">{assets.length === 0 ? "No assets in this library" : "No assets match your search"}</Typography>
                </Box>
              ) : (
                <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2 }}>
                  {filteredAssets.map(a => (
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
          </>
        ) : (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: "100%", gap: 1 }}>
            <LibraryBooksOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">Select a library</Typography>
          </Box>
        )}
      </Box>

      {/* Create library dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 360 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>New Library</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField label="Name" size="small" value={newName} onChange={e => setNewName(e.target.value)} autoFocus fullWidth />
          <TextField label="Description" size="small" value={newDesc} onChange={e => setNewDesc(e.target.value)} multiline rows={2} fullWidth />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setCreateOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>Cancel</Button>
          <Button onClick={handleCreate} size="small" variant="contained" disabled={!newName.trim() || creating}>
            {creating ? "Creating…" : "Create"}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirmation dialog */}
      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 340 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>Delete Library</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">
            Delete <strong style={{ color: tokens.text.primary }}>{deleteTarget?.name}</strong>? This cannot be undone.
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteTarget(null)} size="small" sx={{ color: tokens.text.secondary }}>Cancel</Button>
          <Button onClick={handleDelete} size="small" variant="contained" sx={{ bgcolor: tokens.accent.red, "&:hover": { bgcolor: tokens.accent.red } }}>Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
