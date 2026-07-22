import {
  MetricSeries,
  DetectedFace, FaceCluster, Person,
  DetectedObject,
} from "../types";

// ── Helpers ───────────────────────────────────────────────────────────────
function daysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString();
}
function hoursAgo(n: number): string {
  const d = new Date();
  d.setHours(d.getHours() - n);
  return d.toISOString();
}
function genPoints(base: number, count: number, variance: number, dayOffset = 0): Array<{ts: string; value: number}> {
  return Array.from({ length: count }, (_, i) => ({
    ts: daysAgo(count - i + dayOffset),
    value: Math.max(0, Math.round(base + (Math.random() - 0.5) * 2 * variance)),
  }));
}

// ── Metrics ───────────────────────────────────────────────────────────────
export const METRICS: {
  ingestion: MetricSeries[];
  pipelineRuns: MetricSeries[];
  latency: MetricSeries[];
  storage: MetricSeries[];
  taskBacklog: MetricSeries[];
  annotations: MetricSeries[];
  chatUsage: MetricSeries[];
} = {
  ingestion: [
    { label: "Assets Ingested", color: "#7c6af7", data: genPoints(40, 14, 20) },
  ],
  pipelineRuns: [
    { label: "Successful", color: "#34d58a", data: genPoints(12, 14, 5) },
    { label: "Failed", color: "#f0546e", data: genPoints(1, 14, 1) },
  ],
  latency: [
    { label: "Avg Latency (ms)", color: "#2ea8ff", data: genPoints(340, 14, 80) },
    { label: "P99 Latency (ms)", color: "#f5a623", data: genPoints(820, 14, 200) },
  ],
  storage: [
    { label: "Storage (TB)", color: "#00c9b1", data: genPoints(8.4, 14, 0.4).map((p, i) => ({ ts: p.ts, value: 8.0 + i * 0.03 + Math.random() * 0.05 })) },
  ],
  taskBacklog: [
    { label: "Open Tasks", color: "#f5a623", data: genPoints(18, 14, 6) },
    { label: "Overdue", color: "#f0546e", data: genPoints(3, 14, 2) },
  ],
  annotations: [
    { label: "New Annotations", color: "#a597ff", data: genPoints(7, 14, 4) },
  ],
  chatUsage: [
    { label: "Agent Queries", color: "#7c6af7", data: genPoints(22, 14, 10) },
    { label: "Actions Taken", color: "#2ea8ff", data: genPoints(14, 14, 7) },
  ],
};

export const DETECTED_FACES: DetectedFace[] = [];

export const FACE_CLUSTERS: FaceCluster[] = [];

export const PERSONS: Person[] = [];

// ── Object Detection ──────────────────────────────────────────────────────
export const DETECTED_OBJECTS: DetectedObject[] = [];
