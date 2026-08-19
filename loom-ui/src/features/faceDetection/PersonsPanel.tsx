import React, { useState } from "react";
import { Link as RouterLink } from "react-router-dom";
import {
  Box, Typography, Paper, Avatar, Chip, IconButton, Dialog, DialogTitle,
  DialogContent, DialogActions, Button, TextField,
} from "@mui/material";
import {
  PersonOutlined, EditOutlined, DeleteOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { FaceCluster, Person } from "../../types";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { useFailure } from "../../context/FailureContext";
import { deletePerson as apiDeletePerson, updatePerson as apiUpdatePerson } from "../../api/persons";
import { toUiPerson } from "./personMapping";

interface PersonsPanelProps {
  persons: Person[];
  clusters: FaceCluster[];
  onPersonDeleted?: (id: string) => void;
  onPersonUpdated?: (person: Person) => void;
}

export default function PersonsPanel({ persons, clusters, onPersonDeleted, onPersonUpdated }: PersonsPanelProps) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const { reportFailure } = useFailure();
  const [editPerson, setEditPerson] = useState<Person | null>(null);
  const [editAlias, setEditAlias] = useState("");
  const [editFirstname, setEditFirstname] = useState("");
  const [editLastname, setEditLastname] = useState("");

  const handleDelete = async (id: string) => {
    if (!token) return;
    try {
      await apiDeletePerson(token, id);
      onPersonDeleted?.(id);
    } catch (e) {
      // The row stays. The optimistic removal is only applied once the server has agreed, so
      // there is nothing to roll back - which is the arrangement that cannot lie.
      reportFailure("deletePerson", e);
    }
  };

  const openEdit = (person: Person) => {
    setEditPerson(person);
    setEditAlias(person.description || "");
    setEditFirstname("");
    setEditLastname("");
    const parts = person.name.split(" ");
    if (parts.length >= 2) {
      setEditFirstname(parts[0]);
      setEditLastname(parts.slice(1).join(" "));
    } else {
      setEditFirstname(person.name);
    }
  };

  const handleUpdate = async () => {
    if (!editPerson || !token) return;
    try {
      const resp = await apiUpdatePerson(token, editPerson.id, {
        alias: editAlias || undefined,
        firstname: editFirstname || undefined,
        lastname: editLastname || undefined,
      });
      // Echo what the server stored rather than the strings that were typed. The response was
      // previously assigned and never read, so the panel showed the edit even when the write had
      // silently dropped part of it - which it did, for the avatar, on every save.
      onPersonUpdated?.(toUiPerson(resp, editPerson.clusterIds));
      // Inside the try: closing the editor outside it made a rejected save look like a saved one.
      setEditPerson(null);
    } catch (e) {
      reportFailure("updatePerson", e);
    }
  };

  return (
    <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 2 }}>
      {persons.map(person => {
        const personClusters = clusters.filter(c => person.clusterIds.includes(c.id));
        return (
          <Paper
            key={person.id}
            elevation={0}
            data-testid="person-card"
            sx={{
              bgcolor: tokens.bg.elevated,
              border: `1px solid ${tokens.border.subtle}`,
              borderRadius: tokens.radius.lg,
              overflow: "hidden",
            }}
          >
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 2, py: 1.5 }}>
              <Avatar src={person.avatarUrl} sx={{ width: 48, height: 48 }} />
              <Box sx={{ flex: 1 }}>
                <Typography
                  variant="body2"
                  fontWeight={600}
                  data-testid="person-name"
                  component={RouterLink}
                  to={`/persons/${person.id}`}
                  sx={{ fontSize: "0.88rem", color: tokens.text.primary, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
                >
                  {person.name}
                </Typography>
                <Typography variant="caption" data-testid="person-alias" sx={{ color: tokens.text.secondary, fontSize: "0.75rem", display: "block" }}>
                  {person.description}
                </Typography>
                <Box sx={{ display: "flex", gap: 0.5, mt: 0.5 }}>
                  <Chip label={t("faceDetection.count.clusters", { count: person.clusterIds.length })} size="small" data-testid="person-cluster-count" sx={{ height: 18, fontSize: "0.62rem", bgcolor: tokens.bg.overlay }} />
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.65rem", alignSelf: "center" }}>
                    {t("faceDetection.label.since", { date: new Date(person.createdAt).toLocaleDateString() })}
                  </Typography>
                </Box>
              </Box>
              <Box sx={{ display: "flex", flexDirection: "column" }}>
                <IconButton size="small" data-testid="person-edit" onClick={() => openEdit(person)}>
                  <EditOutlined sx={{ fontSize: 16 }} />
                </IconButton>
                <IconButton size="small" data-testid="person-delete" onClick={() => handleDelete(person.id)}>
                  <DeleteOutlined sx={{ fontSize: 16 }} />
                </IconButton>
              </Box>
            </Box>
            {personClusters.length > 0 && (
              <Box sx={{ px: 2, pb: 1.5, display: "flex", gap: 0.75, flexWrap: "wrap" }}>
                {personClusters.map(c => (
                  <Chip
                    key={c.id}
                    avatar={<Avatar src={c.representativeThumbnailUrl} />}
                    label={c.label}
                    size="small"
                    data-testid="person-cluster-chip"
                    sx={{ height: 24, fontSize: "0.7rem", bgcolor: tokens.bg.overlay }}
                  />
                ))}
              </Box>
            )}
          </Paper>
        );
      })}
      {persons.length === 0 && (
        <Box data-testid="persons-empty" sx={{ gridColumn: "1 / -1", display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
          <PersonOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
          <Typography variant="body2" color="text.secondary">{t("faceDetection.empty.persons")}</Typography>
        </Box>
      )}

      {/* Edit Person Dialog */}
      <Dialog open={!!editPerson} onClose={() => setEditPerson(null)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("faceDetection.dialog.editPerson")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField
            label={t("faceDetection.label.alias")}
            value={editAlias}
            onChange={e => setEditAlias(e.target.value)}
            size="small"
            fullWidth
            data-testid="person-edit-alias"
          />
          <TextField
            label={t("faceDetection.label.firstname")}
            value={editFirstname}
            onChange={e => setEditFirstname(e.target.value)}
            size="small"
            fullWidth
            data-testid="person-edit-firstname"
          />
          <TextField
            label={t("faceDetection.label.lastname")}
            value={editLastname}
            onChange={e => setEditLastname(e.target.value)}
            size="small"
            fullWidth
            data-testid="person-edit-lastname"
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditPerson(null)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleUpdate} variant="contained" size="small" data-testid="person-edit-save">{t("faceDetection.button.save")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
