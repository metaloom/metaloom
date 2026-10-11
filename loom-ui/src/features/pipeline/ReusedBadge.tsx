import { useTranslation } from "react-i18next";
import { Chip, Tooltip } from "@mui/material";
import { ReplayOutlined } from "@mui/icons-material";

import { tokens } from "../../theme";
import type { PipelineNodeTaskRecord } from "../../api/pipelines";
import { isReusedTask } from "./nodeTiming";

/**
 * Marks a node execution that handed on a result it already had instead of computing one - from Loom's
 * stored outputs or from an earlier run. Renders nothing for a computed execution, so it can sit beside
 * every task row unconditionally.
 */
export function ReusedBadge({ task, testId }: { task: Pick<PipelineNodeTaskRecord, "origin" | "state">; testId?: string }) {
  const { t } = useTranslation();
  if (!isReusedTask(task)) return null;
  return (
    <Tooltip title={t(task.origin === "LOCAL" ? "pipeline.performance.reusedLocal" : "pipeline.performance.reusedRemote")}>
      <Chip
        data-testid={testId ?? "node-result-reused"}
        icon={<ReplayOutlined sx={{ fontSize: "0.7rem !important" }} />}
        label={t("pipeline.performance.reusedBadge")}
        size="small"
        sx={{ height: 16, fontSize: "0.55rem", fontWeight: 700, bgcolor: tokens.primary.subtle, color: tokens.primary.light, "& .MuiChip-icon": { ml: 0.5, mr: -0.25, color: "inherit" } }}
      />
    </Tooltip>
  );
}
