import type { ClusterMemberModel } from "../../api/clusters";

/**
 * Turning a speaker cluster's speech windows into something a reviewer can listen to.
 *
 * The voiceprint node embeds 3-second windows every 1.5 seconds, so a speaker's members overlap
 * pairwise and a member list read as-is offers twenty clips of the same sentence. Merging the
 * overlapping ones gives the speaker's *turns* - the stretches they actually held the floor - which
 * is the unit the review screen plays and the import route cuts.
 */

/** One uninterrupted stretch of one speaker, in milliseconds into the asset. */
export interface SpeakerTurn {
  startMs: number;
  endMs: number;
}

/** How much of a turn the card's play button plays. Enough to recognise a voice. */
export const SPEAKER_PREVIEW_MS = 30_000;

/**
 * How much reference speech a person needs before recognition is dependable.
 *
 * Below this the voice section warns. Not a hard limit: a short sample still enrols, it just
 * matches less reliably.
 */
export const VOICE_REFERENCE_MIN_MS = 30_000;

function finite(n: unknown): n is number {
  return typeof n === "number" && Number.isFinite(n);
}

/**
 * Merge overlapping or touching windows into turns, in time order.
 *
 * Members without both times - a face, or a malformed row - are skipped rather than read as zero,
 * which would invent a turn at the start of the asset.
 */
export function mergeWindowsIntoTurns(members: Pick<ClusterMemberModel, "timeFrom" | "timeTo">[]): SpeakerTurn[] {
  const windows = members
    .filter(m => finite(m.timeFrom) && finite(m.timeTo) && (m.timeTo as number) > (m.timeFrom as number))
    .map(m => ({ startMs: m.timeFrom as number, endMs: m.timeTo as number }))
    .sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);

  const turns: SpeakerTurn[] = [];
  for (const w of windows) {
    const last = turns[turns.length - 1];
    if (last && w.startMs <= last.endMs) {
      last.endMs = Math.max(last.endMs, w.endMs);
    } else {
      turns.push({ ...w });
    }
  }
  return turns;
}

/**
 * The turns the node already merged into the cluster's `meta.segments`.
 *
 * Read defensively: `meta` is free-form JSON, so anything that is not a list of number pairs is
 * dropped rather than trusted.
 */
export function turnsFromMeta(meta: Record<string, unknown> | undefined | null): SpeakerTurn[] {
  const raw = meta?.segments;
  if (!Array.isArray(raw)) return [];
  const turns: SpeakerTurn[] = [];
  for (const s of raw) {
    const seg = s as { startMs?: unknown; endMs?: unknown } | null;
    if (seg && finite(seg.startMs) && finite(seg.endMs) && seg.endMs > seg.startMs) {
      turns.push({ startMs: seg.startMs, endMs: seg.endMs });
    }
  }
  return turns.sort((a, b) => a.startMs - b.startMs);
}

/** How long the speaker spoke: the node's own `meta.totalMs`, or the sum of the turns. */
export function speakingTimeMs(meta: Record<string, unknown> | undefined | null, turns: SpeakerTurn[]): number {
  const total = meta?.totalMs;
  if (finite(total) && total >= 0) return total;
  return turns.reduce((sum, t) => sum + (t.endMs - t.startMs), 0);
}

/** The longest turn, the earliest one on a tie; undefined when there are none. */
export function longestTurn(turns: SpeakerTurn[]): SpeakerTurn | undefined {
  let best: SpeakerTurn | undefined;
  for (const t of turns) {
    if (!best || t.endMs - t.startMs > best.endMs - best.startMs) best = t;
  }
  return best;
}

/** The part of a turn to play: from its start, at most `capMs` long. */
export function clipRange(turn: SpeakerTurn, capMs: number): { fromMs: number; toMs: number } {
  return { fromMs: turn.startMs, toMs: Math.min(turn.endMs, turn.startMs + capMs) };
}

/** A position or a length as `m:ss`, or `h:mm:ss` past the hour. Negative and missing read as 0. */
export function formatClock(ms: number | null | undefined): string {
  const totalSeconds = finite(ms) && ms > 0 ? Math.round(ms / 1000) : 0;
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const ss = String(s).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

/** A 0..1 similarity as a whole percentage, clamped. */
export function scorePercent(score: number | null | undefined): number {
  if (!finite(score)) return 0;
  return Math.round(Math.min(1, Math.max(0, score)) * 100);
}

/** Total reference speech across a person's samples; a sample of unknown length counts as nothing. */
export function totalReferenceMs(samples: { durationMs?: number | null }[]): number {
  return samples.reduce((sum, s) => sum + (finite(s.durationMs) && s.durationMs > 0 ? s.durationMs : 0), 0);
}
