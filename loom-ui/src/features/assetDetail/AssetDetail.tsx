import React, { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams, useNavigate } from "react-router-dom";
import {
  Box, Typography, Chip, IconButton, Tab, Tabs,
  Tooltip, LinearProgress, TextField, InputAdornment,
  Menu, MenuItem, ListItemIcon, ListItemText,
  Button, CircularProgress,
  Dialog, DialogTitle, DialogContent, DialogActions,
} from "@mui/material";
import {
  ArrowBack, PlayArrowOutlined, PauseOutlined,
  ChatBubbleOutlineOutlined, BookmarkBorderOutlined,
  ThumbUpAltOutlined, TaskAltOutlined, AccountTreeOutlined,
  FaceOutlined, SearchOutlined,
  MoreVertOutlined, SendOutlined, AddTaskOutlined,
  CollectionsOutlined, CropFreeOutlined, SaveOutlined, DeleteOutlineOutlined,
  DownloadOutlined, UploadFileOutlined, LinkOutlined,
  StorageOutlined, LockOutlined, LaunchOutlined,
  CenterFocusStrongOutlined, CheckOutlined, AddOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { Asset, AssetType, AssetStatus, Comment, Annotation, TranscriptSection, DetectedFace, FaceCluster, Person } from "../../types";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { loadAsset as apiLoadAsset, updateAsset, deleteAsset, AssetResponse, TagReference, AssetLocationInfo } from "../../api/assets";
import { uploadAssetBinary, downloadAssetBinary, deleteAssetBinary, createAssetBinaryMeta } from "../../api/binaries";
import MediaPlaceholder from "../../components/MediaPlaceholder";
import { listPipelines, runPipeline, PipelineResponse } from "../../api/pipelines";
import { tagAsset as apiTagAsset, untagAsset as apiUntagAsset, DEFAULT_TAG_COLLECTION } from "../../api/tags";
import { AreaInfo } from "../../api/annotations";
import { listPersons, PersonResponse } from "../../api/persons";
import { toUiPerson } from "../faceDetection/personMapping";
import { listAssetClusters, ClusterResponse as ClusterApiResponse } from "../../api/clusters";
import {
  listAssetDetections, createDetection, updateDetection, deleteDetection,
  bulkCreateDetections, DetectionResponse,
} from "../../api/detections";
import { listAssetTranscripts, createTranscript, updateTranscript, deleteTranscript, TranscriptResponse } from "../../api/transcripts";
import { AnnotationResponseItem, createAnnotation, updateAnnotation, deleteAnnotation } from "../../api/annotations";
import { listAssetReactions, createAssetReaction, deleteAssetReaction, ReactionResponseItem, TaskReactionType } from "../../api/reactions";
import { listCommentsForAsset, createCommentForAsset, updateComment, deleteComment, CommentResponse } from "../../api/comments";
import { listAssetTasks, assignTaskToAsset, createTask, TaskResponse } from "../../api/tasks";
import { apiToAsset, formatDuration, formatBytes, userName, tagBreadcrumb } from "./helpers";
import { VideoTimeline, TimelineMarker } from "./VideoTimeline";
import { ZoomableImage } from "./ZoomableImage";
import { CommentItem } from "./CommentItem";
import { AnnotationItem } from "./AnnotationItem";
import { ReactionsPanel } from "../reactions/ReactionsPanel";
import { TaskItem, taskPriorityColor, taskStatusColor } from "./TaskItem";
import { TranscriptPanel } from "./TranscriptPanel";
import { FaceDetectionPanel } from "./FaceDetectionPanel";
import { PAGE_SIZE } from "../../hooks/pagedList";


// Map a REST comment response onto the local Comment view model.
function commentResponseToComment(c: CommentResponse, assetId: string): Comment {
  return {
    id: c.uuid,
    assetId: c.assetUuid ?? assetId,
    authorId: c.status?.creator?.uuid ?? "",
    title: c.title,
    text: c.text ?? "",
    createdAt: c.status?.created ?? "",
    updatedAt: c.status?.edited ?? c.status?.created ?? "",
  };
}

// Map a REST annotation response onto the local Annotation view model.
function annotationResponseToAnnotation(a: AnnotationResponseItem, assetId: string): Annotation {
  return {
    id: a.uuid ?? "",
    assetId: a.assetUuid ?? assetId,
    authorId: a.status?.creator?.uuid ?? "",
    type: a.type,
    title: a.title ?? "",
    description: a.description ?? "",
    timestampStart: a.area?.from != null ? a.area.from / 1000 : undefined,
    timestampEnd: a.area?.to != null ? a.area.to / 1000 : undefined,
    region: a.area?.width != null && a.area?.height != null && a.area?.startX != null && a.area?.startY != null
      ? { x: a.area.startX, y: a.area.startY, width: a.area.width, height: a.area.height }
      : undefined,
    color: tokens.accent.amber,
    createdAt: a.status?.created ?? "",
  };
}

// A single transcript kept as its own group so update/delete stay keyed on the
// transcript uuid (an asset may have several — different source/language).
interface TranscriptGroup {
  uuid: string;
  source?: string;
  lang?: string;
  sections: TranscriptSection[];
}

// Map a REST transcript response onto a local TranscriptGroup view model.
function transcriptResponseToGroup(tr: TranscriptResponse): TranscriptGroup {
  return {
    uuid: tr.uuid,
    source: tr.source,
    lang: tr.lang,
    sections: (tr.transcriptJson?.sections ?? []).map(s => ({
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
    })),
  };
}

// ── Main Asset Detail ─────────────────────────────────────────────────────
export default function AssetDetail() {
  const { t: tAD } = useTranslation("translation", { keyPrefix: "assetDetail" });
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { token, userUuid } = useAuth();
  const [asset, setAsset] = useState<Asset | null>(null);
  const [comments, setComments] = useState<Comment[]>([]);
  const [annotations, setAnnotations] = useState<Annotation[]>([]);
  const [reactions, setReactions] = useState<ReactionResponseItem[]>([]);
  const [tasks, setTasks] = useState<TaskResponse[]>([]);
  const [transcripts, setTranscripts] = useState<TranscriptGroup[]>([]);
  // Add-transcript dialog (captures source + language, then creates an empty transcript)
  const [transcriptAddOpen, setTranscriptAddOpen] = useState(false);
  const [transcriptSource, setTranscriptSource] = useState("");
  const [transcriptLang, setTranscriptLang] = useState("");
  const [creatingTranscript, setCreatingTranscript] = useState(false);
  const [detectedFaces, setDetectedFaces] = useState<DetectedFace[]>([]);
  const [faceClusters, setFaceClusters] = useState<FaceCluster[]>([]);
  const [persons, setPersons] = useState<Person[]>([]);
  const [assetCollections, setAssetCollections] = useState<{ uuid: string; name: string }[]>([]);
  const [assetLocations, setAssetLocations] = useState<AssetLocationInfo[]>([]);
  const [tab, setTab] = useState(0);
  const [sidebarQuery, setSidebarQuery] = useState("");
  const [currentTime, setCurrentTime] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [highlightedId, setHighlightedId] = useState<string | null>(null);
  const [hoveredMarkerId, setHoveredMarkerId] = useState<string | null>(null);
  const [selectedTask, setSelectedTask] = useState<TaskResponse | null>(null);
  // Create-task dialog (creates a task and assigns it to this asset)
  const [taskCreateOpen, setTaskCreateOpen] = useState(false);
  const [taskTitle, setTaskTitle] = useState("");
  const [taskDescription, setTaskDescription] = useState("");
  const [taskPriority, setTaskPriority] = useState("MEDIUM");
  const [taskDueDate, setTaskDueDate] = useState("");
  const [creatingTask, setCreatingTask] = useState(false);
  const [tagInput, setTagInput] = useState("");
  const [commentInput, setCommentInput] = useState("");
  const [postingComment, setPostingComment] = useState(false);
  const [editingCommentId, setEditingCommentId] = useState<string | null>(null);
  const [annotationTitleInput, setAnnotationTitleInput] = useState("");
  const [annotationDescInput, setAnnotationDescInput] = useState("");
  const [postingAnnotation, setPostingAnnotation] = useState(false);
  const [editingAnnotationId, setEditingAnnotationId] = useState<string | null>(null);
  const [assetTags, setAssetTags] = useState<TagReference[]>([]);
  const [regionMode, setRegionMode] = useState(false);
  const [pendingArea, setPendingArea] = useState<AreaInfo | null>(null);
  // Object-detection bounding-box editing on the central image.
  const [detections, setDetections] = useState<DetectionResponse[]>([]);
  const [detectionMode, setDetectionMode] = useState(false);
  const [redrawId, setRedrawId] = useState<string | null>(null);
  const [bulkMode, setBulkMode] = useState(false);
  const [stagedBoxes, setStagedBoxes] = useState<{ x: number; y: number; width: number; height: number }[]>([]);
  const tagInputRef = useRef<HTMLInputElement>(null);
  const [actionMenuAnchor, setActionMenuAnchor] = useState<null | HTMLElement>(null);
  const [pipelineMenuAnchor, setPipelineMenuAnchor] = useState<null | HTMLElement>(null);
  const { showToast } = useToast();
  const { t: tCommon } = useTranslation();
  const [editName, setEditName] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [pipelines, setPipelines] = useState<PipelineResponse[]>([]);
  // Draggable left/right split (percentage)
  const [leftPct, setLeftPct] = useState(60);
  const isDragging = useRef(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const intervalRef = useRef<number | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [binaryBusy, setBinaryBusy] = useState(false);
  const [registerBinaryOpen, setRegisterBinaryOpen] = useState(false);
  const [registerBinaryPath, setRegisterBinaryPath] = useState("");

  // Refresh the asset's object detections from the backend (used after each mutation).
  const reloadDetections = useCallback(() => {
    if (!token || !id) return Promise.resolve();
    return listAssetDetections(token, id)
      .then(resp => setDetections((resp.data ?? []).filter(d => d.type === "objectdetection")))
      .catch(() => { /* detection load failed */ });
  }, [token, id]);

  useEffect(() => {
    if (!id || !token) return;
    reloadDetections();
    // Load asset from real API
    apiLoadAsset(token, id).then(resp => {
      const mapped = apiToAsset(resp);
      setAsset(mapped);
      setEditName(mapped.name);
      setAssetTags(resp.tags ?? []);
      // Extract collections from the asset response
      setAssetCollections((resp.collections ?? []).map(c => ({ uuid: c.uuid, name: c.name })));
      // Storage locations of the binary (path / pool / state / license)
      setAssetLocations(resp.locations ?? []);
      // Extract annotations from the asset response
      const restAnnotations: Annotation[] = (resp.annotations ?? []).map((a: AnnotationResponseItem) => annotationResponseToAnnotation(a, id));
      setAnnotations(restAnnotations);
    }).catch(() => { /* asset not found */ });

    // Load pipelines for the "Process" menu
    listPipelines(token).then(resp => setPipelines(resp.data ?? [])).catch(() => { /* pipelines optional */ });

    // Load reactions from REST
    listAssetReactions(token, id)
      .then(resp => setReactions(resp.data ?? []))
      .catch(() => { /* reactions load failed */ });

    // Comments, tasks and transcripts from the REST API
    Promise.all([
      token ? listCommentsForAsset(token, id).then(r => (r.data ?? []).map((c: CommentResponse) => commentResponseToComment(c, id))) : Promise.resolve([] as Comment[]),
      token ? listAssetTasks(token, id).then(r => r.data ?? []).catch(() => [] as TaskResponse[]) : Promise.resolve([] as TaskResponse[]),
      token ? listAssetTranscripts(token, id).then(resp => (resp.data ?? []).map(transcriptResponseToGroup)) : Promise.resolve([] as TranscriptGroup[]),
      token ? listAssetDetections(token, id).then(resp => (resp.data ?? [])
        // "face" is what FacedetectNode writes. This read "facedetection" — which is the node's *options*
        // key, not its detection type — so the panel matched the demo seed and never a real asset.
        .filter(d => d.type === "face")
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
      // The clusters computed within THIS asset, not every cluster in the library. The panel groups the
      // asset's faces by subject, so a library-wide list could only ever have produced groups whose
      // members are not here.
      token && id ? listAssetClusters(token, id).then(r => (r.data ?? []).map((c: ClusterApiResponse): FaceCluster => ({
        id: c.uuid,
        label: c.name || "Unnamed cluster",
        representativeThumbnailUrl: "",
        faceIds: [],
        faceCount: c.memberCount ?? 0,
        assetId: c.assetUuid,
        reviewStatus: c.reviewStatus,
        score: c.score,
        personId: c.personUuid,
      }))) : Promise.resolve([] as FaceCluster[]),
      token ? listPersons(token, { limit: PAGE_SIZE }).then(r => r.data.map((p: PersonResponse) => toUiPerson(p)))
        : Promise.resolve([] as Person[]),
    ]).then(([c, t, tr, faces, clusters, pers]) => {
      setComments(c);
      setTasks(t);
      setTranscripts(tr);
      setDetectedFaces(faces);
      setFaceClusters(clusters);
      setPersons(pers);
    });
  }, [id, token, reloadDetections]);

  // ── Create-task handler (creates the task, then assigns it to this asset) ──
  const handleCreateTask = useCallback(async () => {
    const title = taskTitle.trim();
    if (!title || !token || !id || creatingTask) return;
    setCreatingTask(true);
    try {
      const created = await createTask(token, {
        title,
        description: taskDescription.trim() || undefined,
        priority: taskPriority,
        // The REST API expects second precision (yyyy-MM-dd'T'HH:mm:ssX)
        dueDate: taskDueDate ? new Date(taskDueDate).toISOString().replace(/\.\d{3}Z$/, "Z") : undefined,
      });
      await assignTaskToAsset(token, id, created.uuid);
      const refreshed = await listAssetTasks(token, id).then(r => r.data ?? []).catch(() => null);
      setTasks(prev => refreshed ?? [created, ...prev]);
      setTaskCreateOpen(false);
      setTaskTitle("");
      setTaskDescription("");
      setTaskPriority("MEDIUM");
      setTaskDueDate("");
    } catch {
      showToast(tAD("taskCreate.error"), "error");
    } finally {
      setCreatingTask(false);
    }
  }, [taskTitle, taskDescription, taskPriority, taskDueDate, token, id, creatingTask, showToast, tAD]);

  // ── Comment authoring handlers ──────────────────────────────────────
  const handlePostComment = useCallback(async () => {
    const text = commentInput.trim();
    if (!text || !token || !id || postingComment) return;
    setPostingComment(true);
    try {
      const created = await createCommentForAsset(token, id, { text });
      setComments(prev => [commentResponseToComment(created, id), ...prev]);
      setCommentInput("");
    } catch {
      showToast(tAD("comment.postError"), "error");
    } finally {
      setPostingComment(false);
    }
  }, [commentInput, token, id, postingComment, showToast, tAD]);

  const handleEditComment = useCallback(async (commentId: string, text: string) => {
    const trimmed = text.trim();
    if (!trimmed || !token) return;
    try {
      const updated = await updateComment(token, commentId, { text: trimmed });
      setComments(prev => prev.map(c => (c.id === commentId
        ? commentResponseToComment(updated, c.assetId)
        : c)));
      setEditingCommentId(null);
    } catch {
      showToast(tAD("comment.editError"), "error");
    }
  }, [token, showToast, tAD]);

  const handleDeleteComment = useCallback(async (commentId: string) => {
    if (!token) return;
    try {
      await deleteComment(token, commentId);
      setComments(prev => prev.filter(c => c.id !== commentId));
    } catch {
      showToast(tAD("comment.deleteError"), "error");
    }
  }, [token, showToast, tAD]);

  // ── Annotation authoring handlers ───────────────────────────────────
  const handleCreateAnnotation = useCallback(async () => {
    const title = annotationTitleInput.trim();
    if (!title || !token || !id || postingAnnotation) return;
    setPostingAnnotation(true);
    try {
      const created = await createAnnotation(token, {
        type: "FEEDBACK",
        title,
        description: annotationDescInput.trim(),
        assetUuid: id,
        ...(pendingArea ? { area: pendingArea } : {}),
      });
      setAnnotations(prev => [annotationResponseToAnnotation(created, id), ...prev]);
      setAnnotationTitleInput("");
      setAnnotationDescInput("");
      setPendingArea(null);
      setRegionMode(false);
    } catch {
      showToast(tAD("annotation.addError"), "error");
    } finally {
      setPostingAnnotation(false);
    }
  }, [annotationTitleInput, annotationDescInput, pendingArea, token, id, postingAnnotation, showToast, tAD]);

  const handleEditAnnotation = useCallback(async (annotationId: string, title: string, description: string) => {
    const trimmed = title.trim();
    if (!trimmed || !token) return;
    try {
      const updated = await updateAnnotation(token, annotationId, { title: trimmed, description: description.trim() });
      setAnnotations(prev => prev.map(a => (a.id === annotationId
        ? annotationResponseToAnnotation(updated, a.assetId)
        : a)));
      setEditingAnnotationId(null);
    } catch {
      showToast(tAD("annotation.editError"), "error");
    }
  }, [token, showToast, tAD]);

  const handleDeleteAnnotation = useCallback(async (annotationId: string) => {
    if (!token) return;
    try {
      await deleteAnnotation(token, annotationId);
      setAnnotations(prev => prev.filter(a => a.id !== annotationId));
    } catch {
      showToast(tAD("annotation.deleteError"), "error");
    }
  }, [token, showToast, tAD]);

  const handleAddReaction = useCallback(async (type: TaskReactionType) => {
    if (!token || !id) return;
    try {
      const created = await createAssetReaction(token, id, { type });
      setReactions(prev => [created, ...prev]);
    } catch {
      showToast(tAD("reaction.addError"), "error");
    }
  }, [token, id, showToast, tAD]);

  const handleDeleteReaction = useCallback(async (reactionUuid: string) => {
    if (!token || !id) return;
    try {
      await deleteAssetReaction(token, id, reactionUuid);
      setReactions(prev => prev.filter(r => r.uuid !== reactionUuid));
    } catch {
      showToast(tAD("reaction.deleteError"), "error");
    }
  }, [token, id, showToast, tAD]);

  // ── Transcript handlers ─────────────────────────────────────────────
  // Persist section edits (title on blur, boundary nudges) for one transcript.
  const handleTranscriptSectionsChange = useCallback(async (transcriptUuid: string, sections: TranscriptSection[]) => {
    // Optimistic local update keeps the panel responsive.
    setTranscripts(prev => prev.map(tr => (tr.uuid === transcriptUuid ? { ...tr, sections } : tr)));
    if (!token || !id) return;
    try {
      await updateTranscript(token, id, transcriptUuid, { transcriptJson: { sections } });
    } catch {
      showToast(tAD("transcript.updateError"), "error");
    }
  }, [token, id, showToast, tAD]);

  const handleDeleteTranscript = useCallback(async (transcriptUuid: string) => {
    if (!token || !id) return;
    try {
      await deleteTranscript(token, id, transcriptUuid);
      setTranscripts(prev => prev.filter(tr => tr.uuid !== transcriptUuid));
    } catch {
      showToast(tAD("transcript.deleteError"), "error");
    }
  }, [token, id, showToast, tAD]);

  const handleAddTranscript = useCallback(async () => {
    if (!token || !id) return;
    setCreatingTranscript(true);
    try {
      const created = await createTranscript(token, id, {
        source: transcriptSource.trim() || undefined,
        lang: transcriptLang.trim() || undefined,
        transcriptJson: { sections: [] },
      });
      setTranscripts(prev => [...prev, transcriptResponseToGroup(created)]);
      setTranscriptAddOpen(false);
      setTranscriptSource("");
      setTranscriptLang("");
    } catch {
      showToast(tAD("transcript.createError"), "error");
    } finally {
      setCreatingTranscript(false);
    }
  }, [token, id, transcriptSource, transcriptLang, showToast, tAD]);

  // ── Object-detection handlers ───────────────────────────────────────
  // Detections use normalized (0-1) bbox coordinates, matching ZoomableImage regions.
  const DEFAULT_DETECTION_CONFIDENCE = 1;

  const handleCreateDetection = useCallback(async (box: { x: number; y: number; width: number; height: number }) => {
    if (!token || !id) return;
    try {
      const created = await createDetection(token, id, {
        type: "objectdetection",
        bboxX: box.x, bboxY: box.y, bboxWidth: box.width, bboxHeight: box.height,
        confidence: DEFAULT_DETECTION_CONFIDENCE,
      });
      setDetections(prev => [...prev, created]);
    } catch {
      showToast(tAD("detection.createError"), "error");
    }
  }, [token, id, showToast, tAD]);

  const handleBulkSaveDetections = useCallback(async () => {
    if (!token || !id || stagedBoxes.length === 0) return;
    try {
      await bulkCreateDetections(token, id, {
        detections: stagedBoxes.map(b => ({
          type: "objectdetection",
          bboxX: b.x, bboxY: b.y, bboxWidth: b.width, bboxHeight: b.height,
          confidence: DEFAULT_DETECTION_CONFIDENCE,
        })),
      });
      setStagedBoxes([]);
      await reloadDetections();
    } catch {
      showToast(tAD("detection.createError"), "error");
    }
  }, [token, id, stagedBoxes, reloadDetections, showToast, tAD]);

  const handleUpdateDetectionBox = useCallback(async (uuid: string, box: { x: number; y: number; width: number; height: number }) => {
    if (!token || !id) return;
    try {
      const updated = await updateDetection(token, id, uuid, {
        bboxX: box.x, bboxY: box.y, bboxWidth: box.width, bboxHeight: box.height,
      });
      setDetections(prev => prev.map(d => (d.uuid === uuid ? updated : d)));
    } catch {
      showToast(tAD("detection.updateError"), "error");
    }
  }, [token, id, showToast, tAD]);

  const handleUpdateDetectionConfidence = useCallback(async (uuid: string, confidence: number) => {
    if (!token || !id) return;
    try {
      const updated = await updateDetection(token, id, uuid, { confidence });
      setDetections(prev => prev.map(d => (d.uuid === uuid ? updated : d)));
    } catch {
      showToast(tAD("detection.updateError"), "error");
    }
  }, [token, id, showToast, tAD]);

  const handleConfirmDetection = useCallback(async (uuid: string) => {
    if (!token || !id) return;
    const det = detections.find(d => d.uuid === uuid);
    try {
      const updated = await updateDetection(token, id, uuid, {
        meta: { ...(det?.meta ?? {}), confirmed: true },
      });
      setDetections(prev => prev.map(d => (d.uuid === uuid ? updated : d)));
    } catch {
      showToast(tAD("detection.updateError"), "error");
    }
  }, [token, id, detections, showToast, tAD]);

  const handleDeleteDetection = useCallback(async (uuid: string) => {
    if (!token || !id) return;
    try {
      await deleteDetection(token, id, uuid);
      setDetections(prev => prev.filter(d => d.uuid !== uuid));
      if (redrawId === uuid) setRedrawId(null);
    } catch {
      showToast(tAD("detection.deleteError"), "error");
    }
  }, [token, id, redrawId, showToast, tAD]);

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

  // ── Asset metadata edit / delete / process ──────────────────────────────
  const nameDirty = editName.trim() !== "" && editName.trim() !== asset.name;

  const handleSave = () => {
    if (!token || !nameDirty) return;
    setSaving(true);
    updateAsset(token, asset.id, { filename: editName.trim(), meta: asset.metadata })
      .then(resp => {
        setAsset(apiToAsset(resp));
        setEditName(apiToAsset(resp).name);
        showToast(tAD("toast.saved"), "success");
      })
      .catch(() => showToast(tAD("toast.saveFailed"), "error"))
      .finally(() => setSaving(false));
  };

  const handleDeleteAsset = () => {
    if (!token) return;
    deleteAsset(token, asset.id)
      .then(() => {
        showToast(tAD("toast.deleted"), "success");
        navigate("/assets");
      })
      .catch(() => showToast(tAD("toast.deleteFailed"), "error"));
  };

  const handleRunPipeline = (pipelineUuid: string) => {
    if (!token) return;
    setPipelineMenuAnchor(null);
    setActionMenuAnchor(null);
    runPipeline(token, pipelineUuid, { mediaUuids: [asset.id] })
      .then(resp => showToast(resp.dispatched ? tAD("toast.processStarted") : (resp.message || tAD("toast.processFailed")), resp.dispatched ? "success" : "error"))
      .catch(() => showToast(tAD("toast.processFailed"), "error"));
  };

  // ── Binary upload / download / remove ───────────────────────────────────
  // Refresh the core asset (mime type / derived type can change after a binary swap).
  const reloadAsset = () => {
    if (!token) return;
    apiLoadAsset(token, asset.id).then(resp => {
      setAsset(apiToAsset(resp));
      setAssetLocations(resp.locations ?? []);
    }).catch(() => {});
  };

  const handleBinaryUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file
    if (!token || !file) return;
    setBinaryBusy(true);
    uploadAssetBinary(token, asset.id, file)
      .then(() => {
        showToast(tAD("toast.binaryUploaded"), "success");
        reloadAsset();
      })
      .catch(() => showToast(tAD("toast.binaryUploadFailed"), "error"))
      .finally(() => setBinaryBusy(false));
  };

  const handleBinaryDownload = () => {
    if (!token) return;
    setBinaryBusy(true);
    downloadAssetBinary(token, asset.id, asset.name)
      .catch(() => showToast(tAD("toast.binaryDownloadFailed"), "error"))
      .finally(() => setBinaryBusy(false));
  };

  const handleBinaryRemove = () => {
    if (!token) return;
    setActionMenuAnchor(null);
    setBinaryBusy(true);
    deleteAssetBinary(token, asset.id)
      .then(() => {
        showToast(tAD("toast.binaryRemoved"), "success");
        reloadAsset();
      })
      .catch(() => showToast(tAD("toast.binaryRemoveFailed"), "error"))
      .finally(() => setBinaryBusy(false));
  };

  // Register binary *metadata* — point the asset at bytes already present on disk
  // (no upload). The backend requires libraryUuid, sourced from the asset itself.
  const handleRegisterBinary = () => {
    const path = registerBinaryPath.trim();
    if (!token || !path) return;
    setBinaryBusy(true);
    createAssetBinaryMeta(token, asset.id, {
      libraryUuid: asset.libraryId,
      filesystem: { path },
    })
      .then(() => {
        showToast(tAD("toast.binaryRegistered"), "success");
        setRegisterBinaryOpen(false);
        setRegisterBinaryPath("");
        reloadAsset();
      })
      .catch(() => showToast(tAD("toast.binaryRegisterFailed"), "error"))
      .finally(() => setBinaryBusy(false));
  };

  // ── Region tagging helpers ──────────────────────────────────────────────
  // Area coordinates are stored as normalized permille (0-1000) because the DB columns are ints.
  const toPermille = (v: number) => Math.round(v * 1000);
  const fromPermille = (v: number) => v / 1000;
  const isSpatial = (a?: AreaInfo) => !!a && a.startX != null && a.startY != null && a.width != null && a.height != null;
  const isTemporal = (a?: AreaInfo) => !!a && a.from != null;

  const reloadTags = () => {
    if (!token) return Promise.resolve();
    return apiLoadAsset(token, asset.id).then(resp => {
      setAsset(apiToAsset(resp));
      setAssetTags(resp.tags ?? []);
    }).catch(() => { /* reload failed */ });
  };

  const handleAddTag = (rawName: string) => {
    const name = rawName.trim();
    if (!name || !token) return;
    const request = { name, collection: DEFAULT_TAG_COLLECTION, ...(pendingArea ? { area: pendingArea } : {}) };
    apiTagAsset(token, asset.id, request).then(() => reloadTags()).catch(() => { /* tag failed */ });
    setTagInput("");
    setPendingArea(null);
    setRegionMode(false);
  };

  const handleRemoveTag = (uuid: string) => {
    if (!token) return;
    apiUntagAsset(token, asset.id, uuid).then(() => reloadTags()).catch(() => { /* untag failed */ });
  };

  // Existing spatial region tags (normalized 0-1) plus the in-progress selection preview.
  const imageRegions = [
    ...assetTags.filter(t => isSpatial(t.area)).map(t => ({
      id: t.uuid, label: t.name, color: tokens.primary.main,
      x: fromPermille(t.area!.startX!), y: fromPermille(t.area!.startY!),
      width: fromPermille(t.area!.width!), height: fromPermille(t.area!.height!),
    })),
    ...(isSpatial(pendingArea ?? undefined) ? [{
      id: "__pending__", label: tagInput || tAD("tag.newRegion"), color: tokens.accent.amber,
      x: fromPermille(pendingArea!.startX!), y: fromPermille(pendingArea!.startY!),
      width: fromPermille(pendingArea!.width!), height: fromPermille(pendingArea!.height!),
    }] : []),
  ];

  // Object-detection bounding boxes overlaid on the image while in detection mode.
  const detectionLabel = (d: DetectionResponse) => ((d.meta as Record<string, unknown> | undefined)?.label as string) ?? tAD("detection.defaultLabel");
  const isConfirmed = (d: DetectionResponse) => ((d.meta as Record<string, unknown> | undefined)?.confirmed) === true;
  const detectionRegions = detectionMode
    ? [
        ...detections.map(d => ({
          id: d.uuid,
          label: `${detectionLabel(d)} · ${Math.round(d.confidence * 100)}%`,
          color: isConfirmed(d) ? tokens.accent.green : tokens.accent.amber,
          x: d.bboxX, y: d.bboxY, width: d.bboxWidth, height: d.bboxHeight,
        })),
        ...stagedBoxes.map((b, i) => ({
          id: `__staged_${i}__`, label: tAD("detection.staged"), color: tokens.primary.main,
          x: b.x, y: b.y, width: b.width, height: b.height,
        })),
      ]
    : [];

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
    ...assetTags.filter(t => isTemporal(t.area)).map(t => ({
      // area.from/to are milliseconds; the timeline works in seconds.
      time: t.area!.from! / 1000, endTime: t.area!.to != null ? t.area!.to / 1000 : undefined,
      type: "tag" as const, color: tokens.primary.main, label: t.name, id: t.uuid,
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
          <TextField
            value={editName}
            onChange={e => setEditName(e.target.value)}
            variant="standard"
            fullWidth
            InputProps={{ disableUnderline: true, sx: { fontSize: "0.95rem", fontWeight: 700 } }}
            sx={{ "& .MuiInput-root:hover": { bgcolor: tokens.bg.elevated }, borderRadius: tokens.radius.sm, px: 0.5 }}
          />
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
        {/* Save filename/meta */}
        <Tooltip title={tAD("action.save")}>
          <span>
            <Button
              size="small"
              variant="contained"
              startIcon={saving ? <CircularProgress size={13} /> : <SaveOutlined sx={{ fontSize: 15 }} />}
              disabled={!nameDirty || saving}
              onClick={handleSave}
              sx={{ fontSize: "0.75rem" }}
            >
              {tAD("action.save")}
            </Button>
          </span>
        </Tooltip>
        {/* Download binary */}
        <Tooltip title={tAD("action.download")}>
          <span>
            <IconButton size="small" onClick={handleBinaryDownload} disabled={binaryBusy}>
              {binaryBusy ? <CircularProgress size={16} /> : <DownloadOutlined sx={{ fontSize: 18 }} />}
            </IconButton>
          </span>
        </Tooltip>
        {/* Hidden input for binary upload / replace */}
        <input
          ref={fileInputRef}
          type="file"
          style={{ display: "none" }}
          onChange={handleBinaryUpload}
        />
        {/* Actions menu */}
        <IconButton data-testid="asset-actions-menu-button" size="small" onClick={e => setActionMenuAnchor(e.currentTarget)}>
          <MoreVertOutlined sx={{ fontSize: 18 }} />
        </IconButton>
        <Menu anchorEl={actionMenuAnchor} open={Boolean(actionMenuAnchor)} onClose={() => setActionMenuAnchor(null)}>
          <MenuItem onClick={e => { setPipelineMenuAnchor(e.currentTarget); }}>
            <ListItemIcon><SendOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.process")}</ListItemText>
            <Typography variant="caption" sx={{ ml: 2, color: tokens.text.tertiary }}>▸</Typography>
          </MenuItem>
          <MenuItem data-testid="asset-task-create-menu-item" onClick={() => { setActionMenuAnchor(null); setTaskCreateOpen(true); }}>
            <ListItemIcon><AddTaskOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.createTask")}</ListItemText>
          </MenuItem>
          <MenuItem data-testid="asset-transcript-create-menu-item" onClick={() => { setActionMenuAnchor(null); setTranscriptAddOpen(true); }}>
            <ListItemIcon><AddOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.addTranscript")}</ListItemText>
          </MenuItem>
          <MenuItem disabled={binaryBusy} onClick={() => { setActionMenuAnchor(null); fileInputRef.current?.click(); }}>
            <ListItemIcon><UploadFileOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.uploadBinary")}</ListItemText>
          </MenuItem>
          {/* Register bytes that already exist on disk (no upload). The route always inserts,
              so only offer it when the asset has no binary/location yet. */}
          {assetLocations.length === 0 && (
            <MenuItem data-testid="asset-register-binary-menu-item" disabled={binaryBusy} onClick={() => { setActionMenuAnchor(null); setRegisterBinaryOpen(true); }}>
              <ListItemIcon><LinkOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
              <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.registerBinary")}</ListItemText>
            </MenuItem>
          )}
          <MenuItem disabled={binaryBusy} onClick={handleBinaryRemove}>
            <ListItemIcon><DeleteOutlineOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.removeBinary")}</ListItemText>
          </MenuItem>
          <MenuItem onClick={() => { setActionMenuAnchor(null); setDeleteOpen(true); }}>
            <ListItemIcon><DeleteOutlineOutlined sx={{ fontSize: 16, color: tokens.accent.red }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem", color: tokens.accent.red }}>{tAD("action.delete")}</ListItemText>
          </MenuItem>
        </Menu>
        <Menu
          anchorEl={pipelineMenuAnchor}
          open={Boolean(pipelineMenuAnchor)}
          onClose={() => { setPipelineMenuAnchor(null); setActionMenuAnchor(null); }}
          anchorOrigin={{ vertical: "top", horizontal: "right" }}
          transformOrigin={{ vertical: "top", horizontal: "left" }}
        >
          {pipelines.length === 0 ? (
            <MenuItem disabled sx={{ gap: 1 }}>
              <Typography variant="body2" sx={{ fontSize: "0.82rem", color: tokens.text.tertiary }}>{tAD("action.noPipelines")}</Typography>
            </MenuItem>
          ) : pipelines.map(p => (
            <MenuItem key={p.uuid} onClick={() => handleRunPipeline(p.uuid)} sx={{ gap: 1 }}>
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
                <MediaPlaceholder type={asset.type} iconSize={64} bgcolor="#000" />
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
            ) : asset.url ? (
              <ZoomableImage
                src={asset.url}
                alt={asset.name}
                selectMode={regionMode || detectionMode}
                regions={[...imageRegions, ...detectionRegions]}
                onRegionSelect={r => {
                  if (detectionMode) {
                    if (redrawId) {
                      handleUpdateDetectionBox(redrawId, r);
                      setRedrawId(null);
                    } else if (bulkMode) {
                      setStagedBoxes(prev => [...prev, r]);
                    } else {
                      handleCreateDetection(r);
                    }
                    return;
                  }
                  setPendingArea({
                    startX: toPermille(r.x), startY: toPermille(r.y),
                    width: toPermille(r.width), height: toPermille(r.height),
                  });
                  tagInputRef.current?.focus();
                }}
              />
            ) : (
              <MediaPlaceholder type={asset.type} iconSize={64} bgcolor="#000" />
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
                rangeMode={regionMode}
                onRangeSelect={(from, to) => {
                  setPendingArea({ from: Math.round(from * 1000), to: Math.round(to * 1000) });
                  tagInputRef.current?.focus();
                }}
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
                onMarkerDragEnd={(markerId) => {
                  // Persist annotation time-range edits to the backend on drag release.
                  if (!token) return;
                  const ann = annotations.find(a => a.id === markerId);
                  if (!ann) return; // comments have no time-persist endpoint wired
                  const area: AreaInfo = {
                    ...(ann.timestampStart != null ? { from: Math.round(ann.timestampStart * 1000) } : {}),
                    ...(ann.timestampEnd != null ? { to: Math.round(ann.timestampEnd * 1000) } : {}),
                  };
                  updateAnnotation(token, markerId, { area }).catch(() => {
                    showToast(tAD("annotation.editError"), "error");
                  });
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

          {/* Tags — editable (persisted via the /assets/:uuid/tags endpoint) */}
          <Box sx={{ px: 2, py: 1, bgcolor: tokens.bg.surface, display: "flex", gap: 0.5, flexWrap: "wrap", alignItems: "center", borderTop: `1px solid ${tokens.border.subtle}` }} data-testid="asset-tags">
            {assetTags.map(t => {
              const region = !!t.area && (isSpatial(t.area) || isTemporal(t.area));
              const bc = tagBreadcrumb(t.name);
              const title = region ? tAD("tag.regionTooltip") : (bc || "");
              return (
                <Tooltip key={t.uuid} title={title} placement="top" arrow>
                  <Chip
                    label={t.name}
                    size="small"
                    icon={region ? <CropFreeOutlined sx={{ fontSize: 12 }} /> : undefined}
                    onDelete={() => handleRemoveTag(t.uuid)}
                    data-testid={region ? "region-tag-chip" : "tag-chip"}
                    sx={{
                      height: 20, fontSize: "0.7rem",
                      bgcolor: region ? `${tokens.primary.main}22` : tokens.bg.elevated,
                      color: region ? tokens.primary.main : tokens.text.secondary,
                      "& .MuiChip-icon": { color: tokens.primary.main },
                    }}
                  />
                </Tooltip>
              );
            })}
            {/* Region-tag toggle: draw a box (image) or select a time range (video) for the next tag. */}
            <Tooltip title={tAD("tag.regionToggle")} placement="top" arrow>
              <IconButton
                size="small"
                onClick={() => { setRegionMode(m => !m); setPendingArea(null); }}
                data-testid="region-mode-toggle"
                sx={{ p: 0.25, color: regionMode ? tokens.primary.main : tokens.text.tertiary, bgcolor: regionMode ? `${tokens.primary.main}18` : "transparent" }}
              >
                <CropFreeOutlined sx={{ fontSize: 15 }} />
              </IconButton>
            </Tooltip>
            {regionMode && (
              <Typography variant="caption" sx={{ fontSize: "0.66rem", color: pendingArea ? tokens.accent.green : tokens.text.tertiary }}>
                {pendingArea ? tAD("tag.regionCaptured") : (isVideo ? tAD("tag.regionHintVideo") : tAD("tag.regionHintImage"))}
              </Typography>
            )}
            <TextField
              inputRef={tagInputRef}
              value={tagInput}
              onChange={e => setTagInput(e.target.value)}
              onKeyDown={e => {
                if (e.key === "Enter" && tagInput.trim()) {
                  e.preventDefault();
                  handleAddTag(tagInput);
                }
              }}
              placeholder={tAD("tag.addPlaceholder")}
              size="small"
              variant="standard"
              inputProps={{ "data-testid": "tag-input" }}
              sx={{ minWidth: 80, maxWidth: 140, "& .MuiInput-root": { fontSize: "0.75rem" }, "& .MuiInput-underline:before": { borderBottom: "none" }, "& .MuiInput-underline:hover:before": { borderBottom: `1px solid ${tokens.border.default}` } }}
            />
          </Box>

          {/* Object detections — editable bounding boxes overlaid on the central image */}
          {!isVideo && asset.url && (
            <Box sx={{ px: 2, py: 1, bgcolor: tokens.bg.surface, borderTop: `1px solid ${tokens.border.subtle}` }} data-testid="asset-detections">
              <Box sx={{ display: "flex", gap: 0.5, alignItems: "center", flexWrap: "wrap" }}>
                <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>{tAD("detection.label")}</Typography>
                <Tooltip title={tAD("detection.modeToggle")} placement="top" arrow>
                  <IconButton
                    size="small"
                    data-testid="detection-mode-toggle"
                    onClick={() => setDetectionMode(m => {
                      const next = !m;
                      if (!next) { setRedrawId(null); setBulkMode(false); setStagedBoxes([]); }
                      return next;
                    })}
                    sx={{ p: 0.25, color: detectionMode ? tokens.primary.main : tokens.text.tertiary, bgcolor: detectionMode ? `${tokens.primary.main}18` : "transparent" }}
                  >
                    <CenterFocusStrongOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                </Tooltip>
                {detectionMode && (
                  <>
                    <Tooltip title={tAD("detection.bulkToggle")} placement="top" arrow>
                      <IconButton
                        size="small"
                        data-testid="detection-bulk-toggle"
                        onClick={() => { setBulkMode(b => !b); setRedrawId(null); }}
                        sx={{ p: 0.25, color: bulkMode ? tokens.primary.main : tokens.text.tertiary, bgcolor: bulkMode ? `${tokens.primary.main}18` : "transparent" }}
                      >
                        <AddOutlined sx={{ fontSize: 15 }} />
                      </IconButton>
                    </Tooltip>
                    {stagedBoxes.length > 0 && (
                      <Button size="small" startIcon={<SaveOutlined sx={{ fontSize: 14 }} />} data-testid="detection-bulk-save" onClick={handleBulkSaveDetections} sx={{ fontSize: "0.68rem", py: 0 }}>
                        {tAD("detection.saveAll", { count: stagedBoxes.length })}
                      </Button>
                    )}
                    <Typography variant="caption" sx={{ fontSize: "0.66rem", color: redrawId ? tokens.accent.green : tokens.text.tertiary }}>
                      {redrawId ? tAD("detection.redrawHint") : bulkMode ? tAD("detection.bulkHint") : tAD("detection.hint")}
                    </Typography>
                  </>
                )}
              </Box>
              {detectionMode && (
                <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, mt: 0.75 }}>
                  {detections.length === 0 && (
                    <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>{tAD("detection.empty")}</Typography>
                  )}
                  {detections.map(d => (
                    // The confirmed state is otherwise only a background tint and an icon colour,
                    // neither of which a test can read without asserting on a theme token.
                    <Box key={d.uuid} data-testid="detection-row" data-confirmed={isConfirmed(d) ? "true" : "false"} sx={{ display: "flex", alignItems: "center", gap: 0.75, py: 0.25, px: 0.5, borderRadius: tokens.radius.sm, bgcolor: isConfirmed(d) ? `${tokens.accent.green}0d` : "transparent" }}>
                      <Typography variant="caption" sx={{ flex: 1, minWidth: 0, fontSize: "0.72rem", textTransform: "capitalize" }} noWrap>{detectionLabel(d)}</Typography>
                      <TextField
                        type="number"
                        size="small"
                        variant="standard"
                        key={`${d.uuid}-${d.confidence}`}
                        defaultValue={d.confidence}
                        inputProps={{ step: 0.05, min: 0, max: 1, "data-testid": "detection-confidence", "aria-label": tAD("detection.confidence") }}
                        onKeyDown={e => { if (e.key === "Enter") { (e.target as HTMLInputElement).blur(); } }}
                        onBlur={e => { const v = parseFloat(e.target.value); if (!isNaN(v) && v !== d.confidence) handleUpdateDetectionConfidence(d.uuid, v); }}
                        sx={{ width: 54, "& .MuiInput-root": { fontSize: "0.72rem" } }}
                      />
                      <Tooltip title={tAD("detection.redraw")} placement="top" arrow>
                        <IconButton size="small" data-testid="detection-redraw" onClick={() => { setDetectionMode(true); setBulkMode(false); setRedrawId(d.uuid); }} sx={{ p: 0.25, color: redrawId === d.uuid ? tokens.primary.main : tokens.text.tertiary }}>
                          <CropFreeOutlined sx={{ fontSize: 14 }} />
                        </IconButton>
                      </Tooltip>
                      <Tooltip title={tAD("detection.confirm")} placement="top" arrow>
                        <IconButton size="small" data-testid="detection-confirm" onClick={() => handleConfirmDetection(d.uuid)} sx={{ p: 0.25, color: isConfirmed(d) ? tokens.accent.green : tokens.text.tertiary }}>
                          <CheckOutlined sx={{ fontSize: 14 }} />
                        </IconButton>
                      </Tooltip>
                      <Tooltip title={tAD("detection.delete")} placement="top" arrow>
                        <IconButton size="small" data-testid="detection-delete" onClick={() => handleDeleteDetection(d.uuid)} sx={{ p: 0.25, color: tokens.text.tertiary, "&:hover": { color: tokens.accent.red } }}>
                          <DeleteOutlineOutlined sx={{ fontSize: 14 }} />
                        </IconButton>
                      </Tooltip>
                    </Box>
                  ))}
                </Box>
              )}
            </Box>
          )}

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

          {/* Storage location(s) — pool / path / state / license of the binary */}
          <Box sx={{ px: 2, py: 2, borderTop: `1px solid ${tokens.border.subtle}` }} data-testid="asset-locations">
            <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", display: "flex", alignItems: "center", gap: 0.5 }}>
              <StorageOutlined sx={{ fontSize: 13 }} />
              {tAD("location.title")}
            </Typography>
            {assetLocations.length === 0 ? (
              <Typography sx={{ mt: 1, color: tokens.text.tertiary, fontSize: "0.8rem" }}>
                {tAD("location.empty")}
              </Typography>
            ) : (
              <Box sx={{ mt: 1, display: "flex", flexDirection: "column", gap: 1.5 }}>
                {assetLocations.map((loc, li) => {
                  const path = loc.filesystem?.path ?? loc.s3?.objectPath;
                  const rows: [string, React.ReactNode][] = [];
                  if (loc.poolUuid) {
                    rows.push([tAD("location.pool"), (
                      <Box
                        component="span"
                        role="link"
                        tabIndex={0}
                        data-testid="asset-location-pool-link"
                        onClick={() => navigate("/asset-pools")}
                        onKeyDown={e => { if (e.key === "Enter") navigate("/asset-pools"); }}
                        sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, color: tokens.primary.main, cursor: "pointer", fontSize: "0.8rem", "&:hover": { textDecoration: "underline" } }}
                      >
                        {loc.poolUuid}
                        <Tooltip title={tAD("location.viewPool")}><LaunchOutlined sx={{ fontSize: 12 }} /></Tooltip>
                      </Box>
                    )]);
                  }
                  if (path) rows.push([tAD("location.path"), <Typography sx={{ fontSize: "0.8rem", color: tokens.text.secondary, fontFamily: "monospace", wordBreak: "break-all" }}>{path}</Typography>]);
                  if (loc.mimeType) rows.push([tAD("location.mime"), <Typography sx={{ fontSize: "0.8rem", color: tokens.text.secondary }}>{loc.mimeType}</Typography>]);
                  if (loc.state) rows.push([tAD("location.state"), <Chip label={loc.state} size="small" data-testid="asset-location-state" sx={{ height: 18, fontSize: "0.65rem", bgcolor: tokens.bg.elevated }} />]);
                  if (loc.license) rows.push([tAD("location.license"), <Typography sx={{ fontSize: "0.8rem", color: tokens.text.secondary }}>{loc.license}</Typography>]);
                  if (loc.lockedByUuid) rows.push([tAD("location.locked"), (
                    <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, color: tokens.accent.amber, fontSize: "0.8rem" }}>
                      <LockOutlined sx={{ fontSize: 12 }} />{loc.lockedByUuid}
                    </Box>
                  )]);
                  return (
                    <Box key={loc.uuid ?? li} data-testid="asset-location" sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
                      {rows.map(([k, v], idx) => (
                        <Box
                          key={k}
                          sx={{
                            display: "grid", gridTemplateColumns: "120px 1fr", px: 1.5, py: 0.85, alignItems: "center",
                            borderBottom: idx < rows.length - 1 ? `1px solid ${tokens.border.subtle}` : "none",
                            bgcolor: idx % 2 === 0 ? "transparent" : "rgba(255,255,255,0.02)",
                          }}
                        >
                          <Typography sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>{k}</Typography>
                          {v}
                        </Box>
                      ))}
                    </Box>
                  );
                })}
              </Box>
            )}
          </Box>

          {/* Transcripts — inline in content area, synced with player. One panel
              per transcript (an asset may carry several — different source/language). */}
          {transcripts.length > 0 && (
            <Box sx={{ px: 2, py: 2, borderTop: `1px solid ${tokens.border.subtle}` }}>
              {transcripts.map(tr => (
                <Box key={tr.uuid} sx={{ mb: 2, "&:last-of-type": { mb: 0 } }}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
                    <Typography variant="caption" fontWeight={600} sx={{ textTransform: "uppercase", letterSpacing: "0.07em", color: tokens.text.tertiary, fontSize: "0.68rem", flex: 1 }}>
                      {[tr.source, tr.lang].filter(Boolean).join(" · ") || tAD("transcript.untitled")}
                    </Typography>
                    <Tooltip title={tAD("transcript.delete")} placement="top" arrow>
                      <IconButton
                        size="small"
                        data-testid="transcript-delete"
                        onClick={() => handleDeleteTranscript(tr.uuid)}
                        sx={{ p: 0.25, color: tokens.text.tertiary, "&:hover": { color: tokens.accent.red } }}
                      >
                        <DeleteOutlineOutlined sx={{ fontSize: 14 }} />
                      </IconButton>
                    </Tooltip>
                  </Box>
                  <TranscriptPanel
                    sections={tr.sections}
                    currentTime={currentTime}
                    onSeek={setCurrentTime}
                    onSectionsChange={sections => handleTranscriptSectionsChange(tr.uuid, sections)}
                  />
                </Box>
              ))}
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
                {/* Composer */}
                <Box sx={{ display: "flex", alignItems: "flex-end", gap: 0.75, mb: 0.5 }}>
                  <TextField
                    fullWidth
                    multiline
                    maxRows={4}
                    size="small"
                    value={commentInput}
                    onChange={(e) => setCommentInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" && !e.shiftKey) {
                        e.preventDefault();
                        handlePostComment();
                      }
                    }}
                    placeholder={tAD("comment.addPlaceholder")}
                    disabled={!token || postingComment}
                    inputProps={{ "aria-label": tAD("comment.addPlaceholder") }}
                  />
                  <IconButton
                    aria-label={tAD("comment.post")}
                    color="primary"
                    disabled={!commentInput.trim() || !token || postingComment}
                    onClick={handlePostComment}
                  >
                    {postingComment ? <CircularProgress size={18} /> : <SendOutlined fontSize="small" />}
                  </IconButton>
                </Box>
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
                    currentUserUuid={userUuid}
                    token={token}
                    editing={editingCommentId === c.id}
                    onStartEdit={() => setEditingCommentId(c.id)}
                    onCancelEdit={() => setEditingCommentId(null)}
                    onEdit={handleEditComment}
                    onDelete={handleDeleteComment}
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
                {/* Composer */}
                <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, mb: 0.5 }} data-testid="annotation-composer">
                  <TextField
                    fullWidth
                    size="small"
                    value={annotationTitleInput}
                    onChange={(e) => setAnnotationTitleInput(e.target.value)}
                    placeholder={tAD("annotation.titlePlaceholder")}
                    disabled={!token || postingAnnotation}
                    inputProps={{ "aria-label": tAD("annotation.titlePlaceholder"), "data-testid": "annotation-new-title" }}
                  />
                  <Box sx={{ display: "flex", alignItems: "flex-end", gap: 0.75 }}>
                    <TextField
                      fullWidth
                      multiline
                      maxRows={4}
                      size="small"
                      value={annotationDescInput}
                      onChange={(e) => setAnnotationDescInput(e.target.value)}
                      placeholder={tAD("annotation.addPlaceholder")}
                      disabled={!token || postingAnnotation}
                      inputProps={{ "aria-label": tAD("annotation.addPlaceholder"), "data-testid": "annotation-new-desc" }}
                    />
                    <Tooltip title={tAD("tag.regionToggle")} placement="top" arrow>
                      <IconButton
                        size="small"
                        onClick={() => { setRegionMode(m => !m); setPendingArea(null); }}
                        data-testid="annotation-region-toggle"
                        sx={{ p: 0.5, color: regionMode ? tokens.primary.main : tokens.text.tertiary, bgcolor: regionMode ? `${tokens.primary.main}18` : "transparent" }}
                      >
                        <CropFreeOutlined sx={{ fontSize: 18 }} />
                      </IconButton>
                    </Tooltip>
                    <IconButton
                      aria-label={tAD("annotation.add")}
                      color="primary"
                      disabled={!annotationTitleInput.trim() || !token || postingAnnotation}
                      onClick={handleCreateAnnotation}
                      data-testid="annotation-post"
                    >
                      {postingAnnotation ? <CircularProgress size={18} /> : <SendOutlined fontSize="small" />}
                    </IconButton>
                  </Box>
                  {regionMode && (
                    <Typography variant="caption" sx={{ fontSize: "0.66rem", color: pendingArea ? tokens.accent.green : tokens.text.tertiary }}>
                      {pendingArea ? tAD("tag.regionCaptured") : (isVideo ? tAD("tag.regionHintVideo") : tAD("tag.regionHintImage"))}
                    </Typography>
                  )}
                </Box>
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
                    token={token}
                    currentUserUuid={userUuid}
                    editing={editingAnnotationId === a.id}
                    onStartEdit={() => setEditingAnnotationId(a.id)}
                    onCancelEdit={() => setEditingAnnotationId(null)}
                    onEdit={handleEditAnnotation}
                    onDelete={handleDeleteAnnotation}
                  />
                ))}
              </Box>
              );
            })()}

            {/* Reactions tab */}
            {tab === 3 && (
              <ReactionsPanel
                reactions={reactions}
                currentUserUuid={userUuid}
                onAdd={handleAddReaction}
                onDelete={handleDeleteReaction}
                testIdPrefix="assets"
              />
            )}

            {/* Tasks tab */}
            {tab === 4 && (
              <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
                {tasks.length === 0 ? (
                  <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", py: 4, gap: 1 }}>
                    <TaskAltOutlined sx={{ fontSize: 32, color: tokens.text.tertiary }} />
                    <Typography variant="body2" color="text.secondary">{tAD("empty.noTasks")}</Typography>
                  </Box>
                ) : tasks.map(t => <TaskItem key={t.uuid} task={t} onClick={() => setSelectedTask(t)} />)}
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
            data-testid="asset-task-drawer"
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
              {(() => {
                const prio = selectedTask.priority?.toUpperCase() ?? "MEDIUM";
                const pc = taskPriorityColor[prio] ?? tokens.text.tertiary;
                const status = selectedTask.taskStatus?.toUpperCase();
                const sc = (status && taskStatusColor[status]) || tokens.text.tertiary;
                const rows: [string, React.ReactNode][] = [
                  [tAD("taskDetail.status"), status
                    ? <Chip data-testid="asset-task-drawer-status-chip" label={tCommon(`tasks.status.${status}`, status)} size="small" sx={{ height: 18, fontSize: "0.7rem", bgcolor: `${sc}22`, color: sc }} />
                    : <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>—</Typography>],
                  [tAD("taskDetail.priority"), <Chip data-testid="asset-task-drawer-priority-chip" label={tCommon(`tasks.priority.${prio}`, prio)} size="small" sx={{ height: 18, fontSize: "0.7rem", bgcolor: `${pc}22`, color: pc, fontWeight: 700 }} />],
                  [tAD("taskDetail.dueDate"), <Typography data-testid="asset-task-drawer-due-date" sx={{ fontSize: "0.82rem", color: selectedTask.dueDate && new Date(selectedTask.dueDate) < new Date() ? tokens.accent.red : tokens.text.secondary }}>{selectedTask.dueDate ? new Date(selectedTask.dueDate).toLocaleDateString() : "—"}</Typography>],
                  [tAD("taskDetail.created"), <Typography sx={{ fontSize: "0.82rem", color: tokens.text.secondary }}>{selectedTask.status?.created ? new Date(selectedTask.status.created).toLocaleDateString() : "—"}</Typography>],
                ];
                return (
                  <>
                    <Box>
                      <Box sx={{ display: "flex", gap: 1, alignItems: "flex-start", mb: 1 }}>
                        <Box sx={{ width: 4, height: 20, borderRadius: 2, bgcolor: pc, mt: 0.3, flexShrink: 0 }} />
                        <Typography variant="h6" fontWeight={700} sx={{ fontSize: "1rem", lineHeight: 1.3 }}>{selectedTask.title}</Typography>
                      </Box>
                      {selectedTask.description && <Typography variant="body2" sx={{ color: tokens.text.secondary, lineHeight: 1.6 }}>{selectedTask.description}</Typography>}
                    </Box>
                    {/* Meta grid */}
                    <Box sx={{ border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.md, overflow: "hidden" }}>
                      {rows.map(([label, content], idx) => (
                        <Box key={String(label)} sx={{ display: "grid", gridTemplateColumns: "100px 1fr", px: 1.5, py: 0.85, borderBottom: idx < rows.length - 1 ? `1px solid ${tokens.border.subtle}` : "none", bgcolor: idx % 2 === 0 ? "transparent" : "rgba(255,255,255,0.02)", alignItems: "center" }}>
                          <Typography sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>{label}</Typography>
                          {content}
                        </Box>
                      ))}
                    </Box>
                  </>
                );
              })()}
            </Box>
          </Box>
        </Box>
      )}

      {/* Create task dialog */}
      <Dialog open={taskCreateOpen} onClose={() => setTaskCreateOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 380 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{tAD("taskCreate.title")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 1.5, pt: "8px !important" }}>
          <TextField
            value={taskTitle}
            onChange={e => setTaskTitle(e.target.value)}
            label={tCommon("tasks.form.title")}
            size="small"
            fullWidth
            autoFocus
            inputProps={{ "data-testid": "asset-task-create-title-input" }}
          />
          <TextField
            value={taskDescription}
            onChange={e => setTaskDescription(e.target.value)}
            label={tCommon("tasks.form.description")}
            size="small"
            fullWidth
            multiline
            minRows={2}
            inputProps={{ "data-testid": "asset-task-create-description-input" }}
          />
          <TextField
            value={taskPriority}
            onChange={e => setTaskPriority(e.target.value)}
            label={tCommon("tasks.form.priority")}
            size="small"
            select
            SelectProps={{ native: true }}
            inputProps={{ "data-testid": "asset-task-create-priority-select" }}
          >
            {["LOW", "MEDIUM", "HIGH", "CRITICAL"].map(p => (
              <option key={p} value={p}>{tCommon(`tasks.priority.${p}`)}</option>
            ))}
          </TextField>
          <TextField
            value={taskDueDate}
            onChange={e => setTaskDueDate(e.target.value)}
            label={tAD("taskCreate.dueDate")}
            size="small"
            type="date"
            InputLabelProps={{ shrink: true }}
            inputProps={{ "data-testid": "asset-task-create-due-date-input" }}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setTaskCreateOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{tCommon("tasks.button.cancel")}</Button>
          <Button
            data-testid="asset-task-create-submit-button"
            onClick={handleCreateTask}
            size="small"
            variant="contained"
            disabled={!taskTitle.trim() || creatingTask}
            startIcon={creatingTask ? <CircularProgress size={13} /> : undefined}
          >
            {tCommon("tasks.button.create")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete confirmation */}
      <Dialog open={deleteOpen} onClose={() => setDeleteOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 340 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{tAD("dialog.delete")}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">{tAD("confirm.delete", { name: asset.name })}</Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{tCommon("assets.button.cancel")}</Button>
          <Button onClick={handleDeleteAsset} size="small" variant="contained" sx={{ bgcolor: tokens.accent.red, "&:hover": { bgcolor: tokens.accent.red } }}>{tCommon("assets.button.delete")}</Button>
        </DialogActions>
      </Dialog>

      {/* Add transcript */}
      <Dialog open={transcriptAddOpen} onClose={() => setTranscriptAddOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 380 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{tAD("transcriptCreate.title")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 1.5, pt: "8px !important" }}>
          <TextField
            value={transcriptSource}
            onChange={e => setTranscriptSource(e.target.value)}
            label={tAD("transcriptCreate.source")}
            size="small"
            fullWidth
            autoFocus
            inputProps={{ "data-testid": "transcript-create-source-input" }}
          />
          <TextField
            value={transcriptLang}
            onChange={e => setTranscriptLang(e.target.value)}
            label={tAD("transcriptCreate.lang")}
            size="small"
            fullWidth
            inputProps={{ "data-testid": "transcript-create-lang-input" }}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setTranscriptAddOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{tCommon("assets.button.cancel")}</Button>
          <Button
            data-testid="transcript-create-submit-button"
            onClick={handleAddTranscript}
            size="small"
            variant="contained"
            disabled={creatingTranscript}
            startIcon={creatingTranscript ? <CircularProgress size={13} /> : undefined}
          >
            {tCommon("tasks.button.create")}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Register existing binary */}
      <Dialog open={registerBinaryOpen} onClose={() => setRegisterBinaryOpen(false)} PaperProps={{ sx: { bgcolor: tokens.bg.panel, border: `1px solid ${tokens.border.default}`, minWidth: 420 } }}>
        <DialogTitle sx={{ fontSize: "1rem", fontWeight: 700, pb: 1 }}>{tAD("registerBinary.title")}</DialogTitle>
        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 1.5, pt: "8px !important" }}>
          <Typography variant="body2" color="text.secondary">{tAD("registerBinary.description")}</Typography>
          <TextField
            value={registerBinaryPath}
            onChange={e => setRegisterBinaryPath(e.target.value)}
            label={tAD("registerBinary.pathLabel")}
            placeholder={tAD("registerBinary.pathPlaceholder")}
            size="small"
            fullWidth
            autoFocus
            inputProps={{ "data-testid": "asset-register-binary-path-input" }}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setRegisterBinaryOpen(false)} size="small" sx={{ color: tokens.text.secondary }}>{tCommon("assets.button.cancel")}</Button>
          <Button
            data-testid="asset-register-binary-submit-button"
            onClick={handleRegisterBinary}
            size="small"
            variant="contained"
            disabled={!registerBinaryPath.trim() || binaryBusy}
            startIcon={binaryBusy ? <CircularProgress size={13} /> : undefined}
          >
            {tAD("registerBinary.submit")}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
