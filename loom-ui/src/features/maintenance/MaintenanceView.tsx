import React, { useEffect, useState } from "react";
import { Box, Typography, Chip, Divider } from "@mui/material";
import {
  BuildOutlined, StorageOutlined, MemoryOutlined, UpdateOutlined, HistoryOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { getHealth, HealthCheckResponse } from "../../api/health";
import { getInfo, InfoResponse } from "../../api/info";

// Visual state for a status chip: which token palette to paint it with.
type Tone = "green" | "amber" | "red" | "neutral";

function toneStyles(tone: Tone) {
  switch (tone) {
    case "green":
      return { bgcolor: "rgba(52,213,138,0.15)", color: tokens.accent.green };
    case "amber":
      return { bgcolor: "rgba(245,166,35,0.15)", color: tokens.accent.amber };
    case "red":
      return { bgcolor: "rgba(240,84,110,0.15)", color: tokens.accent.red };
    case "neutral":
    default:
      return { bgcolor: tokens.bg.overlay, color: tokens.text.tertiary };
  }
}

function StatusChip({ label, tone, testId }: { label: string; tone: Tone; testId?: string }) {
  return (
    <Chip
      label={label}
      size="small"
      data-testid={testId}
      sx={{
        ...toneStyles(tone),
        fontWeight: 600,
        fontSize: "0.72rem",
        height: 22,
      }}
    />
  );
}

// Cards that have no backend metric yet — rendered but gated as "no metric".
const gatedItems = [
  { key: "storage", label: "Object Storage (S3)", icon: <StorageOutlined sx={{ fontSize: 18 }} /> },
  { key: "memory", label: "Memory", icon: <MemoryOutlined sx={{ fontSize: 18 }} /> },
  { key: "workers", label: "Pipeline Workers", icon: <BuildOutlined sx={{ fontSize: 18 }} /> },
];

/** Map the health payload's `database` field to a display label + tone. */
function databaseStatus(health: HealthCheckResponse | null, failed: boolean): { label: string; tone: Tone } {
  if (failed || !health || !health.database) return { label: "Unknown", tone: "neutral" };
  if (health.database === "UP") return { label: "Healthy", tone: "green" };
  return { label: "Unavailable", tone: "red" };
}

/** Map the overall `status` field to a display label + tone. */
function overallStatus(health: HealthCheckResponse | null, failed: boolean): { label: string; tone: Tone } {
  if (failed || !health) return { label: "Unknown", tone: "neutral" };
  if (health.status === "UP") return { label: "Operational", tone: "green" };
  if (health.status === "DEGRADED") return { label: "Degraded", tone: "amber" };
  return { label: health.status, tone: "neutral" };
}

const cardSx = {
  p: 2.5,
  border: `1px solid ${tokens.border.subtle}`,
  borderRadius: tokens.radius.md,
  bgcolor: tokens.bg.surface,
};

export default function MaintenanceView() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [health, setHealth] = useState<HealthCheckResponse | null>(null);
  const [info, setInfo] = useState<InfoResponse | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    getHealth(token)
      .then((resp) => {
        if (cancelled) return;
        setHealth(resp);
        setFailed(false);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error("Failed to load health status", err);
        setFailed(true);
      });
    // Authoritative instance/system info (DB revision + last-used) is a separate,
    // non-critical endpoint — a failure here must not flip the overall health state.
    getInfo(token)
      .then((resp) => {
        if (cancelled) return;
        setInfo(resp);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error("Failed to load instance info", err);
      });
    return () => { cancelled = true; };
  }, [token]);

  const db = databaseStatus(health, failed);
  const overall = overallStatus(health, failed);
  const version = info?.version ?? health?.version ?? "unknown";
  const checkedAt = health?.timestamp ? new Date(health.timestamp).toLocaleString() : null;
  const dbRevision = info?.dbRevision ?? null;
  const lastUsed = info?.lastUsed ? new Date(info.lastUsed).toLocaleString() : null;

  return (
    <Box sx={{ flex: 1, overflow: "auto", p: 4, maxWidth: 720, mx: "auto" }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 1 }}>
        <BuildOutlined sx={{ color: tokens.primary.main }} />
        <Typography variant="h5" fontWeight={700} color="text.primary">
          {t("maintenance.title")}
        </Typography>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t("maintenance.subtitle")}
      </Typography>

      <Divider sx={{ mb: 3 }} />

      {/* System info summary — driven by GET /health and GET /api/v1 (instance info) */}
      <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap", alignItems: "center", mb: 3 }}>
        <StatusChip label={overall.label} tone={overall.tone} testId="health-overall-status" />
        <Chip label={`Version ${version}`} size="small" variant="outlined" data-testid="health-version" />
        {dbRevision && (
          <Chip
            icon={<StorageOutlined />}
            label={`DB revision: ${dbRevision}`}
            size="small"
            variant="outlined"
            data-testid="info-db-revision"
          />
        )}
        {lastUsed && (
          <Chip
            icon={<HistoryOutlined />}
            label={`Last used: ${lastUsed}`}
            size="small"
            variant="outlined"
            data-testid="info-last-used"
          />
        )}
        {checkedAt && (
          <Chip
            icon={<UpdateOutlined />}
            label={`Checked: ${checkedAt}`}
            size="small"
            variant="outlined"
            data-testid="health-timestamp"
          />
        )}
      </Box>

      {/* Status cards */}
      <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {/* Database — real status from the health check */}
        <Box sx={cardSx} data-testid="health-card-database">
          <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              <StorageOutlined sx={{ fontSize: 18 }} />
              <Typography variant="body2" fontWeight={600} color="text.primary">
                Database
              </Typography>
            </Box>
            <StatusChip label={db.label} tone={db.tone} testId="health-status-database" />
          </Box>
        </Box>

        {/* Gated cards — no backend metric available yet */}
        {gatedItems.map((item) => (
          <Box key={item.key} sx={cardSx} data-testid={`health-card-${item.key}`}>
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                {item.icon}
                <Typography variant="body2" fontWeight={600} color="text.secondary">
                  {item.label}
                </Typography>
              </Box>
              <StatusChip label="No metric available" tone="neutral" testId={`health-status-${item.key}`} />
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
