import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Alert, Box, Button, Collapse, IconButton, LinearProgress, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Tooltip, Typography,
} from "@mui/material";
import {
  CheckCircleOutlineOutlined, ExpandLessOutlined, ExpandMoreOutlined, LockOutlined,
  RefreshOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import StatusChip, { type Tone } from "../../components/StatusChip";
import EmptyState from "../../components/EmptyState";
import { useAuth } from "../../context/AuthContext";
import {
  groupByCategory, loadDbIntegrityReport, severityCounts,
  type DbIntegrityCategory, type DbIntegrityCheckResult, type DbIntegrityReport,
  type DbIntegritySeverity,
} from "../../api/dbIntegrity";

/**
 * The database integrity report.
 *
 * <p>Deliberately not polled. Unlike the index jobs next door there is nothing running in the
 * background to watch, and a sweep is real database work - re-running it every fifteen seconds on
 * an open tab would be load nobody asked for. The operator presses the button.</p>
 */

const SEVERITY_TONE: Record<DbIntegritySeverity, Tone> = {
  ERROR: "red",
  WARN: "amber",
  INFO: "neutral",
};

const CATEGORY_ORDER: DbIntegrityCategory[] = [
  "DANGLING", "TIMESTAMP", "MANDATORY_FIELD", "VOCABULARY", "CARDINALITY",
];

const cardSx = {
  p: 2,
  mb: 2,
  border: `1px solid ${tokens.border.subtle}`,
  borderRadius: tokens.radius.md,
  bgcolor: tokens.bg.surface,
};

function isForbidden(error: Error): boolean {
  return error.message.startsWith("API error 403");
}

export default function DbIntegrityAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();

  const [report, setReport] = useState<DbIntegrityReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const response = await loadDbIntegrityReport(token);
      setReport(response);
      setError(null);
      setForbidden(false);
    } catch (e) {
      // A 403 is a different screen from a failed request: the user is not allowed here, which is
      // stable, versus the server hiccupped, which is not. Only the latter keeps the last report.
      if (isForbidden(e as Error)) {
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

  const counts = useMemo(() => (report ? severityCounts(report) : null), [report]);

  const failing = useMemo(
    () => (report ? report.results.filter(r => r.count > 0 || r.error) : []),
    [report],
  );

  const grouped = useMemo(() => groupByCategory(failing), [failing]);

  const toggle = (code: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
  };

  if (forbidden) {
    return (
      <Box data-testid="db-integrity-admin">
        <EmptyState
          icon={LockOutlined}
          title={t("admin.dbIntegrity.forbiddenTitle")}
          description={t("admin.dbIntegrity.forbiddenDescription")}
          testId="db-integrity-forbidden"
        />
      </Box>
    );
  }

  return (
    <Box data-testid="db-integrity-admin">
      <Box sx={{ mb: 2, display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>
            {t("admin.dbIntegrity.title")}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t("admin.dbIntegrity.subtitle")}
          </Typography>
        </Box>
        <Button
          size="small"
          variant="outlined"
          startIcon={<RefreshOutlined sx={{ fontSize: 16 }} />}
          disabled={loading}
          onClick={load}
          data-testid="db-integrity-run"
        >
          {t("admin.dbIntegrity.run")}
        </Button>
      </Box>

      {loading && <LinearProgress sx={{ mb: 2 }} data-testid="db-integrity-loading" />}

      {/* A failed sweep warns but keeps the last report on screen: a blank panel is a worse answer
          than a slightly stale one when the question is "is anything broken". */}
      {error && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="db-integrity-error">
          {t("admin.dbIntegrity.refreshFailed", { error })}
        </Alert>
      )}

      {report && counts && (
        <Paper elevation={0} sx={cardSx} data-testid="db-integrity-summary">
          <Box sx={{ display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap" }}>
            <StatusChip
              label={t("admin.dbIntegrity.errors", { count: counts.ERROR })}
              tone={counts.ERROR > 0 ? "red" : "green"}
              testId="db-integrity-count-error"
            />
            <StatusChip
              label={t("admin.dbIntegrity.warnings", { count: counts.WARN })}
              tone={counts.WARN > 0 ? "amber" : "neutral"}
              testId="db-integrity-count-warn"
            />
            <Typography variant="caption" color="text.secondary" data-testid="db-integrity-ran">
              {t("admin.dbIntegrity.ran", {
                checks: report.checksRun,
                findings: report.findingCount,
                duration: report.durationMs,
              })}
            </Typography>
          </Box>
        </Paper>
      )}

      {report && report.clean && (
        <EmptyState
          icon={CheckCircleOutlineOutlined}
          title={t("admin.dbIntegrity.cleanTitle")}
          description={t("admin.dbIntegrity.cleanDescription", { checks: report.checksRun })}
          testId="db-integrity-clean"
        />
      )}

      {CATEGORY_ORDER.filter(category => grouped.has(category)).map(category => (
        <Paper elevation={0} sx={cardSx} key={category} data-testid={`db-integrity-group-${category}`}>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
            {t(`admin.dbIntegrity.category.${category}`)}
          </Typography>
          <TableContainer sx={{ overflowX: "auto" }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell width={32} />
                  <TableCell>{t("admin.dbIntegrity.column.check")}</TableCell>
                  <TableCell>{t("admin.dbIntegrity.column.location")}</TableCell>
                  <TableCell align="right">{t("admin.dbIntegrity.column.rows")}</TableCell>
                  <TableCell>{t("admin.dbIntegrity.column.severity")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {(grouped.get(category) ?? []).map(result => (
                  <ResultRows
                    key={result.check.code}
                    result={result}
                    open={expanded.has(result.check.code)}
                    onToggle={() => toggle(result.check.code)}
                    t={t}
                  />
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      ))}
    </Box>
  );
}

function ResultRows({
  result,
  open,
  onToggle,
  t,
}: {
  result: DbIntegrityCheckResult;
  open: boolean;
  onToggle: () => void;
  t: (key: string, opts?: Record<string, unknown>) => string;
}) {
  const { check } = result;
  const hasSamples = result.samples.length > 0 || !!result.error;
  return (
    <>
      <TableRow data-testid={`db-integrity-row-${check.code}`}>
        <TableCell>
          {hasSamples && (
            <IconButton
              size="small"
              onClick={onToggle}
              aria-label={t("admin.dbIntegrity.toggleSamples")}
              data-testid={`db-integrity-toggle-${check.code}`}
            >
              {open ? <ExpandLessOutlined fontSize="small" /> : <ExpandMoreOutlined fontSize="small" />}
            </IconButton>
          )}
        </TableCell>
        <TableCell>
          <Tooltip title={check.description}>
            <Typography
              component="span"
              sx={{ fontFamily: "monospace", fontSize: "0.78rem" }}
              data-testid={`db-integrity-code-${check.code}`}
            >
              {check.code}
            </Typography>
          </Tooltip>
        </TableCell>
        <TableCell>
          <Typography variant="caption" color="text.secondary">
            {check.column ? `${check.table}.${check.column}` : check.table}
          </Typography>
        </TableCell>
        <TableCell align="right" data-testid={`db-integrity-count-${check.code}`}>
          {result.error ? "-" : result.count.toLocaleString()}
        </TableCell>
        <TableCell>
          <StatusChip
            label={result.error ? t("admin.dbIntegrity.checkFailed") : check.severity}
            tone={SEVERITY_TONE[check.severity]}
            testId={`db-integrity-severity-${check.code}`}
          />
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell sx={{ py: 0, border: 0 }} colSpan={5}>
          <Collapse in={open} unmountOnExit>
            <Box sx={{ py: 1.5, pl: 4 }} data-testid={`db-integrity-samples-${check.code}`}>
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 0.5 }}>
                {check.description}
              </Typography>
              {result.error ? (
                <Alert severity="error" sx={{ mt: 1 }}>
                  {t("admin.dbIntegrity.checkFailedDetail", { error: result.error })}
                </Alert>
              ) : (
                <>
                  {result.samples.map(sample => (
                    <Typography
                      key={sample}
                      sx={{ fontFamily: "monospace", fontSize: "0.72rem", color: tokens.text.secondary }}
                    >
                      {sample}
                    </Typography>
                  ))}
                  {/* The server caps the sample list, so say what is not shown rather than
                      letting the row count and the list silently disagree. */}
                  {result.count > result.samples.length && (
                    <Typography variant="caption" color="text.secondary">
                      {t("admin.dbIntegrity.andMore", { count: result.count - result.samples.length })}
                    </Typography>
                  )}
                </>
              )}
            </Box>
          </Collapse>
        </TableCell>
      </TableRow>
    </>
  );
}
