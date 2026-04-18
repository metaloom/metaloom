import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { ThemeProvider, CssBaseline } from "@mui/material";
import loomTheme from "./theme";
import "./i18n/i18n";
import { SpaceProvider } from "./context/SpaceContext";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { ToastProvider } from "./context/ToastContext";
import { NodeRegistryProvider } from "./context/NodeRegistryContext";
import AppShell from "./layout/AppShell";
import LoginPage from "./features/auth/LoginPage";

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

function App() {
  return (
    <ThemeProvider theme={loomTheme}>
      <CssBaseline />
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <AuthGate />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}

const container = document.querySelector("#app")!;
createRoot(container).render(<App />);
