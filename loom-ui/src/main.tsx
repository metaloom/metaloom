import React, { useMemo } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { ThemeProvider, CssBaseline } from "@mui/material";
import { buildTheme, setActiveTokens } from "./theme";
import "./i18n/i18n";
import { SpaceProvider } from "./context/SpaceContext";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { ToastProvider } from "./context/ToastContext";
import { NodeRegistryProvider } from "./context/NodeRegistryContext";
import { ThemeModeProvider, useThemeMode } from "./context/ThemeContext";
import AppShell from "./layout/AppShell";
import LoginPage from "./features/auth/LoginPage";

// The app is mounted under a path prefix (/ui/ in dev and in the served build), so the
// router has to strip that prefix before matching. Deriving it from Vite's BASE_URL keeps
// the value in one place; React Router wants it without the trailing slash.
const ROUTER_BASENAME = import.meta.env.BASE_URL.replace(/\/+$/, "");

function AuthGate() {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) return <LoginPage />;
  return (
    <NodeRegistryProvider>
      <SpaceProvider>
        <AppShell />
      </SpaceProvider>
    </NodeRegistryProvider>
  );
}

function ThemedApp() {
  const { mode } = useThemeMode();
  const theme = useMemo(() => {
    setActiveTokens(mode);
    return buildTheme(mode);
  }, [mode]);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter basename={ROUTER_BASENAME}>
        <AuthProvider>
          <ToastProvider>
            <AuthGate />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}

function App() {
  return (
    <ThemeModeProvider>
      <ThemedApp />
    </ThemeModeProvider>
  );
}

const container = document.querySelector("#app")!;
createRoot(container).render(<App />);
