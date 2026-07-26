import React, { useState, useCallback, useEffect, useRef } from "react";
import {
  Box, Typography, IconButton, TextField, Tooltip, Chip, Divider, Menu, MenuItem,
  InputAdornment, Button, Paper, Rating,
} from "@mui/material";
import {
  ExpandMore, ChevronRight, AddOutlined, EditOutlined,
  DeleteOutlineOutlined, LocalOfferOutlined, MoreVertOutlined,
  FolderOutlined, SearchOutlined, HelpOutlineOutlined,
  SaveOutlined, CloseOutlined, DragIndicatorOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import EmptyState from "../../components/EmptyState";
import { useAuth } from "../../context/AuthContext";
import {
  listTags, createTag, updateTag, deleteTag as apiDeleteTag,
  rateTag, loadTagRating, deleteTagRating,
  TagResponse,
} from "../../api/tags";
import { useTranslation } from "react-i18next";

// Backend tags are flat with a "collection" grouper.
// We present them as a two-level tree: collection → tag leaf nodes.

interface TagNode {
  /** For a collection header: `col:<name>`. For a real tag: the UUID. */
  id: string;
  label: string;
  /** The REST collection string. */
  collection: string;
  /** Only collection headers have children. */
  children: TagNode[];
  /** True when the node represents a real backend tag (has a UUID). */
  isTag: boolean;
}

/** Build a two-level tree from a flat list of TagResponse objects. */
function buildTree(tags: TagResponse[]): TagNode[] {
  const groups = new Map<string, TagNode[]>();
  for (const t of tags) {
    const col = t.collection || "uncategorized";
    if (!groups.has(col)) groups.set(col, []);
    groups.get(col)!.push({
      id: t.uuid,
      label: t.name,
      collection: col,
      children: [],
      isTag: true,
    });
  }
  const tree: TagNode[] = [];
  for (const [col, children] of groups) {
    tree.push({
      id: `col:${col}`,
      label: col,
      collection: col,
      children,
      isTag: false,
    });
  }
  tree.sort((a, b) => a.label.localeCompare(b.label));
  return tree;
}

function countDescendants(node: TagNode): number {
  return node.children.reduce((sum, c) => sum + 1 + countDescendants(c), 0);
}

// ── Tag tree row ──────────────────────────────────────────────────────
function TagTreeRow({
  node, depth, expanded, selectedId,
  onToggle, onSelect, onDelete,
  onDragStart, onDragOver, onDrop,
}: {
  node: TagNode;
  depth: number;
  expanded: Set<string>;
  selectedId: string | null;
  onToggle: (id: string) => void;
  onSelect: (node: TagNode) => void;
  onDelete: (node: TagNode) => void;
  onDragStart: (e: React.DragEvent, id: string) => void;
  onDragOver: (e: React.DragEvent) => void;
  onDrop: (e: React.DragEvent, targetCollectionId: string) => void;
}) {
  const isOpen = expanded.has(node.id);
  const hasChildren = node.children.length > 0;
  const [menuAnchor, setMenuAnchor] = useState<null | HTMLElement>(null);
  const { t } = useTranslation();

  return (
    <>
      <Box
        draggable={node.isTag}
        onDragStart={node.isTag ? e => onDragStart(e, node.id) : undefined}
        onDragOver={!node.isTag ? onDragOver : undefined}
        onDrop={!node.isTag ? e => onDrop(e, node.id) : undefined}
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 0.5,
          pl: depth * 2.5 + 0.5,
          pr: 1,
          py: 0.5,
          borderRadius: tokens.radius.sm,
          cursor: "pointer",
          bgcolor: selectedId === node.id ? tokens.primary.subtle : "transparent",
          "&:hover": { bgcolor: selectedId === node.id ? tokens.primary.subtle : tokens.bg.hover },
          "&:hover .tag-actions": { opacity: 1 },
          "&:hover .drag-handle": { opacity: 0.6 },
          transition: "background 100ms ease",
        }}
        onClick={() => { onSelect(node); if (hasChildren) onToggle(node.id); }}
      >
        {/* Drag handle — tags only */}
        {node.isTag ? (
          <Box className="drag-handle" sx={{ opacity: 0, display: "flex", alignItems: "center", transition: "opacity 100ms ease", cursor: "grab", flexShrink: 0 }}>
            <DragIndicatorOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
          </Box>
        ) : <Box sx={{ width: 14, flexShrink: 0 }} />}

        {/* Expand / leaf indicator */}
        <Box sx={{ width: 20, height: 20, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
          {hasChildren ? (
            isOpen ? <ExpandMore sx={{ fontSize: 16, color: tokens.text.tertiary }} /> : <ChevronRight sx={{ fontSize: 16, color: tokens.text.tertiary }} />
          ) : (
            <LocalOfferOutlined sx={{ fontSize: 13, color: tokens.text.tertiary }} />
          )}
        </Box>

        {/* Icon for collections */}
        {!node.isTag && <FolderOutlined sx={{ fontSize: 15, color: tokens.primary.main, flexShrink: 0 }} />}

        {/* Label */}
        <Typography
          variant="body2"
          sx={{ fontSize: "0.82rem", fontWeight: !node.isTag ? 600 : 400, color: tokens.text.primary, flex: 1, userSelect: "none" }}
        >
          {node.label}
        </Typography>

        {/* Child count badge */}
        {!node.isTag && (
          <Chip label={countDescendants(node)} size="small" sx={{ height: 16, fontSize: "0.6rem", bgcolor: tokens.bg.overlay, color: tokens.text.tertiary }} />
        )}

        {/* Actions (tags only) */}
        {node.isTag && (
          <Box className="tag-actions" sx={{ opacity: 0, display: "flex", gap: 0.25, transition: "opacity 100ms ease" }}>
            <IconButton size="small" onClick={e => { e.stopPropagation(); setMenuAnchor(e.currentTarget); }} sx={{ width: 20, height: 20 }}>
              <MoreVertOutlined sx={{ fontSize: 14 }} />
            </IconButton>
          </Box>
        )}
        <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => setMenuAnchor(null)}>
          <MenuItem onClick={() => { setMenuAnchor(null); onDelete(node); }} sx={{ gap: 1, fontSize: "0.82rem", color: tokens.accent.red }}>
              <DeleteOutlineOutlined sx={{ fontSize: 16 }} /> {t("tags.menu.delete")}
          </MenuItem>
        </Menu>
      </Box>

      {/* Children */}
      {isOpen && node.children.map(child => (
        <TagTreeRow
          key={child.id}
          node={child}
          depth={depth + 1}
          expanded={expanded}
          selectedId={selectedId}
          onToggle={onToggle}
          onSelect={onSelect}
          onDelete={onDelete}
          onDragStart={onDragStart}
          onDragOver={onDragOver}
          onDrop={onDrop}
        />
      ))}
    </>
  );
}

// ── Main view ─────────────────────────────────────────────────────────
export default function TagsView() {
  const { token } = useAuth();
  const { t } = useTranslation();
  const [allTags, setAllTags] = useState<TagResponse[]>([]);
  const [tree, setTree] = useState<TagNode[]>([]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set<string>());
  const [loading, setLoading] = useState(true);
  const newTagInputRef = useRef<HTMLInputElement>(null);
  const [newTagName, setNewTagName] = useState("");
  const [newTagCollection, setNewTagCollection] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedNode, setSelectedNode] = useState<TagNode | null>(null);
  const [editName, setEditName] = useState("");
  const [editCollection, setEditCollection] = useState("");
  const [rating, setRating] = useState<number | null>(null);
  const [dragId, setDragId] = useState<string | null>(null);

  // ── Load tags from API ──────────────────────────────────────────────
  const reload = useCallback(() => {
    if (!token) return;
    setLoading(true);
    listTags(token).then(resp => {
      const tags = resp.data ?? [];
      setAllTags(tags);
      const tree = buildTree(tags);
      setTree(tree);
      // Auto-expand all collections on first load
      setExpanded(prev => prev.size > 0 ? prev : new Set(tree.map(n => n.id)));
      setLoading(false);
    }).catch(() => setLoading(false));
  }, [token]);

  useEffect(() => { reload(); }, [reload]);

  // Rebuild tree whenever allTags changes
  useEffect(() => { setTree(buildTree(allTags)); }, [allTags]);

  // ── Create tag ──────────────────────────────────────────────────────
  const handleCreateTag = async () => {
    const name = newTagName.trim();
    const collection = newTagCollection.trim() || "general";
    if (!name || !token) return;
    const created = await createTag(token, { name, collection });
    setAllTags(prev => [...prev, created]);
    setNewTagName("");
    setNewTagCollection("");
    setExpanded(prev => new Set([...prev, `col:${collection}`]));
  };

  // ── Delete tag ──────────────────────────────────────────────────────
  const handleDeleteTag = useCallback(async (node: TagNode) => {
    if (!node.isTag || !token) return;
    await apiDeleteTag(token, node.id);
    setAllTags(prev => prev.filter(t => t.uuid !== node.id));
    if (selectedNode?.id === node.id) setSelectedNode(null);
  }, [token, selectedNode]);

  // ── Update tag (name / move to different collection) ────────────────
  const handleSaveTag = async () => {
    if (!selectedNode?.isTag || !token) return;
    const name = editName.trim();
    const collection = editCollection.trim() || "general";
    if (!name) return;
    const updated = await updateTag(token, selectedNode.id, { name, collection });
    setAllTags(prev => prev.map(t => t.uuid === updated.uuid ? updated : t));
    setSelectedNode({ ...selectedNode, label: updated.name, collection: updated.collection });
    setExpanded(prev => new Set([...prev, `col:${collection}`]));
  };

  // ── Drag-and-drop: move tag to a different collection ───────────────
  const handleDragStart = useCallback((e: React.DragEvent, id: string) => {
    setDragId(id);
    e.dataTransfer.effectAllowed = "move";
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
  }, []);

  const handleDrop = useCallback(async (e: React.DragEvent, targetCollectionId: string) => {
    e.preventDefault();
    if (!dragId || !token) { setDragId(null); return; }
    const targetCollection = targetCollectionId.replace(/^col:/, "");
    const tag = allTags.find(t => t.uuid === dragId);
    if (!tag || tag.collection === targetCollection) { setDragId(null); return; }
    const updated = await updateTag(token, dragId, { name: tag.name, collection: targetCollection });
    setAllTags(prev => prev.map(t => t.uuid === updated.uuid ? updated : t));
    setDragId(null);
  }, [dragId, token, allTags]);

  // ── Expand / collapse ──────────────────────────────────────────────
  const toggleExpand = useCallback((id: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  const handleSelectNode = useCallback((node: TagNode) => {
    setSelectedNode(node);
    if (node.isTag) {
      setEditName(node.label);
      setEditCollection(node.collection);
      setRating(null);
      if (token) {
        loadTagRating(token, node.id)
          .then(r => setRating(r.rating ?? null))
          .catch(() => setRating(null));
      }
    }
  }, [token]);

  // Per-user rating is persisted immediately (not via the Save button).
  const handleRate = async (value: number | null) => {
    if (!selectedNode?.isTag || !token) return;
    setRating(value);
    try {
      if (value === null) {
        await deleteTagRating(token, selectedNode.id);
      } else {
        await rateTag(token, selectedNode.id, value);
      }
    } catch {
      // Reload the persisted value on failure to keep the widget in sync.
      loadTagRating(token, selectedNode.id)
        .then(r => setRating(r.rating ?? null))
        .catch(() => setRating(null));
    }
  };

  // ── Derived state ──────────────────────────────────────────────────
  const totalTags = allTags.length;
  const collections = new Set(allTags.map(t => t.collection || "uncategorized"));

  // Filter tree: keep nodes (and parents) matching the query
  const filterTree = (nodes: TagNode[], q: string): TagNode[] => {
    if (!q) return nodes;
    return nodes
      .map(n => {
        const childMatches = filterTree(n.children, q);
        const selfMatch = n.label.toLowerCase().includes(q);
        if (selfMatch || childMatches.length > 0) {
          return { ...n, children: selfMatch ? n.children : childMatches };
        }
        return null;
      })
      .filter(Boolean) as TagNode[];
  };

  const displayTree = filterTree(tree, searchQuery.toLowerCase().trim());

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column", gap: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box>
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("tags.title")}</Typography>
              <Tooltip title={t("tags.tooltip.info")} arrow>
                <HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} />
              </Tooltip>
            </Box>
            <Typography variant="caption" color="text.secondary">{totalTags} {t("tags.count", { collections: collections.size })}</Typography>
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <TextField
              inputRef={newTagInputRef}
              value={newTagName}
              onChange={e => setNewTagName(e.target.value)}
              onKeyDown={e => { if (e.key === "Enter") handleCreateTag(); }}
              placeholder={t("tags.placeholder.name")}
              size="small"
              sx={{ width: 140, "& .MuiInputBase-root": { fontSize: "0.82rem" } }}
            />
            <TextField
              value={newTagCollection}
              onChange={e => setNewTagCollection(e.target.value)}
              onKeyDown={e => { if (e.key === "Enter") handleCreateTag(); }}
              placeholder={t("tags.placeholder.collection")}
              size="small"
              sx={{ width: 120, "& .MuiInputBase-root": { fontSize: "0.82rem" } }}
            />
              <Tooltip title={t("tags.tooltip.create")}>
              <IconButton size="small" onClick={handleCreateTag} sx={{ bgcolor: tokens.primary.main, color: "#fff", "&:hover": { bgcolor: tokens.primary.dark }, width: 28, height: 28 }}>
                <AddOutlined sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
          </Box>
        </Box>
        <TextField
          value={searchQuery}
          onChange={e => setSearchQuery(e.target.value)}
          placeholder={t("tags.search.placeholder")}  
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

      {/* Tree + Detail Sidebar */}
      <Box sx={{ flex: 1, display: "flex", overflow: "hidden" }}>
        <Box sx={{ flex: 1, overflow: "auto", p: 2 }}>
          <Box sx={{ maxWidth: 600 }}>
            {loading && displayTree.length === 0 && (
              <Typography variant="body2" color="text.secondary" sx={{ py: 4, textAlign: "center" }}>{t("tags.loading")}</Typography>
            )}
            {displayTree.map(node => (
              <TagTreeRow
                key={node.id}
                node={node}
                depth={0}
                expanded={expanded}
                selectedId={selectedNode?.id ?? null}
                onToggle={toggleExpand}
                onSelect={handleSelectNode}
                onDelete={handleDeleteTag}
                onDragStart={handleDragStart}
                onDragOver={handleDragOver}
                onDrop={handleDrop}
              />
            ))}
            {!loading && displayTree.length === 0 && (
              tree.length === 0 ? (
                // No tags at all — the CTA focuses the inline "new tag" field in the header.
                <EmptyState
                  icon={LocalOfferOutlined}
                  title={t("tags.emptyState.title")}
                  description={t("tags.emptyState.description")}
                  actionLabel={t("tags.emptyState.action")}
                  actionIcon={<AddOutlined sx={{ fontSize: 18 }} />}
                  onAction={() => newTagInputRef.current?.focus()}
                  testId="tags-empty-state"
                />
              ) : (
                <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                  <LocalOfferOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                  <Typography variant="body2" color="text.secondary">{t("tags.empty")}</Typography>
                </Box>
              )
            )}
          </Box>
        </Box>

        {/* Detail Sidebar */}
        {selectedNode?.isTag && (
          <Paper
            elevation={0}
            sx={{
              width: 280, flexShrink: 0, borderLeft: `1px solid ${tokens.border.subtle}`,
              bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column", overflow: "auto",
            }}
          >
            <Box sx={{ px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                <LocalOfferOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
                <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.88rem" }}>{t("tags.detail.title")}</Typography>
              </Box>
              <IconButton size="small" onClick={() => setSelectedNode(null)}>
                <CloseOutlined sx={{ fontSize: 14 }} />
              </IconButton>
            </Box>
            <Box sx={{ p: 2, display: "flex", flexDirection: "column", gap: 2 }}>
              <TextField
                label={t("tags.detail.name")}
                value={editName}
                onChange={e => setEditName(e.target.value)}
                size="small"
                fullWidth
              />
              <TextField
                label={t("tags.detail.collection")}
                value={editCollection}
                onChange={e => setEditCollection(e.target.value)}
                size="small"
                fullWidth
                placeholder={t("tags.detail.collectionPlaceholder")}
                helperText={t("tags.detail.collectionHelper")}
              />
              <Box data-testid="tags-rating" sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.75rem" }}>
                  {t("tags.detail.rating")}
                </Typography>
                <Rating
                  value={rating}
                  max={10}
                  size="small"
                  onChange={(_, v) => handleRate(v)}
                  sx={{
                    "& .MuiRating-iconFilled": { color: tokens.accent.amber },
                    "& .MuiRating-iconEmpty": { color: tokens.text.tertiary },
                  }}
                />
              </Box>
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                UUID: {selectedNode.id}
              </Typography>
              <Button
                size="small"
                variant="contained"
                startIcon={<SaveOutlined sx={{ fontSize: 14 }} />}
                onClick={handleSaveTag}
                disabled={!editName.trim()}
                sx={{ textTransform: "none" }}
              >
                {t("tags.detail.save")}
              </Button>
            </Box>
          </Paper>
        )}
      </Box>
    </Box>
  );
}
