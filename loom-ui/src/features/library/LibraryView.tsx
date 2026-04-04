import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, Paper, Chip, Avatar, Divider, List, ListItemButton, ListItemText,
} from "@mui/material";
import { LibraryBooksOutlined, PhotoLibraryOutlined, VideocamOutlined, FolderOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Library, Asset } from "../../types";
import { mockLibraryService, mockAssetService } from "../../mock/services";
import { useProject } from "../../context/ProjectContext";

function formatBytes(bytes: number): string {
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(1)} TB`;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(0)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

export default function LibraryView() {
  const { activeProject } = useProject();
  const navigate = useNavigate();
  const [libraries, setLibraries] = useState<Library[]>([]);
  const [selectedLib, setSelectedLib] = useState<Library | null>(null);
  const [assets, setAssets] = useState<Asset[]>([]);

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

  const videoCount = assets.filter(a => a.type === "video").length;
  const imageCount = assets.filter(a => a.type === "image").length;
  const totalSize = assets.reduce((s, a) => s + a.fileSize, 0);

  return (
    <Box sx={{ display: "flex", height: "100%", overflow: "hidden", bgcolor: tokens.bg.base }}>
      {/* Library sidebar */}
      <Box sx={{ width: 220, flexShrink: 0, borderRight: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column" }}>
        <Box sx={{ px: 2, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}` }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Libraries</Typography>
          <Typography variant="caption" color="text.secondary">{activeProject?.name}</Typography>
        </Box>
        <List dense sx={{ p: 1, flex: 1 }}>
          {libraries.map(lib => (
            <ListItemButton
              key={lib.id}
              selected={selectedLib?.id === lib.id}
              onClick={() => setSelectedLib(lib)}
              sx={{ borderRadius: tokens.radius.md, mb: 0.5 }}
            >
              <Box sx={{ mr: 1.25, color: tokens.text.secondary, display: "flex" }}>
                <FolderOutlined sx={{ fontSize: 18 }} />
              </Box>
              <ListItemText
                primary={<Typography variant="body2" fontWeight={500} noWrap sx={{ fontSize: "0.82rem" }}>{lib.name}</Typography>}
                secondary={<Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>{lib.assetCount} assets</Typography>}
              />
            </ListItemButton>
          ))}
        </List>
      </Box>

      {/* Library detail */}
      <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
        {selectedLib ? (
          <>
            {/* Header */}
            <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
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

            {/* Asset grid */}
            <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
              {assets.length === 0 ? (
                <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                  <LibraryBooksOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                  <Typography variant="body2" color="text.secondary">No assets in this library</Typography>
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
                        "&:hover": { borderColor: tokens.border.strong, transform: "translateY(-1px)" },
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
    </Box>
  );
}
