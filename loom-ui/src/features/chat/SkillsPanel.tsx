import React, { useEffect, useState } from "react";
import {
  Badge, Box, Checkbox, IconButton, Popover, Tooltip, Typography,
} from "@mui/material";
import { AutoFixHighOutlined, SettingsOutlined } from "@mui/icons-material";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { listSkills, SkillResponse } from "../../api/skills";
import { PAGE_SIZE } from "../../hooks/pagedList";

/**
 * Per-chat skill activation: a popover listing the users enabled skills with
 * checkboxes. Toggling updates the active set for the session — it takes effect
 * on the next message sent to the agent.
 */
export default function SkillsPanel({
  activeSkillUuids,
  onChange,
}: {
  activeSkillUuids: string[];
  onChange: (uuids: string[]) => void;
}) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const navigate = useNavigate();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [skills, setSkills] = useState<SkillResponse[]>([]);

  useEffect(() => {
    if (!token) return;
    listSkills(token, { limit: PAGE_SIZE })
      .then(res => setSkills((res.data ?? []).filter(s => s.enabled)))
      .catch(() => setSkills([]));
  }, [token, anchor]);

  const toggle = (uuid: string) => {
    onChange(
      activeSkillUuids.includes(uuid)
        ? activeSkillUuids.filter(u => u !== uuid)
        : [...activeSkillUuids, uuid],
    );
  };

  return (
    <>
      <Tooltip title={t("chat.skills.title")}>
        <IconButton
          size="small"
          data-testid="chat-skills-button"
          onClick={(e) => setAnchor(e.currentTarget)}
          sx={{ color: activeSkillUuids.length > 0 ? tokens.primary.light : tokens.text.secondary }}
        >
          <Badge badgeContent={activeSkillUuids.length} color="primary" sx={{ "& .MuiBadge-badge": { fontSize: "0.6rem", height: 14, minWidth: 14 } }}>
            <AutoFixHighOutlined sx={{ fontSize: 18 }} />
          </Badge>
        </IconButton>
      </Tooltip>
      <Popover
        open={!!anchor}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
        slotProps={{ paper: { sx: { width: 280, bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.default}` } } }}
      >
        <Box data-testid="chat-skills-panel" sx={{ py: 1 }}>
          <Box sx={{ px: 1.75, pb: 0.75, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.secondary, fontSize: "0.68rem" }}>
              {t("chat.skills.active", { count: activeSkillUuids.length })}
            </Typography>
            <Tooltip title={t("chat.skills.manage")}>
              <IconButton size="small" onClick={() => { setAnchor(null); navigate("/skills"); }} sx={{ color: tokens.text.tertiary }}>
                <SettingsOutlined sx={{ fontSize: 15 }} />
              </IconButton>
            </Tooltip>
          </Box>
          {skills.length === 0 ? (
            <Typography variant="caption" sx={{ px: 1.75, py: 1, display: "block", color: tokens.text.tertiary, fontSize: "0.74rem" }}>
              {t("chat.skills.none")}
            </Typography>
          ) : (
            skills.map((skill) => (
              <Tooltip key={skill.uuid} title={skill.description} placement="left">
                <Box
                  data-testid={`skill-toggle-${skill.name}`}
                  onClick={() => toggle(skill.uuid)}
                  sx={{
                    display: "flex", alignItems: "center", gap: 0.5,
                    px: 1, py: 0.25, mx: 0.75, borderRadius: tokens.radius.md,
                    cursor: "pointer",
                    "&:hover": { bgcolor: tokens.bg.hover },
                  }}
                >
                  <Checkbox
                    size="small"
                    checked={activeSkillUuids.includes(skill.uuid)}
                    sx={{ p: 0.5 }}
                    tabIndex={-1}
                  />
                  <Box sx={{ overflow: "hidden" }}>
                    <Typography variant="caption" fontWeight={500} noWrap display="block" sx={{ fontSize: "0.78rem", color: tokens.text.primary }}>
                      {skill.name}
                    </Typography>
                    <Typography variant="caption" noWrap display="block" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary }}>
                      {skill.description}
                    </Typography>
                  </Box>
                </Box>
              </Tooltip>
            ))
          )}
        </Box>
      </Popover>
    </>
  );
}
