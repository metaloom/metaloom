import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  InputAdornment,
  Stack,
  Switch,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import DeleteOutline from "@mui/icons-material/DeleteOutline";
import PublicOutlined from "@mui/icons-material/PublicOutlined";
import AddOutlined from "@mui/icons-material/AddOutlined";
import SearchOutlined from "@mui/icons-material/SearchOutlined";
import { useTranslation } from "react-i18next";
import Title from "../../components/Title";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import {
  ChatSessionResponse,
  createChatSession,
  deleteChatSession,
  listChatSessions,
  publishChatSession,
} from "../../api/chatSessions";
import type { PagingParams } from "../../api/paging";
import ListPaging from "../../components/ListPaging";
import { DEFAULT_SORT, ListFilterSelect, ListSortControl, type SortState } from "../../components/ListControls";
import { useCreatorOptions } from "../../hooks/useCreatorOptions";
import { pageFrom, usePagedList } from "../../hooks/usePagedList";

function formatDate(value?: string | null): string {
  return value ? new Date(value).toLocaleString() : "—";
}

export default function ChatSessionsView() {
  const { token } = useAuth();
  const { t } = useTranslation();
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [tab, setTab] = useState<"mine" | "published">("mine");
  const [query, setQuery] = useState("");
  const [sortState, setSortState] = useState<SortState>(DEFAULT_SORT);
  const [creator, setCreator] = useState("");
  const creators = useCreatorOptions(token);
  const [deleteTarget, setDeleteTarget] = useState<ChatSessionResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createName, setCreateName] = useState("");
  const [createDescription, setCreateDescription] = useState("");
  const [createTags, setCreateTags] = useState("");

  // The tab is the route's own `?scope=`, the rest are the shared list parameters. All four are
  // dependencies: changing any of them invalidates the cursor, so the listing restarts.
  const loadPage = useMemo(
    () => (token
      ? (paging: PagingParams) => listChatSessions(token, tab, {
        ...paging,
        sort: sortState.sort,
        dir: sortState.dir,
        filters: creator ? [{ key: "creator", value: creator }] : undefined,
      }).then(r => pageFrom(r, session => session))
      : null),
    [token, tab, sortState.sort, sortState.dir, creator],
  );
  const page = usePagedList<ChatSessionResponse>(loadPage, session => session.uuid);
  const sessions = page.items;
  const loading = page.loading;
  const refresh = page.reload;

  const handleTogglePublish = async (session: ChatSessionResponse) => {
    if (!token) return;
    try {
      await publishChatSession(token, session.uuid, !session.published);
      showToast(session.published ? "Session unpublished" : "Session published");
      refresh();
    } catch (e) {
      console.error("Failed to toggle publish", e);
      showToast("Failed to change publish state", "error");
    }
  };

  const openCreate = () => {
    setCreateName("");
    setCreateDescription("");
    setCreateTags("");
    setCreateOpen(true);
  };

  const handleCreate = async () => {
    if (!token || !createName.trim()) return;
    try {
      await createChatSession(token, {
        name: createName.trim(),
        description: createDescription.trim() || undefined,
        tags: createTags.split(",").map((t) => t.trim()).filter(Boolean),
      });
      showToast("Session created");
      setCreateOpen(false);
      setTab("mine");
      refresh();
    } catch (e) {
      console.error("Failed to create session", e);
      showToast("Failed to create session", "error");
    }
  };

  const handleDelete = async () => {
    if (!token || !deleteTarget) return;
    try {
      await deleteChatSession(token, deleteTarget.uuid);
      showToast("Session deleted");
      setDeleteTarget(null);
      refresh();
    } catch (e) {
      console.error("Failed to delete session", e);
      showToast("Failed to delete session", "error");
    }
  };

  const filteredSessions = sessions.filter((s) => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return s.name.toLowerCase().includes(q)
      || (s.description ?? "").toLowerCase().includes(q)
      || (s.tags ?? []).some((tag) => tag.toLowerCase().includes(q));
  });

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" alignItems="center" justifyContent="space-between">
        <Title>Chat sessions</Title>
        <Button
          variant="contained"
          size="small"
          startIcon={<AddOutlined />}
          onClick={openCreate}
          data-testid="chat-session-create-button"
        >
          New session
        </Button>
      </Stack>
      <Typography variant="body2" sx={{ color: tokens.text.tertiary, mb: 2 }}>
        Publishable coding/chat sessions. Publish a session to share it, or open one to edit its context.
      </Typography>

      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab value="mine" label="My sessions" data-testid="chat-sessions-mine-tab" />
        <Tab value="published" label="Library (published)" data-testid="chat-sessions-library-tab" />
      </Tabs>

      {/* This view predates the i18n sweep and still hardcodes its English strings; only the new
          search copy is translated. See LOOM_UI_TASKS.md. */}
      <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap", mb: 2 }}>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t("chatSessions.search.placeholder")}
          size="small"
          data-testid="chat-sessions-search"
          sx={{ maxWidth: 360, flex: 1 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
        />
        {creators.length > 0 && (
          <ListFilterSelect value={creator} onChange={setCreator} options={creators}
            allLabel={t("chatSessions.filter.allCreators")} testId="chat-sessions-filter-creator" minWidth={160} />
        )}
        <ListSortControl value={sortState} onChange={setSortState} testId="chat-sessions-sort" />
      </Box>

      {loading ? (
        <CircularProgress size={20} />
      ) : (
        <Table size="small" data-testid="chat-sessions-table">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Description</TableCell>
              <TableCell>Tags</TableCell>
              <TableCell>Created</TableCell>
              <TableCell>Edited</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {filteredSessions.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6}>
                  <Typography
                    variant="caption"
                    sx={{ color: tokens.text.tertiary }}
                    data-testid={sessions.length === 0 ? "chat-sessions-empty" : "chat-sessions-no-match"}
                  >
                    {sessions.length === 0
                      ? "No sessions yet. A session is captured automatically the first time a chat is used."
                      : t("chatSessions.emptyState.noSearch")}
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              filteredSessions.map((s) => (
                <TableRow
                  key={s.uuid}
                  hover
                  sx={{ cursor: "pointer" }}
                  data-testid={`chat-session-row-${s.name}`}
                  onClick={() => navigate(`/chat/sessions/${s.uuid}`)}
                >
                  <TableCell>
                    <Stack direction="row" spacing={1} alignItems="center">
                      {s.name}
                      {s.published && (
                        <Tooltip title="Published">
                          <PublicOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                        </Tooltip>
                      )}
                    </Stack>
                  </TableCell>
                  <TableCell sx={{ maxWidth: 320 }}>
                    <Typography variant="body2" noWrap>
                      {s.description || "—"}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={0.5}>
                      {(s.tags ?? []).map((tag) => (
                        <Chip key={tag} label={tag} size="small" />
                      ))}
                    </Stack>
                  </TableCell>
                  <TableCell>{formatDate(s.status?.created)}</TableCell>
                  <TableCell>{formatDate(s.status?.edited)}</TableCell>
                  <TableCell align="right" onClick={(e) => e.stopPropagation()}>
                    {tab === "mine" && (
                      <>
                        <Tooltip title={s.published ? "Unpublish" : "Publish"}>
                          <Switch size="small" checked={s.published} onChange={() => handleTogglePublish(s)} data-testid={`chat-session-publish-${s.name}`} />
                        </Tooltip>
                        <Tooltip title="Delete">
                          <IconButton size="small" onClick={() => setDeleteTarget(s)} data-testid={`chat-session-delete-${s.name}`}>
                            <DeleteOutline sx={{ fontSize: 16 }} />
                          </IconButton>
                        </Tooltip>
                      </>
                    )}
                    {tab === "published" && (
                      <Button size="small" onClick={() => navigate(`/chat/sessions/${s.uuid}`)}>
                        Open
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      )}

      {/* Hidden while a term is typed: the box filters the loaded rows, so the count would be
          describing a different set than the one on screen. */}
      {!query.trim() && (
        <ListPaging
          loaded={sessions.length}
          total={page.totalCount}
          hasMore={page.hasMore}
          loadingMore={page.loadingMore}
          onLoadMore={page.loadMore}
          testId="chat-sessions-paging"
        />
      )}

      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)}>
        <DialogTitle>Delete session</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Delete “{deleteTarget?.name}”? This removes the session record and its context references. Sessions it
            referenced are not affected.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteTarget(null)}>Cancel</Button>
          <Button color="error" onClick={handleDelete} data-testid="chat-session-delete-confirm">
            Delete
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} fullWidth maxWidth="sm" data-testid="chat-session-create-dialog">
        <DialogTitle>New session</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Name"
              size="small"
              fullWidth
              value={createName}
              onChange={(e) => setCreateName(e.target.value)}
              inputProps={{ "data-testid": "chat-session-create-name" }}
            />
            <TextField
              label="Description"
              size="small"
              fullWidth
              multiline
              minRows={2}
              value={createDescription}
              onChange={(e) => setCreateDescription(e.target.value)}
              inputProps={{ "data-testid": "chat-session-create-description" }}
            />
            <TextField
              label="Tags (comma separated)"
              size="small"
              fullWidth
              value={createTags}
              onChange={(e) => setCreateTags(e.target.value)}
              inputProps={{ "data-testid": "chat-session-create-tags" }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={handleCreate} disabled={!createName.trim()} data-testid="chat-session-create-save">
            Create
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
