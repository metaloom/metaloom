import { describe, expect, it } from "vitest";
import { PipelineRunDayStats } from "../../api/pipelines";
import { formatDeltaPct, summarizeRunStats } from "./runMetrics";

function bucket(date: string, runCount: number, over: Partial<PipelineRunDayStats> = {}): PipelineRunDayStats {
  return { date, runCount, successCount: 0, failureCount: 0, skippedCount: 0, ...over };
}

/** 14 buckets oldest first; runCounts[0] is the oldest day. */
function window14(runCounts: number[]): PipelineRunDayStats[] {
  return runCounts.map((count, i) => bucket(`2026-07-${String(9 + i).padStart(2, "0")}`, count));
}

describe("summarizeRunStats", () => {
  it("splits the buckets into a current and a previous 7 day window", () => {
    const daily = window14([1, 1, 1, 1, 1, 1, 2, 3, 0, 0, 3, 0, 2, 2]);
    const summary = summarizeRunStats(daily);
    expect(summary.prevRuns7d).toBe(8);
    expect(summary.totalRuns7d).toBe(10);
    expect(summary.runsDeltaPct).toBe(25);
  });

  it("computes a negative delta", () => {
    const daily = window14([0, 0, 0, 0, 0, 0, 5, 4, 0, 0, 0, 0, 0, 0]);
    const summary = summarizeRunStats(daily);
    expect(summary.prevRuns7d).toBe(5);
    expect(summary.totalRuns7d).toBe(4);
    expect(summary.runsDeltaPct).toBe(-20);
  });

  it("yields a null delta when the previous window has no runs", () => {
    const daily = window14([0, 0, 0, 0, 0, 0, 0, 1, 2, 0, 0, 0, 0, 0]);
    const summary = summarizeRunStats(daily);
    expect(summary.prevRuns7d).toBe(0);
    expect(summary.totalRuns7d).toBe(3);
    expect(summary.runsDeltaPct).toBeNull();
  });

  it("handles an empty bucket list", () => {
    expect(summarizeRunStats([])).toEqual({ totalRuns7d: 0, prevRuns7d: 0, runsDeltaPct: null });
  });

  it("treats a short bucket list as current window only", () => {
    const summary = summarizeRunStats([bucket("2026-07-21", 2), bucket("2026-07-22", 3)]);
    expect(summary.totalRuns7d).toBe(5);
    expect(summary.prevRuns7d).toBe(0);
    expect(summary.runsDeltaPct).toBeNull();
  });
});

describe("formatDeltaPct", () => {
  it("formats positive deltas with a plus sign", () => {
    expect(formatDeltaPct(25)).toBe("+25%");
    expect(formatDeltaPct(0)).toBe("+0%");
  });

  it("formats negative deltas", () => {
    expect(formatDeltaPct(-20)).toBe("-20%");
  });

  it("returns undefined for null so the KPI omits its delta line", () => {
    expect(formatDeltaPct(null)).toBeUndefined();
  });
});
