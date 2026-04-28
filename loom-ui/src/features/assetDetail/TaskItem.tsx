import React from "react";
import { Box, Chip, Typography } from "@mui/material";
import { tokens } from "../../theme";
import { Task } from "../../types";

export function TaskItem({ task, onClick }: { task: Task; onClick?: () => void }) {
  const priorityColor: Record<string, string> = { critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary };
  const statusColor: Record<string, string> = { open: tokens.accent.blue, in_progress: tokens.accent.amber, review: tokens.primary.main, done: tokens.accent.green, blocked: tokens.accent.red };
  return (
    <Box onClick={onClick} sx={{ display: "flex", gap: 1.5, p: 1.5, borderRadius: tokens.radius.md, bgcolor: tokens.bg.overlay, cursor: "pointer", "&:hover": { bgcolor: tokens.primary.subtle, border: `1px solid ${tokens.primary.glow}` }, border: "1px solid transparent", transition: "all 140ms ease" }}>
      <Box sx={{ width: 3, height: "auto", bgcolor: priorityColor[task.priority], borderRadius: 2, flexShrink: 0, alignSelf: "stretch" }} />
      <Box sx={{ flex: 1 }}>
        <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem", color: tokens.text.primary, mb: 0.5 }}>{task.title}</Typography>
        <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem", display: "block", mb: 0.5 }}>{task.description}</Typography>
        <Box sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
          <Chip label={task.status.replace("_", " ")} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${statusColor[task.status]}22`, color: statusColor[task.status] }} />
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>→ {task.assigneeId}</Typography>
          {task.dueDate && <Typography variant="caption" sx={{ color: new Date(task.dueDate) < new Date() ? tokens.accent.red : tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>{new Date(task.dueDate).toLocaleDateString()}</Typography>}
        </Box>
      </Box>
    </Box>
  );
}
