import {
  byTag, deltaPerSecond, gaugeOf, seriesOf, timerMeanMs, totalOf,
  type MetricRecord, type MetricsResponse,
} from "../../api/metrics";

/**
 * Turning a metric snapshot into the rows each monitoring panel draws.
 *
 * Kept out of the component for the usual reason — a chart's shape is testable, a chart is not —
 * and because the interesting decisions are here rather than in the JSX: which label a series is
 * split by, what an absent meter means, and how two snapshots become a rate.
 */

/** How often the dashboard re-reads `GET /metrics`. */
export const POLL_INTERVAL_MS = 5_000;

/** How many samples the live charts keep. 60 × 5s = the last five minutes. */
export const MAX_SAMPLES = 60;

// ── Snapshot panels ───────────────────────────────────────────────────────

export interface KindOutcomeRow {
  kind: string;
  completed: number;
  failed: number;
  skipped: number;
}

/**
 * Node results per node kind, split by settled state.
 *
 * The state label is whatever the engine settled on, lower-cased (`PipelineRunEngine` and
 * `ProcessorEndpoint` both write `state.name().toLowerCase()`). Only the three settled states get a
 * column; anything else — `unknown` from a result that arrived without a state — is counted as
 * failed rather than dropped, because a result nobody could classify is not a success.
 */
export function outcomesByKind(metrics: MetricRecord[]): KindOutcomeRow[] {
  const rows = new Map<string, KindOutcomeRow>();
  for (const record of seriesOf(metrics, "loom_node_results_received_total")) {
    const kind = record.tags?.kind ?? "unknown";
    const row = rows.get(kind) ?? { kind, completed: 0, failed: 0, skipped: 0 };
    const value = record.value ?? 0;
    if (record.tags?.state === "completed") row.completed += value;
    else if (record.tags?.state === "skipped") row.skipped += value;
    else row.failed += value;
    rows.set(kind, row);
  }
  return [...rows.values()].sort((a, b) =>
    (b.completed + b.failed + b.skipped) - (a.completed + a.failed + a.skipped));
}

export interface KindLatencyRow {
  kind: string;
  meanMs: number;
  maxMs: number;
}

/**
 * Mean and worst dispatch-to-result latency per node kind, in milliseconds.
 *
 * Only `state=completed` counts. A failed task's duration is the time it took to fail, which is
 * usually a timeout and would dominate the mean while saying nothing about how long the work takes;
 * a retried attempt is never timed at all (it does not settle). Both are visible elsewhere — the
 * outcome chart and the dead-letter KPI.
 */
export function latencyByKind(metrics: MetricRecord[]): KindLatencyRow[] {
  const kinds = byTag(metrics, "loom_node_task_latency_seconds", "kind");
  const rows: KindLatencyRow[] = [];
  for (const [kind, records] of kinds) {
    const completed = records.filter(r => r.tags?.state === "completed");
    if (completed.length === 0) continue;
    const count = completed.reduce((sum, r) => sum + (r.count ?? 0), 0);
    if (count === 0) continue;
    const sum = completed.reduce((acc, r) => acc + (r.sumSeconds ?? 0), 0);
    const max = completed.reduce((acc, r) => Math.max(acc, r.maxSeconds ?? 0), 0);
    rows.push({ kind, meanMs: (sum / count) * 1000, maxMs: max * 1000 });
  }
  return rows.sort((a, b) => b.meanMs - a.meanMs);
}

export interface WorkerStateRow {
  state: string;
  count: number;
}

/**
 * Workers per lifecycle state.
 *
 * Every state is kept, including the zero ones: `loom_processors_by_state` binds one series per enum
 * constant at construction precisely so a state with no workers reads 0 rather than vanishing, and
 * dropping the zeroes here would throw that away. A fleet stuck in `terminating` is the case this
 * chart exists for.
 */
export function workersByState(metrics: MetricRecord[]): WorkerStateRow[] {
  const order = ["online", "starting", "paused", "terminating", "offline"];
  const rows = [...byTag(metrics, "loom_processors_by_state", "state")]
    .map(([state, records]) => ({ state, count: records[0]?.value ?? 0 }));
  return rows.sort((a, b) => {
    const ai = order.indexOf(a.state);
    const bi = order.indexOf(b.state);
    return (ai < 0 ? order.length : ai) - (bi < 0 ? order.length : bi);
  });
}

/**
 * Node kinds whose circuit breaker is not closed.
 *
 * The gauge is encoded by severity — 0 closed, 1 half-open, 2 open — so anything above 0 is a kind
 * that is not dispatching normally. A parked kind produces no dispatches, no failures and no errors;
 * throughput simply goes flat, which is why this is a KPI of its own rather than something to infer.
 */
export function parkedKinds(metrics: MetricRecord[]): string[] {
  return seriesOf(metrics, "loom_node_circuit_breaker_state")
    .filter(record => (record.value ?? 0) > 0)
    .map(record => record.tags?.kind ?? "unknown")
    .sort();
}

export interface FleetSummary {
  activeRuns?: number;
  inFlight?: number;
  /** 0 means "no run declares a limit", which is unlimited rather than saturated. */
  inFlightCeiling?: number;
  workersOnline?: number;
  workersConnected?: number;
  /** Mean dispatch-to-result latency of completed tasks, in ms; undefined when nothing has settled. */
  meanLatencyMs?: number;
  dispatchFailures: number;
  deadLettered: number;
  parked: string[];
}

/** Every KPI tile's value, read from one snapshot. */
export function summarizeFleet(metrics: MetricRecord[]): FleetSummary {
  return {
    activeRuns: gaugeOf(metrics, "loom_pipeline_runs_active"),
    inFlight: gaugeOf(metrics, "loom_node_tasks_inflight"),
    inFlightCeiling: gaugeOf(metrics, "loom_node_tasks_inflight_ceiling"),
    workersOnline: byTag(metrics, "loom_processors_by_state", "state").get("online")?.[0]?.value ?? undefined,
    workersConnected: gaugeOf(metrics, "loom_processors_connected"),
    meanLatencyMs: timerMeanMs(metrics, "loom_node_task_latency_seconds", m => m.tags?.state === "completed"),
    dispatchFailures: totalOf(metrics, "loom_node_tasks_dispatch_failed_total"),
    deadLettered: totalOf(metrics, "loom_node_tasks_deadlettered_total"),
    parked: parkedKinds(metrics),
  };
}

// ── Live series ───────────────────────────────────────────────────────────

/** One poll, reduced to what the two live charts plot. */
export interface LiveSample {
  /** Epoch millis of the snapshot, taken from the server's own timestamp. */
  ts: number;
  /** Cumulative node results received, before differencing. */
  resultsTotal: number;
  inFlight: number;
  ceiling: number;
}

export interface LivePoint {
  ts: number;
  /** Node results settled per second over the interval that ended at `ts`. */
  resultsPerSecond: number;
  inFlight: number;
  ceiling: number;
}

/**
 * Reduce a snapshot to a live sample.
 *
 * The server's `timestamp` is used rather than the browser's clock: the interval a rate is divided
 * by must be the interval the counters actually advanced over, and a tab that was backgrounded
 * resumes with a long gap that the browser side would misattribute.
 */
export function toLiveSample(response: MetricsResponse): LiveSample {
  const metrics = response.metrics ?? [];
  const parsed = Date.parse(response.timestamp ?? "");
  return {
    ts: Number.isNaN(parsed) ? 0 : parsed,
    resultsTotal: totalOf(metrics, "loom_node_results_received_total"),
    inFlight: gaugeOf(metrics, "loom_node_tasks_inflight") ?? 0,
    ceiling: gaugeOf(metrics, "loom_node_tasks_inflight_ceiling") ?? 0,
  };
}

/**
 * Append a sample, keeping at most {@link MAX_SAMPLES}.
 *
 * A sample without a usable timestamp is refused: it would make the next rate divide by a nonsense
 * interval, and one bad point in a rate series is worse than a missing one.
 */
export function appendSample(history: LiveSample[], sample: LiveSample): LiveSample[] {
  if (sample.ts <= 0) return history;
  return [...history, sample].slice(-MAX_SAMPLES);
}

/**
 * Difference the counter history into a rate series.
 *
 * The first sample yields no point: a rate needs an interval, and plotting the cumulative total as
 * if it were a rate is exactly the kind of invented number this screen was rebuilt to remove. So a
 * freshly opened dashboard shows "collecting" until the second poll lands.
 */
export function toLiveSeries(history: LiveSample[]): LivePoint[] {
  const points: LivePoint[] = [];
  for (let i = 1; i < history.length; i++) {
    const previous = history[i - 1];
    const current = history[i];
    points.push({
      ts: current.ts,
      resultsPerSecond: deltaPerSecond(previous.resultsTotal, current.resultsTotal, current.ts - previous.ts),
      inFlight: current.inFlight,
      ceiling: current.ceiling,
    });
  }
  return points;
}
