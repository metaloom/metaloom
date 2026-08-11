import React, { useState } from "react";
import { Alert, Box, Button, Paper, TextField, Typography } from "@mui/material";
import LockOutlined from "@mui/icons-material/LockOutlined";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { openShare, ShareApiError, type ShareChallengeResponse } from "../../api/shares";
import { toShareSession, useShareSession } from "./ShareSessionContext";

/**
 * The front door of a share link: who are you, and what is the password.
 *
 * Both questions on one card rather than two steps. A customer who has been sent a link and a
 * password expects to use both at once, and a two-step gate makes the second step look like a
 * failure of the first.
 */
export default function ShareGate({ slug, challenge }: { slug: string; challenge: ShareChallengeResponse }) {
  const { t } = useTranslation();
  const { setSession } = useShareSession();

  const [name, setName] = useState(challenge.visitorName ?? "");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (visitorName: string) => {
    setBusy(true);
    setError(null);
    try {
      const response = await openShare(slug, {
        visitorName,
        password: challenge.passwordRequired ? password : undefined,
      });
      setSession(toShareSession(slug, response));
    } catch (e) {
      // A wrong password must not navigate anywhere or clear the form - the visitor tries again in
      // place. 429 is the throttle, and says so rather than looking like another wrong password.
      const status = e instanceof ShareApiError ? e.status : 0;
      if (status === 429) {
        setError(t("share.gate.tooManyAttempts"));
      } else if (status === 401) {
        setError(t("share.gate.wrongPassword"));
      } else if (status === 404) {
        setError(t("share.gate.notAvailable"));
      } else {
        setError(t("share.gate.failed"));
      }
      setBusy(false);
    }
  };

  const canSubmit = !busy && (!challenge.passwordRequired || password.length > 0);

  return (
    <Box
      data-testid="share-gate"
      sx={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        bgcolor: tokens.bg.base,
        p: 2,
      }}
    >
      <Paper
        elevation={0}
        component="form"
        onSubmit={(e: React.FormEvent) => {
          e.preventDefault();
          if (canSubmit) submit(name.trim() || t("share.gate.anonymous"));
        }}
        sx={{
          bgcolor: tokens.bg.panel,
          border: `1px solid ${tokens.border.default}`,
          borderRadius: tokens.radius.lg,
          p: 4,
          width: "100%",
          maxWidth: 420,
          display: "flex",
          flexDirection: "column",
          gap: 2.5,
        }}
      >
        <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
          <Typography sx={{ fontSize: "1.25rem", fontWeight: 700, color: tokens.text.primary }}>
            {challenge.visitorNameKnown
              ? t("share.gate.welcomeBack", { name: challenge.visitorName })
              : t("share.gate.title")}
          </Typography>
          <Typography variant="body2" sx={{ color: tokens.text.secondary }}>
            {challenge.targetType === "COLLECTION" ? t("share.gate.subtitleCollection") : t("share.gate.subtitleAsset")}
          </Typography>
        </Box>

        <TextField
          label={t("share.gate.nameLabel")}
          placeholder={t("share.gate.namePlaceholder")}
          size="small"
          value={name}
          onChange={(e) => setName(e.target.value)}
          autoFocus={!challenge.passwordRequired}
          fullWidth
          inputProps={{ "data-testid": "share-gate-name", maxLength: 80 }}
        />

        {challenge.passwordRequired && (
          <TextField
            label={t("share.gate.passwordLabel")}
            type="password"
            size="small"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoFocus
            fullWidth
            InputProps={{
              startAdornment: <LockOutlined sx={{ fontSize: 18, mr: 1, color: tokens.text.tertiary }} />,
            }}
            inputProps={{ "data-testid": "share-gate-password" }}
          />
        )}

        {error && (
          <Alert severity="error" data-testid="share-gate-error" sx={{ py: 0 }}>
            {error}
          </Alert>
        )}

        <Box sx={{ display: "flex", gap: 1, justifyContent: "flex-end", alignItems: "center" }}>
          {/* Skip stores the localised "Anonymous" rather than leaving the link unnamed, so that
              "nobody has opened it" stays distinguishable from "somebody opened it and would not
              say who". It is not offered when a password is required - there is nothing to skip. */}
          {!challenge.passwordRequired && (
            <Button
              size="small"
              onClick={() => submit(t("share.gate.anonymous"))}
              disabled={busy}
              data-testid="share-gate-skip"
              sx={{ color: tokens.text.secondary }}
            >
              {t("share.gate.skip")}
            </Button>
          )}
          <Button type="submit" size="small" variant="contained" disabled={!canSubmit} data-testid="share-gate-submit">
            {busy ? t("share.gate.opening") : t("share.gate.open")}
          </Button>
        </Box>
      </Paper>
    </Box>
  );
}
