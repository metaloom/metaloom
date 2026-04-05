import React, { useState, useCallback } from "react";
import {
  Box, Typography, IconButton, TextField, Tooltip, Chip, Divider, Menu, MenuItem,
  InputAdornment, Button, Paper,
} from "@mui/material";
import {
  ExpandMore, ChevronRight, AddOutlined, EditOutlined,
  DeleteOutlineOutlined, LocalOfferOutlined, MoreVertOutlined,
  FolderOutlined, SearchOutlined, HelpOutlineOutlined,
  SaveOutlined, CloseOutlined, DragIndicatorOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";

interface TagNode {
  id: string;
  label: string;
  glob: string;
  children: TagNode[];
}

// Initial mock tag tree
const INITIAL_TAGS: TagNode[] = [
  {
    id: "t1", label: "Vehicles", glob: "vehicles/**", children: [
      {
        id: "t1a", label: "Cars", glob: "vehicles/cars/**", children: [
          { id: "t1a1", label: "Audi Quattro", glob: "*audi*quattro*", children: [] },
          { id: "t1a2", label: "VW Käfer", glob: "*vw*kaefer*", children: [] },
          { id: "t1a3", label: "Tesla Roadster", glob: "*tesla*roadster*", children: [] },
        ],
      },
      {
        id: "t1b", label: "Trucks", glob: "vehicles/trucks/**", children: [
          { id: "t1b1", label: "Ford F-150", glob: "*ford*f150*", children: [] },
        ],
      },
      { id: "t1c", label: "Motorcycles", glob: "vehicles/motorcycles/**", children: [] },
    ],
  },
  {
    id: "t2", label: "Nature", glob: "nature/**", children: [
      { id: "t2a", label: "Forest", glob: "nature/forest/**", children: [] },
      { id: "t2b", label: "Ocean", glob: "nature/ocean/**", children: [] },
      {
        id: "t2c", label: "Animals", glob: "nature/animals/**", children: [
          { id: "t2c1", label: "Mammals", glob: "nature/animals/mammals/**", children: [] },
          { id: "t2c2", label: "Birds", glob: "nature/animals/birds/**", children: [] },
          { id: "t2c3", label: "Reptiles", glob: "nature/animals/reptiles/**", children: [] },
        ],
      },
    ],
  },
  {
    id: "t3", label: "People", glob: "people/**", children: [
      { id: "t3a", label: "Portraits", glob: "people/portraits/**", children: [] },
      { id: "t3b", label: "Groups", glob: "people/groups/**", children: [] },
    ],
  },
  {
    id: "t4", label: "Architecture", glob: "architecture/**", children: [
      { id: "t4a", label: "Modern", glob: "architecture/modern/**", children: [] },
      { id: "t4b", label: "Historic", glob: "architecture/historic/**", children: [] },
    ],
  },
  { id: "t5", label: "Abstract", glob: "abstract/**", children: [] },
];

function countDescendants(node: TagNode): number {
  return node.children.reduce((sum, c) => sum + 1 + countDescendants(c), 0);
}

function TagTreeNode({
  node, depth, expanded, selectedId, onToggle, onSelect, onAdd, onRename, onDelete, onDragStart, onDragOver, onDrop,
}: {
  node: TagNode;
  depth: number;
  expanded: Set<string>;
  selectedId: string | null;
  onToggle: (id: string) => void;
  onSelect: (node: TagNode) => void;
  onAdd: (parentId: string) => void;
  onRename: (id: string, newLabel: string) => void;
  onDelete: (id: string) => void;
  onDragStart: (e: React.DragEvent, id: string) => void;
  onDragOver: (e: React.DragEvent) => void;
  onDrop: (e: React.DragEvent, targetId: string) => void;
}) {
  const isOpen = expanded.has(node.id);
  const hasChildren = node.children.length > 0;
  const [menuAnchor, setMenuAnchor] = useState<null | HTMLElement>(null);
  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState(node.label);

  const handleRename = () => {
    if (editValue.trim() && editValue.trim() !== node.label) {
      onRename(node.id, editValue.trim());
    }
    setEditing(false);
  };

  return (
    <>
      <Box
        draggable
        onDragStart={e => onDragStart(e, node.id)}
        onDragOver={onDragOver}
        onDrop={e => onDrop(e, node.id)}
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
        {/* Drag handle */}
        <Box className="drag-handle" sx={{ opacity: 0, display: "flex", alignItems: "center", transition: "opacity 100ms ease", cursor: "grab", flexShrink: 0 }}>
          <DragIndicatorOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
        </Box>
        {/* Expand / leaf indicator */}
        <Box sx={{ width: 20, height: 20, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
          {hasChildren ? (
            isOpen ? <ExpandMore sx={{ fontSize: 16, color: tokens.text.tertiary }} /> : <ChevronRight sx={{ fontSize: 16, color: tokens.text.tertiary }} />
          ) : (
            <LocalOfferOutlined sx={{ fontSize: 13, color: tokens.text.tertiary }} />
          )}
        </Box>

        {/* Icon for folders */}
        {hasChildren && <FolderOutlined sx={{ fontSize: 15, color: tokens.primary.main, flexShrink: 0 }} />}

        {/* Label or edit field */}
        {editing ? (
          <TextField
            value={editValue}
            onChange={e => setEditValue(e.target.value)}
            onBlur={handleRename}
            onKeyDown={e => { if (e.key === "Enter") handleRename(); if (e.key === "Escape") setEditing(false); }}
            autoFocus
            size="small"
            variant="standard"
            onClick={e => e.stopPropagation()}
            sx={{ flex: 1, "& .MuiInput-root": { fontSize: "0.82rem" } }}
          />
        ) : (
          <Typography
            variant="body2"
            sx={{ fontSize: "0.82rem", fontWeight: hasChildren ? 600 : 400, color: tokens.text.primary, flex: 1, userSelect: "none" }}
            onDoubleClick={(e) => { e.stopPropagation(); setEditValue(node.label); setEditing(true); }}
          >
            {node.label}
          </Typography>
        )}

        {/* Child count badge */}
        {hasChildren && (
          <Chip label={countDescendants(node)} size="small" sx={{ height: 16, fontSize: "0.6rem", bgcolor: tokens.bg.overlay, color: tokens.text.tertiary }} />
        )}

        {/* Actions */}
        <Box className="tag-actions" sx={{ opacity: 0, display: "flex", gap: 0.25, transition: "opacity 100ms ease" }}>
          <IconButton size="small" onClick={e => { e.stopPropagation(); setMenuAnchor(e.currentTarget); }} sx={{ width: 20, height: 20 }}>
            <MoreVertOutlined sx={{ fontSize: 14 }} />
          </IconButton>
        </Box>
        <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => setMenuAnchor(null)}>
          <MenuItem onClick={() => { setMenuAnchor(null); onAdd(node.id); }} sx={{ gap: 1, fontSize: "0.82rem" }}>
            <AddOutlined sx={{ fontSize: 16 }} /> Add child tag
          </MenuItem>
          <MenuItem onClick={() => { setMenuAnchor(null); setEditValue(node.label); setEditing(true); }} sx={{ gap: 1, fontSize: "0.82rem" }}>
            <EditOutlined sx={{ fontSize: 16 }} /> Rename
          </MenuItem>
          <Divider />
          <MenuItem onClick={() => { setMenuAnchor(null); onDelete(node.id); }} sx={{ gap: 1, fontSize: "0.82rem", color: tokens.accent.red }}>
            <DeleteOutlineOutlined sx={{ fontSize: 16 }} /> Delete
          </MenuItem>
        </Menu>
      </Box>

      {/* Children */}
      {isOpen && node.children.map(child => (
        <TagTreeNode
          key={child.id}
          node={child}
          depth={depth + 1}
          expanded={expanded}
          selectedId={selectedId}
          onToggle={onToggle}
          onSelect={onSelect}
          onAdd={onAdd}
          onRename={onRename}
          onDelete={onDelete}
          onDragStart={onDragStart}
          onDragOver={onDragOver}
          onDrop={onDrop}
        />
      ))}
    </>
  );
}

export default function TagsView() {
  const [tags, setTags] = useState<TagNode[]>(INITIAL_TAGS);
  const [expanded, setExpanded] = useState<Set<string>>(new Set(["t1", "t1a", "t2", "t2c"]));
  const [newTagInput, setNewTagInput] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedTag, setSelectedTag] = useState<TagNode | null>(null);
  const [editName, setEditName] = useState("");
  const [editGlob, setEditGlob] = useState("");
  const [dragId, setDragId] = useState<string | null>(null);

  const toggleExpand = useCallback((id: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  // Recursive helpers
  const addChild = useCallback((parentId: string) => {
    const newId = `t_${Date.now()}`;
    const addToTree = (nodes: TagNode[]): TagNode[] =>
      nodes.map(n => n.id === parentId
        ? { ...n, children: [...n.children, { id: newId, label: "New Tag", glob: "", children: [] }] }
        : { ...n, children: addToTree(n.children) });
    setTags(prev => addToTree(prev));
    setExpanded(prev => new Set([...prev, parentId]));
  }, []);

  const renameTag = useCallback((id: string, label: string) => {
    const renameInTree = (nodes: TagNode[]): TagNode[] =>
      nodes.map(n => n.id === id ? { ...n, label } : { ...n, children: renameInTree(n.children) });
    setTags(prev => renameInTree(prev));
  }, []);

  const deleteTag = useCallback((id: string) => {
    const removeFromTree = (nodes: TagNode[]): TagNode[] =>
      nodes.filter(n => n.id !== id).map(n => ({ ...n, children: removeFromTree(n.children) }));
    setTags(prev => removeFromTree(prev));
  }, []);

  const addRootTag = () => {
    const label = newTagInput.trim();
    if (!label) return;
    setTags(prev => [...prev, { id: `t_${Date.now()}`, label, glob: "", children: [] }]);
    setNewTagInput("");
  };

  const handleSelectTag = useCallback((node: TagNode) => {
    setSelectedTag(node);
    setEditName(node.label);
    setEditGlob(node.glob);
  }, []);

  const handleSaveTag = () => {
    if (!selectedTag) return;
    const updateInTree = (nodes: TagNode[]): TagNode[] =>
      nodes.map(n => n.id === selectedTag.id ? { ...n, label: editName.trim() || n.label, glob: editGlob } : { ...n, children: updateInTree(n.children) });
    setTags(prev => updateInTree(prev));
    setSelectedTag(prev => prev ? { ...prev, label: editName.trim() || prev.label, glob: editGlob } : null);
  };

  // Drag-and-drop: reorder siblings
  const handleDragStart = useCallback((e: React.DragEvent, id: string) => {
    setDragId(id);
    e.dataTransfer.effectAllowed = "move";
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
  }, []);

  const handleDrop = useCallback((e: React.DragEvent, targetId: string) => {
    e.preventDefault();
    if (!dragId || dragId === targetId) return;
    const reorderSiblings = (nodes: TagNode[]): TagNode[] => {
      const srcIdx = nodes.findIndex(n => n.id === dragId);
      const tgtIdx = nodes.findIndex(n => n.id === targetId);
      if (srcIdx !== -1 && tgtIdx !== -1) {
        const copy = [...nodes];
        const [moved] = copy.splice(srcIdx, 1);
        copy.splice(tgtIdx, 0, moved);
        return copy;
      }
      return nodes.map(n => ({ ...n, children: reorderSiblings(n.children) }));
    };
    setTags(prev => reorderSiblings(prev));
    setDragId(null);
  }, [dragId]);

  const totalTags = tags.reduce((s, n) => s + 1 + countDescendants(n), 0);

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

  const displayTags = filterTree(tags, searchQuery.toLowerCase().trim());

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column", gap: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box>
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Tags</Typography>
              <Tooltip title="Tags are hierarchical labels used to classify and filter assets. Assign tags manually or via automated workflows." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
            </Box>
            <Typography variant="caption" color="text.secondary">{totalTags} tags across {tags.length} root categories</Typography>
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <TextField
              value={newTagInput}
              onChange={e => setNewTagInput(e.target.value)}
              onKeyDown={e => { if (e.key === "Enter") addRootTag(); }}
              placeholder="New root tag…"
              size="small"
              sx={{ width: 180, "& .MuiInputBase-root": { fontSize: "0.82rem" } }}
            />
            <Tooltip title="Add root tag">
              <IconButton size="small" onClick={addRootTag} sx={{ bgcolor: tokens.primary.main, color: "#fff", "&:hover": { bgcolor: tokens.primary.dark }, width: 28, height: 28 }}>
                <AddOutlined sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
          </Box>
        </Box>
        <TextField
          value={searchQuery}
          onChange={e => setSearchQuery(e.target.value)}
          placeholder="Filter tags…"
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
            {displayTags.map(node => (
              <TagTreeNode
                key={node.id}
                node={node}
                depth={0}
                expanded={expanded}
                selectedId={selectedTag?.id ?? null}
                onToggle={toggleExpand}
                onSelect={handleSelectTag}
                onAdd={addChild}
                onRename={renameTag}
                onDelete={deleteTag}
                onDragStart={handleDragStart}
                onDragOver={handleDragOver}
                onDrop={handleDrop}
              />
            ))}
            {displayTags.length === 0 && (
              <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                <LocalOfferOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                <Typography variant="body2" color="text.secondary">No tags yet. Create a root tag to get started.</Typography>
              </Box>
            )}
          </Box>
        </Box>

        {/* Detail Sidebar */}
        {selectedTag && (
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
                <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.88rem" }}>Tag Details</Typography>
              </Box>
              <IconButton size="small" onClick={() => setSelectedTag(null)}>
                <CloseOutlined sx={{ fontSize: 14 }} />
              </IconButton>
            </Box>
            <Box sx={{ p: 2, display: "flex", flexDirection: "column", gap: 2 }}>
              <TextField
                label="Name"
                value={editName}
                onChange={e => setEditName(e.target.value)}
                size="small"
                fullWidth
              />
              <TextField
                label="Glob pattern"
                value={editGlob}
                onChange={e => setEditGlob(e.target.value)}
                size="small"
                fullWidth
                placeholder="e.g. vehicles/cars/**"
                helperText="File matching pattern for auto-tagging"
              />
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                ID: {selectedTag.id} · Children: {selectedTag.children.length}
              </Typography>
              <Button
                size="small"
                variant="contained"
                startIcon={<SaveOutlined sx={{ fontSize: 14 }} />}
                onClick={handleSaveTag}
                disabled={!editName.trim()}
                sx={{ textTransform: "none" }}
              >
                Save
              </Button>
            </Box>
          </Paper>
        )}
      </Box>
    </Box>
  );
}
