import type { PipelineNodeStatsResponse, PipelineNodeTaskRecord, PipelineNodeTimeStats } from "../../api/pipelines";

/**
 * Pure helpers behind the pipeline performance view and the per-node time on the canvas.
 *
 * Execution time here is what nodes report for their own work, summed over every item a run
 * processed - not wall-clock time, which parallel execution makes shorter.
 */

/** Whether a task handed on a result it already had instead of computing one. */
export function isReusedTask(task: Pick<PipelineNodeTaskRecord, "origin" | "state">): boolean {
  return task.state === "COMPLETED" && (task.origin === "LOCAL" || task.origin === "REMOTE");
}

/**
 * A duration as a person reads it: `850 ms`, `4.2 s`, `3 min 05 s`, `2 h 07 min`.
 * Two significant units at most - a performance view is about where time goes, not about the last millisecond.
 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "–";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)} s`;
  const totalSeconds = Math.round(seconds);
  const minutes = Math.floor(totalSeconds / 60);
  if (minutes < 60) return `${minutes} min ${String(totalSeconds % 60).padStart(2, "0")} s`;
  const hours = Math.floor(minutes / 60);
  return `${hours} h ${String(minutes % 60).padStart(2, "0")} min`;
}

/** A histogram bucket bound, compact enough for an axis tick: `512 ms`, `2 s`, `1 min`. */
export function formatBound(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${Math.round(ms / 1000)} s`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)} min`;
  return `${Math.round(ms / 3_600_000)} h`;
}

/** A share 0..1 as a percentage label; anything above zero but under one percent says so rather than `0 %`. */
export function formatShare(share: number): string {
  if (!Number.isFinite(share) || share <= 0) return "0 %";
  if (share < 0.01) return "<1 %";
  return `${Math.round(share * 100)} %`;
}

/** The fraction of a node's (or a pipeline's) executions that reused a result. */
export function reusedFraction(stats: { tasks: number; reused: number }): number {
  return stats.tasks > 0 ? stats.reused / stats.tasks : 0;
}

/** What the canvas card of one node shows. */
export interface NodeTiming {
  totalDurationMs: number;
  tasks: number;
  reused: number;
  share: number;
}

/** Index the per-node figures by graph node id, for the canvas. */
export function timingsByNode(stats: PipelineNodeStatsResponse | null | undefined): Record<string, NodeTiming> {
  const out: Record<string, NodeTiming> = {};
  for (const n of stats?.nodes ?? []) {
    out[n.nodeId] = { totalDurationMs: n.totalDurationMs, tasks: n.tasks, reused: n.reused, share: n.share };
  }
  return out;
}

/** One bar of the "where does the time go" chart. */
export interface NodeTimeRow {
  nodeId: string;
  label: string;
  totalDurationMs: number;
  share: number;
  reused: number;
  tasks: number;
}

/**
 * Bars for the per-node chart, most expensive first. Labels prefer the node's display name and fall
 * back to its id; a node that ran but is no longer in the graph keeps its id, so its cost stays visible.
 */
export function nodeTimeRows(nodes: PipelineNodeTimeStats[], displayNames: Record<string, string | undefined> = {}): NodeTimeRow[] {
  return [...nodes]
    .sort((a, b) => b.totalDurationMs - a.totalDurationMs || a.nodeId.localeCompare(b.nodeId))
    .map(n => ({
      nodeId: n.nodeId,
      label: displayNames[n.nodeId] || n.nodeId,
      totalDurationMs: n.totalDurationMs,
      share: n.share,
      reused: n.reused,
      tasks: n.tasks,
    }));
}

/** Histogram bars labelled by their range, in ascending order. */
export function histogramRows(node: PipelineNodeTimeStats | undefined): { label: string; count: number; fromMs: number; toMs: number }[] {
  return [...(node?.histogram ?? [])]
    .sort((a, b) => a.fromMs - b.fromMs)
    .map(b => ({ label: `${formatBound(b.fromMs)}–${formatBound(b.toMs)}`, count: b.count, fromMs: b.fromMs, toMs: b.toMs }));
}

/**
 * The node's time per run, oldest first so the chart reads left to right in time. Runs in which the node
 * did not execute are shown as zero - a gap is information, not something to close up.
 */
export function runTrendRows(
  node: PipelineNodeTimeStats | undefined,
  runs: PipelineNodeStatsResponse["runs"],
): { runUuid: string; label: string; totalDurationMs: number; reused: number; tasks: number }[] {
  const byRun = new Map((node?.runs ?? []).map(r => [r.runUuid, r]));
  return [...runs].reverse().map((run, i) => {
    const total = byRun.get(run.uuid);
    return {
      runUuid: run.uuid,
      label: run.started ? new Date(run.started).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }) : `#${i + 1}`,
      totalDurationMs: total?.totalDurationMs ?? 0,
      reused: total?.reused ?? 0,
      tasks: total?.tasks ?? 0,
    };
  });
}
