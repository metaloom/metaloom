import * as React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route } from "react-router-dom";

import Dashboard from "./Dashboard/Dashboard";
import Login from "./Login/Login";
import WelcomePage from "./Welcome/WelcomePage";
import { createTheme, ThemeProvider } from "@mui/material/styles";
import { shadows } from "@mui/system";
import AppTheme from "./Theme";

const theme = AppTheme();

function App() {
  return (
    <ThemeProvider theme={theme}>
      <BrowserRouter>
        <Routes>
          <Route path="/dash/*" element={<Dashboard />} />
          <Route path="/" element={<WelcomePage />} />
          <Route path="/login/*" element={<Login />} />
        </Routes>
      </BrowserRouter>
    </ThemeProvider>
  );
}

const container = document.querySelector("#app")!;
const root = createRoot(container);
root.render(<App />);
