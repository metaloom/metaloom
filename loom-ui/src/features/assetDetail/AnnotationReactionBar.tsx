import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Tooltip, Typography } from "@mui/material";
import { ThumbUpAltOutlined, ThumbDownAltOutlined } from "@mui/icons-material";
import { tokens } from "../../theme";
import { useToast } from "../../context/ToastContext";
import {
  listAnnotationReactions,
  createAnnotationReaction,
  deleteAnnotationReaction,
  ReactionResponseItem,
  TaskReactionType,
} from "../../api/reactions";

/**
 * Compact thumbs up/down reaction bar for a single annotation. Loads the
 * annotation's reactions on mount and lets the current user toggle their own
 * THUMBSUP / THUMBSDOWN reaction (create if absent, delete if present).
 */
export function AnnotationReactionBar({
  annotationUuid,
  token,
  currentUserUuid,
}: {
  annotationUuid: string;
  token?: string | null;
  currentUserUuid?: string | null;
}) {
  const { t } = useTranslation("translation", { keyPrefix: "assetDetail" });
  const { showToast } = useToast();
  const [reactions, setReactions] = useState<ReactionResponseItem[]>([]);

  useEffect(() => {
    if (!token || !annotationUuid) {
      setReactions([]);
      return;
    }
    let active = true;
    listAnnotationReactions(token, annotationUuid)
      .then((resp) => { if (active) setReactions(resp.data ?? []); })
      .catch(() => { if (active) setReactions([]); });
    return () => { active = false; };
  }, [token, annotationUuid]);

  const mine = useCallback(
    (type: TaskReactionType) =>
      reactions.find((r) => r.type === type && !!currentUserUuid && r.status?.creator?.uuid === currentUserUuid),
    [reactions, currentUserUuid],
  );

  const toggle = useCallback(
    async (type: TaskReactionType) => {
      if (!token || !annotationUuid) return;
      const own = mine(type);
      try {
        if (own) {
          await deleteAnnotationReaction(token, annotationUuid, own.uuid);
          setReactions((prev) => prev.filter((r) => r.uuid !== own.uuid));
        } else {
          const created = await createAnnotationReaction(token, annotationUuid, { type });
          setReactions((prev) => [created, ...prev]);
        }
      } catch {
        showToast(own ? t("reaction.deleteError") : t("reaction.addError"), "error");
      }
    },
    [token, annotationUuid, mine, showToast, t],
  );

  const count = (type: TaskReactionType) => reactions.filter((r) => r.type === type).length;

  const button = (
    type: TaskReactionType,
    label: string,
    testId: string,
    Icon: typeof ThumbUpAltOutlined,
  ) => {
    const active = !!mine(type);
    return (
      <Tooltip title={label}>
        <Box
          component="button"
          type="button"
          aria-label={label}
          aria-pressed={active}
          data-testid={testId}
          disabled={!token}
          onClick={() => toggle(type)}
          sx={{
            display: "inline-flex",
            alignItems: "center",
            gap: 0.5,
            px: 0.75,
            py: 0.25,
            border: `1px solid ${active ? tokens.primary.glow : tokens.border.subtle}`,
            borderRadius: tokens.radius.sm,
            bgcolor: active ? tokens.primary.subtle : "transparent",
            color: active ? tokens.primary.light : tokens.text.tertiary,
            cursor: token ? "pointer" : "default",
            transition: "all 140ms ease",
            "&:hover": token ? { borderColor: tokens.primary.glow, color: tokens.primary.light } : undefined,
          }}
        >
          <Icon sx={{ fontSize: 13 }} />
          <Typography component="span" data-testid={`${testId}-count`} sx={{ fontSize: "0.68rem", fontWeight: 600, lineHeight: 1 }}>
            {count(type)}
          </Typography>
        </Box>
      </Tooltip>
    );
  };

  return (
    <Box sx={{ display: "flex", gap: 0.5, mt: 0.5 }}>
      {button("THUMBSUP", t("reaction.thumbsUp"), "annotation-reaction-up", ThumbUpAltOutlined)}
      {button("THUMBSDOWN", t("reaction.thumbsDown"), "annotation-reaction-down", ThumbDownAltOutlined)}
    </Box>
  );
}
