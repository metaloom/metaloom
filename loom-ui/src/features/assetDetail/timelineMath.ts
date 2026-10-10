/**
 * Where a moment sits on the timeline, as a CSS percentage of the bar.
 *
 * <p>The bar used to compute `time / duration * 100` inline. A video's duration is 0 until the
 * probe answers (and stays 0 when it fails — `asset_video_comp` has no producer), so every marker
 * came out as `left: Infinity%` and the playhead as `NaN%`. Both are invalid CSS, the declaration is
 * dropped, an absolutely positioned box falls back to its static position, and every comment,
 * annotation and region tag piled up on top of each other at 0:00. A marker past the end of the
 * media (a comment left on a longer cut) was drawn off the bar entirely.</p>
 *
 * <p>Returns 0 when the duration is unknown and clamps to the bar otherwise.</p>
 */
export function timelinePct(time: number, duration: number): number {
  if (!(duration > 0) || !Number.isFinite(time)) return 0;
  return Math.max(0, Math.min(100, (time / duration) * 100));
}

/** The width of the span `[from, to]` on the bar, in percent; never negative. */
export function timelineSpanPct(from: number, to: number, duration: number): number {
  return Math.max(0, timelinePct(to, duration) - timelinePct(from, duration));
}
