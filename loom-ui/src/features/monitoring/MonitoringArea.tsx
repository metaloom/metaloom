import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Box, Typography, Paper, Grid, Chip, Alert,
} from "@mui/material";
import {
  AreaChart, Area, BarChart, Bar, LineChart, Line,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from "recharts";
import {
  CloudUploadOutlined, AccountTreeOutlined, SpeedOutlined,
  StorageOutlined, TaskAltOutlined, BookmarkBorderOutlined,
  AutoAwesome,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { METRICS } from "../../mock/data";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { loadPipelineRunStats, PipelineRunDayStats } from "../../api/pipelines";
import { subscribePipelineEvents, PipelineEventMessage } from "../../api/pipelineEvents";
import { formatDeltaPct, summarizeRunStats } from "./runMetrics";

// ── Sample data badge ─────────────────────────────────────────────────────
// Marks panels that still render synthetic demo series so they cannot be
// mistaken for live metrics.
function SampleDataBadge() {
  const { t } = useTranslation();
  return (
    <Chip
      data-testid="sample-data-badge"
      label={t("monitoring.sampleData")}
      size="small"
      sx={{ height: 16, fontSize: "0.6rem", bgcolor: `${tokens.accent.amber}22`, color: tokens.accent.amber }}
    />
  );
}

// ── KPI Card ──────────────────────────────────────────────────────────────
function KPICard({
  title, value, unit, delta, color, icon, subtitle, sample, testId,
}: {
  title: string;
  value: string | number;
  unit?: string;
  delta?: string;
  color: string;
  icon: React.ReactNode;
  subtitle?: string;
  sample?: boolean;
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
            {sample && <SampleDataBadge />}
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
function ChartCard({ title, children, height = 160, sample, testId }: {
  title: string;
  children: React.ReactNode;
  height?: number;
  sample?: boolean;
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
        overflow: "hidden",
      }}
    >
      <Box sx={{ px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.875rem" }}>{title}</Typography>
        {sample && <SampleDataBadge />}
      </Box>
      <Box sx={{ p: 1.5, height }}>
        {children}
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

// ── Main Monitoring Area ──────────────────────────────────────────────────
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

  const runSummary = useMemo(() => summarizeRunStats(runStats ?? []), [runStats]);
  const runChartData = useMemo(() => (runStats ?? []).map(b => ({
    ts: b.date,
    success: b.successCount,
    failed: b.failureCount,
    skipped: b.skippedCount,
  })), [runStats]);

  // Compute KPI snapshot values from latest data points
  const lastIngestion = METRICS.ingestion[0].data.slice(-1)[0]?.value ?? 0;
  const lastLatency = METRICS.latency[0].data.slice(-1)[0]?.value ?? 0;
  const storageLatest = METRICS.storage[0].data.slice(-1)[0]?.value ?? 0;
  const openTasks = METRICS.taskBacklog[0].data.slice(-1)[0]?.value ?? 0;
  const chatQueries = METRICS.chatUsage[0].data.reduce((a, b) => a + b.value, 0);

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

        {/* KPI Row */}
        <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2, mb: 3 }}>
          <KPICard title={t("monitoring.kpi.dailyIngest")} value={lastIngestion} unit={t("monitoring.kpi.assets")} delta="+14%" color={tokens.primary.main} icon={<CloudUploadOutlined />} subtitle={t("monitoring.kpi.assetsToday")} sample />
          <KPICard title={t("monitoring.kpi.pipelineRuns")} value={runStats === null ? "—" : runSummary.totalRuns7d} unit={t("monitoring.kpi.runs")} delta={formatDeltaPct(runSummary.runsDeltaPct)} color={tokens.accent.teal} icon={<AccountTreeOutlined />} subtitle={t("monitoring.kpi.allPipelines")} testId="kpi-pipeline-runs" />
          <KPICard title={t("monitoring.kpi.avgLatency")} value={lastLatency} unit="ms" delta={lastLatency > 400 ? "+5%" : "-3%"} color={tokens.accent.blue} icon={<SpeedOutlined />} subtitle={t("monitoring.kpi.processingPipeline")} sample />
          <KPICard title={t("monitoring.kpi.storageUsed")} value={storageLatest.toFixed(1)} unit="TB" color={tokens.accent.amber} icon={<StorageOutlined />} subtitle={t("monitoring.kpi.totalLibraries")} sample />
          <KPICard title={t("monitoring.kpi.openTasks")} value={openTasks} delta="-2%" color={tokens.accent.green} icon={<TaskAltOutlined />} subtitle={t("monitoring.kpi.allProjects")} sample />
          <KPICard title={t("monitoring.kpi.agentQueries")} value={chatQueries} color={tokens.primary.light} icon={<AutoAwesome />} subtitle={t("monitoring.kpi.totalAgentQueries")} sample />
          <KPICard title={t("monitoring.kpi.annotations")} value={METRICS.annotations[0].data.reduce((a, b) => a + b.value, 0)} color={tokens.accent.red} icon={<BookmarkBorderOutlined />} subtitle={t("monitoring.kpi.newAnnotations")} sample />
        </Box>

        {/* Charts Grid */}
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr", lg: "2fr 1fr 1fr" }, gap: 2 }}>
          {/* Ingestion throughput */}
          <ChartCard title={t("monitoring.chart.ingestion")} height={180} sample>
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={METRICS.ingestion[0].data} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <defs>
                  <linearGradient id="ingestionGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={tokens.primary.main} stopOpacity={0.3} />
                    <stop offset="95%" stopColor={tokens.primary.main} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={v => new Date(v).toLocaleDateString("en", { month: "short", day: "numeric" })} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} />
                <Area type="monotone" dataKey="value" name="Assets" stroke={tokens.primary.main} fill="url(#ingestionGrad)" strokeWidth={2} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Pipeline runs — real cross-pipeline aggregation */}
          <ChartCard title={t("monitoring.chart.pipelineRuns")} height={180} testId="monitoring-runs-chart">
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

          {/* Task backlog */}
          <ChartCard title={t("monitoring.chart.taskBacklog")} height={180} sample>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={METRICS.taskBacklog[0].data.map((d, i) => ({ ts: d.ts, open: d.value, overdue: METRICS.taskBacklog[1].data[i]?.value ?? 0 }))} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={v => new Date(v).toLocaleDateString("en", { day: "numeric" })} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} />
                <Line type="monotone" dataKey="open" name="Open" stroke={tokens.accent.amber} strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="overdue" name="Overdue" stroke={tokens.accent.red} strokeWidth={2} dot={false} strokeDasharray="4 2" />
              </LineChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Processing latency */}
          <ChartCard title={t("monitoring.chart.latency")} height={170} sample>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={METRICS.latency[0].data.map((d, i) => ({ ts: d.ts, avg: d.value, p99: METRICS.latency[1].data[i]?.value ?? 0 }))} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={v => new Date(v).toLocaleDateString("en", { day: "numeric" })} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} />
                <Line type="monotone" dataKey="avg" name="Avg" stroke={tokens.accent.blue} strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="p99" name="P99" stroke={tokens.accent.amber} strokeWidth={1.5} dot={false} strokeDasharray="4 2" />
              </LineChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Storage growth */}
          <ChartCard title={t("monitoring.chart.storage")} height={170} sample>
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={METRICS.storage[0].data} margin={{ top: 5, right: 5, bottom: 0, left: -10 }}>
                <defs>
                  <linearGradient id="storageGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={tokens.accent.teal} stopOpacity={0.3} />
                    <stop offset="95%" stopColor={tokens.accent.teal} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={v => new Date(v).toLocaleDateString("en", { day: "numeric" })} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} tickFormatter={v => v.toFixed(1)} />
                <Tooltip {...tooltipStyle} formatter={(v: number) => [v.toFixed(2) + " TB", "Storage"]} />
                <Area type="monotone" dataKey="value" name="Storage" stroke={tokens.accent.teal} fill="url(#storageGrad)" strokeWidth={2} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Agent chat usage */}
          <ChartCard title={t("monitoring.chart.agentUsage")} height={170} sample>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={METRICS.chatUsage[0].data.map((d, i) => ({ ts: d.ts, queries: d.value, actions: METRICS.chatUsage[1].data[i]?.value ?? 0 }))} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={v => new Date(v).toLocaleDateString("en", { day: "numeric" })} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} />
                <Bar dataKey="queries" name="Queries" fill={tokens.primary.main} radius={[2, 2, 0, 0]} />
                <Bar dataKey="actions" name="Actions" fill={tokens.accent.blue} radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>
        </Box>
      </Box>
    </Box>
  );
}
