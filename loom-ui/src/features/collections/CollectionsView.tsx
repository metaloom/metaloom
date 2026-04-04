import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, Paper, Chip, Grid, IconButton, AvatarGroup, Avatar, Tooltip,
} from "@mui/material";
import { AddOutlined, CollectionsOutlined, ArrowForwardIos } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Collection, Asset } from "../../types";
import { mockCollectionService, mockAssetService } from "../../mock/services";
import { useProject } from "../../context/ProjectContext";
import { ASSETS } from "../../mock/data";

function CollectionCard({ collection }: { collection: Collection }) {
  const navigate = useNavigate();
  const assets = ASSETS.filter(a => collection.assetIds.includes(a.id));

  return (
    <Paper
      elevation={0}
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        overflow: "hidden",
        cursor: "pointer",
        transition: "border-color 140ms ease, transform 130ms ease",
        "&:hover": { borderColor: tokens.border.strong, transform: "translateY(-1px)" },
        position: "relative",
      }}
    >
      {/* Color accent */}
      <Box sx={{ height: 3, bgcolor: collection.color }} />

      {/* Thumbnails grid */}
      <Box sx={{ display: "grid", gridTemplateColumns: "1fr 1fr", height: 80, gap: 0.5, p: 0.5, bgcolor: tokens.bg.overlay }}>
        {assets.slice(0, 4).map((a, i) => (
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
            <Chip label={`${collection.assetIds.length} assets`} size="small" sx={{ height: 18, fontSize: "0.65rem" }} />
            <Chip label={new Date(collection.updatedAt).toLocaleDateString()} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: "transparent", color: tokens.text.tertiary }} />
          </Box>
        </Box>
      </Box>
    </Paper>
  );
}

export default function CollectionsView() {
  const { activeProject } = useProject();
  const [collections, setCollections] = useState<Collection[]>([]);

  useEffect(() => {
    if (!activeProject) return;
    mockCollectionService.getByProject(activeProject.id).then(setCollections);
  }, [activeProject]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Collections</Typography>
          <Typography variant="caption" color="text.secondary">{activeProject?.name} · {collections.length} collections</Typography>
        </Box>
        <Chip icon={<AddOutlined sx={{ fontSize: 14 }} />} label="New Collection" size="small" onClick={() => {}} sx={{ cursor: "pointer", bgcolor: tokens.primary.subtle, border: `1px solid ${tokens.primary.main}`, color: tokens.primary.light }} />
      </Box>

      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {collections.length === 0 ? (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
            <CollectionsOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">No collections in this project</Typography>
          </Box>
        ) : (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))", gap: 2 }}>
            {collections.map(c => <CollectionCard key={c.id} collection={c} />)}
          </Box>
        )}
      </Box>
    </Box>
  );
}
