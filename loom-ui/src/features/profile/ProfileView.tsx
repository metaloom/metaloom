import React, { useState } from "react";
import {
  Box, Typography, TextField, Button, Avatar, IconButton, Divider,
} from "@mui/material";
import { PhotoCameraOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { USERS } from "../../mock/data";
import { useToast } from "../../context/ToastContext";

export default function ProfileView() {
  const user = USERS[0];
  const { showToast } = useToast();
  const [firstName, setFirstName] = useState(user.name.split(" ")[0]);
  const [lastName, setLastName] = useState(user.name.split(" ").slice(1).join(" "));
  const [email, setEmail] = useState(user.email);
  const [avatarPreview, setAvatarPreview] = useState<string | null>(user.avatarUrl ?? null);

  const handleAvatarChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = () => setAvatarPreview(reader.result as string);
      reader.readAsDataURL(file);
    }
  };

  const handleSave = () => {
    user.name = `${firstName} ${lastName}`.trim();
    user.email = email;
    showToast("Profile updated", "success");
  };

  const initials = `${firstName[0] ?? ""}${lastName[0] ?? ""}`.toUpperCase();

  return (
    <Box sx={{ flex: 1, overflow: "auto", p: 4, maxWidth: 600, mx: "auto" }}>
      <Typography variant="h5" fontWeight={700} color="text.primary" gutterBottom>
        Profile
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Manage your personal information
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
            {user.role} &middot; {user.username}
          </Typography>
        </Box>
      </Box>

      {/* Fields */}
      <Box sx={{ display: "flex", flexDirection: "column", gap: 2.5 }}>
        <Box sx={{ display: "flex", gap: 2 }}>
          <TextField
            label="First name"
            size="small"
            fullWidth
            value={firstName}
            onChange={(e) => setFirstName(e.target.value)}
          />
          <TextField
            label="Last name"
            size="small"
            fullWidth
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
          />
        </Box>
        <TextField
          label="Email"
          size="small"
          fullWidth
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <TextField
          label="Username"
          size="small"
          fullWidth
          value={user.username}
          disabled
          helperText="Username cannot be changed"
        />
        <TextField
          label="Role"
          size="small"
          fullWidth
          value={user.role}
          disabled
        />
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
          Save changes
        </Button>
      </Box>
    </Box>
  );
}
