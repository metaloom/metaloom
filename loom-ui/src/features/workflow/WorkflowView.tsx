import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Box, Typography, Chip, IconButton, Paper, TextField, Tooltip, Divider,
  Autocomplete, Rating, Button, ToggleButtonGroup, ToggleButton,
  InputAdornment,
} from "@mui/material";
import {
  StarOutlined, LocalOfferOutlined, ContentCopyOutlined,
  ArrowBackIosNewOutlined, ArrowForwardIosOutlined, SkipNextOutlined,
  ChevronLeftOutlined, ChevronRightOutlined, SettingsOutlined,
  SearchOutlined, CheckOutlined, CloseOutlined, DeleteOutlineOutlined,
  KeyboardOutlined, TuneOutlined, SpeedOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Asset } from "../../types";
import { ASSETS } from "../../mock/data";

// ── Types ─────────────────────────────────────────────────────────────────
type WorkflowMode = "rating" | "tagging" | "deduplication";

interface KeyAction {
  key: string;
  label: string;
  action: string;
  param?: string;
}

interface KeyProfile {
  id: string;
  name: string;
  mode: WorkflowMode;
  bindings: KeyAction[];
}

// Tag names from mock data (flat list)
const ALL_TAGS = [
  "landscape", "portrait", "urban", "nature", "drone", "interview",
  "timelapse", "b-roll", "hero", "archive", "aerial", "macro",
  "night", "studio", "outdoor", "indoor", "wildlife", "street",
  "fashion", "architecture", "food", "travel", "sports", "music",
];

// Duplicate groups (mock: pairs of similar assets)
function buildDuplicateGroups(assets: Asset[]): { keep: Asset; candidates: Asset[] }[] {
  const groups: { keep: Asset; candidates: Asset[] }[] = [];
  for (let i = 0; i + 1 < assets.length; i += 2) {
    groups.push({ keep: assets[i], candidates: [assets[i + 1]] });
  }
  return groups;
}

// ── Default Key Profiles ──────────────────────────────────────────────────
const DEFAULT_PROFILES: KeyProfile[] = [
  {
    id: "rating-default",
    name: "Rating — Default",
    mode: "rating",
    bindings: [
      { key: "1", label: "1 Star", action: "set_rating", param: "1" },
      { key: "2", label: "2 Stars", action: "set_rating", param: "2" },
      { key: "3", label: "3 Stars", action: "set_rating", param: "3" },
      { key: "4", label: "4 Stars", action: "set_rating", param: "4" },
      { key: "5", label: "5 Stars", action: "set_rating", param: "5" },
      { key: "6", label: "6 Stars", action: "set_rating", param: "6" },
      { key: "7", label: "7 Stars", action: "set_rating", param: "7" },
      { key: "8", label: "8 Stars", action: "set_rating", param: "8" },
      { key: "9", label: "9 Stars", action: "set_rating", param: "9" },
      { key: "0", label: "10 Stars", action: "set_rating", param: "10" },
      { key: "ArrowRight", label: "Next", action: "next_asset" },
      { key: "ArrowLeft", label: "Previous", action: "prev_asset" },
      { key: " ", label: "Next (Space)", action: "next_asset" },
      { key: "Enter", label: "Edit Tags", action: "focus_tags" },
    ],
  },
  {
    id: "tagging-default",
    name: "Tagging — Default",
    mode: "tagging",
    bindings: [
      { key: "ArrowRight", label: "Next", action: "next_asset" },
      { key: "ArrowLeft", label: "Previous", action: "prev_asset" },
      { key: " ", label: "Next (Space)", action: "next_asset" },
      { key: "Enter", label: "Edit Tags", action: "focus_tags" },
      { key: "Escape", label: "Blur Tag Input", action: "blur_tags" },
    ],
  },
  {
    id: "dedup-default",
    name: "Deduplication — Default",
    mode: "deduplication",
    bindings: [
      { key: "ArrowRight", label: "Next Group", action: "next_asset" },
      { key: "ArrowLeft", label: "Prev Group", action: "prev_asset" },
      { key: " ", label: "Next Group", action: "next_asset" },
      { key: "y", label: "Confirm Dedup", action: "confirm_dedup" },
      { key: "n", label: "Reject Dedup", action: "reject_dedup" },
    ],
  },
];

// ── Rating Mode ───────────────────────────────────────────────────────────
function RatingMode({
  asset,
  ratings,
  onRate,
  tagInputRef,
  assetTags,
  onAddTag,
  onRemoveTag,
}: {
  asset: Asset;
  ratings: Record<string, number>;
  onRate: (rating: number) => void;
  tagInputRef: React.RefObject<HTMLInputElement | null>;
  assetTags: string[];
  onAddTag: (tag: string) => void;
  onRemoveTag: (tag: string) => void;
}) {
  const rating = ratings[asset.id] ?? 0;
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1 }}>
      {/* Asset preview */}
      <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", bgcolor: "#000", borderRadius: tokens.radius.lg, overflow: "hidden", position: "relative", minHeight: 300 }}>
        <img src={asset.thumbnailUrl} alt={asset.name} style={{ maxWidth: "100%", maxHeight: "100%", objectFit: "contain" }} />
      </Box>

      {/* Info bar */}
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, px: 1 }}>
        <Box sx={{ flex: 1 }}>
          <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.95rem" }}>{asset.name}</Typography>
          <Typography variant="caption" color="text.secondary">{asset.type} · {asset.mimeType}</Typography>
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.78rem" }}>Rating:</Typography>
          <Rating
            value={rating}
            max={10}
            onChange={(_, v) => v !== null && onRate(v)}
            size="small"
            sx={{ "& .MuiRating-iconFilled": { color: tokens.accent.amber }, "& .MuiRating-iconEmpty": { color: tokens.text.tertiary } }}
          />
          <Typography variant="caption" fontWeight={600} sx={{ minWidth: 20, textAlign: "center" }}>{rating || "—"}</Typography>
        </Box>
      </Box>

      {/* Tag editor */}
      <Box sx={{ px: 1 }}>
        <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap", alignItems: "center", mb: 0.75 }}>
          {assetTags.map(t => (
            <Chip key={t} label={t} size="small" onDelete={() => onRemoveTag(t)} sx={{ height: 22, fontSize: "0.72rem" }} />
          ))}
        </Box>
        <Autocomplete
          freeSolo
          options={ALL_TAGS.filter(t => !assetTags.includes(t))}
          renderInput={(params) => (
            <TextField
              {...params}
              inputRef={tagInputRef}
              placeholder="Add tag… (Enter to confirm)"
              size="small"
              InputProps={{
                ...params.InputProps,
                startAdornment: (
                  <InputAdornment position="start">
                    <LocalOfferOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                  </InputAdornment>
                ),
              }}
              sx={{ "& .MuiInputBase-root": { fontSize: "0.8rem" } }}
            />
          )}
          onChange={(_, val) => {
            if (typeof val === "string" && val.trim()) {
              onAddTag(val.trim());
            }
          }}
          clearOnBlur={false}
          selectOnFocus
          handleHomeEndKeys
          sx={{ maxWidth: 360 }}
        />
      </Box>
    </Box>
  );
}

// ── Tagging Mode ──────────────────────────────────────────────────────────
function TaggingMode({
  asset,
  tagInputRef,
  assetTags,
  onAddTag,
  onRemoveTag,
}: {
  asset: Asset;
  tagInputRef: React.RefObject<HTMLInputElement | null>;
  assetTags: string[];
  onAddTag: (tag: string) => void;
  onRemoveTag: (tag: string) => void;
}) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1 }}>
      <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", bgcolor: "#000", borderRadius: tokens.radius.lg, overflow: "hidden", minHeight: 300 }}>
        <img src={asset.thumbnailUrl} alt={asset.name} style={{ maxWidth: "100%", maxHeight: "100%", objectFit: "contain" }} />
      </Box>
      <Box sx={{ px: 1 }}>
        <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.95rem", mb: 0.5 }}>{asset.name}</Typography>
        <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap", alignItems: "center", mb: 1 }}>
          {assetTags.map(t => (
            <Chip key={t} label={t} size="small" onDelete={() => onRemoveTag(t)} sx={{ height: 22, fontSize: "0.72rem" }} />
          ))}
          {assetTags.length === 0 && <Typography variant="caption" color="text.secondary">No tags yet</Typography>}
        </Box>
        <Autocomplete
          freeSolo
          options={ALL_TAGS.filter(t => !assetTags.includes(t))}
          renderInput={(params) => (
            <TextField
              {...params}
              inputRef={tagInputRef}
              placeholder="Type to search tags… (Enter to add)"
              size="small"
              autoFocus
              InputProps={{
                ...params.InputProps,
                startAdornment: (
                  <InputAdornment position="start">
                    <LocalOfferOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                  </InputAdornment>
                ),
              }}
            />
          )}
          onChange={(_, val) => {
            if (typeof val === "string" && val.trim()) {
              onAddTag(val.trim());
            }
          }}
          clearOnBlur={false}
          selectOnFocus
          handleHomeEndKeys
          sx={{ maxWidth: 440 }}
        />
      </Box>
    </Box>
  );
}

// ── Deduplication Mode ────────────────────────────────────────────────────
function DeduplicationMode({
  group,
  onConfirm,
  onReject,
  decision,
}: {
  group: { keep: Asset; candidates: Asset[] };
  onConfirm: () => void;
  onReject: () => void;
  decision: "confirmed" | "rejected" | null;
}) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1, overflow: "auto" }}>
      {/* Keep asset */}
      <Box>
        <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", color: tokens.accent.green, fontSize: "0.7rem", letterSpacing: "0.06em", mb: 0.5, display: "block" }}>Keep</Typography>
        <Paper elevation={0} sx={{ border: `2px solid ${tokens.accent.green}`, borderRadius: tokens.radius.lg, overflow: "hidden", bgcolor: tokens.bg.elevated }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 2, p: 1.5 }}>
            <Box sx={{ width: 160, height: 90, borderRadius: tokens.radius.md, overflow: "hidden", flexShrink: 0, bgcolor: "#000" }}>
              <img src={group.keep.thumbnailUrl} alt={group.keep.name} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
            </Box>
            <Box>
              <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.88rem" }}>{group.keep.name}</Typography>
              <Typography variant="caption" color="text.secondary">{group.keep.type} · {group.keep.mimeType}</Typography>
              <Box sx={{ display: "flex", gap: 0.5, mt: 0.5 }}>
                {group.keep.tags.slice(0, 3).map(t => <Chip key={t} label={t} size="small" sx={{ height: 18, fontSize: "0.65rem" }} />)}
              </Box>
            </Box>
          </Box>
        </Paper>
      </Box>

      {/* Duplicate candidates */}
      <Box>
        <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", color: tokens.text.tertiary, fontSize: "0.7rem", letterSpacing: "0.06em", mb: 0.5, display: "block" }}>
          Duplicate Candidates
        </Typography>
        {group.candidates.map(c => (
          <Paper key={c.id} elevation={0} sx={{ border: `2px dashed ${decision === "confirmed" ? tokens.accent.red : decision === "rejected" ? tokens.text.tertiary : tokens.border.strong}`, borderRadius: tokens.radius.lg, overflow: "hidden", bgcolor: tokens.bg.elevated, opacity: decision === "confirmed" ? 0.5 : 1, transition: "opacity 200ms ease", mb: 1 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, p: 1.5 }}>
              <Box sx={{ width: 160, height: 90, borderRadius: tokens.radius.md, overflow: "hidden", flexShrink: 0, bgcolor: "#000" }}>
                <img src={c.thumbnailUrl} alt={c.name} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
              </Box>
              <Box sx={{ flex: 1 }}>
                <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.88rem" }}>{c.name}</Typography>
                <Typography variant="caption" color="text.secondary">{c.type} · {c.mimeType}</Typography>
                <Box sx={{ display: "flex", gap: 0.5, mt: 0.5 }}>
                  {c.tags.slice(0, 3).map(t => <Chip key={t} label={t} size="small" sx={{ height: 18, fontSize: "0.65rem" }} />)}
                </Box>
              </Box>
              {decision && (
                <Chip
                  label={decision === "confirmed" ? "Remove" : "Kept"}
                  size="small"
                  sx={{
                    height: 20, fontSize: "0.68rem", fontWeight: 600,
                    bgcolor: decision === "confirmed" ? `${tokens.accent.red}18` : `${tokens.accent.green}18`,
                    color: decision === "confirmed" ? tokens.accent.red : tokens.accent.green,
                  }}
                />
              )}
            </Box>
          </Paper>
        ))}
      </Box>

      {/* Actions */}
      <Box sx={{ display: "flex", gap: 1, px: 1 }}>
        <Button
          variant={decision === "confirmed" ? "contained" : "outlined"}
          size="small"
          color="error"
          startIcon={<DeleteOutlineOutlined sx={{ fontSize: 16 }} />}
          onClick={onConfirm}
          sx={{ textTransform: "none", fontWeight: 600, fontSize: "0.8rem" }}
        >
          Confirm Dedup (Y)
        </Button>
        <Button
          variant={decision === "rejected" ? "contained" : "outlined"}
          size="small"
          startIcon={<CloseOutlined sx={{ fontSize: 16 }} />}
          onClick={onReject}
          sx={{ textTransform: "none", fontWeight: 600, fontSize: "0.8rem" }}
        >
          Reject (N)
        </Button>
      </Box>
    </Box>
  );
}

// ── Key Profiles Sidebar ──────────────────────────────────────────────────
function ProfilesSidebar({ profiles, activeProfileId, mode, onSelectProfile, collapsed, onToggle }: {
  profiles: KeyProfile[];
  activeProfileId: string;
  mode: WorkflowMode;
  onSelectProfile: (id: string) => void;
  collapsed: boolean;
  onToggle: () => void;
}) {
  const filtered = profiles.filter(p => p.mode === mode);
  const active = profiles.find(p => p.id === activeProfileId);

  return (
    <Box sx={{
      width: collapsed ? 0 : 260,
      flexShrink: 0,
      borderRight: collapsed ? "none" : `1px solid ${tokens.border.subtle}`,
      bgcolor: tokens.bg.surface,
      overflow: "hidden",
      transition: "width 200ms ease",
      display: "flex",
      flexDirection: "column",
    }}>
      <Box sx={{ px: 2, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
        <KeyboardOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
        <Typography variant="caption" fontWeight={700} sx={{ fontSize: "0.78rem", flex: 1 }}>Key Profiles</Typography>
        <Tooltip title="Collapse">
          <IconButton size="small" onClick={onToggle} sx={{ width: 20, height: 20 }}>
            <ChevronLeftOutlined sx={{ fontSize: 14 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {/* Profile list */}
      <Box sx={{ p: 1, display: "flex", flexDirection: "column", gap: 0.5 }}>
        {filtered.map(p => (
          <Paper
            key={p.id}
            elevation={0}
            onClick={() => onSelectProfile(p.id)}
            sx={{
              px: 1.5, py: 1, cursor: "pointer",
              bgcolor: p.id === activeProfileId ? tokens.primary.subtle : tokens.bg.elevated,
              border: `1px solid ${p.id === activeProfileId ? tokens.primary.main : tokens.border.subtle}`,
              borderRadius: tokens.radius.md,
              "&:hover": { borderColor: tokens.border.strong },
              transition: "all 120ms ease",
            }}
          >
            <Typography variant="body2" fontWeight={p.id === activeProfileId ? 700 : 500} sx={{ fontSize: "0.82rem" }}>{p.name}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}>{p.bindings.length} bindings</Typography>
          </Paper>
        ))}
      </Box>

      <Divider sx={{ my: 0.5 }} />

      {/* Active profile bindings */}
      {active && (
        <Box sx={{ flex: 1, overflow: "auto", p: 1.5 }}>
          <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.tertiary, fontSize: "0.66rem", mb: 1, display: "block" }}>
            Bindings
          </Typography>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
            {active.bindings.map((b, i) => (
              <Box key={i} sx={{ display: "flex", alignItems: "center", gap: 1, py: 0.5 }}>
                <Chip
                  label={b.key === " " ? "Space" : b.key === "ArrowRight" ? "→" : b.key === "ArrowLeft" ? "←" : b.key === "Enter" ? "↵" : b.key === "Escape" ? "Esc" : b.key.toUpperCase()}
                  size="small"
                  sx={{ height: 20, minWidth: 28, fontSize: "0.68rem", fontWeight: 700, bgcolor: tokens.bg.overlay, fontFamily: "monospace" }}
                />
                <Typography variant="caption" sx={{ fontSize: "0.72rem", color: tokens.text.secondary }}>{b.label}</Typography>
              </Box>
            ))}
          </Box>
        </Box>
      )}
    </Box>
  );
}

// ── Main Workflow View ────────────────────────────────────────────────────
export default function WorkflowView() {
  const assets = useMemo(() => ASSETS.slice(0, 20), []);
  const [mode, setMode] = useState<WorkflowMode>("rating");
  const [currentIdx, setCurrentIdx] = useState(0);
  const [ratings, setRatings] = useState<Record<string, number>>({});
  const [assetTags, setAssetTags] = useState<Record<string, string[]>>(() => {
    const map: Record<string, string[]> = {};
    assets.forEach(a => { map[a.id] = [...a.tags]; });
    return map;
  });
  const [dedupDecisions, setDedupDecisions] = useState<Record<number, "confirmed" | "rejected">>({});
  const [profileSidebarOpen, setProfileSidebarOpen] = useState(true);
  const [profiles] = useState<KeyProfile[]>(DEFAULT_PROFILES);
  const [activeProfileId, setActiveProfileId] = useState(DEFAULT_PROFILES[0].id);
  const tagInputRef = useRef<HTMLInputElement>(null);

  const duplicateGroups = useMemo(() => buildDuplicateGroups(assets), [assets]);
  const currentAsset = assets[currentIdx] ?? assets[0];
  const currentGroup = duplicateGroups[currentIdx] ?? duplicateGroups[0];
  const maxIdx = mode === "deduplication" ? duplicateGroups.length - 1 : assets.length - 1;

  // Sync active profile when mode changes
  useEffect(() => {
    const profile = profiles.find(p => p.mode === mode);
    if (profile) setActiveProfileId(profile.id);
    setCurrentIdx(0);
  }, [mode, profiles]);

  const goNext = useCallback(() => setCurrentIdx(i => Math.min(i + 1, maxIdx)), [maxIdx]);
  const goPrev = useCallback(() => setCurrentIdx(i => Math.max(i - 1, 0)), []);

  const handleRate = useCallback((rating: number) => {
    setRatings(prev => ({ ...prev, [currentAsset.id]: rating }));
  }, [currentAsset]);

  const handleAddTag = useCallback((tag: string) => {
    setAssetTags(prev => {
      const current = prev[currentAsset.id] ?? [];
      if (current.includes(tag)) return prev;
      return { ...prev, [currentAsset.id]: [...current, tag] };
    });
  }, [currentAsset]);

  const handleRemoveTag = useCallback((tag: string) => {
    setAssetTags(prev => ({
      ...prev,
      [currentAsset.id]: (prev[currentAsset.id] ?? []).filter(t => t !== tag),
    }));
  }, [currentAsset]);

  const handleConfirmDedup = useCallback(() => {
    setDedupDecisions(prev => ({ ...prev, [currentIdx]: "confirmed" }));
  }, [currentIdx]);

  const handleRejectDedup = useCallback(() => {
    setDedupDecisions(prev => ({ ...prev, [currentIdx]: "rejected" }));
  }, [currentIdx]);

  // Keyboard handler
  useEffect(() => {
    const profile = profiles.find(p => p.id === activeProfileId);
    if (!profile) return;

    const handler = (e: KeyboardEvent) => {
      // Don't hijack when user is typing in an input
      const target = e.target as HTMLElement;
      const isInput = target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable;

      const binding = profile.bindings.find(b => b.key === e.key);
      if (!binding) return;

      // Allow Enter in input fields for tag confirmation
      if (isInput && binding.action !== "blur_tags") return;

      switch (binding.action) {
        case "next_asset":
          e.preventDefault();
          goNext();
          break;
        case "prev_asset":
          e.preventDefault();
          goPrev();
          break;
        case "set_rating":
          e.preventDefault();
          if (binding.param) handleRate(parseInt(binding.param, 10));
          break;
        case "focus_tags":
          e.preventDefault();
          tagInputRef.current?.focus();
          break;
        case "blur_tags":
          e.preventDefault();
          tagInputRef.current?.blur();
          break;
        case "confirm_dedup":
          e.preventDefault();
          handleConfirmDedup();
          break;
        case "reject_dedup":
          e.preventDefault();
          handleRejectDedup();
          break;
      }
    };

    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [activeProfileId, profiles, goNext, goPrev, handleRate, handleConfirmDedup, handleRejectDedup]);

  return (
    <Box sx={{ display: "flex", height: "100%", overflow: "hidden", bgcolor: tokens.bg.base }}>
      {/* Key profiles sidebar */}
      <ProfilesSidebar
        profiles={profiles}
        activeProfileId={activeProfileId}
        mode={mode}
        onSelectProfile={setActiveProfileId}
        collapsed={!profileSidebarOpen}
        onToggle={() => setProfileSidebarOpen(v => !v)}
      />

      {/* Main content */}
      <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
        {/* Toolbar */}
        <Box sx={{ px: 2.5, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", alignItems: "center", gap: 2 }}>
          {!profileSidebarOpen && (
            <Tooltip title="Show key profiles">
              <IconButton size="small" onClick={() => setProfileSidebarOpen(true)} sx={{ mr: 0.5 }}>
                <TuneOutlined sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
          )}

          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
            <SpeedOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Workflow</Typography>
          </Box>

          <ToggleButtonGroup
            value={mode}
            exclusive
            onChange={(_, v) => v && setMode(v)}
            size="small"
            sx={{ ml: 1 }}
          >
            <ToggleButton value="rating" sx={{ textTransform: "none", fontSize: "0.78rem", px: 1.5 }}>
              <StarOutlined sx={{ fontSize: 14, mr: 0.5 }} /> Rating
            </ToggleButton>
            <ToggleButton value="tagging" sx={{ textTransform: "none", fontSize: "0.78rem", px: 1.5 }}>
              <LocalOfferOutlined sx={{ fontSize: 14, mr: 0.5 }} /> Tagging
            </ToggleButton>
            <ToggleButton value="deduplication" sx={{ textTransform: "none", fontSize: "0.78rem", px: 1.5 }}>
              <ContentCopyOutlined sx={{ fontSize: 14, mr: 0.5 }} /> Deduplication
            </ToggleButton>
          </ToggleButtonGroup>

          <Box sx={{ flex: 1 }} />

          {/* Navigation */}
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <IconButton size="small" onClick={goPrev} disabled={currentIdx === 0}>
              <ArrowBackIosNewOutlined sx={{ fontSize: 14 }} />
            </IconButton>
            <Typography variant="caption" fontWeight={600} sx={{ minWidth: 60, textAlign: "center" }}>
              {currentIdx + 1} / {maxIdx + 1}
            </Typography>
            <IconButton size="small" onClick={goNext} disabled={currentIdx >= maxIdx}>
              <ArrowForwardIosOutlined sx={{ fontSize: 14 }} />
            </IconButton>
          </Box>
        </Box>

        {/* Content area */}
        <Box sx={{ flex: 1, overflow: "auto", p: 2.5, display: "flex" }}>
          {mode === "rating" && currentAsset && (
            <RatingMode
              asset={currentAsset}
              ratings={ratings}
              onRate={handleRate}
              tagInputRef={tagInputRef}
              assetTags={assetTags[currentAsset.id] ?? []}
              onAddTag={handleAddTag}
              onRemoveTag={handleRemoveTag}
            />
          )}
          {mode === "tagging" && currentAsset && (
            <TaggingMode
              asset={currentAsset}
              tagInputRef={tagInputRef}
              assetTags={assetTags[currentAsset.id] ?? []}
              onAddTag={handleAddTag}
              onRemoveTag={handleRemoveTag}
            />
          )}
          {mode === "deduplication" && currentGroup && (
            <DeduplicationMode
              group={currentGroup}
              onConfirm={handleConfirmDedup}
              onReject={handleRejectDedup}
              decision={dedupDecisions[currentIdx] ?? null}
            />
          )}
        </Box>

        {/* Keyboard hint bar */}
        <Box sx={{ px: 2.5, py: 0.75, borderTop: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
          <KeyboardOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
          {mode === "rating" && (
            <>
              <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}>
                <strong>1-0</strong> Set rating · <strong>←/→</strong> Navigate · <strong>Space</strong> Next · <strong>Enter</strong> Edit tags
              </Typography>
            </>
          )}
          {mode === "tagging" && (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}>
              <strong>←/→</strong> Navigate · <strong>Space</strong> Next · <strong>Enter</strong> Edit tags · <strong>Esc</strong> Blur
            </Typography>
          )}
          {mode === "deduplication" && (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}>
              <strong>←/→</strong> Navigate · <strong>Y</strong> Confirm dedup · <strong>N</strong> Reject
            </Typography>
          )}
        </Box>
      </Box>
    </Box>
  );
}
