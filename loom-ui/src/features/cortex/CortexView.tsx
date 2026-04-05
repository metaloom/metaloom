import React, { useState } from "react";
import {
  Box, Typography, Chip, IconButton, Menu, MenuItem, Divider, Tooltip, Paper,
} from "@mui/material";
import {
  MemoryOutlined, StorageOutlined, GpsFixedOutlined,
  MoreVertOutlined, PauseOutlined, PlayArrowOutlined,
  StopOutlined, RestartAltOutlined, DnsOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";

interface WorkerNode {
  id: string;
  name: string;
  host: string;
  priority: number;
  status: "online" | "offline" | "starting" | "terminating" | "paused";
  stats: { cpu: number; gpu: number; io: number; memory: number };
  capabilities: ("GPU" | "CPU" | "IO")[];
}

const WORKERS: WorkerNode[] = [
  { id: "w1", name: "cortex-gpu-01", host: "10.0.1.10:9090", priority: 1, status: "online", stats: { cpu: 42, gpu: 78, io: 31, memory: 55 }, capabilities: ["GPU", "CPU"] },
  { id: "w2", name: "cortex-gpu-02", host: "10.0.1.11:9090", priority: 2, status: "online", stats: { cpu: 68, gpu: 91, io: 45, memory: 72 }, capabilities: ["GPU", "CPU", "IO"] },
  { id: "w3", name: "cortex-cpu-01", host: "10.0.1.20:9090", priority: 3, status: "online", stats: { cpu: 25, gpu: 0, io: 60, memory: 38 }, capabilities: ["CPU", "IO"] },
  { id: "w4", name: "cortex-cpu-02", host: "10.0.1.21:9090", priority: 4, status: "paused", stats: { cpu: 5, gpu: 0, io: 8, memory: 22 }, capabilities: ["CPU", "IO"] },
  { id: "w5", name: "cortex-io-01", host: "10.0.1.30:9090", priority: 5, status: "starting", stats: { cpu: 12, gpu: 0, io: 15, memory: 18 }, capabilities: ["IO"] },
  { id: "w6", name: "cortex-gpu-03", host: "10.0.1.12:9090", priority: 1, status: "offline", stats: { cpu: 0, gpu: 0, io: 0, memory: 0 }, capabilities: ["GPU", "CPU"] },
];

const statusColor: Record<WorkerNode["status"], string> = {
  online: tokens.accent.green,
  offline: tokens.text.tertiary,
  starting: tokens.accent.amber,
  terminating: tokens.accent.red,
  paused: tokens.accent.blue,
};

const capColor: Record<string, string> = {
  GPU: "#e040fb",
  CPU: tokens.primary.main,
  IO: tokens.accent.amber,
};

// ── Health Meter SVG — concentric ring arcs ───────────────────────────────
function HealthMeter({ cpu, gpu, io, size = 48 }: { cpu: number; gpu: number; io: number; size?: number }) {
  const cx = size / 2;
  const cy = size / 2;

  const rings = [
    { value: gpu, color: "#e040fb", label: "GPU", radius: size / 2 - 3 },
    { value: cpu, color: tokens.primary.main, label: "CPU", radius: size / 2 - 8 },
    { value: io, color: tokens.accent.amber, label: "IO", radius: size / 2 - 13 },
  ].filter(r => r.value > 0 || r.label === "CPU");

  return (
    <Tooltip title={`CPU: ${cpu}% · GPU: ${gpu}% · IO: ${io}%`}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        {/* Background circle */}
        <circle cx={cx} cy={cy} r={size / 2 - 8} fill={tokens.bg.overlay} />
        {/* Ring backgrounds */}
        {rings.map(r => (
          <circle
            key={`bg-${r.label}`}
            cx={cx}
            cy={cy}
            r={r.radius}
            fill="none"
            stroke={`${r.color}22`}
            strokeWidth={3.5}
          />
        ))}
        {/* Ring arcs */}
        {rings.map(r => {
          const circumference = 2 * Math.PI * r.radius;
          const offset = circumference - (r.value / 100) * circumference;
          return (
            <circle
              key={r.label}
              cx={cx}
              cy={cy}
              r={r.radius}
              fill="none"
              stroke={r.color}
              strokeWidth={3.5}
              strokeDasharray={circumference}
              strokeDashoffset={offset}
              strokeLinecap="round"
              transform={`rotate(-90 ${cx} ${cy})`}
              style={{ transition: "stroke-dashoffset 400ms ease" }}
            />
          );
        })}
        {/* Center health score */}
        <text x={cx} y={cy + 1} textAnchor="middle" dominantBaseline="middle" fontSize={size * 0.22} fontWeight={700} fill={tokens.text.primary}>
          {Math.round((cpu + gpu + io) / (gpu > 0 ? 3 : 2))}%
        </text>
      </svg>
    </Tooltip>
  );
}

// ── Worker Card ───────────────────────────────────────────────────────────
function WorkerCard({ worker, onChangeStatus }: { worker: WorkerNode; onChangeStatus: (id: string, status: WorkerNode["status"]) => void }) {
  const [menuAnchor, setMenuAnchor] = useState<null | HTMLElement>(null);
  const sc = statusColor[worker.status];

  return (
    <Paper
      elevation={0}
      sx={{
        display: "flex",
        alignItems: "center",
        gap: 2,
        p: 2,
        border: `1px solid ${tokens.border.subtle}`,
        borderRadius: tokens.radius.lg,
        bgcolor: tokens.bg.surface,
        opacity: worker.status === "offline" ? 0.5 : 1,
        transition: "opacity 200ms ease",
      }}
    >
      {/* Health meter */}
      <HealthMeter cpu={worker.stats.cpu} gpu={worker.stats.gpu} io={worker.stats.io} size={52} />

      {/* Info */}
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
          <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.88rem" }}>{worker.name}</Typography>
          <Chip
            label={worker.status}
            size="small"
            sx={{
              height: 18, fontSize: "0.64rem", fontWeight: 600,
              bgcolor: `${sc}22`, color: sc,
            }}
          />
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 0.75 }}>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem", fontFamily: "monospace" }}>
            {worker.host}
          </Typography>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
            Priority: {worker.priority}
          </Typography>
        </Box>

        {/* Stats bar */}
        <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <MemoryOutlined sx={{ fontSize: 12, color: tokens.primary.main }} />
            <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.secondary }}>CPU {worker.stats.cpu}%</Typography>
          </Box>
          {worker.capabilities.includes("GPU") && (
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
              <GpsFixedOutlined sx={{ fontSize: 12, color: "#e040fb" }} />
              <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.secondary }}>GPU {worker.stats.gpu}%</Typography>
            </Box>
          )}
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <StorageOutlined sx={{ fontSize: 12, color: tokens.accent.amber }} />
            <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.secondary }}>IO {worker.stats.io}%</Typography>
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <MemoryOutlined sx={{ fontSize: 12, color: tokens.accent.teal }} />
            <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.secondary }}>MEM {worker.stats.memory}%</Typography>
          </Box>
        </Box>
      </Box>

      {/* Capabilities */}
      <Box sx={{ display: "flex", gap: 0.5, flexShrink: 0 }}>
        {worker.capabilities.map(cap => (
          <Chip
            key={cap}
            label={cap}
            size="small"
            sx={{
              height: 20, fontSize: "0.65rem", fontWeight: 700,
              bgcolor: `${capColor[cap]}18`, color: capColor[cap],
              borderRadius: tokens.radius.sm,
            }}
          />
        ))}
      </Box>

      {/* Burger menu */}
      <IconButton size="small" onClick={e => setMenuAnchor(e.currentTarget)} sx={{ flexShrink: 0 }}>
        <MoreVertOutlined sx={{ fontSize: 18 }} />
      </IconButton>
      <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => setMenuAnchor(null)}>
        {worker.status === "online" && (
          <MenuItem onClick={() => { setMenuAnchor(null); onChangeStatus(worker.id, "paused"); }} sx={{ gap: 1.25, fontSize: "0.82rem" }}>
            <PauseOutlined sx={{ fontSize: 16 }} /> Pause
          </MenuItem>
        )}
        {worker.status === "paused" && (
          <MenuItem onClick={() => { setMenuAnchor(null); onChangeStatus(worker.id, "online"); }} sx={{ gap: 1.25, fontSize: "0.82rem" }}>
            <PlayArrowOutlined sx={{ fontSize: 16 }} /> Resume
          </MenuItem>
        )}
        {(worker.status === "online" || worker.status === "paused") && (
          <MenuItem onClick={() => { setMenuAnchor(null); onChangeStatus(worker.id, "online"); }} sx={{ gap: 1.25, fontSize: "0.82rem" }}>
            <RestartAltOutlined sx={{ fontSize: 16 }} /> Restart
          </MenuItem>
        )}
        <Divider />
        <MenuItem onClick={() => { setMenuAnchor(null); onChangeStatus(worker.id, "terminating"); }} sx={{ gap: 1.25, fontSize: "0.82rem", color: tokens.accent.red }}>
          <StopOutlined sx={{ fontSize: 16 }} /> Terminate
        </MenuItem>
      </Menu>
    </Paper>
  );
}

// ── Main Cortex View ──────────────────────────────────────────────────────
export default function CortexView() {
  const [workers, setWorkers] = useState<WorkerNode[]>(WORKERS);

  const handleChangeStatus = (id: string, status: WorkerNode["status"]) => {
    setWorkers(prev => prev.map(w => w.id === id ? { ...w, status } : w));
  };

  const onlineCount = workers.filter(w => w.status === "online").length;
  const totalCount = workers.length;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 0.25 }}>
          <DnsOutlined sx={{ color: tokens.primary.main }} />
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Cortex Workers</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary">
          {onlineCount} / {totalCount} nodes online · Registered processor nodes
        </Typography>
      </Box>

      {/* Worker list */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, maxWidth: 900 }}>
          {workers.map(w => (
            <WorkerCard key={w.id} worker={w} onChangeStatus={handleChangeStatus} />
          ))}
        </Box>
      </Box>
    </Box>
  );
}
