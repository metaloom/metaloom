import React, { useEffect, useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
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
import { listBlacklists, createBlacklist, deleteBlacklist, BlacklistResponse } from "../../api/blacklist";
import {
  listMemoryDenyRules, createMemoryDenyRule, updateMemoryDenyRule, deleteMemoryDenyRule,
  MemoryDenyRuleResponse,
} from "../../api/memoryDenylist";
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
import {
  listTokens, createToken, deleteToken as deleteTokenApi, updateToken,
  TokenResponse, TokenUpdateRequest,
} from "../../api/tokens";
import type { PagingParams } from "../../api/paging";
import ListPaging from "../../components/ListPaging";
import { PAGE_SIZE } from "../../hooks/pagedList";
import { pageFrom, usePagedList } from "../../hooks/usePagedList";

// ── Spaces Table ──────────────────────────────────────────────────────────
function SpacesAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [editSpace, setEditSpace] = useState<SpaceResponse | null>(null);
  const [editName, setEditName] = useState("");
  const [deleteConfirm, setDeleteConfirm] = useState<SpaceResponse | null>(null);

  const loadPage = useMemo(
    () => (token ? (paging: PagingParams) => listSpaces(token, paging).then(r => pageFrom(r, s => s)) : null),
    [token],
  );
  const page = usePagedList<SpaceResponse>(loadPage, s => s.uuid);
  const spaces = page.items;
  const reload = page.reload;

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
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.spaces.title")}</Typography>
            <Tooltip title={t("admin.spaces.tooltip")} arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary" data-testid="admin-spaces-count">{page.totalCount} {t("admin.spaces.count")}</Typography>
        </Box>
        <Button startIcon={<AddOutlined />} variant="contained" size="small" onClick={() => setCreateOpen(true)}>{t("admin.spaces.newSpace")}</Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder={t("admin.spaces.search")}
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
              <TableCell>{t("admin.spaces.table.name")}</TableCell>
              <TableCell>{t("admin.spaces.table.uuid")}</TableCell>
              <TableCell>{t("admin.spaces.table.created")}</TableCell>
              <TableCell align="right">{t("admin.spaces.table.actions")}</TableCell>
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
      {!query.trim() && (
        <ListPaging
          loaded={spaces.length}
          total={page.totalCount}
          hasMore={page.hasMore}
          loadingMore={page.loadingMore}
          onLoadMore={page.loadMore}
          testId="admin-spaces-paging"
        />
      )}

      {/* Create Space dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.spaces.dialog.create")}</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField label={t("admin.spaces.dialog.label")} size="small" fullWidth value={newName} onChange={e => setNewName(e.target.value)} autoFocus placeholder={t("admin.spaces.dialog.placeholder")} />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>{t("common.cancel")}</Button>
          <Button size="small" variant="contained" onClick={handleCreate} disabled={!newName.trim()}>{t("admin.spaces.dialog.create")}</Button>
        </DialogActions>
      </Dialog>

      {/* Edit Space dialog */}
      <Dialog open={Boolean(editSpace)} onClose={() => setEditSpace(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.spaces.dialog.edit")}</Typography>
          <IconButton size="small" onClick={() => setEditSpace(null)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
            {editSpace && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>UUID: {editSpace.uuid}</Typography>
            )}
            <TextField label={t("admin.spaces.dialog.label")} size="small" fullWidth value={editName} onChange={e => setEditName(e.target.value)} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditSpace(null)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit}>{t("common.save")}</Button>
        </DialogActions>
      </Dialog>

      {/* Delete Confirm Dialog */}
      <Dialog open={Boolean(deleteConfirm)} onClose={() => setDeleteConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{t("admin.spaces.dialog.delete")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" dangerouslySetInnerHTML={{ __html: t("admin.spaces.confirm.delete", { name: `<strong>${deleteConfirm?.name}</strong>` }) }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteConfirm(null)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" color="error" size="small" onClick={handleDelete}>{t("common.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Users Table ───────────────────────────────────────────────────────────
function UsersAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [editUser, setEditUser] = useState<UserResponse | null>(null);
  const [editForm, setEditForm] = useState({ username: "", email: "", firstname: "", lastname: "" });
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState({ username: "", email: "", firstname: "", lastname: "" });
  const [deleteConfirm, setDeleteConfirm] = useState<UserResponse | null>(null);

  const loadPage = useMemo(
    () => (token ? (paging: PagingParams) => listUsers(token, paging).then(r => pageFrom(r, u => u)) : null),
    [token],
  );
  const page = usePagedList<UserResponse>(loadPage, u => u.uuid);
  const users = page.items;
  const reload = page.reload;

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
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.users.title")}</Typography>
            <Tooltip title={t("admin.users.tooltip")} arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary" data-testid="admin-users-count">{page.totalCount} {t("admin.users.count")}</Typography>
        </Box>
        <Button startIcon={<PersonAddOutlined />} variant="contained" size="small" onClick={() => setCreateOpen(true)}>
          {t("admin.users.createUser")}
        </Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder={t("admin.users.search")}
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
              <TableCell>{t("admin.users.table.user")}</TableCell>
              <TableCell>{t("admin.users.table.email")}</TableCell>
              <TableCell>{t("admin.users.table.status")}</TableCell>
              <TableCell>{t("admin.users.table.created")}</TableCell>
              <TableCell align="right">{t("admin.users.table.actions")}</TableCell>
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
                    label={u.enabled ? t("admin.users.chip.enabled") : t("admin.users.chip.disabled")}
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
      {!query.trim() && (
        <ListPaging
          loaded={users.length}
          total={page.totalCount}
          hasMore={page.hasMore}
          loadingMore={page.loadingMore}
          onLoadMore={page.loadMore}
          testId="admin-users-paging"
        />
      )}

      {/* Create User Dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.users.dialog.create")}</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
            <TextField label={t("admin.users.dialog.label.username")} size="small" fullWidth value={createForm.username} onChange={e => setCreateForm(f => ({ ...f, username: e.target.value }))} autoFocus />
            <TextField label={t("admin.users.dialog.label.email")} size="small" fullWidth type="email" value={createForm.email} onChange={e => setCreateForm(f => ({ ...f, email: e.target.value }))} />
            <TextField label={t("admin.users.dialog.label.firstname")} size="small" fullWidth value={createForm.firstname} onChange={e => setCreateForm(f => ({ ...f, firstname: e.target.value }))} />
            <TextField label={t("admin.users.dialog.label.lastname")} size="small" fullWidth value={createForm.lastname} onChange={e => setCreateForm(f => ({ ...f, lastname: e.target.value }))} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setCreateOpen(false)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" size="small" onClick={handleCreate} disabled={!createForm.username.trim()}>{t("common.create")}</Button>
        </DialogActions>
      </Dialog>

      {/* Edit User Dialog */}
      <Dialog open={Boolean(editUser)} onClose={() => setEditUser(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.users.dialog.edit")}</Typography>
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
              <TextField label={t("admin.users.dialog.label.username")} size="small" fullWidth value={editForm.username} onChange={e => setEditForm(f => ({ ...f, username: e.target.value }))} />
              <TextField label={t("admin.users.dialog.label.email")} size="small" fullWidth type="email" value={editForm.email} onChange={e => setEditForm(f => ({ ...f, email: e.target.value }))} />
              <TextField label={t("admin.users.dialog.label.firstname")} size="small" fullWidth value={editForm.firstname} onChange={e => setEditForm(f => ({ ...f, firstname: e.target.value }))} />
              <TextField label={t("admin.users.dialog.label.lastname")} size="small" fullWidth value={editForm.lastname} onChange={e => setEditForm(f => ({ ...f, lastname: e.target.value }))} />
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditUser(null)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit} sx={{ textTransform: "none", fontWeight: 600 }}>{t("common.save")}</Button>
        </DialogActions>
      </Dialog>

      {/* Delete Confirm Dialog */}
      <Dialog open={Boolean(deleteConfirm)} onClose={() => setDeleteConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{t("admin.users.dialog.delete")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" dangerouslySetInnerHTML={{ __html: t("admin.users.confirm.delete", { name: `<strong>${deleteConfirm?.username}</strong>` }) }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteConfirm(null)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" color="error" size="small" onClick={handleDelete}>{t("common.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Groups Table ──────────────────────────────────────────────────────────
function GroupsAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [editGroup, setEditGroup] = useState<GroupResponse | null>(null);
  const [editName, setEditName] = useState("");
  const [deleteConfirm, setDeleteConfirm] = useState<GroupResponse | null>(null);

  const loadPage = useMemo(
    () => (token ? (paging: PagingParams) => listGroups(token, paging).then(r => pageFrom(r, g => g)) : null),
    [token],
  );
  const page = usePagedList<GroupResponse>(loadPage, g => g.uuid);
  const groups = page.items;
  const reload = page.reload;

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
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.groups.title")}</Typography>
            <Tooltip title={t("admin.groups.tooltip")} arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary" data-testid="admin-groups-count">{page.totalCount} {t("admin.groups.count")}</Typography>
        </Box>
        <Button startIcon={<AddOutlined />} variant="contained" size="small" onClick={() => setCreateOpen(true)}>{t("admin.groups.newGroup")}</Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder={t("admin.groups.search")}
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
              <TableCell>{t("admin.groups.table.name")}</TableCell>
              <TableCell>{t("admin.groups.table.uuid")}</TableCell>
              <TableCell>{t("admin.groups.table.created")}</TableCell>
              <TableCell align="right">{t("admin.groups.table.actions")}</TableCell>
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
      {!query.trim() && (
        <ListPaging
          loaded={groups.length}
          total={page.totalCount}
          hasMore={page.hasMore}
          loadingMore={page.loadingMore}
          onLoadMore={page.loadMore}
          testId="admin-groups-paging"
        />
      )}

      {/* Create Group dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <GroupsOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.groups.dialog.create")}</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField label={t("admin.groups.dialog.label")} size="small" fullWidth value={newName} onChange={e => setNewName(e.target.value)} autoFocus placeholder={t("admin.groups.dialog.placeholder")} />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>{t("common.cancel")}</Button>
          <Button size="small" variant="contained" onClick={handleCreateGroup} disabled={!newName.trim()}>{t("admin.groups.dialog.create")}</Button>
        </DialogActions>
      </Dialog>

      {/* Edit Group dialog */}
      <Dialog open={Boolean(editGroup)} onClose={() => setEditGroup(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.groups.dialog.edit")}</Typography>
          <IconButton size="small" onClick={() => setEditGroup(null)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
            {editGroup && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>UUID: {editGroup.uuid}</Typography>
            )}
            <TextField label={t("admin.groups.dialog.label")} size="small" fullWidth value={editName} onChange={e => setEditName(e.target.value)} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditGroup(null)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit}>{t("common.save")}</Button>
        </DialogActions>
      </Dialog>

      {/* Delete Confirm Dialog */}
      <Dialog open={Boolean(deleteConfirm)} onClose={() => setDeleteConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{t("admin.groups.dialog.delete")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" dangerouslySetInnerHTML={{ __html: t("admin.groups.confirm.delete", { name: `<strong>${deleteConfirm?.name}</strong>` }) }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteConfirm(null)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" color="error" size="small" onClick={handleDelete}>{t("common.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Access Control (Roles with Permissions) ──────────────────────────────
function AccessControlAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [newRoleName, setNewRoleName] = useState("");
  const [editRole, setEditRole] = useState<RoleResponse | null>(null);
  const [editName, setEditName] = useState("");
  const [deleteConfirm, setDeleteConfirm] = useState<RoleResponse | null>(null);
  const [saving, setSaving] = useState(false);

  const loadPage = useMemo(
    () => (token ? (paging: PagingParams) => listRoles(token, paging).then(r => pageFrom(r, role => role)) : null),
    [token],
  );
  const page = usePagedList<RoleResponse>(loadPage, r => r.uuid);
  const roles = page.items;
  const reload = page.reload;

  // Select the first role once they arrive, but never override a selection the user made.
  useEffect(() => {
    if (!selectedRoleId && roles.length) setSelectedRoleId(roles[0].uuid);
  }, [roles, selectedRoleId]);

  const selectedRole = roles.find(r => r.uuid === selectedRoleId) ?? null;

  const filteredRoles = roles.filter(r => {
    if (!query.trim()) return true;
    return r.name.toLowerCase().includes(query.toLowerCase());
  });

  const hasPermission = (perm: string) => selectedRole?.permissions?.includes(perm) ?? false;

  // The i18n key is the permission name itself, so a permission added to the backend enum only
  // needs a locale entry — nothing here changes. An unknown permission renders without a
  // description rather than showing the raw key.
  const permissionDescription = (perm: string) => t(`admin.roles.permission.${perm}`, { defaultValue: "" });

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
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.roles.title")}</Typography>
          <Typography variant="caption" color="text.secondary" data-testid="admin-roles-count">
            {page.totalCount} {t("admin.roles.count")}
            {saving && " · " + t("admin.roles.saving")}
          </Typography>
        </Box>
        <Button startIcon={<AddOutlined />} variant="outlined" size="small" onClick={() => setCreateOpen(true)}>{t("admin.roles.newRole")}</Button>
      </Box>

      <Box sx={{ display: "flex", gap: 2, height: "calc(100vh - 220px)", minHeight: 400 }}>
        {/* Left: Role list */}
        <Box sx={{ width: 220, flexShrink: 0, display: "flex", flexDirection: "column", gap: 0.5 }}>
          <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 0.5, px: 0.5 }}>
            {t("admin.roles.sectionTitle")}
          </Typography>
          <TextField
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={t("admin.roles.search")}
            size="small"
            data-testid="admin-roles-search"
            sx={{ mb: 0.5 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                </InputAdornment>
              ),
            }}
          />
          {filteredRoles.length === 0 && (
            <Typography variant="caption" data-testid="admin-roles-no-match" sx={{ color: tokens.text.tertiary, px: 0.5, py: 1 }}>
              {t("admin.roles.noMatch")}
            </Typography>
          )}
          {filteredRoles.map(role => (
            <Box
              key={role.uuid}
              data-testid={`admin-role-row-${role.name}`}
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
                {(role.permissions ?? []).length} {t("admin.roles.permissionsCount")}
              </Typography>
            </Box>
          ))}
          {!query.trim() && (
            <ListPaging
              loaded={roles.length}
              total={page.totalCount}
              hasMore={page.hasMore}
              loadingMore={page.loadingMore}
              onLoadMore={page.loadMore}
              testId="admin-roles-paging"
            />
          )}
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
                <Typography variant="caption" color="text.secondary">— {(selectedRole.permissions ?? []).length} {t("admin.roles.permissionsGranted")}</Typography>
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
                            sx={{ alignItems: "flex-start", mr: 0, py: 0.25 }}
                            control={
                              <Checkbox
                                size="small"
                                checked={hasPermission(p)}
                                onChange={() => togglePermission(p)}
                                sx={{ py: 0.25, pl: 1, color: tokens.text.tertiary, "&.Mui-checked": { color: tokens.primary.main } }}
                              />
                            }
                            label={
                              <Box sx={{ py: 0.25 }}>
                                <Typography variant="caption" sx={{ display: "block", fontFamily: "monospace", fontWeight: 600, fontSize: "0.75rem", color: hasPermission(p) ? tokens.primary.light : tokens.text.primary }}>
                                  {p}
                                </Typography>
                                {permissionDescription(p) && (
                                  <Typography variant="caption" sx={{ display: "block", fontSize: "0.7rem", lineHeight: 1.45, color: tokens.text.tertiary }}>
                                    {permissionDescription(p)}
                                  </Typography>
                                )}
                              </Box>
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
              <Typography variant="body2" color="text.secondary">{t("admin.roles.empty")}</Typography>
            </Box>
          )}
        </Box>
      </Box>

      {/* Create Role dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <SecurityOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.roles.dialog.create")}</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <TextField label={t("admin.roles.dialog.label")} size="small" fullWidth value={newRoleName} onChange={e => setNewRoleName(e.target.value)} autoFocus placeholder={t("admin.roles.dialog.placeholder")} />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>{t("common.cancel")}</Button>
          <Button size="small" variant="contained" onClick={handleCreateRole} disabled={!newRoleName.trim()}>{t("common.create")}</Button>
        </DialogActions>
      </Dialog>

      {/* Edit Role dialog */}
      <Dialog open={Boolean(editRole)} onClose={() => setEditRole(null)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.roles.dialog.edit")}</Typography>
          <IconButton size="small" onClick={() => setEditRole(null)}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
            {editRole && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>UUID: {editRole.uuid}</Typography>
            )}
            <TextField label={t("admin.roles.dialog.label")} size="small" fullWidth value={editName} onChange={e => setEditName(e.target.value)} />
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 1.5 }}>
          <Button onClick={() => setEditRole(null)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" size="small" onClick={handleSaveEdit}>{t("common.save")}</Button>
        </DialogActions>
      </Dialog>

      {/* Delete Role Confirm */}
      <Dialog open={Boolean(deleteConfirm)} onClose={() => setDeleteConfirm(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{t("admin.roles.dialog.delete")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" dangerouslySetInnerHTML={{ __html: t("admin.roles.confirm.delete", { name: `<strong>${deleteConfirm?.name}</strong>` }) }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteConfirm(null)} size="small">{t("common.cancel")}</Button>
          <Button variant="contained" color="error" size="small" onClick={handleDeleteRole}>{t("common.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── API Keys Table ────────────────────────────────────────────────────────
// Permissions grouped by resource (derived from Permission.java enum)
const PERMISSION_GROUPS: Record<string, string[]> = {
  Annotation: ["CREATE_ANNOTATION", "READ_ANNOTATION", "DELETE_ANNOTATION", "UPDATE_ANNOTATION"],
  // Three, not four: notifications are dispatched server-side, so there is no CREATE.
  Notification: ["READ_NOTIFICATION", "UPDATE_NOTIFICATION", "DELETE_NOTIFICATION"],
  Asset: ["CREATE_ASSET", "READ_ASSET", "DELETE_ASSET", "UPDATE_ASSET"],
  "Asset Location": ["CREATE_ASSET_LOCATION", "READ_ASSET_LOCATION", "DELETE_ASSET_LOCATION", "UPDATE_ASSET_LOCATION"],
  Attachment: ["CREATE_ATTACHMENT", "READ_ATTACHMENT", "DELETE_ATTACHMENT", "UPDATE_ATTACHMENT"],
  User: ["CREATE_USER", "READ_USER", "DELETE_USER", "UPDATE_USER"],
  Role: ["CREATE_ROLE", "READ_ROLE", "DELETE_ROLE", "UPDATE_ROLE"],
  Group: ["CREATE_GROUP", "READ_GROUP", "DELETE_GROUP", "UPDATE_GROUP"],
  // Renamed from Project to Space server-side; the old PROJECT constants no longer exist and a
  // request carrying one is rejected outright, which would take the whole matrix down with it.
  Space: ["CREATE_SPACE", "READ_SPACE", "DELETE_SPACE", "UPDATE_SPACE"],
  Cluster: ["CREATE_CLUSTER", "READ_CLUSTER", "DELETE_CLUSTER", "UPDATE_CLUSTER"],
  Collection: ["CREATE_COLLECTION", "READ_COLLECTION", "DELETE_COLLECTION", "UPDATE_COLLECTION"],
  Comment: ["CREATE_COMMENT", "READ_COMMENT", "DELETE_COMMENT", "UPDATE_COMMENT"],
  // READ + UPDATE is the reviewer's set: see the queue, decide a group. CREATE belongs to the
  // discovery node's credentials, DELETE discards a proposal outright.
  Deduplication: ["CREATE_DEDUP", "READ_DEDUP", "DELETE_DEDUP", "UPDATE_DEDUP"],
  // Same reviewer split as Deduplication: UPDATE_DETECTION is what confirm/reject need, while
  // CREATE belongs to the detecting node's credentials.
  Detection: ["CREATE_DETECTION", "READ_DETECTION", "DELETE_DETECTION", "UPDATE_DETECTION"],
  Embedding: ["CREATE_EMBEDDING", "READ_EMBEDDING", "DELETE_EMBEDDING", "UPDATE_EMBEDDING"],
  Reaction: ["CREATE_REACTION", "READ_REACTION", "DELETE_REACTION", "UPDATE_REACTION"],
  Task: ["CREATE_TASK", "READ_TASK", "DELETE_TASK", "UPDATE_TASK"],
  Tag: ["CREATE_TAG", "READ_TAG", "DELETE_TAG", "UPDATE_TAG", "TAG_ASSET", "UNTAG_ASSET"],
  Token: ["CREATE_TOKEN", "READ_TOKEN", "DELETE_TOKEN", "UPDATE_TOKEN"],
  Library: ["CREATE_LIBRARY", "READ_LIBRARY", "DELETE_LIBRARY", "UPDATE_LIBRARY"],
  Pipeline: ["CREATE_PIPELINE", "READ_PIPELINE", "DELETE_PIPELINE", "UPDATE_PIPELINE"],
  // Authoring a pipeline through the assistant is granted separately from authoring one in the
  // editor. Both are needed to write: these never widen what the Pipeline group above allows.
  "Pipeline (assistant)": ["CREATE_MCP_PIPELINE", "UPDATE_MCP_PIPELINE", "VALIDATE_MCP_PIPELINE", "EXECUTE_MCP_NODE"],
  "Asset Pool": ["CREATE_ASSET_POOL", "READ_ASSET_POOL", "DELETE_ASSET_POOL", "UPDATE_ASSET_POOL"],
};

function ApiKeysAdmin() {
  const { t } = useTranslation();
  const { token: authToken } = useAuth();
  const [keys, setKeys] = useState<TokenResponse[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [creating, setCreating] = useState(false);
  const [query, setQuery] = useState("");
  const [menuAnchor, setMenuAnchor] = useState<null | HTMLElement>(null);
  const [menuKeyId, setMenuKeyId] = useState<string | null>(null);
  const [createdToken, setCreatedToken] = useState<string | null>(null);
  const [editKey, setEditKey] = useState<TokenResponse | null>(null);
  const [editName, setEditName] = useState("");

  useEffect(() => {
    if (!authToken) return;
    listTokens(authToken).then(res => setKeys(res.data ?? [])).catch(() => {});
  }, [authToken]);

  const handleCreate = async () => {
    if (!authToken || !newName.trim()) return;
    setCreating(true);
    try {
      const created = await createToken(authToken, { name: newName.trim() });
      setKeys(prev => [...prev, created]);
      setNewName("");
      setCreatedToken(created.token ?? null);
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (uuid: string) => {
    if (!authToken) return;
    try {
      await deleteTokenApi(authToken, uuid);
      setKeys(prev => prev.filter(k => k.uuid !== uuid));
    } catch { /* ignore */ }
    setMenuAnchor(null); setMenuKeyId(null);
  };

  const openEdit = (k: TokenResponse) => {
    setEditKey(k);
    setEditName(k.name);
    setMenuAnchor(null); setMenuKeyId(null);
  };

  const handleSaveEdit = async () => {
    if (!authToken || !editKey) return;
    const name = editName.trim();
    const request: TokenUpdateRequest = { name: name || undefined };
    try {
      await updateToken(authToken, editKey.uuid, request);
      setKeys(prev => prev.map(k => k.uuid === editKey.uuid ? { ...k, name } : k));
      setEditKey(null);
    } catch { /* ignore */ }
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.apiKeys.title")}</Typography>
            <Tooltip title={t("admin.apiKeys.tooltip")} arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <Typography variant="caption" color="text.secondary">{keys.length} {t("admin.apiKeys.count")}</Typography>
        </Box>
        <Button startIcon={<VpnKeyOutlined />} variant="contained" size="small" onClick={() => { setCreateOpen(true); setCreatedToken(null); }}>
          {t("admin.apiKeys.createKey")}
        </Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder={t("admin.apiKeys.search")}
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
              <TableCell>{t("admin.apiKeys.table.name")}</TableCell>
              <TableCell>{t("admin.apiKeys.table.keyId")}</TableCell>
              <TableCell>{t("admin.apiKeys.table.created")}</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {keys.filter(k => {
              if (!query.trim()) return true;
              const q = query.toLowerCase();
              return k.name.toLowerCase().includes(q) || k.uuid.toLowerCase().includes(q);
            }).map(k => (
              <TableRow key={k.uuid} hover>
                <TableCell><Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{k.name}</Typography></TableCell>
                <TableCell><Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.secondary, bgcolor: tokens.bg.overlay, px: 0.75, py: 0.25, borderRadius: tokens.radius.sm }}>{k.uuid.slice(0, 16)}…</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{k.status?.created ? new Date(k.status.created).toLocaleDateString() : "—"}</Typography></TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={e => { setMenuKeyId(k.uuid); setMenuAnchor(e.currentTarget); }}>
                    <MoreVertOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => { setMenuAnchor(null); setMenuKeyId(null); }}>
        <MenuItem onClick={() => { const k = keys.find(k => k.uuid === menuKeyId); if (k) openEdit(k); }} sx={{ gap: 1, fontSize: "0.82rem" }}>
          <EditOutlined sx={{ fontSize: 16 }} /> {t("admin.apiKeys.menu.rename")}
        </MenuItem>
        <MenuItem onClick={() => menuKeyId && handleDelete(menuKeyId)} sx={{ gap: 1, fontSize: "0.82rem", color: tokens.accent.red }}>
          <DeleteOutlineOutlined sx={{ fontSize: 16 }} /> {t("admin.apiKeys.menu.delete")}
        </MenuItem>
      </Menu>

      {/* Create API Key dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <VpnKeyOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.apiKeys.dialog.create")}</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField
              label={t("admin.apiKeys.dialog.keyName")}
              placeholder={t("admin.apiKeys.dialog.keyPlaceholder")}
              value={newName}
              onChange={e => setNewName(e.target.value)}
              size="small"
              fullWidth
              autoFocus
            />
            {createdToken && (
              <Box sx={{ p: 1.5, bgcolor: tokens.bg.overlay, borderRadius: tokens.radius.sm, border: `1px solid ${tokens.border.subtle}` }}>
                <Typography variant="caption" fontWeight={600} sx={{ color: tokens.accent.green, display: "block", mb: 0.5 }}>
                  {t("admin.apiKeys.dialog.tokenCreated")}
                </Typography>
                <Typography variant="body2" sx={{ fontFamily: "monospace", fontSize: "0.82rem", wordBreak: "break-all" }}>
                  {createdToken}
                </Typography>
                <Typography variant="caption" sx={{ color: tokens.accent.red, display: "block", mt: 0.5 }}>
                  {t("admin.apiKeys.dialog.tokenWarning")}
                </Typography>
              </Box>
            )}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2, gap: 1 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>{createdToken ? t("common.close") : t("common.cancel")}</Button>
          {!createdToken && (
            <Button
              size="small"
              variant="contained"
              onClick={handleCreate}
              disabled={!newName.trim() || creating}
              startIcon={<VpnKeyOutlined />}
            >
              {creating ? t("admin.apiKeys.dialog.creating") : t("admin.apiKeys.dialog.createKey")}
            </Button>
          )}
        </DialogActions>
      </Dialog>

      {/* Rename API Key dialog */}
      <Dialog open={Boolean(editKey)} onClose={() => setEditKey(null)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <EditOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.apiKeys.dialog.edit")}</Typography>
          <IconButton size="small" onClick={() => setEditKey(null)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField
              label={t("admin.apiKeys.dialog.keyName")}
              placeholder={t("admin.apiKeys.dialog.keyPlaceholder")}
              value={editName}
              onChange={e => setEditName(e.target.value)}
              size="small"
              fullWidth
              autoFocus
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2, gap: 1 }}>
          <Button size="small" onClick={() => setEditKey(null)}>{t("common.cancel")}</Button>
          <Button size="small" variant="contained" onClick={handleSaveEdit} disabled={!editName.trim()}>
            {t("common.save")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Blacklist Table ───────────────────────────────────────────────────────
function BlacklistAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [newAssetUuid, setNewAssetUuid] = useState("");

  const loadPage = useMemo(
    () => (token ? (paging: PagingParams) => listBlacklists(token, paging).then(r => pageFrom(r, e => e)) : null),
    [token],
  );
  const page = usePagedList<BlacklistResponse>(loadPage, e => e.uuid);
  const entries = page.items;
  const loadEntries = page.reload;

  const filteredEntries = entries.filter(e => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return (e.name?.toLowerCase().includes(q) ?? false) || (e.assetUuid?.toLowerCase().includes(q) ?? false);
  });

  const handleCreate = () => {
    if (!newName.trim() || !token) return;
    createBlacklist(token, { name: newName.trim(), assetUuid: newAssetUuid.trim() || undefined }).then(() => {
      loadEntries();
      setCreateOpen(false);
      setNewName(""); setNewAssetUuid("");
    }).catch(() => {});
  };

  const handleDelete = (uuid: string) => {
    if (!token) return;
    deleteBlacklist(token, uuid).then(() => loadEntries()).catch(() => {});
  };

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.blacklist.title")}</Typography>
          <Typography variant="caption" color="text.secondary" data-testid="admin-blacklist-count">{page.totalCount} {t("admin.blacklist.count")}</Typography>
        </Box>
        <Button startIcon={<BlockOutlined />} variant="contained" size="small" color="error" onClick={() => setCreateOpen(true)}>
          {t("admin.blacklist.addEntry")}
        </Button>
      </Box>
      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder={t("admin.blacklist.search")}
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
              <TableCell>{t("admin.blacklist.table.name", "Name")}</TableCell>
              <TableCell>{t("admin.blacklist.table.assetUuid", "Asset UUID")}</TableCell>
              <TableCell>{t("admin.blacklist.table.added")}</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {filteredEntries.map(e => (
              <TableRow key={e.uuid} hover>
                <TableCell><Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.primary, fontSize: "0.78rem" }}>{e.name}</Typography></TableCell>
                <TableCell><Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.secondary, fontSize: "0.72rem" }}>{e.assetUuid ?? "—"}</Typography></TableCell>
                <TableCell><Typography variant="caption" color="text.secondary">{e.status?.created ? new Date(e.status.created).toLocaleDateString() : "—"}</Typography></TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={() => handleDelete(e.uuid)}>
                    <DeleteOutlineOutlined sx={{ fontSize: 15, color: tokens.accent.red }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {!query.trim() && (
        <ListPaging
          loaded={entries.length}
          total={page.totalCount}
          hasMore={page.hasMore}
          loadingMore={page.loadingMore}
          onLoadMore={page.loadMore}
          testId="admin-blacklist-paging"
        />
      )}

      {/* Create blacklist entry dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <BlockOutlined sx={{ fontSize: 18, color: tokens.accent.red }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>{t("admin.blacklist.dialog.add")}</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField label={t("admin.blacklist.dialog.name", "Name")} size="small" fullWidth value={newName} onChange={e => setNewName(e.target.value)} autoFocus />
            <TextField label={t("admin.blacklist.dialog.assetUuid", "Asset UUID")} size="small" fullWidth value={newAssetUuid} onChange={e => setNewAssetUuid(e.target.value)} placeholder="Optional asset UUID" />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>{t("common.cancel")}</Button>
          <Button size="small" variant="contained" color="error" onClick={handleCreate} disabled={!newName.trim()} startIcon={<BlockOutlined />}>
            {t("admin.blacklist.addEntry")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}


// ── Memory denylist view ──────────────────────────────────────────────────

/**
 * Admin CRUD for the agent memory denylist.
 *
 * Each rule is one regular expression; a `put_memory` whose body or title matches is rejected with
 * the rule's own message. Several phrases fit in a single rule through alternation, e.g.
 * `(?i)\b(one|two|three)\b`.
 */
function MemoryDenylistAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [rules, setRules] = useState<MemoryDenyRuleResponse[]>([]);
  const [query, setQuery] = useState("");
  const [editor, setEditor] = useState<{ uuid?: string; name: string; pattern: string; message: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadRules = useCallback(() => {
    if (!token) return;
    listMemoryDenyRules(token).then(r => setRules(r.data ?? [])).catch(() => setRules([]));
  }, [token]);

  useEffect(() => { loadRules(); }, [loadRules]);

  const filtered = rules.filter(r => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return r.name.toLowerCase().includes(q) || r.pattern.toLowerCase().includes(q);
  });

  const handleSave = () => {
    if (!token || !editor) return;
    setError(null);
    const body = { name: editor.name.trim(), pattern: editor.pattern, message: editor.message.trim() };
    const request = editor.uuid
      ? updateMemoryDenyRule(token, editor.uuid, body)
      : createMemoryDenyRule(token, body);
    // The server compiles the pattern, so an invalid regex comes back as a 400 with the reason.
    request.then(() => { loadRules(); setEditor(null); }).catch(e => setError(String(e)));
  };

  const handleToggle = (rule: MemoryDenyRuleResponse) => {
    if (!token) return;
    updateMemoryDenyRule(token, rule.uuid, { enabled: !rule.enabled }).then(loadRules).catch(e => setError(String(e)));
  };

  const handleDelete = (uuid: string) => {
    if (!token) return;
    deleteMemoryDenyRule(token, uuid).then(loadRules).catch(e => setError(String(e)));
  };

  return (
    <Box data-testid="memory-denylist-admin">
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>
            {t("admin.memoryDenylist.title", "Memory denylist")}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t("admin.memoryDenylist.subtitle",
              "Patterns the chat agent may never store in its memory. A match rejects the write with the rule's message.")}
          </Typography>
        </Box>
        <Button startIcon={<BlockOutlined />} variant="contained" size="small" color="error"
          onClick={() => setEditor({ name: "", pattern: "", message: "" })} data-testid="memory-denylist-add">
          {t("admin.memoryDenylist.addRule", "Add rule")}
        </Button>
      </Box>

      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder={t("admin.memoryDenylist.search", "Search rules")}
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
              <TableCell>{t("admin.memoryDenylist.table.name", "Name")}</TableCell>
              <TableCell>{t("admin.memoryDenylist.table.pattern", "Pattern")}</TableCell>
              <TableCell>{t("admin.memoryDenylist.table.message", "Rejection message")}</TableCell>
              <TableCell>{t("admin.memoryDenylist.table.enabled", "Enabled")}</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {filtered.map(r => (
              <TableRow key={r.uuid} hover data-testid={`memory-denylist-row-${r.name}`}>
                <TableCell>
                  <Typography variant="caption" sx={{ color: tokens.text.primary, fontSize: "0.78rem" }}>{r.name}</Typography>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" sx={{ fontFamily: "monospace", color: tokens.text.secondary, fontSize: "0.72rem" }}>
                    {r.pattern}
                  </Typography>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" color="text.secondary">{r.message}</Typography>
                </TableCell>
                <TableCell>
                  <Switch size="small" checked={r.enabled} onChange={() => handleToggle(r)}
                    data-testid={`memory-denylist-toggle-${r.name}`} />
                </TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={() => setEditor({ uuid: r.uuid, name: r.name, pattern: r.pattern, message: r.message })}>
                    <EditOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                  <IconButton size="small" onClick={() => handleDelete(r.uuid)}>
                    <DeleteOutlineOutlined sx={{ fontSize: 15, color: tokens.accent.red }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
            {filtered.length === 0 && (
              <TableRow>
                <TableCell colSpan={5}>
                  <Typography variant="caption" color="text.secondary" data-testid="memory-denylist-empty">
                    {t("admin.memoryDenylist.empty", "No deny rules configured.")}
                  </Typography>
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      <Dialog open={editor !== null} onClose={() => setEditor(null)} maxWidth="sm" fullWidth
        PaperProps={{ sx: { bgcolor: tokens.bg.surface, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1, pb: 1 }}>
          <BlockOutlined sx={{ fontSize: 18, color: tokens.accent.red }} />
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>
            {editor?.uuid ? t("admin.memoryDenylist.dialog.edit", "Edit deny rule") : t("admin.memoryDenylist.dialog.add", "Add deny rule")}
          </Typography>
          <IconButton size="small" onClick={() => setEditor(null)} sx={{ ml: "auto" }}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ pt: 1 }}>
          <Stack spacing={2.5}>
            <TextField label={t("admin.memoryDenylist.dialog.name", "Name")} size="small" fullWidth autoFocus
              value={editor?.name ?? ""} onChange={e => setEditor(prev => prev && { ...prev, name: e.target.value })}
              data-testid="memory-denylist-name" />
            <TextField label={t("admin.memoryDenylist.dialog.pattern", "Pattern (regex)")} size="small" fullWidth
              value={editor?.pattern ?? ""} onChange={e => setEditor(prev => prev && { ...prev, pattern: e.target.value })}
              helperText={t("admin.memoryDenylist.dialog.patternHelp",
                "Java regex. Cover several phrases in one rule with alternation, e.g. (?i)\\b(alpha|beta)\\b")}
              InputProps={{ sx: { fontFamily: "monospace" } }}
              data-testid="memory-denylist-pattern" />
            <TextField label={t("admin.memoryDenylist.dialog.message", "Rejection message")} size="small" fullWidth multiline minRows={2}
              value={editor?.message ?? ""} onChange={e => setEditor(prev => prev && { ...prev, message: e.target.value })}
              helperText={t("admin.memoryDenylist.dialog.messageHelp",
                "Shown to the agent when a note is rejected. Explain what to do instead; never repeat the matched text.")}
              data-testid="memory-denylist-message" />
            {error && <Typography variant="caption" sx={{ color: tokens.accent.red }} data-testid="memory-denylist-error">{error}</Typography>}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size="small" onClick={() => setEditor(null)}>{t("common.cancel")}</Button>
          <Button size="small" variant="contained" color="error" onClick={handleSave}
            disabled={!editor?.name.trim() || !editor?.pattern.trim() || !editor?.message.trim()}
            data-testid="memory-denylist-save">
            {t("common.save", "Save")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Permissions view ──────────────────────────────────────────────────────
// ── Admin Area Shell ──────────────────────────────────────────────────────

export default function AdminArea() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();

  const ADMIN_TABS = [
    { label: t("admin.tab.spaces"), path: "/admin/spaces" },
    { label: t("admin.tab.users"), path: "/admin/users" },
    { label: t("admin.tab.groups"), path: "/admin/groups" },
    { label: t("admin.tab.permissions"), path: "/admin/permissions" },
    { label: t("admin.tab.apiKeys"), path: "/admin/api-keys" },
    { label: t("admin.tab.blacklist"), path: "/admin/blacklist" },
    { label: t("admin.tab.memoryDenylist"), path: "/admin/memory-denylist" },
  ];

  const tabIdx = ADMIN_TABS.findIndex(tab => location.pathname === tab.path);
  const activeTab = tabIdx === -1 ? 0 : tabIdx;

  useEffect(() => {
    if (location.pathname === "/admin" || location.pathname === "/admin/") {
      navigate("/admin/spaces", { replace: true });
    }
  }, [location.pathname, navigate]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", mb: 0.25 }}>{t("admin.title")}</Typography>
        <Typography variant="caption" color="text.secondary">{t("admin.subtitle")}</Typography>
      </Box>

      <Box sx={{ borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, px: 2 }}>
        <Tabs value={activeTab} onChange={(_, i) => navigate(ADMIN_TABS[i].path)}>
          {ADMIN_TABS.map(tab => <Tab key={tab.path} label={tab.label} sx={{ fontSize: "0.8rem" }} />)}
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
          <Route path="memory-denylist" element={<MemoryDenylistAdmin />} />
        </Routes>
      </Box>
    </Box>
  );
}
