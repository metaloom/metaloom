import React, { useCallback, useEffect, useState } from "react";
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  IconButton,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from "@mui/material";
import CloseIcon from "@mui/icons-material/Close";
import DeleteOutlineOutlined from "@mui/icons-material/DeleteOutlineOutlined";
import ImageOutlined from "@mui/icons-material/ImageOutlined";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useFailure } from "../../context/FailureContext";
import EmptyState from "../../components/EmptyState";
import LoadFailure from "../../components/LoadFailure";
import { ListFilterSelect } from "../../components/ListControls";
import {
  deleteFailureReport,
  listFailureReports,
  updateFailureReport,
  type FailureReportResponse,
  type FailureReportTriageStatus,
} from "../../api/failureReports";

/**
 * The problem-report inbox.
 *
 * The read half of the failure path: users submit reports from wherever they were standing, and
 * this is where somebody reads them. Without it the submit button would write to a table nobody
 * ever opens, which is a worse outcome than not offering to collect reports at all - it promises
 * the user that somebody is listening.
 *
 * The trace id is rendered first and in monospace on purpose: it is the value an operator carries
 * over to the server log, and everything else on the row is context for it.
 */

const STATUS_COLOURS: Record<FailureReportTriageStatus, "default" | "warning" | "success"> = {
  NEW: "warning",
  ACKNOWLEDGED: "default",
  RESOLVED: "success",
};

export default function FailureReportsAdmin() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { reportFailure } = useFailure();
  const [reports, setReports] = useState<FailureReportResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState("");
  const [enlarged, setEnlarged] = useState<FailureReportResponse | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setLoadError(null);
    try {
      const resp = await listFailureReports(token, { limit: 50 });
      setReports(resp.data ?? []);
    } catch (e) {
      setLoadError(reportFailure("loadFailureReports", e).message);
    } finally {
      setLoading(false);
    }
  }, [reportFailure, token]);

  useEffect(() => {
    load();
  }, [load]);

  const triage = async (report: FailureReportResponse, triageStatus: FailureReportTriageStatus) => {
    if (!token) return;
    try {
      const updated = await updateFailureReport(token, report.uuid, { triageStatus });
      setReports(prev => prev.map(r => (r.uuid === updated.uuid ? updated : r)));
    } catch (e) {
      // The chip keeps its old value, because the row kept its old value.
      reportFailure("triageFailureReport", e);
    }
  };

  const remove = async (report: FailureReportResponse) => {
    if (!token) return;
    try {
      await deleteFailureReport(token, report.uuid);
      setReports(prev => prev.filter(r => r.uuid !== report.uuid));
    } catch (e) {
      reportFailure("deleteFailureReport", e);
    }
  };

  const visible = statusFilter ? reports.filter(r => r.triageStatus === statusFilter) : reports;

  if (loading) {
    return (
      <Box sx={{ display: "flex", justifyContent: "center", py: 6 }} data-testid="failure-reports-loading">
        <CircularProgress size={28} />
      </Box>
    );
  }
  if (loadError) {
    return <LoadFailure message={loadError} onRetry={load} testId="failure-reports-load-failure" />;
  }

  return (
    <Box>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Box>
          <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>
            {t("admin.failureReports.title")}
          </Typography>
          <Typography variant="caption" color="text.secondary" data-testid="failure-reports-count">
            {t("admin.failureReports.count", { count: reports.length })}
          </Typography>
        </Box>
        <ListFilterSelect
          value={statusFilter}
          onChange={setStatusFilter}
          options={[
            { value: "NEW", label: t("admin.failureReports.status.NEW") },
            { value: "ACKNOWLEDGED", label: t("admin.failureReports.status.ACKNOWLEDGED") },
            { value: "RESOLVED", label: t("admin.failureReports.status.RESOLVED") },
          ]}
          allLabel={t("admin.failureReports.filter.all")}
          testId="failure-reports-filter-status"
          minWidth={160}
        />
      </Box>

      {visible.length === 0 ? (
        <EmptyState
          icon={ImageOutlined}
          title={t("admin.failureReports.emptyState.title")}
          description={t("admin.failureReports.emptyState.description")}
          testId="failure-reports-empty-state"
        />
      ) : (
        <Paper variant="outlined" sx={{ bgcolor: tokens.bg.surface }}>
          <Table size="small" data-testid="failure-reports-table">
            <TableHead>
              <TableRow>
                <TableCell>{t("admin.failureReports.column.trace")}</TableCell>
                <TableCell>{t("admin.failureReports.column.action")}</TableCell>
                <TableCell>{t("admin.failureReports.column.report")}</TableCell>
                <TableCell>{t("admin.failureReports.column.status")}</TableCell>
                <TableCell align="right" />
              </TableRow>
            </TableHead>
            <TableBody>
              {visible.map(report => (
                <TableRow key={report.uuid} data-testid={`failure-report-row-${report.uuid}`}>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: "0.75rem", whiteSpace: "nowrap" }}>
                    {report.traceId ?? t("admin.failureReports.noTrace")}
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2">{report.action}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {[report.httpMethod, report.path].filter(Boolean).join(" ")}
                      {report.statusCode ? ` · HTTP ${report.statusCode}` : ""}
                      {report.route ? ` · ${report.route}` : ""}
                    </Typography>
                  </TableCell>
                  <TableCell sx={{ maxWidth: 360 }}>
                    <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
                      {report.text || <em>{t("admin.failureReports.noText")}</em>}
                    </Typography>
                    {report.hasScreenshot && report.screenshotUrl && (
                      <Button
                        size="small"
                        startIcon={<ImageOutlined />}
                        onClick={() => setEnlarged(report)}
                        data-testid={`failure-report-screenshot-${report.uuid}`}
                      >
                        {t("admin.failureReports.viewScreenshot")}
                      </Button>
                    )}
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={t(`admin.failureReports.status.${report.triageStatus}`)}
                      color={STATUS_COLOURS[report.triageStatus] ?? "default"}
                    />
                  </TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                      {report.triageStatus !== "ACKNOWLEDGED" && (
                        <Button size="small" onClick={() => triage(report, "ACKNOWLEDGED")}>
                          {t("admin.failureReports.action.acknowledge")}
                        </Button>
                      )}
                      {report.triageStatus !== "RESOLVED" && (
                        <Button size="small" onClick={() => triage(report, "RESOLVED")}>
                          {t("admin.failureReports.action.resolve")}
                        </Button>
                      )}
                      <Tooltip title={t("admin.failureReports.action.delete")}>
                        <IconButton
                          size="small"
                          onClick={() => remove(report)}
                          aria-label={t("admin.failureReports.action.delete")}
                          data-testid={`failure-report-delete-${report.uuid}`}
                        >
                          <DeleteOutlineOutlined sx={{ fontSize: 15, color: tokens.accent.red }} />
                        </IconButton>
                      </Tooltip>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}

      {/* The screenshot, full size. Fetched from its own route rather than inlined into the list,
          which is why the listing stays a listing - see the V2.107 note on the separate table.

          The <img> carries the bearer token nowhere, because it cannot: an <img src> issues a plain
          GET with no Authorization header. The route is behind READ_FAILURE_REPORT, so this works
          only where the browser also holds a session cookie. Where it does not, the image fails to
          load and the alt text says so rather than showing a broken icon. */}
      <Dialog open={enlarged !== null} onClose={() => setEnlarged(null)} maxWidth="xl" data-testid="failure-report-screenshot-dialog">
        <IconButton
          onClick={() => setEnlarged(null)}
          aria-label={t("common.close")}
          sx={{ position: "absolute", right: 8, top: 8, bgcolor: "background.paper", zIndex: 1 }}
        >
          <CloseIcon />
        </IconButton>
        {enlarged?.screenshotUrl && (
          <Box
            component="img"
            src={enlarged.screenshotUrl}
            alt={t("admin.failureReports.screenshotAlt", { action: enlarged.action })}
            sx={{ display: "block", maxWidth: "90vw", maxHeight: "90vh", objectFit: "contain" }}
          />
        )}
      </Dialog>
    </Box>
  );
}
