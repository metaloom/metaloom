import React, { useEffect, useRef, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  Box, Typography, Chip, Avatar, Paper, IconButton, Tab, Tabs,
  Divider, Tooltip, LinearProgress, Stack,
} from "@mui/material";
import {
  ArrowBack, PlayArrowOutlined, PauseOutlined, PlayCircleOutline,
  ImageOutlined, ChatBubbleOutlineOutlined, BookmarkBorderOutlined,
  ThumbUpAltOutlined, TaskAltOutlined, AccountTreeOutlined,
  FlagOutlined, StarBorderOutlined, HelpOutlineOutlined,
  CheckCircleOutlineOutlined, AccessTimeOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Asset, Comment, Annotation, Reaction, Task } from "../../types";
import {
  mockAssetService, mockCommentService, mockAnnotationService,
  mockReactionService, mockTaskService,
} from "../../mock/services";
import { USERS } from "../../mock/data";

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

// ── Video Timeline ────────────────────────────────────────────────────────
interface TimelineMarker {
  time: number;
  type: "comment" | "annotation" | "reaction";
  color: string;
  label: string;
  id: string;
}

function VideoTimeline({
  duration,
  currentTime,
  markers,
  onSeek,
  onMarkerClick,
}: {
  duration: number;
  currentTime: number;
  markers: TimelineMarker[];
  onSeek: (t: number) => void;
  onMarkerClick: (id: string, type: string) => void;
}) {
  const barRef = useRef<HTMLDivElement>(null);

  const handleBarClick = (e: React.MouseEvent) => {
    if (!barRef.current) return;
    const rect = barRef.current.getBoundingClientRect();
    const pct = (e.clientX - rect.left) / rect.width;
    onSeek(Math.max(0, Math.min(duration, pct * duration)));
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
      {/* Scrubber */}
      <Box
        ref={barRef}
        onClick={handleBarClick}
        sx={{
          position: "relative",
          height: 6,
          bgcolor: tokens.bg.overlay,
          borderRadius: 3,
          cursor: "pointer",
          "&:hover": { height: 8 },
          transition: "height 120ms ease",
        }}
      >
        <Box sx={{ position: "absolute", left: 0, top: 0, bottom: 0, width: `${(currentTime / duration) * 100}%`, bgcolor: tokens.primary.main, borderRadius: 3 }} />
        {markers.map(m => (
          <Tooltip key={m.id} title={m.label}>
            <Box
              onClick={(e) => { e.stopPropagation(); onMarkerClick(m.id, m.type); }}
              sx={{
                position: "absolute",
                left: `${(m.time / duration) * 100}%`,
                top: "50%",
                transform: "translate(-50%, -50%)",
                width: 10, height: 10,
                borderRadius: "50%",
                bgcolor: m.color,
                border: `2px solid ${tokens.bg.elevated}`,
                cursor: "pointer",
                zIndex: 2,
                "&:hover": { width: 13, height: 13 },
                transition: "width 100ms, height 100ms",
              }}
            />
          </Tooltip>
        ))}
      </Box>
      <Box sx={{ display: "flex", justifyContent: "space-between" }}>
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

// ── Comment Item ──────────────────────────────────────────────────────────
function CommentItem({ comment, highlighted, onTimeClick }: { comment: Comment; highlighted: boolean; onTimeClick?: (t: number) => void }) {
  return (
    <Box
      sx={{
        display: "flex",
        gap: 1.5,
        p: 1.5,
        borderRadius: tokens.radius.md,
        bgcolor: highlighted ? tokens.primary.subtle : "transparent",
        border: highlighted ? `1px solid ${tokens.primary.glow}` : "1px solid transparent",
        transition: "all 160ms ease",
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
function AnnotationItem({ ann, highlighted, onTimeClick }: { ann: Annotation; highlighted: boolean; onTimeClick?: (t: number) => void }) {
  return (
    <Box
      sx={{
        display: "flex",
        gap: 1.25,
        p: 1.5,
        borderRadius: tokens.radius.md,
        bgcolor: highlighted ? `${ann.color}14` : "transparent",
        border: highlighted ? `1px solid ${ann.color}44` : `1px solid transparent`,
        transition: "all 160ms ease",
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
function TaskItem({ task }: { task: Task }) {
  const priorityColor: Record<string, string> = { critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary };
  const statusColor: Record<string, string> = { open: tokens.accent.blue, in_progress: tokens.accent.amber, review: tokens.primary.main, done: tokens.accent.green, blocked: tokens.accent.red };
  return (
    <Box sx={{ display: "flex", gap: 1.5, p: 1.5, borderRadius: tokens.radius.md, bgcolor: tokens.bg.overlay }}>
      <Box sx={{ width: 3, height: "auto", bgcolor: priorityColor[task.priority], borderRadius: 2, flexShrink: 0, alignSelf: "stretch" }} />
      <Box sx={{ flex: 1 }}>
        <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.82rem", color: tokens.text.primary, mb: 0.5 }}>{task.title}</Typography>
        <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.78rem", display: "block", mb: 0.5 }}>{task.description}</Typography>
        <Box sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
          <Chip label={task.status.replace("_", " ")} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${statusColor[task.status]}22`, color: statusColor[task.status] }} />
          <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>→ {userName(task.assigneeId)}</Typography>
        </Box>
      </Box>
    </Box>
  );
}

// ── Main Asset Detail ─────────────────────────────────────────────────────
export default function AssetDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [asset, setAsset] = useState<Asset | null>(null);
  const [comments, setComments] = useState<Comment[]>([]);
  const [annotations, setAnnotations] = useState<Annotation[]>([]);
  const [reactions, setReactions] = useState<Reaction[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [tab, setTab] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [highlightedId, setHighlightedId] = useState<string | null>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const intervalRef = useRef<number | null>(null);

  useEffect(() => {
    if (!id) return;
    Promise.all([
      mockAssetService.getById(id),
      mockCommentService.getByAsset(id),
      mockAnnotationService.getByAsset(id),
      mockReactionService.getByAsset(id),
      mockTaskService.getByAsset(id),
    ]).then(([a, c, an, rx, t]) => {
      if (a) setAsset(a);
      setComments(c);
      setAnnotations(an);
      setReactions(rx);
      setTasks(t);
    });
  }, [id]);

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
      time: a.timestampStart!, type: "annotation" as const,
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
          <Box sx={{ display: "flex", gap: 0.75, alignItems: "center" }}>
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
          </Box>
        </Box>
      </Box>

      {/* Body */}
      <Box sx={{ flex: 1, overflow: "hidden", display: "flex", flexDirection: { xs: "column", lg: "row" }, gap: 0 }}>
        {/* Left: media */}
        <Box sx={{ flex: "0 0 auto", width: { xs: "100%", lg: "60%" }, display: "flex", flexDirection: "column", borderRight: { lg: `1px solid ${tokens.border.subtle}` } }}>
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
              <img
                src={asset.url || asset.thumbnailUrl}
                alt={asset.name}
                style={{ maxWidth: "100%", maxHeight: "100%", objectFit: "contain" }}
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
                onSeek={setCurrentTime}
                onMarkerClick={handleMarkerClick}
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

          {/* Metadata */}
          <Box sx={{ px: 2.5, py: 2, flex: 1, overflow: "auto" }}>
            <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem" }}>
              Metadata
            </Typography>
            <Box sx={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "4px 16px", mt: 1 }}>
              {[
                ["Size", formatBytes(asset.fileSize)],
                ["MIME", asset.mimeType],
                ...(asset.width ? [["Dimensions", `${asset.width}×${asset.height}`]] : []),
                ...(asset.duration ? [["Duration", formatDuration(asset.duration)]] : []),
                ["Owner", userName(asset.ownerId)],
                ["Created", new Date(asset.createdAt).toLocaleDateString()],
                ...Object.entries(asset.metadata).slice(0, 4).map(([k, v]) => [k, String(v)]),
              ].map(([k, v]) => (
                <React.Fragment key={k}>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem" }}>{k}</Typography>
                  <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.72rem", wordBreak: "break-word" }}>{v}</Typography>
                </React.Fragment>
              ))}
            </Box>
          </Box>
        </Box>

        {/* Right: discussion tabs */}
        <Box sx={{ flex: 1, display: "flex", flexDirection: "column", overflow: "hidden", bgcolor: tokens.bg.surface }}>
          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ px: 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, minHeight: 40 }}>
            {tabs.map((t, i) => (
              <Tab key={i} label={t.label} iconPosition="start" icon={t.icon} sx={{ minHeight: 40, fontSize: "0.75rem", px: 1.5 }} />
            ))}
          </Tabs>

          <Box sx={{ flex: 1, overflow: "auto", p: 1.5 }}>
            {/* Overview tab */}
            {tab === 0 && (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
                <Typography variant="body2" sx={{ color: tokens.text.secondary, lineHeight: 1.6 }}>{asset.description}</Typography>
                <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                  {asset.tags.map(t => (
                    <Chip key={t} label={t} size="small" sx={{ height: 20, fontSize: "0.7rem", bgcolor: tokens.bg.elevated }} />
                  ))}
                </Box>
              </Box>
            )}

            {/* Comments tab */}
            {tab === 1 && (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75 }}>
                {comments.length === 0 ? (
                  <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
                    <ChatBubbleOutlineOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
                    <Typography variant="body2" color="text.secondary">No comments yet</Typography>
                  </Box>
                ) : comments.map(c => (
                  <CommentItem
                    key={c.id}
                    comment={c}
                    highlighted={highlightedId === c.id}
                    onTimeClick={(t) => { setCurrentTime(t); setHighlightedId(null); }}
                  />
                ))}
              </Box>
            )}

            {/* Annotations tab */}
            {tab === 2 && (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75 }}>
                {annotations.length === 0 ? (
                  <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
                    <BookmarkBorderOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
                    <Typography variant="body2" color="text.secondary">No annotations yet</Typography>
                  </Box>
                ) : annotations.map(a => (
                  <AnnotationItem
                    key={a.id}
                    ann={a}
                    highlighted={highlightedId === a.id}
                    onTimeClick={(t) => { setCurrentTime(t); setHighlightedId(null); }}
                  />
                ))}
              </Box>
            )}

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
                ) : tasks.map(t => <TaskItem key={t.id} task={t} />)}
              </Box>
            )}
          </Box>
        </Box>
      </Box>
    </Box>
  );
}
