import React, { createContext, useContext, useEffect, useState } from "react";
import { Project } from "../types";
import { mockProjectService } from "../mock/services";

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
  loading: true,
});

export function ProjectProvider({ children }: { children: React.ReactNode }) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [activeProject, setActiveProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    mockProjectService.getAll().then((ps) => {
      setProjects(ps);
      setActiveProject(ps[0] ?? null);
      setLoading(false);
    });
  }, []);

  return (
    <ProjectContext.Provider value={{ projects, activeProject, setActiveProject, loading }}>
      {children}
    </ProjectContext.Provider>
  );
}

export const useProject = () => useContext(ProjectContext);
