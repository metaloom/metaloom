import React, { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, TextField, IconButton, Chip, Avatar, Tooltip,
  CircularProgress, Paper, Divider, InputAdornment, Stack,
} from "@mui/material";
import {
  Send, AutoAwesome, CheckCircleOutline, ErrorOutline,
  PlayCircleOutline, ImageOutlined, TaskAltOutlined,
  AccountTreeOutlined, CollectionsOutlined, AccessTimeOutlined,
  ArrowForwardIos, DragIndicator, StopCircleOutlined,
  Add, ChatBubbleOutline, DeleteOutline, ViewSidebarOutlined,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { AgentAction, ChatMessage, ChatReference } from "../../types";
import {
  listChats, loadChat, createChat, updateChat, deleteChat, ChatResponse,
  toChatMessage, toChatReference, BackendChatReference,
} from "../../api/chat";
import {
  streamChatMessage, cancelChatStream, AgentBusyError,
  AgentDeltaEvent, AgentToolStartEvent, AgentToolEndEvent,
  AgentMessageEndEvent, AgentTitleEvent, AgentErrorEvent, AgentEndEvent,
} from "../../api/agent";
import MarkdownContent from "./MarkdownContent";
import ReasoningSection from "./ReasoningSection";
import SkillsPanel from "./SkillsPanel";
import { useToast } from "../../context/ToastContext";
import { useSpace } from "../../context/SpaceContext";
import { useTranslation } from "react-i18next";
import AssetBrowser from "../assets/AssetBrowser";
import { useAuth } from "../../context/AuthContext";
import { listAssets, loadAsset as apiLoadAsset, AssetResponse } from "../../api/assets";
import { listCollections, CollectionResponse } from "../../api/collections";
import { listTasks, TaskResponse } from "../../api/tasks";

// ── Reference chip renderer ───────────────────────────────────────────────
function RefChip({ chatRef: r, onAssetClick }: { chatRef: ChatReference; onAssetClick?: (id: string) => void }) {
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
  onAssetClick?: (id: string) => void;
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
function WorkspacePanel({ mode, selectedAssetId, onClearAsset }: { mode: "assets" | "overview"; selectedAssetId?: string | null; onClearAsset?: () => void }) {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { token } = useAuth();
  const [assets, setAssets] = useState<AssetResponse[]>([]);
  const [tasks, setTasks] = useState<TaskResponse[]>([]);
  const [collections, setCollections] = useState<CollectionResponse[]>([]);
  const [selectedAsset, setSelectedAsset] = useState<AssetResponse | null>(null);

  useEffect(() => {
    if (!token) return;
    listAssets(token).then(r => setAssets(r.data ?? [])).catch(() => {});
    listTasks(token).then(r => setTasks(r.data ?? [])).catch(() => {});
    listCollections(token).then(r => setCollections(r.data ?? [])).catch(() => {});
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
        <Box sx={{ px: 2, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          <Box sx={{ width: 40, height: 28, borderRadius: tokens.radius.sm, overflow: "hidden", flexShrink: 0, bgcolor: tokens.bg.overlay }} />
          <Box sx={{ flex: 1, overflow: "hidden" }}>
            <Typography variant="body2" fontWeight={600} noWrap sx={{ fontSize: "0.82rem" }}>{filename}</Typography>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>{mime}</Typography>
          </Box>
          <Chip
            label={t("chat.panel.open")}
            size="small"
            onClick={() => navigate(`/assets/${selectedAsset.uuid}`)}
            sx={{ cursor: "pointer", bgcolor: tokens.primary.subtle, border: `1px solid ${tokens.primary.main}`, color: tokens.primary.light, fontWeight: 600, fontSize: "0.72rem" }}
          />
          <IconButton size="small" onClick={onClearAsset} sx={{ ml: 0.5 }}>
            <ArrowForwardIos sx={{ fontSize: 10, transform: "rotate(180deg)" }} />
          </IconButton>
        </Box>
        <Box sx={{ flex: 1, overflow: "auto", display: "flex", flexDirection: "column" }}>
          <Box sx={{ p: 2, display: "flex", flexDirection: "column", gap: 1.5 }}>
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

// ── Streaming state of the in-flight assistant message ────────────────────
type StreamPhase = "idle" | "reasoning" | "answering" | "tool";

interface StreamingState {
  phase: StreamPhase;
  msg: ChatMessage;
}

// ── Main Chat Workspace ───────────────────────────────────────────────────
export default function ChatWorkspace() {
  const { activeSpace } = useSpace();
  const { token } = useAuth();
  const { t } = useTranslation();
  const { showToast } = useToast();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [streaming, setStreaming] = useState<StreamingState | null>(null);
  const [activeSkillUuids, setActiveSkillUuids] = useState<string[]>([]);
  const [workspaceMode, setWorkspaceMode] = useState<"overview" | "assets">("overview");
  const [chatWidth, setChatWidth] = useState(440);
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null);
  const [sessions, setSessions] = useState<ChatResponse[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [railOpen, setRailOpen] = useState(true);
  const scrollRef = useRef<HTMLDivElement>(null);
  const isDragging = useRef(false);
  const abortRef = useRef<AbortController | null>(null);
  const activeChatRef = useRef<string | null>(null);

  // Abort a running stream on unmount
  useEffect(() => () => abortRef.current?.abort(), []);

  const handleDividerMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isDragging.current = true;
    const startX = e.clientX;
    const startW = chatWidth;
    const onMove = (ev: MouseEvent) => {
      if (!isDragging.current) return;
      const delta = ev.clientX - startX;
      setChatWidth(Math.max(200, Math.min(1200, startW + delta)));
    };
    const onUp = () => {
      isDragging.current = false;
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, [chatWidth]);

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
  const newChat = () => {
    abortRef.current?.abort();
    setSessionId(null);
    setMessages([]);
    setActiveSkillUuids([]);
  };

  const loadSession = async (uuid: string) => {
    if (!token) return;
    abortRef.current?.abort();
    try {
      const res = await loadChat(token, uuid);
      setMessages(res.messages ?? []);
      setSessionId(uuid);
      // Restore the per-session skill toggles from the chat meta
      const metaSkills = res.meta?.activeSkillUuids;
      setActiveSkillUuids(Array.isArray(metaSkills) ? (metaSkills as string[]) : []);
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

    // Drive workspace from chat
    if (trimmed.toLowerCase().includes("asset") || trimmed.toLowerCase().includes("show")) {
      setWorkspaceMode("assets");
    }

    try {
      // The backend persists the transcript onto the chat row, so the session
      // must exist before streaming. Created lazily to avoid empty sessions.
      let chatUuid = sessionId;
      if (!chatUuid) {
        const title = trimmed.slice(0, 40) || t("chat.sessions.newChat");
        const created = await createChat(token, { title, messages: [] });
        chatUuid = created.uuid;
        setSessionId(created.uuid);
        setSessions(prev => [created, ...prev]);
      }
      activeChatRef.current = chatUuid;

      // Accumulated state of the in-flight assistant message
      const acc = {
        phase: "idle" as StreamPhase,
        content: "",
        reasoning: "",
        actions: [] as AgentAction[],
        references: [] as ChatReference[],
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
          </Box>
          <Box sx={{ flex: 1, overflow: "auto", py: 0.5 }}>
            {sessions.length === 0 ? (
              <Typography variant="caption" sx={{ px: 2, py: 1.5, display: "block", color: tokens.text.tertiary, fontSize: "0.72rem" }}>
                {t("chat.sessions.empty")}
              </Typography>
            ) : (
              sessions.map((s) => (
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
      {/* ── Left: Chat column ── */}
      <Box
        sx={{
          width: { xs: "100%", md: chatWidth },
          minWidth: { md: 280 },
          maxWidth: { md: 700 },
          display: "flex",
          flexDirection: "column",
          bgcolor: tokens.bg.surface,
          flexShrink: 0,
        }}
      >
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
            <Typography variant="subtitle2" fontWeight={700} sx={{ fontSize: "0.875rem", color: tokens.text.primary, lineHeight: 1.2 }}>
              {t("chat.header.title")}
            </Typography>
            <Typography variant="caption" sx={{ color: tokens.accent.green, fontSize: "0.68rem" }}>
              {t("chat.header.status", { space: activeSpace?.name ?? t("chat.header.noSpace") })}
            </Typography>
          </Box>
          <SkillsPanel activeSkillUuids={activeSkillUuids} onChange={handleSkillsChange} />
        </Box>

        {/* Messages */}
        <Box ref={scrollRef} sx={{ flex: 1, overflow: "auto", px: 2, py: 1.5 }}>
          {messages.map((msg) => (
            <MessageBubble key={msg.id} msg={msg} onFollowUp={sendMessage} onAssetClick={(id) => setSelectedAssetId(id)} />
          ))}
          {/* In-flight assistant message driven by the event stream */}
          {streaming && (
            <MessageBubble
              msg={streaming.msg}
              onFollowUp={sendMessage}
              onAssetClick={(id) => setSelectedAssetId(id)}
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
      <Box
        onMouseDown={handleDividerMouseDown}
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

      {/* ── Right: Workspace panel ── */}
      <Box sx={{ flex: 1, overflow: "auto", display: { xs: "none", md: "flex" }, flexDirection: "column", bgcolor: tokens.bg.base }}>
        {/* Workspace tab bar */}
        <Box sx={{ px: 2.5, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          {(["overview", "assets"] as const).map((mode) => (
            <Chip
              key={mode}
              label={t(`chat.tab.${mode}`)}
              size="small"
              onClick={() => setWorkspaceMode(mode)}
              sx={{
                bgcolor: workspaceMode === mode ? tokens.primary.subtle : "transparent",
                border: `1px solid ${workspaceMode === mode ? tokens.primary.main : tokens.border.subtle}`,
                color: workspaceMode === mode ? tokens.primary.light : tokens.text.secondary,
                fontWeight: workspaceMode === mode ? 600 : 400,
                cursor: "pointer",
              }}
            />
          ))}
        </Box>
        <Box sx={{ flex: 1, overflow: "auto" }}>
          <WorkspacePanel mode={workspaceMode} selectedAssetId={selectedAssetId} onClearAsset={() => setSelectedAssetId(null)} />
        </Box>
      </Box>
    </Box>
  );
}
