import { useEffect, useState } from "react";

import { loadMediaInfo } from "../api/assets";
import { useAuth } from "../context/AuthContext";

/** How many probes are allowed to be in flight at once. */
const CONCURRENCY = 3;

/** Probed seconds by asset uuid, module-wide: a grid re-mounts on every page change. */
const cache = new Map<string, number | null>();
const inFlight = new Set<string>();

/** Visible for tests: forget every probe. */
export function clearAssetDurationCache(): void {
  cache.clear();
  inFlight.clear();
}

/**
 * Fill in the missing lengths for a page of video tiles.
 *
 * <p>The duration badge needs a number the catalogue does not have: `asset_video_comp` has
 * carried `media_duration` since V1 and, until the probe route started writing it, nothing ever
 * put a value there — so every video in the list reported no length and the badge would simply
 * never appear. `GET /assets/:uuid/media-info` measures it, and the server persists what it
 * measured, so this fills the gap once per asset and the column answers from then on.</p>
 *
 * <p>Deliberately bounded and deliberately last: three probes at a time, only for assets whose
 * duration is genuinely unknown, and a failure is remembered as "unknown" rather than retried on
 * every render. An ffprobe per tile with no ceiling would be twenty-five processes the moment a
 * library page opened.</p>
 *
 * @param assets the tiles on screen; only videos with no duration are probed
 * @returns seconds by asset uuid, for the ones that have been measured
 */
export function useAssetDurations(assets: { id: string; type: string; duration?: number }[]): Record<string, number> {
  const { token } = useAuth();
  const [probed, setProbed] = useState<Record<string, number>>({});

  // A stable key, so re-rendering the same page does not restart the queue.
  const wanted = assets
    .filter(a => a.type === "video" && !a.duration)
    .map(a => a.id)
    .sort()
    .join(",");

  useEffect(() => {
    if (!token || !wanted) return;
    const ids = wanted.split(",");
    let cancelled = false;

    // Anything already known goes straight into state; nothing is re-requested for it.
    const known: Record<string, number> = {};
    for (const id of ids) {
      const hit = cache.get(id);
      if (typeof hit === "number") known[id] = hit;
    }
    if (Object.keys(known).length > 0) setProbed(prev => ({ ...prev, ...known }));

    const queue = ids.filter(id => !cache.has(id) && !inFlight.has(id));
    let next = 0;

    const pump = (): Promise<void> => {
      if (cancelled || next >= queue.length) return Promise.resolve();
      const id = queue[next++];
      inFlight.add(id);
      return loadMediaInfo(token, id)
        .then(info => {
          const seconds = typeof info?.duration === "number" && info.duration > 0 ? info.duration : null;
          cache.set(id, seconds);
          if (!cancelled && seconds != null) setProbed(prev => ({ ...prev, [id]: seconds }));
        })
        .catch(() => {
          // Remembered as "cannot be measured". Retrying on the next render would be a probe
          // storm against exactly the files that cannot answer one.
          cache.set(id, null);
        })
        .finally(() => { inFlight.delete(id); })
        .then(pump);
    };

    for (let i = 0; i < Math.min(CONCURRENCY, queue.length); i++) void pump();
    return () => { cancelled = true; };
  }, [token, wanted]);

  return probed;
}
