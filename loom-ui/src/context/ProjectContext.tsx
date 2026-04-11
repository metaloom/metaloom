import React, { createContext, useContext, useState } from "react";
import { Project } from "../types";

interface ProjectContextValue {
  projects: Project[];
  activeProject: Project | null;
  setActiveProject: (p: Project) => void;
  loading: boolean;
}

const ProjectContext = createContext<ProjectContextValue>({
  projects: [],
  activeProject: null,
  setActiveProject: () => {},
  loading: false,
});

export function ProjectProvider({ children }: { children: React.ReactNode }) {
  const [projects] = useState<Project[]>([]);
  const [activeProject, setActiveProject] = useState<Project | null>(null);

  return (
    <ProjectContext.Provider value={{ projects, activeProject, setActiveProject, loading: false }}>
      {children}
    </ProjectContext.Provider>
  );
}

export const useProject = () => useContext(ProjectContext);
