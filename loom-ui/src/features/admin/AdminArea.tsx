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
} from "@mui/icons-material";
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
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Users</Typography>
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

  useEffect(() => {
    Promise.all([mockAdminService.getGroups(), mockAdminService.getUsers(), mockAdminService.getRoles()]).then(([g, u, r]) => {
      setGroups(g); setUsers(u); setRoles(r);
    });
  }, []);

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Groups</Typography>
          <Typography variant="caption" color="text.secondary">{groups.length} groups</Typography>
        </Box>
        <Button startIcon={<AddOutlined />} variant="contained" size="small">New Group</Button>
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
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
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
const AVAILABLE_SCOPES = [
  "asset:read", "asset:write", "collection:read", "collection:write",
  "pipeline:read", "pipeline:execute", "library:read", "library:write",
  "task:read", "task:write", "admin:read",
];

function ApiKeysAdmin() {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [newScopes, setNewScopes] = useState<string[]>([]);
  const [newExpiry, setNewExpiry] = useState("");
  const [creating, setCreating] = useState(false);
  const [query, setQuery] = useState("");

  useEffect(() => {
    Promise.all([mockAdminService.getApiKeys(), mockAdminService.getUsers()]).then(([k, u]) => {
      setKeys(k); setUsers(u);
    });
  }, []);

  const handleCreate = async () => {
    if (!newName.trim() || newScopes.length === 0) return;
    setCreating(true);
    try {
      const key = await mockAdminService.createApiKey({
        name: newName.trim(),
        scopes: newScopes,
        expiresAt: newExpiry || undefined,
      });
      setKeys(prev => [...prev, key]);
      setCreateOpen(false);
      setNewName("");
      setNewScopes([]);
      setNewExpiry("");
    } finally {
      setCreating(false);
    }
  };

  const toggleScope = (scope: string) => {
    setNewScopes(prev => prev.includes(scope) ? prev.filter(s => s !== scope) : [...prev, scope]);
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>API Keys</Typography>
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
                      {k.scopes.map(s => <Chip key={s} label={s} size="small" sx={{ height: 16, fontSize: "0.62rem", fontFamily: "monospace" }} />)}
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
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>

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
                Scopes
              </Typography>
              <Box sx={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 0.25 }}>
                {AVAILABLE_SCOPES.map(scope => (
                  <FormControlLabel
                    key={scope}
                    control={
                      <Checkbox
                        size="small"
                        checked={newScopes.includes(scope)}
                        onChange={() => toggleScope(scope)}
                        sx={{ py: 0.25, color: tokens.text.tertiary, "&.Mui-checked": { color: tokens.primary.main } }}
                      />
                    }
                    label={<Typography variant="caption" sx={{ fontFamily: "monospace", fontSize: "0.75rem" }}>{scope}</Typography>}
                  />
                ))}
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
            disabled={!newName.trim() || newScopes.length === 0 || creating}
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

  useEffect(() => { mockAdminService.getBlacklist().then(setEntries); }, []);

  const typeColor: Record<string, string> = {
    ip: tokens.accent.red,
    domain: tokens.accent.amber,
    fingerprint: tokens.primary.main,
    user: tokens.accent.teal,
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Blacklist</Typography>
          <Typography variant="caption" color="text.secondary">{entries.length} entries</Typography>
        </Box>
        <Button startIcon={<BlockOutlined />} variant="contained" size="small" color="error">
          Add Entry
        </Button>
      </Box>
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
            {entries.map(e => (
              <TableRow key={e.id} hover>
                <TableCell>
                  <Chip label={e.type} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${typeColor[e.type] ?? tokens.text.tertiary}22`, color: typeColor[e.type] ?? tokens.text.tertiary }} />
                </TableCell>
                <TableCell><Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.primary, fontSize: "0.78rem" }}>{e.value}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{e.reason}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{new Date(e.createdAt).toLocaleDateString()}</Typography></TableCell>
                <TableCell><Typography variant="caption" sx={{ color: e.expiresAt ? tokens.accent.amber : tokens.text.tertiary }}>{e.expiresAt ? new Date(e.expiresAt).toLocaleDateString() : "Permanent"}</Typography></TableCell>
                <TableCell align="right">
                  <IconButton size="small">
                    <DeleteOutlineOutlined sx={{ fontSize: 15, color: tokens.accent.red }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
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
