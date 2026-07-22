import React, { useCallback, useEffect, useState } from "react";
import {
  Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  IconButton, Switch, Tab, Table, TableBody, TableCell, TableHead, TableRow,
  Tabs, TextField, Tooltip, Typography,
} from "@mui/material";
import {
  Add, DeleteOutline, DownloadOutlined, EditOutlined, UpgradeOutlined,
} from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import Title from "../../components/Title";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import {
  createSkill, deleteSkill, installSkill, listSkillLibrary, listSkills,
  SkillResponse, updateSkill,
} from "../../api/skills";

interface EditorState {
  uuid?: string;
  name: string;
  description: string;
  content: string;
}

/**
 * Management view for the users agent skills: CRUD with a markdown editor,
 * publish toggle, and the shared library (published skills of other users,
 * installable as own copies with provenance).
 */
export default function SkillManagementView() {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { showToast } = useToast();
  const [tab, setTab] = useState<"mine" | "library">("mine");
  const [skills, setSkills] = useState<SkillResponse[]>([]);
  const [library, setLibrary] = useState<SkillResponse[]>([]);
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<SkillResponse | null>(null);

  const refresh = useCallback(() => {
    if (!token) return;
    listSkills(token).then(res => setSkills(res.data ?? [])).catch(() => setSkills([]));
    listSkillLibrary(token).then(res => setLibrary(res.data ?? [])).catch(() => setLibrary([]));
  }, [token]);

  useEffect(refresh, [refresh]);

  const handleSave = async () => {
    if (!token || !editor) return;
    try {
      if (editor.uuid) {
        await updateSkill(token, editor.uuid, {
          name: editor.name,
          description: editor.description,
          content: editor.content,
        });
      } else {
        await createSkill(token, {
          name: editor.name,
          description: editor.description,
          content: editor.content,
        });
      }
      setEditor(null);
      refresh();
      showToast(t("skills.saved"), "success");
    } catch (e) {
      console.error("Failed to save skill", e);
      showToast(t("skills.saveFailed"), "error");
    }
  };

  const handleToggle = async (skill: SkillResponse, field: "enabled" | "published") => {
    if (!token) return;
    try {
      await updateSkill(token, skill.uuid, { [field]: !skill[field] });
      refresh();
    } catch (e) {
      console.error("Failed to update skill", e);
      showToast(t("skills.saveFailed"), "error");
    }
  };

  const handleDelete = async () => {
    if (!token || !deleteTarget) return;
    try {
      await deleteSkill(token, deleteTarget.uuid);
      setDeleteTarget(null);
      refresh();
      showToast(t("skills.deleted"), "success");
    } catch (e) {
      console.error("Failed to delete skill", e);
      showToast(t("skills.saveFailed"), "error");
    }
  };

  const handleInstall = async (skill: SkillResponse) => {
    if (!token) return;
    try {
      const copy = await installSkill(token, skill.uuid);
      refresh();
      showToast(t("skills.installed", { name: copy.name }), "success");
    } catch (e) {
      console.error("Failed to install skill", e);
      showToast(t("skills.installFailed"), "error");
    }
  };

  const ownUuids = new Set(skills.map(s => s.uuid));

  return (
    <Box sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2, height: "100%", overflow: "auto" }}>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <Box>
          <Title>{t("skills.title")}</Title>
          <Typography variant="caption" sx={{ color: tokens.text.secondary }}>{t("skills.subtitle")}</Typography>
        </Box>
        <Button
          variant="contained"
          size="small"
          startIcon={<Add />}
          data-testid="skill-create-button"
          onClick={() => setEditor({ name: "", description: "", content: "" })}
        >
          {t("skills.create")}
        </Button>
      </Box>

      <Tabs value={tab} onChange={(_e, v) => setTab(v)} sx={{ minHeight: 36, "& .MuiTab-root": { minHeight: 36, fontSize: "0.8rem" } }}>
        <Tab label={t("skills.tabMine")} value="mine" data-testid="skills-mine-tab" />
        <Tab label={t("skills.tabLibrary")} value="library" data-testid="skills-library-tab" />
      </Tabs>

      {tab === "mine" ? (
        <Table size="small" data-testid="skills-table">
          <TableHead>
            <TableRow>
              <TableCell>{t("skills.columns.name")}</TableCell>
              <TableCell>{t("skills.columns.description")}</TableCell>
              <TableCell align="center">{t("skills.columns.enabled")}</TableCell>
              <TableCell align="center">{t("skills.columns.published")}</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {skills.length === 0 && (
              <TableRow>
                <TableCell colSpan={5}>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>{t("skills.empty")}</Typography>
                </TableCell>
              </TableRow>
            )}
            {skills.map(skill => (
              <TableRow key={skill.uuid} hover data-testid={`skill-row-${skill.name}`}>
                <TableCell>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                    <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{skill.name}</Typography>
                    {skill.originSkillUuid && (
                      <Tooltip title={t("skills.originBadge")}>
                        <Chip label={t("skills.installedBadge")} size="small" sx={{ height: 18, fontSize: "0.64rem" }} data-testid="skill-origin-badge" />
                      </Tooltip>
                    )}
                    {skill.updateAvailable && (
                      <Tooltip title={t("skills.updateAvailable")}>
                        <UpgradeOutlined sx={{ fontSize: 16, color: tokens.accent.amber }} data-testid="skill-update-available" />
                      </Tooltip>
                    )}
                  </Box>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" sx={{ color: tokens.text.secondary }}>{skill.description}</Typography>
                </TableCell>
                <TableCell align="center">
                  <Switch size="small" checked={skill.enabled} onChange={() => handleToggle(skill, "enabled")} data-testid={`skill-enabled-${skill.name}`} />
                </TableCell>
                <TableCell align="center">
                  <Switch size="small" checked={skill.published} onChange={() => handleToggle(skill, "published")} data-testid={`skill-published-${skill.name}`} />
                </TableCell>
                <TableCell align="right">
                  <IconButton size="small" onClick={() => setEditor({ uuid: skill.uuid, name: skill.name, description: skill.description, content: skill.content })} data-testid={`skill-edit-${skill.name}`}>
                    <EditOutlined sx={{ fontSize: 16 }} />
                  </IconButton>
                  <IconButton size="small" onClick={() => setDeleteTarget(skill)} sx={{ color: tokens.accent.red }} data-testid={`skill-delete-${skill.name}`}>
                    <DeleteOutline sx={{ fontSize: 16 }} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <Table size="small" data-testid="skills-library-table">
          <TableHead>
            <TableRow>
              <TableCell>{t("skills.columns.name")}</TableCell>
              <TableCell>{t("skills.columns.description")}</TableCell>
              <TableCell>{t("skills.columns.author")}</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {library.length === 0 && (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>{t("skills.libraryEmpty")}</Typography>
                </TableCell>
              </TableRow>
            )}
            {library.map(skill => (
              <TableRow key={skill.uuid} hover>
                <TableCell>
                  <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{skill.name}</Typography>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" sx={{ color: tokens.text.secondary }}>{skill.description}</Typography>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>{skill.status?.creator?.name ?? "—"}</Typography>
                </TableCell>
                <TableCell align="right">
                  {ownUuids.has(skill.uuid) ? (
                    <Chip label={t("skills.own")} size="small" sx={{ height: 20, fontSize: "0.68rem" }} />
                  ) : (
                    <Button
                      size="small"
                      startIcon={<DownloadOutlined sx={{ fontSize: 14 }} />}
                      onClick={() => handleInstall(skill)}
                      data-testid={`skill-install-${skill.name}`}
                    >
                      {t("skills.install")}
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {/* Create / edit dialog with markdown editor */}
      <Dialog open={!!editor} onClose={() => setEditor(null)} maxWidth="md" fullWidth data-testid="skill-editor-dialog">
        <DialogTitle sx={{ fontSize: "1rem" }}>{editor?.uuid ? t("skills.edit") : t("skills.create")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField
            label={t("skills.columns.name")}
            size="small"
            value={editor?.name ?? ""}
            onChange={e => setEditor(prev => prev && { ...prev, name: e.target.value })}
            inputProps={{ "data-testid": "skill-editor-name" }}
          />
          <TextField
            label={t("skills.columns.description")}
            size="small"
            helperText={t("skills.descriptionHelper")}
            value={editor?.description ?? ""}
            onChange={e => setEditor(prev => prev && { ...prev, description: e.target.value })}
            inputProps={{ "data-testid": "skill-editor-description", maxLength: 1024 }}
          />
          <TextField
            label={t("skills.columns.content")}
            multiline
            minRows={10}
            maxRows={24}
            helperText={t("skills.contentHelper")}
            value={editor?.content ?? ""}
            onChange={e => setEditor(prev => prev && { ...prev, content: e.target.value })}
            InputProps={{ sx: { fontFamily: "monospace", fontSize: "0.8rem" } }}
            inputProps={{ "data-testid": "skill-editor-content" }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditor(null)}>{t("skills.cancel")}</Button>
          <Button
            variant="contained"
            onClick={handleSave}
            disabled={!editor?.name.trim() || !editor?.description.trim() || !editor?.content.trim()}
            data-testid="skill-editor-save"
          >
            {t("skills.save")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirmation */}
      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} data-testid="skill-delete-dialog">
        <DialogTitle sx={{ fontSize: "1rem" }}>{t("skills.deleteTitle")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2">{t("skills.deleteConfirm", { name: deleteTarget?.name })}</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteTarget(null)}>{t("skills.cancel")}</Button>
          <Button color="error" variant="contained" onClick={handleDelete} data-testid="skill-delete-confirm">
            {t("skills.delete")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
