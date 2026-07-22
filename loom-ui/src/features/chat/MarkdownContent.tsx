import React from "react";
import { Box } from "@mui/material";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { tokens } from "../../theme";

/**
 * Renders assistant/user chat content as GitHub-flavored markdown.
 *
 * Security: LLM output is untrusted — raw HTML stays escaped (no rehype-raw),
 * which is the sanitization story. Links always open in a new tab.
 */
export default function MarkdownContent({ content, muted = false }: { content: string; muted?: boolean }) {
  return (
    <Box
      data-testid="markdown-content"
      sx={{
        color: muted ? tokens.text.secondary : tokens.text.primary,
        fontSize: "0.865rem",
        lineHeight: 1.65,
        wordBreak: "break-word",
        "& p": { m: 0, mb: 1, "&:last-child": { mb: 0 } },
        "& ul, & ol": { m: 0, mb: 1, pl: 2.5, "&:last-child": { mb: 0 } },
        "& li": { mb: 0.25 },
        "& h1, & h2, & h3, & h4, & h5, & h6": { m: 0, mb: 1, mt: 1, fontSize: "1em", fontWeight: 700, "&:first-of-type": { mt: 0 } },
        "& a": { color: tokens.primary.light, textDecoration: "underline" },
        "& code": {
          fontFamily: "monospace",
          fontSize: "0.8rem",
          bgcolor: tokens.bg.overlay,
          px: 0.5,
          py: 0.1,
          borderRadius: tokens.radius.sm,
        },
        "& pre": {
          m: 0,
          mb: 1,
          p: 1.25,
          bgcolor: tokens.bg.overlay,
          borderRadius: tokens.radius.md,
          overflowX: "auto",
          "&:last-child": { mb: 0 },
          "& code": { bgcolor: "transparent", p: 0, fontSize: "0.78rem" },
        },
        "& blockquote": {
          m: 0,
          mb: 1,
          pl: 1.5,
          borderLeft: `3px solid ${tokens.border.strong}`,
          color: tokens.text.secondary,
        },
        "& table": {
          borderCollapse: "collapse",
          mb: 1,
          maxWidth: "100%",
          display: "block",
          overflowX: "auto",
        },
        "& th, & td": {
          border: `1px solid ${tokens.border.default}`,
          px: 1,
          py: 0.5,
          fontSize: "0.8rem",
          textAlign: "left",
        },
        "& th": { bgcolor: tokens.bg.overlay, fontWeight: 600 },
        "& hr": { border: 0, borderTop: `1px solid ${tokens.border.default}`, my: 1 },
      }}
    >
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ node: _node, ...props }) => <a {...props} target="_blank" rel="noopener noreferrer" />,
        }}
      >
        {content}
      </ReactMarkdown>
    </Box>
  );
}
