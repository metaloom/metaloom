import React, { useCallback, useEffect, useState } from "react";
import {
  Box, Button, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle,
  IconButton, Switch, Tab, Table, TableBody, TableCell, TableHead, TableRow,
  Tabs, TextField, Tooltip, Typography, InputAdornment,
} from "@mui/material";
import {
  Add, AutoAwesomeOutlined, DeleteOutline, DownloadOutlined, EditOutlined, HistoryOutlined,
  RestoreOutlined, UpgradeOutlined, SearchOutlined,
} from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import Title from "../../components/Title";
import EmptyState from "../../components/EmptyState";
import { tokens } from "../../theme";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import {
  createSkill, deleteSkill, installSkill, listSkillLibrary, listSkills, listSkillVersions,
  restoreSkillVersion, SkillResponse, updateSkill,
} from "../../api/skills";
import { PAGE_SIZE } from "../../hooks/pagedList";

interface EditorState {
  uuid?: string;
  name: string;
  description: string;
  content: string;
  /** Active version number of the skill being edited (undefined for a new skill). */
  activeVersion?: number;
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
  // One term per tab — switching tabs must not silently carry a filter across.
  const [mineQuery, setMineQuery] = useState("");
  const [libraryQuery, setLibraryQuery] = useState("");
  const [skills, setSkills] = useState<SkillResponse[]>([]);
  const [library, setLibrary] = useState<SkillResponse[]>([]);
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<SkillResponse | null>(null);
  const [versions, setVersions] = useState<SkillResponse[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [restoreConfirm, setRestoreConfirm] = useState<number | null>(null);
  const [restoring, setRestoring] = useState<number | null>(null);

  const refresh = useCallback(() => {
    if (!token) return;
    listSkills(token, { limit: PAGE_SIZE }).then(res => setSkills(res.data ?? [])).catch(() => setSkills([]));
    listSkillLibrary(token, { limit: PAGE_SIZE }).then(res => setLibrary(res.data ?? [])).catch(() => setLibrary([]));
  }, [token]);

  useEffect(refresh, [refresh]);

  const editorUuid = editor?.uuid;
  const loadVersions = useCallback(() => {
    if (!token || !editorUuid) {
      setVersions([]);
      return;
    }
    setVersionsLoading(true);
    listSkillVersions(token, editorUuid)
      // Newest version first
      .then(list => setVersions([...list].sort((a, b) => (b.versionNumber ?? 0) - (a.versionNumber ?? 0))))
      .catch(() => setVersions([]))
      .finally(() => setVersionsLoading(false));
  }, [token, editorUuid]);

  useEffect(loadVersions, [loadVersions]);

  const handleRevert = async (versionNumber: number) => {
    if (!token || !editor?.uuid) return;
    setRestoring(versionNumber);
    try {
      const updated = await restoreSkillVersion(token, editor.uuid, versionNumber);
      // Adopt the reverted body back into the open editor
      setEditor(prev => prev && {
        ...prev,
        description: updated.description,
        content: updated.content,
        activeVersion: updated.versionNumber ?? versionNumber,
      });
      setRestoreConfirm(null);
      loadVersions();
      refresh();
      showToast(t("skills.version.reverted", { version: versionNumber }), "success");
    } catch (e) {
      console.error("Failed to revert skill version", e);
      showToast(t("skills.version.revertFailed"), "error");
    } finally {
      setRestoring(null);
    }
  };

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

  /** Both tabs filter on name + description; each tab keeps its own term. */
  const matches = (skill: SkillResponse, q: string) =>
    !q.trim()
    || skill.name.toLowerCase().includes(q.toLowerCase())
    || (skill.description ?? "").toLowerCase().includes(q.toLowerCase());

  const activeQuery = tab === "mine" ? mineQuery : libraryQuery;
  const filteredSkills = skills.filter(s => matches(s, mineQuery));
  const filteredLibrary = library.filter(s => matches(s, libraryQuery));
  const visible = tab === "mine" ? filteredSkills : filteredLibrary;
  const loaded = tab === "mine" ? skills : library;

  return (
    <Box data-testid="skills-view" sx={{ p: 3, display: "flex", flexDirection: "column", gap: 2, height: "100%", overflow: "auto" }}>
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

      {loaded.length > 0 && (
        <TextField
          value={activeQuery}
          onChange={e => (tab === "mine" ? setMineQuery(e.target.value) : setLibraryQuery(e.target.value))}
          placeholder={t("skills.search.placeholder")}
          size="small"
          data-testid={tab === "mine" ? "skills-mine-search" : "skills-library-search"}
          sx={{ maxWidth: 360 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
        />
      )}

      {/* Filtered to nothing keeps the inline hint; EmptyState stays bound to "no skills at
          all" — LOOM_UI.md §7.5. Note neither tab renders its <Table> when empty. */}
      {loaded.length > 0 && visible.length === 0 && (
        <Typography variant="body2" data-testid="skills-no-match" sx={{ color: tokens.text.tertiary, py: 3, textAlign: "center" }}>
          {t("skills.emptyState.noSearch")}
        </Typography>
      )}

      {tab === "mine" && skills.length === 0 ? (
        // No skills owned yet — offer to author the first one.
        <EmptyState
          icon={AutoAwesomeOutlined}
          title={t("skills.emptyState.title")}
          description={t("skills.emptyState.description")}
          actionLabel={t("skills.emptyState.action")}
          actionIcon={<Add sx={{ fontSize: 18 }} />}
          onAction={() => setEditor({ name: "", description: "", content: "" })}
          testId="skills-empty-state"
        />
      ) : tab === "library" && library.length === 0 ? (
        <EmptyState
          icon={AutoAwesomeOutlined}
          title={t("skills.emptyState.library.title")}
          description={t("skills.emptyState.library.description")}
          testId="skills-library-empty-state"
        />
      ) : tab === "mine" ? (
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
            {filteredSkills.map(skill => (
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
                  <IconButton size="small" onClick={() => setEditor({ uuid: skill.uuid, name: skill.name, description: skill.description, content: skill.content, activeVersion: skill.versionNumber ?? undefined })} data-testid={`skill-edit-${skill.name}`}>
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
            {filteredLibrary.map(skill => (
              <TableRow key={skill.uuid} hover data-testid={`skill-library-row-${skill.name}`}>
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
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, mt: -1 }}>{t("skills.version.saveHint")}</Typography>

          {/* Version history — only for an existing skill */}
          {editor?.uuid && (
            <Box data-testid="skill-version-history" sx={{ borderTop: `1px solid ${tokens.border.subtle}`, pt: 1.5 }}>
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mb: 1 }}>
                <HistoryOutlined sx={{ fontSize: 16, color: tokens.text.secondary }} />
                <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.8rem" }}>{t("skills.version.title")}</Typography>
              </Box>
              {versionsLoading ? (
                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }} data-testid="skill-version-loading">
                  <CircularProgress size={14} />
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>{t("skills.version.loading")}</Typography>
                </Box>
              ) : versions.length === 0 ? (
                <Typography variant="caption" sx={{ color: tokens.text.tertiary }} data-testid="skill-version-empty">{t("skills.version.empty")}</Typography>
              ) : (
                <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, maxHeight: 200, overflow: "auto" }}>
                  {versions.map(v => {
                    const n = v.versionNumber ?? 0;
                    const isCurrent = n === editor?.activeVersion;
                    const meta = [v.status?.created ? new Date(v.status.created).toLocaleString() : null, v.status?.creator?.name]
                      .filter(Boolean).join(" · ");
                    return (
                      <Box
                        key={v.versionUuid ?? n}
                        data-testid={`skill-version-item-${n}`}
                        sx={{
                          display: "flex", alignItems: "center", gap: 1, px: 1, py: 0.5, borderRadius: 1,
                          bgcolor: isCurrent ? tokens.bg.elevated : "transparent",
                        }}
                      >
                        <Chip label={t("skills.version.badge", { version: n })} size="small" sx={{ height: 18, fontSize: "0.64rem" }} />
                        {isCurrent && (
                          <Chip label={t("skills.version.current")} size="small" color="primary" sx={{ height: 18, fontSize: "0.62rem" }} data-testid="skill-version-current" />
                        )}
                        <Typography variant="caption" sx={{ color: tokens.text.tertiary, flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{meta}</Typography>
                        {!isCurrent && (
                          restoring === n ? (
                            <CircularProgress size={14} />
                          ) : (
                            <Tooltip title={t("skills.version.revert")}>
                              <IconButton size="small" onClick={() => setRestoreConfirm(n)} data-testid={`skill-version-revert-${n}`}>
                                <RestoreOutlined sx={{ fontSize: 16 }} />
                              </IconButton>
                            </Tooltip>
                          )
                        )}
                      </Box>
                    );
                  })}
                </Box>
              )}
            </Box>
          )}
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

      {/* Revert confirmation */}
      <Dialog open={restoreConfirm !== null} onClose={() => setRestoreConfirm(null)} data-testid="skill-version-revert-dialog">
        <DialogTitle sx={{ fontSize: "1rem" }}>{t("skills.version.revertTitle", { version: restoreConfirm ?? "" })}</DialogTitle>
        <DialogContent>
          <Typography variant="body2">{t("skills.version.revertConfirm", { version: restoreConfirm ?? "" })}</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRestoreConfirm(null)}>{t("skills.cancel")}</Button>
          <Button
            color="error"
            variant="contained"
            onClick={() => restoreConfirm !== null && handleRevert(restoreConfirm)}
            data-testid="skill-version-revert-confirm"
          >
            {t("skills.version.revert")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
