import React, { useCallback, useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import PhotoCameraIcon from "@mui/icons-material/PhotoCamera";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutline";
import ZoomInIcon from "@mui/icons-material/ZoomIn";
import CloseIcon from "@mui/icons-material/Close";
import { useAuth } from "../context/AuthContext";
import { createFailureReport } from "../api/failureReports";
import { failureSummary, type Failure } from "../failure/failure";
import { captureScreenshot, isScreenshotSupported, type Screenshot } from "../failure/screenshot";

/**
 * The form a user fills in to report a failure.
 *
 * **What it is for.** The server already logs every failure in more detail than a user could ever
 * supply. What it cannot log is the half only the user has: what they were trying to do and what
 * they expected instead. This collects that half and staples it to the trace id, which is what
 * lets an operator find the stack trace behind it.
 *
 * **Everything technical is shown, not hidden.** The action, the request, the status and above
 * all the trace id are rendered read-only where the user can see them - and the trace id is
 * copyable, because a user who prefers to paste it into a support channel should not have to
 * submit a form to get at it.
 */

interface Props {
  /** The failure to report, or null when the dialog is closed. */
  failure: Failure | null;
  onClose: () => void;
}

export default function FailureReportDialog({ failure, onClose }: Props) {
  const { token } = useAuth();
  const [text, setText] = useState("");
  const [screenshot, setScreenshot] = useState<Screenshot | null>(null);
  const [enlarged, setEnlarged] = useState(false);
  const [capturing, setCapturing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [submitted, setSubmitted] = useState(false);

  // Reset per failure rather than per mount: the dialog stays mounted between reports, and
  // carrying one failure's screenshot or prose into the next report would be worse than useless.
  useEffect(() => {
    if (failure) {
      setText("");
      setScreenshot(null);
      setEnlarged(false);
      setError(null);
      setCopied(false);
      setSubmitted(false);
    }
  }, [failure]);

  const capture = useCallback(async () => {
    setError(null);
    setCapturing(true);
    try {
      const shot = await captureScreenshot();
      // Null means the user dismissed the picker. That is a choice, not a failure, so it must
      // leave no error behind - the person is already dealing with one.
      if (shot) setScreenshot(shot);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The screen could not be captured.");
    } finally {
      setCapturing(false);
    }
  }, []);

  const copyTraceId = useCallback(async () => {
    if (!failure?.traceId) return;
    try {
      await navigator.clipboard.writeText(failure.traceId);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // A denied clipboard permission is not worth an error state: the id is on screen and
      // selectable, which is the fallback every user already knows how to use.
    }
  }, [failure]);

  const submit = useCallback(async () => {
    if (!failure || !token) return;
    setSubmitting(true);
    setError(null);
    try {
      await createFailureReport(token, {
        action: failure.action,
        traceId: failure.traceId,
        httpMethod: failure.method,
        path: failure.path,
        statusCode: failure.status,
        errorMessage: failure.message,
        route: failure.route,
        text: text.trim() || undefined,
        screenshot: screenshot?.dataUrl,
        screenshotWidth: screenshot?.width,
        screenshotHeight: screenshot?.height,
      });
      setSubmitted(true);
      // Held open briefly so the confirmation is actually seen. Closing on success would leave a
      // user who has just been told something failed with no evidence anything was sent.
      window.setTimeout(onClose, 1200);
    } catch (e) {
      // Reported inline, never through the failure surface: routing this through reportFailure
      // would raise a toast offering to report the failure of a report, which is a loop.
      setError(
        e instanceof Error && e.message
          ? `The report could not be submitted: ${e.message}`
          : "The report could not be submitted.",
      );
    } finally {
      setSubmitting(false);
    }
  }, [failure, onClose, screenshot, text, token]);

  if (!failure) return null;

  const summary = failureSummary(failure);

  return (
    <>
      <Dialog open fullWidth maxWidth="sm" onClose={submitting ? undefined : onClose} data-testid="failure-report-dialog">
        <DialogTitle>Report a problem</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2}>
            <Alert severity="error" variant="outlined" data-testid="failure-report-message">
              {failure.message}
            </Alert>

            <Box>
              <Typography variant="caption" color="text.secondary">
                What was being done
              </Typography>
              <Typography variant="body2" data-testid="failure-report-action">
                {failure.action}
                {summary ? ` — ${summary}` : ""}
              </Typography>
            </Box>

            {failure.traceId ? (
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Trace ID — quote this and an operator can find the exact request in the server log
                </Typography>
                <Stack direction="row" alignItems="center" spacing={1}>
                  <Chip
                    label={failure.traceId}
                    size="small"
                    sx={{ fontFamily: "monospace" }}
                    data-testid="failure-report-trace-id"
                  />
                  <Tooltip title={copied ? "Copied" : "Copy the trace ID"}>
                    <IconButton size="small" onClick={copyTraceId} aria-label="Copy the trace ID">
                      <ContentCopyIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                </Stack>
              </Box>
            ) : (
              // Said out loud rather than left blank: an absent trace id is normal for a failure
              // that never reached the server, and a user should not wonder what they did wrong.
              <Typography variant="caption" color="text.secondary" data-testid="failure-report-no-trace-id">
                No trace ID — this failure did not produce a server response.
              </Typography>
            )}

            <TextField
              label="What happened?"
              placeholder="What were you expecting, and what did you get instead?"
              multiline
              minRows={3}
              fullWidth
              value={text}
              onChange={event => setText(event.target.value)}
              inputProps={{ "data-testid": "failure-report-text" }}
            />

            {isScreenshotSupported() ? (
              <Box>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Button
                    size="small"
                    startIcon={capturing ? <CircularProgress size={14} /> : <PhotoCameraIcon />}
                    onClick={capture}
                    disabled={capturing || submitting}
                    data-testid="failure-report-capture"
                  >
                    {screenshot ? "Capture again" : "Attach a screenshot"}
                  </Button>
                  {screenshot && (
                    <Button
                      size="small"
                      color="inherit"
                      startIcon={<DeleteOutlineIcon />}
                      onClick={() => setScreenshot(null)}
                      disabled={submitting}
                      data-testid="failure-report-remove-screenshot"
                    >
                      Remove
                    </Button>
                  )}
                </Stack>
                <Typography variant="caption" color="text.secondary">
                  Your browser will ask which window or tab to capture. Nothing is attached until you choose one, and
                  the result is shown below before anything is sent.
                </Typography>

                {screenshot && (
                  <Box
                    sx={{
                      mt: 1,
                      position: "relative",
                      display: "inline-block",
                      border: 1,
                      borderColor: "divider",
                      borderRadius: 1,
                      overflow: "hidden",
                      cursor: "zoom-in",
                    }}
                    onClick={() => setEnlarged(true)}
                    data-testid="failure-report-screenshot-preview"
                  >
                    <Box
                      component="img"
                      src={screenshot.dataUrl}
                      alt={`Screenshot attached to this report, ${screenshot.width} by ${screenshot.height} pixels`}
                      sx={{ display: "block", maxWidth: 320, maxHeight: 180, objectFit: "contain" }}
                    />
                    <IconButton
                      size="small"
                      aria-label="Enlarge the screenshot"
                      sx={{ position: "absolute", right: 4, bottom: 4, bgcolor: "background.paper" }}
                      onClick={event => {
                        event.stopPropagation();
                        setEnlarged(true);
                      }}
                    >
                      <ZoomInIcon fontSize="small" />
                    </IconButton>
                  </Box>
                )}
              </Box>
            ) : (
              // The button is hidden rather than disabled: getDisplayMedia needs a secure context,
              // so on a plain-HTTP deployment offering it at all would only produce a control that
              // can do nothing but fail.
              <Typography variant="caption" color="text.secondary" data-testid="failure-report-no-screenshot-support">
                Screenshots need a secure (HTTPS) connection, so none can be attached here.
              </Typography>
            )}

            {error && (
              <Alert severity="error" data-testid="failure-report-error">
                {error}
              </Alert>
            )}
            {submitted && (
              <Alert severity="success" data-testid="failure-report-submitted">
                Thank you — the report was submitted.
              </Alert>
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={submitting} data-testid="failure-report-cancel">
            Cancel
          </Button>
          <Button
            variant="contained"
            onClick={submit}
            disabled={submitting || submitted}
            data-testid="failure-report-submit"
          >
            {submitting ? "Sending…" : "Send report"}
          </Button>
        </DialogActions>
      </Dialog>

      {/* The enlarged view. A second Dialog rather than a bigger preview, because the point is to
          read text in the screenshot, which needs the whole viewport. */}
      <Dialog open={enlarged} onClose={() => setEnlarged(false)} maxWidth="xl" data-testid="failure-report-lightbox">
        <IconButton
          onClick={() => setEnlarged(false)}
          aria-label="Close the enlarged screenshot"
          sx={{ position: "absolute", right: 8, top: 8, bgcolor: "background.paper", zIndex: 1 }}
        >
          <CloseIcon />
        </IconButton>
        {screenshot && (
          <Box
            component="img"
            src={screenshot.dataUrl}
            alt="Screenshot attached to this report, enlarged"
            sx={{ display: "block", maxWidth: "90vw", maxHeight: "90vh", objectFit: "contain" }}
          />
        )}
      </Dialog>
    </>
  );
}
