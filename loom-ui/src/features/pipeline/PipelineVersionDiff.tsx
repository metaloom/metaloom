import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  Box, Typography, CircularProgress, IconButton,
} from "@mui/material";
import { CloseOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { loadPipelineVersion } from "../../api/pipelines";
import { normalizeDefinition, diffLines, hasChanges, type DiffRow } from "./pipelineDiff";

/**
 * Side-by-side JSON diff between a previous pipeline version and the current
 * one. Both definitions are fetched with `loadPipelineVersion`, normalized to
 * stable JSON, and rendered as two aligned columns with added / removed /
 * changed lines highlighted — so an author can see exactly what a restore of
 * the base version would reintroduce before committing to it.
 */
export function PipelineVersionDiff({
  open, onClose, uuid, token, baseVersion, currentVersion,
}: {
  open: boolean;
  onClose: () => void;
  uuid: string;
  token: string | null;
  /** The older version being compared. `null` while no comparison is active. */
  baseVersion: number | null;
  /** The pipeline's current version number. */
  currentVersion: number | undefined;
}) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [rows, setRows] = useState<DiffRow[]>([]);

  const load = useCallback(async () => {
    if (!token || baseVersion === null || currentVersion === undefined) return;
    setLoading(true);
    setError(null);
    try {
      const [base, current] = await Promise.all([
        loadPipelineVersion(token, uuid, baseVersion),
        loadPipelineVersion(token, uuid, currentVersion),
      ]);
      setRows(diffLines(
        normalizeDefinition(base.definition),
        normalizeDefinition(current.definition),
      ));
    } catch (err) {
      setError((err as Error).message || t("pipeline.diff.failed"));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [token, uuid, baseVersion, currentVersion, t]);

  useEffect(() => {
    if (open) load();
    else { setRows([]); setError(null); }
  }, [open, load]);

  const rowBg = (kind: DiffRow["kind"]) => {
    switch (kind) {
      case "added": return `${tokens.accent.green}22`;
      case "removed": return `${tokens.accent.red}22`;
      case "changed": return `${tokens.accent.amber}22`;
      default: return "transparent";
    }
  };
  const gutter = (kind: DiffRow["kind"]) =>
    kind === "added" ? "+" : kind === "removed" ? "−" : kind === "changed" ? "~" : "";

  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="lg"
      fullWidth
      PaperProps={{
        "data-testid": "pipeline-version-diff",
        sx: {
          bgcolor: tokens.bg.panel,
          border: `1px solid ${tokens.border.default}`,
          borderRadius: tokens.radius.lg,
        },
      }}
    >
      <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, display: "flex", alignItems: "center", gap: 1 }}>
        <Box sx={{ flex: 1 }}>
          {baseVersion !== null && currentVersion !== undefined
            ? t("pipeline.diff.title", { from: baseVersion, to: currentVersion })
            : t("pipeline.diff.title", { from: "?", to: "?" })}
        </Box>
        <IconButton
          data-testid="pipeline-version-diff-close"
          aria-label={t("pipeline.diff.close")}
          size="small"
          onClick={onClose}
          sx={{ color: tokens.text.tertiary }}
        >
          <CloseOutlined sx={{ fontSize: 18 }} />
        </IconButton>
      </DialogTitle>

      <DialogContent dividers sx={{ p: 0, borderColor: tokens.border.subtle }}>
        {loading && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, p: 2 }}>
            <CircularProgress size={14} />
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
              {t("pipeline.diff.loading")}
            </Typography>
          </Box>
        )}

        {!loading && error && (
          <Typography data-testid="pipeline-version-diff-error" variant="caption" sx={{ display: "block", color: tokens.accent.red, p: 2 }}>
            {error}
          </Typography>
        )}

        {!loading && !error && !hasChanges(rows) && (
          <Typography data-testid="pipeline-version-diff-empty" variant="caption" sx={{ display: "block", color: tokens.text.tertiary, p: 2 }}>
            {t("pipeline.diff.empty")}
          </Typography>
        )}

        {!loading && !error && hasChanges(rows) && (
          <Box>
            {/* Column headers */}
            <Box sx={{
              display: "flex", position: "sticky", top: 0, zIndex: 1,
              bgcolor: tokens.bg.panel, borderBottom: `1px solid ${tokens.border.subtle}`,
            }}>
              <Typography variant="caption" sx={{ flex: 1, px: 2, py: 0.75, fontWeight: 700, color: tokens.text.tertiary, textTransform: "uppercase", letterSpacing: "0.06em", fontSize: "0.65rem" }}>
                {t("pipeline.diff.base", { version: baseVersion })}
              </Typography>
              <Typography variant="caption" sx={{ flex: 1, px: 2, py: 0.75, fontWeight: 700, color: tokens.text.tertiary, textTransform: "uppercase", letterSpacing: "0.06em", fontSize: "0.65rem", borderLeft: `1px solid ${tokens.border.subtle}` }}>
                {t("pipeline.diff.current", { version: currentVersion })}
              </Typography>
            </Box>

            <Box sx={{ maxHeight: "60vh", overflow: "auto", fontFamily: "monospace", fontSize: "0.72rem", lineHeight: 1.5 }}>
              {rows.map((row, idx) => (
                <Box
                  key={idx}
                  data-testid={row.kind === "same" ? "pipeline-version-diff-same" : "pipeline-version-diff-changed"}
                  data-diff-kind={row.kind}
                  sx={{ display: "flex", bgcolor: rowBg(row.kind) }}
                >
                  <Box component="pre" sx={{ flex: 1, m: 0, px: 2, whiteSpace: "pre-wrap", wordBreak: "break-word", color: row.left === null ? tokens.text.disabled : tokens.text.primary }}>
                    {row.left !== null ? `${gutter(row.kind === "added" ? "same" : row.kind)} ${row.left}` : ""}
                  </Box>
                  <Box component="pre" sx={{ flex: 1, m: 0, px: 2, whiteSpace: "pre-wrap", wordBreak: "break-word", borderLeft: `1px solid ${tokens.border.subtle}`, color: row.right === null ? tokens.text.disabled : tokens.text.primary }}>
                    {row.right !== null ? `${gutter(row.kind === "removed" ? "same" : row.kind)} ${row.right}` : ""}
                  </Box>
                </Box>
              ))}
            </Box>
          </Box>
        )}
      </DialogContent>

      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} sx={{ color: tokens.text.secondary, textTransform: "none" }}>
          {t("pipeline.diff.close")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
