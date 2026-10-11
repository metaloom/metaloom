import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, CircularProgress, MenuItem, TextField, Typography } from "@mui/material";
import { Bar, BarChart, CartesianGrid, Cell, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

import { tokens } from "../../theme";
import type { PipelineNodeStatsResponse, PipelineNodeTimeStats } from "../../api/pipelines";
import { formatDuration, formatShare, histogramRows, nodeTimeRows, reusedFraction, runTrendRows } from "./nodeTiming";

/** Which runs the figures cover: the latest `limit` runs, accumulated. */
export type PerformanceScope = 1 | 10 | 20 | 50;

export const PERFORMANCE_SCOPES: PerformanceScope[] = [1, 10, 20, 50];

const chartText = { fontSize: 11, fontFamily: "'Inter', sans-serif" };

const tooltipStyle = {
  contentStyle: {
    background: tokens.bg.overlay,
    border: `1px solid ${tokens.border.default}`,
    borderRadius: tokens.radius.sm,
    fontSize: 11,
    fontFamily: "'Inter', sans-serif",
    color: tokens.text.primary,
  },
  cursor: { fill: tokens.primary.subtle },
};

/** Height of one bar row in the per-node chart; the chart grows with the graph instead of squeezing bars. */
const ROW_HEIGHT = 24;

/**
 * "Where does the time go": per-node execution time of a pipeline, accumulated over its latest runs.
 *
 * The left half ranks the nodes by the time they cost. Picking a bar drills into that node on the right:
 * its figures, how its computed durations are distributed, and what it cost in each run. Reused executions
 * are counted apart everywhere - they cost milliseconds, and the time they saved is shown as an estimate.
 *
 * Presentational: the editor owns the scope and the fetch, because the canvas shows the same figures on its
 * node cards and the two must never disagree.
 */
export function PipelinePerformancePanel({ stats, loading, error, scope, onScopeChange, displayNames }: {
  stats: PipelineNodeStatsResponse | null;
  loading: boolean;
  error: string | null;
  scope: PerformanceScope;
  onScopeChange: (scope: PerformanceScope) => void;
  displayNames: Record<string, string | undefined>;
}) {
  const { t } = useTranslation();
  const rows = useMemo(() => nodeTimeRows(stats?.nodes ?? [], displayNames), [stats, displayNames]);
  const [focusId, setFocusId] = useState<string | null>(null);

  // Keep the drill-down on a node that exists in the current figures; default to the most expensive.
  useEffect(() => {
    if (rows.length === 0) {
      setFocusId(null);
    } else if (!focusId || !rows.some(r => r.nodeId === focusId)) {
      setFocusId(rows[0].nodeId);
    }
  }, [rows, focusId]);

  const focus: PipelineNodeTimeStats | undefined = stats?.nodes.find(n => n.nodeId === focusId);

  return (
    <Box data-testid="pipeline-performance-panel" sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      {/* Scope + headline figures, one row */}
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, px: 1.5, py: 1, flexWrap: "wrap", borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <TextField
          select
          size="small"
          value={scope}
          onChange={e => onScopeChange(Number(e.target.value) as PerformanceScope)}
          inputProps={{ "data-testid": "pipeline-performance-scope", "aria-label": t("pipeline.performance.scope") }}
          sx={{ minWidth: 150, "& .MuiInputBase-root": { fontSize: "0.75rem", height: 28 } }}
        >
          {PERFORMANCE_SCOPES.map(s => (
            <MenuItem key={s} value={s} sx={{ fontSize: "0.75rem" }}>
              {s === 1 ? t("pipeline.performance.scopeLastRun") : t("pipeline.performance.scopeLastRuns", { count: s })}
            </MenuItem>
          ))}
        </TextField>
        {stats && stats.runCount > 0 && (
          <>
            <Figure testId="pipeline-performance-total" label={t("pipeline.performance.totalTime")} value={formatDuration(stats.totalDurationMs)} />
            <Figure
              testId="pipeline-performance-reused"
              label={t("pipeline.performance.reused")}
              value={`${stats.reused} / ${stats.tasks} (${formatShare(reusedFraction(stats))})`}
            />
            <Figure testId="pipeline-performance-saved" label={t("pipeline.performance.saved")} value={`~${formatDuration(stats.estimatedSavedMs)}`} />
            <Figure label={t("pipeline.performance.runs")} value={String(stats.runCount)} />
          </>
        )}
        {loading && <CircularProgress size={14} data-testid="pipeline-performance-loading" />}
      </Box>

      {error ? (
        <Message testId="pipeline-performance-error" text={error} color={tokens.accent.red} />
      ) : !stats || stats.runCount === 0 || rows.length === 0 ? (
        loading ? null : <Message testId="pipeline-performance-empty" text={t("pipeline.performance.empty")} />
      ) : (
        <Box sx={{ flex: 1, minHeight: 0, display: "flex", gap: 2, p: 1.5, overflow: "hidden" }}>
          {/* Ranking: one bar per node, most expensive first */}
          <Box sx={{ flex: "1.2 1 0", minWidth: 0, overflowY: "auto" }} data-testid="pipeline-performance-ranking">
            <Typography variant="caption" sx={{ fontWeight: 600, color: tokens.text.secondary, display: "block", mb: 0.5 }}>
              {t("pipeline.performance.byNode")}
            </Typography>
            <Box sx={{ height: rows.length * ROW_HEIGHT + 24 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={rows} layout="vertical" margin={{ top: 0, right: 72, bottom: 0, left: 0 }} barCategoryGap={4}>
                  <CartesianGrid stroke={tokens.border.subtle} horizontal={false} />
                  <XAxis type="number" hide />
                  <YAxis
                    type="category"
                    dataKey="label"
                    width={130}
                    tick={{ ...chartText, fill: tokens.text.secondary }}
                    tickLine={false}
                    axisLine={false}
                  />
                  <Tooltip
                    {...tooltipStyle}
                    formatter={(value: number, _name, item) => {
                      const row = item?.payload as (typeof rows)[number];
                      return [
                        `${formatDuration(value)} · ${formatShare(row.share)} · ${t("pipeline.performance.executions", { count: row.tasks })}`
                        + (row.reused > 0 ? ` · ${t("pipeline.performance.reusedCount", { count: row.reused })}` : ""),
                        t("pipeline.performance.totalTime"),
                      ];
                    }}
                  />
                  <Bar
                    dataKey="totalDurationMs"
                    radius={[0, 4, 4, 0]}
                    maxBarSize={14}
                    cursor="pointer"
                    onClick={(data: { nodeId?: string }) => data?.nodeId && setFocusId(data.nodeId)}
                    isAnimationActive={false}
                  >
                    {rows.map(r => (
                      <Cell
                        key={r.nodeId}
                        data-testid={`pipeline-performance-bar-${r.nodeId}`}
                        fill={tokens.primary.main}
                        // Emphasis, not a second colour: the node in focus is solid, the rest recede.
                        fillOpacity={r.nodeId === focusId ? 1 : 0.4}
                      />
                    ))}
                    {/* Drawn by hand: recharts wraps a label to the bar's width, which breaks "2.8 s" in two on a short bar. */}
                    <LabelList
                      dataKey="totalDurationMs"
                      content={(props: { x?: number | string; y?: number | string; width?: number | string; height?: number | string; value?: number | string }) => (
                        <text
                          x={Number(props.x ?? 0) + Number(props.width ?? 0) + 6}
                          y={Number(props.y ?? 0) + Number(props.height ?? 0) / 2}
                          dominantBaseline="central"
                          style={{ ...chartText, fill: tokens.text.secondary, whiteSpace: "nowrap" }}
                        >
                          {formatDuration(Number(props.value))}
                        </text>
                      )}
                    />
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </Box>
          </Box>

          {/* Drill-down into the node in focus */}
          {focus && (
            <NodeDrillDown
              node={focus}
              stats={stats}
              label={displayNames[focus.nodeId] || focus.nodeId}
              choices={rows.map(r => ({ nodeId: r.nodeId, label: r.label }))}
              onFocus={setFocusId}
            />
          )}
        </Box>
      )}
    </Box>
  );
}

function NodeDrillDown({ node, stats, label, choices, onFocus }: {
  node: PipelineNodeTimeStats;
  stats: PipelineNodeStatsResponse;
  label: string;
  /** Every node in the ranking, so the drill-down is reachable without a pointer - chart bars take no keyboard focus. */
  choices: { nodeId: string; label: string }[];
  onFocus: (nodeId: string) => void;
}) {
  const { t } = useTranslation();
  const histogram = histogramRows(node);
  const trend = runTrendRows(node, stats.runs);
  return (
    <Box
      data-testid="pipeline-performance-detail"
      data-node-id={node.nodeId}
      sx={{ flex: "1 1 0", minWidth: 0, overflowY: "auto", borderLeft: `1px solid ${tokens.border.subtle}`, pl: 2 }}
    >
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <TextField
          select
          size="small"
          value={node.nodeId}
          onChange={e => onFocus(e.target.value)}
          inputProps={{ "data-testid": "pipeline-performance-node-select", "aria-label": t("pipeline.performance.node") }}
          sx={{ minWidth: 160, "& .MuiInputBase-root": { fontSize: "0.75rem", height: 26, fontWeight: 600 } }}
        >
          {choices.map(c => <MenuItem key={c.nodeId} value={c.nodeId} sx={{ fontSize: "0.75rem" }}>{c.label}</MenuItem>)}
        </TextField>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary }} title={label}>{node.nodeKind}</Typography>
      </Box>

      {/* The table view of the node: every figure the charts are drawn from */}
      <Box
        component="dl"
        data-testid="pipeline-performance-figures"
        sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(88px, 1fr))", columnGap: 1.5, rowGap: 0.5, my: 1, "& dd": { m: 0 } }}
      >
        <Stat label={t("pipeline.performance.totalTime")} value={formatDuration(node.totalDurationMs)} />
        <Stat label={t("pipeline.performance.share")} value={formatShare(node.share)} />
        <Stat label={t("pipeline.performance.executionsLabel")} value={String(node.tasks)} />
        <Stat label={t("pipeline.performance.computed")} value={String(node.computed)} />
        <Stat label={t("pipeline.performance.reused")} value={`${node.reused} (${formatShare(reusedFraction(node))})`} testId="pipeline-performance-node-reused" />
        <Stat label={t("pipeline.performance.failed")} value={String(node.failed)} />
        <Stat label={t("pipeline.performance.avg")} value={formatDuration(node.avgDurationMs)} />
        <Stat label={t("pipeline.performance.p50")} value={formatDuration(node.p50DurationMs)} />
        <Stat label={t("pipeline.performance.p95")} value={formatDuration(node.p95DurationMs)} />
        <Stat label={t("pipeline.performance.max")} value={formatDuration(node.maxDurationMs)} />
        <Stat label={t("pipeline.performance.saved")} value={`~${formatDuration(node.estimatedSavedMs)}`} />
      </Box>

      <Box sx={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 2 }}>
        <MiniChart
          testId="pipeline-performance-histogram"
          title={t("pipeline.performance.distribution")}
          empty={histogram.length === 0 ? t("pipeline.performance.noComputed") : null}
          data={histogram}
          xKey="label"
          yKey="count"
          format={v => t("pipeline.performance.executions", { count: v })}
        />
        <MiniChart
          testId="pipeline-performance-trend"
          title={t("pipeline.performance.perRun")}
          empty={null}
          data={trend}
          xKey="label"
          yKey="totalDurationMs"
          format={v => formatDuration(v)}
        />
      </Box>
    </Box>
  );
}

/** A small single-series bar chart with a hover tooltip; one colour, the value is in the tooltip. */
function MiniChart<T extends object>({ testId, title, data, xKey, yKey, format, empty }: {
  testId: string;
  title: string;
  data: T[];
  xKey: keyof T & string;
  yKey: keyof T & string;
  format: (value: number) => string;
  empty: string | null;
}) {
  return (
    <Box data-testid={testId} sx={{ minWidth: 0 }}>
      <Typography variant="caption" sx={{ color: tokens.text.secondary, display: "block", mb: 0.5 }}>{title}</Typography>
      {empty ? (
        <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>{empty}</Typography>
      ) : (
        <Box sx={{ height: 96 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: 4 }} barCategoryGap={2}>
              <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
              <XAxis dataKey={xKey} tick={{ ...chartText, fontSize: 10, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} interval="preserveStartEnd" />
              <YAxis hide />
              <Tooltip {...tooltipStyle} formatter={(v: number) => [format(v), title]} />
              <Bar dataKey={yKey} fill={tokens.primary.main} radius={[4, 4, 0, 0]} maxBarSize={28} isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </Box>
      )}
    </Box>
  );
}

function Figure({ label, value, testId }: { label: string; value: string; testId?: string }) {
  return (
    <Box data-testid={testId}>
      <Typography sx={{ fontSize: "0.62rem", color: tokens.text.tertiary, textTransform: "uppercase", letterSpacing: "0.06em" }}>{label}</Typography>
      <Typography sx={{ fontSize: "0.82rem", fontWeight: 600, color: tokens.text.primary }}>{value}</Typography>
    </Box>
  );
}

function Stat({ label, value, testId }: { label: string; value: string; testId?: string }) {
  return (
    <Box data-testid={testId}>
      <Typography component="dt" sx={{ fontSize: "0.62rem", color: tokens.text.tertiary }}>{label}</Typography>
      <Typography component="dd" sx={{ fontSize: "0.76rem", color: tokens.text.primary }}>{value}</Typography>
    </Box>
  );
}

function Message({ text, testId, color }: { text: string; testId: string; color?: string }) {
  return (
    <Box data-testid={testId} sx={{ p: 2 }}>
      <Typography variant="caption" sx={{ color: color ?? tokens.text.tertiary }}>{text}</Typography>
    </Box>
  );
}
