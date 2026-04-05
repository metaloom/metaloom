import React, { useState } from "react";
import {
  Box, Typography, Tabs, Tab,
} from "@mui/material";
import {
  FaceOutlined, CenterFocusStrongOutlined, AutoAwesomeOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import FaceDetectionManagement from "../faceDetection/FaceDetectionManagement";
import ObjectDetectionManagement from "./ObjectDetectionManagement";
import LLMDetectionManagement from "./LLMDetectionManagement";

export default function DetectionManagement() {
  const [tab, setTab] = useState(0);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", mb: 0.75 }}>
          Detection Management
        </Typography>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ minHeight: 32 }}>
          <Tab icon={<FaceOutlined sx={{ fontSize: 14 }} />} iconPosition="start" label="Faces" sx={{ minHeight: 32, fontSize: "0.78rem", textTransform: "none", px: 1.5 }} />
          <Tab icon={<CenterFocusStrongOutlined sx={{ fontSize: 14 }} />} iconPosition="start" label="Objects" sx={{ minHeight: 32, fontSize: "0.78rem", textTransform: "none", px: 1.5 }} />
          <Tab icon={<AutoAwesomeOutlined sx={{ fontSize: 14 }} />} iconPosition="start" label="LLM" sx={{ minHeight: 32, fontSize: "0.78rem", textTransform: "none", px: 1.5 }} />
        </Tabs>
      </Box>

      {/* Tab content */}
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        {tab === 0 && <FaceDetectionManagement embedded />}
        {tab === 1 && <ObjectDetectionManagement />}
        {tab === 2 && <LLMDetectionManagement />}
      </Box>
    </Box>
  );
}
