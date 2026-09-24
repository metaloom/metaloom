import React from "react";
import { Box, CircularProgress, IconButton, LinearProgress, Tooltip, Typography } from "@mui/material";
import {
  BookmarkAddOutlined,
  CloseOutlined,
  DescriptionOutlined,
  ErrorOutline,
  ImageOutlined,
  InsertDriveFileOutlined,
} from "@mui/icons-material";
import { useTranslation } from "react-i18next";

import { tokens } from "../../theme";
import { AttachmentItem, formatBytes, kindOf } from "./attachmentState";

/**
 * The strip of attached files above the composer.
 *
 * <p>Each chip says three things, and the third is the one that matters: what the file is called,
 * how big it is, and <b>what the assistant can do with it</b>. A picture is for the image tools; a
 * text file can be read; anything else cannot be read in this deployment, and saying so on the chip
 * is far better than letting the user ask a question about a PDF and be told afterwards.</p>
 */
export interface ChatAttachmentChipsProps {
  items: AttachmentItem[];
  onRemove: (item: AttachmentItem) => void;
  onSave: (item: AttachmentItem) => void;
  /** Whether "Save to library" is offered — hidden when the caller cannot create assets. */
  canSave?: boolean;
}

export default function ChatAttachmentChips({ items, onRemove, onSave, canSave = true }: ChatAttachmentChipsProps) {
  const { t } = useTranslation();
  if (items.length === 0) {
    return null;
  }

  return (
    <Box
      data-testid="chat-attachment-chips"
      sx={{
        display: "flex", flexWrap: "wrap", gap: 0.75,
        px: 1.5, pt: 1.25, pb: 0.25,
      }}
    >
      {items.map(item => {
        const kind = kindOf(item.mimeType);
        const failed = item.status === "failed";
        const uploading = item.status === "uploading";
        const color = failed ? tokens.accent.red : kind === "image" ? tokens.accent.blue : tokens.accent.teal;

        return (
          <Box
            key={item.id}
            data-testid={`chat-attachment-${item.filename}`}
            data-status={item.status}
            sx={{
              display: "flex", alignItems: "center", gap: 0.75,
              maxWidth: 260, minWidth: 0,
              pl: 1, pr: 0.25, py: 0.5,
              borderRadius: tokens.radius.md,
              border: `1px solid ${failed ? tokens.accent.red : tokens.border.default}`,
              bgcolor: tokens.bg.surface,
              position: "relative",
              overflow: "hidden",
            }}
          >
            <Box sx={{ display: "flex", color, flexShrink: 0 }}>
              {uploading ? (
                <CircularProgress size={13} sx={{ color }} />
              ) : failed ? (
                <ErrorOutline sx={{ fontSize: 14 }} />
              ) : kind === "image" ? (
                <ImageOutlined sx={{ fontSize: 14 }} />
              ) : kind === "text" ? (
                <DescriptionOutlined sx={{ fontSize: 14 }} />
              ) : (
                <InsertDriveFileOutlined sx={{ fontSize: 14 }} />
              )}
            </Box>

            <Tooltip title={failed ? (item.error ?? "") : t(`chat.attachments.kind.${kind}`)}>
              <Box sx={{ minWidth: 0 }}>
                <Typography
                  variant="caption"
                  sx={{
                    display: "block", fontSize: "0.72rem", fontWeight: 500,
                    color: tokens.text.primary,
                    whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
                  }}
                >
                  {item.filename}
                </Typography>
                <Typography variant="caption" sx={{ display: "block", fontSize: "0.64rem", color: tokens.text.tertiary }}>
                  {failed ? t("chat.attachments.failed") : `${formatBytes(item.size)} · ${t(`chat.attachments.kind.${kind}`)}`}
                </Typography>
              </Box>
            </Tooltip>

            {canSave && item.status === "ready" && (
              <Tooltip title={t("chat.attachments.save")}>
                <IconButton
                  size="small"
                  aria-label={t("chat.attachments.save")}
                  onClick={() => onSave(item)}
                  sx={{ width: 20, height: 20, color: tokens.text.tertiary, "&:hover": { color: tokens.primary.main } }}
                >
                  <BookmarkAddOutlined sx={{ fontSize: 13 }} />
                </IconButton>
              </Tooltip>
            )}

            <Tooltip title={t("chat.attachments.remove")}>
              <IconButton
                size="small"
                aria-label={t("chat.attachments.remove")}
                data-testid={`chat-attachment-remove-${item.filename}`}
                onClick={() => onRemove(item)}
                sx={{ width: 20, height: 20, color: tokens.text.tertiary, "&:hover": { color: tokens.accent.red } }}
              >
                <CloseOutlined sx={{ fontSize: 13 }} />
              </IconButton>
            </Tooltip>

            {uploading && (
              <LinearProgress
                // Indeterminate until the first progress event: a bar sitting at 0% reads as stuck.
                variant={item.progress === undefined ? "indeterminate" : "determinate"}
                value={(item.progress ?? 0) * 100}
                sx={{ position: "absolute", left: 0, right: 0, bottom: 0, height: 2 }}
              />
            )}
          </Box>
        );
      })}
    </Box>
  );
}
