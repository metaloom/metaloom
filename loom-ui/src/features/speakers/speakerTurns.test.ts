import { describe, expect, it } from "vitest";
import {
  clipRange, formatClock, longestTurn, mergeWindowsIntoTurns, scorePercent, speakingTimeMs,
  totalReferenceMs, turnsFromMeta,
} from "./speakerTurns";

describe("mergeWindowsIntoTurns", () => {
  it("merges the overlapping 3 s windows the node emits every 1.5 s into one turn", () => {
    const turns = mergeWindowsIntoTurns([
      { timeFrom: 1500, timeTo: 4500 },
      { timeFrom: 0, timeTo: 3000 },
      { timeFrom: 3000, timeTo: 6000 },
    ]);
    expect(turns).toEqual([{ startMs: 0, endMs: 6000 }]);
  });

  it("keeps a gap as two turns, in time order", () => {
    const turns = mergeWindowsIntoTurns([
      { timeFrom: 20_000, timeTo: 23_000 },
      { timeFrom: 0, timeTo: 3000 },
      { timeFrom: 1500, timeTo: 4500 },
    ]);
    expect(turns).toEqual([{ startMs: 0, endMs: 4500 }, { startMs: 20_000, endMs: 23_000 }]);
  });

  it("joins windows that only touch", () => {
    expect(mergeWindowsIntoTurns([{ timeFrom: 0, timeTo: 3000 }, { timeFrom: 3000, timeTo: 6000 }]))
      .toEqual([{ startMs: 0, endMs: 6000 }]);
  });

  it("skips members without times rather than inventing a turn at zero", () => {
    expect(mergeWindowsIntoTurns([{ timeFrom: null, timeTo: null }, {}, { timeFrom: 5000, timeTo: 4000 }])).toEqual([]);
  });

  it("does not fold a contained window into a later one", () => {
    expect(mergeWindowsIntoTurns([{ timeFrom: 0, timeTo: 10_000 }, { timeFrom: 2000, timeTo: 3000 }, { timeFrom: 11_000, timeTo: 12_000 }]))
      .toEqual([{ startMs: 0, endMs: 10_000 }, { startMs: 11_000, endMs: 12_000 }]);
  });
});

describe("turnsFromMeta", () => {
  it("reads the node's segments and sorts them", () => {
    expect(turnsFromMeta({ segments: [{ startMs: 9000, endMs: 12_000 }, { startMs: 0, endMs: 4500 }] }))
      .toEqual([{ startMs: 0, endMs: 4500 }, { startMs: 9000, endMs: 12_000 }]);
  });

  it("drops anything that is not a pair of numbers", () => {
    expect(turnsFromMeta({ segments: [null, { startMs: "0", endMs: 1 }, { startMs: 5, endMs: 2 }, { startMs: 1, endMs: 2 }] }))
      .toEqual([{ startMs: 1, endMs: 2 }]);
    expect(turnsFromMeta({ segments: "nope" })).toEqual([]);
    expect(turnsFromMeta(undefined)).toEqual([]);
  });
});

describe("speakingTimeMs", () => {
  it("prefers the node's own total", () => {
    expect(speakingTimeMs({ totalMs: 42_000 }, [{ startMs: 0, endMs: 1000 }])).toBe(42_000);
  });

  it("falls back to the sum of the turns", () => {
    expect(speakingTimeMs({}, [{ startMs: 0, endMs: 1000 }, { startMs: 5000, endMs: 7500 }])).toBe(3500);
  });
});

describe("longestTurn / clipRange", () => {
  it("picks the longest turn, the earliest on a tie", () => {
    const a = { startMs: 0, endMs: 3000 };
    const b = { startMs: 10_000, endMs: 50_000 };
    const c = { startMs: 60_000, endMs: 100_000 };
    expect(longestTurn([a, b, c])).toBe(b);
    expect(longestTurn([])).toBeUndefined();
  });

  it("caps the clip at the preview length", () => {
    expect(clipRange({ startMs: 10_000, endMs: 50_000 }, 30_000)).toEqual({ fromMs: 10_000, toMs: 40_000 });
    expect(clipRange({ startMs: 10_000, endMs: 14_000 }, 30_000)).toEqual({ fromMs: 10_000, toMs: 14_000 });
  });
});

describe("formatClock", () => {
  it("formats minutes and hours", () => {
    expect(formatClock(0)).toBe("0:00");
    expect(formatClock(65_000)).toBe("1:05");
    expect(formatClock(3_725_000)).toBe("1:02:05");
  });

  it("reads missing and negative as zero", () => {
    expect(formatClock(undefined)).toBe("0:00");
    expect(formatClock(null)).toBe("0:00");
    expect(formatClock(-5)).toBe("0:00");
  });
});

describe("scorePercent", () => {
  it("rounds and clamps", () => {
    expect(scorePercent(0.873)).toBe(87);
    expect(scorePercent(1.2)).toBe(100);
    expect(scorePercent(-1)).toBe(0);
    expect(scorePercent(undefined)).toBe(0);
  });
});

describe("totalReferenceMs", () => {
  it("sums the known durations and ignores the rest", () => {
    expect(totalReferenceMs([{ durationMs: 12_000 }, { durationMs: null }, {}, { durationMs: 8000 }])).toBe(20_000);
  });
});
