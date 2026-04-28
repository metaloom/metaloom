import React from "react";
import { Box, Chip, Tooltip } from "@mui/material";
import {
  CheckCircleOutlineOutlined, FlagOutlined, ThumbUpAltOutlined,
  StarBorderOutlined, HelpOutlineOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Reaction } from "../../types";
import { userName } from "./helpers";

const reactionIcon: Record<string, React.ReactNode> = {
  approve: <CheckCircleOutlineOutlined sx={{ fontSize: 13 }} />,
  flag: <FlagOutlined sx={{ fontSize: 13 }} />,
  reject: <ThumbUpAltOutlined sx={{ fontSize: 13, transform: "scaleY(-1)" }} />,
  favorite: <StarBorderOutlined sx={{ fontSize: 13 }} />,
  question: <HelpOutlineOutlined sx={{ fontSize: 13 }} />,
};

export const reactionColor: Record<string, string> = {
  approve: tokens.accent.green,
  flag: tokens.accent.red,
  reject: tokens.accent.amber,
  favorite: "#f5c842",
  question: tokens.accent.blue,
};

export function ReactionChip({ reaction }: { reaction: Reaction }) {
  const c = reactionColor[reaction.type] ?? tokens.text.secondary;
  return (
    <Tooltip title={`${userName(reaction.userId)} · ${reaction.type}${reaction.rating ? ` · ${reaction.rating}/5` : ""}`}>
      <Chip
        icon={<Box sx={{ color: c, display: "flex", ml: "6px !important" }}>{reactionIcon[reaction.type]}</Box>}
        label={reaction.type}
        size="small"
        sx={{ bgcolor: `${c}14`, border: `1px solid ${c}33`, color: c, fontSize: "0.72rem" }}
      />
    </Tooltip>
  );
}
