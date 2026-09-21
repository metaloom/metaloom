import React, { useCallback, useEffect, useState } from "react";
import {
  Box, Typography, Chip, TextField, InputAdornment, Button, Autocomplete,
  Dialog, DialogTitle, DialogContent, DialogActions,
  CircularProgress, Slider, Tooltip,
} from "@mui/material";
import {
  SearchOutlined, GroupWorkOutlined, PersonOutlined, AddOutlined,
  PhotoSizeSelectSmallOutlined, PhotoSizeSelectActualOutlined,
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
  detachClusterPerson as apiDetachClusterPerson,
  ClusterResponse,
} from "../../api/clusters";
import ClustersPanel, {
  CLUSTER_SIZE_DEFAULT, CLUSTER_SIZE_MAX, CLUSTER_SIZE_MIN, CLUSTER_SIZE_STEPS, clampClusterSize,
} from "./ClustersPanel";
import PersonsPanel from "./PersonsPanel";
import { toUiPerson } from "./personMapping";
import { PAGE_SIZE } from "../../hooks/pagedList";
import { ListFilterSelect } from "../../components/ListControls";
import { useFailure } from "../../context/FailureContext";
import LoadFailure from "../../components/LoadFailure";

/**
 * localStorage key for the thumbnail-size step.
 *
 * A new key rather than the old one: the value used to be "small" | "medium" | "large" and is
 * now an index, so a stored word has to read as "nothing stored" rather than as step NaN.
 */
const CARD_SIZE_KEY = "loom.faceDetection.clusterThumbStep";

export default function FaceDetectionManagement({ embedded }: { embedded?: boolean }) {
  const [clusters, setClusters] = useState<FaceCluster[]>([]);
  const [persons, setPersons] = useState<Person[]>([]);
  const [query, setQuery] = useState("");
  /**
   * How large to draw the face thumbnails, as a step on {@link CLUSTER_SIZE_STEPS}.
   *
   * Remembered across visits: which size suits you is a property of the work you are doing — a
   * sweep for duplicate people wants the smallest, checking whether one cluster is coherent
   * wants the largest — and having to re-pick it on every navigation is the kind of friction
   * that makes people stop using a control. Anything unparseable falls back to the smallest,
   * which is the density the grid is designed around.
   */
  const [cardSize, setCardSize] = useState<number>(() => {
    const stored = typeof window !== "undefined" ? window.localStorage.getItem(CARD_SIZE_KEY) : null;
    const parsed = stored == null ? NaN : Number.parseInt(stored, 10);
    return Number.isFinite(parsed) ? clampClusterSize(parsed) : CLUSTER_SIZE_DEFAULT;
  });
  const [activeSection, setActiveSection] = useState<"clusters" | "persons">("clusters");
  const [assignment, setAssignment] = useState("");
  const [createPersonOpen, setCreatePersonOpen] = useState(false);
  const [createClusterOpen, setCreateClusterOpen] = useState(false);
  const [newClusterName, setNewClusterName] = useState("");
  const [newPersonAlias, setNewPersonAlias] = useState("");
  const [newPersonFirstname, setNewPersonFirstname] = useState("");
  const [newPersonLastname, setNewPersonLastname] = useState("");
  const [assignOpen, setAssignOpen] = useState<string | null>(null);
  /** A stack-drop waiting on a name, because neither cluster is attributed yet. */
  const [mergePending, setMergePending] = useState<{ sourceId: string; targetId: string } | null>(null);
  const [mergeName, setMergeName] = useState("");
  /**
   * What the assign dialog currently holds.
   *
   * A name rather than a uuid, because the dialog is a type-to-find box: the reviewer knows
   * "Jack O'Neill", not a uuid, and the person they want may not exist yet. Resolved back to a
   * uuid on save, and passed as an `alias` when it matches nobody — which is the same thing the
   * stack-drop dialog does, so naming a person means one thing in this screen however you got
   * there.
   */
  const [assignPersonName, setAssignPersonName] = useState("");
  // Three states, not two: loading, failed and loaded-but-empty are different things to say, and
  // this screen used to render all three identically.
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  // Bumped by the retry button. The effect depends on it, so a retry re-runs the same load
  // rather than needing the load extracted into a callback the effect and the button both call.
  const [reloadToken, setReloadToken] = useState(0);
  const { t } = useTranslation();
  const { token } = useAuth();
  const { reportFailure } = useFailure();

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
        setLoading(true);
        setLoadError(null);
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
          // An inline state, not only a toast: the toast fades and the screen would still read as
          // "no faces found", which is a different and much more alarming statement than "this
          // could not be loaded". See LOOM_UI.md 11.2.
          setLoadError(reportFailure("loadFaceDetection", e).message);
        } finally {
          setLoading(false);
        }
      }
    };
    loadData();
  }, [token, reloadToken]);

  useEffect(() => {
    try {
      window.localStorage.setItem(CARD_SIZE_KEY, String(cardSize));
    } catch {
      // Private browsing, or a full quota. A size that does not survive a reload is a much smaller
      // problem than a screen that throws while rendering.
    }
  }, [cardSize]);

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
      // Inside the try, after the await. These four lines used to sit outside it, so a rejected
      // create cleared the form and closed the dialog exactly as an accepted one did - and the
      // user walked away believing in a person that does not exist.
      setNewPersonAlias("");
      setNewPersonFirstname("");
      setNewPersonLastname("");
      setCreatePersonOpen(false);
    } catch (e) {
      // The dialog stays open with the typed values intact, so the user can retry without
      // retyping - which is the whole reason the reset moved.
      reportFailure("createPerson", e);
    }
  };

  const handleCreateCluster = async () => {
    if (!newClusterName.trim() || !token) return;
    try {
      const resp = await apiCreateCluster(token, { name: newClusterName });
      setClusters(prev => [...prev, toUiCluster(resp)]);
      setNewClusterName("");
      setCreateClusterOpen(false);
    } catch (e) {
      reportFailure("createCluster", e);
    }
  };

  /**
   * Confirm that a cluster is a person.
   *
   * This used to mutate local state and nothing else, so the assignment vanished on reload. It now
   * goes through the confirmation endpoint, which sets the verdict and the person link in one
   * transaction; local state is updated from what the server actually stored.
   */
  const handleAssignCluster = async () => {
    const typed = assignPersonName.trim();
    if (!assignOpen || !typed || !token) return;
    const existing = persons.find(p => p.name.toLowerCase() === typed.toLowerCase());
    try {
      // `alias` when nobody matches: the confirm route creates the person and returns its uuid,
      // which is the only way to name somebody without a second round trip.
      const confirmed = await apiConfirmCluster(token, assignOpen,
        existing ? { personUuid: existing.id } : { alias: typed });
      setClusters(prev => prev.map(c => (c.id === assignOpen ? { ...c, ...toUiCluster(confirmed), faceIds: c.faceIds } : c)));
      const personId = confirmed.personUuid ?? existing?.id;
      if (personId) {
        setPersons(prev => (prev.some(p => p.id === personId)
          ? prev.map(p => (p.id === personId && !p.clusterIds.includes(assignOpen)
            ? { ...p, clusterIds: [...p.clusterIds, assignOpen] }
            : p))
          : [...prev, { id: personId, name: typed, description: "", avatarUrl: "", clusterIds: [assignOpen], createdAt: new Date().toISOString() }]));
      }
      setAssignOpen(null);
      setAssignPersonName("");
    } catch (e) {
      reportFailure("confirmCluster", e);
    }
  };

  /**
   * Two clusters were stacked: they are the same person.
   *
   * Three cases, and the difference matters because only one of them creates a person:
   *  - the target already has one, so the source joins it;
   *  - neither does, so the reviewer is asked for a name and both are confirmed to it;
   *  - the source has one and the target does not, so the target joins the source's.
   *
   * Nothing is updated optimistically - the state that lands is what the server returned, the same
   * rule `handleAssignCluster` follows. Attributing a face to a named person is biometric, and a
   * card that claims an attribution the server rejected is worse than no card.
   */
  const mergeInto = useCallback(async (personUuid: string, clusterIds: string[]) => {
    if (!token) return;
    for (const clusterId of clusterIds) {
      const confirmed = await apiConfirmCluster(token, clusterId, { personUuid });
      setClusters(prev => prev.map(c => (c.id === clusterId ? { ...c, ...toUiCluster(confirmed), faceIds: c.faceIds } : c)));
    }
    setPersons(prev => prev.map(p => (p.id === personUuid
      ? { ...p, clusterIds: [...new Set([...p.clusterIds, ...clusterIds])] }
      : p)));
  }, [token]);

  /** Take the person back off a cluster. The person row stays: it may hold other clusters. */
  const handleDetachPerson = useCallback(async (clusterId: string) => {
    if (!token) return;
    try {
      const detached = await apiDetachClusterPerson(token, clusterId);
      setClusters(prev => prev.map(c => (c.id === clusterId ? { ...c, ...toUiCluster(detached), faceIds: c.faceIds } : c)));
      setPersons(prev => prev.map(p => ({ ...p, clusterIds: p.clusterIds.filter(id => id !== clusterId) })));
    } catch (e) {
      reportFailure("detachClusterPerson", e);
    }
  }, [token, reportFailure]);

  /** A card dropped on a person's group: the same confirm, with the person already known. */
  const handleAssignToPerson = useCallback(async (clusterId: string, personId: string) => {
    try {
      await mergeInto(personId, [clusterId]);
    } catch (e) {
      reportFailure("confirmCluster", e);
    }
  }, [mergeInto, reportFailure]);

  const handleMergeClusters = useCallback(async (sourceId: string, targetId: string) => {
    if (!token) return;
    const source = clusters.find(c => c.id === sourceId);
    const target = clusters.find(c => c.id === targetId);
    if (!source || !target) return;
    const existingPerson = target.personId ?? source.personId;
    if (!existingPerson) {
      // Neither is attributed yet, so this merge has to name somebody. Deferred to the dialog
      // rather than inventing a placeholder name: an unnamed person is not a person.
      setMergePending({ sourceId, targetId });
      setMergeName("");
      return;
    }
    try {
      const toMove = [sourceId, targetId].filter(id => {
        const c = clusters.find(x => x.id === id);
        return c && c.personId !== existingPerson;
      });
      await mergeInto(existingPerson, toMove);
    } catch (e) {
      reportFailure("confirmCluster", e);
    }
  }, [token, clusters, mergeInto, reportFailure]);

  /** Confirm both clusters to a newly named person. */
  const handleMergeWithNewPerson = useCallback(async () => {
    if (!token || !mergePending || !mergeName.trim()) return;
    try {
      // The first confirm creates the person; the second links to the uuid it returned. Passing the
      // alias twice would create two people with the same name - or fail on the unique index.
      const first = await apiConfirmCluster(token, mergePending.targetId, { alias: mergeName.trim() });
      setClusters(prev => prev.map(c => (c.id === mergePending.targetId ? { ...c, ...toUiCluster(first), faceIds: c.faceIds } : c)));
      if (first.personUuid) {
        await mergeInto(first.personUuid, [mergePending.sourceId]);
        setPersons(prev => prev.some(p => p.id === first.personUuid)
          ? prev
          : [...prev, { id: first.personUuid!, name: mergeName.trim(), description: "", avatarUrl: "", clusterIds: [mergePending.sourceId, mergePending.targetId], createdAt: new Date().toISOString() }]);
      }
      setMergePending(null);
      setMergeName("");
    } catch (e) {
      reportFailure("confirmCluster", e);
    }
  }, [token, mergePending, mergeName, mergeInto, reportFailure]);

  /**
   * Enter submits, Escape is already MUI's.
   *
   * Every dialog on this screen is one field and two buttons, and none of them finished on Enter
   * — you typed a name and then had to go and find the mouse. Bound on the dialog rather than on
   * each field so it holds however the focus got there, and guarded on `disabled` so Enter cannot
   * do what the greyed-out button refuses to.
   */
  const submitOnEnter = (enabled: boolean, submit: () => void) => (e: React.KeyboardEvent) => {
    if (e.key !== "Enter" || e.shiftKey) return;
    // An open Autocomplete popup owns Enter: it is picking an option, not ending the dialog.
    if ((e.target as HTMLElement).getAttribute?.("aria-expanded") === "true") return;
    e.preventDefault();
    if (enabled) submit();
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
        {/* Thumbnail size. Beside the search box because it is the same kind of control: neither
            changes what is in the list, both change what you can find in it.

            A slider rather than the three named buttons that were here, because the question is
            "how big is a face" and the answer is a magnitude. The scale is anchored at the
            bottom: step 0 is the density the grid is designed around and every other step is
            larger, which is how it was asked for. */}
        {activeSection === "clusters" && (
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexShrink: 0 }}
            data-testid="facedetection-card-size" data-step={cardSize}>
            <Tooltip title={t("faceDetection.size.smaller")}>
              <PhotoSizeSelectSmallOutlined sx={{ fontSize: 15, color: tokens.text.tertiary }} />
            </Tooltip>
            <Slider
              size="small"
              value={cardSize}
              min={CLUSTER_SIZE_MIN}
              max={CLUSTER_SIZE_MAX}
              step={1}
              marks
              valueLabelDisplay="auto"
              valueLabelFormat={value => `${CLUSTER_SIZE_STEPS[clampClusterSize(value)].crop}px`}
              onChange={(_, value) => setCardSize(clampClusterSize(Array.isArray(value) ? value[0] : value))}
              aria-label={t("faceDetection.size.thumbnails")}
              data-testid="facedetection-card-size-slider"
              sx={{ width: 110, color: tokens.primary.main, "& .MuiSlider-thumb": { width: 12, height: 12 } }}
            />
            <Tooltip title={t("faceDetection.size.larger")}>
              <PhotoSizeSelectActualOutlined sx={{ fontSize: 17, color: tokens.text.tertiary }} />
            </Tooltip>
          </Box>
        )}
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
        {loading && (
          <Box sx={{ display: "flex", justifyContent: "center", py: 6 }} data-testid="face-detection-loading">
            <CircularProgress size={28} />
          </Box>
        )}
        {!loading && loadError && <LoadFailure message={loadError} onRetry={() => setReloadToken(n => n + 1)} testId="face-detection-load-failure" />}
        {!loading && !loadError && activeSection === "clusters" && (
          <ClustersPanel
            clusters={filteredClusters}
            persons={persons}
            onAssignCluster={(clusterId) => {
              // Prefill with whoever the cluster already belongs to, so re-opening the dialog on an
              // attributed card shows the attribution instead of an empty box.
              const current = clusters.find(c => c.id === clusterId);
              setAssignOpen(clusterId);
              setAssignPersonName(persons.find(p => p.id === current?.personId)?.name ?? "");
            }}
            onClusterDeleted={(id) => setClusters(prev => prev.filter(c => c.id !== id))}
            onClusterUpdated={(updated) => setClusters(prev => prev.map(c => c.id === updated.id ? updated : c))}
            onMergeClusters={handleMergeClusters}
            onDetachPerson={handleDetachPerson}
            onAssignToPerson={handleAssignToPerson}
            size={cardSize}
          />
        )}
        {!loading && !loadError && activeSection === "persons" && (
          <PersonsPanel
            persons={filteredPersons}
            clusters={clusters}
            onPersonDeleted={(id) => setPersons(prev => prev.filter(p => p.id !== id))}
            onPersonUpdated={(updated) => setPersons(prev => prev.map(p => p.id === updated.id ? updated : p))}
          />
        )}
      </Box>

      {/* Create Cluster Dialog */}
      <Dialog open={createClusterOpen} onClose={() => setCreateClusterOpen(false)} maxWidth="xs" fullWidth
        onKeyDown={submitOnEnter(!!newClusterName.trim(), handleCreateCluster)}>
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
      <Dialog open={createPersonOpen} onClose={() => setCreatePersonOpen(false)} maxWidth="xs" fullWidth
        onKeyDown={submitOnEnter(!!newPersonAlias.trim(), handleCreatePerson)}>
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
        onKeyDown={submitOnEnter(!!assignPersonName.trim(), handleAssignCluster)}
        PaperProps={{ "data-testid": "facedetection-assign-dialog" } as React.ComponentProps<typeof Dialog>["PaperProps"]}>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("faceDetection.dialog.assign")}</DialogTitle>
        <DialogContent sx={{ pt: "8px !important" }}>
          {/* A type-to-find box, not a dropdown. This is reached by double-clicking a card in a
              grid of a hundred, and scrolling a select to the right name is the slow half of a
              review pass. `freeSolo` because the person you are looking at may not exist yet, and
              having to leave the dialog to create them is the other slow half. */}
          <Autocomplete
            freeSolo
            options={persons.map(p => p.name)}
            inputValue={assignPersonName}
            onInputChange={(_, value, reason) => { if (reason !== "reset") setAssignPersonName(value); }}
            onChange={(_, value) => { if (typeof value === "string") setAssignPersonName(value); }}
            size="small"
            renderInput={params => (
              // autoFocus, because the dialog opens on a double-click and the next thing the
              // reviewer does is type. Landing on the Cancel button instead is the friction this
              // whole control exists to remove.
              <TextField {...params} autoFocus label={t("faceDetection.dialog.selectPerson")}
                inputProps={{ ...params.inputProps, "data-testid": "facedetection-assign-input" }} />
            )}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAssignOpen(null)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleAssignCluster} variant="contained" size="small" data-testid="facedetection-assign-save" disabled={!assignPersonName.trim()}>{t("faceDetection.button.assign")}</Button>
        </DialogActions>
      </Dialog>

      {/* Naming the person a stack of two unattributed clusters belongs to. */}
      <Dialog open={!!mergePending} onClose={() => setMergePending(null)} maxWidth="xs" fullWidth
        onKeyDown={submitOnEnter(!!mergeName.trim(), handleMergeWithNewPerson)}
        PaperProps={{ "data-testid": "facedetection-merge-dialog" } as React.ComponentProps<typeof Dialog>["PaperProps"]}>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>{t("faceDetection.label.mergePrompt")}</DialogTitle>
        <DialogContent sx={{ pt: "8px !important" }}>
          {/* A plain field, deliberately. This dialog only opens when *neither* cluster is
              attributed, so there is by definition nobody to suggest; and an Autocomplete popup
              in a dialog this short opens straight over the Save button underneath it. Picking an
              existing person is what the assign dialog above is for. */}
          <TextField
            label={t("faceDetection.label.name")}
            value={mergeName}
            onChange={e => setMergeName(e.target.value)}
            size="small"
            fullWidth
            autoFocus
            inputProps={{ "data-testid": "facedetection-merge-name" }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setMergePending(null)} size="small">{t("faceDetection.button.cancel")}</Button>
          <Button onClick={handleMergeWithNewPerson} variant="contained" size="small"
            data-testid="facedetection-merge-save" disabled={!mergeName.trim()}>
            {t("faceDetection.button.assign")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
