import React from "react";
import { Avatar, AvatarGroup, Box, Chip, Tooltip, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { TaskResponse } from "../../api/tasks";

// Priority keys use the REST API's uppercase TaskPriority enum.
export const taskPriorityColor: Record<string, string> = {
  CRITICAL: tokens.accent.red,
  HIGH: tokens.accent.amber,
  MEDIUM: tokens.accent.blue,
  LOW: tokens.text.tertiary,
};

// Status keys use the REST API's uppercase TaskStatus enum.
export const taskStatusColor: Record<string, string> = {
  PENDING: tokens.accent.blue,
  REVIEW: tokens.primary.main,
  ACCEPTED: tokens.accent.green,
  REJECTED: tokens.accent.red,
};

export function TaskItem({ task, onClick }: { task: TaskResponse; onClick?: () => void }) {
  const { t } = useTranslation();
  const prio = task.priority?.toUpperCase() ?? "MEDIUM";
  const pc = taskPriorityColor[prio] ?? tokens.text.tertiary;
  const status = task.taskStatus?.toUpperCase();
  const sc = (status && taskStatusColor[status]) || tokens.text.tertiary;
  const overdue = task.dueDate ? new Date(task.dueDate) < new Date() : false;
  return (
    <Box data-testid="asset-task-item" onClick={onClick} sx={{ display: "flex", gap: 1.5, p: 1.5, borderRadius: tokens.radius.md, bgcolor: tokens.bg.overlay, cursor: "pointer", "&:hover": { bgcolor: tokens.primary.subtle, border: `1px solid ${tokens.primary.glow}` }, border: "1px solid transparent", transition: "all 140ms ease" }}>
      <Box sx={{ width: 3, height: "auto", bgcolor: pc, borderRadius: 2, flexShrink: 0, alignSelf: "stretch" }} />
      <Box sx={{ flex: 1 }}>
        <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem", color: tokens.text.primary, mb: 0.5 }}>{task.title}</Typography>
        {task.description && <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem", display: "block", mb: 0.5 }}>{task.description}</Typography>}
        <Box sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
          {status && <Chip data-testid="asset-task-status-chip" label={t(`tasks.status.${status}`, status)} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${sc}22`, color: sc }} />}
          <Chip data-testid="asset-task-priority-chip" label={t(`tasks.priority.${prio}`, prio)} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${pc}22`, color: pc }} />
          {/* Sits before the ml:auto due date so the assignees stay grouped with the chips */}
          {(task.assignees ?? []).length > 0 && (
            <AvatarGroup
              max={3}
              data-testid="asset-task-assignees"
              sx={{ "& .MuiAvatar-root": { width: 18, height: 18, fontSize: "0.55rem", border: "none" } }}
            >
              {task.assignees!.map((a) => (
                <Tooltip key={a.userUuid ?? a.groupUuid} title={a.groupUuid ? `@${a.name ?? ""}` : (a.name ?? "")}>
                  <Avatar sx={{ bgcolor: a.groupUuid ? tokens.accent.blue : tokens.primary.dark }}>
                    {(a.name ?? "?").charAt(0).toUpperCase()}
                  </Avatar>
                </Tooltip>
              ))}
            </AvatarGroup>
          )}
          {task.dueDate && <Typography data-testid="asset-task-due-date" variant="caption" sx={{ color: overdue ? tokens.accent.red : tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>{new Date(task.dueDate).toLocaleDateString()}</Typography>}
        </Box>
      </Box>
    </Box>
  );
}
