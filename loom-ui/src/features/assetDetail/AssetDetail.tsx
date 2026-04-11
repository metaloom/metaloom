import React, { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  Box, Typography, Chip, Avatar, Paper, IconButton, Tab, Tabs,
  Divider, Tooltip, LinearProgress, Stack, TextField, InputAdornment,
  Menu, MenuItem, ListItemIcon, ListItemText,
} from "@mui/material";
import {
  ArrowBack, PlayArrowOutlined, PauseOutlined, PlayCircleOutline,
  ImageOutlined, ChatBubbleOutlineOutlined, BookmarkBorderOutlined,
  ThumbUpAltOutlined, TaskAltOutlined, AccountTreeOutlined,
  FlagOutlined, StarBorderOutlined, HelpOutlineOutlined,
  CheckCircleOutlineOutlined, AccessTimeOutlined,
  ZoomInOutlined, ZoomOutOutlined, CenterFocusStrongOutlined,
  FaceOutlined, GroupWorkOutlined, PersonOutlined,
  ArrowUpwardOutlined, ArrowDownwardOutlined, SearchOutlined,
  MoreVertOutlined, SendOutlined, AddTaskOutlined,
  CollectionsOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Asset, AssetType, AssetStatus, Comment, Annotation, Reaction, Task, TranscriptSection, DetectedFace, FaceCluster, Person } from "../../types";
import {
  mockCommentService, mockAnnotationService,
  mockReactionService, mockTranscriptService,
  mockFaceDetectionService,
} from "../../mock/services";
import { USERS, COLLECTIONS, PIPELINES } from "../../mock/data";
import { useAuth } from "../../context/AuthContext";
import { loadAsset as apiLoadAsset, AssetResponse } from "../../api/assets";

/** Map a Loom REST AssetResponse to the local Asset type used by the UI. */
function apiToAsset(r: AssetResponse): Asset {
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
    projectId: "",
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
    taskIds: [],
    createdAt: r.status?.created ?? "",
    updatedAt: r.status?.edited ?? "",
    metadata: {},
  };
}

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h > 0) return `${h}:${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

function formatBytes(bytes: number): string {
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(1)} TB`;
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(0)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

function userName(userId: string) {
  return USERS.find(u => u.id === userId)?.name ?? userId;
}

const reactionIcon: Record<string, React.ReactNode> = {
  approve: <CheckCircleOutlineOutlined sx={{ fontSize: 13 }} />,
  flag: <FlagOutlined sx={{ fontSize: 13 }} />,
  reject: <ThumbUpAltOutlined sx={{ fontSize: 13, transform: "scaleY(-1)" }} />,
  favorite: <StarBorderOutlined sx={{ fontSize: 13 }} />,
  question: <HelpOutlineOutlined sx={{ fontSize: 13 }} />,
};

const reactionColor: Record<string, string> = {
  approve: tokens.accent.green,
  flag: tokens.accent.red,
  reject: tokens.accent.amber,
  favorite: "#f5c842",
  question: tokens.accent.blue,
};

// Tag parent hierarchy (mock) — tag → parent chain from root to immediate parent
const TAG_HIERARCHY: Record<string, string[]> = {
  suv: ["vehicles", "cars"],
  sedan: ["vehicles", "cars"],
  supercars: ["vehicles", "cars"],
  cars: ["vehicles"],
  trucks: ["vehicles"],
  vehicles: [],
  nature: ["outdoor"],
  wildlife: ["outdoor", "nature"],
  landscape: ["outdoor"],
  aerial: ["outdoor"],
  drone: ["outdoor", "aerial"],
  portrait: ["photography"],
  macro: ["photography"],
  street: ["photography", "outdoor"],
  fashion: ["photography"],
  studio: ["photography", "indoor"],
  indoor: [],
  outdoor: [],
  urban: ["outdoor"],
  architecture: ["outdoor", "urban"],
  food: [],
  travel: [],
  sports: [],
  music: [],
  hero: ["editorial"],
  archive: ["editorial"],
  "b-roll": ["editorial"],
  timelapse: ["editorial", "technique"],
  interview: ["editorial"],
};

function tagBreadcrumb(tag: string): string {
  const parents = TAG_HIERARCHY[tag.toLowerCase()];
  if (!parents || parents.length === 0) return "";
  return [...parents, tag.toLowerCase()].join(" → ");
}

// ── Video Timeline ────────────────────────────────────────────────────────
interface TimelineMarker {
  time: number;
  endTime?: number;
  type: "comment" | "annotation" | "reaction";
  color: string;
  label: string;
  id: string;
}

function VideoTimeline({
  duration,
  currentTime,
  markers,
  hoveredMarkerId,
  onSeek,
  onMarkerClick,
  onMarkerHover,
  onMarkerDrag,
}: {
  duration: number;
  currentTime: number;
  markers: TimelineMarker[];
  hoveredMarkerId: string | null;
  onSeek: (t: number) => void;
  onMarkerClick: (id: string, type: string) => void;
  onMarkerHover: (id: string | null) => void;
  onMarkerDrag?: (markerId: string, edge: "start" | "end", newTime: number) => void;
}) {
  const barRef = useRef<HTMLDivElement>(null);
  const markerBarRef = useRef<HTMLDivElement>(null);
  const [draggingMarker, setDraggingMarker] = useState<{ id: string; edge: "start" | "end" } | null>(null);

  const handleBarClick = (e: React.MouseEvent) => {
    if (!barRef.current) return;
    const rect = barRef.current.getBoundingClientRect();
    const pct = (e.clientX - rect.left) / rect.width;
    onSeek(Math.max(0, Math.min(duration, pct * duration)));
  };

  const handleMarkerBarClick = (e: React.MouseEvent) => {
    if (draggingMarker) return;
    if (!markerBarRef.current) return;
    const rect = markerBarRef.current.getBoundingClientRect();
    const pct = (e.clientX - rect.left) / rect.width;
    onSeek(Math.max(0, Math.min(duration, pct * duration)));
  };

  // Draggable marker handle
  const handleMarkerDragStart = useCallback((e: React.MouseEvent, markerId: string, edge: "start" | "end") => {
    e.stopPropagation();
    e.preventDefault();
    setDraggingMarker({ id: markerId, edge });
    const onMove = (ev: MouseEvent) => {
      if (!markerBarRef.current) return;
      const rect = markerBarRef.current.getBoundingClientRect();
      const pct = Math.max(0, Math.min(1, (ev.clientX - rect.left) / rect.width));
      const time = Math.round(pct * duration * 10) / 10;
      onSeek(time);
      onMarkerDrag?.(markerId, edge, time);
    };
    const onUp = () => {
      setDraggingMarker(null);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, [duration, onSeek, onMarkerDrag]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: "4px" }}>
      {/* Top: thin progress bar */}
      <Box sx={{ position: "relative", height: 6, cursor: "pointer" }}>
        <Box
          ref={barRef}
          onClick={handleBarClick}
          sx={{
            position: "absolute", left: 0, right: 0, top: 0, bottom: 0,
            bgcolor: tokens.bg.overlay, borderRadius: "3px 3px 0 0",
          }}
        >
          <Box sx={{ position: "absolute", left: 0, top: 0, bottom: 0, width: `${(currentTime / duration) * 100}%`, bgcolor: tokens.primary.main, borderRadius: "3px 3px 0 0", transition: "width 50ms linear" }} />
        </Box>
      </Box>

      {/* Bottom: marker/highlight area */}
      <Box
        ref={markerBarRef}
        onClick={handleMarkerBarClick}
        sx={{
          position: "relative", height: 28, bgcolor: `${tokens.bg.overlay}88`,
          borderRadius: "0 0 3px 3px", cursor: "pointer",
          borderTop: `1px solid ${tokens.border.subtle}`,
        }}
      >
        {/* Range highlights for all annotations with endTime */}
        {markers.filter(m => m.endTime && m.endTime > m.time).map(m => {
          const left = (m.time / duration) * 100;
          const width = ((m.endTime! - m.time) / duration) * 100;
          const isHovered = hoveredMarkerId === m.id;
          return (
            <Box
              key={`range_${m.id}`}
              sx={{
                position: "absolute",
                left: `${left}%`,
                width: `${width}%`,
                top: 4,
                bottom: 4,
                bgcolor: isHovered ? `${m.color}44` : `${m.color}22`,
                borderRadius: 1,
                transition: "background-color 120ms ease",
                zIndex: 1,
              }}
              onMouseEnter={() => onMarkerHover(m.id)}
              onMouseLeave={() => onMarkerHover(null)}
            >
              {/* Draggable start handle */}
              <Box
                onMouseDown={(e) => handleMarkerDragStart(e, m.id, "start")}
                sx={{
                  position: "absolute", left: -4, top: 0, bottom: 0, width: 8,
                  cursor: "ew-resize", zIndex: 3, display: "flex", alignItems: "center", justifyContent: "center",
                  "&:hover .handle-line": { bgcolor: m.color },
                }}
              >
                <Box className="handle-line" sx={{ width: 2, height: 14, borderRadius: 1, bgcolor: isHovered ? m.color : `${m.color}66`, transition: "background-color 100ms ease" }} />
              </Box>
              {/* Draggable end handle */}
              <Box
                onMouseDown={(e) => handleMarkerDragStart(e, m.id, "end")}
                sx={{
                  position: "absolute", right: -4, top: 0, bottom: 0, width: 8,
                  cursor: "ew-resize", zIndex: 3, display: "flex", alignItems: "center", justifyContent: "center",
                  "&:hover .handle-line": { bgcolor: m.color },
                }}
              >
                <Box className="handle-line" sx={{ width: 2, height: 14, borderRadius: 1, bgcolor: isHovered ? m.color : `${m.color}66`, transition: "background-color 100ms ease" }} />
              </Box>
            </Box>
          );
        })}

        {/* Point markers */}
        {markers.map(m => {
          const isHovered = hoveredMarkerId === m.id;
          return (
            <Tooltip key={m.id} title={m.label}>
              <Box
                onClick={(e) => { e.stopPropagation(); onMarkerClick(m.id, m.type); }}
                onMouseEnter={() => onMarkerHover(m.id)}
                onMouseLeave={() => onMarkerHover(null)}
                sx={{
                  position: "absolute",
                  left: `${(m.time / duration) * 100}%`,
                  top: "50%",
                  transform: "translate(-50%, -50%)",
                  width: isHovered ? 12 : 8,
                  height: isHovered ? 12 : 8,
                  borderRadius: "50%",
                  bgcolor: m.color,
                  border: `2px solid ${isHovered ? tokens.bg.base : tokens.bg.elevated}`,
                  boxShadow: isHovered ? `0 0 8px ${m.color}` : "none",
                  cursor: "pointer",
                  zIndex: isHovered ? 5 : 4,
                  transition: "width 100ms, height 100ms, box-shadow 100ms",
                }}
              />
            </Tooltip>
          );
        })}

        {/* Playhead indicator */}
        <Box sx={{ position: "absolute", left: `${(currentTime / duration) * 100}%`, top: 0, bottom: 0, width: 1.5, bgcolor: tokens.primary.main, zIndex: 6, pointerEvents: "none", transition: "left 50ms linear" }} />
      </Box>

      <Box sx={{ display: "flex", justifyContent: "space-between", mt: 0.25 }}>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
          {formatDuration(Math.round(currentTime))}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
          {formatDuration(duration)}
        </Typography>
      </Box>
    </Box>
  );
}

// ── Zoom/Pan Image Viewer ─────────────────────────────────────────────────
function ZoomableImage({ src, alt }: { src: string; alt: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const dragging = useRef(false);
  const lastMouse = useRef({ x: 0, y: 0 });

  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    setScale(prev => {
      const next = Math.min(8, Math.max(1, prev - e.deltaY * 0.002));
      if (next <= 1) setPan({ x: 0, y: 0 });
      return next;
    });
  }, []);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (scale <= 1) return;
    e.preventDefault();
    dragging.current = true;
    lastMouse.current = { x: e.clientX, y: e.clientY };
  }, [scale]);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (!dragging.current) return;
    const dx = e.clientX - lastMouse.current.x;
    const dy = e.clientY - lastMouse.current.y;
    lastMouse.current = { x: e.clientX, y: e.clientY };
    setPan(prev => ({ x: prev.x + dx, y: prev.y + dy }));
  }, []);

  const handleMouseUp = useCallback(() => { dragging.current = false; }, []);

  const reset = useCallback(() => { setScale(1); setPan({ x: 0, y: 0 }); }, []);

  // Minimap viewport fraction
  const vpW = Math.min(1, 1 / scale);
  const vpH = Math.min(1, 1 / scale);
  const cw = containerRef.current?.clientWidth ?? 1;
  const ch = containerRef.current?.clientHeight ?? 1;
  const vpX = 0.5 - pan.x / (cw * scale) - vpW / 2;
  const vpY = 0.5 - pan.y / (ch * scale) - vpH / 2;

  return (
    <Box
      ref={containerRef}
      onWheel={handleWheel}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
      sx={{
        position: "relative", width: "100%", height: "100%",
        overflow: "hidden", cursor: scale > 1 ? (dragging.current ? "grabbing" : "grab") : "default",
      }}
    >
      <img
        src={src}
        alt={alt}
        draggable={false}
        style={{
          maxWidth: "100%", maxHeight: "100%", objectFit: "contain",
          transform: `translate(${pan.x}px, ${pan.y}px) scale(${scale})`,
          transformOrigin: "center center",
          transition: dragging.current ? "none" : "transform 80ms ease-out",
          userSelect: "none",
        }}
      />
      {/* Zoom controls */}
      <Box sx={{ position: "absolute", bottom: 8, right: 8, display: "flex", gap: 0.5, bgcolor: "rgba(0,0,0,0.6)", borderRadius: tokens.radius.md, px: 0.5, py: 0.25 }}>
        <IconButton size="small" onClick={() => setScale(s => Math.min(8, s + 0.5))} sx={{ color: "#fff", p: 0.5 }}><ZoomInOutlined sx={{ fontSize: 16 }} /></IconButton>
        <IconButton size="small" onClick={reset} sx={{ color: "#fff", p: 0.5 }}><CenterFocusStrongOutlined sx={{ fontSize: 16 }} /></IconButton>
        <IconButton size="small" onClick={() => { const ns = Math.max(1, scale - 0.5); setScale(ns); if (ns <= 1) setPan({ x: 0, y: 0 }); }} sx={{ color: "#fff", p: 0.5 }}><ZoomOutOutlined sx={{ fontSize: 16 }} /></IconButton>
        {scale > 1 && (
          <Typography variant="caption" sx={{ color: "#fff", fontSize: "0.65rem", alignSelf: "center", px: 0.5 }}>
            {Math.round(scale * 100)}%
          </Typography>
        )}
      </Box>
      {/* Minimap */}
      {scale > 1 && (
        <Box sx={{ position: "absolute", top: 8, right: 8, width: 100, height: 70, bgcolor: "rgba(0,0,0,0.5)", border: `1px solid ${tokens.border.default}`, borderRadius: tokens.radius.sm, overflow: "hidden" }}>
          <img src={src} alt="" style={{ width: "100%", height: "100%", objectFit: "contain", opacity: 0.7 }} />
          <Box
            sx={{
              position: "absolute",
              left: `${vpX * 100}%`,
              top: `${vpY * 100}%`,
              width: `${vpW * 100}%`,
              height: `${vpH * 100}%`,
              border: `2px solid ${tokens.primary.main}`,
              bgcolor: `${tokens.primary.main}22`,
              boxSizing: "border-box",
              pointerEvents: "none",
            }}
          />
        </Box>
      )}
    </Box>
  );
}

// ── Comment Item ──────────────────────────────────────────────────────────
function CommentItem({ comment, highlighted, onTimeClick, onHover }: { comment: Comment; highlighted: boolean; onTimeClick?: (t: number) => void; onHover?: (id: string | null) => void }) {
  return (
    <Box
      onMouseEnter={() => onHover?.(comment.id)}
      onMouseLeave={() => onHover?.(null)}
      sx={{
        display: "flex",
        gap: 1.5,
        p: 1.5,
        borderRadius: tokens.radius.md,
        bgcolor: highlighted ? tokens.primary.subtle : "transparent",
        border: highlighted ? `1px solid ${tokens.primary.glow}` : "1px solid transparent",
        transition: "all 160ms ease",
        cursor: "default",
      }}
    >
      <Avatar sx={{ width: 26, height: 26, fontSize: "0.65rem", bgcolor: tokens.bg.overlay, color: tokens.text.secondary, flexShrink: 0 }}>
        {userName(comment.authorId).split(" ").map(n => n[0]).join("")}
      </Avatar>
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.5 }}>
          <Typography variant="caption" fontWeight={600} color="text.primary" sx={{ fontSize: "0.78rem" }}>
            {userName(comment.authorId)}
          </Typography>
          {comment.timestampStart != null && (
            <Chip
              icon={<AccessTimeOutlined sx={{ fontSize: 10 }} />}
              label={formatDuration(comment.timestampStart)}
              size="small"
              onClick={() => onTimeClick?.(comment.timestampStart!)}
              sx={{ height: 16, fontSize: "0.65rem", bgcolor: tokens.primary.subtle, color: tokens.primary.light, cursor: "pointer" }}
            />
          )}
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>
            {new Date(comment.createdAt).toLocaleDateString()}
          </Typography>
        </Box>
        {comment.title && (
          <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.8rem", color: tokens.text.primary, mb: 0.25 }}>{comment.title}</Typography>
        )}
        <Typography variant="body2" sx={{ fontSize: "0.82rem", color: tokens.text.secondary, lineHeight: 1.55 }}>
          {comment.text}
        </Typography>
      </Box>
    </Box>
  );
}

// ── Annotation Item ───────────────────────────────────────────────────────
function AnnotationItem({ ann, highlighted, onTimeClick, onHover }: { ann: Annotation; highlighted: boolean; onTimeClick?: (t: number) => void; onHover?: (id: string | null) => void }) {
  return (
    <Box
      onMouseEnter={() => onHover?.(ann.id)}
      onMouseLeave={() => onHover?.(null)}
      sx={{
        display: "flex",
        gap: 1.25,
        p: 1.5,
        borderRadius: tokens.radius.md,
        bgcolor: highlighted ? `${ann.color}14` : "transparent",
        border: highlighted ? `1px solid ${ann.color}44` : `1px solid transparent`,
        transition: "all 160ms ease",
        cursor: "default",
      }}
    >
      <Box sx={{ width: 3, bgcolor: ann.color, borderRadius: 2, alignSelf: "stretch", flexShrink: 0 }} />
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.35 }}>
          <Typography variant="caption" fontWeight={700} sx={{ fontSize: "0.78rem", color: ann.color }}>{ann.title}</Typography>
          {ann.timestampStart != null && (
            <Chip
              icon={<AccessTimeOutlined sx={{ fontSize: 10 }} />}
              label={ann.timestampEnd != null ? `${formatDuration(ann.timestampStart)} – ${formatDuration(ann.timestampEnd)}` : formatDuration(ann.timestampStart)}
              size="small"
              onClick={() => onTimeClick?.(ann.timestampStart!)}
              sx={{ height: 16, fontSize: "0.65rem", bgcolor: `${ann.color}22`, color: ann.color, cursor: "pointer" }}
            />
          )}
        </Box>
        <Typography variant="body2" sx={{ fontSize: "0.8rem", color: tokens.text.secondary }}>
          {ann.description}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
          {userName(ann.authorId)}
        </Typography>
      </Box>
    </Box>
  );
}

// ── Reaction Chip ─────────────────────────────────────────────────────────
function ReactionChip({ reaction }: { reaction: Reaction }) {
  const c = reactionColor[reaction.type] ?? tokens.text.secondary;
  return (
    <Tooltip title={`${userName(reaction.userId)} · ${reaction.type}${reaction.rating ? ` · ${reaction.rating}/5` : ""}`}>
      <Chip
        icon={<Box sx={{ color: c, display: "flex", ml: "6px !important" }}>{reactionIcon[reaction.type]}</Box>}
        label={reaction.type}
        size="small"
        sx={{ bgcolor: `${c}14`, border: `1px solid ${c}33`, color: c, fontSize: "0.72rem" }}
      />
    </Tooltip>
  );
}

// ── Task Item ─────────────────────────────────────────────────────────────
function TaskItem({ task, onClick }: { task: Task; onClick?: () => void }) {
  const priorityColor: Record<string, string> = { critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary };
  const statusColor: Record<string, string> = { open: tokens.accent.blue, in_progress: tokens.accent.amber, review: tokens.primary.main, done: tokens.accent.green, blocked: tokens.accent.red };
  return (
    <Box onClick={onClick} sx={{ display: "flex", gap: 1.5, p: 1.5, borderRadius: tokens.radius.md, bgcolor: tokens.bg.overlay, cursor: "pointer", "&:hover": { bgcolor: tokens.primary.subtle, border: `1px solid ${tokens.primary.glow}` }, border: "1px solid transparent", transition: "all 140ms ease" }}>
      <Box sx={{ width: 3, height: "auto", bgcolor: priorityColor[task.priority], borderRadius: 2, flexShrink: 0, alignSelf: "stretch" }} />
      <Box sx={{ flex: 1 }}>
        <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem", color: tokens.text.primary, mb: 0.5 }}>{task.title}</Typography>
        <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem", display: "block", mb: 0.5 }}>{task.description}</Typography>
        <Box sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
          <Chip label={task.status.replace("_", " ")} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${statusColor[task.status]}22`, color: statusColor[task.status] }} />
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>→ {USERS.find(u => u.id === task.assigneeId)?.name ?? task.assigneeId}</Typography>
          {task.dueDate && <Typography variant="caption" sx={{ color: new Date(task.dueDate) < new Date() ? tokens.accent.red : tokens.text.tertiary, fontSize: "0.68rem", ml: "auto" }}>{new Date(task.dueDate).toLocaleDateString()}</Typography>}
        </Box>
      </Box>
    </Box>
  );
}

// ── Main Asset Detail ─────────────────────────────────────────────────────

// ── Transcript Panel ──────────────────────────────────────────────────────
function TranscriptPanel({
  sections,
  currentTime,
  onSeek,
  onSectionsChange,
}: {
  sections: TranscriptSection[];
  currentTime: number;
  onSeek: (t: number) => void;
  onSectionsChange: (s: TranscriptSection[]) => void;
}) {
  const sectionColors = [tokens.accent.blue, tokens.accent.green, tokens.accent.amber, "#c077db", tokens.primary.main, tokens.accent.red];

  const moveBoundary = (idx: number, direction: "up" | "down") => {
    const updated = [...sections];
    const step = 0.5;
    if (direction === "up" && idx > 0) {
      const newTime = Math.max(updated[idx - 1].startTime + 0.5, updated[idx].startTime - step);
      updated[idx - 1] = { ...updated[idx - 1], endTime: newTime };
      updated[idx] = { ...updated[idx], startTime: newTime, words: updated[idx].words.filter(w => w.startTime >= newTime) };
    } else if (direction === "down" && idx < updated.length - 1) {
      const newTime = Math.min(updated[idx + 1].endTime - 0.5, updated[idx].endTime + step);
      updated[idx] = { ...updated[idx], endTime: newTime };
      updated[idx + 1] = { ...updated[idx + 1], startTime: newTime, words: updated[idx + 1].words.filter(w => w.startTime >= newTime) };
    }
    onSectionsChange(updated);
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0, overflow: "auto" }}>
      {/* Section timeline bar */}
      {sections.length > 0 && (() => {
        const total = sections[sections.length - 1].endTime;
        return (
          <Box sx={{ mb: 1.5, px: 0.5 }}>
            <Box sx={{ position: "relative", height: 20, bgcolor: tokens.bg.overlay, borderRadius: tokens.radius.sm, overflow: "hidden" }}>
              {sections.map((s, i) => {
                const left = (s.startTime / total) * 100;
                const width = ((s.endTime - s.startTime) / total) * 100;
                const active = currentTime >= s.startTime && currentTime <= s.endTime;
                return (
                  <Tooltip key={s.id} title={`${s.title} (${formatDuration(Math.round(s.startTime))} – ${formatDuration(Math.round(s.endTime))})`}>
                    <Box
                      onClick={() => onSeek(s.startTime)}
                      sx={{
                        position: "absolute", left: `${left}%`, width: `${width}%`, top: 0, bottom: 0,
                        bgcolor: active ? `${sectionColors[i % sectionColors.length]}44` : `${sectionColors[i % sectionColors.length]}22`,
                        borderLeft: i > 0 ? `1px solid ${tokens.bg.surface}` : "none",
                        cursor: "pointer",
                        "&:hover": { bgcolor: `${sectionColors[i % sectionColors.length]}55` },
                        transition: "background-color 120ms ease",
                      }}
                    />
                  </Tooltip>
                );
              })}
              {/* Playhead */}
              <Box sx={{ position: "absolute", left: `${(currentTime / total) * 100}%`, top: 0, bottom: 0, width: 2, bgcolor: tokens.primary.main, zIndex: 2, pointerEvents: "none" }} />
            </Box>
          </Box>
        );
      })()}

      {sections.map((section, idx) => {
        const color = sectionColors[idx % sectionColors.length];
        const active = currentTime >= section.startTime && currentTime <= section.endTime;
        return (
          <Box key={section.id}>
            {/* Boundary drag arrows between sections */}
            {idx > 0 && (
              <Box sx={{ display: "flex", justifyContent: "center", py: 0.25 }}>
                <Box sx={{ display: "flex", gap: 0.25, bgcolor: tokens.bg.overlay, borderRadius: tokens.radius.sm, px: 0.5 }}>
                  <IconButton size="small" onClick={() => moveBoundary(idx, "up")} sx={{ p: 0.25, color: tokens.text.tertiary, "&:hover": { color: tokens.text.primary } }}>
                    <ArrowUpwardOutlined sx={{ fontSize: 12 }} />
                  </IconButton>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.6rem", alignSelf: "center", px: 0.25 }}>
                    {formatDuration(Math.round(section.startTime))}
                  </Typography>
                  <IconButton size="small" onClick={() => moveBoundary(idx, "down")} sx={{ p: 0.25, color: tokens.text.tertiary, "&:hover": { color: tokens.text.primary } }}>
                    <ArrowDownwardOutlined sx={{ fontSize: 12 }} />
                  </IconButton>
                </Box>
              </Box>
            )}

            {/* Section block */}
            <Box
              sx={{
                p: 1.5, borderRadius: tokens.radius.md,
                borderLeft: `3px solid ${color}`,
                bgcolor: active ? `${color}11` : "transparent",
                transition: "background-color 160ms ease",
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 0.75 }}>
                <Typography variant="caption" fontWeight={700} sx={{ fontSize: "0.78rem", color }}>
                  {section.title}
                </Typography>
                <Chip
                  label={`${formatDuration(Math.round(section.startTime))} – ${formatDuration(Math.round(section.endTime))}`}
                  size="small"
                  onClick={() => onSeek(section.startTime)}
                  sx={{ height: 16, fontSize: "0.62rem", bgcolor: `${color}22`, color, cursor: "pointer" }}
                />
              </Box>
              <Typography variant="body2" sx={{ fontSize: "0.8rem", color: tokens.text.secondary, lineHeight: 1.8 }}>
                {section.words.map((w, wi) => {
                  const wordActive = currentTime >= w.startTime && currentTime <= w.endTime;
                  return (
                    <Box
                      key={wi}
                      component="span"
                      onClick={() => onSeek(w.startTime)}
                      sx={{
                        cursor: "pointer",
                        bgcolor: wordActive ? `${tokens.primary.main}33` : "transparent",
                        borderRadius: wordActive ? "2px" : 0,
                        px: wordActive ? 0.25 : 0,
                        fontWeight: wordActive ? 600 : 400,
                        color: wordActive ? tokens.primary.light : tokens.text.secondary,
                        transition: "all 80ms ease",
                        "&:hover": { bgcolor: `${tokens.primary.main}22`, borderRadius: "2px" },
                      }}
                    >
                      {w.word}{" "}
                    </Box>
                  );
                })}
              </Typography>
            </Box>
          </Box>
        );
      })}

      {sections.length === 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
          <Typography variant="body2" color="text.secondary">No transcript available</Typography>
        </Box>
      )}
    </Box>
  );
}

// ── Face Detection Panel ──────────────────────────────────────────────────
function FaceDetectionPanel({
  faces,
  clusters,
  persons,
  onSeek,
}: {
  faces: DetectedFace[];
  clusters: FaceCluster[];
  persons: Person[];
  onSeek?: (t: number) => void;
}) {
  // Group faces by cluster
  const grouped = clusters.filter(c => c.faceIds.some(fid => faces.some(f => f.id === fid))).map(cluster => {
    const clusterFaces = faces.filter(f => cluster.faceIds.includes(f.id));
    const person = cluster.personId ? persons.find(p => p.id === cluster.personId) : undefined;
    return { cluster, faces: clusterFaces, person };
  });

  const unclustered = faces.filter(f => !f.clusterId || !clusters.some(c => c.id === f.clusterId));

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      {/* Summary */}
      <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <FaceOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
          <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem" }}>{faces.length} faces detected</Typography>
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <GroupWorkOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
          <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem" }}>{grouped.length} clusters</Typography>
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
          <PersonOutlined sx={{ fontSize: 16, color: tokens.text.tertiary }} />
          <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem" }}>{grouped.filter(g => g.person).length} identified</Typography>
        </Box>
      </Box>

      {/* Clusters */}
      {grouped.map(({ cluster, faces: cFaces, person }) => (
        <Box key={cluster.id} sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
          {/* Cluster header */}
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.25, px: 1.5, py: 1, bgcolor: tokens.bg.overlay }}>
            <Avatar src={cluster.representativeThumbnailUrl} sx={{ width: 28, height: 28 }} />
            <Box sx={{ flex: 1 }}>
              <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.78rem", color: tokens.text.primary }}>
                {person ? person.name : cluster.label}
              </Typography>
              {person && (
                <Typography variant="caption" sx={{ fontSize: "0.68rem", color: tokens.text.tertiary, display: "block" }}>
                  {person.description}
                </Typography>
              )}
            </Box>
            {person ? (
              <Chip label="Identified" size="small" sx={{ height: 18, fontSize: "0.62rem", bgcolor: `${tokens.accent.green}22`, color: tokens.accent.green }} />
            ) : (
              <Chip label="Unidentified" size="small" sx={{ height: 18, fontSize: "0.62rem", bgcolor: tokens.bg.elevated, color: tokens.text.tertiary }} />
            )}
          </Box>
          {/* Face thumbnails */}
          <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", p: 1.25 }}>
            {cFaces.map(face => (
              <Tooltip key={face.id} title={`Confidence: ${(face.confidence * 100).toFixed(0)}%${face.timestamp != null ? ` · ${formatDuration(Math.round(face.timestamp))}` : ""}`}>
                <Box
                  onClick={() => face.timestamp != null && onSeek?.(face.timestamp)}
                  sx={{
                    width: 48, height: 48, borderRadius: tokens.radius.sm, overflow: "hidden",
                    border: `2px solid ${tokens.border.subtle}`, cursor: face.timestamp != null ? "pointer" : "default",
                    "&:hover": face.timestamp != null ? { borderColor: tokens.primary.main } : {},
                    transition: "border-color 120ms ease",
                  }}
                >
                  <img src={face.thumbnailUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
                </Box>
              </Tooltip>
            ))}
          </Box>
        </Box>
      ))}

      {/* Unclustered */}
      {unclustered.length > 0 && (
        <Box sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
          <Box sx={{ px: 1.5, py: 1, bgcolor: tokens.bg.overlay }}>
            <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.78rem", color: tokens.text.tertiary }}>Unclustered ({unclustered.length})</Typography>
          </Box>
          <Box sx={{ display: "flex", gap: 0.75, flexWrap: "wrap", p: 1.25 }}>
            {unclustered.map(face => (
              <Box key={face.id} sx={{ width: 48, height: 48, borderRadius: tokens.radius.sm, overflow: "hidden", border: `2px solid ${tokens.border.subtle}` }}>
                <img src={face.thumbnailUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
              </Box>
            ))}
          </Box>
        </Box>
      )}

      {faces.length === 0 && (
        <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
          <FaceOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
          <Typography variant="body2" color="text.secondary">No face detection data</Typography>
        </Box>
      )}
    </Box>
  );
}

// ── Main Asset Detail ─────────────────────────────────────────────────────
export default function AssetDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [asset, setAsset] = useState<Asset | null>(null);
  const [comments, setComments] = useState<Comment[]>([]);
  const [annotations, setAnnotations] = useState<Annotation[]>([]);
  const [reactions, setReactions] = useState<Reaction[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [transcriptSections, setTranscriptSections] = useState<TranscriptSection[]>([]);
  const [detectedFaces, setDetectedFaces] = useState<DetectedFace[]>([]);
  const [faceClusters, setFaceClusters] = useState<FaceCluster[]>([]);
  const [persons, setPersons] = useState<Person[]>([]);
  const [tab, setTab] = useState(0);
  const [sidebarQuery, setSidebarQuery] = useState("");
  const [currentTime, setCurrentTime] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [highlightedId, setHighlightedId] = useState<string | null>(null);
  const [hoveredMarkerId, setHoveredMarkerId] = useState<string | null>(null);
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [tagInput, setTagInput] = useState("");
  const tagInputRef = useRef<HTMLInputElement>(null);
  const [actionMenuAnchor, setActionMenuAnchor] = useState<null | HTMLElement>(null);
  const [pipelineMenuAnchor, setPipelineMenuAnchor] = useState<null | HTMLElement>(null);
  // Draggable left/right split (percentage)
  const [leftPct, setLeftPct] = useState(60);
  const isDragging = useRef(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const intervalRef = useRef<number | null>(null);

  useEffect(() => {
    if (!id || !token) return;
    // Load asset from real API; social features still use mock services
    apiLoadAsset(token, id).then(resp => {
      setAsset(apiToAsset(resp));
    }).catch(() => { /* asset not found */ });
    Promise.all([
      mockCommentService.getByAsset(id),
      mockAnnotationService.getByAsset(id),
      mockReactionService.getByAsset(id),
      Promise.resolve([] as Task[]),
      mockTranscriptService.getByAsset(id),
      mockFaceDetectionService.getFacesByAsset(id),
      mockFaceDetectionService.getAllClusters(),
      mockFaceDetectionService.getAllPersons(),
    ]).then(([c, an, rx, t, tr, faces, clusters, pers]) => {
      setComments(c);
      setAnnotations(an);
      setReactions(rx);
      setTasks(t);
      setTranscriptSections(tr);
      setDetectedFaces(faces);
      setFaceClusters(clusters);
      setPersons(pers);
    });
  }, [id, token]);

  // Draggable divider handlers
  const handleDividerMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isDragging.current = true;
    const onMove = (ev: MouseEvent) => {
      if (!isDragging.current || !containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const pct = ((ev.clientX - rect.left) / rect.width) * 100;
      setLeftPct(Math.min(Math.max(pct, 25), 75));
    };
    const onUp = () => { isDragging.current = false; window.removeEventListener("mousemove", onMove); window.removeEventListener("mouseup", onUp); };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, []);

  // Simulated video progress
  useEffect(() => {
    if (playing && asset?.duration) {
      intervalRef.current = window.setInterval(() => {
        setCurrentTime(prev => {
          if (prev >= asset.duration!) { setPlaying(false); return asset.duration!; }
          return prev + 0.25;
        });
      }, 250);
    } else {
      if (intervalRef.current) clearInterval(intervalRef.current);
    }
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, [playing, asset?.duration]);

  if (!asset) {
    return (
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100%", bgcolor: tokens.bg.base }}>
        <LinearProgress sx={{ width: 200 }} />
      </Box>
    );
  }

  const isVideo = asset.type === "video";
  const duration = asset.duration ?? 0;

  // Build timeline markers
  const markers: TimelineMarker[] = [
    ...comments.filter(c => c.timestampStart != null).map(c => ({
      time: c.timestampStart!, type: "comment" as const,
      color: tokens.accent.blue, label: c.title ?? c.text.slice(0, 30), id: c.id,
    })),
    ...annotations.filter(a => a.timestampStart != null).map(a => ({
      time: a.timestampStart!, endTime: a.timestampEnd ?? undefined, type: "annotation" as const,
      color: a.color, label: a.title, id: a.id,
    })),
    ...reactions.filter(r => r.timestamp != null).map(r => ({
      time: r.timestamp!, type: "reaction" as const,
      color: reactionColor[r.type] ?? tokens.text.secondary, label: r.type, id: r.id,
    })),
  ];

  const handleMarkerClick = (markerId: string, type: string) => {
    setHighlightedId(markerId);
    if (type === "comment") setTab(1);
    else if (type === "annotation") setTab(2);
    else if (type === "reaction") setTab(3);
  };

  const tabs = [
    { label: "Overview", icon: <AccountTreeOutlined sx={{ fontSize: 14 }} /> },
    { label: `Comments (${comments.length})`, icon: <ChatBubbleOutlineOutlined sx={{ fontSize: 14 }} /> },
    { label: `Annotations (${annotations.length})`, icon: <BookmarkBorderOutlined sx={{ fontSize: 14 }} /> },
    { label: `Reactions (${reactions.length})`, icon: <ThumbUpAltOutlined sx={{ fontSize: 14 }} /> },
    { label: `Tasks (${tasks.length})`, icon: <TaskAltOutlined sx={{ fontSize: 14 }} /> },
    ...(transcriptSections.length > 0 ? [{ label: "Transcript", icon: <ChatBubbleOutlineOutlined sx={{ fontSize: 14 }} /> }] : []),
    ...(detectedFaces.length > 0 ? [{ label: `Faces (${detectedFaces.length})`, icon: <FaceOutlined sx={{ fontSize: 14 }} /> }] : []),
  ];

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", bgcolor: tokens.bg.base }}>
      {/* Header */}
      <Box sx={{ px: 2.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, bgcolor: tokens.bg.surface, display: "flex", alignItems: "center", gap: 1.5 }}>
        <IconButton size="small" onClick={() => navigate(-1)}>
          <ArrowBack sx={{ fontSize: 18 }} />
        </IconButton>
        <Box sx={{ flex: 1, overflow: "hidden" }}>
          <Typography variant="h6" fontWeight={700} noWrap sx={{ fontSize: "0.95rem" }}>{asset.name}</Typography>
          <Box sx={{ display: "flex", gap: 0.75, alignItems: "center", flexWrap: "wrap" }}>
            <Chip label={asset.type} size="small" sx={{ height: 16, fontSize: "0.65rem", bgcolor: tokens.bg.elevated }} />
            <Chip
              label={asset.status}
              size="small"
              sx={{
                height: 16, fontSize: "0.65rem",
                bgcolor: `${asset.status === "ready" ? tokens.accent.green : asset.status === "failed" ? tokens.accent.red : tokens.accent.amber}22`,
                color: asset.status === "ready" ? tokens.accent.green : asset.status === "failed" ? tokens.accent.red : tokens.accent.amber,
              }}
            />
            {asset.tags.slice(0, 3).map(t => (
              <Chip key={t} label={t} size="small" sx={{ height: 16, fontSize: "0.65rem", bgcolor: tokens.bg.overlay }} />
            ))}
            {/* Collection chips */}
            {COLLECTIONS.filter(c => asset.collectionIds.includes(c.id)).map(col => (
              <Chip
                key={col.id}
                icon={<CollectionsOutlined sx={{ fontSize: 10 }} />}
                label={col.name}
                size="small"
                sx={{ height: 16, fontSize: "0.65rem", bgcolor: `${col.color}22`, color: col.color, "& .MuiChip-icon": { color: col.color } }}
              />
            ))}
          </Box>
        </Box>
        {/* Actions menu */}
        <IconButton size="small" onClick={e => setActionMenuAnchor(e.currentTarget)}>
          <MoreVertOutlined sx={{ fontSize: 18 }} />
        </IconButton>
        <Menu anchorEl={actionMenuAnchor} open={Boolean(actionMenuAnchor)} onClose={() => setActionMenuAnchor(null)}>
          <MenuItem onClick={e => { setPipelineMenuAnchor(e.currentTarget); }}>
            <ListItemIcon><SendOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>Process</ListItemText>
            <Typography variant="caption" sx={{ ml: 2, color: tokens.text.tertiary }}>▸</Typography>
          </MenuItem>
          <MenuItem onClick={() => { setActionMenuAnchor(null); /* create task action */ }}>
            <ListItemIcon><AddTaskOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>Create Task</ListItemText>
          </MenuItem>
        </Menu>
        <Menu
          anchorEl={pipelineMenuAnchor}
          open={Boolean(pipelineMenuAnchor)}
          onClose={() => { setPipelineMenuAnchor(null); setActionMenuAnchor(null); }}
          anchorOrigin={{ vertical: "top", horizontal: "right" }}
          transformOrigin={{ vertical: "top", horizontal: "left" }}
        >
          {PIPELINES.map(p => (
            <MenuItem key={p.id} onClick={() => { setPipelineMenuAnchor(null); setActionMenuAnchor(null); }} sx={{ gap: 1 }}>
              <AccountTreeOutlined sx={{ fontSize: 14, color: tokens.primary.main }} />
              <Typography variant="body2" sx={{ fontSize: "0.82rem" }}>{p.name}</Typography>
            </MenuItem>
          ))}
        </Menu>
      </Box>

      {/* Body */}
      <Box ref={containerRef} sx={{ flex: 1, overflow: "hidden", display: "flex", flexDirection: { xs: "column", lg: "row" }, gap: 0 }}>
        {/* Left: media */}
        <Box sx={{ flex: "0 0 auto", width: { xs: "100%", lg: `${leftPct}%` }, display: "flex", flexDirection: "column", overflow: "hidden" }}>
          {/* Media area */}
          <Box sx={{ position: "relative", bgcolor: "#000", aspectRatio: isVideo ? "16/9" : "auto", maxHeight: { xs: 240, lg: 380 }, overflow: "hidden", display: "flex", alignItems: "center", justifyContent: "center" }}>
            {isVideo ? (
              <>
                <img
                  src={asset.thumbnailUrl}
                  alt={asset.name}
                  style={{ width: "100%", height: "100%", objectFit: "contain", opacity: playing ? 0 : 1 }}
                />
                {/* Overlay controls */}
                <Box
                  sx={{
                    position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center",
                    opacity: 0, "&:hover": { opacity: 1 }, transition: "opacity 160ms ease",
                    background: "radial-gradient(ellipse at center, rgba(0,0,0,0.5) 0%, transparent 70%)",
                  }}
                >
                  <IconButton
                    onClick={() => setPlaying(!playing)}
                    sx={{ bgcolor: "rgba(0,0,0,0.6)", border: `2px solid rgba(255,255,255,0.3)`, "&:hover": { bgcolor: "rgba(0,0,0,0.8)" } }}
                  >
                    {playing ? <PauseOutlined sx={{ fontSize: 28, color: "#fff" }} /> : <PlayArrowOutlined sx={{ fontSize: 28, color: "#fff" }} />}
                  </IconButton>
                </Box>
              </>
            ) : (
              <ZoomableImage
                src={asset.url || asset.thumbnailUrl}
                alt={asset.name}
              />
            )}
          </Box>

          {/* Timeline (video only) */}
          {isVideo && (
            <Box sx={{ px: 2.5, py: 1.5, bgcolor: tokens.bg.surface, borderTop: `1px solid ${tokens.border.subtle}` }}>
              <VideoTimeline
                duration={duration}
                currentTime={currentTime}
                markers={markers}
                hoveredMarkerId={hoveredMarkerId}
                onSeek={setCurrentTime}
                onMarkerClick={handleMarkerClick}
                onMarkerHover={setHoveredMarkerId}
                onMarkerDrag={(markerId, edge, newTime) => {
                  // Update comment or annotation time when dragging handles
                  const comment = comments.find(c => c.id === markerId);
                  if (comment) {
                    if (edge === "start") comment.timestampStart = newTime;
                    else if (edge === "end") comment.timestampEnd = newTime;
                    setComments([...comments]);
                    return;
                  }
                  const ann = annotations.find(a => a.id === markerId);
                  if (ann) {
                    if (edge === "start") ann.timestampStart = newTime;
                    else if (edge === "end") ann.timestampEnd = newTime;
                    setAnnotations([...annotations]);
                  }
                }}
              />
            </Box>
          )}

          {/* Annotation overlay for images */}
          {!isVideo && annotations.filter(a => a.region).length > 0 && (
            <Box sx={{ px: 2, py: 1, bgcolor: tokens.bg.surface, display: "flex", gap: 0.75, flexWrap: "wrap", alignItems: "center", borderTop: `1px solid ${tokens.border.subtle}` }}>
              <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>Annotations:</Typography>
              {annotations.filter(a => a.region).map(a => (
                <Chip key={a.id} label={a.title} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${a.color}22`, color: a.color }} />
              ))}
            </Box>
          )}

          {/* Tags — editable */}
          <Box sx={{ px: 2, py: 1, bgcolor: tokens.bg.surface, display: "flex", gap: 0.5, flexWrap: "wrap", alignItems: "center", borderTop: `1px solid ${tokens.border.subtle}` }}>
            {asset.tags.map(t => {
              const bc = tagBreadcrumb(t);
              return (
                <Tooltip key={t} title={bc || ""} placement="top" arrow>
                  <Chip
                    label={t}
                    size="small"
                    onDelete={() => { asset.tags = asset.tags.filter(tag => tag !== t); setAsset({ ...asset }); }}
                    sx={{ height: 20, fontSize: "0.7rem", bgcolor: tokens.bg.elevated, color: tokens.text.secondary }}
                  />
                </Tooltip>
              );
            })}
            <TextField
              inputRef={tagInputRef}
              value={tagInput}
              onChange={e => setTagInput(e.target.value)}
              onKeyDown={e => {
                if (e.key === "Enter" && tagInput.trim()) {
                  e.preventDefault();
                  const newTag = tagInput.trim();
                  if (!asset.tags.includes(newTag)) {
                    asset.tags = [...asset.tags, newTag];
                    setAsset({ ...asset });
                  }
                  setTagInput("");
                } else if (e.key === "Backspace" && tagInput === "" && asset.tags.length > 0) {
                  asset.tags = asset.tags.slice(0, -1);
                  setAsset({ ...asset });
                }
              }}
              placeholder="Add tag…"
              size="small"
              variant="standard"
              sx={{ minWidth: 80, maxWidth: 140, "& .MuiInput-root": { fontSize: "0.75rem" }, "& .MuiInput-underline:before": { borderBottom: "none" }, "& .MuiInput-underline:hover:before": { borderBottom: `1px solid ${tokens.border.default}` } }}
            />
          </Box>

          {/* Description */}
          <Box sx={{ px: 2, py: 1.5, bgcolor: tokens.bg.surface, borderTop: `1px solid ${tokens.border.subtle}` }}>
            <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", display: "block", mb: 0.75 }}>
              Description
            </Typography>
            <TextField
              multiline
              minRows={2}
              maxRows={5}
              fullWidth
              value={asset.description}
              size="small"
              InputProps={{ sx: { fontSize: "0.82rem", color: tokens.text.secondary, lineHeight: 1.55 } }}
              sx={{ "& .MuiOutlinedInput-root": { bgcolor: tokens.bg.elevated } }}
            />
          </Box>

          {/* Metadata */}
          <Box sx={{ px: 2, py: 2, flex: 1, overflow: "auto" }}>
            <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem" }}>
              Metadata
            </Typography>
            <Box sx={{ mt: 1, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
              {[
                ["Size", formatBytes(asset.fileSize)],
                ["MIME", asset.mimeType],
                ...(asset.width ? [["Dimensions", `${asset.width}×${asset.height}`]] : []),
                ...(asset.duration ? [["Duration", formatDuration(asset.duration)]] : []),
                ["Owner", userName(asset.ownerId)],
                ["Created", new Date(asset.createdAt).toLocaleDateString()],
                ...Object.entries(asset.metadata).slice(0, 4).map(([k, v]) => [k, String(v)]),
              ].map(([k, v], idx, arr) => (
                <Box
                  key={k}
                  sx={{
                    display: "grid",
                    gridTemplateColumns: "120px 1fr",
                    px: 1.5,
                    py: 0.85,
                    borderBottom: idx < arr.length - 1 ? `1px solid ${tokens.border.subtle}` : "none",
                    bgcolor: idx % 2 === 0 ? "transparent" : `rgba(255,255,255,0.02)`,
                  }}
                >
                  <Typography sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>{k}</Typography>
                  <Typography sx={{ color: tokens.text.secondary, fontSize: "0.8rem", wordBreak: "break-word" }}>{v}</Typography>
                </Box>
              ))}
            </Box>
          </Box>
        </Box>

        {/* Draggable divider */}
        <Box
          onMouseDown={handleDividerMouseDown}
          sx={{
            display: { xs: "none", lg: "flex" },
            width: 6,
            flexShrink: 0,
            alignItems: "center",
            justifyContent: "center",
            cursor: "col-resize",
            bgcolor: "transparent",
            borderLeft: `1px solid ${tokens.border.subtle}`,
            borderRight: `1px solid ${tokens.border.subtle}`,
            "&:hover": { bgcolor: tokens.primary.subtle },
            "&:hover .drag-handle": { opacity: 1 },
            transition: "background-color 120ms ease",
            zIndex: 10,
          }}
        >
          <Box
            className="drag-handle"
            sx={{
              width: 2, height: 32, borderRadius: 1,
              bgcolor: tokens.primary.main, opacity: 0,
              transition: "opacity 120ms ease",
            }}
          />
        </Box>

        {/* Right: discussion tabs */}
        <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden", bgcolor: tokens.bg.surface }}>
          <Tabs value={tab} onChange={(_, v) => { setTab(v); setSidebarQuery(""); }} sx={{ px: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, minHeight: 40 }}>
            {tabs.map((t, i) => (
              <Tab key={i} label={t.label} iconPosition="start" icon={t.icon} sx={{ minHeight: 40, fontSize: "0.75rem", px: 1.5 }} />
            ))}
          </Tabs>

          {/* Mini search (for Comments, Annotations, Tasks) */}
          {(tab === 1 || tab === 2 || tab === 4) && (
            <Box sx={{ px: 1.5, py: 0.75, borderBottom: `1px solid ${tokens.border.subtle}` }}>
              <TextField
                value={sidebarQuery}
                onChange={e => setSidebarQuery(e.target.value)}
                placeholder="Filter…"
                size="small"
                fullWidth
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                    </InputAdornment>
                  ),
                  sx: { fontSize: "0.75rem", height: 30 },
                }}
                sx={{ "& .MuiOutlinedInput-root": { bgcolor: tokens.bg.elevated } }}
              />
            </Box>
          )}

          <Box sx={{ flex: 1, overflow: "auto", p: 1.5 }}>
            {/* Overview tab */}
            {tab === 0 && (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
                <Typography variant="body2" sx={{ color: tokens.text.secondary, lineHeight: 1.6 }}>{asset.description}</Typography>
                <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                  {asset.tags.map(t => (
                    <Chip
                      key={t}
                      label={t}
                      size="small"
                      onMouseEnter={() => setHoveredMarkerId(t)}
                      onMouseLeave={() => setHoveredMarkerId(null)}
                      sx={{ height: 20, fontSize: "0.7rem", bgcolor: hoveredMarkerId === t ? tokens.primary.subtle : tokens.bg.elevated, border: `1px solid ${hoveredMarkerId === t ? tokens.primary.main : "transparent"}`, transition: "all 120ms ease", cursor: "default" }}
                    />
                  ))}
                </Box>
              </Box>
            )}

            {/* Comments tab */}
            {tab === 1 && (() => {
              const sq = sidebarQuery.toLowerCase().trim();
              const filtered = sq ? comments.filter(c => (c.title?.toLowerCase().includes(sq)) || c.text.toLowerCase().includes(sq) || userName(c.authorId).toLowerCase().includes(sq)) : comments;
              return (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75 }}>
                {filtered.length === 0 ? (
                  <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
                    <ChatBubbleOutlineOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
                    <Typography variant="body2" color="text.secondary">{comments.length === 0 ? "No comments yet" : "No matching comments"}</Typography>
                  </Box>
                ) : filtered.map(c => (
                  <CommentItem
                    key={c.id}
                    comment={c}
                    highlighted={highlightedId === c.id || hoveredMarkerId === c.id}
                    onTimeClick={(t) => { setCurrentTime(t); setHighlightedId(null); }}
                    onHover={setHoveredMarkerId}
                  />
                ))}
              </Box>
              );
            })()}

            {/* Annotations tab */}
            {tab === 2 && (() => {
              const sq = sidebarQuery.toLowerCase().trim();
              const filtered = sq ? annotations.filter(a => a.title.toLowerCase().includes(sq) || (a.description?.toLowerCase().includes(sq) ?? false)) : annotations;
              return (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75 }}>
                {filtered.length === 0 ? (
                  <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
                    <BookmarkBorderOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
                    <Typography variant="body2" color="text.secondary">{annotations.length === 0 ? "No annotations yet" : "No matching annotations"}</Typography>
                  </Box>
                ) : filtered.map(a => (
                  <AnnotationItem
                    key={a.id}
                    ann={a}
                    highlighted={highlightedId === a.id || hoveredMarkerId === a.id}
                    onTimeClick={(t) => { setCurrentTime(t); setHighlightedId(null); }}
                    onHover={setHoveredMarkerId}
                  />
                ))}
              </Box>
              );
            })()}

            {/* Reactions tab */}
            {tab === 3 && (
              <Box>
                {reactions.length === 0 ? (
                  <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
                    <ThumbUpAltOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
                    <Typography variant="body2" color="text.secondary">No reactions yet</Typography>
                  </Box>
                ) : (
                  <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.75 }}>
                    {reactions.map(r => <ReactionChip key={r.id} reaction={r} />)}
                  </Box>
                )}
              </Box>
            )}

            {/* Tasks tab */}
            {tab === 4 && (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
                {tasks.length === 0 ? (
                  <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
                    <TaskAltOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
                    <Typography variant="body2" color="text.secondary">No tasks linked</Typography>
                  </Box>
                ) : tasks.map(t => <TaskItem key={t.id} task={t} onClick={() => setSelectedTask(t)} />)}
              </Box>
            )}

            {/* Transcript tab */}
            {transcriptSections.length > 0 && tab === 5 && (
              <TranscriptPanel
                sections={transcriptSections}
                currentTime={currentTime}
                onSeek={setCurrentTime}
                onSectionsChange={setTranscriptSections}
              />
            )}

            {/* Faces tab */}
            {detectedFaces.length > 0 && tab === (transcriptSections.length > 0 ? 6 : 5) && (
              <FaceDetectionPanel
                faces={detectedFaces}
                clusters={faceClusters}
                persons={persons}
                onSeek={isVideo ? setCurrentTime : undefined}
              />
            )}
          </Box>
        </Box>
      </Box>

      {/* Task detail drawer */}
      {selectedTask && (
        <Box
          onClick={() => setSelectedTask(null)}
          sx={{ position: "fixed", inset: 0, bgcolor: "rgba(0,0,0,0.5)", zIndex: 1200, display: "flex", justifyContent: "flex-end" }}
        >
          <Box
            onClick={(e) => e.stopPropagation()}
            sx={{
              width: 420, bgcolor: tokens.bg.surface, borderLeft: `1px solid ${tokens.border.default}`,
              display: "flex", flexDirection: "column", height: "100%", overflow: "hidden",
            }}
          >
            <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
              <TaskAltOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "0.95rem", flex: 1 }}>Task Detail</Typography>
              <IconButton size="small" onClick={() => setSelectedTask(null)}><ArrowBack sx={{ fontSize: 16 }} /></IconButton>
            </Box>
            <Box sx={{ flex: 1, overflow: "auto", p: 2.5, display: "flex", flexDirection: "column", gap: 2 }}>
              {/* Title + Priority */}
              <Box>
                <Box sx={{ display: "flex", gap: 1, alignItems: "flex-start", mb: 1 }}>
                  <Box sx={{ width: 4, height: 20, borderRadius: 2, bgcolor: { critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary }[selectedTask.priority], mt: 0.3, flexShrink: 0 }} />
                  <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", lineHeight: 1.3 }}>{selectedTask.title}</Typography>
                </Box>
                <Typography variant="body2" sx={{ color: tokens.text.secondary, lineHeight: 1.6 }}>{selectedTask.description}</Typography>
              </Box>
              {/* Meta grid */}
              <Box sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
                {[
                  ["Status", <Chip label={selectedTask.status.replace("_", " ")} size="small" sx={{ height: 18, fontSize: "0.7rem", bgcolor: `${{ open: tokens.accent.blue, in_progress: tokens.accent.amber, review: tokens.primary.main, done: tokens.accent.green, blocked: tokens.accent.red }[selectedTask.status]}22`, color: { open: tokens.accent.blue, in_progress: tokens.accent.amber, review: tokens.primary.main, done: tokens.accent.green, blocked: tokens.accent.red }[selectedTask.status] }} />],
                  ["Priority", <Chip label={selectedTask.priority} size="small" sx={{ height: 18, fontSize: "0.7rem", bgcolor: `${{ critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary }[selectedTask.priority]}22`, color: { critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary }[selectedTask.priority], fontWeight: 700 }} />],
                  ["Assignee", <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>{USERS.find(u => u.id === selectedTask.assigneeId)?.name ?? selectedTask.assigneeId}</Typography>],
                  ["Due Date", <Typography sx={{ fontSize: "0.82rem", color: selectedTask.dueDate && new Date(selectedTask.dueDate) < new Date() ? tokens.accent.red : tokens.text.secondary }}>{selectedTask.dueDate ? new Date(selectedTask.dueDate).toLocaleDateString() : "—"}</Typography>],
                  ["Created", <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>{new Date(selectedTask.createdAt).toLocaleDateString()}</Typography>],
                ].map(([label, content], idx) => (
                  <Box key={String(label)} sx={{ display: "grid", gridTemplateColumns: "100px 1fr", px: 1.5, py: 0.85, borderBottom: idx < 4 ? `1px solid ${tokens.border.subtle}` : "none", bgcolor: idx % 2 === 0 ? "transparent" : "rgba(255,255,255,0.02)", alignItems: "center" }}>
                    <Typography sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>{label}</Typography>
                    {content}
                  </Box>
                ))}
              </Box>
              {/* Tags */}
              {selectedTask.tags.length > 0 && (
                <Box>
                  <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.tertiary, fontSize: "0.68rem", display: "block", mb: 0.75 }}>Tags</Typography>
                  <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                    {selectedTask.tags.map(t => <Chip key={t} label={t} size="small" sx={{ height: 20, fontSize: "0.7rem", bgcolor: tokens.bg.elevated }} />)}
                  </Box>
                </Box>
              )}
            </Box>
          </Box>
        </Box>
      )}
    </Box>
  );
}
