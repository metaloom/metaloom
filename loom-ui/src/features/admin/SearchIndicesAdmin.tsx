import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle,
  LinearProgress, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  TextField, Tooltip, Typography, InputAdornment,
} from "@mui/material";
import {
  AutorenewOutlined, DeleteSweepOutlined, LockOutlined, StorageOutlined, SyncOutlined, SearchOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import StatusChip from "../../components/StatusChip";
import EmptyState from "../../components/EmptyState";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import {
  cancelSearchIndexJob, createSearchIndexJob, formatBytes, indexStateLabel, indexTone,
  isTerminal, jobProgress, listSearchIndices, SearchIndexApiError,
  type IndexJobAction, type SearchIndexBackendResponse, type SearchIndexListResponse,
  type SearchIndexResponse,
} from "../../api/searchIndices";

/**
 * While a job is running the numbers change every second; when nothing is happening they
 * change when a node writes a vector. Two intervals rather than one compromise: a 15s
 * idle poll is cheap enough to leave open on a second monitor, and a 2s active poll makes
 * the progress bar move rather than jump.
 */
const IDLE_POLL_MS = 15_000;
const ACTIVE_POLL_MS = 2_000;

const ACTION_ICONS: Record<IndexJobAction, React.ReactElement> = {
  REINDEX: <AutorenewOutlined sx={{ fontSize: 16 }} />,
  DELTA_SYNC: <SyncOutlined sx={{ fontSize: 16 }} />,
  DROP: <DeleteSweepOutlined sx={{ fontSize: 16 }} />,
};

const cardSx = {
  p: 2,
  mb: 2,
  border: `1px solid ${tokens.border.subtle}`,
  borderRadius: tokens.radius.md,
  bgcolor: tokens.bg.surface,
};

function numberFormat(value: number): string {
  return value.toLocaleString();
}

/** The vector space, or the algorithm, or nothing — whichever identifies this index. */
function describeSource(index: SearchIndexResponse): string {
  if (index.kind === "VECTOR") {
    const model = index.model ? index.model : "(no model recorded)";
    return index.dimensions ? `${model} · ${index.dimensions}d` : model;
  }
  if (index.kind === "FINGERPRINT") {
    return index.algorithm ?? "";
  }
  return "";
}

export default function SearchIndicesAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();

  const [snapshot, setSnapshot] = useState<SearchIndexListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [confirmDrop, setConfirmDrop] = useState<SearchIndexResponse | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    try {
      const response = await listSearchIndices(token);
      setSnapshot(response);
      setError(null);
      setForbidden(false);
    } catch (e) {
      // A 403 is a different screen from a failed request: the user is not allowed here,
      // which is stable, versus the server hiccupped, which is not. Only the latter keeps
      // the last good snapshot on display.
      if (e instanceof SearchIndexApiError && e.status === 403) {
        setForbidden(true);
        setSnapshot(null);
      } else {
        setError((e as Error).message);
      }
    } finally {
      setLoading(false);
    }
  }, [token]);

  const hasActiveJob = useMemo(
    () => (snapshot?.data ?? []).some(index => index.activeJob && !isTerminal(index.activeJob)),
    [snapshot],
  );

  // A self-rescheduling timeout rather than setInterval: the cadence depends on the
  // response, so the next delay has to be chosen after each poll rather than fixed up front.
  useEffect(() => {
    if (!token || forbidden) return;
    let cancelled = false;
    const tick = async () => {
      await load();
      if (cancelled) return;
      timerRef.current = setTimeout(tick, hasActiveJob ? ACTIVE_POLL_MS : IDLE_POLL_MS);
    };
    timerRef.current = setTimeout(tick, hasActiveJob ? ACTIVE_POLL_MS : IDLE_POLL_MS);
    return () => {
      cancelled = true;
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [token, forbidden, hasActiveJob, load]);

  useEffect(() => {
    load();
  }, [load]);

  const runAction = async (index: SearchIndexResponse, action: IndexJobAction) => {
    if (!token) return;
    setBusy(index.id);
    try {
      await createSearchIndexJob(token, index.id, action);
      showToast(t("admin.searchIndices.jobStarted", { action, index: index.label ?? index.id }), "success");
      await load();
    } catch (e) {
      showToast(t("admin.searchIndices.jobFailed", { error: (e as Error).message }), "error");
    } finally {
      setBusy(null);
    }
  };

  const cancel = async (index: SearchIndexResponse) => {
    if (!token || !index.activeJob) return;
    try {
      await cancelSearchIndexJob(token, index.id, index.activeJob.uuid);
      showToast(t("admin.searchIndices.cancelRequested"), "info");
      await load();
    } catch (e) {
      showToast(t("admin.searchIndices.jobFailed", { error: (e as Error).message }), "error");
    }
  };

  if (forbidden) {
    return (
      <Box data-testid="search-indices-admin">
        <EmptyState
          icon={LockOutlined}
          title={t("admin.searchIndices.forbiddenTitle")}
          description={t("admin.searchIndices.forbiddenDescription")}
          testId="search-indices-forbidden"
        />
      </Box>
    );
  }

  const backends = snapshot?.backends ?? [];
  const indices = snapshot?.data ?? [];

  return (
    <Box data-testid="search-indices-admin">
      <Box sx={{ mb: 2 }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>
          {t("admin.searchIndices.title")}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {t("admin.searchIndices.subtitle")}
        </Typography>
      </Box>

      {/* A failed poll warns but keeps the last good snapshot: a blank screen is a worse
          answer than a slightly stale one when the question is "is anything broken". */}
      {error && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="search-indices-error">
          {t("admin.searchIndices.refreshFailed", { error })}
        </Alert>
      )}

      {loading && !snapshot && <LinearProgress sx={{ mb: 2 }} data-testid="search-indices-loading" />}

      {!loading && indices.length === 0 && !error && (
        <EmptyState
          icon={StorageOutlined}
          title={t("admin.searchIndices.emptyTitle")}
          description={t("admin.searchIndices.emptyDescription")}
          testId="search-indices-empty"
        />
      )}

      {backends.map(backend => (
        <BackendSection
          key={backend.id}
          backend={backend}
          indices={indices.filter(index => index.backendId === backend.id)}
          busy={busy}
          onAction={runAction}
          onCancel={cancel}
          onDrop={setConfirmDrop}
        />
      ))}

      <Dialog open={confirmDrop !== null} onClose={() => setConfirmDrop(null)}>
        <DialogTitle>{t("admin.searchIndices.dropTitle")}</DialogTitle>
        <DialogContent>
          <DialogContentText data-testid="search-index-drop-confirm-text">
            {t("admin.searchIndices.dropBody", { index: confirmDrop?.label ?? confirmDrop?.id })}
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmDrop(null)}>{t("common.cancel")}</Button>
          <Button
            color="error"
            variant="contained"
            data-testid="search-index-drop-confirm"
            onClick={() => {
              const target = confirmDrop;
              setConfirmDrop(null);
              if (target) runAction(target, "DROP");
            }}
          >
            {t("admin.searchIndices.dropAction")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

function BackendSection({
  backend,
  indices,
  busy,
  onAction,
  onCancel,
  onDrop,
}: {
  backend: SearchIndexBackendResponse;
  indices: SearchIndexResponse[];
  busy: string | null;
  onAction: (index: SearchIndexResponse, action: IndexJobAction) => void;
  onCancel: (index: SearchIndexResponse) => void;
  onDrop: (index: SearchIndexResponse) => void;
}) {
  const { t } = useTranslation();
  const [query, setQuery] = useState("");

  // One row per indexed entity type, so this table is short — but the rule in LOOM_UI.md §7.5.1 is
  // that every list view carries a search field, and with a vector backend the rows multiply by
  // embedding type and model, which is when it stops being short.
  const visibleIndices = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return indices;
    return indices.filter(index =>
      index.id.toLowerCase().includes(q)
      || (index.label ?? "").toLowerCase().includes(q)
      || (index.type ?? "").toLowerCase().includes(q)
      || describeSource(index).toLowerCase().includes(q));
  }, [indices, query]);

  if (indices.length === 0) return null;

  return (
    <Box sx={cardSx} data-testid={`search-index-backend-${backend.id}`}>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 1.5, flexWrap: "wrap", gap: 1 }}>
        <Box>
          <Typography variant="body2" fontWeight={700} color="text.primary">
            {t(`admin.searchIndices.backend.${backend.id}`, { defaultValue: backend.id })}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {backend.provider}
            {/* Size belongs to the directory, not to the indices in it: Lucene segments
                interleave the vector spaces, so there is no per-space byte figure to show. */}
            {" · "}
            <span data-testid={`search-index-backend-size-${backend.id}`}>{formatBytes(backend.sizeBytes)}</span>
            {backend.deletedCount > 0 && (
              <Tooltip title={t("admin.searchIndices.deletedHint")}>
                <span> · {t("admin.searchIndices.deleted", { count: backend.deletedCount })}</span>
              </Tooltip>
            )}
          </Typography>
        </Box>
        <StatusChip
          label={backend.available
            ? t("admin.searchIndices.state.healthy")
            : backend.enabled
              ? t("admin.searchIndices.state.unavailable")
              : t("admin.searchIndices.state.disabled")}
          tone={backend.available ? "green" : backend.enabled ? "red" : "neutral"}
          title={backend.reason ?? undefined}
          testId={`search-index-backend-status-${backend.id}`}
        />
      </Box>

      <TextField
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder={t("admin.searchIndices.search")}
        size="small"
        data-testid="search-indices-search"
        sx={{ mb: 1.5, maxWidth: 280 }}
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
            </InputAdornment>
          ),
        }}
      />

      <TableContainer component={Paper} elevation={0}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>{t("admin.searchIndices.column.index")}</TableCell>
              <TableCell>{t("admin.searchIndices.column.source")}</TableCell>
              <TableCell align="right">{t("admin.searchIndices.column.records")}</TableCell>
              <TableCell align="right">{t("admin.searchIndices.column.indexed")}</TableCell>
              <TableCell align="right">{t("admin.searchIndices.column.pending")}</TableCell>
              <TableCell>{t("admin.searchIndices.column.state")}</TableCell>
              <TableCell align="right">{t("admin.searchIndices.column.actions")}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {visibleIndices.map(index => (
              <IndexRow
                key={index.id}
                index={index}
                busy={busy === index.id}
                onAction={onAction}
                onCancel={onCancel}
                onDrop={onDrop}
              />
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}

function IndexRow({
  index,
  busy,
  onAction,
  onCancel,
  onDrop,
}: {
  index: SearchIndexResponse;
  busy: boolean;
  onAction: (index: SearchIndexResponse, action: IndexJobAction) => void;
  onCancel: (index: SearchIndexResponse) => void;
  onDrop: (index: SearchIndexResponse) => void;
}) {
  const { t } = useTranslation();
  const job = index.activeJob && !isTerminal(index.activeJob) ? index.activeJob : null;
  const progress = job ? jobProgress(job) : null;
  const state = indexStateLabel(index);

  return (
    <>
      <TableRow data-testid={`search-index-row-${index.id}`} data-state={state}>
        <TableCell>
          <Typography variant="body2" fontWeight={600}>{index.label ?? index.id}</Typography>
          <Typography variant="caption" color="text.secondary">{index.id}</Typography>
        </TableCell>
        <TableCell>
          <Typography variant="caption" color="text.secondary">{describeSource(index)}</Typography>
        </TableCell>
        <TableCell align="right">{numberFormat(index.documentCount)}</TableCell>
        <TableCell align="right">{numberFormat(index.indexedCount)}</TableCell>
        <TableCell align="right" data-testid={`search-index-pending-${index.id}`}>
          {numberFormat(index.pendingCount)}
        </TableCell>
        <TableCell>
          <StatusChip
            label={t(`admin.searchIndices.state.${state.toLowerCase()}`, { defaultValue: state })}
            tone={indexTone(index)}
            title={index.reason ?? undefined}
            testId={`search-index-status-${index.id}`}
          />
        </TableCell>
        <TableCell align="right">
          <Box sx={{ display: "flex", gap: 0.5, justifyContent: "flex-end", flexWrap: "wrap" }}>
            {job ? (
              <Button
                size="small"
                color="warning"
                data-testid={`search-index-action-${index.id}-cancel`}
                onClick={() => onCancel(index)}
              >
                {t("admin.searchIndices.cancel")}
              </Button>
            ) : (
              // Driven by supportedActions, never hardcoded: the lexical index accepts only a
              // rebuild, and the server rejects the rest regardless of what is rendered here.
              index.supportedActions.map(action => (
                <Button
                  key={action}
                  size="small"
                  startIcon={ACTION_ICONS[action]}
                  color={action === "DROP" ? "error" : "primary"}
                  disabled={busy || !index.available}
                  data-testid={`search-index-action-${index.id}-${action.toLowerCase()}`}
                  onClick={() => (action === "DROP" ? onDrop(index) : onAction(index, action))}
                >
                  {t(`admin.searchIndices.action.${action.toLowerCase()}`)}
                </Button>
              ))
            )}
          </Box>
        </TableCell>
      </TableRow>
      {job && (
        <TableRow>
          <TableCell colSpan={7} sx={{ py: 0.5 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
              <Box sx={{ flex: 1 }}>
                {/* An unknown total means an indeterminate bar. A determinate bar stuck at 0%
                    reads as a hung job, which is the opposite of what is happening. */}
                <LinearProgress
                  variant={progress === null ? "indeterminate" : "determinate"}
                  value={progress ?? 0}
                  data-testid={`search-index-job-progress-${index.id}`}
                  data-progress={progress === null ? "indeterminate" : String(progress)}
                />
              </Box>
              <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: "nowrap" }}>
                {job.action} · {numberFormat(job.processed)}
                {job.total ? ` / ${numberFormat(job.total)}` : ""}
                {job.removed > 0 ? ` · ${t("admin.searchIndices.removed", { count: job.removed })}` : ""}
              </Typography>
            </Box>
          </TableCell>
        </TableRow>
      )}
    </>
  );
}
