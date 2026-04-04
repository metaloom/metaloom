import React, { useEffect, useState } from "react";
import { Routes, Route, useNavigate, useLocation } from "react-router-dom";
import {
  Box, Typography, Tab, Tabs, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Paper, Chip, Avatar, IconButton, Tooltip, Drawer,
  TextField, Button, Select, MenuItem, FormControl, InputLabel, Stack,
  Divider, Switch,
} from "@mui/material";
import {
  PersonAddOutlined, VpnKeyOutlined, BlockOutlined, GroupsOutlined,
  SecurityOutlined, EditOutlined, DeleteOutlineOutlined, AddOutlined,
  CloseOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { User, Group, Role, Permission, ApiKey, BlacklistEntry } from "../../types";
import { mockAdminService } from "../../mock/services";

// ── Users Table ───────────────────────────────────────────────────────────
function UsersAdmin() {
  const [users, setUsers] = useState<User[]>([]);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selected, setSelected] = useState<User | null>(null);

  useEffect(() => { mockAdminService.getUsers().then(setUsers); }, []);

  const roleColor: Record<string, string> = {
    admin: tokens.accent.red,
    editor: tokens.primary.main,
    viewer: tokens.accent.blue,
    operator: tokens.accent.teal,
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
      <TableContainer component={Paper} elevation={0}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>User</TableCell>
              <TableCell>Role</TableCell>
              <TableCell>Groups</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Last Active</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {users.map(u => (
              <TableRow key={u.id} hover sx={{ cursor: "pointer" }} onClick={() => { setSelected(u); setDrawerOpen(true); }}>
                <TableCell>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
                    <Avatar sx={{ width: 28, height: 28, fontSize: "0.7rem", bgcolor: tokens.primary.dark }}>
                      {u.name.split(" ").map(n => n[0]).join("")}
                    </Avatar>
                    <Box>
                      <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{u.name}</Typography>
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
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                    <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: u.active ? tokens.accent.green : tokens.text.tertiary }} />
                    <Typography variant="caption" sx={{ color: u.active ? tokens.accent.green : tokens.text.tertiary, fontSize: "0.72rem" }}>
                      {u.active ? "active" : "inactive"}
                    </Typography>
                  </Box>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
                    {new Date(u.lastSeenAt).toLocaleDateString()}
                  </Typography>
                </TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={e => { e.stopPropagation(); }}>
                    <EditOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Drawer anchor="right" open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        <Box sx={{ width: 340, p: 2.5, bgcolor: tokens.bg.surface, height: "100%" }}>
          <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 2 }}>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>User Detail</Typography>
            <IconButton size="small" onClick={() => setDrawerOpen(false)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
          </Box>
          {selected && (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
                <Avatar sx={{ width: 44, height: 44, bgcolor: tokens.primary.dark }}>{selected.name.split(" ").map(n => n[0]).join("")}</Avatar>
                <Box>
                  <Typography variant="subtitle1" fontWeight={700}>{selected.name}</Typography>
                  <Typography variant="caption" color="text.secondary">{selected.email}</Typography>
                </Box>
              </Box>
              <Divider />
              {[
                ["Username", `@${selected.username}`],
                ["Role", selected.role],
                ["Member since", new Date(selected.createdAt).toLocaleDateString()],
                ["Last seen", new Date(selected.lastSeenAt).toLocaleString()],
              ].map(([k, v]) => (
                <Box key={k} sx={{ display: "flex", justifyContent: "space-between" }}>
                  <Typography variant="body2" color="text.secondary" sx={{ fontSize: "0.82rem" }}>{k}</Typography>
                  <Typography variant="body2" fontWeight={500} sx={{ fontSize: "0.82rem" }}>{v}</Typography>
                </Box>
              ))}
              <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mt: 1 }}>
                <Typography variant="body2" color="text.secondary" sx={{ fontSize: "0.82rem" }}>Active</Typography>
                <Switch size="small" checked={selected.active} />
              </Box>
            </Box>
          )}
        </Box>
      </Drawer>
    </Box>
  );
}

// ── Groups Table ──────────────────────────────────────────────────────────
function GroupsAdmin() {
  const [groups, setGroups] = useState<Group[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);

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
            {groups.map(g => (
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

// ── RBAC Table ────────────────────────────────────────────────────────────
function RBACAdmin() {
  const [roles, setRoles] = useState<Role[]>([]);
  const [permissions, setPermissions] = useState<Permission[]>([]);

  useEffect(() => {
    Promise.all([mockAdminService.getRoles(), mockAdminService.getPermissions()]).then(([r, p]) => {
      setRoles(r); setPermissions(p);
    });
  }, []);

  return (
    <Box>
      <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", mb: 0.5 }}>Roles & Permissions</Typography>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 2 }}>
        {roles.length} roles · {permissions.length} permissions
      </Typography>

      <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {roles.map(role => (
          <Paper key={role.id} elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.lg, overflow: "hidden" }}>
            <Box sx={{ px: 2, py: 1.5, display: "flex", alignItems: "center", gap: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
              <SecurityOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
              <Typography variant="subtitle2" fontWeight={700}>{role.name}</Typography>
              {role.isSystem && <Chip label="system" size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: tokens.bg.overlay, color: tokens.text.tertiary }} />}
              <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>{role.description}</Typography>
            </Box>
            <Box sx={{ px: 2, py: 1.25, display: "flex", gap: 0.75, flexWrap: "wrap" }}>
              {role.permissionIds.map(pid => {
                const p = permissions.find(x => x.id === pid);
                return (
                  <Chip
                    key={pid}
                    label={p ? `${p.resource}:${p.action}` : pid}
                    size="small"
                    sx={{ height: 20, fontSize: "0.68rem", bgcolor: tokens.bg.overlay, fontFamily: "monospace", color: tokens.text.secondary }}
                  />
                );
              })}
            </Box>
          </Paper>
        ))}
      </Box>
    </Box>
  );
}

// ── API Keys Table ────────────────────────────────────────────────────────
function ApiKeysAdmin() {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [users, setUsers] = useState<User[]>([]);

  useEffect(() => {
    Promise.all([mockAdminService.getApiKeys(), mockAdminService.getUsers()]).then(([k, u]) => {
      setKeys(k); setUsers(u);
    });
  }, []);

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>API Keys</Typography>
          <Typography variant="caption" color="text.secondary">{keys.length} keys</Typography>
        </Box>
        <Button startIcon={<VpnKeyOutlined />} variant="contained" size="small">Create Key</Button>
      </Box>
      <TableContainer component={Paper} elevation={0}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Prefix</TableCell>
              <TableCell>Owner</TableCell>
              <TableCell>Scopes</TableCell>
              <TableCell>Last Used</TableCell>
              <TableCell>Expires</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {keys.map(k => {
              const owner = users.find(u => u.id === k.ownerId);
              const expired = k.expiresAt && new Date(k.expiresAt) < new Date();
              return (
                <TableRow key={k.id} hover>
                  <TableCell><Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{k.name}</Typography></TableCell>
                  <TableCell><Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.secondary, bgcolor: tokens.bg.overlay, px: 0.75, py: 0.25, borderRadius: tokens.radius.sm }}>{k.prefix}…</Typography></TableCell>
                  <TableCell><Typography variant="caption" color="text.secondary">{owner?.name ?? k.ownerId}</Typography></TableCell>
                  <TableCell>
                    <Box sx={{ display: "flex", gap: 0.5 }}>
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
function PermissionsAdmin() {
  const [permissions, setPermissions] = useState<Permission[]>([]);

  useEffect(() => { mockAdminService.getPermissions().then(setPermissions); }, []);

  const resources = [...new Set(permissions.map(p => p.resource))];

  return (
    <Box>
      <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", mb: 2 }}>Permission Matrix</Typography>
      <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
        {resources.map(resource => (
          <Paper key={resource} elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, p: 1.5 }}>
            <Typography variant="caption" fontWeight={700} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.secondary, fontSize: "0.7rem", mb: 1, display: "block" }}>
              {resource}
            </Typography>
            <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap" }}>
              {permissions.filter(p => p.resource === resource).map(p => (
                <Chip
                  key={p.id}
                  label={`${p.action} — ${p.description}`}
                  size="small"
                  sx={{ height: 22, fontSize: "0.7rem", bgcolor: tokens.bg.overlay }}
                />
              ))}
            </Box>
          </Paper>
        ))}
      </Box>
    </Box>
  );
}

// ── Admin Area Shell ──────────────────────────────────────────────────────
const ADMIN_TABS = [
  { label: "Users", path: "/admin/users" },
  { label: "Groups", path: "/admin/groups" },
  { label: "RBAC", path: "/admin/rbac" },
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
          <Route path="rbac" element={<RBACAdmin />} />
          <Route path="permissions" element={<PermissionsAdmin />} />
          <Route path="api-keys" element={<ApiKeysAdmin />} />
          <Route path="blacklist" element={<BlacklistAdmin />} />
        </Routes>
      </Box>
    </Box>
  );
}
