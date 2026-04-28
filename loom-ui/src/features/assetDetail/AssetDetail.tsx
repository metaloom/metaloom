import React, { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams, useNavigate } from "react-router-dom";
import {
  Box, Typography, Chip, IconButton, Tab, Tabs,
  Tooltip, LinearProgress, TextField, InputAdornment,
  Menu, MenuItem, ListItemIcon, ListItemText,
} from "@mui/material";
import {
  ArrowBack, PlayArrowOutlined, PauseOutlined,
  ChatBubbleOutlineOutlined, BookmarkBorderOutlined,
  ThumbUpAltOutlined, TaskAltOutlined, AccountTreeOutlined,
  FaceOutlined, SearchOutlined,
  MoreVertOutlined, SendOutlined, AddTaskOutlined,
  CollectionsOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Asset, AssetType, AssetStatus, Comment, Annotation, Reaction, Task, TranscriptSection, DetectedFace, FaceCluster, Person } from "../../types";
import { useAuth } from "../../context/AuthContext";
import { loadAsset as apiLoadAsset, AssetResponse } from "../../api/assets";
import { listPersons, PersonResponse } from "../../api/persons";
import { listClusters, ClusterResponse as ClusterApiResponse } from "../../api/clusters";
import { listAssetDetections } from "../../api/detections";
import { listAssetTranscripts } from "../../api/transcripts";
import { AnnotationResponseItem } from "../../api/annotations";
import { listAssetReactions, ReactionResponseItem } from "../../api/reactions";
import { listComments, CommentResponse } from "../../api/comments";
import { apiToAsset, formatDuration, formatBytes, userName, tagBreadcrumb } from "./helpers";
import { VideoTimeline, TimelineMarker } from "./VideoTimeline";
import { ZoomableImage } from "./ZoomableImage";
import { CommentItem } from "./CommentItem";
import { AnnotationItem } from "./AnnotationItem";
import { ReactionChip, reactionColor } from "./ReactionChip";
import { TaskItem } from "./TaskItem";
import { TranscriptPanel } from "./TranscriptPanel";
import { FaceDetectionPanel } from "./FaceDetectionPanel";


// ── Main Asset Detail ─────────────────────────────────────────────────────
export default function AssetDetail() {
  const { t: tAD } = useTranslation("translation", { keyPrefix: "assetDetail" });
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
  const [assetCollections, setAssetCollections] = useState<{ uuid: string; name: string }[]>([]);
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
    // Load asset from real API
    apiLoadAsset(token, id).then(resp => {
      setAsset(apiToAsset(resp));
      // Extract collections from the asset response
      setAssetCollections((resp.collections ?? []).map(c => ({ uuid: c.uuid, name: c.name })));
      // Extract annotations from the asset response
      const restAnnotations: Annotation[] = (resp.annotations ?? []).map((a: AnnotationResponseItem) => ({
        id: a.uuid ?? "",
        assetId: a.assetUuid ?? id,
        authorId: a.status?.creator?.uuid ?? "",
        title: a.title ?? "",
        description: a.description ?? "",
        timestampStart: a.area?.from != null ? a.area.from / 1000 : undefined,
        timestampEnd: a.area?.to != null ? a.area.to / 1000 : undefined,
        region: a.area?.width != null && a.area?.height != null && a.area?.startX != null && a.area?.startY != null
          ? { x: a.area.startX, y: a.area.startY, width: a.area.width, height: a.area.height }
          : undefined,
        color: tokens.accent.amber,
        createdAt: a.status?.created ?? "",
      }));
      setAnnotations(restAnnotations);
    }).catch(() => { /* asset not found */ });

    // Load reactions from REST
    listAssetReactions(token, id).then(resp => {
      const restReactions: Reaction[] = (resp.data ?? []).map((r: ReactionResponseItem) => ({
        id: r.uuid ?? "",
        assetId: id,
        userId: r.status?.creator?.uuid ?? "",
        type: (r.type?.toLowerCase() ?? "approve") as Reaction["type"],
        rating: r.rating,
        createdAt: r.status?.created ?? "",
      }));
      setReactions(restReactions);
    }).catch(() => { /* reactions load failed */ });

    // Comments from REST API, other social features still use mock services
    Promise.all([
      token ? listComments(token).then(r => (r.data ?? []).map((c: CommentResponse): Comment => ({
        id: c.uuid,
        assetId: id,
        authorId: c.status?.creator?.uuid ?? "",
        title: c.title,
        text: c.text ?? "",
        createdAt: c.status?.created ?? "",
        updatedAt: c.status?.edited ?? c.status?.created ?? "",
      }))) : Promise.resolve([] as Comment[]),
      Promise.resolve([] as Task[]),
      token ? listAssetTranscripts(token, id).then(resp => {
        const sections: TranscriptSection[] = [];
        for (const tr of (resp.data ?? [])) {
          const json = tr.transcriptJson;
          if (json?.sections) {
            for (const s of json.sections) {
              sections.push({
                id: s.id,
                title: s.title,
                startTime: s.startTime,
                endTime: s.endTime,
                words: (s.words ?? []).map(w => ({
                  word: w.word,
                  startTime: w.startTime,
                  endTime: w.endTime,
                  confidence: w.confidence,
                })),
              });
            }
          }
        }
        return sections;
      }) : Promise.resolve([] as TranscriptSection[]),
      token ? listAssetDetections(token, id).then(resp => (resp.data ?? [])
        .filter(d => d.type === "facedetection")
        .map((d): DetectedFace => ({
          id: d.uuid,
          assetId: d.assetUuid,
          timestamp: d.frameNumber,
          boundingBox: { x: d.bboxX, y: d.bboxY, width: d.bboxWidth, height: d.bboxHeight },
          confidence: d.confidence,
          thumbnailUrl: "",
          clusterId: (d.meta as Record<string, unknown>)?.clusterId as string | undefined,
        }))
      ) : Promise.resolve([] as DetectedFace[]),
      token ? listClusters(token).then(r => r.data.map((c: ClusterApiResponse): FaceCluster => ({
        id: c.uuid, label: c.name, representativeThumbnailUrl: "", faceIds: [], personId: undefined,
      }))) : Promise.resolve([] as FaceCluster[]),
      token ? listPersons(token).then(r => r.data.map((p: PersonResponse): Person => ({
        id: p.uuid, name: [p.firstname, p.lastname].filter(Boolean).join(" ") || p.alias,
        description: p.alias, avatarUrl: "", clusterIds: [], createdAt: p.status?.created ?? "",
      }))) : Promise.resolve([] as Person[]),
    ]).then(([c, t, tr, faces, clusters, pers]) => {
      setComments(c);
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
    { label: tAD("tab.overview"), icon: <AccountTreeOutlined sx={{ fontSize: 14 }} /> },
    { label: tAD("tab.comments", { count: comments.length }), icon: <ChatBubbleOutlineOutlined sx={{ fontSize: 14 }} /> },
    { label: tAD("tab.annotations", { count: annotations.length }), icon: <BookmarkBorderOutlined sx={{ fontSize: 14 }} /> },
    { label: tAD("tab.reactions", { count: reactions.length }), icon: <ThumbUpAltOutlined sx={{ fontSize: 14 }} /> },
    { label: tAD("tab.tasks", { count: tasks.length }), icon: <TaskAltOutlined sx={{ fontSize: 14 }} /> },
    ...(detectedFaces.length > 0 ? [{ label: tAD("tab.faces", { count: detectedFaces.length }), icon: <FaceOutlined sx={{ fontSize: 14 }} /> }] : []),
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
            {/* Collection chips – from the asset response */}
            {(assetCollections ?? []).map(col => (
              <Chip
                key={col.uuid}
                icon={<CollectionsOutlined sx={{ fontSize: 10 }} />}
                label={col.name}
                size="small"
                sx={{ height: 16, fontSize: "0.65rem", bgcolor: `${tokens.primary.main}22`, color: tokens.primary.main, "& .MuiChip-icon": { color: tokens.primary.main } }}
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
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.process")}</ListItemText>
            <Typography variant="caption" sx={{ ml: 2, color: tokens.text.tertiary }}>▸</Typography>
          </MenuItem>
          <MenuItem onClick={() => { setActionMenuAnchor(null); /* create task action */ }}>
            <ListItemIcon><AddTaskOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.createTask")}</ListItemText>
          </MenuItem>
        </Menu>
        <Menu
          anchorEl={pipelineMenuAnchor}
          open={Boolean(pipelineMenuAnchor)}
          onClose={() => { setPipelineMenuAnchor(null); setActionMenuAnchor(null); }}
          anchorOrigin={{ vertical: "top", horizontal: "right" }}
          transformOrigin={{ vertical: "top", horizontal: "left" }}
        >
          {([] as { id: string; name: string }[]).map(p => (
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
              <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>{tAD("annotations.label")}</Typography>
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
              placeholder={tAD("tag.addPlaceholder")}
              size="small"
              variant="standard"
              sx={{ minWidth: 80, maxWidth: 140, "& .MuiInput-root": { fontSize: "0.75rem" }, "& .MuiInput-underline:before": { borderBottom: "none" }, "& .MuiInput-underline:hover:before": { borderBottom: `1px solid ${tokens.border.default}` } }}
            />
          </Box>

          {/* Description */}
          <Box sx={{ px: 2, py: 1.5, bgcolor: tokens.bg.surface, borderTop: `1px solid ${tokens.border.subtle}` }}>
            <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", display: "block", mb: 0.75 }}>
              {tAD("meta.description")}
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
              {tAD("meta.title")}
            </Typography>
            <Box sx={{ mt: 1, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
              {[
                [tAD("meta.size"), formatBytes(asset.fileSize)],
                [tAD("meta.mime"), asset.mimeType],
                ...(asset.width ? [[tAD("meta.dimensions"), `${asset.width}×${asset.height}`]] : []),
                ...(asset.duration ? [[tAD("meta.duration"), formatDuration(asset.duration)]] : []),
                [tAD("meta.owner"), userName(asset.ownerId)],
                [tAD("meta.created"), new Date(asset.createdAt).toLocaleDateString()],
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

          {/* Transcript — inline in content area, synced with player */}
          {transcriptSections.length > 0 && (
            <Box sx={{ px: 2, py: 2, borderTop: `1px solid ${tokens.border.subtle}` }}>
              <TranscriptPanel
                sections={transcriptSections}
                currentTime={currentTime}
                onSeek={setCurrentTime}
                onSectionsChange={setTranscriptSections}
              />
            </Box>
          )}
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
                placeholder={tAD("sidebar.filterPlaceholder")}
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
                    <Typography variant="body2" color="text.secondary">{comments.length === 0 ? tAD("empty.noComments") : tAD("empty.noMatchComments")}</Typography>
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
                    <Typography variant="body2" color="text.secondary">{annotations.length === 0 ? tAD("empty.noAnnotations") : tAD("empty.noMatchAnnotations")}</Typography>
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
                    <Typography variant="body2" color="text.secondary">{tAD("empty.noReactions")}</Typography>
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
                    <Typography variant="body2" color="text.secondary">{tAD("empty.noTasks")}</Typography>
                  </Box>
                ) : tasks.map(t => <TaskItem key={t.id} task={t} onClick={() => setSelectedTask(t)} />)}
              </Box>
            )}

            {/* Faces tab */}
            {detectedFaces.length > 0 && tab === 5 && (
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
              <Typography variant="h6" fontWeight={700} sx={{ fontSize: "0.95rem", flex: 1 }}>{tAD("taskDetail.title")}</Typography>
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
                  [tAD("taskDetail.status"), <Chip label={selectedTask.status.replace("_", " ")} size="small" sx={{ height: 18, fontSize: "0.7rem", bgcolor: `${{ open: tokens.accent.blue, in_progress: tokens.accent.amber, review: tokens.primary.main, done: tokens.accent.green, blocked: tokens.accent.red }[selectedTask.status]}22`, color: { open: tokens.accent.blue, in_progress: tokens.accent.amber, review: tokens.primary.main, done: tokens.accent.green, blocked: tokens.accent.red }[selectedTask.status] }} />],
                  [tAD("taskDetail.priority"), <Chip label={selectedTask.priority} size="small" sx={{ height: 18, fontSize: "0.7rem", bgcolor: `${{ critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary }[selectedTask.priority]}22`, color: { critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary }[selectedTask.priority], fontWeight: 700 }} />],
                  [tAD("taskDetail.assignee"), <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>{selectedTask.assigneeId}</Typography>],
                  [tAD("taskDetail.dueDate"), <Typography sx={{ fontSize: "0.82rem", color: selectedTask.dueDate && new Date(selectedTask.dueDate) < new Date() ? tokens.accent.red : tokens.text.secondary }}>{selectedTask.dueDate ? new Date(selectedTask.dueDate).toLocaleDateString() : "—"}</Typography>],
                  [tAD("taskDetail.created"), <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>{new Date(selectedTask.createdAt).toLocaleDateString()}</Typography>],
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
                  <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.06em", color: tokens.text.tertiary, fontSize: "0.68rem", display: "block", mb: 0.75 }}>{tAD("taskDetail.tags")}</Typography>
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
