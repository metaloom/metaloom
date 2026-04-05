import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { ThemeProvider, CssBaseline } from "@mui/material";
import loomTheme from "./theme";
import { ProjectProvider } from "./context/ProjectContext";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { ToastProvider } from "./context/ToastContext";
import AppShell from "./layout/AppShell";
import LoginPage from "./features/auth/LoginPage";

function AuthGate() {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) return <LoginPage />;
  return (
    <ProjectProvider>
      <AppShell />
    </ProjectProvider>
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
