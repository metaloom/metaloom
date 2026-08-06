import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Box, Button, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle,
  IconButton, InputAdornment, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField,
  Tooltip, Typography,
} from "@mui/material";
import { Add, DeleteOutline, EditOutlined, LockOutlined, SearchOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import Title from "../../components/Title";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import {
  deleteMemoryEntry, listMemory, listMemoryScopes, loadMemoryEntry, MemoryEntrySummary,
  MemoryScopeInfo, saveMemoryEntry,
} from "../../api/memory";

interface EditorState {
  /** Empty while creating a new note. */
  originalId: string;
  id: string;
  title: string;
  body: string;
  version?: number;
}

/** "3 days ago" style age, matching the other management views. */
function relativeAge(iso?: string): string {
  if (!iso) return "";
  const ms = Date.now() - new Date(iso).getTime();
  const days = Math.floor(ms / 86_400_000);
  if (days > 0) return days === 1 ? "1 day ago" : `${days} days ago`;
  const hours = Math.floor(ms / 3_600_000);
  if (hours > 0) return hours === 1 ? "1 hour ago" : `${hours} hours ago`;
  const minutes = Math.floor(ms / 60_000);
  return minutes > 1 ? `${minutes} minutes ago` : "just now";
}

function humanSize(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`;
}

/**
 * Browse and edit the agent memory bank: the scoped markdown notes the chat agent reads and writes.
 *
 * Notes are addressed by a path-like id within a scope; the scope tabs come from the server, which
 * is also where the "can I write here" decision is made (shared scopes may be human-curated only).
 */
export default function MemoryView() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();

  const [scopes, setScopes] = useState<MemoryScopeInfo[]>([]);
  const [activeRef, setActiveRef] = useState<string>("");
  const [entries, setEntries] = useState<MemoryEntrySummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [saving, setSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<MemoryEntrySummary | null>(null);
  const [query, setQuery] = useState("");

  const activeScope = useMemo(
    () => scopes.find(s => s.ref === activeRef) ?? scopes[0],
    [scopes, activeRef],
  );

  // A local filter over the loaded scope. `listMemory` does accept a server-side `prefix`, but it
  // only matches the id — the box below also matches the title and the session, so it stays here.
  const filteredEntries = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return entries;
    return entries.filter(e =>
      e.id.toLowerCase().includes(q)
      || (e.title ?? "").toLowerCase().includes(q)
      || (e.sessionName ?? "").toLowerCase().includes(q)
    );
  }, [entries, query]);

  useEffect(() => {
    if (!token) return;
    listMemoryScopes(token)
      .then(res => {
        setScopes(res.scopes ?? []);
        setActiveRef(prev => prev || (res.scopes?.[0]?.ref ?? ""));
      })
      .catch(() => setScopes([]));
  }, [token]);

  const refresh = useCallback(() => {
    if (!token || !activeScope) return;
    setLoading(true);
    // Shared scopes are addressed by label, so `ref` is only sent when there is one.
    listMemory(token, activeScope.scope, activeScope.scope === "user" ? undefined : activeScope.label)
      .then(res => setEntries(res.entries ?? []))
      .catch(() => setEntries([]))
      .finally(() => setLoading(false));
  }, [token, activeScope]);

  useEffect(refresh, [refresh]);

  const scopeRef = activeScope && activeScope.scope !== "user" ? activeScope.label : undefined;

  const openEditor = async (entry?: MemoryEntrySummary) => {
    if (!token || !activeScope) return;
    if (!entry) {
      setEditor({ originalId: "", id: "", title: "", body: "" });
      return;
    }
    try {
      const detail = await loadMemoryEntry(token, activeScope.scope, entry.id, scopeRef);
      setEditor({ originalId: detail.id, id: detail.id, title: detail.title ?? "", body: detail.body, version: detail.version });
    } catch (e) {
      showToast(String(e), "error");
    }
  };

  const handleSave = async () => {
    if (!token || !activeScope || !editor) return;
    setSaving(true);
    try {
      await saveMemoryEntry(token, activeScope.scope, editor.id, { body: editor.body, title: editor.title || undefined }, scopeRef);
      // A rename writes a new id; drop the old note so the rename is not a silent copy.
      if (editor.originalId && editor.originalId !== editor.id) {
        await deleteMemoryEntry(token, activeScope.scope, editor.originalId, scopeRef);
      }
      setEditor(null);
      refresh();
    } catch (e) {
      showToast(String(e), "error");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!token || !activeScope || !deleteTarget) return;
    try {
      await deleteMemoryEntry(token, activeScope.scope, deleteTarget.id, scopeRef);
      setDeleteTarget(null);
      refresh();
    } catch (e) {
      showToast(String(e), "error");
    }
  };

  const writable = activeScope?.writable ?? false;

  return (
    <Box sx={{ p: 3 }} data-testid="memory-view">
      <Title>{t("memory.title", "Agent memory")}</Title>
      <Typography variant="body2" sx={{ color: tokens.text.tertiary, mb: 2 }}>
        {t("memory.subtitle",
          "Markdown notes the chat agent can read and write across conversations. The agent sees this as a read-only /memory folder.")}
      </Typography>

      {scopes.length === 0 && (
        <Typography variant="body2" data-testid="memory-empty-scopes">
          {t("memory.noScopes", "No memory scopes are available.")}
        </Typography>
      )}

      {scopes.length > 0 && (
        <>
          <Tabs value={activeScope?.ref ?? false} onChange={(_, v) => setActiveRef(v)} sx={{ mb: 2 }}>
            {scopes.map(scope => (
              <Tab
                key={scope.ref}
                value={scope.ref}
                data-testid={`memory-scope-tab-${scope.scope}`}
                label={
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    {scope.scope === "user" ? t("memory.myNotes", "My notes") : scope.label}
                    <Chip size="small" label={`${scope.count}/${scope.maxEntries}`} />
                    {!scope.writable && (
                      <Tooltip title={t("memory.readOnlyScope", "This shared scope is read-only for you.")}>
                        <LockOutlined fontSize="small" />
                      </Tooltip>
                    )}
                  </Box>
                }
              />
            ))}
          </Tabs>

          <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 2, mb: 1 }}>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
              {activeScope && `${humanSize(activeScope.bytes)} of ${humanSize(activeScope.maxBytes)} used`}
            </Typography>
            <TextField
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder={t("memory.search.placeholder")}
              size="small"
              data-testid="memory-search"
              sx={{ flex: 1, maxWidth: 320 }}
              InputProps={{
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                  </InputAdornment>
                ),
              }}
            />
            <Button startIcon={<Add />} disabled={!writable} onClick={() => openEditor()} data-testid="memory-new">
              {t("memory.new", "New note")}
            </Button>
          </Box>

          {loading ? <CircularProgress size={24} /> : (
            <Table size="small" data-testid="memory-table">
              <TableHead>
                <TableRow>
                  <TableCell>{t("memory.id", "Id")}</TableCell>
                  <TableCell>{t("memory.noteTitle", "Title")}</TableCell>
                  <TableCell>{t("memory.updated", "Updated")}</TableCell>
                  <TableCell>{t("memory.session", "Session")}</TableCell>
                  <TableCell>{t("memory.size", "Size")}</TableCell>
                  <TableCell align="right" />
                </TableRow>
              </TableHead>
              <TableBody>
                {filteredEntries.map(entry => (
                  <TableRow key={entry.id} data-testid={`memory-row-${entry.id}`}>
                    <TableCell><code>{entry.id}</code></TableCell>
                    <TableCell>{entry.title}</TableCell>
                    <TableCell>
                      <Tooltip title={entry.edited ?? ""}>
                        <span>{relativeAge(entry.edited)}{entry.editor ? ` by ${entry.editor}` : ""}</span>
                      </Tooltip>
                    </TableCell>
                    <TableCell>{entry.sessionName}</TableCell>
                    <TableCell>{humanSize(entry.size)}</TableCell>
                    <TableCell align="right">
                      <IconButton size="small" onClick={() => openEditor(entry)} data-testid={`memory-edit-${entry.id}`}>
                        <EditOutlined fontSize="small" />
                      </IconButton>
                      <IconButton size="small" disabled={!writable} onClick={() => setDeleteTarget(entry)}
                        data-testid={`memory-delete-${entry.id}`}>
                        <DeleteOutline fontSize="small" />
                      </IconButton>
                    </TableCell>
                  </TableRow>
                ))}
                {filteredEntries.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={6}>
                      {/* "Nothing here yet" and "nothing matched" are different facts — say
                          which one it is (LOOM_UI.md §7.5). */}
                      <Typography
                        variant="body2"
                        sx={{ color: tokens.text.tertiary }}
                        data-testid={entries.length === 0 ? "memory-empty" : "memory-no-match"}
                      >
                        {entries.length === 0
                          ? t("memory.empty", "No notes stored in this scope yet.")
                          : t("memory.emptyState.noSearch")}
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          )}
        </>
      )}

      <Dialog open={editor !== null} onClose={() => setEditor(null)} maxWidth="md" fullWidth>
        <DialogTitle>
          {editor?.originalId ? t("memory.edit", "Edit note") : t("memory.create", "New note")}
          {editor?.version ? <Chip size="small" sx={{ ml: 1 }} label={`v${editor.version}`} /> : null}
        </DialogTitle>
        <DialogContent>
          <TextField
            fullWidth margin="dense" label={t("memory.id", "Id")}
            helperText={t("memory.idHelp", "Lowercase path ending in .md, e.g. projects/loom-db.md")}
            value={editor?.id ?? ""}
            onChange={e => setEditor(prev => prev && { ...prev, id: e.target.value })}
            data-testid="memory-editor-id"
          />
          <TextField
            fullWidth margin="dense" label={t("memory.noteTitle", "Title")}
            value={editor?.title ?? ""}
            onChange={e => setEditor(prev => prev && { ...prev, title: e.target.value })}
            data-testid="memory-editor-title"
          />
          <TextField
            fullWidth multiline minRows={12} margin="dense" label={t("memory.body", "Body (markdown)")}
            value={editor?.body ?? ""}
            onChange={e => setEditor(prev => prev && { ...prev, body: e.target.value })}
            data-testid="memory-editor-body"
          />
          <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
            {t("memory.headerNote", "The provenance header (updated, session, version) is generated automatically.")}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditor(null)}>{t("common.cancel", "Cancel")}</Button>
          <Button variant="contained" disabled={saving || !editor?.id} onClick={handleSave} data-testid="memory-editor-save">
            {t("common.save", "Save")}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={deleteTarget !== null} onClose={() => setDeleteTarget(null)}>
        <DialogTitle>{t("memory.deleteTitle", "Delete note")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            {t("memory.deleteBody", "This permanently removes the note — there is no version history yet.")}
          </Typography>
          <Typography variant="body2" sx={{ mt: 1 }}><code>{deleteTarget?.id}</code></Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteTarget(null)}>{t("common.cancel", "Cancel")}</Button>
          <Button color="error" variant="contained" onClick={handleDelete} data-testid="memory-delete-confirm">
            {t("common.delete", "Delete")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
