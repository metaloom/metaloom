import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import { Avatar, Box, Button, Chip, IconButton, TextField, Tooltip, Typography } from "@mui/material";
import { AccessTimeOutlined, EditOutlined, DeleteOutlineOutlined, ReplyOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Comment } from "../../types";
import { formatDuration, userName } from "./helpers";
import { CommentReactionBar } from "./CommentReactionBar";

export function CommentItem({
  comment,
  highlighted,
  onTimeClick,
  onHover,
  currentUserUuid,
  token,
  editing,
  onStartEdit,
  onCancelEdit,
  onEdit,
  onDelete,
  onReply,
  isReply,
}: {
  comment: Comment;
  highlighted: boolean;
  onTimeClick?: (t: number) => void;
  onHover?: (id: string | null) => void;
  currentUserUuid?: string | null;
  token?: string | null;
  editing?: boolean;
  onStartEdit?: (id: string) => void;
  onCancelEdit?: () => void;
  onEdit?: (id: string, text: string) => void;
  onDelete?: (id: string) => void;
  /** Present when this comment can be replied to. Replies themselves get no reply action — the thread is one level deep. */
  onReply?: (id: string) => void;
  /** Renders the comment indented beneath its parent. */
  isReply?: boolean;
}) {
  const { t } = useTranslation("translation", { keyPrefix: "assetDetail" });
  const [draft, setDraft] = useState(comment.text);
  const isAuthor = !!currentUserUuid && comment.authorId === currentUserUuid;
  const canEdit = isAuthor && !!onEdit && !!onDelete;

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
        "&:hover .comment-actions": { opacity: 1 },
        ...(isReply ? { ml: 4, borderLeft: `2px solid ${tokens.border.subtle}`, borderRadius: 0, pl: 1.5 } : {}),
      }}
      data-testid={isReply ? "comment-reply-item" : "comment-item"}
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
          {!editing && (onReply || canEdit) && (
            <Box className="comment-actions" sx={{ display: "flex", gap: 0.25, opacity: 0, transition: "opacity 120ms ease" }}>
              {onReply && (
                <Tooltip title={t("comment.reply")}>
                  <IconButton
                    size="small"
                    aria-label={t("comment.reply")}
                    data-testid="comment-reply"
                    onClick={() => onReply(comment.id)}
                    sx={{ p: 0.25 }}
                  >
                    <ReplyOutlined sx={{ fontSize: 15 }} />
                  </IconButton>
                </Tooltip>
              )}
              {canEdit && (
              <>
              <Tooltip title={t("comment.edit")}>
                <IconButton
                  size="small"
                  aria-label={t("comment.edit")}
                  data-testid="comment-edit"
                  onClick={() => { setDraft(comment.text); onStartEdit?.(comment.id); }}
                  sx={{ p: 0.25 }}
                >
                  <EditOutlined sx={{ fontSize: 15 }} />
                </IconButton>
              </Tooltip>
              <Tooltip title={t("comment.delete")}>
                <IconButton
                  size="small"
                  aria-label={t("comment.delete")}
                  data-testid="comment-delete"
                  onClick={() => onDelete?.(comment.id)}
                  sx={{ p: 0.25, color: tokens.accent.red }}
                >
                  <DeleteOutlineOutlined sx={{ fontSize: 15 }} />
                </IconButton>
              </Tooltip>
              </>
              )}
            </Box>
          )}
        </Box>
        {comment.title && (
          <Typography variant="body2" fontWeight={600} sx={{ fontSize: "0.8rem", color: tokens.text.primary, mb: 0.25 }}>{comment.title}</Typography>
        )}
        {editing ? (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75, mt: 0.5 }}>
            <TextField
              fullWidth
              multiline
              maxRows={6}
              size="small"
              autoFocus
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              inputProps={{ "aria-label": t("comment.edit"), "data-testid": "comment-edit-field" }}
            />
            <Box sx={{ display: "flex", gap: 0.75, justifyContent: "flex-end" }}>
              <Button size="small" data-testid="comment-cancel" onClick={() => onCancelEdit?.()}>{t("comment.cancel")}</Button>
              <Button size="small" variant="contained" data-testid="comment-save" disabled={!draft.trim()} onClick={() => onEdit?.(comment.id, draft)}>{t("comment.save")}</Button>
            </Box>
          </Box>
        ) : (
          <>
            <Typography variant="body2" sx={{ fontSize: "0.82rem", color: tokens.text.secondary, lineHeight: 1.55 }}>
              {comment.text}
            </Typography>
            <CommentReactionBar commentUuid={comment.id} token={token} currentUserUuid={currentUserUuid} />
          </>
        )}
      </Box>
    </Box>
  );
}
