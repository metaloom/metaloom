import React, { useCallback, useEffect, useState } from "react";
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Paper,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import CloseOutlined from "@mui/icons-material/CloseOutlined";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import StarOutlined from "@mui/icons-material/StarOutlined";
import StarBorderOutlined from "@mui/icons-material/StarBorderOutlined";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import AssetThumbnail from "../../components/AssetThumbnail";
import { assetBinaryUrl } from "../../api/assets";
import { assetTypeFromMime } from "../assets/assetMapping";
import {
  deleteRemix,
  listRemixMembers,
  loadRemix,
  removeRemixAsset,
  setRemixSource,
  updateRemix,
  type RemixMemberResponse,
  type RemixResponse,
} from "../../api/remixes";

export interface RemixDialogProps {
  /** Uuid of the remix to show, or null when the dialog is closed. */
  remixUuid: string | null;
  onClose: () => void;
  /** Called after a change that alters the remix list — a rename, a member change or a delete. */
  onChanged?: () => void;
}

/**
 * The opened remix: its members, and the edits that can be made to them.
 *
 * <p>A dialog rather than a route, so opening a remix does not cost the user their scroll
 * position in the asset grid. The caller keeps the open state in the URL (`?remix=<uuid>`), which
 * is what makes it deep-linkable and reachable from a test or the screenshot script.</p>
 */
export default function RemixDialog({ remixUuid, onClose, onChanged }: RemixDialogProps) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [remix, setRemix] = useState<RemixResponse | null>(null);
  const [members, setMembers] = useState<RemixMemberResponse[]>([]);
  const [name, setName] = useState("");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!token || !remixUuid) return;
    setLoading(true);
    try {
      const [r, m] = await Promise.all([loadRemix(token, remixUuid), listRemixMembers(token, remixUuid)]);
      setRemix(r);
      setName(r.name);
      setMembers(m.data ?? []);
    } catch {
      showToast(t("remix.error.load"), "error");
      onClose();
    } finally {
      setLoading(false);
    }
    // onClose/showToast/t are stable enough here; re-running on them would refetch on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, remixUuid]);

  useEffect(() => {
    if (remixUuid) {
      load();
    } else {
      setRemix(null);
      setMembers([]);
    }
  }, [remixUuid, load]);

  const handleRename = async () => {
    if (!token || !remix || name.trim() === "" || name === remix.name) return;
    setBusy(true);
    try {
      const updated = await updateRemix(token, remix.uuid, { name: name.trim() });
      setRemix(updated);
      onChanged?.();
      showToast(t("remix.toast.renamed"), "success");
    } catch {
      showToast(t("remix.error.rename"), "error");
    } finally {
      setBusy(false);
    }
  };

  const handleRemoveMember = async (assetUuid: string) => {
    if (!token || !remix) return;
    setBusy(true);
    try {
      await removeRemixAsset(token, remix.uuid, assetUuid);
      await load();
      onChanged?.();
    } catch {
      showToast(t("remix.error.removeMember"), "error");
    } finally {
      setBusy(false);
    }
  };

  const handleSetSource = async (assetUuid: string) => {
    if (!token || !remix) return;
    setBusy(true);
    try {
      await setRemixSource(token, remix.uuid, assetUuid);
      await load();
      onChanged?.();
    } catch {
      showToast(t("remix.error.setSource"), "error");
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!token || !remix) return;
    setBusy(true);
    try {
      await deleteRemix(token, remix.uuid);
      onChanged?.();
      onClose();
      showToast(t("remix.toast.deleted"), "success");
    } catch {
      showToast(t("remix.error.delete"), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={remixUuid != null}
      onClose={onClose}
      maxWidth="md"
      fullWidth
      data-testid="remix-dialog"
      PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}` } }}
    >
      <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1, display: "flex", alignItems: "center", gap: 1 }}>
        <TextField
          value={name}
          onChange={e => setName(e.target.value)}
          onBlur={handleRename}
          onKeyDown={e => { if (e.key === "Enter") handleRename(); }}
          variant="standard"
          size="small"
          disabled={busy || loading}
          inputProps={{ "aria-label": t("remix.dialog.nameLabel"), "data-testid": "remix-name-input" }}
          sx={{ flexGrow: 1 }}
        />
        <IconButton size="small" onClick={onClose} aria-label={t("common.close")}>
          <CloseOutlined sx={{ fontSize: 18 }} />
        </IconButton>
      </DialogTitle>

      <DialogContent sx={{ pt: "8px !important" }}>
        {remix?.description && (
          <Typography variant="body2" sx={{ color: tokens.text.secondary, mb: 1.5 }}>
            {remix.description}
          </Typography>
        )}
        <Typography variant="caption" sx={{ color: tokens.text.tertiary }} data-testid="remix-member-count">
          {t("remix.card.members", { count: remix?.memberCount ?? 0 })}
        </Typography>

        <Box
          sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(160px, 1fr))", gap: 2, mt: 1.5 }}
          data-testid="remix-members"
        >
          {members.map(member => {
            const type = assetTypeFromMime(member.mimeType ?? "");
            return (
              <Paper
                key={member.uuid}
                elevation={0}
                data-testid="remix-member"
                data-asset-uuid={member.assetUuid}
                sx={{
                  position: "relative",
                  bgcolor: tokens.bg.elevated,
                  // The source member is the one the group is built around, so it is the one the
                  // border calls out — the star alone is easy to miss in a grid.
                  border: `1px solid ${member.role === "SOURCE" ? tokens.primary.main : tokens.border.subtle}`,
                  borderRadius: tokens.radius.lg,
                  overflow: "hidden",
                  "&:hover .remix-member-actions": { opacity: 1 },
                }}
              >
                <Box
                  sx={{ position: "relative", paddingTop: "56.25%", bgcolor: tokens.bg.overlay, cursor: "pointer" }}
                  onClick={() => { onClose(); navigate(`/assets/${member.assetUuid}`); }}
                >
                  <AssetThumbnail
                    type={type}
                    src={type === "image" ? assetBinaryUrl(member.assetUuid) : ""}
                    iconSize={32}
                    alt={member.filename ?? ""}
                  />
                </Box>

                <Box
                  className="remix-member-actions"
                  sx={{ position: "absolute", top: 4, right: 4, display: "flex", gap: 0.5, opacity: 0, transition: "opacity 140ms ease" }}
                >
                  <Tooltip title={member.role === "SOURCE" ? t("remix.member.isSource") : t("remix.member.makeSource")}>
                    <span>
                      <IconButton
                        size="small"
                        disabled={busy || member.role === "SOURCE"}
                        onClick={() => handleSetSource(member.assetUuid)}
                        data-testid="remix-member-source"
                        sx={{ bgcolor: "rgba(0,0,0,0.6)", width: 24, height: 24 }}
                      >
                        {member.role === "SOURCE"
                          ? <StarOutlined sx={{ fontSize: 13, color: tokens.primary.light }} />
                          : <StarBorderOutlined sx={{ fontSize: 13, color: "#fff" }} />}
                      </IconButton>
                    </span>
                  </Tooltip>
                  <Tooltip title={t("remix.member.remove")}>
                    <span>
                      <IconButton
                        size="small"
                        disabled={busy}
                        onClick={() => handleRemoveMember(member.assetUuid)}
                        data-testid="remix-member-remove"
                        sx={{ bgcolor: "rgba(0,0,0,0.6)", color: tokens.accent.red, width: 24, height: 24 }}
                      >
                        <DeleteOutlined sx={{ fontSize: 13 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                </Box>

                <Box sx={{ px: 1, py: 0.75 }}>
                  <Typography variant="caption" noWrap sx={{ display: "block", fontSize: "0.7rem", color: tokens.text.primary }}>
                    {member.filename ?? member.assetUuid}
                  </Typography>
                  {member.role === "SOURCE" && (
                    <Typography variant="caption" sx={{ fontSize: "0.65rem", color: tokens.primary.light, fontWeight: 600 }}>
                      {t("remix.member.sourceLabel")}
                    </Typography>
                  )}
                </Box>
              </Paper>
            );
          })}
          {!loading && members.length === 0 && (
            <Typography variant="body2" sx={{ color: tokens.text.tertiary }} data-testid="remix-empty">
              {t("remix.dialog.empty")}
            </Typography>
          )}
        </Box>
      </DialogContent>

      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button
          size="small"
          color="error"
          disabled={busy}
          onClick={handleDelete}
          data-testid="remix-delete"
          startIcon={<DeleteOutlined sx={{ fontSize: 15 }} />}
        >
          {t("remix.dialog.delete")}
        </Button>
        <Box sx={{ flexGrow: 1 }} />
        <Button size="small" onClick={onClose}>{t("common.close")}</Button>
      </DialogActions>
    </Dialog>
  );
}
