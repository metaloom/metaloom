import { describe, expect, it } from "vitest";
import { poolCapacity } from "./poolCapacity";

const GB = 1e9;

describe("poolCapacity", () => {
  it("takes the capacity as free plus used when the server reports no total", () => {
    // The bug: total fell back to freeSpace, so this drew 50% used of a 100 GB pool.
    const c = poolCapacity(100 * GB, 50 * GB, 0)!;
    expect(c.total).toBe(150 * GB);
    expect(c.usedFraction).toBeCloseTo(1 / 3, 5);
  });

  it("does not saturate when more is used than is free", () => {
    const c = poolCapacity(10 * GB, 90 * GB, 0)!;
    expect(c.total).toBe(100 * GB);
    expect(c.usedFraction).toBeCloseTo(0.9, 5);
  });

  it("prefers an explicit total and derives used from it", () => {
    const c = poolCapacity(25 * GB, undefined, 100 * GB)!;
    expect(c.used).toBe(75 * GB);
    expect(c.usedFraction).toBeCloseTo(0.75, 5);
  });

  it("an empty pool with only a free figure is 0% used", () => {
    const c = poolCapacity(100 * GB, undefined, 0)!;
    expect(c.total).toBe(100 * GB);
    expect(c.usedFraction).toBe(0);
  });

  it("is null when there is nothing to draw", () => {
    expect(poolCapacity(undefined, 5 * GB, 0)).toBeNull();
    expect(poolCapacity(-1, undefined, 0)).toBeNull();
    expect(poolCapacity(0, 0, 0)).toBeNull();
  });
});
