import React, { forwardRef, useCallback, useImperativeHandle, useRef, useState } from "react";
import { Box, IconButton, Tooltip, Typography } from "@mui/material";
import {
  PlayArrowOutlined, PauseOutlined, VolumeUpOutlined, VolumeOffOutlined,
  FullscreenOutlined, Replay10Outlined, Forward10Outlined,
} from "@mui/icons-material";
import { useTranslation } from "react-i18next";

import { assetPosterUrl, assetStreamUrl } from "../api/assets";
import { useMediaToken } from "../hooks/useMediaToken";
import { tokens } from "../theme";
import MediaPlaceholder from "./MediaPlaceholder";

/** Imperative surface: the timeline lives outside this component, so seeking has to come in. */
export interface AssetVideoPlayerHandle {
  /** Jump to an absolute position in the *asset*, re-requesting the stream if need be. */
  seekTo: (seconds: number) => void;
  play: () => void;
  pause: () => void;
}

interface AssetVideoPlayerProps {
  assetUuid: string;
  /**
   * Length of the asset in seconds, from the probe.
   *
   * Required rather than read off the element, and that is the whole point of this component. The
   * stream is a fragmented MP4 delivered over a pipe: `el.duration` is whatever has arrived so far
   * and `el.seekable` is empty, so a native control bar shows a few seconds of an hour-long file
   * and refuses to scrub past them. Pass 0 only when nothing is known.
   */
  duration: number;
  /** Playback position in asset time, reported continuously. */
  onTimeUpdate?: (seconds: number) => void;
  /** Absolutely positioned children drawn over the picture — bounding boxes, badges. */
  overlay?: React.ReactNode;
  autoPlay?: boolean;
  testId?: string;
  /**
   * Size of the player as a whole — picture *and* transport, not the picture alone.
   *
   * It used to land on the picture box, which meant `height: "100%"` made the picture as tall as
   * the slot and the control bar was then pushed out of the bottom of it. A caller sizing "the
   * video" means the thing they can see, so the box the caller sizes is the outer one and the
   * picture takes what the transport leaves.
   */
  sx?: React.ComponentProps<typeof Box>["sx"];
}

/**
 * A video player for a Loom asset, driven by the on-demand remux.
 *
 * <h3>Why this is not just a `<video controls>`</h3>
 *
 * <p>It was, and it could only play the opening seconds of anything. Loom stores originals, so the
 * browser is handed a stream the server remuxes on demand; a fragmented MP4 over a pipe carries no
 * index, so `seekable` is empty and the native scrubber has nothing to scrub. The native bar also
 * reports the pipe's length as the file's, which drew a five-second timeline for a 43-minute
 * episode and made the player look broken rather than limited.</p>
 *
 * <p>So the native controls are off and the transport is ours. A seek past what has buffered
 * becomes a <em>new request</em> at that offset — {@link AssetVideoPlayerHandle.seekTo} — and
 * everything the element reports has the offset added back before anyone sees it. A seek inside
 * the buffer is still just a `currentTime` write, because re-requesting to move two seconds would
 * be an ffmpeg process for nothing.</p>
 *
 * <p>The timeline itself is deliberately not here. The asset detail view needs markers, range
 * selection and draggable handles; the workflow review queue needs detection ticks and a hover
 * highlight. Both drive this through the ref instead, so neither has to inherit the other's
 * controls.</p>
 */
export const AssetVideoPlayer = forwardRef<AssetVideoPlayerHandle, AssetVideoPlayerProps>(
  function AssetVideoPlayer({ assetUuid, duration, onTimeUpdate, overlay, autoPlay, testId = "asset-video", sx }, ref) {
    const { t } = useTranslation();
    const videoRef = useRef<HTMLVideoElement>(null);
    const containerRef = useRef<HTMLDivElement>(null);
    const mediaToken = useMediaToken(assetUuid);

    /**
     * Where the current response starts, in asset seconds.
     *
     * Every position the element reports is relative to this, and every position anybody outside
     * this component talks about is absolute. Getting that wrong is how a seek to minute twenty
     * lands at minute forty.
     */
    const [streamOffset, setStreamOffset] = useState(0);
    const [paused, setPaused] = useState(!autoPlay);
    const [muted, setMuted] = useState(false);
    const [position, setPosition] = useState(0);

    const streamUrl = mediaToken ? assetStreamUrl(assetUuid, mediaToken, streamOffset) : null;
    const posterUrl = mediaToken ? assetPosterUrl(assetUuid, mediaToken, undefined, 960) : undefined;

    const seekTo = useCallback((seconds: number) => {
      if (!Number.isFinite(seconds)) return;
      const target = Math.max(0, seconds);
      setPosition(target);
      onTimeUpdate?.(target);
      const video = videoRef.current;
      if (!video) {
        setStreamOffset(Math.floor(target));
        return;
      }
      const relative = target - streamOffset;
      const buffered = video.seekable.length > 0
        && relative >= video.seekable.start(0)
        && relative <= video.seekable.end(video.seekable.length - 1);
      if (buffered) {
        video.currentTime = relative;
        return;
      }
      // Outside what arrived: a different response, starting there. `key` on the element makes
      // React build a new one, or the browser keeps playing the old body.
      setStreamOffset(Math.floor(target));
    }, [streamOffset, onTimeUpdate]);

    useImperativeHandle(ref, () => ({
      seekTo,
      play: () => { void videoRef.current?.play().catch(() => { /* autoplay policy */ }); },
      pause: () => videoRef.current?.pause(),
    }), [seekTo]);

    const togglePlay = () => {
      const video = videoRef.current;
      if (!video) return;
      if (video.paused) {
        void video.play().catch(() => { /* autoplay policy */ });
      } else {
        video.pause();
      }
    };

    if (!streamUrl) {
      return (
        <Box sx={{ position: "relative", bgcolor: "#000", display: "flex", alignItems: "center", justifyContent: "center", minHeight: 0, overflow: "hidden", ...sx }}>
          <MediaPlaceholder type="video" iconSize={48} />
        </Box>
      );
    }

    return (
      <Box ref={containerRef} data-testid={`${testId}-container`}
        sx={{ display: "flex", flexDirection: "column", bgcolor: "#000", minWidth: 0, minHeight: 0, overflow: "hidden", ...sx }}>
        {/* `flex: 1` + `minHeight: 0`, never a percentage height: a flex item that ignores
            `min-height:auto` is how a video taller than its slot pushes the transport off the
            bottom edge of an `overflow: hidden` parent instead of shrinking. */}
        <Box sx={{ position: "relative", flex: 1, minHeight: 0, bgcolor: "#000", display: "flex", alignItems: "center", justifyContent: "center", overflow: "hidden" }}>
          <Box
            component="video"
            key={streamUrl}
            ref={videoRef}
            data-testid={testId}
            data-stream-offset={streamOffset}
            src={streamUrl}
            poster={posterUrl}
            playsInline
            preload="metadata"
            autoPlay={autoPlay || streamOffset > 0}
            muted={muted}
            onPlay={() => setPaused(false)}
            onPause={() => setPaused(true)}
            onClick={togglePlay}
            onTimeUpdate={e => {
              const at = streamOffset + (e.currentTarget as HTMLVideoElement).currentTime;
              setPosition(at);
              onTimeUpdate?.(at);
            }}
            sx={{ width: "100%", height: "100%", objectFit: "contain", display: "block", cursor: "pointer" }}
          />
          {overlay}
        </Box>

        {/* Transport. The scrubber is not here: see the class comment. */}
        <Box data-testid={`${testId}-controls`}
          sx={{ flexShrink: 0, display: "flex", alignItems: "center", gap: 0.25, px: 1, py: 0.5, bgcolor: tokens.bg.surface, borderTop: `1px solid ${tokens.border.subtle}` }}>
          <Tooltip title={paused ? t("player.play") : t("player.pause")}>
            <IconButton size="small" onClick={togglePlay} data-testid={`${testId}-playpause`} data-paused={paused ? "true" : "false"}>
              {paused ? <PlayArrowOutlined sx={{ fontSize: 18 }} /> : <PauseOutlined sx={{ fontSize: 18 }} />}
            </IconButton>
          </Tooltip>
          <Tooltip title={t("player.back10")}>
            <IconButton size="small" onClick={() => seekTo(position - 10)} data-testid={`${testId}-back10`}>
              <Replay10Outlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Tooltip title={t("player.forward10")}>
            <IconButton size="small" onClick={() => seekTo(position + 10)} data-testid={`${testId}-forward10`}>
              <Forward10Outlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Typography variant="caption" data-testid={`${testId}-time`}
            sx={{ fontFamily: "monospace", fontSize: "0.7rem", color: tokens.text.secondary, ml: 0.5 }}>
            {clock(position)} / {duration > 0 ? clock(duration) : "--:--"}
          </Typography>
          <Box sx={{ flex: 1 }} />
          <Tooltip title={muted ? t("player.unmute") : t("player.mute")}>
            <IconButton size="small" onClick={() => setMuted(m => !m)} data-testid={`${testId}-mute`}>
              {muted ? <VolumeOffOutlined sx={{ fontSize: 18 }} /> : <VolumeUpOutlined sx={{ fontSize: 18 }} />}
            </IconButton>
          </Tooltip>
          <Tooltip title={t("player.fullscreen")}>
            <IconButton size="small" data-testid={`${testId}-fullscreen`}
              onClick={() => { void containerRef.current?.requestFullscreen?.().catch(() => { /* denied */ }); }}>
              <FullscreenOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
        </Box>
      </Box>
    );
  });

/** h:mm:ss for anything over an hour, m:ss below. An episode is over an hour often enough to matter. */
export function clock(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const whole = Math.floor(seconds);
  const h = Math.floor(whole / 3600);
  const m = Math.floor((whole % 3600) / 60);
  const s = whole % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`
    : `${m}:${String(s).padStart(2, "0")}`;
}

export default AssetVideoPlayer;
