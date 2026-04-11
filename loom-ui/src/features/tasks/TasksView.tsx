import React, { useEffect, useState } from "react";
import {
  Box, Typography, Chip, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Tooltip, IconButton, Drawer, Divider,
} from "@mui/material";
import {
  TaskAltOutlined, RadioButtonUncheckedOutlined, PendingOutlined,
  BlockOutlined, RateReviewOutlined,
  CloseOutlined, CalendarTodayOutlined, FlagOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { listTasks, TaskResponse } from "../../api/tasks";

const priorityColor: Record<string, string> = {
  CRITICAL: tokens.accent.red,
  HIGH: tokens.accent.amber,
  MEDIUM: tokens.accent.blue,
  LOW: tokens.text.tertiary,
};

const statusConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  open: { label: "Open", color: tokens.accent.blue, icon: <RadioButtonUncheckedOutlined sx={{ fontSize: 13 }} /> },
  in_progress: { label: "In Progress", color: tokens.accent.amber, icon: <PendingOutlined sx={{ fontSize: 13 }} /> },
  review: { label: "Review", color: tokens.primary.main, icon: <RateReviewOutlined sx={{ fontSize: 13 }} /> },
  done: { label: "Done", color: tokens.accent.green, icon: <TaskAltOutlined sx={{ fontSize: 13 }} /> },
  blocked: { label: "Blocked", color: tokens.accent.red, icon: <BlockOutlined sx={{ fontSize: 13 }} /> },
};

// ── Task Detail Drawer ────────────────────────────────────────────────────
function TaskDetailDrawer({ task, onClose }: { task: TaskResponse | null; onClose: () => void }) {
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
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "0.95rem", flex: 1 }}>Task Detail</Typography>
          <IconButton size="small" onClick={onClose}><CloseOutlined sx={{ fontSize: 16 }} /></IconButton>
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
              sx={{ bgcolor: `${pc}22`, color: pc, border: `1px solid ${pc}44`, fontWeight: 700 }}
            />
          </Box>

          <Divider sx={{ borderColor: tokens.border.subtle }} />

          {/* Meta grid */}
          <Box sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
            {[
              {
                icon: <CalendarTodayOutlined sx={{ fontSize: 14 }} />,
                label: "Created",
                content: <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>
                  {task.status?.created ? new Date(task.status.created).toLocaleDateString() : "—"}
                </Typography>,
              },
              {
                icon: <CalendarTodayOutlined sx={{ fontSize: 14 }} />,
                label: "Last Edited",
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
        <Chip label={prio} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${pc}22`, color: pc, fontWeight: 700 }} />
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
  const [tasks, setTasks] = useState<TaskResponse[]>([]);
  const [selectedTask, setSelectedTask] = useState<TaskResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!token) return;
    setLoading(true);
    listTasks(token)
      .then((res) => setTasks(res.data ?? []))
      .catch(() => setTasks([]))
      .finally(() => setLoading(false));
  }, [token]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Tasks</Typography>
        <Typography variant="caption" color="text.secondary">{tasks.length} tasks</Typography>
      </Box>

      <Box sx={{ flex: 1, overflow: "auto" }}>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Task</TableCell>
                <TableCell>Priority</TableCell>
                <TableCell>Created</TableCell>
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
            <Typography variant="body2" color="text.secondary">No tasks found</Typography>
          </Box>
        )}
      </Box>

      <TaskDetailDrawer task={selectedTask} onClose={() => setSelectedTask(null)} />
    </Box>
  );
}

