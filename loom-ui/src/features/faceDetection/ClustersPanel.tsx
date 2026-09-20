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

/**
 * How much of a cluster card to draw.
 *
 * `small` is faces and nothing else: the reviewer scanning a wall of clusters for two that are the
 * same person is reading pictures, and the name, count, review date and buttons above each one are
 * three lines of chrome between every two rows of the thing they are actually comparing. The
 * actions do not disappear — the card is still a drop target, and `medium` is one click away.
 */
export type ClusterCardSize = "small" | "medium" | "large";

/**
 * Card width, crop size and how many crops fit, per size.
 *
 * The faces at `small` are 60px, not the 40 they started at: the point of the mode is comparing
 * faces, and 40 was below what a face is recognisable at, so the mode that exists for looking at
 * faces was the one you could see them worst in. The column grew with them.
 */
const SIZE_SPECS: Record<ClusterCardSize, { column: number; crop: number; maxCrops: number; header: boolean }> = {
  small: { column: 250, crop: 60, maxCrops: 12, header: false },
  medium: { column: 280, crop: 44, maxCrops: 8, header: true },
  large: { column: 400, crop: 72, maxCrops: 10, header: true },
};

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
  /**
   * A card was dropped on a person's group rather than on one of its cards.
   *
   * The same statement — "this cluster is that person" — reached by aiming at the heading instead
   * of at a sibling. Worth its own callback because the group knows the person uuid directly and
   * the card-to-card path has to infer it from the target.
   */
  onAssignToPerson?: (clusterId: string, personId: string) => void;
  /** How much of each card to draw; see {@link ClusterCardSize}. */
  size?: ClusterCardSize;
}

export default function ClustersPanel({ clusters, persons, onAssignCluster, onClusterDeleted, onClusterUpdated, onMergeClusters, onDetachPerson, onAssignToPerson, size = "medium" }: ClustersPanelProps) {
  const spec = SIZE_SPECS[size];
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
   * The cards, gathered into the person each belongs to.
   *
   * Clustering is per asset, so one person across twenty episodes is twenty cards. Left in the
   * server's order they are scattered through the grid and the reviewer has no way to see that
   * the work of attributing them is done. Grouping is what makes a stack a stack — and it is also
   * what gives a drop its target: a card dragged at a *person* rather than at one of that
   * person's cards is the same statement and should not have to be aimed at a particular tile.
   */
  const groups = useMemo(() => {
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
    const rows: Array<{ key: string; personId: string | null; label: string | null; count: number; cards: FaceCluster[] }> = [];
    for (const [personId, group] of byPerson) {
      const person = persons.find(p => p.id === personId);
      rows.push({
        key: `p-${personId}`,
        personId,
        label: person?.name ?? personId,
        count: group.reduce((sum, c) => sum + c.faceCount, 0),
        cards: group,
      });
    }
    if (loose.length > 0) {
      rows.push({
        key: "unassigned",
        personId: null,
        // No heading at all while nothing is attributed: a single "Unassigned" band over the whole
        // grid says nothing, and the grid then looks like it has a grouping it does not have.
        label: byPerson.size > 0 ? t("faceDetection.label.unassigned") : null,
        count: loose.reduce((sum, c) => sum + c.faceCount, 0),
        cards: loose,
      });
    }
    return rows;
  }, [clusters, persons, t]);

  /** Every card in visual order — what the arrow keys walk. */
  const cardOrder = useMemo(() => groups.flatMap(g => g.cards.map(c => c.id)), [groups]);

  /**
   * The card the keyboard is on.
   *
   * Real DOM focus, not a highlight drawn beside it: Enter has to reach the card the user is
   * looking at, and a component that paints its own selection while the browser's focus is on the
   * search box is the classic way to get those two out of step. The state exists only so a
   * freshly-rendered grid knows which card to give `tabIndex={0}` to.
   */
  const gridRef = useRef<HTMLDivElement>(null);
  const [focusedId, setFocusedId] = useState<string | null>(null);

  useEffect(() => {
    // The focused card can vanish — deleted, filtered out, moved by a merge. Fall back to the
    // first card rather than to nothing, or the grid becomes unreachable by keyboard.
    if (focusedId && !cardOrder.includes(focusedId)) {
      setFocusedId(cardOrder[0] ?? null);
    }
  }, [cardOrder, focusedId]);

  const focusCard = (id: string) => {
    setFocusedId(id);
    gridRef.current?.querySelector<HTMLElement>(`[data-cluster-id="${CSS.escape(id)}"]`)?.focus();
  };

  /**
   * Arrow-key movement, measured rather than computed.
   *
   * The grid is `auto-fill`, so how many cards are on a row depends on the window, and the groups
   * each start a fresh row — so there is no column count to do arithmetic with. Geometry answers
   * it directly: left/right is the neighbouring card in document order, up/down is the card
   * nearest in x on the closest row in that direction, which also carries the caret across a
   * group boundary the way the eye does.
   */
  const moveFocus = (from: string, key: "ArrowLeft" | "ArrowRight" | "ArrowUp" | "ArrowDown") => {
    const grid = gridRef.current;
    if (!grid) return;
    const cards = Array.from(grid.querySelectorAll<HTMLElement>("[data-testid='cluster-card']"));
    const idx = cards.findIndex(el => el.dataset.clusterId === from);
    if (idx < 0) return;

    if (key === "ArrowLeft" || key === "ArrowRight") {
      const next = cards[idx + (key === "ArrowLeft" ? -1 : 1)];
      if (next?.dataset.clusterId) focusCard(next.dataset.clusterId);
      return;
    }

    const origin = cards[idx].getBoundingClientRect();
    const wanted = key === "ArrowUp" ? -1 : 1;
    let best: { el: HTMLElement; dy: number; dx: number } | null = null;
    for (const el of cards) {
      if (el === cards[idx]) continue;
      const rect = el.getBoundingClientRect();
      const dy = (rect.top - origin.top) * wanted;
      // Same row, or the wrong direction entirely. A tolerance because cards in a row are not
      // pixel-aligned once their contents differ in height.
      if (dy <= 4) continue;
      const dx = Math.abs((rect.left + rect.width / 2) - (origin.left + origin.width / 2));
      if (!best || dy < best.dy - 4 || (Math.abs(dy - best.dy) <= 4 && dx < best.dx)) {
        best = { el, dy, dx };
      }
    }
    if (best?.el.dataset.clusterId) focusCard(best.el.dataset.clusterId);
  };

  const handleCardKeyDown = (e: React.KeyboardEvent, cluster: FaceCluster) => {
    if (e.key === "ArrowLeft" || e.key === "ArrowRight" || e.key === "ArrowUp" || e.key === "ArrowDown") {
      e.preventDefault();
      moveFocus(cluster.id, e.key);
      return;
    }
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onAssignCluster(cluster.id);
    }
  };

  /** Drop handlers shared by a card and by a person group. */
  const dropProps = (onDropped: (sourceId: string) => void, targetKey: string, selfId?: string) => ({
    onDragOver: (e: React.DragEvent) => {
      const dragging = dragIdRef.current;
      if (!onMergeClusters || !dragging || dragging === selfId) return;
      e.preventDefault();
      // A card sits inside its person's group and both are drop targets, so without this the
      // group lights up instead of the card — and the drop below writes the attribution twice.
      e.stopPropagation();
      e.dataTransfer.dropEffect = "move";
      setDropTargetId(targetKey);
    },
    onDragLeave: () => setDropTargetId(prev => (prev === targetKey ? null : prev)),
    onDrop: (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();
      // The ref first: `getData` is only readable during the drop, and the ref is what survived
      // the dragover gating above.
      const sourceId = dragIdRef.current ?? e.dataTransfer.getData("text/plain");
      dragIdRef.current = null;
      setDragId(null);
      setDropTargetId(null);
      if (sourceId && sourceId !== selfId) onDropped(sourceId);
    },
  });

  return (
    <Box ref={gridRef} data-testid="clusters-grid" data-card-size={size}
      sx={{ display: "flex", flexDirection: "column", gap: size === "small" ? 1 : 2 }}>
      {groups.map(group => {
        const groupKey = `group:${group.key}`;
        const isDropTarget = dropTargetId === groupKey;
        // Only an attributed group can absorb a drop on its own: dropping onto the unassigned
        // band would mean "make this cluster nobody", which is what the chip's delete does.
        const groupDroppable = !!group.personId && !!onAssignToPerson;
        return (
          <Box
            key={group.key}
            // Keyed on the person, not on the label: the unassigned band has a heading too, and
            // calling that a person group made "how many people are on screen" read one too high.
            data-testid={group.personId ? "cluster-person-group" : "cluster-loose-group"}
            data-person-name={group.label ?? ""}
            data-person-id={group.personId ?? ""}
            data-drop-target={isDropTarget ? "true" : "false"}
            {...(groupDroppable ? dropProps(sourceId => onAssignToPerson!(sourceId, group.personId!), groupKey) : {})}
            sx={{
              // The group is the drop zone, so it has to be a visible region rather than a
              // heading with cards loose beneath it — you cannot aim at something with no edges.
              borderRadius: tokens.radius.lg,
              p: group.label ? 1 : 0,
              border: `1px ${isDropTarget ? "solid" : "dashed"} ${isDropTarget ? tokens.primary.main : (group.label ? tokens.border.subtle : "transparent")}`,
              bgcolor: isDropTarget ? `${tokens.primary.main}14` : "transparent",
              boxShadow: isDropTarget ? `0 0 0 2px ${tokens.primary.main}44` : "none",
              transition: "background-color 120ms ease, border-color 120ms ease, box-shadow 120ms ease",
            }}
          >
            {group.label && (
              <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, mb: 1, px: 0.5 }}>
                <Typography variant="caption" fontWeight={700} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", fontSize: "0.7rem", color: tokens.text.secondary }}>
                  {group.label}
                </Typography>
                <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                  {t("faceDetection.count.faces", { count: group.count })}
                </Typography>
              </Box>
            )}
            <Box sx={{ display: "grid", gridTemplateColumns: `repeat(auto-fill, minmax(${spec.column}px, 1fr))`, gap: size === "small" ? 1 : 2 }}>
              {group.cards.map(cluster => {
                const person = cluster.personId ? persons.find(p => p.id === cluster.personId) : undefined;
                const cardDropTarget = dropTargetId === cluster.id;
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
                    data-drop-target={cardDropTarget ? "true" : "false"}
                    data-focused={focusedId === cluster.id ? "true" : "false"}
                    // Reachable by keyboard, and only one card in the tab order: Tab gets you to the
                    // grid, the arrows move inside it. Twenty-five stops between the toolbar and the
                    // content below is the alternative.
                    tabIndex={focusedId === cluster.id || (focusedId === null && cluster.id === cardOrder[0]) ? 0 : -1}
                    onFocus={() => setFocusedId(cluster.id)}
                    onKeyDown={e => handleCardKeyDown(e, cluster)}
                    // Double-click rather than single: a single click on a card is how you pick it
                    // up to drag, and opening a dialog on that would make the grid unusable.
                    onDoubleClick={() => onAssignCluster(cluster.id)}
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
                    {...dropProps(sourceId => onMergeClusters?.(sourceId, cluster.id), cluster.id, cluster.id)}
                    sx={{
                      position: "relative",
                      bgcolor: tokens.bg.elevated,
                      border: `1px solid ${cardDropTarget ? tokens.primary.main : tokens.border.subtle}`,
                      borderRadius: tokens.radius.lg,
                      overflow: "hidden",
                      cursor: onMergeClusters ? "grab" : "default",
                      opacity: dragId === cluster.id ? 0.45 : 1,
                      boxShadow: cardDropTarget ? `0 0 0 2px ${tokens.primary.main}55` : "none",
                      transition: "border-color 120ms ease, box-shadow 120ms ease, opacity 120ms ease",
                      // Dotted, so the keyboard caret reads as "here" rather than as the solid
                      // ring a drop target gets — the two are on screen at the same time.
                      "&:focus": { outline: "none" },
                      "&:focus-visible, &[data-focused='true']:focus": {
                        outline: `2px dotted ${tokens.primary.main}`,
                        outlineOffset: 2,
                      },
                    }}
                  >
                    {/* Cluster header. Dropped entirely at `small`: see ClusterCardSize. */}
                    {spec.header && (
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
                    )}
                    {/* At `small` the person is the one thing that cannot be read off the faces, so it
                        stays - as a dot of colour and a tooltip rather than a row of its own. */}
                    {!spec.header && person && (
                      <Tooltip title={person.name}>
                        <Box data-testid="cluster-person-dot" data-person-name={person.name}
                          sx={{ position: "absolute", top: 4, right: 4, width: 8, height: 8, borderRadius: "50%", bgcolor: tokens.accent.green, zIndex: 1 }} />
                      </Tooltip>
                    )}
                    {/* Face thumbnails grid.

                        The crops are served from this deployment via <FaceCrop>. They used to be
                        https://i.pravatar.cc/80?u={faceId} — stock portraits of people who were not in the
                        picture, fetched by sending every detection uuid to a third party. Face crops are
                        biometric data and do not leave the deployment. */}
                    <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", p: spec.header ? 1.5 : 0.75 }}>
                      {(memberIds[cluster.id] ?? []).slice(0, spec.maxCrops).map(fid => (
                        <FaceCrop key={fid} assetUuid={cluster.assetId} detectionUuid={fid}
                          size={spec.crop} rounded={false}
                          // The whole reason the grid is readable at `small`: judging whether two clusters
                          // are one person needs a face bigger than a favicon, and opening each cluster to
                          // get one is the loop this replaces.
                          hoverZoom zoomCaption={cluster.label} />
                      ))}
                      {cluster.faceCount > spec.maxCrops && (
                        <Box sx={{ width: spec.crop, height: spec.crop, borderRadius: tokens.radius.sm, bgcolor: tokens.bg.overlay, display: "flex", alignItems: "center", justifyContent: "center" }}>
                          <Typography variant="caption" sx={{ fontSize: "0.7rem", color: tokens.text.tertiary }}>
                            +{cluster.faceCount - spec.maxCrops}
                          </Typography>
                        </Box>
                      )}
                    </Box>
                  </Paper>
                );
              })}
            </Box>
          </Box>
        );
      })}
      {clusters.length === 0 && (
        <Box data-testid="clusters-empty" sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
          <GroupWorkOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
          <Typography variant="body2" color="text.secondary">{t("faceDetection.empty.clusters")}</Typography>
        </Box>
      )}

      {/* Edit Cluster Dialog */}
      <Dialog open={!!editCluster} onClose={() => setEditCluster(null)} maxWidth="xs" fullWidth
        // Enter finishes it. One field and two buttons, and it used to need the mouse.
        onKeyDown={e => {
          if (e.key !== "Enter" || e.shiftKey) return;
          e.preventDefault();
          if (editName.trim()) void handleUpdate();
        }}>
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
