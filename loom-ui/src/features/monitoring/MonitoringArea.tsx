import React from "react";
import {
  Box, Typography, Paper, Grid,
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

// ── KPI Card ──────────────────────────────────────────────────────────────
function KPICard({
  title, value, unit, delta, color, icon, subtitle,
}: {
  title: string;
  value: string | number;
  unit?: string;
  delta?: string;
  color: string;
  icon: React.ReactNode;
  subtitle?: string;
}) {
  return (
    <Paper
      elevation={0}
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
          <Typography variant="caption" sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem" }}>
            {title}
          </Typography>
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
function ChartCard({ title, children, height = 160 }: { title: string; children: React.ReactNode; height?: number }) {
  return (
    <Paper
      elevation={0}
      sx={{
        bgcolor: tokens.bg.elevated,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        overflow: "hidden",
      }}
    >
      <Box sx={{ px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.875rem" }}>{title}</Typography>
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
  // Compute KPI snapshot values from latest data points
  const lastIngestion = METRICS.ingestion[0].data.slice(-1)[0]?.value ?? 0;
  const lastLatency = METRICS.latency[0].data.slice(-1)[0]?.value ?? 0;
  const storageLatest = METRICS.storage[0].data.slice(-1)[0]?.value ?? 0;
  const totalRuns = METRICS.pipelineRuns.reduce((acc, s) => acc + (s.data.slice(-7).reduce((a, b) => a + b.value, 0)), 0);
  const openTasks = METRICS.taskBacklog[0].data.slice(-1)[0]?.value ?? 0;
  const chatQueries = METRICS.chatUsage[0].data.reduce((a, b) => a + b.value, 0);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Monitoring & Statistics</Typography>
        <Typography variant="caption" color="text.secondary">14-day rolling metrics</Typography>
      </Box>

      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {/* KPI Row */}
        <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 2, mb: 3 }}>
          <KPICard title="Daily Ingest" value={lastIngestion} unit="assets" delta="+14%" color={tokens.primary.main} icon={<CloudUploadOutlined />} subtitle="Assets ingested today" />
          <KPICard title="Pipeline Runs (7d)" value={totalRuns} unit="runs" delta="+8%" color={tokens.accent.teal} icon={<AccountTreeOutlined />} subtitle="All pipelines" />
          <KPICard title="Avg Latency" value={lastLatency} unit="ms" delta={lastLatency > 400 ? "+5%" : "-3%"} color={tokens.accent.blue} icon={<SpeedOutlined />} subtitle="Processing pipeline" />
          <KPICard title="Storage Used" value={storageLatest.toFixed(1)} unit="TB" color={tokens.accent.amber} icon={<StorageOutlined />} subtitle="Total across libraries" />
          <KPICard title="Open Tasks" value={openTasks} delta="-2%" color={tokens.accent.green} icon={<TaskAltOutlined />} subtitle="Across all projects" />
          <KPICard title="Agent Queries (14d)" value={chatQueries} color={tokens.primary.light} icon={<AutoAwesome />} subtitle="Total Loom Agent queries" />
          <KPICard title="Annotations (14d)" value={METRICS.annotations[0].data.reduce((a, b) => a + b.value, 0)} color={tokens.accent.red} icon={<BookmarkBorderOutlined />} subtitle="New annotations created" />
        </Box>

        {/* Charts Grid */}
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr", lg: "2fr 1fr 1fr" }, gap: 2 }}>
          {/* Ingestion throughput */}
          <ChartCard title="Asset Ingestion (14d)" height={180}>
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

          {/* Pipeline runs */}
          <ChartCard title="Pipeline Runs (14d)" height={180}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={METRICS.pipelineRuns[0].data.map((d, i) => ({ ts: d.ts, success: d.value, failed: METRICS.pipelineRuns[1].data[i]?.value ?? 0 }))} margin={{ top: 5, right: 5, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={tokens.border.subtle} vertical={false} />
                <XAxis dataKey="ts" tickFormatter={v => new Date(v).toLocaleDateString("en", { day: "numeric" })} tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <YAxis tick={{ ...chartStyle, fill: tokens.text.tertiary }} tickLine={false} axisLine={false} />
                <Tooltip {...tooltipStyle} />
                <Bar dataKey="success" name="Success" fill={tokens.accent.green} radius={[2, 2, 0, 0]} stackId="runs" />
                <Bar dataKey="failed" name="Failed" fill={tokens.accent.red} radius={[2, 2, 0, 0]} stackId="runs" />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>

          {/* Task backlog */}
          <ChartCard title="Task Backlog" height={180}>
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
          <ChartCard title="Processing Latency (ms)" height={170}>
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
          <ChartCard title="Storage Growth (TB)" height={170}>
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
          <ChartCard title="Agent Usage (14d)" height={170}>
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
