import type { SearchHitResponse } from "../../api/search";
import { SEARCH_MAX_OFFSET, SEARCH_PAGE_SIZE } from "../../types";

/**
 * Where clicking a hit should go.
 *
 * Only assets have a detail route of their own. Everything that belongs to an asset resolves to
 * that asset; the rest lands on the management screen that lists it. Returns null when the hit
 * cannot be resolved — a transcript whose `assetUuid` the server omitted — and the row then
 * renders as plain, non-clickable text rather than a link to nowhere.
 *
 * A hit that knows *when* carries `?t=` with it. Before the transcript index was windowed there
 * was nothing worth carrying — every transcript hit reported offset zero — so a search for a
 * line of dialogue opened a 43-minute episode at the beginning and left the reader to find it.
 */
export function hitTarget(hit: SearchHitResponse): string | null {
  switch (hit.type) {
    case "asset":
      return `/assets/${encodeURIComponent(hit.uuid)}`;
    case "transcript":
    case "annotation":
    case "segment":
    case "detection":
      return hit.assetUuid
        ? `/assets/${encodeURIComponent(hit.assetUuid)}${deepLinkTime(hit.timeFromMs)}`
        : null;
    case "tag":
      return "/tags";
    case "collection":
      return "/collections";
    // A remix has no page of its own: it opens as a dialog over the asset grid, and ?remix= is the
    // same deep link the grid itself writes.
    case "remix":
      return `/assets?remix=${encodeURIComponent(hit.uuid)}`;
    case "library":
      return "/library";
    case "person":
    case "cluster":
      return "/detection";
    default:
      return null;
  }
}

/**
 * The `?t=` fragment for a hit that knows its offset, or the empty string.
 *
 * Seconds rather than milliseconds because that is what a player's `currentTime` is and what
 * every other `?t=` on the web means; zero is omitted because it is where the file opens anyway
 * and a URL that says so is noise.
 */
function deepLinkTime(timeFromMs?: number): string {
  if (timeFromMs === undefined || !Number.isFinite(timeFromMs) || timeFromMs <= 0) return "";
  return `?t=${Math.floor(timeFromMs / 1000)}`;
}

/** Format a millisecond offset as `m:ss`, or `h:mm:ss` once it passes an hour. */
export function formatTimecode(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const seconds = totalSeconds % 60;
  const minutes = Math.floor(totalSeconds / 60) % 60;
  const hours = Math.floor(totalSeconds / 3600);
  const pad = (value: number) => String(value).padStart(2, "0");
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

/**
 * Clamp an offset into the range the provider will actually serve.
 *
 * Paging past the cap is a 400, not an empty page, so a stale or hand-edited URL is corrected
 * before the request is built rather than surfacing as an error.
 */
export function clampOffset(raw: number): number {
  if (!Number.isFinite(raw)) return 0;
  return Math.min(SEARCH_MAX_OFFSET, Math.max(0, Math.floor(raw)));
}

/**
 * Whether a next page exists and can be reached.
 *
 * False at the deep-paging cap even when more hits exist — the provider would answer 400, and
 * saying so before the round trip is the honest version.
 */
export function hasNextPage(offset: number, pageLength: number, totalHits: number): boolean {
  if (pageLength === 0) return false;
  if (offset + pageLength >= totalHits) return false;
  return offset + SEARCH_PAGE_SIZE <= SEARCH_MAX_OFFSET;
}

/** The 1-based inclusive range this page covers, for "showing 26–50 of 312". */
export function pageRange(offset: number, pageLength: number): { from: number; to: number } {
  if (pageLength === 0) return { from: 0, to: 0 };
  return { from: offset + 1, to: offset + pageLength };
}
