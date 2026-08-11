import React, { useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  IconButton,
  InputAdornment,
  MenuItem,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import ContentCopyOutlined from "@mui/icons-material/ContentCopyOutlined";
import RefreshOutlined from "@mui/icons-material/RefreshOutlined";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { createShareLink, updateShareLink, type ShareResponse, type ShareTargetType, type ShareUpdateRequest } from "../../api/shareLinks";
import { DEFAULT_EXPIRY, EXPIRY_CHOICES, expiryToIso, generatePassword, type ExpiryChoice } from "./shareExpiry";

export interface ShareDialogProps {
  open: boolean;
  onClose: () => void;
  targetType: ShareTargetType;
  targetUuid: string;
  /** Shown in the heading so the user can see what they are about to publish. */
  targetName?: string;
}

/**
 * Create a share link.
 *
 * The link is created when the dialog opens, not when it closes: the point of the dialog is the
 * URL, and a "create" button would mean the user has to press something before they can copy the
 * thing they came for. Changing expiry or the password after that updates the same link, so there
 * is never a half-made one and never two.
 */
export default function ShareDialog({ open, onClose, targetType, targetUuid, targetName }: ShareDialogProps) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();

  const [share, setShare] = useState<ShareResponse | null>(null);
  const [expiry, setExpiry] = useState<ExpiryChoice>(DEFAULT_EXPIRY);
  const [password, setPassword] = useState("");
  const [usePassword, setUsePassword] = useState(true);
  const [allowFeedback, setAllowFeedback] = useState(true);
  const [allowDownload, setAllowDownload] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // A fresh dialog is a fresh link. Reopening on the same asset deliberately mints a new one
  // rather than resurrecting the last: two reviewers should get two links, which is also the only
  // way their feedback stays apart under the one-name-per-link model.
  useEffect(() => {
    if (!open || !token) return;
    const generated = generatePassword();
    setShare(null);
    setError(null);
    setExpiry(DEFAULT_EXPIRY);
    setPassword(generated);
    setUsePassword(true);
    setAllowFeedback(true);
    setAllowDownload(true);
    setBusy(true);

    createShareLink(token, {
      targetType,
      targetUuid,
      password: generated,
      expiresAt: expiryToIso(DEFAULT_EXPIRY),
      allowDownload: true,
      showMetadata: true,
      allowComments: true,
      allowReactions: true,
      allowAnnotations: true,
    })
      .then(setShare)
      .catch(() => setError(t("share.dialog.createFailed")))
      .finally(() => setBusy(false));
  }, [open, token, targetType, targetUuid, t]);

  const copyLink = async () => {
    if (!share) return;
    try {
      await navigator.clipboard.writeText(share.url);
      showToast(t("share.dialog.copied"), "success");
    } catch {
      // Clipboard access can be refused outright (insecure context, denied permission). The link
      // is on screen and selectable, so this is a missed convenience rather than a dead end.
      showToast(t("share.dialog.copyFailed"), "error");
    }
  };

  const apply = async (changes: ShareUpdateRequest) => {
    if (!share || !token) return;
    setBusy(true);
    try {
      const updated = await updateShareLink(token, share.uuid, changes);
      // The password is only ever returned once, on create, so carry the one already on screen
      // rather than letting the update response blank it.
      setShare({ ...updated, password: share.password });
    } catch {
      setError(t("share.dialog.updateFailed"));
    } finally {
      setBusy(false);
    }
  };

  const onExpiryChange = (next: ExpiryChoice) => {
    setExpiry(next);
    const iso = expiryToIso(next);
    void apply(iso ? { expiresAt: iso } : { clearExpiry: true });
  };

  const onPasswordToggle = (enabled: boolean) => {
    setUsePassword(enabled);
    void apply(enabled ? { password } : { removePassword: true });
  };

  const regeneratePassword = () => {
    const next = generatePassword();
    setPassword(next);
    void apply({ password: next });
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 460 } }}
    >
      <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }} data-testid="share-dialog-title">
        {t("share.dialog.title", { name: targetName ?? "" })}
      </DialogTitle>
      <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
        {error && (
          <Alert severity="error" data-testid="share-dialog-error">
            {error}
          </Alert>
        )}

        <TextField
          label={t("share.dialog.linkLabel")}
          size="small"
          fullWidth
          value={share?.url ?? ""}
          InputProps={{
            readOnly: true,
            endAdornment: (
              <InputAdornment position="end">
                <Tooltip title={t("share.dialog.copy")}>
                  <span>
                    <IconButton size="small" onClick={copyLink} disabled={!share} data-testid="share-dialog-copy">
                      <ContentCopyOutlined sx={{ fontSize: 17 }} />
                    </IconButton>
                  </span>
                </Tooltip>
              </InputAdornment>
            ),
          }}
          inputProps={{ "data-testid": "share-dialog-link" }}
        />

        <TextField
          select
          label={t("share.dialog.expiryLabel")}
          size="small"
          fullWidth
          value={expiry}
          onChange={(e) => onExpiryChange(e.target.value as ExpiryChoice)}
          disabled={!share || busy}
          // The testid has to land on the element a person clicks. MUI's `inputProps` reach the
          // hidden native input behind a select, which is never clickable; SelectDisplayProps reach
          // the visible combobox.
          SelectProps={{ SelectDisplayProps: { "data-testid": "share-dialog-expiry" } as never }}
        >
          {EXPIRY_CHOICES.map((choice) => (
            <MenuItem key={choice} value={choice}>
              {t(`share.expiry.${choice}`)}
            </MenuItem>
          ))}
        </TextField>

        <Box>
          <FormControlLabel
            control={
              <Checkbox
                size="small"
                checked={usePassword}
                onChange={(e) => onPasswordToggle(e.target.checked)}
                disabled={!share || busy}
                data-testid="share-dialog-password-toggle"
              />
            }
            label={<Typography variant="body2">{t("share.dialog.passwordProtect")}</Typography>}
          />
          {usePassword && (
            <TextField
              size="small"
              fullWidth
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onBlur={() => void apply({ password })}
              disabled={!share || busy}
              InputProps={{
                endAdornment: (
                  <InputAdornment position="end">
                    <Tooltip title={t("share.dialog.regenerate")}>
                      <span>
                        <IconButton size="small" onClick={regeneratePassword} disabled={!share || busy}>
                          <RefreshOutlined sx={{ fontSize: 17 }} />
                        </IconButton>
                      </span>
                    </Tooltip>
                  </InputAdornment>
                ),
              }}
              inputProps={{ "data-testid": "share-dialog-password" }}
            />
          )}
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, display: "block", mt: 0.5 }}>
            {t("share.dialog.passwordHint")}
          </Typography>
        </Box>

        <Divider sx={{ borderColor: tokens.border.subtle }} />

        <Box>
          <FormControlLabel
            control={
              <Checkbox
                size="small"
                checked={allowDownload}
                onChange={(e) => {
                  setAllowDownload(e.target.checked);
                  void apply({ allowDownload: e.target.checked });
                }}
                disabled={!share || busy}
                data-testid="share-dialog-download"
              />
            }
            label={<Typography variant="body2">{t("share.dialog.allowDownload")}</Typography>}
          />
          <FormControlLabel
            control={
              <Checkbox
                size="small"
                checked={allowFeedback}
                onChange={(e) => {
                  setAllowFeedback(e.target.checked);
                  void apply({
                    allowComments: e.target.checked,
                    allowReactions: e.target.checked,
                    allowAnnotations: e.target.checked,
                  });
                }}
                disabled={!share || busy}
                data-testid="share-dialog-feedback"
              />
            }
            label={<Typography variant="body2">{t("share.dialog.allowFeedback")}</Typography>}
          />
        </Box>

        {/* The consequence of one-name-per-link, said plainly rather than left to be discovered
            when two reviewers' notes turn out to be attributed to one of them. */}
        <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
          {t("share.dialog.oneLinkPerReviewer")}
        </Typography>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} size="small" variant="contained" data-testid="share-dialog-done">
          {t("share.dialog.done")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
