import React, { useState } from "react";
import { Box, TextField, Button, Typography, Alert } from "@mui/material";
import { LockOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";

export default function LoginPage() {
  const { login } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const ok = await login(username, password);
    if (!ok) {
      setError(true);
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
          Loom Studio
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: -1.5 }}>
          Sign in to continue
        </Typography>

        {error && (
          <Alert severity="error" sx={{ width: "100%", fontSize: "0.8rem" }}>
            Invalid credentials.
          </Alert>
        )}

        <TextField
          placeholder="Username"
          value={username}
          onChange={(e) => { setUsername(e.target.value); setError(false); }}
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
          placeholder="Password"
          type="password"
          value={password}
          onChange={(e) => { setPassword(e.target.value); setError(false); }}
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
          sx={{
            mt: 1,
            textTransform: "none",
            fontWeight: 600,
            bgcolor: tokens.primary.main,
            "&:hover": { bgcolor: tokens.primary.dark },
          }}
        >
          Sign in
        </Button>
      </Box>
    </Box>
  );
}
