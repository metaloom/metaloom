import { describe, expect, it } from "vitest";
import {
  byTag, deltaPerSecond, gaugeOf, seriesOf, timerMeanMs, totalOf,
  type MetricRecord,
} from "./metrics";

function counter(name: string, value: number, tags: Record<string, string> = {}): MetricRecord {
  return { name, type: "COUNTER", tags, value };
}

function gauge(name: string, value: number, tags: Record<string, string> = {}): MetricRecord {
  return { name, type: "GAUGE", tags, value };
}

function timer(name: string, count: number, sumSeconds: number, tags: Record<string, string> = {}): MetricRecord {
  return { name, type: "TIMER", tags, count, sumSeconds, maxSeconds: sumSeconds, meanSeconds: count ? sumSeconds / count : 0 };
}

const SNAPSHOT: MetricRecord[] = [
  counter("loom_node_tasks_dispatched_total", 40, { kind: "sha512" }),
  counter("loom_node_tasks_dispatched_total", 60, { kind: "whisper" }),
  gauge("loom_node_tasks_inflight", 6),
  gauge("loom_processors_by_state", 3, { state: "online" }),
  gauge("loom_processors_by_state", 0, { state: "offline" }),
  timer("loom_node_task_latency_seconds", 10, 1, { kind: "sha512", state: "completed" }),
  timer("loom_node_task_latency_seconds", 90, 27, { kind: "whisper", state: "completed" }),
  timer("loom_node_task_latency_seconds", 2, 8, { kind: "whisper", state: "failed" }),
];

describe("seriesOf", () => {
  it("matches the name exactly rather than by prefix", () => {
    expect(seriesOf(SNAPSHOT, "loom_node_tasks_inflight")).toHaveLength(1);
    // loom_node_tasks_inflight_ceiling is a different meter; a prefix match would fold them together.
    expect(seriesOf(SNAPSHOT, "loom_node_tasks")).toHaveLength(0);
  });
});

describe("totalOf", () => {
  it("sums every labelled series into the fleet-wide number", () => {
    expect(totalOf(SNAPSHOT, "loom_node_tasks_dispatched_total")).toBe(100);
  });

  it("reads an unregistered meter as 0, not as missing", () => {
    // Nothing has happened yet is a real answer on a fresh instance.
    expect(totalOf(SNAPSHOT, "loom_leases_reclaimed_total")).toBe(0);
  });
});

describe("gaugeOf", () => {
  it("returns the single value", () => {
    expect(gaugeOf(SNAPSHOT, "loom_node_tasks_inflight")).toBe(6);
  });

  it("returns undefined for an absent meter, so a card can show a dash", () => {
    expect(gaugeOf(SNAPSHOT, "loom_pipeline_runs_active")).toBeUndefined();
  });
});

describe("byTag", () => {
  it("groups by the requested label", () => {
    const states = byTag(SNAPSHOT, "loom_processors_by_state", "state");
    expect([...states.keys()]).toEqual(["online", "offline"]);
    expect(states.get("offline")?.[0].value).toBe(0);
  });

  it("drops series that do not carry the label", () => {
    expect(byTag(SNAPSHOT, "loom_node_tasks_inflight", "state").size).toBe(0);
  });
});

describe("timerMeanMs", () => {
  it("weights by event count, not by series", () => {
    // 100 events totalling 28s → 280ms. A plain mean of the per-series means would say 200ms,
    // letting a kind that ran ten times outvote one that ran ninety.
    const completed = timerMeanMs(SNAPSHOT, "loom_node_task_latency_seconds", m => m.tags.state === "completed");
    expect(completed).toBeCloseTo(280, 6);
  });

  it("is undefined when nothing has been timed — an untimed fleet is not a fast one", () => {
    expect(timerMeanMs(SNAPSHOT, "loom_pipeline_run_duration_seconds")).toBeUndefined();
  });
});

describe("deltaPerSecond", () => {
  it("converts a counter difference into a rate", () => {
    expect(deltaPerSecond(100, 130, 5_000)).toBe(6);
  });

  it("reports a counter reset as 0 rather than a negative spike", () => {
    // Counters only rise; a drop means the process restarted.
    expect(deltaPerSecond(9_000, 12, 5_000)).toBe(0);
  });

  it("guards against a zero interval", () => {
    expect(deltaPerSecond(1, 2, 0)).toBe(0);
  });
});
