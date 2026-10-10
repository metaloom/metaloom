import { describe, expect, it } from "vitest";
import { timelinePct, timelineSpanPct } from "./timelineMath";

describe("timelinePct", () => {
  it("is the fraction of the duration, in percent", () => {
    expect(timelinePct(30, 120)).toBe(25);
    expect(timelinePct(0, 120)).toBe(0);
    expect(timelinePct(120, 120)).toBe(100);
  });

  it("is 0 rather than Infinity or NaN while the duration is unknown", () => {
    // The bug: 30 / 0 * 100 = Infinity, 0 / 0 * 100 = NaN — invalid CSS, markers piled at 0:00.
    expect(timelinePct(30, 0)).toBe(0);
    expect(timelinePct(0, 0)).toBe(0);
    expect(timelinePct(30, Number.NaN)).toBe(0);
    expect(timelinePct(Number.NaN, 120)).toBe(0);
  });

  it("clamps a moment outside the media to the bar", () => {
    expect(timelinePct(500, 120)).toBe(100);
    expect(timelinePct(-5, 120)).toBe(0);
  });
});

describe("timelineSpanPct", () => {
  it("is the width between the two edges", () => {
    expect(timelineSpanPct(30, 60, 120)).toBe(25);
  });

  it("never goes negative and never overruns the bar", () => {
    expect(timelineSpanPct(60, 30, 120)).toBe(0);
    expect(timelineSpanPct(90, 500, 120)).toBe(25);
    expect(timelineSpanPct(10, 20, 0)).toBe(0);
  });
});
