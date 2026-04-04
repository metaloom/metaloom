import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, Paper, Chip, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Avatar, Tooltip, IconButton, Tabs, Tab,
  LinearProgress, Drawer, Divider,
} from "@mui/material";
import {
  TaskAltOutlined, RadioButtonUncheckedOutlined, PendingOutlined,
  BlockOutlined, RateReviewOutlined, PlayCircleOutline, ImageOutlined,
  CloseOutlined, PersonOutlineOutlined, CalendarTodayOutlined, FlagOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Task, Asset } from "../../types";
import { mockTaskService, mockAssetService } from "../../mock/services";
import { useProject } from "../../context/ProjectContext";
import { USERS, ASSETS } from "../../mock/data";

const priorityColor: Record<string, string> = {
  critical: tokens.accent.red,
  high: tokens.accent.amber,
  medium: tokens.accent.blue,
  low: tokens.text.tertiary,
};

const statusConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  open: { label: "Open", color: tokens.accent.blue, icon: <RadioButtonUncheckedOutlined sx={{ fontSize: 13 }} /> },
  in_progress: { label: "In Progress", color: tokens.accent.amber, icon: <PendingOutlined sx={{ fontSize: 13 }} /> },
  review: { label: "Review", color: tokens.primary.main, icon: <RateReviewOutlined sx={{ fontSize: 13 }} /> },
  done: { label: "Done", color: tokens.accent.green, icon: <TaskAltOutlined sx={{ fontSize: 13 }} /> },
  blocked: { label: "Blocked", color: tokens.accent.red, icon: <BlockOutlined sx={{ fontSize: 13 }} /> },
};

// ── Task Detail Drawer ────────────────────────────────────────────────────
function TaskDetailDrawer({ task, onClose }: { task: Task | null; onClose: () => void }) {
  const navigate = useNavigate();
  if (!task) return null;
  const sc = statusConfig[task.status] ?? statusConfig.open;
  const pc = priorityColor[task.priority] ?? tokens.text.tertiary;
  const assignee = USERS.find(u => u.id === task.assigneeId);
  const asset = task.assetId ? ASSETS.find(a => a.id === task.assetId) : null;

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
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "0.95rem", flex: 1 }}>Task Detail</Typography>
          <IconButton size="small" onClick={onClose}><CloseOutlined sx={{ fontSize: 16 }} /></IconButton>
        </Box>

        <Box sx={{ flex: 1, overflow: "auto", p: 2.5, display: "flex", flexDirection: "column", gap: 2.5 }}>
          {/* Title */}
          <Box>
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", lineHeight: 1.4, mb: 0.75 }}>{task.title}</Typography>
            <Typography variant="body2" sx={{ color: tokens.text.secondary, lineHeight: 1.65 }}>{task.description}</Typography>
          </Box>

          {/* Status + Priority row */}
          <Box sx={{ display: "flex", gap: 1 }}>
            <Chip
              icon={<Box sx={{ color: sc.color, display: "flex", ml: "6px !important" }}>{sc.icon}</Box>}
              label={sc.label}
              size="small"
              sx={{ bgcolor: `${sc.color}22`, color: sc.color, border: `1px solid ${sc.color}44`, fontWeight: 600 }}
            />
            <Chip
              label={task.priority}
              size="small"
              sx={{ bgcolor: `${pc}22`, color: pc, border: `1px solid ${pc}44`, fontWeight: 700 }}
            />
          </Box>

          <Divider sx={{ borderColor: tokens.border.subtle }} />

          {/* Meta grid */}
          <Box sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
            {[
              {
                icon: <PersonOutlineOutlined sx={{ fontSize: 14 }} />,
                label: "Assignee",
                content: assignee ? (
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    <Avatar sx={{ width: 20, height: 20, fontSize: "0.55rem", bgcolor: tokens.primary.dark }}>
                      {assignee.name.split(" ").map(n => n[0]).join("")}
                    </Avatar>
                    <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>{assignee.name}</Typography>
                  </Box>
                ) : <Typography sx={{ fontSize: "0.82rem", color: tokens.text.tertiary }}>Unassigned</Typography>,
              },
              {
                icon: <CalendarTodayOutlined sx={{ fontSize: 14 }} />,
                label: "Due Date",
                content: <Typography sx={{ fontSize: "0.82rem", color: task.dueDate && new Date(task.dueDate) < new Date() ? tokens.accent.red : tokens.text.secondary }}>
                  {task.dueDate ? new Date(task.dueDate).toLocaleDateString() : "—"}
                </Typography>,
              },
              {
                icon: <CalendarTodayOutlined sx={{ fontSize: 14 }} />,
                label: "Created",
                content: <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>{new Date(task.createdAt).toLocaleDateString()}</Typography>,
              },
            ].map(({ icon, label, content }, idx) => (
              <Box key={label} sx={{ display: "grid", gridTemplateColumns: "130px 1fr", px: 1.5, py: 0.9, borderBottom: idx < 2 ? `1px solid ${tokens.border.subtle}` : "none", bgcolor: idx % 2 === 0 ? "transparent" : "rgba(255,255,255,0.02)", alignItems: "center" }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, color: tokens.text.tertiary }}>
                  {icon}
                  <Typography sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>{label}</Typography>
                </Box>
                {content}
              </Box>
            ))}
          </Box>

          {/* Linked asset */}
          {asset && (
            <Box>
              <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.tertiary, fontSize: "0.68rem", display: "block", mb: 0.75 }}>Linked Asset</Typography>
              <Box
                onClick={() => navigate(`/assets/${asset.id}`)}
                sx={{
                  display: "flex", alignItems: "center", gap: 1.25, p: 1.25,
                  borderRadius: tokens.radius.md, border: `1px solid ${tokens.border.subtle}`,
                  bgcolor: tokens.bg.elevated, cursor: "pointer",
                  "&:hover": { borderColor: tokens.primary.main, bgcolor: tokens.primary.subtle },
                  transition: "all 140ms ease",
                }}
              >
                <Box sx={{ width: 48, height: 30, borderRadius: tokens.radius.sm, overflow: "hidden", flexShrink: 0 }}>
                  <img src={asset.thumbnailUrl} alt={asset.name} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
                </Box>
                <Box sx={{ flex: 1, overflow: "hidden" }}>
                  <Typography variant="caption" fontWeight={600} noWrap sx={{ fontSize: "0.78rem", color: tokens.text.primary, display: "block" }}>{asset.name}</Typography>
                  <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>{asset.type}</Typography>
                </Box>
              </Box>
            </Box>
          )}

          {/* Tags */}
          {task.tags.length > 0 && (
            <Box>
              <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.tertiary, fontSize: "0.68rem", display: "block", mb: 0.75 }}>Tags</Typography>
              <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                {task.tags.map(t => <Chip key={t} label={t} size="small" sx={{ height: 20, fontSize: "0.7rem", bgcolor: tokens.bg.elevated }} />)}
              </Box>
            </Box>
          )}
        </Box>
      </Box>
    </Drawer>
  );
}

// ── Task Row ──────────────────────────────────────────────────────────────
function TaskRow({ task, onSelect }: { task: Task; onSelect: (t: Task) => void }) {
  const navigate = useNavigate();
  const sc = statusConfig[task.status] ?? statusConfig.open;
  const pc = priorityColor[task.priority] ?? tokens.text.tertiary;
  const assignee = USERS.find(u => u.id === task.assigneeId);
  const asset = task.assetId ? ASSETS.find(a => a.id === task.assetId) : null;

  return (
    <TableRow hover onClick={() => onSelect(task)} sx={{ cursor: "pointer" }}>
      <TableCell sx={{ pl: 2 }}>
        <Box sx={{ display: "flex", alignItems: "flex-start", gap: 1 }}>
          <Box sx={{ width: 3, height: 36, bgcolor: pc, borderRadius: 2, flexShrink: 0, mt: 0.25 }} />
          <Box>
            <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem", color: tokens.text.primary }}>
              {task.title}
            </Typography>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
              {task.description.slice(0, 60)}…
            </Typography>
          </Box>
        </Box>
      </TableCell>
      <TableCell>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <Box sx={{ color: sc.color }}>{sc.icon}</Box>
          <Chip label={sc.label} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${sc.color}22`, color: sc.color }} />
        </Box>
      </TableCell>
      <TableCell>
        <Chip label={task.priority} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${pc}22`, color: pc, fontWeight: 700 }} />
      </TableCell>
      <TableCell>
        {assignee && (
          <Tooltip title={assignee.name}>
            <Avatar sx={{ width: 22, height: 22, fontSize: "0.58rem", bgcolor: tokens.primary.dark }}>
              {assignee.name.split(" ").map(n => n[0]).join("")}
            </Avatar>
          </Tooltip>
        )}
      </TableCell>
      <TableCell>
        {asset && (
          <Box
            onClick={(e) => { e.stopPropagation(); navigate(`/assets/${asset.id}`); }}
            sx={{
              display: "flex", alignItems: "center", gap: 0.75,
              px: 0.75, py: 0.25, borderRadius: tokens.radius.sm,
              bgcolor: tokens.bg.overlay, cursor: "pointer",
              "&:hover": { bgcolor: tokens.primary.subtle },
              maxWidth: 180, overflow: "hidden",
            }}
          >
            {asset.type === "video" ? <PlayCircleOutline sx={{ fontSize: 12, color: tokens.accent.blue, flexShrink: 0 }} /> : <ImageOutlined sx={{ fontSize: 12, color: tokens.accent.blue, flexShrink: 0 }} />}
            <Typography variant="caption" noWrap sx={{ fontSize: "0.7rem", color: tokens.text.secondary }}>{asset.name}</Typography>
          </Box>
        )}
      </TableCell>
      <TableCell>
        <Typography variant="caption" sx={{ color: task.dueDate && new Date(task.dueDate) < new Date() ? tokens.accent.red : tokens.text.tertiary, fontSize: "0.7rem" }}>
          {task.dueDate ? new Date(task.dueDate).toLocaleDateString() : "—"}
        </Typography>
      </TableCell>
    </TableRow>
  );
}

const STATUS_FILTERS = ["all", "open", "in_progress", "review", "done", "blocked"] as const;

export default function TasksView() {
  const { activeProject } = useProject();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [statusFilter, setStatusFilter] = useState<string>("all");
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);

  useEffect(() => {
    if (!activeProject) return;
    mockTaskService.getByProject(activeProject.id).then(setTasks);
  }, [activeProject]);

  const filtered = statusFilter === "all" ? tasks : tasks.filter(t => t.status === statusFilter);
  const counts = STATUS_FILTERS.slice(1).reduce((acc, s) => ({ ...acc, [s]: tasks.filter(t => t.status === s).length }), {} as Record<string, number>);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Tasks</Typography>
        <Typography variant="caption" color="text.secondary">{activeProject?.name} · {tasks.length} tasks</Typography>
      </Box>

      {/* Status pills */}
      <Box sx={{ px: 2.5, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 0.75, flexWrap: "wrap" }}>
        {STATUS_FILTERS.map(s => {
          const sc = s === "all" ? null : statusConfig[s];
          const count = s === "all" ? tasks.length : counts[s] ?? 0;
          return (
            <Chip
              key={s}
              label={`${s === "all" ? "All" : sc?.label} (${count})`}
              size="small"
              onClick={() => setStatusFilter(s)}
              sx={{
                cursor: "pointer",
                bgcolor: statusFilter === s ? (sc ? `${sc.color}22` : tokens.primary.subtle) : "transparent",
                border: `1px solid ${statusFilter === s ? (sc?.color ?? tokens.primary.main) : tokens.border.subtle}`,
                color: statusFilter === s ? (sc?.color ?? tokens.primary.light) : tokens.text.secondary,
                fontWeight: statusFilter === s ? 600 : 400,
                height: 22,
                fontSize: "0.72rem",
              }}
            />
          );
        })}
      </Box>

      <Box sx={{ flex: 1, overflow: "auto" }}>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Task</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Priority</TableCell>
                <TableCell>Assignee</TableCell>
                <TableCell>Asset</TableCell>
                <TableCell>Due</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.map(t => <TaskRow key={t.id} task={t} onSelect={setSelectedTask} />)}
            </TableBody>
          </Table>
        </TableContainer>
        {filtered.length === 0 && (
          <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
            <TaskAltOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
            <Typography variant="body2" color="text.secondary">No tasks for this filter</Typography>
          </Box>
        )}
      </Box>

      <TaskDetailDrawer task={selectedTask} onClose={() => setSelectedTask(null)} />
    </Box>
  );
}

