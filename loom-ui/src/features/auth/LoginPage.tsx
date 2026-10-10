import React, { useState } from "react";
import { Box, TextField, Button, Typography, Alert } from "@mui/material";
import { LockOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useTranslation } from "react-i18next";

export default function LoginPage() {
  const { login } = useAuth();
  const { t } = useTranslation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  /** Which message to show: wrong credentials, or a server that could not be reached / failed. */
  const [error, setError] = useState<"invalidCredentials" | "unavailable" | null>(null);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    // Enter pressed twice, or a double click, used to send two logins in flight at once.
    if (busy) return;
    setBusy(true);
    try {
      const ok = await login(username, password);
      if (!ok) {
        setError("invalidCredentials");
      }
    } catch {
      // Network failure or a 5xx: the credentials were never judged, so do not say they are wrong.
      setError("unavailable");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box
      sx={{
        height: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        bgcolor: tokens.bg.base,
      }}
    >
      <Box
        component="form"
        onSubmit={handleSubmit}
        sx={{
          width: 360,
          bgcolor: tokens.bg.surface,
          border: `1px solid ${tokens.border.subtle}`,
          borderRadius: tokens.radius.lg,
          p: 4,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 2.5,
        }}
      >
        <Box
          sx={{
            width: 48,
            height: 48,
            borderRadius: "50%",
            bgcolor: tokens.primary.main,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            mb: 0.5,
          }}
        >
          <LockOutlined sx={{ color: "#fff", fontSize: 24 }} />
        </Box>

        <Typography variant="h6" fontWeight={700} color="text.primary">
          {t("auth.title")}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: -1.5 }}>
          {t("auth.subtitle")}
        </Typography>

        {error && (
          <Alert severity="error" data-testid={`login-error-${error}`} sx={{ width: "100%", fontSize: "0.8rem" }}>
            {t(`auth.error.${error}`)}
          </Alert>
        )}

        <TextField
          placeholder={t("auth.placeholder.username")}
          value={username}
          onChange={(e) => { setUsername(e.target.value); setError(null); }}
          fullWidth
          size="small"
          autoFocus
          sx={{
            "& .MuiInputBase-input": { py: "8.5px" },
            "& .MuiInputBase-input:focus::placeholder": { opacity: 0, transition: "opacity 150ms ease" },
            "& .MuiInputBase-input::placeholder": { transition: "opacity 150ms ease" },
          }}
        />
        <TextField
          placeholder={t("auth.placeholder.password")}
          type="password"
          value={password}
          onChange={(e) => { setPassword(e.target.value); setError(null); }}
          fullWidth
          size="small"
          sx={{
            "& .MuiInputBase-input": { py: "8.5px" },
            "& .MuiInputBase-input:focus::placeholder": { opacity: 0, transition: "opacity 150ms ease" },
            "& .MuiInputBase-input::placeholder": { transition: "opacity 150ms ease" },
          }}
        />

        <Button
          type="submit"
          variant="contained"
          fullWidth
          disabled={busy}
          sx={{
            mt: 1,
            textTransform: "none",
            fontWeight: 600,
            bgcolor: tokens.primary.main,
            "&:hover": { bgcolor: tokens.primary.dark },
          }}
        >
          {t("auth.button.signIn")}
        </Button>
      </Box>
    </Box>
  );
}
