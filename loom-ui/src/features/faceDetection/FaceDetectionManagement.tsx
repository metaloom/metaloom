import React, { useEffect, useState } from "react";
import {
  Box, Typography, Paper, Avatar, Chip, IconButton, Tooltip,
  TextField, InputAdornment, Button, Dialog, DialogTitle,
  DialogContent, DialogActions, FormControl, Select, MenuItem,
  SelectChangeEvent, Divider,
} from "@mui/material";
import {
  SearchOutlined, FaceOutlined, GroupWorkOutlined, PersonOutlined,
  AddOutlined, LinkOutlined, EditOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { FaceCluster, Person } from "../../types";
import { mockFaceDetectionService } from "../../mock/services";

export default function FaceDetectionManagement() {
  const [clusters, setClusters] = useState<FaceCluster[]>([]);
  const [persons, setPersons] = useState<Person[]>([]);
  const [query, setQuery] = useState("");
  const [activeSection, setActiveSection] = useState<"clusters" | "persons">("clusters");
  const [createPersonOpen, setCreatePersonOpen] = useState(false);
  const [newPersonName, setNewPersonName] = useState("");
  const [newPersonDesc, setNewPersonDesc] = useState("");
  const [assignOpen, setAssignOpen] = useState<string | null>(null); // clusterId
  const [assignPersonId, setAssignPersonId] = useState("");

  useEffect(() => {
    Promise.all([
      mockFaceDetectionService.getAllClusters(),
      mockFaceDetectionService.getAllPersons(),
    ]).then(([c, p]) => {
      setClusters(c);
      setPersons(p);
    });
  }, []);

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
    if (!newPersonName.trim()) return;
    const p = await mockFaceDetectionService.createPerson(newPersonName, newPersonDesc);
    setPersons(prev => [...prev, p]);
    setNewPersonName("");
    setNewPersonDesc("");
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
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface }}>
        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", mb: 0.5 }}>
          Face Detection
        </Typography>
        <Typography variant="caption" color="text.secondary">
          Manage face clusters and person database
        </Typography>
      </Box>

      {/* Toolbar */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 1, alignItems: "center" }}>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="Search clusters, persons…"
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
            label="Clusters"
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
            label="Persons"
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
            Add Person
          </Button>
        )}
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2.5 }}>
        {activeSection === "clusters" && (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 2 }}>
            {filteredClusters.map(cluster => {
              const person = cluster.personId ? persons.find(p => p.id === cluster.personId) : undefined;
              return (
                <Paper
                  key={cluster.id}
                  elevation={0}
                  sx={{
                    bgcolor: tokens.bg.elevated,
                    border: `1px solid ${tokens.border.subtle}`,
                    borderRadius: tokens.radius.lg,
                    overflow: "hidden",
                  }}
                >
                  {/* Cluster header */}
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
                    <Avatar src={cluster.representativeThumbnailUrl} sx={{ width: 40, height: 40 }} />
                    <Box sx={{ flex: 1 }}>
                      <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.85rem", color: tokens.text.primary }}>
                        {cluster.label}
                      </Typography>
                      <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem" }}>
                        {cluster.faceIds.length} faces
                      </Typography>
                    </Box>
                    {person ? (
                      <Chip label={person.name} size="small" avatar={<Avatar src={person.avatarUrl} />} sx={{ height: 24, fontSize: "0.72rem", bgcolor: `${tokens.accent.green}18`, border: `1px solid ${tokens.accent.green}44` }} />
                    ) : (
                      <Tooltip title="Assign to person">
                        <IconButton size="small" onClick={() => { setAssignOpen(cluster.id); setAssignPersonId(""); }}>
                          <LinkOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
                        </IconButton>
                      </Tooltip>
                    )}
                  </Box>
                  {/* Face thumbnails grid */}
                  <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", p: 1.5 }}>
                    {cluster.faceIds.slice(0, 8).map(fid => (
                      <Box key={fid} sx={{ width: 44, height: 44, borderRadius: tokens.radius.sm, overflow: "hidden", border: `2px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.overlay }}>
                        <img src={`https://i.pravatar.cc/80?u=${fid}`} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
                      </Box>
                    ))}
                    {cluster.faceIds.length > 8 && (
                      <Box sx={{ width: 44, height: 44, borderRadius: tokens.radius.sm, bgcolor: tokens.bg.overlay, display: "flex", alignItems: "center", justifyContent: "center" }}>
                        <Typography variant="caption" sx={{ fontSize: "0.7rem", color: tokens.text.tertiary }}>+{cluster.faceIds.length - 8}</Typography>
                      </Box>
                    )}
                  </Box>
                </Paper>
              );
            })}
            {filteredClusters.length === 0 && (
              <Box sx={{ gridColumn: "1 / -1", display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                <GroupWorkOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                <Typography variant="body2" color="text.secondary">No clusters found</Typography>
              </Box>
            )}
          </Box>
        )}

        {activeSection === "persons" && (
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 2 }}>
            {filteredPersons.map(person => {
              const personClusters = clusters.filter(c => person.clusterIds.includes(c.id));
              return (
                <Paper
                  key={person.id}
                  elevation={0}
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
                      <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.88rem", color: tokens.text.primary }}>
                        {person.name}
                      </Typography>
                      <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.75rem", display: "block" }}>
                        {person.description}
                      </Typography>
                      <Box sx={{ display: "flex", gap: 0.5, mt: 0.5 }}>
                        <Chip label={`${person.clusterIds.length} clusters`} size="small" sx={{ height: 18, fontSize: "0.62rem", bgcolor: tokens.bg.overlay }} />
                        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.65rem", alignSelf: "center" }}>
                          since {new Date(person.createdAt).toLocaleDateString()}
                        </Typography>
                      </Box>
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
                          sx={{ height: 24, fontSize: "0.7rem", bgcolor: tokens.bg.overlay }}
                        />
                      ))}
                    </Box>
                  )}
                </Paper>
              );
            })}
            {filteredPersons.length === 0 && (
              <Box sx={{ gridColumn: "1 / -1", display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                <PersonOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                <Typography variant="body2" color="text.secondary">No persons found</Typography>
              </Box>
            )}
          </Box>
        )}
      </Box>

      {/* Create Person Dialog */}
      <Dialog open={createPersonOpen} onClose={() => setCreatePersonOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>Add Person</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: "8px !important" }}>
          <TextField
            label="Name"
            value={newPersonName}
            onChange={e => setNewPersonName(e.target.value)}
            size="small"
            fullWidth
            autoFocus
          />
          <TextField
            label="Description"
            value={newPersonDesc}
            onChange={e => setNewPersonDesc(e.target.value)}
            size="small"
            fullWidth
            multiline
            rows={2}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreatePersonOpen(false)} size="small">Cancel</Button>
          <Button onClick={handleCreatePerson} variant="contained" size="small" disabled={!newPersonName.trim()}>Create</Button>
        </DialogActions>
      </Dialog>

      {/* Assign Cluster to Person Dialog */}
      <Dialog open={!!assignOpen} onClose={() => setAssignOpen(null)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontSize: "0.95rem", fontWeight: 700 }}>Assign Cluster to Person</DialogTitle>
        <DialogContent sx={{ pt: "8px !important" }}>
          <FormControl fullWidth size="small">
            <Select
              value={assignPersonId}
              onChange={(e: SelectChangeEvent) => setAssignPersonId(e.target.value)}
              displayEmpty
              sx={{ fontSize: "0.85rem" }}
            >
              <MenuItem value="" disabled>Select a person…</MenuItem>
              {persons.map(p => (
                <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>
              ))}
            </Select>
          </FormControl>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAssignOpen(null)} size="small">Cancel</Button>
          <Button onClick={handleAssignCluster} variant="contained" size="small" disabled={!assignPersonId}>Assign</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
