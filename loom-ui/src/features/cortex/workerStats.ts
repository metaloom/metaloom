import { tokens } from "../../theme";
import type { Processor } from "../../api/processors";

/**
 * The figures a worker card reads off a `STATUS_UPDATE`.
 *
 * In a module of their own rather than in `CortexView.tsx` so they can be unit-tested in the node
 * environment: the view imports MUI, and the arithmetic here is the part that is worth pinning.
 * What it is pinning, mostly, is the difference between **absent** and **zero** — which on this
 * screen is the difference between "this worker has no graphics card" and "this worker has an
 * idle graphics card", and getting it wrong sends every GPU job to the machine least able to run
 * one.
 */

/** A 0–100 percentage, with an absent figure read as zero. For a bar that must draw something. */
export function pct(v?: number): number {
  return Math.max(0, Math.min(100, Math.round(v ?? 0)));
}

/**
 * A 0–100 percentage, or null when the worker did not say.
 *
 * {@link pct}'s zero is the right answer for CPU and I/O, which every worker has, and exactly the
 * wrong one for a GPU, which most do not.
 */
export function pctOrNull(v?: number): number | null {
  return v == null ? null : Math.max(0, Math.min(100, Math.round(v)));
}

/** Bytes as gigabytes, to one decimal: the unit an operator sizes a model in. */
export function gigabytes(bytes: number): string {
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

/** Heap use as a percentage. Zero when the worker reported no total — there is nothing to divide by. */
export function memoryPct(p: Processor): number {
  const used = p.systemStatus?.memoryUsed;
  const total = p.systemStatus?.memoryTotal;
  if (!used || !total || total <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((used / total) * 100)));
}

export interface Vram {
  usedBytes: number;
  totalBytes: number;
  pct: number;
}

/**
 * The worker's video memory, or null when it has none to report.
 *
 * A total of zero counts as "none": that is what a machine with the driver present and no device
 * produces, and dividing by it would give NaN.
 *
 * Reported apart from the GPU load because the two answer different questions, and a worker can
 * be at either extreme of one while at the other extreme of the other — a model left resident
 * between jobs pins memory at 90% while the card reads idle, which is precisely the state in
 * which the next model will not fit.
 */
export function vramOf(p: Processor): Vram | null {
  const used = p.systemStatus?.gpuMemoryUsed;
  const total = p.systemStatus?.gpuMemoryTotal;
  if (used == null || total == null || total <= 0) return null;
  return {
    usedBytes: used,
    totalBytes: total,
    pct: Math.max(0, Math.min(100, Math.round((used / total) * 100))),
  };
}

/**
 * The colour a utilisation figure is worth reading at.
 *
 * Three bands rather than a gradient, because the question an operator asks of this screen is
 * "which box do I not send this to", and that has a yes/no answer at each end.
 */
export function loadColor(value: number | null): string {
  if (value == null) return tokens.text.tertiary;
  if (value >= 90) return tokens.accent.red;
  if (value >= 70) return tokens.accent.amber;
  return tokens.text.secondary;
}
