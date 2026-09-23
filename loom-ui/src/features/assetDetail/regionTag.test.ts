import { describe, expect, it } from "vitest";

import { MIN_REGION_MS, movedArea } from "./regionTag";

describe("moving one edge of a region tag", () => {
  const span = { from: 10_000, to: 20_000 };

  it("moves the edge that was dragged and leaves the other alone", () => {
    expect(movedArea(span, "end", 31.5)).toEqual({ from: 10_000, to: 31_500 });
    expect(movedArea(span, "start", 4)).toEqual({ from: 4_000, to: 20_000 });
  });

  it("carries the spatial fields through untouched", () => {
    // A tag can be a box *and* a timecode; the timeline only ever edits the second, and dropping
    // the box would move the tag off the face it was drawn on.
    const both = { ...span, startX: 100, startY: 200, width: 50, height: 60 };
    expect(movedArea(both, "end", 25)).toEqual({ ...both, to: 25_000 });
  });

  it("will not drag one end past the other", () => {
    // Easy to do by accident when a whole episode is 900 pixels wide, and the result is a band
    // of negative width — which renders as nothing, so the tag looks deleted.
    expect(movedArea(span, "start", 40).from).toBe(20_000 - MIN_REGION_MS);
    expect(movedArea(span, "end", 1).to).toBe(10_000 + MIN_REGION_MS);
  });

  it("never produces a negative time", () => {
    expect(movedArea(span, "start", -3).from).toBe(0);
  });

  it("copes with a half-open region, which is what a point tag is", () => {
    expect(movedArea({ from: 5_000 }, "end", 9)).toEqual({ from: 5_000, to: 9_000 });
    expect(movedArea({ to: 5_000 }, "start", 1)).toEqual({ from: 1_000, to: 5_000 });
  });
});
