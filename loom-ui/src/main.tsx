import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { ThemeProvider, CssBaseline } from "@mui/material";
import loomTheme from "./theme";
import { ProjectProvider } from "./context/ProjectContext";
import AppShell from "./layout/AppShell";

function App() {
  return (
    <ThemeProvider theme={loomTheme}>
      <CssBaseline />
      <BrowserRouter>
        <ProjectProvider>
          <AppShell />
        </ProjectProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}

const container = document.querySelector("#app")!;
createRoot(container).render(<App />);
