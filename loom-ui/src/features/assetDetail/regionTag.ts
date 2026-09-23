import type { AreaInfo } from "../../api/annotations";

/**
 * How far apart the two ends of a region tag must stay, in milliseconds.
 *
 * Dragging one edge past the other is a gesture people make by accident on a timeline where a
 * whole episode is 900 pixels wide. The band then has a negative width, which renders as nothing
 * at all — so the reviewer's tag appears to have been deleted by a slip of the mouse. A tenth of
 * a second is short enough never to get in the way of a deliberate edit and long enough that the
 * band stays findable.
 */
export const MIN_REGION_MS = 100;

/**
 * One edge of a region tag moved to a new time.
 *
 * Takes and returns the stored `AreaInfo` — the spatial fields ride through untouched, because a
 * tag can carry both a box and a timecode and the timeline only ever edits the second.
 *
 * @param area   the placement as it stands
 * @param edge   which handle was dragged
 * @param seconds where it was dropped; the caller works in seconds, the wire in milliseconds
 */
export function movedArea(area: AreaInfo, edge: "start" | "end", seconds: number): AreaInfo {
  const ms = Math.max(0, Math.round(seconds * 1000));
  if (edge === "start") {
    const limit = area.to != null ? area.to - MIN_REGION_MS : Number.POSITIVE_INFINITY;
    return { ...area, from: Math.min(ms, limit) };
  }
  const floor = area.from != null ? area.from + MIN_REGION_MS : 0;
  return { ...area, to: Math.max(ms, floor) };
}
