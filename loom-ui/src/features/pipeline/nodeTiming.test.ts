import { describe, expect, it } from "vitest";

import {
  formatBound,
  formatDuration,
  formatShare,
  histogramRows,
  isReusedTask,
  nodeTimeRows,
  reusedFraction,
  runTrendRows,
  timingsByNode,
} from "./nodeTiming";
import type { PipelineNodeStatsResponse, PipelineNodeTimeStats } from "../../api/pipelines";

function node(nodeId: string, totalDurationMs: number, extra: Partial<PipelineNodeTimeStats> = {}): PipelineNodeTimeStats {
  return {
    nodeId, nodeKind: nodeId, tasks: 4, computed: 3, reused: 1, failed: 0, skipped: 0,
    totalDurationMs, computedDurationMs: totalDurationMs, estimatedSavedMs: 0, share: 0.5,
    histogram: [], runs: [], ...extra,
  };
}

describe("isReusedTask", () => {
  it("marks completed LOCAL and REMOTE results as reused", () => {
    expect(isReusedTask({ state: "COMPLETED", origin: "REMOTE" })).toBe(true);
    expect(isReusedTask({ state: "COMPLETED", origin: "LOCAL" })).toBe(true);
  });
  it("treats a missing origin as computed, and never a failure as reused", () => {
    expect(isReusedTask({ state: "COMPLETED" })).toBe(false);
    expect(isReusedTask({ state: "COMPLETED", origin: "COMPUTED" })).toBe(false);
    expect(isReusedTask({ state: "FAILED", origin: "REMOTE" })).toBe(false);
  });
});

describe("formatDuration", () => {
  it("picks the unit a person reads", () => {
    expect(formatDuration(850)).toBe("850 ms");
    expect(formatDuration(4210)).toBe("4.2 s");
    expect(formatDuration(42_000)).toBe("42 s");
    expect(formatDuration(185_000)).toBe("3 min 05 s");
    expect(formatDuration(7_620_000)).toBe("2 h 07 min");
  });
  it("shows a dash for nothing", () => {
    expect(formatDuration(null)).toBe("–");
    expect(formatDuration(undefined)).toBe("–");
    expect(formatDuration(-1)).toBe("–");
  });
});

describe("formatBound / formatShare / reusedFraction", () => {
  it("formats bounds compactly", () => {
    expect(formatBound(512)).toBe("512 ms");
    expect(formatBound(2048)).toBe("2 s");
    expect(formatBound(65_536)).toBe("1 min");
  });
  it("never shows a non-zero share as zero", () => {
    expect(formatShare(0)).toBe("0 %");
    expect(formatShare(0.004)).toBe("<1 %");
    expect(formatShare(0.937)).toBe("94 %");
  });
  it("computes the reused fraction without dividing by zero", () => {
    expect(reusedFraction({ tasks: 4, reused: 1 })).toBe(0.25);
    expect(reusedFraction({ tasks: 0, reused: 0 })).toBe(0);
  });
});

describe("nodeTimeRows", () => {
  it("orders by cost and prefers display names", () => {
    const rows = nodeTimeRows([node("hash", 10), node("whisper", 900), node("ocr", 10)], { whisper: "Transcribe" });
    expect(rows.map(r => r.nodeId)).toEqual(["whisper", "hash", "ocr"]);
    expect(rows[0].label).toBe("Transcribe");
    expect(rows[1].label).toBe("hash");
  });
});

describe("timingsByNode", () => {
  it("indexes by node id and tolerates no stats", () => {
    expect(timingsByNode(null)).toEqual({});
    const stats = { nodes: [node("whisper", 900)] } as unknown as PipelineNodeStatsResponse;
    expect(timingsByNode(stats).whisper).toEqual({ totalDurationMs: 900, tasks: 4, reused: 1, share: 0.5 });
  });
});

describe("histogramRows", () => {
  it("sorts buckets ascending and labels their range", () => {
    const rows = histogramRows(node("whisper", 1, {
      histogram: [{ fromMs: 1024, toMs: 2048, count: 2 }, { fromMs: 0, toMs: 2, count: 1 }],
    }));
    expect(rows.map(r => r.label)).toEqual(["0 ms–2 ms", "1 s–2 s"]);
    expect(rows.map(r => r.count)).toEqual([1, 2]);
  });
});

describe("runTrendRows", () => {
  it("reads oldest to newest and keeps runs where the node did not execute", () => {
    const runs = [
      { uuid: "r2", pipelineVersion: 1, started: null },
      { uuid: "r1", pipelineVersion: 1, started: null },
    ];
    const rows = runTrendRows(node("whisper", 1, { runs: [{ runUuid: "r2", tasks: 2, reused: 2, totalDurationMs: 8 }] }), runs);
    expect(rows.map(r => r.runUuid)).toEqual(["r1", "r2"]);
    expect(rows[0].totalDurationMs).toBe(0);
    expect(rows[1].reused).toBe(2);
  });
});
