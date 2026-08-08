import React, { useEffect, useState } from "react";
import {
  Box, Typography, Paper, Avatar, Chip, IconButton, Tooltip, Dialog, DialogTitle,
  DialogContent, DialogActions, Button, TextField,
} from "@mui/material";
import {
  GroupWorkOutlined, LinkOutlined, EditOutlined, DeleteOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { FaceCluster, Person } from "../../types";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import {
  deleteCluster as apiDeleteCluster,
  updateCluster as apiUpdateCluster,
  listClusterMembers,
} from "../../api/clusters";
import { FaceCrop } from "./FaceCrop";

interface ClustersPanelProps {
  clusters: FaceCluster[];
  persons: Person[];
  onAssignCluster: (clusterId: string) => void;
  onClusterDeleted?: (id: string) => void;
  onClusterUpdated?: (cluster: FaceCluster) => void;
}

export default function ClustersPanel({ clusters, persons, onAssignCluster, onClusterDeleted, onClusterUpdated }: ClustersPanelProps) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [editCluster, setEditCluster] = useState<FaceCluster | null>(null);
  const [editName, setEditName] = useState("");

  /**
   * Detection uuids per cluster, fetched once the cards are on screen.
   *
   * Held here rather than on the cluster objects because it is a display concern: the list route
   * already reports the member count, so the cards can say "N faces" immediately and fill in the
   * thumbnails as the member lists arrive. Membership used to be hardcoded to an empty array, which
   * made every card read "0 faces" no matter what was in it.
   */
  const [memberIds, setMemberIds] = useState<Record<string, string[]>>({});

  useEffect(() => {
    if (!token) return;
    let cancelled = false;

    // Only the clusters that have members and have not been fetched yet.
    const pending = clusters.filter(c => c.faceCount > 0 && memberIds[c.id] === undefined);
    if (pending.length === 0) return;

    Promise.all(
      pending.map(c =>
        listClusterMembers(token, c.id)
          .then(resp => [c.id, (resp.members ?? []).map(m => m.detectionUuid).filter((d): d is string => !!d)] as const)
          .catch(() => [c.id, [] as string[]] as const),
      ),
    ).then(entries => {
      if (cancelled) return;
      setMemberIds(prev => {
        const next = { ...prev };
        for (const [id, ids] of entries) {
          next[id] = ids;
        }
        return next;
      });
    });

    return () => {
      cancelled = true;
    };
  }, [token, clusters, memberIds]);

  const handleDelete = async (id: string) => {
    if (!token) return;
    try {
      await apiDeleteCluster(token, id);
      onClusterDeleted?.(id);
    } catch (e) {
      console.error("Failed to delete cluster", e);
    }
  };

  const openEdit = (cluster: FaceCluster) => {
    setEditCluster(cluster);
    setEditName(cluster.label);
  };

  const handleUpdate = async () => {
    if (!editCluster || !token) return;
    try {
      await apiUpdateCluster(token, editCluster.id, { name: editName });
      onClusterUpdated?.({ ...editCluster, label: editName });
    } catch (e) {
      console.error("Failed to update cluster", e);
    }
    setEditCluster(null);
  };

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
                  {t("faceDetection.count.faces", { count: cluster.faceCount })}
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
              <Box sx={{ display: "flex", flexDirection: "column" }}>
                <IconButton size="small" onClick={() => openEdit(cluster)}>
                  <EditOutlined sx={{ fontSize: 16 }} />
                </IconButton>
                <IconButton size="small" onClick={() => handleDelete(cluster.id)}>
                  <DeleteOutlined sx={{ fontSize: 16 }} />
                </IconButton>
              </Box>
            </Box>
            {/* Face thumbnails grid.

                The crops are served from this deployment via <FaceCrop>. They used to be
                https://i.pravatar.cc/80?u={faceId} — stock portraits of people who were not in the
                picture, fetched by sending every detection uuid to a third party. Face crops are
                biometric data and do not leave the deployment. */}
            <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", p: 1.5 }}>
              {(memberIds[cluster.id] ?? []).slice(0, 8).map(fid => (
                <FaceCrop key={fid} assetUuid={cluster.assetId} detectionUuid={fid} size={44} rounded={false} />
              ))}
              {cluster.faceCount > 8 && (
                <Box sx={{ width: 44, height: 44, borderRadius: tokens.radius.sm, bgcolor: tokens.bg.overlay, display: "flex", alignItems: "center", justifyContent: "center" }}>
                  <Typography variant="caption" sx={{ fontSize: "0.7rem", color: tokens.text.tertiary }}>
                    +{cluster.faceCount - 8}
                  </Typography>
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

      {/* Edit Cluster Dialog */}
      <Dialog open={!!editCluster} onClose={() => setEditCluster(null)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("faceDetection.dialog.editCluster")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField
            label={t("faceDetection.label.name")}
            value={editName}
            onChange={e => setEditName(e.target.value)}
            size="small"
            fullWidth
            autoFocus
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditCluster(null)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleUpdate} variant="contained" size="small" disabled={!editName.trim()}>{t("faceDetection.button.save")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
