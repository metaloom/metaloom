import React, { useCallback, useEffect, useState } from "react";
import {
  Box, Typography, Chip, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, IconButton, Drawer, Divider, Button,
  Dialog, DialogActions, DialogContent, DialogTitle, TextField, CircularProgress,
  FormControl, InputLabel, Select, MenuItem, Autocomplete, Avatar, AvatarGroup, Tooltip,
  InputAdornment,
} from "@mui/material";
import {
  TaskAltOutlined, SearchOutlined,
  CloseOutlined, CalendarTodayOutlined, FlagOutlined, AddOutlined,
  EditOutlined, DeleteOutlined, SendOutlined, ChatBubbleOutlineOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import EmptyState from "../../components/EmptyState";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import {
  assignTask, createTask, deleteTask, listTasks, TaskAssigneeResponse, TaskResponse,
  unassignTaskFromGroup, unassignTaskFromUser, updateTask,
} from "../../api/tasks";
import { listUsers } from "../../api/users";
import { listGroups } from "../../api/groups";
import {
  createTaskReaction, deleteTaskReaction, listTaskReactions,
  ReactionResponseItem, TaskReactionType,
} from "../../api/reactions";
import { CommentResponse, createCommentForTask, listCommentsForTask, updateComment, deleteComment } from "../../api/comments";
import { ReactionsPanel } from "../reactions/ReactionsPanel";
import { CommentItem } from "../assetDetail/CommentItem";
import { commentResponseToComment } from "../assetDetail/helpers";
import { threadComments } from "./commentThread";
import { useTranslation } from "react-i18next";
import { PAGE_SIZE } from "../../hooks/pagedList";

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

// ── Assignees ─────────────────────────────────────────────────────────────

/** A user or a group, flattened into one option list so a single control covers both. */
export interface AssigneeOption {
  uuid: string;
  name: string;
  kind: "USER" | "GROUP";
}

export function assigneeLabel(assignee: TaskAssigneeResponse): string {
  // The server resolves `name` onto the response; the uuid fallback only shows when the
  // referenced user or group has since been deleted.
  return assignee.name ?? assignee.userUuid ?? assignee.groupUuid ?? "?";
}

/** Group assignments are prefixed so a group named "alice" cannot be mistaken for a person. */
export function assigneeDisplay(assignee: TaskAssigneeResponse): string {
  const label = assigneeLabel(assignee);
  return assignee.groupUuid ? `@${label}` : label;
}

function AssigneeAvatars({ assignees, testId }: { assignees?: TaskAssigneeResponse[]; testId?: string }) {
  if (!assignees || assignees.length === 0) {
    return <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>—</Typography>;
  }
  return (
    <AvatarGroup
      max={4}
      data-testid={testId}
      sx={{ justifyContent: "flex-end", "& .MuiAvatar-root": { width: 22, height: 22, fontSize: "0.6rem" } }}
    >
      {assignees.map((a) => (
        <Tooltip key={a.userUuid ?? a.groupUuid} title={assigneeDisplay(a)}>
          <Avatar sx={{ bgcolor: a.groupUuid ? tokens.accent.blue : tokens.primary.dark }}>
            {assigneeLabel(a).charAt(0).toUpperCase()}
          </Avatar>
        </Tooltip>
      ))}
    </AvatarGroup>
  );
}

function AssigneeSelect({
  options, value, onChange, testId,
}: {
  options: AssigneeOption[];
  value: AssigneeOption[];
  onChange: (v: AssigneeOption[]) => void;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <Autocomplete
      multiple
      size="small"
      options={options}
      value={value}
      onChange={(_e, v) => onChange(v)}
      getOptionLabel={(o) => (o.kind === "GROUP" ? `@${o.name}` : o.name)}
      // Users and groups are separate uuid spaces, so identity has to include the kind.
      isOptionEqualToValue={(a, b) => a.uuid === b.uuid && a.kind === b.kind}
      groupBy={(o) => t(`tasks.assignees.group.${o.kind}`)}
      renderInput={(params) => (
        <TextField
          {...params}
          label={t("tasks.assignees.label")}
          inputProps={{ ...params.inputProps, "data-testid": testId }}
        />
      )}
    />
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
  const [replyTo, setReplyTo] = useState<CommentResponse | null>(null);
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
      const created = await createCommentForTask(token, taskUuid, { text, parentUuid: replyTo?.uuid });
      setComments((prev) => [created, ...prev]);
      setCommentInput("");
      setReplyTo(null);
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

          {/* Priority chip + assignees */}
          <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center" }}>
            <Chip
              icon={<FlagOutlined sx={{ fontSize: 13, ml: "6px !important" }} />}
              label={prio}
              size="small"
              data-testid="tasks-drawer-priority-chip"
              sx={{ bgcolor: `${pc}22`, color: pc, border: `1px solid ${pc}44`, fontWeight: 700 }}
            />
            {(task.assignees ?? []).map((a) => (
              <Chip
                key={a.userUuid ?? a.groupUuid}
                label={assigneeDisplay(a)}
                size="small"
                data-testid="tasks-drawer-assignee-chip"
                sx={{
                  bgcolor: tokens.bg.base,
                  color: tokens.text.secondary,
                  border: `1px solid ${tokens.border.default}`,
                }}
              />
            ))}
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
            {replyTo && (
              <Box
                data-testid="tasks-comment-reply-banner"
                sx={{ display: "flex", alignItems: "center", gap: 1, px: 1, py: 0.5, mb: 1,
                  borderLeft: `2px solid ${tokens.primary.main}`, bgcolor: tokens.bg.overlay }}
              >
                <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.72rem", flex: 1 }} noWrap>
                  {t("tasks.comments.replyingTo", { text: replyTo.text ?? "" })}
                </Typography>
                <IconButton size="small" data-testid="tasks-comment-reply-cancel" onClick={() => setReplyTo(null)} sx={{ p: 0.25 }}>
                  <CloseOutlined sx={{ fontSize: 14 }} />
                </IconButton>
              </Box>
            )}
            {comments.length === 0 ? (
              <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 3, gap: 1 }}>
                <ChatBubbleOutlineOutlined sx={{ fontSize: 28, color: tokens.text.tertiary }} />
                <Typography variant="body2" sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>
                  {t("tasks.comments.empty")}
                </Typography>
              </Box>
            ) : (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                {threadComments(comments).map(({ root, replies }) => (
                  <React.Fragment key={root.uuid}>
                    <CommentItem
                      comment={commentResponseToComment(root)}
                      highlighted={false}
                      currentUserUuid={userUuid}
                      token={token}
                      editing={editingCommentId === root.uuid}
                      onStartEdit={() => setEditingCommentId(root.uuid)}
                      onCancelEdit={() => setEditingCommentId(null)}
                      onEdit={handleEditComment}
                      onDelete={handleDeleteComment}
                      onReply={() => setReplyTo(root)}
                    />
                    {replies.map((r) => (
                      <CommentItem
                        key={r.uuid}
                        comment={commentResponseToComment(r)}
                        highlighted={false}
                        currentUserUuid={userUuid}
                        token={token}
                        editing={editingCommentId === r.uuid}
                        onStartEdit={() => setEditingCommentId(r.uuid)}
                        onCancelEdit={() => setEditingCommentId(null)}
                        onEdit={handleEditComment}
                        onDelete={handleDeleteComment}
                        isReply
                      />
                    ))}
                  </React.Fragment>
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
        <AssigneeAvatars assignees={task.assignees} testId="tasks-row-assignees" />
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
  const [query, setQuery] = useState("");
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
  const [assigneeOptions, setAssigneeOptions] = useState<AssigneeOption[]>([]);
  const [newAssignees, setNewAssignees] = useState<AssigneeOption[]>([]);
  const [editAssignees, setEditAssignees] = useState<AssigneeOption[]>([]);

  // Users and groups are loaded once for the picker. Failing to load them must not break
  // task management, so the control simply offers nothing.
  useEffect(() => {
    if (!token) return;
    void Promise.all([listUsers(token, { limit: PAGE_SIZE }), listGroups(token, { limit: PAGE_SIZE })])
      .then(([users, groups]) => {
        setAssigneeOptions([
          ...(users.data ?? []).map((u) => ({ uuid: u.uuid, name: u.username, kind: "USER" as const })),
          ...(groups.data ?? []).map((g) => ({ uuid: g.uuid, name: g.name, kind: "GROUP" as const })),
        ]);
      })
      .catch(() => setAssigneeOptions([]));
  }, [token]);

  const toOptions = useCallback(
    (assignees?: TaskAssigneeResponse[]): AssigneeOption[] =>
      (assignees ?? []).map((a) => ({
        uuid: (a.userUuid ?? a.groupUuid)!,
        name: assigneeLabel(a),
        kind: a.groupUuid ? ("GROUP" as const) : ("USER" as const),
      })),
    [],
  );

  const loadTaskList = useCallback(() => {
    if (!token) {
      setLoading(false);
      return Promise.resolve();
    }
    setLoading(true);
    return listTasks(token, { limit: PAGE_SIZE })
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
    setNewAssignees([]);
    setCreateOpen(true);
  };

  const openEditForSelected = () => {
    if (!selectedTask) return;
    setEditTitle(selectedTask.title ?? "");
    setEditDescription(selectedTask.description ?? "");
    setEditPriority(selectedTask.priority?.toUpperCase() ?? "MEDIUM");
    setEditAssignees(toOptions(selectedTask.assignees));
    setEditMode(true);
  };

  /**
   * Reconcile the picker's selection against what the task already has.
   *
   * The REST surface is deliberately additive plus explicit deletes rather than a
   * replace-all, so the diff has to happen here. Doing nothing when nothing changed
   * keeps an unrelated title edit from re-writing the assignment rows.
   */
  const syncAssignees = async (taskUuid: string, before: TaskAssigneeResponse[] | undefined, after: AssigneeOption[]) => {
    if (!token) return;
    const current = toOptions(before);
    const key = (o: AssigneeOption) => `${o.kind}:${o.uuid}`;
    const currentKeys = new Set(current.map(key));
    const nextKeys = new Set(after.map(key));

    const added = after.filter((o) => !currentKeys.has(key(o)));
    const removed = current.filter((o) => !nextKeys.has(key(o)));

    if (added.length > 0) {
      await assignTask(token, taskUuid, {
        userUuids: added.filter((o) => o.kind === "USER").map((o) => o.uuid),
        groupUuids: added.filter((o) => o.kind === "GROUP").map((o) => o.uuid),
      });
    }
    for (const o of removed) {
      if (o.kind === "USER") {
        await unassignTaskFromUser(token, taskUuid, o.uuid);
      } else {
        await unassignTaskFromGroup(token, taskUuid, o.uuid);
      }
    }
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
      // Assignment is a second call — a task has to exist before it can be assigned.
      await syncAssignees(created.uuid, [], newAssignees);
      // Refetch rather than splicing `created` in: it was rendered before the assignment
      // rows existed, so its `assignees` array is stale.
      await loadTaskList();
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
      await syncAssignees(selectedTask.uuid, selectedTask.assignees, editAssignees);
      const withAssignees = { ...updated, assignees: editAssignees.map((o) => ({
        [o.kind === "GROUP" ? "groupUuid" : "userUuid"]: o.uuid,
        name: o.name,
      })) as TaskAssigneeResponse[] };
      setTasks((prev) => prev.map((task) => (task.uuid === withAssignees.uuid ? withAssignees : task)));
      setSelectedTask(withAssignees);
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

  const filteredTasks = tasks.filter(task => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return (task.title ?? "").toLowerCase().includes(q)
      || (task.description ?? "").toLowerCase().includes(q);
  });

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("tasks.title")}</Typography>
          <Typography variant="caption" color="text.secondary">{tasks.length} {t("tasks.count")}</Typography>
        </Box>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t("tasks.search.placeholder")}
          size="small"
          data-testid="tasks-search"
          sx={{ flex: 1, maxWidth: 320 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
        />
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
        {tasks.length > 0 && (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t("tasks.table.task")}</TableCell>
                  <TableCell>{t("tasks.table.priority")}</TableCell>
                  <TableCell>{t("tasks.table.assignees")}</TableCell>
                  <TableCell>{t("tasks.table.created")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filteredTasks.map(t => <TaskRow key={t.uuid} task={t} onSelect={setSelectedTask} />)}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        {/* Filtered to nothing keeps the inline hint; the EmptyState below stays bound to
            "there are no tasks at all" — LOOM_UI.md §7.5. */}
        {tasks.length > 0 && filteredTasks.length === 0 && (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }} data-testid="tasks-no-match">
            <TaskAltOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">{t("tasks.emptyState.noSearch")}</Typography>
          </Box>
        )}
        {!loading && tasks.length === 0 && (
          <EmptyState
            icon={TaskAltOutlined}
            title={t("tasks.emptyState.title")}
            description={t("tasks.emptyState.description")}
            actionLabel={t("tasks.emptyState.action")}
            actionIcon={<AddOutlined sx={{ fontSize: 18 }} />}
            onAction={openCreateDialog}
            testId="tasks-empty-state"
          />
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
          <AssigneeSelect
            options={assigneeOptions}
            value={editAssignees}
            onChange={setEditAssignees}
            testId="tasks-edit-assignees-input"
          />
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
          <AssigneeSelect
            options={assigneeOptions}
            value={newAssignees}
            onChange={setNewAssignees}
            testId="tasks-assignees-input"
          />
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

