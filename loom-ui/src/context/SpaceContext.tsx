import React, { createContext, useContext, useState } from "react";
import { Space } from "../types";

interface SpaceContextValue {
  spaces: Space[];
  activeSpace: Space | null;
  setActiveSpace: (p: Space) => void;
  loading: boolean;
}

const SpaceContext = createContext<SpaceContextValue>({
  spaces: [],
  activeSpace: null,
  setActiveSpace: () => {},
  loading: false,
});

export function SpaceProvider({ children }: { children: React.ReactNode }) {
  const [spaces] = useState<Space[]>([]);
  const [activeSpace, setActiveSpace] = useState<Space | null>(null);

  return (
    <SpaceContext.Provider value={{ spaces, activeSpace, setActiveSpace, loading: false }}>
      {children}
    </SpaceContext.Provider>
  );
}

export const useSpace = () => useContext(SpaceContext);
