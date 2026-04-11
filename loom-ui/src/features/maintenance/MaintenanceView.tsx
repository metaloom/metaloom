import React from "react";
import { Box, Typography, Chip, Divider, LinearProgress } from "@mui/material";
import {
  BuildOutlined, StorageOutlined, MemoryOutlined, UpdateOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { useTranslation } from "react-i18next";

const statusItems = [
  { label: "Database", status: "Healthy", icon: <StorageOutlined sx={{ fontSize: 18 }} />, progress: 34 },
  { label: "Object Storage (S3)", status: "Healthy", icon: <StorageOutlined sx={{ fontSize: 18 }} />, progress: 61 },
  { label: "Memory", status: "Healthy", icon: <MemoryOutlined sx={{ fontSize: 18 }} />, progress: 48 },
  { label: "Pipeline Workers", status: "Running", icon: <BuildOutlined sx={{ fontSize: 18 }} />, progress: 22 },
];

export default function MaintenanceView() {
  const { t } = useTranslation();
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

      {/* System info summary */}
      <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap", mb: 3 }}>
        <Chip icon={<UpdateOutlined />} label="Last restart: 2 days ago" size="small" variant="outlined" />
        <Chip label="Version 0.9.4-alpha" size="small" variant="outlined" />
        <Chip label="Uptime: 48h 12m" size="small" variant="outlined" />
      </Box>

      {/* Status cards */}
      <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {statusItems.map((item) => (
          <Box
            key={item.label}
            sx={{
              p: 2.5,
              border: `1px solid ${tokens.border.subtle}`,
              borderRadius: tokens.radius.md,
              bgcolor: tokens.bg.surface,
            }}
          >
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 1.5 }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                {item.icon}
                <Typography variant="body2" fontWeight={600} color="text.primary">
                  {item.label}
                </Typography>
              </Box>
              <Chip
                label={item.status}
                size="small"
                sx={{
                  bgcolor: item.status === "Healthy" ? "rgba(76,175,80,0.15)" : "rgba(255,183,77,0.15)",
                  color: item.status === "Healthy" ? tokens.accent.green : tokens.accent.amber,
                  fontWeight: 600,
                  fontSize: "0.72rem",
                  height: 22,
                }}
              />
            </Box>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
              <LinearProgress
                variant="determinate"
                value={item.progress}
                sx={{
                  flex: 1,
                  height: 6,
                  borderRadius: 3,
                  bgcolor: tokens.bg.overlay,
                  "& .MuiLinearProgress-bar": {
                    bgcolor: item.progress > 80 ? tokens.accent.red : tokens.primary.main,
                    borderRadius: 3,
                  },
                }}
              />
              <Typography variant="caption" color="text.secondary" sx={{ minWidth: 32, textAlign: "right" }}>
                {item.progress}%
              </Typography>
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
