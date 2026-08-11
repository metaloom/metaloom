import React, { useState } from "react";
import { Box, Button, Chip, Divider, IconButton, TextField, Tooltip, Typography } from "@mui/material";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import PlaceOutlined from "@mui/icons-material/PlaceOutlined";
import CropFreeOutlined from "@mui/icons-material/CropFreeOutlined";
import CropSquareOutlined from "@mui/icons-material/CropSquareOutlined";
import ReplyOutlined from "@mui/icons-material/ReplyOutlined";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import type { ShareAnnotationResponse, ShareCommentResponse, ShareReactionResponse } from "../../api/shares";
import { countReactions, formatTimecode, groupComments } from "./shareExpiry";

/** The reactions a customer can leave, in the order the bar shows them. Sign-off leads. */
const REACTION_TYPES = ["APPROVE", "REJECT", "THUMBSUP", "THUMBSDOWN", "LOVE", "QUESTION"] as const;

/**
 * Glyphs from the standard text repertoire, deliberately not emoji.
 *
 * Emoji need a colour emoji font, which a headless browser, a locked-down corporate desktop and a
 * minimal container image all routinely lack — and a missing one renders as a tofu box, so the
 * chip reads as broken exactly where a customer is being asked to sign something off. These six
 * are in every default font.
 */
const REACTION_GLYPH: Record<string, string> = {
  APPROVE: "✓",
  REJECT: "✕",
  THUMBSUP: "↑",
  THUMBSDOWN: "↓",
  LOVE: "♥",
  QUESTION: "?",
};

export interface ShareFeedbackPanelProps {
  comments: ShareCommentResponse[];
  annotations: ShareAnnotationResponse[];
  reactions: ShareReactionResponse[];
  allowComments: boolean;
  allowReactions: boolean;
  allowAnnotations: boolean;
  /** Playback position, so a new mark can be anchored where the viewer is looking. */
  currentTime?: number;
  onAddComment: (text: string, parentUuid?: string) => Promise<void>;
  onDeleteComment: (uuid: string) => Promise<void>;
  onToggleReaction: (type: string) => Promise<void>;
  onAddTimecodeMark: (text: string) => Promise<void>;
  onDeleteAnnotation: (uuid: string) => Promise<void>;
  onSeek?: (seconds: number) => void;
  /** True while the next drag across the media will draw a box. */
  drawingRegion?: boolean;
  onToggleDrawRegion?: () => void;
}

/**
 * What the customer says back.
 *
 * Everything here is optional per link, and each section simply does not render when the link does
 * not allow it — rather than rendering disabled, which would advertise a capability the owner
 * deliberately withheld.
 */
export default function ShareFeedbackPanel(props: ShareFeedbackPanelProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState("");
  const [replyTo, setReplyTo] = useState<ShareCommentResponse | null>(null);
  const [markDraft, setMarkDraft] = useState("");
  const [busy, setBusy] = useState(false);

  const threads = groupComments(props.comments);
  const reactionCounts = countReactions(props.reactions);

  const submitComment = async () => {
    const text = draft.trim();
    if (!text) return;
    setBusy(true);
    try {
      await props.onAddComment(text, replyTo?.uuid);
      setDraft("");
      setReplyTo(null);
    } finally {
      setBusy(false);
    }
  };

  const submitMark = async () => {
    setBusy(true);
    try {
      await props.onAddTimecodeMark(markDraft.trim());
      setMarkDraft("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box data-testid="share-feedback" sx={{ display: "flex", flexDirection: "column", gap: 2.5 }}>
      {props.allowReactions && (
        <Box>
          <SectionLabel>{t("share.feedback.reactions")}</SectionLabel>
          <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
            {REACTION_TYPES.map((type) => {
              const count = reactionCounts[type] ?? 0;
              return (
                <Chip
                  key={type}
                  data-testid={`share-reaction-${type}`}
                  label={`${REACTION_GLYPH[type]} ${t(`share.reaction.${type}`)}${count ? ` ${count}` : ""}`}
                  size="small"
                  onClick={() => props.onToggleReaction(type)}
                  variant={count ? "filled" : "outlined"}
                  sx={{
                    cursor: "pointer",
                    borderColor: tokens.border.default,
                    bgcolor: count ? tokens.primary.subtle : "transparent",
                    color: count ? tokens.primary.main : tokens.text.secondary,
                  }}
                />
              );
            })}
          </Box>
        </Box>
      )}

      {props.allowAnnotations && (
        <Box>
          <SectionLabel>{t("share.feedback.marks")}</SectionLabel>
          {props.annotations.length === 0 && (
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }}>
              {t("share.feedback.noMarks")}
            </Typography>
          )}
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
            {props.annotations.map((annotation) => (
              <Box
                key={annotation.uuid}
                data-testid="share-annotation"
                sx={{
                  display: "flex",
                  alignItems: "center",
                  gap: 1,
                  px: 1,
                  py: 0.5,
                  borderRadius: tokens.radius.sm,
                  "&:hover": { bgcolor: tokens.bg.hover },
                }}
              >
                {annotation.areaX !== undefined && annotation.areaX !== null ? (
                  <CropSquareOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
                ) : (
                  <PlaceOutlined sx={{ fontSize: 16, color: tokens.primary.main }} />
                )}
                {annotation.timeFrom !== undefined && (
                  <Chip
                    size="small"
                    label={formatTimecode(annotation.timeFrom)}
                    onClick={() => props.onSeek?.(annotation.timeFrom!)}
                    sx={{ cursor: props.onSeek ? "pointer" : "default", height: 20, fontSize: "0.7rem" }}
                  />
                )}
                <Typography variant="body2" sx={{ flex: 1, color: tokens.text.primary }}>
                  {annotation.text || t("share.feedback.untitledMark")}
                </Typography>
                <IconButton size="small" onClick={() => props.onDeleteAnnotation(annotation.uuid)} aria-label={t("share.feedback.delete")}>
                  <DeleteOutlined sx={{ fontSize: 15 }} />
                </IconButton>
              </Box>
            ))}
          </Box>

          {props.onToggleDrawRegion && (
            <Button
              size="small"
              fullWidth
              variant={props.drawingRegion ? "contained" : "outlined"}
              startIcon={<CropFreeOutlined sx={{ fontSize: 16 }} />}
              onClick={props.onToggleDrawRegion}
              data-testid="share-draw-region"
              sx={{ mt: 1 }}
            >
              {props.drawingRegion ? t("share.feedback.drawRegionActive") : t("share.feedback.drawRegion")}
            </Button>
          )}

          {/* A mark is anchored where the player is, so the customer never types a timecode. */}
          <Box sx={{ display: "flex", gap: 1, mt: 1 }}>
            <TextField
              size="small"
              fullWidth
              value={markDraft}
              onChange={(e) => setMarkDraft(e.target.value)}
              placeholder={t("share.feedback.markPlaceholder", { time: formatTimecode(props.currentTime ?? 0) })}
              inputProps={{ "data-testid": "share-mark-input" }}
            />
            <Button size="small" variant="outlined" disabled={busy} onClick={submitMark} data-testid="share-mark-submit">
              {t("share.feedback.mark")}
            </Button>
          </Box>
        </Box>
      )}

      {props.allowComments && (
        <Box>
          <SectionLabel>{t("share.feedback.comments")}</SectionLabel>
          {threads.length === 0 && (
            <Typography variant="caption" sx={{ color: tokens.text.tertiary }} data-testid="share-comments-empty">
              {t("share.feedback.noComments")}
            </Typography>
          )}
          <Box sx={{ display: "flex", flexDirection: "column", gap: 1.5 }}>
            {threads.map(({ root, replies }) => (
              <Box key={root.uuid}>
                <CommentRow comment={root} onReply={() => setReplyTo(root)} onDelete={() => props.onDeleteComment(root.uuid)} />
                {replies.length > 0 && (
                  <Box sx={{ pl: 3, mt: 0.5, borderLeft: `2px solid ${tokens.border.subtle}` }}>
                    {replies.map((reply) => (
                      <CommentRow key={reply.uuid} comment={reply} onDelete={() => props.onDeleteComment(reply.uuid)} />
                    ))}
                  </Box>
                )}
              </Box>
            ))}
          </Box>

          <Divider sx={{ my: 1.5, borderColor: tokens.border.subtle }} />

          {replyTo && (
            <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
              <Typography variant="caption" sx={{ color: tokens.text.secondary }}>
                {t("share.feedback.replyingTo", { name: replyTo.authorName })}
              </Typography>
              <Button size="small" onClick={() => setReplyTo(null)} sx={{ minWidth: 0, p: 0, fontSize: "0.7rem" }}>
                {t("share.feedback.cancelReply")}
              </Button>
            </Box>
          )}

          <Box sx={{ display: "flex", gap: 1 }}>
            <TextField
              size="small"
              fullWidth
              multiline
              maxRows={4}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder={t("share.feedback.commentPlaceholder")}
              inputProps={{ "data-testid": "share-comment-input", maxLength: 8000 }}
            />
            <Button
              size="small"
              variant="contained"
              disabled={busy || !draft.trim()}
              onClick={submitComment}
              data-testid="share-comment-submit"
              sx={{ alignSelf: "flex-end" }}
            >
              {t("share.feedback.send")}
            </Button>
          </Box>
        </Box>
      )}
    </Box>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <Typography
      sx={{
        fontSize: "0.7rem",
        fontWeight: 700,
        letterSpacing: "0.06em",
        textTransform: "uppercase",
        color: tokens.text.tertiary,
        mb: 1,
      }}
    >
      {children}
    </Typography>
  );
}

function CommentRow({
  comment,
  onReply,
  onDelete,
}: {
  comment: ShareCommentResponse;
  onReply?: () => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Box
      data-testid="share-comment"
      sx={{
        display: "flex",
        gap: 1,
        alignItems: "flex-start",
        px: 1,
        py: 0.75,
        borderRadius: tokens.radius.sm,
        "&:hover .share-comment-actions": { opacity: 1 },
      }}
    >
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontSize: "0.72rem", fontWeight: 700, color: tokens.text.secondary }}>
          {comment.authorName}
        </Typography>
        <Typography variant="body2" sx={{ color: tokens.text.primary, whiteSpace: "pre-wrap", wordBreak: "break-word" }}>
          {comment.text}
        </Typography>
      </Box>
      <Box className="share-comment-actions" sx={{ opacity: 0, transition: "opacity 120ms", display: "flex" }}>
        {onReply && (
          <Tooltip title={t("share.feedback.reply")}>
            <IconButton size="small" onClick={onReply} data-testid="share-comment-reply">
              <ReplyOutlined sx={{ fontSize: 15 }} />
            </IconButton>
          </Tooltip>
        )}
        <Tooltip title={t("share.feedback.delete")}>
          <IconButton size="small" onClick={onDelete} data-testid="share-comment-delete">
            <DeleteOutlined sx={{ fontSize: 15 }} />
          </IconButton>
        </Tooltip>
      </Box>
    </Box>
  );
}
