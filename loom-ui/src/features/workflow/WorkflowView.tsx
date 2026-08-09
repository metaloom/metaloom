import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Box, Typography, Chip, IconButton, Paper, TextField, Tooltip, Divider,
  Autocomplete, Rating, Button, ToggleButtonGroup, ToggleButton,
  InputAdornment, Avatar,
} from "@mui/material";
import {
  StarOutlined, LocalOfferOutlined, ContentCopyOutlined,
  ArrowBackIosNewOutlined, ArrowForwardIosOutlined,
  ChevronRightOutlined,
  CheckOutlined, CloseOutlined, DeleteOutlineOutlined,
  KeyboardOutlined, TuneOutlined, SpeedOutlined,
  FaceOutlined, CenterFocusStrongOutlined, FullscreenOutlined,
  FullscreenExitOutlined, PersonOutlined,
  HelpOutlineOutlined, CheckCircleOutlineOutlined,
  AutoAwesomeOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Asset, AssetType, AssetStatus, DetectedFace, FaceCluster, Person, DetectedObject } from "../../types";
import { PERSONS } from "../../mock/data";
import { useLayout } from "../../context/LayoutContext";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { listAssets, loadAsset, assetBinaryUrl, AssetResponse } from "../../api/assets";
import {
  bulkReviewDetections, listAssetDetections, confirmDetection, rejectDetection,
  type DetectionResponse,
} from "../../api/detections";
import { confirmCluster, listAssetClusters, listClusterMembers, rejectCluster } from "../../api/clusters";
import {
  listDedupGroups, STATUS_CONFIRMED, STATUS_PENDING, STATUS_REJECTED,
  type DedupGroupMemberModel, type DedupGroupResponse,
} from "../../api/dedup";
import { persistAssetRating, hydrateAssetRatings } from "./ratingPersistence";
import {
  addAssetTag, isCurated, isPending, loadTagVocabulary, pendingTag, removeAssetTag,
  toWorkflowTags, type WorkflowTag,
} from "./tagPersistence";
import { decideGroup, dupMembers, formatSize, isComplete, keepMember, reassignKeep, replaceGroup } from "./dedupGroups";
import EmptyState from "../../components/EmptyState";
import AssetThumbnail from "../../components/AssetThumbnail";
import { PAGE_SIZE } from "../../hooks/pagedList";

function apiToWorkflowAsset(r: AssetResponse): Asset {
  const mime = r.file?.mimeType ?? "";
  let type: AssetType = "unknown";
  if (mime.startsWith("image/")) type = "image";
  else if (mime.startsWith("video/")) type = "video";
  else if (mime.startsWith("audio/")) type = "audio";
  else if (mime.startsWith("application/") || mime.startsWith("text/")) type = "document";
  const video = r.videoComponents?.[0];
  const image = r.imageComponents?.[0];
  return {
    id: r.uuid,
    spaceId: "",
    libraryId: "",
    name: r.file?.filename ?? r.uuid,
    type,
    status: "ready" as AssetStatus,
    tags: (r.tags ?? []).map(t => t.name),
    description: "",
    duration: video?.duration,
    width: video?.width ?? image?.width,
    height: video?.height ?? image?.height,
    fileSize: r.file?.size ?? 0,
    mimeType: mime,
    thumbnailUrl: "",
    url: "",
    ownerId: r.status?.creator?.uuid ?? "",
    collectionIds: (r.collections ?? []).map(c => c.uuid),
    createdAt: r.status?.created ?? "",
    updatedAt: r.status?.edited ?? "",
    metadata: {},
  };
}

/** Media class of an asset, used to pick the placeholder icon when there is no preview. */
function assetTypeOf(asset?: AssetResponse): AssetType {
  const mime = asset?.file?.mimeType ?? "";
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime.startsWith("audio/")) return "audio";
  if (mime.startsWith("application/") || mime.startsWith("text/")) return "document";
  return "unknown";
}

/**
 * Preview URL for an asset, or "" when it has none.
 *
 * Only images can be rendered by an `<img>` — there is no thumbnail service and no poster frames,
 * so most dedup members (near-duplicate detection runs on video) show the type placeholder. That is
 * the honest result, not a broken image.
 */
function previewUrlOf(asset?: AssetResponse): string {
  return asset && assetTypeOf(asset) === "image" ? assetBinaryUrl(asset.uuid) : "";
}

// ── Types ─────────────────────────────────────────────────────────────────
type WorkflowMode = "rating" | "tagging" | "deduplication" | "facedetection" | "objectdetection" | "llm";

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

function keyDisplayName(key: string): string {
  if (key === " ") return "Space";
  if (key === "ArrowRight") return "→";
  if (key === "ArrowLeft") return "←";
  if (key === "ArrowUp") return "↑";
  if (key === "ArrowDown") return "↓";
  if (key === "Enter") return "↵";
  if (key === "Escape") return "Esc";
  if (key === "Backspace") return "⌫";
  if (key === "Tab") return "Tab";
  if (key === "") return "—";
  return key.length === 1 ? key.toUpperCase() : key;
}

// ── Default Key Profiles ──────────────────────────────────────────────────
/**
 * How long to wait for more detection verdicts before sending the batch.
 *
 * Long enough that a run of keystrokes coalesces into one request, short enough that a reviewer who
 * stops to look at something has already had their work saved.
 */
const REVIEW_FLUSH_MS = 400;

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
  {
    id: "face-default",
    name: "Faces — Default",
    mode: "facedetection",
    bindings: [
      { key: "ArrowRight", label: "Next Asset", action: "next_asset" },
      { key: "ArrowLeft", label: "Prev Asset", action: "prev_asset" },
      { key: " ", label: "Next Asset", action: "next_asset" },
      { key: "ArrowDown", label: "Next Cluster", action: "next_cluster" },
      { key: "ArrowUp", label: "Prev Cluster", action: "prev_cluster" },
      { key: "y", label: "Confirm Cluster", action: "confirm_cluster" },
      { key: "n", label: "Deny Cluster", action: "deny_cluster" },
      { key: "Tab", label: "Next Cluster", action: "next_cluster" },
      { key: "Enter", label: "Assign Person", action: "focus_person" },
    ],
  },
  {
    id: "object-default",
    name: "Objects — Default",
    mode: "objectdetection",
    bindings: [
      { key: "ArrowRight", label: "Next Asset", action: "next_asset" },
      { key: "ArrowLeft", label: "Prev Asset", action: "prev_asset" },
      { key: " ", label: "Next Asset", action: "next_asset" },
      { key: "ArrowDown", label: "Next Detection", action: "next_detection" },
      { key: "ArrowUp", label: "Prev Detection", action: "prev_detection" },
      { key: "y", label: "Confirm Detection", action: "confirm_object" },
      { key: "n", label: "Reject Detection", action: "reject_object" },
      { key: "Tab", label: "Next Detection", action: "next_detection" },
    ],
  },
  {
    id: "llm-default",
    name: "LLM — Default",
    mode: "llm",
    bindings: [
      { key: "ArrowRight", label: "Next Asset", action: "next_asset" },
      { key: "ArrowLeft", label: "Prev Asset", action: "prev_asset" },
      { key: " ", label: "Next Asset", action: "next_asset" },
      { key: "y", label: "Approve Result", action: "approve_llm" },
      { key: "n", label: "Reject Result", action: "reject_llm" },
      { key: "r", label: "Re-run Prompt", action: "rerun_llm" },
    ],
  },
];

/**
 * The tags on one asset, plus the box to add another.
 *
 * Shared by both modes so a chip means the same thing in each. Two rules it enforces:
 *
 * - A **machine** tag (`nodeKind` other than `manual`) is outlined and carries no delete
 *   affordance. Removing what a pipeline decided is an explicit act, and it belongs on the asset
 *   detail screen rather than one keystroke away from a review flow.
 * - A **pending** chip — one whose POST has not landed yet — also carries no delete affordance,
 *   because there is no server-side uuid to delete with until it does.
 */
function TagEditor({
  tags, vocabulary, onAddTag, onRemoveTag, tagInputRef, placeholder, emptyState, maxWidth, autoFocus,
}: {
  tags: WorkflowTag[]; vocabulary: string[];
  onAddTag: (name: string) => void; onRemoveTag: (tag: WorkflowTag) => void;
  tagInputRef: React.RefObject<HTMLInputElement | null>;
  placeholder: string; emptyState?: string; maxWidth: number; autoFocus?: boolean;
}) {
  const { t } = useTranslation();
  const taken = new Set(tags.map(tag => tag.name.toLowerCase()));
  return (
    <>
      <Box data-testid="workflow-tags" sx={{ display: "flex", gap: 0.5, flexWrap: "wrap", alignItems: "center", mb: 0.75 }}>
        {tags.map(tag => {
          const chipSx = { height: 22, fontSize: "0.72rem", opacity: isPending(tag) ? 0.55 : 1 };
          if (isCurated(tag)) {
            return <Chip key={tag.uuid} label={tag.name} size="small" sx={chipSx}
              data-testid="workflow-tag-chip" data-tag-name={tag.name}
              onDelete={isPending(tag) ? undefined : () => onRemoveTag(tag)} />;
          }
          const tooltip = tag.confidence != null
            ? t("workflow.tagging.machineTooltip", { nodeKind: tag.nodeKind, confidence: Math.round(tag.confidence * 100) })
            : t("workflow.tagging.machineTooltipNoConfidence", { nodeKind: tag.nodeKind });
          return (
            <Tooltip key={tag.uuid} title={tooltip}>
              <Chip label={tag.name} size="small" variant="outlined" sx={chipSx}
                data-testid="workflow-tag-chip" data-tag-name={tag.name} data-machine-tag="true" />
            </Tooltip>
          );
        })}
        {tags.length === 0 && emptyState && <Typography variant="caption" color="text.secondary">{emptyState}</Typography>}
      </Box>
      <Autocomplete freeSolo options={vocabulary.filter(name => !taken.has(name.toLowerCase()))}
        renderInput={(params) => (
          <TextField {...params} inputRef={tagInputRef} placeholder={placeholder} size="small" autoFocus={autoFocus}
            inputProps={{ ...params.inputProps, "data-testid": "workflow-tag-input" }}
            InputProps={{ ...params.InputProps, startAdornment: <InputAdornment position="start"><LocalOfferOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} /></InputAdornment> }}
            sx={{ "& .MuiInputBase-root": { fontSize: "0.8rem" } }} />
        )}
        onChange={(_, val) => { if (typeof val === "string" && val.trim()) onAddTag(val.trim()); }}
        clearOnBlur={false} selectOnFocus handleHomeEndKeys sx={{ maxWidth }} />
    </>
  );
}

// ── Rating Mode ───────────────────────────────────────────────────────────
function RatingMode({
  asset, ratings, onRate, tagInputRef, assetTags, vocabulary, onAddTag, onRemoveTag, alreadyRated,
}: {
  asset: Asset; ratings: Record<string, number>; onRate: (r: number) => void;
  tagInputRef: React.RefObject<HTMLInputElement | null>;
  assetTags: WorkflowTag[]; vocabulary: string[];
  onAddTag: (t: string) => void; onRemoveTag: (t: WorkflowTag) => void;
  alreadyRated: boolean;
}) {
  const { t } = useTranslation();
  const rating = ratings[asset.id] ?? 0;
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1 }}>
      <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", bgcolor: "#000", borderRadius: tokens.radius.lg, overflow: "hidden", minHeight: 300 }}>
        <img src={asset.thumbnailUrl} alt={asset.name} style={{ maxWidth: "100%", maxHeight: "100%", objectFit: "contain" }} />
      </Box>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, px: 1 }}>
        <Box sx={{ flex: 1 }}>
          <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.95rem" }}>{asset.name}</Typography>
          <Typography variant="caption" color="text.secondary">{asset.type}</Typography>
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          {/* Reflects the rating the asset ARRIVED with, so a reviewer can skip work someone
              already did. Deliberately not driven by the live value, which would light up the
              instant they press a key and tell them nothing. */}
          {alreadyRated && <Chip data-testid="workflow-already-rated" label={t("workflow.rating.alreadyRated")}
            size="small" variant="outlined" sx={{ height: 20, fontSize: "0.68rem" }} />}
          <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.78rem" }}>{t("workflow.rating.label")}:</Typography>
          <Rating value={rating} max={10} onChange={(_, v) => v !== null && onRate(v)} size="small"
            sx={{ "& .MuiRating-iconFilled": { color: tokens.accent.amber }, "& .MuiRating-iconEmpty": { color: tokens.text.tertiary } }} />
          <Typography variant="caption" fontWeight={600} data-testid="workflow-rating-value" sx={{ minWidth: 20, textAlign: "center" }}>{rating || "—"}</Typography>
        </Box>
      </Box>
      <Box sx={{ px: 1 }}>
        <TagEditor tags={assetTags} vocabulary={vocabulary} onAddTag={onAddTag} onRemoveTag={onRemoveTag}
          tagInputRef={tagInputRef} placeholder={t("workflow.rating.addTagPlaceholder")} maxWidth={360} />
      </Box>
    </Box>
  );
}

// ── Tagging Mode ──────────────────────────────────────────────────────────
function TaggingMode({
  asset, tagInputRef, assetTags, vocabulary, onAddTag, onRemoveTag, alreadyTagged,
}: {
  asset: Asset; tagInputRef: React.RefObject<HTMLInputElement | null>;
  assetTags: WorkflowTag[]; vocabulary: string[];
  onAddTag: (tag: string) => void; onRemoveTag: (tag: WorkflowTag) => void;
  alreadyTagged: number;
}) {
  const { t } = useTranslation();
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1 }}>
      <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", bgcolor: "#000", borderRadius: tokens.radius.lg, overflow: "hidden", minHeight: 300 }}>
        <img src={asset.thumbnailUrl} alt={asset.name} style={{ maxWidth: "100%", maxHeight: "100%", objectFit: "contain" }} />
      </Box>
      <Box sx={{ px: 1 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
          <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.95rem" }}>{asset.name}</Typography>
          {/* How many curated tags the asset ARRIVED with - the "somebody already did this one" hint. */}
          {alreadyTagged > 0 && <Chip data-testid="workflow-already-tagged"
            label={t("workflow.tagging.alreadyTagged", { count: alreadyTagged })}
            size="small" variant="outlined" sx={{ height: 20, fontSize: "0.68rem" }} />}
        </Box>
        <TagEditor tags={assetTags} vocabulary={vocabulary} onAddTag={onAddTag} onRemoveTag={onRemoveTag}
          tagInputRef={tagInputRef} placeholder={t("workflow.tagging.placeholder")}
          emptyState={t("workflow.tagging.noTags")} maxWidth={440} autoFocus />
      </Box>
    </Box>
  );
}

// ── Deduplication Mode ────────────────────────────────────────────────────

/**
 * Facts about one member of a duplicate group: name, preview, size and completeness.
 *
 * Size is the usual reason a reviewer overrides the machine's keep choice, and completeness is why
 * a plausible-looking duplicate is sometimes a truncated download — both are discovery-time
 * snapshots, which the apply node re-verifies against the live file before moving anything.
 */
function DedupMemberFacts({ member, asset }: { member: DedupGroupMemberModel; asset?: AssetResponse }) {
  const { t } = useTranslation();
  const complete = isComplete(member);
  return (
    <>
      <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.88rem", wordBreak: "break-all" }}>
        {asset?.file?.filename ?? member.assetUuid}
      </Typography>
      <Box sx={{ display: "flex", gap: 0.75, mt: 0.5, alignItems: "center", flexWrap: "wrap" }}>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {t("workflow.dedup.size")}: {formatSize(member.size)}
        </Typography>
        {typeof member.score === "number" && (
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
            {t("workflow.dedup.score")}: {member.score.toFixed(2)}
          </Typography>
        )}
        {!complete && (
          <Chip label={t("workflow.dedup.incomplete")} size="small"
            sx={{ height: 18, fontSize: "0.65rem", fontWeight: 600, bgcolor: `${tokens.accent.red}18`, color: tokens.accent.red }} />
        )}
      </Box>
    </>
  );
}

function DedupPreview({ asset, alt }: { asset?: AssetResponse; alt: string }) {
  // AssetThumbnail is absolutely positioned, so the aspect-ratio wrapper is load-bearing.
  return (
    <Box sx={{ position: "relative", width: "100%", paddingTop: "56.25%", bgcolor: tokens.bg.overlay, overflow: "hidden" }}>
      <AssetThumbnail type={assetTypeOf(asset)} src={previewUrlOf(asset)} iconSize={32} alt={alt} fit="contain" />
    </Box>
  );
}

function DeduplicationMode({
  group, assets, onConfirm, onReject, onMakeKeep, busy,
}: {
  group: DedupGroupResponse;
  /** assetUuid -> the loaded asset record; a member not yet resolved renders by uuid. */
  assets: Record<string, AssetResponse>;
  onConfirm: () => void; onReject: () => void;
  onMakeKeep: (assetUuid: string) => void;
  busy: boolean;
}) {
  const { t } = useTranslation();
  const keep = keepMember(group);
  const dups = dupMembers(group);
  const decision = group.status === STATUS_CONFIRMED ? "confirmed" : group.status === STATUS_REJECTED ? "rejected" : null;
  const keepAsset = keep ? assets[keep.assetUuid] : undefined;

  return (
    <Box data-testid="dedup-group" data-group-uuid={group.uuid}
      sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1, overflow: "auto" }}>
      <Box sx={{ display: "flex", gap: 1, alignItems: "center", flexWrap: "wrap" }}>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {t("workflow.dedup.algorithm")}: {group.algorithm}
        </Typography>
        {typeof group.score === "number" && (
          <Typography variant="caption" data-testid="dedup-group-score" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
            {t("workflow.dedup.score")}: {group.score.toFixed(2)}
          </Typography>
        )}
      </Box>

      {/* Keep asset */}
      {keep && (
        <Box>
          <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", color: tokens.accent.green, fontSize: "0.7rem", letterSpacing: "0.06em", mb: 0.5, display: "block" }}>{t("workflow.dedup.keep")}</Typography>
          <Paper data-testid="dedup-keep" elevation={0} sx={{ border: `2px solid ${tokens.accent.green}`, borderRadius: tokens.radius.lg, overflow: "hidden", bgcolor: tokens.bg.elevated }}>
            <DedupPreview asset={keepAsset} alt={keepAsset?.file?.filename ?? ""} />
            <Box sx={{ p: 1.5 }}>
              <DedupMemberFacts member={keep} asset={keepAsset} />
            </Box>
          </Paper>
        </Box>
      )}

      {/* Duplicate candidates */}
      <Box>
        <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", color: tokens.text.tertiary, fontSize: "0.7rem", letterSpacing: "0.06em", mb: 0.5, display: "block" }}>
          {t("workflow.dedup.duplicateCandidates")}
        </Typography>
        {dups.map(c => {
          const asset = assets[c.assetUuid];
          return (
            <Paper key={c.assetUuid} data-testid={`dedup-member-${c.assetUuid}`} elevation={0} sx={{
              border: `2px dashed ${decision === "confirmed" ? tokens.accent.red : decision === "rejected" ? tokens.text.tertiary : tokens.border.strong}`,
              borderRadius: tokens.radius.lg, overflow: "hidden", bgcolor: tokens.bg.elevated,
              opacity: decision === "confirmed" ? 0.5 : 1, transition: "opacity 200ms ease", mb: 1,
            }}>
              <DedupPreview asset={asset} alt={asset?.file?.filename ?? ""} />
              <Box sx={{ p: 1.5, display: "flex", alignItems: "center", gap: 1 }}>
                <Box sx={{ flex: 1 }}>
                  <DedupMemberFacts member={c} asset={asset} />
                </Box>
                {/* The decision a reviewer could not express at all before: keep this one instead. */}
                <Button size="small" variant="outlined" disabled={busy}
                  data-testid={`dedup-make-keep-${c.assetUuid}`}
                  onClick={() => onMakeKeep(c.assetUuid)}
                  sx={{ textTransform: "none", fontWeight: 600, fontSize: "0.72rem", whiteSpace: "nowrap" }}>
                  {t("workflow.dedup.makeKeep")}
                </Button>
                {decision && (
                  <Chip data-testid="dedup-decision-chip"
                    label={decision === "confirmed" ? t("workflow.dedup.chipRemove") : t("workflow.dedup.chipKept")} size="small"
                    sx={{ height: 20, fontSize: "0.68rem", fontWeight: 600,
                      bgcolor: decision === "confirmed" ? `${tokens.accent.red}18` : `${tokens.accent.green}18`,
                      color: decision === "confirmed" ? tokens.accent.red : tokens.accent.green }} />
                )}
              </Box>
            </Paper>
          );
        })}
      </Box>

      <Box sx={{ display: "flex", gap: 1, px: 1 }}>
        <Button variant={decision === "confirmed" ? "contained" : "outlined"} size="small" color="error"
          data-testid="dedup-confirm" disabled={busy}
          startIcon={<DeleteOutlineOutlined sx={{ fontSize: 16 }} />} onClick={onConfirm}
          sx={{ textTransform: "none", fontWeight: 600, fontSize: "0.8rem" }}>
          {t("workflow.dedup.confirmBtn")}
        </Button>
        <Button variant={decision === "rejected" ? "contained" : "outlined"} size="small"
          data-testid="dedup-reject" disabled={busy}
          startIcon={<CloseOutlined sx={{ fontSize: 16 }} />} onClick={onReject}
          sx={{ textTransform: "none", fontWeight: 600, fontSize: "0.8rem" }}>
          {t("workflow.dedup.rejectBtn")}
        </Button>
      </Box>
    </Box>
  );
}

// ── Face Detection Mode ───────────────────────────────────────────────────
function FaceDetectionMode({
  asset, faces, clusters, persons,
  selectedClusterIdx, onSelectCluster,
  clusterDecisions, onConfirmCluster, onDenyCluster,
  clusterPersonAssignments, onAssignPerson,
  personInputRef,
}: {
  asset: Asset;
  faces: DetectedFace[];
  clusters: { cluster: FaceCluster; faces: DetectedFace[] }[];
  persons: Person[];
  selectedClusterIdx: number;
  onSelectCluster: (idx: number) => void;
  clusterDecisions: Record<string, "confirmed" | "denied">;
  onConfirmCluster: (clusterId: string) => void;
  onDenyCluster: (clusterId: string) => void;
  clusterPersonAssignments: Record<string, string>;
  onAssignPerson: (clusterId: string, personName: string) => void;
  personInputRef: React.RefObject<HTMLInputElement | null>;
}) {
  const selectedCluster = clusters[selectedClusterIdx];
  const { t } = useTranslation();

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1, overflow: "auto" }}>
      {/* Asset preview with face bboxes */}
      <Box sx={{ position: "relative", bgcolor: "#000", borderRadius: tokens.radius.lg, overflow: "hidden", minHeight: 200 }}>
        <img src={asset.thumbnailUrl} alt={asset.name} style={{ width: "100%", display: "block", objectFit: "contain" }} />
        {faces.map(f => {
          const clusterDec = clusterDecisions[f.clusterId ?? ""];
          return (
          <Box key={f.id} sx={{
            position: "absolute",
            left: `${f.boundingBox.x * 100}%`, top: `${f.boundingBox.y * 100}%`,
            width: `${f.boundingBox.width * 100}%`, height: `${f.boundingBox.height * 100}%`,
            border: `2px solid ${selectedCluster && f.clusterId === selectedCluster.cluster.id ? tokens.primary.main : tokens.accent.amber}`,
            borderRadius: tokens.radius.sm, pointerEvents: "none",
          }}>
            {/* Status icon */}
            <Box sx={{ position: "absolute", top: -16, right: 0 }}>
              {clusterDec === "confirmed" ? (
                <CheckCircleOutlineOutlined sx={{ fontSize: 12, color: tokens.accent.green }} />
              ) : (
                <HelpOutlineOutlined sx={{ fontSize: 12, color: clusterDec === "denied" ? tokens.accent.red : tokens.accent.amber }} />
              )}
            </Box>
          </Box>
          );
        })}
      </Box>

      <Typography variant="body2" fontWeight={700} sx={{ px: 1 }}>{asset.name}</Typography>

      {/* Clusters */}
      <Box sx={{ px: 1 }}>
        <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 1, display: "block" }}>
          {t("workflow.faceMode.clusters", { count: clusters.length })}
        </Typography>
        <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
          {clusters.map((c, idx) => {
            const decision = clusterDecisions[c.cluster.id];
            const assignedPerson = clusterPersonAssignments[c.cluster.id];
            const isSelected = idx === selectedClusterIdx;
            return (
              <Paper key={c.cluster.id} elevation={0} onClick={() => onSelectCluster(idx)} sx={{
                p: 1.5, cursor: "pointer",
                border: `1px solid ${isSelected ? tokens.primary.main : tokens.border.subtle}`,
                bgcolor: isSelected ? tokens.primary.subtle : tokens.bg.elevated,
                borderRadius: tokens.radius.md, transition: "all 120ms ease",
                opacity: decision === "denied" ? 0.5 : 1,
              }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 1 }}>
                  <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.85rem", flex: 1 }}>
                    {c.cluster.label}
                    {assignedPerson && <Typography component="span" variant="caption" sx={{ ml: 1, color: tokens.primary.light }}>→ {assignedPerson}</Typography>}
                  </Typography>
                  {decision && (
                    <Chip label={decision === "confirmed" ? t("workflow.faceMode.confirmed") : t("workflow.faceMode.denied")} size="small"
                      sx={{ height: 18, fontSize: "0.65rem", bgcolor: decision === "confirmed" ? `${tokens.accent.green}22` : `${tokens.accent.red}22`, color: decision === "confirmed" ? tokens.accent.green : tokens.accent.red }} />
                  )}
                </Box>
                <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                  {c.faces.map(f => (
                    <Avatar key={f.id} src={f.thumbnailUrl} variant="rounded"
                      sx={{ width: 36, height: 36, border: `1px solid ${tokens.border.subtle}` }} />
                  ))}
                </Box>
                {isSelected && !decision && (
                  <Box sx={{ display: "flex", gap: 1, mt: 1.5, alignItems: "center", flexWrap: "wrap" }}>
                    <Button variant="outlined" size="small" color="success" startIcon={<CheckOutlined sx={{ fontSize: 14 }} />}
                      onClick={(e) => { e.stopPropagation(); onConfirmCluster(c.cluster.id); }}
                      sx={{ textTransform: "none", fontSize: "0.75rem", fontWeight: 600 }}>
                      {t("workflow.faceMode.confirmBtn")}
                    </Button>
                    <Button variant="outlined" size="small" color="error" startIcon={<CloseOutlined sx={{ fontSize: 14 }} />}
                      onClick={(e) => { e.stopPropagation(); onDenyCluster(c.cluster.id); }}
                      sx={{ textTransform: "none", fontSize: "0.75rem", fontWeight: 600 }}>
                      {t("workflow.faceMode.denyBtn")}
                    </Button>
                    <Autocomplete
                      freeSolo size="small"
                      options={persons.map(p => p.name)}
                      value={assignedPerson ?? ""}
                      onChange={(_, val) => { if (typeof val === "string" && val.trim()) onAssignPerson(c.cluster.id, val.trim()); }}
                      renderInput={(params) => (
                        <TextField {...params} inputRef={personInputRef} placeholder={t("workflow.faceMode.assignPerson")} size="small"
                          onClick={(e) => e.stopPropagation()}
                          InputProps={{ ...params.InputProps, startAdornment: <InputAdornment position="start"><PersonOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} /></InputAdornment> }}
                          sx={{ "& .MuiInputBase-root": { fontSize: "0.78rem" } }} />
                      )}
                      sx={{ minWidth: 160, flex: 1 }}
                    />
                  </Box>
                )}
              </Paper>
            );
          })}
          {clusters.length === 0 && <Typography variant="caption" color="text.secondary">{t("workflow.faceMode.empty")}</Typography>}
        </Box>
      </Box>
    </Box>
  );
}

// ── Object Detection Mode ─────────────────────────────────────────────────
function ObjectDetectionMode({
  asset, objects, selectedIdx, onSelectIdx, decisions, onConfirm, onReject,
}: {
  asset: Asset; objects: DetectedObject[]; selectedIdx: number;
  onSelectIdx: (idx: number) => void;
  decisions: Record<string, "confirmed" | "rejected">;
  onConfirm: (id: string) => void; onReject: (id: string) => void;
}) {
  const selected = objects[selectedIdx];
  const { t } = useTranslation();

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1, overflow: "auto" }}>
      {/* Asset preview with bounding boxes */}
      <Box sx={{ position: "relative", bgcolor: "#000", borderRadius: tokens.radius.lg, overflow: "hidden", minHeight: 200 }}>
        <img src={asset.thumbnailUrl} alt={asset.name} style={{ width: "100%", display: "block", objectFit: "contain" }} />
        {objects.map((obj, idx) => {
          const dec = decisions[obj.id];
          const color = dec === "confirmed" ? tokens.accent.green : dec === "rejected" ? tokens.accent.red : idx === selectedIdx ? tokens.primary.main : tokens.accent.amber;
          return (
            <Box key={obj.id} onClick={() => onSelectIdx(idx)} sx={{
              position: "absolute",
              left: `${obj.boundingBox.x * 100}%`, top: `${obj.boundingBox.y * 100}%`,
              width: `${obj.boundingBox.width * 100}%`, height: `${obj.boundingBox.height * 100}%`,
              border: `2px solid ${color}`, borderRadius: tokens.radius.sm,
              cursor: "pointer", "&:hover": { borderWidth: 3 }, transition: "border-width 100ms ease",
            }}>
              {/* Status icon */}
              <Box sx={{ position: "absolute", top: -20, right: 0, display: "flex", alignItems: "center", gap: 0.25 }}>
                {dec === "confirmed" ? (
                  <CheckCircleOutlineOutlined sx={{ fontSize: 12, color: tokens.accent.green }} />
                ) : (
                  <HelpOutlineOutlined sx={{ fontSize: 12, color: dec === "rejected" ? tokens.accent.red : tokens.accent.amber }} />
                )}
              </Box>
              <Typography variant="caption" sx={{
                position: "absolute", top: -18, left: 0,
                bgcolor: color, color: "#000", px: 0.5, borderRadius: "2px",
                fontSize: "0.6rem", fontWeight: 700, whiteSpace: "nowrap",
              }}>
                {obj.label} ({Math.round(obj.confidence * 100)}%)
              </Typography>
            </Box>
          );
        })}
      </Box>

      <Typography variant="body2" fontWeight={700} sx={{ px: 1 }}>{asset.name}</Typography>

      {/* Object list */}
      <Box sx={{ px: 1 }}>
        <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.tertiary, fontSize: "0.68rem", mb: 1, display: "block" }}>
          {t("workflow.objectMode.detected", { count: objects.length })}
        </Typography>
        <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
          {objects.map((obj, idx) => {
            const dec = decisions[obj.id];
            const isSelected = idx === selectedIdx;
            return (
              <Paper key={obj.id} elevation={0} onClick={() => onSelectIdx(idx)} sx={{
                px: 1.5, py: 1, cursor: "pointer", display: "flex", alignItems: "center", gap: 1.5,
                border: `1px solid ${isSelected ? tokens.primary.main : tokens.border.subtle}`,
                bgcolor: isSelected ? tokens.primary.subtle : tokens.bg.elevated,
                borderRadius: tokens.radius.md, opacity: dec === "rejected" ? 0.5 : 1, transition: "all 120ms ease",
              }}>
                <Box sx={{ flex: 1 }}>
                  <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.84rem", textTransform: "capitalize" }}>{obj.label}</Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>{t("workflow.objectMode.confidence", { pct: Math.round(obj.confidence * 100) })}</Typography>
                </Box>
                {dec ? (
                  <Chip label={dec === "confirmed" ? t("workflow.objectMode.confirmed") : t("workflow.objectMode.rejected")} size="small"
                    sx={{ height: 18, fontSize: "0.65rem", bgcolor: dec === "confirmed" ? `${tokens.accent.green}22` : `${tokens.accent.red}22`, color: dec === "confirmed" ? tokens.accent.green : tokens.accent.red }} />
                ) : isSelected ? (
                  <Box sx={{ display: "flex", gap: 0.5 }}>
                    <IconButton size="small" onClick={(e) => { e.stopPropagation(); onConfirm(obj.id); }}
                      sx={{ width: 26, height: 26, bgcolor: `${tokens.accent.green}18`, "&:hover": { bgcolor: `${tokens.accent.green}33` } }}>
                      <CheckOutlined sx={{ fontSize: 14, color: tokens.accent.green }} />
                    </IconButton>
                    <IconButton size="small" onClick={(e) => { e.stopPropagation(); onReject(obj.id); }}
                      sx={{ width: 26, height: 26, bgcolor: `${tokens.accent.red}18`, "&:hover": { bgcolor: `${tokens.accent.red}33` } }}>
                      <CloseOutlined sx={{ fontSize: 14, color: tokens.accent.red }} />
                    </IconButton>
                  </Box>
                ) : null}
              </Paper>
            );
          })}
          {objects.length === 0 && <Typography variant="caption" color="text.secondary">{t("workflow.objectMode.empty")}</Typography>}
        </Box>
      </Box>
    </Box>
  );
}

// ── LLM Mode ─────────────────────────────────────────────────────────────
function LLMMode({
  asset, llmDecisions, onApprove, onReject,
}: {
  asset: Asset;
  llmDecisions: Record<string, "approved" | "rejected">;
  onApprove: (assetId: string) => void;
  onReject: (assetId: string) => void;
}) {
  const decision = llmDecisions[asset.id];
  const { t } = useTranslation();
  // Mock LLM results for this asset
  const mockResult = asset.id === "a1" ? "Corporate presentation scene with modern furniture. Professional indoor lighting." :
    asset.id === "a3" ? "Product hero shot with clean studio background and dramatic side lighting." :
    asset.id === "a4" ? "Nature scene with golden retriever on grass near a wooden bench." :
    "No LLM analysis available for this asset yet.";

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, flex: 1, overflow: "auto" }}>
      <Box sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", bgcolor: "#000", borderRadius: tokens.radius.lg, overflow: "hidden", minHeight: 300 }}>
        <img src={asset.thumbnailUrl} alt={asset.name} style={{ maxWidth: "100%", maxHeight: "100%", objectFit: "contain" }} />
      </Box>
      <Box sx={{ px: 1 }}>
        <Typography variant="body2" fontWeight={700} sx={{ fontSize: "0.95rem", mb: 0.5 }}>{asset.name}</Typography>
        <Paper elevation={0} sx={{ p: 2, bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
            <AutoAwesomeOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
            <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.78rem" }}>{t("workflow.llmMode.modelOutput")}</Typography>
            <Chip label="gpt-4o" size="small" sx={{ height: 16, fontSize: "0.62rem", bgcolor: tokens.bg.overlay }} />
          </Box>
          <Typography variant="body2" sx={{ color: tokens.text.secondary, fontSize: "0.84rem", lineHeight: 1.6, whiteSpace: "pre-wrap" }}>
            {mockResult}
          </Typography>
        </Paper>
        <Box sx={{ display: "flex", gap: 1, mt: 1.5 }}>
          <Button variant={decision === "approved" ? "contained" : "outlined"} size="small" color="success"
            startIcon={<CheckOutlined sx={{ fontSize: 14 }} />} onClick={() => onApprove(asset.id)}
            sx={{ textTransform: "none", fontSize: "0.8rem", fontWeight: 600 }}>{t("workflow.llmMode.approveBtn")}</Button>
          <Button variant={decision === "rejected" ? "contained" : "outlined"} size="small" color="error"
            startIcon={<CloseOutlined sx={{ fontSize: 14 }} />} onClick={() => onReject(asset.id)}
            sx={{ textTransform: "none", fontSize: "0.8rem", fontWeight: 600 }}>{t("workflow.llmMode.rejectBtn")}</Button>
          {decision && (
            <Chip label={decision} size="small" sx={{ height: 22, fontSize: "0.72rem", fontWeight: 600, ml: "auto",
              bgcolor: decision === "approved" ? `${tokens.accent.green}18` : `${tokens.accent.red}18`,
              color: decision === "approved" ? tokens.accent.green : tokens.accent.red }} />
          )}
        </Box>
      </Box>
    </Box>
  );
}

// ── Key Profiles Sidebar (right side, narrower, editable bindings) ────────
function ProfilesSidebar({
  profiles, activeProfileId, mode, onSelectProfile, collapsed, onToggle, onUpdateBinding,
}: {
  profiles: KeyProfile[]; activeProfileId: string; mode: WorkflowMode;
  onSelectProfile: (id: string) => void; collapsed: boolean; onToggle: () => void;
  onUpdateBinding: (profileId: string, bindingIdx: number, newKey: string) => void;
}) {
  const filtered = profiles.filter(p => p.mode === mode);
  const active = profiles.find(p => p.id === activeProfileId);
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const { t } = useTranslation();

  useEffect(() => {
    if (editingIdx === null || !active) return;
    const handler = (e: KeyboardEvent) => {
      e.preventDefault();
      e.stopPropagation();
      if (e.key === "Backspace") {
        onUpdateBinding(active.id, editingIdx, "");
      } else {
        onUpdateBinding(active.id, editingIdx, e.key);
      }
      setEditingIdx(null);
    };
    window.addEventListener("keydown", handler, true);
    return () => window.removeEventListener("keydown", handler, true);
  }, [editingIdx, active, onUpdateBinding]);

  return (
    <Box sx={{
      width: collapsed ? 0 : 200, flexShrink: 0,
      borderLeft: collapsed ? "none" : `1px solid ${tokens.border.subtle}`,
      bgcolor: tokens.bg.surface, overflow: "hidden",
      transition: "width 200ms ease", display: "flex", flexDirection: "column",
    }}>
      <Box sx={{ px: 1.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 0.75 }}>
        <Tooltip title={t("workflow.profiles.collapse")}>
          <IconButton size="small" onClick={onToggle} sx={{ width: 20, height: 20 }}>
            <ChevronRightOutlined sx={{ fontSize: 14 }} />
          </IconButton>
        </Tooltip>
        <KeyboardOutlined sx={{ fontSize: 14, color: tokens.primary.main }} />
        <Typography variant="caption" fontWeight={700} sx={{ fontSize: "0.72rem", flex: 1 }}>{t("workflow.profiles.title")}</Typography>
      </Box>

      <Box sx={{ p: 0.75, display: "flex", flexDirection: "column", gap: 0.5 }}>
        {filtered.map(p => (
          <Paper key={p.id} elevation={0} onClick={() => onSelectProfile(p.id)} sx={{
            px: 1, py: 0.75, cursor: "pointer",
            bgcolor: p.id === activeProfileId ? tokens.primary.subtle : tokens.bg.elevated,
            border: `1px solid ${p.id === activeProfileId ? tokens.primary.main : tokens.border.subtle}`,
            borderRadius: tokens.radius.md, "&:hover": { borderColor: tokens.border.strong }, transition: "all 120ms ease",
          }}>
            <Typography variant="body2" fontWeight={p.id === activeProfileId ? 700 : 500} sx={{ fontSize: "0.74rem" }}>{p.name}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.63rem" }}>{t("workflow.profiles.bindings", { count: p.bindings.length })}</Typography>
          </Paper>
        ))}
      </Box>

      <Divider sx={{ my: 0.5 }} />

      {active && (
        <Box sx={{ flex: 1, overflow: "auto", p: 1 }}>
          <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.tertiary, fontSize: "0.6rem", mb: 0.75, display: "block" }}>
            {t("workflow.profiles.bindingsTitle")}
          </Typography>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
            {active.bindings.map((b, i) => (
              <Tooltip key={i} title={editingIdx === i ? t("workflow.profiles.pressKey") : t("workflow.profiles.clickToRebind")} placement="left">
                <Box onClick={() => setEditingIdx(i)} sx={{
                  display: "flex", alignItems: "center", gap: 0.75, py: 0.4, px: 0.5,
                  cursor: "pointer", borderRadius: tokens.radius.sm,
                  bgcolor: editingIdx === i ? tokens.primary.subtle : "transparent",
                  border: editingIdx === i ? `1px solid ${tokens.primary.main}` : "1px solid transparent",
                  "&:hover": { bgcolor: tokens.bg.hover }, transition: "all 100ms ease",
                }}>
                  <Chip
                    label={editingIdx === i ? "…" : b.key === "" ? "—" : keyDisplayName(b.key)}
                    size="small"
                    sx={{
                      height: 18, minWidth: 24, fontSize: "0.62rem", fontWeight: 700,
                      bgcolor: b.key === "" ? `${tokens.accent.red}18` : tokens.bg.overlay,
                      color: b.key === "" ? tokens.accent.red : tokens.text.primary, fontFamily: "monospace",
                    }}
                  />
                  <Typography variant="caption" sx={{ fontSize: "0.66rem", color: tokens.text.secondary, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {b.label}
                  </Typography>
                </Box>
              </Tooltip>
            ))}
          </Box>
        </Box>
      )}
    </Box>
  );
}

// ── Main Workflow View ────────────────────────────────────────────────────
export default function WorkflowView() {
  const { setSidebarCollapsed } = useLayout();
  const { t } = useTranslation();
  const { token, userUuid } = useAuth();
  const { showToast } = useToast();
  const [assets, setAssets] = useState<Asset[]>([]);
  const [mode, setMode] = useState<WorkflowMode>("rating");
  const [currentIdx, setCurrentIdx] = useState(0);
  const [fullscreen, setFullscreen] = useState(false);
  const [ratings, setRatings] = useState<Record<string, number>>({});
  const [ratingReactionUuids, setRatingReactionUuids] = useState<Record<string, string>>({});
  // Tags are held as objects, not names: removal goes through the tag's uuid, and telling a
  // curated tag from a machine one needs its provenance.
  const [assetTags, setAssetTags] = useState<Record<string, WorkflowTag[]>>({});
  const [tagVocabulary, setTagVocabulary] = useState<string[]>([]);
  // What each asset ARRIVED carrying, so the screen can say "somebody already decided this one"
  // without the marker lighting up the moment the reviewer presses a key.
  const [initiallyRated, setInitiallyRated] = useState<Set<string>>(new Set());
  const [initiallyTagged, setInitiallyTagged] = useState<Record<string, number>>({});
  // The dedup queue is server state: the group carries its own status, so there is no local
  // decision map to drift out of sync with what was actually written.
  const [dedupGroups, setDedupGroups] = useState<DedupGroupResponse[]>([]);
  const [dedupLoaded, setDedupLoaded] = useState(false);
  const [dedupAssets, setDedupAssets] = useState<Record<string, AssetResponse>>({});
  const [dedupBusy, setDedupBusy] = useState(false);
  const [selectedClusterIdx, setSelectedClusterIdx] = useState(0);
  const [clusterDecisions, setClusterDecisions] = useState<Record<string, "confirmed" | "denied">>({});
  const [clusterPersonAssignments, setClusterPersonAssignments] = useState<Record<string, string>>({});
  const [selectedObjIdx, setSelectedObjIdx] = useState(0);
  const [objectDecisions, setObjectDecisions] = useState<Record<string, "confirmed" | "rejected">>({});
  const [llmDecisions, setLlmDecisions] = useState<Record<string, "approved" | "rejected">>({});
  const [profileSidebarOpen, setProfileSidebarOpen] = useState(true);
  const [profiles, setProfiles] = useState<KeyProfile[]>(DEFAULT_PROFILES);
  const [activeProfileId, setActiveProfileId] = useState(DEFAULT_PROFILES[0].id);
  const tagInputRef = useRef<HTMLInputElement>(null);
  const personInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!token) return;
    listAssets(token, { limit: PAGE_SIZE }).then(r => {
      const raw = (r.data ?? []).slice(0, 20);
      const loaded = raw.map(apiToWorkflowAsset);
      setAssets(loaded);
      // Built from the raw response rather than from Asset.tags, which is a list of names:
      // untagAsset needs the tag uuid, and the machine/curated distinction needs nodeKind.
      const map: Record<string, WorkflowTag[]> = {};
      const tagged: Record<string, number> = {};
      raw.forEach(a => {
        const tags = toWorkflowTags(a.tags);
        map[a.uuid] = tags;
        const curated = tags.filter(isCurated).length;
        if (curated > 0) tagged[a.uuid] = curated;
      });
      setAssetTags(map);
      setInitiallyTagged(tagged);
      // Hydrate this reviewer's previously persisted ratings so bulk-review work survives reloads.
      // Scoped to userUuid: reactions come back for every user, and adopting a colleague's reaction
      // uuid here would make the next keystroke overwrite their rating rather than write our own.
      hydrateAssetRatings(token, loaded.map(a => a.id), userUuid ?? undefined).then(({ ratings, reactionUuids }) => {
        setRatings(ratings);
        setRatingReactionUuids(reactionUuids);
        setInitiallyRated(new Set(Object.keys(ratings)));
      }).catch(() => {
        showToast(t("workflow.rating.hydrateFailed"), "error");
      });
    }).catch(() => {
      showToast(t("workflow.queue.loadFailed"), "error");
    });
  }, [token, userUuid, showToast, t]);

  // The tag vocabulary a reviewer picks from. Unscoped on purpose - see loadTagVocabulary. A
  // failure is not fatal: the input is freeSolo, so a reviewer can still type a tag.
  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    loadTagVocabulary(token)
      .then(names => { if (!cancelled) setTagVocabulary(names); })
      .catch(() => { if (!cancelled) showToast(t("workflow.tagging.vocabularyFailed"), "error"); });
    return () => { cancelled = true; };
  }, [token, showToast, t]);

  // The real review queue: groups a discovery run proposed and nobody has decided yet.
  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    listDedupGroups(token, { status: STATUS_PENDING, limit: PAGE_SIZE })
      .then(r => {
        if (cancelled) return;
        setDedupGroups(r.data ?? []);
        setDedupLoaded(true);
      })
      .catch(() => {
        if (cancelled) return;
        setDedupLoaded(true);
        showToast(t("workflow.dedup.loadFailed"), "error");
      });
    return () => { cancelled = true; };
  }, [token, showToast, t]);

  const currentAsset = assets[currentIdx] ?? assets[0];
  const currentGroup = dedupGroups[currentIdx] ?? dedupGroups[0];

  // A group carries only asset uuids; the filename, mime type and preview come from the asset itself.
  useEffect(() => {
    if (!token || !currentGroup) return;
    const missing = (currentGroup.members ?? [])
      .map(m => m.assetUuid)
      .filter(uuid => !dedupAssets[uuid]);
    if (missing.length === 0) return;
    let cancelled = false;
    Promise.all(missing.map(uuid => loadAsset(token, uuid).then(a => [uuid, a] as const).catch(() => null)))
      .then(results => {
        if (cancelled) return;
        const resolved = results.filter((r): r is readonly [string, AssetResponse] => r !== null);
        if (resolved.length === 0) return;
        setDedupAssets(prev => ({ ...prev, ...Object.fromEntries(resolved) }));
      });
    return () => { cancelled = true; };
  }, [token, currentGroup, dedupAssets]);

  const [currentFaces, setCurrentFaces] = useState<DetectedFace[]>([]);
  const [currentObjects, setCurrentObjects] = useState<DetectedObject[]>([]);

  useEffect(() => {
    if (!token || !currentAsset?.id) {
      setCurrentFaces([]);
      setCurrentObjects([]);
      return;
    }
    listAssetDetections(token, currentAsset.id).then(resp => {
      const faces: DetectedFace[] = [];
      const objects: DetectedObject[] = [];
      const decisions: Record<string, "confirmed" | "rejected"> = {};
      for (const d of (resp.data ?? [])) {
        // Seed the decision map from what the server already holds, so a reload shows the review
        // that was actually recorded rather than an empty queue.
        if (d.reviewStatus === "CONFIRMED") decisions[d.uuid] = "confirmed";
        else if (d.reviewStatus === "REJECTED") decisions[d.uuid] = "rejected";

        // "face" is the detection type FacedetectNode writes. Not to be confused with the workflow
        // mode of the same-ish name below, nor with the node's options key "facedetection".
        if (d.type === "face") {
          faces.push({
            id: d.uuid,
            assetId: d.assetUuid,
            timestamp: d.frameNumber,
            boundingBox: { x: d.bboxX, y: d.bboxY, width: d.bboxWidth, height: d.bboxHeight },
            confidence: d.confidence,
            thumbnailUrl: "",
            clusterId: (d.meta as Record<string, unknown>)?.clusterId as string | undefined,
          });
        } else if (d.type === "objectdetection") {
          objects.push({
            id: d.uuid,
            assetId: d.assetUuid,
            // correctedLabel first: once a reviewer has said what this actually is, that is the
            // answer to show. d.label is the column - reading it from meta rendered every object as
            // the literal string "objectdetection", because the node never wrote meta.label.
            label: d.correctedLabel ?? d.label ?? d.type,
            confidence: d.confidence,
            boundingBox: { x: d.bboxX, y: d.bboxY, width: d.bboxWidth, height: d.bboxHeight },
            timestamp: d.frameNumber,
          });
        }
      }
      setCurrentFaces(faces);
      setCurrentObjects(objects);
      setObjectDecisions(decisions);
    }).catch(() => {
      setCurrentFaces([]);
      setCurrentObjects([]);
      setObjectDecisions({});
    });
  }, [token, currentAsset?.id]);

  /**
   * The clusters the face-detection node proposed within this asset, with their members.
   *
   * Previously joined against a `FACE_CLUSTERS` mock array that is empty, so the face review pane
   * rendered nothing whatever the server held.
   */
  const [assetClusters, setAssetClusters] = useState<FaceCluster[]>([]);

  useEffect(() => {
    if (!token || !currentAsset?.id) {
      setAssetClusters([]);
      setClusterDecisions({});
      return;
    }
    let cancelled = false;
    listAssetClusters(token, currentAsset.id).then(async resp => {
      const clusters = resp.data ?? [];
      const decisions: Record<string, "confirmed" | "denied"> = {};
      const loaded: FaceCluster[] = [];
      for (const c of clusters) {
        if (c.reviewStatus === "CONFIRMED") decisions[c.uuid] = "confirmed";
        else if (c.reviewStatus === "REJECTED") decisions[c.uuid] = "denied";
        const members = await listClusterMembers(token, c.uuid).catch(() => null);
        loaded.push({
          id: c.uuid,
          label: c.name ?? "",
          representativeThumbnailUrl: "",
          faceIds: (members?.members ?? []).map(m => m.detectionUuid).filter((id): id is string => Boolean(id)),
          faceCount: members?.members?.length ?? 0,
          assetId: currentAsset.id,
        });
      }
      if (cancelled) return;
      setAssetClusters(loaded);
      setClusterDecisions(decisions);
    }).catch(() => {
      if (cancelled) return;
      setAssetClusters([]);
      setClusterDecisions({});
    });
    return () => { cancelled = true; };
  }, [token, currentAsset?.id]);

  const currentFaceClusters = useMemo(() => {
    // Membership comes from the cluster's own member list, not from a clusterId on the detection:
    // the join lives in embedding_cluster, and the face detections carry no cluster pointer.
    return assetClusters.map(cluster => ({
      cluster,
      faces: currentFaces.filter(f => cluster.faceIds.includes(f.id)),
    }));
  }, [assetClusters, currentFaces]);
  // The dedup queue counts groups, not assets.
  const maxIdx = mode === "deduplication" ? dedupGroups.length - 1 : assets.length - 1;

  useEffect(() => {
    const profile = profiles.find(p => p.mode === mode);
    if (profile) setActiveProfileId(profile.id);
    setCurrentIdx(0);
    setSelectedClusterIdx(0);
    setSelectedObjIdx(0);
  }, [mode, profiles]);

  const toggleFullscreen = useCallback(() => {
    setFullscreen(prev => {
      const next = !prev;
      if (next) { setProfileSidebarOpen(false); setSidebarCollapsed(true); }
      else { setProfileSidebarOpen(true); setSidebarCollapsed(false); }
      return next;
    });
  }, [setSidebarCollapsed]);

  const goNext = useCallback(() => { setCurrentIdx(i => Math.min(i + 1, maxIdx)); setSelectedClusterIdx(0); setSelectedObjIdx(0); }, [maxIdx]);
  const goPrev = useCallback(() => { setCurrentIdx(i => Math.max(i - 1, 0)); setSelectedClusterIdx(0); setSelectedObjIdx(0); }, []);
  /**
   * Show the rating at once, then persist it — and take it back if the write fails.
   *
   * At a keystroke per decision a POST that silently disappears is worse than no persistence at
   * all: the stars say the work is done and the server never heard about it.
   */
  const handleRate = useCallback((rating: number) => {
    const assetId = currentAsset?.id;
    if (!assetId) return;
    const previous = ratings[assetId];
    setRatings(prev => ({ ...prev, [assetId]: rating }));
    if (!token) return;
    persistAssetRating(token, assetId, rating, ratingReactionUuids[assetId])
      .then(uuid => setRatingReactionUuids(prev => ({ ...prev, [assetId]: uuid })))
      .catch(() => {
        setRatings(prev => {
          const next = { ...prev };
          if (previous === undefined) delete next[assetId];
          else next[assetId] = previous;
          return next;
        });
        showToast(t("workflow.rating.saveFailed"), "error");
      });
  }, [currentAsset, token, ratings, ratingReactionUuids, showToast, t]);
  /**
   * Attach a tag, showing the chip at once and taking it back if the write fails.
   *
   * The optimistic chip carries a placeholder uuid, and the rollback removes *that* chip rather
   * than the last one or the one with this name: at a keystroke per decision several writes are in
   * flight, and reverting by position would take out whichever chip happens to sit there by then.
   */
  const handleAddTag = useCallback((rawName: string) => {
    const name = rawName.trim();
    const assetId = currentAsset?.id;
    if (!name || !assetId || !token) return;
    const existing = assetTags[assetId] ?? [];
    if (existing.some(tag => tag.name.toLowerCase() === name.toLowerCase())) return;

    const pending = pendingTag(name);
    setAssetTags(prev => ({ ...prev, [assetId]: [...(prev[assetId] ?? []), pending] }));

    addAssetTag(token, assetId, name)
      .then(saved => setAssetTags(prev => ({
        ...prev,
        [assetId]: (prev[assetId] ?? []).map(tag => (tag.uuid === pending.uuid ? saved : tag)),
      })))
      .catch(() => {
        setAssetTags(prev => ({
          ...prev,
          [assetId]: (prev[assetId] ?? []).filter(tag => tag.uuid !== pending.uuid),
        }));
        showToast(t("workflow.tagging.addFailed", { tag: name }), "error");
      });
  }, [currentAsset, token, assetTags, showToast, t]);

  /** Remove a tag, restoring it at its original position if the DELETE fails. */
  const handleRemoveTag = useCallback((tag: WorkflowTag) => {
    const assetId = currentAsset?.id;
    // A pending chip has no server-side uuid yet, so there is nothing to delete.
    if (!assetId || !token || isPending(tag)) return;
    const index = (assetTags[assetId] ?? []).findIndex(candidate => candidate.uuid === tag.uuid);
    setAssetTags(prev => ({ ...prev, [assetId]: (prev[assetId] ?? []).filter(candidate => candidate.uuid !== tag.uuid) }));

    removeAssetTag(token, assetId, tag.uuid).catch(() => {
      setAssetTags(prev => {
        const current = prev[assetId] ?? [];
        if (current.some(candidate => candidate.uuid === tag.uuid)) return prev;
        const restored = [...current];
        restored.splice(index < 0 ? restored.length : index, 0, tag);
        return { ...prev, [assetId]: restored };
      });
      showToast(t("workflow.tagging.removeFailed", { tag: tag.name }), "error");
    });
  }, [currentAsset, token, assetTags, showToast, t]);
  /**
   * Write a dedup decision, showing it immediately and taking it back if the write fails.
   *
   * A row that looks decided but was never PATCHed is the exact failure this screen used to have:
   * a reviewer works through twenty groups, the server learns nothing, and the apply node moves
   * nothing. So the optimistic chip is always paired with a rollback.
   */
  const applyDedupDecision = useCallback(async (
    optimistic: DedupGroupResponse,
    write: (token: string, group: DedupGroupResponse) => Promise<DedupGroupResponse>,
    failureKey: string,
  ) => {
    const group = dedupGroups[currentIdx];
    if (!group || !token) return;
    setDedupGroups(prev => replaceGroup(prev, optimistic));
    setDedupBusy(true);
    try {
      const saved = await write(token, group);
      setDedupGroups(prev => replaceGroup(prev, saved));
    } catch {
      setDedupGroups(prev => replaceGroup(prev, group));
      showToast(t(failureKey), "error");
    } finally {
      setDedupBusy(false);
    }
  }, [dedupGroups, currentIdx, token, showToast, t]);

  const handleConfirmDedup = useCallback(() => {
    const group = dedupGroups[currentIdx];
    if (!group) return;
    void applyDedupDecision(
      { ...group, status: STATUS_CONFIRMED },
      (tk, g) => decideGroup(tk, g, STATUS_CONFIRMED),
      "workflow.dedup.saveFailed");
  }, [dedupGroups, currentIdx, applyDedupDecision]);

  const handleRejectDedup = useCallback(() => {
    const group = dedupGroups[currentIdx];
    if (!group) return;
    void applyDedupDecision(
      { ...group, status: STATUS_REJECTED },
      (tk, g) => decideGroup(tk, g, STATUS_REJECTED),
      "workflow.dedup.saveFailed");
  }, [dedupGroups, currentIdx, applyDedupDecision]);

  /** Keep a different member. Sent as a status-preserving PATCH so picking a file decides nothing. */
  const handleMakeKeep = useCallback((assetUuid: string) => {
    const group = dedupGroups[currentIdx];
    if (!group) return;
    void applyDedupDecision(
      { ...group, keepAssetUuid: assetUuid },
      (tk, g) => reassignKeep(tk, g, assetUuid),
      "workflow.dedup.keepFailed");
  }, [dedupGroups, currentIdx, applyDedupDecision]);
  /**
   * Show the verdict at once, then persist it — and take it back if the write fails.
   *
   * At a keystroke per decision, a POST that silently disappears is worse than no persistence at
   * all: the card says the work is done and the server never heard about it. Same shape as
   * {@link applyDedupDecision} and the rating handler.
   */
  const applyClusterDecision = useCallback((
    id: string,
    optimistic: "confirmed" | "denied",
    write: (tk: string, uuid: string) => Promise<unknown>,
  ) => {
    if (!token) return;
    const previous = clusterDecisions[id];
    setClusterDecisions(prev => ({ ...prev, [id]: optimistic }));
    void write(token, id).catch(() => {
      setClusterDecisions(prev => {
        const next = { ...prev };
        if (previous === undefined) delete next[id];
        else next[id] = previous;
        return next;
      });
      showToast(t("workflow.cluster.saveFailed"), "error");
    });
  }, [token, clusterDecisions, showToast, t]);

  const handleConfirmCluster = useCallback((id: string) => {
    // A cluster confirm without a personUuid creates a person, which the server refuses unless it
    // is given a name. The reviewer supplies one through "assign person"; until then the grouping
    // itself is confirmed under the name already on the card.
    const name = clusterPersonAssignments[id] ?? assetClusters.find(c => c.id === id)?.label;
    applyClusterDecision(id, "confirmed", (tk, uuid) => confirmCluster(tk, uuid, { alias: name || undefined, name: name || undefined }));
  }, [applyClusterDecision, clusterPersonAssignments, assetClusters]);

  const handleDenyCluster = useCallback((id: string) => {
    applyClusterDecision(id, "denied", (tk, uuid) => rejectCluster(tk, uuid));
  }, [applyClusterDecision]);

  const handleAssignPerson = useCallback((clusterId: string, name: string) => { setClusterPersonAssignments(prev => ({ ...prev, [clusterId]: name })); }, []);

  /**
   * Persist object-detection verdicts, optimistically, reversibly and in batches.
   *
   * Keyboard review produces decisions faster than one request each can carry them, so a verdict is
   * queued and whatever accumulated within {@link REVIEW_FLUSH_MS} goes out as one `review-bulk`
   * call. The optimistic state is applied immediately; a failed flush rolls back exactly the
   * detections that were in it, leaving later decisions alone.
   */
  const pendingReviews = useRef<Map<string, { status: "CONFIRMED" | "REJECTED"; previous?: "confirmed" | "rejected" }>>(new Map());
  const reviewFlushTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const flushObjectReviews = useCallback(() => {
    reviewFlushTimer.current = null;
    const batch = pendingReviews.current;
    if (batch.size === 0 || !token || !currentAsset?.id) return;
    pendingReviews.current = new Map();

    const entries = [...batch.entries()];
    void bulkReviewDetections(token, currentAsset.id, {
      reviews: entries.map(([uuid, { status }]) => ({ uuid, status })),
    }).then(response => {
      // A partially applied batch is reported rather than thrown, so the failed count is the only
      // signal that some of these decisions did not land.
      if (response.failed > 0) {
        showToast(t("workflow.object.saveFailed"), "error");
      }
    }).catch(() => {
      setObjectDecisions(prev => {
        const next = { ...prev };
        for (const [uuid, { previous }] of entries) {
          if (previous === undefined) delete next[uuid];
          else next[uuid] = previous;
        }
        return next;
      });
      showToast(t("workflow.object.saveFailed"), "error");
    });
  }, [token, currentAsset?.id, showToast, t]);

  const applyObjectDecision = useCallback((id: string, optimistic: "confirmed" | "rejected") => {
    if (!token || !currentAsset?.id) return;
    // Keep the value from the first queued decision for this detection: it is the one that
    // describes the state before the batch, which is what a rollback has to restore.
    if (!pendingReviews.current.has(id)) {
      pendingReviews.current.set(id, {
        status: optimistic === "confirmed" ? "CONFIRMED" : "REJECTED",
        previous: objectDecisions[id],
      });
    } else {
      pendingReviews.current.get(id)!.status = optimistic === "confirmed" ? "CONFIRMED" : "REJECTED";
    }
    setObjectDecisions(prev => ({ ...prev, [id]: optimistic }));

    if (reviewFlushTimer.current !== null) clearTimeout(reviewFlushTimer.current);
    reviewFlushTimer.current = setTimeout(flushObjectReviews, REVIEW_FLUSH_MS);
  }, [token, currentAsset?.id, objectDecisions, flushObjectReviews]);

  // Leaving the asset or unmounting must not strand a queued batch.
  useEffect(() => {
    return () => {
      if (reviewFlushTimer.current !== null) {
        clearTimeout(reviewFlushTimer.current);
        flushObjectReviews();
      }
    };
  }, [flushObjectReviews]);

  const handleConfirmObject = useCallback((id: string) => { applyObjectDecision(id, "confirmed"); }, [applyObjectDecision]);
  const handleRejectObject = useCallback((id: string) => { applyObjectDecision(id, "rejected"); }, [applyObjectDecision]);
  const handleApproveLlm = useCallback((assetId: string) => { setLlmDecisions(prev => ({ ...prev, [assetId]: "approved" })); }, []);
  const handleRejectLlm = useCallback((assetId: string) => { setLlmDecisions(prev => ({ ...prev, [assetId]: "rejected" })); }, []);
  const handleUpdateBinding = useCallback((profileId: string, bindingIdx: number, newKey: string) => {
    setProfiles(prev => prev.map(p => {
      if (p.id !== profileId) return p;
      const nb = [...p.bindings];
      nb[bindingIdx] = { ...nb[bindingIdx], key: newKey };
      if (newKey === "") nb[bindingIdx].label = "Not bound";
      return { ...p, bindings: nb };
    }));
  }, []);

  // Master keyboard handler
  useEffect(() => {
    const profile = profiles.find(p => p.id === activeProfileId);
    if (!profile) return;
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      const isInput = target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable;
      if (e.key === "f" && !isInput) { e.preventDefault(); toggleFullscreen(); return; }
      const binding = profile.bindings.find(b => b.key !== "" && b.key === e.key);
      if (!binding) return;
      if (isInput && binding.action !== "blur_tags") return;
      switch (binding.action) {
        case "next_asset": e.preventDefault(); goNext(); break;
        case "prev_asset": e.preventDefault(); goPrev(); break;
        case "set_rating": e.preventDefault(); if (binding.param) handleRate(parseInt(binding.param, 10)); break;
        case "focus_tags": e.preventDefault(); tagInputRef.current?.focus(); break;
        case "blur_tags": e.preventDefault(); tagInputRef.current?.blur(); break;
        case "confirm_dedup": e.preventDefault(); handleConfirmDedup(); break;
        case "reject_dedup": e.preventDefault(); handleRejectDedup(); break;
        case "confirm_cluster": e.preventDefault(); if (currentFaceClusters[selectedClusterIdx]) handleConfirmCluster(currentFaceClusters[selectedClusterIdx].cluster.id); break;
        case "deny_cluster": e.preventDefault(); if (currentFaceClusters[selectedClusterIdx]) handleDenyCluster(currentFaceClusters[selectedClusterIdx].cluster.id); break;
        case "next_cluster": e.preventDefault(); setSelectedClusterIdx(i => (i + 1) % Math.max(1, currentFaceClusters.length)); break;
        case "prev_cluster": e.preventDefault(); setSelectedClusterIdx(i => (i - 1 + currentFaceClusters.length) % Math.max(1, currentFaceClusters.length)); break;
        case "focus_person": e.preventDefault(); personInputRef.current?.focus(); break;
        case "confirm_object": e.preventDefault(); if (currentObjects[selectedObjIdx]) handleConfirmObject(currentObjects[selectedObjIdx].id); break;
        case "approve_llm": e.preventDefault(); if (currentAsset) handleApproveLlm(currentAsset.id); break;
        case "reject_llm": e.preventDefault(); if (currentAsset) handleRejectLlm(currentAsset.id); break;
        case "rerun_llm": e.preventDefault(); break;
        case "reject_object": e.preventDefault(); if (currentObjects[selectedObjIdx]) handleRejectObject(currentObjects[selectedObjIdx].id); break;
        case "next_detection": e.preventDefault(); setSelectedObjIdx(i => (i + 1) % Math.max(1, currentObjects.length)); break;
        case "prev_detection": e.preventDefault(); setSelectedObjIdx(i => (i - 1 + currentObjects.length) % Math.max(1, currentObjects.length)); break;
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [activeProfileId, profiles, goNext, goPrev, handleRate, handleConfirmDedup, handleRejectDedup,
      toggleFullscreen, handleConfirmCluster, handleDenyCluster, handleConfirmObject, handleRejectObject,
      handleApproveLlm, handleRejectLlm,
      currentFaceClusters, currentObjects, selectedClusterIdx, selectedObjIdx, currentAsset]);

  return (
    <Box sx={{ display: "flex", height: "100%", overflow: "hidden", bgcolor: tokens.bg.base }}>
      {/* Main content */}
      <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden" }}>
        {/* Toolbar */}
        <Box sx={{ px: 2.5, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", alignItems: "center", gap: 1.5 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
            <SpeedOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
            <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem" }}>Workflow</Typography>
            <Tooltip title="Workflows provide keyboard-driven bulk review modes for rating, tagging, deduplication, and detection tasks." arrow><HelpOutlineOutlined sx={{ fontSize: 14, color: tokens.text.tertiary, cursor: "help" }} /></Tooltip>
          </Box>
          <ToggleButtonGroup value={mode} exclusive onChange={(_, v) => v && setMode(v)} size="small" sx={{ ml: 0.5 }}>
            <ToggleButton value="rating" sx={{ textTransform: "none", fontSize: "0.72rem", px: 1 }}>
              <StarOutlined sx={{ fontSize: 13, mr: 0.5 }} /> {t("workflow.mode.rating")}
            </ToggleButton>
            <ToggleButton value="tagging" sx={{ textTransform: "none", fontSize: "0.72rem", px: 1 }}>
              <LocalOfferOutlined sx={{ fontSize: 13, mr: 0.5 }} /> {t("workflow.mode.tagging")}
            </ToggleButton>
            <ToggleButton value="deduplication" data-testid="workflow-mode-deduplication" sx={{ textTransform: "none", fontSize: "0.72rem", px: 1 }}>
              <ContentCopyOutlined sx={{ fontSize: 13, mr: 0.5 }} /> {t("workflow.mode.dedup")}
            </ToggleButton>
            <ToggleButton value="llm" sx={{ textTransform: "none", fontSize: "0.72rem", px: 1 }}>
              <AutoAwesomeOutlined sx={{ fontSize: 13, mr: 0.5 }} /> {t("workflow.mode.llm")}
            </ToggleButton>
            <ToggleButton value="facedetection" sx={{ textTransform: "none", fontSize: "0.72rem", px: 1 }}>
              <FaceOutlined sx={{ fontSize: 13, mr: 0.5 }} /> {t("workflow.mode.faces")}
            </ToggleButton>
            <ToggleButton value="objectdetection" data-testid="workflow-mode-objectdetection" sx={{ textTransform: "none", fontSize: "0.72rem", px: 1 }}>
              <CenterFocusStrongOutlined sx={{ fontSize: 13, mr: 0.5 }} /> {t("workflow.mode.objects")}
            </ToggleButton>
          </ToggleButtonGroup>
          <Box sx={{ flex: 1 }} />
          <Tooltip title={fullscreen ? t("workflow.tooltip.exitFullscreen") : t("workflow.tooltip.fullscreen")}>
            <IconButton size="small" onClick={toggleFullscreen}>
              {fullscreen ? <FullscreenExitOutlined sx={{ fontSize: 18 }} /> : <FullscreenOutlined sx={{ fontSize: 18 }} />}
            </IconButton>
          </Tooltip>
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
          {!profileSidebarOpen && (
            <Tooltip title={t("workflow.tooltip.showProfiles")}>
              <IconButton size="small" onClick={() => setProfileSidebarOpen(true)}>
                <TuneOutlined sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
          )}
        </Box>

        {/* Content area */}
        <Box sx={{ flex: 1, overflow: "auto", p: 2.5, display: "flex" }}>
          {mode === "rating" && currentAsset && (
            <RatingMode asset={currentAsset} ratings={ratings} onRate={handleRate}
              tagInputRef={tagInputRef} assetTags={assetTags[currentAsset.id] ?? []}
              vocabulary={tagVocabulary} onAddTag={handleAddTag} onRemoveTag={handleRemoveTag}
              alreadyRated={initiallyRated.has(currentAsset.id)} />
          )}
          {mode === "tagging" && currentAsset && (
            <TaggingMode asset={currentAsset} tagInputRef={tagInputRef}
              assetTags={assetTags[currentAsset.id] ?? []} vocabulary={tagVocabulary}
              onAddTag={handleAddTag} onRemoveTag={handleRemoveTag}
              alreadyTagged={initiallyTagged[currentAsset.id] ?? 0} />
          )}
          {mode === "deduplication" && currentGroup && (
            <DeduplicationMode group={currentGroup} assets={dedupAssets}
              onConfirm={handleConfirmDedup} onReject={handleRejectDedup}
              onMakeKeep={handleMakeKeep} busy={dedupBusy} />
          )}
          {mode === "deduplication" && !currentGroup && dedupLoaded && (
            <Box data-testid="dedup-empty" sx={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center" }}>
              <EmptyState icon={ContentCopyOutlined} title={t("workflow.dedup.emptyState.title")}
                description={t("workflow.dedup.emptyState.description")} />
            </Box>
          )}
          {mode === "facedetection" && currentAsset && (
            <FaceDetectionMode asset={currentAsset} faces={currentFaces} clusters={currentFaceClusters}
              persons={PERSONS} selectedClusterIdx={selectedClusterIdx} onSelectCluster={setSelectedClusterIdx}
              clusterDecisions={clusterDecisions} onConfirmCluster={handleConfirmCluster}
              onDenyCluster={handleDenyCluster} clusterPersonAssignments={clusterPersonAssignments}
              onAssignPerson={handleAssignPerson} personInputRef={personInputRef} />
          )}
          {mode === "llm" && currentAsset && (
            <LLMMode asset={currentAsset} llmDecisions={llmDecisions}
              onApprove={handleApproveLlm} onReject={handleRejectLlm} />
          )}
          {mode === "objectdetection" && currentAsset && (
            <ObjectDetectionMode asset={currentAsset} objects={currentObjects}
              selectedIdx={selectedObjIdx} onSelectIdx={setSelectedObjIdx}
              decisions={objectDecisions} onConfirm={handleConfirmObject} onReject={handleRejectObject} />
          )}
        </Box>

        {/* Keyboard hint bar */}
        <Box sx={{ px: 2.5, py: 0.75, borderTop: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
          <KeyboardOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
          {mode === "rating" && (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}
              dangerouslySetInnerHTML={{ __html: t("workflow.hint.rating") }} />
          )}
          {mode === "tagging" && (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}
              dangerouslySetInnerHTML={{ __html: t("workflow.hint.tagging") }} />
          )}
          {mode === "deduplication" && (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}
              dangerouslySetInnerHTML={{ __html: t("workflow.hint.dedup") }} />
          )}
          {mode === "llm" && (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}
              dangerouslySetInnerHTML={{ __html: t("workflow.hint.llm") }} />
          )}
          {mode === "facedetection" && (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}
              dangerouslySetInnerHTML={{ __html: t("workflow.hint.facedetection") }} />
          )}
          {mode === "objectdetection" && (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.68rem" }}
              dangerouslySetInnerHTML={{ __html: t("workflow.hint.objectdetection") }} />
          )}
        </Box>
      </Box>

      {/* Key profiles sidebar (right side) */}
      <ProfilesSidebar profiles={profiles} activeProfileId={activeProfileId} mode={mode}
        onSelectProfile={setActiveProfileId} collapsed={!profileSidebarOpen}
        onToggle={() => setProfileSidebarOpen(v => !v)} onUpdateBinding={handleUpdateBinding} />
    </Box>
  );
}
