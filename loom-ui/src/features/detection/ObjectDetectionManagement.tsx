import React, { useEffect, useMemo, useState } from "react";
import {
  Box, Typography, Paper, Chip, TextField, InputAdornment,
  Avatar, IconButton, Tooltip,
} from "@mui/material";
import {
  SearchOutlined, CenterFocusStrongOutlined,
  CheckOutlined, CloseOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { DetectedObject } from "../../types";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { listAssets, AssetResponse } from "../../api/assets";
import { listAssetDetections } from "../../api/detections";

export default function ObjectDetectionManagement() {
  const [query, setQuery] = useState("");
  const [decisions, setDecisions] = useState<Record<string, "confirmed" | "rejected">>({});
  const { t } = useTranslation();
  const { token } = useAuth();
  const [assetMap, setAssetMap] = useState<Record<string, AssetResponse>>({});
  const [detectedObjects, setDetectedObjects] = useState<DetectedObject[]>([]);

  useEffect(() => {
    if (!token) return;
    listAssets(token).then(r => {
      const assets = r.data ?? [];
      const map: Record<string, AssetResponse> = {};
      assets.forEach(a => { map[a.uuid] = a; });
      setAssetMap(map);

      // Fetch detections for all assets
      Promise.all(assets.map(a => listAssetDetections(token, a.uuid).catch(() => ({ data: [] as never[] }))))
        .then(results => {
          const objects: DetectedObject[] = [];
          results.forEach(resp => {
            for (const d of (resp.data ?? [])) {
              if (d.type === "objectdetection") {
                objects.push({
                  id: d.uuid,
                  assetId: d.assetUuid,
                  label: ((d.meta as Record<string, unknown>)?.label as string) ?? d.type,
                  confidence: d.confidence,
                  boundingBox: { x: d.bboxX, y: d.bboxY, width: d.bboxWidth, height: d.bboxHeight },
                  timestamp: d.frameNumber,
                });
              }
            }
          });
          setDetectedObjects(objects);
        });
    }).catch(() => {});
  }, [token]);

  const grouped = useMemo(() => {
    const byLabel: Record<string, DetectedObject[]> = {};
    detectedObjects.forEach(o => {
      (byLabel[o.label] ??= []).push(o);
    });
    return Object.entries(byLabel).sort((a, b) => b[1].length - a[1].length);
  }, [detectedObjects]);

  const filtered = query.trim()
    ? grouped.filter(([label]) => label.toLowerCase().includes(query.toLowerCase()))
    : grouped;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", overflow: "hidden" }}>
      {/* Toolbar */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 1, alignItems: "center" }}>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t("objectDetection.search.placeholder")}
          size="small"
          sx={{ flex: 1, maxWidth: 320 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
        />
        <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
          {t("objectDetection.count", { detections: detectedObjects.length, labels: grouped.length })}
        </Typography>
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2 }}>
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
          {filtered.map(([label, objects]) => (
            <Paper key={label} elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
              <Box sx={{ px: 2, py: 1.25, display: "flex", alignItems: "center", gap: 1, borderBottom: `1px solid ${tokens.border.subtle}` }}>
                <CenterFocusStrongOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
                <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.88rem", textTransform: "capitalize", flex: 1 }}>
                  {label}
                </Typography>
                <Chip label={`${objects.length}`} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: tokens.bg.overlay }} />
              </Box>
              <Box sx={{ p: 1.5, display: "flex", flexDirection: "column", gap: 0.75 }}>
                {objects.map(obj => {
                  const asset = assetMap[obj.assetId];
                  const dec = decisions[obj.id];
                  return (
                    <Box key={obj.id} sx={{ display: "flex", alignItems: "center", gap: 1.5, py: 0.5, px: 1, borderRadius: tokens.radius.sm, bgcolor: dec === "rejected" ? `${tokens.accent.red}08` : "transparent", opacity: dec === "rejected" ? 0.6 : 1 }}>
                      <Avatar variant="rounded" sx={{ width: 40, height: 40 }} />
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.8rem" }} noWrap>{asset?.file?.filename ?? obj.assetId}</Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>
                          {t("objectDetection.label.confidence", { pct: Math.round(obj.confidence * 100) })}{obj.timestamp != null && ` · ${obj.timestamp}s`}
                        </Typography>
                      </Box>
                      {dec ? (
                        <Chip label={dec} size="small" sx={{ height: 18, fontSize: "0.64rem", fontWeight: 600, bgcolor: dec === "confirmed" ? `${tokens.accent.green}18` : `${tokens.accent.red}18`, color: dec === "confirmed" ? tokens.accent.green : tokens.accent.red }} />
                      ) : (
                        <Box sx={{ display: "flex", gap: 0.5 }}>
                          <Tooltip title={t("objectDetection.tooltip.confirm")}><IconButton size="small" onClick={() => setDecisions(prev => ({ ...prev, [obj.id]: "confirmed" }))} sx={{ width: 24, height: 24, bgcolor: `${tokens.accent.green}18`, "&:hover": { bgcolor: `${tokens.accent.green}33` } }}><CheckOutlined sx={{ fontSize: 12, color: tokens.accent.green }} /></IconButton></Tooltip>
                          <Tooltip title={t("objectDetection.tooltip.reject")}><IconButton size="small" onClick={() => setDecisions(prev => ({ ...prev, [obj.id]: "rejected" }))} sx={{ width: 24, height: 24, bgcolor: `${tokens.accent.red}18`, "&:hover": { bgcolor: `${tokens.accent.red}33` } }}><CloseOutlined sx={{ fontSize: 12, color: tokens.accent.red }} /></IconButton></Tooltip>
                        </Box>
                      )}
                    </Box>
                  );
                })}
              </Box>
            </Paper>
          ))}
          {filtered.length === 0 && (
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
              <CenterFocusStrongOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
              <Typography variant="body2" color="text.secondary">{t("objectDetection.empty")}</Typography>
            </Box>
          )}
        </Box>
      </Box>
    </Box>
  );
}
