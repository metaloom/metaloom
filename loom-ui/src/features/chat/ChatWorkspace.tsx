import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, TextField, IconButton, Chip, Avatar, Tooltip,
  CircularProgress, Paper, Divider, InputAdornment, Stack,
} from "@mui/material";
import {
  Send, AutoAwesome, CheckCircleOutline, ErrorOutline,
  PlayCircleOutline, ImageOutlined, TaskAltOutlined,
  AccountTreeOutlined, CollectionsOutlined, AccessTimeOutlined,
  StopCircleOutlined,
  Add, ChatBubbleOutline, DeleteOutline, ViewSidebarOutlined,
  SpaceDashboardOutlined, KeyboardDoubleArrowRight,
  SearchOutlined, AttachFileOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import HelpHint from "../../components/HelpHint";
import { AgentAction, AssetResultsPayload, AssetViewerPayload, ChatMessage, ChatReference, ChatVisual } from "../../types";
import {
  listChats, loadChat, createChat, updateChat, deleteChat, ChatResponse,
  toChatMessage, toChatReference, toChatVisual, BackendChatReference, BackendChatVisual,
} from "../../api/chat";
import {
  streamChatMessage, cancelChatStream, AgentBusyError,
  AgentDeltaEvent, AgentToolStartEvent, AgentToolEndEvent,
  AgentMessageEndEvent, AgentTitleEvent, AgentErrorEvent, AgentEndEvent,
} from "../../api/agent";
import MarkdownContent from "./MarkdownContent";
import PipelineGraphCard from "./PipelineGraphCard";
import AssetViewerCard from "./AssetViewerCard";
import { AssetResultsPanel, AssetResultsStrip } from "./AssetResults";
import ReasoningSection from "./ReasoningSection";
import SkillsPanel from "./SkillsPanel";
import ChatGreeting from "./ChatGreeting";
import { useToast } from "../../context/ToastContext";
import { useSpace } from "../../context/SpaceContext";
import { useTranslation } from "react-i18next";
import AssetBrowser from "../assets/AssetBrowser";
import { useAuth } from "../../context/AuthContext";
import { listAssets, loadAsset as apiLoadAsset, AssetResponse } from "../../api/assets";
import { assetTypeFromMime } from "../assets/assetMapping";
import { listCollections, CollectionResponse } from "../../api/collections";
import { listTasks, TaskResponse } from "../../api/tasks";
import { PAGE_SIZE } from "../../hooks/pagedList";
import ChatAttachmentChips from "./ChatAttachmentChips";
import { dragCarriesFiles } from "./attachmentState";
import { useChatAttachments } from "./useChatAttachments";

/**
 * Courtesy limits for dropped files, mirroring LOOM_CHAT_ATTACHMENT_MAX_FILES / _MAX_BYTES.
 *
 * The server enforces both again and is the authority; these exist so a refusal happens at drop
 * time rather than after a 25 MB upload. They are not read from the server because there is no
 * configuration endpoint for them, and a wrong guess here costs a redundant round trip at worst.
 */
const ATTACHMENT_LIMITS = { maxFiles: 10, maxBytes: 25 * 1024 * 1024 };

// ── Reference chip renderer ───────────────────────────────────────────────
function RefChip({ chatRef: r, onAssetClick }: { chatRef: ChatReference; onAssetClick?: (id: string, startSeconds?: number) => void }) {
  const navigate = useNavigate();
  type RefType = "asset" | "collection" | "task" | "pipeline" | "annotation";
  const iconMap: Record<RefType, React.ReactNode> = {
    asset: <PlayCircleOutline sx={{ fontSize: 13 }} />,
    collection: <CollectionsOutlined sx={{ fontSize: 13 }} />,
    task: <TaskAltOutlined sx={{ fontSize: 13 }} />,
    pipeline: <AccountTreeOutlined sx={{ fontSize: 13 }} />,
    annotation: <AccessTimeOutlined sx={{ fontSize: 13 }} />,
  };
  const colorMap: Record<RefType, string> = {
    asset: tokens.accent.blue,
    collection: tokens.primary.light,
    task: tokens.accent.amber,
    pipeline: tokens.accent.teal,
    annotation: tokens.accent.green,
  };
  const color = colorMap[r.type as RefType] ?? tokens.text.secondary;
  const icon = iconMap[r.type as RefType];

  const handleClick = () => {
    if (r.type === "asset" && onAssetClick) {
      onAssetClick(r.id);
    } else if (r.type === "asset") {
      navigate(`/assets/${r.id}`);
    } else if (r.type === "pipeline") navigate("/pipelines");
    else if (r.type === "task") navigate("/tasks");
    else if (r.type === "collection") navigate("/collections");
  };

  return (
    <Chip
      icon={<Box sx={{ color, display: "flex", ml: "6px !important" }}>{icon}</Box>}
      label={r.label}
      size="small"
      onClick={handleClick}
      sx={{
        bgcolor: `${color}14`,
        border: `1px solid ${color}33`,
        color: color,
        fontSize: "0.73rem",
        fontWeight: 500,
        cursor: "pointer",
        "&:hover": { bgcolor: `${color}22` },
        height: 22,
      }}
    />
  );
}

// ── Action status row ─────────────────────────────────────────────────────
function ActionRow({ action }: { action: NonNullable<ChatMessage["actions"]>[0] }) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1, py: 0.3 }}>
      {action.status === "done" ? (
        <CheckCircleOutline sx={{ fontSize: 14, color: tokens.accent.green }} />
      ) : action.status === "error" ? (
        <ErrorOutline sx={{ fontSize: 14, color: tokens.accent.red }} />
      ) : (
        <CircularProgress size={12} sx={{ color: tokens.primary.main }} />
      )}
      <Typography variant="caption" fontWeight={500} color={action.status === "error" ? "error" : "text.secondary"}>
        {action.label}
      </Typography>
      {action.result && (
        <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
          — {action.result}
        </Typography>
      )}
    </Box>
  );
}

// ── Message bubble ────────────────────────────────────────────────────────
function MessageBubble({ msg, onFollowUp, onAssetClick, reasoningStreaming = false }: {
  msg: ChatMessage;
  onFollowUp: (text: string) => void;
  /** Open an asset in the workspace panel; `startSeconds` carries the moment a transcript hit matched. */
  onAssetClick?: (id: string, startSeconds?: number) => void;
  /** True while the agent is streaming reasoning deltas for this (in-flight) message. */
  reasoningStreaming?: boolean;
}) {
  const isUser = msg.role === "user";
  const isSystem = msg.role === "system";

  if (isSystem) {
    return (
      <Box sx={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 1, py: 2 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, opacity: 0.7 }}>
          <AutoAwesome sx={{ fontSize: 14, color: tokens.primary.main }} />
          <Typography variant="caption" sx={{ color: tokens.text.secondary, fontStyle: "italic" }}>
            {msg.content}
          </Typography>
        </Box>
        {msg.suggestedFollowUps && (
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.75, justifyContent: "center" }}>
            {msg.suggestedFollowUps.map((s) => (
              <Chip
                key={s} label={s} size="small"
                onClick={() => onFollowUp(s)}
                sx={{
                  bgcolor: tokens.primary.subtle,
                  border: `1px solid ${tokens.primary.glow}`,
                  color: tokens.primary.light,
                  fontSize: "0.72rem",
                  cursor: "pointer",
                  "&:hover": { bgcolor: tokens.primary.glow },
                }}
              />
            ))}
          </Box>
        )}
      </Box>
    );
  }

  return (
    <Box
      sx={{
        display: "flex",
        flexDirection: isUser ? "row-reverse" : "row",
        gap: 1.25,
        alignItems: "flex-start",
        mb: 2,
      }}
    >
      {!isUser && (
        <Box
          sx={{
            width: 28, height: 28, borderRadius: "50%",
            background: `linear-gradient(135deg, ${tokens.primary.main}, ${tokens.primary.dark})`,
            display: "flex", alignItems: "center", justifyContent: "center",
            flexShrink: 0, mt: 0.25, boxShadow: `0 0 10px ${tokens.primary.glow}`,
          }}
        >
          <AutoAwesome sx={{ fontSize: 14, color: "#fff" }} />
        </Box>
      )}

      <Box sx={{ maxWidth: "82%", display: "flex", flexDirection: "column", gap: 0.75, alignItems: isUser ? "flex-end" : "flex-start" }}>
        {/* Reasoning — hidden by default, live indicator while streaming */}
        {!isUser && (reasoningStreaming || msg.reasoning) && (
          <ReasoningSection reasoning={msg.reasoning} streaming={reasoningStreaming} />
        )}

        {(msg.content || isUser) && (
          <Paper
            elevation={0}
            sx={{
              px: 1.75, py: 1.25,
              bgcolor: isUser ? tokens.primary.subtle : tokens.bg.elevated,
              border: `1px solid ${isUser ? tokens.primary.glow : tokens.border.subtle}`,
              borderRadius: isUser ? `${tokens.radius.lg} ${tokens.radius.md} ${tokens.radius.sm} ${tokens.radius.lg}` : `${tokens.radius.md} ${tokens.radius.lg} ${tokens.radius.lg} ${tokens.radius.sm}`,
            }}
          >
            <MarkdownContent content={msg.content} />
          </Paper>
        )}

        {/* Actions */}
        {msg.actions && msg.actions.length > 0 && (
          <Box sx={{ px: 1, display: "flex", flexDirection: "column", gap: 0.25 }}>
            {msg.actions.map((a) => <ActionRow key={a.id} action={a} />)}
          </Box>
        )}

        {/* Inline visualizations — rendered as soon as the tool returns them, before the answer exists.
            An unknown type renders nothing: a visual is an enhancement of a tool result whose text
            already carries the answer, so a client that does not know a type must stay quiet rather
            than draw a broken card. */}
        {msg.visuals && msg.visuals.length > 0 && (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75, width: "100%", maxWidth: "100%" }}>
            {msg.visuals.map((v, i) => {
              const key = `${v.type}-${v.id}-${i}`;
              if (v.type === "pipeline-graph") return <PipelineGraphCard key={key} visual={v} />;
              if (v.type === "asset-viewer") return <AssetViewerCard key={key} payload={v.payload as AssetViewerPayload} />;
              if (v.type === "asset-results") {
                return <AssetResultsStrip key={key} payload={v.payload as AssetResultsPayload} onSelect={onAssetClick} />;
              }
              return null;
            })}
          </Box>
        )}

        {/* References */}
        {msg.references && msg.references.length > 0 && (
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, px: 0.5 }}>
            {msg.references.map((r) => <RefChip key={r.id + r.type} chatRef={r} onAssetClick={onAssetClick} />)}
          </Box>
        )}

        {/* Follow-ups */}
        {!isUser && msg.suggestedFollowUps && (
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, px: 0.5 }}>
            {msg.suggestedFollowUps.map((s) => (
              <Chip
                key={s} label={s} size="small"
                onClick={() => onFollowUp(s)}
                sx={{
                  bgcolor: "transparent",
                  border: `1px solid ${tokens.border.default}`,
                  color: tokens.text.secondary,
                  fontSize: "0.72rem",
                  cursor: "pointer",
                  "&:hover": { borderColor: tokens.primary.main, color: tokens.primary.light, bgcolor: tokens.primary.subtle },
                }}
              />
            ))}
          </Box>
        )}

        <Typography variant="caption" sx={{ color: tokens.text.tertiary, px: 0.5, fontSize: "0.68rem" }}>
          {new Date(msg.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
        </Typography>
      </Box>
    </Box>
  );
}

// ── Right panel — context-driven workspace ────────────────────────────────

/** The panel's tabs. `results` only exists once the agent has run a search, and is then the default. */
type WorkspaceMode = "overview" | "assets" | "results";

/** The payload `kind` for a mime type, so a panel preview builds the same viewer an inline card does. */
function viewerKindOf(mimeType: string | undefined): AssetViewerPayload["kind"] {
  const type = assetTypeFromMime(mimeType);
  return type === "unknown" ? "other" : type;
}

function WorkspacePanel({ mode, results, selectedAssetId, selectedAssetStart, onSelectAsset, onClearAsset }: {
  mode: WorkspaceMode;
  /** The last result set the agent produced, mirrored from an `asset-results` visual. */
  results: AssetResultsPayload | null;
  selectedAssetId?: string | null;
  /** Where a player opened from a result should start — the moment a transcript hit matched. */
  selectedAssetStart?: number;
  onSelectAsset?: (uuid: string, startSeconds?: number) => void;
  onClearAsset?: () => void;
}) {
  const { t } = useTranslation();
  const { token } = useAuth();
  const [assets, setAssets] = useState<AssetResponse[]>([]);
  const [tasks, setTasks] = useState<TaskResponse[]>([]);
  const [collections, setCollections] = useState<CollectionResponse[]>([]);
  const [selectedAsset, setSelectedAsset] = useState<AssetResponse | null>(null);

  useEffect(() => {
    if (!token) return;
    listAssets(token, { limit: PAGE_SIZE }).then(r => setAssets(r.data ?? [])).catch(() => {});
    listTasks(token, { limit: PAGE_SIZE }).then(r => setTasks(r.data ?? [])).catch(() => {});
    listCollections(token, { limit: PAGE_SIZE }).then(r => setCollections(r.data ?? [])).catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!selectedAssetId || !token) { setSelectedAsset(null); return; }
    apiLoadAsset(token, selectedAssetId).then(setSelectedAsset).catch(() => setSelectedAsset(null));
  }, [selectedAssetId, token]);

  // If an asset is selected from the chat, show it inline
  if (selectedAssetId && selectedAsset) {
    const mime = selectedAsset.file?.mimeType ?? "";
    const filename = selectedAsset.file?.filename ?? selectedAsset.uuid;
    const fileSize = selectedAsset.file?.size ?? 0;
    const tags = (selectedAsset.tags ?? []).map(t => t.name);
    const width = selectedAsset.imageComponents?.[0]?.width ?? selectedAsset.videoComponents?.[0]?.width;
    const height = selectedAsset.imageComponents?.[0]?.height ?? selectedAsset.videoComponents?.[0]?.height;
    const duration = selectedAsset.videoComponents?.[0]?.duration ?? selectedAsset.audioComponents?.[0]?.duration;
    return (
      <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
        {/* The same viewer the transcript embeds. This slot used to be a 40x28 grey rectangle — a
            panel headed "Open" beside a chat that had just found the file, showing nothing of it. */}
        <Box sx={{ p: 1.25, flexShrink: 0 }}>
          <AssetViewerCard
            testId="chat-panel-asset-viewer"
            payload={{
              assetUuid: selectedAsset.uuid,
              filename,
              mimeType: mime,
              kind: viewerKindOf(mime),
              size: fileSize,
              startSeconds: selectedAssetStart,
            }}
            onClose={onClearAsset}
          />
        </Box>
        <Box sx={{ flex: 1, overflow: "auto", display: "flex", flexDirection: "column" }}>
          <Box sx={{ px: 2, pb: 2, display: "flex", flexDirection: "column", gap: 1.5 }}>
            <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}>
              {tags.map(tg => (
                <Chip key={tg} label={tg} size="small" sx={{ height: 20, fontSize: "0.68rem", bgcolor: tokens.bg.elevated }} />
              ))}
            </Box>
            <Box sx={{ display: "grid", gridTemplateColumns: "auto 1fr", gap: "4px 12px" }}>
              {[
                [t("chat.detail.size"), `${(fileSize / 1e6).toFixed(1)} MB`],
                ...(duration ? [[t("chat.detail.duration"), `${Math.floor(duration / 60)}:${(duration % 60).toString().padStart(2, "0")}`]] : []),
                ...(width ? [[t("chat.detail.dimensions"), `${width}×${height}`]] : []),
              ].map(([k, v]) => (
                <React.Fragment key={k}>
                  <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.72rem" }}>{k}</Typography>
                  <Typography variant="caption" sx={{ color: tokens.text.secondary, fontSize: "0.72rem" }}>{v}</Typography>
                </React.Fragment>
              ))}
            </Box>
          </Box>
        </Box>
      </Box>
    );
  }

  // The result set the agent is talking about, not the newest rows in the catalogue.
  if (mode === "results" && results) {
    return <AssetResultsPanel payload={results} selectedUuid={selectedAssetId} onSelect={onSelectAsset} />;
  }

  if (mode === "assets") return <AssetBrowser embedded />;

  return (
    <Box sx={{ p: 2.5, display: "flex", flexDirection: "column", gap: 2 }}>
      {/* Recent assets */}
      <SectionCard title={t("chat.panel.recentAssets")} icon={<ImageOutlined sx={{ fontSize: 14 }} />}>
        {assets.slice(0, 4).map((a) => (
          <AssetRow key={a.uuid} asset={a} />
        ))}
      </SectionCard>

      <SectionCard title={t("chat.panel.activeTasks")} icon={<TaskAltOutlined sx={{ fontSize: 14 }} />}>
        {tasks.slice(0, 4).map((tk) => (
          <TaskRow key={tk.uuid} task={tk} />
        ))}
      </SectionCard>

      <SectionCard title={t("chat.panel.collections")} icon={<CollectionsOutlined sx={{ fontSize: 14 }} />}>
        {collections.slice(0, 3).map((c) => (
          <CollectionRow key={c.uuid} collection={c} />
        ))}
      </SectionCard>
    </Box>
  );
}

function SectionCard({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <Paper elevation={0} sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.lg, overflow: "hidden" }}>
      <Box sx={{ px: 2, py: 1.25, display: "flex", alignItems: "center", gap: 0.75, borderBottom: `1px solid ${tokens.border.subtle}` }}>
        <Box sx={{ color: tokens.text.secondary }}>{icon}</Box>
        <Typography variant="caption" fontWeight={600} color="text.secondary" sx={{ textTransform: "uppercase", letterSpacing: "0.06em", fontSize: "0.7rem" }}>
          {title}
        </Typography>
      </Box>
      <Box sx={{ p: 1.5, display: "flex", flexDirection: "column", gap: 0.5 }}>
        {children}
      </Box>
    </Paper>
  );
}

function AssetRow({ asset }: { asset: AssetResponse }) {
  const navigate = useNavigate();
  const filename = asset.file?.filename ?? asset.uuid;
  const mime = asset.file?.mimeType ?? "";
  return (
    <Box
      onClick={() => navigate(`/assets/${asset.uuid}`)}
      sx={{
        display: "flex", alignItems: "center", gap: 1.5, px: 1, py: 0.75,
        borderRadius: tokens.radius.md, cursor: "pointer",
        "&:hover": { bgcolor: tokens.bg.hover },
      }}
    >
      <Box sx={{ width: 36, height: 24, borderRadius: tokens.radius.sm, overflow: "hidden", flexShrink: 0, bgcolor: tokens.bg.overlay }} />
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Typography variant="caption" fontWeight={500} color="text.primary" noWrap display="block" sx={{ fontSize: "0.78rem" }}>
          {filename}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {mime}
        </Typography>
      </Box>
    </Box>
  );
}

function TaskRow({ task }: { task: TaskResponse }) {
  const priorityColor: Record<string, string> = { critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary };
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 1, py: 0.6, borderRadius: tokens.radius.md, "&:hover": { bgcolor: tokens.bg.hover }, cursor: "pointer" }}>
      <Box sx={{ width: 3, height: 18, borderRadius: 2, bgcolor: priorityColor[task.priority ?? ""] ?? tokens.text.tertiary, flexShrink: 0 }} />
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Typography variant="caption" fontWeight={500} color="text.primary" noWrap display="block" sx={{ fontSize: "0.78rem" }}>
          {task.title}
        </Typography>
      </Box>
    </Box>
  );
}

function CollectionRow({ collection }: { collection: CollectionResponse }) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 1, py: 0.6, borderRadius: tokens.radius.md, "&:hover": { bgcolor: tokens.bg.hover }, cursor: "pointer" }}>
      <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: tokens.primary.main, flexShrink: 0 }} />
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Typography variant="caption" fontWeight={500} color="text.primary" noWrap display="block" sx={{ fontSize: "0.78rem" }}>
          {collection.name}
        </Typography>
      </Box>
    </Box>
  );
}

// ── Chat / workspace split ────────────────────────────────────────────────
// The split is stored as a percentage of the area right of the sessions rail, not as a
// pixel width: a fixed width capped the chat column at a few hundred pixels, so the
// divider barely moved on a wide screen. The bounds only keep both sides usable.
const SPLIT_STORAGE_KEY = "loom.chat.splitPct";
const PANEL_STORAGE_KEY = "loom.chat.panelOpen";
const SPLIT_DEFAULT_PCT = 80;
const SPLIT_MIN_PCT = 20;
const SPLIT_MAX_PCT = 95;

function clampSplit(pct: number): number {
  return Math.max(SPLIT_MIN_PCT, Math.min(SPLIT_MAX_PCT, pct));
}

function readStoredSplit(): number {
  const raw = Number(localStorage.getItem(SPLIT_STORAGE_KEY));
  return Number.isFinite(raw) && raw > 0 ? clampSplit(raw) : SPLIT_DEFAULT_PCT;
}

function readStoredPanelOpen(): boolean {
  return localStorage.getItem(PANEL_STORAGE_KEY) !== "false";
}

/**
 * The newest `asset-results` payload in a transcript, or null.
 *
 * Read on session load so reopening a conversation restores the panel to the search it was about.
 * Newest wins: a conversation that searched three times is about the third search.
 */
function latestResults(messages: ChatMessage[]): AssetResultsPayload | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const visuals = messages[i].visuals ?? [];
    for (let j = visuals.length - 1; j >= 0; j--) {
      if (visuals[j].type === "asset-results") return visuals[j].payload as AssetResultsPayload;
    }
  }
  return null;
}

// ── Streaming state of the in-flight assistant message ────────────────────
type StreamPhase = "idle" | "reasoning" | "answering" | "tool";

interface StreamingState {
  phase: StreamPhase;
  msg: ChatMessage;
}

// ── Main Chat Workspace ───────────────────────────────────────────────────
export default function ChatWorkspace() {
  const { activeSpace } = useSpace();
  const { token, username } = useAuth();
  const { t } = useTranslation();
  const { showToast } = useToast();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [streaming, setStreaming] = useState<StreamingState | null>(null);
  const [activeSkillUuids, setActiveSkillUuids] = useState<string[]>([]);
  const [workspaceMode, setWorkspaceMode] = useState<WorkspaceMode>("overview");
  /**
   * The last result set the agent produced, mirrored out of an `asset-results` visual.
   *
   * Kept here rather than in the panel because both halves of the screen read it: the strip under
   * the answer and the browser beside it are two renderings of one payload, which is what keeps
   * them talking about the same assets.
   */
  const [results, setResults] = useState<AssetResultsPayload | null>(null);
  const [chatPct, setChatPct] = useState(readStoredSplit);
  const [panelOpen, setPanelOpen] = useState(readStoredPanelOpen);
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null);
  /** Where the panel's player should open, when the asset was picked from a transcript hit. */
  const [selectedAssetStart, setSelectedAssetStart] = useState<number | undefined>(undefined);
  const [sessions, setSessions] = useState<ChatResponse[]>([]);
  const [railQuery, setRailQuery] = useState("");

  /** The rail, narrowed by the box above it. Titles only — the rail shows nothing else. */
  const visibleSessions = useMemo(() => {
    const q = railQuery.trim().toLowerCase();
    if (!q) return sessions;
    return sessions.filter(s => (s.title ?? "").toLowerCase().includes(q));
  }, [sessions, railQuery]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [railOpen, setRailOpen] = useState(true);
  const scrollRef = useRef<HTMLDivElement>(null);
  const splitRef = useRef<HTMLDivElement>(null);
  const isDragging = useRef(false);
  const abortRef = useRef<AbortController | null>(null);
  const attachmentsRef = useRef<ReturnType<typeof useChatAttachments> | null>(null);
  const activeChatRef = useRef<string | null>(null);

  // Abort a running stream on unmount
  useEffect(() => () => abortRef.current?.abort(), []);

  useEffect(() => {
    localStorage.setItem(SPLIT_STORAGE_KEY, String(chatPct));
  }, [chatPct]);

  useEffect(() => {
    localStorage.setItem(PANEL_STORAGE_KEY, String(panelOpen));
  }, [panelOpen]);

  // Dragging tracks the pointer against the split container rather than accumulating a
  // delta, so the divider stays glued to the cursor even after the clamp kicks in.
  const handleDividerMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isDragging.current = true;
    const rect = splitRef.current?.getBoundingClientRect();
    const onMove = (ev: MouseEvent) => {
      if (!isDragging.current || !rect || rect.width === 0) return;
      setChatPct(clampSplit(((ev.clientX - rect.left) / rect.width) * 100));
    };
    const onUp = () => {
      isDragging.current = false;
      document.body.style.cursor = "";
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    // Held on the body for the whole drag — without it the cursor flickers back to the
    // default whenever the pointer outruns the 6px divider.
    document.body.style.cursor = "col-resize";
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, []);

  // Load the list of persisted conversations for the session rail.
  useEffect(() => {
    if (!token) return;
    listChats(token)
      .then(res => setSessions(res.data ?? []))
      .catch(e => console.error("Failed to list chats", e));
  }, [token]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, streaming]);

  // Start a fresh, empty conversation. The backend session is created lazily
  // on the first sendMessage, so we never persist empty sessions.
  /**
   * Point the workspace panel at a result set, or back at the overview.
   *
   * The panel switches to it by itself, and that is the feature: a user who has just asked the
   * agent to find something is looking at the answer, not at a tab bar. It only ever switches
   * *to* results — leaving the tab they chose alone once they have chosen one is handled by the
   * caller, which only calls this when a new search has actually run.
   */
  const showResults = useCallback((payload: AssetResultsPayload | null) => {
    setResults(payload);
    setWorkspaceMode(mode => (payload ? "results" : mode === "results" ? "overview" : mode));
  }, []);

  const newChat = () => {
    abortRef.current?.abort();
    setSessionId(null);
    setMessages([]);
    setActiveSkillUuids([]);
    showResults(null);
    setSelectedAssetId(null);
    attachmentsRef.current?.clear();
  };

  const loadSession = async (uuid: string) => {
    if (!token) return;
    abortRef.current?.abort();
    try {
      const res = await loadChat(token, uuid);
      setMessages(res.messages ?? []);
      setSessionId(uuid);
      // The panel belongs to the conversation, so it is restored with it rather than left
      // showing the previous session's search.
      setSelectedAssetId(null);
      showResults(latestResults(res.messages ?? []));
      // Restore the per-session skill toggles from the chat meta
      const metaSkills = res.meta?.activeSkillUuids;
      setActiveSkillUuids(Array.isArray(metaSkills) ? (metaSkills as string[]) : []);
      // The attachments belong to the conversation too, so they are restored with it.
      attachmentsRef.current?.load(uuid);
    } catch (e) {
      console.error("Failed to load chat", e);
    }
  };

  // Toggling skills takes effect on the next message; the set is persisted onto
  // the chat meta so reopening the session restores the toggles.
  const handleSkillsChange = (uuids: string[]) => {
    setActiveSkillUuids(uuids);
    if (token && sessionId) {
      updateChat(token, sessionId, { meta: { activeSkillUuids: uuids } }).catch(e => console.error("Failed to persist skill toggles", e));
    }
  };

  const handleDeleteSession = async (uuid: string) => {
    if (!token) return;
    try {
      await deleteChat(token, uuid);
      setSessions(prev => prev.filter(s => s.uuid !== uuid));
      if (sessionId === uuid) {
        newChat();
      }
    } catch (e) {
      console.error("Failed to delete chat", e);
    }
  };

  /**
   * The chat uuid, creating the conversation if this is the first thing to happen in it.
   *
   * A session is created lazily so an opened-and-abandoned chat never reaches the database. Both a
   * first message and a first dropped file have to trigger that, and they have to agree on how — a
   * drop that made its own session would leave the message stream talking to a different chat.
   *
   * @param title what to call a newly created conversation
   */
  const ensureSession = useCallback(async (title: string): Promise<string> => {
    if (!token) throw new Error("Not authenticated");
    if (sessionId) return sessionId;
    const created = await createChat(token, { title: title.slice(0, 40) || t("chat.sessions.newChat"), messages: [] });
    setSessionId(created.uuid);
    setSessions(prev => [created, ...prev]);
    return created.uuid;
  }, [token, sessionId, t]);

  const attachments = useChatAttachments(
    token,
    sessionId,
    // A file dropped before anything is typed names the conversation after itself; the first
    // message would otherwise have to rename it, and an untitled chat is worse than an approximate
    // title.
    useCallback(() => ensureSession(t("chat.attachments.newChatTitle")), [ensureSession, t]),
    ATTACHMENT_LIMITS,
    useCallback((msg: string) => showToast(msg, "warning"), [showToast])
  );

  // newChat and loadSession are declared above this hook, so they reach it through a ref rather
  // than being reordered around it.
  attachmentsRef.current = attachments;

  /** How many nested dragenter events are outstanding; see the drop handlers below. */
  const dragDepth = useRef(0);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const handleDragEnter = (e: React.DragEvent) => {
    if (!dragCarriesFiles(e.dataTransfer?.types)) return;
    e.preventDefault();
    // Counted rather than a boolean: dragging across a child fires dragleave on the parent, and a
    // plain flag makes the overlay flicker off over every message bubble it crosses.
    dragDepth.current += 1;
    setDragActive(true);
  };

  const handleDragOver = (e: React.DragEvent) => {
    if (!dragCarriesFiles(e.dataTransfer?.types)) return;
    // Without this the browser navigates to the dropped file and the conversation is gone.
    e.preventDefault();
  };

  const handleDragLeave = (e: React.DragEvent) => {
    if (!dragCarriesFiles(e.dataTransfer?.types)) return;
    e.preventDefault();
    dragDepth.current = Math.max(dragDepth.current - 1, 0);
    if (dragDepth.current === 0) setDragActive(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    if (!dragCarriesFiles(e.dataTransfer?.types)) return;
    e.preventDefault();
    dragDepth.current = 0;
    setDragActive(false);
    attachments.add(Array.from(e.dataTransfer?.files ?? []));
  };

  const sendMessage = async (text: string) => {
    if (!text.trim() || sending || !token) return;
    const trimmed = text.trim();
    const userMsg: ChatMessage = {
      id: `msg_usr_${Date.now()}`,
      role: "user",
      content: trimmed,
      createdAt: new Date().toISOString(),
    };
    setMessages(prev => [...prev, userMsg]);
    setInput("");
    setSending(true);

    // The panel used to be driven from here, by looking for the words "asset" or "show" in what
    // was typed. It opened the asset browser on "show me the pipeline" and left it on the
    // catalogue's newest rows whatever the agent then found. The panel now follows the agent's
    // actual result set instead — see the asset-results visual in the tool_end handler below.

    try {
      // The backend persists the transcript onto the chat row, so the session
      // must exist before streaming. Created lazily to avoid empty sessions.
      const chatUuid = await ensureSession(trimmed);
      activeChatRef.current = chatUuid;

      // Accumulated state of the in-flight assistant message
      const acc = {
        phase: "idle" as StreamPhase,
        content: "",
        reasoning: "",
        actions: [] as AgentAction[],
        references: [] as ChatReference[],
        visuals: [] as ChatVisual[],
      };
      const render = () => setStreaming({
        phase: acc.phase,
        msg: {
          id: "msg_streaming",
          role: "assistant",
          content: acc.content,
          reasoning: acc.reasoning || undefined,
          createdAt: new Date().toISOString(),
          actions: acc.actions.length ? [...acc.actions] : undefined,
          references: acc.references.length ? [...acc.references] : undefined,
          visuals: acc.visuals.length ? [...acc.visuals] : undefined,
        },
      });

      const controller = new AbortController();
      abortRef.current = controller;
      let finalMsg: ChatMessage | null = null;

      await streamChatMessage(token, chatUuid, { message: trimmed, skillUuids: activeSkillUuids }, (type, data) => {
        switch (type) {
          case "reasoning_delta": {
            acc.phase = "reasoning";
            acc.reasoning += (data as AgentDeltaEvent).text;
            break;
          }
          case "text_delta": {
            acc.phase = "answering";
            acc.content += (data as AgentDeltaEvent).text;
            break;
          }
          case "tool_start": {
            const d = data as AgentToolStartEvent;
            acc.phase = "tool";
            acc.actions.push({ id: d.toolCallId, label: d.name, description: "", status: "running" });
            break;
          }
          case "tool_end": {
            const d = data as AgentToolEndEvent;
            const action = acc.actions.find(a => a.id === d.toolCallId);
            if (action) {
              action.status = d.isError ? "error" : "done";
              action.result = d.summary ? d.summary.split("\n")[0].slice(0, 120) : undefined;
            }
            // References render as chips as soon as the tool returns them
            for (const raw of (d.references ?? []) as BackendChatReference[]) {
              const ref = toChatReference(raw);
              if (ref.id && !acc.references.some(r => r.id === ref.id && r.type === ref.type)) {
                acc.references.push(ref);
              }
            }
            // …and visuals (e.g. a pipeline graph) render as cards, likewise before the answer exists
            for (const raw of (d.visuals ?? []) as BackendChatVisual[]) {
              const vis = toChatVisual(raw);
              if (vis.type && !acc.visuals.some(v => v.id === vis.id && v.type === vis.type)) {
                acc.visuals.push(vis);
              }
              // A search result set also drives the panel beside the conversation, and it does so
              // the moment the tool returns — while the model is still composing the sentence
              // about it, which is when the user is already scanning for the file.
              if (vis.type === "asset-results") {
                showResults(vis.payload as AssetResultsPayload);
              }
            }
            break;
          }
          case "message_end": {
            finalMsg = toChatMessage((data as AgentMessageEndEvent).message);
            break;
          }
          case "title": {
            const title = (data as AgentTitleEvent).title;
            setSessions(prev => prev.map(s => (s.uuid === chatUuid ? { ...s, title } : s)));
            break;
          }
          case "error": {
            const d = data as AgentErrorEvent;
            showToast(d.terminal ? t("chat.error.llm") : d.message, d.terminal ? "error" : "warning");
            break;
          }
          case "agent_end": {
            if ((data as AgentEndEvent).status === "aborted") {
              showToast(t("chat.error.aborted"), "info");
            }
            break;
          }
        }
        render();
      }, controller.signal);

      if (finalMsg !== null) {
        // The authoritative persisted message replaces the accumulated deltas;
        // the live action rows are kept for display.
        const settled = finalMsg as ChatMessage;
        setMessages(prev => [...prev, {
          ...settled,
          actions: acc.actions.length ? acc.actions : undefined,
          references: settled.references ?? (acc.references.length ? acc.references : undefined),
          visuals: settled.visuals ?? (acc.visuals.length ? acc.visuals : undefined),
        }]);
      } else if (acc.content || acc.actions.length) {
        // Aborted or errored mid-run — keep the partial output visible
        setMessages(prev => [...prev, {
          id: `msg_partial_${Date.now()}`,
          role: "assistant",
          content: acc.content,
          reasoning: acc.reasoning || undefined,
          createdAt: new Date().toISOString(),
          actions: acc.actions,
          references: acc.references.length ? acc.references : undefined,
          visuals: acc.visuals.length ? acc.visuals : undefined,
        }]);
      }
    } catch (e) {
      if (e instanceof AgentBusyError) {
        showToast(t("chat.error.busy"), "warning");
      } else {
        console.error("Agent stream failed", e);
        showToast(t("chat.error.llm"), "error");
      }
      // Roll back the optimistic user message and restore the input for a retry
      setMessages(prev => prev.filter(m => m.id !== userMsg.id));
      setInput(trimmed);
    } finally {
      abortRef.current = null;
      activeChatRef.current = null;
      setStreaming(null);
      setSending(false);
    }
  };

  /**
   * Put one asset in the workspace panel, optionally at a position.
   *
   * The panel rather than the asset detail route, because the conversation is the context: opening
   * `/assets/:uuid` navigates away from the transcript that named the file, and the next thing a
   * reviewer does is ask a follow-up about it.
   */
  const openAsset = useCallback((uuid: string, startSeconds?: number) => {
    setSelectedAssetId(uuid);
    setSelectedAssetStart(startSeconds);
  }, []);

  const stopStreaming = () => {
    abortRef.current?.abort();
    const chatUuid = activeChatRef.current;
    if (token && chatUuid) {
      cancelChatStream(token, chatUuid).catch(() => {});
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage(input);
    }
  };

  return (
    <Box sx={{ display: "flex", height: "100%", overflow: "hidden" }}>
      {/* ── Sessions rail ── */}
      {railOpen && (
        <Box
          sx={{
            width: 220,
            flexShrink: 0,
            display: { xs: "none", md: "flex" },
            flexDirection: "column",
            bgcolor: tokens.bg.base,
            borderRight: `1px solid ${tokens.border.subtle}`,
          }}
        >
          <Box sx={{ px: 1.5, py: 1.5, borderBottom: `1px solid ${tokens.border.subtle}` }}>
            <Box
              role="button"
              onClick={newChat}
              sx={{
                display: "flex", alignItems: "center", gap: 1,
                px: 1.25, py: 1, borderRadius: tokens.radius.md,
                border: `1px solid ${tokens.border.default}`,
                cursor: "pointer", color: tokens.text.primary,
                "&:hover": { bgcolor: tokens.bg.hover, borderColor: tokens.primary.main },
                transition: "all 120ms ease",
              }}
            >
              <Add sx={{ fontSize: 16 }} />
              <Typography variant="caption" fontWeight={600} sx={{ fontSize: "0.78rem" }}>
                {t("chat.sessions.newChat")}
              </Typography>
            </Box>

            {/* The rail is how you get back to a past conversation, and it grows without bound.
                Scrolling it was the only way to find one. Filters the rail only — the sessions
                screen at /chat/sessions is the one with sorting and server-side filters. */}
            {sessions.length > 0 && (
              <TextField
                value={railQuery}
                onChange={e => setRailQuery(e.target.value)}
                placeholder={t("chat.sessions.search")}
                size="small"
                data-testid="chat-rail-search"
                fullWidth
                sx={{ mt: 1, "& .MuiInputBase-root": { fontSize: "0.75rem" } }}
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchOutlined sx={{ fontSize: 14, color: tokens.text.tertiary }} />
                    </InputAdornment>
                  ),
                }}
              />
            )}
          </Box>
          <Box sx={{ flex: 1, overflow: "auto", py: 0.5 }}>
            {sessions.length === 0 ? (
              <Typography variant="caption" sx={{ px: 2, py: 1.5, display: "block", color: tokens.text.tertiary, fontSize: "0.72rem" }}>
                {t("chat.sessions.empty")}
              </Typography>
            ) : visibleSessions.length === 0 ? (
              <Typography variant="caption" data-testid="chat-rail-no-match"
                sx={{ px: 2, py: 1.5, display: "block", color: tokens.text.tertiary, fontSize: "0.72rem" }}>
                {t("chat.sessions.noMatch")}
              </Typography>
            ) : (
              visibleSessions.map((s) => (
                <Box
                  key={s.uuid}
                  onClick={() => loadSession(s.uuid)}
                  sx={{
                    display: "flex", alignItems: "center", gap: 1,
                    mx: 1, px: 1.25, py: 0.9, borderRadius: tokens.radius.md,
                    cursor: "pointer",
                    bgcolor: sessionId === s.uuid ? tokens.primary.subtle : "transparent",
                    border: `1px solid ${sessionId === s.uuid ? tokens.primary.main : "transparent"}`,
                    "&:hover": { bgcolor: sessionId === s.uuid ? tokens.primary.subtle : tokens.bg.hover },
                    "&:hover .chat-del": { opacity: 1 },
                    transition: "background-color 120ms ease",
                  }}
                >
                  <ChatBubbleOutline sx={{ fontSize: 14, color: tokens.text.tertiary, flexShrink: 0 }} />
                  <Typography variant="caption" noWrap sx={{ flex: 1, fontSize: "0.76rem", color: tokens.text.primary }}>
                    {s.title || t("chat.sessions.untitled")}
                  </Typography>
                  <IconButton
                    className="chat-del"
                    size="small"
                    onClick={(e) => { e.stopPropagation(); handleDeleteSession(s.uuid); }}
                    sx={{ opacity: 0, p: 0.25, color: tokens.text.tertiary, "&:hover": { color: tokens.accent.red }, transition: "opacity 120ms ease" }}
                  >
                    <DeleteOutline sx={{ fontSize: 14 }} />
                  </IconButton>
                </Box>
              ))
            )}
          </Box>
        </Box>
      )}
      {/* ── Split area: chat column | divider | workspace panel ── */}
      <Box ref={splitRef} sx={{ flex: 1, display: "flex", minWidth: 0, overflow: "hidden" }}>
      {/* ── Left: Chat column ── */}
      <Box
        data-testid="chat-column"
        onDragEnter={handleDragEnter}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        sx={{
          width: { xs: "100%", md: panelOpen ? `${chatPct}%` : "100%" },
          minWidth: { md: 320 },
          display: "flex",
          flexDirection: "column",
          bgcolor: tokens.bg.surface,
          flexShrink: 0,
          // Anchors the drop overlay below; the column had no positioning context of its own.
          position: "relative",
        }}
      >
        {/* Drop overlay. pointerEvents stays off so it never swallows a click, and the drag
            events it would otherwise intercept keep reaching the column underneath. */}
        <Box
          data-testid="chat-drop-overlay"
          sx={{
            position: "absolute", inset: 0, zIndex: 5,
            pointerEvents: "none",
            display: "flex", alignItems: "center", justifyContent: "center",
            opacity: dragActive ? 1 : 0,
            visibility: dragActive ? "visible" : "hidden",
            transition: "opacity 120ms ease",
            bgcolor: `${tokens.bg.surface}e6`,
            border: `2px dashed ${tokens.primary.main}`,
            borderRadius: tokens.radius.lg,
          }}
        >
          <Box sx={{ textAlign: "center", color: tokens.primary.main }}>
            <AttachFileOutlined sx={{ fontSize: 28 }} />
            <Typography variant="body2" sx={{ mt: 0.5, fontWeight: 600 }}>
              {t("chat.attachments.dropTitle")}
            </Typography>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
              {t("chat.attachments.dropHint")}
            </Typography>
          </Box>
        </Box>
        {/* Header */}
        <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          <Tooltip title={t("chat.sessions.toggle")}>
            <IconButton
              size="small"
              onClick={() => setRailOpen(o => !o)}
              sx={{ display: { xs: "none", md: "inline-flex" }, color: tokens.text.secondary, mr: 0.25 }}
            >
              <ViewSidebarOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Box
            sx={{
              width: 28, height: 28, borderRadius: "50%",
              background: `linear-gradient(135deg, ${tokens.primary.main} 0%, ${tokens.primary.dark} 100%)`,
              display: "flex", alignItems: "center", justifyContent: "center",
              boxShadow: `0 0 14px ${tokens.primary.glow}`,
            }}
          >
            <AutoAwesome sx={{ fontSize: 14, color: "#fff" }} />
          </Box>
          <Box sx={{ flex: 1 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.25 }}>
              <Typography variant="subtitle2" fontWeight={700} sx={{ fontSize: "0.875rem", color: tokens.text.primary, lineHeight: 1.2 }}>
                {t("chat.header.title")}
              </Typography>
              <HelpHint topic="chat" size={13} />
            </Box>
            <Typography variant="caption" sx={{ color: tokens.accent.green, fontSize: "0.68rem" }}>
              {t("chat.header.status", { space: activeSpace?.name ?? t("chat.header.noSpace") })}
            </Typography>
          </Box>
          <SkillsPanel activeSkillUuids={activeSkillUuids} onChange={handleSkillsChange} />
          <Tooltip title={panelOpen ? t("chat.panel.hide") : t("chat.panel.show")}>
            <IconButton
              size="small"
              data-testid="chat-panel-toggle"
              onClick={() => setPanelOpen(o => !o)}
              sx={{ display: { xs: "none", md: "inline-flex" }, color: panelOpen ? tokens.text.secondary : tokens.primary.light }}
            >
              <SpaceDashboardOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
        </Box>

        {/* Messages */}
        <Box ref={scrollRef} sx={{ flex: 1, overflow: "auto", px: 2, py: 1.5 }}>
          {/* Fresh session: greet the user instead of showing an empty transcript */}
          {messages.length === 0 && !streaming && !sending && <ChatGreeting username={username} />}
          {messages.map((msg) => (
            <MessageBubble key={msg.id} msg={msg} onFollowUp={sendMessage} onAssetClick={openAsset} />
          ))}
          {/* In-flight assistant message driven by the event stream */}
          {streaming && (
            <MessageBubble
              msg={streaming.msg}
              onFollowUp={sendMessage}
              onAssetClick={openAsset}
              reasoningStreaming={streaming.phase === "reasoning"}
            />
          )}
          {sending && !streaming && (
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 2 }}>
              <Box sx={{ width: 28, height: 28, borderRadius: "50%", background: `linear-gradient(135deg, ${tokens.primary.main}, ${tokens.primary.dark})`, display: "flex", alignItems: "center", justifyContent: "center" }}>
                <AutoAwesome sx={{ fontSize: 14, color: "#fff" }} />
              </Box>
              <Paper elevation={0} sx={{ px: 2, py: 1.25, bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.subtle}`, borderRadius: tokens.radius.lg, display: "flex", gap: 0.5, alignItems: "center" }}>
                {[0, 1, 2].map(i => (
                  <Box key={i} sx={{ width: 5, height: 5, borderRadius: "50%", bgcolor: tokens.primary.main, animation: "pulse 1.2s ease-in-out infinite", animationDelay: `${i * 0.2}s`, "@keyframes pulse": { "0%,100%": { opacity: 0.3 }, "50%": { opacity: 1 } } }} />
                ))}
              </Paper>
            </Box>
          )}
        </Box>

        {/* Input */}
        <Box sx={{ px: 2, py: 1.5, borderTop: `1px solid ${tokens.border.subtle}` }}>
          <Paper
            elevation={0}
            sx={{ bgcolor: tokens.bg.elevated, border: `1px solid ${tokens.border.default}`, borderRadius: tokens.radius.lg, overflow: "hidden", "&:focus-within": { borderColor: tokens.primary.main, boxShadow: `0 0 0 2px ${tokens.primary.glow}` }, transition: "all 160ms ease" }}
          >
            <ChatAttachmentChips
              items={attachments.items}
              onRemove={item => attachments.remove(item)}
              onSave={item => {
                attachments.save(item);
                showToast(t("chat.attachments.saved", { name: item.filename }), "success");
              }}
            />
            <input
              ref={fileInputRef}
              type="file"
              multiple
              hidden
              data-testid="chat-attachment-input"
              onChange={e => {
                attachments.add(Array.from(e.target.files ?? []));
                // Reset so picking the same file again still fires a change event.
                e.target.value = "";
              }}
            />
            <TextField
              multiline
              maxRows={5}
              fullWidth
              placeholder={t("chat.input.placeholder")}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={sending}
              variant="standard"
              InputProps={{
                disableUnderline: true,
                sx: { px: 2, pt: 1.25, pb: 0.5, fontSize: "0.875rem", lineHeight: 1.6 },
                startAdornment: (
                  <InputAdornment position="start" sx={{ pb: 0.5, alignSelf: input.includes("\n") ? "flex-end" : "center" }}>
                    <Tooltip title={t("chat.attachments.attach")}>
                      <span>
                        <IconButton
                          size="small"
                          aria-label={t("chat.attachments.attach")}
                          data-testid="chat-attach-button"
                          disabled={sending || attachments.busy}
                          onClick={() => fileInputRef.current?.click()}
                          sx={{ width: 26, height: 26, color: tokens.text.tertiary, "&:hover": { color: tokens.primary.main } }}
                        >
                          <AttachFileOutlined sx={{ fontSize: 16 }} />
                        </IconButton>
                      </span>
                    </Tooltip>
                  </InputAdornment>
                ),
                endAdornment: (
                  <InputAdornment position="end" sx={{ pb: 0.5, pr: 0.5, alignSelf: input.includes("\n") ? "flex-end" : "center" }}>
                    {sending ? (
                      <Tooltip title={t("chat.streaming.stop")}>
                        <IconButton
                          size="small"
                          data-testid="chat-stop-button"
                          onClick={stopStreaming}
                          sx={{
                            color: tokens.accent.red,
                            border: `1px solid ${tokens.accent.red}`,
                            width: 28, height: 28,
                            "&:hover": { bgcolor: `${tokens.accent.red}14` },
                          }}
                        >
                          <StopCircleOutlined sx={{ fontSize: 16 }} />
                        </IconButton>
                      </Tooltip>
                    ) : (
                      <IconButton
                        size="small"
                        onClick={() => sendMessage(input)}
                        disabled={!input.trim()}
                        sx={{
                          bgcolor: input.trim() ? tokens.primary.main : "transparent",
                          color: input.trim() ? "#fff" : tokens.text.tertiary,
                          border: `1px solid ${input.trim() ? tokens.primary.main : tokens.border.default}`,
                          width: 28, height: 28,
                          "&:hover": { bgcolor: input.trim() ? tokens.primary.light : tokens.bg.hover },
                          "&.Mui-disabled": { opacity: 0.4 },
                        }}
                      >
                        <Send sx={{ fontSize: 14 }} />
                      </IconButton>
                    )}
                  </InputAdornment>
                ),
              }}
            />
          </Paper>
          <Typography variant="caption" sx={{ mt: 0.75, display: "block", color: tokens.text.tertiary, fontSize: "0.68rem", textAlign: "center" }}>
            {t("chat.input.helper")}
          </Typography>
        </Box>
      </Box>

      {/* ── Drag divider ── */}
      {panelOpen && (
      <Box
        data-testid="chat-split-divider"
        onMouseDown={handleDividerMouseDown}
        onDoubleClick={() => setChatPct(SPLIT_DEFAULT_PCT)}
        sx={{
          display: { xs: "none", md: "flex" },
          width: 6,
          cursor: "col-resize",
          borderLeft: `1px solid ${tokens.border.subtle}`,
          borderRight: `1px solid ${tokens.border.subtle}`,
          bgcolor: "transparent",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
          alignSelf: "stretch",
          "&:hover": { bgcolor: tokens.primary.subtle },
          transition: "background-color 120ms ease",
          userSelect: "none",
          zIndex: 2,
        }}
      >
        <Box
          sx={{
            width: 2, height: 36, borderRadius: 1,
            bgcolor: tokens.border.strong,
            opacity: 0.5,
            transition: "opacity 120ms ease",
          }}
        />
      </Box>
      )}

      {/* ── Right: Workspace panel ── */}
      {panelOpen && (
      <Box data-testid="chat-workspace-panel" sx={{ flex: 1, minWidth: 0, overflow: "auto", display: { xs: "none", md: "flex" }, flexDirection: "column", bgcolor: tokens.bg.base }}>
        {/* Workspace tab bar */}
        <Box sx={{ px: 2.5, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          {/* `results` is only a tab once there is a result set; it disappears with the conversation
              it belongs to rather than sitting there empty. */}
          {((results ? ["results", "overview", "assets"] : ["overview", "assets"]) as WorkspaceMode[]).map((mode) => (
            <Chip
              key={mode}
              label={mode === "results" ? t("chat.tab.results", { n: results?.items?.length ?? 0 }) : t(`chat.tab.${mode}`)}
              size="small"
              data-testid={`chat-tab-${mode}`}
              onClick={() => { setWorkspaceMode(mode); setSelectedAssetId(null); }}
              sx={{
                bgcolor: workspaceMode === mode ? tokens.primary.subtle : "transparent",
                border: `1px solid ${workspaceMode === mode ? tokens.primary.main : tokens.border.subtle}`,
                color: workspaceMode === mode ? tokens.primary.light : tokens.text.secondary,
                fontWeight: workspaceMode === mode ? 600 : 400,
                cursor: "pointer",
              }}
            />
          ))}
          <Box sx={{ flex: 1 }} />
          <Tooltip title={t("chat.panel.hide")}>
            <IconButton
              size="small"
              data-testid="chat-panel-collapse"
              onClick={() => setPanelOpen(false)}
              sx={{ color: tokens.text.tertiary, "&:hover": { color: tokens.text.primary } }}
            >
              <KeyboardDoubleArrowRight sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
        </Box>
        <Box sx={{ flex: 1, overflow: "auto" }}>
          <WorkspacePanel
            mode={workspaceMode}
            results={results}
            selectedAssetId={selectedAssetId}
            selectedAssetStart={selectedAssetStart}
            onSelectAsset={openAsset}
            onClearAsset={() => setSelectedAssetId(null)}
          />
        </Box>
      </Box>
      )}
      </Box>
    </Box>
  );
}
