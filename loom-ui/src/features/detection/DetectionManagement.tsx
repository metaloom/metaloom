import React, { useState } from "react";
import {
  Box, Tabs, Tab,
} from "@mui/material";
import {
  FaceOutlined, CenterFocusStrongOutlined, AutoAwesomeOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import ViewHeader from "../../components/ViewHeader";
import { useTranslation } from "react-i18next";
import HelpHint from "../../components/HelpHint";
import FaceDetectionManagement from "../faceDetection/FaceDetectionManagement";
import ObjectDetectionManagement from "./ObjectDetectionManagement";
import LLMDetectionManagement from "./LLMDetectionManagement";

export default function DetectionManagement() {
  const [tab, setTab] = useState(0);
  const { t } = useTranslation();

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <ViewHeader
        icon={<CenterFocusStrongOutlined />}
        title={t("detection.title")}
        // Faces have a section of their own — grouping a stranger's face is a different job
        // from confirming a box a model drew. The other two tabs share the general one.
        meta={<HelpHint topic={tab === 0 ? "detection.faces" : "detection.results"} />}
      >
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ minHeight: 32 }}>
          <Tab icon={<FaceOutlined sx={{ fontSize: 14 }} />} iconPosition="start" label={t("detection.tab.faces")} sx={{ minHeight: 32, fontSize: "0.78rem", textTransform: "none", px: 1.5 }} />
          <Tab icon={<CenterFocusStrongOutlined sx={{ fontSize: 14 }} />} iconPosition="start" label={t("detection.tab.objects")} sx={{ minHeight: 32, fontSize: "0.78rem", textTransform: "none", px: 1.5 }} />
          <Tab icon={<AutoAwesomeOutlined sx={{ fontSize: 14 }} />} iconPosition="start" label={t("detection.tab.llm")} sx={{ minHeight: 32, fontSize: "0.78rem", textTransform: "none", px: 1.5 }} />
        </Tabs>
      </ViewHeader>

      {/* Tab content.

          `overflow: visible`, not `hidden`: each panel below clips and scrolls itself, and the
          only thing the hidden here ever cut off was the thumbnail slider's value bubble, which
          pops *up* out of the toolbar and into this band's top edge. `minHeight: 0` is what the
          hidden was silently providing — a column flex item defaults to `min-height: auto` and
          would otherwise refuse to shrink below its content. The stacking context puts whatever
          escapes above the header rather than behind it. */}
      <Box sx={{ flex: 1, minHeight: 0, overflow: "visible", position: "relative", zIndex: 1 }}>
        {tab === 0 && <FaceDetectionManagement embedded />}
        {tab === 1 && <ObjectDetectionManagement />}
        {tab === 2 && <LLMDetectionManagement />}
      </Box>
    </Box>
  );
}
