import React, { useState, useCallback } from "react";
import {
  Box, Typography, IconButton, TextField, Tooltip, Chip, Divider, Menu, MenuItem,
} from "@mui/material";
import {
  ExpandMore, ChevronRight, AddOutlined, EditOutlined,
  DeleteOutlineOutlined, LocalOfferOutlined, MoreVertOutlined,
  FolderOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";

interface TagNode {
  id: string;
  label: string;
  children: TagNode[];
}

// Initial mock tag tree
const INITIAL_TAGS: TagNode[] = [
  {
    id: "t1", label: "Vehicles", children: [
      {
        id: "t1a", label: "Cars", children: [
          { id: "t1a1", label: "Audi Quattro", children: [] },
          { id: "t1a2", label: "VW Käfer", children: [] },
          { id: "t1a3", label: "Tesla Roadster", children: [] },
        ],
      },
      {
        id: "t1b", label: "Trucks", children: [
          { id: "t1b1", label: "Ford F-150", children: [] },
        ],
      },
      { id: "t1c", label: "Motorcycles", children: [] },
    ],
  },
  {
    id: "t2", label: "Nature", children: [
      { id: "t2a", label: "Forest", children: [] },
      { id: "t2b", label: "Ocean", children: [] },
      {
        id: "t2c", label: "Animals", children: [
          { id: "t2c1", label: "Mammals", children: [] },
          { id: "t2c2", label: "Birds", children: [] },
          { id: "t2c3", label: "Reptiles", children: [] },
        ],
      },
    ],
  },
  {
    id: "t3", label: "People", children: [
      { id: "t3a", label: "Portraits", children: [] },
      { id: "t3b", label: "Groups", children: [] },
    ],
  },
  {
    id: "t4", label: "Architecture", children: [
      { id: "t4a", label: "Modern", children: [] },
      { id: "t4b", label: "Historic", children: [] },
    ],
  },
  { id: "t5", label: "Abstract", children: [] },
];

function countDescendants(node: TagNode): number {
  return node.children.reduce((sum, c) => sum + 1 + countDescendants(c), 0);
}

function TagTreeNode({
  node, depth, expanded, onToggle, onAdd, onRename, onDelete,
}: {
  node: TagNode;
  depth: number;
  expanded: Set<string>;
  onToggle: (id: string) => void;
  onAdd: (parentId: string) => void;
  onRename: (id: string, newLabel: string) => void;
  onDelete: (id: string) => void;
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
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 0.5,
          pl: depth * 2.5 + 0.5,
          pr: 1,
          py: 0.5,
          borderRadius: tokens.radius.sm,
          cursor: "pointer",
          "&:hover": { bgcolor: tokens.bg.hover },
          "&:hover .tag-actions": { opacity: 1 },
          transition: "background 100ms ease",
        }}
        onClick={() => hasChildren && onToggle(node.id)}
      >
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
          onToggle={onToggle}
          onAdd={onAdd}
          onRename={onRename}
          onDelete={onDelete}
        />
      ))}
    </>
  );
}

export default function TagsView() {
  const [tags, setTags] = useState<TagNode[]>(INITIAL_TAGS);
  const [expanded, setExpanded] = useState<Set<string>>(new Set(["t1", "t1a", "t2", "t2c"]));
  const [newTagInput, setNewTagInput] = useState("");

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
        ? { ...n, children: [...n.children, { id: newId, label: "New Tag", children: [] }] }
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
    setTags(prev => [...prev, { id: `t_${Date.now()}`, label, children: [] }]);
    setNewTagInput("");
  };

  const totalTags = tags.reduce((s, n) => s + 1 + countDescendants(n), 0);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Tags</Typography>
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
      </Box>

      {/* Tree */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2 }}>
        <Box sx={{ maxWidth: 600 }}>
          {tags.map(node => (
            <TagTreeNode
              key={node.id}
              node={node}
              depth={0}
              expanded={expanded}
              onToggle={toggleExpand}
              onAdd={addChild}
              onRename={renameTag}
              onDelete={deleteTag}
            />
          ))}
          {tags.length === 0 && (
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
              <LocalOfferOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
              <Typography variant="body2" color="text.secondary">No tags yet. Create a root tag to get started.</Typography>
            </Box>
          )}
        </Box>
      </Box>
    </Box>
  );
}
