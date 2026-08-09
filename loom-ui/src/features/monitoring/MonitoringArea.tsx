import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Box, Typography, Paper, Alert,
} from "@mui/material";
import {
  AreaChart, Area, BarChart, Bar, LineChart, Line,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from "recharts";
import {
  AccountTreeOutlined, SpeedOutlined,
  PlayCircleOutlineOutlined, MemoryOutlined, DnsOutlined,
  ReportProblemOutlined, BlockOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { loadPipelineRunStats, PipelineRunDayStats } from "../../api/pipelines";
import { subscribePipelineEvents, PipelineEventMessage } from "../../api/pipelineEvents";
import { loadMetrics, type MetricRecord } from "../../api/metrics";
import { formatDeltaPct, summarizeRunStats } from "./runMetrics";
import {
  appendSample, latencyByKind, outcomesByKind, summarizeFleet, toLiveSample, toLiveSeries,
  workersByState, POLL_INTERVAL_MS, type LiveSample,
} from "./metricsPanels";

// ── KPI Card ──────────────────────────────────────────────────────────────
function KPICard({
  title, value, unit, delta, color, icon, subtitle, testId,
}: {
  title: string;
  value: string | number;
  unit?: string;
  delta?: string;
  color: string;
  icon: React.ReactNode;
  subtitle?: string;
  testId?: string;
}) {
  return (
    <Paper
      elevation={0}
      data-testid={testId}
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        p: 2,
        display: "flex",
        flexDirection: "column",
        gap: 1,
        position: "relative",
        overflow: "hidden",
        "&::before": {
          content: '""',
          position: "absolute",
          top: 0, left: 0, right: 0,
          height: 3,
          bgcolor: color,
        },
      }}
    >
      <Box sx={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
        <Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
            <Typography variant="caption" sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem" }}>
              {title}
            </Typography>
          </Box>
          <Box sx={{ display: "flex", alignItems: "baseline", gap: 0.5, mt: 0.25 }}>
            <Typography variant="h4" fontWeight={700} sx={{ fontSize: "1.75rem", color: tokens.text.primary, lineHeight: 1 }}>
              {value}
            </Typography>
            {unit && <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>{unit}</Typography>}
          </Box>
        </Box>
        <Box sx={{ width: 36, height: 36, borderRadius: tokens.radius.md, bgcolor: `${color}18`, display: "flex", alignItems: "center", justifyContent: "center", color }}>
          {icon}
        </Box>
      </Box>
      {subtitle && <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem" }}>{subtitle}</Typography>}
      {delta && (
        <Typography variant="caption" sx={{ color: delta.startsWith("+") ? tokens.accent.green : tokens.accent.red, fontWeight: 600, fontSize: "0.72rem" }}>
          {delta} vs last 7d
        </Typography>
      )}
    </Paper>
  );
}

// ── Chart Card ────────────────────────────────────────────────────────────
/**
 * A panel with a title and, when its series is empty, a reason instead of an empty plot.
 *
 * A blank chart and a chart of zeroes look identical, and neither says which one it is. `empty` is
 * rendered whenever there is nothing to draw, so "no worker has run anything yet" never reads as
 * "throughput is zero".
 */
function ChartCard({ title, children, height = 160, testId, empty }: {
  title: string;
  children: React.ReactNode;
  height?: number;
  testId?: string;
  empty?: string;
}) {
  return (
    <Paper
      elevation={0}
      data-testid={testId}
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        overflow: "hidden",
      }}
    >
      <Box sx={{ px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.875rem" }}>{title}</Typography>
      </Box>
      <Box sx={{ p: 1.5, height }}>
        {empty
          ? (
            <Box data-testid="chart-empty" sx={{ height: "100%", display: "flex", alignItems: "center", justifyContent: "center" }}>
              <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem", textAlign: "center" }}>{empty}</Typography>
            </Box>
          )
          : children}
      </Box>
    </Paper>
  );
}

const chartStyle = {
  fontSize: 11,
  fontFamily: "'Inter', sans-serif",
};

const tooltipStyle = {
  contentStyle: {
    background: tokens.bg.overlay,
    border: `1px solid ${tokens.border.default}`,
    borderRadius: tokens.radius.sm,
    fontSize: 11,
    fontFamily: "'Inter', sans-serif",
    color: tokens.text.primary,
  },
  labelStyle: { color: tokens.text.secondary },
};

/** Clock time of a live sample, which is the only axis a five-minute window can use. */
function clockTime(ts: number): string {
  return new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

/** A KPI value that has no reading yet shows a dash rather than a zero it cannot justify. */
function orDash(value: number | undefined, digits = 0): string {
  return value === undefined ? "—" : value.toFixed(digits);
}

// ── Main Monitoring Area ──────────────────────────────────────────────────
/**
 * The instance dashboard, drawn entirely from what Loom measures.
 *
 * Two independent sources, and a failure of either degrades only its own panels:
 *
 * - `GET /pipelines/runs/stats` — the one genuinely historical series Loom keeps, because pipeline
 *   runs are database rows with dates. It is the only chart here with a day axis.
 * - `GET /metrics` — the `loom_*` meter catalog, polled. Meters have no history, so the panels fed
 *   by it are either instantaneous (in flight, workers, breaker state, per-kind totals) or a rate
 *   differenced across polls and labelled as live. Nothing here interpolates a past it did not see.
 */
export default function MonitoringArea() {
  const { t } = useTranslation();
  const { token } = useAuth();

  // Real cross-pipeline run stats from GET /pipelines/runs/stats.
  // null = still loading; an empty bucket list is a valid (all-zero) result.
  const [runStats, setRunStats] = useState<PipelineRunDayStats[] | null>(null);
  const [runStatsError, setRunStatsError] = useState(false);

  const loadRunStats = useCallback(() => {
    if (!token) return;
    loadPipelineRunStats(token)
      .then(response => {
        setRunStats(response.daily ?? []);
        setRunStatsError(false);
      })
      .catch(() => {
        setRunStats([]);
        setRunStatsError(true);
      });
  }, [token]);

  useEffect(() => { loadRunStats(); }, [loadRunStats]);

  // Live refresh: any pipeline start/completion re-fetches the roll-up.
  useEffect(() => {
    const handle = (event: PipelineEventMessage) => {
      if (event.type === "PIPELINE_STARTED" || event.type === "PIPELINE_COMPLETED") {
        loadRunStats();
      }
    };
    return subscribePipelineEvents(handle, token);
  }, [token, loadRunStats]);

  // ── Metric catalog ──────────────────────────────────────────────────────
  // null = not read yet; [] is a valid answer from an instance that has recorded nothing.
  const [metrics, setMetrics] = useState<MetricRecord[] | null>(null);
  const [metricsError, setMetricsError] = useState(false);
  const [history, setHistory] = useState<LiveSample[]>([]);

  // The poll must not be torn down and rebuilt on every state change it causes, so the effect below
  // depends on the token alone and reaches the fetch through a ref.
  const pollRef = useRef<() => void>(() => { });
  pollRef.current = () => {
    if (!token) return;
    loadMetrics(token)
      .then(response => {
        setMetrics(response.metrics ?? []);
        setMetricsError(false);
        setHistory(previous => appendSample(previous, toLiveSample(response)));
      })
      .catch(() => {
        // Keep the last good snapshot on screen rather than blanking the dashboard on one bad poll,
        // and say so in the banner. A transient 502 is not a fleet that went to zero.
        setMetrics(previous => previous ?? []);
        setMetricsError(true);
      });
  };

  useEffect(() => {
    if (!token) return;
    pollRef.current();
    const handle = setInterval(() => pollRef.current(), POLL_INTERVAL_MS);
    return () => clearInterval(handle);
  }, [token]);

  const runSummary = useMemo(() => summarizeRunStats(runStats ?? []), [runStats]);
  const runChartData = useMemo(() => (runStats ?? []).map(b => ({
    ts: b.date,
    success: b.successCount,
    failed: b.failureCount,
    skipped: b.skippedCount,
  })), [runStats]);

  const fleet = useMemo(() => summarizeFleet(metrics ?? []), [metrics]);
  const outcomes = useMemo(() => outcomesByKind(metrics ?? []), [metrics]);
  const latencies = useMemo(() => latencyByKind(metrics ?? []), [metrics]);
  const workers = useMemo(() => workersByState(metrics ?? []), [metrics]);
  const live = useMemo(() => toLiveSeries(history), [history]);

  const loading = metrics === null;
  const noMetrics = loading ? t("monitoring.empty.loading") : undefined;
  // One point cannot be a rate, so the live charts say what they are waiting for.
  const noLive = live.length === 0 ? (loading ? t("monitoring.empty.loading") : t("monitoring.empty.collecting")) : undefined;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("monitoring.title")}</Typography>
        <Typography variant="caption" color="text.secondary">{t("monitoring.subtitle")}</Typography>
      </Box>

      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {runStatsError && (
          <Alert severity="warning" sx={{ mb: 2 }}>{t("monitoring.loadError")}</Alert>
        )}
        {metricsError && (
          <Alert severity="warning" data-testid="metrics-error" sx={{ mb: 2 }}>{t("monitoring.metricsError")}</Alert>
        )}

        {/* KPI Row */}
        <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2, mb: 3 }}>
          <KPICard title={t("monitoring.kpi.pipelineRuns")} value={runStats === null ? "—" : runSummary.totalRuns7d} unit={t("monitoring.kpi.runs")} delta={formatDeltaPct(runSummary.runsDeltaPct)} color={tokens.accent.teal} icon={<AccountTreeOutlined />} subtitle={t("monitoring.kpi.allPipelines")} testId="kpi-pipeline-runs" />
          <KPICard title={t("monitoring.kpi.activeRuns")} value={orDash(fleet.activeRuns)} color={tokens.primary.main} icon={<PlayCircleOutlineOutlined />} subtitle={t("monitoring.kpi.activeRunsSub")} testId="kpi-active-runs" />
          <KPICard title={t("monitoring.kpi.tasksInFlight")} value={orDash(fleet.inFlight)} color={tokens.accent.blue} icon={<MemoryOutlined />}
            /* A run configured as unlimited contributes 0 to the ceiling, so "of 0 slots" would be a
               lie in the alarming direction. Say unlimited instead. */
            subtitle={fleet.inFlightCeiling ? t("monitoring.kpi.ofCeiling", { ceiling: fleet.inFlightCeiling }) : t("monitoring.kpi.noCeiling")}
            testId="kpi-tasks-inflight" />
          <KPICard title={t("monitoring.kpi.avgTaskLatency")} value={orDash(fleet.meanLatencyMs)} unit="ms" color={tokens.accent.amber} icon={<SpeedOutlined />} subtitle={t("monitoring.kpi.completedTasks")} testId="kpi-task-latency" />
          <KPICard title={t("monitoring.kpi.workersOnline")} value={orDash(fleet.workersOnline)} color={tokens.accent.green} icon={<DnsOutlined />}
            subtitle={t("monitoring.kpi.ofConnected", { connected: fleet.workersConnected ?? 0 })} testId="kpi-workers" />
          <KPICard title={t("monitoring.kpi.dispatchFailures")} value={fleet.dispatchFailures} color={tokens.accent.red} icon={<ReportProblemOutlined />} subtitle={t("monitoring.kpi.deadLettered", { count: fleet.deadLettered })} testId="kpi-dispatch-failures" />
          <KPICard title={t("monitoring.kpi.parkedKinds")} value={fleet.parked.length} color={fleet.parked.length > 0 ? tokens.accent.red : tokens.primary.light} icon={<BlockOutlined />}
            subtitle={fleet.parked.length > 0 ? fleet.parked.join(", ") : t("monitoring.kpi.allKindsDispatching")} testId="kpi-parked-kinds" />
        </Box>

        {/* Charts Grid */}
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr", lg: "2fr 1fr 1fr" }, gap: 2 }}>
          {/* Pipeline runs — the one series with real history, from the run table */}
          <ChartCard title={t("monitoring.chart.pipelineRuns")} height={180} testId="monitoring-runs-chart"
            empty={runStats === null ? t("monitoring.empty.loading") : undefined}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={runChartData} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={v => new Date(v).toLocaleDateString("en", { day: "numeric" })} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Bar dataKey="success" name={t("monitoring.series.success")} fill={tokens.accent.green} radius={[2, 2, 0, 0]} stackId="runs" />
                <Bar dataKey="failed" name={t("monitoring.series.failed")} fill={tokens.accent.red} radius={[2, 2, 0, 0]} stackId="runs" />
                <Bar dataKey="skipped" name={t("monitoring.series.skipped")} fill={tokens.accent.amber} radius={[2, 2, 0, 0]} stackId="runs" />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Node task outcomes, per node kind */}
          <ChartCard title={t("monitoring.chart.outcomes")} height={180} testId="monitoring-outcomes-chart"
            empty={noMetrics ?? (outcomes.length === 0 ? t("monitoring.empty.noResults") : undefined)}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={outcomes} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="kind" interval={0} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} />
                <Bar dataKey="completed" name={t("monitoring.series.completed")} fill={tokens.accent.green} radius={[2, 2, 0, 0]} stackId="outcome" />
                <Bar dataKey="failed" name={t("monitoring.series.failed")} fill={tokens.accent.red} radius={[2, 2, 0, 0]} stackId="outcome" />
                <Bar dataKey="skipped" name={t("monitoring.series.skipped")} fill={tokens.accent.amber} radius={[2, 2, 0, 0]} stackId="outcome" />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Workers by lifecycle state */}
          <ChartCard title={t("monitoring.chart.workers")} height={180} testId="monitoring-workers-chart"
            empty={noMetrics ?? (workers.length === 0 ? t("monitoring.empty.noWorkers") : undefined)}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={workers} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="state" interval={0} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis allowDecimals={false} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} />
                <Bar dataKey="count" name={t("monitoring.series.workers")} fill={tokens.accent.teal} radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Dispatch-to-result latency, per node kind */}
          <ChartCard title={t("monitoring.chart.latencyByKind")} height={170} testId="monitoring-latency-chart"
            empty={noMetrics ?? (latencies.length === 0 ? t("monitoring.empty.noLatency") : undefined)}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={latencies} margin={{ top: 5, right: 5, bottom: 0, left: -10 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="kind" interval={0} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} tickFormatter={v => v.toFixed(0)} />
                <Tooltip {...tooltipStyle} formatter={(v: number) => [`${v.toFixed(0)} ms`, ""]} />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Bar dataKey="meanMs" name={t("monitoring.series.mean")} fill={tokens.accent.blue} radius={[2, 2, 0, 0]} />
                <Bar dataKey="maxMs" name={t("monitoring.series.worst")} fill={tokens.accent.amber} radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Live throughput — differenced across polls, never interpolated */}
          <ChartCard title={t("monitoring.chart.throughput")} height={170} testId="monitoring-throughput-chart" empty={noLive}>
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={live} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <defs>
                  <linearGradient id="throughputGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={tokens.primary.main} stopOpacity={0.3} />
                    <stop offset="95%" stopColor={tokens.primary.main} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={clockTime} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} labelFormatter={(v: number) => clockTime(v)} formatter={(v: number) => [v.toFixed(2), t("monitoring.series.perSecond")]} />
                <Area type="monotone" dataKey="resultsPerSecond" name={t("monitoring.series.perSecond")} stroke={tokens.primary.main} fill="url(#throughputGrad)" strokeWidth={2} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Live saturation — depth against its ceiling, which is what decides whether workers help */}
          <ChartCard title={t("monitoring.chart.saturation")} height={170} testId="monitoring-inflight-chart" empty={noLive}>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={live} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={clockTime} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis allowDecimals={false} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} labelFormatter={(v: number) => clockTime(v)} />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Line type="monotone" dataKey="inFlight" name={t("monitoring.series.inFlight")} stroke={tokens.accent.blue} strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="ceiling" name={t("monitoring.series.ceiling")} stroke={tokens.accent.amber} strokeWidth={1.5} dot={false} strokeDasharray="4 2" />
              </LineChart>
            </ResponsiveContainer>
          </ChartCard>
        </Box>
      </Box>
    </Box>
  );
}
