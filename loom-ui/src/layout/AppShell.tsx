import React, { useCallback, useMemo, useRef, useState } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import { Box } from "@mui/material";
import Sidebar from "./Sidebar";
import { tokens } from "../theme";
import ChatWorkspace from "../features/chat/ChatWorkspace";
import AssetBrowser from "../features/assets/AssetBrowser";
import SearchView from "../features/search/SearchView";
import AssetDetail from "../features/assetDetail/AssetDetail";
import PipelineEditor from "../features/pipeline/PipelineEditor";
import AdminArea from "../features/admin/AdminArea";
import MonitoringArea from "../features/monitoring/MonitoringArea";
import CollectionsView from "../features/collections/CollectionsView";
import TasksView from "../features/tasks/TasksView";
import LibraryView from "../features/library/LibraryView";
import DetectionManagement from "../features/detection/DetectionManagement";
import PersonDetail from "../features/persons/PersonDetail";
import ProfileView from "../features/profile/ProfileView";
import MaintenanceView from "../features/maintenance/MaintenanceView";
import TagsView from "../features/tags/TagsView";
import CortexView from "../features/cortex/CortexView";
import WorkflowView from "../features/workflow/WorkflowView";
import SkillManagementView from "../features/skills/SkillManagementView";
import MemoryView from "../features/memory/MemoryView";
import ChatSessionsView from "../features/chatSessions/ChatSessionsView";
import ChatSessionDetail from "../features/chatSessions/ChatSessionDetail";
import AssetPoolsView from "../features/assetPools/AssetPoolsView";
import UploadView from "../features/uploads/UploadView";
import { LayoutContext, type NavGuard } from "../context/LayoutContext";
import { runGuarded } from "../hooks/useUnsavedChanges";

export default function AppShell() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  // A ref, not state: registering a guard must not re-render the shell (and with it every route),
  // and `requestNavigation` has to read the guard that is current at click time.
  const navGuard = useRef<NavGuard | null>(null);
  const setNavGuard = useCallback((guard: NavGuard | null) => {
    navGuard.current = guard;
  }, []);
  const requestNavigation = useCallback((proceed: () => void) => {
    runGuarded(navGuard.current, proceed);
  }, []);

  const layout = useMemo(
    () => ({ sidebarCollapsed, setSidebarCollapsed, setNavGuard, requestNavigation }),
    [sidebarCollapsed, setNavGuard, requestNavigation],
  );

  return (
    <LayoutContext.Provider value={layout}>
    <Box sx={{ display: "flex", height: "100vh", overflow: "hidden", bgcolor: tokens.bg.base }}>
      <Sidebar collapsed={sidebarCollapsed} onCollapse={setSidebarCollapsed} />
      <Box
        component="main"
        sx={{
          flex: 1,
          overflow: "hidden",
          display: "flex",
          flexDirection: "column",
          minWidth: 0,
        }}
      >
        <Routes>
          <Route path="/" element={<ChatWorkspace />} />
          <Route path="/search" element={<SearchView />} />
          <Route path="/library" element={<LibraryView />} />
          <Route path="/assets" element={<AssetBrowser />} />
          <Route path="/assets/:id" element={<AssetDetail />} />
          <Route path="/uploads" element={<UploadView />} />
          <Route path="/collections" element={<CollectionsView />} />
          <Route path="/tasks" element={<TasksView />} />
          <Route path="/skills" element={<SkillManagementView />} />
          <Route path="/memory" element={<MemoryView />} />
          <Route path="/chat/sessions" element={<ChatSessionsView />} />
          <Route path="/chat/sessions/:id" element={<ChatSessionDetail />} />
          <Route path="/pipelines" element={<PipelineEditor />} />
          <Route path="/detection" element={<DetectionManagement />} />
          <Route path="/faces" element={<Navigate to="/detection" replace />} />
          {/* The only face-review surface with a route of its own: a person's pictures are theirs, not a
              view onto the material they were found in, and they need somewhere to live that outlasts a panel. */}
          <Route path="/persons/:id" element={<PersonDetail />} />
          <Route path="/tags" element={<TagsView />} />
          <Route path="/workflow" element={<WorkflowView />} />
          <Route path="/asset-pools" element={<AssetPoolsView />} />
          <Route path="/cortex" element={<CortexView />} />
          <Route path="/monitoring" element={<MonitoringArea />} />
          <Route path="/admin/*" element={<AdminArea />} />
          <Route path="/profile" element={<ProfileView />} />
          <Route path="/maintenance" element={<MaintenanceView />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Box>
    </Box>
    </LayoutContext.Provider>
  );
}
