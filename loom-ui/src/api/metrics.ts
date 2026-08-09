import { API_BASE_URL } from "./config";

/**
 * Client for `GET /metrics` — the JSON read of Loom's `loom_*` metric catalog.
 *
 * This is *not* the Prometheus scrape endpoint. That one lives on the internal monitoring port
 * (8989), is unauthenticated by design and is not reachable from a browser. This route serves the
 * same registry, the same series names and the same label sets over the authenticated app API.
 *
 * **There is no history.** Loom keeps no time-series store, so every call is one instant. A trend is
 * built by sampling repeatedly and differencing the counters — see `deltaPerSecond` — and never by
 * inventing points.
 */

/** Meter kinds the snapshot can carry. */
export type MetricType = "COUNTER" | "GAUGE" | "TIMER" | "SUMMARY" | "OTHER";

/**
 * One name+tag series. `value` is set for counters and gauges; the timer fields for timers.
 *
 * `name` carries the suffixes a Prometheus scrape shows (`_total`, `_seconds`), so a series is
 * spelled here exactly as in `spec/features/ops/METRICS.md` §3.
 */
export interface MetricRecord {
  name: string;
  type: MetricType;
  tags: Record<string, string>;
  value?: number | null;
  count?: number | null;
  sumSeconds?: number | null;
  maxSeconds?: number | null;
  meanSeconds?: number | null;
}

export interface MetricsResponse {
  /** Server time the snapshot was taken (ISO 8601 instant). */
  timestamp: string;
  metrics: MetricRecord[];
}

function authHeaders(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

/**
 * Load a snapshot of the metric catalog.
 *
 * @param prefix optional series-name prefix inside the `loom_` namespace
 */
export async function loadMetrics(token: string, prefix?: string): Promise<MetricsResponse> {
  const query = prefix ? `?prefix=${encodeURIComponent(prefix)}` : "";
  const res = await fetch(`${API_BASE_URL}/metrics${query}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.json() as Promise<MetricsResponse>;
}

// ── Reading a snapshot ────────────────────────────────────────────────────
//
// A snapshot is a flat list of series, so every panel needs the same three questions answered:
// "all series of this name", "the one untagged value", "the total across tags". Each is a one-liner
// and each is wrong in a different way if written by hand at the call site, so they live here and
// are unit-tested.

/** Every series carrying this exact name, in snapshot order. */
export function seriesOf(metrics: MetricRecord[], name: string): MetricRecord[] {
  return metrics.filter(m => m.name === name);
}

/**
 * The sum of `value` across every series of this name.
 *
 * A counter labelled by kind has one series per kind; the fleet-wide number is their sum. Returns 0
 * when the meter has not been registered yet, which on a freshly started instance is the honest
 * reading — nothing has happened, not "no data".
 */
export function totalOf(metrics: MetricRecord[], name: string): number {
  return seriesOf(metrics, name).reduce((sum, m) => sum + (m.value ?? 0), 0);
}

/** The `value` of the single series of this name, or undefined when the meter is absent. */
export function gaugeOf(metrics: MetricRecord[], name: string): number | undefined {
  const found = seriesOf(metrics, name)[0];
  return found?.value ?? undefined;
}

/** Series of this name grouped by one label, e.g. `state` or `kind`. Untagged series are dropped. */
export function byTag(metrics: MetricRecord[], name: string, tag: string): Map<string, MetricRecord[]> {
  const grouped = new Map<string, MetricRecord[]>();
  for (const m of seriesOf(metrics, name)) {
    const key = m.tags?.[tag];
    if (key === undefined) continue;
    const bucket = grouped.get(key);
    if (bucket) bucket.push(m);
    else grouped.set(key, [m]);
  }
  return grouped;
}

/**
 * Mean duration of a timer across every series of that name, in milliseconds.
 *
 * Weighted by event count rather than averaging the per-series means: a kind that ran twice must not
 * pull the fleet average as hard as one that ran ten thousand times. Undefined when nothing has been
 * timed — an untimed fleet is not a fast one, and a chart must be able to tell those apart.
 */
export function timerMeanMs(metrics: MetricRecord[], name: string, filter?: (m: MetricRecord) => boolean): number | undefined {
  let count = 0;
  let sum = 0;
  for (const m of seriesOf(metrics, name)) {
    if (filter && !filter(m)) continue;
    count += m.count ?? 0;
    sum += m.sumSeconds ?? 0;
  }
  return count === 0 ? undefined : (sum / count) * 1000;
}

/**
 * Per-second rate between two snapshots of the same counter.
 *
 * Counters only ever rise, so a negative difference means the process restarted and its counters
 * went back to zero. That is reported as 0 rather than as a large negative spike: a restart is not
 * throughput running backwards.
 */
export function deltaPerSecond(previous: number, current: number, elapsedMs: number): number {
  if (elapsedMs <= 0) return 0;
  const delta = current - previous;
  return delta < 0 ? 0 : (delta / elapsedMs) * 1000;
}
