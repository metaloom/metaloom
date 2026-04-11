import React, { useEffect, useState, useCallback } from "react";
import { Routes, Route, useNavigate, useLocation } from "react-router-dom";
import {
  Box, Typography, Tab, Tabs, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Paper, Chip, Avatar, IconButton, Tooltip,
  TextField, Button, Select, MenuItem, FormControl, InputLabel, Stack,
  Divider, Switch, Dialog, DialogTitle, DialogContent, DialogActions,
  Checkbox, FormControlLabel, FormGroup, Collapse, InputAdornment,
} from "@mui/material";
import {
  PersonAddOutlined, VpnKeyOutlined, BlockOutlined, GroupsOutlined,
  SecurityOutlined, EditOutlined, DeleteOutlineOutlined, AddOutlined,
  CloseOutlined, ExpandMoreOutlined, ExpandLessOutlined, LockOutlined,
  CheckBoxOutlined, CheckBoxOutlineBlankOutlined, SearchOutlined,
  MoreVertOutlined, HelpOutlineOutlined,
} from "@mui/icons-material";
import { Menu } from "@mui/material";
import { tokens } from "../../theme";
import { Permission, ApiKey, BlacklistEntry } from "../../types";
import { mockAdminService } from "../../mock/services";
import { useAuth } from "../../context/AuthContext";
import {
  listUsers, createUser, updateUser, deleteUser,
  UserResponse, UserCreateRequest,
} from "../../api/users";
import {
  listGroups, createGroup, updateGroup, deleteGroup,
  GroupResponse,
} from "../../api/groups";
import {
  listRoles, createRole, updateRole, deleteRole,
  RoleResponse,
} from "../../api/roles";
import {
  listSpaces, createSpace, updateSpace, deleteSpace,
  SpaceResponse,
} from "../../api/spaces";

// ── Spaces Table ──────────────────────────────────────────────────────────
function SpacesAdmin() {
  const { token } = useAuth();
  const [spaces, setSpaces] = useState<SpaceResponse[]>([]);
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [editSpace, setEditSpace] = useState<SpaceResponse | null>(null);
  const [editName, setEditName] = useState("");
  const [deleteConfirm, setDeleteConfirm] = useState<SpaceResponse | null>(null);

  const reload = useCallback(async () => {
    if (!token) return;
    try {
      const resp = await listSpaces(token);
      setSpaces(resp.data ?? []);
    } catch (e) {
      console.error("Failed to load spaces", e);
    }
  }, [token]);

  useEffect(() => { reload(); }, [reload]);

  const handleCreate = async () => {
    if (!newName.trim() || !token) return;
    try {
      await createSpace(token, { name: newName.trim() });
      setCreateOpen(false);
      setNewName("");
      reload();
    } catch (e) {
      console.error("Failed to create space", e);
    }
  };

  const openEdit = (s: SpaceResponse) => {
    setEditSpace(s);
    setEditName(s.name);
  };

  const handleSaveEdit = async () => {
    if (!editSpace || !token) return;
    try {
      await updateSpace(token, editSpace.uuid, { name: editName.trim() || undefined });
      setEditSpace(null);
      reload();
    } catch (e) {
      console.error("Failed to update space", e);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirm || !token) return;
    try {
      await deleteSpace(token, deleteConfirm.uuid);
      setDeleteConfirm(null);
      reload();
    } catch (e) {
      console.error("Failed to delete space", e);
    }
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Spaces</Typography>
            <Tooltip title="Spaces organise projects, libraries and assets into logical groups." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary">{spaces.length} spaces</Typography>
        </Box>
        <Button startIcon={<AddOutlined />} variant="contained" size="small" onClick={() => setCreateOpen(true)}>New Space</Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Search spaces…"
        size="small"
        sx={{ mb: 1.5, maxWidth: 320 }}
        fullWidth
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
            </InputAdornment>
          ),
        }}
      />
      <TableContainer component={Paper} elevation={0}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>UUID</TableCell>
              <TableCell>Created</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {spaces.filter(s => {
              if (!query.trim()) return true;
              return s.name.toLowerCase().includes(query.toLowerCase());
            }).map(s => (
              <TableRow key={s.uuid} hover sx={{ cursor: "pointer" }} onClick={() => openEdit(s)}>
                <TableCell><Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{s.name}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace", fontSize: "0.7rem" }}>{s.uuid}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{s.status?.created ? new Date(s.status.created).toLocaleDateString() : "—"}</Typography></TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={e => { e.stopPropagation(); openEdit(s); }}>
                    <EditOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                  <IconButton size="small" onClick={e => { e.stopPropagation(); setDeleteConfirm(s); }}>
                    <DeleteOutlineOutlined sx={{ fontSize: 15, color: tokens.accent.red }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Create Space dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>Create Space</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField label="Space name" size="small" fullWidth value={newName} onChange={e => setNewName(e.target.value)} autoFocus placeholder="e.g. Production" />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button size="small" variant="contained" onClick={handleCreate} disabled={!newName.trim()}>Create Space</Button>
        </DialogActions>
      </Dialog>

      {/* Edit Space dialog */}
      <Dialog open={Boolean(editSpace)} onClose={() => setEditSpace(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Edit Space</Typography>
          <IconButton size="small" onClick={() => setEditSpace(null)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
            {editSpace && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>UUID: {editSpace.uuid}</Typography>
            )}
            <TextField label="Space name" size="small" fullWidth value={editName} onChange={e => setEditName(e.target.value)} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditSpace(null)} size="small">Cancel</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit}>Save</Button>
        </DialogActions>
      </Dialog>

      {/* Delete Confirm Dialog */}
      <Dialog open={Boolean(deleteConfirm)} onClose={() => setDeleteConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Delete Space</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            Are you sure you want to delete space <strong>{deleteConfirm?.name}</strong>?
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteConfirm(null)} size="small">Cancel</Button>
          <Button variant="contained" color="error" size="small" onClick={handleDelete}>Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Users Table ───────────────────────────────────────────────────────────
function UsersAdmin() {
  const { token } = useAuth();
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [editUser, setEditUser] = useState<UserResponse | null>(null);
  const [editForm, setEditForm] = useState({ username: "", email: "", firstname: "", lastname: "" });
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState({ username: "", email: "", firstname: "", lastname: "" });
  const [deleteConfirm, setDeleteConfirm] = useState<UserResponse | null>(null);

  const reload = useCallback(async () => {
    if (!token) return;
    try {
      const resp = await listUsers(token);
      setUsers(resp.data ?? []);
    } catch (e) {
      console.error("Failed to load users", e);
    }
  }, [token]);

  useEffect(() => { reload(); }, [reload]);

  const openEdit = (user: UserResponse) => {
    setEditUser(user);
    setEditForm({ username: user.username ?? "", email: user.email ?? "", firstname: user.firstname ?? "", lastname: user.lastname ?? "" });
  };

  const handleSaveEdit = async () => {
    if (!editUser || !token) return;
    try {
      await updateUser(token, editUser.uuid, {
        username: editForm.username || undefined,
        email: editForm.email || undefined,
        firstname: editForm.firstname || undefined,
        lastname: editForm.lastname || undefined,
      });
      setEditUser(null);
      reload();
    } catch (e) {
      console.error("Failed to update user", e);
    }
  };

  const handleCreate = async () => {
    if (!createForm.username.trim() || !token) return;
    try {
      await createUser(token, {
        username: createForm.username.trim(),
        email: createForm.email.trim() || undefined,
        firstname: createForm.firstname.trim() || undefined,
        lastname: createForm.lastname.trim() || undefined,
      });
      setCreateOpen(false);
      setCreateForm({ username: "", email: "", firstname: "", lastname: "" });
      reload();
    } catch (e) {
      console.error("Failed to create user", e);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirm || !token) return;
    try {
      await deleteUser(token, deleteConfirm.uuid);
      setDeleteConfirm(null);
      reload();
    } catch (e) {
      console.error("Failed to delete user", e);
    }
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Users</Typography>
            <Tooltip title="Manage user accounts and access levels. Create new users or remove existing ones." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary">{users.length} accounts</Typography>
        </Box>
        <Button startIcon={<PersonAddOutlined />} variant="contained" size="small" onClick={() => setCreateOpen(true)}>
          Create User
        </Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Search users…"
        size="small"
        sx={{ mb: 1.5, maxWidth: 320 }}
        fullWidth
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
            </InputAdornment>
          ),
        }}
      />
      <TableContainer component={Paper} elevation={0}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>User</TableCell>
              <TableCell>Email</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Created</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {users.filter(u => {
              if (!query.trim()) return true;
              const q = query.toLowerCase();
              return (u.username ?? "").toLowerCase().includes(q) || (u.email ?? "").toLowerCase().includes(q) || (u.firstname ?? "").toLowerCase().includes(q) || (u.lastname ?? "").toLowerCase().includes(q);
            }).map(u => (
              <TableRow key={u.uuid} hover sx={{ cursor: "pointer" }} onClick={() => openEdit(u)}>
                <TableCell>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
                    <Avatar sx={{ width: 28, height: 28, fontSize: "0.7rem", bgcolor: u.enabled ? tokens.primary.dark : tokens.text.tertiary, opacity: u.enabled ? 1 : 0.5 }}>
                      {(u.firstname ?? u.username ?? "?").charAt(0).toUpperCase()}
                    </Avatar>
                    <Box>
                      <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem", opacity: u.enabled ? 1 : 0.5 }}>{u.username}</Typography>
                      {(u.firstname || u.lastname) && (
                        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>{[u.firstname, u.lastname].filter(Boolean).join(" ")}</Typography>
                      )}
                    </Box>
                  </Box>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" color="text.secondary">{u.email ?? "—"}</Typography>
                </TableCell>
                <TableCell>
                  <Chip
                    label={u.enabled ? "enabled" : "disabled"}
                    size="small"
                    sx={{ height: 18, fontSize: "0.65rem", bgcolor: u.enabled ? `${tokens.accent.green}22` : `${tokens.accent.red}22`, color: u.enabled ? tokens.accent.green : tokens.accent.red }}
                  />
                </TableCell>
                <TableCell>
                  <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
                    {u.status?.created ? new Date(u.status.created).toLocaleDateString() : "—"}
                  </Typography>
                </TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={e => { e.stopPropagation(); openEdit(u); }}>
                    <EditOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                  <IconButton size="small" onClick={e => { e.stopPropagation(); setDeleteConfirm(u); }}>
                    <DeleteOutlineOutlined sx={{ fontSize: 15, color: tokens.accent.red }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Create User Dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Create User</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
            <TextField label="Username" size="small" fullWidth value={createForm.username} onChange={e => setCreateForm(f => ({ ...f, username: e.target.value }))} autoFocus />
            <TextField label="Email" size="small" fullWidth type="email" value={createForm.email} onChange={e => setCreateForm(f => ({ ...f, email: e.target.value }))} />
            <TextField label="Firstname" size="small" fullWidth value={createForm.firstname} onChange={e => setCreateForm(f => ({ ...f, firstname: e.target.value }))} />
            <TextField label="Lastname" size="small" fullWidth value={createForm.lastname} onChange={e => setCreateForm(f => ({ ...f, lastname: e.target.value }))} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setCreateOpen(false)} size="small">Cancel</Button>
          <Button variant="contained" size="small" onClick={handleCreate} disabled={!createForm.username.trim()}>Create</Button>
        </DialogActions>
      </Dialog>

      {/* Edit User Dialog */}
      <Dialog open={Boolean(editUser)} onClose={() => setEditUser(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Edit User</Typography>
          <IconButton size="small" onClick={() => setEditUser(null)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          {editUser && (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Avatar sx={{ width: 56, height: 56, bgcolor: tokens.primary.dark, fontSize: "1.15rem" }}>
                  {(editUser.firstname ?? editUser.username ?? "?").charAt(0).toUpperCase()}
                </Avatar>
                <Box sx={{ flex: 1 }}>
                  <Typography variant="body2" sx={{ color: tokens.text.tertiary, fontSize: "0.75rem" }}>UUID: {editUser.uuid}</Typography>
                  {editUser.status?.created && (
                    <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                      Created {new Date(editUser.status.created).toLocaleDateString()}
                    </Typography>
                  )}
                </Box>
              </Box>
              <TextField label="Username" size="small" fullWidth value={editForm.username} onChange={e => setEditForm(f => ({ ...f, username: e.target.value }))} />
              <TextField label="Email" size="small" fullWidth type="email" value={editForm.email} onChange={e => setEditForm(f => ({ ...f, email: e.target.value }))} />
              <TextField label="Firstname" size="small" fullWidth value={editForm.firstname} onChange={e => setEditForm(f => ({ ...f, firstname: e.target.value }))} />
              <TextField label="Lastname" size="small" fullWidth value={editForm.lastname} onChange={e => setEditForm(f => ({ ...f, lastname: e.target.value }))} />
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditUser(null)} size="small">Cancel</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit} sx={{ textTransform: "none", fontWeight: 600 }}>Save</Button>
        </DialogActions>
      </Dialog>

      {/* Delete Confirm Dialog */}
      <Dialog open={Boolean(deleteConfirm)} onClose={() => setDeleteConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Delete User</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            Are you sure you want to delete user <strong>{deleteConfirm?.username}</strong>? This action cannot be undone.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteConfirm(null)} size="small">Cancel</Button>
          <Button variant="contained" color="error" size="small" onClick={handleDelete}>Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Groups Table ──────────────────────────────────────────────────────────
function GroupsAdmin() {
  const { token } = useAuth();
  const [groups, setGroups] = useState<GroupResponse[]>([]);
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [editGroup, setEditGroup] = useState<GroupResponse | null>(null);
  const [editName, setEditName] = useState("");
  const [deleteConfirm, setDeleteConfirm] = useState<GroupResponse | null>(null);

  const reload = useCallback(async () => {
    if (!token) return;
    try {
      const resp = await listGroups(token);
      setGroups(resp.data ?? []);
    } catch (e) {
      console.error("Failed to load groups", e);
    }
  }, [token]);

  useEffect(() => { reload(); }, [reload]);

  const handleCreateGroup = async () => {
    if (!newName.trim() || !token) return;
    try {
      await createGroup(token, { name: newName.trim() });
      setCreateOpen(false);
      setNewName("");
      reload();
    } catch (e) {
      console.error("Failed to create group", e);
    }
  };

  const openEdit = (g: GroupResponse) => {
    setEditGroup(g);
    setEditName(g.name);
  };

  const handleSaveEdit = async () => {
    if (!editGroup || !token) return;
    try {
      await updateGroup(token, editGroup.uuid, { name: editName.trim() || undefined });
      setEditGroup(null);
      reload();
    } catch (e) {
      console.error("Failed to update group", e);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirm || !token) return;
    try {
      await deleteGroup(token, deleteConfirm.uuid);
      setDeleteConfirm(null);
      reload();
    } catch (e) {
      console.error("Failed to delete group", e);
    }
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Groups</Typography>
            <Tooltip title="Groups let you organise users and assign shared permissions." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary">{groups.length} groups</Typography>
        </Box>
        <Button startIcon={<AddOutlined />} variant="contained" size="small" onClick={() => setCreateOpen(true)}>New Group</Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Search groups…"
        size="small"
        sx={{ mb: 1.5, maxWidth: 320 }}
        fullWidth
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
            </InputAdornment>
          ),
        }}
      />
      <TableContainer component={Paper} elevation={0}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>UUID</TableCell>
              <TableCell>Created</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {groups.filter(g => {
              if (!query.trim()) return true;
              const q = query.toLowerCase();
              return g.name.toLowerCase().includes(q);
            }).map(g => (
              <TableRow key={g.uuid} hover sx={{ cursor: "pointer" }} onClick={() => openEdit(g)}>
                <TableCell><Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{g.name}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace", fontSize: "0.7rem" }}>{g.uuid}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{g.status?.created ? new Date(g.status.created).toLocaleDateString() : "—"}</Typography></TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={e => { e.stopPropagation(); openEdit(g); }}>
                    <EditOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                  <IconButton size="small" onClick={e => { e.stopPropagation(); setDeleteConfirm(g); }}>
                    <DeleteOutlineOutlined sx={{ fontSize: 15, color: tokens.accent.red }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Create Group dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <GroupsOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>Create Group</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField label="Group name" size="small" fullWidth value={newName} onChange={e => setNewName(e.target.value)} autoFocus placeholder="e.g. Engineering" />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button size="small" variant="contained" onClick={handleCreateGroup} disabled={!newName.trim()}>Create Group</Button>
        </DialogActions>
      </Dialog>

      {/* Edit Group dialog */}
      <Dialog open={Boolean(editGroup)} onClose={() => setEditGroup(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Edit Group</Typography>
          <IconButton size="small" onClick={() => setEditGroup(null)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
            {editGroup && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>UUID: {editGroup.uuid}</Typography>
            )}
            <TextField label="Group name" size="small" fullWidth value={editName} onChange={e => setEditName(e.target.value)} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditGroup(null)} size="small">Cancel</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit}>Save</Button>
        </DialogActions>
      </Dialog>

      {/* Delete Confirm Dialog */}
      <Dialog open={Boolean(deleteConfirm)} onClose={() => setDeleteConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Delete Group</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            Are you sure you want to delete group <strong>{deleteConfirm?.name}</strong>?
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteConfirm(null)} size="small">Cancel</Button>
          <Button variant="contained" color="error" size="small" onClick={handleDelete}>Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Access Control (Roles with Permissions) ──────────────────────────────
function AccessControlAdmin() {
  const { token } = useAuth();
  const [roles, setRoles] = useState<RoleResponse[]>([]);
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [newRoleName, setNewRoleName] = useState("");
  const [editRole, setEditRole] = useState<RoleResponse | null>(null);
  const [editName, setEditName] = useState("");
  const [deleteConfirm, setDeleteConfirm] = useState<RoleResponse | null>(null);
  const [saving, setSaving] = useState(false);

  const reload = useCallback(async () => {
    if (!token) return;
    try {
      const resp = await listRoles(token);
      setRoles(resp.data ?? []);
      if (!selectedRoleId && resp.data?.length) {
        setSelectedRoleId(resp.data[0].uuid);
      }
    } catch (e) {
      console.error("Failed to load roles", e);
    }
  }, [token]);

  useEffect(() => { reload(); }, [reload]);

  const selectedRole = roles.find(r => r.uuid === selectedRoleId) ?? null;

  const hasPermission = (perm: string) => selectedRole?.permissions?.includes(perm) ?? false;

  const togglePermission = useCallback(async (perm: string) => {
    if (!selectedRole || !token) return;
    const current = selectedRole.permissions ?? [];
    const next = current.includes(perm)
      ? current.filter(p => p !== perm)
      : [...current, perm];
    setSaving(true);
    try {
      await updateRole(token, selectedRole.uuid, { permissions: next });
      reload();
    } catch (e) {
      console.error("Failed to update role permissions", e);
    } finally {
      setSaving(false);
    }
  }, [selectedRole, token, reload]);

  const handleCreateRole = async () => {
    if (!newRoleName.trim() || !token) return;
    try {
      await createRole(token, { name: newRoleName.trim() });
      setCreateOpen(false);
      setNewRoleName("");
      reload();
    } catch (e) {
      console.error("Failed to create role", e);
    }
  };

  const openEditRole = (r: RoleResponse) => {
    setEditRole(r);
    setEditName(r.name);
  };

  const handleSaveEdit = async () => {
    if (!editRole || !token) return;
    try {
      await updateRole(token, editRole.uuid, { name: editName.trim() || undefined });
      setEditRole(null);
      reload();
    } catch (e) {
      console.error("Failed to update role", e);
    }
  };

  const handleDeleteRole = async () => {
    if (!deleteConfirm || !token) return;
    try {
      await deleteRole(token, deleteConfirm.uuid);
      setDeleteConfirm(null);
      if (selectedRoleId === deleteConfirm.uuid) setSelectedRoleId(null);
      reload();
    } catch (e) {
      console.error("Failed to delete role", e);
    }
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Access Control</Typography>
          <Typography variant="caption" color="text.secondary">
            {roles.length} roles
            {saving && " · saving…"}
          </Typography>
        </Box>
        <Button startIcon={<AddOutlined />} variant="outlined" size="small" onClick={() => setCreateOpen(true)}>New Role</Button>
      </Box>

      <Box sx={{ display: "flex", gap: 2, height: "calc(100vh - 220px)", minHeight: 400 }}>
        {/* Left: Role list */}
        <Box sx={{ width: 220, flexShrink: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
          <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 0.5, px: 0.5 }}>
            Roles
          </Typography>
          {roles.map(role => (
            <Box
              key={role.uuid}
              onClick={() => setSelectedRoleId(role.uuid)}
              sx={{
                px: 1.5, py: 1, borderRadius: tokens.radius.md, cursor: "pointer",
                bgcolor: selectedRoleId === role.uuid ? tokens.primary.subtle : "transparent",
                border: `1px solid ${selectedRoleId === role.uuid ? tokens.primary.main : "transparent"}`,
                "&:hover": { bgcolor: selectedRoleId === role.uuid ? tokens.primary.subtle : tokens.bg.hover },
                transition: "all 120ms ease",
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                <SecurityOutlined sx={{ fontSize: 14, color: selectedRoleId === role.uuid ? tokens.primary.main : tokens.text.tertiary }} />
                <Typography variant="body2" fontWeight={selectedRoleId === role.uuid ? 700 : 500} sx={{ fontSize: "0.82rem", color: selectedRoleId === role.uuid ? tokens.primary.light : tokens.text.primary, flex: 1 }}>
                  {role.name}
                </Typography>
                <IconButton size="small" onClick={e => { e.stopPropagation(); openEditRole(role); }} sx={{ p: 0.25 }}>
                  <EditOutlined sx={{ fontSize: 12 }} />
                </IconButton>
                <IconButton size="small" onClick={e => { e.stopPropagation(); setDeleteConfirm(role); }} sx={{ p: 0.25 }}>
                  <DeleteOutlineOutlined sx={{ fontSize: 12, color: tokens.accent.red }} />
                </IconButton>
              </Box>
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", pl: 2.5, display: "block" }}>
                {(role.permissions ?? []).length} permissions
              </Typography>
            </Box>
          ))}
        </Box>

        {/* Divider */}
        <Divider orientation="vertical" flexItem />

        {/* Right: Permission tree */}
        <Box sx={{ flex: 1, overflow: "auto" }}>
          {selectedRole ? (
            <Box>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 2, pb: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
                <SecurityOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
                <Typography variant="subtitle2" fontWeight={700}>{selectedRole.name}</Typography>
                <Typography variant="caption" color="text.secondary">— {(selectedRole.permissions ?? []).length} permissions granted</Typography>
              </Box>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                {Object.entries(PERMISSION_GROUPS).map(([resource, perms]) => {
                  const grantedCount = perms.filter(p => hasPermission(p)).length;
                  const allGranted = grantedCount === perms.length;
                  return (
                    <Paper key={resource} elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
                      <Box
                        sx={{
                          px: 2, py: 1, display: "flex", alignItems: "center", gap: 1.5,
                          bgcolor: allGranted ? `${tokens.primary.main}08` : "transparent",
                        }}
                      >
                        <LockOutlined sx={{ fontSize: 14, color: allGranted ? tokens.primary.main : tokens.text.tertiary }} />
                        <Typography variant="caption" fontWeight={700} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.secondary, fontSize: "0.72rem", flex: 1 }}>
                          {resource}
                        </Typography>
                        <Typography variant="caption" sx={{ color: grantedCount > 0 ? tokens.primary.light : tokens.text.tertiary, fontSize: "0.68rem", mr: 0.5 }}>
                          {grantedCount}/{perms.length}
                        </Typography>
                      </Box>
                      <Box sx={{ px: 1, pb: 0.75, display: "flex", flexDirection: "column" }}>
                        {perms.map(p => (
                          <FormControlLabel
                            key={p}
                            control={
                              <Checkbox
                                size="small"
                                checked={hasPermission(p)}
                                onChange={() => togglePermission(p)}
                                sx={{ py: 0.5, pl: 1, color: tokens.text.tertiary, "&.Mui-checked": { color: tokens.primary.main } }}
                              />
                            }
                            label={
                              <Typography variant="caption" sx={{ fontFamily: "monospace", fontWeight: 600, fontSize: "0.75rem", color: hasPermission(p) ? tokens.primary.light : tokens.text.primary }}>
                                {p}
                              </Typography>
                            }
                          />
                        ))}
                      </Box>
                    </Paper>
                  );
                })}
              </Box>
            </Box>
          ) : (
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100%" }}>
              <Typography variant="body2" color="text.secondary">Select a role to manage its permissions</Typography>
            </Box>
          )}
        </Box>
      </Box>

      {/* Create Role dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <SecurityOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>Create Role</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <TextField label="Role name" size="small" fullWidth value={newRoleName} onChange={e => setNewRoleName(e.target.value)} autoFocus placeholder="e.g. Editor" />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button size="small" variant="contained" onClick={handleCreateRole} disabled={!newRoleName.trim()}>Create</Button>
        </DialogActions>
      </Dialog>

      {/* Edit Role dialog */}
      <Dialog open={Boolean(editRole)} onClose={() => setEditRole(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Edit Role</Typography>
          <IconButton size="small" onClick={() => setEditRole(null)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
            {editRole && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>UUID: {editRole.uuid}</Typography>
            )}
            <TextField label="Role name" size="small" fullWidth value={editName} onChange={e => setEditName(e.target.value)} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditRole(null)} size="small">Cancel</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit}>Save</Button>
        </DialogActions>
      </Dialog>

      {/* Delete Role Confirm */}
      <Dialog open={Boolean(deleteConfirm)} onClose={() => setDeleteConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Delete Role</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            Are you sure you want to delete role <strong>{deleteConfirm?.name}</strong>?
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteConfirm(null)} size="small">Cancel</Button>
          <Button variant="contained" color="error" size="small" onClick={handleDeleteRole}>Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── API Keys Table ────────────────────────────────────────────────────────
// Permissions grouped by resource (derived from Permission.java enum)
const PERMISSION_GROUPS: Record<string, string[]> = {
  Annotation: ["CREATE_ANNOTATION", "READ_ANNOTATION", "DELETE_ANNOTATION", "UPDATE_ANNOTATION"],
  Asset: ["CREATE_ASSET", "READ_ASSET", "DELETE_ASSET", "UPDATE_ASSET"],
  "Asset Location": ["CREATE_ASSET_LOCATION", "READ_ASSET_LOCATION", "DELETE_ASSET_LOCATION", "UPDATE_ASSET_LOCATION"],
  Attachment: ["CREATE_ATTACHMENT", "READ_ATTACHMENT", "DELETE_ATTACHMENT", "UPDATE_ATTACHMENT"],
  User: ["CREATE_USER", "READ_USER", "DELETE_USER", "UPDATE_USER"],
  Role: ["CREATE_ROLE", "READ_ROLE", "DELETE_ROLE", "UPDATE_ROLE"],
  Group: ["CREATE_GROUP", "READ_GROUP", "DELETE_GROUP", "UPDATE_GROUP"],
  Project: ["CREATE_PROJECT", "READ_PROJECT", "DELETE_PROJECT", "UPDATE_PROJECT"],
  Cluster: ["CREATE_CLUSTER", "READ_CLUSTER", "DELETE_CLUSTER", "UPDATE_CLUSTER"],
  Collection: ["CREATE_COLLECTION", "READ_COLLECTION", "DELETE_COLLECTION", "UPDATE_COLLECTION"],
  Comment: ["CREATE_COMMENT", "READ_COMMENT", "DELETE_COMMENT", "UPDATE_COMMENT"],
  Embedding: ["CREATE_EMBEDDING", "READ_EMBEDDING", "DELETE_EMBEDDING", "UPDATE_EMBEDDING"],
  Reaction: ["CREATE_REACTION", "READ_REACTION", "DELETE_REACTION", "UPDATE_REACTION"],
  Task: ["CREATE_TASK", "READ_TASK", "DELETE_TASK", "UPDATE_TASK"],
  Tag: ["CREATE_TAG", "READ_TAG", "DELETE_TAG", "UPDATE_TAG", "TAG_ASSET", "UNTAG_ASSET"],
  Token: ["CREATE_TOKEN", "READ_TOKEN", "DELETE_TOKEN", "UPDATE_TOKEN"],
  WebHook: ["CREATE_WEBHOOK", "READ_WEBHOOK", "DELETE_WEBHOOK", "UPDATE_WEBHOOK"],
  Library: ["CREATE_LIBRARY", "READ_LIBRARY", "DELETE_LIBRARY", "UPDATE_LIBRARY"],
  Pipeline: ["CREATE_PIPELINE", "READ_PIPELINE", "DELETE_PIPELINE", "UPDATE_PIPELINE"],
  "Asset Pool": ["CREATE_ASSET_POOL", "READ_ASSET_POOL", "DELETE_ASSET_POOL", "UPDATE_ASSET_POOL"],
};

function ApiKeysAdmin() {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [newPermissions, setNewPermissions] = useState<string[]>([]);
  const [newExpiry, setNewExpiry] = useState("");
  const [creating, setCreating] = useState(false);
  const [query, setQuery] = useState("");
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [menuAnchor, setMenuAnchor] = useState<null | HTMLElement>(null);
  const [menuKeyId, setMenuKeyId] = useState<string | null>(null);

  useEffect(() => {
    mockAdminService.getApiKeys().then(setKeys);
  }, []);

  const handleCreate = async () => {
    if (!newName.trim() || newPermissions.length === 0) return;
    setCreating(true);
    try {
      const key = await mockAdminService.createApiKey({
        name: newName.trim(),
        scopes: newPermissions,
        expiresAt: newExpiry || undefined,
      });
      setKeys(prev => [...prev, key]);
      setCreateOpen(false);
      setNewName("");
      setNewPermissions([]);
      setNewExpiry("");
    } finally {
      setCreating(false);
    }
  };

  const togglePermission = (perm: string) => {
    setNewPermissions(prev => prev.includes(perm) ? prev.filter(s => s !== perm) : [...prev, perm]);
  };

  const toggleGroup = (group: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      if (next.has(group)) next.delete(group); else next.add(group);
      return next;
    });
  };

  const handleRevoke = (keyId: string) => {
    setKeys(prev => prev.map(k => k.id === keyId ? { ...k, active: false } : k));
    setMenuAnchor(null); setMenuKeyId(null);
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>API Keys</Typography>
            <Tooltip title="API keys provide programmatic access to the platform. Assign granular permissions and set expiry dates for security." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary">{keys.length} keys</Typography>
        </Box>
        <Button startIcon={<VpnKeyOutlined />} variant="contained" size="small" onClick={() => setCreateOpen(true)}>
          Create Key
        </Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Search API keys…"
        size="small"
        sx={{ mb: 1.5, maxWidth: 320 }}
        fullWidth
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
            </InputAdornment>
          ),
        }}
      />
      <TableContainer component={Paper} elevation={0}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Key ID</TableCell>
              <TableCell>Owner</TableCell>
              <TableCell>Scopes</TableCell>
              <TableCell>Last Used</TableCell>
              <TableCell>Expires</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {keys.filter(k => {
              if (!query.trim()) return true;
              const q = query.toLowerCase();
              return k.name.toLowerCase().includes(q) || k.ownerId.toLowerCase().includes(q) || k.scopes.some(s => s.toLowerCase().includes(q));
            }).map(k => {
              const expired = k.expiresAt && new Date(k.expiresAt) < new Date();
              return (
                <TableRow key={k.id} hover>
                  <TableCell><Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{k.name}</Typography></TableCell>
                  <TableCell><Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.secondary, bgcolor: tokens.bg.overlay, px: 0.75, py: 0.25, borderRadius: tokens.radius.sm }}>{k.id.slice(0, 16)}…</Typography></TableCell>
                  <TableCell><Typography variant="caption" color="text.secondary">{k.ownerId}</Typography></TableCell>
                  <TableCell>
                    <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                      {k.scopes.slice(0, 3).map(s => <Chip key={s} label={s} size="small" sx={{ height: 16, fontSize: "0.62rem", fontFamily: "monospace" }} />)}
                      {k.scopes.length > 3 && <Chip label={`+${k.scopes.length - 3}`} size="small" sx={{ height: 16, fontSize: "0.62rem" }} />}
                    </Box>
                  </TableCell>
                  <TableCell><Typography variant="caption" color="text.secondary">{k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleDateString() : "—"}</Typography></TableCell>
                  <TableCell><Typography variant="caption" sx={{ color: expired ? tokens.accent.red : "text.secondary" }}>{k.expiresAt ? new Date(k.expiresAt).toLocaleDateString() : "Never"}</Typography></TableCell>
                  <TableCell>
                    <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                      <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: k.active && !expired ? tokens.accent.green : tokens.accent.red }} />
                      <Typography variant="caption" sx={{ fontSize: "0.7rem", color: k.active && !expired ? tokens.accent.green : tokens.accent.red }}>
                        {k.active && !expired ? "active" : "inactive"}
                      </Typography>
                    </Box>
                  </TableCell>
                  <TableCell align="right">
                    <IconButton size="small" onClick={e => { setMenuKeyId(k.id); setMenuAnchor(e.currentTarget); }}>
                      <MoreVertOutlined sx={{ fontSize: 15 }} />
                    </IconButton>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
      <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => { setMenuAnchor(null); setMenuKeyId(null); }}>
        <MenuItem onClick={() => menuKeyId && handleRevoke(menuKeyId)} sx={{ gap: 1, fontSize: "0.82rem", color: tokens.accent.red }}>
          <VpnKeyOutlined sx={{ fontSize: 16 }} /> Revoke Key
        </MenuItem>
        <MenuItem onClick={() => { if (menuKeyId) setKeys(prev => prev.filter(k => k.id !== menuKeyId)); setMenuAnchor(null); setMenuKeyId(null); }} sx={{ gap: 1, fontSize: "0.82rem", color: tokens.accent.red }}>
          <DeleteOutlineOutlined sx={{ fontSize: 16 }} /> Delete Key
        </MenuItem>
      </Menu>

      {/* Create API Key dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <VpnKeyOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>Create API Key</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField
              label="Key name"
              placeholder="e.g. CI Pipeline Key"
              value={newName}
              onChange={e => setNewName(e.target.value)}
              size="small"
              fullWidth
              autoFocus
            />
            <Box>
              <Typography variant="caption" fontWeight={600} sx={{ color: tokens.text.secondary, textTransform: "uppercase", letterSpacing: "0.06em", fontSize: "0.7rem", mb: 1, display: "block" }}>
                Permissions ({newPermissions.length})
              </Typography>
              <Box sx={{ maxHeight: 320, overflow: "auto", display: "flex", flexDirection: "column", gap: 0.5 }}>
                {Object.entries(PERMISSION_GROUPS).map(([group, perms]) => {
                  const selected = perms.filter(p => newPermissions.includes(p));
                  const allSelected = selected.length === perms.length;
                  const expanded = expandedGroups.has(group);
                  return (
                    <Paper key={group} variant="outlined" sx={{ bgcolor: tokens.bg.overlay, border: `1px solid ${tokens.border}` }}>
                      <Box sx={{ display: "flex", alignItems: "center", px: 1.5, py: 0.5, cursor: "pointer" }} onClick={() => toggleGroup(group)}>
                        <Checkbox
                          size="small"
                          checked={allSelected}
                          indeterminate={selected.length > 0 && !allSelected}
                          onChange={() => {
                            if (allSelected) setNewPermissions(prev => prev.filter(p => !perms.includes(p)));
                            else setNewPermissions(prev => [...new Set([...prev, ...perms])]);
                          }}
                          onClick={e => e.stopPropagation()}
                          sx={{ p: 0.25, mr: 1, color: tokens.text.tertiary, "&.Mui-checked": { color: tokens.primary.main } }}
                        />
                        <Typography variant="caption" fontWeight={600} sx={{ flex: 1, fontSize: "0.75rem" }}>{group}</Typography>
                        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.65rem", mr: 0.5 }}>{selected.length}/{perms.length}</Typography>
                        {expanded ? <ExpandLessOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} /> : <ExpandMoreOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
                      </Box>
                      <Collapse in={expanded}>
                        <Box sx={{ px: 1.5, pb: 1, display: "grid", gridTemplateColumns: "1fr 1fr", gap: 0 }}>
                          {perms.map(p => (
                            <FormControlLabel
                              key={p}
                              control={<Checkbox size="small" checked={newPermissions.includes(p)} onChange={() => togglePermission(p)} sx={{ py: 0.15, color: tokens.text.tertiary, "&.Mui-checked": { color: tokens.primary.main } }} />}
                              label={<Typography variant="caption" sx={{ fontFamily: "monospace", fontSize: "0.68rem" }}>{p}</Typography>}
                            />
                          ))}
                        </Box>
                      </Collapse>
                    </Paper>
                  );
                })}
              </Box>
            </Box>
            <TextField
              label="Expiry date (optional)"
              type="date"
              value={newExpiry}
              onChange={e => setNewExpiry(e.target.value)}
              size="small"
              fullWidth
              InputLabelProps={{ shrink: true }}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2, gap: 1 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button
            size="small"
            variant="contained"
            onClick={handleCreate}
            disabled={!newName.trim() || newPermissions.length === 0 || creating}
            startIcon={<VpnKeyOutlined />}
          >
            {creating ? "Creating…" : "Create Key"}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Blacklist Table ───────────────────────────────────────────────────────
function BlacklistAdmin() {
  const [entries, setEntries] = useState<BlacklistEntry[]>([]);
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [newType, setNewType] = useState<string>("ip");
  const [newValue, setNewValue] = useState("");
  const [newReason, setNewReason] = useState("");
  const [newExpiry, setNewExpiry] = useState("");

  useEffect(() => { mockAdminService.getBlacklist().then(setEntries); }, []);

  const typeColor: Record<string, string> = {
    ip: tokens.accent.red,
    domain: tokens.accent.amber,
    fingerprint: tokens.primary.main,
    user: tokens.accent.teal,
  };

  const filteredEntries = entries.filter(e => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return e.value.toLowerCase().includes(q) || e.type.toLowerCase().includes(q) || e.reason.toLowerCase().includes(q);
  });

  const handleCreate = () => {
    if (!newValue.trim()) return;
    const entry: BlacklistEntry = {
      id: `bl_${Date.now()}`,
      type: newType as BlacklistEntry["type"],
      value: newValue.trim(),
      reason: newReason.trim() || "Manual entry",
      addedBy: "",
      createdAt: new Date().toISOString(),
      expiresAt: newExpiry || undefined,
    };
    setEntries(prev => [...prev, entry]);
    setCreateOpen(false);
    setNewType("ip"); setNewValue(""); setNewReason(""); setNewExpiry("");
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Blacklist</Typography>
          <Typography variant="caption" color="text.secondary">{entries.length} entries</Typography>
        </Box>
        <Button startIcon={<BlockOutlined />} variant="contained" size="small" color="error" onClick={() => setCreateOpen(true)}>
          Add Entry
        </Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Search blacklist entries…"
        size="small"
        sx={{ mb: 1.5, maxWidth: 320 }}
        fullWidth
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
            </InputAdornment>
          ),
        }}
      />
      <TableContainer component={Paper} elevation={0}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell>
              <TableCell>Value</TableCell>
              <TableCell>Reason</TableCell>
              <TableCell>Added</TableCell>
              <TableCell>Expires</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {filteredEntries.map(e => (
              <TableRow key={e.id} hover>
                <TableCell>
                  <Chip label={e.type} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${typeColor[e.type] ?? tokens.text.tertiary}22`, color: typeColor[e.type] ?? tokens.text.tertiary }} />
                </TableCell>
                <TableCell><Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.primary, fontSize: "0.78rem" }}>{e.value}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{e.reason}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{new Date(e.createdAt).toLocaleDateString()}</Typography></TableCell>
                <TableCell><Typography variant="caption" sx={{ color: e.expiresAt ? tokens.accent.amber : tokens.text.tertiary }}>{e.expiresAt ? new Date(e.expiresAt).toLocaleDateString() : "Permanent"}</Typography></TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={() => setEntries(prev => prev.filter(x => x.id !== e.id))}>
                    <DeleteOutlineOutlined sx={{ fontSize: 15, color: tokens.accent.red }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Create blacklist entry dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <BlockOutlined sx={{ fontSize: 18, color: tokens.accent.red }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>Add Blacklist Entry</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <FormControl size="small" fullWidth>
              <InputLabel>Type</InputLabel>
              <Select label="Type" value={newType} onChange={e => setNewType(e.target.value)}>
                <MenuItem value="ip">IP Address</MenuItem>
                <MenuItem value="domain">Domain</MenuItem>
                <MenuItem value="fingerprint">Fingerprint</MenuItem>
                <MenuItem value="user">User</MenuItem>
              </Select>
            </FormControl>
            <TextField label="Value" size="small" fullWidth value={newValue} onChange={e => setNewValue(e.target.value)}
              placeholder={newType === "ip" ? "192.168.1.100" : newType === "domain" ? "example.com" : newType === "user" ? "username" : "hash…"} autoFocus />
            <TextField label="Reason" size="small" fullWidth value={newReason} onChange={e => setNewReason(e.target.value)} placeholder="Reason for blacklisting" />
            <TextField label="Expiry date (optional)" type="date" size="small" fullWidth value={newExpiry} onChange={e => setNewExpiry(e.target.value)} InputLabelProps={{ shrink: true }} />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button size="small" variant="contained" color="error" onClick={handleCreate} disabled={!newValue.trim()} startIcon={<BlockOutlined />}>
            Add Entry
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Permissions view ──────────────────────────────────────────────────────
// ── Admin Area Shell ──────────────────────────────────────────────────────
const ADMIN_TABS = [
  { label: "Spaces", path: "/admin/spaces" },
  { label: "Users", path: "/admin/users" },
  { label: "Groups", path: "/admin/groups" },
  { label: "Permissions", path: "/admin/permissions" },
  { label: "API Keys", path: "/admin/api-keys" },
  { label: "Blacklist", path: "/admin/blacklist" },
];

export default function AdminArea() {
  const navigate = useNavigate();
  const location = useLocation();

  const tabIdx = ADMIN_TABS.findIndex(t => location.pathname === t.path);
  const activeTab = tabIdx === -1 ? 0 : tabIdx;

  useEffect(() => {
    if (location.pathname === "/admin" || location.pathname === "/admin/") {
      navigate("/admin/spaces", { replace: true });
    }
  }, [location.pathname, navigate]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", mb: 0.25 }}>Admin</Typography>
        <Typography variant="caption" color="text.secondary">User management, access control, and security</Typography>
      </Box>

      <Box sx={{ borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, px: 2 }}>
        <Tabs value={activeTab} onChange={(_, i) => navigate(ADMIN_TABS[i].path)}>
          {ADMIN_TABS.map(t => <Tab key={t.path} label={t.label} sx={{ fontSize: "0.8rem" }} />)}
        </Tabs>
      </Box>

      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        <Routes>
          <Route path="spaces" element={<SpacesAdmin />} />
          <Route path="users" element={<UsersAdmin />} />
          <Route path="groups" element={<GroupsAdmin />} />
          <Route path="permissions" element={<AccessControlAdmin />} />
          <Route path="api-keys" element={<ApiKeysAdmin />} />
          <Route path="blacklist" element={<BlacklistAdmin />} />
        </Routes>
      </Box>
    </Box>
  );
}
