import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Alert, Box, Button, LinearProgress, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Tooltip, Typography,
} from "@mui/material";
import { LockOutlined, RefreshOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import StatusChip from "../../components/StatusChip";
import EmptyState from "../../components/EmptyState";
import { useAuth } from "../../context/AuthContext";
import { formatBytes, formatBytesOrUnknown } from "../../api/format";
import {
  dedupeSavings, loadStorageReport, savingsPercent, sortBackends, sortCategories, StorageApiError,
  storageTotals, usedFraction, watermarkTone, type StorageBackend, type StorageReport,
} from "../../api/storage";

/**
 * What is stored, and how much room is left.
 *
 * <p>Two byte columns per category, and shipping only one would be wrong for somebody. The logical
 * total is what the catalogue claims and what a quota would charge for; the distinct total is what
 * the disk actually holds, because storage is content-addressed. On a face-crop-heavy install the
 * first is several times the second, and an operator deciding what to delete needs to know which
 * number they are looking at.</p>
 *
 * <p>Deliberately not polled, for the same reason as the integrity screen next door: the report is
 * several aggregate scans and there is no background job to watch. The operator presses the
 * button.</p>
 */

const cardSx = {
  p: 2,
  mb: 2,
  border: `1px solid ${tokens.border.subtle}`,
  borderRadius: tokens.radius.md,
  bgcolor: tokens.bg.surface,
};

/**
 * How full a backend looks, as a bar.
 *
 * A backend that cannot report capacity gets no bar at all rather than an empty one. An empty bar
 * reads as "plenty of room", and for a bucket the honest answer is that the question does not apply.
 */
function CapacityBar({ backend }: { backend: StorageBackend }) {
  const fraction = usedFraction(backend);
  if (fraction === null) return null;
  const tone = watermarkTone(backend.watermark);
  const colour = tone === "red" ? tokens.accent.red : tone === "amber" ? tokens.accent.amber : tokens.accent.green;
  return (
    <Box
      sx={{ mt: 1, height: 6, borderRadius: 3, bgcolor: tokens.bg.overlay, overflow: "hidden" }}
      data-testid={`storage-backend-bar-${backend.poolUuid ?? "default"}`}
      data-used={Math.round(fraction * 100)}
    >
      <Box sx={{ width: `${fraction * 100}%`, height: "100%", bgcolor: colour }} />
    </Box>
  );
}

export default function StorageAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();

  const [report, setReport] = useState<StorageReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const response = await loadStorageReport(token);
      setReport(response);
      setError(null);
      setForbidden(false);
    } catch (e) {
      // A 403 is a different screen from a failed request: the user is not allowed here, which is
      // stable, versus the server hiccupped, which is not. Only the latter keeps the last report.
      if (e instanceof StorageApiError && e.status === 403) {
        setForbidden(true);
        setReport(null);
      } else {
        setError((e as Error).message);
      }
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    load();
  }, [load]);

  const backends = useMemo(() => (report ? sortBackends(report.backends) : []), [report]);
  const categories = useMemo(() => (report ? sortCategories(report.categories) : []), [report]);
  // Not the sum of the per-category savings: sharing between categories is invisible to those, and
  // an install whose duplicates are all cross-category would report "0 B saved" beside two byte
  // columns that visibly disagree.
  const totals = useMemo(() => (report ? storageTotals(report) : null), [report]);

  if (forbidden) {
    return (
      <Box data-testid="storage-admin">
        <EmptyState
          icon={LockOutlined}
          title={t("admin.storage.forbiddenTitle")}
          description={t("admin.storage.forbiddenDescription")}
          testId="storage-forbidden"
        />
      </Box>
    );
  }

  return (
    <Box data-testid="storage-admin">
      <Box sx={{ mb: 2, display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>
            {t("admin.storage.title")}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t("admin.storage.subtitle")}
          </Typography>
        </Box>
        <Button
          size="small"
          variant="outlined"
          startIcon={<RefreshOutlined sx={{ fontSize: 16 }} />}
          disabled={loading}
          onClick={load}
          data-testid="storage-refresh"
        >
          {t("admin.storage.refresh")}
        </Button>
      </Box>

      {loading && <LinearProgress sx={{ mb: 2 }} data-testid="storage-loading" />}

      {/* A failed refresh warns but keeps the last report on screen: a blank panel is a worse
          answer than a slightly stale one when the question is "am I about to run out of disk". */}
      {error && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="storage-error">
          {t("admin.storage.refreshFailed", { error })}
        </Alert>
      )}

      {report && totals && (
        <>
          <Paper elevation={0} sx={cardSx} data-testid="storage-summary">
            <Box sx={{ display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap" }}>
              <StatusChip
                label={t("admin.storage.objects", { count: totals.objects })}
                tone="neutral"
                testId="storage-total-objects"
              />
              <StatusChip
                label={t("admin.storage.onDisk", { size: formatBytes(totals.onDiskBytes) })}
                tone="neutral"
                testId="storage-total-bytes"
              />
              <StatusChip
                label={t("admin.storage.saved", { size: formatBytes(totals.savedBytes) })}
                tone="green"
                title={t("admin.storage.savedHint")}
                testId="storage-total-saved"
              />
              {/* Only shown when there is something to show: a permanent "0 unreferenced" trains
                  the eye to skip the one chip that means space is being wasted. */}
              {report.orphanObjects > 0 && (
                <StatusChip
                  label={t("admin.storage.orphans", {
                    count: report.orphanObjects,
                    size: formatBytes(report.orphanBytes),
                  })}
                  tone="amber"
                  title={t("admin.storage.orphansHint")}
                  testId="storage-orphans"
                />
              )}
            </Box>
          </Paper>

          <Paper elevation={0} sx={cardSx} data-testid="storage-backends">
            <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1.5 }}>
              {t("admin.storage.backendsTitle")}
            </Typography>
            <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 1.5 }}>
              {backends.map(backend => (
                <Box
                  key={backend.poolUuid ?? "default"}
                  data-testid={`storage-backend-${backend.poolUuid ?? "default"}`}
                  data-watermark={backend.watermark}
                  sx={{
                    p: 1.5,
                    border: `1px solid ${tokens.border.subtle}`,
                    borderRadius: tokens.radius.sm,
                    bgcolor: tokens.bg.overlay,
                  }}
                >
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
                    <Typography variant="body2" fontWeight={600}>{backend.poolName}</Typography>
                    <StatusChip label={backend.kind} tone="neutral" />
                    <Box sx={{ flexGrow: 1 }} />
                    <StatusChip
                      label={t(`admin.storage.watermark.${backend.watermark}`, {
                        defaultValue: backend.watermark,
                      })}
                      tone={watermarkTone(backend.watermark)}
                      testId={`storage-backend-watermark-${backend.poolUuid ?? "default"}`}
                    />
                  </Box>
                  {backend.description && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5 }}>
                      {backend.description}
                    </Typography>
                  )}
                  <CapacityBar backend={backend} />
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ display: "block", mt: 0.75 }}
                    data-testid={`storage-backend-free-${backend.poolUuid ?? "default"}`}
                  >
                    {/* "Unknown" rather than "0 B": an object store reports no capacity, and the
                        difference between those two is the difference between a non-question and an
                        emergency. */}
                    {t("admin.storage.freeOf", {
                      free: formatBytesOrUnknown(backend.freeBytes, t("admin.storage.unknown")),
                      total: formatBytesOrUnknown(backend.totalBytes, t("admin.storage.unknown")),
                    })}
                    {" · "}
                    {t("admin.storage.holding", {
                      count: backend.objects,
                      size: formatBytes(backend.bytes),
                    })}
                  </Typography>
                  {backend.error && (
                    <Alert severity="warning" sx={{ mt: 1 }} data-testid={`storage-backend-error-${backend.poolUuid ?? "default"}`}>
                      {backend.error}
                    </Alert>
                  )}
                </Box>
              ))}
            </Box>
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1.5 }} data-testid="storage-thresholds">
              {t("admin.storage.thresholds", {
                critical: formatBytes(report.thresholds.minFreeSpaceBytes),
                warn: formatBytes(report.thresholds.warnFreeSpaceBytes),
              })}
            </Typography>
          </Paper>

          <Paper elevation={0} sx={cardSx} data-testid="storage-categories">
            <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1.5 }}>
              {t("admin.storage.categoriesTitle")}
            </Typography>
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t("admin.storage.column.category")}</TableCell>
                    <TableCell align="right">{t("admin.storage.column.elements")}</TableCell>
                    <TableCell align="right">
                      <Tooltip title={t("admin.storage.column.logicalHint")}>
                        <span>{t("admin.storage.column.logical")}</span>
                      </Tooltip>
                    </TableCell>
                    <TableCell align="right">
                      <Tooltip title={t("admin.storage.column.onDiskHint")}>
                        <span>{t("admin.storage.column.onDisk")}</span>
                      </Tooltip>
                    </TableCell>
                    <TableCell align="right">{t("admin.storage.column.saved")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {/* Every category, including the empty ones. A row that disappears when a kind of
                      content has none is indistinguishable from a report that stopped counting it. */}
                  {categories.map(category => (
                    <TableRow key={category.category} data-testid={`storage-category-${category.category}`}>
                      <TableCell>
                        {t(`admin.storage.category.${category.category}`, { defaultValue: category.category })}
                      </TableCell>
                      <TableCell align="right">{category.elements.toLocaleString()}</TableCell>
                      <TableCell align="right">{formatBytes(category.logicalBytes)}</TableCell>
                      <TableCell align="right" data-testid={`storage-category-ondisk-${category.category}`}>
                        {formatBytes(category.distinctBytes)}
                      </TableCell>
                      <TableCell align="right" data-testid={`storage-category-saved-${category.category}`}>
                        {dedupeSavings(category) > 0
                          ? `${formatBytes(dedupeSavings(category))} (${savingsPercent(category)}%)`
                          : "—"}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1.5 }} data-testid="storage-categories-note">
              {t("admin.storage.categoriesNote")}
            </Typography>
          </Paper>
        </>
      )}
    </Box>
  );
}
