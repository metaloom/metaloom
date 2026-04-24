import React, { createContext, useContext, useState, useMemo } from "react";

export type ThemeMode = "dark" | "light";

interface ThemeContextValue {
  mode: ThemeMode;
  toggleMode: () => void;
  setMode: (mode: ThemeMode) => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  mode: "dark",
  toggleMode: () => {},
  setMode: () => {},
});

export function useThemeMode() {
  return useContext(ThemeContext);
}

export function ThemeModeProvider({ children }: { children: React.ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(
    () => (localStorage.getItem("loom-ui-theme") as ThemeMode) || "dark",
  );

  const toggleMode = useMemo(
    () => () =>
      setMode((prev) => {
        const next = prev === "dark" ? "light" : "dark";
        localStorage.setItem("loom-ui-theme", next);
        return next;
      }),
    [],
  );

  const handleSetMode = useMemo(
    () => (m: ThemeMode) => {
      localStorage.setItem("loom-ui-theme", m);
      setMode(m);
    },
    [],
  );

  return (
    <ThemeContext.Provider value={{ mode, toggleMode, setMode: handleSetMode }}>
      {children}
    </ThemeContext.Provider>
  );
}
