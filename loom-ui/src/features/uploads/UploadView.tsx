import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Box, Typography, Paper, Button, IconButton, Tooltip, Chip, LinearProgress,
  MenuItem, Select, FormControl, InputLabel, TextField, Divider,
} from "@mui/material";
import {
  CloudUploadOutlined, CloseOutlined, ReplayOutlined, CheckCircleOutlined,
  ErrorOutlineOutlined, ContentCopyOutlined, BlockOutlined, FolderOutlined,
  CloudOutlined, DeleteSweepOutlined,
} from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import Title from "../../components/Title";
import EmptyState from "../../components/EmptyState";
import { useAuth } from "../../context/AuthContext";
import { listLibraries, LibraryResponse } from "../../api/libraries";
import { listPools, PoolResponse } from "../../api/pools";
import { useUploads } from "./UploadContext";
import {
  UploadItem, UploadStatus, cancel, cancelAll, clearFinished, enqueue, retry, retryFailed,
} from "./uploadQueue";
import { formatBytes, percentOf, progressLabel } from "./uploadFormat";
import { PAGE_SIZE } from "../../hooks/pagedList";

/** Statuses that will not change again without user action. */
function isSettled(status: UploadStatus): boolean {
  return status === "done" || status === "duplicate" || status === "error" || status === "cancelled";
}

function StatusIcon({ status }: { status: UploadStatus }) {
  switch (status) {
    case "done":
      return <CheckCircleOutlined sx={{ fontSize: 18, color: tokens.accent.green }} />;
    case "duplicate":
      return <ContentCopyOutlined sx={{ fontSize: 18, color: tokens.accent.blue }} />;
    case "error":
      return <ErrorOutlineOutlined sx={{ fontSize: 18, color: tokens.accent.red }} />;
    case "cancelled":
      return <BlockOutlined sx={{ fontSize: 18, color: tokens.text.tertiary }} />;
    default:
      return <CloudUploadOutlined sx={{ fontSize: 18, color: tokens.text.tertiary }} />;
  }
}

function barColor(status: UploadStatus): "primary" | "success" | "error" | "info" | "inherit" {
  if (status === "done") return "success";
  if (status === "duplicate") return "info";
  if (status === "error") return "error";
  if (status === "cancelled") return "inherit";
  return "primary";
}

/** One queued file: name, target, progress bar and the action appropriate to its state. */
function UploadRow({ item }: { item: UploadItem }) {
  const { t } = useTranslation();
  const settled = isSettled(item.status);
  const percent = settled ? 100 : percentOf(item.loaded, item.size);

  return (
    <Box
      data-testid={`upload-row-${item.fileName}`}
      data-status={item.status}
      sx={{
        display: "flex", alignItems: "center", gap: 1.5, px: 2, py: 1.25,
        borderBottom: `1px solid ${tokens.border.subtle}`,
        "&:last-of-type": { borderBottom: "none" },
      }}
    >
      <StatusIcon status={item.status} />

      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: 1 }}>
          <Typography noWrap sx={{ fontSize: "0.83rem", fontWeight: 500, color: tokens.text.primary, minWidth: 0 }}>
            {item.fileName}
          </Typography>
          <Typography sx={{ fontSize: "0.72rem", color: tokens.text.tertiary, flexShrink: 0 }}>
            {progressLabel(item.loaded, item.size, settled)}
          </Typography>
        </Box>

        <LinearProgress
          variant={item.status === "uploading" && item.loaded === 0 ? "indeterminate" : "determinate"}
          value={percent}
          color={barColor(item.status)}
          sx={{ mt: 0.75, height: 4, borderRadius: tokens.radius.full, bgcolor: tokens.bg.overlay }}
        />

        <Typography sx={{ fontSize: "0.7rem", color: item.status === "error" ? tokens.accent.red : tokens.text.tertiary, mt: 0.5 }}>
          {item.status === "error" && item.error
            ? item.error
            : t(`uploads.status.${item.status}`)}
          {item.libraryName ? ` · ${item.libraryName}` : ""}
          {item.poolName ? ` · ${item.poolName}` : ""}
        </Typography>
      </Box>

      {settled ? (
        (item.status === "error" || item.status === "cancelled") && (
          <Tooltip title={t("uploads.action.retry")}>
            <IconButton size="small" data-testid={`upload-retry-${item.fileName}`} onClick={() => retry(item.id)}>
              <ReplayOutlined sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
        )
      ) : (
        <Tooltip title={t("uploads.action.cancel")}>
          <IconButton size="small" data-testid={`upload-cancel-${item.fileName}`} onClick={() => cancel(item.id)}>
            <CloseOutlined sx={{ fontSize: 16 }} />
          </IconButton>
        </Tooltip>
      )}
    </Box>
  );
}

export default function UploadView() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const summary = useUploads();

  const [libraries, setLibraries] = useState<LibraryResponse[]>([]);
  const [pools, setPools] = useState<PoolResponse[]>([]);
  /** False when /pools answers 403 — the caller is not an operator, so no pool override is offered. */
  const [poolsReadable, setPoolsReadable] = useState(false);

  const [libraryUuid, setLibraryUuid] = useState("");
  /** "" means "let the library decide", which is the default and the only option for non-operators. */
  const [poolUuid, setPoolUuid] = useState("");
  const [origin, setOrigin] = useState("upload");
  const [dragging, setDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!token) return;
    listLibraries(token, { limit: PAGE_SIZE })
      .then((resp) => {
        const data = resp.data ?? [];
        setLibraries(data);
        setLibraryUuid((current) => current || data[0]?.uuid || "");
      })
      .catch(() => { /* an empty selector already communicates that nothing can be targeted */ });
  }, [token]);

  // Pools are an operator concern. Everyone uploads; only someone who may read pools is offered the
  // choice of overriding the library's. A 403 here is an expected answer, not an error to report.
  useEffect(() => {
    if (!token) return;
    listPools(token, { limit: PAGE_SIZE })
      .then((resp) => {
        setPools(resp.data ?? []);
        setPoolsReadable(true);
      })
      .catch(() => setPoolsReadable(false));
  }, [token]);

  const selectedLibrary = useMemo(
    () => libraries.find((l) => l.uuid === libraryUuid),
    [libraries, libraryUuid]
  );

  /** The pool the bytes will actually land in, and where that answer came from. */
  const effectivePool = useMemo(() => {
    if (poolUuid) {
      const pool = pools.find((p) => p.uuid === poolUuid);
      return { name: pool?.name ?? poolUuid, type: pool?.s3Bucket ? "s3" : "filesystem", overridden: true };
    }
    if (!selectedLibrary) return null;
    if (!selectedLibrary.poolUuid) {
      return { name: t("uploads.pool.serverDefault"), type: "filesystem", overridden: false };
    }
    const pool = pools.find((p) => p.uuid === selectedLibrary.poolUuid);
    return {
      name: pool?.name ?? t("uploads.pool.libraryPool"),
      type: selectedLibrary.storageType ?? (pool?.s3Bucket ? "s3" : "filesystem"),
      overridden: false,
    };
  }, [poolUuid, pools, selectedLibrary, t]);

  const addFiles = useCallback((files: File[]) => {
    if (!files.length || !libraryUuid) return;
    const pool = pools.find((p) => p.uuid === poolUuid);
    enqueue(files, {
      libraryUuid,
      libraryName: selectedLibrary?.name,
      poolUuid: poolUuid || undefined,
      poolName: pool?.name,
      origin: origin.trim() || undefined,
    });
  }, [libraryUuid, origin, poolUuid, pools, selectedLibrary]);

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    addFiles(Array.from(e.dataTransfer.files ?? []));
  };

  const hasItems = summary.items.length > 0;
  const canUpload = Boolean(libraryUuid);

  return (
    <Box sx={{ p: 3, overflow: "auto", height: "100%" }} data-testid="upload-view">
      <Title>{t("uploads.title")}</Title>
      <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary, mb: 2.5, maxWidth: 720 }}>
        {t("uploads.subtitle")}
      </Typography>

      {/* Target selection */}
      <Paper
        elevation={0}
        sx={{
          bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`,
          borderRadius: tokens.radius.lg, p: 2, mb: 2,
          display: "flex", gap: 2, flexWrap: "wrap", alignItems: "center",
        }}
      >
        <FormControl size="small" sx={{ minWidth: 220 }}>
          <InputLabel id="upload-library-label">{t("uploads.field.library")}</InputLabel>
          <Select
            labelId="upload-library-label"
            label={t("uploads.field.library")}
            value={libraryUuid}
            data-testid="upload-library-select"
            onChange={(e) => setLibraryUuid(e.target.value)}
          >
            {libraries.map((lib) => (
              <MenuItem key={lib.uuid} value={lib.uuid}>{lib.name}</MenuItem>
            ))}
          </Select>
        </FormControl>

        {poolsReadable && (
          <FormControl size="small" sx={{ minWidth: 220 }}>
            <InputLabel id="upload-pool-label">{t("uploads.field.pool")}</InputLabel>
            <Select
              labelId="upload-pool-label"
              label={t("uploads.field.pool")}
              value={poolUuid}
              data-testid="upload-pool-select"
              onChange={(e) => setPoolUuid(e.target.value)}
            >
              <MenuItem value="">{t("uploads.pool.fromLibrary")}</MenuItem>
              {pools.map((pool) => (
                <MenuItem key={pool.uuid} value={pool.uuid}>{pool.name}</MenuItem>
              ))}
            </Select>
          </FormControl>
        )}

        <TextField
          size="small"
          label={t("uploads.field.origin")}
          value={origin}
          data-testid="upload-origin-input"
          onChange={(e) => setOrigin(e.target.value)}
          sx={{ width: 160 }}
        />

        {effectivePool && (
          <Tooltip title={effectivePool.overridden ? t("uploads.pool.overrideHint") : t("uploads.pool.derivedHint")}>
            <Chip
              size="small"
              data-testid="upload-effective-pool"
              icon={effectivePool.type === "s3"
                ? <CloudOutlined sx={{ fontSize: 14 }} />
                : <FolderOutlined sx={{ fontSize: 14 }} />}
              label={`${t("uploads.pool.storesTo")}: ${effectivePool.name}`}
              variant={effectivePool.overridden ? "filled" : "outlined"}
              sx={{ fontSize: "0.72rem" }}
            />
          </Tooltip>
        )}
      </Paper>

      {/* Drop zone */}
      <Paper
        elevation={0}
        data-testid="upload-dropzone"
        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        onClick={() => canUpload && fileInputRef.current?.click()}
        sx={{
          bgcolor: dragging ? tokens.primary.subtle : tokens.bg.elevated,
          border: `1.5px dashed ${dragging ? tokens.primary.main : tokens.border.strong}`,
          borderRadius: tokens.radius.lg,
          p: 4, mb: 2, textAlign: "center",
          cursor: canUpload ? "pointer" : "not-allowed",
          opacity: canUpload ? 1 : 0.6,
          transition: "background-color 140ms ease, border-color 140ms ease",
        }}
      >
        <CloudUploadOutlined sx={{ fontSize: 40, color: tokens.primary.main, opacity: 0.85 }} />
        <Typography sx={{ fontSize: "0.9rem", fontWeight: 600, color: tokens.text.primary, mt: 1 }}>
          {t("uploads.dropzone.title")}
        </Typography>
        <Typography sx={{ fontSize: "0.78rem", color: tokens.text.secondary, mt: 0.5 }}>
          {canUpload ? t("uploads.dropzone.hint") : t("uploads.dropzone.noLibrary")}
        </Typography>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          data-testid="upload-file-input"
          onChange={(e) => {
            addFiles(Array.from(e.target.files ?? []));
            // Reset so picking the same file again still fires a change event.
            e.target.value = "";
          }}
        />
      </Paper>

      {/* Queue */}
      {hasItems && (
        <Paper
          elevation={0}
          sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.lg }}
        >
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 2, py: 1.5 }}>
            <Typography sx={{ fontSize: "0.85rem", fontWeight: 600, flex: 1 }} data-testid="upload-queue-heading">
              {summary.isActive
                ? t("uploads.queue.active", { done: summary.doneCount + summary.duplicateCount, total: summary.items.length, percent: summary.percent })
                : t("uploads.queue.idle", { count: summary.items.length })}
            </Typography>

            {summary.errorCount > 0 && (
              <Button size="small" startIcon={<ReplayOutlined sx={{ fontSize: 15 }} />} onClick={retryFailed} data-testid="upload-retry-failed"
                sx={{ textTransform: "none", fontSize: "0.76rem" }}>
                {t("uploads.action.retryFailed")}
              </Button>
            )}
            {summary.isActive && (
              <Button size="small" color="error" startIcon={<CloseOutlined sx={{ fontSize: 15 }} />} onClick={cancelAll} data-testid="upload-cancel-all"
                sx={{ textTransform: "none", fontSize: "0.76rem" }}>
                {t("uploads.action.cancelAll")}
              </Button>
            )}
            <Button size="small" startIcon={<DeleteSweepOutlined sx={{ fontSize: 15 }} />} onClick={clearFinished} data-testid="upload-clear-finished"
              sx={{ textTransform: "none", fontSize: "0.76rem" }}>
              {t("uploads.action.clearFinished")}
            </Button>
          </Box>

          {summary.isActive && (
            <LinearProgress
              variant="determinate"
              value={summary.percent}
              sx={{ height: 3, bgcolor: tokens.bg.overlay }}
            />
          )}
          <Divider />

          {summary.items.map((item) => <UploadRow key={item.id} item={item} />)}

          <Box sx={{ display: "flex", gap: 2, px: 2, py: 1.25, borderTop: `1px solid ${tokens.border.subtle}` }}>
            <Typography sx={{ fontSize: "0.72rem", color: tokens.text.tertiary }} data-testid="upload-totals">
              {t("uploads.totals", {
                uploaded: summary.doneCount,
                duplicates: summary.duplicateCount,
                failed: summary.errorCount,
                bytes: formatBytes(summary.items.reduce((sum, i) => sum + i.size, 0)),
              })}
            </Typography>
          </Box>
        </Paper>
      )}

      {!hasItems && (
        <EmptyState
          icon={CloudUploadOutlined}
          title={t("uploads.empty.title")}
          description={t("uploads.empty.description")}
          actionLabel={t("uploads.empty.action")}
          actionIcon={<CloudUploadOutlined />}
          actionDisabled={!canUpload}
          onAction={() => fileInputRef.current?.click()}
          testId="upload-empty"
          compact
        />
      )}
    </Box>
  );
}
