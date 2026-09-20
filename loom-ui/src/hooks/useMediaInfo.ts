import { useEffect, useState } from "react";

import { loadMediaInfo, MediaInfoResponse } from "../api/assets";
import { useAuth } from "../context/AuthContext";

/**
 * What the decoder says about one video, fetched once and shared across the page.
 *
 * <p>This exists because nothing else in Loom knows how long a video is. `asset_video_comp` has
 * carried duration, dimensions and frame rate since V1 and has no producer — no Cortex node writes
 * it — so `asset.duration` is empty for every ingested file. The media element is not a fallback
 * either: the player is fed a fragmented MP4 over a pipe, and a pipe reports only what has already
 * arrived, which made a 43-minute episode's timeline read "0:05".</p>
 *
 * <p>Cached module-wide by asset uuid, like {@link useMediaToken}: a screen showing a player and a
 * strip of detections asks for the same probe from several components, and an ffprobe call per
 * component is a process per component.</p>
 *
 * @param assetUuid the asset to probe, or null/undefined to probe nothing
 */
export function useMediaInfo(assetUuid?: string | null): MediaInfoResponse | null {
  const { token } = useAuth();
  const [info, setInfo] = useState<MediaInfoResponse | null>(() =>
    assetUuid ? cache.get(assetUuid) ?? null : null);

  useEffect(() => {
    if (!token || !assetUuid) {
      setInfo(null);
      return;
    }
    const cached = cache.get(assetUuid);
    if (cached) {
      setInfo(cached);
      return;
    }
    let cancelled = false;
    // One in-flight request per asset, shared. Without this a mount storm — a grid switching
    // pages, a mode switch in the workflow view — fires one probe per component before the first
    // has answered, and the cache only helps from the second render on.
    let pending = inFlight.get(assetUuid);
    if (!pending) {
      pending = loadMediaInfo(token, assetUuid);
      inFlight.set(assetUuid, pending);
    }
    pending
      .then(resp => {
        cache.set(assetUuid, resp);
        inFlight.delete(assetUuid);
        if (!cancelled) {
          setInfo(resp);
        }
      })
      .catch(() => {
        // Degrades to "unknown", which every caller already has to handle: a video with no probe
        // is indistinguishable from a video nothing has measured.
        inFlight.delete(assetUuid);
        if (!cancelled) {
          setInfo(null);
        }
      });
    return () => { cancelled = true; };
  }, [token, assetUuid]);

  return info;
}

const cache = new Map<string, MediaInfoResponse>();
const inFlight = new Map<string, Promise<MediaInfoResponse>>();

/** Visible for tests: forget every probe. */
export function clearMediaInfoCache(): void {
  cache.clear();
  inFlight.clear();
}
