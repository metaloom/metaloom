import { useCallback, useEffect, useState } from "react";
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

function formatDate(value?: string | null): string {
  return value ? new Date(value).toLocaleString() : "—";
}

export default function ChatSessionsView() {
  const { token } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [tab, setTab] = useState<"mine" | "published">("mine");
  const [sessions, setSessions] = useState<ChatSessionResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ChatSessionResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createName, setCreateName] = useState("");
  const [createDescription, setCreateDescription] = useState("");
  const [createTags, setCreateTags] = useState("");

  const refresh = useCallback(() => {
    if (!token) return;
    setLoading(true);
    listChatSessions(token, tab)
      .then((res) => setSessions(res.data ?? []))
      .catch(() => setSessions([]))
      .finally(() => setLoading(false));
  }, [token, tab]);

  useEffect(refresh, [refresh]);

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
            {sessions.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6}>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
                    No sessions yet. A session is captured automatically the first time a chat is used.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              sessions.map((s) => (
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
