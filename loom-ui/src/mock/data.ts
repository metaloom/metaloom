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
export const DETECTED_FACES: DetectedFace[] = [
  { id: "f1", assetId: "a1", timestamp: 2, boundingBox: { x: 0.3, y: 0.2, width: 0.12, height: 0.2 }, confidence: 0.97, thumbnailUrl: "https://i.pravatar.cc/80?u=f1", clusterId: "fc1" },
  { id: "f2", assetId: "a1", timestamp: 8, boundingBox: { x: 0.55, y: 0.15, width: 0.1, height: 0.18 }, confidence: 0.94, thumbnailUrl: "https://i.pravatar.cc/80?u=f2", clusterId: "fc2" },
  { id: "f3", assetId: "a1", timestamp: 15, boundingBox: { x: 0.25, y: 0.25, width: 0.11, height: 0.19 }, confidence: 0.96, thumbnailUrl: "https://i.pravatar.cc/80?u=f3", clusterId: "fc1" },
  { id: "f4", assetId: "a5", timestamp: 120, boundingBox: { x: 0.4, y: 0.1, width: 0.15, height: 0.22 }, confidence: 0.92, thumbnailUrl: "https://i.pravatar.cc/80?u=f4", clusterId: "fc3" },
  { id: "f5", assetId: "a5", timestamp: 300, boundingBox: { x: 0.2, y: 0.3, width: 0.1, height: 0.18 }, confidence: 0.89, thumbnailUrl: "https://i.pravatar.cc/80?u=f5", clusterId: "fc2" },
  { id: "f6", assetId: "a7", boundingBox: { x: 0.35, y: 0.2, width: 0.08, height: 0.15 }, confidence: 0.95, thumbnailUrl: "https://i.pravatar.cc/80?u=f6", clusterId: "fc1" },
  { id: "f7", assetId: "a7", boundingBox: { x: 0.55, y: 0.22, width: 0.09, height: 0.16 }, confidence: 0.93, thumbnailUrl: "https://i.pravatar.cc/80?u=f7", clusterId: "fc3" },
  { id: "f8", assetId: "a7", boundingBox: { x: 0.7, y: 0.18, width: 0.07, height: 0.14 }, confidence: 0.88, thumbnailUrl: "https://i.pravatar.cc/80?u=f8", clusterId: "fc4" },
  { id: "f9", assetId: "a4", boundingBox: { x: 0.42, y: 0.15, width: 0.13, height: 0.22 }, confidence: 0.91, thumbnailUrl: "https://i.pravatar.cc/80?u=f9", clusterId: "fc2" },
  { id: "f10", assetId: "a8", timestamp: 600, boundingBox: { x: 0.45, y: 0.2, width: 0.1, height: 0.18 }, confidence: 0.96, thumbnailUrl: "https://i.pravatar.cc/80?u=f10", clusterId: "fc1" },
];

export const FACE_CLUSTERS: FaceCluster[] = [
  { id: "fc1", label: "Cluster A", representativeThumbnailUrl: "https://i.pravatar.cc/80?u=f1", faceIds: ["f1", "f3", "f6", "f10"], personId: "per1" },
  { id: "fc2", label: "Cluster B", representativeThumbnailUrl: "https://i.pravatar.cc/80?u=f2", faceIds: ["f2", "f5", "f9"], personId: "per2" },
  { id: "fc3", label: "Cluster C", representativeThumbnailUrl: "https://i.pravatar.cc/80?u=f4", faceIds: ["f4", "f7"], personId: undefined },
  { id: "fc4", label: "Cluster D", representativeThumbnailUrl: "https://i.pravatar.cc/80?u=f8", faceIds: ["f8"], personId: undefined },
];

export const PERSONS: Person[] = [
  { id: "per1", name: "Aria Chen", description: "CEO and co-founder", avatarUrl: "https://i.pravatar.cc/80?u=per1", clusterIds: ["fc1"], createdAt: daysAgo(60) },
  { id: "per2", name: "Marcus Webb", description: "Lead editor", avatarUrl: "https://i.pravatar.cc/80?u=per2", clusterIds: ["fc2"], createdAt: daysAgo(45) },
  { id: "per3", name: "Sofia Reyes", description: "Pipeline operator", avatarUrl: "https://i.pravatar.cc/80?u=per3", clusterIds: [], createdAt: daysAgo(30) },
];

// ── Object Detection ──────────────────────────────────────────────────────
export const DETECTED_OBJECTS: DetectedObject[] = [
  { id: "obj1", assetId: "a1", label: "car", confidence: 0.95, boundingBox: { x: 0.1, y: 0.4, width: 0.25, height: 0.3 }, timestamp: 5 },
  { id: "obj2", assetId: "a1", label: "person", confidence: 0.92, boundingBox: { x: 0.5, y: 0.2, width: 0.12, height: 0.35 }, timestamp: 5 },
  { id: "obj3", assetId: "a1", label: "tree", confidence: 0.88, boundingBox: { x: 0.75, y: 0.1, width: 0.2, height: 0.5 }, timestamp: 12 },
  { id: "obj4", assetId: "a4", label: "dog", confidence: 0.94, boundingBox: { x: 0.3, y: 0.5, width: 0.15, height: 0.2 } },
  { id: "obj5", assetId: "a4", label: "bench", confidence: 0.87, boundingBox: { x: 0.55, y: 0.6, width: 0.3, height: 0.2 } },
  { id: "obj6", assetId: "a5", label: "building", confidence: 0.96, boundingBox: { x: 0.05, y: 0.05, width: 0.4, height: 0.7 }, timestamp: 60 },
  { id: "obj7", assetId: "a5", label: "person", confidence: 0.91, boundingBox: { x: 0.6, y: 0.3, width: 0.1, height: 0.3 }, timestamp: 120 },
  { id: "obj8", assetId: "a7", label: "laptop", confidence: 0.89, boundingBox: { x: 0.35, y: 0.45, width: 0.2, height: 0.15 } },
  { id: "obj9", assetId: "a7", label: "cup", confidence: 0.85, boundingBox: { x: 0.6, y: 0.5, width: 0.08, height: 0.12 } },
  { id: "obj10", assetId: "a8", label: "microphone", confidence: 0.93, boundingBox: { x: 0.45, y: 0.15, width: 0.08, height: 0.25 }, timestamp: 300 },
];
