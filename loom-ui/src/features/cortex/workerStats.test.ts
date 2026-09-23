import { describe, expect, it } from "vitest";

import { gigabytes, loadColor, memoryPct, pct, pctOrNull, vramOf } from "./workerStats";
import { tokens } from "../../theme";
import type { Processor } from "../../api/processors";

/** A processor snapshot with only the status fields a test cares about. */
function worker(systemStatus: Processor["systemStatus"]): Processor {
  return { uuid: "u", nodeId: "n", name: "n", systemStatus };
}

describe("utilisation percentages", () => {
  it("rounds and clamps", () => {
    expect(pct(37.4)).toBe(37);
    expect(pct(-5)).toBe(0);
    expect(pct(240)).toBe(100);
  });

  it("reads an absent CPU or IO figure as zero, because every worker has one", () => {
    expect(pct(undefined)).toBe(0);
  });

  it("keeps an absent GPU figure absent, because most workers have none", () => {
    // The whole reason `pctOrNull` exists. A CPU-only worker reporting 0% advertises itself as
    // the emptiest GPU on the fleet, which is where the scheduler would then send every GPU job.
    expect(pctOrNull(undefined)).toBeNull();
    expect(pctOrNull(0)).toBe(0);
    expect(pctOrNull(96.6)).toBe(97);
  });
});

describe("video memory", () => {
  it("is a fraction, and keeps the bytes for the gigabyte readout", () => {
    const vram = vramOf(worker({ gpuMemoryUsed: 8 * 1024 ** 3, gpuMemoryTotal: 24 * 1024 ** 3 }));
    expect(vram).toEqual({ usedBytes: 8 * 1024 ** 3, totalBytes: 24 * 1024 ** 3, pct: 33 });
  });

  it("is absent when the worker reported no card", () => {
    expect(vramOf(worker({ cpuLoad: 10 }))).toBeNull();
    expect(vramOf(worker(undefined))).toBeNull();
  });

  it("is absent when the total is zero rather than dividing by it", () => {
    // What a machine with the driver present and no device produces. `used / 0` is Infinity, and
    // `Math.round(NaN)` for `0 / 0` renders as "NaN%".
    expect(vramOf(worker({ gpuMemoryUsed: 0, gpuMemoryTotal: 0 }))).toBeNull();
  });

  it("prints gigabytes to one decimal, which is how a model is sized", () => {
    expect(gigabytes(24 * 1024 ** 3)).toBe("24.0 GB");
    expect(gigabytes(1_500_000_000)).toBe("1.4 GB");
    expect(gigabytes(0)).toBe("0.0 GB");
  });
});

describe("heap percentage", () => {
  it("is zero when there is no total to divide by", () => {
    expect(memoryPct(worker({ memoryUsed: 512 }))).toBe(0);
    expect(memoryPct(worker({ memoryUsed: 512, memoryTotal: 1024 }))).toBe(50);
  });
});

describe("the colour a figure is read at", () => {
  it("has three bands, because the question has a yes/no answer at each end", () => {
    expect(loadColor(12)).toBe(tokens.text.secondary);
    expect(loadColor(69)).toBe(tokens.text.secondary);
    expect(loadColor(70)).toBe(tokens.accent.amber);
    expect(loadColor(89)).toBe(tokens.accent.amber);
    expect(loadColor(90)).toBe(tokens.accent.red);
  });

  it("says nothing about a figure that is not there", () => {
    expect(loadColor(null)).toBe(tokens.text.tertiary);
  });
});
