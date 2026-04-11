import React, { useState } from "react";
import {
  Box, Typography, Paper, Chip, TextField, InputAdornment,
  IconButton, Tooltip, Button, Avatar, Divider,
  Dialog, DialogTitle, DialogContent, DialogActions, Stack,
  FormControl, InputLabel, Select, MenuItem, SelectChangeEvent,
} from "@mui/material";
import {
  SearchOutlined, AutoAwesomeOutlined, AddOutlined,
  PlayArrowOutlined, EditOutlined, DeleteOutlineOutlined,
  CloseOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { ASSETS } from "../../mock/data";
import { useTranslation } from "react-i18next";

interface VisionPrompt {
  id: string;
  name: string;
  model: string;
  prompt: string;
  reasoningEffort: "low" | "medium" | "high";
  outputFormat: "text" | "json";
  maxTokens: number;
  createdAt: string;
}

interface LLMResult {
  id: string;
  promptId: string;
  assetId: string;
  output: string;
  tokenCount: number;
  status: "success" | "failed" | "pending";
  createdAt: string;
}

const MOCK_PROMPTS: VisionPrompt[] = [
  { id: "vp1", name: "Scene Description", model: "gpt-4o", prompt: "Describe this scene in detail. Include objects, people, setting, lighting, and mood.", reasoningEffort: "medium", outputFormat: "text", maxTokens: 512, createdAt: new Date(Date.now() - 86400000 * 7).toISOString() },
  { id: "vp2", name: "Object Inventory", model: "gpt-4o", prompt: "List all visible objects in this image as a JSON array of {label, description, approximate_position}.", reasoningEffort: "high", outputFormat: "json", maxTokens: 1024, createdAt: new Date(Date.now() - 86400000 * 3).toISOString() },
  { id: "vp3", name: "Content Moderation", model: "gpt-4o-mini", prompt: "Analyze this image for content moderation. Flag any NSFW, violent, or inappropriate content. Return JSON {safe: boolean, flags: string[]}.", reasoningEffort: "low", outputFormat: "json", maxTokens: 256, createdAt: new Date(Date.now() - 86400000).toISOString() },
  { id: "vp4", name: "Brand Detection", model: "gpt-4o", prompt: "Identify any brand logos, product placements, or branded items in this image. Return JSON array.", reasoningEffort: "high", outputFormat: "json", maxTokens: 512, createdAt: new Date(Date.now() - 86400000 * 2).toISOString() },
];

const MOCK_RESULTS: LLMResult[] = [
  { id: "lr1", promptId: "vp1", assetId: "a1", output: "A professional video scene showing a corporate presentation. Well-lit indoor setting with modern furniture.", tokenCount: 87, status: "success", createdAt: new Date(Date.now() - 3600000 * 4).toISOString() },
  { id: "lr2", promptId: "vp1", assetId: "a3", output: "Product photography of a hero shot. Clean studio background with dramatic side lighting.", tokenCount: 62, status: "success", createdAt: new Date(Date.now() - 3600000 * 3).toISOString() },
  { id: "lr3", promptId: "vp2", assetId: "a4", output: '[{"label":"dog","description":"Golden retriever sitting on grass"},{"label":"bench","description":"Wooden park bench"}]', tokenCount: 124, status: "success", createdAt: new Date(Date.now() - 3600000 * 2).toISOString() },
  { id: "lr4", promptId: "vp3", assetId: "a5", output: '{"safe":true,"flags":[]}', tokenCount: 18, status: "success", createdAt: new Date(Date.now() - 3600000).toISOString() },
  { id: "lr5", promptId: "vp2", assetId: "a7", output: "", tokenCount: 0, status: "failed", createdAt: new Date(Date.now() - 1800000).toISOString() },
  { id: "lr6", promptId: "vp4", assetId: "a1", output: "", tokenCount: 0, status: "pending", createdAt: new Date().toISOString() },
];

export default function LLMDetectionManagement() {
  const [query, setQuery] = useState("");
  const [activeSection, setActiveSection] = useState<"prompts" | "results">("prompts");
  const [prompts, setPrompts] = useState<VisionPrompt[]>(MOCK_PROMPTS);
  const { t } = useTranslation();

  // Create dialog state
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState("");
  const [newModel, setNewModel] = useState("gpt-4o");
  const [newPrompt, setNewPrompt] = useState("");
  const [newEffort, setNewEffort] = useState<"low" | "medium" | "high">("medium");
  const [newFormat, setNewFormat] = useState<"text" | "json">("text");
  const [newMaxTokens, setNewMaxTokens] = useState(512);
  const [newDescription, setNewDescription] = useState("");

  const handleCreatePrompt = () => {
    if (!newName.trim() || !newPrompt.trim()) return;
    const prompt: VisionPrompt = {
      id: `vp_${Date.now()}`,
      name: newName.trim(),
      model: newModel,
      prompt: newPrompt.trim(),
      reasoningEffort: newEffort,
      outputFormat: newFormat,
      maxTokens: newMaxTokens,
      createdAt: new Date().toISOString(),
    };
    setPrompts(prev => [prompt, ...prev]);
    setCreateOpen(false);
    setNewName(""); setNewModel("gpt-4o"); setNewPrompt(""); setNewEffort("medium"); setNewFormat("text"); setNewMaxTokens(512); setNewDescription("");
  };

  const filteredPrompts = prompts.filter(p =>
    !query.trim() || p.name.toLowerCase().includes(query.toLowerCase()) || p.model.toLowerCase().includes(query.toLowerCase())
  );

  const filteredResults = MOCK_RESULTS.filter(r => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    const prompt = MOCK_PROMPTS.find(p => p.id === r.promptId);
    const asset = ASSETS.find(a => a.id === r.assetId);
    return (prompt?.name.toLowerCase().includes(q) ?? false) || (asset?.name.toLowerCase().includes(q) ?? false);
  });

  const effortColor: Record<string, string> = { low: tokens.accent.green, medium: tokens.accent.amber, high: tokens.accent.red };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", overflow: "hidden" }}>
      {/* Toolbar */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 1, alignItems: "center" }}>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t("llmDetection.search.placeholder")}
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
          <Chip label={t("llmDetection.chip.prompts")} size="small" icon={<AutoAwesomeOutlined sx={{ fontSize: 14 }} />}
            onClick={() => setActiveSection("prompts")}
            sx={{ bgcolor: activeSection === "prompts" ? tokens.primary.subtle : tokens.bg.elevated, color: activeSection === "prompts" ? tokens.primary.main : tokens.text.secondary, border: `1px solid ${activeSection === "prompts" ? tokens.primary.main : "transparent"}`, fontWeight: activeSection === "prompts" ? 600 : 400 }} />
          <Chip label={t("llmDetection.chip.results")} size="small" icon={<PlayArrowOutlined sx={{ fontSize: 14 }} />}
            onClick={() => setActiveSection("results")}
            sx={{ bgcolor: activeSection === "results" ? tokens.primary.subtle : tokens.bg.elevated, color: activeSection === "results" ? tokens.primary.main : tokens.text.secondary, border: `1px solid ${activeSection === "results" ? tokens.primary.main : "transparent"}`, fontWeight: activeSection === "results" ? 600 : 400 }} />
        </Box>
        {activeSection === "prompts" && (
          <Button size="small" startIcon={<AddOutlined sx={{ fontSize: 14 }} />} onClick={() => setCreateOpen(true)} sx={{ ml: "auto", textTransform: "none", fontSize: "0.78rem" }}>
            {t("llmDetection.button.newPrompt")}
          </Button>
        )}
      </Box>

      {/* Create Prompt Dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth PaperProps={{ sx: { bgcolor: tokens.bg.surface, backgroundImage: "none", borderRadius: tokens.radius.lg, border: `1px solid ${tokens.border.subtle}` } }}>
        <DialogTitle sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", pb: 1 }}>
          <Typography fontWeight={700} sx={{ fontSize: "1rem" }}>{t("llmDetection.dialog.title")}</Typography>
          <IconButton size="small" onClick={() => setCreateOpen(false)}><CloseOutlined sx={{ fontSize: 16 }} /></IconButton>
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 0.5 }}>
            <TextField label={t("llmDetection.label.name")} placeholder={t("llmDetection.label.namePlaceholder")} value={newName} onChange={e => setNewName(e.target.value)} size="small" fullWidth autoFocus />
            <FormControl size="small" fullWidth>
              <InputLabel>{t("llmDetection.label.model")}</InputLabel>
              <Select value={newModel} label={t("llmDetection.label.model")} onChange={(e: SelectChangeEvent) => setNewModel(e.target.value)}>
                <MenuItem value="gpt-4o">gpt-4o</MenuItem>
                <MenuItem value="gpt-4o-mini">gpt-4o-mini</MenuItem>
                <MenuItem value="gpt-4.1">gpt-4.1</MenuItem>
                <MenuItem value="gpt-4.1-mini">gpt-4.1-mini</MenuItem>
                <MenuItem value="claude-sonnet-4-20250514">claude-sonnet-4-20250514</MenuItem>
                <MenuItem value="gemini-2.5-flash">gemini-2.5-flash</MenuItem>
              </Select>
            </FormControl>
            <TextField label={t("llmDetection.label.prompt")} placeholder={t("llmDetection.label.promptPlaceholder")} value={newPrompt} onChange={e => setNewPrompt(e.target.value)} size="small" fullWidth multiline minRows={3} maxRows={6} />
            <Box sx={{ display: "flex", gap: 1.5 }}>
              <FormControl size="small" sx={{ flex: 1 }}>
                <InputLabel>{t("llmDetection.label.reasoningEffort")}</InputLabel>
                <Select value={newEffort} label={t("llmDetection.label.reasoningEffort")} onChange={(e: SelectChangeEvent) => setNewEffort(e.target.value as "low" | "medium" | "high")}>
                  <MenuItem value="low">{t("llmDetection.effort.low")}</MenuItem>
                  <MenuItem value="medium">{t("llmDetection.effort.medium")}</MenuItem>
                  <MenuItem value="high">{t("llmDetection.effort.high")}</MenuItem>
                </Select>
              </FormControl>
              <FormControl size="small" sx={{ flex: 1 }}>
                <InputLabel>{t("llmDetection.label.outputFormat")}</InputLabel>
                <Select value={newFormat} label={t("llmDetection.label.outputFormat")} onChange={(e: SelectChangeEvent) => setNewFormat(e.target.value as "text" | "json")}>
                  <MenuItem value="text">{t("llmDetection.format.text")}</MenuItem>
                  <MenuItem value="json">{t("llmDetection.format.json")}</MenuItem>
                </Select>
              </FormControl>
            </Box>
            <TextField label={t("llmDetection.label.maxTokens")} type="number" value={newMaxTokens} onChange={e => setNewMaxTokens(Math.max(1, parseInt(e.target.value) || 1))} size="small" fullWidth inputProps={{ min: 1, max: 16384 }} />
            <TextField label={t("llmDetection.label.description")} placeholder={t("llmDetection.label.descriptionPlaceholder")} value={newDescription} onChange={e => setNewDescription(e.target.value)} size="small" fullWidth />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2, gap: 1 }}>
          <Button size="small" onClick={() => setCreateOpen(false)}>{t("llmDetection.button.cancel")}</Button>
          <Button size="small" variant="contained" onClick={handleCreatePrompt} disabled={!newName.trim() || !newPrompt.trim()} startIcon={<AutoAwesomeOutlined sx={{ fontSize: 14 }} />}>{t("llmDetection.button.create")}</Button>
        </DialogActions>
      </Dialog>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2 }}>
        {activeSection === "prompts" && (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            {filteredPrompts.map(p => (
              <Paper key={p.id} elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, p: 2 }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
                  <AutoAwesomeOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
                  <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.9rem", flex: 1 }}>{p.name}</Typography>
                  <Chip label={p.model} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: tokens.bg.overlay }} />
                  <Chip label={p.outputFormat.toUpperCase()} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${tokens.accent.blue}18`, color: tokens.accent.blue }} />
                  <Chip label={t("llmDetection.chip.effort", { effort: p.reasoningEffort })} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${effortColor[p.reasoningEffort]}18`, color: effortColor[p.reasoningEffort] }} />
                </Box>
                <Typography variant="body2" sx={{ color: tokens.text.secondary, fontSize: "0.8rem", lineHeight: 1.5, mb: 1 }}>{p.prompt}</Typography>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                  <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>{t("llmDetection.label.maxTokensValue", { count: p.maxTokens })}</Typography>
                  <Typography variant="caption" color="text.tertiary" sx={{ fontSize: "0.7rem", ml: "auto" }}>Created {new Date(p.createdAt).toLocaleDateString()}</Typography>
                  <Tooltip title={t("llmDetection.tooltip.edit")}><IconButton size="small"><EditOutlined sx={{ fontSize: 14 }} /></IconButton></Tooltip>
                  <Tooltip title={t("llmDetection.tooltip.run")}><IconButton size="small"><PlayArrowOutlined sx={{ fontSize: 14, color: tokens.accent.green }} /></IconButton></Tooltip>
                </Box>
              </Paper>
            ))}
            {filteredPrompts.length === 0 && (
              <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                <AutoAwesomeOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                <Typography variant="body2" color="text.secondary">{t("llmDetection.empty.prompts")}</Typography>
              </Box>
            )}
          </Box>
        )}

        {activeSection === "results" && (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75 }}>
            {filteredResults.map(r => {
              const prompt = MOCK_PROMPTS.find(p => p.id === r.promptId);
              const asset = ASSETS.find(a => a.id === r.assetId);
              const statusColor = r.status === "success" ? tokens.accent.green : r.status === "failed" ? tokens.accent.red : tokens.accent.amber;
              return (
                <Paper key={r.id} elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, p: 1.5, display: "flex", gap: 1.5, alignItems: "flex-start" }}>
                  <Avatar variant="rounded" src={asset?.thumbnailUrl} sx={{ width: 48, height: 48 }} />
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mb: 0.5 }}>
                      <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{asset?.name ?? r.assetId}</Typography>
                      <Chip label={prompt?.name ?? r.promptId} size="small" sx={{ height: 16, fontSize: "0.6rem", bgcolor: tokens.bg.overlay }} />
                      <Chip label={r.status} size="small" sx={{ height: 16, fontSize: "0.6rem", bgcolor: `${statusColor}18`, color: statusColor }} />
                    </Box>
                    {r.output && (
                      <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.72rem", display: "block", fontFamily: r.output.startsWith("[") || r.output.startsWith("{") ? "monospace" : "inherit", whiteSpace: "pre-wrap", lineHeight: 1.5 }}>
                        {r.output.slice(0, 200)}{r.output.length > 200 ? "…" : ""}
                      </Typography>
                    )}
                    <Box sx={{ display: "flex", gap: 1, mt: 0.5 }}>
                      <Typography variant="caption" color="text.tertiary" sx={{ fontSize: "0.65rem" }}>{r.tokenCount} tokens</Typography>
                      <Typography variant="caption" color="text.tertiary" sx={{ fontSize: "0.65rem" }}>{new Date(r.createdAt).toLocaleString()}</Typography>
                    </Box>
                  </Box>
                </Paper>
              );
            })}
            {filteredResults.length === 0 && (
              <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 6, gap: 1 }}>
                <PlayArrowOutlined sx={{ fontSize: 36, color: tokens.text.tertiary }} />
                <Typography variant="body2" color="text.secondary">{t("llmDetection.empty.results")}</Typography>
              </Box>
            )}
          </Box>
        )}
      </Box>
    </Box>
  );
}
