import React, { useCallback, useEffect, useState } from "react";
import {
  Box, Typography, Chip, IconButton, Menu, MenuItem, Divider, Tooltip, Paper,
  TextField, InputAdornment, FormControl, Select, SelectChangeEvent, CircularProgress,
  Dialog, DialogTitle, DialogContent, DialogActions, Button, Autocomplete,
} from "@mui/material";
import {
  MemoryOutlined, StorageOutlined,
  MoreVertOutlined, PauseOutlined, PlayArrowOutlined,
  StopOutlined, RestartAltOutlined, DnsOutlined,
  SearchOutlined, HelpOutlineOutlined, TuneOutlined, DeleteOutlineOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { useNodeRegistry } from "../../context/NodeRegistryContext";
import {
  listProcessors, Processor, updateProcessorRestrictions, forgetProcessor,
} from "../../api/processors";
import { subscribeProcessorEvents, ProcessorEventMessage } from "../../api/pipelineEvents";

interface WorkerNode {
  id: string;
  name: string;
  host: string;
  priority: number;
  status: "online" | "offline" | "starting" | "terminating" | "paused";
  stats: { cpu: number; gpu: number; io: number; memory: number };
  capabilities: ("GPU" | "CPU" | "IO")[];
  nodeWhitelist: string[];
  nodeBlacklist: string[];
  persisted: boolean;
  lastSeen?: string;
}

// ── Map a REST/live processor snapshot to the card render shape ────────────
function pct(v?: number): number {
  return Math.max(0, Math.min(100, Math.round(v ?? 0)));
}

function memoryPct(p: Processor): number {
  const used = p.systemStatus?.memoryUsed;
  const total = p.systemStatus?.memoryTotal;
  if (!used || !total || total <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((used / total) * 100)));
}

function processorToWorker(p: Processor): WorkerNode {
  return {
    id: p.nodeId,
    name: p.name,
    host: p.host ?? "",
    priority: p.priority ?? 0,
    status: (p.state ? p.state.toLowerCase() : "offline") as WorkerNode["status"],
    stats: {
      cpu: pct(p.systemStatus?.cpuLoad),
      gpu: pct(p.systemStatus?.gpuLoad),
      io: pct(p.systemStatus?.ioLoad),
      memory: memoryPct(p),
    },
    capabilities: p.capabilities ?? [],
    nodeWhitelist: p.nodeWhitelist ?? [],
    nodeBlacklist: p.nodeBlacklist ?? [],
    persisted: p.persisted ?? false,
    lastSeen: p.lastSeen,
  };
}

// ── Relative "last seen" formatting ────────────────────────────────────────
function formatLastSeen(iso: string | undefined, now: number): string | null {
  if (!iso) return null;
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return null;
  const secs = Math.max(0, Math.round((now - then) / 1000));
  if (secs < 5) return "just now";
  if (secs < 60) return `${secs}s ago`;
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}h ago`;
}

/** A worker is considered stale when no signal has arrived for this long. */
const STALE_AFTER_MS = 30_000;

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

// ── Node restrictions editor ───────────────────────────────────────────────
function RestrictionsDialog({ worker, nodeKinds, onClose, onSave }: {
  worker: WorkerNode;
  nodeKinds: string[];
  onClose: () => void;
  onSave: (id: string, whitelist: string[], blacklist: string[]) => Promise<void>;
}) {
  const { t } = useTranslation();
  const [whitelist, setWhitelist] = useState<string[]>(worker.nodeWhitelist);
  const [blacklist, setBlacklist] = useState<string[]>(worker.nodeBlacklist);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      await onSave(worker.id, whitelist, blacklist);
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="worker-restrictions-dialog">
      <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700 }}>
        {t("cortex.restrictions.title", { name: worker.name })}
      </DialogTitle>
      <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2.5, pt: 1 }}>
        <Autocomplete
          multiple
          options={nodeKinds}
          value={whitelist}
          onChange={(_e, v) => setWhitelist(v)}
          data-testid="worker-whitelist-input"
          renderInput={(params) => (
            <TextField {...params} label={t("cortex.restrictions.whitelist")} placeholder={t("cortex.restrictions.whitelistPlaceholder")} size="small" />
          )}
        />
        <Autocomplete
          multiple
          options={nodeKinds}
          value={blacklist}
          onChange={(_e, v) => setBlacklist(v)}
          data-testid="worker-blacklist-input"
          renderInput={(params) => (
            <TextField {...params} label={t("cortex.restrictions.blacklist")} placeholder={t("cortex.restrictions.blacklistPlaceholder")} size="small" />
          )}
        />
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={saving} size="small">{t("cortex.restrictions.cancel")}</Button>
        <Button onClick={handleSave} disabled={saving} variant="contained" size="small" data-testid="worker-restrictions-save">
          {saving ? <CircularProgress size={16} /> : t("cortex.restrictions.save")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ── Worker Card ───────────────────────────────────────────────────────────
function WorkerCard({ worker, now, nodeKinds, onChangeStatus, onSaveRestrictions, onForget }: {
  worker: WorkerNode;
  now: number;
  nodeKinds: string[];
  onChangeStatus: (id: string, status: WorkerNode["status"]) => void;
  onSaveRestrictions: (id: string, whitelist: string[], blacklist: string[]) => Promise<void>;
  onForget: (id: string) => void;
}) {
  const [menuAnchor, setMenuAnchor] = useState<null | HTMLElement>(null);
  const [editing, setEditing] = useState(false);
  const sc = statusColor[worker.status];
  const { t } = useTranslation();
  const lastSeenLabel = formatLastSeen(worker.lastSeen, now);
  const stale = worker.status === "online" && worker.lastSeen != null
    && !Number.isNaN(Date.parse(worker.lastSeen))
    && now - Date.parse(worker.lastSeen) > STALE_AFTER_MS;

  return (
    <Paper
      elevation={0}
      data-testid={`worker-card-${worker.id}`}
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
          <Chip data-testid={`worker-status-${worker.id}`} label={worker.status} size="small" sx={{ height: 18, fontSize: "0.64rem", fontWeight: 600, bgcolor: `${sc}18`, color: sc }} />
          {worker.status === "offline" && worker.persisted && (
            <Tooltip title={t("cortex.badge.persistedTooltip")} arrow>
              <Chip
                data-testid={`worker-badge-persisted-${worker.id}`}
                label={t("cortex.badge.persisted")}
                size="small"
                sx={{ height: 18, fontSize: "0.62rem", fontWeight: 600, bgcolor: `${tokens.primary.main}18`, color: tokens.primary.main }}
              />
            </Tooltip>
          )}
          <HeartbeatIndicator active={worker.status === "online"} />
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 0.75 }}>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem", fontFamily: "monospace" }}>{worker.host}</Typography>
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>P{worker.priority}</Typography>
          {lastSeenLabel && (
            <Tooltip title={t("cortex.lastSeen.tooltip")} arrow>
              <Typography variant="caption" sx={{ fontSize: "0.68rem", color: stale ? tokens.accent.amber : tokens.text.tertiary }}>
                {stale ? "⚠ " : ""}{t("cortex.lastSeen.label", { time: lastSeenLabel })}
              </Typography>
            </Tooltip>
          )}
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

      <Box sx={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 0.5, flexShrink: 0 }}>
        <Box sx={{ display: "flex", gap: 0.5 }}>
          {worker.capabilities.map(cap => (
            <Chip key={cap} label={cap} size="small" sx={{ height: 20, fontSize: "0.65rem", fontWeight: 600, bgcolor: tokens.bg.overlay, color: tokens.text.secondary, borderRadius: tokens.radius.sm }} />
          ))}
        </Box>
        {worker.nodeWhitelist.length > 0 && (
          <Box data-testid={`worker-whitelist-${worker.id}`} sx={{ display: "flex", gap: 0.5, flexWrap: "wrap", justifyContent: "flex-end", maxWidth: 260 }}>
            {worker.nodeWhitelist.map(kind => (
              <Tooltip key={`wl-${kind}`} title={t("cortex.restrictions.allowTooltip")} arrow>
                <Chip label={kind} size="small" sx={{ height: 18, fontSize: "0.6rem", fontWeight: 600, bgcolor: `${tokens.accent.green}18`, color: tokens.accent.green, borderRadius: tokens.radius.sm }} />
              </Tooltip>
            ))}
          </Box>
        )}
        {worker.nodeBlacklist.length > 0 && (
          <Box data-testid={`worker-blacklist-${worker.id}`} sx={{ display: "flex", gap: 0.5, flexWrap: "wrap", justifyContent: "flex-end", maxWidth: 260 }}>
            {worker.nodeBlacklist.map(kind => (
              <Tooltip key={`bl-${kind}`} title={t("cortex.restrictions.denyTooltip")} arrow>
                <Chip label={kind} size="small" sx={{ height: 18, fontSize: "0.6rem", fontWeight: 600, bgcolor: `${tokens.accent.red}18`, color: tokens.accent.red, borderRadius: tokens.radius.sm }} />
              </Tooltip>
            ))}
          </Box>
        )}
      </Box>

      <IconButton size="small" onClick={e => setMenuAnchor(e.currentTarget)} sx={{ flexShrink: 0 }}>
        <MoreVertOutlined sx={{ fontSize: 18 }} />
      </IconButton>
      <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => setMenuAnchor(null)}>
        <MenuItem data-testid={`worker-menu-restrictions-${worker.id}`} onClick={() => { setMenuAnchor(null); setEditing(true); }} sx={{ gap: 1.25, fontSize: "0.82rem" }}>
          <TuneOutlined sx={{ fontSize: 16 }} /> {t("cortex.menu.restrictions")}
        </MenuItem>
        <Divider />
        {worker.status === "online" && (
          <MenuItem onClick={() => { setMenuAnchor(null); onChangeStatus(worker.id, "paused"); }} sx={{ gap: 1.25, fontSize: "0.82rem" }}>
            <PauseOutlined sx={{ fontSize: 16 }} /> {t("cortex.menu.pause")}
          </MenuItem>
        )}
        {worker.status === "paused" && (
          <MenuItem onClick={() => { setMenuAnchor(null); onChangeStatus(worker.id, "online"); }} sx={{ gap: 1.25, fontSize: "0.82rem" }}>
            <PlayArrowOutlined sx={{ fontSize: 16 }} /> {t("cortex.menu.resume")}
          </MenuItem>
        )}
        {(worker.status === "online" || worker.status === "paused") && (
          <MenuItem onClick={() => { setMenuAnchor(null); onChangeStatus(worker.id, "online"); }} sx={{ gap: 1.25, fontSize: "0.82rem" }}>
            <RestartAltOutlined sx={{ fontSize: 16 }} /> {t("cortex.menu.restart")}
          </MenuItem>
        )}
        {(worker.status === "online" || worker.status === "paused") && (
          <MenuItem onClick={() => { setMenuAnchor(null); onChangeStatus(worker.id, "terminating"); }} sx={{ gap: 1.25, fontSize: "0.82rem", color: tokens.accent.red }}>
            <StopOutlined sx={{ fontSize: 16 }} /> {t("cortex.menu.terminate")}
          </MenuItem>
        )}
        {worker.status === "offline" && worker.persisted && (
          <MenuItem data-testid={`worker-menu-forget-${worker.id}`} onClick={() => { setMenuAnchor(null); onForget(worker.id); }} sx={{ gap: 1.25, fontSize: "0.82rem", color: tokens.accent.red }}>
            <DeleteOutlineOutlined sx={{ fontSize: 16 }} /> {t("cortex.menu.forget")}
          </MenuItem>
        )}
      </Menu>
      {editing && (
        <RestrictionsDialog
          worker={worker}
          nodeKinds={nodeKinds}
          onClose={() => setEditing(false)}
          onSave={onSaveRestrictions}
        />
      )}
    </Paper>
  );
}

// ── Main Cortex View ──────────────────────────────────────────────────────
export default function CortexView() {
  const { token } = useAuth();
  const { showToast } = useToast();
  const { descriptors } = useNodeRegistry();
  // Guard against a malformed/empty descriptors payload so the whole view never blanks.
  const nodeKinds = (descriptors ?? []).map(d => d.kind).sort();
  const [workers, setWorkers] = useState<WorkerNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<WorkerNode["status"] | "all">("all");
  const [capFilter, setCapFilter] = useState<"all" | "GPU" | "CPU" | "IO">("all");
  const [filtered, setFiltered] = useState<WorkerNode[]>([]);
  const [now, setNow] = useState<number>(() => Date.now());
  const { t } = useTranslation();

  // Initial REST snapshot.
  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    setLoading(true);
    listProcessors(token)
      .then((resp) => {
        if (cancelled) return;
        setWorkers((resp.data ?? []).map(processorToWorker));
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error("Failed to load processors", err);
        setError(String(err?.message ?? err));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token]);

  // Live processor events over the shared UI socket.
  const handleEvent = useCallback((event: ProcessorEventMessage) => {
    setWorkers((prev) => {
      switch (event.type) {
        case "REGISTERED":
        case "STATE_CHANGED":
        case "STATUS_UPDATED": {
          if (!event.processor) return prev;
          const worker = processorToWorker(event.processor);
          const idx = prev.findIndex((w) => w.id === worker.id);
          if (idx === -1) return [...prev, worker];
          const next = prev.slice();
          next[idx] = worker;
          return next;
        }
        case "HEARTBEAT": {
          const idx = prev.findIndex((w) => w.id === event.nodeId);
          if (idx === -1) return prev;
          const next = prev.slice();
          next[idx] = { ...next[idx], lastSeen: event.lastSeen ?? next[idx].lastSeen };
          return next;
        }
        case "DISCONNECTED": {
          // Keep the card (offline, persisted) rather than dropping it.
          const idx = prev.findIndex((w) => w.id === event.nodeId);
          if (idx === -1) return prev;
          const next = prev.slice();
          next[idx] = { ...next[idx], status: "offline" };
          return next;
        }
        default:
          return prev;
      }
    });
  }, []);

  useEffect(() => subscribeProcessorEvents(handleEvent, token), [handleEvent, token]);

  // Tick a clock so relative "last seen" / staleness re-renders without new events.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(id);
  }, []);

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

  const handleSaveRestrictions = async (id: string, whitelist: string[], blacklist: string[]) => {
    if (!token) return;
    try {
      const updated = await updateProcessorRestrictions(token, id, { nodeWhitelist: whitelist, nodeBlacklist: blacklist });
      // Optimistically reflect the persisted restriction on the card.
      setWorkers(prev => prev.map(w => w.id === id
        ? { ...w, nodeWhitelist: updated.nodeWhitelist ?? whitelist, nodeBlacklist: updated.nodeBlacklist ?? blacklist, persisted: updated.persisted ?? true }
        : w));
      showToast(t("cortex.restrictions.saved"), "success");
    } catch (err) {
      showToast(t("cortex.restrictions.saveError", { error: (err as Error).message }), "error");
      throw err;
    }
  };

  const handleForget = async (id: string) => {
    if (!token) return;
    try {
      await forgetProcessor(token, id);
      setWorkers(prev => prev.filter(w => w.id !== id));
      showToast(t("cortex.forget.done"), "success");
    } catch (err) {
      showToast(t("cortex.forget.error", { error: (err as Error).message }), "error");
    }
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
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>{t("cortex.title")}</Typography>
              <Tooltip title={t("cortex.tooltip.info")} arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
            </Box>
            <Typography variant="caption" color="text.secondary">{t("cortex.count.online", { online: onlineCount, total: workers.length })}</Typography>
          </Box>
        </Box>

        <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
          <TextField
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={t("cortex.search.placeholder")}
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
              <MenuItem value="all">{t("cortex.filter.allStatus")}</MenuItem>
              <MenuItem value="online">{t("cortex.filter.online")}</MenuItem>
              <MenuItem value="paused">{t("cortex.filter.paused")}</MenuItem>
              <MenuItem value="starting">{t("cortex.filter.starting")}</MenuItem>
              <MenuItem value="offline">{t("cortex.filter.offline")}</MenuItem>
              <MenuItem value="terminating">{t("cortex.filter.terminating")}</MenuItem>
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 90 }}>
            <Select
              value={capFilter}
              onChange={(e: SelectChangeEvent) => setCapFilter(e.target.value as any)}
              displayEmpty
              sx={{ fontSize: "0.78rem", bgcolor: tokens.bg.elevated }}
            >
              <MenuItem value="all">{t("cortex.filter.allCaps")}</MenuItem>
              <MenuItem value="GPU">GPU</MenuItem>
              <MenuItem value="CPU">CPU</MenuItem>
              <MenuItem value="IO">IO</MenuItem>
            </Select>
          </FormControl>
        </Box>

        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
            {t("cortex.count.workers", { count: filtered.length })}
          </Typography>
          {(statusFilter !== "all" || capFilter !== "all" || query) && (
            <Chip
              label={t("cortex.chip.clearFilters")}
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
          {loading ? (
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1.5 }}>
              <CircularProgress size={28} />
              <Typography variant="body2" color="text.secondary">{t("cortex.loading")}</Typography>
            </Box>
          ) : error ? (
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
              <DnsOutlined sx={{ fontSize: 36, color: tokens.accent.red }} />
              <Typography variant="body2" color="text.secondary">{t("cortex.error")}</Typography>
            </Box>
          ) : filtered.length === 0 ? (
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 200, gap: 1 }}>
              <DnsOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
              <Typography variant="body2" color="text.secondary">{t("cortex.empty")}</Typography>
            </Box>
          ) : (
            filtered.map(w => (
              <WorkerCard
                key={w.id}
                worker={w}
                now={now}
                nodeKinds={nodeKinds}
                onChangeStatus={handleChangeStatus}
                onSaveRestrictions={handleSaveRestrictions}
                onForget={handleForget}
              />
            ))
          )}
        </Box>
      </Box>
    </Box>
  );
}
