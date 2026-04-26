import {
  MetricSeries, ChatMessage,
  TranscriptSection, DetectedFace, FaceCluster, Person,
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

// ── Chat History ──────────────────────────────────────────────────────────
export const INITIAL_CHAT: ChatMessage[] = [
  {
    id: "msg0", role: "system", content: "Loom Agent is ready. Ask me about assets, collections, tasks, pipelines, or let me help you find and organize content.",
    createdAt: daysAgo(1), suggestedFollowUps: [
      "Show me assets that need review",
      "What pipelines ran today?",
      "Create a collection from flagged assets",
      "Find comments around 00:43 in the finals video",
    ],
  },
  {
    id: "msg1", role: "user", content: "Show me the latest assets in Campaign Alpha",
    createdAt: hoursAgo(3),
  },
  {
    id: "msg2", role: "assistant",
    content: "Here are the most recently updated assets in **Campaign Alpha**. The hero video and 15-second social cut are both ready. The BTS footage has a failed ingest that needs attention.",
    createdAt: hoursAgo(3),
    references: [
      { type: "asset", id: "a1", label: "Hero_Campaign_30s_Final.mp4" },
      { type: "asset", id: "a2", label: "Hero_Campaign_15s_Cut.mp4" },
      { type: "asset", id: "a9", label: "Behind_The_Scenes_BTS.mp4" },
    ],
    suggestedFollowUps: [
      "Open the hero video",
      "Show tasks for Campaign Alpha",
      "Why did the BTS ingest fail?",
    ],
  },
  {
    id: "msg3", role: "user", content: "Why did the BTS ingest fail?",
    createdAt: hoursAgo(2),
  },
  {
    id: "msg4", role: "assistant",
    content: "The BTS footage failed during encoding. The metadata shows a **corrupt audio track at 03:14**. The source file is available on Deck 3.\n\nI've already created a task to re-ingest it and assigned it to Marcus.",
    createdAt: hoursAgo(2),
    references: [
      { type: "asset", id: "a9", label: "Behind_The_Scenes_BTS.mp4" },
      { type: "task", id: "t8", label: "Re-ingest BTS footage" },
    ],
    actions: [
      { id: "act1", label: "Task created", description: "Re-ingest BTS footage assigned to Marcus Webb", status: "done", result: "Task t8 created" },
    ],
    suggestedFollowUps: [
      "Show all blocked tasks",
      "Open the re-ingest task",
    ],
  },
];

// ── Transcripts ───────────────────────────────────────────────────────────
function makeWords(text: string, startTime: number): { words: Array<{word: string; startTime: number; endTime: number; confidence: number}>; endTime: number } {
  const ws = text.split(/\s+/);
  let t = startTime;
  const words = ws.map(w => {
    const dur = 0.2 + Math.random() * 0.3;
    const gap = Math.random() * 0.15;
    const wObj = { word: w, startTime: Math.round(t * 100) / 100, endTime: Math.round((t + dur) * 100) / 100, confidence: 0.85 + Math.random() * 0.15 };
    t += dur + gap;
    return wObj;
  });
  return { words, endTime: Math.round(t * 100) / 100 };
}

const s1w = makeWords("Welcome everyone to the quarterly update. We have a packed agenda today covering product launches, financial results, and team updates.", 0);
const s2w = makeWords("First up, let's discuss the new product launch. The campaign alpha assets are performing exceptionally well across all channels. Social engagement is up forty percent compared to last quarter.", s1w.endTime + 0.5);
const s3w = makeWords("Moving on to financials. Q1 revenue came in twelve percent above target. Our media pipeline automation reduced processing costs by nearly a third. The investment in the new encoding infrastructure is already paying dividends.", s2w.endTime + 0.5);
const s4w = makeWords("Let's talk about the highlight reel we produced for the championship finals. The broadcast team pulled together the package in record time using our automated workflows.", s3w.endTime + 0.5);
const s5w = makeWords("Finally, some team updates. We're welcoming two new members to Media Ops next week. Please make sure to update your project permissions and onboard them into the relevant pipelines.", s4w.endTime + 0.5);

export const TRANSCRIPTS: Record<string, TranscriptSection[]> = {
  a1: [
    { id: "ts1", title: "Introduction", startTime: 0, endTime: s1w.endTime, words: s1w.words },
    { id: "ts2", title: "Product Launch Update", startTime: s1w.endTime + 0.5, endTime: s2w.endTime, words: s2w.words },
    { id: "ts3", title: "Financial Results", startTime: s2w.endTime + 0.5, endTime: s3w.endTime, words: s3w.words },
  ],
  a5: [
    { id: "ts4", title: "Broadcast Highlights", startTime: 0, endTime: s4w.endTime, words: s4w.words },
  ],
  a8: [
    { id: "ts5", title: "Opening Remarks", startTime: 0, endTime: s1w.endTime, words: s1w.words },
    { id: "ts6", title: "Campaign Review", startTime: s1w.endTime + 0.5, endTime: s2w.endTime, words: s2w.words },
    { id: "ts7", title: "Financials", startTime: s2w.endTime + 0.5, endTime: s3w.endTime, words: s3w.words },
    { id: "ts8", title: "Broadcast Segment", startTime: s3w.endTime + 0.5, endTime: s4w.endTime, words: s4w.words },
    { id: "ts9", title: "Team Updates", startTime: s4w.endTime + 0.5, endTime: s5w.endTime, words: s5w.words },
  ],
};

// ── Face Detection ────────────────────────────────────────────────────────
export const DETECTED_FACES: DetectedFace[] = [];

export const FACE_CLUSTERS: FaceCluster[] = [];

export const PERSONS: Person[] = [];

// ── Object Detection ──────────────────────────────────────────────────────
export const DETECTED_OBJECTS: DetectedObject[] = [];
