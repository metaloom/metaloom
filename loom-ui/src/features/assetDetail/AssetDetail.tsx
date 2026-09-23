import React, { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import ShareDialog from "../share/ShareDialog";
import AddToRemixDialog from "../remix/AddToRemixDialog";
import { listAssetRemixes, type RemixResponse } from "../../api/remixes";
import { useParams, useNavigate, useSearchParams } from "react-router-dom";
import {
  Box, Typography, Chip, IconButton, Tab, Tabs, Autocomplete,
  Tooltip, LinearProgress, TextField, InputAdornment,
  Menu, MenuItem, ListItemIcon, ListItemText,
  Button, CircularProgress,
  Dialog, DialogTitle, DialogContent, DialogActions,
} from "@mui/material";
import {
  ArrowBack,
  ChatBubbleOutlineOutlined, BookmarkBorderOutlined,
  ThumbUpAltOutlined, TaskAltOutlined, AccountTreeOutlined,
  FaceOutlined, SearchOutlined,
  MoreVertOutlined, SendOutlined, AddTaskOutlined,
  CollectionsOutlined, CropFreeOutlined, SaveOutlined, DeleteOutlineOutlined, LayersOutlined,
  DownloadOutlined, UploadFileOutlined, LinkOutlined,
  StorageOutlined, LockOutlined, LaunchOutlined,
  CenterFocusStrongOutlined, CheckOutlined, AddOutlined, ShareOutlined,
  NotesOutlined, InfoOutlined, RecordVoiceOverOutlined, DragHandleOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Asset, AssetType, AssetStatus, Comment, Annotation, TranscriptSection, DetectedFace, FaceCluster, Person } from "../../types";
import { useAuth } from "../../context/AuthContext";
import { useToast } from "../../context/ToastContext";
import { loadAsset as apiLoadAsset, updateAsset, deleteAsset, AssetResponse, TagReference, AssetLocationInfo } from "../../api/assets";
import { useMediaInfo } from "../../hooks/useMediaInfo";
import { AssetVideoPlayer, AssetVideoPlayerHandle } from "../../components/AssetVideoPlayer";
import { FaceBoxes, FACE_FLASH_MS, visibleFacesAt } from "../../components/FaceBoxes";
import { useFaceFlash } from "../../hooks/useFaceFlash";
import { uploadAssetBinary, downloadAssetBinary, deleteAssetBinary, createAssetBinaryMeta } from "../../api/binaries";
import MediaPlaceholder from "../../components/MediaPlaceholder";
import { listPipelines, runPipeline, PipelineResponse } from "../../api/pipelines";
import { tagAsset as apiTagAsset, untagAsset as apiUntagAsset, updateTagPlacement, loadTagVocabulary, DEFAULT_TAG_COLLECTION } from "../../api/tags";
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
import { asrSegmentsToSections } from "./transcriptMapping";
import { VideoTimeline, TimelineMarker, TranscriptSpan } from "./VideoTimeline";
import { ZoomableImage } from "./ZoomableImage";
import { CommentItem } from "./CommentItem";
import { AnnotationItem } from "./AnnotationItem";
import { ReactionsPanel } from "../reactions/ReactionsPanel";
import { TaskItem, taskPriorityColor, taskStatusColor } from "./TaskItem";
import { TranscriptPanel, TRANSCRIPT_SECTION_COLORS } from "./TranscriptPanel";
import { TranscriptSearchPanel } from "./TranscriptSearchPanel";
import { FaceDetectionPanel } from "./FaceDetectionPanel";
import { PAGE_SIZE } from "../../hooks/pagedList";
import CollapsibleSection from "../../components/CollapsibleSection";
import { useSectionState } from "../../hooks/useSectionState";
import { useAuthedImage } from "../../hooks/useAuthedImage";
import { movedArea } from "./regionTag";


/**
 * Below this many pixels the tab strip drops its labels and keeps the icons.
 *
 * 380, and the number is bounded on both sides. At the 300 it was first set to, the labels
 * survived through the whole range anybody would call narrow and only vanished once the sidebar
 * was a sliver — which is not when you want them to. At 420 they were gone at the *default*
 * split on a 1600px window (30% of the body is about 414px there), so a screen nobody had
 * dragged opened in icon mode. 380 keeps the words at every default width and drops them as soon
 * as the divider is pulled in.
 *
 * It is not a hard floor on the sidebar: the strip is `scrollable`, so labels never block a
 * drag — this is about what is worth reading in a column this thin.
 */
const SIDEBAR_ICON_ONLY_PX = 380;

/**
 * How wide one tab is once the labels are gone, and the padding around the strip.
 *
 * These are the `minWidth` and `px` the icon-only `<Tab>` below is given, repeated here because
 * the divider has to know the answer *before* the strip is rendered at the new width. Together
 * they are the floor a drag may not cross: below it the last tab is scrolled out of the strip and
 * the panel behind it becomes unreachable, which is what "the tab icons vanish" was.
 */
const TAB_ICON_ONLY_PX = 40;
const TAB_STRIP_PADDING_PX = 16;

/**
 * Slack between "the labels fit" and "show the labels".
 *
 * Without it the strip flips back to words at the exact width they need and clips again the
 * moment a comment count gains a digit. The measurement decides the threshold; this keeps the
 * decision from sitting on the boundary.
 */
const TAB_LABEL_SLACK_PX = 12;

/** Where the fold state of the left column's sections is remembered. */
const SECTION_STATE_KEY = "loom.assetDetail.sections";

/**
 * How tall the media slot is, in pixels, and where the drag handle may take it.
 *
 * A pixel height rather than a fraction of the pane: the thing being sized is a picture, and how
 * much of one you want on screen does not scale with how long the page below it happens to be.
 */
const MEDIA_HEIGHT_KEY = "loom.assetDetail.mediaHeight";
const MEDIA_MIN_PX = 140;
const MEDIA_MAX_PX = 1200;
const MEDIA_DEFAULT_PX = 380;

function readStoredMediaHeight(): number {
  try {
    const raw = window.localStorage.getItem(MEDIA_HEIGHT_KEY);
    const value = raw == null ? NaN : Number.parseInt(raw, 10);
    if (!Number.isFinite(value)) return MEDIA_DEFAULT_PX;
    return Math.min(MEDIA_MAX_PX, Math.max(MEDIA_MIN_PX, value));
  } catch {
    return MEDIA_DEFAULT_PX;
  }
}

/**
 * Which sections start open for somebody who has never folded one.
 *
 * Only the raw metadata table starts shut, and only because it is the longest block of the least
 * situational interest — size, mime, owner, created. Everything else opens, which keeps the
 * default view of an asset the same set of information it always showed; the fold is a control
 * the reviewer reaches for, not a decision made on their behalf.
 */
const SECTION_DEFAULTS: Record<string, boolean> = {
  description: true,
  detections: true,
  transcript: true,
  locations: true,
  metadata: false,
};

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
  const authored = (tr.transcriptJson?.sections ?? []).map(s => ({
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
  }));
  return {
    uuid: tr.uuid,
    source: tr.source,
    lang: tr.lang,
    // Authored sections win where both are present: somebody edited those on purpose.
    sections: authored.length > 0 ? authored : asrSegmentsToSections(tr.transcriptJson?.segments),
  };
}

// ── Main Asset Detail ─────────────────────────────────────────────────────
export default function AssetDetail() {
  const { t: tAD } = useTranslation("translation", { keyPrefix: "assetDetail" });
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  /** `?t=<seconds>`: where a transcript hit or a shared link wants the player to open. */
  const deepLinkSeconds = Number.parseFloat(searchParams.get("t") ?? "");
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
  // What the <video> element reports once it has its metadata. Preferred over the asset's own
  // duration, which is a seeded or probed number and can disagree with the file by a frame.
  const [highlightedId, setHighlightedId] = useState<string | null>(null);
  const [hoveredMarkerId, setHoveredMarkerId] = useState<string | null>(null);
  const [selectedTask, setSelectedTask] = useState<TaskResponse | null>(null);
  // Create-task dialog (creates a task and assigns it to this asset)
  const [taskCreateOpen, setTaskCreateOpen] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  /** The remixes this asset takes part in - rendered as chips beside the collection chips. */
  const [assetRemixes, setAssetRemixes] = useState<RemixResponse[]>([]);
  const [addToRemixOpen, setAddToRemixOpen] = useState(false);
  const [taskTitle, setTaskTitle] = useState("");
  const [taskDescription, setTaskDescription] = useState("");
  const [taskPriority, setTaskPriority] = useState("MEDIUM");
  const [taskDueDate, setTaskDueDate] = useState("");
  const [creatingTask, setCreatingTask] = useState(false);
  const [tagInput, setTagInput] = useState("");
  /**
   * Existing tag names, for the suggestion list.
   *
   * Loaded once per view rather than queried per keystroke: `listTags` has no search parameter, so
   * a per-keystroke call would fetch the same page each time and filter it client-side anyway. The
   * input stays free-form, so a new word is still one Enter away - the list exists to stop the
   * same idea being coined three times as "interview", "Interview" and "interviews".
   */
  const [tagVocabulary, setTagVocabulary] = useState<string[]>([]);
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
  const { t: tRemix } = useTranslation("translation", { keyPrefix: "remix" });
  const [editName, setEditName] = useState("");
  const [saving, setSaving] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [pipelines, setPipelines] = useState<PipelineResponse[]>([]);
  /**
   * Where the split between media and sidebar sits, as a percentage of the body.
   *
   * 70 rather than 60: the sidebar is a column of short rows — comments, tags, faces — and at 40%
   * of a wide window it was mostly whitespace while the video it discusses was cramped. The upper
   * clamp went to 88 for the same reason; a sidebar can be pushed down to a strip of icons now
   * that {@link SIDEBAR_ICON_ONLY_PX} drops the tab labels, and the labels used to be the floor.
   */
  const [leftPct, setLeftPct] = useState(70);
  const isDragging = useRef(false);
  const containerRef = useRef<HTMLDivElement>(null);
  /**
   * How tall the media slot is.
   *
   * The vertical twin of {@link leftPct}: the sidebar had a handle and the player had a hard cap,
   * so the only way to see more of a video was to widen the pane, which does nothing once the
   * picture is already as wide as the column. Remembered, because it is a preference about how
   * you review rather than about this asset.
   */
  const [mediaPx, setMediaPx] = useState(readStoredMediaHeight);
  const mediaColumnRef = useRef<HTMLDivElement>(null);
  const draggingMedia = useRef(false);
  /**
   * The sidebar's measured width, so the tab strip can drop its labels.
   *
   * Measured rather than derived from `leftPct`: the percentage says nothing about how wide the
   * window is, and the question the tab strip is asking is whether six labels fit in the pixels
   * it actually has.
   */
  const [sidebarPx, setSidebarPx] = useState(0);
  /**
   * The measured verdict on the tab labels; null until the strip has been looked at.
   *
   * Separate from the width so the guess based on {@link SIDEBAR_ICON_ONLY_PX} can render first
   * and be replaced rather than fought with.
   */
  const [labelsHiddenState, setLabelsHidden] = useState<boolean | null>(null);
  /** What the strip needed when the labels were last on screen. Zero means "never seen". */
  const neededLabelPx = useRef(0);
  const tabStripRef = useRef<HTMLDivElement | null>(null);
  /** How many tabs there are, for the divider's floor. Set during render, read during a drag. */
  const tabCountRef = useRef(0);
  const sidebarObserver = useRef<ResizeObserver | null>(null);
  const sidebarRef = useCallback((node: HTMLDivElement | null) => {
    sidebarObserver.current?.disconnect();
    sidebarObserver.current = null;
    if (!node || typeof ResizeObserver === "undefined") return;
    // Watch the element, not the window: the divider changes this width without the window
    // changing at all, so a resize listener would miss every drag.
    const observer = new ResizeObserver(entries => {
      const width = entries[0]?.contentRect.width;
      if (width != null) setSidebarPx(width);
    });
    observer.observe(node);
    sidebarObserver.current = observer;
    setSidebarPx(node.getBoundingClientRect().width);
  }, []);

  /**
   * Decide whether the tab labels fit, by looking at whether they did.
   *
   * The strip is `variant="scrollable"`, so when the words do not fit it does not wrap or
   * ellipsise — it scrolls, and the tabs past the edge are simply not there. That is the
   * "initially the labels are also clipped" case: a width just above the constant showed six
   * labels of which two were off the end.
   *
   * So the constant is only the opening guess. While the labels are up, `scrollWidth` says what
   * the strip would need; if that is more than it has, the labels go. Coming back needs the
   * remembered requirement plus a little slack, which is what stops the two states flip-flopping
   * at the width where they meet.
   */
  useEffect(() => {
    const scroller = tabStripRef.current?.querySelector<HTMLElement>(".MuiTabs-scroller");
    if (!scroller || sidebarPx <= 0) return;
    if (!labelsHiddenState) {
      // Rounded up: a sub-pixel scroller width reads as "one pixel short" forever otherwise.
      const needed = Math.ceil(scroller.scrollWidth);
      if (needed > 0) neededLabelPx.current = needed;
      if (needed > Math.ceil(scroller.clientWidth) + 1) setLabelsHidden(true);
    } else if (neededLabelPx.current > 0 && sidebarPx >= neededLabelPx.current + TAB_LABEL_SLACK_PX) {
      setLabelsHidden(false);
    }
  }, [sidebarPx, labelsHiddenState, comments.length, annotations.length, reactions.length, tasks.length, detectedFaces.length]);

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
      // Remix membership is its own route: the asset response has no remixes field, and adding one
      // would make every asset read pay for a join most callers do not want.
      listAssetRemixes(token, id)
        .then(r => setAssetRemixes(r.data ?? []))
        // 403 for a caller without READ_REMIX. The chips just do not appear.
        .catch(() => setAssetRemixes([]));
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
      // 94 was the whole story and it was not enough: on a wide window 6% is still 90 pixels,
      // and a strip of six icons needs 256. Past that the last tabs scroll out of the strip and
      // the panels behind them cannot be reached at all. So the ceiling is whichever is tighter
      // — the flat 94%, or the percentage that still leaves the icons room to stand in.
      const floorPx = tabCountRef.current * TAB_ICON_ONLY_PX + TAB_STRIP_PADDING_PX;
      const iconCeiling = rect.width > 0 ? 100 - (floorPx / rect.width) * 100 : 94;
      setLeftPct(Math.min(Math.max(pct, 30), Math.min(94, iconCeiling)));
    };
    const onUp = () => { isDragging.current = false; window.removeEventListener("mousemove", onMove); window.removeEventListener("mouseup", onUp); };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, []);

  useEffect(() => {
    try { window.localStorage.setItem(MEDIA_HEIGHT_KEY, String(Math.round(mediaPx))); } catch { /* private mode */ }
  }, [mediaPx]);

  /**
   * Drag the media slot taller or shorter.
   *
   * Clamped against the column it lives in rather than only against a constant: leaving less
   * than a couple of hundred pixels for the timeline and the sections below turns the handle
   * into a way to hide the rest of the screen.
   */
  const handleMediaResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    draggingMedia.current = true;
    const startY = e.clientY;
    const startPx = mediaPx;
    const columnHeight = mediaColumnRef.current?.getBoundingClientRect().height ?? 0;
    const ceiling = columnHeight > 0 ? Math.max(MEDIA_MIN_PX, columnHeight - 200) : MEDIA_MAX_PX;
    const onMove = (ev: MouseEvent) => {
      if (!draggingMedia.current) return;
      setMediaPx(Math.min(ceiling, Math.max(MEDIA_MIN_PX, startPx + (ev.clientY - startY))));
    };
    const onUp = () => {
      draggingMedia.current = false;
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, [mediaPx]);

  /** Which of the left column's sections are open. See {@link useSectionState}. */
  const { isExpanded: sectionExpanded, toggle: toggleSection, expand: expandSection } = useSectionState(SECTION_STATE_KEY, SECTION_DEFAULTS);
  /**
   * The moment a chapter tile or a search hit asked the transcript to show.
   *
   * Seeking is only half of what those clicks mean: the chapter they point at may be several
   * screens down the section scroller, or behind a fold that is shut, and a panel that quietly
   * repainted a highlight nobody can see reads as a click that did nothing. The nonce makes the
   * same tile clicked twice scroll twice.
   */
  const [transcriptReveal, setTranscriptReveal] = useState<{ time: number; nonce: number } | null>(null);
  const revealNonce = useRef(0);

  /**
   * The player, which owns the stream offset and the transport.
   *
   * Seeking a remuxed stream means re-requesting it at a different offset — a pipe has no index —
   * and that bookkeeping lives in {@link AssetVideoPlayer} rather than here, because the workflow
   * review queue needs exactly the same behaviour and had none of it.
   */
  const playerRef = useRef<AssetVideoPlayerHandle>(null);

  /**
   * What the decoder says about this file, above all how long it is.
   *
   * Nothing else knows. `asset_video_comp` has no producer, so `asset.duration` is empty for every
   * ingested video, and the element cannot answer either: it is being fed a pipe and reports only
   * what has arrived. Without this the timeline was a few seconds wide and there was nowhere to
   * click to reach the middle of an episode.
   */
  const mediaInfo = useMediaInfo(asset?.type === "video" ? asset?.id : null);

  // Above the early return below: every hook in this component has to run on every render, and
  // the natural home for this - beside reloadTags - is past it.
  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    loadTagVocabulary(token)
      .then(names => { if (!cancelled) setTagVocabulary(names); })
      .catch(() => { /* suggestions are an aid; typing still works without them */ });
    return () => { cancelled = true; };
  }, [token]);

  /**
   * Honour `?t=` once, when the player can actually act on it.
   *
   * Deliberately gated on the probe rather than run on mount: the stream is a remux and a seek
   * before the duration is known is a request for an offset into a file of unknown length. Keyed
   * on the asset so navigating from one transcript hit to another in a different episode seeks
   * again, and guarded by a ref so a re-render does not drag the viewer back to the link's
   * timestamp after they have scrubbed away from it.
   */
  const deepLinkApplied = useRef<string | null>(null);
  useEffect(() => {
    if (!id || !Number.isFinite(deepLinkSeconds) || deepLinkSeconds <= 0) return;
    if (deepLinkApplied.current === id) return;
    if (!(mediaInfo?.duration ?? 0)) return;
    deepLinkApplied.current = id;
    setCurrentTime(deepLinkSeconds);
    playerRef.current?.seekTo(deepLinkSeconds);
  }, [id, deepLinkSeconds, mediaInfo?.duration]);

  /**
   * The picture for an image asset, fetched with the auth header.
   *
   * `asset.url` points at `/assets/:uuid/binary/data`, which an `<img>` cannot authenticate
   * against — so every image asset rendered as a broken-image glyph. See {@link useAuthedImage}.
   * Beyond the obvious, this is load-bearing for the region overlays: `ZoomableImage` reads the
   * picture's intrinsic size off the element, and an image that never loads leaves it guessing.
   */
  const imageUrl = useAuthedImage(asset?.type !== "video" && asset?.url ? asset.id : null);

  /** The bounding box to light up, and when. See {@link useFaceFlash}. */
  const { flash: faceFlash, armFlash } = useFaceFlash();
  /** The face a click jumped to, kept on screen even once the playhead has drifted off it. */
  const [pinnedFaceId, setPinnedFaceId] = useState<string | null>(null);


  // Seek the player. Everything that puts a time on the timeline — a marker, a transcript line, a
  // detection — goes through here, so the picture follows the click rather than only the playhead.
  const seekTo = useCallback((time: number) => {
    if (!Number.isFinite(time)) {
      return;
    }
    setCurrentTime(time);
    playerRef.current?.seekTo(time);
  }, []);

  /**
   * Seek to a moment *and* take the reader to what was said there.
   *
   * What a chapter tile in the timeline and a transcript search hit both mean. Three steps, and
   * skipping any one of them was the complaint: the fold has to open, because the transcript is
   * shut by default on a screen this tall; the panel has to scroll, because the chapter is not on
   * screen; and the playhead has to move, because the running highlight in the transcript is
   * driven by `currentTime` and is what keeps following along once playback resumes.
   */
  const revealTranscriptAt = useCallback((time: number) => {
    if (!Number.isFinite(time)) return;
    seekTo(time);
    expandSection("transcript");
    revealNonce.current += 1;
    setTranscriptReveal({ time, nonce: revealNonce.current });
  }, [seekTo, expandSection]);

  /**
   * Where a face detection sits in the video, in seconds.
   *
   * `DetectedFace.timestamp` carries the detection's `frame_number` — the field is misnamed and
   * the column is what it is — so this needs a frame rate, and the probe is the only place one
   * comes from: `asset_video_comp` has no producer. Null means "cannot be placed in time", which
   * every caller treats as "not clickable" rather than as second zero. Clicking a face used to
   * seek to its frame *number* read as seconds, which put frame 24000 two thirds of a day in.
   */
  const faceTimeOf = useCallback((face: DetectedFace): number | null => {
    const fps = mediaInfo?.frameRate ?? 0;
    if (asset?.type !== "video" || fps <= 0 || face.timestamp == null) return null;
    return face.timestamp / fps;
  }, [asset?.type, mediaInfo?.frameRate]);

  /**
   * Jump to a face and light its box up as we arrive.
   *
   * The lead-in is deliberate: landing on the detection's own frame puts the moment in the past
   * before the picture has settled. See {@link FACE_FLASH_MS}.
   */
  const seekToFace = useCallback((face: DetectedFace) => {
    const at = faceTimeOf(face);
    if (at == null) return;
    setPinnedFaceId(face.id);
    seekTo(Math.max(0, at - FACE_FLASH_MS / 1000));
    armFlash(face.id);
  }, [faceTimeOf, seekTo, armFlash]);

  if (!asset) {
    return (
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100%", bgcolor: tokens.bg.base }}>
        <LinearProgress sx={{ width: 200 }} />
      </Box>
    );
  }

  const isVideo = asset.type === "video";
  // Where a video's length comes from: the component row if a node ever wrote one, otherwise the
  // ffprobe measurement. The media element is deliberately not a third fallback - for a remuxed
  // stream it describes the pipe rather than the clip, which is what made a 43-minute episode's
  // timeline read "0:05".
  const duration = asset.duration || mediaInfo?.duration || 0;

  /** Whether the transcript fold is open — the timeline tiles follow it. */
  const transcriptOpen = sectionExpanded("transcript");

  /**
   * The transcript's chapters as timeline tiles.
   *
   * Flattened across every transcript on the asset, because the bar is one bar: two transcripts
   * of the same audio in different languages describe the same moments, and drawing each in its
   * own lane would say they were different scenes. The colour cycles through the same palette
   * the panel below uses, so a tile and its section are recognisably the same thing.
   */
  const transcriptSpans: TranscriptSpan[] = transcripts.flatMap(tr =>
    tr.sections
      .filter(section => section.endTime > section.startTime)
      .map((section, idx) => ({
        id: `${tr.uuid}:${section.id}`,
        from: section.startTime,
        to: section.endTime,
        label: section.title || formatDuration(Math.round(section.startTime)),
        color: TRANSCRIPT_SECTION_COLORS[idx % TRANSCRIPT_SECTION_COLORS.length],
      })));

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

  // The divider reads this at drag time, so it has to be the count from the last render rather
  // than a value captured when the handler was created.
  tabCountRef.current = tabs.length;

  /**
   * Whether the tab strip is down to icons — decided by measurement, not only by a constant.
   *
   * {@link SIDEBAR_ICON_ONLY_PX} is the first guess, and it is only ever a guess: how much room
   * six labels need depends on the language, on the font, and on whether a count is one digit or
   * three. The effect below corrects it from what the strip actually did, which is the only
   * thing that knows. The remembered width is captured while the labels are *on screen*, so the
   * way back is a comparison against a real measurement rather than against the same guess.
   */
  const labelsHidden = labelsHiddenState ?? (sidebarPx > 0 && sidebarPx < SIDEBAR_ICON_ONLY_PX);

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
            // The filename is an editable field rather than a heading, so nothing can address it by
            // text. Named, because it is the one thing on this screen that identifies the asset.
            inputProps={{ "data-testid": "asset-name", "aria-label": "Asset filename" }}
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
            {/* Remix chips. Clicking one goes back to the asset browser with that remix open -
                the same ?remix= deep link the grid writes, so there is one way in. */}
            {assetRemixes.map(r => (
              <Chip
                key={r.uuid}
                data-testid="asset-remix-chip"
                icon={<LayersOutlined sx={{ fontSize: 10 }} />}
                label={r.name}
                size="small"
                clickable
                onClick={() => navigate(`/assets?remix=${encodeURIComponent(r.uuid)}`)}
                sx={{ height: 16, fontSize: "0.65rem", bgcolor: `${tokens.primary.main}18`, color: tokens.primary.light, "& .MuiChip-icon": { color: tokens.primary.light } }}
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
          <MenuItem data-testid="asset-share-menu-item" onClick={() => { setActionMenuAnchor(null); setShareOpen(true); }}>
            <ListItemIcon><ShareOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tCommon("share.action.share")}</ListItemText>
          </MenuItem>
          <MenuItem data-testid="asset-task-create-menu-item" onClick={() => { setActionMenuAnchor(null); setTaskCreateOpen(true); }}>
            <ListItemIcon><AddTaskOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tAD("action.createTask")}</ListItemText>
          </MenuItem>
          <MenuItem data-testid="asset-add-to-remix-menu-item" onClick={() => { setActionMenuAnchor(null); setAddToRemixOpen(true); }}>
            <ListItemIcon><LayersOutlined sx={{ fontSize: 16 }} /></ListItemIcon>
            <ListItemText primaryTypographyProps={{ fontSize: "0.85rem" }}>{tRemix("action.addTo")}</ListItemText>
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
        <Box ref={mediaColumnRef} sx={{ flex: "0 0 auto", width: { xs: "100%", lg: `${leftPct}%` }, display: "flex", flexDirection: "column", overflow: "hidden" }}>
          {/* Media area */}
          {/* `flexShrink: 0`, or the column below squashes this to nothing.
              The media area is a flex item in a column whose other children — timeline, tags,
              description, metadata, transcript — routinely overrun the pane. With the default
              `flex: 0 1 auto` the shrink came out of the picture first: the player computed a
              correct 966x380 and was then handed a 0px slot, so it overflowed upward out of an
              `overflow: hidden` parent and the page showed a timeline with no video above it.
              The old `aspectRatio` here hid the same bug behind a definite basis. */}
          <Box data-testid="asset-media-area" data-media-height={Math.round(mediaPx)}
            sx={{ position: "relative", flexShrink: 0, bgcolor: "#000", height: mediaPx, overflow: "hidden", display: "flex", alignItems: "center", justifyContent: "center" }}>
            {isVideo ? (
              // The remuxed stream, not the stored binary: no browser decodes the Matroska files
              // this deployment holds, whatever range support /assets/:uuid/binary/data offers.
              // Native controls are deliberately absent - see AssetVideoPlayer, which owns the
              // transport because a piped fragmented MP4 gives the native bar nothing to scrub.
              <AssetVideoPlayer
                ref={playerRef}
                assetUuid={asset.id}
                duration={duration}
                onTimeUpdate={setCurrentTime}
                // The player sizes itself: 16:9 capped at the same height the slot is capped at, so
                // the aspect ratio yields to the cap rather than overflowing it. Handing it
                // `height: 100%` against an aspect-ratio'd parent is what collapsed the picture.
                sx={{ width: "100%", height: "100%" }}
                // Only the faces belonging to the moment on screen, plus whichever one was
                // clicked. Drawing all of them at once is every detection in a 43-minute episode
                // stacked on one frame, which is not a picture of anything.
                overlay={<FaceBoxes
                  faces={visibleFacesAt(detectedFaces, currentTime, faceTimeOf, { isVideo: true, pinnedFaceId })}
                  hoveredFaceId={hoveredMarkerId}
                  flash={faceFlash} />}
              />
            ) : !isVideo && asset.url ? (
              <ZoomableImage
                src={imageUrl ?? ""}
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

          {/* Resize handle for the media slot.

              Below the picture rather than above the timeline's own controls, because what it
              sizes is the picture: dragging down makes the video bigger and pushes the sections
              below further down, which is the gesture people already know from a split pane. */}
          <Box
            data-testid="asset-media-resize"
            onMouseDown={handleMediaResizeStart}
            role="separator"
            aria-orientation="horizontal"
            aria-label={tAD("media.resize")}
            sx={{
              flexShrink: 0, height: 8, display: "flex", alignItems: "center", justifyContent: "center",
              cursor: "row-resize", bgcolor: tokens.bg.surface,
              borderTop: `1px solid ${tokens.border.subtle}`, borderBottom: `1px solid ${tokens.border.subtle}`,
              "&:hover": { bgcolor: tokens.primary.subtle },
              "&:hover .media-drag-grip": { opacity: 1 },
              transition: "background-color 120ms ease",
            }}
          >
            <DragHandleOutlined className="media-drag-grip"
              sx={{ fontSize: 14, color: tokens.primary.main, opacity: 0.35, transition: "opacity 120ms ease" }} />
          </Box>

          {/* Timeline (video only) */}
          {isVideo && (
            <Box sx={{ flexShrink: 0, px: 2.5, py: 1.5, bgcolor: tokens.bg.surface, borderTop: `1px solid ${tokens.border.subtle}` }}>
              <VideoTimeline
                duration={duration}
                currentTime={currentTime}
                markers={markers}
                hoveredMarkerId={hoveredMarkerId}
                onSeek={seekTo}
                onMarkerClick={handleMarkerClick}
                onMarkerHover={setHoveredMarkerId}
                transcriptSpans={transcriptSpans}
                showTranscript={transcriptOpen}
                onTranscriptClick={revealTranscriptAt}
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
                    return;
                  }
                  // A region tag. The bar has drawn these since region tagging shipped and the
                  // handles moved nothing: this branch did not exist, so the drag repainted from
                  // state that never changed and the handle sprang back on release.
                  setAssetTags(prev => prev.map(tag => (tag.uuid === markerId && tag.area
                    ? { ...tag, area: movedArea(tag.area, edge, newTime) }
                    : tag)));
                }}
                onMarkerDragEnd={(markerId, edge, newTime) => {
                  if (!token) return;
                  const ann = annotations.find(a => a.id === markerId);
                  if (ann) {
                    const area: AreaInfo = {
                      ...(ann.timestampStart != null ? { from: Math.round(ann.timestampStart * 1000) } : {}),
                      ...(ann.timestampEnd != null ? { to: Math.round(ann.timestampEnd * 1000) } : {}),
                    };
                    updateAnnotation(token, markerId, { area }).catch(() => {
                      showToast(tAD("annotation.editError"), "error");
                    });
                    return;
                  }
                  // A region tag moves through its *placement*, not by being withdrawn and
                  // re-attached: the same tag may sit on the asset several times, and a
                  // re-attach would mint a new placement uuid and record the person dragging
                  // the handle as the one who attached it.
                  const tag = assetTags.find(t => t.uuid === markerId);
                  if (!tag?.placementUuid || !tag.area || !id) return; // comments have no time-persist route
                  // `newTime`, not the tag we just found: this handler was captured by the
                  // drag's mouse-up listener when the drag *started*, so `assetTags` here is
                  // the array from before the move. The annotation branch above gets away with
                  // reading its object because it mutates it in place; the tag branch replaces
                  // it, which is the correct thing to do to React state and the reason the
                  // final position has to travel as an argument.
                  const moved = movedArea(tag.area, edge, newTime);
                  updateTagPlacement(token, id, tag.placementUuid, {
                    area: {
                      ...(moved.from != null ? { from: moved.from } : {}),
                      ...(moved.to != null ? { to: moved.to } : {}),
                    },
                  })
                    .then(() => reloadTags())
                    .catch(() => showToast(tAD("tag.moveError"), "error"));
                }}
              />
            </Box>
          )}

          {/* Annotation overlay for images */}
          {!isVideo && annotations.filter(a => a.region).length > 0 && (
            <Box sx={{ flexShrink: 0, px: 2, py: 1, bgcolor: tokens.bg.surface, display: "flex", gap: 0.75, flexWrap: "wrap", alignItems: "center", borderTop: `1px solid ${tokens.border.subtle}` }}>
              <Typography variant="caption" color="text.secondary" sx={{ fontSize: "0.7rem" }}>{tAD("annotations.label")}</Typography>
              {annotations.filter(a => a.region).map(a => (
                <Chip key={a.id} label={a.title} size="small" sx={{ height: 18, fontSize: "0.65rem", bgcolor: `${a.color}22`, color: a.color }} />
              ))}
            </Box>
          )}

          {/* Tags — editable (persisted via the /assets/:uuid/tags endpoint) */}
          <Box sx={{ flexShrink: 0, px: 2, py: 1, bgcolor: tokens.bg.surface, display: "flex", gap: 0.5, flexWrap: "wrap", alignItems: "center", borderTop: `1px solid ${tokens.border.subtle}` }} data-testid="asset-tags">
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
            {/* freeSolo: the suggestions are a spelling aid, not a vocabulary. Tags already on
                this asset are filtered out - offering one that is a no-op is worse than offering
                nothing. `clearOnBlur={false}` keeps a half-typed word while the reviewer looks
                back at the picture. */}
            <Autocomplete
              freeSolo
              options={tagVocabulary.filter(name => !assetTags.some(t => t.name.toLowerCase() === name.toLowerCase()))}
              inputValue={tagInput}
              onInputChange={(_, value, reason) => { if (reason !== "reset") setTagInput(value); }}
              // Both Enter-on-a-typed-word and picking from the list arrive here — freeSolo
              // reports the first as `createOption`. A second Enter handler on the TextField
              // would fire alongside this one and tag the asset twice.
              onChange={(_, value) => { if (typeof value === "string" && value.trim()) handleAddTag(value); }}
              clearOnBlur={false}
              selectOnFocus
              handleHomeEndKeys
              sx={{ minWidth: 120, maxWidth: 180 }}
              renderInput={params => (
                <TextField
                  {...params}
                  inputRef={tagInputRef}
                  placeholder={tAD("tag.addPlaceholder")}
                  size="small"
                  variant="standard"
                  inputProps={{ ...params.inputProps, "data-testid": "tag-input" }}
                  sx={{ "& .MuiInput-root": { fontSize: "0.75rem" }, "& .MuiInput-underline:before": { borderBottom: "none" }, "& .MuiInput-underline:hover:before": { borderBottom: `1px solid ${tokens.border.default}` } }}
                />
              )}
            />
          </Box>

          {/* The foldable stack — and the one scroller in this column.

              It used to be five fixed bands with `overflow: auto` on the metadata one alone, so
              metadata scrolled and the locations, the description and a 43-minute transcript
              below it were off the bottom of an `overflow: hidden` pane with no way to reach
              them at all. One scroller around the stack, and each band foldable, is the pair of
              changes that makes the column navigable; the fold state is remembered per user, not
              per asset (see SECTION_STATE_KEY). */}
          <Box data-testid="asset-section-stack"
            sx={{ flex: 1, minHeight: 0, overflow: "auto", bgcolor: tokens.bg.surface }}>
            <CollapsibleSection id="description" title={tAD("meta.description")} icon={<NotesOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
              expanded={sectionExpanded("description")} onToggle={toggleSection}>
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
            </CollapsibleSection>

            {/* Object detections — editable bounding boxes overlaid on the central image */}
            {!isVideo && asset.url && (
              <CollapsibleSection id="detections" title={tAD("detection.label")} icon={<CenterFocusStrongOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
                meta={detections.length || undefined}
                expanded={sectionExpanded("detections")} onToggle={toggleSection}>
              <Box data-testid="asset-detections">
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
              </CollapsibleSection>
            )}

            <CollapsibleSection id="metadata" title={tAD("meta.title")} icon={<InfoOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
              expanded={sectionExpanded("metadata")} onToggle={toggleSection}>
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
            </CollapsibleSection>

            <CollapsibleSection id="locations" title={tAD("location.title")} icon={<StorageOutlined sx={{ fontSize: 13, color: tokens.text.tertiary }} />}
              meta={assetLocations.length || undefined}
              expanded={sectionExpanded("locations")} onToggle={toggleSection}>
              <Box data-testid="asset-locations">
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
            </CollapsibleSection>

            {/* Transcripts — synced with the player. One panel per transcript (an asset may
                carry several: different source, different language). Folding this is what turns
                the chapter tiles in the timeline above on and off. */}
            {transcripts.length > 0 && (
              <CollapsibleSection id="transcript" title={tAD("transcript.sectionTitle")} icon={<RecordVoiceOverOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />}
                meta={transcripts.map(t => [t.source, t.lang].filter(Boolean).join(" ")).filter(Boolean).join(" · ") || undefined}
                expanded={sectionExpanded("transcript")} onToggle={toggleSection}>
              {/* Server-side search over this asset's transcript windows, above the panels. The
                  "find in transcript" box inside each panel is a literal scan of what is already
                  loaded and stays — this answers the question that one cannot. */}
              <TranscriptSearchPanel assetUuid={asset.id} onSeek={revealTranscriptAt} />
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
                    onSeek={seekTo}
                    reveal={transcriptReveal}
                    onSectionsChange={sections => handleTranscriptSectionsChange(tr.uuid, sections)}
                  />
                </Box>
              ))}
              </CollapsibleSection>
            )}
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
        <Box ref={sidebarRef} data-testid="asset-sidebar" data-compact={labelsHidden ? "true" : "false"}
          sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", overflow: "hidden", bgcolor: tokens.bg.surface }}>
          {/* Below SIDEBAR_ICON_ONLY_PX the labels go and the icons stay, which is what lets the
              divider keep going past the width six words need. `scrollButtons` because the strip
              is then narrow enough that even icons can outrun it. */}
          <Tabs ref={tabStripRef} value={tab} onChange={(_, v) => { setTab(v); setSidebarQuery(""); }}
            variant="scrollable" scrollButtons={false}
            sx={{ px: labelsHidden ? 0.5 : 1.5, borderBottom: `1px solid ${tokens.border.subtle}`, minHeight: 40 }}>
            {tabs.map((t, i) => (
              <Tab key={i}
                label={labelsHidden ? undefined : t.label}
                aria-label={typeof t.label === "string" ? t.label : undefined}
                title={labelsHidden && typeof t.label === "string" ? t.label : undefined}
                iconPosition="start" icon={t.icon}
                sx={{ minHeight: 40, fontSize: "0.75rem", px: labelsHidden ? 1 : 1.5, minWidth: labelsHidden ? 40 : undefined }} />
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
                    onTimeClick={(t) => { seekTo(t); setHighlightedId(null); }}
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
                    onTimeClick={(t) => { seekTo(t); setHighlightedId(null); }}
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
                timeOf={faceTimeOf}
                onSeekToFace={isVideo ? seekToFace : undefined}
                onHoverFace={setHoveredMarkerId}
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

      <AddToRemixDialog
        open={addToRemixOpen}
        assetUuid={id ?? ""}
        onClose={() => setAddToRemixOpen(false)}
        onAdded={() => {
          if (token && id) {
            listAssetRemixes(token, id).then(r => setAssetRemixes(r.data ?? [])).catch(() => undefined);
          }
        }}
      />

      {shareOpen && (
        <ShareDialog
          open
          onClose={() => setShareOpen(false)}
          targetType="ASSET"
          targetUuid={asset.id}
          targetName={asset.name}
        />
      )}
    </Box>
  );
}
