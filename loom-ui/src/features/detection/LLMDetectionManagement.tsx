import React, { useState } from "react";
import {
  Alert, AlertTitle, Box, Typography, Paper, Chip, TextField, InputAdornment,
  IconButton, Tooltip, Button, Avatar,
} from "@mui/material";
import {
  SearchOutlined, AutoAwesomeOutlined, AddOutlined,
  PlayArrowOutlined, EditOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { useTranslation } from "react-i18next";

/**
 * The LLM detection tab - a **preview** with sample data.
 *
 * There is no backend for vision prompts or their results yet. The screen is kept so the idea stays
 * visible, but it says so up front: a banner names the data as samples, and every action that
 * would need a server (create, edit, run) is disabled with a tooltip explaining why. It used to
 * render the same samples as if they had come from the server and offered buttons that did nothing,
 * which is worse than no screen at all.
 */

type Effort = "low" | "medium" | "high";
type ResultStatus = "success" | "failed" | "pending";

interface VisionPrompt {
  id: string;
  name: string;
  model: string;
  prompt: string;
  reasoningEffort: Effort;
  outputFormat: "text" | "json";
  maxTokens: number;
  createdAt: string;
}

interface LLMResult {
  id: string;
  promptId: string;
  /** A sample file name. Deliberately not an asset uuid: there is no real asset behind it. */
  assetName: string;
  output: string;
  tokenCount: number;
  status: ResultStatus;
  createdAt: string;
}

const SAMPLE_PROMPTS: VisionPrompt[] = [
  { id: "vp1", name: "Scene Description", model: "gpt-4o", prompt: "Describe this scene in detail. Include objects, people, setting, lighting, and mood.", reasoningEffort: "medium", outputFormat: "text", maxTokens: 512, createdAt: new Date(Date.now() - 86400000 * 7).toISOString() },
  { id: "vp2", name: "Object Inventory", model: "gpt-4o", prompt: "List all visible objects in this image as a JSON array of {label, description, approximate_position}.", reasoningEffort: "high", outputFormat: "json", maxTokens: 1024, createdAt: new Date(Date.now() - 86400000 * 3).toISOString() },
  { id: "vp3", name: "Content Moderation", model: "gpt-4o-mini", prompt: "Analyze this image for content moderation. Flag any NSFW, violent, or inappropriate content. Return JSON {safe: boolean, flags: string[]}.", reasoningEffort: "low", outputFormat: "json", maxTokens: 256, createdAt: new Date(Date.now() - 86400000).toISOString() },
  { id: "vp4", name: "Brand Detection", model: "gpt-4o", prompt: "Identify any brand logos, product placements, or branded items in this image. Return JSON array.", reasoningEffort: "high", outputFormat: "json", maxTokens: 512, createdAt: new Date(Date.now() - 86400000 * 2).toISOString() },
];

const SAMPLE_RESULTS: LLMResult[] = [
  { id: "lr1", promptId: "vp1", assetName: "sample-presentation.mp4", output: "A professional video scene showing a corporate presentation. Well-lit indoor setting with modern furniture.", tokenCount: 87, status: "success", createdAt: new Date(Date.now() - 3600000 * 4).toISOString() },
  { id: "lr2", promptId: "vp1", assetName: "sample-product.jpg", output: "Product photography of a hero shot. Clean studio background with dramatic side lighting.", tokenCount: 62, status: "success", createdAt: new Date(Date.now() - 3600000 * 3).toISOString() },
  { id: "lr3", promptId: "vp2", assetName: "sample-park.jpg", output: '[{"label":"dog","description":"Golden retriever sitting on grass"},{"label":"bench","description":"Wooden park bench"}]', tokenCount: 124, status: "success", createdAt: new Date(Date.now() - 3600000 * 2).toISOString() },
  { id: "lr4", promptId: "vp3", assetName: "sample-street.jpg", output: '{"safe":true,"flags":[]}', tokenCount: 18, status: "success", createdAt: new Date(Date.now() - 3600000).toISOString() },
  { id: "lr5", promptId: "vp2", assetName: "sample-crowd.jpg", output: "", tokenCount: 0, status: "failed", createdAt: new Date(Date.now() - 1800000).toISOString() },
  { id: "lr6", promptId: "vp4", assetName: "sample-presentation.mp4", output: "", tokenCount: 0, status: "pending", createdAt: new Date().toISOString() },
];

export default function LLMDetectionManagement() {
  const [query, setQuery] = useState("");
  const [activeSection, setActiveSection] = useState<"prompts" | "results">("prompts");
  const { t } = useTranslation();
  const disabledReason = t("llmDetection.preview.disabled");

  const filteredPrompts = SAMPLE_PROMPTS.filter(p =>
    !query.trim() || p.name.toLowerCase().includes(query.toLowerCase()) || p.model.toLowerCase().includes(query.toLowerCase())
  );

  const filteredResults = SAMPLE_RESULTS.filter(r => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    const prompt = SAMPLE_PROMPTS.find(p => p.id === r.promptId);
    return (prompt?.name.toLowerCase().includes(q) ?? false) || r.assetName.toLowerCase().includes(q);
  });

  const effortColor: Record<Effort, string> = { low: tokens.accent.green, medium: tokens.accent.amber, high: tokens.accent.red };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", overflow: "hidden" }}>
      <Alert severity="warning" data-testid="llmdetection-preview-notice" sx={{ borderRadius: 0 }}>
        <AlertTitle>{t("llmDetection.preview.title")}</AlertTitle>
        {t("llmDetection.preview.notice")}
      </Alert>

      {/* Toolbar */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 1, alignItems: "center" }}>
        <TextField
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={t("llmDetection.search.placeholder")}
          size="small"
          data-testid="llmdetection-search"
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
          // A disabled button fires no pointer events, so the tooltip needs a live wrapper.
          <Tooltip title={disabledReason}>
            <Box component="span" sx={{ ml: "auto" }}>
              <Button size="small" disabled data-testid="llmdetection-new-prompt" startIcon={<AddOutlined sx={{ fontSize: 14 }} />} sx={{ textTransform: "none", fontSize: "0.78rem" }}>
                {t("llmDetection.button.newPrompt")}
              </Button>
            </Box>
          </Tooltip>
        )}
      </Box>

      {/* Content */}
      <Box sx={{ flex: 1, overflow: "auto", p: 2 }}>
        {activeSection === "prompts" && (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            {filteredPrompts.map(p => (
              <Paper key={p.id} elevation={0} data-testid="llmdetection-prompt" sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, p: 2 }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
                  <AutoAwesomeOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
                  <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.9rem", flex: 1 }}>{p.name}</Typography>
                  <Chip label={t("llmDetection.chip.sample")} size="small" variant="outlined" sx={{ height: 18, fontSize: "0.65rem" }} />
                  <Chip label={p.model} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: tokens.bg.overlay }} />
                  <Chip label={p.outputFormat.toUpperCase()} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${tokens.accent.blue}18`, color: tokens.accent.blue }} />
                  <Chip label={t("llmDetection.chip_template.effort", { effort: t(`llmDetection.effort.${p.reasoningEffort}`) })} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${effortColor[p.reasoningEffort]}18`, color: effortColor[p.reasoningEffort] }} />
                </Box>
                <Typography variant="body2" sx={{ color: tokens.text.secondary, fontSize: "0.8rem", lineHeight: 1.5, mb: 1 }}>{p.prompt}</Typography>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                  <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>{t("llmDetection.label.maxTokensValue", { count: p.maxTokens })}</Typography>
                  <Typography variant="caption" color="text.tertiary" sx={{ fontSize: "0.7rem", ml: "auto" }}>{t("llmDetection.label.created", { date: new Date(p.createdAt).toLocaleDateString() })}</Typography>
                  <Tooltip title={disabledReason}>
                    <span>
                      <IconButton size="small" disabled aria-label={t("llmDetection.tooltip.edit")} data-testid="llmdetection-edit"><EditOutlined sx={{ fontSize: 14 }} /></IconButton>
                    </span>
                  </Tooltip>
                  <Tooltip title={disabledReason}>
                    <span>
                      <IconButton size="small" disabled aria-label={t("llmDetection.tooltip.run")} data-testid="llmdetection-run"><PlayArrowOutlined sx={{ fontSize: 14 }} /></IconButton>
                    </span>
                  </Tooltip>
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
              const prompt = SAMPLE_PROMPTS.find(p => p.id === r.promptId);
              const statusColor = r.status === "success" ? tokens.accent.green : r.status === "failed" ? tokens.accent.red : tokens.accent.amber;
              return (
                <Paper key={r.id} elevation={0} data-testid="llmdetection-result" sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, p: 1.5, display: "flex", gap: 1.5, alignItems: "flex-start" }}>
                  <Avatar variant="rounded" sx={{ width: 48, height: 48 }} />
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mb: 0.5 }}>
                      <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem" }}>{r.assetName}</Typography>
                      <Chip label={prompt?.name ?? r.promptId} size="small" sx={{ height: 16, fontSize: "0.6rem", bgcolor: tokens.bg.overlay }} />
                      <Chip label={t(`llmDetection.status.${r.status}`)} size="small" sx={{ height: 16, fontSize: "0.6rem", bgcolor: `${statusColor}18`, color: statusColor }} />
                    </Box>
                    {r.output && (
                      <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.72rem", display: "block", fontFamily: r.output.startsWith("[") || r.output.startsWith("{") ? "monospace" : "inherit", whiteSpace: "pre-wrap", lineHeight: 1.5 }}>
                        {r.output.slice(0, 200)}{r.output.length > 200 ? "…" : ""}
                      </Typography>
                    )}
                    <Box sx={{ display: "flex", gap: 1, mt: 0.5 }}>
                      <Typography variant="caption" color="text.tertiary" sx={{ fontSize: "0.65rem" }}>{t("llmDetection.label.tokens", { count: r.tokenCount })}</Typography>
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
