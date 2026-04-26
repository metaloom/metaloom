import React from "react";
import {
  Box, Typography, Paper, Avatar, Chip, IconButton, Tooltip,
} from "@mui/material";
import {
  GroupWorkOutlined, LinkOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { FaceCluster, Person } from "../../types";
import { useTranslation } from "react-i18next";

interface ClustersPanelProps {
  clusters: FaceCluster[];
  persons: Person[];
  onAssignCluster: (clusterId: string) => void;
}

export default function ClustersPanel({ clusters, persons, onAssignCluster }: ClustersPanelProps) {
  const { t } = useTranslation();

  return (
    <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 2 }}>
      {clusters.map(cluster => {
        const person = cluster.personId ? persons.find(p => p.id === cluster.personId) : undefined;
        return (
          <Paper
            key={cluster.id}
            elevation={0}
            sx={{
              bgcolor: tokens.bg.elevated,
              border: `1px solid ${tokens.border.subtle}`,
              borderRadius: tokens.radius.lg,
              overflow: "hidden",
            }}
          >
            {/* Cluster header */}
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
              <Avatar src={cluster.representativeThumbnailUrl} sx={{ width: 40, height: 40 }} />
              <Box sx={{ flex: 1 }}>
                <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.85rem", color: tokens.text.primary }}>
                  {cluster.label}
                </Typography>
                <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem" }}>
                  {t("faceDetection.count.faces", { count: cluster.faceIds.length })}
                </Typography>
              </Box>
              {person ? (
                <Chip label={person.name} size="small" avatar={<Avatar src={person.avatarUrl} />} sx={{ height: 24, fontSize: "0.72rem", bgcolor: `${tokens.accent.green}18`, border: `1px solid ${tokens.accent.green}44` }} />
              ) : (
                <Tooltip title={t("faceDetection.tooltip.assign")}>
                  <IconButton size="small" onClick={() => onAssignCluster(cluster.id)}>
                    <LinkOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                  </IconButton>
                </Tooltip>
              )}
            </Box>
            {/* Face thumbnails grid */}
            <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", p: 1.5 }}>
              {cluster.faceIds.slice(0, 8).map(fid => (
                <Box key={fid} sx={{ width: 44, height: 44, borderRadius: tokens.radius.sm, overflow: "hidden", border: `2px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.overlay }}>
                  <img src={`https://i.pravatar.cc/80?u=${fid}`} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
                </Box>
              ))}
              {cluster.faceIds.length > 8 && (
                <Box sx={{ width: 44, height: 44, borderRadius: tokens.radius.sm, bgcolor: tokens.bg.overlay, display: "flex", alignItems: "center", justifyContent: "center" }}>
                  <Typography variant="caption" sx={{ fontSize: "0.7rem", color: tokens.text.tertiary }}>+{cluster.faceIds.length - 8}</Typography>
                </Box>
              )}
            </Box>
          </Paper>
        );
      })}
      {clusters.length === 0 && (
        <Box sx={{ gridColumn: "1 / -1", display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
          <GroupWorkOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
          <Typography variant="body2" color="text.secondary">{t("faceDetection.empty.clusters")}</Typography>
        </Box>
      )}
    </Box>
  );
}
