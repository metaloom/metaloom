import React, { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Box, Typography, TextField, IconButton, Chip, Avatar, Tooltip,
  CircularProgress, Paper, Divider, InputAdornment, Stack,
} from "@mui/material";
import {
  Send, AutoAwesome, CheckCircleOutline, ErrorOutline,
  PlayCircleOutline, ImageOutlined, TaskAltOutlined,
  AccountTreeOutlined, CollectionsOutlined, AccessTimeOutlined,
  ArrowForwardIos,
} from "@mui/icons-material";
import { tokens } from "../../theme";
import { ChatMessage, ChatReference } from "../../types";
import { mockChatService } from "../../mock/services";
import { useProject } from "../../context/ProjectContext";
import AssetBrowser from "../assets/AssetBrowser";
import { ASSETS, COLLECTIONS, TASKS, PIPELINES } from "../../mock/data";

// ── Reference chip renderer ───────────────────────────────────────────────
function RefChip({ ref: r }: { ref: ChatReference }) {
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
    if (r.type === "asset") navigate(`/assets/${r.id}`);
    else if (r.type === "pipeline") navigate("/pipelines");
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
function MessageBubble({ msg, onFollowUp }: { msg: ChatMessage; onFollowUp: (text: string) => void }) {
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
        <Paper
          elevation={0}
          sx={{
            px: 1.75, py: 1.25,
            bgcolor: isUser ? tokens.primary.subtle : tokens.bg.elevated,
            border: `1px solid ${isUser ? tokens.primary.glow : tokens.border.subtle}`,
            borderRadius: isUser ? `${tokens.radius.lg} ${tokens.radius.md} ${tokens.radius.sm} ${tokens.radius.lg}` : `${tokens.radius.md} ${tokens.radius.lg} ${tokens.radius.lg} ${tokens.radius.sm}`,
          }}
        >
          <Typography
            variant="body2"
            sx={{ color: tokens.text.primary, lineHeight: 1.65, whiteSpace: "pre-wrap", fontSize: "0.865rem" }}
            dangerouslySetInnerHTML={{
              __html: msg.content
                .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
                .replace(/\n/g, '<br/>'),
            }}
          />
        </Paper>

        {/* Actions */}
        {msg.actions && msg.actions.length > 0 && (
          <Box sx={{ px: 1, display: "flex", flexDirection: "column", gap: 0.25 }}>
            {msg.actions.map((a) => <ActionRow key={a.id} action={a} />)}
          </Box>
        )}

        {/* References */}
        {msg.references && msg.references.length > 0 && (
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, px: 0.5 }}>
            {msg.references.map((r) => <RefChip key={r.id + r.type} ref={r} />)}
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
function WorkspacePanel({ mode }: { mode: "assets" | "overview" }) {
  if (mode === "assets") return <AssetBrowser embedded />;

  return (
    <Box sx={{ p: 2.5, display: "flex", flexDirection: "column", gap: 2 }}>
      {/* Recent assets */}
      <SectionCard title="Recent Assets" icon={<ImageOutlined sx={{ fontSize: 14 }} />}>
        {ASSETS.slice(0, 4).map((a) => (
          <AssetRow key={a.id} asset={a} />
        ))}
      </SectionCard>

      <SectionCard title="Active Tasks" icon={<TaskAltOutlined sx={{ fontSize: 14 }} />}>
        {TASKS.filter(t => t.status !== "done").slice(0, 4).map((t) => (
          <TaskRow key={t.id} task={t} />
        ))}
      </SectionCard>

      <SectionCard title="Collections" icon={<CollectionsOutlined sx={{ fontSize: 14 }} />}>
        {COLLECTIONS.slice(0, 3).map((c) => (
          <CollectionRow key={c.id} collection={c} />
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

function AssetRow({ asset }: { asset: typeof ASSETS[0] }) {
  const navigate = useNavigate();
  const statusColor = asset.status === "ready" ? tokens.accent.green : asset.status === "failed" ? tokens.accent.red : tokens.accent.amber;
  return (
    <Box
      onClick={() => navigate(`/assets/${asset.id}`)}
      sx={{
        display: "flex", alignItems: "center", gap: 1.5, px: 1, py: 0.75,
        borderRadius: tokens.radius.md, cursor: "pointer",
        "&:hover": { bgcolor: tokens.bg.hover },
      }}
    >
      <Box sx={{ width: 36, height: 24, borderRadius: tokens.radius.sm, overflow: "hidden", flexShrink: 0, bgcolor: tokens.bg.overlay }}>
        <img src={asset.thumbnailUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
      </Box>
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Typography variant="caption" fontWeight={500} color="text.primary" noWrap display="block" sx={{ fontSize: "0.78rem" }}>
          {asset.name}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {asset.type} · {asset.libraryId}
        </Typography>
      </Box>
      <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: statusColor, flexShrink: 0 }} />
    </Box>
  );
}

function TaskRow({ task }: { task: typeof TASKS[0] }) {
  const priorityColor: Record<string, string> = { critical: tokens.accent.red, high: tokens.accent.amber, medium: tokens.accent.blue, low: tokens.text.tertiary };
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 1, py: 0.6, borderRadius: tokens.radius.md, "&:hover": { bgcolor: tokens.bg.hover }, cursor: "pointer" }}>
      <Box sx={{ width: 3, height: 18, borderRadius: 2, bgcolor: priorityColor[task.priority] ?? tokens.text.tertiary, flexShrink: 0 }} />
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Typography variant="caption" fontWeight={500} color="text.primary" noWrap display="block" sx={{ fontSize: "0.78rem" }}>
          {task.title}
        </Typography>
        <Chip label={task.status.replace("_", " ")} size="small" sx={{ height: 16, fontSize: "0.65rem", mt: 0.25 }} />
      </Box>
    </Box>
  );
}

function CollectionRow({ collection }: { collection: typeof COLLECTIONS[0] }) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 1, py: 0.6, borderRadius: tokens.radius.md, "&:hover": { bgcolor: tokens.bg.hover }, cursor: "pointer" }}>
      <Box sx={{ width: 8, height: 8, borderRadius: "50%", bgcolor: collection.color, flexShrink: 0 }} />
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <Typography variant="caption" fontWeight={500} color="text.primary" noWrap display="block" sx={{ fontSize: "0.78rem" }}>
          {collection.name}
        </Typography>
        <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.7rem" }}>
          {collection.assetIds.length} assets
        </Typography>
      </Box>
    </Box>
  );
}

// ── Main Chat Workspace ───────────────────────────────────────────────────
export default function ChatWorkspace() {
  const { activeProject } = useProject();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [workspaceMode, setWorkspaceMode] = useState<"overview" | "assets">("overview");
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    mockChatService.getHistory().then(setMessages);
  }, []);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages]);

  const sendMessage = async (text: string) => {
    if (!text.trim() || sending) return;
    const userMsg: ChatMessage = {
      id: `msg_usr_${Date.now()}`,
      role: "user",
      content: text.trim(),
      createdAt: new Date().toISOString(),
    };
    setMessages(prev => [...prev, userMsg]);
    setInput("");
    setSending(true);

    // Drive workspace from chat
    if (text.toLowerCase().includes("asset") || text.toLowerCase().includes("show")) {
      setWorkspaceMode("assets");
    }

    try {
      const response = await mockChatService.sendMessage(text, activeProject?.id ?? "");
      setMessages(prev => [...prev, response]);
    } finally {
      setSending(false);
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
      {/* ── Left: Chat column ── */}
      <Box
        sx={{
          width: { xs: "100%", md: 440 },
          minWidth: { md: 380 },
          maxWidth: { md: 500 },
          display: "flex",
          flexDirection: "column",
          borderRight: `1px solid ${tokens.border.subtle}`,
          bgcolor: tokens.bg.surface,
        }}
      >
        {/* Header */}
        <Box sx={{ px: 2.5, py: 1.75, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
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
          <Box>
            <Typography variant="subtitle2" fontWeight={700} sx={{ fontSize: "0.875rem", color: tokens.text.primary, lineHeight: 1.2 }}>
              Loom Agent
            </Typography>
            <Typography variant="caption" sx={{ color: tokens.accent.green, fontSize: "0.68rem" }}>
              ● online · {activeProject?.name ?? "No project"}
            </Typography>
          </Box>
        </Box>

        {/* Messages */}
        <Box ref={scrollRef} sx={{ flex: 1, overflow: "auto", px: 2, py: 1.5 }}>
          {messages.map((msg) => (
            <MessageBubble key={msg.id} msg={msg} onFollowUp={sendMessage} />
          ))}
          {sending && (
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
              placeholder="Ask about assets, collections, tasks, pipelines…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={sending}
              variant="standard"
              InputProps={{
                disableUnderline: true,
                sx: { px: 2, pt: 1.25, pb: 0.5, fontSize: "0.875rem", lineHeight: 1.6 },
                endAdornment: (
                  <InputAdornment position="end" sx={{ pb: 0.5, pr: 0.5, alignSelf: "flex-end" }}>
                    <IconButton
                      size="small"
                      onClick={() => sendMessage(input)}
                      disabled={!input.trim() || sending}
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
                  </InputAdornment>
                ),
              }}
            />
          </Paper>
          <Typography variant="caption" sx={{ mt: 0.75, display: "block", color: tokens.text.tertiary, fontSize: "0.68rem", textAlign: "center" }}>
            Enter to send · Shift+Enter for new line
          </Typography>
        </Box>
      </Box>

      {/* ── Right: Workspace panel ── */}
      <Box sx={{ flex: 1, overflow: "auto", display: { xs: "none", md: "flex" }, flexDirection: "column", bgcolor: tokens.bg.base }}>
        {/* Workspace tab bar */}
        <Box sx={{ px: 2.5, py: 1.25, borderBottom: `1px solid ${tokens.border.subtle}`, display: "flex", alignItems: "center", gap: 1 }}>
          {(["overview", "assets"] as const).map((mode) => (
            <Chip
              key={mode}
              label={mode.charAt(0).toUpperCase() + mode.slice(1)}
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
          <WorkspacePanel mode={workspaceMode} />
        </Box>
      </Box>
    </Box>
  );
}
