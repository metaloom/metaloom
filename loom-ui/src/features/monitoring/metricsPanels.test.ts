import { describe, expect, it } from "vitest";
import type { MetricRecord, MetricsResponse } from "../../api/metrics";
import {
  appendSample, latencyByKind, outcomesByKind, parkedKinds, summarizeFleet,
  toLiveSample, toLiveSeries, workersByState, MAX_SAMPLES, type LiveSample,
} from "./metricsPanels";

function counter(name: string, value: number, tags: Record<string, string> = {}): MetricRecord {
  return { name, type: "COUNTER", tags, value };
}

function gauge(name: string, value: number, tags: Record<string, string> = {}): MetricRecord {
  return { name, type: "GAUGE", tags, value };
}

function timer(name: string, count: number, sumSeconds: number, maxSeconds: number, tags: Record<string, string>): MetricRecord {
  return { name, type: "TIMER", tags, count, sumSeconds, maxSeconds, meanSeconds: count ? sumSeconds / count : 0 };
}

describe("outcomesByKind", () => {
  const metrics = [
    counter("loom_node_results_received_total", 10, { kind: "sha512", state: "completed" }),
    counter("loom_node_results_received_total", 2, { kind: "sha512", state: "failed" }),
    counter("loom_node_results_received_total", 1, { kind: "sha512", state: "skipped" }),
    counter("loom_node_results_received_total", 100, { kind: "whisper", state: "completed" }),
  ];

  it("splits each kind by settled state", () => {
    expect(outcomesByKind(metrics)).toEqual([
      { kind: "whisper", completed: 100, failed: 0, skipped: 0 },
      { kind: "sha512", completed: 10, failed: 2, skipped: 1 },
    ]);
  });

  it("counts an unclassifiable state as failed rather than dropping it", () => {
    // A result nobody could classify is not a success, and silently discarding it would make the
    // totals disagree with the dispatch counters.
    const rows = outcomesByKind([counter("loom_node_results_received_total", 3, { kind: "ocr", state: "unknown" })]);
    expect(rows).toEqual([{ kind: "ocr", completed: 0, failed: 3, skipped: 0 }]);
  });

  it("is empty when nothing has been received", () => {
    expect(outcomesByKind([])).toEqual([]);
  });
});

describe("latencyByKind", () => {
  const metrics = [
    timer("loom_node_task_latency_seconds", 4, 2, 1.5, { kind: "sha512", state: "completed" }),
    // A failure's duration is how long it took to time out; including it would swamp the mean.
    timer("loom_node_task_latency_seconds", 1, 300, 300, { kind: "sha512", state: "failed" }),
    timer("loom_node_task_latency_seconds", 2, 6, 4, { kind: "whisper", state: "completed" }),
  ];

  it("reports mean and worst of completed tasks only, worst first", () => {
    expect(latencyByKind(metrics)).toEqual([
      { kind: "whisper", meanMs: 3000, maxMs: 4000 },
      { kind: "sha512", meanMs: 500, maxMs: 1500 },
    ]);
  });

  it("omits a kind that has only ever failed", () => {
    const failedOnly = [timer("loom_node_task_latency_seconds", 3, 90, 30, { kind: "vlm", state: "failed" })];
    expect(latencyByKind(failedOnly)).toEqual([]);
  });
});

describe("workersByState", () => {
  it("keeps the zero states — a fleet stuck in terminating is the case this chart exists for", () => {
    const rows = workersByState([
      gauge("loom_processors_by_state", 0, { state: "offline" }),
      gauge("loom_processors_by_state", 2, { state: "terminating" }),
      gauge("loom_processors_by_state", 0, { state: "online" }),
    ]);
    expect(rows).toEqual([
      { state: "online", count: 0 },
      { state: "terminating", count: 2 },
      { state: "offline", count: 0 },
    ]);
  });
});

describe("parkedKinds", () => {
  it("counts half-open as parked — the gauge is ordered by severity", () => {
    expect(parkedKinds([
      gauge("loom_node_circuit_breaker_state", 0, { kind: "sha512" }),
      gauge("loom_node_circuit_breaker_state", 1, { kind: "ocr" }),
      gauge("loom_node_circuit_breaker_state", 2, { kind: "whisper" }),
    ])).toEqual(["ocr", "whisper"]);
  });
});

describe("summarizeFleet", () => {
  it("reads every KPI from one snapshot", () => {
    const summary = summarizeFleet([
      gauge("loom_pipeline_runs_active", 2),
      gauge("loom_node_tasks_inflight", 6),
      gauge("loom_node_tasks_inflight_ceiling", 16),
      gauge("loom_processors_connected", 4),
      gauge("loom_processors_by_state", 3, { state: "online" }),
      counter("loom_node_tasks_dispatch_failed_total", 5, { reason: "no_processor" }),
      counter("loom_node_tasks_dispatch_failed_total", 2, { reason: "socket_gone" }),
      counter("loom_node_tasks_deadlettered_total", 1, { kind: "ocr" }),
      timer("loom_node_task_latency_seconds", 2, 1, 0.6, { kind: "sha512", state: "completed" }),
    ]);
    expect(summary.activeRuns).toBe(2);
    expect(summary.inFlight).toBe(6);
    expect(summary.inFlightCeiling).toBe(16);
    expect(summary.workersOnline).toBe(3);
    expect(summary.workersConnected).toBe(4);
    // Both dispatch-failure reasons roll into one fleet number.
    expect(summary.dispatchFailures).toBe(7);
    expect(summary.deadLettered).toBe(1);
    expect(summary.meanLatencyMs).toBe(500);
  });

  it("leaves absent gauges undefined so the card shows a dash, and counters at 0", () => {
    const summary = summarizeFleet([]);
    expect(summary.activeRuns).toBeUndefined();
    expect(summary.workersOnline).toBeUndefined();
    expect(summary.meanLatencyMs).toBeUndefined();
    // A counter that was never incremented genuinely reads zero.
    expect(summary.dispatchFailures).toBe(0);
    expect(summary.parked).toEqual([]);
  });
});

describe("live sampling", () => {
  function response(timestamp: string, results: number, inFlight: number, ceiling: number): MetricsResponse {
    return {
      timestamp,
      metrics: [
        counter("loom_node_results_received_total", results, { kind: "sha512", state: "completed" }),
        gauge("loom_node_tasks_inflight", inFlight),
        gauge("loom_node_tasks_inflight_ceiling", ceiling),
      ],
    };
  }

  it("takes its clock from the server, not the browser", () => {
    const sample = toLiveSample(response("2026-08-09T11:00:00Z", 10, 2, 8));
    expect(sample.ts).toBe(Date.parse("2026-08-09T11:00:00Z"));
    expect(sample).toMatchObject({ resultsTotal: 10, inFlight: 2, ceiling: 8 });
  });

  it("refuses a sample with no usable timestamp", () => {
    // It would make the next rate divide by a nonsense interval.
    const history: LiveSample[] = [{ ts: 1, resultsTotal: 0, inFlight: 0, ceiling: 0 }];
    expect(appendSample(history, toLiveSample(response("not-a-date", 1, 1, 1)))).toBe(history);
  });

  it("keeps only the most recent window", () => {
    let history: LiveSample[] = [];
    for (let i = 1; i <= MAX_SAMPLES + 10; i++) {
      history = appendSample(history, { ts: i * 1000, resultsTotal: i, inFlight: 0, ceiling: 0 });
    }
    expect(history).toHaveLength(MAX_SAMPLES);
    expect(history[history.length - 1].resultsTotal).toBe(MAX_SAMPLES + 10);
  });

  it("yields no point from a single sample — a rate needs an interval", () => {
    expect(toLiveSeries([{ ts: 1000, resultsTotal: 5, inFlight: 1, ceiling: 4 }])).toEqual([]);
  });

  it("differences the counter into a per-second rate", () => {
    const series = toLiveSeries([
      { ts: 0, resultsTotal: 10, inFlight: 1, ceiling: 8 },
      { ts: 5_000, resultsTotal: 60, inFlight: 3, ceiling: 8 },
    ]);
    expect(series).toEqual([{ ts: 5_000, resultsPerSecond: 10, inFlight: 3, ceiling: 8 }]);
  });

  it("reads a counter reset as 0 rather than a negative spike", () => {
    const series = toLiveSeries([
      { ts: 0, resultsTotal: 900, inFlight: 2, ceiling: 8 },
      { ts: 5_000, resultsTotal: 3, inFlight: 0, ceiling: 0 },
    ]);
    expect(series[0].resultsPerSecond).toBe(0);
  });
});
