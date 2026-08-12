import React, { useEffect, useState } from "react";
import {
  Autocomplete,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  TextField,
  Typography,
} from "@mui/material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { addRemixAssets, createRemix, listRemixes, type RemixResponse } from "../../api/remixes";

export interface AddToRemixDialogProps {
  open: boolean;
  assetUuid: string;
  onClose: () => void;
  onAdded?: (remix: RemixResponse) => void;
}

/**
 * Put one asset into a remix, existing or new.
 *
 * <p>The counterpart to the asset browser's selection tray: that one builds a group out of many
 * assets at once, this one adds a single asset you are already looking at. A free-solo
 * Autocomplete does both jobs with one control — pick an existing remix from the list, or type a
 * name that is not in it and get a new remix with this asset in it.</p>
 */
export default function AddToRemixDialog({ open, assetUuid, onClose, onAdded }: AddToRemixDialogProps) {
  const { t } = useTranslation("translation", { keyPrefix: "remix" });
  const { t: tCommon } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();

  const [remixes, setRemixes] = useState<RemixResponse[]>([]);
  const [value, setValue] = useState<RemixResponse | string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open || !token) return;
    setValue(null);
    listRemixes(token)
      .then(r => setRemixes(r.data ?? []))
      .catch(() => setRemixes([]));
  }, [open, token]);

  const handleSubmit = async () => {
    if (!token || !value || !assetUuid) return;
    setBusy(true);
    try {
      let remix: RemixResponse;
      if (typeof value === "string") {
        // A name that matched nothing: create the remix with this asset already in it, so a
        // failure cannot leave a named but empty remix behind.
        remix = await createRemix(token, { name: value.trim(), assetUuids: [assetUuid] });
      } else {
        remix = await addRemixAssets(token, value.uuid, [assetUuid]);
      }
      showToast(t("toast.added"), "success");
      onAdded?.(remix);
      onClose();
    } catch {
      showToast(t("error.add"), "error");
    } finally {
      setBusy(false);
    }
  };

  const submitDisabled = busy || value == null || (typeof value === "string" && value.trim() === "");

  return (
    <Dialog
      open={open}
      onClose={onClose}
      data-testid="add-to-remix-dialog"
      PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 420 } }}
    >
      <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{t("dialog.pickTitle")}</DialogTitle>
      <DialogContent sx={{ pt: "8px !important" }}>
        <Typography variant="body2" sx={{ color: tokens.text.secondary, mb: 1.5 }}>
          {t("dialog.pickHint")}
        </Typography>
        <Autocomplete
          freeSolo
          options={remixes}
          value={value}
          onChange={(_e, next) => setValue(next)}
          onInputChange={(_e, input, reason) => {
            // freeSolo only reports a typed name through onInputChange; without this a user who
            // types a new name and presses the button submits null.
            if (reason === "input") setValue(input);
          }}
          getOptionLabel={option => (typeof option === "string" ? option : option.name)}
          isOptionEqualToValue={(option, selected) =>
            typeof option !== "string" && typeof selected !== "string" && option.uuid === selected.uuid
          }
          renderInput={params => (
            <TextField
              {...params}
              autoFocus
              size="small"
              label={t("dialog.pickLabel")}
              inputProps={{ ...params.inputProps, "data-testid": "add-to-remix-input" }}
            />
          )}
        />
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button size="small" onClick={onClose}>{tCommon("common.cancel")}</Button>
        <Button
          size="small"
          variant="contained"
          onClick={handleSubmit}
          disabled={submitDisabled}
          data-testid="add-to-remix-submit"
        >
          {t("dialog.pickSubmit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
