import React from "react";
import { Box } from "@mui/material";
import {
  PlayCircleOutline, ImageOutlined, AudiotrackOutlined, InsertDriveFileOutlined,
} from "@mui/icons-material";
import { tokens } from "../theme";
import { AssetType } from "../types";

const icon: Record<AssetType, React.ComponentType<{ sx?: object }>> = {
  video: PlayCircleOutline,
  image: ImageOutlined,
  audio: AudiotrackOutlined,
  document: InsertDriveFileOutlined,
  unknown: InsertDriveFileOutlined,
};

interface Props {
  type: AssetType;
  /** Icon size in px. */
  iconSize?: number;
  /** Background colour of the placeholder box. */
  bgcolor?: string;
}

/**
 * Type-based media placeholder shown in place of a real thumbnail/preview. Renders a
 * centered icon chosen by asset type. Used until the binary bytes are served for preview.
 */
export default function MediaPlaceholder({ type, iconSize = 48, bgcolor = tokens.bg.overlay }: Props) {
  const Icon = icon[type] ?? InsertDriveFileOutlined;
  return (
    <Box
      sx={{
        position: "absolute",
        inset: 0,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        bgcolor,
      }}
    >
      <Icon sx={{ fontSize: iconSize, color: tokens.text.tertiary }} />
    </Box>
  );
}
