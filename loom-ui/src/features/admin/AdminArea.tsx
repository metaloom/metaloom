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
import { User, Group, Role, Permission, ApiKey, BlacklistEntry } from "../../types";
import { mockAdminService } from "../../mock/services";

// ── Users Table ───────────────────────────────────────────────────────────
function UsersAdmin() {
  const [users, setUsers] = useState<User[]>([]);
  const [editUser, setEditUser] = useState<User | null>(null);
  const [editForm, setEditForm] = useState({ name: "", email: "", role: "" as string });
  const [query, setQuery] = useState("");

  useEffect(() => { mockAdminService.getUsers().then(setUsers); }, []);

  const roleColor: Record<string, string> = {
    admin: tokens.accent.red,
    editor: tokens.primary.main,
    viewer: tokens.accent.blue,
    operator: tokens.accent.teal,
  };

  const handleToggleActive = (e: React.MouseEvent, userId: string) => {
    e.stopPropagation();
    setUsers(prev => prev.map(u => u.id === userId ? { ...u, active: !u.active } : u));
  };

  const openEdit = (user: User) => {
    setEditUser(user);
    setEditForm({ name: user.name, email: user.email, role: user.role });
  };

  const handleSaveEdit = () => {
    if (!editUser) return;
    setUsers(prev => prev.map(u => u.id === editUser.id ? { ...u, name: editForm.name, email: editForm.email, role: editForm.role as User["role"] } : u));
    setEditUser(null);
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Users</Typography>
            <Tooltip title="Manage user accounts, roles, and access levels. Invite new users or deactivate existing ones." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary">{users.length} accounts</Typography>
        </Box>
        <Button startIcon={<PersonAddOutlined />} variant="contained" size="small">
          Invite User
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
              <TableCell>Role</TableCell>
              <TableCell>Groups</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Last Active</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {users.filter(u => {
              if (!query.trim()) return true;
              const q = query.toLowerCase();
              return u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q) || u.role.toLowerCase().includes(q);
            }).map(u => (
              <TableRow key={u.id} hover sx={{ cursor: "pointer" }} onClick={() => openEdit(u)}>
                <TableCell>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
                    <Avatar sx={{ width: 28, height: 28, fontSize: "0.7rem", bgcolor: u.active ? tokens.primary.dark : tokens.text.tertiary, opacity: u.active ? 1 : 0.5 }}>
                      {u.name.split(" ").map(n => n[0]).join("")}
                    </Avatar>
                    <Box>
                      <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem", opacity: u.active ? 1 : 0.5 }}>{u.name}</Typography>
                      <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>{u.email}</Typography>
                    </Box>
                  </Box>
                </TableCell>
                <TableCell>
                  <Chip label={u.role} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${roleColor[u.role] ?? tokens.text.tertiary}22`, color: roleColor[u.role] ?? tokens.text.tertiary }} />
                </TableCell>
                <TableCell>
                  <Typography variant="caption" color="text.secondary">{u.groupIds.length} groups</Typography>
                </TableCell>
                <TableCell>
                  <Tooltip title={u.active ? "Disable user" : "Enable user"}>
                    <Switch
                      size="small"
                      checked={u.active}
                      onClick={(e) => handleToggleActive(e, u.id)}
                      sx={{ "& .MuiSwitch-switchBase.Mui-checked": { color: tokens.accent.green }, "& .MuiSwitch-switchBase.Mui-checked + .MuiSwitch-track": { bgcolor: tokens.accent.green } }}
                    />
                  </Tooltip>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
                    {new Date(u.lastSeenAt).toLocaleDateString()}
                  </Typography>
                </TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={e => { e.stopPropagation(); openEdit(u); }}>
                    <EditOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Edit User Dialog */}
      <Dialog open={Boolean(editUser)} onClose={() => setEditUser(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Edit User</Typography>
          <IconButton size="small" onClick={() => setEditUser(null)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          {editUser && (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
              {/* Avatar + identity */}
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <Avatar sx={{ width: 56, height: 56, bgcolor: tokens.primary.dark, fontSize: "1.15rem" }}>
                  {editUser.name.split(" ").map(n => n[0]).join("")}
                </Avatar>
                <Box sx={{ flex: 1 }}>
                  <Typography variant="body2" sx={{ color: tokens.text.tertiary, fontSize: "0.75rem" }}>@{editUser.username}</Typography>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                    Member since {new Date(editUser.createdAt).toLocaleDateString()} · Last seen {new Date(editUser.lastSeenAt).toLocaleString()}
                  </Typography>
                </Box>
              </Box>

              <TextField label="Full Name" size="small" fullWidth value={editForm.name} onChange={e => setEditForm(f => ({ ...f, name: e.target.value }))} />
              <TextField label="Email" size="small" fullWidth type="email" value={editForm.email} onChange={e => setEditForm(f => ({ ...f, email: e.target.value }))} />
              <FormControl size="small" fullWidth>
                <InputLabel>Role</InputLabel>
                <Select label="Role" value={editForm.role} onChange={e => setEditForm(f => ({ ...f, role: e.target.value }))}>
                  <MenuItem value="admin">Admin</MenuItem>
                  <MenuItem value="editor">Editor</MenuItem>
                  <MenuItem value="viewer">Viewer</MenuItem>
                  <MenuItem value="operator">Operator</MenuItem>
                </Select>
              </FormControl>

              <Divider />
              <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <Typography variant="body2" color="text.secondary">Account enabled</Typography>
                <Switch
                  checked={editUser.active}
                  onChange={() => {
                    const toggled = { ...editUser, active: !editUser.active };
                    setEditUser(toggled);
                    setUsers(prev => prev.map(u => u.id === toggled.id ? toggled : u));
                  }}
                  sx={{ "& .MuiSwitch-switchBase.Mui-checked": { color: tokens.accent.green }, "& .MuiSwitch-switchBase.Mui-checked + .MuiSwitch-track": { bgcolor: tokens.accent.green } }}
                />
              </Box>
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditUser(null)} size="small">Cancel</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit} sx={{ textTransform: "none", fontWeight: 600 }}>Save</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Groups Table ──────────────────────────────────────────────────────────
function GroupsAdmin() {
  const [groups, setGroups] = useState<Group[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [manageGroup, setManageGroup] = useState<Group | null>(null);

  useEffect(() => {
    Promise.all([mockAdminService.getGroups(), mockAdminService.getUsers(), mockAdminService.getRoles()]).then(([g, u, r]) => {
      setGroups(g); setUsers(u); setRoles(r);
    });
  }, []);

  const handleCreateGroup = () => {
    if (!newName.trim()) return;
    const g: Group = { id: `grp_${Date.now()}`, name: newName.trim(), description: newDesc.trim(), memberIds: [], roleIds: [], createdAt: new Date().toISOString() };
    setGroups(prev => [...prev, g]);
    setCreateOpen(false); setNewName(""); setNewDesc("");
  };

  const toggleMember = (groupId: string, userId: string) => {
    setGroups(prev => prev.map(g => {
      if (g.id !== groupId) return g;
      const has = g.memberIds.includes(userId);
      return { ...g, memberIds: has ? g.memberIds.filter(id => id !== userId) : [...g.memberIds, userId] };
    }));
    if (manageGroup) {
      setManageGroup(prev => {
        if (!prev) return prev;
        const has = prev.memberIds.includes(userId);
        return { ...prev, memberIds: has ? prev.memberIds.filter(id => id !== userId) : [...prev.memberIds, userId] };
      });
    }
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Groups</Typography>
            <Tooltip title="Groups let you organise users and assign shared permissions. Members inherit the group's access rights." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
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
              <TableCell>Description</TableCell>
              <TableCell>Members</TableCell>
              <TableCell>Roles</TableCell>
              <TableCell>Created</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {groups.filter(g => {
              if (!query.trim()) return true;
              const q = query.toLowerCase();
              return g.name.toLowerCase().includes(q) || g.description.toLowerCase().includes(q);
            }).map(g => (
              <TableRow key={g.id} hover>
                <TableCell><Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{g.name}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{g.description}</Typography></TableCell>
                <TableCell>
                  <Box sx={{ display: "flex", gap: -0.5 }}>
                    {g.memberIds.slice(0, 3).map(uid => {
                      const u = users.find(x => x.id === uid);
                      return (
                        <Tooltip key={uid} title={u?.name ?? uid}>
                          <Avatar sx={{ width: 22, height: 22, fontSize: "0.58rem", bgcolor: tokens.primary.dark, border: `2px solid ${tokens.bg.surface}`, marginLeft: -0.5 }}>
                            {(u?.name ?? uid).split(" ").map(n => n[0]).join("")}
                          </Avatar>
                        </Tooltip>
                      );
                    })}
                    {g.memberIds.length > 3 && (
                      <Chip label={`+${g.memberIds.length - 3}`} size="small" sx={{ height: 18, fontSize: "0.6rem", ml: 0.5 }} />
                    )}
                  </Box>
                </TableCell>
                <TableCell>
                  <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                    {g.roleIds.map(rid => {
                      const r = roles.find(x => x.id === rid);
                      return <Chip key={rid} label={r?.name ?? rid} size="small" sx={{ height: 16, fontSize: "0.62rem" }} />;
                    })}
                  </Box>
                </TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{new Date(g.createdAt).toLocaleDateString()}</Typography></TableCell>
                <TableCell align="right">
                  <Tooltip title="Manage members">
                    <IconButton size="small" onClick={() => setManageGroup(g)}>
                      <GroupsOutlined sx={{ fontSize: 15 }} />
                    </IconButton>
                  </Tooltip>
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
            <TextField label="Description" size="small" fullWidth value={newDesc} onChange={e => setNewDesc(e.target.value)} multiline rows={2} placeholder="What is this group for?" />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button size="small" variant="contained" onClick={handleCreateGroup} disabled={!newName.trim()}>Create Group</Button>
        </DialogActions>
      </Dialog>

      {/* Manage members dialog */}
      <Dialog open={Boolean(manageGroup)} onClose={() => setManageGroup(null)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <GroupsOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>Manage Members — {manageGroup?.name}</Typography>
          <IconButton size="small" onClick={() => setManageGroup(null)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent>
          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 1.5 }}>
            Toggle users to assign or unassign them from this group.
          </Typography>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, maxHeight: 360, overflow: "auto" }}>
            {users.map(u => {
              const isMember = manageGroup?.memberIds.includes(u.id) ?? false;
              return (
                <Box key={u.id}
                  onClick={() => manageGroup && toggleMember(manageGroup.id, u.id)}
                  sx={{
                    display: "flex", alignItems: "center", gap: 1.5, px: 1.5, py: 1,
                    borderRadius: tokens.radius.md, cursor: "pointer",
                    bgcolor: isMember ? `${tokens.primary.main}12` : "transparent",
                    border: `1px solid ${isMember ? tokens.primary.main : "transparent"}`,
                    "&:hover": { bgcolor: isMember ? `${tokens.primary.main}18` : tokens.bg.hover },
                  }}
                >
                  <Checkbox size="small" checked={isMember} sx={{ p: 0, color: tokens.text.tertiary, "&.Mui-checked": { color: tokens.primary.main } }} />
                  <Avatar sx={{ width: 24, height: 24, fontSize: "0.65rem", bgcolor: tokens.primary.dark }}>
                    {u.name.split(" ").map(n => n[0]).join("")}
                  </Avatar>
                  <Box sx={{ flex: 1 }}>
                    <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{u.name}</Typography>
                    <Typography variant="caption" color="text.tertiary" sx={{ fontSize: "0.7rem" }}>{u.email}</Typography>
                  </Box>
                  <Chip label={u.role} size="small" sx={{ height: 16, fontSize: "0.6rem" }} />
                </Box>
              );
            })}
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" variant="contained" onClick={() => setManageGroup(null)}>Done</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Access Control (Unified RBAC + Permissions) ───────────────────────────
function AccessControlAdmin() {
  const [roles, setRoles] = useState<Role[]>([]);
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [expandedResources, setExpandedResources] = useState<Set<string>>(new Set());
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    Promise.all([mockAdminService.getRoles(), mockAdminService.getPermissions()]).then(([r, p]) => {
      setRoles(r);
      setPermissions(p);
      if (r.length > 0) setSelectedRoleId(r[0].id);
      setExpandedResources(new Set([...new Set(p.map(x => x.resource))]));
    });
  }, []);

  const selectedRole = roles.find(r => r.id === selectedRoleId) ?? null;
  const resources = [...new Set(permissions.map(p => p.resource))].sort();

  const hasPermission = (pid: string) => selectedRole?.permissionIds.includes(pid) ?? false;

  const togglePermission = useCallback(async (pid: string) => {
    if (!selectedRole) return;
    const next = hasPermission(pid)
      ? selectedRole.permissionIds.filter(id => id !== pid)
      : [...selectedRole.permissionIds, pid];
    setRoles(prev => prev.map(r => r.id === selectedRole.id ? { ...r, permissionIds: next } : r));
    setSaving(true);
    try {
      await mockAdminService.updateRolePermissions(selectedRole.id, next);
    } finally {
      setSaving(false);
    }
  }, [selectedRole]);

  const toggleResource = (resource: string) => {
    setExpandedResources(prev => {
      const next = new Set(prev);
      if (next.has(resource)) next.delete(resource); else next.add(resource);
      return next;
    });
  };

  const resourcePermissionCount = (resource: string) =>
    permissions.filter(p => p.resource === resource && hasPermission(p.id)).length;

  const totalForResource = (resource: string) =>
    permissions.filter(p => p.resource === resource).length;

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Access Control</Typography>
          <Typography variant="caption" color="text.secondary">
            {roles.length} roles · {permissions.length} permissions
            {saving && " · saving…"}
          </Typography>
        </Box>
        <Button startIcon={<AddOutlined />} variant="outlined" size="small">New Role</Button>
      </Box>

      <Box sx={{ display: "flex", gap: 2, height: "calc(100vh - 220px)", minHeight: 400 }}>
        {/* Left: Role list */}
        <Box sx={{ width: 200, flexShrink: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
          <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 0.5, px: 0.5 }}>
            Roles
          </Typography>
          {roles.map(role => (
            <Box
              key={role.id}
              onClick={() => setSelectedRoleId(role.id)}
              sx={{
                px: 1.5, py: 1, borderRadius: tokens.radius.md, cursor: "pointer",
                bgcolor: selectedRoleId === role.id ? tokens.primary.subtle : "transparent",
                border: `1px solid ${selectedRoleId === role.id ? tokens.primary.main : "transparent"}`,
                "&:hover": { bgcolor: selectedRoleId === role.id ? tokens.primary.subtle : tokens.bg.hover },
                transition: "all 120ms ease",
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                <SecurityOutlined sx={{ fontSize: 14, color: selectedRoleId === role.id ? tokens.primary.main : tokens.text.tertiary }} />
                <Typography variant="body2" fontWeight={selectedRoleId === role.id ? 700 : 500} sx={{ fontSize: "0.82rem", color: selectedRoleId === role.id ? tokens.primary.light : tokens.text.primary }}>
                  {role.name}
                </Typography>
              </Box>
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", pl: 2.5, display: "block" }}>
                {role.permissionIds.length} permissions
              </Typography>
              {role.isSystem && (
                <Chip label="system" size="small" sx={{ height: 14, fontSize: "0.6rem", bgcolor: tokens.bg.overlay, color: tokens.text.tertiary, ml: 2.5, mt: 0.25 }} />
              )}
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
                <Typography variant="caption" color="text.secondary">— {selectedRole.description}</Typography>
              </Box>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                {resources.map(resource => {
                  const expanded = expandedResources.has(resource);
                  const perms = permissions.filter(p => p.resource === resource);
                  const grantedCount = resourcePermissionCount(resource);
                  const allGranted = grantedCount === totalForResource(resource);
                  return (
                    <Paper key={resource} elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
                      {/* Resource header */}
                      <Box
                        onClick={() => toggleResource(resource)}
                        sx={{
                          px: 2, py: 1, display: "flex", alignItems: "center", gap: 1.5, cursor: "pointer",
                          "&:hover": { bgcolor: tokens.bg.hover },
                          bgcolor: allGranted ? `${tokens.primary.main}08` : "transparent",
                        }}
                      >
                        <LockOutlined sx={{ fontSize: 14, color: allGranted ? tokens.primary.main : tokens.text.tertiary }} />
                        <Typography variant="caption" fontWeight={700} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.secondary, fontSize: "0.72rem", flex: 1 }}>
                          {resource}
                        </Typography>
                        <Typography variant="caption" sx={{ color: grantedCount > 0 ? tokens.primary.light : tokens.text.tertiary, fontSize: "0.68rem", mr: 0.5 }}>
                          {grantedCount}/{totalForResource(resource)}
                        </Typography>
                        {expanded ? <ExpandLessOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} /> : <ExpandMoreOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
                      </Box>
                      {/* Permissions */}
                      <Collapse in={expanded}>
                        <Box sx={{ px: 1, pb: 0.75, display: "flex", flexDirection: "column" }}>
                          {perms.map(p => (
                            <FormControlLabel
                              key={p.id}
                              control={
                                <Checkbox
                                  size="small"
                                  checked={hasPermission(p.id)}
                                  onChange={() => togglePermission(p.id)}
                                  sx={{ py: 0.5, pl: 1, color: tokens.text.tertiary, "&.Mui-checked": { color: tokens.primary.main } }}
                                />
                              }
                              label={
                                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                                  <Typography variant="caption" sx={{ fontFamily: "monospace", fontWeight: 600, fontSize: "0.75rem", color: hasPermission(p.id) ? tokens.primary.light : tokens.text.primary }}>
                                    {p.action}
                                  </Typography>
                                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                                    — {p.description}
                                  </Typography>
                                </Box>
                              }
                            />
                          ))}
                        </Box>
                      </Collapse>
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
  const [users, setUsers] = useState<User[]>([]);
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
    Promise.all([mockAdminService.getApiKeys(), mockAdminService.getUsers()]).then(([k, u]) => {
      setKeys(k); setUsers(u);
    });
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
              const owner = users.find(u => u.id === k.ownerId);
              return k.name.toLowerCase().includes(q) || (owner?.name.toLowerCase().includes(q) ?? false) || k.scopes.some(s => s.toLowerCase().includes(q));
            }).map(k => {
              const owner = users.find(u => u.id === k.ownerId);
              const expired = k.expiresAt && new Date(k.expiresAt) < new Date();
              return (
                <TableRow key={k.id} hover>
                  <TableCell><Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{k.name}</Typography></TableCell>
                  <TableCell><Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.secondary, bgcolor: tokens.bg.overlay, px: 0.75, py: 0.25, borderRadius: tokens.radius.sm }}>{k.id.slice(0, 16)}…</Typography></TableCell>
                  <TableCell><Typography variant="caption" color="text.secondary">{owner?.name ?? k.ownerId}</Typography></TableCell>
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
      navigate("/admin/users", { replace: true });
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
