import React, { useEffect, useState } from "react";
import {
  Box, Typography, Chip, IconButton, Menu, MenuItem, Divider, Tooltip, Paper,
  TextField, InputAdornment, FormControl, Select, SelectChangeEvent, ToggleButtonGroup, ToggleButton,
} from "@mui/material";
import {
  MemoryOutlined, StorageOutlined,
  MoreVertOutlined, PauseOutlined, PlayArrowOutlined,
  StopOutlined, RestartAltOutlined, DnsOutlined,
  SearchOutlined, FilterListOutlined, HelpOutlineOutlined,
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
  paused: tokens.text.secondary,
};

// Muted, single-hue palette for rings — all based on primary teal with varying opacity
const ringColor = {
  gpu: tokens.primary.main,
  cpu: tokens.primary.light,
  io: tokens.text.secondary,
};

// ── Health Meter SVG — concentric ring arcs ───────────────────────────────
function HealthMeter({ cpu, gpu, io, size = 48 }: { cpu: number; gpu: number; io: number; size?: number }) {
  const cx = size / 2;
  const cy = size / 2;

  const rings = [
    { value: gpu, color: ringColor.gpu, label: "GPU", radius: size / 2 - 3 },
    { value: cpu, color: ringColor.cpu, label: "CPU", radius: size / 2 - 8 },
    { value: io, color: ringColor.io, label: "IO", radius: size / 2 - 13 },
  ].filter(r => r.value > 0 || r.label === "CPU");

  const avg = Math.round(rings.reduce((s, r) => s + r.value, 0) / rings.length);

  return (
    <Tooltip title={`CPU: ${cpu}% · GPU: ${gpu}% · IO: ${io}%`}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle cx={cx} cy={cy} r={size / 2 - 8} fill={tokens.bg.overlay} />
        {rings.map(r => (
          <circle key={`bg-${r.label}`} cx={cx} cy={cy} r={r.radius} fill="none" stroke={`${r.color}18`} strokeWidth={3.5} />
        ))}
        {rings.map(r => {
          const circumference = 2 * Math.PI * r.radius;
          const offset = circumference - (r.value / 100) * circumference;
          return (
            <circle
              key={r.label} cx={cx} cy={cy} r={r.radius} fill="none"
              stroke={r.color} strokeWidth={3.5} strokeDasharray={circumference}
              strokeDashoffset={offset} strokeLinecap="round"
              transform={`rotate(-90 ${cx} ${cy})`}
              style={{ transition: "stroke-dashoffset 400ms ease" }}
            />
          );
        })}
        <text x={cx} y={cy + 1} textAnchor="middle" dominantBaseline="middle" fontSize={size * 0.22} fontWeight={700} fill={tokens.text.primary}>
          {avg}%
        </text>
      </svg>
    </Tooltip>
  );
}

// ── Heartbeat EKG animation ──────────────────────────────────────────────
const heartbeatKeyframes = `
@keyframes heartbeat-trace {
  0% { stroke-dashoffset: 120; }
  100% { stroke-dashoffset: 0; }
}
`;

function HeartbeatIndicator({ active }: { active: boolean }) {
  if (!active) return null;
  return (
    <>
      <style>{heartbeatKeyframes}</style>
      <svg width={36} height={16} viewBox="0 0 36 16" style={{ display: "block" }}>
        <polyline
          points="0,8 6,8 9,8 11,2 13,14 15,4 17,10 19,8 24,8 26,8 28,3 30,13 32,8 36,8"
          fill="none"
          stroke={tokens.accent.green}
          strokeWidth={1.5}
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeDasharray="120"
          style={{ animation: "heartbeat-trace 1.5s linear infinite" }}
        />
      </svg>
    </>
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
        display: "flex", alignItems: "center", gap: 2, p: 2,
        border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.lg,
        bgcolor: tokens.bg.surface, opacity: worker.status === "offline" ? 0.45 : 1,
        transition: "opacity 200ms ease",
      }}
    >
      <HealthMeter cpu={worker.stats.cpu} gpu={worker.stats.gpu} io={worker.stats.io} size={52} />

      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
          <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.88rem" }}>{worker.name}</Typography>
          <Chip label={worker.status} size="small" sx={{ height: 18, fontSize: "0.64rem", fontWeight: 600, bgcolor: `${sc}18`, color: sc }} />
          <HeartbeatIndicator active={worker.status === "online"} />
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 0.75 }}>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem", fontFamily: "monospace" }}>{worker.host}</Typography>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>P{worker.priority}</Typography>
        </Box>
        <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
          {[
            { label: "CPU", value: worker.stats.cpu, icon: <MemoryOutlined sx={{ fontSize: 12, color: tokens.text.tertiary }} /> },
            ...(worker.capabilities.includes("GPU") ? [{ label: "GPU", value: worker.stats.gpu, icon: <MemoryOutlined sx={{ fontSize: 12, color: tokens.text.tertiary }} /> }] : []),
            { label: "IO", value: worker.stats.io, icon: <StorageOutlined sx={{ fontSize: 12, color: tokens.text.tertiary }} /> },
            { label: "MEM", value: worker.stats.memory, icon: <MemoryOutlined sx={{ fontSize: 12, color: tokens.text.tertiary }} /> },
          ].map(s => (
            <Box key={s.label} sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
              {s.icon}
              <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.secondary }}>{s.label} {s.value}%</Typography>
            </Box>
          ))}
        </Box>
      </Box>

      <Box sx={{ display: "flex", gap: 0.5, flexShrink: 0 }}>
        {worker.capabilities.map(cap => (
          <Chip key={cap} label={cap} size="small" sx={{ height: 20, fontSize: "0.65rem", fontWeight: 600, bgcolor: tokens.bg.overlay, color: tokens.text.secondary, borderRadius: tokens.radius.sm }} />
        ))}
      </Box>

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
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<WorkerNode["status"] | "all">("all");
  const [capFilter, setCapFilter] = useState<"all" | "GPU" | "CPU" | "IO">("all");
  const [filtered, setFiltered] = useState<WorkerNode[]>(WORKERS);

  useEffect(() => {
    let res = workers;
    if (statusFilter !== "all") res = res.filter(w => w.status === statusFilter);
    if (capFilter !== "all") res = res.filter(w => w.capabilities.includes(capFilter as any));
    if (query.trim()) {
      const q = query.toLowerCase();
      res = res.filter(w => w.name.toLowerCase().includes(q) || w.host.includes(q));
    }
    setFiltered(res);
  }, [workers, query, statusFilter, capFilter]);

  const handleChangeStatus = (id: string, status: WorkerNode["status"]) => {
    setWorkers(prev => prev.map(w => w.id === id ? { ...w, status } : w));
  };

  const onlineCount = workers.filter(w => w.status === "online").length;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Toolbar */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", flexDirection: "column", gap: 1.25 }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <Box>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.25 }}>
              <DnsOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Cortex Workers</Typography>
              <Tooltip title="Cortex workers are distributed processing nodes that handle media analysis, transcoding, and AI inference tasks." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
            </Box>
            <Typography variant="caption" color="text.secondary">{onlineCount} / {workers.length} online</Typography>
          </Box>
        </Box>

        <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
          <TextField
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search workers, hosts…"
            size="small"
            sx={{ flex: 1, minWidth: 180 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                </InputAdornment>
              ),
            }}
          />

          <FormControl size="small" sx={{ minWidth: 100 }}>
            <Select
              value={statusFilter}
              onChange={(e: SelectChangeEvent) => setStatusFilter(e.target.value as WorkerNode["status"] | "all")}
              displayEmpty
              sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
            >
              <MenuItem value="all">All Status</MenuItem>
              <MenuItem value="online">Online</MenuItem>
              <MenuItem value="paused">Paused</MenuItem>
              <MenuItem value="starting">Starting</MenuItem>
              <MenuItem value="offline">Offline</MenuItem>
              <MenuItem value="terminating">Terminating</MenuItem>
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 90 }}>
            <Select
              value={capFilter}
              onChange={(e: SelectChangeEvent) => setCapFilter(e.target.value as any)}
              displayEmpty
              sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
            >
              <MenuItem value="all">All Caps</MenuItem>
              <MenuItem value="GPU">GPU</MenuItem>
              <MenuItem value="CPU">CPU</MenuItem>
              <MenuItem value="IO">IO</MenuItem>
            </Select>
          </FormControl>
        </Box>

        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
            {filtered.length} workers
          </Typography>
          {(statusFilter !== "all" || capFilter !== "all" || query) && (
            <Chip
              label="Clear filters"
              size="small"
              onDelete={() => { setStatusFilter("all"); setCapFilter("all"); setQuery(""); }}
              sx={{ height: 18, fontSize: "0.65rem" }}
            />
          )}
        </Box>
      </Box>

      {/* Worker list */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5, maxWidth: 900 }}>
          {filtered.length === 0 ? (
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
              <DnsOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
              <Typography variant="body2" color="text.secondary">No workers match your filters</Typography>
            </Box>
          ) : (
            filtered.map(w => (
              <WorkerCard key={w.id} worker={w} onChangeStatus={handleChangeStatus} />
            ))
          )}
        </Box>
      </Box>
    </Box>
  );
}
