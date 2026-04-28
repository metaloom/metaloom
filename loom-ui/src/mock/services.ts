import {
  ChatMessage,
  DetectedFace,
} from "../types";
import {
  INITIAL_CHAT,
  DETECTED_FACES,
} from "./data";

// Simulate realistic async latency
const delay = (ms = 200) => new Promise<void>((r) => setTimeout(r, ms));

// ── Chat ──────────────────────────────────────────────────────────────────
export const mockChatService = {
  getHistory: async (): Promise<ChatMessage[]> => {
    await delay(100);
    return [...INITIAL_CHAT];
  },

  sendMessage: async (content: string, spaceId: string): Promise<ChatMessage> => {
    await delay(900 + Math.random() * 600);

    const q = content.toLowerCase();
    let response: Partial<ChatMessage> = {};

    if (q.includes("flagged") && q.includes("collection")) {
      response = {
        content: "I found **2 flagged assets** in Campaign Alpha with a flag reaction. I've created the collection **Flagged Review** and added them.",
        references: [
          { type: "asset", id: "a1", label: "Hero_Campaign_30s_Final.mp4" },
          { type: "collection", id: "col1", label: "Broadcast Deliverables" },
        ],
        actions: [
          { id: "ax1", label: "Collection created", description: "Flagged Review — 2 assets added", status: "done" },
        ],
        suggestedFollowUps: ["Open the collection", "Assign review tasks to flagged assets"],
      };
    } else if (q.includes("pipeline") && (q.includes("fail") || q.includes("failed"))) {
      response = {
        content: "The **Campaign Ingest Pipeline** had a failure 3 days ago: an S3 timeout during delivery. The other recent runs completed successfully.\n\nWould you like me to retry the failed run or inspect the logs?",
        references: [
          { type: "pipeline", id: "pipe1", label: "Campaign Ingest Pipeline" },
        ],
        suggestedFollowUps: ["Show pipeline logs", "Open pipeline editor", "Retry failed run"],
      };
    } else if (q.includes("task") && (q.includes("sport") || q.includes("sports"))) {
      response = {
        content: "There are **3 tasks** in the Sports Archive space. Two are critical priority right now: an overdue broadcast proxy and the halftime ingest monitoring.",
        references: [
          { type: "task", id: "t5", label: "Export broadcast proxy" },
          { type: "task", id: "t6", label: "Monitor halftime ingest" },
          { type: "asset", id: "a5", label: "Match_Finals_Highlight_Reel.mp4" },
        ],
        suggestedFollowUps: ["Show overdue tasks", "Open the broadcast proxy task"],
      };
    } else if (q.includes("00:43") || q.includes("43") || q.includes("timestamp") || q.includes("43:12")) {
      response = {
        content: "Found a comment and annotation at **43:12** on the Finals Highlight Reel. Marcus Webb marked it as a strong sequence for a promo reel. There's also an annotation marking it as 'Promo clip'.",
        references: [
          { type: "asset", id: "a5", label: "Match_Finals_Highlight_Reel.mp4" },
          { type: "annotation", id: "an4", label: "Promo clip @ 43:12" },
        ],
        suggestedFollowUps: ["Open asset at 43:12", "Add to promo collection"],
      };
    } else if (q.includes("campaign") && (q.includes("show") || q.includes("assets"))) {
      response = {
        content: "**Campaign Alpha** has 9 assets across 2 libraries. Most recent activity is on the hero video and BTS footage.",
        references: [
          { type: "asset", id: "a1", label: "Hero_Campaign_30s_Final.mp4" },
          { type: "asset", id: "a2", label: "Hero_Campaign_15s_Cut.mp4" },
          { type: "asset", id: "a9", label: "Behind_The_Scenes_BTS.mp4" },
        ],
        suggestedFollowUps: ["Show failed assets", "Open asset browser", "Show tasks"],
      };
    } else {
      const fallbacks = [
        "I understand your request. Let me look across the current space for relevant assets and actions.",
        "Here's what I found. The data is scoped to the active space — switch spaces from the sidebar if needed.",
        "I've reviewed the metadata. Let me know if you want me to take any action or open a specific asset.",
      ];
      response = {
        content: fallbacks[Math.floor(Math.random() * fallbacks.length)],
        suggestedFollowUps: ["Show recent assets", "Open pipeline editor", "View monitoring stats"],
      };
    }

    return {
      id: `msg_${Date.now()}`,
      role: "assistant",
      content: response.content ?? "Done.",
      createdAt: new Date().toISOString(),
      references: response.references,
      actions: response.actions,
      suggestedFollowUps: response.suggestedFollowUps,
    };
  },
};

// ── Face Detection ───────────────────────────────────────────────────────
export const mockFaceDetectionService = {
  getFacesByAsset: async (assetId: string): Promise<DetectedFace[]> => {
    await delay(100);
    return DETECTED_FACES.filter(f => f.assetId === assetId);
  },
};
