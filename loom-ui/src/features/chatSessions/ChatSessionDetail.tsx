import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Divider,
  FormControlLabel,
  IconButton,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import ArrowBack from "@mui/icons-material/ArrowBack";
import DeleteOutline from "@mui/icons-material/DeleteOutline";
import Title from "../../components/Title";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import {
  ChatSessionContextRef,
  ChatSessionResponse,
  SessionFileEntry,
  deleteChatSession,
  listChatSessions,
  listSessionFiles,
  loadChatSession,
  publishChatSession,
  replaceChatSessionContext,
  updateChatSession,
} from "../../api/chatSessions";

function formatDate(value?: string | null): string {
  return value ? new Date(value).toLocaleString() : "—";
}

export default function ChatSessionDetail() {
  const { id } = useParams<{ id: string }>();
  const { token } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [session, setSession] = useState<ChatSessionResponse | null>(null);
  const [library, setLibrary] = useState<ChatSessionResponse[]>([]);
  const [loading, setLoading] = useState(true);

  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [tags, setTags] = useState("");
  const [refs, setRefs] = useState<ChatSessionContextRef[]>([]);
  const [files, setFiles] = useState<SessionFileEntry[]>([]);
  const [filesUnavailable, setFilesUnavailable] = useState(false);

  const load = useCallback(() => {
    if (!id || !token) return;
    setLoading(true);
    Promise.all([loadChatSession(token, id), listChatSessions(token, "published")])
      .then(([s, lib]) => {
        setSession(s);
        setName(s.name ?? "");
        setDescription(s.description ?? "");
        setTags((s.tags ?? []).join(", "));
        setRefs(s.contextRefs ?? []);
        setLibrary((lib.data ?? []).filter((x) => x.uuid !== s.uuid));

        // Load the live coding-session workspace files (Phase-2 proxy). The proxy 404s when no
        // runner is live — surface that as "unavailable" rather than an error.
        setFiles([]);
        setFilesUnavailable(false);
        if (s.chatUuid) {
          listSessionFiles(token, s.chatUuid)
            .then((res) => setFiles(res.entries ?? []))
            .catch(() => setFilesUnavailable(true));
        } else {
          setFilesUnavailable(true);
        }
      })
      .catch(() => setSession(null))
      .finally(() => setLoading(false));
  }, [id, token]);

  useEffect(load, [load]);

  const refByUuid = useMemo(() => {
    const map = new Map<string, ChatSessionContextRef>();
    refs.forEach((r) => map.set(r.sourceSessionUuid, r));
    return map;
  }, [refs]);

  const setPart = (sourceUuid: string, part: "includeChatHistory" | "includeSkills" | "includeFilesystem", value: boolean) => {
    setRefs((prev) => {
      const existing = prev.find((r) => r.sourceSessionUuid === sourceUuid);
      const next: ChatSessionContextRef = existing
        ? { ...existing, [part]: value }
        : {
            sourceSessionUuid: sourceUuid,
            includeChatHistory: false,
            includeSkills: false,
            includeFilesystem: false,
            ordinal: prev.length,
            [part]: value,
          };
      const others = prev.filter((r) => r.sourceSessionUuid !== sourceUuid);
      // Drop the ref entirely when no part is selected.
      if (!next.includeChatHistory && !next.includeSkills && !next.includeFilesystem) {
        return others;
      }
      return [...others, next];
    });
  };

  const handleSaveDetails = async () => {
    if (!token || !id) return;
    try {
      await updateChatSession(token, id, {
        name,
        description,
        tags: tags.split(",").map((t) => t.trim()).filter(Boolean),
      });
      showToast("Session saved");
      load();
    } catch (e) {
      console.error("Failed to save session", e);
      showToast("Failed to save session", "error");
    }
  };

  const handleSaveContext = async () => {
    if (!token || !id) return;
    try {
      await replaceChatSessionContext(token, id, { refs });
      showToast("Context updated");
      load();
    } catch (e) {
      console.error("Failed to save context", e);
      showToast("Failed to save context", "error");
    }
  };

  const handleTogglePublish = async () => {
    if (!token || !id || !session) return;
    try {
      await publishChatSession(token, id, !session.published);
      showToast(session.published ? "Session unpublished" : "Session published");
      load();
    } catch (e) {
      console.error("Failed to toggle publish", e);
      showToast("Failed to change publish state", "error");
    }
  };

  const handleDelete = async () => {
    if (!token || !id) return;
    try {
      await deleteChatSession(token, id);
      showToast("Session deleted");
      navigate("/chat/sessions");
    } catch (e) {
      console.error("Failed to delete session", e);
      showToast("Failed to delete session", "error");
    }
  };

  if (loading) return <Box sx={{ p: 3 }}><CircularProgress size={20} /></Box>;
  if (!session) return <Box sx={{ p: 3 }}><Typography>Session not found.</Typography></Box>;

  const owned = !!session.status?.creator; // detail is only editable for owned sessions; server enforces this.

  return (
    <Box sx={{ p: 3, maxWidth: 900 }}>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }}>
        <IconButton size="small" onClick={() => navigate("/chat/sessions")}>
          <ArrowBack fontSize="small" />
        </IconButton>
        <Title>{session.name}</Title>
        {session.published && <Chip label="Published" size="small" color="primary" />}
      </Stack>
      <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
        Created {formatDate(session.status?.created)} · Edited {formatDate(session.status?.edited)}
        {session.hasFilesystem ? ` · Filesystem ${Math.round((session.fsSize ?? 0) / 1024)} KB` : " · No filesystem"}
      </Typography>

      {/* Details */}
      <Paper variant="outlined" sx={{ p: 2, mt: 2 }}>
        <Typography variant="subtitle2" gutterBottom>Details</Typography>
        <Stack spacing={2}>
          <TextField label="Name" size="small" value={name} onChange={(e) => setName(e.target.value)} fullWidth inputProps={{ "data-testid": "chat-session-name-input" }} />
          <TextField label="Description" size="small" value={description} onChange={(e) => setDescription(e.target.value)} fullWidth multiline minRows={2} inputProps={{ "data-testid": "chat-session-description-input" }} />
          <TextField label="Tags (comma separated)" size="small" value={tags} onChange={(e) => setTags(e.target.value)} fullWidth inputProps={{ "data-testid": "chat-session-tags-input" }} />
          <Stack direction="row" spacing={1}>
            <Button variant="contained" size="small" onClick={handleSaveDetails} data-testid="chat-session-save">Save</Button>
            <Button variant="outlined" size="small" onClick={handleTogglePublish} data-testid="chat-session-publish-toggle">
              {session.published ? "Unpublish" : "Publish"}
            </Button>
            <Box sx={{ flex: 1 }} />
            <Tooltip title="Delete session">
              <IconButton size="small" color="error" onClick={handleDelete} data-testid="chat-session-delete">
                <DeleteOutline fontSize="small" />
              </IconButton>
            </Tooltip>
          </Stack>
        </Stack>
      </Paper>

      {/* Pinned skills */}
      <Paper variant="outlined" sx={{ p: 2, mt: 2 }}>
        <Typography variant="subtitle2" gutterBottom>Active skill versions</Typography>
        {(session.skills ?? []).length === 0 ? (
          <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>No skills were active for this session.</Typography>
        ) : (
          <Stack direction="row" spacing={1} flexWrap="wrap">
            {(session.skills ?? []).map((sk) => (
              <Chip key={sk.skillUuid} label={`${sk.skillUuid.slice(0, 8)} @ v${sk.skillVersion}`} size="small" />
            ))}
          </Stack>
        )}
      </Paper>

      {/* Session files */}
      <Paper variant="outlined" sx={{ p: 2, mt: 2 }} data-testid="session-files-panel">
        <Typography variant="subtitle2" gutterBottom>Files</Typography>
        {files.length > 0 ? (
          <Stack spacing={0.5} data-testid="session-files-list">
            {files.map((f) => (
              <Stack key={f.name} direction="row" spacing={1} alignItems="center" data-testid={`session-file-${f.name}`}>
                <Typography variant="body2">{f.type === "dir" ? `${f.name}/` : f.name}</Typography>
                {f.type === "file" && (
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
                    {Math.max(1, Math.round(f.size / 1024))} KB
                  </Typography>
                )}
              </Stack>
            ))}
          </Stack>
        ) : (
          <Typography variant="caption" sx={{ color: tokens.text.tertiary }} data-testid="session-files-empty">
            {filesUnavailable
              ? "No live coding session — files are only available while the session's sandbox is running."
              : "No files in the session workspace."}
          </Typography>
        )}
      </Paper>

      {/* Context editor */}
      <Paper variant="outlined" sx={{ p: 2, mt: 2 }}>
        <Typography variant="subtitle2" gutterBottom>Context — reuse other published sessions</Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
          Select which parts of other published sessions feed this session's context.
        </Typography>
        <Divider sx={{ my: 1 }} />
        {library.length === 0 ? (
          <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>No published sessions available.</Typography>
        ) : (
          library.map((lib) => {
            const ref = refByUuid.get(lib.uuid);
            return (
              <Box key={lib.uuid} sx={{ py: 1 }}>
                <Typography variant="body2">{lib.name}</Typography>
                <Stack direction="row" spacing={2}>
                  <FormControlLabel
                    control={<Checkbox size="small" checked={!!ref?.includeChatHistory} onChange={(e) => setPart(lib.uuid, "includeChatHistory", e.target.checked)} data-testid={`chat-session-ctx-${lib.uuid}-history`} />}
                    label="Chat history"
                  />
                  <FormControlLabel
                    control={<Checkbox size="small" checked={!!ref?.includeSkills} onChange={(e) => setPart(lib.uuid, "includeSkills", e.target.checked)} data-testid={`chat-session-ctx-${lib.uuid}-skills`} />}
                    label="Skills"
                  />
                  <FormControlLabel
                    control={<Checkbox size="small" checked={!!ref?.includeFilesystem} onChange={(e) => setPart(lib.uuid, "includeFilesystem", e.target.checked)} data-testid={`chat-session-ctx-${lib.uuid}-filesystem`} />}
                    label="Filesystem"
                  />
                </Stack>
              </Box>
            );
          })
        )}
        {owned && (
          <Button variant="contained" size="small" sx={{ mt: 1 }} onClick={handleSaveContext} data-testid="chat-session-ctx-save">
            Save context
          </Button>
        )}
      </Paper>
    </Box>
  );
}
