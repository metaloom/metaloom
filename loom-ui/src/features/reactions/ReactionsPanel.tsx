import React, { useRef, useState } from "react";
import { Box, Typography, Chip, Button, Menu, MenuItem } from "@mui/material";
import { AddOutlined, CloseOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { ReactionResponseItem, TaskReactionType } from "../../api/reactions";

// ── Reaction vocabulary (mirrors backend io.metaloom.loom.api.reaction.ReactionType) ──
/**
 * The types a person can pick from this panel.
 *
 * `RATING` is deliberately absent: it is a workflow star rating that happens to live on the
 * reaction table, and it is given from the review screen with a value attached. Offering it here
 * would let someone create a rating of nothing.
 */
export const REACTION_TYPES: TaskReactionType[] = ["THUMBSUP", "THUMBSDOWN", "SATISFIED", "PLUS_ONE", "MINUS_ONE"];
export const REACTION_EMOJI: Record<TaskReactionType, string> = {
  THUMBSUP: "👍",
  THUMBSDOWN: "👎",
  SATISFIED: "🤣",
  PLUS_ONE: "➕",
  MINUS_ONE: "➖",
  // Not pickable, but a rating written by the review screen still surfaces here, so it needs a face.
  RATING: "⭐",
};

interface ReactionsPanelProps {
  reactions: ReactionResponseItem[];
  currentUserUuid?: string | null;
  onAdd: (type: TaskReactionType) => void;
  /** May return the request's promise; a second remove of the same row is ignored until it settles. */
  onDelete: (reactionUuid: string) => void | Promise<void>;
  /** Prefix for data-testid attributes, e.g. "tasks" or "assets". */
  testIdPrefix?: string;
}

export function ReactionsPanel({ reactions, currentUserUuid, onAdd, onDelete, testIdPrefix = "reaction" }: ReactionsPanelProps) {
  const { t } = useTranslation("translation", { keyPrefix: "reactions" });
  const [menuAnchor, setMenuAnchor] = useState<null | HTMLElement>(null);
  // Rows whose DELETE is still in flight. A double click on the x used to send two, and the second
  // one 404ed into a "failed to delete" toast for a reaction that had just been deleted.
  const deleting = useRef(new Set<string>());

  const handleDelete = (reactionUuid: string) => {
    if (deleting.current.has(reactionUuid)) return;
    deleting.current.add(reactionUuid);
    void Promise.resolve(onDelete(reactionUuid)).finally(() => deleting.current.delete(reactionUuid));
  };

  const handleAdd = (type: TaskReactionType) => {
    setMenuAnchor(null);
    onAdd(type);
  };

  return (
    <Box>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 1 }}>
        <Typography sx={{ color: tokens.text.tertiary, fontSize: "0.8rem", fontWeight: 600 }}>
          {t("title")}
        </Typography>
        <Button
          size="small"
          startIcon={<AddOutlined sx={{ fontSize: 14 }} />}
          onClick={(e) => setMenuAnchor(e.currentTarget)}
          data-testid={`${testIdPrefix}-react-button`}
        >
          {t("add")}
        </Button>
        <Menu
          anchorEl={menuAnchor}
          open={!!menuAnchor}
          onClose={() => setMenuAnchor(null)}
        >
          {REACTION_TYPES.map((type) => (
            <MenuItem
              key={type}
              onClick={() => handleAdd(type)}
              data-testid={`${testIdPrefix}-react-option-${type}`}
            >
              <Box component="span" sx={{ mr: 1 }}>{REACTION_EMOJI[type]}</Box>
              {t(`type.${type}`)}
            </MenuItem>
          ))}
        </Menu>
      </Box>
      {reactions.length === 0 ? (
        <Typography variant="body2" sx={{ color: tokens.text.tertiary, fontSize: "0.8rem" }}>
          {t("empty")}
        </Typography>
      ) : (
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.75 }}>
          {reactions.map((r) => {
            const type = r.type as TaskReactionType | undefined;
            const emoji = type ? REACTION_EMOJI[type] : "•";
            const owned = !!currentUserUuid && r.status?.creator?.uuid === currentUserUuid;
            return (
              <Chip
                key={r.uuid}
                size="small"
                data-testid={`${testIdPrefix}-reaction-chip`}
                label={
                  <Box component="span">
                    <Box component="span" sx={{ mr: 0.5 }}>{emoji}</Box>
                    {type ? t(`type.${type}`) : (r.type ?? "")}
                    {/* A rating's meaning is its number, so a chip that showed only the star
                        would say strictly less than the row holds. */}
                    {type === "RATING" && typeof r.rating === "number" && ` ${r.rating}`}
                  </Box>
                }
                onDelete={owned ? () => handleDelete(r.uuid) : undefined}
                deleteIcon={
                  <CloseOutlined data-testid={`${testIdPrefix}-reaction-delete`} sx={{ fontSize: 14 }} />
                }
                sx={{ bgcolor: tokens.bg.base, border: `1px solid ${tokens.border.subtle}`, fontSize: "0.72rem" }}
              />
            );
          })}
        </Box>
      )}
    </Box>
  );
}
