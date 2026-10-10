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

const THEME_KEY = "loom-ui-theme";

/**
 * The stored preference, or dark.
 *
 * Guarded, because this provider sits at the very root, above every error boundary: a browser with
 * site storage blocked throws `SecurityError` on the bare `localStorage` access, and that used to
 * take the whole app down to a white page before the login form could render - the same
 * private-mode case `AuthContext` already survives for its own storage. A value that is neither
 * mode (hand-edited, or left by some older build) is ignored rather than handed to MUI as a palette
 * mode it does not have.
 */
function readStoredMode(): ThemeMode {
  try {
    const stored = window.localStorage.getItem(THEME_KEY);
    return stored === "light" || stored === "dark" ? stored : "dark";
  } catch {
    return "dark";
  }
}

function writeStoredMode(mode: ThemeMode) {
  try {
    window.localStorage.setItem(THEME_KEY, mode);
  } catch {
    /* see readStoredMode: the choice then lasts for this page only */
  }
}

export function ThemeModeProvider({ children }: { children: React.ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(readStoredMode);

  const toggleMode = useMemo(
    () => () =>
      setMode((prev) => {
        const next = prev === "dark" ? "light" : "dark";
        writeStoredMode(next);
        return next;
      }),
    [],
  );

  const handleSetMode = useMemo(
    () => (m: ThemeMode) => {
      writeStoredMode(m);
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
