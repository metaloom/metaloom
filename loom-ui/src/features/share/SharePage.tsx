import React, { useEffect, useState } from "react";
import { Box, CircularProgress, Typography } from "@mui/material";
import LinkOffOutlined from "@mui/icons-material/LinkOffOutlined";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { tokens } from "../../theme";
import { loadShareChallenge, type ShareChallengeResponse } from "../../api/shares";
import { ShareSessionProvider, useShareSession } from "./ShareSessionContext";
import ShareGate from "./ShareGate";
import ShareViewer from "./ShareViewer";

/**
 * The customer-facing area, mounted at `/share/:slug`.
 *
 * **This route is declared in `main.tsx`, above `AuthGate`, and not in `AppShell`.** Authentication
 * in this app is a conditional render rather than a route guard: `AuthGate` returns `LoginPage` at
 * any URL when there is no token, and `AppShell` — which owns every other route — is only mounted
 * once there is one. A share route inside `AppShell` would therefore be unreachable by exactly the
 * people it exists for, and `AppShell`'s catch-all redirect would swallow it besides.
 *
 * No sidebar, no app chrome, no space picker. A customer opening a link is not using Loom; they
 * are looking at one thing somebody sent them.
 */
export default function SharePage() {
  const { slug } = useParams<{ slug: string }>();
  if (!slug) return <ShareUnavailable />;
  return (
    <ShareSessionProvider slug={slug}>
      <ShareRoute slug={slug} />
    </ShareSessionProvider>
  );
}

function ShareRoute({ slug }: { slug: string }) {
  const { session } = useShareSession();
  const [challenge, setChallenge] = useState<ShareChallengeResponse | null>(null);
  const [gone, setGone] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // Asked even when a stored session exists: the link may have been revoked or may have lapsed
    // since the last visit, and finding that out here is better than the viewer failing per panel.
    loadShareChallenge(slug)
      .then((response) => !cancelled && setChallenge(response))
      .catch(() => !cancelled && setGone(true));
    return () => {
      cancelled = true;
    };
  }, [slug]);

  if (gone) return <ShareUnavailable />;
  if (!challenge) return <ShareLoading />;
  if (session) return <ShareViewer session={session} />;
  return <ShareGate slug={slug} challenge={challenge} />;
}

function ShareLoading() {
  return (
    <Box
      data-testid="share-loading"
      sx={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", bgcolor: tokens.bg.base }}
    >
      <CircularProgress size={28} />
    </Box>
  );
}

/**
 * One page for "never existed", "revoked" and "expired".
 *
 * The server answers all three with 404 so that a slug cannot be used to find out which links were
 * ever real, and the wording here keeps that promise rather than guessing which case it is.
 */
function ShareUnavailable() {
  const { t } = useTranslation();
  return (
    <Box
      data-testid="share-unavailable"
      sx={{
        minHeight: "100vh",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: 1.5,
        bgcolor: tokens.bg.base,
        color: tokens.text.secondary,
        p: 3,
        textAlign: "center",
      }}
    >
      <LinkOffOutlined sx={{ fontSize: 48, color: tokens.text.tertiary }} />
      <Typography sx={{ fontSize: "1.1rem", fontWeight: 700, color: tokens.text.primary }}>
        {t("share.unavailable.title")}
      </Typography>
      <Typography variant="body2" sx={{ maxWidth: 380 }}>
        {t("share.unavailable.body")}
      </Typography>
    </Box>
  );
}
