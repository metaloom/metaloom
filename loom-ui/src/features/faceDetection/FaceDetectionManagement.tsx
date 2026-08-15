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
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { listPersons, createPerson as apiCreatePerson, listPersonClusters } from "../../api/persons";
import {
  listClusters as apiListClusters,
  createCluster as apiCreateCluster,
  confirmCluster as apiConfirmCluster,
  ClusterResponse,
} from "../../api/clusters";
import ClustersPanel from "./ClustersPanel";
import PersonsPanel from "./PersonsPanel";
import { toUiPerson } from "./personMapping";
import { PAGE_SIZE } from "../../hooks/pagedList";
import { ListFilterSelect } from "../../components/ListControls";

export default function FaceDetectionManagement({ embedded }: { embedded?: boolean }) {
  const [clusters, setClusters] = useState<FaceCluster[]>([]);
  const [persons, setPersons] = useState<Person[]>([]);
  const [query, setQuery] = useState("");
  const [activeSection, setActiveSection] = useState<"clusters" | "persons">("clusters");
  const [assignment, setAssignment] = useState("");
  const [createPersonOpen, setCreatePersonOpen] = useState(false);
  const [createClusterOpen, setCreateClusterOpen] = useState(false);
  const [newClusterName, setNewClusterName] = useState("");
  const [newPersonAlias, setNewPersonAlias] = useState("");
  const [newPersonFirstname, setNewPersonFirstname] = useState("");
  const [newPersonLastname, setNewPersonLastname] = useState("");
  const [assignOpen, setAssignOpen] = useState<string | null>(null);
  const [assignPersonId, setAssignPersonId] = useState("");
  const { t } = useTranslation();
  const { token } = useAuth();

  const toUiCluster = (r: ClusterResponse): FaceCluster => ({
    id: r.uuid,
    // A machine proposal has no name until somebody confirms one, so fall back to something a
    // reviewer can act on rather than rendering a blank card.
    label: r.name || t("faceDetection.label.unnamedCluster"),
    representativeThumbnailUrl: "",
    // Members are fetched lazily: the list route reports the count, so a page of cards is one request
    // rather than one per card.
    faceIds: [],
    faceCount: r.memberCount ?? 0,
    assetId: r.assetUuid,
    reviewStatus: r.reviewStatus,
    reviewedAt: r.reviewedAt,
    reviewerUuid: r.reviewerUuid,
    score: r.score,
    personId: r.personUuid,
  });

  useEffect(() => {
    const loadData = async () => {
      if (token) {
        try {
          const [clustersResp, personsResp] = await Promise.all([
            apiListClusters(token, { limit: PAGE_SIZE }),
            listPersons(token, { limit: PAGE_SIZE }),
          ]);
          setClusters((clustersResp.data ?? []).map(toUiCluster));

          // A person's clusters are the inverse of the confirmation, and the person list does not carry
          // them. One request per person is acceptable for a page of 25 and is what makes the cluster
          // chips on a person card real rather than permanently empty.
          const persons = personsResp.data ?? [];
          const clusterIdsByPerson = await Promise.all(
            persons.map(p =>
              listPersonClusters(token, p.uuid)
                .then(resp => (resp.data ?? []).map(c => c.uuid))
                .catch(() => [] as string[]),
            ),
          );
          setPersons(persons.map((p, i) => toUiPerson(p, clusterIdsByPerson[i])));
        } catch (e) {
          console.error("Failed to load face detection data", e);
        }
      }
    };
    loadData();
  }, [token]);

  const filteredClusters = clusters.filter(c => {
    // Assignment is the axis review actually runs along: the work is finding the clusters that
    // still have nobody attached to them.
    if (assignment === "assigned" && !c.personId) return false;
    if (assignment === "unassigned" && c.personId) return false;
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
    if (!newPersonAlias.trim() || !token) return;
    try {
      const resp = await apiCreatePerson(token, {
        alias: newPersonAlias,
        firstname: newPersonFirstname || undefined,
        lastname: newPersonLastname || undefined,
      });
      setPersons(prev => [...prev, toUiPerson(resp)]);
    } catch (e) {
      console.error("Failed to create person", e);
    }
    setNewPersonAlias("");
    setNewPersonFirstname("");
    setNewPersonLastname("");
    setCreatePersonOpen(false);
  };

  const handleCreateCluster = async () => {
    if (!newClusterName.trim() || !token) return;
    try {
      const resp = await apiCreateCluster(token, { name: newClusterName });
      setClusters(prev => [...prev, toUiCluster(resp)]);
    } catch (e) {
      console.error("Failed to create cluster", e);
    }
    setNewClusterName("");
    setCreateClusterOpen(false);
  };

  /**
   * Confirm that a cluster is a person.
   *
   * This used to mutate local state and nothing else, so the assignment vanished on reload. It now
   * goes through the confirmation endpoint, which sets the verdict and the person link in one
   * transaction; local state is updated from what the server actually stored.
   */
  const handleAssignCluster = async () => {
    if (!assignOpen || !assignPersonId || !token) return;
    try {
      const confirmed = await apiConfirmCluster(token, assignOpen, { personUuid: assignPersonId });
      setClusters(prev => prev.map(c => (c.id === assignOpen ? { ...c, ...toUiCluster(confirmed), faceIds: c.faceIds } : c)));
      setPersons(prev =>
        prev.map(p => (p.id === assignPersonId && !p.clusterIds.includes(assignOpen) 
          ? { ...p, clusterIds: [...p.clusterIds, assignOpen] } 
          : p)),
      );
    } catch (e) {
      console.error("Failed to confirm the cluster", e);
    }
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
          data-testid="facedetection-search"
          sx={{ flex: 1, maxWidth: 320 }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
              </InputAdornment>
            ),
          }}
        />
        {/* Only meaningful for clusters — a person is not assigned to anything. */}
        {activeSection === "clusters" && (
          <ListFilterSelect value={assignment} onChange={setAssignment}
            options={[
              { value: "assigned", label: t("faceDetection.filter.assigned") },
              { value: "unassigned", label: t("faceDetection.filter.unassigned") },
            ]}
            allLabel={t("faceDetection.filter.anyAssignment")} testId="facedetection-filter-assignment" minWidth={150} />
        )}
        {/* Panel switcher. These two panels have no route of their own (LOOM_UI.md §4.2), so the
            chips are the only way to reach them and `aria-pressed` is the only thing that says which
            one is showing. */}
        <Box sx={{ display: "flex", gap: 0.5 }} data-testid="facedetection-switcher">
          <Chip
            label={t("faceDetection.chip.clusters")}
            size="small"
            icon={<GroupWorkOutlined sx={{ fontSize: 14 }} />}
            onClick={() => setActiveSection("clusters")}
            data-testid="facedetection-section-clusters"
            aria-pressed={activeSection === "clusters"}
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
            data-testid="facedetection-section-persons"
            aria-pressed={activeSection === "persons"}
            sx={{
              bgcolor: activeSection === "persons" ? tokens.primary.subtle : tokens.bg.elevated,
              color: activeSection === "persons" ? tokens.primary.main : tokens.text.secondary,
              border: `1px solid ${activeSection === "persons" ? tokens.primary.main : "transparent"}`,
              fontWeight: activeSection === "persons" ? 600 : 400,
            }}
          />
        </Box>
        {activeSection === "clusters" && (
          <Button
            size="small"
            startIcon={<AddOutlined sx={{ fontSize: 14 }} />}
            onClick={() => setCreateClusterOpen(true)}
            data-testid="facedetection-add-cluster"
            sx={{ ml: "auto", textTransform: "none", fontSize: "0.78rem" }}
          >
            {t("faceDetection.button.addCluster")}
          </Button>
        )}
        {activeSection === "persons" && (
          <Button
            size="small"
            startIcon={<AddOutlined sx={{ fontSize: 14 }} />}
            onClick={() => setCreatePersonOpen(true)}
            data-testid="facedetection-add-person"
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
            onClusterDeleted={(id) => setClusters(prev => prev.filter(c => c.id !== id))}
            onClusterUpdated={(updated) => setClusters(prev => prev.map(c => c.id === updated.id ? updated : c))}
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

      {/* Create Cluster Dialog */}
      <Dialog open={createClusterOpen} onClose={() => setCreateClusterOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("faceDetection.dialog.addCluster")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField
            label={t("faceDetection.label.name")}
            value={newClusterName}
            onChange={e => setNewClusterName(e.target.value)}
            size="small"
            fullWidth
            autoFocus
            data-testid="facedetection-cluster-name"
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateClusterOpen(false)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleCreateCluster} variant="contained" size="small" data-testid="facedetection-cluster-create" disabled={!newClusterName.trim()}>{t("faceDetection.button.create")}</Button>
        </DialogActions>
      </Dialog>

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
            data-testid="facedetection-person-alias"
          />
          <TextField
            label={t("faceDetection.label.firstname")}
            value={newPersonFirstname}
            onChange={e => setNewPersonFirstname(e.target.value)}
            size="small"
            fullWidth
            data-testid="facedetection-person-firstname"
          />
          <TextField
            label={t("faceDetection.label.lastname")}
            value={newPersonLastname}
            onChange={e => setNewPersonLastname(e.target.value)}
            size="small"
            fullWidth
            data-testid="facedetection-person-lastname"
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreatePersonOpen(false)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleCreatePerson} variant="contained" size="small" data-testid="facedetection-person-create" disabled={!newPersonAlias.trim()}>{t("faceDetection.button.create")}</Button>
        </DialogActions>
      </Dialog>

      {/* Assign Cluster to Person Dialog */}
      <Dialog open={!!assignOpen} onClose={() => setAssignOpen(null)} maxWidth="xs" fullWidth
        PaperProps={{ "data-testid": "facedetection-assign-dialog" } as React.ComponentProps<typeof Dialog>["PaperProps"]}>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("faceDetection.dialog.assign")}</DialogTitle>
        <DialogContent sx={{ pt: "8px !important" }}>
          <FormControl fullWidth size="small">
            <Select
              value={assignPersonId}
              onChange={(e: SelectChangeEvent) => setAssignPersonId(e.target.value)}
              displayEmpty
              data-testid="facedetection-assign-select"
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
          <Button onClick={handleAssignCluster} variant="contained" size="small" data-testid="facedetection-assign-save" disabled={!assignPersonId}>{t("faceDetection.button.assign")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
