import React from "react";
import { Box, IconButton, Tooltip } from "@mui/material";
import { HelpOutlineOutlined } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../theme";
import { helpUrl, type HelpTopic } from "../help/topics";

/**
 * The help icon beside a screen or section heading: the coachmark that takes somebody to the part
 * of the documentation this screen is about.
 *
 * It deliberately looks like the inert `HelpOutlineOutlined` tooltips that were already scattered
 * across these headers, because it *is* those — the ones on a screen with a documentation section
 * behind it were made to lead somewhere. `description` is how their explanatory text survives the
 * change: the tooltip still says what the screen is, and now also offers the rest of the story.
 *
 * A persistent icon rather than a first-run popover. It is available the third time somebody is
 * confused as well as the first, it needs no dismissal state on a client whose auth is already
 * in-memory only, and it never interrupts anyone who was not looking for it.
 *
 * The destination is resolved by the website, not by this build — see `src/help/topics.ts` for why
 * a shipped UI must not hold a documentation URL.
 */
export default function HelpHint({
  topic,
  description,
  size = 14,
}: {
  topic: HelpTopic;
  /** What the screen is, if the header was carrying that explanation already. */
  description?: React.ReactNode;
  size?: number;
}) {
  const { t } = useTranslation();
  const label = t(`help.topic.${topic}`);

  return (
    <Tooltip
      arrow
      title={
        <Box sx={{ maxWidth: 260 }}>
          {description && <Box sx={{ mb: 0.75 }}>{description}</Box>}
          <Box sx={{ fontWeight: 600 }}>{t("help.open", { topic: label })}</Box>
        </Box>
      }
    >
      <IconButton
        component="a"
        href={helpUrl(topic)}
        target="_blank"
        /* `noopener` because the documentation is another origin and this one holds a session. */
        rel="noopener noreferrer"
        size="small"
        aria-label={t("help.open", { topic: label })}
        data-testid={`help-hint-${topic}`}
        sx={{
          p: 0.25,
          color: tokens.text.tertiary,
          "&:hover": { color: tokens.primary.main, bgcolor: "transparent" },
        }}
      >
        <HelpOutlineOutlined sx={{ fontSize: size }} />
      </IconButton>
    </Tooltip>
  );
}
