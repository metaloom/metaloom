import React, { useEffect, useMemo, useState } from "react";
import {
  Box, Typography, Paper, Chip, TextField, InputAdornment,
  Avatar, IconButton, Tooltip, CircularProgress,
} from "@mui/material";
import {
  SearchOutlined, CenterFocusStrongOutlined,
  CheckOutlined, CloseOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { DetectedObject } from "../../types";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { listAssets, AssetResponse } from "../../api/assets";
import { listAssetDetections, updateDetection, deleteDetection, type DetectionResponse } from "../../api/detections";
import { PAGE_SIZE } from "../../hooks/pagedList";
import { ListFilterSelect } from "../../components/ListControls";
import { useFailure } from "../../context/FailureContext";
import LoadFailure from "../../components/LoadFailure";

/**
 * Confidence bands for the review filter.
 *
 * Boundaries rather than a slider: the useful question is "show me what the model was unsure
 * about", and three named bands answer it without asking anyone to pick a number.
 */
const CONFIDENCE_BANDS = ["high", "medium", "low"] as const;

function matchesConfidence(value: number, band: string): boolean {
  if (band === "high") return value >= 0.9;
  if (band === "medium") return value >= 0.6 && value < 0.9;
  if (band === "low") return value < 0.6;
  return true;
}

/**
 * The class a detection is grouped under.
 *
 * The reviewer's correction first, then the model's own `label` column - which is where the
 * objectdetect node writes the class. Reading only `meta.label` (where the demo seed used to keep
 * it) filed every real pipeline detection under the one group "objectdetection".
 */
export function detectionLabel(d: DetectionResponse): string {
  const metaLabel = (d.meta as Record<string, unknown> | undefined)?.label;
  return d.correctedLabel || d.label || (typeof metaLabel === "string" && metaLabel ? metaLabel : "") || d.type;
}

/** The verdict already on record: the review columns, or the `meta.confirmed` this screen writes. */
function storedVerdict(d: DetectionResponse): "confirmed" | "rejected" | null {
  if (d.reviewStatus === "CONFIRMED") return "confirmed";
  if (d.reviewStatus === "REJECTED") return "rejected";
  if ((d.meta as Record<string, unknown> | undefined)?.confirmed === true) return "confirmed";
  return null;
}

export default function ObjectDetectionManagement() {
  const [query, setQuery] = useState("");
  const [decisions, setDecisions] = useState<Record<string, "confirmed" | "rejected">>({});
  const { t } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();
  const [assetMap, setAssetMap] = useState<Record<string, AssetResponse>>({});
  const [detectedObjects, setDetectedObjects] = useState<DetectedObject[]>([]);
  const [confidence, setConfidence] = useState("");
  // Loading, failed and loaded-but-empty are three different things to say. A failed asset list
  // used to be swallowed, and the screen then claimed there were no detections at all.
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  // Bumped by the retry button; the effect depends on it.
  const [reloadToken, setReloadToken] = useState(0);
  const { reportFailure } = useFailure();

  useEffect(() => {
    if (!token) return;
    setLoading(true);
    setLoadError(null);
    listAssets(token, { limit: PAGE_SIZE }).then(r => {
      const assets = r.data ?? [];
      const map: Record<string, AssetResponse> = {};
      assets.forEach(a => { map[a.uuid] = a; });
      setAssetMap(map);

      // Fetch detections for all assets
      return Promise.all(assets.map(a => listAssetDetections(token, a.uuid).catch(() => ({ data: [] as never[] }))))
        .then(results => {
          const objects: DetectedObject[] = [];
          const reviewed: Record<string, "confirmed" | "rejected"> = {};
          results.forEach(resp => {
            for (const d of (resp.data ?? [])) {
              if (d.type === "objectdetection") {
                objects.push({
                  id: d.uuid,
                  assetId: d.assetUuid,
                  label: detectionLabel(d),
                  confidence: d.confidence,
                  boundingBox: { x: d.bboxX, y: d.bboxY, width: d.bboxWidth, height: d.bboxHeight },
                  timestamp: d.frameNumber,
                });
                const verdict = storedVerdict(d);
                if (verdict) reviewed[d.uuid] = verdict;
              }
            }
          });
          setDetectedObjects(objects);
          // A verdict given in an earlier session is still a verdict. These used to be local state
          // only, so every reload offered the same confirm/reject buttons on rows already decided.
          setDecisions(reviewed);
        });
    }).catch(e => {
      setLoadError(reportFailure("loadObjectDetections", e).message);
    }).finally(() => setLoading(false));
  }, [token, reloadToken]);

  const grouped = useMemo(() => {
    const byLabel: Record<string, DetectedObject[]> = {};
    detectedObjects.forEach(o => {
      (byLabel[o.label] ??= []).push(o);
    });
    return Object.entries(byLabel).sort((a, b) => b[1].length - a[1].length);
  }, [detectedObjects]);

  // Confidence is the second axis this screen is actually read along — "what did the model only
  // half-believe" is the review queue. Applied to the members of each label group, so a group
  // whose detections are all above the threshold disappears rather than showing as empty.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return grouped
      .map(([label, objects]) => [
        label,
        confidence === "" ? objects : objects.filter(o => matchesConfidence(o.confidence, confidence)),
      ] as [string, DetectedObject[]])
      .filter(([label, objects]) => objects.length > 0 && (!q || label.toLowerCase().includes(q)));
  }, [grouped, query, confidence]);

  // Confirm marks the detection as reviewed (meta.confirmed); reject deletes it. Both persist.
  const handleConfirm = async (obj: DetectedObject) => {
    if (!token) return;
    try {
      await updateDetection(token, obj.assetId, obj.id, { meta: { label: obj.label, confirmed: true } });
      setDecisions(prev => ({ ...prev, [obj.id]: "confirmed" }));
    } catch {
      showToast(t("objectDetection.confirmError"), "error");
    }
  };

  const handleReject = async (obj: DetectedObject) => {
    if (!token) return;
    try {
      await deleteDetection(token, obj.assetId, obj.id);
      setDetectedObjects(prev => prev.filter(o => o.id !== obj.id));
    } catch {
      showToast(t("objectDetection.rejectError"), "error");
    }
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", overflow: "hidden" }}>
      {/* Toolbar */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 1, alignItems: "center" }}>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t("objectDetection.search.placeholder")}
          size="small"
          data-testid="objectdetection-search"
          sx={{ flex: 1, maxWidth: 320 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
        />
        <ListFilterSelect value={confidence} onChange={setConfidence}
          options={CONFIDENCE_BANDS.map(b => ({ value: b, label: t(`objectDetection.filter.${b}`) }))}
          allLabel={t("objectDetection.filter.anyConfidence")} testId="objectdetection-filter-confidence" minWidth={150} />
        <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.72rem" }}>
          {t("objectDetection.count", { detections: detectedObjects.length, labels: grouped.length })}
        </Typography>
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2 }}>
        {loading && (
          <Box sx={{ display: "flex", justifyContent: "center", py: 6 }} data-testid="objectdetection-loading">
            <CircularProgress size={28} />
          </Box>
        )}
        {!loading && loadError && (
          <LoadFailure message={loadError} onRetry={() => setReloadToken(n => n + 1)} testId="objectdetection-load-failure" />
        )}
        {!loading && !loadError && (
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
          {filtered.map(([label, objects]) => (
            <Paper key={label} elevation={0} data-testid="objectdetection-group" sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
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
                    <Box key={obj.id} data-testid="objectdetection-row" sx={{ display: "flex", alignItems: "center", gap: 1.5, py: 0.5, px: 1, borderRadius: tokens.radius.sm, bgcolor: dec === "rejected" ? `${tokens.accent.red}08` : "transparent", opacity: dec === "rejected" ? 0.6 : 1 }}>
                      <Avatar variant="rounded" sx={{ width: 40, height: 40 }} />
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.8rem" }} noWrap>{asset?.file?.filename ?? obj.assetId}</Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>
                          {t("objectDetection.label.confidence", { pct: Math.round(obj.confidence * 100) })}{obj.timestamp != null && ` · ${t("objectDetection.label.frame", { frame: obj.timestamp })}`}
                        </Typography>
                      </Box>
                      {dec ? (
                        <Chip label={dec} size="small" data-testid="objectdetection-decision" sx={{ height: 18, fontSize: "0.64rem", fontWeight: 600, bgcolor: dec === "confirmed" ? `${tokens.accent.green}18` : `${tokens.accent.red}18`, color: dec === "confirmed" ? tokens.accent.green : tokens.accent.red }} />
                      ) : (
                        <Box sx={{ display: "flex", gap: 0.5 }}>
                          <Tooltip title={t("objectDetection.tooltip.confirm")}><IconButton size="small" data-testid="objectdetection-confirm" onClick={() => handleConfirm(obj)} sx={{ width: 24, height: 24, bgcolor: `${tokens.accent.green}18`, "&:hover": { bgcolor: `${tokens.accent.green}33` } }}><CheckOutlined sx={{ fontSize: 12, color: tokens.accent.green }} /></IconButton></Tooltip>
                          <Tooltip title={t("objectDetection.tooltip.reject")}><IconButton size="small" data-testid="objectdetection-reject" onClick={() => handleReject(obj)} sx={{ width: 24, height: 24, bgcolor: `${tokens.accent.red}18`, "&:hover": { bgcolor: `${tokens.accent.red}33` } }}><CloseOutlined sx={{ fontSize: 12, color: tokens.accent.red }} /></IconButton></Tooltip>
                        </Box>
                      )}
                    </Box>
                  );
                })}
              </Box>
            </Paper>
          ))}
          {filtered.length === 0 && (
            <Box data-testid="objectdetection-empty" sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
              <CenterFocusStrongOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
              <Typography variant="body2" color="text.secondary">{t("objectDetection.empty")}</Typography>
            </Box>
          )}
        </Box>
        )}
      </Box>
    </Box>
  );
}
