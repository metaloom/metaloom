import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Alert, Box, Button, Collapse, IconButton, LinearProgress, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, ToggleButton, ToggleButtonGroup, Tooltip, Typography,
  InputAdornment,
} from "@mui/material";
import {
  ExpandLessOutlined, ExpandMoreOutlined, LockOutlined, RefreshOutlined, SearchOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import StatusChip, { type Tone } from "../../components/StatusChip";
import EmptyState from "../../components/EmptyState";
import { useAuth } from "../../context/AuthContext";
import {
  checkStatus, groupByCategory, loadDbIntegrityReport, notRunCount, passedCount, severityCounts,
  type DbIntegrityCategory, type DbIntegrityCheckResult, type DbIntegrityReport,
  type DbIntegrityStatus,
} from "../../api/dbIntegrity";

/**
 * The database integrity report.
 *
 * <p>Shows the whole catalogue, not only what failed. A list of findings answers "what is broken"
 * but not "what was looked at", and those are different questions - an operator running this
 * because something is wrong needs to know whether the rule they suspect is even covered. So every
 * registered check gets a row and a status, and the findings filter narrows to the subset.</p>
 *
 * <p>Deliberately not polled. Unlike the index jobs next door there is nothing running in the
 * background to watch, and a sweep is real database work - re-running it every fifteen seconds on
 * an open tab would be load nobody asked for. The operator presses the button.</p>
 */

const STATUS_TONE: Record<DbIntegrityStatus, Tone> = {
  ERROR: "red",
  WARN: "amber",
  INFO: "neutral",
  PASSED: "green",
  NOT_RUN: "neutral",
};

const CATEGORY_ORDER: DbIntegrityCategory[] = [
  "DANGLING", "TIMESTAMP", "MANDATORY_FIELD", "VOCABULARY", "CARDINALITY",
];

type Filter = "all" | "findings";

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
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");
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
  const passed = useMemo(() => (report ? passedCount(report) : 0), [report]);
  const notRun = useMemo(() => (report ? notRunCount(report) : 0), [report]);

  const visible = useMemo(() => {
    if (!report) return [];
    const byFilter = filter === "all" ? report.results : report.results.filter(r => checkStatus(r) !== "PASSED");
    // Fourteen checks across five categories, and the one you are chasing is named in a ticket.
    // Matches the code as well as the name, because a ticket usually carries the code.
    const q = query.trim().toLowerCase();
    if (!q) return byFilter;
    return byFilter.filter(r =>
      (r.check.name ?? "").toLowerCase().includes(q)
      || (r.check.code ?? "").toLowerCase().includes(q)
      || (r.check.description ?? "").toLowerCase().includes(q));
  }, [report, filter, query]);

  const grouped = useMemo(() => groupByCategory(visible), [visible]);

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
            <StatusChip
              label={t("admin.dbIntegrity.passed", { count: passed })}
              tone="green"
              testId="db-integrity-count-passed"
            />
            {/* Only rendered when it is not zero: a permanent "0 did not run" trains the eye to
                skip the one chip that means the report is incomplete. */}
            {notRun > 0 && (
              <StatusChip
                label={t("admin.dbIntegrity.notRunCount", { count: notRun })}
                tone="amber"
                testId="db-integrity-count-notrun"
              />
            )}
            <Typography variant="caption" color="text.secondary" data-testid="db-integrity-ran">
              {t("admin.dbIntegrity.ran", {
                checks: report.checksRun,
                findings: report.findingCount,
                duration: report.durationMs,
              })}
            </Typography>
            <Box sx={{ flexGrow: 1 }} />
            <TextField
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder={t("admin.dbIntegrity.search")}
              size="small"
              data-testid="db-integrity-search"
              sx={{ maxWidth: 240 }}
              InputProps={{
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                  </InputAdornment>
                ),
              }}
            />
            <ToggleButtonGroup
              size="small"
              exclusive
              value={filter}
              onChange={(_e, value: Filter | null) => value && setFilter(value)}
              data-testid="db-integrity-filter"
            >
              <ToggleButton value="all" data-testid="db-integrity-filter-all">
                {t("admin.dbIntegrity.filter.all")}
              </ToggleButton>
              <ToggleButton value="findings" data-testid="db-integrity-filter-findings">
                {t("admin.dbIntegrity.filter.findings")}
              </ToggleButton>
            </ToggleButtonGroup>
          </Box>
        </Paper>
      )}

      {report && report.clean && (
        <Alert severity="success" sx={{ mb: 2 }} data-testid="db-integrity-clean">
          {t("admin.dbIntegrity.cleanDescription", { checks: report.checksRun })}
        </Alert>
      )}

      {/* Nothing to narrow to. Only reachable with the findings filter on, because the catalogue is
          never empty otherwise. */}
      {report && filter === "findings" && visible.length === 0 && (
        <Typography
          variant="body2"
          color="text.secondary"
          sx={{ px: 1, py: 2 }}
          data-testid="db-integrity-no-findings"
        >
          {t("admin.dbIntegrity.noFindings")}
        </Typography>
      )}

      {CATEGORY_ORDER.filter(category => grouped.has(category)).map(category => (
        <Paper elevation={0} sx={cardSx} key={category} data-testid={`db-integrity-group-${category}`}>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.25 }}>
            {t(`admin.dbIntegrity.category.${category}`)}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 1 }}>
            {t(`admin.dbIntegrity.categoryHint.${category}`)}
          </Typography>
          <TableContainer sx={{ overflowX: "auto" }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell width={32} />
                  <TableCell>{t("admin.dbIntegrity.column.check")}</TableCell>
                  <TableCell>{t("admin.dbIntegrity.column.location")}</TableCell>
                  <TableCell align="right">{t("admin.dbIntegrity.column.rows")}</TableCell>
                  <TableCell>{t("admin.dbIntegrity.column.status")}</TableCell>
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
  const status = checkStatus(result);
  const label = status === "PASSED"
    ? t("admin.dbIntegrity.status.passed")
    : status === "NOT_RUN"
      ? t("admin.dbIntegrity.checkFailed")
      : check.severity;
  return (
    <>
      <TableRow data-testid={`db-integrity-row-${check.code}`}>
        <TableCell>
          {/* Every row expands, including a passing one: the description is where a reader finds
              out what the check would have caught, and that is worth reading before it fires. */}
          <IconButton
            size="small"
            onClick={onToggle}
            aria-label={t("admin.dbIntegrity.toggleSamples")}
            data-testid={`db-integrity-toggle-${check.code}`}
          >
            {open ? <ExpandLessOutlined fontSize="small" /> : <ExpandMoreOutlined fontSize="small" />}
          </IconButton>
        </TableCell>
        <TableCell>
          <Tooltip title={check.description}>
            <Box>
              <Typography
                variant="body2"
                sx={{ fontSize: "0.82rem" }}
                data-testid={`db-integrity-name-${check.code}`}
              >
                {check.name}
              </Typography>
              <Typography
                component="span"
                sx={{ fontFamily: "monospace", fontSize: "0.7rem", color: tokens.text.secondary }}
                data-testid={`db-integrity-code-${check.code}`}
              >
                {check.code}
              </Typography>
            </Box>
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
            label={label}
            tone={STATUS_TONE[status]}
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
