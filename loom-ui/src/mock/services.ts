import {
  Library, Asset, Comment,
  ApiKey,
  BlacklistEntry, ChatMessage,
  TranscriptSection, DetectedFace, FaceCluster, Person,
} from "../types";
import {
  LIBRARIES, ASSETS,
  COMMENTS,
  API_KEYS, BLACKLIST, INITIAL_CHAT,
  TRANSCRIPTS, DETECTED_FACES, FACE_CLUSTERS, PERSONS,
} from "./data";

// Simulate realistic async latency
const delay = (ms = 200) => new Promise<void>((r) => setTimeout(r, ms));

// ── Libraries ────────────────────────────────────────────────────────────
export const mockLibraryService = {
  getBySpace: async (spaceId: string): Promise<Library[]> => {
    await delay(100);
    return LIBRARIES.filter(l => l.spaceId === spaceId);
  },
  create: async (spaceId: string, name: string, description: string): Promise<Library> => {
    await delay(150);
    const lib: Library = {
      id: `lib_${Date.now()}`,
      spaceId: spaceId,
      name,
      description,
      assetCount: 0,
      createdAt: new Date().toISOString(),
    };
    LIBRARIES.push(lib);
    return lib;
  },
  delete: async (id: string): Promise<void> => {
    await delay(100);
    const idx = LIBRARIES.findIndex(l => l.id === id);
    if (idx >= 0) LIBRARIES.splice(idx, 1);
  },
};

// ── Assets ────────────────────────────────────────────────────────────────
export const mockAssetService = {
  getAll: async (): Promise<Asset[]> => { await delay(150); return [...ASSETS]; },
  getBySpace: async (spaceId: string): Promise<Asset[]> => {
    await delay(150);
    return ASSETS.filter(a => a.spaceId === spaceId);
  },
  getByLibrary: async (libraryId: string): Promise<Asset[]> => {
    await delay(120);
    return ASSETS.filter(a => a.libraryId === libraryId);
  },
  getById: async (id: string): Promise<Asset | undefined> => {
    await delay(80);
    return ASSETS.find(a => a.id === id);
  },
  search: async (spaceId: string, query: string): Promise<Asset[]> => {
    await delay(200);
    const q = query.toLowerCase();
    return ASSETS.filter(a =>
      a.spaceId === spaceId &&
      (a.name.toLowerCase().includes(q) || a.tags.some(t => t.includes(q)) || a.description.toLowerCase().includes(q))
    );
  },
};

// ── Comments ──────────────────────────────────────────────────────────────
export const mockCommentService = {
  getByAsset: async (assetId: string): Promise<Comment[]> => {
    await delay(100);
    return COMMENTS.filter(c => c.assetId === assetId);
  },
};


// ── Admin ─────────────────────────────────────────────────────────────────
export const mockAdminService = {
  getApiKeys: async (): Promise<ApiKey[]> => { await delay(120); return [...API_KEYS]; },
  createApiKey: async (data: { name: string; scopes: string[]; expiresAt?: string }): Promise<ApiKey> => {
    await delay(200);
    const key: ApiKey = {
      id: `k${Date.now()}`,
      name: data.name,
      prefix: `lm_${data.name.toLowerCase().replace(/\s+/g, "_").slice(0, 8)}_${Math.random().toString(36).slice(2, 6)}`,
      ownerId: "u1",
      scopes: data.scopes,
      expiresAt: data.expiresAt,
      createdAt: new Date().toISOString(),
      active: true,
    };
    API_KEYS.push(key);
    return key;
  },
  getBlacklist: async (): Promise<BlacklistEntry[]> => { await delay(100); return [...BLACKLIST]; },
};

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

// ── Transcript ───────────────────────────────────────────────────────────
export const mockTranscriptService = {
  getByAsset: async (assetId: string): Promise<TranscriptSection[]> => {
    await delay(100);
    return TRANSCRIPTS[assetId] ? [...TRANSCRIPTS[assetId]] : [];
  },
};

// ── Face Detection ───────────────────────────────────────────────────────
export const mockFaceDetectionService = {
  getFacesByAsset: async (assetId: string): Promise<DetectedFace[]> => {
    await delay(100);
    return DETECTED_FACES.filter(f => f.assetId === assetId);
  },
  getAllClusters: async (): Promise<FaceCluster[]> => {
    await delay(80);
    return [...FACE_CLUSTERS];
  },
  getAllPersons: async (): Promise<Person[]> => {
    await delay(80);
    return [...PERSONS];
  },
  createPerson: async (name: string, description: string): Promise<Person> => {
    await delay(150);
    const p: Person = { id: `per_${Date.now()}`, name, description, avatarUrl: `https://i.pravatar.cc/80?u=${Date.now()}`, clusterIds: [], createdAt: new Date().toISOString() };
    PERSONS.push(p);
    return p;
  },
  assignClusterToPerson: async (clusterId: string, personId: string): Promise<void> => {
    await delay(100);
    const cluster = FACE_CLUSTERS.find(c => c.id === clusterId);
    if (cluster) cluster.personId = personId;
    const person = PERSONS.find(p => p.id === personId);
    if (person && !person.clusterIds.includes(clusterId)) person.clusterIds.push(clusterId);
  },
};
