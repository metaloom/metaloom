import React, { useMemo } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider, CssBaseline } from "@mui/material";
import { buildTheme, setActiveTokens } from "./theme";
import "./i18n/i18n";
import { SpaceProvider } from "./context/SpaceContext";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { ToastProvider } from "./context/ToastContext";
import { NodeRegistryProvider } from "./context/NodeRegistryContext";
// The React context reporting whether the server can serve searches — not to be confused with
// the backend's SearchProvider SPI, which is the thing it reports on.
import { SearchProvider } from "./context/SearchContext";
import { ThemeModeProvider, useThemeMode } from "./context/ThemeContext";
import { UploadProvider } from "./features/uploads/UploadContext";
import { NotificationProvider } from "./context/NotificationContext";
import AppShell from "./layout/AppShell";
import LoginPage from "./features/auth/LoginPage";
import SharePage from "./features/share/SharePage";

// The app is mounted under a path prefix (/ui/ in dev and in the served build), so the
// router has to strip that prefix before matching. Deriving it from Vite's BASE_URL keeps
// the value in one place; React Router wants it without the trailing slash.
const ROUTER_BASENAME = import.meta.env.BASE_URL.replace(/\/+$/, "");

function AuthGate() {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) return <LoginPage />;
  // UploadProvider sits above AppShell so an in-flight batch outlives every route change; only a
  // logout (which unmounts this branch) tears it down.
  return (
    <NodeRegistryProvider>
      <SearchProvider>
        <SpaceProvider>
          <NotificationProvider>
            <UploadProvider>
              <AppShell />
            </UploadProvider>
          </NotificationProvider>
        </SpaceProvider>
      </SearchProvider>
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
            {/* The customer-facing share area sits ABOVE AuthGate, and is the only route that
                does. Authentication here is a conditional render rather than a route guard:
                AuthGate answers every URL with LoginPage when there is no token, and AppShell —
                which declares every other route — is mounted only once there is one. A share
                route inside AppShell would therefore be unreachable by the people it exists for,
                and AppShell's catch-all redirect would swallow it besides.

                It stays inside ThemedApp because `tokens` is read at render time; a component
                mounted outside ThemeModeProvider would paint with stale values. */}
            <Routes>
              <Route path="/share/:slug" element={<SharePage />} />
              <Route path="*" element={<AuthGate />} />
            </Routes>
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
