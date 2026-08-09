import React, { useEffect, useState } from "react";
import {
  Box, Typography, TextField, Button, Avatar, IconButton, Divider,
  ToggleButton, ToggleButtonGroup, CircularProgress, Alert,
} from "@mui/material";
import { PhotoCameraOutlined, DarkModeOutlined, LightModeOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { loadUser, updateUser, UserResponse, UserUpdateRequest } from "../../api/users";
import { useToast } from "../../context/ToastContext";
import { useTranslation } from "react-i18next";
import { useThemeMode } from "../../context/ThemeContext";
import type { ThemeMode } from "../../context/ThemeContext";

export default function ProfileView() {
  const { token: authToken, username: authUsername, userUuid } = useAuth();
  const { showToast } = useToast();
  const { t, i18n } = useTranslation();
  const { mode, setMode } = useThemeMode();
  const [language, setLanguage] = useState(i18n.language);
  const [user, setUser] = useState<UserResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null);

  useEffect(() => {
    if (!authToken) return;
    // The auth context derives the uuid from the JWT and then confirms it via /me.
    if (!userUuid) { setLoading(false); return; }
    loadUser(authToken, userUuid)
      .then(u => {
        setUser(u);
        setFirstName(u.firstname ?? "");
        setLastName(u.lastname ?? "");
        setEmail(u.email ?? "");
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [authToken, userUuid]);

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

  /**
   * Only the fields the user actually touched are sent — a full-record write would
   * clobber concurrent changes to fields this screen does not even show.
   */
  const changedFields = (loaded: UserResponse): UserUpdateRequest => {
    const request: UserUpdateRequest = {};
    if (firstName !== (loaded.firstname ?? "")) request.firstname = firstName;
    if (lastName !== (loaded.lastname ?? "")) request.lastname = lastName;
    if (email !== (loaded.email ?? "")) request.email = email;
    return request;
  };

  const handleSave = async () => {
    if (!authToken || !user || saving) return;
    setSaveError(null);
    setSaving(true);
    try {
      const updated = await updateUser(authToken, user.uuid, changedFields(user));
      setUser(updated);
      setFirstName(updated.firstname ?? "");
      setLastName(updated.lastname ?? "");
      setEmail(updated.email ?? "");
      showToast(t("profile.toast.saved"), "success");
    } catch {
      // The inline alert is what survives; the toast auto-hides after a few seconds.
      setSaveError(t("profile.toast.error"));
      showToast(t("profile.toast.error"), "error");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <Box
        data-testid="profile-loading"
        sx={{ flex: 1, display: "flex", justifyContent: "center", alignItems: "center" }}
      >
        <CircularProgress size={32} />
      </Box>
    );
  }

  const initials = `${firstName[0] ?? ""}${lastName[0] ?? ""}`.toUpperCase();

  return (
    <Box data-testid="profile-view" sx={{ flex: 1, overflow: "auto", p: 4, maxWidth: 600, mx: "auto" }}>
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
            inputProps={{ "data-testid": "profile-field-firstName" }}
          />
          <TextField
            label={t("profile.field.lastName")}
            size="small"
            fullWidth
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
            inputProps={{ "data-testid": "profile-field-lastName" }}
          />
        </Box>
        <TextField
          label={t("profile.field.email")}
          size="small"
          fullWidth
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          inputProps={{ "data-testid": "profile-field-email" }}
        />
        <TextField
          label={t("profile.field.username")}
          size="small"
          fullWidth
          value={user?.username ?? authUsername ?? ""}
          disabled
          helperText={t("profile.field.usernameHelper")}
          inputProps={{ "data-testid": "profile-field-username" }}
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

      {saveError && (
        <Alert severity="error" data-testid="profile-error" sx={{ mt: 3 }}>
          {saveError}
        </Alert>
      )}

      <Box sx={{ mt: 4 }}>
        <Button
          variant="contained"
          data-testid="profile-save"
          onClick={handleSave}
          disabled={saving}
          startIcon={saving ? <CircularProgress size={14} data-testid="profile-saving" /> : undefined}
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
