import React, { useEffect, useState } from "react";
import {
  Box, Typography, TextField, Button, Avatar, IconButton, Divider,
  ToggleButton, ToggleButtonGroup, CircularProgress,
} from "@mui/material";
import { PhotoCameraOutlined, DarkModeOutlined, LightModeOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { loadUser, updateUser, UserResponse } from "../../api/users";
import { useToast } from "../../context/ToastContext";
import { useTranslation } from "react-i18next";
import { useThemeMode } from "../../context/ThemeContext";
import type { ThemeMode } from "../../context/ThemeContext";

/** Decode the payload of a JWT (no validation, just base64 parse). */
function decodeJwtPayload(jwt: string): Record<string, unknown> {
  const parts = jwt.split(".");
  if (parts.length < 2) return {};
  const payload = parts[1].replace(/-/g, "+").replace(/_/g, "/");
  return JSON.parse(atob(payload));
}

export default function ProfileView() {
  const { token: authToken, username: authUsername } = useAuth();
  const { showToast } = useToast();
  const { t, i18n } = useTranslation();
  const { mode, setMode } = useThemeMode();
  const [language, setLanguage] = useState(i18n.language);
  const [user, setUser] = useState<UserResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null);

  useEffect(() => {
    if (!authToken) return;
    const payload = decodeJwtPayload(authToken);
    const uuid = payload.uuid as string | undefined;
    if (!uuid) { setLoading(false); return; }
    loadUser(authToken, uuid)
      .then(u => {
        setUser(u);
        setFirstName(u.firstname ?? "");
        setLastName(u.lastname ?? "");
        setEmail(u.email ?? "");
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [authToken]);

  const handleAvatarChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = () => setAvatarPreview(reader.result as string);
      reader.readAsDataURL(file);
    }
  };

  const handleLanguageChange = (_: React.MouseEvent<HTMLElement>, lang: string | null) => {
    if (!lang) return;
    i18n.changeLanguage(lang);
    localStorage.setItem("loom-ui-language", lang);
    setLanguage(lang);
  };

  const handleSave = async () => {
    if (!authToken || !user) return;
    try {
      const updated = await updateUser(authToken, user.uuid, {
        firstname: firstName,
        lastname: lastName,
        email,
      });
      setUser(updated);
      showToast(t("profile.toast.saved"), "success");
    } catch {
      showToast(t("profile.toast.error"), "error");
    }
  };

  if (loading) {
    return (
      <Box sx={{ flex: 1, display: "flex", justifyContent: "center", alignItems: "center" }}>
        <CircularProgress size={32} />
      </Box>
    );
  }

  const initials = `${firstName[0] ?? ""}${lastName[0] ?? ""}`.toUpperCase();

  return (
    <Box sx={{ flex: 1, overflow: "auto", p: 4, maxWidth: 600, mx: "auto" }}>
      <Typography variant="h5" fontWeight={700} color="text.primary" gutterBottom>
        {t("profile.title")}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t("profile.subtitle")}
      </Typography>

      <Divider sx={{ mb: 3 }} />

      {/* Avatar */}
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 3 }}>
        <Box sx={{ position: "relative" }}>
          <Avatar
            src={avatarPreview ?? undefined}
            sx={{ width: 72, height: 72, fontSize: "1.5rem", bgcolor: tokens.primary.dark }}
          >
            {initials}
          </Avatar>
          <IconButton
            component="label"
            size="small"
            sx={{
              position: "absolute",
              bottom: -4,
              right: -4,
              bgcolor: tokens.bg.elevated,
              border: `1px solid ${tokens.border.subtle}`,
              width: 26,
              height: 26,
              "&:hover": { bgcolor: tokens.bg.overlay },
            }}
          >
            <PhotoCameraOutlined sx={{ fontSize: 14 }} />
            <input type="file" hidden accept="image/*" onChange={handleAvatarChange} />
          </IconButton>
        </Box>
        <Box>
          <Typography variant="body1" fontWeight={600} color="text.primary">
            {firstName} {lastName}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {user?.username ?? authUsername}
          </Typography>
        </Box>
      </Box>

      {/* Fields */}
      <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5 }}>
        <Box sx={{ display: "flex", gap: 2 }}>
          <TextField
            label={t("profile.field.firstName")}
            size="small"
            fullWidth
            value={firstName}
            onChange={(e) => setFirstName(e.target.value)}
          />
          <TextField
            label={t("profile.field.lastName")}
            size="small"
            fullWidth
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
          />
        </Box>
        <TextField
          label={t("profile.field.email")}
          size="small"
          fullWidth
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <TextField
          label={t("profile.field.username")}
          size="small"
          fullWidth
          value={user?.username ?? authUsername ?? ""}
          disabled
          helperText={t("profile.field.usernameHelper")}
        />
      </Box>

      <Divider sx={{ my: 3 }} />

      {/* Language */}
      <Box sx={{ mb: 3 }}>
        <Typography
          variant="body2"
          fontWeight={600}
          color="text.secondary"
          sx={{ mb: 1.5, textTransform: "uppercase", fontSize: "0.72rem", letterSpacing: "0.07em" }}
        >
          {t("profile.language.label")}
        </Typography>
        <ToggleButtonGroup value={language} exclusive onChange={handleLanguageChange} size="small">
          <ToggleButton value="en" sx={{ px: 2, fontSize: "0.8rem", textTransform: "none" }}>
            🇬🇧 {t("profile.language.en")}
          </ToggleButton>
          <ToggleButton value="de" sx={{ px: 2, fontSize: "0.8rem", textTransform: "none" }}>
            🇩🇪 {t("profile.language.de")}
          </ToggleButton>
        </ToggleButtonGroup>
      </Box>

      {/* Appearance */}
      <Box sx={{ mb: 3 }}>
        <Typography
          variant="body2"
          fontWeight={600}
          color="text.secondary"
          sx={{ mb: 1.5, textTransform: "uppercase", fontSize: "0.72rem", letterSpacing: "0.07em" }}
        >
          {t("profile.appearance.label")}
        </Typography>
        <ToggleButtonGroup
          value={mode}
          exclusive
          onChange={(_, v: ThemeMode | null) => { if (v) setMode(v); }}
          size="small"
        >
          <ToggleButton value="dark" sx={{ px: 2, fontSize: "0.8rem", textTransform: "none", gap: 0.75 }}>
            <DarkModeOutlined sx={{ fontSize: 16 }} /> {t("profile.appearance.dark")}
          </ToggleButton>
          <ToggleButton value="light" sx={{ px: 2, fontSize: "0.8rem", textTransform: "none", gap: 0.75 }}>
            <LightModeOutlined sx={{ fontSize: 16 }} /> {t("profile.appearance.light")}
          </ToggleButton>
        </ToggleButtonGroup>
      </Box>

      <Box sx={{ mt: 4 }}>
        <Button
          variant="contained"
          onClick={handleSave}
          sx={{
            textTransform: "none",
            fontWeight: 600,
            bgcolor: tokens.primary.main,
            "&:hover": { bgcolor: tokens.primary.dark },
          }}
        >
          {t("profile.button.save")}
        </Button>
      </Box>
    </Box>
  );
}
