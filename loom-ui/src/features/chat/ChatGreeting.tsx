import React from "react";
import { Box, Typography } from "@mui/material";
import { AutoAwesome } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";

interface Props {
  /** Name shown in the greeting. Falls back to a generic salutation when absent. */
  username?: string | null;
}

/**
 * Prominent welcome shown in a brand-new chat session (no messages yet). Replaces
 * the blank transcript area with a large "Hello <username>" and a one-line hint of
 * what the agent can do. Disappears as soon as the first message is sent.
 */
export default function ChatGreeting({ username }: Props) {
  const { t } = useTranslation();

  return (
    <Box
      data-testid="chat-greeting"
      sx={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        textAlign: "center",
        height: "100%",
        minHeight: 260,
        gap: 1.5,
        px: 3,
      }}
    >
      <Box
        sx={{
          width: 56,
          height: 56,
          borderRadius: "50%",
          background: `linear-gradient(135deg, ${tokens.primary.main} 0%, ${tokens.primary.dark} 100%)`,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          boxShadow: `0 0 26px ${tokens.primary.glow}`,
        }}
      >
        <AutoAwesome sx={{ fontSize: 26, color: "#fff" }} />
      </Box>

      <Typography
        variant="h4"
        fontWeight={800}
        data-testid="chat-greeting-title"
        sx={{
          fontSize: { xs: "1.6rem", md: "2.1rem" },
          lineHeight: 1.15,
          background: `linear-gradient(135deg, ${tokens.text.primary} 0%, ${tokens.primary.main} 100%)`,
          WebkitBackgroundClip: "text",
          WebkitTextFillColor: "transparent",
          backgroundClip: "text",
        }}
      >
        {username ? t("chat.greeting.hello", { name: username }) : t("chat.greeting.helloAnonymous")}
      </Typography>

      <Typography variant="body2" sx={{ color: tokens.text.secondary, maxWidth: 420, lineHeight: 1.6 }}>
        {t("chat.greeting.subtitle")}
      </Typography>
    </Box>
  );
}
