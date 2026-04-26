import React, { useEffect, useState } from "react";
import {
  Box, Typography, Chip, TextField, InputAdornment, Button,
  Dialog, DialogTitle, DialogContent, DialogActions,
  FormControl, Select, MenuItem, SelectChangeEvent,
} from "@mui/material";
import {
  SearchOutlined, GroupWorkOutlined, PersonOutlined, AddOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { FaceCluster, Person } from "../../types";
import { mockFaceDetectionService } from "../../mock/services";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { listPersons, createPerson as apiCreatePerson, deletePerson as apiDeletePerson, updatePerson as apiUpdatePerson, PersonResponse } from "../../api/persons";
import ClustersPanel from "./ClustersPanel";
import PersonsPanel from "./PersonsPanel";

export default function FaceDetectionManagement({ embedded }: { embedded?: boolean }) {
  const [clusters, setClusters] = useState<FaceCluster[]>([]);
  const [persons, setPersons] = useState<Person[]>([]);
  const [query, setQuery] = useState("");
  const [activeSection, setActiveSection] = useState<"clusters" | "persons">("clusters");
  const [createPersonOpen, setCreatePersonOpen] = useState(false);
  const [newPersonAlias, setNewPersonAlias] = useState("");
  const [newPersonFirstname, setNewPersonFirstname] = useState("");
  const [newPersonLastname, setNewPersonLastname] = useState("");
  const [assignOpen, setAssignOpen] = useState<string | null>(null);
  const [assignPersonId, setAssignPersonId] = useState("");
  const { t } = useTranslation();
  const { token } = useAuth();

  const toUiPerson = (r: PersonResponse): Person => ({
    id: r.uuid,
    name: [r.firstname, r.lastname].filter(Boolean).join(" ") || r.alias,
    description: r.alias,
    avatarUrl: "",
    clusterIds: [],
    createdAt: r.status?.created ?? new Date().toISOString(),
  });

  useEffect(() => {
    const loadData = async () => {
      const c = await mockFaceDetectionService.getAllClusters();
      setClusters(c);
      if (token) {
        try {
          const resp = await listPersons(token);
          setPersons(resp.data.map(toUiPerson));
        } catch {
          const p = await mockFaceDetectionService.getAllPersons();
          setPersons(p);
        }
      } else {
        const p = await mockFaceDetectionService.getAllPersons();
        setPersons(p);
      }
    };
    loadData();
  }, [token]);

  const filteredClusters = clusters.filter(c => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    const person = c.personId ? persons.find(p => p.id === c.personId) : undefined;
    return c.label.toLowerCase().includes(q) || (person?.name.toLowerCase().includes(q) ?? false);
  });

  const filteredPersons = persons.filter(p => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    return p.name.toLowerCase().includes(q) || p.description.toLowerCase().includes(q);
  });

  const handleCreatePerson = async () => {
    if (!newPersonAlias.trim()) return;
    if (token) {
      try {
        const resp = await apiCreatePerson(token, {
          alias: newPersonAlias,
          firstname: newPersonFirstname || undefined,
          lastname: newPersonLastname || undefined,
        });
        setPersons(prev => [...prev, toUiPerson(resp)]);
      } catch {
        // fallback to mock
        const p = await mockFaceDetectionService.createPerson(newPersonAlias, "");
        setPersons(prev => [...prev, p]);
      }
    } else {
      const p = await mockFaceDetectionService.createPerson(newPersonAlias, "");
      setPersons(prev => [...prev, p]);
    }
    setNewPersonAlias("");
    setNewPersonFirstname("");
    setNewPersonLastname("");
    setCreatePersonOpen(false);
  };

  const handleAssignCluster = async () => {
    if (!assignOpen || !assignPersonId) return;
    await mockFaceDetectionService.assignClusterToPerson(assignOpen, assignPersonId);
    setClusters(prev => prev.map(c => c.id === assignOpen ? { ...c, personId: assignPersonId } : c));
    setPersons(prev => prev.map(p => p.id === assignPersonId ? { ...p, clusterIds: [...p.clusterIds, assignOpen!] } : p));
    setAssignOpen(null);
    setAssignPersonId("");
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      {!embedded && (
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", mb: 0.5 }}>
          {t("faceDetection.title")}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {t("faceDetection.subtitle")}
        </Typography>
      </Box>
      )}

      {/* Toolbar */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 1, alignItems: "center" }}>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t("faceDetection.search.placeholder")}
          size="small"
          sx={{ flex: 1, maxWidth: 320 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
        />
        <Box sx={{ display: "flex", gap: 0.5 }}>
          <Chip
            label={t("faceDetection.chip.clusters")}
            size="small"
            icon={<GroupWorkOutlined sx={{ fontSize: 14 }} />}
            onClick={() => setActiveSection("clusters")}
            sx={{
              bgcolor: activeSection === "clusters" ? tokens.primary.subtle : tokens.bg.elevated,
              color: activeSection === "clusters" ? tokens.primary.main : tokens.text.secondary,
              border: `1px solid ${activeSection === "clusters" ? tokens.primary.main : "transparent"}`,
              fontWeight: activeSection === "clusters" ? 600 : 400,
            }}
          />
          <Chip
            label={t("faceDetection.chip.persons")}
            size="small"
            icon={<PersonOutlined sx={{ fontSize: 14 }} />}
            onClick={() => setActiveSection("persons")}
            sx={{
              bgcolor: activeSection === "persons" ? tokens.primary.subtle : tokens.bg.elevated,
              color: activeSection === "persons" ? tokens.primary.main : tokens.text.secondary,
              border: `1px solid ${activeSection === "persons" ? tokens.primary.main : "transparent"}`,
              fontWeight: activeSection === "persons" ? 600 : 400,
            }}
          />
        </Box>
        {activeSection === "persons" && (
          <Button
            size="small"
            startIcon={<AddOutlined sx={{ fontSize: 14 }} />}
            onClick={() => setCreatePersonOpen(true)}
            sx={{ ml: "auto", textTransform: "none", fontSize: "0.78rem" }}
          >
            {t("faceDetection.button.addPerson")}
          </Button>
        )}
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {activeSection === "clusters" && (
          <ClustersPanel
            clusters={filteredClusters}
            persons={persons}
            onAssignCluster={(clusterId) => { setAssignOpen(clusterId); setAssignPersonId(""); }}
          />
        )}
        {activeSection === "persons" && (
          <PersonsPanel
            persons={filteredPersons}
            clusters={clusters}
            onPersonDeleted={(id) => setPersons(prev => prev.filter(p => p.id !== id))}
            onPersonUpdated={(updated) => setPersons(prev => prev.map(p => p.id === updated.id ? updated : p))}
          />
        )}
      </Box>

      {/* Create Person Dialog */}
      <Dialog open={createPersonOpen} onClose={() => setCreatePersonOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("faceDetection.dialog.addPerson")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField
            label={t("faceDetection.label.alias")}
            value={newPersonAlias}
            onChange={e => setNewPersonAlias(e.target.value)}
            size="small"
            fullWidth
            autoFocus
          />
          <TextField
            label={t("faceDetection.label.firstname")}
            value={newPersonFirstname}
            onChange={e => setNewPersonFirstname(e.target.value)}
            size="small"
            fullWidth
          />
          <TextField
            label={t("faceDetection.label.lastname")}
            value={newPersonLastname}
            onChange={e => setNewPersonLastname(e.target.value)}
            size="small"
            fullWidth
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreatePersonOpen(false)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleCreatePerson} variant="contained" size="small" disabled={!newPersonAlias.trim()}>{t("faceDetection.button.create")}</Button>
        </DialogActions>
      </Dialog>

      {/* Assign Cluster to Person Dialog */}
      <Dialog open={!!assignOpen} onClose={() => setAssignOpen(null)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("faceDetection.dialog.assign")}</DialogTitle>
        <DialogContent sx={{ pt: "8px !important" }}>
          <FormControl fullWidth size="small">
            <Select
              value={assignPersonId}
              onChange={(e: SelectChangeEvent) => setAssignPersonId(e.target.value)}
              displayEmpty
              sx={{ fontSize: "0.85rem" }}
            >
              <MenuItem value="" disabled>{t("faceDetection.dialog.selectPerson")}</MenuItem>
              {persons.map(p => (
                <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>
              ))}
            </Select>
          </FormControl>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAssignOpen(null)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleAssignCluster} variant="contained" size="small" disabled={!assignPersonId}>{t("faceDetection.button.assign")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
