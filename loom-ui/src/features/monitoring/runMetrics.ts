import type { PipelineRunDayStats } from "../../api/pipelines";

export interface RunStatsSummary {
  /** Number of runs started in the last 7 buckets (most recent window). */
  totalRuns7d: number;
  /** Number of runs started in the 7 buckets before that window. */
  prevRuns7d: number;
  /** Rounded percent change of totalRuns7d vs prevRuns7d; null when the previous window is empty. */
  runsDeltaPct: number | null;
}

/**
 * Summarize the daily stats buckets returned by `GET /pipelines/runs/stats` into
 * KPI values. The buckets are expected oldest first; the last 7 form the current
 * window and the 7 before it the comparison window.
 */
export function summarizeRunStats(daily: PipelineRunDayStats[]): RunStatsSummary {
  const current = daily.slice(-7);
  const previous = daily.slice(-14, -7);
  const totalRuns7d = current.reduce((acc, b) => acc + b.runCount, 0);
  const prevRuns7d = previous.reduce((acc, b) => acc + b.runCount, 0);
  const runsDeltaPct = prevRuns7d > 0 ? Math.round(((totalRuns7d - prevRuns7d) / prevRuns7d) * 100) : null;
  return { totalRuns7d, prevRuns7d, runsDeltaPct };
}

/**
 * Format a percent delta as "+25%" / "-20%". Returns undefined for null so the
 * KPI card omits its delta line instead of showing a fabricated value.
 */
export function formatDeltaPct(pct: number | null): string | undefined {
  if (pct === null) return undefined;
  return `${pct >= 0 ? "+" : ""}${pct}%`;
}
