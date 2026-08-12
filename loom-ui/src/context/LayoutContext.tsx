import { createContext, useContext } from "react";

/**
 * Consulted before an in-app navigation leaves the current screen. The guard owns the deferred
 * navigation: it runs `proceed` once the user has agreed, or drops it to cancel.
 */
export type NavGuard = (proceed: () => void) => void;

interface LayoutContextValue {
  sidebarCollapsed: boolean;
  setSidebarCollapsed: (v: boolean | ((prev: boolean) => boolean)) => void;
  /**
   * Register the guard of the screen currently mounted, or `null` to clear it. Only one screen
   * guards at a time — the editor with unsaved edits is the screen the user is looking at.
   */
  setNavGuard: (guard: NavGuard | null) => void;
  /**
   * Navigate through the guard. Every navigation control outside a screen's own body (the sidebar,
   * the notification list) goes through this instead of calling `navigate` directly, otherwise it
   * discards the screen's unsaved work without asking.
   */
  requestNavigation: (proceed: () => void) => void;
}

export const LayoutContext = createContext<LayoutContextValue>({
  sidebarCollapsed: false,
  setSidebarCollapsed: () => {},
  setNavGuard: () => {},
  requestNavigation: (proceed) => proceed(),
});

export const useLayout = () => useContext(LayoutContext);
