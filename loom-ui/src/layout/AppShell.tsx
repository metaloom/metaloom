import React, { useState } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import { Box } from "@mui/material";
import Sidebar from "./Sidebar";
import { tokens } from "../theme";
import ChatWorkspace from "../features/chat/ChatWorkspace";
import AssetBrowser from "../features/assets/AssetBrowser";
import AssetDetail from "../features/assetDetail/AssetDetail";
import PipelineEditor from "../features/pipeline/PipelineEditor";
import AdminArea from "../features/admin/AdminArea";
import MonitoringArea from "../features/monitoring/MonitoringArea";
import CollectionsView from "../features/collections/CollectionsView";
import TasksView from "../features/tasks/TasksView";
import LibraryView from "../features/library/LibraryView";

export default function AppShell() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  return (
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
          <Route path="/library" element={<LibraryView />} />
          <Route path="/assets" element={<AssetBrowser />} />
          <Route path="/assets/:id" element={<AssetDetail />} />
          <Route path="/collections" element={<CollectionsView />} />
          <Route path="/tasks" element={<TasksView />} />
          <Route path="/pipelines" element={<PipelineEditor />} />
          <Route path="/monitoring" element={<MonitoringArea />} />
          <Route path="/admin/*" element={<AdminArea />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Box>
    </Box>
  );
}
