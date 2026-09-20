import React, { useEffect, useMemo, useRef, useState } from "react";
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
import { useFailure } from "../../context/FailureContext";
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
  /**
   * Two clusters were dropped onto each other: the reviewer is saying they are the same person.
   *
   * Deliberately not a structural merge. `cluster.asset_uuid` is scalar, and this is a global list,
   * so two cards the reviewer stacks usually come from different assets and no single row could
   * hold both. What "same subject" means in this schema is already a person - `cluster.person_uuid`
   * is many-to-one - and it is the one statement the facedetect node cannot undo, because
   * `person_uuid` is among the review columns its upsert preserves. A structural merge would be
   * wiped by the next pipeline pass over that asset.
   */
  onMergeClusters?: (sourceId: string, targetId: string) => void;
  /** Take the person back off a cluster - the way out of a wrong stack. */
  onDetachPerson?: (clusterId: string) => void;
}

export default function ClustersPanel({ clusters, persons, onAssignCluster, onClusterDeleted, onClusterUpdated, onMergeClusters, onDetachPerson }: ClustersPanelProps) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { reportFailure } = useFailure();
  const [editCluster, setEditCluster] = useState<FaceCluster | null>(null);
  const [editName, setEditName] = useState("");
  /**
   * The card being dragged, and the one it is currently over.
   *
   * `dragId` is a ref as well as state, and the ref is the one that matters: `onDragOver` has to
   * call `preventDefault()` to allow a drop, and it needs to know *now* whether a drag is in
   * flight. Reading React state there means the first few `dragover` events see the value from
   * before `onDragStart` re-rendered, the drop is refused, and the merge silently does nothing.
   * The state copy exists only so the cards can show it.
   */
  const dragIdRef = useRef<string | null>(null);
  const [dragId, setDragId] = useState<string | null>(null);
  const [dropTargetId, setDropTargetId] = useState<string | null>(null);

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
      reportFailure("deleteCluster", e);
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
      setEditCluster(null);
    } catch (e) {
      reportFailure("updateCluster", e);
    }
  };

  /**
   * The cards in display order, with a heading before each person's run of them.
   *
   * Clustering is per asset, so one person in twenty episodes is twenty cards. Left in the
   * server's order they are scattered through the grid and the reviewer has no way to see that the
   * work of attributing them is done. Grouping is what makes a stack a stack.
   */
  const ordered = useMemo(() => {
    const byPerson = new Map<string, FaceCluster[]>();
    const loose: FaceCluster[] = [];
    for (const cluster of clusters) {
      if (cluster.personId) {
        const bucket = byPerson.get(cluster.personId);
        if (bucket) bucket.push(cluster);
        else byPerson.set(cluster.personId, [cluster]);
      } else {
        loose.push(cluster);
      }
    }
    const rows: Array<{ kind: "heading"; key: string; label: string; count: number } | { kind: "card"; cluster: FaceCluster }> = [];
    for (const [personId, group] of byPerson) {
      const person = persons.find(p => p.id === personId);
      rows.push({
        kind: "heading",
        key: `h-${personId}`,
        label: person?.name ?? personId,
        count: group.reduce((sum, c) => sum + c.faceCount, 0),
      });
      group.forEach(cluster => rows.push({ kind: "card", cluster }));
    }
    if (loose.length > 0) {
      if (byPerson.size > 0) {
        rows.push({
          kind: "heading",
          key: "h-unassigned",
          label: t("faceDetection.label.unassigned"),
          count: loose.reduce((sum, c) => sum + c.faceCount, 0),
        });
      }
      loose.forEach(cluster => rows.push({ kind: "card", cluster }));
    }
    return rows;
  }, [clusters, persons, t]);

  return (
    <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 2 }}>
      {ordered.map(row => {
        if (row.kind === "heading") {
          return (
            <Box key={row.key} data-testid="cluster-person-group" data-person-name={row.label}
              sx={{ gridColumn: "1 / -1", display: "flex", alignItems: "baseline", gap: 1, mt: 1 }}>
              <Typography variant="caption" fontWeight={700} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", fontSize: "0.7rem", color: tokens.text.secondary }}>
                {row.label}
              </Typography>
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                {t("faceDetection.count.faces", { count: row.count })}
              </Typography>
            </Box>
          );
        }
        const cluster = row.cluster;
        const person = cluster.personId ? persons.find(p => p.id === cluster.personId) : undefined;
        return (
          <Paper
            key={cluster.id}
            elevation={0}
            data-testid="cluster-card"
            // The assign affordance is replaced by the person chip once a cluster is confirmed, so
            // "is this one still unassigned?" is otherwise only answerable by probing for a button.
            data-assigned={person ? "true" : "false"}
            data-cluster-id={cluster.id}
            data-person-id={person?.id ?? ""}
            data-drop-target={dropTargetId === cluster.id ? "true" : "false"}
            // Native HTML5 drag and drop, following TagsView: the repo carries no DnD library and
            // MUI ships none, and reactflow is a graph canvas rather than a list primitive.
            draggable={!!onMergeClusters}
            onDragStart={e => {
              dragIdRef.current = cluster.id;
              setDragId(cluster.id);
              e.dataTransfer.effectAllowed = "move";
              // Firefox ignores a drag that sets no data.
              e.dataTransfer.setData("text/plain", cluster.id);
            }}
            onDragEnd={() => { dragIdRef.current = null; setDragId(null); setDropTargetId(null); }}
            onDragOver={e => {
              const dragging = dragIdRef.current;
              if (!onMergeClusters || !dragging || dragging === cluster.id) return;
              e.preventDefault();
              e.dataTransfer.dropEffect = "move";
              setDropTargetId(cluster.id);
            }}
            onDragLeave={() => setDropTargetId(prev => (prev === cluster.id ? null : prev))}
            onDrop={e => {
              e.preventDefault();
              const sourceId = dragIdRef.current ?? e.dataTransfer.getData("text/plain");
              dragIdRef.current = null;
              setDragId(null);
              setDropTargetId(null);
              if (sourceId && sourceId !== cluster.id) {
                // Nothing is moved optimistically. A card that showed itself merged after a failed
                // write would be lying about a biometric attribution.
                onMergeClusters?.(sourceId, cluster.id);
              }
            }}
            sx={{
              bgcolor: tokens.bg.elevated,
              border: `1px solid ${dropTargetId === cluster.id ? tokens.primary.main : tokens.border.subtle}`,
              borderRadius: tokens.radius.lg,
              overflow: "hidden",
              cursor: onMergeClusters ? "grab" : "default",
              opacity: dragId === cluster.id ? 0.45 : 1,
              boxShadow: dropTargetId === cluster.id ? `0 0 0 2px ${tokens.primary.main}55` : "none",
              transition: "border-color 120ms ease, box-shadow 120ms ease, opacity 120ms ease",
            }}
          >
            {/* Cluster header */}
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
              <Avatar src={cluster.representativeThumbnailUrl} sx={{ width: 40, height: 40 }} />
              <Box sx={{ flex: 1 }}>
                <Typography variant="body2" fontWeight={600} data-testid="cluster-name" sx={{ fontSize: "0.85rem", color: tokens.text.primary }}>
                  {cluster.label}
                </Typography>
                <Typography variant="caption" data-testid="cluster-face-count" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem" }}>
                  {t("faceDetection.count.faces", { count: cluster.faceCount })}
                </Typography>
                {/* Who attributed this face, and when.

                    Shown as the date with the reviewer uuid on hover rather than a resolved name: the
                    user directory needs READ_USER, which a reviewer is not required to hold, and a card
                    that fails to render for the very people who use it would be the worse trade. Absent
                    while nobody has decided — the field is only written by confirm and reject, and it is
                    deliberately not `status.edited`, which the facedetect node rewrites on every run. */}
                {cluster.reviewedAt && (
                  <Tooltip title={cluster.reviewerUuid ? t("faceDetection.tooltip.reviewer", { uuid: cluster.reviewerUuid }) : ""}>
                    <Typography variant="caption" data-testid="cluster-reviewed-at" data-reviewer-uuid={cluster.reviewerUuid ?? ""}
                      sx={{ display: "block", color: tokens.text.tertiary, fontSize: "0.68rem" }}>
                      {t("faceDetection.label.reviewedOn", { date: new Date(cluster.reviewedAt).toLocaleDateString() })}
                    </Typography>
                  </Tooltip>
                )}
              </Box>
              {person ? (
                <Chip label={person.name} size="small" data-testid="cluster-person-chip"
                  avatar={<Avatar src={person.avatarUrl} />}
                  // Deletable, because a drag onto the wrong card is the obvious way to get this
                  // wrong and there was no way back from it.
                  onDelete={onDetachPerson ? () => onDetachPerson(cluster.id) : undefined}
                  sx={{ height: 24, fontSize: "0.72rem", bgcolor: `${tokens.accent.green}18`, border: `1px solid ${tokens.accent.green}44` }} />
              ) : (
                <Tooltip title={t("faceDetection.tooltip.assign")}>
                  <IconButton size="small" data-testid="cluster-assign" onClick={() => onAssignCluster(cluster.id)}>
                    <LinkOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                  </IconButton>
                </Tooltip>
              )}
              <Box sx={{ display: "flex", flexDirection: "column" }}>
                <IconButton size="small" data-testid="cluster-edit" onClick={() => openEdit(cluster)}>
                  <EditOutlined sx={{ fontSize: 16 }} />
                </IconButton>
                <IconButton size="small" data-testid="cluster-delete" onClick={() => handleDelete(cluster.id)}>
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
        <Box data-testid="clusters-empty" sx={{ gridColumn: "1 / -1", display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
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
            data-testid="cluster-edit-name"
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditCluster(null)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleUpdate} variant="contained" size="small" data-testid="cluster-edit-save" disabled={!editName.trim()}>{t("faceDetection.button.save")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
