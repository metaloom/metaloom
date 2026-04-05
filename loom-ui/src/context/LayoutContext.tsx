import { createContext, useContext } from "react";

interface LayoutContextValue {
  sidebarCollapsed: boolean;
  setSidebarCollapsed: (v: boolean | ((prev: boolean) => boolean)) => void;
}

export const LayoutContext = createContext<LayoutContextValue>({
  sidebarCollapsed: false,
  setSidebarCollapsed: () => {},
});

export const useLayout = () => useContext(LayoutContext);
