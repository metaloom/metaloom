import React, { useCallback, useEffect, useState } from "react";
import {
  Box, Typography, Chip, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, IconButton, Drawer, Divider, Button,
  Dialog, DialogActions, DialogContent, DialogTitle, TextField, CircularProgress,
  FormControl, InputLabel, Select, MenuItem,
} from "@mui/material";
import {
  TaskAltOutlined,
  CloseOutlined, CalendarTodayOutlined, FlagOutlined, AddOutlined,
  EditOutlined, DeleteOutlined, SendOutlined, ChatBubbleOutlineOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { createTask, deleteTask, listTasks, TaskResponse, updateTask } from "../../api/tasks";
import {
  createTaskReaction, deleteTaskReaction, listTaskReactions,
  ReactionResponseItem, TaskReactionType,
} from "../../api/reactions";
import { CommentResponse, createCommentForTask, listCommentsForTask, updateComment, deleteComment } from "../../api/comments";
import { ReactionsPanel } from "../reactions/ReactionsPanel";
import { CommentItem } from "../assetDetail/CommentItem";
import { commentResponseToComment } from "../assetDetail/helpers";
import { useTranslation } from "react-i18next";

const priorityColor: Record<string, string> = {
  CRITICAL: tokens.accent.red,
  HIGH: tokens.accent.amber,
  MEDIUM: tokens.accent.blue,
  LOW: tokens.text.tertiary,
};

const PRIORITIES = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;

// ── Priority selector (shared by create dialog + edit drawer) ─────────────
function PrioritySelect({ value, onChange, testId }: { value: string; onChange: (v: string) => void; testId: string }) {
  const { t } = useTranslation();
  return (
    <FormControl size="small" fullWidth>
      <InputLabel id={`${testId}-label`}>{t("tasks.form.priority")}</InputLabel>
      <Select
        labelId={`${testId}-label`}
        label={t("tasks.form.priority")}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        SelectDisplayProps={{ "data-testid": testId } as React.HTMLAttributes<HTMLDivElement>}
      >
        {PRIORITIES.map((p) => (
          <MenuItem key={p} value={p} data-testid={`${testId}-option-${p}`}>
            {t(`tasks.priority.${p}`)}
          </MenuItem>
        ))}
      </Select>
    </FormControl>
  );
}

// ── Task Detail Drawer ────────────────────────────────────────────────────
function TaskDetailDrawer({
  task,
  onClose,
  onStartEdit,
  onDelete,
}: {
  task: TaskResponse | null;
  onClose: () => void;
  onStartEdit: () => void;
  onDelete: () => void;
}) {
  // Hooks must run unconditionally and before any early return.
  const { t } = useTranslation();
  const { token, userUuid } = useAuth();
  const { showToast } = useToast();
  const [reactions, setReactions] = useState<ReactionResponseItem[]>([]);
  const [comments, setComments] = useState<CommentResponse[]>([]);
  const [commentInput, setCommentInput] = useState("");
  const [postingComment, setPostingComment] = useState(false);
  const [editingCommentId, setEditingCommentId] = useState<string | null>(null);

  const taskUuid = task?.uuid;
  useEffect(() => {
    if (!token || !taskUuid) {
      setReactions([]);
      return;
    }
    listTaskReactions(token, taskUuid)
      .then((resp) => setReactions(resp.data ?? []))
      .catch(() => setReactions([]));
  }, [token, taskUuid]);

  useEffect(() => {
    setComments(task?.comments ?? []);
    if (!token || !taskUuid) return;
    listCommentsForTask(token, taskUuid)
      .then((resp) => setComments(resp.data ?? []))
      .catch(() => { /* keep the embedded comments on failure */ });
  }, [token, taskUuid, task?.comments]);

  const handleAddReaction = async (type: TaskReactionType) => {
    if (!token || !taskUuid) return;
    const created = await createTaskReaction(token, taskUuid, { type });
    setReactions((prev) => [created, ...prev]);
  };

  const handleDeleteReaction = async (reactionUuid: string) => {
    if (!token || !taskUuid) return;
    await deleteTaskReaction(token, taskUuid, reactionUuid);
    setReactions((prev) => prev.filter((r) => r.uuid !== reactionUuid));
  };

  const handlePostComment = async () => {
    const text = commentInput.trim();
    if (!text || !token || !taskUuid || postingComment) return;
    setPostingComment(true);
    try {
      const created = await createCommentForTask(token, taskUuid, { text });
      setComments((prev) => [created, ...prev]);
      setCommentInput("");
    } catch {
      showToast(t("assetDetail.comment.postError"), "error");
    } finally {
      setPostingComment(false);
    }
  };

  const handleEditComment = async (commentId: string, text: string) => {
    const trimmed = text.trim();
    if (!trimmed || !token) return;
    try {
      const updated = await updateComment(token, commentId, { text: trimmed });
      setComments((prev) => prev.map((c) => (c.uuid === commentId ? updated : c)));
      setEditingCommentId(null);
    } catch {
      showToast(t("assetDetail.comment.editError"), "error");
    }
  };

  const handleDeleteComment = async (commentId: string) => {
    if (!token) return;
    try {
      await deleteComment(token, commentId);
      setComments((prev) => prev.filter((c) => c.uuid !== commentId));
    } catch {
      showToast(t("assetDetail.comment.deleteError"), "error");
    }
  };

  if (!task) return null;
  const prio = task.priority?.toUpperCase() ?? "MEDIUM";
  const pc = priorityColor[prio] ?? tokens.text.tertiary;

  return (
    <Drawer
      anchor="right"
      open={!!task}
      onClose={onClose}
      PaperProps={{
        sx: {
          width: 420,
          bgcolor: tokens.bg.surface,
          border: `1px solid ${tokens.border.default}`,
          backgroundImage: "none",
        },
      }}
    >
      <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
        {/* Header */}
        <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          <Box sx={{ width: 4, height: 20, borderRadius: 2, bgcolor: pc, flexShrink: 0 }} />
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "0.95rem", flex: 1 }}>{t("tasks.drawer.title")}</Typography>
          <IconButton size="small" onClick={onStartEdit} data-testid="tasks-edit-button" aria-label={t("tasks.button.edit")}> 
            <EditOutlined sx={{ fontSize: 16 }} />
          </IconButton>
          <IconButton size="small" onClick={onDelete} data-testid="tasks-delete-button" aria-label={t("tasks.button.delete")}> 
            <DeleteOutlined sx={{ fontSize: 16 }} />
          </IconButton>
          <IconButton size="small" onClick={onClose} aria-label={t("tasks.button.close")}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </Box>

        <Box sx={{ flex: 1, overflow: "auto", p: 2.5, display: "flex", flexDirection: "column", gap: 2.5 }}>
          {/* Title + Description */}
          <Box>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", lineHeight: 1.4, mb: 0.75 }}>{task.title}</Typography>
            {task.description && (
              <Typography variant="body2" sx={{ color: tokens.text.secondary, lineHeight: 1.65 }}>{task.description}</Typography>
            )}
          </Box>

          {/* Priority chip */}
          <Box sx={{ display: "flex", gap: 1 }}>
            <Chip
              icon={<FlagOutlined sx={{ fontSize: 13, ml: "6px !important" }} />}
              label={prio}
              size="small"
              data-testid="tasks-drawer-priority-chip"
              sx={{ bgcolor: `${pc}22`, color: pc, border: `1px solid ${pc}44`, fontWeight: 700 }}
            />
          </Box>

          <Divider sx={{ borderColor: tokens.border.subtle }} />

          {/* Meta grid */}
          <Box sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
            {[
              {
                icon: <CalendarTodayOutlined sx={{ fontSize: 14 }} />,
                label: t("tasks.drawer.created"),
                content: <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>
                  {task.status?.created ? new Date(task.status.created).toLocaleDateString() : "—"}
                </Typography>,
              },
              {
                icon: <CalendarTodayOutlined sx={{ fontSize: 14 }} />,
                label: t("tasks.drawer.lastEdited"),
                content: <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>
                  {task.status?.edited ? new Date(task.status.edited).toLocaleDateString() : "—"}
                </Typography>,
              },
            ].map(({ icon, label, content }, idx) => (
              <Box key={label} sx={{ display: "grid", gridTemplateColumns: "130px 1fr", px: 1.5, py: 0.9, borderBottom: idx < 1 ? `1px solid ${tokens.border.subtle}` : "none", bgcolor: idx % 2 === 0 ? "transparent" : "rgba(255,255,255,0.02)", alignItems: "center" }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, color: tokens.text.tertiary }}>
                  {icon}
                  <Typography sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>{label}</Typography>
                </Box>
                {content}
              </Box>
            ))}
          </Box>

          <Divider sx={{ borderColor: tokens.border.subtle }} />

          {/* Reactions */}
          <ReactionsPanel
            reactions={reactions}
            currentUserUuid={userUuid}
            onAdd={handleAddReaction}
            onDelete={handleDeleteReaction}
            testIdPrefix="tasks"
          />

          <Divider sx={{ borderColor: tokens.border.subtle }} />

          {/* Comments */}
          <Box>
            <Typography sx={{ color: tokens.text.tertiary, fontSize: "0.8rem", fontWeight: 600, mb: 1 }}>
              {t("tasks.comments.title")}
            </Typography>
            <Box sx={{ display: "flex", alignItems: "flex-end", gap: 0.75, mb: 1 }}>
              <TextField
                fullWidth
                multiline
                maxRows={4}
                size="small"
                value={commentInput}
                onChange={(e) => setCommentInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    handlePostComment();
                  }
                }}
                placeholder={t("tasks.comments.addPlaceholder")}
                disabled={!token || postingComment}
                inputProps={{ "aria-label": t("tasks.comments.addPlaceholder"), "data-testid": "tasks-comment-input" }}
              />
              <IconButton
                aria-label={t("tasks.comments.post")}
                data-testid="tasks-comment-post"
                color="primary"
                disabled={!commentInput.trim() || !token || postingComment}
                onClick={handlePostComment}
              >
                {postingComment ? <CircularProgress size={18} /> : <SendOutlined fontSize="small" />}
              </IconButton>
            </Box>
            {comments.length === 0 ? (
              <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 3, gap: 1 }}>
                <ChatBubbleOutlineOutlined sx={{ fontSize: 28, color: tokens.text.tertiary }} />
                <Typography variant="body2" sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>
                  {t("tasks.comments.empty")}
                </Typography>
              </Box>
            ) : (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                {comments.map((c) => (
                  <CommentItem
                    key={c.uuid}
                    comment={commentResponseToComment(c)}
                    highlighted={false}
                    currentUserUuid={userUuid}
                    token={token}
                    editing={editingCommentId === c.uuid}
                    onStartEdit={() => setEditingCommentId(c.uuid)}
                    onCancelEdit={() => setEditingCommentId(null)}
                    onEdit={handleEditComment}
                    onDelete={handleDeleteComment}
                  />
                ))}
              </Box>
            )}
          </Box>
        </Box>
      </Box>
    </Drawer>
  );
}

// ── Task Row ──────────────────────────────────────────────────────────────
function TaskRow({ task, onSelect }: { task: TaskResponse; onSelect: (t: TaskResponse) => void }) {
  const prio = task.priority?.toUpperCase() ?? "MEDIUM";
  const pc = priorityColor[prio] ?? tokens.text.tertiary;

  return (
    <TableRow hover onClick={() => onSelect(task)} sx={{ cursor: "pointer" }}>
      <TableCell sx={{ pl: 2 }}>
        <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1 }}>
          <Box sx={{ width: 3, height: 36, bgcolor: pc, borderRadius: 2, flexShrink: 0, mt: 0.25 }} />
          <Box>
            <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem", color: tokens.text.primary }}>
              {task.title}
            </Typography>
            {task.description && (
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
                {task.description.length > 60 ? `${task.description.slice(0, 60)}…` : task.description}
              </Typography>
            )}
          </Box>
        </Box>
      </TableCell>
      <TableCell>
        <Chip label={prio} size="small" data-testid="tasks-row-priority-chip" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${pc}22`, color: pc, fontWeight: 700 }} />
      </TableCell>
      <TableCell>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {task.status?.created ? new Date(task.status.created).toLocaleDateString() : "—"}
        </Typography>
      </TableCell>
    </TableRow>
  );
}

export default function TasksView() {
  const { token } = useAuth();
  const { t } = useTranslation();
  const [tasks, setTasks] = useState<TaskResponse[]>([]);
  const [selectedTask, setSelectedTask] = useState<TaskResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [editMode, setEditMode] = useState(false);
  const [saving, setSaving] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [newPriority, setNewPriority] = useState("MEDIUM");
  const [editTitle, setEditTitle] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [editPriority, setEditPriority] = useState("MEDIUM");

  const loadTaskList = useCallback(() => {
    if (!token) {
      setLoading(false);
      return Promise.resolve();
    }
    setLoading(true);
    return listTasks(token)
      .then((res) => setTasks(res.data ?? []))
      .catch(() => setTasks([]))
      .finally(() => setLoading(false));
  }, [token]);

  useEffect(() => {
    void loadTaskList();
  }, [loadTaskList]);

  const openCreateDialog = () => {
    setNewTitle("");
    setNewDescription("");
    setNewPriority("MEDIUM");
    setCreateOpen(true);
  };

  const openEditForSelected = () => {
    if (!selectedTask) return;
    setEditTitle(selectedTask.title ?? "");
    setEditDescription(selectedTask.description ?? "");
    setEditPriority(selectedTask.priority?.toUpperCase() ?? "MEDIUM");
    setEditMode(true);
  };

  const closeDrawer = () => {
    setEditMode(false);
    setSelectedTask(null);
  };

  const handleCreateTask = async () => {
    if (!token || !newTitle.trim()) return;
    setSaving(true);
    try {
      const created = await createTask(token, {
        title: newTitle.trim(),
        description: newDescription.trim() || undefined,
        priority: newPriority,
      });
      setTasks((prev) => [created, ...prev]);
      setCreateOpen(false);
    } finally {
      setSaving(false);
    }
  };

  const handleSaveEdit = async () => {
    if (!token || !selectedTask || !editTitle.trim()) return;
    setSaving(true);
    try {
      const updated = await updateTask(token, selectedTask.uuid, {
        title: editTitle.trim(),
        description: editDescription.trim() || undefined,
        priority: editPriority,
      });
      setTasks((prev) => prev.map((task) => (task.uuid === updated.uuid ? updated : task)));
      setSelectedTask(updated);
      setEditMode(false);
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteTask = async () => {
    if (!token || !selectedTask) return;
    setSaving(true);
    try {
      await deleteTask(token, selectedTask.uuid);
      setTasks((prev) => prev.filter((task) => task.uuid !== selectedTask.uuid));
      setDeleteOpen(false);
      closeDrawer();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("tasks.title")}</Typography>
          <Typography variant="caption" color="text.secondary">{tasks.length} {t("tasks.count")}</Typography>
        </Box>
        <Button
          size="small"
          variant="contained"
          startIcon={<AddOutlined sx={{ fontSize: 14 }} />}
          onClick={openCreateDialog}
          data-testid="tasks-create-button"
        >
          {t("tasks.button.new")}
        </Button>
      </Box>

      <Box sx={{ flex: 1, overflow: "auto" }}>
        {loading && (
          <Box sx={{ display: "flex", justifyContent: "center", py: 4 }}>
            <CircularProgress size={22} />
          </Box>
        )}
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t("tasks.table.task")}</TableCell>
                <TableCell>{t("tasks.table.priority")}</TableCell>
                <TableCell>{t("tasks.table.created")}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {tasks.map(t => <TaskRow key={t.uuid} task={t} onSelect={setSelectedTask} />)}
            </TableBody>
          </Table>
        </TableContainer>
        {!loading && tasks.length === 0 && (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
            <TaskAltOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">{t("tasks.empty")}</Typography>
          </Box>
        )}
      </Box>

      {selectedTask && editMode && (
        <Drawer
          anchor="right"
          open={editMode}
          onClose={() => setEditMode(false)}
          PaperProps={{
            sx: {
              width: 420,
              bgcolor: tokens.bg.surface,
              border: `1px solid ${tokens.border.default}`,
              backgroundImage: "none",
              p: 2,
              display: "flex",
              flexDirection: "column",
              gap: 2,
            },
          }}
        >
          <Typography variant="h6" sx={{ fontSize: "1rem", fontWeight: 700 }}>{t("tasks.dialog.editTitle")}</Typography>
          <TextField
            label={t("tasks.form.title")}
            size="small"
            value={editTitle}
            inputProps={{ "data-testid": "tasks-edit-title-input" }}
            onChange={(e) => setEditTitle(e.target.value)}
            fullWidth
          />
          <TextField
            label={t("tasks.form.description")}
            size="small"
            value={editDescription}
            inputProps={{ "data-testid": "tasks-edit-description-input" }}
            onChange={(e) => setEditDescription(e.target.value)}
            fullWidth
            multiline
            minRows={3}
          />
          <PrioritySelect value={editPriority} onChange={setEditPriority} testId="tasks-edit-priority-select" />
          <Box sx={{ display: "flex", justifyContent: "flex-end", gap: 1 }}>
            <Button size="small" onClick={() => setEditMode(false)}>{t("tasks.button.cancel")}</Button>
            <Button size="small" variant="contained" onClick={handleSaveEdit} disabled={saving || !editTitle.trim()} data-testid="tasks-save-button">
              {t("tasks.button.save")}
            </Button>
          </Box>
        </Drawer>
      )}

      {!editMode && (
        <TaskDetailDrawer
          task={selectedTask}
          onClose={closeDrawer}
          onStartEdit={openEditForSelected}
          onDelete={() => setDeleteOpen(true)}
        />
      )}

      <Dialog open={createOpen} onClose={() => setCreateOpen(false)}>
        <DialogTitle>{t("tasks.dialog.newTitle")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, minWidth: 360, pt: "8px !important" }}>
          <TextField
            label={t("tasks.form.title")}
            size="small"
            value={newTitle}
            inputProps={{ "data-testid": "tasks-title-input" }}
            onChange={(e) => setNewTitle(e.target.value)}
            fullWidth
          />
          <TextField
            label={t("tasks.form.description")}
            size="small"
            value={newDescription}
            inputProps={{ "data-testid": "tasks-description-input" }}
            onChange={(e) => setNewDescription(e.target.value)}
            fullWidth
            multiline
            minRows={3}
          />
          <PrioritySelect value={newPriority} onChange={setNewPriority} testId="tasks-priority-select" />
        </DialogContent>
        <DialogActions>
          <Button size="small" onClick={() => setCreateOpen(false)}>{t("tasks.button.cancel")}</Button>
          <Button size="small" variant="contained" onClick={handleCreateTask} disabled={saving || !newTitle.trim()} data-testid="tasks-create-submit-button">
            {t("tasks.button.create")}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={deleteOpen} onClose={() => setDeleteOpen(false)}>
        <DialogTitle>{t("tasks.dialog.deleteTitle")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2">{t("tasks.confirm.delete", { name: selectedTask?.title ?? "" })}</Typography>
        </DialogContent>
        <DialogActions>
          <Button size="small" onClick={() => setDeleteOpen(false)}>{t("tasks.button.cancel")}</Button>
          <Button
            size="small"
            variant="contained"
            onClick={handleDeleteTask}
            data-testid="tasks-delete-confirm-button"
            sx={{ bgcolor: tokens.accent.red, "&:hover": { bgcolor: tokens.accent.red } }}
          >
            {t("tasks.button.delete")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

