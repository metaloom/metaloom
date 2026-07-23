import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Button, Chip, IconButton, TextField, Tooltip, Typography } from "@mui/material";
import { AccessTimeOutlined, EditOutlined, DeleteOutlineOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { Annotation } from "../../types";
import { formatDuration, userName } from "./helpers";
import { AnnotationReactionBar } from "./AnnotationReactionBar";

export function AnnotationItem({
  ann,
  highlighted,
  onTimeClick,
  onHover,
  token,
  currentUserUuid,
  editing,
  onStartEdit,
  onCancelEdit,
  onEdit,
  onDelete,
}: {
  ann: Annotation;
  highlighted: boolean;
  onTimeClick?: (t: number) => void;
  onHover?: (id: string | null) => void;
  token?: string | null;
  currentUserUuid?: string | null;
  editing?: boolean;
  onStartEdit?: (id: string) => void;
  onCancelEdit?: () => void;
  onEdit?: (id: string, title: string, description: string) => void;
  onDelete?: (id: string) => void;
}) {
  const { t } = useTranslation("translation", { keyPrefix: "assetDetail" });
  const [titleDraft, setTitleDraft] = useState(ann.title);
  const [descDraft, setDescDraft] = useState(ann.description);
  const isAuthor = !!currentUserUuid && ann.authorId === currentUserUuid;
  const canEdit = isAuthor && !!onEdit && !!onDelete;

  return (
    <Box
      onMouseEnter={() => onHover?.(ann.id)}
      onMouseLeave={() => onHover?.(null)}
      sx={{
        display: "flex",
        gap: 1.25,
        p: 1.5,
        borderRadius: tokens.radius.md,
        bgcolor: highlighted ? `${ann.color}14` : "transparent",
        border: highlighted ? `1px solid ${ann.color}44` : `1px solid transparent`,
        transition: "all 160ms ease",
        cursor: "default",
        "&:hover .annotation-actions": { opacity: 1 },
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
          {canEdit && !editing && (
            <Box className="annotation-actions" sx={{ display: "flex", gap: 0.25, ml: "auto", opacity: 0, transition: "opacity 120ms ease" }}>
              <Tooltip title={t("annotation.edit")}>
                <IconButton
                  size="small"
                  aria-label={t("annotation.edit")}
                  data-testid="annotation-edit"
                  onClick={() => { setTitleDraft(ann.title); setDescDraft(ann.description); onStartEdit?.(ann.id); }}
                  sx={{ p: 0.25 }}
                >
                  <EditOutlined sx={{ fontSize: 15 }} />
                </IconButton>
              </Tooltip>
              <Tooltip title={t("annotation.delete")}>
                <IconButton
                  size="small"
                  aria-label={t("annotation.delete")}
                  data-testid="annotation-delete"
                  onClick={() => onDelete?.(ann.id)}
                  sx={{ p: 0.25, color: tokens.accent.red }}
                >
                  <DeleteOutlineOutlined sx={{ fontSize: 15 }} />
                </IconButton>
              </Tooltip>
            </Box>
          )}
        </Box>
        {editing ? (
          <Box sx={{ display: "flex", flexDirection: "column", gap: 0.75, mt: 0.5 }}>
            <TextField
              fullWidth
              size="small"
              autoFocus
              value={titleDraft}
              onChange={(e) => setTitleDraft(e.target.value)}
              placeholder={t("annotation.titlePlaceholder")}
              inputProps={{ "aria-label": t("annotation.titlePlaceholder"), "data-testid": "annotation-title-field" }}
            />
            <TextField
              fullWidth
              multiline
              maxRows={6}
              size="small"
              value={descDraft}
              onChange={(e) => setDescDraft(e.target.value)}
              inputProps={{ "aria-label": t("annotation.edit"), "data-testid": "annotation-edit-field" }}
            />
            <Box sx={{ display: "flex", gap: 0.75, justifyContent: "flex-end" }}>
              <Button size="small" data-testid="annotation-cancel" onClick={() => onCancelEdit?.()}>{t("annotation.cancel")}</Button>
              <Button size="small" variant="contained" data-testid="annotation-save" disabled={!titleDraft.trim()} onClick={() => onEdit?.(ann.id, titleDraft, descDraft)}>{t("annotation.save")}</Button>
            </Box>
          </Box>
        ) : (
          <>
            <Typography variant="body2" sx={{ fontSize: "0.8rem", color: tokens.text.secondary }}>
              {ann.description}
            </Typography>
            <Typography variant="caption" sx={{ color: tokens.text.tertiary, fontSize: "0.68rem" }}>
              {userName(ann.authorId)}
            </Typography>
            <AnnotationReactionBar annotationUuid={ann.id} token={token} currentUserUuid={currentUserUuid} />
          </>
        )}
      </Box>
    </Box>
  );
}
