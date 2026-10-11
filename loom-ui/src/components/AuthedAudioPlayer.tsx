import React, { useEffect, useRef, useState } from "react";
import { Box, CircularProgress, IconButton, Tooltip } from "@mui/material";
import { ErrorOutlineOutlined, PlayArrowRounded } from "@mui/icons-material";
import { useTranslation } from "react-i18next";

import { tokens } from "../theme";

interface AuthedAudioPlayerProps {
  /** Fetch the recording. Called on the first press of play, and again on a retry. */
  load: () => Promise<Blob>;
  /**
   * What is being played. A different key is a different recording: the old object URL is revoked
   * and the player goes back to its play button.
   */
  sourceKey: string;
  /** Accessible name of the play button, e.g. "Play Anna's voice". */
  label: string;
  testId?: string;
  /** Width of the `<audio>` element once loaded. */
  width?: number | string;
}

type State =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ready"; url: string }
  | { kind: "failed" };

/**
 * A play button that turns into an `<audio>` element once its recording has been fetched.
 *
 * Two things this exists to get right, the same two `FaceCrop` gets right for pictures:
 *
 *  - **The bytes need an Authorization header.** An `<audio src>` cannot send one, so the
 *    recording is fetched with the header and played from an object URL - and that URL is revoked
 *    when the player unmounts or switches recordings, or the decoded audio stays pinned for the
 *    life of the tab.
 *  - **Nothing is fetched until somebody asks.** A review grid of forty speakers would otherwise
 *    cut forty WAVs on the server just to draw the page.
 */
export default function AuthedAudioPlayer({ load, sourceKey, label, testId = "audio-player", width = 220 }: AuthedAudioPlayerProps) {
  const { t } = useTranslation();
  const [state, setState] = useState<State>({ kind: "idle" });
  // The latest loader, read at click time. Callers pass an inline closure, and depending on it
  // would reset the player on every render of the card around it.
  const loadRef = useRef(load);
  loadRef.current = load;
  const urlRef = useRef<string | null>(null);
  const generation = useRef(0);

  useEffect(() => {
    // A new recording: forget the old one, and make any fetch still in flight land nowhere.
    generation.current += 1;
    setState({ kind: "idle" });
    return () => {
      generation.current += 1;
      if (urlRef.current) {
        URL.revokeObjectURL(urlRef.current);
        urlRef.current = null;
      }
    };
  }, [sourceKey]);

  const start = async () => {
    const mine = ++generation.current;
    setState({ kind: "loading" });
    try {
      const blob = await loadRef.current();
      if (mine !== generation.current) return;
      const url = URL.createObjectURL(blob);
      urlRef.current = url;
      setState({ kind: "ready", url });
    } catch {
      if (mine === generation.current) setState({ kind: "failed" });
    }
  };

  if (state.kind === "ready") {
    return (
      <Box component="audio" src={state.url} controls autoPlay data-testid={`${testId}-audio`}
        sx={{ width, height: 32, maxWidth: "100%", display: "block" }} />
    );
  }

  if (state.kind === "loading") {
    return (
      <Box sx={{ width: 30, height: 30, display: "flex", alignItems: "center", justifyContent: "center" }} data-testid={`${testId}-loading`}>
        <CircularProgress size={16} />
      </Box>
    );
  }

  if (state.kind === "failed") {
    return (
      <Tooltip disableInteractive title={t("audio.loadFailed")}>
        <IconButton size="small" onClick={start} aria-label={t("audio.retry")} data-testid={`${testId}-failed`}>
          <ErrorOutlineOutlined sx={{ fontSize: 18, color: tokens.accent.red }} />
        </IconButton>
      </Tooltip>
    );
  }

  return (
    <Tooltip disableInteractive title={label}>
      <IconButton size="small" onClick={start} aria-label={label} data-testid={`${testId}-play`}
        sx={{ color: tokens.primary.main }}>
        <PlayArrowRounded sx={{ fontSize: 20 }} />
      </IconButton>
    </Tooltip>
  );
}
